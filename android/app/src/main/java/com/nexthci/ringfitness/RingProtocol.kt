package com.nexthci.ringfitness

import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class PpgMode(val displayName: String, val firmwareMode: Int) {
    HRS("绿光 + 红外", 0),
    SPO2("红光 + 红外", 1),
}

data class ImuFrame(
    val accelX: Int,
    val accelY: Int,
    val accelZ: Int,
    val gyroX: Int,
    val gyroY: Int,
    val gyroZ: Int,
)

sealed interface SensorPacket {
    val receivedEpochMs: Long

    data class Imu(
        val packetSeq: Int,
        val frameCount: Int,
        val lastFrameUptimeMs: Long,
        val frames: List<ImuFrame>,
        override val receivedEpochMs: Long,
    ) : SensorPacket

    data class PpgRaw(
        val packetSeq: Int,
        val mode: Int,
        val channelsMask: Int,
        val lastSampleUptimeMs: Long,
        val green: List<Int>,
        val red: List<Int>,
        val infrared: List<Int>,
        override val receivedEpochMs: Long,
    ) : SensorPacket

    data class Battery(
        val millivolts: Int,
        val percent: Int,
        val chargeStatus: Int?,
        override val receivedEpochMs: Long,
    ) : SensorPacket

    data class Info(
        val formatVersion: Int,
        val hardwareRevision: Int,
        val firmwareMajor: Int,
        val firmwareMinor: Int,
        val firmwarePatch: Int,
        val firmwareTweak: Int,
        val componentCount: Int,
        override val receivedEpochMs: Long,
    ) : SensorPacket {
        val firmwareVersion: String
            get() {
                val base = "$firmwareMajor.$firmwareMinor.$firmwarePatch"
                return if (firmwareTweak == 0) base else "$base.$firmwareTweak"
            }
    }

    data class Health(
        val message: HealthMessage,
        override val receivedEpochMs: Long,
    ) : SensorPacket
}

sealed interface HealthMessage {
    data class Status(
        val collecting: Boolean,
        val bytes: Long,
        val records: Long,
        val errorCode: Int,
        val sessionId: Int,
    ) : HealthMessage

    data class ListItem(
        val sessionId: Int,
        val bytes: Long,
        val records: Long,
        val uptimeMs: Long,
        val unixMs: Long,
    ) : HealthMessage

    data class ListEnd(val count: Int) : HealthMessage
    data class DataChunk(val offset: Long, val payload: ByteArray) : HealthMessage
    data class ReadEnd(val nextOffset: Long, val done: Boolean) : HealthMessage
}

object RingProtocol {
    const val NUS_SERVICE_UUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
    const val NUS_WRITE_UUID = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
    const val NUS_NOTIFY_UUID = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"

    private const val CMD_IMU = 0x21
    private const val SUBCMD_IMU_START = 0x00
    private const val SUBCMD_IMU_STOP = 0x01
    private const val SUBCMD_IMU_PACKET = 0x02

    private const val CMD_PPG = 0x24
    private const val SUBCMD_PPG_START = 0x00
    private const val SUBCMD_PPG_STOP = 0x01
    private const val SUBCMD_PPG_RAW_PACKET = 0x08
    private const val PPG_SEND_RAW = 0x01

    private const val CMD_BATTERY = 0x29
    private const val SUBCMD_BATTERY_GET = 0x01
    private const val SUBCMD_BATTERY_STATUS = 0x02

    private const val CMD_INFO = 0x2A
    private const val SUBCMD_INFO_GET = 0x01
    private const val SUBCMD_INFO_STATUS = 0x02

