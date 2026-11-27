package com.example

import com.example.ble.ColorOrder
import com.example.ble.LedProtocol
import com.example.ble.ProtocolProfile
import com.example.data.CoverSyncStyle
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
    fun `ledcar 01 dual color packets emit both 7B strip and 7E footwell frames`() {
        val packets = LedProtocol.buildColorPackets(
            profile = ProtocolProfile.LEDCAR_01,
            stripArgb = 0xFFFF0000.toInt(),
            footwellArgb = 0xFF0000FF.toInt(),
            brightnessPercent = 100,
            colorOrder = ColorOrder.RGB,
            pureLedCalibration = false,
        )
        assertEquals(2, packets.size)
        assertEquals(0x7B.toByte(), packets[0][0])
        assertEquals(0xFF.toByte(), packets[0][3]) // Red on 7B strip
        assertEquals(0x7E.toByte(), packets[1][0])
        assertEquals(0xFF.toByte(), packets[1][6]) // Blue on 7E footwell
    }

    @Test
    fun `ledcar 01 power packets send 7B and 7E wake and off frames`() {
        val onPackets = LedProtocol.buildPowerPackets(ProtocolProfile.LEDCAR_01, on = true)
        val offPackets = LedProtocol.buildPowerPackets(ProtocolProfile.LEDCAR_01, on = false)
        assertEquals(3, onPackets.size)
        assertEquals(0x03.toByte(), onPackets[0][3])
        assertEquals(0x01.toByte(), onPackets[1][3])
        assertEquals(0x7E.toByte(), onPackets[2][0])
        assertEquals(0xF0.toByte(), onPackets[2][3])

        assertEquals(3, offPackets.size)
        assertEquals(0x00.toByte(), offPackets[0][3])
        assertEquals(0x02.toByte(), offPackets[1][3])
        assertEquals(0x7E.toByte(), offPackets[2][0])
        assertEquals(0x00.toByte(), offPackets[2][3])
    }

    @Test
    fun `pure led calibration strips minor green and blue bleed on red and respects GRB order`() {
        val rgbClean = LedProtocol.mapRgbForStrip(
            argb = 0xFFFA140F.toInt(),
            brightnessPercent = 100,
            colorOrder = ColorOrder.GRB,
            pureLedCalibration = true,
        )
        assertEquals(0, rgbClean.first)
        assertTrue(rgbClean.second > 220)
        assertEquals(0, rgbClean.third)

        // Soft-knee region (G=40 = 16% of 250) transitions smoothly without hard jump
        val rgbSoftKnee = LedProtocol.mapRgbForStrip(
            argb = 0xFFFA281E.toInt(),
            brightnessPercent = 100,
            colorOrder = ColorOrder.GRB,
            pureLedCalibration = true,
        )
        assertTrue(rgbSoftKnee.first in 1..10)
        assertTrue(rgbSoftKnee.second > 220)
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
    fun `auto detect prioritizes characteristic uuid for dual LEDCAR controllers`() {
        assertEquals(
            ProtocolProfile.LEDCAR_01,
            LedProtocol.detectProfile("LEDCAR-01-0BE6", "0000ffe1-0000-1000-8000-00805f9b34fb"),
        )
        assertEquals(
            ProtocolProfile.ELK_LED_LAMP,
            LedProtocol.detectProfile("LEDCAR-01-007E", "0000fff3-0000-1000-8000-00805f9b34fb"),
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
            ProtocolProfile.TRIONES,
            LedProtocol.detectProfile("QHM-1234", "0000ffd9-0000-1000-8000-00805f9b34fb"),
        )
    }

    @Test
    fun `ledcar 01 brightness packets emit both 7B and 7E restore frames`() {
        val brightnessPkts = LedProtocol.buildBrightnessPackets(ProtocolProfile.LEDCAR_01, 50)
        assertEquals(2, brightnessPkts.size)
        assertEquals(0x7B.toByte(), brightnessPkts[0][0])
        assertEquals(0x01.toByte(), brightnessPkts[0][2])
        assertEquals(16.toByte(), brightnessPkts[0][3])
        assertEquals(0x7E.toByte(), brightnessPkts[1][0])
        assertEquals(50.toByte(), brightnessPkts[1][3])
    }

    @Test
    fun `saved app prefs v1_6_0 defaults and expanded cover styles are configured`() {
        val defaults = com.example.data.SavedAppPrefs()
        assertTrue(defaults.autoConnectBle)
        assertTrue(defaults.autoOpenOnCarStart)
        assertTrue(defaults.autoEnableBluetooth)
        assertFalse(defaults.manualEffectActive)
        assertEquals(CoverSyncStyle.PRIMARY_BREATHING, defaults.coverSyncStyle)
        assertTrue(CoverSyncStyle.entries.size >= 9)
    }

    @Test
    fun `github update checker normalizes url and compares semver`() {
        assertEquals(
            "rafa/SymphonySync",
            GitHubUpdateChecker.normalizeRepoInput("https://github.com/rafa/SymphonySync.git"),
        )
        assertTrue(GitHubUpdateChecker.isNewerVersion("1.6.0", "1.5.0"))
        assertFalse(GitHubUpdateChecker.isNewerVersion("1.6.0", "1.6.0"))
        assertFalse(GitHubUpdateChecker.isNewerVersion("1.4.0", "1.5.0"))
    }
}
