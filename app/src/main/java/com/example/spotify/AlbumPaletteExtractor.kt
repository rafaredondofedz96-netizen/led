package com.example.spotify

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.InputStream
import kotlin.math.abs

data class CoverPalette(
    val primaryArgb: Int,
    val secondaryArgb: Int,
) {
    companion object {
        val Default = CoverPalette(
            primaryArgb = 0xFF00E5FF.toInt(),
            secondaryArgb = 0xFFFF3D8B.toInt(),
        )
    }
}

object AlbumPaletteExtractor {

    /**
     * Decodes a 64x64 RGB_565 thumbnail and extracts ONLY the 2 most predominant
     * vibrant colors from the album cover, avoiding pink/orange distortion on red covers.
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

        // 24 hue bins (15 degrees each) weighted strongly by pixel area so true dominant color wins
        val bucketCount = 24
        val bucketWeight = FloatArray(bucketCount)
        val bucketR = FloatArray(bucketCount)
        val bucketG = FloatArray(bucketCount)
        val bucketB = FloatArray(bucketCount)
        val bucketCountPx = IntArray(bucketCount)

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

            // Skip near-black shadows
            if (valBright < 0.14f) continue

            // Keep neutral grays/whites only as fallback if entire cover is monochrome
            if (sat < 0.24f || (valBright > 0.94f && sat < 0.30f)) {
                val fw = valBright * 0.3f
                fallbackWeight += fw
                fallbackR += r * fw
                fallbackG += g * fw
                fallbackB += b * fw
                continue
            }

            // Area-dominant weighting: pixel count matters most, moderate boost for rich saturation
            val weight = 1.0f + (sat * 1.4f) + (valBright * 0.4f)
            val idx = ((hue / 15f).toInt()).coerceIn(0, bucketCount - 1)
            bucketWeight[idx] += weight
            bucketR[idx] += r * weight
            bucketG[idx] += g * weight
            bucketB[idx] += b * weight
            bucketCountPx[idx]++
        }

        val rankedBuckets = (0 until bucketCount)
            .filter { bucketWeight[it] > 0f && bucketCountPx[it] >= 4 }
            .sortedByDescending { bucketWeight[it] }

        if (rankedBuckets.isEmpty()) {
            if (fallbackWeight > 0f) {
                val avg = normalizeColorForLed(
                    Color.rgb(
                        (fallbackR / fallbackWeight).toInt().coerceIn(0, 255),
                        (fallbackG / fallbackWeight).toInt().coerceIn(0, 255),
                        (fallbackB / fallbackWeight).toInt().coerceIn(0, 255),
                    )
                )
                return CoverPalette(avg, shiftHue(avg, 120f))
            }
            return CoverPalette.Default
        }

        val primaryIdx = rankedBuckets.first()
        val primary = bucketToPureLedColor(primaryIdx, bucketWeight, bucketR, bucketG, bucketB)

        // Only pick a 2nd color if it has meaningful visual presence (>= 18% of primary) and distinct hue
        val primaryWeight = bucketWeight[primaryIdx]
        val secondaryIdx = rankedBuckets.firstOrNull { idx ->
            idx != primaryIdx &&
                bucketWeight[idx] >= primaryWeight * 0.18f &&
                hueDistanceDegrees(idx * 15f, primaryIdx * 15f) >= 45f
        }
        val secondary = if (secondaryIdx != null) {
            bucketToPureLedColor(secondaryIdx, bucketWeight, bucketR, bucketG, bucketB)
        } else {
            primary
        }

        return CoverPalette(
            primaryArgb = primary,
            secondaryArgb = secondary,
        )
    }

    private fun bucketToPureLedColor(
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
        return normalizeColorForLed(Color.rgb(r, g, b))
    }

    /**
     * Preserves true hue (doesn't turn deep red into pink/orange) while ensuring
     * full LED saturation and crisp brightness.
     */
    private fun normalizeColorForLed(argb: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(argb, hsv)
        if (hsv[1] >= 0.25f) {
            // Pull high saturation cleanly without shifting hue toward yellow/pink
            hsv[1] = hsv[1].coerceAtLeast(0.85f)
        }
        hsv[2] = hsv[2].coerceAtLeast(0.88f)
        return Color.HSVToColor(hsv)
    }

    private fun shiftHue(argb: Int, deltaDeg: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(argb, hsv)
        hsv[0] = ((hsv[0] + deltaDeg) % 360f + 360f) % 360f
        hsv[1] = hsv[1].coerceIn(0.80f, 1.0f)
        hsv[2] = hsv[2].coerceIn(0.85f, 1.0f)
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
