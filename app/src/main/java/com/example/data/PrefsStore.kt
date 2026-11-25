package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.ble.ColorOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.symphonyDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "symphony_prefs"
)

enum class CoverSyncStyle(val id: String, val label: String) {
    SOLID_PRIMARY("solid", "Solo Principal"),
    DUAL_BREATHING("dual", "Pulso 2 Colores");

    companion object {
        fun fromId(id: String?): CoverSyncStyle =
            entries.firstOrNull { it.id == id } ?: SOLID_PRIMARY
    }
}

data class SavedBleDevice(
    val address: String,
    val name: String,
)

data class SavedAppPrefs(
    val clientId: String = "",
    val clientSecret: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val expiresAtMs: Long = 0L,
    val spotifyUserName: String = "",
    val savedBleDevices: List<SavedBleDevice> = emptyList(),
    val protocolId: String = "AUTO",
    val colorOrder: ColorOrder = ColorOrder.RGB,
    val pureLedCalibration: Boolean = true,
    val powerOn: Boolean = true,
    val brightness: Int = 85,
    val effectSpeed: Int = 55,
    val syncEnabled: Boolean = true,
    val coverSyncStyle: CoverSyncStyle = CoverSyncStyle.SOLID_PRIMARY,
    val manualColorArgb: Int = 0xFF00E5FF.toInt(),
    val manualEffectActive: Boolean = false,
    val lastLiveLedArgb: Int = 0xFF00E5FF.toInt(),
    val activeEffectIndex: Int = 0,
    val pollIntervalMs: Long = 1200L,
    val autoConnectBle: Boolean = true,
    val autoOpenOnCarStart: Boolean = true,
    val githubRepo: String = "",
) {
    val hasSpotifyCredentials: Boolean
        get() = clientId.isNotBlank() && clientSecret.isNotBlank()

    val isSpotifyLoggedIn: Boolean
        get() = hasSpotifyCredentials && refreshToken.isNotBlank()
}

class PrefsStore(context: Context) {

    private val dataStore = context.applicationContext.symphonyDataStore

    private object Keys {
        val CLIENT_ID = stringPreferencesKey("spotify_client_id")
        val CLIENT_SECRET = stringPreferencesKey("spotify_client_secret")
        val ACCESS_TOKEN = stringPreferencesKey("spotify_access_token")
        val REFRESH_TOKEN = stringPreferencesKey("spotify_refresh_token")
        val EXPIRES_AT = longPreferencesKey("spotify_expires_at")
        val USER_NAME = stringPreferencesKey("spotify_user_name")
        val BLE_ADDRESS = stringPreferencesKey("ble_device_address")
        val BLE_NAME = stringPreferencesKey("ble_device_name")
        val BLE_DEVICES_SET = stringSetPreferencesKey("ble_devices_set")
        val PROTOCOL_ID = stringPreferencesKey("led_protocol_id")
        val COLOR_ORDER = stringPreferencesKey("led_color_order")
        val PURE_LED_CAL = booleanPreferencesKey("led_pure_calibration")
        val POWER_ON = booleanPreferencesKey("led_power_on")
        val BRIGHTNESS = intPreferencesKey("led_brightness")
        val SPEED = intPreferencesKey("led_speed")
        val SYNC_ENABLED = booleanPreferencesKey("sync_enabled")
        val COVER_SYNC_STYLE = stringPreferencesKey("cover_sync_style")
        val MANUAL_COLOR = intPreferencesKey("manual_color_rgb")
        val MANUAL_EFFECT_ACTIVE = booleanPreferencesKey("manual_effect_active")
        val LAST_LIVE_LED = intPreferencesKey("last_live_led_rgb")
        val ACTIVE_EFFECT = intPreferencesKey("active_symphony_effect")
        val POLL_INTERVAL = longPreferencesKey("poll_interval_ms")
        val AUTO_CONNECT_BLE = booleanPreferencesKey("auto_connect_ble")
        val AUTO_OPEN_ON_CAR_START = booleanPreferencesKey("auto_open_on_car_start")
        val GITHUB_REPO = stringPreferencesKey("github_repo")
    }

