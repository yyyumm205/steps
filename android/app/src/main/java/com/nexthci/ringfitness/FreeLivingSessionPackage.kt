package com.nexthci.ringfitness

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.CheckedInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class FrozenSessionPackage(val file: File, val sha256: String, val bytes: Long, val sessionId: String)

/**
 * Freezes research metadata and raw bytes as one atomic directory. Retry verifies the same
 * archive; it never rebuilds a published package or changes its acquisition timestamps.
 * The serial upload owner supplies a real-session directory, never the demo directory.
 */
class FreeLivingSessionPackage internal constructor(
    directory: File,
    private val store: FreeLivingSessionStore,
    private val syncDirectory: (File) -> Unit,
    private val commitDirectory: (File, File) -> Unit,
    private val now: () -> Instant = { Instant.now() },
) {
    constructor(directory: File, store: FreeLivingSessionStore) : this(
        directory, store,
        { FileChannel.open(it.toPath(), StandardOpenOption.READ).use { channel -> channel.force(true) } },
        { source, target -> Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE) },
    )

    private val directory = directory.canonicalFile
    private val packages = child(this.directory, "packages")
    private data class Entry(val file: File, val manifest: JsonObject)
    private data class Snapshot(val manifest: JsonObject, val entries: List<Entry>)
    private data class RawHeader(val payloadBytes: Long, val crc: Long, val records: Long,
        val anchorUptimeMs: Long, val anchorUnixMs: Long)

    fun freeze(session: FreeLivingSession): FrozenSessionPackage = synchronized(lock) {
        require(UUID.fromString(session.sessionId).toString() == session.sessionId) { "采集段标识无效" }
        require(directory.isDirectory) { "采集目录尚未就绪" }
        ensureDirectory(packages, directory)
        val target = child(packages, session.sessionId)
        // For an existing provenance package, its persisted timestamp is part of the immutable
        // research snapshot. A new package receives the time of this first freeze attempt.
        val savedManifest = if (target.exists()) runCatching { readJson(child(target, SNAPSHOT)) }.getOrNull() else null
        val packageCreatedAt = savedManifest?.get("created_at")?.takeUnless { it.isJsonNull }?.asString
            ?.let(Instant::parse) ?: now()
        val initial = snapshot(session.sessionId, packageCreatedAt)
        if (target.exists()) {
            val savedVersion = savedManifest?.get("version")?.asInt
            val expected = snapshotForVersion(initial, requireNotNull(savedVersion) { "冻结上传包缺少版本" })
            return@synchronized verifyFrozen(target, session.sessionId, expected)
        }

        val staging = Files.createTempDirectory(packages.toPath(), ".${session.sessionId}-").toFile()
        try {
            val archive = child(staging, archiveName(session.sessionId))
            writeArchive(archive, initial)
            val frozen = FrozenSessionPackage(archive, digest(archive), archive.length(), session.sessionId)
            // A file or metadata change during packaging must not become a frozen snapshot.
            val latest = snapshot(session.sessionId, packageCreatedAt)
            require(latest.manifest == initial.manifest) { "采集信息发生变化，上传包未冻结" }
            verifyZip(archive, initial)
            writeSynced(child(staging, SNAPSHOT), initial.manifest.toString().toByteArray(Charsets.UTF_8))
            writeSynced(child(staging, METADATA), JsonObject().apply {
                addProperty("package_version", 1)
                addProperty("session_id", session.sessionId)
                addProperty("file_name", archive.name)
                addProperty("bytes", frozen.bytes)
                addProperty("sha256", frozen.sha256)
                addProperty("manifest_sha256", digest(initial.manifest.toString().toByteArray(Charsets.UTF_8)))
            }.toString().toByteArray(Charsets.UTF_8))
            syncDirectory(staging)
            require(!target.exists()) { "已有冻结上传包，已保留原文件" }
            commitDirectory(staging, target)
            syncDirectory(packages)
            verifyFrozen(target, session.sessionId, latest)
        } finally {
            // Only files generated in this attempt are removed; committed packages stay intact.
            if (staging.exists()) {
                listOf(archiveName(session.sessionId), SNAPSHOT, METADATA).forEach { child(staging, it).delete() }
                staging.delete()
            }
        }
    }

    /** Removes an unpublished snapshot before a local reference correction is committed. */
    fun invalidateUnpublished(sessionId: String): Boolean = synchronized(lock) {
        require(UUID.fromString(sessionId).toString() == sessionId) { "采集段标识无效" }
        val session = requireNotNull(store.read(sessionId)) { "采集段不匹配" }
        require(session.completionPolicy in setOf(CompletionPolicy.SAVE_LATER, CompletionPolicy.SAVE_UPLOAD) &&
            session.transfer.status == SessionTransferStatus.PENDING && session.transfer.attempts == 0 &&
            session.transfer.receipt == null) { "本段已确认上传，原上传包保持不变" }
        if (!packages.exists()) return@synchronized false
        require(packages.isDirectory)
        val target = child(packages, sessionId)
        if (!target.exists()) return@synchronized false
        val savedManifest = readJson(child(target, SNAPSHOT))
        val savedVersion = savedManifest.get("version")?.asInt ?: throw IOException("冻结上传包缺少版本")
        val createdAt = if (savedVersion >= 6) {
            savedManifest.get("created_at")?.takeUnless { it.isJsonNull }?.asString?.let(Instant::parse)
                ?: throw IOException("冻结上传包缺少创建时间")
        } else {
            Instant.ofEpochMilli(session.startRequestedAtMs)
        }
        verifyFrozen(target, sessionId, snapshotForVersion(snapshot(sessionId, createdAt), savedVersion))
        val obsolete = child(packages, ".obsolete-$sessionId-${UUID.randomUUID()}")
        Files.move(target.toPath(), obsolete.toPath(), StandardCopyOption.ATOMIC_MOVE)
        syncDirectory(packages)
        listOf(archiveName(sessionId), SNAPSHOT, METADATA).forEach { child(obsolete, it).delete() }
        obsolete.delete()
        true
    }

    /** Keeps package invalidation and the replacement reference atomic with upload publication. */
    fun reviseUnpublishedReference(sessionId: String, reference: SessionReference): FreeLivingSession =
        synchronized(lock) {
            invalidateUnpublished(sessionId)
            store.reviseReferenceBeforeUpload(sessionId, reference)
        }

    private fun snapshot(sessionId: String, packageCreatedAt: Instant): Snapshot {
        val manifest = store.manifestSnapshot(sessionId, packageCreatedAt)
        val rawEntries = manifest.getAsJsonArray("files").map { item ->
            val entry = item.asJsonObject.deepCopy()
            val name = entry.get("file_name").asString
            require(name.startsWith("$sessionId-") && name.endsWith(".rfbin") && SIMPLE_NAME.matches(name)) {
                "原始文件名称无效"
            }
            val source = child(directory, name)
            verifySource(source, entry)
            Entry(source, entry)
        }
        require(rawEntries.isNotEmpty() && rawEntries.map { it.file.name }.distinct().size == rawEntries.size)
        val entries = rawEntries.flatMap { raw ->
            val header = verifyRaw(raw.file, raw.manifest.get("device_session_id").asInt,
                manifest.get("started_at_ms").takeUnless { it.isJsonNull }?.asLong ?: 0,
                manifest.get("ended_at_ms").takeUnless { it.isJsonNull }?.asLong ?: 0)
            val device = manifest.get("device_record_evidence").takeUnless { it.isJsonNull }?.asJsonObject?.getAsJsonObject("record")
            device?.let {
                require(header.anchorUptimeMs == it.get("uptime_ms").asLong &&
                    header.anchorUnixMs == it.get("unix_ms").asLong) { "原始容器时间锚与设备证据不一致" }
                if (rawEntries.size == 1) require(header.payloadBytes == it.get("bytes").asLong &&
                    header.records == it.get("records").asLong) { "原始容器记录量与设备证据不一致" }
            }
            val evidenceFile = child(directory, raw.file.name.removeSuffix(".rfbin") + ".raw-evidence.json")
            if (!evidenceFile.exists()) listOf(raw) else {
                require(evidenceFile.isFile && evidenceFile.length() <= MAX_METADATA_BYTES) { "原始文件证据无效" }
                val evidence = JsonParser.parseString(evidenceFile.readText(Charsets.UTF_8)).asJsonObject
                require(evidence.get("session_id")?.asString == sessionId &&
                    evidence.get("device_session_id")?.asInt == raw.manifest.get("device_session_id").asInt &&
                    evidence.get("file_sha256")?.asString == raw.manifest.get("sha256").asString &&
                    evidence.get("bytes")?.asLong == header.payloadBytes && evidence.get("payload_crc32")?.asLong == header.crc) {
                    "原始文件证据与采集段不一致"
                }
                val entry = JsonObject().apply {
                    addProperty("file_name", evidenceFile.name)
                    addProperty("role", "evidence")
                    addProperty("device_session_id", raw.manifest.get("device_session_id").asInt)
                    addProperty("bytes", evidenceFile.length())
                    addProperty("sha256", digest(evidenceFile))
                    addProperty("simulated", false)
                }
                listOf(raw, Entry(evidenceFile, entry))
            }
        }.sortedBy { it.file.name }
        manifest.add("files", JsonArray().apply { entries.forEach { add(it.manifest) } })
        return Snapshot(manifest, entries)
    }

    private fun legacySnapshot(current: Snapshot): Snapshot {
        val manifest = current.manifest.deepCopy().apply {
            listOf("ring_placement_schema", "ring_hand", "ring_finger", "app_version", "created_at",
                "stop_origin", "stop_observed_at_ms").forEach(::remove)
            val baseline = getAsJsonObject("start_baseline")
            val version = when {
                baseline?.get("unknown_time_start_evidence")?.isJsonNull == false -> 5
                get("activity_schema")?.asString == "daily_activity_v3" -> 4
                baseline?.get("charging_recovery_evidence")?.isJsonNull == false -> 3
                else -> 2
            }
            addProperty("version", version)
        }
        return Snapshot(manifest, current.entries)
    }

    private fun snapshotForVersion(current: Snapshot, version: Int): Snapshot = when (version) {
        in 2..5 -> legacySnapshot(current).also {
            require(it.manifest.get("version").asInt == version) { "冻结上传包版本与采集证据不一致" }
        }
        6 -> Snapshot(current.manifest.deepCopy().apply {
            addProperty("version", 6)
            remove("stop_origin")
            remove("stop_observed_at_ms")
        }, current.entries)
        7 -> current
        else -> throw IOException("冻结上传包版本不受支持")
    }

    private fun writeArchive(archive: File, snapshot: Snapshot) {
        FileOutputStream(archive).use { file ->
            ZipOutputStream(BufferedOutputStream(file, BUFFER_SIZE)).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json").apply { time = ZIP_TIME })
                zip.write(snapshot.manifest.toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                for (entry in snapshot.entries) {
                    zip.putNextEntry(ZipEntry(entry.file.name).apply { time = ZIP_TIME })
                    entry.file.inputStream().use { it.copyTo(zip, BUFFER_SIZE) }
                    zip.closeEntry()
                }
                zip.finish()
                zip.flush()
                file.fd.sync()
            }
        }
    }

    private fun verifyFrozen(target: File, sessionId: String, snapshot: Snapshot): FrozenSessionPackage {
        require(target.isDirectory) { "冻结上传包目录无效" }
        val savedManifest = readJson(child(target, SNAPSHOT))
        require(savedManifest == snapshot.manifest) { "采集信息与冻结上传包不同，已保留原包" }
        val metadata = readJson(child(target, METADATA))
        val archive = child(target, archiveName(sessionId))
        require(metadata.get("package_version")?.asInt == 1 && metadata.get("session_id")?.asString == sessionId &&
            metadata.get("file_name")?.asString == archive.name && archive.isFile &&
            metadata.get("bytes")?.asLong == archive.length() &&
            metadata.get("manifest_sha256")?.asString == digest(savedManifest.toString().toByteArray(Charsets.UTF_8))) {
            "冻结上传包信息校验失败"
        }
        val hash = digest(archive)
        require(metadata.get("sha256")?.asString == hash) { "冻结上传包校验失败，已保留原包" }
        verifyZip(archive, snapshot)
        syncDirectory(target)
        syncDirectory(packages)
        return FrozenSessionPackage(archive, hash, archive.length(), sessionId)
    }

    private fun verifyZip(archive: File, snapshot: Snapshot) {
        ZipFile(archive).use { zip ->
            val all = zip.entries().asSequence().toList()
            val expected = snapshot.entries.associateBy { it.file.name }
            require(all.size == expected.size + 1 && all.map { it.name }.toSet() == expected.keys + "manifest.json" &&
                all.none { it.isDirectory }) { "上传包文件清单不一致" }
            val manifest = zip.getEntry("manifest.json")
            require(manifest.size in 1..MAX_METADATA_BYTES) { "上传包清单长度无效" }
            val decoded = zip.getInputStream(manifest).use { JsonParser.parseString(it.reader(Charsets.UTF_8).readText()) }
            require(decoded == snapshot.manifest) { "上传包采集信息校验失败" }
            for ((name, entry) in expected) {
                val stored = zip.getEntry(name)
                require(stored.size == entry.manifest.get("bytes").asLong) { "上传包文件长度不完整" }
                val result = zip.getInputStream(stored).use(::digest)
                require(result == entry.manifest.get("sha256").asString) { "上传包文件哈希校验失败" }
            }
        }
    }

    private fun verifyRaw(file: File, deviceSessionId: Int, startedAtMs: Long, endedAtMs: Long): RawHeader {
        DataInputStream(BufferedInputStream(FileInputStream(file), BUFFER_SIZE)).use { stream ->
            val raw = ByteArray(64)
            stream.readFully(raw)
            require(raw.copyOfRange(0, 8).contentEquals(byteArrayOf(82, 70, 86, 50, 82, 65, 87, 0))) { "原始容器头无效" }
            val header = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            require(header.getShort(8).toInt() == 2 && header.getShort(10).toInt() == 64 &&
                (header.getShort(12).toInt() and 0xffff) == deviceSessionId &&
                header.getLong(32) == startedAtMs && header.getLong(40) == endedAtMs) { "原始容器与采集段不一致" }
            val payloadBytes = header.getLong(48)
            val crc = header.getInt(56).toLong() and 0xffff_ffffL
            require(payloadBytes > 0 && payloadBytes == file.length() - 64) { "原始容器长度不完整" }
            val expectedRecords = header.getInt(16).toLong() and 0xffff_ffffL
            val actual = CRC32()
            val packets = DataInputStream(CheckedInputStream(stream, actual))
            var parsedRecords = 0L
            while (true) {
                val command = packets.read()
                if (command < 0) break
                require(command == 0x32) { "原始记录头无效" }
                when (packets.readUnsignedByte()) {
                    0x10 -> packets.readFully(ByteArray(15))
                    0x11 -> {
                        packets.readInt() // sequence, mode and reserved bytes remain unmodified
                        val count = packets.readUnsignedByte()
                        val mask = packets.readUnsignedByte()
                        packets.readInt() // original uptime
                        require(count > 0 && mask in 1..7) { "PPG 原始记录无效" }
                        packets.readFully(ByteArray(count * Integer.bitCount(mask) * 4))
                    }
                    0x12 -> {
                        val count = packets.readUnsignedByte()
                        packets.readInt() // original uptime
                        require(count > 0) { "IMU 原始记录无效" }
                        packets.readFully(ByteArray(count * 6))
                    }
                    else -> throw IOException("未知原始记录类型")
                }
                parsedRecords++
            }
            require(parsedRecords > 0 && parsedRecords == expectedRecords) { "原始容器记录数不一致" }
            require(actual.value == crc) { "原始容器 CRC 校验失败" }
            return RawHeader(payloadBytes, crc, parsedRecords,
                header.getInt(20).toLong() and 0xffff_ffffL, header.getLong(24))
        }
    }

    private fun verifySource(file: File, entry: JsonObject) {
        require(file.isFile && file.length() == entry.get("bytes").asLong &&
            digest(file) == entry.get("sha256").asString && !entry.get("simulated").asBoolean) { "原始文件校验失败" }
    }

    private fun ensureDirectory(target: File, parent: File) {
        if (!target.exists()) check(target.mkdir()) { "无法建立上传包目录" }
        require(target.isDirectory)
        syncDirectory(parent)
    }

    private fun readJson(file: File): JsonObject {
        require(file.isFile && file.length() in 1..MAX_METADATA_BYTES) { "冻结上传包信息缺失" }
        return JsonParser.parseString(file.readText(Charsets.UTF_8)).asJsonObject
    }

    private fun writeSynced(file: File, bytes: ByteArray) = FileOutputStream(file).use {
        it.write(bytes)
        it.fd.sync()
    }

    companion object {
        private val lock = Any()
        internal fun <T> withPublicationLock(action: () -> T): T = synchronized(lock, action)
        private const val SNAPSHOT = "manifest.snapshot.json"
        private const val METADATA = "package.json"
        private const val MAX_METADATA_BYTES = 2L * 1024 * 1024
        private const val BUFFER_SIZE = 64 * 1024
        private const val ZIP_TIME = 315_532_800_000L
        private val SIMPLE_NAME = Regex("^[a-zA-Z0-9._-]+$")
        private fun archiveName(sessionId: String) = "ringfitness-session-$sessionId.zip"
        private fun child(parent: File, name: String): File = File(parent, name).canonicalFile.also {
            require(it.parentFile == parent.canonicalFile && it.name == name) { "上传包文件路径无效" }
        }
        private fun digest(file: File): String = file.inputStream().use(::digest)
        private fun digest(input: InputStream): String {
            val sha = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                sha.update(buffer, 0, count)
            }
            return hex(sha.digest())
        }
        private fun digest(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
