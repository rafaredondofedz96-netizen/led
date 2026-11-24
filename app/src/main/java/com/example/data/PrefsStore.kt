package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.symphonyDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "symphony_prefs"
)

enum class CoverSyncStyle(val id: String, val label: String) {
    SOLID_PRIMARY("solid", "Color Fijo"),
    DUAL_BREATHING("dual", "Pulso Dual"),
    TRIPLE_FLOW("flow", "Flujo Portada");

    companion object {
        fun fromId(id: String?): CoverSyncStyle =
            entries.firstOrNull { it.id == id } ?: DUAL_BREATHING
    }
}

data class SavedAppPrefs(
    val clientId: String = "",
    val clientSecret: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val expiresAtMs: Long = 0L,
    val spotifyUserName: String = "",
    val lastBleAddress: String = "",
    val lastBleName: String = "",
    val protocolId: String = "AUTO",
    val powerOn: Boolean = true,
    val brightness: Int = 85,
    val effectSpeed: Int = 65,
    val syncEnabled: Boolean = true,
    val coverSyncStyle: CoverSyncStyle = CoverSyncStyle.DUAL_BREATHING,
    val manualColorArgb: Int = 0xFF00E5FF.toInt(),
    val activeEffectIndex: Int = 0,
    val pollIntervalMs: Long = 1200L,
    val autoConnectBle: Boolean = true,
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
        val PROTOCOL_ID = stringPreferencesKey("led_protocol_id")
        val POWER_ON = booleanPreferencesKey("led_power_on")
        val BRIGHTNESS = intPreferencesKey("led_brightness")
        val SPEED = intPreferencesKey("led_speed")
        val SYNC_ENABLED = booleanPreferencesKey("sync_enabled")
        val COVER_SYNC_STYLE = stringPreferencesKey("cover_sync_style")
        val MANUAL_COLOR = intPreferencesKey("manual_color_rgb")
        val ACTIVE_EFFECT = intPreferencesKey("active_symphony_effect")
        val POLL_INTERVAL = longPreferencesKey("poll_interval_ms")
        val AUTO_CONNECT_BLE = booleanPreferencesKey("auto_connect_ble")
        val GITHUB_REPO = stringPreferencesKey("github_repo")
    }

    val prefsFlow: Flow<SavedAppPrefs> = dataStore.data.map { p ->
        SavedAppPrefs(
            clientId = p[Keys.CLIENT_ID] ?: "",
            clientSecret = p[Keys.CLIENT_SECRET] ?: "",
            accessToken = p[Keys.ACCESS_TOKEN] ?: "",
            refreshToken = p[Keys.REFRESH_TOKEN] ?: "",
            expiresAtMs = p[Keys.EXPIRES_AT] ?: 0L,
            spotifyUserName = p[Keys.USER_NAME] ?: "",
            lastBleAddress = p[Keys.BLE_ADDRESS] ?: "",
            lastBleName = p[Keys.BLE_NAME] ?: "",
            protocolId = p[Keys.PROTOCOL_ID] ?: "AUTO",
            powerOn = p[Keys.POWER_ON] ?: true,
            brightness = (p[Keys.BRIGHTNESS] ?: 85).coerceIn(5, 100),
            effectSpeed = (p[Keys.SPEED] ?: 65).coerceIn(5, 100),
            syncEnabled = p[Keys.SYNC_ENABLED] ?: true,
            coverSyncStyle = CoverSyncStyle.fromId(p[Keys.COVER_SYNC_STYLE]),
            manualColorArgb = p[Keys.MANUAL_COLOR] ?: 0xFF00E5FF.toInt(),
            activeEffectIndex = p[Keys.ACTIVE_EFFECT] ?: 0,
            pollIntervalMs = (p[Keys.POLL_INTERVAL] ?: 1200L).coerceIn(800L, 5000L),
            autoConnectBle = p[Keys.AUTO_CONNECT_BLE] ?: true,
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

    suspend fun saveLastBleDevice(address: String, name: String) {
        dataStore.edit { p ->
            p[Keys.BLE_ADDRESS] = address
            p[Keys.BLE_NAME] = name
        }
    }

    suspend fun setProtocolId(protocolId: String) {
        dataStore.edit { it[Keys.PROTOCOL_ID] = protocolId }
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

    suspend fun setActiveEffectIndex(index: Int) {
        dataStore.edit { it[Keys.ACTIVE_EFFECT] = index }
    }

    suspend fun setPollIntervalMs(intervalMs: Long) {
        dataStore.edit { it[Keys.POLL_INTERVAL] = intervalMs.coerceIn(800L, 5000L) }
    }

    suspend fun setAutoConnectBle(enabled: Boolean) {
        dataStore.edit { it[Keys.AUTO_CONNECT_BLE] = enabled }
    }

    suspend fun saveGithubRepo(repo: String) {
        dataStore.edit { it[Keys.GITHUB_REPO] = repo.trim() }
    }
}
