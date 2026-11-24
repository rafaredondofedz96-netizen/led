package com.example.ble

import kotlin.math.roundToInt

enum class ProtocolProfile(
    val id: String,
    val displayName: String,
    val subtitle: String,
) {
    AUTO("AUTO", "Auto (Recomendado)", "Detecta por UUID y nombre del módulo BLE"),
    ELK_LED_LAMP("ELK_LED_LAMP", "LED LAMP / Symphony (7E..EF)", "Estándar 0xFFF3 / 0xFFE1 (ELK-BLEDOM, CAR-LED)"),
    BJ_LED("BJ_LED", "BJ_LED / Mohuan (69 96)", "Módulos 0xEE01 / 0xAE01 (BJ_LED, iDeal LED)"),
    TRIONES("TRIONES", "Triones / Magic (56..AA)", "Módulos 0xFFD9 / 0xFFE9 (Triones, HappyLighting)"),
    SP110E("SP110E", "SP110E Pixel (RGB 1E)", "Controlador SPI Symphony 0xFFE1");

    companion object {
        fun fromId(id: String): ProtocolProfile =
            entries.firstOrNull { it.id == id } ?: AUTO
    }
}

data class SymphonyEffect(
    val id: Int,
    val name: String,
    val elkModeByte: Int,
    val bjModeByte: Int,
    val trionesModeByte: Int,
    val previewColors: List<Int>,
)

object LedProtocol {

    val symphonyEffects: List<SymphonyEffect> = listOf(
        SymphonyEffect(0, "Arcoíris Flujo", 0x87, 0x01, 0x25, listOf(0xFFFF0055.toInt(), 0xFF00E5FF.toInt(), 0xFF8C52FF.toInt())),
        SymphonyEffect(1, "Persecución Neón", 0x88, 0x02, 0x26, listOf(0xFF00E5FF.toInt(), 0xFF1DB954.toInt(), 0xFFFFD600.toInt())),
        SymphonyEffect(2, "Onda Symphony", 0x89, 0x03, 0x27, listOf(0xFF8C52FF.toInt(), 0xFFFF3D8B.toInt(), 0xFF00E5FF.toInt())),
        SymphonyEffect(3, "Respiración RGB", 0x80, 0x04, 0x28, listOf(0xFFFF3D8B.toInt(), 0xFF6200EA.toInt())),
        SymphonyEffect(4, "Pulso Coche", 0x8B, 0x05, 0x29, listOf(0xFF00E5FF.toInt(), 0xFF0055FF.toInt())),
        SymphonyEffect(5, "Fuego Deportivo", 0x8C, 0x06, 0x2A, listOf(0xFFFF1744.toInt(), 0xFFFF9100.toInt(), 0xFFFFEA00.toInt())),
        SymphonyEffect(6, "Océano Nocturno", 0x8D, 0x07, 0x2B, listOf(0xFF00B0FF.toInt(), 0xFF1DE9B6.toInt(), 0xFF304FFE.toInt())),
        SymphonyEffect(7, "Estrobo Disco", 0x95, 0x08, 0x30, listOf(0xFFFFFFFF.toInt(), 0xFF00E5FF.toInt(), 0xFFFF3D8B.toInt())),
        SymphonyEffect(8, "Meteoro", 0x90, 0x09, 0x31, listOf(0xFF00E5FF.toInt(), 0xFFFFFFFF.toInt())),
        SymphonyEffect(9, "Cortina Dual", 0x92, 0x0A, 0x32, listOf(0xFFFF3D8B.toInt(), 0xFF00E676.toInt())),
    )

    fun detectProfile(deviceName: String?, characteristicUuid: String?): ProtocolProfile {
        val name = deviceName?.uppercase().orEmpty()
        val uuid = characteristicUuid?.lowercase().orEmpty()
        return when {
            name.contains("BJ_LED") || name.contains("MOHUAN") ||
                uuid.contains("0000ee01") || uuid.contains("0000ae01") -> ProtocolProfile.BJ_LED
            name.contains("SP110") || name.contains("SP105") -> ProtocolProfile.SP110E
            name.contains("TRIONES") || name.contains("QHM") ||
                uuid.contains("0000ffd9") || uuid.contains("0000ffe9") -> ProtocolProfile.TRIONES
            else -> ProtocolProfile.ELK_LED_LAMP
        }
    }

