package com.nexthci.ringfitness

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.CRC32

data class RawV2Capture(val uri: Uri, val displayName: String, val payloadBytes: Long)

object HealthRawV2 {
    const val EXTENSION = "rfbin"
    const val FORMAT = "ringfitness-health-v2"
    const val MIME = "application/vnd.ringfitness.health-v2"
    const val HEADER_SIZE = 64
    private val magic = byteArrayOf(0x52, 0x46, 0x56, 0x32, 0x52, 0x41, 0x57, 0x00)
    private val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS").withZone(ZoneId.systemDefault())

    fun create(
        context: Context,
        payload: File,
        anchor: HealthMessage.ListItem,
        startedAtMs: Long,
        endedAtMs: Long,
    ): RawV2Capture {
        val name = "ringfitness_health_raw_v2_${timestamp.format(Instant.ofEpochMilli(startedAtMs))}.$EXTENSION"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/${BuildConfig.PUBLIC_DATA_DIRECTORY}/" +
                    Instant.ofEpochMilli(startedAtMs).atZone(ZoneId.systemDefault()).toLocalDate(),
            )
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("无法创建 RingFitness v2 原始数据文件")
        return try {
            val crc = CRC32()
            FileInputStream(payload).use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    crc.update(buffer, 0, count)
                }
            }
            resolver.openOutputStream(uri, "w")!!.use { output ->
                output.write(header(anchor, startedAtMs, endedAtMs, payload.length(), crc.value))
                FileInputStream(payload).use { input ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
                output.flush()
            }
            RawV2Capture(uri, name, payload.length())
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    data class Header(
        val sessionId: Int,
        val recordCount: Long,
        val anchorUptimeMs: Long,
        val anchorUnixMs: Long,
        val startedAtMs: Long,
        val endedAtMs: Long,
        val payloadBytes: Long,
    )

    fun readHeader(context: Context, uri: Uri): Header {
        context.contentResolver.openInputStream(uri)!!.use { stream ->
            val bytes = ByteArray(HEADER_SIZE)
            DataInputStream(stream).readFully(bytes)
            require(bytes.copyOfRange(0, 8).contentEquals(magic)) { "RingFitness v2 原始数据头无效" }
            val reader = LittleEndianBytes(bytes, 8)
            require(reader.u16() == 2 && reader.u16() == HEADER_SIZE)
            val sessionId = reader.u16(); reader.u16()
            val records = reader.u32(); val uptime = reader.u32()
            val anchorUnix = reader.i64(); val started = reader.i64(); val ended = reader.i64()
            val payloadBytes = reader.i64(); reader.u32(); reader.u32()
            return Header(sessionId, records, uptime, anchorUnix, started, ended, payloadBytes)
        }
    }

    fun exportCSV(
        context: Context,
        uri: Uri,
        transferId: String,
        progress: (Double) -> Unit,
    ): List<ConvertedHealthCapture> {
        val header = readHeader(context, uri)
        val anchor = HealthMessage.ListItem(
            header.sessionId,
            header.payloadBytes,
            header.recordCount,
            header.anchorUptimeMs,
            header.anchorUnixMs,
        )
        val download = HealthFlashDownload(
            context,
            anchor,
            header.startedAtMs,
            "csv-export-$transferId",
            reset = true,
        )
        return try {
            context.contentResolver.openInputStream(uri)!!.use { input ->
                var skipped = 0L
                while (skipped < HEADER_SIZE) {
                    val count = input.skip(HEADER_SIZE - skipped)
                    if (count <= 0) error("RingFitness v2 原始数据头不完整")
                    skipped += count
                }
                val buffer = ByteArray(1024 * 1024)
                var offset = 0L
                while (offset < header.payloadBytes) {
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), header.payloadBytes - offset).toInt())
                    if (count < 0) error("RingFitness v2 原始数据提前结束")
                    download.write(offset, buffer.copyOf(count))
                    offset += count
                    progress((offset.toDouble() / header.payloadBytes.coerceAtLeast(1)).coerceIn(0.0, 0.25))
                }
            }
            val result = download.closeAndConvert { parsed -> progress(0.25 + parsed * 0.75) }
            progress(1.0)
            result
        } catch (error: Exception) {
            download.discard()
            throw error
        }
    }

    private fun header(
        anchor: HealthMessage.ListItem,
        startedAtMs: Long,
        endedAtMs: Long,
        payloadBytes: Long,
        crc32: Long,
    ): ByteArray = java.io.ByteArrayOutputStream(HEADER_SIZE).apply {
        write(magic); le16(2); le16(HEADER_SIZE); le16(anchor.sessionId); le16(0)
        le32(anchor.records); le32(anchor.uptimeMs); le64(anchor.unixMs)
        le64(startedAtMs); le64(endedAtMs); le64(payloadBytes); le32(crc32); le32(0)
        check(size() == HEADER_SIZE)
    }.toByteArray()

    private fun java.io.ByteArrayOutputStream.le16(value: Int) = repeat(2) { write(value ushr (it * 8)) }
    private fun java.io.ByteArrayOutputStream.le32(value: Long) = repeat(4) { write((value ushr (it * 8)).toInt()) }
    private fun java.io.ByteArrayOutputStream.le64(value: Long) = repeat(8) { write((value ushr (it * 8)).toInt()) }
}

private class LittleEndianBytes(private val bytes: ByteArray, private var offset: Int) {
    fun u16(): Int = scalar(2).toInt()
    fun u32(): Long = scalar(4)
    fun i64(): Long = scalar(8)
    private fun scalar(count: Int): Long {
        var value = 0L
        repeat(count) { value = value or ((bytes[offset++].toLong() and 0xff) shl (it * 8)) }
        return value
    }
}
