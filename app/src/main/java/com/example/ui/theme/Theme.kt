package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

private val CockpitColorScheme = darkColorScheme(
    primary = NeonCyan,
    onPrimary = CockpitVoid,
    primaryContainer = Color(0xFF0A3240),
    onPrimaryContainer = NeonCyan,
    secondary = SpotifyGreen,
    onSecondary = CockpitVoid,
    secondaryContainer = Color(0xFF0E3521),
    onSecondaryContainer = Color(0xFF8DF5B3),
    tertiary = NeonMagenta,
    onTertiary = Color.White,
    background = CockpitVoid,
    onBackground = TextPrimary,
    surface = CockpitSurface,
    onSurface = TextPrimary,
    surfaceVariant = CockpitSurfaceElevated,
    onSurfaceVariant = TextSecondary,
    outline = CockpitBorder,
    outlineVariant = Color(0xFF1D2336),
    error = CoralError,
    onError = Color.White,
)

val CockpitShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun SymphonySyncTheme(
    accentColor: Color = NeonCyan,
    content: @Composable () -> Unit,
) {
    val dynamicCockpitScheme = CockpitColorScheme.copy(
        primary = accentColor,
    )
    MaterialTheme(
        colorScheme = dynamicCockpitScheme,
        typography = Typography,
        shapes = CockpitShapes,
        content = content,
    )
}
