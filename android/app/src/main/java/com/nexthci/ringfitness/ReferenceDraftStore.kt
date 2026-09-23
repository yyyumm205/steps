package com.nexthci.ringfitness

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID

data class ReferenceDraft(val sessionId: String, val stepsText: String)

/** A recoverable UI draft kept separate from the research session journal. */
class ReferenceDraftStore internal constructor(
    file: File,
    private val commitFile: (File, File) -> Unit,
    private val syncDirectory: (File) -> Unit,
) {
    constructor(file: File) : this(file, { source, target ->
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING)
    }, { directory ->
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
    })

    private val file = file.canonicalFile

    fun read(sessionId: String): ReferenceDraft? = synchronized(lock) {
        readAny()?.takeIf { it.sessionId == sessionId }
    }

    fun save(sessionId: String, stepsText: String): ReferenceDraft? = synchronized(lock) {
        requireValidSessionId(sessionId)
        if (stepsText.isEmpty()) {
            clearLocked(sessionId)
            return@synchronized null
        }
        require(stepsText.matches(STEPS_PATTERN)) { "请输入计步器上的整数" }
        val draft = ReferenceDraft(sessionId, stepsText)
        writeLocked(draft)
        draft
    }

    fun clear(sessionId: String) = synchronized(lock) {
        requireValidSessionId(sessionId)
        clearLocked(sessionId)
    }

    fun clearAll() = synchronized(lock) {
        deleteLocked()
    }

    private fun clearLocked(sessionId: String) {
        val current = readAny()
        if (current == null || current.sessionId == sessionId) deleteLocked()
    }

    private fun deleteLocked() {
        if (!file.exists()) return
        check(file.delete()) { "计步器读数草稿清理失败" }
        file.parentFile?.let(syncDirectory)
    }

    private fun readAny(): ReferenceDraft? = runCatching {
        if (!file.isFile || file.length() !in 1..MAX_FILE_BYTES) return@runCatching null
        val root = JsonParser.parseString(file.readText(Charsets.UTF_8)).asJsonObject
        val version = root.primitive(VERSION_KEY)?.asInt
        val sessionId = root.primitive(SESSION_ID_KEY)?.asString
        val stepsText = root.primitive(STEPS_KEY)?.asString
        val checksum = root.primitive(CHECKSUM_KEY)?.asString
        if (version != VERSION || sessionId == null || stepsText == null || checksum == null ||
            runCatching { requireValidSessionId(sessionId) }.isFailure ||
            !stepsText.matches(STEPS_PATTERN) || checksum != checksum(sessionId, stepsText)) null
        else ReferenceDraft(sessionId, stepsText)
    }.getOrNull()

    private fun writeLocked(draft: ReferenceDraft) {
        val parent = requireNotNull(file.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "计步器读数草稿目录创建失败" }
        val root = JsonObject().apply {
            addProperty(VERSION_KEY, VERSION)
            addProperty(SESSION_ID_KEY, draft.sessionId)
            addProperty(STEPS_KEY, draft.stepsText)
            addProperty(CHECKSUM_KEY, checksum(draft.sessionId, draft.stepsText))
        }
        val temporary = File.createTempFile("reference-draft-", ".tmp", parent)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(root.toString().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            commitFile(temporary, file)
            syncDirectory(parent)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun JsonObject.primitive(name: String) = get(name)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive

    private fun requireValidSessionId(value: String) {
        require(runCatching { UUID.fromString(value).toString() == value.lowercase() }.getOrDefault(false)) {
            "采集段编号无效"
        }
    }

    private fun checksum(sessionId: String, stepsText: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$VERSION\n$sessionId\n$stepsText".toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    companion object {
        private val lock = Any()
        private val STEPS_PATTERN = Regex("[0-9]{1,19}")
        private const val VERSION = 1
        private const val MAX_FILE_BYTES = 4L * 1024L
        private const val VERSION_KEY = "version"
        private const val SESSION_ID_KEY = "session_id"
        private const val STEPS_KEY = "steps_text"
        private const val CHECKSUM_KEY = "checksum_sha256"
    }
}
