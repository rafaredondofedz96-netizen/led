package com.example.spotify

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.InputStream
import kotlin.math.abs

data class CoverPalette(
    val primaryArgb: Int,
    val secondaryArgb: Int,
    val ambientArgb: Int,
) {
    companion object {
        val Default = CoverPalette(
            primaryArgb = 0xFF00E5FF.toInt(),
            secondaryArgb = 0xFFFF3D8B.toInt(),
            ambientArgb = 0xFF8C52FF.toInt(),
        )
    }
}

object AlbumPaletteExtractor {

    /**
     * Decodes a low-res RGB_565 bitmap directly from stream and extracts 3 vivid
     * Symphony LED colors in < 2ms with minimal RAM usage.
     */
    fun extractFromBytes(bytes: ByteArray): CoverPalette {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val maxDim = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
        var sample = 1
        while (maxDim / sample > 64) {
            sample *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
            ?: return CoverPalette.Default

        return try {
            extractFromBitmap(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    fun extractFromStream(stream: InputStream): CoverPalette =
        extractFromBytes(stream.readBytes())

    private fun extractFromBitmap(bitmap: Bitmap): CoverPalette {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return CoverPalette.Default

        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        // 36 hue buckets (10 degrees each) + neutral fallback accumulator
        val bucketCount = 36
        val bucketWeight = FloatArray(bucketCount)
        val bucketR = FloatArray(bucketCount)
        val bucketG = FloatArray(bucketCount)
        val bucketB = FloatArray(bucketCount)

        var fallbackWeight = 0f
        var fallbackR = 0f
        var fallbackG = 0f
        var fallbackB = 0f

        val hsv = FloatArray(3)
        for (px in pixels) {
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            Color.RGBToHSV(r, g, b, hsv)
            val hue = hsv[0]
            val sat = hsv[1]
            val valBright = hsv[2]

            // Skip near-black and near-white pixels for vibrant car LEDs
            if (valBright < 0.14f) continue
            if (sat < 0.16f || valBright > 0.96f && sat < 0.22f) {
                val fw = valBright * 0.25f
                fallbackWeight += fw
                fallbackR += r * fw
                fallbackG += g * fw
                fallbackB += b * fw
                continue
            }

            // Favor vivid, mid-to-high brightness colors
            val weight = (sat * sat * 2.4f) * (0.4f + valBright * 0.8f)
            val idx = ((hue / 10f).toInt()).coerceIn(0, bucketCount - 1)
            bucketWeight[idx] += weight
            bucketR[idx] += r * weight
            bucketG[idx] += g * weight
            bucketB[idx] += b * weight
        }

        val rankedBuckets = (0 until bucketCount)
            .filter { bucketWeight[it] > 0f }
            .sortedByDescending { bucketWeight[it] }

        if (rankedBuckets.isEmpty()) {
            if (fallbackWeight > 0f) {
                val avg = boostForLed(
                    Color.rgb(
                        (fallbackR / fallbackWeight).toInt().coerceIn(0, 255),
                        (fallbackG / fallbackWeight).toInt().coerceIn(0, 255),
                        (fallbackB / fallbackWeight).toInt().coerceIn(0, 255),
                    )
                )
                return CoverPalette(avg, shiftHue(avg, 45f), shiftHue(avg, -45f))
            }
            return CoverPalette.Default
        }

        val primaryIdx = rankedBuckets.first()
        val primary = bucketToLedColor(primaryIdx, bucketWeight, bucketR, bucketG, bucketB)

        // Pick secondary with at least 40 deg hue separation for dynamic contrast
        val secondaryIdx = rankedBuckets.firstOrNull { idx ->
            hueDistanceDegrees(idx * 10f, primaryIdx * 10f) >= 40f
        } ?: rankedBuckets.getOrNull(1)
        val secondary = if (secondaryIdx != null) {
            bucketToLedColor(secondaryIdx, bucketWeight, bucketR, bucketG, bucketB)
        } else {
            shiftHue(primary, 60f)
        }

        val ambientIdx = rankedBuckets.firstOrNull { idx ->
            idx != primaryIdx && idx != secondaryIdx &&
                hueDistanceDegrees(idx * 10f, primaryIdx * 10f) >= 25f
        }
        val ambient = if (ambientIdx != null) {
            bucketToLedColor(ambientIdx, bucketWeight, bucketR, bucketG, bucketB)
        } else {
            shiftHue(primary, -50f)
        }

        return CoverPalette(
            primaryArgb = primary,
            secondaryArgb = secondary,
            ambientArgb = ambient,
        )
    }

    private fun bucketToLedColor(
        idx: Int,
        w: FloatArray,
        rArr: FloatArray,
        gArr: FloatArray,
        bArr: FloatArray,
    ): Int {
        val weight = w[idx].coerceAtLeast(0.001f)
        val r = (rArr[idx] / weight).toInt().coerceIn(0, 255)
        val g = (gArr[idx] / weight).toInt().coerceIn(0, 255)
        val b = (bArr[idx] / weight).toInt().coerceIn(0, 255)
        return boostForLed(Color.rgb(r, g, b))
    }

    /**
     * Ensures LED colors look rich and saturated on RGB car strips instead of muddy pastel.
     */
    private fun boostForLed(argb: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(argb, hsv)
        if (hsv[1] > 0.12f) {
            hsv[1] = (hsv[1] * 1.22f).coerceIn(0.68f, 1.0f)
        }
        hsv[2] = hsv[2].coerceIn(0.75f, 1.0f)
        return Color.HSVToColor(hsv)
    }

    private fun shiftHue(argb: Int, deltaDeg: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(argb, hsv)
        hsv[0] = ((hsv[0] + deltaDeg) % 360f + 360f) % 360f
        hsv[1] = hsv[1].coerceIn(0.65f, 1.0f)
        hsv[2] = hsv[2].coerceIn(0.70f, 1.0f)
        return Color.HSVToColor(hsv)
    }

    private fun hueDistanceDegrees(h1: Float, h2: Float): Float {
        val diff = abs(h1 - h2)
        return minOf(diff, 360f - diff)
    }

    fun lerpRgb(startArgb: Int, endArgb: Int, fraction: Float): Int {
        val t = fraction.coerceIn(0f, 1f)
        val sr = (startArgb shr 16) and 0xFF
        val sg = (startArgb shr 8) and 0xFF
        val sb = startArgb and 0xFF
        val er = (endArgb shr 16) and 0xFF
        val eg = (endArgb shr 8) and 0xFF
        val eb = endArgb and 0xFF
        val r = (sr + ((er - sr) * t)).toInt().coerceIn(0, 255)
        val g = (sg + ((eg - sg) * t)).toInt().coerceIn(0, 255)
        val b = (sb + ((eb - sb) * t)).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }
}
