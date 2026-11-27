package com.example.spotify

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.InputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

data class CoverPalette(
    val primaryArgb: Int,
    val secondaryArgb: Int,
    val accentArgb: Int = secondaryArgb,
) {
    companion object {
        val Default = CoverPalette(
            primaryArgb = 0xFF00E5FF.toInt(),
            secondaryArgb = 0xFFFF3D8B.toInt(),
            accentArgb = 0xFF8C52FF.toInt(),
        )
    }
}

object AlbumPaletteExtractor {

    /**
     * Decodes a crisp 96x96 ARGB_8888 thumbnail and extracts the 3 most representative
     * cover colors using 36-bin (10-deg) circular HSV histogram clustering.
     * Avoids turning beige/cream/skin into fake orange and preserves true reds/purples/blues.
     */
    fun extractFromBytes(bytes: ByteArray): CoverPalette {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val maxDim = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
        var sample = 1
        while (maxDim / sample > 96) {
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

        // 36 hue bins (10 degrees each) -> fine hue resolution prevents orange/red/pink bleed
        val bucketCount = 36
        val bucketWeight = FloatArray(bucketCount)
        val bucketCos = FloatArray(bucketCount)
        val bucketSin = FloatArray(bucketCount)
        val bucketSat = FloatArray(bucketCount)
        val bucketVal = FloatArray(bucketCount)
        val bucketCountPx = IntArray(bucketCount)

        var fallbackWeight = 0f
        var fallbackR = 0f
        var fallbackG = 0f
        var fallbackB = 0f

        val hsv = FloatArray(3)
        val cx = w * 0.5f
        val cy = h * 0.5f
        val maxDist = (w + h) * 0.40f
        val totalPixels = (w * h).coerceAtLeast(1)

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

                // Skip near-pure black pixels
                if (valBright < 0.08f) continue

                val distNorm = ((abs(x - cx) + abs(y - cy)) / maxDist.coerceAtLeast(1f)).coerceIn(0f, 1f)
                val centerWeight = 1.25f - (0.35f * distNorm)

                // Neutral/cream/beige/gray/white pixels must NOT be boosted into neon orange
                val isNeutralOrBeige = sat < 0.25f || (valBright > 0.94f && sat < 0.28f)
                if (isNeutralOrBeige) {
                    val fw = valBright * centerWeight * 0.30f
                    fallbackWeight += fw
                    fallbackR += r * fw
                    fallbackG += g * fw
                    fallbackB += b * fw
                    continue
                }

                // Balanced area + saturation weighting so dominant album colors always win
                // without tiny neon text stealing the whole palette
                val satBoost = 0.65f + (sat * 1.35f) + (sat * sat * 0.70f)
                val valBoost = 0.45f + (valBright * 0.55f)
                val weight = centerWeight * satBoost * valBoost

                val idx = ((hue / 10f).toInt()).coerceIn(0, bucketCount - 1)
                val rad = hue * (PI / 180.0)
                bucketWeight[idx] += weight
                bucketCos[idx] += (cos(rad) * weight).toFloat()
                bucketSin[idx] += (sin(rad) * weight).toFloat()
                bucketSat[idx] += sat * weight
                bucketVal[idx] += valBright * weight
                bucketCountPx[idx]++
            }
        }

        // Mild circular neighbor smoothing for ranking only (unites 355° & 5° red covers)
        val smoothedScores = FloatArray(bucketCount) { i ->
            val prev = (i + bucketCount - 1) % bucketCount
            val next = (i + 1) % bucketCount
            bucketWeight[i] + 0.32f * (bucketWeight[prev] + bucketWeight[next])
        }

        val minPixelsRequired = (totalPixels / 120).coerceAtLeast(4)
        val rankedBuckets = (0 until bucketCount)
            .filter { bucketWeight[it] > 0f && bucketCountPx[it] >= minPixelsRequired }
            .sortedByDescending { smoothedScores[it] }
            .ifEmpty {
                (0 until bucketCount)
                    .filter { bucketWeight[it] > 0f && bucketCountPx[it] >= 2 }
                    .sortedByDescending { smoothedScores[it] }
            }

        if (rankedBuckets.isEmpty()) {
            if (fallbackWeight > 0f) {
                val avg = Color.rgb(
                    (fallbackR / fallbackWeight).toInt().coerceIn(0, 255),
                    (fallbackG / fallbackWeight).toInt().coerceIn(0, 255),
                    (fallbackB / fallbackWeight).toInt().coerceIn(0, 255),
                )
                val normalized = normalizeMonochromeForLed(avg)
                return CoverPalette(
                    primaryArgb = normalized,
                    secondaryArgb = shiftHue(normalized, 120f, minSat = 0.25f),
                    accentArgb = shiftHue(normalized, 220f, minSat = 0.25f),
                )
            }
            return CoverPalette.Default
        }

