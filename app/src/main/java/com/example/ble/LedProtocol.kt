package com.example.ble

import kotlin.math.pow
import kotlin.math.roundToInt

enum class ProtocolProfile(
    val id: String,
    val displayName: String,
    val subtitle: String,
) {
    AUTO("AUTO", "Auto (Recomendado)", "Detecta LEDCAR-01 Dual (Tiras+Reposapiés), ELK, BJ_LED o Triones"),
    LEDCAR_01(
        "LEDCAR_01",
        "LEDCAR-01 Dual (7B + 7E)",
        "Envía tiras DMX (7B..BF) y reposapiés RGB (7E..EF) a la vez",
    ),
    LEDCAR_01_ONLY_7B(
        "LEDCAR_01_ONLY_7B",
        "Solo Tiras DMX (7B..BF)",
        "Trama única 7B FF 07 solo para canal de tiras",
    ),
    LEDCAR_01_ALT("LEDCAR_01_ALT", "LEDCAR-01 / LEDDMX Alt (7B FF 04)", "Variante DMX 7B FF 04 01 RGB"),
    ELK_LED_LAMP("ELK_LED_LAMP", "ELK / Reposapiés RGB (7E..EF)", "Módulos 0xFFF3 / canales RGB estándar"),
    BJ_LED("BJ_LED", "BJ_LED / Mohuan (69 96)", "Módulos 0xEE01 / 0xAE01"),
    TRIONES("TRIONES", "Triones / Magic (56..AA)", "Módulos 0xFFD9 / 0xFFE9"),
    SP110E("SP110E", "SP110E Pixel (RGB 1E)", "Controlador SPI Symphony");

    companion object {
        fun fromId(id: String): ProtocolProfile =
            entries.firstOrNull { it.id == id } ?: AUTO
    }
}

enum class ColorOrder(val id: String, val label: String) {
    RGB("RGB", "RGB"),
    GRB("GRB", "GRB"),
    BRG("BRG", "BRG"),
    RBG("RBG", "RBG"),
    GBR("GBR", "GBR"),
    BGR("BGR", "BGR");

    companion object {
        fun fromId(id: String?): ColorOrder =
            entries.firstOrNull { it.id == id } ?: RGB
    }
}

object LedProtocol {

    fun detectProfile(deviceName: String?, characteristicUuid: String?): ProtocolProfile {
        val name = deviceName?.uppercase().orEmpty()
        val uuid = characteristicUuid?.lowercase().orEmpty()
        return when {
            uuid.contains("0000fff3") || uuid.contains("0000fff1") -> ProtocolProfile.ELK_LED_LAMP
            uuid.contains("0000ee01") || uuid.contains("0000ae01") -> ProtocolProfile.BJ_LED
            uuid.contains("0000ffd9") || uuid.contains("0000ffe9") -> ProtocolProfile.TRIONES
            uuid.contains("0000ffe1") -> ProtocolProfile.LEDCAR_01
            name.contains("LEDCAR") || name.contains("LEDDMX") || name.contains("VGETTING") ->
                ProtocolProfile.LEDCAR_01
            name.contains("BJ_LED") || name.contains("MOHUAN") -> ProtocolProfile.BJ_LED
            name.contains("SP110") || name.contains("SP105") -> ProtocolProfile.SP110E
            name.contains("TRIONES") || name.contains("QHM") -> ProtocolProfile.TRIONES
            else -> ProtocolProfile.ELK_LED_LAMP
        }
    }

