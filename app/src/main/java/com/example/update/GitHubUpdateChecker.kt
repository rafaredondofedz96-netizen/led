package com.example.update

import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed interface UpdateCheckState {
    data object Idle : UpdateCheckState
    data object Checking : UpdateCheckState
    data class Available(
        val latestVersion: String,
        val apkDownloadUrl: String,
        val releasePageUrl: String,
        val notes: String,
    ) : UpdateCheckState
    data class UpToDate(val currentVersion: String) : UpdateCheckState
    data class Error(val message: String) : UpdateCheckState
}

object GitHubUpdateChecker {

    private val repoRegex = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    fun normalizeRepoInput(raw: String): String {
        var cleaned = raw.trim()
            .removePrefix("https://github.com/")
            .removePrefix("http://github.com/")
            .removePrefix("github.com/")
            .removeSuffix(".git")
            .trim('/')
        // If user pasted /releases or extra path segments, keep only owner/repo
        val parts = cleaned.split("/")
        if (parts.size >= 2) {
            cleaned = "${parts[0]}/${parts[1]}"
        }
        return cleaned
    }

    suspend fun checkLatestRelease(rawRepo: String): UpdateCheckState = withContext(Dispatchers.IO) {
        val repo = normalizeRepoInput(rawRepo)
        if (!repoRegex.matches(repo)) {
            return@withContext UpdateCheckState.Error(
                "Formato inválido. Usa usuario/repositorio (ej: rafa/SymphonySync)"
            )
        }

        val apiUrl = "https://api.github.com/repos/$repo/releases/latest"
        val req = Request.Builder()
            .url(apiUrl)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "SymphonySync-Android/${BuildConfig.VERSION_NAME}")
            .build()

        try {
            client.newCall(req).execute().use { resp ->
                if (resp.code == 404) {
                    return@withContext UpdateCheckState.Error(
                        "Sin Releases publicadas aún en $repo (espera 2 min tras subir a GitHub)"
                    )
                }
                if (!resp.isSuccessful) {
                    return@withContext UpdateCheckState.Error("Error GitHub HTTP ${resp.code}")
                }
                val raw = resp.body?.string().orEmpty()
                val json = JSONObject(raw)
                val tag = json.optString("tag_name").ifBlank { json.optString("name") }
                val latestClean = tag.trim().removePrefix("v").removePrefix("V")
                val htmlUrl = json.optString("html_url")
                val body = json.optString("body").take(160)

                val assets = json.optJSONArray("assets")
                var apkUrl: String? = null
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.optJSONObject(i) ?: continue
                        val name = asset.optString("name").lowercase()
                        if (name.endsWith(".apk")) {
                            apkUrl = asset.optString("browser_download_url")
                            break
                        }
                    }
                }

                if (latestClean.isBlank()) {
                    return@withContext UpdateCheckState.Error("Release sin etiqueta de versión")
                }

                if (isNewerVersion(latestClean, BuildConfig.VERSION_NAME)) {
                    UpdateCheckState.Available(
                        latestVersion = latestClean,
                        apkDownloadUrl = apkUrl ?: htmlUrl,
                        releasePageUrl = htmlUrl,
                        notes = body,
                    )
                } else {
                    UpdateCheckState.UpToDate(BuildConfig.VERSION_NAME)
                }
            }
        } catch (_: Exception) {
            UpdateCheckState.Error("Sin conexión al comprobar GitHub")
        }
    }

    fun isNewerVersion(remote: String, local: String): Boolean {
        val rParts = remote.split(".", "-", "_").mapNotNull { it.toIntOrNull() }
        val lParts = local.split(".", "-", "_").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(rParts.size, lParts.size)
        for (i in 0 until maxLen) {
            val r = rParts.getOrElse(i) { 0 }
            val l = lParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return false
    }
}
