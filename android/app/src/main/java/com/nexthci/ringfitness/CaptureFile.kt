package com.nexthci.ringfitness

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

enum class CaptureKind(val fileLabel: String) {
    IMU("imu_50hz"),
    IMU_25("imu_25hz"),
    IMU_LP_25("imu_lp_acc_25hz"),
    IMU_LP_50("imu_lp_acc_50hz"),
    PPG_HRS("ppg_green_ir_25hz"),
    PPG_SPO2("ppg_red_ir_25hz"),
    POLAR_HR_RR("polar_hr_rr"),
    HEALTH_RAW_V2("health_raw_v2"),
    ;

    val isMotion: Boolean
        get() = this == IMU || this == IMU_25 || this == IMU_LP_25 || this == IMU_LP_50

    val isLowPower: Boolean
        get() = this == IMU_LP_25 || this == IMU_LP_50
}

class CaptureFile private constructor(
    val kind: CaptureKind,
    val displayName: String,
    val uri: Uri,
    private val writer: BufferedWriter,
    initialSampleIndex: Long,
    writeHeader: Boolean,
) {
    private var sampleIndex = initialSampleIndex
    private var closed = false

    init {
        if (writeHeader) {
            when (kind) {
                CaptureKind.IMU, CaptureKind.IMU_25 -> writer.write(
                    "timestamp_iso,timestamp_unix_ms,ring_uptime_ms,packet_seq,sample_index," +
                        "accel_x_raw,accel_y_raw,accel_z_raw,gyro_x_raw,gyro_y_raw,gyro_z_raw," +
                        "accel_x_ms2,accel_y_ms2,accel_z_ms2,gyro_x_dps,gyro_y_dps,gyro_z_dps\n",
                )
                CaptureKind.IMU_LP_25, CaptureKind.IMU_LP_50 -> writer.write(
                    "timestamp_iso,timestamp_unix_ms,ring_uptime_ms,packet_seq,sample_index," +
                        "accel_x_raw,accel_y_raw,accel_z_raw," +
                        "accel_x_ms2,accel_y_ms2,accel_z_ms2\n",
                )
                else -> writer.write(
                    "timestamp_iso,timestamp_unix_ms,ring_uptime_ms,packet_seq,sample_index," +
                        "mode,channels_mask,green_raw,red_raw,infrared_raw\n",
                )
            }
            writer.flush()
        }
    }

    @Synchronized
    fun writeImu(packet: SensorPacket.Imu, flush: Boolean = true) {
        if (closed || !kind.isMotion) return
        val rateHz = if (kind == CaptureKind.IMU_25 || kind == CaptureKind.IMU_LP_25) {
            RingProtocol.IMU_RATE_25_HZ
        } else {
            RingProtocol.IMU_RATE_HZ
        }
        packet.frames.forEachIndexed { index, frame ->
            val remaining = packet.frames.lastIndex - index
            val offsetMs = (remaining * 1000.0 / rateHz).roundToLong()
            val epochMs = packet.receivedEpochMs - offsetMs
            val uptimeMs = wrapU32(packet.lastFrameUptimeMs - offsetMs)
            sampleIndex += 1
            val ax = frame.accelX / 2048.0 * 9.80665
            val ay = frame.accelY / 2048.0 * 9.80665
            val az = frame.accelZ / 2048.0 * 9.80665
            val gx = frame.gyroX / 16.4
            val gy = frame.gyroY / 16.4
            val gz = frame.gyroZ / 16.4
            if (kind.isLowPower) {
                writer.write(
                    String.format(
                        Locale.US,
                        "%s,%d,%d,%d,%d,%d,%d,%d,%.6f,%.6f,%.6f\n",
                        Instant.ofEpochMilli(epochMs), epochMs, uptimeMs, packet.packetSeq, sampleIndex,
                        frame.accelX, frame.accelY, frame.accelZ, ax, ay, az,
                    ),
                )
            } else {
                writer.write(
                    String.format(
                        Locale.US,
                        "%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f\n",
                        Instant.ofEpochMilli(epochMs), epochMs, uptimeMs, packet.packetSeq, sampleIndex,
                        frame.accelX, frame.accelY, frame.accelZ,
                        frame.gyroX, frame.gyroY, frame.gyroZ,
                        ax, ay, az, gx, gy, gz,
                    ),
                )
            }
        }
        if (flush) writer.flush()
    }

    @Synchronized
    fun writePpg(packet: SensorPacket.PpgRaw, flush: Boolean = true) {
        if (closed || kind.isMotion) return
        val sampleCount = maxOf(packet.green.size, packet.red.size, packet.infrared.size)
        repeat(sampleCount) { index ->
            val remaining = sampleCount - 1 - index
            val offsetMs = (remaining * 1000.0 / RingProtocol.PPG_RATE_HZ).roundToLong()
            val epochMs = packet.receivedEpochMs - offsetMs
            val uptimeMs = wrapU32(packet.lastSampleUptimeMs - offsetMs)
            val green = packet.green.getOrNull(index)?.toString().orEmpty()
            val red = packet.red.getOrNull(index)?.toString().orEmpty()
            val infrared = packet.infrared.getOrNull(index)?.toString().orEmpty()
            sampleIndex += 1
            writer.write(
                "${Instant.ofEpochMilli(epochMs)},$epochMs,$uptimeMs,${packet.packetSeq}," +
                    "$sampleIndex,${packet.mode},${packet.channelsMask},$green,$red,$infrared\n",
            )
        }
        if (flush) writer.flush()
    }

    @Synchronized
    fun count(): Long = sampleIndex

    @Synchronized
    fun close(): Long {
        if (!closed) {
            closed = true
            writer.flush()
            writer.close()
        }
        return sampleIndex
    }

    private fun wrapU32(value: Long): Long = (value and 0xFFFF_FFFFL)

    companion object {
        private val fileTimestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")
            .withZone(ZoneId.systemDefault())

        fun create(
            context: Context,
            kind: CaptureKind,
            now: Instant = Instant.now(),
        ): CaptureFile {
            val name = "ringfitness_${kind.fileLabel}_${fileTimestamp.format(now)}.csv"
            val dateFolder = dayFormatter.format(now)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/RingFitness/$dateFolder",
                )
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("无法在 Download/RingFitness 创建文件")
            val stream = resolver.openOutputStream(uri, "w")
                ?: error("无法打开采集文件：$name")
            return CaptureFile(
                kind,
                name,
                uri,
                BufferedWriter(OutputStreamWriter(stream, Charsets.UTF_8), 64 * 1024),
                initialSampleIndex = 0L,
                writeHeader = true,
            )
        }

        fun reopen(
            context: Context,
            kind: CaptureKind,
            uri: Uri,
            displayName: String,
            sampleIndex: Long,
        ): CaptureFile {
            val stream = context.contentResolver.openOutputStream(uri, "wa")
                ?: error("无法继续写入采集文件：$displayName")
            return CaptureFile(
                kind,
                displayName,
                uri,
                BufferedWriter(OutputStreamWriter(stream, Charsets.UTF_8), 64 * 1024),
                initialSampleIndex = sampleIndex,
                writeHeader = false,
            )
        }

        private val dayFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneId.systemDefault())
    }
}