    /**
     * Returns power wake/off packets.
     * LEDCAR_01 sends BOTH 0x7B (addressable strips) AND 0x7E (door-box footwell/pocket RGB) frames
     * because VGETTING / LEDCAR-01 controllers multiplex their two outputs by start-byte envelope.
     */
    fun buildPowerPackets(profile: ProtocolProfile, on: Boolean): List<ByteArray> {
        val p = if (profile == ProtocolProfile.AUTO) ProtocolProfile.LEDCAR_01 else profile
        val power7B = if (on) {
            listOf(
                byteArrayOf(0x7B, 0xFF.toByte(), 0x04, 0x03, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xBF.toByte()),
                byteArrayOf(0x7B, 0xFF.toByte(), 0x04, 0x01, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xBF.toByte()),
            )
        } else {
            listOf(
                byteArrayOf(0x7B, 0xFF.toByte(), 0x04, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xBF.toByte()),
                byteArrayOf(0x7B, 0xFF.toByte(), 0x04, 0x02, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xBF.toByte()),
            )
        }
        val power7E = if (on) {
            byteArrayOf(0x7E, 0x04, 0x04, 0xF0.toByte(), 0x00, 0x01, 0xFF.toByte(), 0x00, 0xEF.toByte())
        } else {
            byteArrayOf(0x7E, 0x04, 0x04, 0x00, 0x00, 0x00, 0xFF.toByte(), 0x00, 0xEF.toByte())
        }

        return when (p) {
            ProtocolProfile.LEDCAR_01, ProtocolProfile.AUTO -> power7B + listOf(power7E)
            ProtocolProfile.LEDCAR_01_ONLY_7B, ProtocolProfile.LEDCAR_01_ALT -> power7B
            ProtocolProfile.ELK_LED_LAMP -> listOf(power7E)
            ProtocolProfile.BJ_LED -> listOf(
                if (on) byteArrayOf(0x69, 0x96.toByte(), 0x02, 0x01, 0x01)
                else byteArrayOf(0x69, 0x96.toByte(), 0x02, 0x01, 0x00)
            )
            ProtocolProfile.TRIONES -> listOf(
                if (on) byteArrayOf(0xCC.toByte(), 0x23, 0x33)
                else byteArrayOf(0xCC.toByte(), 0x24, 0x33)
            )
            ProtocolProfile.SP110E -> listOf(
                if (on) byteArrayOf(0xFA.toByte(), 0x0E, 0xC7.toByte(), 0xAA.toByte())
                else byteArrayOf(0xB0.toByte(), 0x4F, 0xC2.toByte(), 0xAB.toByte())
            )
        }
    }

    /**
     * Applies LED perceptual gamma + stray channel cleanup so red stays pure red
     * and secondary diode bleed doesn't wash out colors, then maps wire ColorOrder.
     */
    fun mapRgbForStrip(
        argb: Int,
        brightnessPercent: Int,
        colorOrder: ColorOrder = ColorOrder.RGB,
        pureLedCalibration: Boolean = true,
    ): Triple<Int, Int, Int> {
        var r = (argb shr 16) and 0xFF
        var g = (argb shr 8) and 0xFF
        var b = argb and 0xFF

        if (pureLedCalibration) {
            val maxC = maxOf(r, g, b)
            val minC = minOf(r, g, b)
            val isNeutralWhite = (maxC - minC) < 28 && maxC > 160
            if (!isNeutralWhite && maxC > 0) {
                val rf = calibrateChannel(r, maxC)
                val gf = calibrateChannel(g, maxC)
                val bf = calibrateChannel(b, maxC)
                r = rf
                g = gf
                b = bf
            }
        }

        val scale = brightnessPercent.coerceIn(5, 100) / 100f
        val sr = (r * scale).roundToInt().coerceIn(0, 255)
        val sg = (g * scale).roundToInt().coerceIn(0, 255)
        val sb = (b * scale).roundToInt().coerceIn(0, 255)

        return when (colorOrder) {
            ColorOrder.RGB -> Triple(sr, sg, sb)
            ColorOrder.GRB -> Triple(sg, sr, sb)
            ColorOrder.BRG -> Triple(sb, sr, sg)
            ColorOrder.RBG -> Triple(sr, sb, sg)
            ColorOrder.GBR -> Triple(sg, sb, sr)
            ColorOrder.BGR -> Triple(sb, sg, sr)
        }
    }

    private fun calibrateChannel(channel: Int, maxChannel: Int): Int {
        if (channel == 0) return 0
        val ratio = channel.toFloat() / maxChannel.toFloat()
        // Smooth soft-knee suppression + perceptual LED gamma 1.85 so colors stay true on diodes
        if (ratio < 0.11f) return 0
        val kneeFactor = if (ratio < 0.28f) {
            ((ratio - 0.11f) / 0.17f).coerceIn(0f, 1f)
        } else 1f
        val normalized = channel / 255.0
        val gammaCorrected = normalized.pow(1.85) * kneeFactor
        return (gammaCorrected * 255.0).roundToInt().coerceIn(0, 255)
    }

