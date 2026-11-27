package com.example.core

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import com.example.MainActivity
import com.example.ble.BleUiState
import com.example.ble.ColorOrder
import com.example.ble.LedProtocol
import com.example.ble.ProtocolProfile
import com.example.ble.SymphonyBleManager
import com.example.data.CoverSyncStyle
import com.example.data.PrefsStore
import com.example.data.SavedAppPrefs
import com.example.service.SymphonySyncService
import com.example.spotify.AlbumPaletteExtractor
import com.example.spotify.CoverPalette
import com.example.spotify.SpotifyRepository
import com.example.spotify.SpotifyTrack
import com.example.spotify.TrackPollResult
import com.example.update.GitHubUpdateChecker
import com.example.update.UpdateCheckState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sign

data class LivePlaybackState(
    val track: SpotifyTrack? = null,
    val palette: CoverPalette = CoverPalette.Default,
    val liveLedArgb: Int = 0xFF00E5FF.toInt(),
    val spotifyStatusMessage: String? = null,
    val isAuthBusy: Boolean = false,
    val authFeedback: String? = null,
    val authError: String? = null,
)

class SyncEngine(
    private val appContext: Context,
    val prefsStore: PrefsStore,
    val bleManager: SymphonyBleManager,
    val spotifyRepository: SpotifyRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _prefs = MutableStateFlow(SavedAppPrefs())
    val prefs: StateFlow<SavedAppPrefs> = _prefs.asStateFlow()

    val bleState: StateFlow<BleUiState> = bleManager.state

    private val _playback = MutableStateFlow(LivePlaybackState())
    val playback: StateFlow<LivePlaybackState> = _playback.asStateFlow()

    private val _updateState = MutableStateFlow<UpdateCheckState>(UpdateCheckState.Idle)
    val updateState: StateFlow<UpdateCheckState> = _updateState.asStateFlow()

    private var pollJob: Job? = null
    private var animationJob: Job? = null
    private var reconnectJob: Job? = null
    private var lastTrackKey: String? = null
    private var lastCoverUrl: String? = null
    private var autoConnectAttempted = false
    private var initialColorRestored = false
    private var prevConnectedAddresses: Set<String> = emptySet()

    @Volatile
    private var isActivityVisible: Boolean = false

    @Volatile
    private var wasBluetoothOff: Boolean = false

    @Volatile
    private var lastScreenOffElapsedMs: Long = 0L

    @Volatile
    private var lastAutoLaunchElapsedMs: Long = 0L

    init {
        scope.launch {
            prefsStore.prefsFlow.collectLatest { saved ->
                _prefs.value = saved
                bleManager.setSelectedProfile(ProtocolProfile.fromId(saved.protocolId))
                bleManager.setDeviceOverrides(saved.deviceProfiles, saved.deviceColorOrders)

                if (!initialColorRestored) {
                    initialColorRestored = true
                    if (_playback.value.track == null) {
                        val restoredArgb = if (saved.syncEnabled) {
                            saved.lastLiveLedArgb
                        } else {
                            saved.manualColorArgb
                        }
                        _playback.update { it.copy(liveLedArgb = restoredArgb) }
                    }
                }

                if (saved.autoEnableBluetooth && !bleState.value.isBluetoothEnabled) {
                    bleManager.tryAutoEnableBluetooth()
                }

                if (!autoConnectAttempted &&
                    saved.autoConnectBle &&
                    saved.savedBleDevices.isNotEmpty() &&
                    bleManager.hasBleConnectPermission()
                ) {
                    autoConnectAttempted = true
                    connectAllSavedDevicesStaggered(saved)
                }

                ensureLoopsState(saved)
            }
        }

        scope.launch {
            bleState.collectLatest { ble ->
                val currentConnected = ble.connectedDevices.map { it.address }.toSet()
                val newlyConnected = currentConnected - prevConnectedAddresses
                prevConnectedAddresses = currentConnected

                if (newlyConnected.isNotEmpty()) {
                    val saved = _prefs.value
                    restoreSavedConfigurationToControllers(saved)
                    syncForegroundServiceState()
                }

                val saved = _prefs.value
                val hasDisconnectedSaved = saved.savedBleDevices.any { dev ->
                    !bleManager.isManuallyDisconnected(dev.address) &&
                        ble.devicesByAddress[dev.address]?.status == null
                }
                if (saved.autoConnectBle && ble.isBluetoothEnabled && hasDisconnectedSaved) {
                    scheduleAutoReconnectIfNeeded(aggressiveCarStart = false)
                }
            }
        }
    }

    private suspend fun restoreSavedConfigurationToControllers(saved: SavedAppPrefs) {
        bleManager.sendPowerAll(saved.powerOn)
        if (!saved.powerOn) return

        delay(120L)
        bleManager.sendControlPacketsAll { profile ->
            LedProtocol.buildBrightnessPackets(profile, saved.brightness)
        }
        delay(110L)

        val targetColor = when {
            saved.syncEnabled -> if (_playback.value.track != null) _playback.value.liveLedArgb else saved.lastLiveLedArgb
            else -> saved.manualColorArgb
        }
        sendColorWithPrefs(targetColor, saved)
    }

    private fun connectAllSavedDevicesStaggered(saved: SavedAppPrefs = _prefs.value) {
        scope.launch {
            bleManager.refreshAdapterState()
            if (!bleState.value.isBluetoothEnabled && saved.autoEnableBluetooth) {
                bleManager.tryAutoEnableBluetooth()
                delay(1200L)
                bleManager.refreshAdapterState()
            }
            if (!bleState.value.isBluetoothEnabled || !bleManager.hasBleConnectPermission()) {
                scheduleAutoReconnectIfNeeded(aggressiveCarStart = true)
                return@launch
            }
            val devices = saved.savedBleDevices.filter { !bleManager.isManuallyDisconnected(it.address) }
            if (devices.isEmpty()) return@launch

            bleManager.warmupScanBeforeReconnect(1800L)
            devices.forEach { dev ->
                if (!bleManager.isManuallyDisconnected(dev.address)) {
                    bleManager.connectSequential(dev.address, dev.name)
                }
            }
            scheduleAutoReconnectIfNeeded(aggressiveCarStart = true)
        }
    }

    private fun scheduleAutoReconnectIfNeeded(aggressiveCarStart: Boolean) {
        val saved = _prefs.value
        if (!saved.autoConnectBle ||
            saved.savedBleDevices.isEmpty() ||
            !bleManager.hasBleConnectPermission()
        ) {
            return
        }
        if (aggressiveCarStart) {
            reconnectJob?.cancel()
            reconnectJob = null
        } else if (reconnectJob?.isActive == true) {
            return
        }
        reconnectJob = scope.launch {
            val steps = if (aggressiveCarStart) {
                listOf(1500L, 2500L, 3500L, 5000L, 7000L, 10_000L, 12_000L)
            } else {
                listOf(3500L, 5000L, 8000L, 12_000L)
            }
            var stepIdx = 0
            while (isActive) {
                val waitMs = steps[stepIdx.coerceAtMost(steps.lastIndex)]
                delay(waitMs)
                stepIdx++

                val currentSaved = _prefs.value
                if (!currentSaved.autoConnectBle || !bleManager.hasBleConnectPermission()) break
                bleManager.refreshAdapterState()
                if (!bleState.value.isBluetoothEnabled) {
                    if (currentSaved.autoEnableBluetooth) {
                        bleManager.tryAutoEnableBluetooth()
                    }
                    continue
                }

                val currentBle = bleState.value
                val pending = currentSaved.savedBleDevices.filter { dev ->
                    !bleManager.isManuallyDisconnected(dev.address) &&
                        currentBle.devicesByAddress[dev.address] == null
                }
                if (pending.isEmpty()) break

                bleManager.warmupScanBeforeReconnect(1800L)
                pending.forEach { dev ->
                    bleManager.connectSequential(dev.address, dev.name)
                }
            }
        }
    }

    private fun ensureLoopsState(saved: SavedAppPrefs) {
        val shouldPollSpotify = saved.isSpotifyLoggedIn && saved.syncEnabled
        if (shouldPollSpotify && pollJob?.isActive != true) {
            startSpotifyPollingLoop()
        } else if (!shouldPollSpotify) {
            pollJob?.cancel()
            pollJob = null
        }

        val shouldRunSmoothSync = saved.syncEnabled && saved.powerOn
        if (shouldRunSmoothSync && animationJob?.isActive != true) {
            startCoverAnimationLoop()
        } else if (!shouldRunSmoothSync) {
            animationJob?.cancel()
            animationJob = null
        }

        syncForegroundServiceState()
    }

    private fun sendColorWithPrefs(argb: Int, saved: SavedAppPrefs = _prefs.value) {
        bleManager.sendLiveColorAll(
            argb = argb,
            brightnessPercent = saved.brightness,
            colorOrder = saved.colorOrder,
            pureLedCalibration = saved.pureLedCalibration,
        )
    }

    private fun startSpotifyPollingLoop() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                val saved = _prefs.value
                if (!saved.isSpotifyLoggedIn || !saved.syncEnabled) break

                when (val result = spotifyRepository.fetchCurrentlyPlaying()) {
                    is TrackPollResult.Playing -> {
                        val track = result.track
                        val key = "${track.id}|${track.coverThumbUrl.orEmpty()}"
                        val isNewTrack = key != lastTrackKey

                        var updatedPalette = _playback.value.palette
                        if (isNewTrack) {
                            lastTrackKey = key
                            val coverUrl = track.coverThumbUrl ?: track.coverLargeUrl
                            if (!coverUrl.isNullOrBlank() && coverUrl != lastCoverUrl) {
                                lastCoverUrl = coverUrl
                                updatedPalette = spotifyRepository.extractPaletteFromCoverUrl(coverUrl)
                                prefsStore.setLastLiveLedArgb(updatedPalette.primaryArgb)
                            }
                        }

                        // Only update track & target palette; smooth loop glides progressively without snaps or flashes
                        _playback.update {
                            it.copy(
                                track = track,
                                palette = updatedPalette,
                                spotifyStatusMessage = if (track.isPlaying) null else "En pausa en Spotify",
                            )
                        }

                        delay(saved.pollIntervalMs)
                    }

                    TrackPollResult.NothingPlaying -> {
                        _playback.update {
                            it.copy(
                                track = null,
                                spotifyStatusMessage = "Nada reproduciéndose en Spotify",
                            )
                        }
                        delay((saved.pollIntervalMs * 2L).coerceAtLeast(2500L))
                    }

                    is TrackPollResult.RateLimited -> {
                        _playback.update {
                            it.copy(spotifyStatusMessage = "Límite temporal de Spotify, reintentando…")
                        }
                        delay(result.retryAfterMs)
                    }

                    is TrackPollResult.Error -> {
                        _playback.update {
                            it.copy(spotifyStatusMessage = result.message)
                        }
                        delay(if (result.requiresReauth) 5000L else 2500L)
                    }
                }
            }
        }
    }

    /**
     * Unified smooth transition + progressive intensity engine (~6.5 Hz, 155ms pacing
     * matched cleanly to dual 7B+7E BLE packet writes).
     */
    private fun startCoverAnimationLoop() {
        animationJob?.cancel()
        animationJob = scope.launch {
            var phase = 0f
            var smoothedPrimary = _playback.value.liveLedArgb
            var smoothedSecondary = _playback.value.palette.secondaryArgb
            var smoothedAccent = _playback.value.palette.accentArgb
            var lastSentPrimary = 0
            var lastSentSecondary = 0
            var lastSentBrightness = -1f
            var lastSentDualZones = false

            while (isActive) {
                val saved = _prefs.value
                if (!saved.syncEnabled || !saved.powerOn) {
                    break
                }

                val pal = _playback.value.palette
                val hasTrack = _playback.value.track != null
                val targetPrimary = if (hasTrack) pal.primaryArgb else saved.lastLiveLedArgb
                val targetSecondary = pal.secondaryArgb
                val targetAccent = pal.accentArgb

                // Progressive easing factor controlled by saved.transitionSpeed (5..100)
                val easeFraction = 0.06f + (saved.transitionSpeed / 100f) * 0.22f
                smoothedPrimary = easeRgbSmooth(smoothedPrimary, targetPrimary, easeFraction)
                smoothedSecondary = easeRgbSmooth(smoothedSecondary, targetSecondary, easeFraction)
                smoothedAccent = easeRgbSmooth(smoothedAccent, targetAccent, easeFraction)

                val waveT = ((1.0 - cos(phase * 2.0 * PI)) * 0.5).toFloat()
                val waveShifted = ((1.0 - cos(((phase + 0.35f) % 1f) * 2.0 * PI)) * 0.5).toFloat()
                val minFloor = (saved.pulseMinBrightness / 100f).coerceIn(0.15f, 0.85f)
                val maxBrightness = saved.brightness.toFloat()
                val isPlaying = _playback.value.track?.isPlaying != false

                var outPrimary = smoothedPrimary
                var outSecondary = smoothedPrimary
                var dualSplit = false
                var outBrightness = maxBrightness

                when (saved.coverSyncStyle) {
                    CoverSyncStyle.PRIMARY_BREATHING -> {
                        outPrimary = smoothedPrimary
                        val depth = if (isPlaying) waveT else 0.75f
                        outBrightness = maxBrightness * (minFloor + (1f - minFloor) * depth)
                    }

                    CoverSyncStyle.SOLID_PRIMARY -> {
                        outPrimary = smoothedPrimary
                        outBrightness = maxBrightness
                    }

                    CoverSyncStyle.DUAL_BREATHING -> {
                        outPrimary = AlbumPaletteExtractor.lerpRgb(smoothedPrimary, smoothedSecondary, waveT)
                        val softFloor = minFloor.coerceAtLeast(0.62f)
                        outBrightness = maxBrightness * (softFloor + (1f - softFloor) * waveT)
                    }

                    CoverSyncStyle.TRI_PALETTE_FLOW -> {
                        val seg = (phase * 3f) % 3f
                        val idx = seg.toInt()
                        val localFrac = ((1.0 - cos((seg - idx) * PI)) * 0.5).toFloat()
                        outPrimary = when (idx) {
                            0 -> AlbumPaletteExtractor.lerpRgb(smoothedPrimary, smoothedSecondary, localFrac)
                            1 -> AlbumPaletteExtractor.lerpRgb(smoothedSecondary, smoothedAccent, localFrac)
                            else -> AlbumPaletteExtractor.lerpRgb(smoothedAccent, smoothedPrimary, localFrac)
                        }
                        val softFloor = minFloor.coerceAtLeast(0.68f)
                        outBrightness = maxBrightness * (softFloor + (1f - softFloor) * waveT)
                    }

                    CoverSyncStyle.DUAL_ZONES -> {
                        dualSplit = true
                        outPrimary = smoothedPrimary
                        outSecondary = smoothedSecondary
                        val depth = if (isPlaying) waveT else 0.8f
                        outBrightness = maxBrightness * (minFloor + (1f - minFloor) * depth)
                    }

                    CoverSyncStyle.HEARTBEAT_GLOW -> {
                        // Organic double-swell pulse (no harsh spikes or snaps)
                        val mainPulse = ((1.0 - cos(phase * 2.0 * PI)) * 0.5).toFloat()
                        val secondSwell = ((1.0 - cos(((phase * 2f) % 1f) * 2.0 * PI)) * 0.5).toFloat()
                        val envelope = (mainPulse * 0.65f + secondSwell * 0.35f).coerceIn(0f, 1f)
                        outPrimary = AlbumPaletteExtractor.lerpRgb(smoothedPrimary, smoothedAccent, envelope * 0.25f)
                        val depth = if (isPlaying) envelope else 0.7f
                        outBrightness = maxBrightness * (minFloor + (1f - minFloor) * depth)
                    }

                    CoverSyncStyle.AURORA_MIX -> {
                        dualSplit = true
                        outPrimary = AlbumPaletteExtractor.lerpRgb(smoothedPrimary, smoothedAccent, waveT)
                        outSecondary = AlbumPaletteExtractor.lerpRgb(smoothedSecondary, smoothedPrimary, waveShifted)
                        val softFloor = minFloor.coerceAtLeast(0.60f)
                        outBrightness = maxBrightness * (softFloor + (1f - softFloor) * waveT)
                    }

                    CoverSyncStyle.SPECTRUM_DRIFT -> {
                        val driftDeg = (waveT - 0.5f) * 32f
                        outPrimary = shiftHueGentle(smoothedPrimary, driftDeg)
                        val depth = if (isPlaying) waveT else 0.8f
                        outBrightness = maxBrightness * (minFloor + (1f - minFloor) * depth)
                    }

                    CoverSyncStyle.NIGHT_CRUISE -> {
                        outPrimary = AlbumPaletteExtractor.lerpRgb(smoothedPrimary, smoothedSecondary, waveT * 0.45f)
                        val nightCap = maxBrightness.coerceAtMost(60f)
                        val depth = 0.55f + 0.45f * waveT
                        outBrightness = (nightCap * depth).coerceAtLeast(10f)
                    }
                }

                _playback.update { it.copy(liveLedArgb = outPrimary) }

                val shouldDispatch = bleState.value.isConnected && (
                    lastSentDualZones != dualSplit ||
                        colorDelta(outPrimary, lastSentPrimary) >= 2 ||
                        (dualSplit && colorDelta(outSecondary, lastSentSecondary) >= 2) ||
                        abs(outBrightness - lastSentBrightness) >= 1.5f
                    )

                if (shouldDispatch) {
                    lastSentPrimary = outPrimary
                    lastSentSecondary = outSecondary
                    lastSentBrightness = outBrightness
                    lastSentDualZones = dualSplit
                    bleManager.sendLiveColors(
                        primaryArgb = outPrimary,
                        secondaryArgb = outSecondary,
                        dualZoneSplit = dualSplit,
                        brightnessPercent = outBrightness.roundToInt().coerceIn(5, 100),
                        defaultColorOrder = saved.colorOrder,
                        pureLedCalibration = saved.pureLedCalibration,
                    )
                }

                val speedFactor = if (saved.coverSyncStyle == CoverSyncStyle.NIGHT_CRUISE) 0.5f else 1f
                val phaseStep = (0.010f + (saved.transitionSpeed / 100f) * 0.030f) * speedFactor
                phase = (phase + phaseStep) % 1f
                delay(155L)
            }
        }
    }

    private fun easeRgbSmooth(current: Int, target: Int, fraction: Float): Int {
        if (current == target) return target
        val cr = (current shr 16) and 0xFF
        val cg = (current shr 8) and 0xFF
        val cb = current and 0xFF
        val tr = (target shr 16) and 0xFF
        val tg = (target shr 8) and 0xFF
        val tb = target and 0xFF

        fun stepChannel(c: Int, t: Int): Int {
            val diff = t - c
            if (diff == 0) return c
            val step = (diff * fraction).roundToInt()
            val moved = if (step == 0) diff.sign else step
            return (c + moved).coerceIn(0, 255)
        }

        return AndroidColor.rgb(
            stepChannel(cr, tr),
            stepChannel(cg, tg),
            stepChannel(cb, tb),
        )
    }

    private fun shiftHueGentle(argb: Int, deltaDeg: Float): Int {
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(argb, hsv)
        hsv[0] = ((hsv[0] + deltaDeg) % 360f + 360f) % 360f
        return AndroidColor.HSVToColor(hsv)
    }

    private fun colorDelta(a: Int, b: Int): Int {
        val dr = abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF))
        val dg = abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF))
        val db = abs((a and 0xFF) - (b and 0xFF))
        return dr + dg + db
    }

    fun setActivityVisible(visible: Boolean) {
        isActivityVisible = visible
    }

    fun onScreenOff() {
        lastScreenOffElapsedMs = SystemClock.elapsedRealtime()
    }

    fun onBluetoothAdapterStateChanged(btState: Int) {
        when (btState) {
            BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> {
                wasBluetoothOff = true
                bleManager.handleBluetoothAdapterStateChanged(false)
            }

            BluetoothAdapter.STATE_ON -> {
                bleManager.handleBluetoothAdapterStateChanged(true)
                val wokeFromSleep = wasBluetoothOff || !isActivityVisible
                wasBluetoothOff = false
                onCarStartedEvent(
                    triggerOpenUi = wokeFromSleep,
                    reason = "bluetooth_on",
                )
            }
        }
    }

    fun onScreenOrUserWake(isUserPresent: Boolean, onComplete: (() -> Unit)? = null) {
        val sleptLongEnough = lastScreenOffElapsedMs > 0L &&
            (SystemClock.elapsedRealtime() - lastScreenOffElapsedMs) >= 15_000L
        if (sleptLongEnough) {
            lastScreenOffElapsedMs = 0L
        }
        val shouldOpen = !isActivityVisible && (sleptLongEnough || isUserPresent || !bleState.value.isConnected)
        onCarStartedEvent(
            triggerOpenUi = shouldOpen,
            reason = if (isUserPresent) "user_present" else "screen_on",
            onComplete = onComplete,
        )
    }

    fun onCarStartedEvent(
        triggerOpenUi: Boolean,
        reason: String,
        onComplete: (() -> Unit)? = null,
    ) {
        scope.launch {
            try {
                val saved = prefsStore.getSnapshot()
                _prefs.value = saved
                bleManager.setSelectedProfile(ProtocolProfile.fromId(saved.protocolId))
                bleManager.setDeviceOverrides(saved.deviceProfiles, saved.deviceColorOrders)
                bleManager.refreshAdapterState()

                if (saved.autoEnableBluetooth && !bleState.value.isBluetoothEnabled) {
                    bleManager.tryAutoEnableBluetooth()
                    delay(800L)
                    bleManager.refreshAdapterState()
                }

                if (_playback.value.track == null) {
                    val restoredArgb = if (saved.syncEnabled) saved.lastLiveLedArgb else saved.manualColorArgb
                    _playback.update { it.copy(liveLedArgb = restoredArgb) }
                }

                if (triggerOpenUi && saved.autoOpenOnCarStart) {
                    launchMainActivityForCarStart()
                }

                syncForegroundServiceState(
                    savedOverride = saved,
                    forceKeepAliveForAutoConnect = saved.autoConnectBle && saved.savedBleDevices.isNotEmpty(),
                )

                if (saved.autoConnectBle &&
                    saved.savedBleDevices.isNotEmpty() &&
                    bleManager.hasBleConnectPermission()
                ) {
                    autoConnectAttempted = true
                    connectAllSavedDevicesStaggered(saved)
                    scheduleAutoReconnectIfNeeded(aggressiveCarStart = true)
                }

                ensureLoopsState(saved)
            } finally {
                onComplete?.invoke()
            }
        }
    }

    private fun launchMainActivityForCarStart() {
        val now = SystemClock.elapsedRealtime()
        if (isActivityVisible) return
        if (now - lastAutoLaunchElapsedMs < 15_000L) return
        lastAutoLaunchElapsedMs = now

        val launchIntent = Intent(appContext, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
            putExtra("car_auto_start", true)
        }
        runCatching {
            appContext.startActivity(launchIntent)
        }
    }

    fun reconnectSavedDevicesIfNeeded() {
        val saved = _prefs.value
        bleManager.refreshAdapterState()
        if (!saved.autoConnectBle ||
            saved.savedBleDevices.isEmpty() ||
            !bleManager.hasBleConnectPermission() ||
            !bleState.value.isBluetoothEnabled
        ) {
            return
        }
        val currentBle = bleState.value
        val hasDisconnected = saved.savedBleDevices.any { dev ->
            !bleManager.isManuallyDisconnected(dev.address) &&
                currentBle.devicesByAddress[dev.address] == null
        }
        if (hasDisconnected) {
            connectAllSavedDevicesStaggered(saved)
            scheduleAutoReconnectIfNeeded(aggressiveCarStart = true)
        }
    }

    fun ensureBackgroundServiceFromForeground() {
        syncForegroundServiceState()
    }

    private fun syncForegroundServiceState(
        savedOverride: SavedAppPrefs? = null,
        forceKeepAliveForAutoConnect: Boolean = false,
    ) {
        val saved = savedOverride ?: _prefs.value
        val hasSavedAutoConnect = saved.autoConnectBle && saved.savedBleDevices.isNotEmpty()
        val shouldRunService = (saved.powerOn &&
            (saved.syncEnabled || bleState.value.isConnected || hasSavedAutoConnect) &&
            (saved.isSpotifyLoggedIn || bleState.value.isConnected || hasSavedAutoConnect)) ||
            forceKeepAliveForAutoConnect

        val intent = Intent(appContext, SymphonySyncService::class.java)
        runCatching {
            if (shouldRunService) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
            } else {
                appContext.stopService(intent)
            }
        }
    }

    // --- User Controls ---

    fun togglePower(on: Boolean) {
        scope.launch {
            prefsStore.setPowerOn(on)
            val saved = _prefs.value.copy(powerOn = on)
            if (on) {
                restoreSavedConfigurationToControllers(saved)
            } else {
                bleManager.sendPowerAll(false)
            }
        }
    }

    fun setBrightness(percent: Int) {
        val clamped = percent.coerceIn(5, 100)
        scope.launch {
            prefsStore.setBrightness(clamped)
            bleManager.sendControlPacketsAll { profile ->
                LedProtocol.buildBrightnessPackets(profile, clamped)
            }
            if (_prefs.value.powerOn) {
                delay(110L)
                val saved = _prefs.value
                val color = if (saved.syncEnabled) _playback.value.liveLedArgb else saved.manualColorArgb
                sendColorWithPrefs(color, saved.copy(brightness = clamped))
            }
        }
    }

    fun setSyncEnabled(enabled: Boolean) {
        scope.launch {
            prefsStore.setSyncEnabled(enabled)
            val saved = _prefs.value
            if (enabled) {
                val primary = _playback.value.palette.primaryArgb
                _playback.update { it.copy(liveLedArgb = primary) }
                if (saved.powerOn) {
                    sendColorWithPrefs(primary, saved)
                }
            } else if (saved.powerOn) {
                sendColorWithPrefs(saved.manualColorArgb, saved)
            }
        }
    }

    fun setCoverSyncStyle(style: CoverSyncStyle) {
        scope.launch { prefsStore.setCoverSyncStyle(style) }
    }

    fun setManualColor(argb: Int) {
        scope.launch {
            prefsStore.setManualColorArgb(argb)
            prefsStore.setManualEffectActive(false)
            if (_prefs.value.syncEnabled) {
                prefsStore.setSyncEnabled(false)
            }
            if (!_prefs.value.powerOn) {
                prefsStore.setPowerOn(true)
                bleManager.sendPowerAll(true)
                delay(180L)
            }
            _playback.update { it.copy(liveLedArgb = argb) }
            sendColorWithPrefs(argb, _prefs.value)
        }
    }

    fun connectBleDevice(address: String, name: String) {
        scope.launch {
            prefsStore.addSavedBleDevice(address, name)
            bleManager.connect(address, name)
        }
    }

    fun disconnectBleDevice(address: String) {
        scope.launch {
            prefsStore.removeSavedBleDevice(address)
            bleManager.disconnect(address)
        }
    }

    fun disconnectAllBleDevices() {
        bleManager.disconnectAll()
    }

    fun setProtocolProfile(profile: ProtocolProfile) {
        scope.launch {
            prefsStore.setProtocolId(profile.id)
            bleManager.setSelectedProfile(profile)
        }
    }

    fun setColorOrder(order: ColorOrder) {
        scope.launch {
            prefsStore.setColorOrder(order)
            val saved = _prefs.value.copy(colorOrder = order)
            if (saved.powerOn) {
                val color = if (saved.syncEnabled) _playback.value.liveLedArgb else saved.manualColorArgb
                sendColorWithPrefs(color, saved)
            }
        }
    }

    fun setPureLedCalibration(enabled: Boolean) {
        scope.launch {
            prefsStore.setPureLedCalibration(enabled)
            val saved = _prefs.value.copy(pureLedCalibration = enabled)
            if (saved.powerOn) {
                val color = if (saved.syncEnabled) _playback.value.liveLedArgb else saved.manualColorArgb
                sendColorWithPrefs(color, saved)
            }
        }
    }

    fun setAutoConnectBle(enabled: Boolean) {
        scope.launch {
            prefsStore.setAutoConnectBle(enabled)
            if (enabled) {
                reconnectSavedDevicesIfNeeded()
            }
        }
    }

    fun setAutoOpenOnCarStart(enabled: Boolean) {
        scope.launch { prefsStore.setAutoOpenOnCarStart(enabled) }
    }

    fun setAutoEnableBluetooth(enabled: Boolean) {
        scope.launch {
            prefsStore.setAutoEnableBluetooth(enabled)
            if (enabled) {
                bleManager.tryAutoEnableBluetooth()
            }
        }
    }

    fun setTransitionSpeed(speed: Int) {
        scope.launch { prefsStore.setTransitionSpeed(speed) }
    }

    fun setPulseMinBrightness(minPercent: Int) {
        scope.launch { prefsStore.setPulseMinBrightness(minPercent) }
    }

    fun setDeviceProtocol(address: String, profile: ProtocolProfile) {
        scope.launch {
            prefsStore.setDeviceProtocol(address, profile)
            val saved = _prefs.value
            val updatedMap = saved.deviceProfiles.toMutableMap().apply {
                if (profile == ProtocolProfile.AUTO) remove(address) else put(address, profile)
            }
            bleManager.setDeviceOverrides(updatedMap, saved.deviceColorOrders)
            if (saved.powerOn) {
                restoreSavedConfigurationToControllers(saved.copy(deviceProfiles = updatedMap))
            }
        }
    }

    fun setDeviceColorOrder(address: String, order: ColorOrder?) {
        scope.launch {
            prefsStore.setDeviceColorOrder(address, order)
            val saved = _prefs.value
            val updatedMap = saved.deviceColorOrders.toMutableMap().apply {
                if (order == null) remove(address) else put(address, order)
            }
            bleManager.setDeviceOverrides(saved.deviceProfiles, updatedMap)
            if (saved.powerOn) {
                val color = if (saved.syncEnabled) _playback.value.liveLedArgb else saved.manualColorArgb
                sendColorWithPrefs(color, saved.copy(deviceColorOrders = updatedMap))
            }
        }
    }

    fun testBleDevice(address: String) {
        scope.launch {
            val saved = _prefs.value
            bleManager.sendTestFlashToDevice(
                address = address,
                defaultColorOrder = saved.colorOrder,
                pureLedCalibration = saved.pureLedCalibration,
            )
            delay(1200L)
            if (saved.powerOn) {
                restoreSavedConfigurationToControllers(saved)
            }
        }
    }

    fun setPollIntervalMs(intervalMs: Long) {
        scope.launch { prefsStore.setPollIntervalMs(intervalMs) }
    }

    fun saveCredentialsAndBuildAuthUrl(clientId: String, clientSecret: String): String? {
        val id = clientId.trim()
        val secret = clientSecret.trim()
        if (id.isBlank() || secret.isBlank()) {
            _playback.update { it.copy(authError = "Introduce Client ID y Client Secret válidos.") }
            return null
        }
        scope.launch { prefsStore.saveSpotifyCredentials(id, secret) }
        _playback.update { it.copy(authError = null, authFeedback = null) }
        return spotifyRepository.createAuthorizationUrl(id)
    }

    fun handleOAuthCallbackUri(uri: Uri) {
        val extracted = spotifyRepository.extractCodeFromCallback(uri)
        extracted.onFailure { err ->
            _playback.update { it.copy(authError = err.message, isAuthBusy = false) }
            return
        }
        val code = extracted.getOrNull() ?: return
        val saved = _prefs.value
        exchangeCode(saved.clientId, saved.clientSecret, code)
    }

    fun exchangeCode(clientId: String, clientSecret: String, codeOrUrl: String) {
        scope.launch {
            _playback.update { it.copy(isAuthBusy = true, authError = null, authFeedback = null) }
            val result = spotifyRepository.exchangeCodeAndSave(clientId, clientSecret, codeOrUrl)
            result.onSuccess { userName ->
                _playback.update {
                    it.copy(
                        isAuthBusy = false,
                        authFeedback = "Conectado y guardado como $userName",
                        authError = null,
                    )
                }
            }.onFailure { err ->
                _playback.update {
                    it.copy(
                        isAuthBusy = false,
                        authError = err.message ?: "No se pudo validar el código de Spotify",
                    )
                }
            }
        }
    }

    fun clearAuthMessages() {
        _playback.update { it.copy(authError = null, authFeedback = null) }
    }

    fun logoutSpotify(eraseCredentialsToo: Boolean) {
        scope.launch {
            lastTrackKey = null
            lastCoverUrl = null
            if (eraseCredentialsToo) {
                prefsStore.clearAllSpotifyAccount()
            } else {
                prefsStore.clearSpotifySession()
            }
            _playback.update {
                LivePlaybackState(authFeedback = "Sesión cerrada")
            }
        }
    }

    fun checkForUpdates(rawRepo: String) {
        val normalized = GitHubUpdateChecker.normalizeRepoInput(rawRepo)
        scope.launch {
            if (normalized.isNotBlank()) {
                prefsStore.saveGithubRepo(normalized)
            }
            _updateState.value = UpdateCheckState.Checking
            _updateState.value = GitHubUpdateChecker.checkLatestRelease(rawRepo)
        }
    }
}
