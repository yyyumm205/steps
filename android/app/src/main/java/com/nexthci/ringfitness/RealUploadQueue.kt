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
    val version: Int = 3,
    val sessionId: String,
    val targetLink: String,
    val targetSha256: String,
    val state: String = "queued",
    val archiveSha256: String? = null,
    val receipt: RemoteSessionReceipt? = null,
    val receivedAtMs: Long? = null,
    val failureStage: String? = null,
    val dispatchStarted: Boolean? = false,
    val payloadStarted: Boolean? = false,
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
        if (task != null && !(task.version == 3 && task.state == "sending") &&
            requiresOutcomeReview(task, session)) {
            markFailed(sessionId)
            task = task.copy(version = 3, state = "failed", failureStage = "outcome",
                dispatchStarted = true, payloadStarted = true)
            save(task)
            changed()
            return false
        }
        if (task == null) {
            check(session.transfer.status == SessionTransferStatus.PENDING && session.transfer.attempts == 0) {
                "上传记录需要检查，已保留本地数据"
            }
            if (configuredLink.isBlank()) return false
            SeafileSessionTransport.validateLink(configuredLink)
            task = RealUploadTask(sessionId = sessionId, targetLink = configuredLink, targetSha256 = digest(configuredLink))
            save(task)
        } else if (task.state == "failed") {
            if (!retry || task.failureStage in setOf("outcome", "destination")) return false
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
        if (task.state !in setOf("queued", "sending")) {
            if (task.state == "failed") markFailed(sessionId)
            return false
        }
        val initialSession = requireNotNull(store.read(sessionId))
        if (requiresOutcomeReview(task, initialSession)) {
            // The body-start marker is durable before RequestBody writes. A legacy sending task
            // lacks that distinction, so migration chooses duplicate prevention over replay.
            markFailed(sessionId)
            task = task.copy(version = 3, state = "failed", failureStage = "outcome",
                dispatchStarted = true, payloadStarted = true)
            synchronized(taskLock) { save(task) }
            changed()
            return false
        }
        var failureStage = "preparation"
        var transferClaimed = false
        var claimedAttempt: Int? = null
        var transportInvoked = false
        var receiptVerified = false
        try {
            if (cancelled()) return true
            val session = requireNotNull(store.read(sessionId))
            if (!session.uploadAllowed) return false
            if (session.transfer.status == SessionTransferStatus.COMPLETE) {
                synchronized(taskLock) { save(task.copy(state = "complete", failureStage = null)) }
                return false
            }
            val archive = FreeLivingSessionPackage.withPublicationLock {
                val latest = requireNotNull(store.read(sessionId))
                require(latest.uploadAllowed) { "本段已暂停上传" }
                if (task.receipt == null) {
                    if (latest.transfer.status == SessionTransferStatus.TRANSFERRING) {
                        if (task.dispatchStarted == false) {
                            store.releaseTransferClaimBeforeDispatch(sessionId, latest.transfer.attempts)
                        } else {
                            store.markTransferFailed(sessionId)
                        }
                    }
                    task = task.copy(version = 3, state = "queued", dispatchStarted = false,
                        payloadStarted = false)
                    synchronized(taskLock) { save(task) }
                    claimedAttempt = store.markTransferStarted(sessionId).transfer.attempts
                    transferClaimed = true
                }
                freeze(requireNotNull(store.read(sessionId)))
            }
            if (transferClaimed) changed()
            require(archive.sessionId == sessionId && archive.file.isFile && archive.bytes == archive.file.length())
            require(task.archiveSha256 == null || task.archiveSha256 == archive.sha256) { "上传文件发生变化，已保留原任务" }
            // A retry remains flagged until both the source files and frozen package pass again.
            if (task.failureStage != null) {
                task = task.copy(failureStage = null)
                synchronized(taskLock) { save(task) }
                changed()
            }
            if (task.receipt == null) {
                task = task.copy(state = "queued", archiveSha256 = archive.sha256)
                synchronized(taskLock) { save(task) }
                failureStage = "transport"
                if (cancelled()) throw InterruptedException()
                val receipt = transport.upload(task.targetLink, archive.file, cancelled, {
                    if (!transportInvoked) {
                        val dispatched = task.copy(version = 3, state = "sending", dispatchStarted = true,
                            payloadStarted = false)
                        synchronized(taskLock) { save(dispatched) }
                        task = dispatched
                        transportInvoked = true
                        changed()
                    }
                }, {
                    if (task.payloadStarted != true) {
                        val started = task.copy(version = 3, state = "sending", dispatchStarted = true,
                            payloadStarted = true)
                        synchronized(taskLock) { save(started) }
                        task = started
                        changed()
                    }
                })
                require(transportInvoked) { "上传传输未报告网络请求" }
                require(receipt.fileName == archive.file.name && receipt.bytes == archive.bytes && receipt.fileId.isNotBlank())
                task = task.copy(receipt = receipt, receivedAtMs = now())
                failureStage = "receipt"
                synchronized(taskLock) { save(task) }
            }
            failureStage = "receipt"
            val receipt = requireNotNull(task.receipt)
            require(receipt.fileName == archive.file.name && receipt.bytes == archive.bytes) {
                "上传回执与冻结包不一致，已保留本地数据"
            }
            receiptVerified = true
            store.completeTransfer(sessionId, SessionTransferReceipt(receipt.fileId, requireNotNull(task.receivedAtMs), false, sessionId))
            synchronized(taskLock) { save(task.copy(state = "complete", failureStage = null)) }
            changed()
            false
        } catch (error: Exception) {
            if (store.read(sessionId)?.uploadAllowed == false) return false
            // A verified receipt can finish locally after a crash. An unverified receipt needs review.
            val recoverableReceipt = task.receipt != null && receiptVerified
            val outcomeUncertain = error is UploadOutcomeUncertainException
            val destinationRejected = error is PermanentUploadException
            val paused = !outcomeUncertain && !destinationRejected &&
                (cancelled() || error is InterruptedException || recoverableReceipt)
            val attempts = store.read(sessionId)?.transfer?.attempts ?: MAX_AUTOMATIC_ATTEMPTS
            val automaticRetry = !paused && !outcomeUncertain && !destinationRejected &&
                failureStage == "transport" &&
                attempts < MAX_AUTOMATIC_ATTEMPTS
            val nextState = when {
                outcomeUncertain || destinationRejected -> "failed"
                recoverableReceipt -> "queued"
                transportInvoked && (paused || automaticRetry) -> "sending"
                paused || automaticRetry -> "queued"
                else -> "failed"
            }
            task = task.copy(state = nextState,
                failureStage = when {
                    outcomeUncertain -> "outcome"
                    destinationRejected -> "destination"
                    error is InterruptedException -> task.failureStage
                    else -> failureStage
                }, payloadStarted = if (error is RetryableUploadException) false
                    else task.payloadStarted)
            if (paused && transferClaimed && !transportInvoked && task.receipt == null) {
                store.releaseTransferClaimBeforeDispatch(sessionId, requireNotNull(claimedAttempt))
            }
            synchronized(taskLock) { save(task) }
            val session = store.read(sessionId)
            if (!paused && !automaticRetry && session?.transfer?.status == SessionTransferStatus.TRANSFERRING) {
                store.markTransferFailed(sessionId)
            }
            changed()
            paused || automaticRetry
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
            if (task != null) task.failureStage in setOf("preparation", "outcome", "destination") ||
                requiresOutcomeReview(task, requireNotNull(store.read(sessionId))) ||
                (task.state == "failed" && task.failureStage == "receipt")
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
        require(task.version in 1..3 && task.sessionId == sessionId &&
            task.state in setOf("queued", "sending", "failed", "complete"))
        require((task.version == 1 && task.dispatchStarted == null) ||
            (task.version in 2..3 && task.dispatchStarted != null))
        require((task.version in 1..2 && task.payloadStarted == null) ||
            (task.version == 3 && task.payloadStarted != null))
        SeafileSessionTransport.validateLink(task.targetLink)
        require(task.targetSha256 == digest(task.targetLink))
        require(task.archiveSha256 == null || task.archiveSha256.matches(Regex("[a-f0-9]{64}")))
        require(task.failureStage == null || task.failureStage in
            setOf("preparation", "transport", "receipt", "outcome", "destination"))
        require((task.receipt == null) == (task.receivedAtMs == null))
        require(task.receipt == null || (task.archiveSha256 != null && task.receivedAtMs!! > 0 && task.receipt.bytes > 0 &&
            task.receipt.fileId.matches(Regex("[a-fA-F0-9]{40,64}")) && task.receipt.fileName.isNotBlank() &&
            '/' !in task.receipt.fileName && '\\' !in task.receipt.fileName))
        require(task.state != "sending" || task.dispatchStarted != false)
        require(task.receipt == null || task.dispatchStarted != false)
        require(task.payloadStarted != true || task.dispatchStarted == true)
        require(task.version != 3 || task.receipt == null || task.payloadStarted == true)
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

    private fun requiresOutcomeReview(task: RealUploadTask, session: FreeLivingSession): Boolean {
        if (task.receipt != null || task.failureStage in setOf("outcome", "destination")) return false
        return when (task.version) {
            3 -> task.payloadStarted == true
            2 -> task.dispatchStarted == true
            else -> !(task.state == "queued" && session.transfer.status == SessionTransferStatus.PENDING &&
                session.transfer.attempts == 0)
        }
    }

    private fun taskFile(id: String): File { require(UUID.fromString(id).toString() == id); return File(folder, "$id.json") }
    companion object {
        internal const val MAX_AUTOMATIC_ATTEMPTS = 5
        private val taskLock = Any()
        private val deliveryLock = Any()
        private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