    private const val CMD_HEALTH = 0x32
    private const val SUBCMD_HEALTH_START = 0x00
    private const val SUBCMD_HEALTH_STOP = 0x01
    private const val SUBCMD_HEALTH_STATUS_GET = 0x02
    private const val SUBCMD_HEALTH_READ = 0x03
    private const val SUBCMD_HEALTH_STATUS = 0x04
    private const val SUBCMD_HEALTH_DATA = 0x05
    private const val SUBCMD_HEALTH_READ_END = 0x06
    private const val SUBCMD_HEALTH_LIST = 0x07
    private const val SUBCMD_HEALTH_LIST_ITEM = 0x08
    private const val SUBCMD_HEALTH_LIST_END = 0x09

    const val PPG_CHANNEL_GREEN = 1 shl 0
    const val PPG_CHANNEL_RED = 1 shl 1
    const val PPG_CHANNEL_IR = 1 shl 2

    const val IMU_RATE_HZ = 50
    const val IMU_RATE_25_HZ = 25
    const val PPG_RATE_HZ = 25
    const val IMU_ACCEL_FULL_SCALE_G = 16
    const val IMU_GYRO_FULL_SCALE_DPS = 2000
    const val IMU_FRAMES_PER_PACKET = 10

    fun buildImuStart50Hz(): ByteArray = buildLegacyImuStart(IMU_RATE_HZ)

    fun buildImuStart25Hz(): ByteArray = buildLegacyImuStart(IMU_RATE_25_HZ)

    fun buildImuLowPowerStart25Hz(): ByteArray = buildLowPowerImuStart(IMU_RATE_25_HZ)

    fun buildImuLowPowerStart50Hz(): ByteArray = buildLowPowerImuStart(IMU_RATE_HZ)

    // Current production ring firmware expects the original 11-byte START command.
    private fun buildLegacyImuStart(rateHz: Int): ByteArray = ByteBuffer.allocate(11)
        .order(ByteOrder.LITTLE_ENDIAN)
        .put(CMD_IMU.toByte())
        .put(SUBCMD_IMU_START.toByte())
        .putShort(rateHz.toShort())
        .putShort(rateHz.toShort())
        .putShort(IMU_GYRO_FULL_SCALE_DPS.toShort())
        .put(IMU_ACCEL_FULL_SCALE_G.toByte())
        .put(IMU_FRAMES_PER_PACKET.toByte())
        .put(0) // raw encoding
        .array()

    // Newer firmware adds one lp byte after the encoding byte.
    private fun buildLowPowerImuStart(rateHz: Int): ByteArray = ByteBuffer.allocate(12)
        .order(ByteOrder.LITTLE_ENDIAN)
        .put(CMD_IMU.toByte())
        .put(SUBCMD_IMU_START.toByte())
        .putShort(rateHz.toShort())
        .putShort(rateHz.toShort())
        .putShort(IMU_GYRO_FULL_SCALE_DPS.toShort())
        .put(IMU_ACCEL_FULL_SCALE_G.toByte())
        .put(IMU_FRAMES_PER_PACKET.toByte())
        .put(0) // raw encoding
        .put(1) // low-power mode
        .array()

    fun buildImuStop(): ByteArray = byteArrayOf(CMD_IMU.toByte(), SUBCMD_IMU_STOP.toByte())

    fun buildPpgStart(mode: PpgMode): ByteArray {
        return byteArrayOf(
            CMD_PPG.toByte(),
            SUBCMD_PPG_START.toByte(),
            mode.firmwareMode.toByte(),
            PPG_SEND_RAW.toByte(),
        )
    }

    fun buildPpgStop(): ByteArray = byteArrayOf(CMD_PPG.toByte(), SUBCMD_PPG_STOP.toByte())

    fun buildBatteryGet(): ByteArray = byteArrayOf(
        CMD_BATTERY.toByte(),
        SUBCMD_BATTERY_GET.toByte(),
    )

    fun buildInfoGet(): ByteArray = byteArrayOf(CMD_INFO.toByte(), SUBCMD_INFO_GET.toByte())

    fun buildHealthStart(): ByteArray = byteArrayOf(CMD_HEALTH.toByte(), SUBCMD_HEALTH_START.toByte())

