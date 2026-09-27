package com.nexthci.ringfitness

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant

/** Original H10 HR/RR columns, with private, session-owned storage and crash recovery. */
data class HeartRateSample(
    val hr: Int,
    val correctedHr: Int,
    val ppgQuality: Int,
    val rrAvailable: Boolean,
    val contactSupported: Boolean,
    val contactStatus: Boolean,
    val rrMs: List<Int>,
    val rrRaw: List<Int>,
)

/** Uses the same serial executor as the session journal; no BLE or Activity owns this file. */
class SessionHeartRateRecorder(
    private val directory: File,
    private val store: FreeLivingSessionStore,
    private val clock: CaptureClock,
    private val syncDirectory: (File) -> Unit = { folder ->
        FileChannel.open(folder.toPath(), StandardOpenOption.READ).use { it.force(true) }
    },
    private val appendRow: (RandomAccessFile, ByteArray) -> Unit = { file, bytes -> file.write(bytes) },
) {
    private var sessionId: String? = null
    private var metadata: SessionHeartRate? = null
    private var stream: RandomAccessFile? = null
    private var lastSyncElapsedMs = 0L

    val activeSessionId: String? get() = sessionId

    fun open(session: FreeLivingSession, recovering: Boolean) {
        require(!session.isDiscarded && session.localData == null)
        val configured = requireNotNull(session.heartRate)
        require(configured.endedAtMs == null)
        if (sessionId == session.sessionId && stream != null) return
        check(sessionId == null) { "上一段心率文件尚未关闭" }
        val file = File(directory, "${session.sessionId}_polar_hr_rr.csv")
        require(file.canonicalFile.parentFile == directory.canonicalFile)
        val exists = file.exists()
        require(recovering || !exists) { "本段心率文件已存在，请恢复原记录" }
        val opened = RandomAccessFile(file, "rw")
        try {
            var recovered = configured
            if (!exists || opened.length() == 0L) {
                opened.write((HEADER + "\n").toByteArray(Charsets.US_ASCII))
                opened.fd.sync()
                syncDirectory(directory)
                if (recovering && configured.sampleCount > 0) {
                    // A missing file must never retain an invented count from an old checkpoint.
                    recovered = recovered.copy(sampleCount = 0, firstSampleAtMs = null, lastSampleAtMs = null)
                }
            } else {
                require(opened.readLine() == HEADER) { "心率文件表头损坏，原文件已保留" }
                var count = 0L
                var first: Long? = null
                var last: Long? = null
                while (opened.filePointer < opened.length()) {
                    val rowStart = opened.filePointer
                    val line = opened.readLine()
                    val rowEnd = opened.filePointer
                    opened.seek(rowEnd - 1)
                    val complete = opened.read() == 10
                    opened.seek(rowEnd)
                    if (!complete && rowEnd == opened.length()) {
                        // Only an incomplete final append can be rolled back after process death.
                        opened.setLength(rowStart)
                        break
                    }
                    val cells = line.split(',')
                    require(cells.size == 11 && cells[2].toLongOrNull() == count + 1) {
                        "心率文件内容无法核对，原文件已保留"
                    }
                    val received = requireNotNull(cells[1].toLongOrNull())
                    require(received > 0 && Instant.parse(cells[0]).toEpochMilli() == received)
                    count++
                    if (first == null) first = received
                    last = received
                }
                recovered = recovered.copy(sampleCount = count, firstSampleAtMs = first, lastSampleAtMs = last)
                opened.fd.sync()
            }
            opened.seek(opened.length())
            if (recovering) {
                val now = clock.nowEpochMs()
                val from = recovered.lastSampleAtMs ?: recovered.startedAtMs
                val gaps = recovered.gaps.map {
                    if (it.endedAtMs == null) it.copy(endedAtMs = now) else it
                }.toMutableList()
                if (recovered.sampleCount < configured.sampleCount) {
                    // Also covers a header created before an earlier recovery's journal commit failed.
                    gaps += HeartRateGap(from, now, "storage_error")
                }
                gaps += HeartRateGap(from, null, "process_restart")
                recovered = recovered.copy(gaps = gaps)
            }
            store.updateHeartRate(session.sessionId, recovered)
            sessionId = session.sessionId
            metadata = recovered
            stream = opened
            lastSyncElapsedMs = clock.nowElapsedMs()
        } catch (error: Exception) {
            opened.close()
            throw error
        }
    }

    fun write(sample: HeartRateSample, receivedAtMs: Long) {
        val output = stream ?: return
        var current = requireNotNull(metadata)
        require(receivedAtMs > 0)
        require(sample.hr in 0..65535)
        require(sample.rrMs.size == sample.rrRaw.size && sample.rrMs.size <= MAX_RR_VALUES_PER_ROW &&
            sample.rrMs.all { it in 0..65535 } && sample.rrRaw.all { it in 0..65535 })
        val last = current.lastSampleAtMs ?: current.startedAtMs
        if (receivedAtMs - last > NO_DATA_GAP_MS && current.gaps.none { it.endedAtMs == null }) {
            current = current.copy(gaps = current.gaps + HeartRateGap(last, receivedAtMs, "no_data"))
        }
        val hadOpenGap = current.gaps.any { it.endedAtMs == null }
        val gaps = current.gaps.map { gap ->
            if (gap.endedAtMs == null) gap.copy(endedAtMs = receivedAtMs) else gap
        }
        val count = current.sampleCount + 1
        val row = "${Instant.ofEpochMilli(receivedAtMs)},$receivedAtMs,$count,${sample.hr},${sample.correctedHr}," +
            "${sample.ppgQuality},${sample.rrAvailable},${sample.contactSupported},${sample.contactStatus}," +
            "${sample.rrMs.joinToString("|")},${sample.rrRaw.joinToString("|")}\n"
        // Include the newline in the backend's shared CSV line quota before touching disk.
        require(row.length <= MAX_LINE_CHARACTERS) { "心率记录长度无效" }
        val appendPosition = output.filePointer
        try { appendRow(output, row.toByteArray(Charsets.US_ASCII)) } catch (error: Exception) {
            // A later retry may only follow a complete row, never an interrupted CSV append.
            runCatching { output.setLength(appendPosition); output.seek(appendPosition); output.fd.sync() }
                .onFailure {
                    // No further callback may append behind an unverified tail.
                    runCatching { output.close() }
                    stream = null
                }
            throw error
        }
        metadata = current.copy(sampleCount = count, firstSampleAtMs = current.firstSampleAtMs ?: receivedAtMs,
            lastSampleAtMs = receivedAtMs, gaps = gaps)
        if (hadOpenGap || clock.nowElapsedMs() - lastSyncElapsedMs >= CHECKPOINT_MS) checkpoint()
    }

    fun gap(reason: String) {
        val current = metadata ?: return
        if (current.gaps.any { it.endedAtMs == null }) return
        metadata = current.copy(gaps = current.gaps + HeartRateGap(clock.nowEpochMs(), null, reason))
        checkpoint()
    }

    fun checkpoint() {
        stream?.fd?.sync()
        sessionId?.let { store.updateHeartRate(it, requireNotNull(metadata)) }
        lastSyncElapsedMs = clock.nowElapsedMs()
    }

    fun finish(session: FreeLivingSession) {
        val current = session.heartRate ?: return
        if (current.endedAtMs != null) {
            discard(session)
            return
        }
        if (sessionId == session.sessionId && stream == null) {
            // A failed append or seal may have left an incomplete last row. Reconcile disk first.
            sessionId = null
            metadata = null
        }
        if (sessionId == null) open(session, recovering = true)
        require(sessionId == session.sessionId)
        val captured = requireNotNull(metadata)
        val now = clock.nowEpochMs()
        val gaps = captured.gaps.map { if (it.endedAtMs == null)
            it.copy(endedAtMs = now) else it }.toMutableList()
        val last = captured.lastSampleAtMs ?: captured.startedAtMs
        val tailAlreadyCovered = captured.gaps.any { it.endedAtMs == null ||
            (it.startedAtMs <= last && it.endedAtMs >= now) }
        if (!tailAlreadyCovered && (captured.sampleCount == 0L || now - last > NO_DATA_GAP_MS)) {
            gaps += HeartRateGap(last, now, "no_data")
        }
        stream?.fd?.sync()
        stream?.close()
        stream = null
        val file = File(directory, "${session.sessionId}_polar_hr_rr.csv")
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        val sealed = captured.copy(endedAtMs = now, gaps = gaps,
            file = SessionHeartRateFile(file.name, file.length(), digest.digest().joinToString("") { "%02x".format(it) }))
        // Never freeze a corrupt CSV into immutable ended metadata.
        SessionHeartRate.verifyFile(directory, session.sessionId, sealed)
        store.updateHeartRate(session.sessionId, sealed)
        metadata = null
        sessionId = null
    }

    fun discard(session: FreeLivingSession) {
        if (sessionId != session.sessionId) return
        try { stream?.close() } finally {
            stream = null; metadata = null; sessionId = null
        }
        // The session store owns the discard tombstone and exact file deletion whitelist.
    }

    fun close() {
        try { checkpoint() } finally {
            try { stream?.close() } finally {
                stream = null; metadata = null; sessionId = null
            }
        }
    }

    companion object {
        const val HEADER = "timestamp_iso,timestamp_unix_ms,sample_index,hr_bpm,corrected_hr_bpm,ppg_quality," +
            "rr_available,contact_supported,contact_status,rr_ms,rr_1_1024s"
        const val CHECKPOINT_MS = 5_000L
        const val NO_DATA_GAP_MS = 10_000L
        private const val MAX_RR_VALUES_PER_ROW = 4096
        private const val MAX_LINE_CHARACTERS = 64 * 1024
    }
}
