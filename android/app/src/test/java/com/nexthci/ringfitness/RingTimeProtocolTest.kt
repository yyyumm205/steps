package com.nexthci.ringfitness

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RingTimeProtocolTest {
    @Test
    fun commandsMatchReferenceSdkWireBytes() {
        assertArrayEquals(
            byteArrayOf(0x23, 0x00, 0xE3.toByte(), 0x19, 0xD0.toByte(), 0xEB.toByte(), 0x9D.toByte(), 1, 0, 0),
            RingProtocol.buildTimeSet(1_777_777_777_123),
        )
        assertArrayEquals(byteArrayOf(0x23, 0x01), RingProtocol.buildTimeGet())
    }

    @Test
    fun timeSetPreservesRepresentableBoundaryValues() {
        assertArrayEquals(byteArrayOf(0x23, 0, 0, 0, 0, 0, 0, 0, 0, 0), RingProtocol.buildTimeSet(0))
        assertArrayEquals(
            byteArrayOf(0x23, 0, -1, -1, -1, -1, -1, -1, -1, 0x7F),
            RingProtocol.buildTimeSet(Long.MAX_VALUE),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun timeSetRejectsNegativeTime() {
        RingProtocol.buildTimeSet(-1)
    }

    @Test
    fun statusPreservesBoth64BitAnchorsAndPhoneReceiptTime() {
        val wire = byteArrayOf(
            0x23, 2, 1, 0xE3.toByte(), 0x19, 0xD0.toByte(), 0xEB.toByte(), 0x9D.toByte(), 1, 0, 0,
            8, 7, 6, 5, 4, 3, 2, 1,
        )
        assertEquals(
            SensorPacket.TimeStatus(true, 1_777_777_777_123, 0x0102030405060708, 1_777_777_777_300),
            RingProtocol.parseNotification(wire, 1_777_777_777_300),
        )
    }

    @Test
    fun unsyncedStatusPreservesZeroEpochAsUnknown() {
        assertEquals(SensorPacket.TimeStatus(false, 0, 123_456, 999),
            RingProtocol.parseNotification(status(synced = 0, unix = 0, uptime = 123_456), 999))
    }

    @Test
    fun statusRejectsTruncationAndUndocumentedExtraBytes() {
        val wire = status()
        for (size in 0 until wire.size) assertNull(RingProtocol.parseNotification(wire.copyOf(size), 999))
        assertNull(RingProtocol.parseNotification(wire + byteArrayOf(0), 999))
    }

    @Test
    fun statusRejectsUnknownFlagAndOverflowingUnsignedFields() {
        assertNull(RingProtocol.parseNotification(status(synced = 2), 999))
        assertNull(RingProtocol.parseNotification(status(synced = 255), 999))
        assertNull(RingProtocol.parseNotification(status(unix = Long.MIN_VALUE), 999))
        assertNull(RingProtocol.parseNotification(status(uptime = Long.MIN_VALUE), 999))
        assertNull(RingProtocol.parseNotification(status(unix = -1, uptime = -1), 999))
    }

    @Test
    fun onlyTimeStatusSubcommandProducesClockEvidence() {
        assertNull(RingProtocol.parseNotification(status().apply { this[1] = 0 }, 999))
        assertNull(RingProtocol.parseNotification(status().apply { this[1] = 1 }, 999))
        assertNull(RingProtocol.parseNotification(status().apply { this[0] = 0x20 }, 999))
    }

    private fun status(synced: Int = 1, unix: Long = 1_777_777_777_123, uptime: Long = 123_456): ByteArray =
        ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
            .put(0x23).put(2).put(synced.toByte()).putLong(unix).putLong(uptime).array()
}