    fun buildHealthStop(): ByteArray = byteArrayOf(CMD_HEALTH.toByte(), SUBCMD_HEALTH_STOP.toByte())

    fun buildHealthStatusGet(): ByteArray = byteArrayOf(CMD_HEALTH.toByte(), SUBCMD_HEALTH_STATUS_GET.toByte())

    fun buildHealthList(): ByteArray = byteArrayOf(CMD_HEALTH.toByte(), SUBCMD_HEALTH_LIST.toByte())

    fun buildHealthRead(sessionId: Int, offset: Long, maxLength: Int = 0): ByteArray =
        ByteBuffer.allocate(10)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(CMD_HEALTH.toByte())
            .put(SUBCMD_HEALTH_READ.toByte())
            .putShort(sessionId.toShort())
            .putInt(offset.toInt())
            .putShort(maxLength.toShort())
            .array()

    fun parseNotification(
        data: ByteArray,
        receivedEpochMs: Long,
        imuLowPower: Boolean = false,
    ): SensorPacket? {
        if (data.size < 2) return null
        val cmd = data[0].toInt() and 0xFF
        val subCmd = data[1].toInt() and 0xFF
        return when {
            cmd == CMD_IMU && subCmd == SUBCMD_IMU_PACKET -> parseImu(data, receivedEpochMs, imuLowPower)
            cmd == CMD_PPG && subCmd == SUBCMD_PPG_RAW_PACKET -> parsePpgRaw(data, receivedEpochMs)
            cmd == CMD_BATTERY && subCmd == SUBCMD_BATTERY_STATUS -> parseBattery(data, receivedEpochMs)
            cmd == CMD_INFO && subCmd == SUBCMD_INFO_STATUS -> parseInfo(data, receivedEpochMs)
            cmd == CMD_HEALTH -> parseHealth(data)?.let { SensorPacket.Health(it, receivedEpochMs) }
            else -> null
        }
    }

    private fun parseInfo(data: ByteArray, receivedEpochMs: Long): SensorPacket.Info? {
        if (data.size < 9) return null
        return SensorPacket.Info(
            formatVersion = data[2].toInt() and 0xFF,
            hardwareRevision = data[3].toInt() and 0xFF,
            firmwareMajor = data[4].toInt() and 0xFF,
            firmwareMinor = data[5].toInt() and 0xFF,
            firmwarePatch = data[6].toInt() and 0xFF,
            firmwareTweak = data[7].toInt() and 0xFF,
            componentCount = data[8].toInt() and 0xFF,
            receivedEpochMs = receivedEpochMs,
        )
    }

    private fun parseHealth(data: ByteArray): HealthMessage? {
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(2)
        return when (data[1].toInt() and 0xFF) {
            SUBCMD_HEALTH_STATUS -> {
                if (data.size < 15) return null
                HealthMessage.Status(
                    collecting = buffer.get().toInt() != 0,
                    bytes = buffer.int.toLong() and 0xFFFF_FFFFL,
                    records = buffer.int.toLong() and 0xFFFF_FFFFL,
                    errorCode = buffer.short.toInt(),
                    sessionId = buffer.short.toInt() and 0xFFFF,
                )
            }
            SUBCMD_HEALTH_LIST_ITEM -> {
                if (data.size < 24) return null
                HealthMessage.ListItem(
                    sessionId = buffer.short.toInt() and 0xFFFF,
                    bytes = buffer.int.toLong() and 0xFFFF_FFFFL,
                    records = buffer.int.toLong() and 0xFFFF_FFFFL,
                    uptimeMs = buffer.int.toLong() and 0xFFFF_FFFFL,
                    unixMs = buffer.long,
                )
            }
            SUBCMD_HEALTH_LIST_END -> {
                if (data.size < 3) return null
                HealthMessage.ListEnd(buffer.get().toInt() and 0xFF)
            }
            SUBCMD_HEALTH_DATA -> {
                if (data.size < 8) return null
                val offset = buffer.int.toLong() and 0xFFFF_FFFFL
                val length = buffer.short.toInt() and 0xFFFF
                if (data.size < 8 + length) return null
                HealthMessage.DataChunk(offset, data.copyOfRange(8, 8 + length))
            }
            SUBCMD_HEALTH_READ_END -> {
                if (data.size < 7) return null
                HealthMessage.ReadEnd(
                    nextOffset = buffer.int.toLong() and 0xFFFF_FFFFL,
                    done = buffer.get().toInt() != 0,
                )
            }
            else -> null
        }
    }

