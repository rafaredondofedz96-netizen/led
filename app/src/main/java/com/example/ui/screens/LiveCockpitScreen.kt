package com.example.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothConnected
import androidx.compose.material.icons.rounded.BrightnessHigh
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.CoverSyncStyle
import com.example.ui.AppTab
import com.example.ui.MainUiState
import com.example.ui.components.CockpitStatusPill
import com.example.ui.components.QuickColorSwatches
import com.example.ui.components.SymphonyLedStripBar
import com.example.ui.theme.AmberWarn
import com.example.ui.theme.CockpitBorder
import com.example.ui.theme.CockpitSurface
import com.example.ui.theme.CockpitSurfaceElevated
import com.example.ui.theme.CoralError
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.SpotifyGreen
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSecondary

@Composable
fun LiveCockpitScreen(
    state: MainUiState,
    isWidescreen: Boolean,
    onTogglePower: (Boolean) -> Unit,
    onToggleSync: (Boolean) -> Unit,
    onSelectSyncStyle: (CoverSyncStyle) -> Unit,
    onBrightnessChange: (Int) -> Unit,
    onManualColor: (Int) -> Unit,
    onNavigateTab: (AppTab) -> Unit,
) {
    val liveColor by animateColorAsState(
        targetValue = Color(state.playback.liveLedArgb),
        animationSpec = tween(250),
        label = "live_led_color",
    )

    if (isWidescreen) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            HeroSpotifyStage(
                state = state,
                liveColor = liveColor,
                onNavigateTab = onNavigateTab,
                modifier = Modifier
                    .weight(1.1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
            )
            ControlConsoleColumn(
                state = state,
                liveColor = liveColor,
                onTogglePower = onTogglePower,
                onToggleSync = onToggleSync,
                onSelectSyncStyle = onSelectSyncStyle,
                onBrightnessChange = onBrightnessChange,
                onManualColor = onManualColor,
                onNavigateTab = onNavigateTab,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
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
            HeroSpotifyStage(
                state = state,
                liveColor = liveColor,
                onNavigateTab = onNavigateTab,
            )
            ControlConsoleColumn(
                state = state,
                liveColor = liveColor,
                onTogglePower = onTogglePower,
                onToggleSync = onToggleSync,
                onSelectSyncStyle = onSelectSyncStyle,
                onBrightnessChange = onBrightnessChange,
                onManualColor = onManualColor,
                onNavigateTab = onNavigateTab,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun HeroSpotifyStage(
    state: MainUiState,
    liveColor: Color,
    onNavigateTab: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = state.playback.track
    val palette = state.playback.palette
    val primaryColor = Color(palette.primaryArgb)
    val secondaryColor = Color(palette.secondaryArgb)
    val ambientColor = Color(palette.ambientArgb)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                if (state.prefs.powerOn) {
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                liveColor.copy(alpha = 0.24f),
                                Color.Transparent,
                            ),
                            radius = size.maxDimension * 0.72f,
                        )
                    )
                }
            },
        shape = MaterialTheme.shapes.large,
        color = CockpitSurface.copy(alpha = 0.92f),
        border = BorderStroke(1.dp, liveColor.copy(alpha = 0.38f)),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Top Status Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CockpitStatusPill(
                    label = when {
                        state.ble.isConnected ->
                            "${state.ble.connectedName ?: "LED"} • ${state.ble.lastWriteLatencyMs}ms"
                        state.ble.status.name == "CONNECTING" || state.ble.status.name == "DISCOVERING" ->
                            stringResource(R.string.ble_connecting)
                        else -> stringResource(R.string.ble_disconnected)
                    },
                    dotColor = if (state.ble.isConnected) SpotifyGreen else AmberWarn,
                    onClick = { onNavigateTab(AppTab.BLUETOOTH) },
                    modifier = Modifier.testTag("status_ble_pill"),
                )

                val hexString = String.format("#%06X", 0xFFFFFF and state.playback.liveLedArgb)
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = liveColor.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, liveColor.copy(alpha = 0.55f)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(liveColor)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = hexString,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            // Virtual Symphony LED Strip Live Visualizer
            SymphonyLedStripBar(
                primaryColor = if (state.prefs.syncEnabled) liveColor else Color(state.prefs.manualColorArgb),
                secondaryColor = if (state.prefs.syncEnabled) secondaryColor else Color(state.prefs.manualColorArgb),
                ambientColor = if (state.prefs.syncEnabled) ambientColor else Color(state.prefs.manualColorArgb),
                isPowerOn = state.prefs.powerOn,
                brightness = state.prefs.brightness,
            )

            // Album Cover + Track Metadata
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(116.dp)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(18.dp))
                        .background(CockpitSurfaceElevated)
                        .border(1.5.dp, liveColor.copy(alpha = 0.6f), RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!track?.coverLargeUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = track?.coverLargeUrl,
                            contentDescription = "Portada del álbum ${track?.albumName.orEmpty()}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.MusicNote,
                            contentDescription = "Sin portada activa",
                            tint = liveColor,
                            modifier = Modifier.size(46.dp),
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = track?.title ?: stringResource(R.string.no_track_playing),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = track?.artist ?: (state.playback.spotifyStatusMessage
                            ?: stringResource(R.string.no_track_subtitle)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (track != null && track.durationMs > 0) {
                        val frac = (track.progressMs.toFloat() / track.durationMs.toFloat()).coerceIn(0f, 1f)
                        LinearProgressIndicator(
                            progress = { frac },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(RoundedCornerShape(999.dp)),
                            color = liveColor,
                            trackColor = CockpitSurfaceElevated,
                        )
                    }
                }
            }

            // Extracted 3-Color Palette Strip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PaletteChip("Principal", primaryColor, Modifier.weight(1f))
                PaletteChip("Acento", secondaryColor, Modifier.weight(1f))
                PaletteChip("Ambiente", ambientColor, Modifier.weight(1f))
            }

            // Helpful Action Shortcuts when not yet configured
            if (!state.prefs.isSpotifyLoggedIn || !state.ble.isConnected) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (!state.prefs.isSpotifyLoggedIn) {
                        Button(
                            onClick = { onNavigateTab(AppTab.ACCOUNT) },
                            colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("shortcut_spotify_setup"),
                        ) {
                            Text("Conectar Spotify", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (!state.ble.isConnected) {
                        OutlinedButton(
                            onClick = { onNavigateTab(AppTab.BLUETOOTH) },
                            border = BorderStroke(1.dp, NeonCyan),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("shortcut_ble_setup"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Bluetooth,
                                contentDescription = null,
                                tint = NeonCyan,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Conectar LED", color = NeonCyan)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PaletteChip(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = CockpitSurfaceElevated,
        border = BorderStroke(1.dp, CockpitBorder),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlConsoleColumn(
    state: MainUiState,
    liveColor: Color,
    onTogglePower: (Boolean) -> Unit,
    onToggleSync: (Boolean) -> Unit,
    onSelectSyncStyle: (CoverSyncStyle) -> Unit,
    onBrightnessChange: (Int) -> Unit,
    onManualColor: (Int) -> Unit,
    onNavigateTab: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Primary Power + Spotify Live Sync Row (Large Car Touch Targets)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .height(68.dp)
                    .testTag("power_toggle_card")
                    .clickable { onTogglePower(!state.prefs.powerOn) },
                shape = MaterialTheme.shapes.medium,
                color = if (state.prefs.powerOn) liveColor.copy(alpha = 0.18f) else CockpitSurface,
                border = BorderStroke(
                    1.5.dp,
                    if (state.prefs.powerOn) liveColor else CockpitBorder,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.PowerSettingsNew,
                            contentDescription = "Encender o apagar LEDs",
                            tint = if (state.prefs.powerOn) liveColor else CoralError,
                            modifier = Modifier.size(26.dp),
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = if (state.prefs.powerOn) stringResource(R.string.power_on)
                                else stringResource(R.string.power_off),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "Tira Symphony",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextMuted,
                            )
                        }
                    }
                }
            }

            Surface(
                modifier = Modifier
                    .weight(1f)
                    .height(68.dp)
                    .testTag("sync_toggle_card")
                    .clickable {
                        if (!state.prefs.isSpotifyLoggedIn) {
                            onNavigateTab(AppTab.ACCOUNT)
                        } else {
                            onToggleSync(!state.prefs.syncEnabled)
                        }
                    },
                shape = MaterialTheme.shapes.medium,
                color = if (state.prefs.syncEnabled) SpotifyGreen.copy(alpha = 0.16f) else CockpitSurface,
                border = BorderStroke(
                    1.5.dp,
                    if (state.prefs.syncEnabled) SpotifyGreen else CockpitBorder,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Sync,
                            contentDescription = "Sincronizar con portada Spotify",
                            tint = if (state.prefs.syncEnabled) SpotifyGreen else TextSecondary,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = if (state.prefs.syncEnabled) "Sync Vivo" else "Manual",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "Portada Spotify",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextMuted,
                            )
                        }
                    }
                    Switch(
                        checked = state.prefs.syncEnabled,
                        onCheckedChange = {
                            if (!state.prefs.isSpotifyLoggedIn) onNavigateTab(AppTab.ACCOUNT)
                            else onToggleSync(it)
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = SpotifyGreen),
                    )
                }
            }
        }

        // Cover Sync Style Card
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = CockpitSurface,
            border = BorderStroke(1.dp, CockpitBorder),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = liveColor,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Estilo de Color de Portada",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CoverSyncStyle.entries.forEach { style ->
                        val selected = state.prefs.coverSyncStyle == style
                        FilterChip(
                            selected = selected,
                            onClick = { onSelectSyncStyle(style) },
                            label = { Text(style.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = liveColor.copy(alpha = 0.2f),
                                selectedLabelColor = MaterialTheme.colorScheme.onSurface,
                            ),
                            modifier = Modifier.testTag("sync_style_${style.id}"),
                        )
                    }
                }
            }
        }

        // Brightness Card
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = CockpitSurface,
            border = BorderStroke(1.dp, CockpitBorder),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.BrightnessHigh,
                            contentDescription = null,
                            tint = liveColor,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Brillo Interior", style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        text = "${state.prefs.brightness}%",
                        style = MaterialTheme.typography.labelLarge,
                        color = liveColor,
                    )
                }
                Slider(
                    value = state.prefs.brightness.toFloat(),
                    onValueChange = { onBrightnessChange(it.toInt()) },
                    valueRange = 5f..100f,
                    colors = SliderDefaults.colors(
                        thumbColor = liveColor,
                        activeTrackColor = liveColor,
                    ),
                    modifier = Modifier.testTag("live_brightness_slider"),
                )
            }
        }

        // Quick Direct Colors
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = CockpitSurface,
            border = BorderStroke(1.dp, CockpitBorder),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Acceso Rápido (Desactiva Sync)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
                QuickColorSwatches(
                    activeArgb = if (state.prefs.syncEnabled) 0 else state.prefs.manualColorArgb,
                    onSelectColor = onManualColor,
                )
            }
        }
    }
}
