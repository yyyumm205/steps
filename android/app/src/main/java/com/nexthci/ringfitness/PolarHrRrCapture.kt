package com.nexthci.ringfitness

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.polar.sdk.api.model.PolarHrData
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class PolarHrRrCapture private constructor(
    val displayName: String,
    val uri: Uri,
    private val writer: BufferedWriter,
    initialSampleCount: Long,
    writeHeader: Boolean,
) {
    private var sampleCount = initialSampleCount
    private var closed = false

    init {
        if (writeHeader) {
            writer.write(
                "timestamp_iso,timestamp_unix_ms,sample_index,hr_bpm,corrected_hr_bpm,ppg_quality," +
                    "rr_available,contact_supported,contact_status,rr_ms,rr_1_1024s\n",
            )
            writer.flush()
        }
    }

    @Synchronized
    fun write(sample: PolarHrData.PolarHrSample, epochMs: Long) {
        if (closed) return
        sampleCount += 1
        val rrMs = sample.rrsMs.joinToString("|")
        val rrRaw = sample.rrs.joinToString("|")
        writer.write(
            "${Instant.ofEpochMilli(epochMs)},$epochMs,$sampleCount,${sample.hr},${sample.correctedHr}," +
                "${sample.ppgQuality},${sample.rrAvailable},${sample.contactStatusSupported}," +
                "${sample.contactStatus},$rrMs,$rrRaw\n",
        )
        writer.flush()
    }

    @Synchronized
    fun count(): Long = sampleCount

    @Synchronized
    fun close(): Long {
        if (!closed) {
            closed = true
            writer.flush()
            writer.close()
        }
        return sampleCount
    }

    companion object {
        private val fileTimestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")
            .withZone(ZoneId.systemDefault())
        private val dayFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneId.systemDefault())

        fun create(context: Context, now: Instant = Instant.now()): PolarHrRrCapture {
            val name = "ringfitness_polar_hr_rr_${fileTimestamp.format(now)}.csv"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/RingFitness/${dayFormatter.format(now)}",
                )
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("无法在 Download/RingFitness 创建 Polar 数据文件")
            val stream = resolver.openOutputStream(uri, "w") ?: error("无法打开 Polar 数据文件")
            return PolarHrRrCapture(
                name,
                uri,
                BufferedWriter(OutputStreamWriter(stream, Charsets.UTF_8), 16 * 1024),
                initialSampleCount = 0,
                writeHeader = true,
            )
        }

        fun reopen(context: Context, uri: Uri, displayName: String, sampleCount: Long): PolarHrRrCapture {
            val stream = context.contentResolver.openOutputStream(uri, "wa")
                ?: error("无法重新打开 Polar 实时数据文件")
            return PolarHrRrCapture(
                displayName,
                uri,
                BufferedWriter(OutputStreamWriter(stream, Charsets.UTF_8), 16 * 1024),
                initialSampleCount = sampleCount,
                writeHeader = false,
            )
        }
    }
}
