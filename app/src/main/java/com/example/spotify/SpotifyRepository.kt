package com.example.spotify

import android.net.Uri
import android.util.Base64
import com.example.data.PrefsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

data class SpotifyTrack(
    val id: String,
    val title: String,
    val artist: String,
    val albumName: String,
    val coverLargeUrl: String?,
    val coverThumbUrl: String?,
    val isPlaying: Boolean,
    val progressMs: Long,
    val durationMs: Long,
)

sealed interface TrackPollResult {
    data class Playing(val track: SpotifyTrack) : TrackPollResult
    data object NothingPlaying : TrackPollResult
    data class RateLimited(val retryAfterMs: Long) : TrackPollResult
    data class Error(val message: String, val requiresReauth: Boolean = false) : TrackPollResult
}

class SpotifyRepository(private val prefsStore: PrefsStore) {

    companion object {
        const val REDIRECT_URI = "symphonysync://callback"
        private const val AUTH_URL = "https://accounts.spotify.com/authorize"
        private const val TOKEN_URL = "https://accounts.spotify.com/api/token"
        private const val CURRENTLY_PLAYING_URL =
            "https://api.spotify.com/v1/me/player/currently-playing"
        private const val ME_URL = "https://api.spotify.com/v1/me"
        private const val SCOPES =
            "user-read-currently-playing user-read-playback-state"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .writeTimeout(6, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val refreshMutex = Mutex()
    private var pendingOAuthState: String? = null

    fun createAuthorizationUrl(clientId: String): String {
        val state = UUID.randomUUID().toString().replace("-", "").take(16)
        pendingOAuthState = state
        return buildString {
            append(AUTH_URL)
            append("?client_id=").append(enc(clientId.trim()))
            append("&response_type=code")
            append("&redirect_uri=").append(enc(REDIRECT_URI))
            append("&scope=").append(enc(SCOPES))
            append("&state=").append(enc(state))
        }
    }

    fun extractCodeFromCallback(uri: Uri): Result<String> {
        val error = uri.getQueryParameter("error")
        if (!error.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Spotify rechazó el acceso: $error"))
        }
        val state = uri.getQueryParameter("state")
        val expectedState = pendingOAuthState
        if (expectedState != null && state != null && state != expectedState) {
            return Result.failure(IllegalStateException("Estado OAuth inválido. Inténtalo de nuevo."))
        }
        val code = uri.getQueryParameter("code")
        return if (!code.isNullOrBlank()) {
            pendingOAuthState = null
            Result.success(code)
        } else {
            Result.failure(IllegalStateException("No se recibió código de autorización en la URL"))
        }
    }

    suspend fun exchangeCodeAndSave(
        clientId: String,
        clientSecret: String,
        codeOrUrl: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        val trimmedInput = codeOrUrl.trim()
        val authCode = if (trimmedInput.contains("code=")) {
            val parsed = runCatching { Uri.parse(trimmedInput) }.getOrNull()
            parsed?.getQueryParameter("code")
                ?: return@withContext Result.failure(IllegalArgumentException("URL de callback sin parámetro code"))
        } else {
            trimmedInput
        }

        val cleanId = clientId.trim()
        val cleanSecret = clientSecret.trim()
        if (cleanId.isBlank() || cleanSecret.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Introduce Client ID y Client Secret"))
        }

        prefsStore.saveSpotifyCredentials(cleanId, cleanSecret)

        val basicAuth = Base64.encodeToString(
            "$cleanId:$cleanSecret".toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP,
        )
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", authCode)
            .add("redirect_uri", REDIRECT_URI)
            .build()

        val req = Request.Builder()
            .url(TOKEN_URL)
            .header("Authorization", "Basic $basicAuth")
            .post(body)
            .build()

        runCatching {
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val desc = runCatching {
                        JSONObject(raw).optString("error_description")
                            .ifBlank { JSONObject(raw).optString("error") }
                    }.getOrNull().orEmpty()
                    throw IllegalStateException("Error token (${resp.code}): $desc")
                }
                val json = JSONObject(raw)
                val accessToken = json.getString("access_token")
                val refreshToken = json.optString("refresh_token")
                val expiresIn = json.optLong("expires_in", 3600L)
                val expiresAt = System.currentTimeMillis() + (expiresIn * 1000L)
                val displayName = fetchUserDisplayName(accessToken)
                prefsStore.saveSpotifyTokens(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    expiresAtMs = expiresAt,
                    userName = displayName,
                )
                displayName ?: "Cuenta Spotify"
            }
        }
    }

