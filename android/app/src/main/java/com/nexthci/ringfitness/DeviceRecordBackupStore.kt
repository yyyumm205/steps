package com.nexthci.ringfitness

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.File
import java.io.FileOutputStream
import java.io.StringReader
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID

/** Unassigned device originals. These backups have no participant, reference value or upload task. */
class DeviceRecordBackupStore internal constructor(
    directory: File,
    private val syncDirectory: (File) -> Unit,
) {
    constructor(directory: File) : this(directory,
        { FileChannel.open(it.toPath(), StandardOpenOption.READ).use { channel -> channel.force(true) } })

    private val directory = directory.canonicalFile
    private data class Metadata(val backupId: String, val address: String, val record: HealthMessage.ListItem)

    /** Missing receipts are incomplete. A damaged receipt or original raises an error and is retained. */
    fun isPreserved(address: String, record: HealthMessage.ListItem): Boolean = synchronized(lock) {
        val folder = folder(address, record)
        if (!folder.exists()) return@synchronized false
        require(folder.isDirectory)
        val metadataFile = File(folder, "metadata.json")
        if (!metadataFile.exists()) {
            require(folder.listFiles()?.isEmpty() == true) { "设备备份信息缺失，原文件已保留" }
            return@synchronized false
        }
        val metadata = readMetadata(folder, address, record)
        if (!File(folder, "receipt.json").exists()) return@synchronized false
        verifyReceipt(folder, metadata)
        syncDirectory(directory)
        syncDirectory(requireNotNull(directory.parentFile))
        true
    }

    fun open(address: String, record: HealthMessage.ListItem, unknownAttemptId: String? = null): RealSessionDownload = synchronized(lock) {
        val folder = folder(address, record, unknownAttemptId)
        ensureDirectory(directory, requireNotNull(directory.parentFile))
        ensureDirectory(folder, directory)
        val metadataFile = File(folder, "metadata.json")
        if (!metadataFile.exists()) {
            require(folder.listFiles()?.isEmpty() == true) { "设备备份信息缺失，原文件已保留" }
            writeAtomic(metadataFile, encodeMetadata(Metadata(unknownAttemptId ?: UUID.randomUUID().toString(), address, record)))
        }
        val metadata = readMetadata(folder, address, record)
        require(!File(folder, "receipt.json").exists()) { "设备记录已备份，请使用已有原文件" }
        RealSessionDownload(folder, metadata.backupId, address, record, 0, 0, syncDirectory)
    }

    /** Commit the receipt before the caller releases the downloader's durable partial files. */
    fun accept(address: String, record: HealthMessage.ListItem, completed: RealSessionDownload.Completed,
        savedAtMs: Long, unknownAttemptId: String? = null) = synchronized(lock) {
        require(savedAtMs > 0)
        val folder = folder(address, record, unknownAttemptId)
        val metadata = readMetadata(folder, address, record)
        validateFile(folder, metadata, completed.file)
        require(completed.evidence.payloadBytes == record.bytes && completed.evidence.records == record.records)
        require(completed.evidenceFileName == "${metadata.backupId}-ring-${record.sessionId}.raw-evidence.json")
        val receipt = File(folder, "receipt.json")
        if (receipt.exists()) {
            require(verifyReceipt(folder, metadata) == completed.file) { "已有设备备份回执与文件不一致" }
            return@synchronized
        }
        writeAtomic(receipt, encodeReceipt(metadata, completed.file, savedAtMs))
        require(verifyReceipt(folder, metadata) == completed.file)
    }

    /** This verifies a local original only; its zero clock never identifies a reconnected device record. */
    fun verifyUnknown(address: String, record: HealthMessage.ListItem, attemptId: String): SessionRawFile = synchronized(lock) {
        val folder = folder(address, record, attemptId)
        val metadata = readMetadata(folder, address, record)
        require(metadata.backupId == attemptId)
        verifyReceipt(folder, metadata).also { syncDirectory(directory); syncDirectory(requireNotNull(directory.parentFile)) }
    }

    private fun folder(address: String, record: HealthMessage.ListItem, unknownAttemptId: String? = null): File {
        require(Regex("^(?:[0-9A-F]{2}:){5}[0-9A-F]{2}$").matches(address))
        require(record.sessionId in 1..65535 && record.bytes in 1..0xFFFF_FFFFL &&
            record.records in 1..0xFFFF_FFFFL && record.uptimeMs in 1..0xFFFF_FFFFL &&
            ((unknownAttemptId == null && record.unixMs > 0) || (unknownAttemptId != null && record.unixMs == 0L))) {
            "戒指记录指纹不完整，请保留原数据并联系研究者"
        }
        unknownAttemptId?.let { require(UUID.fromString(it).toString() == it) }
        val key = unknownAttemptId?.let { "unknown-$it" }
            ?: sha256("$address|${record.sessionId}|${record.bytes}|${record.records}|${record.uptimeMs}|${record.unixMs}".toByteArray())
        return File(directory, key).canonicalFile.also { require(it.parentFile == directory && it.name == key) }
    }

    private fun readMetadata(folder: File, address: String, record: HealthMessage.ListItem): Metadata {
        val json = readJson(File(folder, "metadata.json"))
        val id = json.strictString("backup_id")
        require(UUID.fromString(id).toString() == id)
        val metadata = Metadata(id, address, record)
        require(encodeMetadata(metadata) == json) { "设备备份指纹不匹配，原文件已保留" }
        return metadata
    }

    private fun verifyReceipt(folder: File, metadata: Metadata): SessionRawFile {
        val json = readJson(File(folder, "receipt.json"))
        val savedAt = json.strictLong("saved_at_ms")
        require(savedAt > 0)
        val raw = requireNotNull(json.getAsJsonObject("raw_file"))
        val simulated = raw.get("simulated")
        require(simulated?.isJsonPrimitive == true && simulated.asJsonPrimitive.isBoolean && !simulated.asBoolean)
        val file = SessionRawFile(raw.strictString("file_name"), Math.toIntExact(raw.strictLong("device_session_id")),
            raw.strictLong("bytes"), raw.strictString("sha256"), false)
        require(encodeReceipt(metadata, file, savedAt) == json) { "设备备份回执字段无效，原文件已保留" }
        validateFile(folder, metadata, file)
        syncDirectory(folder)
        return file
    }

    private fun validateFile(folder: File, metadata: Metadata, entry: SessionRawFile) {
        require(entry.fileName == "${metadata.backupId}-ring-${metadata.record.sessionId}.rfbin" &&
            entry.deviceSessionId == metadata.record.sessionId && !entry.simulated &&
            entry.bytes == HealthRawV2.HEADER_SIZE + metadata.record.bytes && Regex("^[0-9a-f]{64}$").matches(entry.sha256))
        val raw = File(folder, entry.fileName).canonicalFile
        require(raw.parentFile == folder && raw.name == entry.fileName)
        check(raw.isFile && raw.length() == entry.bytes && sha256(raw) == entry.sha256) { "设备备份原文件校验失败，已保留现有文件" }
        RealSessionDownload.verifyPreservedContainer(raw, metadata.record, 0, 0)
        FileOutputStream(raw, true).use { it.fd.sync() }
    }

    private fun encodeMetadata(metadata: Metadata) = JsonObject().apply {
        addProperty("backup_version", 1)
        addProperty("purpose", "unassigned_device_preservation")
        addProperty("backup_id", metadata.backupId)
        addProperty("ring_address", metadata.address)
        add("record", JsonObject().apply {
            addProperty("device_session_id", metadata.record.sessionId)
            addProperty("bytes", metadata.record.bytes)
            addProperty("records", metadata.record.records)
            addProperty("uptime_ms", metadata.record.uptimeMs)
            addProperty("unix_ms", metadata.record.unixMs)
        })
    }

    private fun encodeReceipt(metadata: Metadata, file: SessionRawFile, savedAtMs: Long) = encodeMetadata(metadata).apply {
        addProperty("saved_at_ms", savedAtMs)
        add("raw_file", JsonObject().apply {
            addProperty("file_name", file.fileName)
            addProperty("device_session_id", file.deviceSessionId)
            addProperty("bytes", file.bytes)
            addProperty("sha256", file.sha256)
            addProperty("simulated", file.simulated)
        })
    }

    private fun readJson(file: File): JsonObject {
        require(file.isFile && file.length() in 1..65_536) { "设备备份信息缺失或无效" }
        return JsonReader(StringReader(file.readText(Charsets.UTF_8))).use { reader ->
            reader.strictness = Strictness.STRICT
            val result = JsonParser.parseReader(reader).asJsonObject
            require(reader.peek() == JsonToken.END_DOCUMENT)
            result
        }
    }

    private fun writeAtomic(file: File, json: JsonObject) {
        val parent = requireNotNull(file.parentFile)
        val temporary = File.createTempFile("backup-", ".tmp", parent)
        try {
            FileOutputStream(temporary).use { it.write(json.toString().toByteArray(Charsets.UTF_8)); it.fd.sync() }
            require(!file.exists()) { "设备备份信息已存在，原文件已保留" }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
            syncDirectory(parent)
        } finally { temporary.delete() }
    }

    private fun ensureDirectory(folder: File, parent: File) {
        require(parent.isDirectory && folder.canonicalFile.parentFile == parent.canonicalFile)
        if (!folder.exists()) check(folder.mkdir())
        require(folder.isDirectory)
        syncDirectory(parent)
    }

    private fun JsonObject.strictString(name: String): String = requireNotNull(get(name)).let {
        require(it.isJsonPrimitive && it.asJsonPrimitive.isString); it.asString
    }
    private fun JsonObject.strictLong(name: String): Long = requireNotNull(get(name)).let {
        require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber && Regex("[0-9]+").matches(it.asString)); it.asString.toLong()
    }

    companion object {
        private val lock = Any()
        private fun sha256(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
        private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").let { digest ->
            file.inputStream().use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            hex(digest.digest())
        }
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
