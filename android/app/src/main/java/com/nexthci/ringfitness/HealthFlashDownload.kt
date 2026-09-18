package com.nexthci.ringfitness

import android.content.Context
import android.net.Uri
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.time.Instant

data class ConvertedHealthCapture(
    val kind: CaptureKind,
    val uri: Uri,
    val displayName: String,
    val sampleCount: Long,
)

/** Stores HEALTH flash chunks without keeping an overnight recording in memory. */
class HealthFlashDownload(
    context: Context,
    private val anchor: HealthMessage.ListItem,
    private val fallbackStartedAtMs: Long,
    transferId: String,
    reset: Boolean,
) {
    private val appContext = context.applicationContext
    private val temporary = File(
        File(appContext.filesDir, "ring_transfer_temp").apply { mkdirs() },
        "$transferId.bin",
    )
    private val output = RandomAccessFile(temporary, "rw").apply {
        if (reset) setLength(0L)
    }
    private var imuLastEpochMs: Long? = null
    private var ppgLastEpochMs: Long? = null

    @Synchronized
    fun write(offset: Long, payload: ByteArray) {
        output.seek(offset)
        output.write(payload)
    }

    @Synchronized
    fun closeAndConvert(progress: ((Double) -> Unit)? = null): List<ConvertedHealthCapture> {
        output.fd.sync()
        output.close()
        var imu: CaptureFile? = null
        var ppg: CaptureFile? = null
        return try {
            val totalBytes = temporary.length().coerceAtLeast(1L)
            DataInputStream(FileInputStream(temporary).buffered(64 * 1024)).use { input ->
                while (true) {
                    val command = input.read()
                    if (command < 0) break
                    require(command == CMD_HEALTH) { "健康数据记录头无效：0x${command.toString(16)}" }
                    when (val subcommand = input.readUnsignedByte()) {
                        RECORD_VITALS -> skipExactly(input, 15)
                        RECORD_RAW -> {
                            val sequence = input.readU16LE()
                            val mode = input.readUnsignedByte()
                            input.readUnsignedByte() // reserved
                            val count = input.readUnsignedByte()
                            val mask = input.readUnsignedByte()
                            val uptime = input.readU32LE()
                            val green = ArrayList<Int>(count)
                            val red = ArrayList<Int>(count)
                            val infrared = ArrayList<Int>(count)
                            repeat(count) {
                                if (mask and RingProtocol.PPG_CHANNEL_GREEN != 0) green += input.readI32LE()
                                if (mask and RingProtocol.PPG_CHANNEL_RED != 0) red += input.readI32LE()
                                if (mask and RingProtocol.PPG_CHANNEL_IR != 0) infrared += input.readI32LE()
                            }
                            if (green.isEmpty() && red.isEmpty() && infrared.isEmpty()) {
                                error("健康 PPG 记录没有通道")
                            }
                            val ppgEndEpochMs = normalizeHealthPacketEndEpoch(
                                candidateEpochMs = epochForUptime(uptime),
                                fallbackStartedAtMs = fallbackStartedAtMs,
                                previousPacketEndEpochMs = ppgLastEpochMs,
                                sampleCount = count,
                                sampleIntervalMs = 40L,
                            )
                            ppgLastEpochMs = ppgEndEpochMs
                            val file = ppg ?: CaptureFile.create(
                                appContext,
                                if (green.isNotEmpty()) CaptureKind.PPG_HRS else CaptureKind.PPG_SPO2,
                                Instant.ofEpochMilli(fallbackStartedAtMs),
                            ).also { ppg = it }
                            file.writePpg(
                                SensorPacket.PpgRaw(
                                    sequence, mode, mask, uptime, green, red, infrared,
                                    ppgEndEpochMs,
                                ),
                                flush = false,
                            )
                        }
                        RECORD_IMU -> {
                            val count = input.readUnsignedByte()
                            val uptime = input.readU32LE()
                            val frames = ArrayList<ImuFrame>(count)
                            repeat(count) {
                                frames += ImuFrame(
                                    input.readI16LE(), input.readI16LE(), input.readI16LE(), 0, 0, 0,
                                )
                            }
                            val imuEndEpochMs = normalizeHealthPacketEndEpoch(
                                candidateEpochMs = epochForUptime(uptime),
                                fallbackStartedAtMs = fallbackStartedAtMs,
                                previousPacketEndEpochMs = imuLastEpochMs,
                                sampleCount = count,
                                sampleIntervalMs = 20L,
                            )
                            imuLastEpochMs = imuEndEpochMs
                            val file = imu ?: CaptureFile.create(
                                appContext,
                                CaptureKind.IMU_LP_50,
                                Instant.ofEpochMilli(fallbackStartedAtMs),
                            ).also { imu = it }
                            file.writeImu(
                                SensorPacket.Imu(0, count, uptime, frames, imuEndEpochMs),
                                flush = false,
                            )
                        }
                        else -> error("未知健康数据记录：0x${subcommand.toString(16)}")
                    }
                    progress?.invoke((1.0 - input.available().toDouble() / totalBytes).coerceIn(0.0, 0.99))
                }
            }
            listOfNotNull(imu, ppg).map { file ->
                val count = file.close()
                ConvertedHealthCapture(file.kind, file.uri, file.displayName, count)
            }
        } catch (error: Exception) {
            listOfNotNull(imu, ppg).forEach { file ->
                runCatching { file.close() }
                appContext.contentResolver.delete(file.uri, null, null)
            }
            throw error
        } finally {
            temporary.delete()
        }
    }

    @Synchronized
    fun closeAsV2(endedAtMs: Long): RawV2Capture {
        output.fd.sync()
        output.close()
        return try {
            HealthRawV2.create(
                context = appContext,
                payload = temporary,
                anchor = anchor,
                startedAtMs = fallbackStartedAtMs,
                endedAtMs = endedAtMs,
            )
        } finally {
            temporary.delete()
        }
    }

    @Synchronized
    fun discard() {
        runCatching { output.close() }
        temporary.delete()
    }

    @Synchronized
    fun suspendForRetry() {
        runCatching { output.fd.sync() }
        runCatching { output.close() }
    }

    fun existingBytes(): Long = temporary.length()

    @Synchronized
    fun truncateTo(bytes: Long) {
        output.setLength(bytes.coerceAtLeast(0L))
    }

    private fun epochForUptime(uptime: Long): Long {
        return resolveHealthEpochMs(
            uptimeMs = uptime,
            anchorUptimeMs = anchor.uptimeMs,
            anchorUnixMs = anchor.unixMs,
            fallbackStartedAtMs = fallbackStartedAtMs,
        )
    }

    private fun skipExactly(input: DataInputStream, bytes: Int) {
        repeat(bytes) { if (input.read() < 0) throw EOFException() }
    }

    companion object {
        fun discardStoredTransfer(context: Context, transferId: String) {
            File(File(context.applicationContext.filesDir, "ring_transfer_temp"), "$transferId.bin").delete()
        }

        private const val CMD_HEALTH = 0x32
        private const val RECORD_VITALS = 0x10
        private const val RECORD_RAW = 0x11
        private const val RECORD_IMU = 0x12
    }
}

