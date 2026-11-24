package com.example

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
    fun `elk led lamp rgb frame matches 9-byte symphony specification`() {
        val packet = LedProtocol.buildColorPacket(
            profile = ProtocolProfile.ELK_LED_LAMP,
            argb = 0xFFFF0080.toInt(),
            brightnessPercent = 100,
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
        )
        assertArrayEquals(
            byteArrayOf(0x69, 0x96.toByte(), 0x05, 0x02, 0x80.toByte(), 0x00, 0x00),
            packet,
        )
    }

    @Test
    fun `auto detect selects expected controller profile`() {
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
