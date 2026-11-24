package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.R
import com.example.spotify.SpotifyRepository
import com.example.ui.MainUiState
import com.example.update.UpdateCheckState
import com.example.ui.theme.CockpitBorder
import com.example.ui.theme.CockpitSurface
import com.example.ui.theme.CockpitSurfaceElevated
import com.example.ui.theme.CoralError
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.SpotifyGreen
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSecondary

@Composable
fun SpotifyAccountScreen(
    state: MainUiState,
    isWidescreen: Boolean,
    onBuildAuthUrl: (String, String) -> String?,
    onSubmitManualCode: (String, String, String) -> Unit,
    onSelectPollInterval: (Long) -> Unit,
    onLogout: (Boolean) -> Unit,
    onCheckGithubUpdates: (String) -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var clientId by remember { mutableStateOf(state.prefs.clientId) }
    var clientSecret by remember { mutableStateOf(state.prefs.clientSecret) }
    var secretVisible by remember { mutableStateOf(false) }
    var showManualPaste by remember { mutableStateOf(false) }
    var manualCallbackText by remember { mutableStateOf("") }

    LaunchedEffect(state.prefs.clientId, state.prefs.clientSecret) {
        if (clientId.isBlank() && state.prefs.clientId.isNotBlank()) {
            clientId = state.prefs.clientId
        }
        if (clientSecret.isBlank() && state.prefs.clientSecret.isNotBlank()) {
            clientSecret = state.prefs.clientSecret
        }
    }

    val openSpotifyBrowser = {
        val url = onBuildAuthUrl(clientId, clientSecret)
        if (url != null) {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }.onFailure {
                Toast.makeText(context, "No se encontró navegador para abrir Spotify", Toast.LENGTH_LONG).show()
            }
        }
    }

    if (isWidescreen) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CredentialsCard(
                state = state,
                clientId = clientId,
                onClientIdChange = { clientId = it },
                clientSecret = clientSecret,
                onClientSecretChange = { clientSecret = it },
                secretVisible = secretVisible,
                onToggleSecretVisible = { secretVisible = !secretVisible },
                showManualPaste = showManualPaste,
                onToggleManualPaste = { showManualPaste = !showManualPaste },
                manualCallbackText = manualCallbackText,
                onManualCallbackChange = { manualCallbackText = it },
                onConnectClick = openSpotifyBrowser,
                onSubmitManual = { onSubmitManualCode(clientId, clientSecret, manualCallbackText) },
                onLogout = onLogout,
                modifier = Modifier
                    .weight(1.2f)
                    .verticalScroll(rememberScrollState()),
            )
            GuideAndPerformanceColumn(
                state = state,
                onSelectPollInterval = onSelectPollInterval,
                onCheckGithubUpdates = onCheckGithubUpdates,
                onCopyRedirectUri = {
                    clipboard.setText(AnnotatedString(SpotifyRepository.REDIRECT_URI))
                    Toast.makeText(context, R.string.uri_copied, Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier
                    .weight(0.9f)
                    .verticalScroll(rememberScrollState()),
            )
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CredentialsCard(
                state = state,
                clientId = clientId,
                onClientIdChange = { clientId = it },
                clientSecret = clientSecret,
                onClientSecretChange = { clientSecret = it },
                secretVisible = secretVisible,
                onToggleSecretVisible = { secretVisible = !secretVisible },
                showManualPaste = showManualPaste,
                onToggleManualPaste = { showManualPaste = !showManualPaste },
                manualCallbackText = manualCallbackText,
                onManualCallbackChange = { manualCallbackText = it },
                onConnectClick = openSpotifyBrowser,
                onSubmitManual = { onSubmitManualCode(clientId, clientSecret, manualCallbackText) },
                onLogout = onLogout,
            )
            GuideAndPerformanceColumn(
                state = state,
                onSelectPollInterval = onSelectPollInterval,
                onCheckGithubUpdates = onCheckGithubUpdates,
                onCopyRedirectUri = {
                    clipboard.setText(AnnotatedString(SpotifyRepository.REDIRECT_URI))
                    Toast.makeText(context, R.string.uri_copied, Toast.LENGTH_SHORT).show()
                },
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun CredentialsCard(
    state: MainUiState,
    clientId: String,
    onClientIdChange: (String) -> Unit,
    clientSecret: String,
    onClientSecretChange: (String) -> Unit,
    secretVisible: Boolean,
    onToggleSecretVisible: () -> Unit,
    showManualPaste: Boolean,
    onToggleManualPaste: () -> Unit,
    manualCallbackText: String,
    onManualCallbackChange: (String) -> Unit,
    onConnectClick: () -> Unit,
    onSubmitManual: () -> Unit,
    onLogout: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = CockpitSurface,
        border = BorderStroke(
            1.dp,
            if (state.prefs.isSpotifyLoggedIn) SpotifyGreen.copy(alpha = 0.6f) else CockpitBorder,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = if (state.prefs.isSpotifyLoggedIn) Icons.Rounded.CheckCircle else Icons.Rounded.Key,
                    contentDescription = null,
                    tint = if (state.prefs.isSpotifyLoggedIn) SpotifyGreen else NeonCyan,
                )
                Column {
                    Text(stringResource(R.string.spotify_setup_title), style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = if (state.prefs.isSpotifyLoggedIn) {
                            val name = state.prefs.spotifyUserName.ifBlank { "Usuario Spotify" }
                            "Conectado como $name • Sesión guardada"
                        } else {
                            "Tu ID, Secret y sesión quedan guardados en la radio"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state.prefs.isSpotifyLoggedIn) SpotifyGreen else TextSecondary,
                    )
                }
            }

            OutlinedTextField(
                value = clientId,
                onValueChange = onClientIdChange,
                label = { Text(stringResource(R.string.spotify_client_id)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("spotify_client_id_input"),
            )

            OutlinedTextField(
                value = clientSecret,
                onValueChange = onClientSecretChange,
                label = { Text(stringResource(R.string.spotify_client_secret)) },
                singleLine = true,
                visualTransformation = if (secretVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = onToggleSecretVisible) {
                        Icon(
                            imageVector = if (secretVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = "Mostrar u ocultar Client Secret",
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("spotify_client_secret_input"),
            )

            if (!state.playback.authError.isNullOrBlank()) {
                Text(
                    text = state.playback.authError,
                    style = MaterialTheme.typography.bodyMedium,
                    color = CoralError,
                )
            }
            if (!state.playback.authFeedback.isNullOrBlank()) {
                Text(
                    text = state.playback.authFeedback,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SpotifyGreen,
                )
            }

            Button(
                onClick = onConnectClick,
                enabled = !state.playback.isAuthBusy,
                colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("spotify_connect_button"),
            ) {
                if (state.playback.isAuthBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Color.Black,
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                } else {
                    Icon(
                        imageVector = Icons.Rounded.OpenInBrowser,
                        contentDescription = null,
                        tint = Color.Black,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = if (state.prefs.isSpotifyLoggedIn) "Reconectar Spotify"
                    else stringResource(R.string.spotify_connect_btn),
                    color = Color.Black,
                    fontWeight = FontWeight.Bold,
                )
            }

            TextButton(
                onClick = onToggleManualPaste,
                modifier = Modifier.testTag("toggle_manual_callback_btn"),
            ) {
                Text(
                    text = if (showManualPaste) "Ocultar pegado manual"
                    else "¿La radio no volvió automáticamente? Pegar URL o código",
                    color = NeonCyan,
                )
            }

            AnimatedVisibility(visible = showManualPaste) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = manualCallbackText,
                        onValueChange = onManualCallbackChange,
                        label = { Text("Pega symphonysync://callback?code=...") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("manual_callback_input"),
                    )
                    OutlinedButton(
                        onClick = onSubmitManual,
                        enabled = manualCallbackText.isNotBlank() && !state.playback.isAuthBusy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("manual_callback_submit"),
                    ) {
                        Text("Validar y Guardar Sesión")
                    }
                }
            }

            if (state.prefs.isSpotifyLoggedIn || state.prefs.hasSpotifyCredentials) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (state.prefs.isSpotifyLoggedIn) {
                        OutlinedButton(
                            onClick = { onLogout(false) },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("spotify_logout_session_btn"),
                        ) {
                            Icon(Icons.Rounded.Logout, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Cerrar Sesión")
                        }
                    }
                    OutlinedButton(
                        onClick = { onLogout(true) },
                        border = BorderStroke(1.dp, CoralError.copy(alpha = 0.7f)),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("spotify_clear_all_btn"),
                    ) {
                        Icon(
                            Icons.Rounded.DeleteOutline,
                            contentDescription = null,
                            tint = CoralError,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Borrar Todo", color = CoralError)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GuideAndPerformanceColumn(
    state: MainUiState,
    onSelectPollInterval: (Long) -> Unit,
    onCheckGithubUpdates: (String) -> Unit,
    onCopyRedirectUri: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = CockpitSurface,
            border = BorderStroke(1.dp, CockpitBorder),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(stringResource(R.string.spotify_redirect_label), style = MaterialTheme.typography.titleMedium)
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = CockpitSurfaceElevated,
                    border = BorderStroke(1.dp, CockpitBorder),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = SpotifyRepository.REDIRECT_URI,
                            style = MaterialTheme.typography.labelMedium,
                            color = NeonCyan,
                        )
                        IconButton(
                            onClick = onCopyRedirectUri,
                            modifier = Modifier.testTag("copy_redirect_uri_btn"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ContentCopy,
                                contentDescription = stringResource(R.string.copy_uri),
                                tint = NeonCyan,
                            )
                        }
                    }
                }
                Text(
                    text = "1. Entra a developer.spotify.com/dashboard\n" +
                        "2. Crea una app y pega el Redirect URI de arriba\n" +
                        "3. Copia tu Client ID y Client Secret aquí y pulsa Conectar.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
            }
        }

        Surface(
            shape = MaterialTheme.shapes.large,
            color = CockpitSurface,
            border = BorderStroke(1.dp, CockpitBorder),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Speed, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Latencia y Consumo Spotify", style = MaterialTheme.typography.titleMedium)
                }
                val options = listOf(
                    800L to "0.8s (Ultra)",
                    1200L to "1.2s (Óptimo)",
                    2500L to "2.5s (Ahorro)",
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    options.forEach { (ms, label) ->
                        FilterChip(
                            selected = state.prefs.pollIntervalMs == ms,
                            onClick = { onSelectPollInterval(ms) },
                            label = { Text(label) },
                            modifier = Modifier.testTag("poll_interval_$ms"),
                        )
                    }
                }
                Text(
                    text = "SymphonySync v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextMuted,
                )
            }
        }

        GitHubUpdatesCard(
            savedRepo = state.prefs.githubRepo,
            updateState = state.updateState,
            onCheckUpdates = onCheckGithubUpdates,
        )

        Ts10BackgroundCard()
    }
}

@Composable
private fun GitHubUpdatesCard(
    savedRepo: String,
    updateState: UpdateCheckState,
    onCheckUpdates: (String) -> Unit,
) {
    val context = LocalContext.current
    var repoInput by remember { mutableStateOf(savedRepo) }

    LaunchedEffect(savedRepo) {
        if (repoInput.isBlank() && savedRepo.isNotBlank()) {
            repoInput = savedRepo
        }
    }

    Surface(
        shape = MaterialTheme.shapes.large,
        color = CockpitSurface,
        border = BorderStroke(
            1.dp,
            if (updateState is UpdateCheckState.Available) SpotifyGreen else CockpitBorder,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.SystemUpdate,
                    contentDescription = null,
                    tint = if (updateState is UpdateCheckState.Available) SpotifyGreen else NeonCyan,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Buscar Nueva Versión (GitHub)", style = MaterialTheme.typography.titleMedium)
            }

            OutlinedTextField(
                value = repoInput,
                onValueChange = { repoInput = it },
                label = { Text("Repositorio (usuario/repo o URL)") },
                placeholder = { Text("tuusuario/SymphonySync") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("github_repo_input"),
            )

            OutlinedButton(
                onClick = { onCheckUpdates(repoInput) },
                enabled = repoInput.isNotBlank() && updateState !is UpdateCheckState.Checking,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("check_github_update_btn"),
            ) {
                if (updateState is UpdateCheckState.Checking) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Comprobando GitHub…")
                } else {
                    Text("Buscar nueva versión")
                }
            }

            when (updateState) {
                is UpdateCheckState.Available -> {
                    Text(
                        text = "¡Nueva versión v${updateState.latestVersion} disponible!",
                        style = MaterialTheme.typography.titleMedium,
                        color = SpotifyGreen,
                    )
                    Button(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(updateState.apkDownloadUrl))
                                )
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("download_apk_btn"),
                    ) {
                        Text("Descargar APK v${updateState.latestVersion}", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
                is UpdateCheckState.UpToDate -> {
                    Text(
                        text = "Ya tienes la última versión (v${updateState.currentVersion}).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SpotifyGreen,
                    )
                }
                is UpdateCheckState.Error -> {
                    Text(
                        text = updateState.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = CoralError,
                    )
                }
                else -> {
                    Text(
                        text = "Al subir tus cambios a GitHub, GitHub Actions compila el .apk automáticamente en Releases para bajarlo desde tu radio.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun Ts10BackgroundCard() {
    val context = LocalContext.current
    val pm = remember(context) { context.getSystemService(Context.POWER_SERVICE) as? PowerManager }
    val isUnrestricted = remember(pm, context.packageName) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        } else true
    }

    Surface(
        shape = MaterialTheme.shapes.large,
        color = CockpitSurface,
        border = BorderStroke(1.dp, if (isUnrestricted) SpotifyGreen.copy(alpha = 0.5f) else CockpitBorder),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.BatteryChargingFull,
                    contentDescription = null,
                    tint = if (isUnrestricted) SpotifyGreen else NeonCyan,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Segundo Plano Radio TS10", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                text = if (isUnrestricted) {
                    "Sin restricciones de batería: los LED siguen reaccionando al cambiar a Maps, Waze o Spotify."
                } else {
                    "En radios TS10, desactiva el ahorro de batería para que Android no pause los LED al cambiar de app."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (isUnrestricted) SpotifyGreen else TextSecondary,
            )
            if (!isUnrestricted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("ts10_battery_settings_btn"),
                ) {
                    Text("Permitir siempre en segundo plano (TS10)")
                }
            }
            Text(
                text = "Para actualizar la APK en tu radio TS10 cuando hagas cambios aquí, exporta/genera la nueva APK e instálala encima: conservará tu cuenta Spotify y tu tira LED.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
            )
        }
    }
}