    val prefsFlow: Flow<SavedAppPrefs> = dataStore.data.map { p ->
        val rawSet = p[Keys.BLE_DEVICES_SET]
        val devices = if (!rawSet.isNullOrEmpty()) {
            rawSet.mapNotNull { entry ->
                val idx = entry.indexOf('|')
                if (idx <= 0) null
                else SavedBleDevice(
                    address = entry.substring(0, idx),
                    name = entry.substring(idx + 1).ifBlank { "LEDCAR" },
                )
            }.sortedBy { it.name }
        } else {
            val legacyAddr = p[Keys.BLE_ADDRESS].orEmpty()
            val legacyName = p[Keys.BLE_NAME].orEmpty()
            if (legacyAddr.isNotBlank()) {
                listOf(SavedBleDevice(legacyAddr, legacyName.ifBlank { "Symphony LED" }))
            } else {
                emptyList()
            }
        }

        SavedAppPrefs(
            clientId = p[Keys.CLIENT_ID] ?: "",
            clientSecret = p[Keys.CLIENT_SECRET] ?: "",
            accessToken = p[Keys.ACCESS_TOKEN] ?: "",
            refreshToken = p[Keys.REFRESH_TOKEN] ?: "",
            expiresAtMs = p[Keys.EXPIRES_AT] ?: 0L,
            spotifyUserName = p[Keys.USER_NAME] ?: "",
            savedBleDevices = devices,
            protocolId = p[Keys.PROTOCOL_ID] ?: "AUTO",
            colorOrder = ColorOrder.fromId(p[Keys.COLOR_ORDER]),
            pureLedCalibration = p[Keys.PURE_LED_CAL] ?: true,
            powerOn = p[Keys.POWER_ON] ?: true,
            brightness = (p[Keys.BRIGHTNESS] ?: 85).coerceIn(5, 100),
            effectSpeed = (p[Keys.SPEED] ?: 55).coerceIn(5, 100),
            syncEnabled = p[Keys.SYNC_ENABLED] ?: true,
            coverSyncStyle = CoverSyncStyle.fromId(p[Keys.COVER_SYNC_STYLE]),
            manualColorArgb = p[Keys.MANUAL_COLOR] ?: 0xFF00E5FF.toInt(),
            manualEffectActive = p[Keys.MANUAL_EFFECT_ACTIVE] ?: false,
            lastLiveLedArgb = p[Keys.LAST_LIVE_LED] ?: (p[Keys.MANUAL_COLOR] ?: 0xFF00E5FF.toInt()),
            activeEffectIndex = p[Keys.ACTIVE_EFFECT] ?: 0,
            pollIntervalMs = (p[Keys.POLL_INTERVAL] ?: 1200L).coerceIn(800L, 5000L),
            autoConnectBle = p[Keys.AUTO_CONNECT_BLE] ?: true,
            autoOpenOnCarStart = p[Keys.AUTO_OPEN_ON_CAR_START] ?: true,
            githubRepo = p[Keys.GITHUB_REPO] ?: "",
        )
    }

    suspend fun getSnapshot(): SavedAppPrefs = prefsFlow.first()

    suspend fun saveSpotifyCredentials(clientId: String, clientSecret: String) {
        dataStore.edit { p ->
            p[Keys.CLIENT_ID] = clientId.trim()
            p[Keys.CLIENT_SECRET] = clientSecret.trim()
        }
    }

    suspend fun saveSpotifyTokens(
        accessToken: String,
        refreshToken: String?,
        expiresAtMs: Long,
        userName: String? = null,
    ) {
        dataStore.edit { p ->
            p[Keys.ACCESS_TOKEN] = accessToken
            if (!refreshToken.isNullOrBlank()) {
                p[Keys.REFRESH_TOKEN] = refreshToken
            }
            p[Keys.EXPIRES_AT] = expiresAtMs
            if (!userName.isNullOrBlank()) {
                p[Keys.USER_NAME] = userName
            }
        }
    }

    suspend fun clearSpotifySession() {
        dataStore.edit { p ->
            p.remove(Keys.ACCESS_TOKEN)
            p.remove(Keys.REFRESH_TOKEN)
            p.remove(Keys.EXPIRES_AT)
            p.remove(Keys.USER_NAME)
        }
    }