    fun buildPowerPacket(profile: ProtocolProfile, on: Boolean): ByteArray {
        val p = if (profile == ProtocolProfile.AUTO) ProtocolProfile.ELK_LED_LAMP else profile
        return when (p) {
            ProtocolProfile.ELK_LED_LAMP, ProtocolProfile.AUTO -> {
                if (on) byteArrayOf(0x7E, 0x04, 0x04, 0xF0.toByte(), 0x00, 0x01, 0xFF.toByte(), 0x00, 0xEF.toByte())
                else byteArrayOf(0x7E, 0x04, 0x04, 0x00, 0x00, 0x00, 0xFF.toByte(), 0x00, 0xEF.toByte())
            }
            ProtocolProfile.BJ_LED -> {
                if (on) byteArrayOf(0x69, 0x96.toByte(), 0x02, 0x01, 0x01)
                else byteArrayOf(0x69, 0x96.toByte(), 0x02, 0x01, 0x00)
            }
            ProtocolProfile.TRIONES -> {
                if (on) byteArrayOf(0xCC.toByte(), 0x23, 0x33)
                else byteArrayOf(0xCC.toByte(), 0x24, 0x33)
            }
            ProtocolProfile.SP110E -> {
                if (on) byteArrayOf(0xFA.toByte(), 0x0E, 0xC7.toByte(), 0xAA.toByte())
                else byteArrayOf(0xB0.toByte(), 0x4F, 0xC2.toByte(), 0xAB.toByte())
            }
        }
    }

    fun buildColorPacket(
        profile: ProtocolProfile,
        argb: Int,
        brightnessPercent: Int,
    ): ByteArray {
        val p = if (profile == ProtocolProfile.AUTO) ProtocolProfile.ELK_LED_LAMP else profile
        val scale = brightnessPercent.coerceIn(5, 100) / 100f
        val r = (((argb shr 16) and 0xFF) * scale).roundToInt().coerceIn(0, 255)
        val g = (((argb shr 8) and 0xFF) * scale).roundToInt().coerceIn(0, 255)
        val b = ((argb and 0xFF) * scale).roundToInt().coerceIn(0, 255)

        return when (p) {
            ProtocolProfile.ELK_LED_LAMP, ProtocolProfile.AUTO -> byteArrayOf(
                0x7E,
                0x07,
                0x05,
                0x03,
                r.toByte(),
                g.toByte(),
                b.toByte(),
                0x10,
                0xEF.toByte(),
            )
            ProtocolProfile.BJ_LED -> byteArrayOf(
                0x69,
                0x96.toByte(),
                0x05,
                0x02,
                r.toByte(),
                g.toByte(),
                b.toByte(),
            )
            ProtocolProfile.TRIONES -> byteArrayOf(
                0x56,
                r.toByte(),
                g.toByte(),
                b.toByte(),
                0x00,
                0xF0.toByte(),
                0xAA.toByte(),
            )
            ProtocolProfile.SP110E -> byteArrayOf(
                r.toByte(),
                g.toByte(),
                b.toByte(),
                0x1E,
            )
        }
    }

    fun buildBrightnessPacket(profile: ProtocolProfile, brightnessPercent: Int): ByteArray? {
        val p = if (profile == ProtocolProfile.AUTO) ProtocolProfile.ELK_LED_LAMP else profile
        val br = brightnessPercent.coerceIn(0, 100)
        return when (p) {
            ProtocolProfile.ELK_LED_LAMP, ProtocolProfile.AUTO -> byteArrayOf(
                0x7E, 0x04, 0x01, br.toByte(), 0x01, 0xFF.toByte(), 0xFF.toByte(), 0x00, 0xEF.toByte()
            )
            ProtocolProfile.SP110E -> byteArrayOf(
                ((br * 255) / 100).toByte(), 0x00, 0x00, 0x2A
            )
            ProtocolProfile.BJ_LED, ProtocolProfile.TRIONES -> null
        }
    }

    fun buildSpeedPacket(profile: ProtocolProfile, speedPercent: Int): ByteArray? {
        val p = if (profile == ProtocolProfile.AUTO) ProtocolProfile.ELK_LED_LAMP else profile
        val sp = speedPercent.coerceIn(1, 100)
        return when (p) {
            ProtocolProfile.ELK_LED_LAMP, ProtocolProfile.AUTO -> byteArrayOf(
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
        val p = if (profile == ProtocolProfile.AUTO) ProtocolProfile.ELK_LED_LAMP else profile
        // Invert speed for Triones/BJ_LED where 0x01 is fastest
        val invSpeed = (1 + ((100 - speedPercent.coerceIn(1, 100)) * 30 / 100)).coerceIn(1, 31)
        return when (p) {
            ProtocolProfile.ELK_LED_LAMP, ProtocolProfile.AUTO -> byteArrayOf(
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
