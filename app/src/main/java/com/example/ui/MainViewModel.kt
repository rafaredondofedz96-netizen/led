package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.SymphonyApp
import com.example.ble.BleUiState
import com.example.ble.ProtocolProfile
import com.example.core.LivePlaybackState
import com.example.data.CoverSyncStyle
import com.example.data.SavedAppPrefs
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

enum class AppTab(val label: String) {
    LIVE("En Vivo"),
    MODES("Lamp LED"),
    BLUETOOTH("Bluetooth"),
    ACCOUNT("Spotify"),
}

data class MainUiState(
    val prefs: SavedAppPrefs = SavedAppPrefs(),
    val ble: BleUiState = BleUiState(),
    val playback: LivePlaybackState = LivePlaybackState(),
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = (application as SymphonyApp).syncEngine

    val uiState: StateFlow<MainUiState> = combine(
        engine.prefs,
        engine.bleState,
        engine.playback,
    ) { prefs, ble, playback ->
        MainUiState(prefs = prefs, ble = ble, playback = playback)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = MainUiState(),
    )

    fun hasBleScanPermission(): Boolean = engine.bleManager.hasBleScanPermission()
    fun hasBleConnectPermission(): Boolean = engine.bleManager.hasBleConnectPermission()
    fun refreshBluetoothState() = engine.bleManager.refreshAdapterState()
    fun startBleScan() = engine.bleManager.startScan()
    fun stopBleScan() = engine.bleManager.stopScan()

    fun connectBleDevice(address: String, name: String) = engine.connectBleDevice(address, name)
    fun disconnectBleDevice() = engine.disconnectBleDevice()
    fun setProtocolProfile(profile: ProtocolProfile) = engine.setProtocolProfile(profile)
    fun setAutoConnectBle(enabled: Boolean) = engine.setAutoConnectBle(enabled)

    fun togglePower(on: Boolean) = engine.togglePower(on)
    fun setBrightness(percent: Int) = engine.setBrightness(percent)
    fun setEffectSpeed(percent: Int) = engine.setEffectSpeed(percent)
    fun setSyncEnabled(enabled: Boolean) = engine.setSyncEnabled(enabled)
    fun setCoverSyncStyle(style: CoverSyncStyle) = engine.setCoverSyncStyle(style)
    fun setManualColor(argb: Int) = engine.setManualColor(argb)
    fun selectSymphonyEffect(index: Int) = engine.selectSymphonyEffect(index)
    fun setPollIntervalMs(intervalMs: Long) = engine.setPollIntervalMs(intervalMs)

    fun buildSpotifyAuthUrl(clientId: String, clientSecret: String): String? =
        engine.saveCredentialsAndBuildAuthUrl(clientId, clientSecret)

    fun handleOAuthCallback(uri: Uri) = engine.handleOAuthCallbackUri(uri)

    fun submitManualCallback(clientId: String, clientSecret: String, codeOrUrl: String) =
        engine.exchangeCode(clientId, clientSecret, codeOrUrl)

    fun clearAuthMessages() = engine.clearAuthMessages()
    fun logoutSpotify(eraseCredentialsToo: Boolean) = engine.logoutSpotify(eraseCredentialsToo)
}
