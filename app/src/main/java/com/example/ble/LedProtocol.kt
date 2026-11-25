package com.example.ble

import kotlin.math.pow
import kotlin.math.roundToInt

enum class ProtocolProfile(
    val id: String,
    val displayName: String,
    val subtitle: String,
) {
    AUTO("AUTO", "Auto (Recomendado)", "Detecta LEDCAR-01, ELK, BJ_LED o Triones automáticamente"),
    LEDCAR_01("LEDCAR_01", "LEDCAR-01 / LED LAMP (7B..BF)", "Controladores coche LEDCAR-01 / VGETTING (0xFFE1)"),
    LEDCAR_01_ALT("LEDCAR_01_ALT", "LEDCAR-01 / LEDDMX Alt (7B FF 04)", "Variante DMX 7B FF 04 01 RGB para tiras RGBIC"),
    ELK_LED_LAMP("ELK_LED_LAMP", "ELK-BLEDOM / Lotus (7E..EF)", "Módulos 0xFFF3 / 0xFFF1 estándar"),
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

data class SymphonyEffect(
    val id: Int,
    val name: String,
    val ledCarModeByte: Int,
    val elkModeByte: Int,
    val bjModeByte: Int,
    val trionesModeByte: Int,
    val previewColors: List<Int>,
)

object LedProtocol {

    val symphonyEffects: List<SymphonyEffect> = listOf(
        SymphonyEffect(0, "Arcoíris Flujo", 0x01, 0x87, 0x01, 0x25, listOf(0xFFFF0055.toInt(), 0xFF00E5FF.toInt())),
        SymphonyEffect(1, "Persecución Neón", 0x07, 0x88, 0x02, 0x26, listOf(0xFF00E5FF.toInt(), 0xFF1DB954.toInt())),
        SymphonyEffect(2, "Onda Symphony", 0x0D, 0x89, 0x03, 0x27, listOf(0xFF8C52FF.toInt(), 0xFFFF3D8B.toInt())),
        SymphonyEffect(3, "Respiración RGB", 0x12, 0x80, 0x04, 0x28, listOf(0xFFFF3D8B.toInt(), 0xFF6200EA.toInt())),
        SymphonyEffect(4, "Pulso Coche", 0x18, 0x8B, 0x05, 0x29, listOf(0xFF00E5FF.toInt(), 0xFF0055FF.toInt())),
        SymphonyEffect(5, "Fuego Deportivo", 0x22, 0x8C, 0x06, 0x2A, listOf(0xFFFF1744.toInt(), 0xFFFF9100.toInt())),
        SymphonyEffect(6, "Océano Nocturno", 0x28, 0x8D, 0x07, 0x2B, listOf(0xFF00B0FF.toInt(), 0xFF1DE9B6.toInt())),
        SymphonyEffect(7, "Estrobo Disco", 0x35, 0x95, 0x08, 0x30, listOf(0xFFFFFFFF.toInt(), 0xFF00E5FF.toInt())),
        SymphonyEffect(8, "Meteoro", 0x42, 0x90, 0x09, 0x31, listOf(0xFF00E5FF.toInt(), 0xFFFFFFFF.toInt())),
        SymphonyEffect(9, "Cortina Dual", 0x50, 0x92, 0x0A, 0x32, listOf(0xFFFF3D8B.toInt(), 0xFF00E676.toInt())),
    )

    fun detectProfile(deviceName: String?, characteristicUuid: String?): ProtocolProfile {
        val name = deviceName?.uppercase().orEmpty()
        val uuid = characteristicUuid?.lowercase().orEmpty()
        return when {
            name.contains("LEDCAR") || name.contains("LEDDMX") || name.contains("VGETTING") ->
                ProtocolProfile.LEDCAR_01
            name.contains("BJ_LED") || name.contains("MOHUAN") ||
                uuid.contains("0000ee01") || uuid.contains("0000ae01") -> ProtocolProfile.BJ_LED
            name.contains("SP110") || name.contains("SP105") -> ProtocolProfile.SP110E
            name.contains("TRIONES") || name.contains("QHM") ||
                uuid.contains("0000ffd9") || uuid.contains("0000ffe9") -> ProtocolProfile.TRIONES
            uuid.contains("0000ffe1") -> ProtocolProfile.LEDCAR_01
            else -> ProtocolProfile.ELK_LED_LAMP
        }
    }

    /**
     * Returns one or two packets to reliably wake up/turn off LEDCAR-01 & ELK controllers.
     * LEDCAR-01 supports both 0x03 and 0x01 power-on opcodes depending on firmware revision.
     */
    fun buildPowerPackets(profile: ProtocolProfile, on: Boolean): List<ByteArray> {
        val p = if (profile == ProtocolProfile.AUTO) ProtocolProfile.LEDCAR_01 else profile
        return when (p) {
            ProtocolProfile.LEDCAR_01, ProtocolProfile.LEDCAR_01_ALT, ProtocolProfile.AUTO -> {
                if (on) {
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
            }
            ProtocolProfile.ELK_LED_LAMP -> listOf(
                if (on) byteArrayOf(0x7E, 0x04, 0x04, 0xF0.toByte(), 0x00, 0x01, 0xFF.toByte(), 0x00, 0xEF.toByte())
                else byteArrayOf(0x7E, 0x04, 0x04, 0x00, 0x00, 0x00, 0xFF.toByte(), 0x00, 0xEF.toByte())
            )
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
     * on car LED diodes instead of looking pink/orange, then maps wire ColorOrder.
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
                // Suppress low stray green/blue bleed on red/saturated colors + apply LED gamma
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
        // Suppress bleed below 22% of dominant channel so reds don't shift pink/orange
        if (ratio < 0.22f) return 0
        val normalized = channel / 255.0
        val gammaCorrected = normalized.pow(1.65)
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
            ProtocolProfile.LEDCAR_01, ProtocolProfile.AUTO -> byteArrayOf(
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

    fun buildBrightnessPacket(profile: ProtocolProfile, brightnessPercent: Int): ByteArray? {
        val p = if (profile == ProtocolProfile.AUTO) ProtocolProfile.LEDCAR_01 else profile
        val br = brightnessPercent.coerceIn(5, 100)
        return when (p) {
            ProtocolProfile.LEDCAR_01, ProtocolProfile.LEDCAR_01_ALT, ProtocolProfile.AUTO -> {
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

    fun buildSpeedPacket(profile: ProtocolProfile, speedPercent: Int): ByteArray? {
        val p = if (profile == ProtocolProfile.AUTO) ProtocolProfile.LEDCAR_01 else profile
        val sp = speedPercent.coerceIn(1, 100)
        return when (p) {
            ProtocolProfile.LEDCAR_01, ProtocolProfile.LEDCAR_01_ALT, ProtocolProfile.AUTO -> {
                val scaled = ((sp * 32) / 100).coerceIn(1, 32)
                byteArrayOf(
                    0x7B, 0xFF.toByte(), 0x02, scaled.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xBF.toByte()
                )
            }
            ProtocolProfile.ELK_LED_LAMP -> byteArrayOf(
                0x7E, 0x04, 0x02, sp.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00, 0xEF.toByte()
            )
            else -> null
        }
    }

    fun buildEffectPacket(
        profile: ProtocolProfile,
        effect: SymphonyEffect,
        speedPercent: Int,
    ): ByteArray {
        val p = if (profile == ProtocolProfile.AUTO) ProtocolProfile.LEDCAR_01 else profile
        val invSpeed = (1 + ((100 - speedPercent.coerceIn(1, 100)) * 30 / 100)).coerceIn(1, 31)
        return when (p) {
            ProtocolProfile.LEDCAR_01, ProtocolProfile.LEDCAR_01_ALT, ProtocolProfile.AUTO -> byteArrayOf(
                0x7B,
                0xFF.toByte(),
                0x03,
                effect.ledCarModeByte.toByte(),
                0xFF.toByte(),
                0xFF.toByte(),
                0xFF.toByte(),
                0xFF.toByte(),
                0xBF.toByte(),
            )
            ProtocolProfile.ELK_LED_LAMP -> byteArrayOf(
                0x7E,
                0x05,
                0x03,
                effect.elkModeByte.toByte(),
                0x03,
                0xFF.toByte(),
                0xFF.toByte(),
                0x00,
                0xEF.toByte(),
            )
            ProtocolProfile.BJ_LED -> byteArrayOf(
                0x69,
                0x96.toByte(),
                0x03,
                0x03,
                effect.bjModeByte.toByte(),
                invSpeed.coerceIn(1, 10).toByte(),
            )
            ProtocolProfile.TRIONES -> byteArrayOf(
                0xBB.toByte(),
                effect.trionesModeByte.toByte(),
                invSpeed.toByte(),
                0x44,
            )
            ProtocolProfile.SP110E -> byteArrayOf(
                effect.id.toByte(), 0x00, 0x00, 0x2C
            )
        }
    }

    fun toHexString(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02X".format(it) }
}
