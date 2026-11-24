package com.example.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.example.ble.BleConnectionStatus
import com.example.ble.BleUiState
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

    private var pollJob: Job? = null
    private var animationJob: Job? = null
    private var reconnectJob: Job? = null
    private var lastTrackKey: String? = null
    private var lastCoverUrl: String? = null
    private var autoConnectAttempted = false

    init {
        scope.launch {
            prefsStore.prefsFlow.collectLatest { saved ->
                _prefs.value = saved
                bleManager.setSelectedProfile(ProtocolProfile.fromId(saved.protocolId))

                if (!autoConnectAttempted &&
                    saved.autoConnectBle &&
                    saved.lastBleAddress.isNotBlank() &&
                    bleManager.hasBleConnectPermission() &&
                    bleState.value.status == BleConnectionStatus.DISCONNECTED
                ) {
                    autoConnectAttempted = true
                    bleManager.connect(saved.lastBleAddress, saved.lastBleName)
                }

                ensureLoopsState(saved)
            }
        }

        scope.launch {
            bleState.collectLatest { ble ->
                if (ble.isConnected) {
                    reconnectJob?.cancel()
                    reconnectJob = null
                    val saved = _prefs.value
                    // Immediately push current power & active color/effect on connect
                    bleManager.sendControlPacket(
                        LedProtocol.buildPowerPacket(bleManager.effectiveProfile(), saved.powerOn)
                    )
                    if (saved.powerOn) {
                        if (saved.syncEnabled) {
                            bleManager.sendLiveColor(_playback.value.liveLedArgb, saved.brightness)
                        } else {
                            bleManager.sendLiveColor(saved.manualColorArgb, saved.brightness)
                        }
                    }
                    syncForegroundServiceState()
                } else if (ble.status == BleConnectionStatus.DISCONNECTED) {
                    scheduleAutoReconnectIfNeeded()
                }
            }
        }
    }

    private fun scheduleAutoReconnectIfNeeded() {
        val saved = _prefs.value
        if (!saved.autoConnectBle ||
            saved.lastBleAddress.isBlank() ||
            bleManager.userDisconnected ||
            !bleManager.hasBleConnectPermission()
        ) {
            return
        }
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            var delayMs = 3500L
            while (isActive) {
                delay(delayMs)
                val currentSaved = _prefs.value
                val currentBle = bleState.value
                if (!currentSaved.autoConnectBle ||
                    currentSaved.lastBleAddress.isBlank() ||
                    bleManager.userDisconnected ||
                    currentBle.status != BleConnectionStatus.DISCONNECTED
                ) {
                    break
                }
                if (bleManager.hasBleConnectPermission()) {
                    bleManager.connect(currentSaved.lastBleAddress, currentSaved.lastBleName)
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
            saved.coverSyncStyle != CoverSyncStyle.SOLID_PRIMARY
        if (shouldAnimateCover && animationJob?.isActive != true) {
            startCoverAnimationLoop()
        } else if (!shouldAnimateCover) {
            animationJob?.cancel()
            animationJob = null
            if (saved.syncEnabled) {
                val primary = _playback.value.palette.primaryArgb
                _playback.update { it.copy(liveLedArgb = primary) }
                if (saved.powerOn) {
                    bleManager.sendLiveColor(primary, saved.brightness)
                }
            }
        }

        syncForegroundServiceState()
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
                            bleManager.sendLiveColor(updatedPalette.primaryArgb, saved.brightness)
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
            while (isActive) {
                val saved = _prefs.value
                if (!saved.syncEnabled || !saved.powerOn || saved.coverSyncStyle == CoverSyncStyle.SOLID_PRIMARY) {
                    break
                }

                val currentTrack = _playback.value.track
                val pal = _playback.value.palette
                val targetArgb = if (currentTrack?.isPlaying == false) {
                    pal.primaryArgb
                } else {
                    when (saved.coverSyncStyle) {
                        CoverSyncStyle.SOLID_PRIMARY -> pal.primaryArgb
                        CoverSyncStyle.DUAL_BREATHING -> {
                            val t = ((1.0 - cos(phase * 2.0 * PI)) * 0.5).toFloat()
                            AlbumPaletteExtractor.lerpRgb(pal.primaryArgb, pal.secondaryArgb, t)
                        }
                        CoverSyncStyle.TRIPLE_FLOW -> {
                            val p = (phase % 1f) * 3f
                            when {
                                p < 1f -> AlbumPaletteExtractor.lerpRgb(pal.primaryArgb, pal.secondaryArgb, p)
                                p < 2f -> AlbumPaletteExtractor.lerpRgb(pal.secondaryArgb, pal.ambientArgb, p - 1f)
                                else -> AlbumPaletteExtractor.lerpRgb(pal.ambientArgb, pal.primaryArgb, p - 2f)
                            }
                        }
                    }
                }

                _playback.update { it.copy(liveLedArgb = targetArgb) }
                if (bleState.value.isConnected) {
                    bleManager.sendLiveColor(targetArgb, saved.brightness)
                }

                val speedFactor = 0.008f + (saved.effectSpeed / 100f) * 0.025f
                phase = (phase + speedFactor) % 1f
                delay(65L) // ~15 FPS smooth automotive ambient transition, zero BLE congestion
            }
        }
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
            val profile = bleManager.effectiveProfile()
            bleManager.sendControlPacket(LedProtocol.buildPowerPacket(profile, on))
            if (on) {
                delay(40L)
                val saved = _prefs.value
                val target = if (saved.syncEnabled) _playback.value.liveLedArgb else saved.manualColorArgb
                bleManager.sendLiveColor(target, saved.brightness)
            }
        }
    }

    fun setBrightness(percent: Int) {
        val clamped = percent.coerceIn(5, 100)
        scope.launch {
            prefsStore.setBrightness(clamped)
            val profile = bleManager.effectiveProfile()
            val brPacket = LedProtocol.buildBrightnessPacket(profile, clamped)
            if (brPacket != null) {
                bleManager.sendControlPacket(brPacket)
            } else if (_prefs.value.powerOn) {
                val color = if (_prefs.value.syncEnabled) _playback.value.liveLedArgb else _prefs.value.manualColorArgb
                bleManager.sendLiveColor(color, clamped)
            }
        }
    }

    fun setEffectSpeed(percent: Int) {
        val clamped = percent.coerceIn(5, 100)
        scope.launch {
            prefsStore.setEffectSpeed(clamped)
            LedProtocol.buildSpeedPacket(bleManager.effectiveProfile(), clamped)?.let {
                bleManager.sendControlPacket(it)
            }
        }
    }

    fun setSyncEnabled(enabled: Boolean) {
        scope.launch {
            prefsStore.setSyncEnabled(enabled)
            if (enabled) {
                val primary = _playback.value.palette.primaryArgb
                _playback.update { it.copy(liveLedArgb = primary) }
                if (_prefs.value.powerOn) {
                    bleManager.sendLiveColor(primary, _prefs.value.brightness)
                }
            } else if (_prefs.value.powerOn) {
                bleManager.sendLiveColor(_prefs.value.manualColorArgb, _prefs.value.brightness)
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
                bleManager.sendControlPacket(
                    LedProtocol.buildPowerPacket(bleManager.effectiveProfile(), true)
                )
            }
            _playback.update { it.copy(liveLedArgb = argb) }
            bleManager.sendLiveColor(argb, _prefs.value.brightness)
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
                bleManager.sendControlPacket(
                    LedProtocol.buildPowerPacket(bleManager.effectiveProfile(), true)
                )
                delay(35L)
            }
            bleManager.sendControlPacket(
                LedProtocol.buildEffectPacket(
                    bleManager.effectiveProfile(),
                    effect,
                    _prefs.value.effectSpeed,
                )
            )
        }
    }

    fun connectBleDevice(address: String, name: String) {
        scope.launch {
            prefsStore.saveLastBleDevice(address, name)
            bleManager.connect(address, name)
        }
    }

    fun disconnectBleDevice() {
        bleManager.disconnect()
    }

    fun setProtocolProfile(profile: ProtocolProfile) {
        scope.launch {
            prefsStore.setProtocolId(profile.id)
            bleManager.setSelectedProfile(profile)
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
}
