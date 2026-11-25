package com.example

import com.example.ble.ColorOrder
import com.example.ble.LedProtocol
import com.example.ble.ProtocolProfile
import com.example.update.GitHubUpdateChecker
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun `ledcar 01 rgb frame matches 7B FF 07 specification`() {
        val packet = LedProtocol.buildColorPacket(
            profile = ProtocolProfile.LEDCAR_01,
            argb = 0xFFFF0000.toInt(),
            brightnessPercent = 100,
            colorOrder = ColorOrder.RGB,
            pureLedCalibration = true,
        )
        assertArrayEquals(
            byteArrayOf(0x7B, 0xFF.toByte(), 0x07, 0xFF.toByte(), 0x00, 0x00, 0x00, 0xFF.toByte(), 0xBF.toByte()),
            packet,
        )
    }

    @Test
    fun `ledcar 01 power packets send dual wake and off frames`() {
        val onPackets = LedProtocol.buildPowerPackets(ProtocolProfile.LEDCAR_01, on = true)
        val offPackets = LedProtocol.buildPowerPackets(ProtocolProfile.LEDCAR_01, on = false)
        assertEquals(2, onPackets.size)
        assertEquals(0x03.toByte(), onPackets[0][3])
        assertEquals(0x01.toByte(), onPackets[1][3])
        assertEquals(2, offPackets.size)
        assertEquals(0x00.toByte(), offPackets[0][3])
        assertEquals(0x02.toByte(), offPackets[1][3])
    }

    @Test
    fun `pure led calibration strips minor green and blue bleed on red and respects GRB order`() {
        // Red with slight warm/pink bleed (R=250, G=35, B=30 -> <22% of maxChannel)
        val rgb = LedProtocol.mapRgbForStrip(
            argb = 0xFFFA231E.toInt(),
            brightnessPercent = 100,
            colorOrder = ColorOrder.GRB,
            pureLedCalibration = true,
        )
        // GRB order: first is Green (0), second is Red (>220), third is Blue (0)
        assertEquals(0, rgb.first)
        assertTrue(rgb.second > 220)
        assertEquals(0, rgb.third)
    }

    @Test
    fun `elk led lamp rgb frame matches 9-byte symphony specification`() {
        val packet = LedProtocol.buildColorPacket(
            profile = ProtocolProfile.ELK_LED_LAMP,
            argb = 0xFFFF0080.toInt(),
            brightnessPercent = 100,
            pureLedCalibration = false,
        )
        assertArrayEquals(
            byteArrayOf(0x7E, 0x07, 0x05, 0x03, 0xFF.toByte(), 0x00, 0x80.toByte(), 0x10, 0xEF.toByte()),
            packet,
        )
    }

    @Test
    fun `bj led rgb frame uses 69 96 header and brightness scaling`() {
        val packet = LedProtocol.buildColorPacket(
            profile = ProtocolProfile.BJ_LED,
            argb = 0xFFFF0000.toInt(),
            brightnessPercent = 50,
            pureLedCalibration = false,
        )
        assertArrayEquals(
            byteArrayOf(0x69, 0x96.toByte(), 0x05, 0x02, 0x80.toByte(), 0x00, 0x00),
            packet,
        )
    }

    @Test
    fun `auto detect selects expected controller profile`() {
        assertEquals(
            ProtocolProfile.LEDCAR_01,
            LedProtocol.detectProfile("LEDCAR-01-0BE6", "0000ffe1-0000-1000-8000-00805f9b34fb"),
        )
        assertEquals(
            ProtocolProfile.LEDCAR_01,
            LedProtocol.detectProfile("LEDCAR-01-007E", null),
        )
        assertEquals(
            ProtocolProfile.BJ_LED,
            LedProtocol.detectProfile("BJ_LED_CAR", "0000ee01-0000-1000-8000-00805f9b34fb"),
        )
        assertEquals(
            ProtocolProfile.ELK_LED_LAMP,
            LedProtocol.detectProfile("ELK-BLEDOM", "0000fff3-0000-1000-8000-00805f9b34fb"),
        )
        assertEquals(
            ProtocolProfile.TRIONES,
            LedProtocol.detectProfile("QHM-1234", "0000ffd9-0000-1000-8000-00805f9b34fb"),
        )
    }

    @Test
    fun `github update checker normalizes url and compares semver`() {
        assertEquals(
            "rafa/SymphonySync",
            GitHubUpdateChecker.normalizeRepoInput("https://github.com/rafa/SymphonySync.git"),
        )
        assertTrue(GitHubUpdateChecker.isNewerVersion("1.3.0", "1.2.0"))
        assertFalse(GitHubUpdateChecker.isNewerVersion("1.2.0", "1.2.0"))
        assertFalse(GitHubUpdateChecker.isNewerVersion("1.1.9", "1.2.0"))
    }
}
