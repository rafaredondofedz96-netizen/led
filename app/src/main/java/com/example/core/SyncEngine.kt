package com.example.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.example.ble.BleConnectionStatus
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
    private var prevConnectedCount = 0

    init {
        scope.launch {
            prefsStore.prefsFlow.collectLatest { saved ->
                _prefs.value = saved
                bleManager.setSelectedProfile(ProtocolProfile.fromId(saved.protocolId))

                if (!autoConnectAttempted &&
                    saved.autoConnectBle &&
                    saved.savedBleDevices.isNotEmpty() &&
                    bleManager.hasBleConnectPermission()
                ) {
                    autoConnectAttempted = true
                    connectAllSavedDevicesStaggered()
                }

                ensureLoopsState(saved)
            }
        }

        scope.launch {
            bleState.collectLatest { ble ->
                val count = ble.connectedCount
                if (count > prevConnectedCount) {
                    val saved = _prefs.value
                    bleManager.sendPowerAll(saved.powerOn)
                    if (saved.powerOn) {
                        delay(110L)
                        val targetColor = if (saved.syncEnabled) {
                            _playback.value.liveLedArgb
                        } else {
                            saved.manualColorArgb
                        }
                        bleManager.sendLiveColorAll(
                            argb = targetColor,
                            brightnessPercent = saved.brightness,
                            colorOrder = saved.colorOrder,
                            pureLedCalibration = saved.pureLedCalibration,
                        )
                    }
                    syncForegroundServiceState()
                }
                prevConnectedCount = count

                val saved = _prefs.value
                val hasDisconnectedSaved = saved.savedBleDevices.any { dev ->
                    !bleManager.isManuallyDisconnected(dev.address) &&
                        ble.devicesByAddress[dev.address]?.status == null
                }
                if (saved.autoConnectBle && hasDisconnectedSaved) {
                    scheduleAutoReconnectIfNeeded()
                }
            }
        }
    }

    private fun connectAllSavedDevicesStaggered() {
        scope.launch {
            val saved = _prefs.value.savedBleDevices
            saved.forEachIndexed { idx, dev ->
                if (!bleManager.isManuallyDisconnected(dev.address) &&
                    bleManager.hasBleConnectPermission()
                ) {
                    if (idx > 0) delay(250L) // Stagger dual GATT connect so TS10 BLE doesn't collide
                    bleManager.connect(dev.address, dev.name)
                }
            }
        }
    }

    private fun scheduleAutoReconnectIfNeeded() {
        val saved = _prefs.value
        if (!saved.autoConnectBle ||
            saved.savedBleDevices.isEmpty() ||
            !bleManager.hasBleConnectPermission()
        ) {
            return
        }
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            var delayMs = 4000L
            while (isActive) {
                delay(delayMs)
                val currentSaved = _prefs.value
                if (!currentSaved.autoConnectBle || !bleManager.hasBleConnectPermission()) break

                val currentBle = bleState.value
                val pending = currentSaved.savedBleDevices.filter { dev ->
                    !bleManager.isManuallyDisconnected(dev.address) &&
                        currentBle.devicesByAddress[dev.address] == null
                }
                if (pending.isEmpty()) break

                pending.forEachIndexed { i, dev ->
                    if (i > 0) delay(250L)
                    bleManager.connect(dev.address, dev.name)
                }
                delayMs = (delayMs * 2L).coerceAtMost(12_000L)
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

        val shouldAnimateCover = saved.syncEnabled &&
            saved.powerOn &&
            saved.coverSyncStyle == CoverSyncStyle.DUAL_BREATHING
        if (shouldAnimateCover && animationJob?.isActive != true) {
            startCoverAnimationLoop()
        } else if (!shouldAnimateCover) {
            animationJob?.cancel()
            animationJob = null
            if (saved.syncEnabled) {
                val primary = _playback.value.palette.primaryArgb
                _playback.update { it.copy(liveLedArgb = primary) }
                if (saved.powerOn) {
                    sendColorWithPrefs(primary, saved)
                }
            }
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
                            }
                        }

                        _playback.update {
                            it.copy(
                                track = track,
                                palette = updatedPalette,
                                liveLedArgb = if (isNewTrack || _prefs.value.coverSyncStyle == CoverSyncStyle.SOLID_PRIMARY) {
                                    updatedPalette.primaryArgb
                                } else it.liveLedArgb,
                                spotifyStatusMessage = if (track.isPlaying) null else "En pausa en Spotify",
                            )
                        }

                        if (isNewTrack && saved.powerOn && saved.syncEnabled) {
                            sendColorWithPrefs(updatedPalette.primaryArgb, saved)
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

    private fun startCoverAnimationLoop() {
        animationJob?.cancel()
        animationJob = scope.launch {
            var phase = 0f
            var lastDispatchedArgb = 0
            while (isActive) {
                val saved = _prefs.value
                if (!saved.syncEnabled || !saved.powerOn || saved.coverSyncStyle != CoverSyncStyle.DUAL_BREATHING) {
                    break
                }

                val currentTrack = _playback.value.track
                val pal = _playback.value.palette
                val targetArgb = if (currentTrack?.isPlaying == false || pal.primaryArgb == pal.secondaryArgb) {
                    pal.primaryArgb
                } else {
                    val t = ((1.0 - cos(phase * 2.0 * PI)) * 0.5).toFloat()
                    AlbumPaletteExtractor.lerpRgb(pal.primaryArgb, pal.secondaryArgb, t)
                }

                _playback.update { it.copy(liveLedArgb = targetArgb) }
                if (bleState.value.isConnected && colorDelta(targetArgb, lastDispatchedArgb) >= 10) {
                    lastDispatchedArgb = targetArgb
                    sendColorWithPrefs(targetArgb, saved)
                }

                val speedFactor = 0.012f + (saved.effectSpeed / 100f) * 0.032f
                phase = (phase + speedFactor) % 1f
                // Safe 140ms pacing keeps dual LEDCAR-01 controllers smooth and crash-free
                delay(140L)
            }
        }
    }

    private fun colorDelta(a: Int, b: Int): Int {
        val dr = abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF))
        val dg = abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF))
        val db = abs((a and 0xFF) - (b and 0xFF))
        return dr + dg + db
    }

    fun ensureBackgroundServiceFromForeground() {
        syncForegroundServiceState()
    }

    private fun syncForegroundServiceState() {
        val saved = _prefs.value
        val shouldRunService = saved.powerOn &&
            (saved.syncEnabled || bleState.value.isConnected) &&
            (saved.isSpotifyLoggedIn || bleState.value.isConnected)

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
            bleManager.sendPowerAll(on)
            if (on) {
                delay(180L)
                val saved = _prefs.value
                val target = if (saved.syncEnabled) _playback.value.liveLedArgb else saved.manualColorArgb
                sendColorWithPrefs(target, saved)
            }
        }
    }

    fun setBrightness(percent: Int) {
        val clamped = percent.coerceIn(5, 100)
        scope.launch {
            prefsStore.setBrightness(clamped)
            bleManager.sendControlPacketsAll { profile ->
                LedProtocol.buildBrightnessPacket(profile, clamped)
            }
            if (_prefs.value.powerOn) {
                delay(100L)
                val saved = _prefs.value
                val color = if (saved.syncEnabled) _playback.value.liveLedArgb else saved.manualColorArgb
                sendColorWithPrefs(color, saved.copy(brightness = clamped))
            }
        }
    }

    fun setEffectSpeed(percent: Int) {
        val clamped = percent.coerceIn(5, 100)
        scope.launch {
            prefsStore.setEffectSpeed(clamped)
            bleManager.sendControlPacketsAll { profile ->
                LedProtocol.buildSpeedPacket(profile, clamped)
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
            if (_prefs.value.syncEnabled) {
                prefsStore.setSyncEnabled(false)
            }
            if (!_prefs.value.powerOn) {
                prefsStore.setPowerOn(true)
                bleManager.sendPowerAll(true)
                delay(160L)
            }
            _playback.update { it.copy(liveLedArgb = argb) }
            sendColorWithPrefs(argb, _prefs.value)
        }
    }

    fun selectSymphonyEffect(effectIndex: Int) {
        val effect = LedProtocol.symphonyEffects.getOrNull(effectIndex) ?: return
        scope.launch {
            prefsStore.setActiveEffectIndex(effectIndex)
            if (_prefs.value.syncEnabled) {
                prefsStore.setSyncEnabled(false)
            }
            if (!_prefs.value.powerOn) {
                prefsStore.setPowerOn(true)
                bleManager.sendPowerAll(true)
                delay(160L)
            }
            val speed = _prefs.value.effectSpeed
            bleManager.sendControlPacketsAll { profile ->
                LedProtocol.buildEffectPacket(profile, effect, speed)
            }
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
        scope.launch { prefsStore.setAutoConnectBle(enabled) }
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
