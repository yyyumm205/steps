package com.nexthci.ringfitness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RingInfoProtocolTest {
    @Test
    fun parsesEveryDeclaredComponentAndKeepsProbeStatesSeparate() {
        val wire = header(3) + byteArrayOf(
            1, 1, 1, 2, 3,
            2, 1, 1, 1, 2,
            4, 1, 2, 1, 0,
        )
        val info = RingProtocol.parseNotification(wire, 1234) as SensorPacket.Info
        assertEquals(3, info.componentCount)
        assertEquals(listOf(
            InfoComponent(1, 1, 1, 2, 3),
            InfoComponent(2, 1, 1, 1, 2),
            InfoComponent(4, 1, 2, 1, 0),
        ), info.components)
        assertTrue(info.components[0].probed)
        assertTrue(info.components[0].probeOk)
        assertTrue(info.components[1].probed)
        assertFalse(info.components[1].probeOk)
        assertFalse(info.components[2].probed)
        assertFalse(info.components[2].probeOk)
        assertEquals(1234, info.receivedEpochMs)
    }

    @Test
    fun rejectsEveryTruncatedDeclaredComponentList() {
        val wire = header(2) + byteArrayOf(1, 1, 1, 2, 3, 2, 1, 1, 1, 3)
        for (length in 0 until wire.size) {
            assertNull("truncated INFO length=$length", RingProtocol.parseNotification(wire.copyOf(length), 1234))
        }
    }

    @Test
    fun retainsUnknownComponentModelPresenceAndFlagValuesAsUnsignedBytes() {
        val wire = header(1) + byteArrayOf(0xFF.toByte(), 0x80.toByte(), 0xFE.toByte(), 0xFD.toByte(), 0xFC.toByte())
        val info = RingProtocol.parseNotification(wire, 1234) as SensorPacket.Info
        assertEquals(listOf(InfoComponent(255, 128, 254, 253, 252)), info.components)
        assertFalse(info.components.single().probed)
        assertFalse(info.components.single().probeOk)
    }

    @Test
    fun acceptsZeroComponentsAndKeepsFirmwareInformation() {
        val info = RingProtocol.parseNotification(header(0), 1234) as SensorPacket.Info
        assertEquals(0, info.componentCount)
        assertTrue(info.components.isEmpty())
        assertEquals("1.2.3.4", info.firmwareVersion)
    }

    @Test
    fun parsesDeclaredListAndAllowsTrailingExtensionBytesLikeSdk() {
        val wire = header(1) + byteArrayOf(1, 1, 1, 2, 3, 9, 8, 7)
        val info = RingProtocol.parseNotification(wire, 1234) as SensorPacket.Info
        assertEquals(listOf(InfoComponent(1, 1, 1, 2, 3)), info.components)
    }

    @Test
    fun componentCountIsUnsignedAndCannotAuthorizeShortPacket() {
        assertNull(RingProtocol.parseNotification(header(255) + ByteArray(5), 1234))
    }

    private fun header(count: Int): ByteArray = byteArrayOf(0x2A, 2, 1, 1, 1, 2, 3, 4, count.toByte())
}