        val primaryIdx = rankedBuckets.first()
        val primary = reconstructBinLedColor(
            idx = primaryIdx,
            bucketCount = bucketCount,
            w = bucketWeight,
            cosArr = bucketCos,
            sinArr = bucketSin,
            satArr = bucketSat,
            valArr = bucketVal,
        )

        val primaryScore = smoothedScores[primaryIdx]
        val secondaryIdx = rankedBuckets.firstOrNull { idx ->
            idx != primaryIdx &&
                smoothedScores[idx] >= primaryScore * 0.14f &&
                hueDistanceDegrees(idx * 10f, primaryIdx * 10f) >= 38f
        }
        val secondary = if (secondaryIdx != null) {
            reconstructBinLedColor(
                idx = secondaryIdx,
                bucketCount = bucketCount,
                w = bucketWeight,
                cosArr = bucketCos,
                sinArr = bucketSin,
                satArr = bucketSat,
                valArr = bucketVal,
            )
        } else {
            shiftHue(primary, 34f)
        }

        val accentIdx = rankedBuckets.firstOrNull { idx ->
            idx != primaryIdx &&
                idx != secondaryIdx &&
                smoothedScores[idx] >= primaryScore * 0.10f &&
                hueDistanceDegrees(idx * 10f, primaryIdx * 10f) >= 36f &&
                (secondaryIdx == null || hueDistanceDegrees(idx * 10f, secondaryIdx * 10f) >= 36f)
        }
        val accent = if (accentIdx != null) {
            reconstructBinLedColor(
                idx = accentIdx,
                bucketCount = bucketCount,
                w = bucketWeight,
                cosArr = bucketCos,
                sinArr = bucketSin,
                satArr = bucketSat,
                valArr = bucketVal,
            )
        } else {
            shiftHue(secondary, 42f)
        }

        return CoverPalette(
            primaryArgb = primary,
            secondaryArgb = secondary,
            accentArgb = accent,
        )
    }

    /**
     * Reconstructs exact circular HSV from the winning 10-degree hue bin (plus only immediate
     * tight 10-deg wrap neighbors if they share strong weight) so hues stay faithful without
     * RGB cross-bin graying.
     */
    private fun reconstructBinLedColor(
        idx: Int,
        bucketCount: Int,
        w: FloatArray,
        cosArr: FloatArray,
        sinArr: FloatArray,
        satArr: FloatArray,
        valArr: FloatArray,
    ): Int {
        val prev = (idx + bucketCount - 1) % bucketCount
        val next = (idx + 1) % bucketCount
        val neighborWeight = 0.22f

        val totalW = (w[idx] + w[prev] * neighborWeight + w[next] * neighborWeight).coerceAtLeast(0.001f)
        val sumCos = cosArr[idx] + cosArr[prev] * neighborWeight + cosArr[next] * neighborWeight
        val sumSin = sinArr[idx] + sinArr[prev] * neighborWeight + sinArr[next] * neighborWeight
        val meanHue = ((Math.toDegrees(atan2(sumSin.toDouble(), sumCos.toDouble())).toFloat()) % 360f + 360f) % 360f

        val meanSat = ((satArr[idx] + satArr[prev] * neighborWeight + satArr[next] * neighborWeight) / totalW)
            .coerceIn(0f, 1f)
        val meanVal = ((valArr[idx] + valArr[prev] * neighborWeight + valArr[next] * neighborWeight) / totalW)
            .coerceIn(0f, 1f)

        // Preserve pastel vs vivid distinction while making vivid cover hues pop on car LEDs
        val ledSat = when {
            meanSat < 0.32f -> (meanSat * 1.45f).coerceIn(0.35f, 0.58f)
            else -> meanSat.coerceAtLeast(0.84f)
        }
        val ledVal = meanVal.coerceAtLeast(0.88f)
        return Color.HSVToColor(floatArrayOf(meanHue, ledSat, ledVal))
    }

    private fun normalizeMonochromeForLed(argb: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(argb, hsv)
        hsv[1] = hsv[1].coerceAtMost(0.16f)
        hsv[2] = hsv[2].coerceAtLeast(0.86f)
        return Color.HSVToColor(hsv)
    }

    private fun shiftHue(argb: Int, deltaDeg: Float, minSat: Float = 0.78f): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(argb, hsv)
        hsv[0] = ((hsv[0] + deltaDeg) % 360f + 360f) % 360f
        hsv[1] = hsv[1].coerceIn(minSat, 1.0f)
        hsv[2] = hsv[2].coerceIn(0.86f, 1.0f)
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
