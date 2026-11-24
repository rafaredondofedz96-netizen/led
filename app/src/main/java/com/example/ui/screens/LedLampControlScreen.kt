package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrightnessHigh
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.ble.LedProtocol
import com.example.ble.SymphonyEffect
import com.example.ui.MainUiState
import com.example.ui.components.QuickColorSwatches
import com.example.ui.components.RgbColorWheel
import com.example.ui.theme.CockpitBorder
import com.example.ui.theme.CockpitSurface
import com.example.ui.theme.CockpitSurfaceElevated
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.TextSecondary

@Composable
fun LedLampControlScreen(
    state: MainUiState,
    isWidescreen: Boolean,
    onManualColor: (Int) -> Unit,
    onBrightnessChange: (Int) -> Unit,
    onSpeedChange: (Int) -> Unit,
    onSelectEffect: (Int) -> Unit,
) {
    val activeColor = Color(state.prefs.manualColorArgb)

    if (isWidescreen) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ColorWheelPanel(
                activeArgb = state.prefs.manualColorArgb,
                activeColor = activeColor,
                brightness = state.prefs.brightness,
                speed = state.prefs.effectSpeed,
                onManualColor = onManualColor,
                onBrightnessChange = onBrightnessChange,
                onSpeedChange = onSpeedChange,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
            )
            SymphonyEffectsGridPanel(
                activeEffectIndex = if (state.prefs.syncEnabled) -1 else state.prefs.activeEffectIndex,
                onSelectEffect = onSelectEffect,
                modifier = Modifier
                    .weight(1.1f)
                    .fillMaxHeight(),
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
            ColorWheelPanel(
                activeArgb = state.prefs.manualColorArgb,
                activeColor = activeColor,
                brightness = state.prefs.brightness,
                speed = state.prefs.effectSpeed,
                onManualColor = onManualColor,
                onBrightnessChange = onBrightnessChange,
                onSpeedChange = onSpeedChange,
            )
            SymphonyEffectsListColumn(
                activeEffectIndex = if (state.prefs.syncEnabled) -1 else state.prefs.activeEffectIndex,
                onSelectEffect = onSelectEffect,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ColorWheelPanel(
    activeArgb: Int,
    activeColor: Color,
    brightness: Int,
    speed: Int,
    onManualColor: (Int) -> Unit,
    onBrightnessChange: (Int) -> Unit,
    onSpeedChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = CockpitSurface,
        border = BorderStroke(1.dp, CockpitBorder),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Palette,
                        contentDescription = null,
                        tint = activeColor,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Rueda RGB (LED LAMP)", style = MaterialTheme.typography.titleLarge)
                }
                Text(
                    text = String.format("#%06X", 0xFFFFFF and activeArgb),
                    style = MaterialTheme.typography.labelMedium,
                    color = activeColor,
                )
            }

            RgbColorWheel(
                selectedArgb = activeArgb,
                onColorSelected = onManualColor,
            )

            QuickColorSwatches(
                activeArgb = activeArgb,
                onSelectColor = onManualColor,
            )

            // Brightness Slider
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.BrightnessHigh,
                            contentDescription = null,
                            tint = NeonCyan,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Brillo", style = MaterialTheme.typography.titleMedium)
                    }
                    Text("$brightness%", style = MaterialTheme.typography.labelLarge, color = NeonCyan)
                }
                Slider(
                    value = brightness.toFloat(),
                    onValueChange = { onBrightnessChange(it.toInt()) },
                    valueRange = 5f..100f,
                    colors = SliderDefaults.colors(thumbColor = NeonCyan, activeTrackColor = NeonCyan),
                    modifier = Modifier.testTag("lamp_brightness_slider"),
                )
            }

            // Speed Slider
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Speed,
                            contentDescription = null,
                            tint = activeColor,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Velocidad Efectos", style = MaterialTheme.typography.titleMedium)
                    }
                    Text("$speed%", style = MaterialTheme.typography.labelLarge, color = activeColor)
                }
                Slider(
                    value = speed.toFloat(),
                    onValueChange = { onSpeedChange(it.toInt()) },
                    valueRange = 5f..100f,
                    colors = SliderDefaults.colors(thumbColor = activeColor, activeTrackColor = activeColor),
                    modifier = Modifier.testTag("lamp_speed_slider"),
                )
            }
        }
    }
}

@Composable
private fun SymphonyEffectsGridPanel(
    activeEffectIndex: Int,
    onSelectEffect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = CockpitSurface,
        border = BorderStroke(1.dp, CockpitBorder),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Modos Symphony Coche", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "Efectos dinámicos tipo LED LAMP",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
            Spacer(modifier = Modifier.height(12.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                items(LedProtocol.symphonyEffects) { effect ->
                    SymphonyEffectCard(
                        effect = effect,
                        selected = effect.id == activeEffectIndex,
                        onClick = { onSelectEffect(effect.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SymphonyEffectsListColumn(
    activeEffectIndex: Int,
    onSelectEffect: (Int) -> Unit,
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
            Text("Modos Symphony Coche", style = MaterialTheme.typography.titleLarge)
            LedProtocol.symphonyEffects.chunked(2).forEach { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    pair.forEach { effect ->
                        SymphonyEffectCard(
                            effect = effect,
                            selected = effect.id == activeEffectIndex,
                            onClick = { onSelectEffect(effect.id) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (pair.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SymphonyEffectCard(
    effect: SymphonyEffect,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gradient = Brush.horizontalGradient(effect.previewColors.map { Color(it) })
    Surface(
        modifier = modifier
            .height(64.dp)
            .testTag("symphony_effect_${effect.id}")
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) Color(0xFF1F2A44) else CockpitSurfaceElevated,
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) Color(effect.previewColors.first()) else CockpitBorder,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(gradient)
            )
            Text(
                text = effect.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}
