package com.nexthci.ringfitness

import com.google.gson.Gson
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID

/** Private durable destination binding and server receipt, separate from the frozen research ZIP. */
data class RealUploadTask(
    val version: Int = 1,
    val sessionId: String,
    val targetLink: String,
    val targetSha256: String,
    val state: String = "queued",
    val archiveSha256: String? = null,
    val receipt: RemoteSessionReceipt? = null,
    val receivedAtMs: Long? = null,
    val failureStage: String? = null,
)

class RealUploadQueue(
    private val directory: File,
    private val store: FreeLivingSessionStore,
    private val freeze: (FreeLivingSession) -> FrozenSessionPackage = FreeLivingSessionPackage(directory, store)::freeze,
    private val transport: SessionUploadTransport = SeafileSessionTransport(),
    private val now: () -> Long = System::currentTimeMillis,
    private val sync: (File) -> Unit = { FileChannel.open(it.toPath(), StandardOpenOption.READ).use { channel -> channel.force(true) } },
    private val changed: () -> Unit = {},
) {
    private val folder = File(directory, "upload-tasks")

    /** Reopening the app resumes durable work, including a manual retry queued before exit. */
    fun restore(configuredLink: String): Boolean {
        var pending = false
        store.listSessions().filter { it.isDiscarded }.forEach { session ->
            runCatching { synchronized(taskLock) { store.cleanupDiscardedSession(session.sessionId) } }
        }
        store.listSessions().filter { session ->
            session.uploadAllowed && session.transfer.status != SessionTransferStatus.COMPLETE &&
                session.localData?.files?.let { it.isNotEmpty() && it.none { file -> file.simulated } } == true
        }.forEach { session ->
            try {
                pending = enqueue(session.sessionId, configuredLink, retry = false) || pending
            } catch (_: Exception) {
                // Keep a damaged binding untouched while allowing other records to recover.
                runCatching { markFailed(session.sessionId) }
            }
        }
        return pending
    }

    fun enqueue(sessionId: String, configuredLink: String, retry: Boolean): Boolean = synchronized(taskLock) {
        val session = requireNotNull(store.read(sessionId))
        if (!session.uploadAllowed) return false
        require(session.localData != null && session.reference != null && session.localData.files.none { it.simulated })
        if (session.transfer.status == SessionTransferStatus.COMPLETE) return false
        var task = read(sessionId)
        if (task == null) {
            check(session.transfer.status == SessionTransferStatus.PENDING && session.transfer.attempts == 0) {
                "上传记录需要检查，已保留本地数据"
            }
            if (configuredLink.isBlank()) return false
            SeafileSessionTransport.validateLink(configuredLink)
            task = RealUploadTask(sessionId = sessionId, targetLink = configuredLink, targetSha256 = digest(configuredLink))
            save(task)
        } else if (task.state == "failed") {
            if (!retry) return false
            task = task.copy(state = "queued")
            save(task)
        }
        // Once bound, changes to the current build's link do not migrate an existing task.
        task.state != "complete"
    }

    fun queuedIds(): List<String> = synchronized(taskLock) {
        store.listSessions().filter { it.uploadAllowed && it.localData != null }.mapNotNull { session ->
            try {
                read(session.sessionId)?.takeIf { it.state in setOf("queued", "sending") }?.sessionId
            } catch (_: Exception) {
                // Preserve the damaged destination binding; other sessions can still progress.
                runCatching { markFailed(session.sessionId) }
                null
            }
        }
    }

    /** A persisted receipt is replayed locally after a crash; it never causes a second HTTP request. */
    fun run(sessionId: String, cancelled: () -> Boolean = { false }): Boolean = synchronized(deliveryLock) {
        var task = synchronized(taskLock) { read(sessionId) } ?: return false
        if (task.state !in setOf("queued", "sending")) return false
        var failureStage = "preparation"
        try {
            if (cancelled()) return true
            val session = requireNotNull(store.read(sessionId))
            if (!session.uploadAllowed) return false
            if (session.transfer.status == SessionTransferStatus.COMPLETE) {
                synchronized(taskLock) { save(task.copy(state = "complete", failureStage = null)) }
                return false
            }
            if (task.receipt == null) {
                if (session.transfer.status == SessionTransferStatus.TRANSFERRING) store.markTransferFailed(sessionId)
                store.markTransferStarted(sessionId)
                changed()
            }
            val archive = freeze(session)
            require(archive.sessionId == sessionId && archive.file.isFile && archive.bytes == archive.file.length())
            require(task.archiveSha256 == null || task.archiveSha256 == archive.sha256) { "上传文件发生变化，已保留原任务" }
            // A retry remains flagged until both the source files and frozen package pass again.
            if (task.failureStage != null) {
                task = task.copy(failureStage = null)
                synchronized(taskLock) { save(task) }
                changed()
            }
            if (task.receipt == null) {
                task = task.copy(state = "sending", archiveSha256 = archive.sha256)
                synchronized(taskLock) { save(task) }
                failureStage = "transport"
                if (cancelled()) throw InterruptedException()
                val receipt = transport.upload(task.targetLink, archive.file, cancelled)
                require(receipt.bytes == archive.bytes && receipt.fileId.isNotBlank())
                task = task.copy(receipt = receipt, receivedAtMs = now())
                failureStage = "receipt"
                synchronized(taskLock) { save(task) }
            }
            failureStage = "receipt"
            val receipt = requireNotNull(task.receipt)
            store.completeTransfer(sessionId, SessionTransferReceipt(receipt.fileId, requireNotNull(task.receivedAtMs), false, sessionId))
            synchronized(taskLock) { save(task.copy(state = "complete", failureStage = null)) }
            changed()
            false
        } catch (error: Exception) {
            if (store.read(sessionId)?.uploadAllowed == false) return false
            // A received-but-not-committed receipt stays queued and will be reconciled without resending.
            val paused = cancelled() || error is InterruptedException || task.receipt != null
            task = task.copy(state = if (paused) "queued" else "failed",
                failureStage = if (error is InterruptedException) task.failureStage else failureStage)
            synchronized(taskLock) { save(task) }
            val session = store.read(sessionId)
            if (!paused && session?.transfer?.status == SessionTransferStatus.TRANSFERRING) store.markTransferFailed(sessionId)
            changed()
            paused
        }
    }

    fun task(sessionId: String): RealUploadTask? = synchronized(taskLock) { read(sessionId) }

    /** Lock order: delivery -> task -> journal. Never cancel or erase another session's work. */
    fun discardSession(sessionId: String, atMs: Long, connectionOwnerId: String? = null,
        connectionGeneration: Long? = null) {
        val before = requireNotNull(store.read(sessionId))
        require(before.transfer.attempts == 0 && before.transfer.receipt == null) { "本段已开始上传，保留当前上传任务" }
        synchronized(deliveryLock) {
            synchronized(taskLock) {
                if (store.read(sessionId)?.isDiscarded != true) {
                    store.discardStoppedSession(sessionId, atMs, connectionOwnerId, connectionGeneration)
                }
                store.cleanupDiscardedSession(sessionId)
            }
        }
        changed()
    }

    /** Also reports damaged task metadata without replacing its destination binding. */
    fun needsLocalReview(sessionId: String): Boolean = synchronized(taskLock) {
        if (store.read(sessionId)?.uploadAllowed == false) return false
        try {
            val task = read(sessionId)
            if (task != null) task.failureStage == "preparation"
            else store.read(sessionId)?.let { session ->
                session.localData != null && session.transfer.status != SessionTransferStatus.COMPLETE &&
                    (session.transfer.status != SessionTransferStatus.PENDING || session.transfer.attempts != 0)
            } == true
        } catch (_: Exception) { true }
    }

    private fun markFailed(sessionId: String) {
        val session = store.read(sessionId) ?: return
        if (!session.uploadAllowed) return
        if (session.transfer.status == SessionTransferStatus.PENDING) store.markTransferStarted(sessionId)
        if (session.transfer.status in setOf(SessionTransferStatus.PENDING, SessionTransferStatus.TRANSFERRING)) {
            store.markTransferFailed(sessionId)
            changed()
        }
    }

    private fun read(sessionId: String): RealUploadTask? {
        val file = taskFile(sessionId)
        if (!file.exists()) return null
        require(file.length() in 2..32_768) { "上传记录无法读取" }
        val task = requireNotNull(Gson().fromJson(file.readText(), RealUploadTask::class.java))
        require(task.version == 1 && task.sessionId == sessionId && task.state in setOf("queued", "sending", "failed", "complete"))
        SeafileSessionTransport.validateLink(task.targetLink)
        require(task.targetSha256 == digest(task.targetLink))
        require(task.archiveSha256 == null || task.archiveSha256.matches(Regex("[a-f0-9]{64}")))
        require(task.failureStage == null || task.failureStage in setOf("preparation", "transport", "receipt"))
        require((task.receipt == null) == (task.receivedAtMs == null))
        require(task.receipt == null || (task.archiveSha256 != null && task.receivedAtMs!! > 0 && task.receipt.bytes > 0 &&
            task.receipt.fileId.matches(Regex("[a-fA-F0-9]{40,64}")) && task.receipt.fileName.isNotBlank() &&
            '/' !in task.receipt.fileName && '\\' !in task.receipt.fileName))
        require(task.state != "complete" || task.receipt != null)
        require(task.state != "complete" || task.failureStage == null)
        return task
    }

    private fun save(task: RealUploadTask) {
        if (!folder.isDirectory) { check(folder.mkdir()); sync(directory) }
        val destination = taskFile(task.sessionId)
        val temporary = File(folder, "${task.sessionId}.json.tmp")
        FileOutputStream(temporary).use { it.write(Gson().toJson(task).toByteArray(Charsets.UTF_8)); it.fd.sync() }
        Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        sync(folder)
    }

    private fun taskFile(id: String): File { require(UUID.fromString(id).toString() == id); return File(folder, "$id.json") }
    companion object {
        private val taskLock = Any()
        private val deliveryLock = Any()
        private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
