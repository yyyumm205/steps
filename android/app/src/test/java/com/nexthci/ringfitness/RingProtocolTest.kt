package com.nexthci.ringfitness

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RingProtocolTest {
    @Test
    fun imuStartRequestsExactly50HzRaw() {
        assertArrayEquals(
            byteArrayOf(0x21, 0x00, 50, 0, 50, 0, 0xD0.toByte(), 0x07, 16, 10, 0),
            RingProtocol.buildImuStart50Hz(),
        )
    }

    @Test
    fun imu25StartRequestsBothSensorsAt25Hz() {
        assertArrayEquals(
            byteArrayOf(0x21, 0x00, 25, 0, 25, 0, 0xD0.toByte(), 0x07, 16, 10, 0),
            RingProtocol.buildImuStart25Hz(),
        )
    }

    @Test
    fun lowPowerStartsAppendLpFlagAt25And50Hz() {
        assertArrayEquals(
            byteArrayOf(0x21, 0x00, 25, 0, 25, 0, 0xD0.toByte(), 0x07, 16, 10, 0, 1),
            RingProtocol.buildImuLowPowerStart25Hz(),
        )
        assertArrayEquals(
            byteArrayOf(0x21, 0x00, 50, 0, 50, 0, 0xD0.toByte(), 0x07, 16, 10, 0, 1),
            RingProtocol.buildImuLowPowerStart50Hz(),
        )
    }

    @Test
    fun ppgModesMatchFirmwareContract() {
        assertArrayEquals(byteArrayOf(0x24, 0, 0, 1), RingProtocol.buildPpgStart(PpgMode.HRS))
        assertArrayEquals(byteArrayOf(0x24, 0, 1, 1), RingProtocol.buildPpgStart(PpgMode.SPO2))
    }

    @Test
    fun batteryCommandAndStatusMatchFirmwareContract() {
        assertArrayEquals(byteArrayOf(0x29, 0x01), RingProtocol.buildBatteryGet())
        val parsed = RingProtocol.parseNotification(
            byteArrayOf(0x29, 0x02, 0x74, 0x0E, 85, 1),
            1234,
        ) as SensorPacket.Battery
        assertEquals(3700, parsed.millivolts)
        assertEquals(85, parsed.percent)
        assertEquals(1, parsed.chargeStatus)
    }

    @Test
    fun infoCommandAndStatusMatchFirmwareContract() {
        assertArrayEquals(byteArrayOf(0x2A, 0x01), RingProtocol.buildInfoGet())
        val packet = byteArrayOf(
            0x2A, 0x02, 1, 1,
            1, 2, 3, 4,
            1,
            1, 1, 1, 2, 3,
        )
        val parsed = RingProtocol.parseNotification(packet, 1_234) as SensorPacket.Info
        assertEquals(1, parsed.formatVersion)
        assertEquals(1, parsed.hardwareRevision)
        assertEquals(1, parsed.componentCount)
        assertEquals("1.2.3.4", parsed.firmwareVersion)
    }

    @Test
    fun healthCommandsAndStatusMatchFirmwareContract() {
        assertArrayEquals(byteArrayOf(0x32, 0x00), RingProtocol.buildHealthStart())
        assertArrayEquals(byteArrayOf(0x32, 0x01), RingProtocol.buildHealthStop())
        assertArrayEquals(byteArrayOf(0x32, 0x02), RingProtocol.buildHealthStatusGet())
        assertArrayEquals(byteArrayOf(0x32, 0x07), RingProtocol.buildHealthList())
        assertArrayEquals(
            byteArrayOf(0x32, 0x03, 7, 0, 0x78, 0x56, 0x34, 0x12, 0, 0),
            RingProtocol.buildHealthRead(7, 0x12345678),
        )
        assertArrayEquals(
            byteArrayOf(0x32, 0x03, 7, 0, 0, 0x10, 0, 0, 0, 0x40),
            RingProtocol.buildHealthRead(7, 0x1000, 16 * 1_024),
        )

        val statusBytes = ByteBuffer.allocate(15).order(ByteOrder.LITTLE_ENDIAN)
            .put(0x32).put(0x04).put(1)
            .putInt(1234).putInt(56).putShort(-2).putShort(7)
            .array()
        val parsed = RingProtocol.parseNotification(statusBytes, 9_000) as SensorPacket.Health
        assertEquals(HealthMessage.Status(true, 1234, 56, -2, 7), parsed.message)
    }

    @Test
    fun parsesHealthListAndDataChunks() {
        val itemBytes = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .put(0x32).put(0x08).putShort(4)
            .putInt(200).putInt(9).putInt(1234).putLong(1_710_000_000_000)
            .array()
        val item = (RingProtocol.parseNotification(itemBytes, 0) as SensorPacket.Health).message
        assertEquals(HealthMessage.ListItem(4, 200, 9, 1234, 1_710_000_000_000), item)

        val chunk = byteArrayOf(0x32, 0x05, 10, 0, 0, 0, 3, 0, 8, 9, 10)
        val parsed = (RingProtocol.parseNotification(chunk, 0) as SensorPacket.Health).message
            as HealthMessage.DataChunk
        assertEquals(10, parsed.offset)
        assertArrayEquals(byteArrayOf(8, 9, 10), parsed.payload)
    }

    @Test
    fun parsesImuRawFrames() {
        val packet = ByteBuffer.allocate(9 + 24).order(ByteOrder.LITTLE_ENDIAN)
            .put(0x21).put(0x02)
            .putShort(7)
            .put(2)
            .putInt(1000)
            .putShort(1).putShort(2).putShort(3).putShort(4).putShort(5).putShort(6)
            .putShort(-1).putShort(-2).putShort(-3).putShort(-4).putShort(-5).putShort(-6)
            .array()
        val parsed = RingProtocol.parseNotification(packet, 1234) as SensorPacket.Imu
        assertEquals(7, parsed.packetSeq)
        assertEquals(1000, parsed.lastFrameUptimeMs)
        assertEquals(ImuFrame(-1, -2, -3, -4, -5, -6), parsed.frames[1])
    }

    @Test
    fun parsesLowPowerAccelOnlyFrames() {
        val packet = ByteBuffer.allocate(9 + 12).order(ByteOrder.LITTLE_ENDIAN)
            .put(0x21).put(0x02)
            .putShort(8)
            .put(2)
            .putInt(2000)
            .putShort(100).putShort(-200).putShort(300)
            .putShort(101).putShort(-201).putShort(301)
            .array()
        val parsed = RingProtocol.parseNotification(packet, 2500, imuLowPower = true) as SensorPacket.Imu
        assertEquals(ImuFrame(101, -201, 301, 0, 0, 0), parsed.frames[1])
    }

    @Test
    fun parsesInterleavedRedAndIr() {
        val packet = ByteBuffer.allocate(12 + 16).order(ByteOrder.LITTLE_ENDIAN)
            .put(0x24).put(0x08)
            .putShort(9)
            .put(1).put(0).put(2).put(0x06)
            .putInt(5000)
            .putInt(101).putInt(201)
            .putInt(102).putInt(202)
            .array()
        val parsed = RingProtocol.parseNotification(packet, 9000) as SensorPacket.PpgRaw
        assertEquals(listOf(101, 102), parsed.red)
        assertEquals(listOf(201, 202), parsed.infrared)
        assertEquals(emptyList<Int>(), parsed.green)
    }

    @Test
    fun rejectsTruncatedPacket() {
        assertNull(RingProtocol.parseNotification(byteArrayOf(0x21, 0x02, 1), 0))
    }
}
