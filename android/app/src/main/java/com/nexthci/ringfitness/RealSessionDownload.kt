package com.nexthci.ringfitness

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.Closeable
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32

/** Raw packet evidence; packet uptimes are preserved without gap repair or wall-clock inference. */
data class HealthPayloadEvidence(
    val payloadBytes: Long,
    val records: Long,
    val crc32: Long,
    val imuSamples: Long,
    val ppgSamples: Long,
    val firstImuUptimeMs: Long?,
    val lastImuUptimeMs: Long?,
    val firstPpgUptimeMs: Long?,
    val lastPpgUptimeMs: Long?,
)

/**
 * One stopped, explicitly associated device record. The owner supplies a fresh complete LIST
 * and checks device identity before every READ; DATA/READ_END themselves carry no session ID.
 * All calls belong to that owner's serial context. Nothing here sends BLE or deletes ring data.
 */
class RealSessionDownload internal constructor(
    directory: File,
    private val sessionId: String,
    private val ringAddress: String,
    record: HealthMessage.ListItem,
    private val startedAtMs: Long,
    private val endedAtMs: Long,
    private val syncDirectory: (File) -> Unit,
) : Closeable {
    constructor(directory: File, sessionId: String, ringAddress: String,
        record: HealthMessage.ListItem, startedAtMs: Long = 0L, endedAtMs: Long = 0L) : this(
        directory, sessionId, ringAddress, record, startedAtMs, endedAtMs,
        { FileChannel.open(it.toPath(), StandardOpenOption.READ).use { channel -> channel.force(true) } },
    )

    data class Completed(val file: SessionRawFile, val evidence: HealthPayloadEvidence,
        val evidenceFileName: String)

    class DownloadNoProgressException : IOException(
        "下载连续 $MAX_NO_PROGRESS_WINDOWS 轮没有进展，已保存的部分会保留，请重新连接后重试",
    )

    private val directory = directory.canonicalFile
    private var record = record
    private val prefix = "$sessionId-ring-${record.sessionId}"
    private val partial = safeFile("$prefix.part")
    private val checkpointFile = safeFile("$prefix.download.json")
    private val destination = safeFile("$prefix.rfbin")
    private val evidenceFile = safeFile("$prefix.raw-evidence.json")
    private fun descriptor(record: HealthMessage.ListItem = this.record) = JsonObject().apply {
        addProperty("schema_version", 1)
        addProperty("session_id", sessionId)
        addProperty("ring_address", ringAddress)
        addProperty("device_session_id", record.sessionId)
        addProperty("bytes", record.bytes)
        addProperty("records", record.records)
        addProperty("anchor_uptime_ms", record.uptimeMs)
        addProperty("anchor_unix_ms", record.unixMs)
        addProperty("started_at_ms", startedAtMs)
        addProperty("ended_at_ms", endedAtMs)
    }
    private var output: RandomAccessFile? = null
    private var completed: Completed? = null
    private val completedPrefixOffsets = mutableSetOf<Long>()
    private val prefixDigest = MessageDigest.getInstance("SHA-256")
    private var readWindowStart = 0L
    private var consecutiveEmptyWindows = 0
    var nextOffset: Long = 0L
        private set

    init {
        require(UUID.fromString(sessionId).toString() == sessionId) { "采集段标识无效" }
        require(Regex("^(?:[0-9A-F]{2}:){5}[0-9A-F]{2}$").matches(ringAddress)) { "戒指地址无效" }
        require(record.sessionId in 0..65535 && record.bytes in 1..0xFFFF_FFFFL &&
            record.records in 1..0xFFFF_FFFFL && record.uptimeMs in 0..0xFFFF_FFFFL && record.unixMs >= 0)
        require(startedAtMs >= 0 && endedAtMs >= 0 &&
            (startedAtMs == 0L || endedAtMs == 0L || endedAtMs >= startedAtMs))
        require(this.directory.isDirectory) { "采集目录尚未就绪" }
        if (checkpointFile.exists()) {
            val saved = JsonParser.parseString(checkpointFile.readText(Charsets.UTF_8)).asJsonObject
            val savedDescriptor = saved.getAsJsonObject("record")
            require(savedDescriptor == descriptor() || isPrefixDescriptor(savedDescriptor, record)) {
                "戒指记录身份已变化，原始片段已保留"
            }
            nextOffset = saved.get("durable_bytes").asLong
            require(nextOffset in 0..record.bytes && partial.isFile && partial.length() in nextOffset..record.bytes) {
                "下载断点与原始片段不一致"
            }
            hashInto(partial, nextOffset, prefixDigest)
            require(saved.get("prefix_sha256").asString == prefixHash()) { "原始片段校验失败" }
            if (savedDescriptor != descriptor()) migrateCompletedPrefix(savedDescriptor.get("bytes").asLong)
            syncDirectory(this.directory)
        } else {
            // The first checkpoint may fail after creating an empty part. It contains no
            // unassociated signal and can safely establish its descriptor on the next retry.
            require((!partial.exists() || (partial.isFile && partial.length() == 0L)) &&
                !destination.exists() && !evidenceFile.exists()) {
                "已有未关联文件，已保留原文件"
            }
        }
        output = RandomAccessFile(partial, "rw")
        try {
            persistCheckpoint()
        } catch (error: Exception) {
            output?.close()
            output = null
            throw error
        }
        readWindowStart = nextOffset
    }

    /** The owner records each READ's cursor, including a replay cursor within the saved prefix. */
    fun beginRead(offset: Long = nextOffset) {
        check(completed == null && output != null) { "下载已关闭或完成" }
        require(offset in 0..nextOffset) { "下载起始位置超出已保存片段" }
        readWindowStart = offset
    }

    /** Duplicate bytes may be replayed; a gap, changed overlap or bytes past LIST is rejected. */
    fun append(chunk: HealthMessage.DataChunk): Long {
        check(completed == null) { "原始文件已完成" }
        val stream = checkNotNull(output) { "下载已关闭" }
        require(chunk.payload.isNotEmpty() && chunk.offset >= 0 && chunk.offset <= nextOffset) { "下载片段存在缺口" }
        require(chunk.offset <= record.bytes - chunk.payload.size) { "下载片段超过记录范围" }
        val overlap = minOf(stream.length() - chunk.offset, chunk.payload.size.toLong()).coerceAtLeast(0).toInt()
        if (overlap > 0) {
            val existing = ByteArray(overlap)
            stream.seek(chunk.offset)
            stream.readFully(existing)
            require(existing.contentEquals(chunk.payload.copyOf(overlap))) { "重传片段内容冲突，已保留原始文件" }
        }
        stream.seek(chunk.offset + overlap)
        stream.write(chunk.payload, overlap, chunk.payload.size - overlap)
        val newBytesAt = (nextOffset - chunk.offset).coerceAtMost(chunk.payload.size.toLong()).toInt()
        prefixDigest.update(chunk.payload, newBytesAt, chunk.payload.size - newBytesAt)
        nextOffset = maxOf(nextOffset, chunk.offset + chunk.payload.size)
        return nextOffset
    }

    /** A READ window end is accepted only at the exact contiguous byte position. */
    fun checkpoint(end: HealthMessage.ReadEnd): Boolean {
        require(end.nextOffset == nextOffset) { "下载结束位置与已保存片段不一致" }
        require(!end.done || nextOffset == record.bytes) { "戒指提前结束下载，原始片段已保留" }
        persistCheckpoint()
        checkReadProgress(nextOffset, end.done)
        return end.done
    }

    /** Replay advances a verified cursor while the saved payload's nextOffset stays unchanged. */
    fun checkpointReplay(end: HealthMessage.ReadEnd, replayOffset: Long) {
        require(replayOffset in readWindowStart..nextOffset && end.nextOffset == replayOffset &&
            (!end.done || replayOffset == nextOffset)) {
            "重连后的下载位置不一致，已保留原始数据"
        }
        checkReadProgress(replayOffset, end.done)
    }

    private fun checkReadProgress(offset: Long, done: Boolean) {
        if (offset > readWindowStart) consecutiveEmptyWindows = 0
        else if (!done) consecutiveEmptyWindows++
        readWindowStart = offset
        if (!done && consecutiveEmptyWindows >= MAX_NO_PROGRESS_WINDOWS) throw DownloadNoProgressException()
    }

    fun finish(end: HealthMessage.ReadEnd): Completed {
        require(checkpoint(end) && nextOffset == record.bytes) { "原始记录尚未下载完整" }
        completed?.let { verifyFinal(it); return it }
        require(sha256(partial) == prefixHash()) { "原始片段校验失败，已保留文件" }
        val evidence = inspectPayload(partial, record)
        val header = HealthRawV2.header(record, startedAtMs, endedAtMs, record.bytes, evidence.crc32)
        if (!destination.exists()) {
            val staging = File.createTempFile("$prefix-", ".rfbin.tmp", directory)
            try {
                FileOutputStream(staging).use { sink ->
                    sink.write(header)
                    partial.inputStream().use { it.copyTo(sink) }
                    sink.fd.sync()
                }
                verifyContainer(staging, header, evidence)
                // The serial owner never replaces an existing final file, including on retry.
                require(!destination.exists()) { "目标文件已存在，已保留原文件" }
                Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
                syncDirectory(directory)
            } finally {
                staging.delete()
            }
        }
        verifyContainer(destination, header, evidence)
        val result = Completed(
            SessionRawFile(destination.name, record.sessionId, destination.length(), sha256(destination), false),
            evidence, evidenceFile.name,
        )
        val details = descriptor().deepCopy().apply {
            addProperty("payload_crc32", evidence.crc32)
            addProperty("file_sha256", result.file.sha256)
            addProperty("parsed_records", evidence.records)
            addProperty("imu_samples", evidence.imuSamples)
            addProperty("ppg_samples", evidence.ppgSamples)
            addProperty("first_imu_uptime_ms", evidence.firstImuUptimeMs)
            addProperty("last_imu_uptime_ms", evidence.lastImuUptimeMs)
            addProperty("first_ppg_uptime_ms", evidence.firstPpgUptimeMs)
            addProperty("last_ppg_uptime_ms", evidence.lastPpgUptimeMs)
            addProperty("crc_source", "phone_payload_and_container_reread")
            addProperty("sample_coverage_status", "not_assessed")
        }
        if (evidenceFile.exists()) {
            require(JsonParser.parseString(evidenceFile.readText()).asJsonObject == details) { "原始文件证据冲突" }
        } else atomicJson(evidenceFile, details)
        syncDirectory(directory)
        completed = result
        return result
    }

    /** Continue the same stopped Flash record when a final LIST exposes a durable tail. */
    fun extendTo(updated: HealthMessage.ListItem) {
        require(FreeLivingSessionStore.sameDeviceRecord(record, updated) &&
            updated.bytes >= record.bytes && updated.records >= record.records &&
            (updated.bytes > record.bytes || updated.records > record.records)) {
            "戒指记录身份或计数发生变化，原始文件已保留"
        }
        require(nextOffset == record.bytes && completed != null) { "原始记录前缀尚未完整保存" }
        completedPrefixOffsets += record.bytes
        migrateCompletedPrefix(record.bytes)
        record = updated
        completed = null
        output = RandomAccessFile(partial, "rw")
        persistCheckpoint()
        syncDirectory(directory)
    }

    /** A completed READ from the frozen prefix may arrive after its final LIST exposed a tail. */
    fun isDelayedCompletedPrefix(end: HealthMessage.ReadEnd): Boolean = end.done &&
        end.nextOffset in completedPrefixOffsets && end.nextOffset <= nextOffset && end.nextOffset < record.bytes

    /** Call only after the session journal durably accepts Completed.file. */
    fun releaseTemporary() {
        verifyFinal(checkNotNull(completed) { "原始文件尚未完成" })
        output?.close()
        output = null
        if (partial.exists() && !partial.delete()) throw IOException("无法清理已保全的下载片段")
        if (checkpointFile.exists() && !checkpointFile.delete()) throw IOException("无法清理已保全的下载断点")
        directory.listFiles { file -> file.name.startsWith("$prefix.prefix-") }?.forEach { file ->
            if (!file.delete()) throw IOException("无法清理已核对的原始前缀")
        }
        syncDirectory(directory)
    }

    override fun close() {
        if (output == null) return
        try { persistCheckpoint() } finally { output?.close(); output = null }
    }

    private fun persistCheckpoint() {
        checkNotNull(output) { "下载已关闭" }.fd.sync()
        val saved = JsonObject().apply {
            add("record", descriptor())
            addProperty("durable_bytes", nextOffset)
            addProperty("prefix_sha256", prefixHash())
        }
        atomicJson(checkpointFile, saved)
    }

    private fun atomicJson(target: File, json: JsonObject) {
        val staging = File.createTempFile("$prefix-", ".json.tmp", directory)
        try {
            FileOutputStream(staging).use { stream ->
                stream.write(json.toString().toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            syncDirectory(directory)
        } finally { staging.delete() }
    }

    private fun safeFile(name: String): File = File(this.directory, name).canonicalFile.also {
        require(it.parentFile == this.directory && it.name == name) { "下载文件路径无效" }
    }

    private fun isPrefixDescriptor(saved: JsonObject, updated: HealthMessage.ListItem): Boolean =
        saved.get("schema_version")?.asInt == 1 &&
            saved.get("session_id")?.asString == sessionId &&
            saved.get("ring_address")?.asString == ringAddress &&
            saved.get("device_session_id")?.asInt == updated.sessionId &&
            saved.get("anchor_uptime_ms")?.asLong == updated.uptimeMs &&
            saved.get("anchor_unix_ms")?.asLong == updated.unixMs &&
            saved.get("started_at_ms")?.asLong == startedAtMs &&
            saved.get("ended_at_ms")?.asLong == endedAtMs &&
            saved.get("bytes")?.asLong in 1..updated.bytes &&
            saved.get("records")?.asLong in 1..updated.records

    private fun migrateCompletedPrefix(bytes: Long) {
        output?.close()
        output = null
        completed = null
        archivePrefixFile(destination, safeFile("$prefix.prefix-$bytes.rfbin"))
        archivePrefixFile(evidenceFile, safeFile("$prefix.prefix-$bytes.raw-evidence.json"))
    }

    private fun archivePrefixFile(source: File, archive: File) {
        if (!source.exists()) return
        if (archive.exists()) {
            require(source.length() == archive.length() && sha256(source) == sha256(archive)) {
                "已保存的原始前缀证据冲突"
            }
            require(source.delete()) { "无法整理已保存的原始前缀" }
        } else {
            Files.move(source.toPath(), archive.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }
        syncDirectory(directory)
    }

    private fun verifyFinal(value: Completed) {
        require(destination.isFile && destination.length() == value.file.bytes &&
            sha256(destination) == value.file.sha256) { "已保存的原始文件校验失败" }
        syncDirectory(directory)
    }

    private fun prefixHash(): String = try {
        hex((prefixDigest.clone() as MessageDigest).digest())
    } catch (_: CloneNotSupportedException) {
        sha256(partial, nextOffset)
    }

    companion object {
        private const val MAX_NO_PROGRESS_WINDOWS = 3

        /** Remove only restart leftovers belonging to a durably completed real session. */
        internal fun cleanupCommittedTemporary(
            directory: File,
            session: FreeLivingSession,
            syncDirectory: (File) -> Unit = {
                FileChannel.open(it.toPath(), StandardOpenOption.READ).use { channel -> channel.force(true) }
            },
        ) {
            val local = requireNotNull(session.localData) { "本次原始文件尚未完成" }
            val record = session.deviceRecordEvidence?.record ?: return
            val root = directory.canonicalFile
            require(root.isDirectory)
            val prefix = "${session.sessionId}-ring-${record.sessionId}"
            val fixed = listOf("$prefix.part", "$prefix.download.json")
            val archived = root.listFiles()?.filter {
                it.name.startsWith("$prefix.prefix-") &&
                    (it.name.endsWith(".rfbin") || it.name.endsWith(".raw-evidence.json"))
            }.orEmpty()
            val leftovers = fixed.map { File(root, it) }.filter { it.exists() } + archived
            if (leftovers.isEmpty()) return
            val finalName = "$prefix.rfbin"
            val finalEntry = local.files.singleOrNull { !it.simulated && it.fileName == finalName }
                ?: throw IllegalArgumentException("本次原始文件与设备记录不匹配")
            val finalFile = File(root, finalName).canonicalFile
            require(finalFile.parentFile == root && finalFile.isFile && finalFile.length() == finalEntry.bytes &&
                sha256(finalFile) == finalEntry.sha256) { "已完成的原始文件校验失败" }
            leftovers.forEach { file ->
                val safe = file.canonicalFile
                require(safe.parentFile == root && safe.name == file.name)
                if (safe.exists()) require(safe.isFile && safe.delete()) { "已完成记录的临时文件清理失败" }
            }
            syncDirectory(root)
        }

        internal fun inspectPayload(file: File, record: HealthMessage.ListItem): HealthPayloadEvidence {
            require(file.length() == record.bytes) { "原始数据字节数不完整" }
            var records = 0L
            var imuSamples = 0L
            var ppgSamples = 0L
            var firstImu: Long? = null
            var lastImu: Long? = null
            var firstPpg: Long? = null
            var lastPpg: Long? = null
            val crc = CRC32()
            DataInputStream(java.util.zip.CheckedInputStream(FileInputStream(file).buffered(), crc)).use { input ->
                while (true) {
                    val command = input.read()
                    if (command < 0) break
                    require(command == 0x32) { "原始记录头无效" }
                    when (val subcommand = input.readUnsignedByte()) {
                        0x10 -> input.readFully(ByteArray(15))
                        0x11 -> {
                            input.readUnsignedShort() // sequence, little-endian; preserved, not interpreted
                            input.readUnsignedByte() // mode
                            input.readUnsignedByte() // reserved
                            val count = input.readUnsignedByte()
                            val mask = input.readUnsignedByte()
                            val uptime = input.u32le()
                            require(count > 0 && mask in 1..7) { "PPG 采样参数无效" }
                            input.readFully(ByteArray(count * Integer.bitCount(mask) * 4))
                            ppgSamples += count
                            if (firstPpg == null) firstPpg = uptime
                            lastPpg = uptime
                        }
                        0x12 -> {
                            val count = input.readUnsignedByte()
                            val uptime = input.u32le()
                            require(count > 0) { "IMU 采样参数无效" }
                            input.readFully(ByteArray(count * 6))
                            imuSamples += count
                            if (firstImu == null) firstImu = uptime
                            lastImu = uptime
                        }
                        else -> throw IOException("未知原始记录类型：$subcommand")
                    }
                    records++
                }
            }
            require(records == record.records) { "原始记录数与戒指列表不一致" }
            return HealthPayloadEvidence(record.bytes, records, crc.value, imuSamples, ppgSamples,
                firstImu, lastImu, firstPpg, lastPpg)
        }

        private fun verifyContainer(file: File, header: ByteArray, evidence: HealthPayloadEvidence) {
            require(file.length() == HealthRawV2.HEADER_SIZE + evidence.payloadBytes) { "原始容器长度不完整" }
            val (actualHeader, crc) = readContainerChecksum(file)
            require(actualHeader.contentEquals(header)) { "原始容器记录信息不匹配" }
            require(crc == evidence.crc32) { "原始容器 CRC 校验失败" }
        }

        /** Recheck the original container before releasing an unconfirmed-start protection gate. */
        internal fun verifyPreservedContainer(file: File, record: HealthMessage.ListItem,
            startedAtMs: Long, endedAtMs: Long) {
            require(file.length() == HealthRawV2.HEADER_SIZE + record.bytes) { "原始容器长度不完整" }
            val (actualHeader, crc) = readContainerChecksum(file)
            val expected = HealthRawV2.header(record, startedAtMs, endedAtMs, record.bytes, crc)
            require(actualHeader.contentEquals(expected)) { "原始容器记录或 CRC 校验失败" }
        }

        private fun readContainerChecksum(file: File): Pair<ByteArray, Long> {
            file.inputStream().use { stream ->
                val actualHeader = ByteArray(HealthRawV2.HEADER_SIZE)
                DataInputStream(stream).readFully(actualHeader)
                val crc = CRC32()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    crc.update(buffer, 0, count)
                }
                return actualHeader to crc.value
            }
        }

        private fun sha256(file: File, limit: Long = file.length()): String {
            val digest = MessageDigest.getInstance("SHA-256")
            hashInto(file, limit, digest)
            return hex(digest.digest())
        }

        private fun hashInto(file: File, limit: Long, digest: MessageDigest) {
            file.inputStream().use { input ->
                var remaining = limit
                val buffer = ByteArray(64 * 1024)
                while (remaining > 0L) {
                    val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                    if (count < 0) throw IOException("原始片段长度不足")
                    digest.update(buffer, 0, count)
                    remaining -= count
                }
            }
        }

        private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

        private fun DataInputStream.u32le(): Long = readUnsignedByte().toLong() or
            (readUnsignedByte().toLong() shl 8) or (readUnsignedByte().toLong() shl 16) or
            (readUnsignedByte().toLong() shl 24)
    }
}