internal fun resolveHealthEpochMs(
    uptimeMs: Long,
    anchorUptimeMs: Long,
    anchorUnixMs: Long,
    fallbackStartedAtMs: Long,
): Long {
    val baseUnixMs = if (anchorUnixMs >= MIN_PLAUSIBLE_UNIX_MS) {
        anchorUnixMs
    } else {
        require(fallbackStartedAtMs >= MIN_PLAUSIBLE_UNIX_MS) {
            "健康采集缺少有效的绝对时间锚点"
        }
        fallbackStartedAtMs
    }
    val delta = (uptimeMs - anchorUptimeMs) and 0xFFFF_FFFFL
    return baseUnixMs + delta
}

private const val MIN_PLAUSIBLE_UNIX_MS = 946_684_800_000L
private const val MAX_INITIAL_HEALTH_OFFSET_MS = 48L * 60L * 60L * 1_000L
private const val MAX_HEALTH_PACKET_GAP_MS = 5L * 60L * 1_000L

internal fun normalizeHealthPacketEndEpoch(
    candidateEpochMs: Long,
    fallbackStartedAtMs: Long,
    previousPacketEndEpochMs: Long?,
    sampleCount: Int,
    sampleIntervalMs: Long,
): Long {
    require(sampleCount > 0 && sampleIntervalMs > 0L)
    val firstPacketEnd = fallbackStartedAtMs + (sampleCount - 1L) * sampleIntervalMs
    val previous = previousPacketEndEpochMs
    if (previous == null) {
        return if (candidateEpochMs in fallbackStartedAtMs..(fallbackStartedAtMs + MAX_INITIAL_HEALTH_OFFSET_MS)) {
            candidateEpochMs
        } else {
            firstPacketEnd
        }
    }
    val expectedNextEnd = previous + sampleCount * sampleIntervalMs
    val gap = candidateEpochMs - previous
    return if (gap > 0L && gap <= MAX_HEALTH_PACKET_GAP_MS) candidateEpochMs else expectedNextEnd
}

private fun DataInputStream.readU16LE(): Int = readUnsignedByte() or (readUnsignedByte() shl 8)
private fun DataInputStream.readI16LE(): Int {
    val value = readU16LE()
    return if (value and 0x8000 != 0) value - 0x1_0000 else value
}
private fun DataInputStream.readU32LE(): Long =
    readUnsignedByte().toLong() or
        (readUnsignedByte().toLong() shl 8) or
        (readUnsignedByte().toLong() shl 16) or
        (readUnsignedByte().toLong() shl 24)
private fun DataInputStream.readI32LE(): Int = readU32LE().toInt()
