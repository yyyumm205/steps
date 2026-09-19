package com.nexthci.ringfitness

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class HealthStatusDiagnosticsTest {
    @Test fun legacyStatusKeepsSignedErrorWithoutInventingAReason() {
        val parsed = parse(statusBytes(-16))
        assertEquals(HealthMessage.Status(false, 0xFFFF_FFFFL, 651, -16, 65535), parsed.message)
        assertEquals(1234L, parsed.receivedEpochMs)
        assertNull(parsed.statusErrorReason)
        assertEquals("unavailable", parsed.statusErrorReasonName)
    }

    @Test fun extendedStatusReadsEachSdkReasonAtOffset15() {
        val labels = listOf("none", "charging", "sensor_busy", "storage_init", "imu_start",
            "ppg_start", "storage_write", "storage_overwrite")
        labels.forEachIndexed { reason, label ->
            val parsed = parse(statusBytes(-16) + reason.toByte())
            assertEquals(reason, parsed.statusErrorReason)
            assertEquals(label, parsed.statusErrorReasonName)
            assertEquals(-16, (parsed.message as HealthMessage.Status).errorCode)
        }
    }

    @Test fun unknownReasonIsUnsignedAndRetained() {
        val parsed = parse(statusBytes(-32768) + 255.toByte())
        assertEquals(255, parsed.statusErrorReason)
        assertEquals("unknown_255", parsed.statusErrorReasonName)
        assertEquals(-32768, (parsed.message as HealthMessage.Status).errorCode)
    }

    @Test fun reasonDoesNotReplaceErrorOrChangeSessionEvidence() {
        val legacy = parse(statusBytes(-16))
        for (reason in listOf(0, 2, 255)) {
            val extended = parse(statusBytes(-16) + reason.toByte())
            assertEquals(legacy.message, extended.message)
        }
        assertEquals(0, (parse(statusBytes(0) + 2.toByte()).message as HealthMessage.Status).errorCode)
    }

    @Test fun explicitZeroAndMissingReasonRemainDistinct() {
        assertNull(parse(statusBytes(0)).statusErrorReason)
        assertEquals(0, parse(statusBytes(0) + 0.toByte()).statusErrorReason)
    }

    @Test fun rejectsEveryTruncatedStatusLength() {
        val packet = statusBytes(-16)
        for (size in 0 until 15) assertNull(RingProtocol.parseNotification(packet.copyOf(size), 0))
    }

    @Test fun listAndDownloadPayloadNeverBecomeStatusReasons() {
        val list = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .put(0x32).put(0x08).putShort(1).putInt(200).putInt(9).putInt(-1).putLong(42).array()
        assertNull(parse(list).statusErrorReason)
        val data = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
            .put(0x32).put(0x05).putInt(0).putShort(12).put(ByteArray(12) { 2 }).array()
        assertNull(parse(data).statusErrorReason)
        assertArrayEquals(ByteArray(12) { 2 }, (parse(data).message as HealthMessage.DataChunk).payload)
    }

    @Test fun additionalFutureBytesKeepCurrentOffsets() {
        val parsed = parse(statusBytes(-16) + byteArrayOf(3, 99, 88))
        assertEquals(3, parsed.statusErrorReason)
        assertEquals(parse(statusBytes(-16)).message, parsed.message)
    }

    private fun parse(bytes: ByteArray) = RingProtocol.parseNotification(bytes, 1234) as SensorPacket.Health

    private fun statusBytes(error: Int): ByteArray = ByteBuffer.allocate(15).order(ByteOrder.LITTLE_ENDIAN)
        .put(0x32).put(0x04).put(0).putInt(-1).putInt(651).putShort(error.toShort()).putShort(-1).array()
}