    private fun fetchUserDisplayName(accessToken: String): String? {
        val req = Request.Builder()
            .url(ME_URL)
            .header("Authorization", "Bearer $accessToken")
            .build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val json = JSONObject(resp.body?.string().orEmpty())
                json.optString("display_name").ifBlank { json.optString("id") }.ifBlank { null }
            }
        }.getOrNull()
    }

    suspend fun getValidAccessToken(forceRefresh: Boolean = false): String? = refreshMutex.withLock {
        val snap = prefsStore.getSnapshot()
        if (!snap.isSpotifyLoggedIn) return@withLock null
        val now = System.currentTimeMillis()
        if (!forceRefresh && snap.accessToken.isNotBlank() && now < (snap.expiresAtMs - 60_000L)) {
            return@withLock snap.accessToken
        }

        val basicAuth = Base64.encodeToString(
            "${snap.clientId}:${snap.clientSecret}".toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP,
        )
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", snap.refreshToken)
            .build()

        val req = Request.Builder()
            .url(TOKEN_URL)
            .header("Authorization", "Basic $basicAuth")
            .post(body)
            .build()

        return@withLock withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) return@use null
                    val json = JSONObject(raw)
                    val newAccess = json.getString("access_token")
                    val newRefresh = json.optString("refresh_token").ifBlank { null }
                    val expiresIn = json.optLong("expires_in", 3600L)
                    prefsStore.saveSpotifyTokens(
                        accessToken = newAccess,
                        refreshToken = newRefresh,
                        expiresAtMs = System.currentTimeMillis() + (expiresIn * 1000L),
                    )
                    newAccess
                }
            }.getOrNull()
        }
    }

    suspend fun fetchCurrentlyPlaying(): TrackPollResult = withContext(Dispatchers.IO) {
        var token = getValidAccessToken(forceRefresh = false)
            ?: return@withContext TrackPollResult.Error("Cuenta Spotify no conectada", requiresReauth = true)

        var result = executeCurrentlyPlayingRequest(token)
        if (result is TrackPollResult.Error && result.requiresReauth) {
            token = getValidAccessToken(forceRefresh = true)
                ?: return@withContext TrackPollResult.Error("Sesión expirada. Reconecta Spotify.", requiresReauth = true)
            result = executeCurrentlyPlayingRequest(token)
        }
        result
    }

    private fun executeCurrentlyPlayingRequest(token: String): TrackPollResult {
        val req = Request.Builder()
            .url(CURRENTLY_PLAYING_URL)
            .header("Authorization", "Bearer $token")
            .build()

        return try {
            client.newCall(req).execute().use { resp ->
                when (resp.code) {
                    204 -> TrackPollResult.NothingPlaying
                    401 -> TrackPollResult.Error("Token caducado", requiresReauth = true)
                    429 -> {
                        val retrySec = resp.header("Retry-After")?.toLongOrNull() ?: 3L
                        TrackPollResult.RateLimited((retrySec.coerceIn(1L, 15L)) * 1000L)
                    }
                    in 200..299 -> {
                        val body = resp.body?.string().orEmpty()
                        if (body.isBlank()) return@use TrackPollResult.NothingPlaying
                        parseTrack(body)
                    }
                    else -> TrackPollResult.Error("Spotify HTTP ${resp.code}")
                }
            }
        } catch (e: Exception) {
            TrackPollResult.Error("Sin conexión de red")
        }
    }

    private fun parseTrack(rawJson: String): TrackPollResult {
        return try {
            val root = JSONObject(rawJson)
            val isPlaying = root.optBoolean("is_playing", false)
            val progressMs = root.optLong("progress_ms", 0L)
            val item = root.optJSONObject("item") ?: return TrackPollResult.NothingPlaying

            val id = item.optString("id").ifBlank { item.optString("name") }
            val title = item.optString("name", "Desconocido")
            val durationMs = item.optLong("duration_ms", 0L)

            val artistsArr = item.optJSONArray("artists")
            val artist = buildString {
                if (artistsArr != null) {
                    for (i in 0 until artistsArr.length()) {
                        if (i > 0) append(", ")
                        append(artistsArr.optJSONObject(i)?.optString("name").orEmpty())
                    }
                }
            }.ifBlank { "Artista" }

            val album = item.optJSONObject("album")
            val albumName = album?.optString("name").orEmpty()
            val images = album?.optJSONArray("images")

            var largeUrl: String? = null
            var thumbUrl: String? = null
            if (images != null && images.length() > 0) {
                largeUrl = images.optJSONObject(0)?.optString("url")
                thumbUrl = images.optJSONObject(images.length() - 1)?.optString("url") ?: largeUrl
            }

            TrackPollResult.Playing(
                SpotifyTrack(
                    id = id,
                    title = title,
                    artist = artist,
                    albumName = albumName,
                    coverLargeUrl = largeUrl,
                    coverThumbUrl = thumbUrl,
                    isPlaying = isPlaying,
                    progressMs = progressMs,
                    durationMs = durationMs,
                )
            )
        } catch (_: Exception) {
            TrackPollResult.NothingPlaying
        }
    }

    suspend fun extractPaletteFromCoverUrl(url: String): CoverPalette = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).build()
        runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use CoverPalette.Default
                val bytes = resp.body?.bytes() ?: return@use CoverPalette.Default
                AlbumPaletteExtractor.extractFromBytes(bytes)
            }
        }.getOrDefault(CoverPalette.Default)
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}