    suspend fun clearAllSpotifyAccount() {
        dataStore.edit { p ->
            p.remove(Keys.CLIENT_ID)
            p.remove(Keys.CLIENT_SECRET)
            p.remove(Keys.ACCESS_TOKEN)
            p.remove(Keys.REFRESH_TOKEN)
            p.remove(Keys.EXPIRES_AT)
            p.remove(Keys.USER_NAME)
        }
    }

    suspend fun addSavedBleDevice(address: String, name: String) {
        dataStore.edit { p ->
            val current = p[Keys.BLE_DEVICES_SET]?.toMutableSet() ?: mutableSetOf()
            // Migrate legacy single device if present
            val legacyAddr = p[Keys.BLE_ADDRESS]
            val legacyName = p[Keys.BLE_NAME]
            if (!legacyAddr.isNullOrBlank() && current.none { it.startsWith("$legacyAddr|") }) {
                current.add("$legacyAddr|${legacyName.orEmpty()}")
            }
            current.removeAll { it.startsWith("$address|") }
            current.add("$address|${name.ifBlank { "LEDCAR" }}")
            p[Keys.BLE_DEVICES_SET] = current
            p[Keys.BLE_ADDRESS] = address
            p[Keys.BLE_NAME] = name
        }
    }

    suspend fun removeSavedBleDevice(address: String) {
        dataStore.edit { p ->
            val current = p[Keys.BLE_DEVICES_SET]?.toMutableSet() ?: mutableSetOf()
            current.removeAll { it.startsWith("$address|") }
            p[Keys.BLE_DEVICES_SET] = current
            if (p[Keys.BLE_ADDRESS] == address) {
                p.remove(Keys.BLE_ADDRESS)
                p.remove(Keys.BLE_NAME)
            }
        }
    }

    suspend fun setProtocolId(protocolId: String) {
        dataStore.edit { it[Keys.PROTOCOL_ID] = protocolId }
    }

    suspend fun setColorOrder(order: ColorOrder) {
        dataStore.edit { it[Keys.COLOR_ORDER] = order.id }
    }

    suspend fun setPureLedCalibration(enabled: Boolean) {
        dataStore.edit { it[Keys.PURE_LED_CAL] = enabled }
    }

    suspend fun setPowerOn(on: Boolean) {
        dataStore.edit { it[Keys.POWER_ON] = on }
    }

    suspend fun setBrightness(brightness: Int) {
        dataStore.edit { it[Keys.BRIGHTNESS] = brightness.coerceIn(5, 100) }
    }

    suspend fun setEffectSpeed(speed: Int) {
        dataStore.edit { it[Keys.SPEED] = speed.coerceIn(5, 100) }
    }

    suspend fun setSyncEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.SYNC_ENABLED] = enabled }
    }

    suspend fun setCoverSyncStyle(style: CoverSyncStyle) {
        dataStore.edit { it[Keys.COVER_SYNC_STYLE] = style.id }
    }

    suspend fun setManualColorArgb(argb: Int) {
        dataStore.edit { it[Keys.MANUAL_COLOR] = argb }
    }

    suspend fun setManualEffectActive(active: Boolean) {
        dataStore.edit { it[Keys.MANUAL_EFFECT_ACTIVE] = active }
    }

    suspend fun setLastLiveLedArgb(argb: Int) {
        dataStore.edit { it[Keys.LAST_LIVE_LED] = argb }
    }

    suspend fun setActiveEffectIndex(index: Int) {
        dataStore.edit { it[Keys.ACTIVE_EFFECT] = index }
    }

    suspend fun setPollIntervalMs(intervalMs: Long) {
        dataStore.edit { it[Keys.POLL_INTERVAL] = intervalMs.coerceIn(800L, 5000L) }
    }

    suspend fun setAutoConnectBle(enabled: Boolean) {
        dataStore.edit { it[Keys.AUTO_CONNECT_BLE] = enabled }
    }

    suspend fun setAutoOpenOnCarStart(enabled: Boolean) {
        dataStore.edit { it[Keys.AUTO_OPEN_ON_CAR_START] = enabled }
    }

    suspend fun saveGithubRepo(repo: String) {
        dataStore.edit { it[Keys.GITHUB_REPO] = repo.trim() }
    }
}