    fun buildColorPacket(
        profile: ProtocolProfile,
        argb: Int,
        brightnessPercent: Int,
        colorOrder: ColorOrder = ColorOrder.RGB,
        pureLedCalibration: Boolean = true,
    ): ByteArray {
        val p = if (profile == ProtocolProfile.AUTO) ProtocolProfile.LEDCAR_01 else profile
        val (c1, c2, c3) = mapRgbForStrip(argb, brightnessPercent, colorOrder, pureLedCalibration)

        return when (p) {
            ProtocolProfile.LEDCAR_01, ProtocolProfile.LEDCAR_01_ONLY_7B, ProtocolProfile.AUTO -> byteArrayOf(
                0x7B,
                0xFF.toByte(),
                0x07,
                c1.toByte(),
                c2.toByte(),
                c3.toByte(),
                0x00,
                0xFF.toByte(),
                0xBF.toByte(),
            )
            ProtocolProfile.LEDCAR_01_ALT -> byteArrayOf(
                0x7B,
                0xFF.toByte(),
                0x04,
                0x01,
                c1.toByte(),
                c2.toByte(),
                c3.toByte(),
                0xFF.toByte(),
                0xBF.toByte(),
            )
            ProtocolProfile.ELK_LED_LAMP -> byteArrayOf(
                0x7E,
                0x07,
                0x05,
                0x03,
                c1.toByte(),
                c2.toByte(),
                c3.toByte(),
                0x10,
                0xEF.toByte(),
            )
            ProtocolProfile.BJ_LED -> byteArrayOf(
                0x69,
                0x96.toByte(),
                0x05,
                0x02,
                c1.toByte(),
                c2.toByte(),
                c3.toByte(),
            )
            ProtocolProfile.TRIONES -> byteArrayOf(
                0x56,
                c1.toByte(),
                c2.toByte(),
                c3.toByte(),
                0x00,
                0xF0.toByte(),
                0xAA.toByte(),
            )
            ProtocolProfile.SP110E -> byteArrayOf(
                c1.toByte(),
                c2.toByte(),
                c3.toByte(),
                0x1E,
            )
        }
    }

    /**
     * Builds the full list of color packets for a controller.
     * For LEDCAR_01 (Dual), sends BOTH 0x7B (addressable strips) AND 0x7E (door-box footwells).
     */
    fun buildColorPackets(
        profile: ProtocolProfile,
        stripArgb: Int,
        footwellArgb: Int = stripArgb,
        brightnessPercent: Int,
        colorOrder: ColorOrder = ColorOrder.RGB,
        pureLedCalibration: Boolean = true,
    ): List<ByteArray> {
        val p = if (profile == ProtocolProfile.AUTO) ProtocolProfile.LEDCAR_01 else profile
        return if (p == ProtocolProfile.LEDCAR_01) {
            val pkt7B = buildColorPacket(
                profile = ProtocolProfile.LEDCAR_01_ONLY_7B,
                argb = stripArgb,
                brightnessPercent = brightnessPercent,
                colorOrder = colorOrder,
                pureLedCalibration = pureLedCalibration,
            )
            val pkt7E = buildColorPacket(
                profile = ProtocolProfile.ELK_LED_LAMP,
                argb = footwellArgb,
                brightnessPercent = brightnessPercent,
                colorOrder = colorOrder,
                pureLedCalibration = pureLedCalibration,
            )
            listOf(pkt7B, pkt7E)
        } else {
            listOf(
                buildColorPacket(
                    profile = p,
                    argb = stripArgb,
                    brightnessPercent = brightnessPercent,
                    colorOrder = colorOrder,
                    pureLedCalibration = pureLedCalibration,
                )
            )
        }
    }

    fun buildBrightnessPacket(profile: ProtocolProfile, brightnessPercent: Int): ByteArray? {
        val p = if (profile == ProtocolProfile.AUTO) ProtocolProfile.LEDCAR_01 else profile
        val br = brightnessPercent.coerceIn(5, 100)
        return when (p) {
            ProtocolProfile.LEDCAR_01,
            ProtocolProfile.LEDCAR_01_ONLY_7B,
            ProtocolProfile.LEDCAR_01_ALT,
            ProtocolProfile.AUTO -> {
                val scaled32 = ((br * 32) / 100).coerceIn(1, 32)
                byteArrayOf(
                    0x7B, 0xFF.toByte(), 0x01, scaled32.toByte(), 0x20, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0xBF.toByte()
                )
            }
            ProtocolProfile.ELK_LED_LAMP -> byteArrayOf(
                0x7E, 0x04, 0x01, br.toByte(), 0x01, 0xFF.toByte(), 0xFF.toByte(), 0x00, 0xEF.toByte()
            )
            ProtocolProfile.SP110E -> byteArrayOf(
                ((br * 255) / 100).toByte(), 0x00, 0x00, 0x2A
            )
            ProtocolProfile.BJ_LED, ProtocolProfile.TRIONES -> null
        }
    }

    fun buildBrightnessPackets(profile: ProtocolProfile, brightnessPercent: Int): List<ByteArray> {
        val p = if (profile == ProtocolProfile.AUTO) ProtocolProfile.LEDCAR_01 else profile
        return if (p == ProtocolProfile.LEDCAR_01) {
            listOfNotNull(
                buildBrightnessPacket(ProtocolProfile.LEDCAR_01_ONLY_7B, brightnessPercent),
                buildBrightnessPacket(ProtocolProfile.ELK_LED_LAMP, brightnessPercent),
            )
        } else {
            listOfNotNull(buildBrightnessPacket(p, brightnessPercent))
        }
    }

    fun toHexString(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02X".format(it) }
}