    private fun parseBattery(data: ByteArray, receivedEpochMs: Long): SensorPacket.Battery? {
        if (data.size < 5) return null
        val millivolts = (data[2].toInt() and 0xFF) or ((data[3].toInt() and 0xFF) shl 8)
        val percent = data[4].toInt() and 0xFF
        val chargeStatus = data.getOrNull(5)?.toInt()?.and(0x7F)
        return SensorPacket.Battery(millivolts, percent, chargeStatus, receivedEpochMs)
    }

    private fun parseImu(
        data: ByteArray,
        receivedEpochMs: Long,
        lowPower: Boolean,
    ): SensorPacket.Imu? {
        if (data.size < 9) return null
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(2)
        val packetSeq = buffer.short.toInt() and 0xFFFF
        val frameCount = buffer.get().toInt() and 0xFF
        val uptimeMs = buffer.int.toLong() and 0xFFFF_FFFFL
        val bytesPerFrame = if (lowPower) 6 else 12
        val expected = 9 + frameCount * bytesPerFrame
        if (frameCount == 0 || data.size < expected) return null

        val frames = ArrayList<ImuFrame>(frameCount)
        repeat(frameCount) {
            val accelX = buffer.short.toInt()
            val accelY = buffer.short.toInt()
            val accelZ = buffer.short.toInt()
            frames += if (lowPower) {
                ImuFrame(accelX, accelY, accelZ, 0, 0, 0)
            } else {
                ImuFrame(
                    accelX,
                    accelY,
                    accelZ,
                    buffer.short.toInt(),
                    buffer.short.toInt(),
                    buffer.short.toInt(),
                )
            }
        }
        return SensorPacket.Imu(packetSeq, frameCount, uptimeMs, frames, receivedEpochMs)
    }

    private fun parsePpgRaw(data: ByteArray, receivedEpochMs: Long): SensorPacket.PpgRaw? {
        if (data.size < 12) return null
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(2)
        val packetSeq = buffer.short.toInt() and 0xFFFF
        val mode = buffer.get().toInt() and 0xFF
        buffer.get() // flags
        val count = buffer.get().toInt() and 0xFF
        val mask = buffer.get().toInt() and 0xFF
        val uptimeMs = buffer.int.toLong() and 0xFFFF_FFFFL
        val channelCount = listOf(PPG_CHANNEL_GREEN, PPG_CHANNEL_RED, PPG_CHANNEL_IR)
            .count { mask and it != 0 }
        val expected = 12 + count * channelCount * 4
        if (count == 0 || channelCount == 0 || data.size < expected) return null

        val green = ArrayList<Int>(count)
        val red = ArrayList<Int>(count)
        val infrared = ArrayList<Int>(count)
        repeat(count) {
            if (mask and PPG_CHANNEL_GREEN != 0) green += buffer.int
            if (mask and PPG_CHANNEL_RED != 0) red += buffer.int
            if (mask and PPG_CHANNEL_IR != 0) infrared += buffer.int
        }
        return SensorPacket.PpgRaw(
            packetSeq,
            mode,
            mask,
            uptimeMs,
            green,
            red,
            infrared,
            receivedEpochMs,
        )
    }
}
