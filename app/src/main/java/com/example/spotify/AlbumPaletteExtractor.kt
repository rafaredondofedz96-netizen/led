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
     * Decodes a 72x72 ARGB_8888 thumbnail and extracts the 2 most predominant
     * colors with circular hue smoothing (no 360/0 red split) and accurate saturation.
     */
    fun extractFromBytes(bytes: ByteArray): CoverPalette {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val maxDim = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
        var sample = 1
        while (maxDim / sample > 72) {
            sample *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
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

        // 24 hue bins (15 degrees each) with circular neighbor smoothing
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
        val cx = w * 0.5f
        val cy = h * 0.5f
        val maxDist = (w + h) * 0.35f

        for (y in 0 until h) {
            for (x in 0 until w) {
                val px = pixels[y * w + x]
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                Color.RGBToHSV(r, g, b, hsv)
                val hue = hsv[0]
                val sat = hsv[1]
                val valBright = hsv[2]

                // Skip only near-pure black pixels so dark moody album covers still register
                if (valBright < 0.09f) continue

                val distNorm = ((abs(x - cx) + abs(y - cy)) / maxDist.coerceAtLeast(1f)).coerceIn(0f, 1f)
                val centerWeight = 1.35f - (0.45f * distNorm)

                // Keep low-saturation neutrals in fallback so dull beige/gray doesn't steal vibrant subjects
                if (sat < 0.20f || (valBright > 0.95f && sat < 0.24f)) {
                    val fw = valBright * centerWeight * 0.35f
                    fallbackWeight += fw
                    fallbackR += r * fw
                    fallbackG += g * fw
                    fallbackB += b * fw
                    continue
                }

                // Balanced area + saturation weighting so vivid artwork colors beat dull backgrounds
                val chromaScore = (sat * sat * 2.4f) + (sat * 1.1f)
                val weight = centerWeight * (0.45f + chromaScore) * (0.35f + valBright * 0.65f)
                val idx = ((hue / 15f).toInt()).coerceIn(0, bucketCount - 1)
                bucketWeight[idx] += weight
                bucketR[idx] += r * weight
                bucketG[idx] += g * weight
                bucketB[idx] += b * weight
                bucketCountPx[idx]++
            }
        }

        // Circular 3-bin smoothed score fixes the 360/0 degree wraparound split on red covers
        val smoothedScores = FloatArray(bucketCount) { i ->
            val prev = (i + bucketCount - 1) % bucketCount
            val next = (i + 1) % bucketCount
            bucketWeight[i] + 0.45f * (bucketWeight[prev] + bucketWeight[next])
        }

        val rankedBuckets = (0 until bucketCount)
            .filter { bucketWeight[it] > 0f && bucketCountPx[it] >= 3 }
            .sortedByDescending { smoothedScores[it] }

        if (rankedBuckets.isEmpty()) {
            if (fallbackWeight > 0f) {
                val avg = Color.rgb(
                    (fallbackR / fallbackWeight).toInt().coerceIn(0, 255),
                    (fallbackG / fallbackWeight).toInt().coerceIn(0, 255),
                    (fallbackB / fallbackWeight).toInt().coerceIn(0, 255),
                )
                val normalized = normalizeColorForLed(avg, isMonochrome = true)
                return CoverPalette(normalized, shiftHue(normalized, 120f))
            }
            return CoverPalette.Default
        }

        val primaryIdx = rankedBuckets.first()
        val primary = clusterToPureLedColor(primaryIdx, bucketCount, bucketWeight, bucketR, bucketG, bucketB)

        val primaryScore = smoothedScores[primaryIdx]
        val secondaryIdx = rankedBuckets.firstOrNull { idx ->
            idx != primaryIdx &&
                smoothedScores[idx] >= primaryScore * 0.16f &&
                hueDistanceDegrees(idx * 15f, primaryIdx * 15f) >= 42f
        }
        val secondary = if (secondaryIdx != null) {
            clusterToPureLedColor(secondaryIdx, bucketCount, bucketWeight, bucketR, bucketG, bucketB)
        } else {
            // Subtle harmonic complementary secondary when cover is single-color
            shiftHue(primary, 32f)
        }

        return CoverPalette(
            primaryArgb = primary,
            secondaryArgb = secondary,
        )
    }

    private fun clusterToPureLedColor(
        centerIdx: Int,
        bucketCount: Int,
        w: FloatArray,
        rArr: FloatArray,
        gArr: FloatArray,
        bArr: FloatArray,
    ): Int {
        val prev = (centerIdx + bucketCount - 1) % bucketCount
        val next = (centerIdx + 1) % bucketCount
        // Merge closely adjacent hue neighbors (weight 0.35) to keep hue true across 360/0
        val totalW = (w[centerIdx] + w[prev] * 0.35f + w[next] * 0.35f).coerceAtLeast(0.001f)
        val r = ((rArr[centerIdx] + rArr[prev] * 0.35f + rArr[next] * 0.35f) / totalW).toInt().coerceIn(0, 255)
        val g = ((gArr[centerIdx] + gArr[prev] * 0.35f + gArr[next] * 0.35f) / totalW).toInt().coerceIn(0, 255)
        val b = ((bArr[centerIdx] + bArr[prev] * 0.35f + bArr[next] * 0.35f) / totalW).toInt().coerceIn(0, 255)
        return normalizeColorForLed(Color.rgb(r, g, b), isMonochrome = false)
    }

    /**
     * Preserves true hue without turning deep reds orange/pink or forcing neutral
     * monochrome covers into neon orange.
     */
    private fun normalizeColorForLed(argb: Int, isMonochrome: Boolean): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(argb, hsv)
        if (isMonochrome || hsv[1] < 0.20f) {
            // Keep clean soft-cool white for B&W covers
            hsv[1] = hsv[1].coerceAtMost(0.18f)
            hsv[2] = hsv[2].coerceAtLeast(0.85f)
            return Color.HSVToColor(hsv)
        }
        hsv[1] = hsv[1].coerceAtLeast(0.80f)
        hsv[2] = hsv[2].coerceAtLeast(0.88f)
        return Color.HSVToColor(hsv)
    }

    private fun shiftHue(argb: Int, deltaDeg: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(argb, hsv)
        hsv[0] = ((hsv[0] + deltaDeg) % 360f + 360f) % 360f
        hsv[1] = hsv[1].coerceIn(0.75f, 1.0f)
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
