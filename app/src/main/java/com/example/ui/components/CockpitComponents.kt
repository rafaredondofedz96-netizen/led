package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.ui.theme.CockpitBorder
import com.example.ui.theme.CockpitSurfaceElevated
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import android.graphics.Color as AndroidColor

@Composable
fun SymphonyLedStripBar(
    primaryColor: Color,
    secondaryColor: Color,
    ambientColor: Color,
    isPowerOn: Boolean,
    brightness: Int,
    modifier: Modifier = Modifier,
) {
    val animPrimary by animateColorAsState(
        targetValue = if (isPowerOn) primaryColor else Color(0xFF1F2433),
        animationSpec = tween(220),
        label = "strip_primary",
    )
    val animSecondary by animateColorAsState(
        targetValue = if (isPowerOn) secondaryColor else Color(0xFF1A1F2B),
        animationSpec = tween(220),
        label = "strip_secondary",
    )
    val animAmbient by animateColorAsState(
        targetValue = if (isPowerOn) ambientColor else Color(0xFF151924),
        animationSpec = tween(220),
        label = "strip_ambient",
    )
    val alphaFactor = if (isPowerOn) (0.35f + (brightness / 100f) * 0.65f) else 0.4f

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(14.dp)
            .clip(RoundedCornerShape(999.dp))
    ) {
        val segments = 18
        val gap = 4.dp.toPx()
        val segWidth = (size.width - gap * (segments - 1)) / segments
        val brush = Brush.horizontalGradient(
            colors = listOf(
                animPrimary.copy(alpha = alphaFactor),
                animSecondary.copy(alpha = alphaFactor),
                animAmbient.copy(alpha = alphaFactor),
                animPrimary.copy(alpha = alphaFactor),
            )
        )
        drawRoundRect(
            brush = brush,
            alpha = 0.22f,
            cornerRadius = CornerRadius(size.height / 2, size.height / 2),
        )
        for (i in 0 until segments) {
            val left = i * (segWidth + gap)
            val frac = i / (segments - 1).toFloat()
            val segColor = when {
                frac < 0.33f -> lerpColor(animPrimary, animSecondary, frac / 0.33f)
                frac < 0.66f -> lerpColor(animSecondary, animAmbient, (frac - 0.33f) / 0.33f)
                else -> lerpColor(animAmbient, animPrimary, (frac - 0.66f) / 0.34f)
            }
            drawRoundRect(
                color = segColor.copy(alpha = alphaFactor),
                topLeft = Offset(left, 2.dp.toPx()),
                size = Size(segWidth, size.height - 4.dp.toPx()),
                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
            )
        }
    }
}

private fun lerpColor(a: Color, b: Color, t: Float): Color {
    val f = t.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * f,
        green = a.green + (b.green - a.green) * f,
        blue = a.blue + (b.blue - a.blue) * f,
        alpha = a.alpha + (b.alpha - a.alpha) * f,
    )
}

@Composable
fun CockpitStatusPill(
    label: String,
    dotColor: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier
            .then(
                if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
            ),
        shape = RoundedCornerShape(999.dp),
        color = CockpitSurfaceElevated,
        border = BorderStroke(1.dp, CockpitBorder),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun RgbColorWheel(
    selectedArgb: Int,
    onColorSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(selectedArgb, hsv)

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .size(210.dp)
                .testTag("rgb_color_wheel")
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val color = pointToColor(offset, size.width.toFloat(), size.height.toFloat())
                        if (color != null) onColorSelected(color)
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        val color = pointToColor(
                            change.position,
                            size.width.toFloat(),
                            size.height.toFloat(),
                        )
                        if (color != null) onColorSelected(color)
                    }
                }
        ) {
            val radius = (minOf(size.width, size.height) / 2f) - 10.dp.toPx()
            val center = Offset(size.width / 2f, size.height / 2f)

            val hueColors = listOf(
                Color.Red,
                Color.Yellow,
                Color.Green,
                Color.Cyan,
                Color.Blue,
                Color.Magenta,
                Color.Red,
            )

            drawCircle(
                brush = Brush.sweepGradient(hueColors, center = center),
                radius = radius,
                center = center,
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White, Color.Transparent),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
            drawCircle(
                color = CockpitBorder,
                radius = radius,
                center = center,
                style = Stroke(width = 2.dp.toPx()),
            )

            // Draw active thumb indicator
            val angleRad = Math.toRadians(hsv[0].toDouble())
            val dist = hsv[1].coerceIn(0f, 1f) * radius
            val thumbX = center.x + (cos(angleRad) * dist).toFloat()
            val thumbY = center.y + (sin(angleRad) * dist).toFloat()
            drawCircle(
                color = Color.White,
                radius = 12.dp.toPx(),
                center = Offset(thumbX, thumbY),
                style = Stroke(width = 3.dp.toPx()),
            )
            drawCircle(
                color = Color(selectedArgb),
                radius = 9.dp.toPx(),
                center = Offset(thumbX, thumbY),
            )
        }
    }
}

private fun pointToColor(pos: Offset, width: Float, height: Float): Int? {
    val cx = width / 2f
    val cy = height / 2f
    val radius = (minOf(width, height) / 2f) - 10f
    if (radius <= 0f) return null
    val dx = pos.x - cx
    val dy = pos.y - cy
    val dist = hypot(dx, dy).coerceAtMost(radius)
    var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
    if (angle < 0f) angle += 360f
    val sat = (dist / radius).coerceIn(0f, 1f)
    return AndroidColor.HSVToColor(floatArrayOf(angle, sat, 1f))
}

@Composable
fun QuickColorSwatches(
    activeArgb: Int,
    onSelectColor: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val presets = listOf(
        0xFFFF1744.toInt(),
        0xFFFF6D00.toInt(),
        0xFFFFEA00.toInt(),
        0xFF00E676.toInt(),
        0xFF00E5FF.toInt(),
        0xFF2979FF.toInt(),
        0xFF8C52FF.toInt(),
        0xFFFF3D8B.toInt(),
        0xFFFFFFFF.toInt(),
    )

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        presets.forEachIndexed { idx, argb ->
            val selected = (activeArgb and 0xFFFFFF) == (argb and 0xFFFFFF)
            Box(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color(argb))
                    .border(
                        width = if (selected) 3.dp else 1.dp,
                        color = if (selected) Color.White else Color.White.copy(alpha = 0.25f),
                        shape = CircleShape,
                    )
                    .testTag("quick_swatch_$idx")
                    .clickable { onSelectColor(argb) }
            )
        }
    }
}
