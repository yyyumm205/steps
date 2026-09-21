package com.nexthci.ringfitness

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.StringReader
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

enum class FreeLivingSessionPhase(val wireValue: String) {
    START_REQUESTED("start_requested"),
    COLLECTING("collecting"),
    STOP_REQUESTED("stop_requested"),
    AWAITING_REFERENCE("awaiting_reference"),
}

enum class DeviceBoundarySource(val wireValue: String) {
    DEVICE_TIME_ANCHOR("device_time_anchor"),
    RAW_SAMPLE("raw_sample"),
}

enum class ReferenceStatus(val wireValue: String) {
    VALID("valid"), MISSING("missing"), UNRELIABLE("unreliable"),
}

data class SessionReference(
    val status: ReferenceStatus,
    val steps: Long?,
    val recordedAtMs: Long,
    val reason: String? = null,
) {
    /** Saving a missing reason records an observation, while numeric confirmation remains absent. */
    val groundTruthRecordedAtMs: Long? get() = recordedAtMs.takeIf { steps != null }
}

/** Local audit retained when a participant corrects a reference before confirming upload. */
data class SessionReferenceRevision(
    val previous: SessionReference,
    val replacedAtMs: Long,
)

data class SessionRawFile(
    val fileName: String,
    val deviceSessionId: Int,
    val bytes: Long,
    val sha256: String,
    val simulated: Boolean = false,
)

data class SessionLocalData(val files: List<SessionRawFile>, val completedAtMs: Long)

enum class SessionTransferStatus(val wireValue: String) {
    PENDING("pending"), TRANSFERRING("transferring"), FAILED("failed"), COMPLETE("complete"),
}

enum class CompletionPolicy(val wireValue: String) {
    SAVE_UPLOAD("save_upload"), SAVE_LATER("save_later"), DEFER_ON_RING("defer_on_ring"),
}

enum class StopOrigin(val wireValue: String) {
    USER_REQUEST("user_request"),
    DEVICE_OBSERVED("device_observed"),
    LEGACY_UNSPECIFIED("legacy_unspecified"),
}

/** Local cancellation audit. Ring flash remains untouched. */
data class SessionDiscard(
    val discardedAtMs: Long,
    val connectionOwnerId: String? = null,
    val connectionGeneration: Long? = null,
)

data class SessionTransferReceipt(
    val receiptId: String,
    val receivedAtMs: Long,
    val simulated: Boolean,
    val sessionId: String,
)

data class SessionTransfer(
    val status: SessionTransferStatus = SessionTransferStatus.PENDING,
    val attempts: Int = 0,
    val receipt: SessionTransferReceipt? = null,
)

/** Device-derived evidence only. Phone receipt timestamps belong in the separate confirmation fields. */
data class DeviceBoundaryEvidence(
    val source: DeviceBoundarySource,
    val epochMs: Long,
    val deviceUptimeMs: Long?,
    val rawEvidence: String,
)

/** Full pre-command observation. LIST anchors are record fingerprints, not capture timestamps. */
data class DeviceStartBaseline(
    val status: HealthMessage.Status,
    val records: List<HealthMessage.ListItem>,
    val observedAtMs: Long,
    val chargingRecoveryEvidence: ChargingRecoveryEvidence? = null,
    val unknownTimeStartEvidence: UnknownTimeStartEvidence? = null,
)

/**
 * Durable outbox evidence for the START side effect. Preparation is persisted before calling BLE,
 * so a process death cannot make a possibly queued command look like an untouched request.
 */
data class StartCommandDispatch(
    val preparedAtMs: Long,
    val acceptedAtMs: Long? = null,
    val ownerId: String? = null,
    val connectionGeneration: Long? = null,
)

/** Durable outbox evidence for STOP. Device confirmation remains in stopConfirmedAtMs. */
data class StopCommandDispatch(
    val preparedAtMs: Long,
    val acceptedAtMs: Long? = null,
    val ownerId: String? = null,
    val connectionGeneration: Long? = null,
)

/** Durable association established by a complete STATUS/LIST round on the issuing connection. */
data class DeviceRecordEvidence(
    val record: HealthMessage.ListItem,
    val status: HealthMessage.Status,
    val observedAtMs: Long,
)

/** Local audit of an explicitly ended, unconfirmed request; never a research capture. */
data class StartAttemptArchive(
    val archivedAtMs: Long,
    val reason: String,
    val observation: HealthRecordObservation,
    val unknownPreservation: UnknownTimeRecordProof? = null,
)

data class FreeLivingSession(
    val sessionId: String,
    val preparation: PreparationSnapshot,
    val phase: FreeLivingSessionPhase,
    val timeZoneId: String,
    val utcOffsetSeconds: Int,
    val startRequestedAtMs: Long,
    val startConfirmedAtMs: Long? = null,
    val stopRequestedAtMs: Long? = null,
    val stopObservedAtMs: Long? = null,
    val stopOrigin: StopOrigin? = null,
    val stopConfirmedAtMs: Long? = null,
    val startStatusEvidence: HealthMessage.Status? = null,
    val stopStatusEvidence: HealthMessage.Status? = null,
    val startBoundaryEvidence: DeviceBoundaryEvidence? = null,
    val endBoundaryEvidence: DeviceBoundaryEvidence? = null,
    val reference: SessionReference? = null,
    val localData: SessionLocalData? = null,
    val transfer: SessionTransfer = SessionTransfer(),
    val startBaseline: DeviceStartBaseline? = null,
    val deviceRecordEvidence: DeviceRecordEvidence? = null,
    val deviceAssociationInvalidated: Boolean = false,
    val startAttemptArchive: StartAttemptArchive? = null,
    val activity: SessionActivity = SessionActivity.FREE_LIVING,
    val completionPolicy: CompletionPolicy? = null,
    val discarded: SessionDiscard? = null,
    val startAbort: UnconfirmedStartAbort? = null,
    val referenceRevisions: List<SessionReferenceRevision> = emptyList(),
    val startCommandDispatch: StartCommandDispatch? = null,
    val stopCommandDispatch: StopCommandDispatch? = null,
) {
    val isDiscarded: Boolean get() = discarded != null
    val isPending: Boolean get() = localData == null && startAttemptArchive == null && !isDiscarded && startAbort?.completedAtMs == null
    val isRingDeferred: Boolean get() = !isDiscarded && startAbort == null &&
        completionPolicy == CompletionPolicy.DEFER_ON_RING && localData == null
    val uploadAllowed: Boolean get() = !isDiscarded && startAbort == null &&
        completionPolicy !in setOf(CompletionPolicy.SAVE_LATER, CompletionPolicy.DEFER_ON_RING)
    val deviceSessionId: Int? get() = startStatusEvidence?.sessionId
    val startedAtMs: Long? get() = startBoundaryEvidence?.epochMs
    // Preserve contradictory raw evidence, while keeping the effective end explicitly unknown.
    val endedAtMs: Long? get() = endBoundaryEvidence?.epochMs?.takeUnless {
        startedAtMs?.let { start -> it < start } == true
    }
    val captureBoundaryStatus: String get() =
        if (startedAtMs != null && endedAtMs != null) "confirmed" else "uncertain"
    val timingWarnings: List<String> get() = buildList {
        val phoneTimes = listOfNotNull(startRequestedAtMs, startConfirmedAtMs,
            stopRequestedAtMs ?: stopObservedAtMs, stopConfirmedAtMs)
        // An anomalous observation may precede a delayed stop confirmation. Compare only the
        // causal dependencies, so recovery does not turn that valid order into a clock warning.
        val referenceEarliest = if (reference?.status == ReferenceStatus.VALID) stopConfirmedAtMs
            else stopRequestedAtMs ?: stopObservedAtMs
        val referenceWentBack = reference?.recordedAtMs?.let { recorded -> referenceEarliest?.let { recorded < it } } == true
        val downloadWentBack = localData?.completedAtMs?.let { completed ->
            listOfNotNull(stopConfirmedAtMs, reference?.recordedAtMs).any { completed < it }
        } == true
        if (phoneTimes.zipWithNext().any { (before, after) -> after < before } || referenceWentBack || downloadWentBack) {
            add("phone_clock_order_uncertain")
        }
        if (endBoundaryEvidence != null && endedAtMs == null) add("device_boundary_order_uncertain")
    }
}

/**
 * Atomic journal for the current session and locally complete sessions awaiting transmission.
 * Verified local files or a guarded unconfirmed-start audit release pending-data protection.
 * Callers must durably record requests
 * before sending BLE commands and correlate a start reply with that request before confirming it.
 * This store neither sends commands nor treats a GATT write callback as capture confirmation.
 * Confirmations retain their first evidence. Enriching unknown boundaries from a later download
 * is a separate future operation, not a side effect of repeating a confirmation.
 * Place the journal directly in an existing app-private directory (for example Context.filesDir).
 */
class FreeLivingSessionStore internal constructor(
    file: File,
    private val commitFile: (File, File) -> Unit,
    private val syncDirectory: (File) -> Unit,
) {
    constructor(file: File) : this(
        file,
        { source, target ->
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        },
        { directory -> FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) } },
    )

    private val file = file.canonicalFile
    private data class Journal(val current: FreeLivingSession, val archived: List<FreeLivingSession> = emptyList())

    fun read(): FreeLivingSession? = synchronized(processLock) { readLocked() }

    fun readPending(): FreeLivingSession? = read()?.takeIf { it.isPending }

    fun read(sessionId: String): FreeLivingSession? = listSessions().singleOrNull { it.sessionId == sessionId }

    fun listSessions(): List<FreeLivingSession> = synchronized(processLock) {
        readJournalLocked()?.let { it.archived + it.current }.orEmpty()
    }

    fun setCompletionPolicy(sessionId: String, policy: CompletionPolicy): FreeLivingSession =
        update(sessionId, allowArchived = true) { current ->
            require(current.stopConfirmedAtMs != null) { "请等待戒指确认结束" }
            require(current.transfer.attempts == 0 && current.transfer.status == SessionTransferStatus.PENDING) {
                "本段已开始上传，保留当前上传任务"
            }
            if (current.completionPolicy == CompletionPolicy.DEFER_ON_RING) {
                require(policy == CompletionPolicy.DEFER_ON_RING) { "请从戒指继续保存本段数据" }
            }
            if (policy == CompletionPolicy.DEFER_ON_RING) {
                require(current.phase == FreeLivingSessionPhase.AWAITING_REFERENCE && current.reference != null &&
                    current.localData == null) { "请先确认结束并保存本次读数" }
            }
            current.copy(completionPolicy = policy)
        }

    /**
     * Persists the participant's completion choice and reference observation in one journal commit.
     * A retry after an uncertain commit keeps the first timestamp and cannot replace either value.
     */
    fun finalizeStoppedSession(sessionId: String, policy: CompletionPolicy,
        reference: SessionReference): FreeLivingSession = update(sessionId) { current ->
        validateReference(reference)
        require(current.phase == FreeLivingSessionPhase.AWAITING_REFERENCE && current.stopConfirmedAtMs != null) {
            "请等待戒指确认结束"
        }
        require(current.localData == null) { "本段已经完成本地保存" }
        require(current.transfer.attempts == 0 && current.transfer.status == SessionTransferStatus.PENDING) {
            "本段已开始上传，保留当前上传任务"
        }
        current.completionPolicy?.let { saved ->
            require(saved == policy) { "本段保存方式已确认" }
        }
        current.reference?.let { saved ->
            require(saved.status == reference.status && saved.steps == reference.steps && saved.reason == reference.reason) {
                "本次读数已保存，请核对原记录"
            }
        }
        current.copy(
            completionPolicy = current.completionPolicy ?: policy,
            reference = current.reference ?: reference,
        )
    }

    /** Explicit user action releases a durable save-for-later choice. */
    fun allowUpload(sessionId: String): FreeLivingSession = update(sessionId, allowArchived = true) { current ->
        require(current.stopConfirmedAtMs != null) { "请等待戒指确认结束" }
        // Journals written before completion policies existed may have complete local data and a
        // null policy. They remain uploadable; ring-deferred sessions never have local data here.
        require(current.localData != null && current.completionPolicy != CompletionPolicy.DEFER_ON_RING) {
            "请先完成戒指数据保存"
        }
        current.copy(completionPolicy = CompletionPolicy.SAVE_UPLOAD)
    }

    /** Explicitly resumes downloading a stopped session whose raw data intentionally stayed on the ring. */
    fun resumeRingTransfer(sessionId: String): FreeLivingSession = update(sessionId) { current ->
        require(current.phase == FreeLivingSessionPhase.AWAITING_REFERENCE && current.stopConfirmedAtMs != null) {
            "请等待戒指确认结束"
        }
        require(current.reference != null && current.localData == null) { "本段无需从戒指继续保存" }
        require(current.transfer.status == SessionTransferStatus.PENDING && current.transfer.attempts == 0 &&
            current.transfer.receipt == null) { "本段已开始上传，保留当前上传任务" }
        require(current.completionPolicy in
            setOf(CompletionPolicy.DEFER_ON_RING, CompletionPolicy.SAVE_UPLOAD)) { "本段没有待恢复的戒指数据" }
        if (current.completionPolicy == CompletionPolicy.SAVE_UPLOAD) current
        else current.copy(completionPolicy = CompletionPolicy.SAVE_UPLOAD)
    }

    /** Commit cancellation before deleting any bytes; late callbacks cannot revive this session. */
    fun discardStoppedSession(sessionId: String, atMs: Long, connectionOwnerId: String? = null,
        connectionGeneration: Long? = null): FreeLivingSession = synchronized(processLock) {
        val current = requireNotNull(read())
        require(current.sessionId == sessionId) { "只能删除当前采集段" }
        if (current.isDiscarded) return@synchronized current
        require(current.stopConfirmedAtMs != null && current.phase == FreeLivingSessionPhase.AWAITING_REFERENCE) {
            "请等待戒指确认结束"
        }
        require(current.transfer.status == SessionTransferStatus.PENDING && current.transfer.attempts == 0 &&
            current.transfer.receipt == null) { "本段已开始上传，保留当前上传任务" }
        update(sessionId) { it.copy(discarded = SessionDiscard(atMs, connectionOwnerId, connectionGeneration)) }
    }

    /** Only the explicit session whitelist is removed; journal audit and device backups remain. */
    fun cleanupDiscardedSession(sessionId: String) = synchronized(processLock) {
        val session = requireNotNull(read(sessionId))
        require(session.isDiscarded) { "请先保存删除决定" }
        DiscardedSessionFiles.cleanup(requireNotNull(file.parentFile), session, syncDirectory)
    }

    fun isDiscardedDeviceRecord(ringAddress: String, record: HealthMessage.ListItem,
        connectionOwnerId: String? = null, connectionGeneration: Long? = null): Boolean = listSessions().any { session ->
        val discarded = session.discarded
        discarded != null && session.preparation.ring?.address == ringAddress &&
            !session.deviceAssociationInvalidated &&
            session.deviceRecordEvidence?.record == record &&
            (record.unixMs > 0 || (connectionOwnerId != null && connectionGeneration != null &&
                discarded.connectionOwnerId == connectionOwnerId && discarded.connectionGeneration == connectionGeneration))
    }

    /** Unknown wall time cannot establish identity after reconnection; avoid silently reimporting it. */
    fun hasUnresolvedDiscardedRecord(ringAddress: String, record: HealthMessage.ListItem): Boolean =
        record.unixMs == 0L && listSessions().any { it.isDiscarded && it.preparation.ring?.address == ringAddress &&
            it.deviceRecordEvidence?.record == record }

    fun requestUnconfirmedStartAbort(sessionId: String, observation: HealthRecordObservation,
        requestedAtMs: Long, ownerId: String): FreeLivingSession = update(sessionId, allowStartAbort = true) { current ->
        current.startAbort?.let { return@update current }
        current.copy(startAbort = UnconfirmedStartAbort(requestedAtMs, ownerId, observation))
    }

    fun confirmUnconfirmedStartAbortStop(sessionId: String, observation: HealthRecordObservation): FreeLivingSession =
        update(sessionId, allowStartAbort = true) { current ->
            val audit = requireNotNull(current.startAbort)
            audit.stoppedObservation?.let {
                require(it == observation) { "异常停止信息已保存" }
                return@update current
            }
            current.copy(startAbort = audit.copy(stoppedObservation = observation))
        }

    /** A fresh channel's complete unassigned backup releases only this failed attempt. */
    fun completeUnconfirmedStartAbort(sessionId: String, proof: UnknownTimeRecordProof?,
        observation: HealthRecordObservation, completedAtMs: Long, ownerId: String? = proof?.ownerId): FreeLivingSession =
        update(sessionId, allowStartAbort = true) { current ->
            val audit = requireNotNull(current.startAbort)
            if (audit.completedAtMs != null) return@update current
            val next = audit.copy(preservation = proof, preservedObservation = observation, completedAtMs = completedAtMs,
                completionOwnerId = ownerId ?: audit.ownerId)
            next.validate(current)
            if (proof != null) {
                val raw = DeviceRecordBackupStore(File(file.parentFile, "device-backups"), syncDirectory)
                    .verifyUnknown(observation.address, proof.record, proof.backupId)
                require(raw.sha256 == proof.rawSha256) { "异常记录保全证据不一致" }
            }
            current.copy(startAbort = next)
        }

    /** Immutable research metadata; transport attempts and receipts never enter an upload package. */
    fun manifestSnapshot(sessionId: String, packageCreatedAt: Instant? = null): JsonObject = synchronized(processLock) {
        val journal = checkNotNull(readJournalLocked()) { "没有可打包的采集段" }
        val session = (journal.archived + journal.current).singleOrNull { it.sessionId == sessionId }
            ?: throw IllegalArgumentException("采集段不匹配")
        require(!session.isDiscarded && session.startAbort == null) { "本段不属于研究采集记录" }
        val local = requireNotNull(session.localData) { "原始文件尚未完整保存" }
        require(session.reference != null && session.stopConfirmedAtMs != null) { "采集信息尚未保存完整" }
        require(local.files.none { it.simulated }) { "演示数据不能进入实验上传包" }
        verifyLocalFiles(session, local.files)
        // Local audit additions must not change already frozen research manifests.
        encode(session, version = 3).apply {
            remove("phase")
            remove("transfer")
            remove("raw_files")
            val chargingRecovery = session.startBaseline?.chargingRecoveryEvidence
            val unknownTime = session.startBaseline?.unknownTimeStartEvidence
            if (chargingRecovery != null) {
                getAsJsonObject("start_baseline").add("charging_recovery_evidence", encodeChargingRecovery(chargingRecovery))
            }
            if (session.activity != SessionActivity.FREE_LIVING) addActivity(session.activity)
            if (unknownTime != null) getAsJsonObject("start_baseline").add("unknown_time_start_evidence", unknownTime.encode())
            addProperty("version", 7)
            addProperty("step_schema_version", 1)
            addProperty("rfbin_version", 2)
            addProperty("simulated", false)
            addProperty("ring_placement_schema", RingPlacement.SCHEMA)
            addProperty("ring_hand", session.preparation.placement!!.hand)
            addProperty("ring_finger", session.preparation.placement.finger)
            addProperty("app_version", BuildConfig.VERSION_NAME)
            addProperty("stop_origin", requireNotNull(session.stopOrigin).wireValue)
            addNullable("stop_observed_at_ms", session.stopObservedAtMs)
            // The package owner supplies the first freeze time. The fallback keeps direct
            // diagnostic snapshots deterministic without changing any already frozen package.
            addProperty("created_at", (packageCreatedAt ?: Instant.ofEpochMilli(session.startRequestedAtMs)).toString())
            add("files", JsonArray().apply {
                local.files.sortedBy { it.fileName }.forEach { file ->
                    add(encodeFile(file).apply { addProperty("role", "raw") })
                }
            })
        }
    }

    fun requestStart(preparation: PreparationSnapshot, requestedAtMs: Long, timeZoneId: String,
        baseline: DeviceStartBaseline? = null, activity: SessionActivity = SessionActivity.FREE_LIVING): FreeLivingSession =
        synchronized(processLock) {
            validatePreparation(preparation)
            // A session freezes research identity and device fields. Local display metadata stays
            // in PreparationStore, so the object returned here must match its durable journal form.
            val sessionPreparation = PreparationSnapshot(
                participantId = preparation.participantId,
                installationId = preparation.installationId,
                placement = preparation.placement,
                ring = preparation.ring,
            )
            require(requestedAtMs > 0) { "开始请求时间无效" }
            val zone = ZoneId.of(timeZoneId)
            val journal = readJournalLocked()
            val existing = journal?.current
            if (existing != null && existing.isPending) {
                require(existing.preparation == sessionPreparation) { "已有采集段的编号、位置或戒指不能更改" }
                require(existing.activity == activity) { "已有采集段的活动类型不能更改" }
                require(existing.phase == FreeLivingSessionPhase.START_REQUESTED ||
                    existing.phase == FreeLivingSessionPhase.COLLECTING) { "上一段原始数据尚未安全保存，暂不能开始新采集" }
                return@synchronized existing
            }
            existing?.takeUnless { it.isDiscarded }?.localData?.let { verifyLocalFiles(existing, it.files) }
            persist(FreeLivingSession(
                sessionId = UUID.randomUUID().toString(), preparation = sessionPreparation,
                phase = FreeLivingSessionPhase.START_REQUESTED,
                timeZoneId = zone.id,
                utcOffsetSeconds = zone.rules.getOffset(Instant.ofEpochMilli(requestedAtMs)).totalSeconds,
                startRequestedAtMs = requestedAtMs,
                startBaseline = baseline,
                activity = activity,
            ), if (journal == null) emptyList() else journal.archived + journal.current)
        }

    /** Persist before invoking BLE: after this point START may have reached the device. */
    fun prepareStartCommand(
        sessionId: String,
        preparedAtMs: Long,
        ownerId: String = LEGACY_COMMAND_OWNER_ID,
        connectionGeneration: Long = 1L,
    ): FreeLivingSession = update(sessionId) { current ->
        require(preparedAtMs > 0) { "开始命令准备时间无效" }
        require(UUID.fromString(ownerId).toString() == ownerId && connectionGeneration > 0) { "开始命令连接证据无效" }
        require(current.phase == FreeLivingSessionPhase.START_REQUESTED && current.startConfirmedAtMs == null) {
            "当前阶段不接受开始命令"
        }
        current.startCommandDispatch?.let { return@update current }
        current.copy(startCommandDispatch = StartCommandDispatch(preparedAtMs,
            ownerId = ownerId, connectionGeneration = connectionGeneration))
    }

    /** Record local queue acceptance; device execution still requires a STATUS/LIST confirmation. */
    fun confirmStartCommandAccepted(sessionId: String, acceptedAtMs: Long): FreeLivingSession = update(sessionId) { current ->
        require(acceptedAtMs > 0) { "开始命令入队时间无效" }
        require(current.phase == FreeLivingSessionPhase.START_REQUESTED && current.startConfirmedAtMs == null) {
            "当前阶段不接受开始命令"
        }
        val dispatch = requireNotNull(current.startCommandDispatch) { "开始命令尚未准备" }
        dispatch.acceptedAtMs?.let { return@update current }
        current.copy(startCommandDispatch = dispatch.copy(acceptedAtMs = acceptedAtMs))
    }

    /** Local rejection proves START never entered this BLE queue, so the outbox marker can clear. */
    fun cancelPreparedStartCommand(sessionId: String): FreeLivingSession = update(sessionId) { current ->
        val dispatch = requireNotNull(current.startCommandDispatch) { "开始命令尚未准备" }
        require(current.phase == FreeLivingSessionPhase.START_REQUESTED && dispatch.acceptedAtMs == null) {
            "开始命令已入队，需先查询戒指状态"
        }
        current.copy(startCommandDispatch = null)
    }

    /** The coordinator supplies a newly completed STATUS/LIST round after explicit user input. */
    fun archiveStartAttempt(sessionId: String, observation: HealthRecordObservation,
        archivedAtMs: Long, reason: String, unknownPreservation: UnknownTimeRecordProof? = null): FreeLivingSession = update(sessionId) { current ->
        current.startAttemptArchive?.let {
            require(it.reason == reason) { "本次尝试已结束，原说明已保留" }
            return@update current
        }
        val audit = StartAttemptArchive(archivedAtMs, reason, observation, unknownPreservation)
        validateStartAttemptArchive(current, audit)
        // An unchanged pre-START baseline is not a new research record. Known-time records
        // still verify their existing local copy. Unknown-time Flash entries remain in the
        // audit and do not become a permanent prerequisite for using the ring again.
        val sessions = listSessions()
        val knownRecords = observation.records.filter { it.unixMs > 0 }
        require(knownRecords.isEmpty() || hasPreservedDeviceRecords(observation.address, knownRecords)) {
            "戒指记录尚未完整保存在手机"
        }
        knownRecords.forEach { record ->
            if (isDiscardedDeviceRecord(observation.address, record)) return@forEach
            val saved = sessions.singleOrNull { it.preparation.ring?.address == observation.address &&
                it.deviceRecordEvidence?.record == record && !it.isDiscarded &&
                !it.deviceAssociationInvalidated && it.localData != null }
            if (saved == null) {
                require(DeviceRecordBackupStore(File(file.parentFile, "device-backups"), syncDirectory)
                    .isPreserved(observation.address, record)) { "戒指记录尚未完整保存在手机" }
            } else {
                val raw = requireNotNull(saved.localData).files.singleOrNull()
                    ?: throw IllegalArgumentException("本次记录的本地文件需要检查")
                RealSessionDownload.verifyPreservedContainer(File(file.parentFile, raw.fileName), record,
                    saved.startedAtMs ?: 0L, saved.endedAtMs ?: 0L)
            }
        }
        unknownPreservation?.let { proof ->
            val raw = DeviceRecordBackupStore(File(file.parentFile, "device-backups"), syncDirectory)
                .verifyUnknown(observation.address, proof.record, proof.backupId)
            require(raw.sha256 == proof.rawSha256) { "旧记录保全证据不一致" }
        }
        current.copy(startAttemptArchive = audit)
    }

    fun confirmStart(
        sessionId: String,
        ringAddress: String,
        status: HealthMessage.Status,
        receivedAtMs: Long,
        boundary: DeviceBoundaryEvidence? = null,
        recordEvidence: DeviceRecordEvidence? = null,
    ): FreeLivingSession = update(sessionId) { current ->
        validateReply(current, ringAddress, status, receivedAtMs, collecting = true)
        validateBoundary(boundary)
        require(recordEvidence == null || recordEvidence.status == status) { "设备关联与开始状态不一致" }
        if (current.startConfirmedAtMs != null) return@update current
        require(current.phase == FreeLivingSessionPhase.START_REQUESTED) { "当前阶段不接受开始确认" }
        current.copy(phase = FreeLivingSessionPhase.COLLECTING, startConfirmedAtMs = receivedAtMs,
            startStatusEvidence = status, startBoundaryEvidence = boundary, deviceRecordEvidence = recordEvidence)
    }

    fun requestStop(
        sessionId: String,
        requestedAtMs: Long,
        ownerId: String = LEGACY_COMMAND_OWNER_ID,
        connectionGeneration: Long = 1L,
    ): FreeLivingSession = update(sessionId) { current ->
        require(requestedAtMs > 0) { "停止请求时间无效" }
        require(UUID.fromString(ownerId).toString() == ownerId && connectionGeneration > 0) { "停止命令连接证据无效" }
        if (current.stopOrigin != null) return@update current
        require(current.phase == FreeLivingSessionPhase.COLLECTING) { "尚未确认开始采集" }
        current.copy(
            phase = FreeLivingSessionPhase.STOP_REQUESTED,
            stopRequestedAtMs = requestedAtMs,
            stopOrigin = StopOrigin.USER_REQUEST,
            stopCommandDispatch = StopCommandDispatch(requestedAtMs,
                ownerId = ownerId, connectionGeneration = connectionGeneration),
        )
    }

    /** Record local queue acceptance; device execution still requires settled STATUS/LIST evidence. */
    fun confirmStopCommandAccepted(sessionId: String, acceptedAtMs: Long): FreeLivingSession = update(sessionId) { current ->
        require(acceptedAtMs > 0) { "停止命令入队时间无效" }
        require(current.phase == FreeLivingSessionPhase.STOP_REQUESTED && current.stopConfirmedAtMs == null &&
            current.stopOrigin == StopOrigin.USER_REQUEST) { "当前阶段不接受停止命令" }
        val dispatch = requireNotNull(current.stopCommandDispatch) { "停止命令尚未准备" }
        dispatch.acceptedAtMs?.let { return@update current }
        current.copy(stopCommandDispatch = dispatch.copy(acceptedAtMs = acceptedAtMs))
    }

    /**
     * A process restart can leave a durable STOP intent that never crossed the BLE boundary.
     * After a fresh STATUS/LIST round proves the same owned record is still collecting, move the
     * pending outbox entry to the recovering owner before it performs the single safe retry.
     */
    fun prepareRecoveredStopCommand(
        sessionId: String,
        preparedAtMs: Long,
        ownerId: String,
        connectionGeneration: Long,
    ): FreeLivingSession = update(sessionId) { current ->
        require(preparedAtMs > 0 && UUID.fromString(ownerId).toString() == ownerId && connectionGeneration > 0) {
            "停止命令恢复证据无效"
        }
        require(current.phase == FreeLivingSessionPhase.STOP_REQUESTED && current.stopConfirmedAtMs == null &&
            current.stopOrigin == StopOrigin.USER_REQUEST) { "当前阶段不接受停止命令恢复" }
        val requestedAtMs = requireNotNull(current.stopRequestedAtMs) { "缺少停止请求时间" }
        val dispatch = requireNotNull(current.stopCommandDispatch) { "停止命令尚未准备" }
        require(dispatch.acceptedAtMs == null && preparedAtMs >= requestedAtMs) { "停止命令已入队或恢复时间无效" }
        current.copy(stopCommandDispatch = StopCommandDispatch(preparedAtMs,
            ownerId = ownerId, connectionGeneration = connectionGeneration))
    }

    /**
     * The ring may stop before the phone can send STOP. Persist that observation as the start of
     * finalization, then require another settled STATUS/LIST round before confirming completion.
     */
    fun beginObservedStopFinalization(
        sessionId: String,
        observedAtMs: Long,
        evidence: DeviceRecordEvidence,
    ): FreeLivingSession = update(sessionId) { current ->
        require(observedAtMs > 0 && evidence.observedAtMs == observedAtMs) { "停止观察时间无效" }
        require(current.phase == FreeLivingSessionPhase.COLLECTING && current.stopRequestedAtMs == null) {
            "当前阶段不接受停止观察"
        }
        require(!evidence.status.collecting && evidence.status.errorCode == 0) { "戒指尚未安全停止" }
        val previous = requireNotNull(current.deviceRecordEvidence) { "缺少设备记录关联证据" }
        require(!current.deviceAssociationInvalidated && sameDeviceRecord(previous.record, evidence.record) &&
            evidence.record.sessionId == evidence.status.sessionId &&
            evidence.record.bytes >= previous.record.bytes && evidence.record.records >= previous.record.records &&
            evidence.status.bytes >= previous.status.bytes && evidence.status.records >= previous.status.records &&
            evidence.record.bytes >= evidence.status.bytes && evidence.record.records >= evidence.status.records) {
            "设备记录已变化"
        }
        current.copy(
            phase = FreeLivingSessionPhase.STOP_REQUESTED,
            stopObservedAtMs = observedAtMs,
            stopOrigin = StopOrigin.DEVICE_OBSERVED,
            deviceRecordEvidence = evidence,
        )
    }

    /** A locally rejected STOP was never queued to the ring, so the collecting state remains true. */
    fun cancelStopRequest(sessionId: String): FreeLivingSession = update(sessionId) { current ->
        require(current.phase == FreeLivingSessionPhase.STOP_REQUESTED && current.stopConfirmedAtMs == null) {
            "当前没有可撤销的停止请求"
        }
        current.copy(
            phase = FreeLivingSessionPhase.COLLECTING,
            stopRequestedAtMs = null,
            stopObservedAtMs = null,
            stopOrigin = null,
            stopCommandDispatch = null,
        )
    }

    fun confirmStop(
        sessionId: String,
        ringAddress: String,
        status: HealthMessage.Status,
        receivedAtMs: Long,
        boundary: DeviceBoundaryEvidence? = null,
        recordEvidence: DeviceRecordEvidence? = null,
    ): FreeLivingSession = update(sessionId) { current ->
        validateReply(current, ringAddress, status, receivedAtMs, collecting = false)
        validateBoundary(boundary)
        require(recordEvidence == null || recordEvidence.status == status) { "设备关联与停止状态不一致" }
        if (current.stopConfirmedAtMs != null) return@update current
        require(current.phase == FreeLivingSessionPhase.STOP_REQUESTED) { "尚未请求停止采集" }
        current.deviceRecordEvidence?.let { previous ->
            val next = requireNotNull(recordEvidence) { "缺少停止记录关联证据" }
            require(!current.deviceAssociationInvalidated && sameDeviceRecord(previous.record, next.record) &&
                next.record.bytes >= previous.record.bytes && next.record.records >= previous.record.records &&
                next.status.bytes >= previous.status.bytes && next.status.records >= previous.status.records) { "设备记录已变化" }
        }
        current.copy(phase = FreeLivingSessionPhase.AWAITING_REFERENCE, stopConfirmedAtMs = receivedAtMs,
            stopStatusEvidence = status, endBoundaryEvidence = boundary,
            deviceRecordEvidence = recordEvidence ?: current.deviceRecordEvidence)
    }

    /** Refresh only a previously proved identity; never promote a same-ID record to ownership. */
    fun updateDeviceEvidence(sessionId: String, evidence: DeviceRecordEvidence): FreeLivingSession = update(sessionId) { current ->
        require(!current.deviceAssociationInvalidated) { "设备记录需要研究者核对" }
        val previous = requireNotNull(current.deviceRecordEvidence) { "缺少设备记录关联证据" }
        require(sameDeviceRecord(previous.record, evidence.record)) { "设备记录已变化" }
        require(evidence.record.bytes >= previous.record.bytes && evidence.record.records >= previous.record.records &&
            evidence.status.bytes >= previous.status.bytes && evidence.status.records >= previous.status.records) { "设备计数发生回退" }
        require(previous.status.collecting || !evidence.status.collecting) { "停止状态发生回退" }
        current.copy(deviceRecordEvidence = evidence)
    }

    fun invalidateDeviceAssociation(sessionId: String): FreeLivingSession = update(sessionId) {
        it.copy(deviceAssociationInvalidated = true)
    }

    /** Replacement requires a verified local copy or an explicit discard of an exactly identified record. */
    fun hasPreservedDeviceRecords(ringAddress: String, records: List<HealthMessage.ListItem>): Boolean =
        synchronized(processLock) {
            if (records.isEmpty()) return@synchronized false
            val sessions = readJournalLocked()?.let { it.archived + it.current }.orEmpty()
            records.all { record ->
                // Uptime alone cannot prove that the device still holds the same boot's record.
                if (record.unixMs == 0L) return@all false
                if (sessions.any { it.isDiscarded && !it.deviceAssociationInvalidated && it.preparation.ring?.address == ringAddress &&
                        it.deviceRecordEvidence?.record == record }) return@all true
                val saved = sessions.singleOrNull { it.preparation.ring?.address == ringAddress &&
                    !it.isDiscarded && !it.deviceAssociationInvalidated && it.deviceRecordEvidence?.record == record && it.localData != null }
                    ?: return@all DeviceRecordBackupStore(File(file.parentFile, "device-backups"), syncDirectory).isPreserved(ringAddress, record)
                verifyLocalFiles(saved, saved.localData!!.files)
                saved.localData.files.none { it.simulated }
            }
        }

    fun saveReference(sessionId: String, reference: SessionReference): FreeLivingSession = update(sessionId) { current ->
        validateReference(reference)
        current.reference?.let { saved ->
            require(saved.status == reference.status && saved.steps == reference.steps && saved.reason == reference.reason) {
                "本次读数已保存，请联系研究者核对修改"
            }
            return@update current
        }
        require(current.phase == FreeLivingSessionPhase.AWAITING_REFERENCE ||
            current.phase == FreeLivingSessionPhase.STOP_REQUESTED) { "请先结束本次采集" }
        require(current.stopConfirmedAtMs != null || reference.status != ReferenceStatus.VALID) {
            "停止尚待确认，请记录读数异常"
        }
        current.copy(reference = reference)
    }

    /**
     * Corrects the reference while it is still a local draft for upload. The previous value stays
     * in the private journal; the research manifest always receives only the final confirmed value.
     */
    fun reviseReferenceBeforeUpload(sessionId: String, reference: SessionReference): FreeLivingSession =
        update(sessionId, allowArchived = true) { current ->
            validateReference(reference)
            val previous = requireNotNull(current.reference) { "本段还没有可修改的计步器读数" }
            require(current.localData != null) { "请先完成戒指数据保存" }
            require(current.transfer.status == SessionTransferStatus.PENDING && current.transfer.attempts == 0 &&
                current.transfer.receipt == null) { "本段已确认上传，原读数保持不变" }
            require(current.completionPolicy in setOf(CompletionPolicy.SAVE_LATER, CompletionPolicy.SAVE_UPLOAD)) {
                "本段尚未选择保存方式"
            }
            if (previous.status == reference.status && previous.steps == reference.steps && previous.reason == reference.reason) {
                return@update current
            }
            current.copy(
                reference = reference,
                referenceRevisions = current.referenceRevisions + SessionReferenceRevision(previous, reference.recordedAtMs),
            )
        }

    fun completeLocalData(sessionId: String, files: List<SessionRawFile>, completedAtMs: Long): FreeLivingSession =
        update(sessionId) { current ->
            require(current.phase == FreeLivingSessionPhase.AWAITING_REFERENCE && current.reference != null) {
                "请先确认结束并保存本次读数"
            }
            require(current.completionPolicy != CompletionPolicy.DEFER_ON_RING) { "请先确认继续保存戒指数据" }
            require(completedAtMs > 0)
            verifyLocalFiles(current, files)
            current.localData?.let {
                require(it.files == files) { "本次文件已确认，不能替换" }
                return@update current
            }
            current.copy(localData = SessionLocalData(files.toList(), completedAtMs))
        }

    fun markTransferStarted(sessionId: String): FreeLivingSession = update(sessionId, allowArchived = true) { current ->
        require(current.uploadAllowed) { "本段已保存，等待手动上传" }
        verifyLocalFiles(current, requireNotNull(current.localData) { "文件尚未保存完整" }.files)
        if (current.transfer.status == SessionTransferStatus.COMPLETE ||
            current.transfer.status == SessionTransferStatus.TRANSFERRING) return@update current
        current.copy(transfer = current.transfer.copy(status = SessionTransferStatus.TRANSFERRING,
            attempts = Math.addExact(current.transfer.attempts, 1)))
    }

    /** Releases only a claim whose HTTP request has not been invoked. */
    fun releaseTransferClaimBeforeDispatch(sessionId: String, claimedAttempt: Int): FreeLivingSession =
        update(sessionId, allowArchived = true) { current ->
            require(current.transfer.status == SessionTransferStatus.TRANSFERRING &&
                current.transfer.receipt == null && current.transfer.attempts == claimedAttempt && claimedAttempt > 0) {
                "上传任务状态已变化，保留当前记录"
            }
            current.copy(transfer = current.transfer.copy(
                status = if (claimedAttempt == 1) SessionTransferStatus.PENDING else SessionTransferStatus.FAILED,
                attempts = claimedAttempt - 1,
            ))
        }

    fun markTransferFailed(sessionId: String): FreeLivingSession = update(sessionId, allowArchived = true) { current ->
        require(current.transfer.status == SessionTransferStatus.TRANSFERRING ||
            current.transfer.status == SessionTransferStatus.FAILED) { "当前没有待确认的传输" }
        current.copy(transfer = current.transfer.copy(status = SessionTransferStatus.FAILED))
    }

    fun completeTransfer(sessionId: String, receipt: SessionTransferReceipt): FreeLivingSession =
        update(sessionId, allowArchived = true) { current ->
            require(receipt.receiptId.isNotBlank() && receipt.receivedAtMs > 0 && receipt.sessionId == current.sessionId) {
                "传输回执不属于本次采集"
            }
            current.transfer.receipt?.let {
                require(it == receipt) { "传输回执已保存，不能替换" }
                return@update current
            }
            require(current.transfer.status == SessionTransferStatus.TRANSFERRING) { "尚未开始传输" }
            val files = requireNotNull(current.localData).files
            require(files.all { it.simulated == receipt.simulated }) { "模拟与真实传输结果不能混用" }
            verifyLocalFiles(current, files)
            current.copy(transfer = current.transfer.copy(status = SessionTransferStatus.COMPLETE, receipt = receipt))
        }

    private fun update(sessionId: String, allowArchived: Boolean = false, allowStartAbort: Boolean = false,
        change: (FreeLivingSession) -> FreeLivingSession): FreeLivingSession =
        synchronized(processLock) {
            val journal = checkNotNull(readJournalLocked()) { "没有可恢复的采集段" }
            val current = if (journal.current.sessionId == sessionId) journal.current
                else if (allowArchived) journal.archived.singleOrNull { it.sessionId == sessionId } else null
            requireNotNull(current) { "采集段不匹配，已保留原记录" }
            require(!current.isDiscarded) { "本段已删除" }
            require(allowStartAbort || current.startAbort == null) { "请先完成异常停止与数据保全" }
            val next = change(current)
            if (next == current) current else {
                val updated = if (current.sessionId == journal.current.sessionId) journal.copy(current = next)
                    else journal.copy(archived = journal.archived.map { if (it.sessionId == sessionId) next else it })
                persistJournal(updated)
                next
            }
        }

    private fun readLocked(): FreeLivingSession? = readJournalLocked()?.current

    private fun readJournalLocked(): Journal? {
        if (!file.exists()) return null
        try {
            val envelope = JsonReader(StringReader(file.readText(Charsets.UTF_8))).use { reader ->
                reader.strictness = Strictness.STRICT
                val value = JsonParser.parseReader(reader)
                require(reader.peek() == JsonToken.END_DOCUMENT) { "采集段后存在额外内容" }
                value.asJsonObject
            }
            val version = envelope.strictLong("journal_version")
            require(version in 1L..13L) { "版本不受支持" }
            val payload = envelope.getAsJsonObject("session") ?: error("缺少采集段")
            val archives = if (version >= 2L) envelope.required("archived_sessions").asJsonArray else JsonArray()
            val hashed = if (version == 1L) payload else journalPayload(payload, archives)
            require(envelope.strictString("sha256") == digest(hashed.toString())) { "采集段完整性检查失败" }
            return Journal(decode(payload, version), archives.map { decode(it.asJsonObject, version) }).also {
                validateJournal(it)
                // A previous rename may have succeeded while syncing its directory failed.
                // Do not acknowledge an idempotent retry until directory persistence succeeds.
                syncDirectory(requireNotNull(file.parentFile))
            }
        } catch (error: RuntimeException) {
            throw IOException("采集段无法读取，原文件已保留，请联系研究者", error)
        }
    }

    private fun persist(session: FreeLivingSession, archived: List<FreeLivingSession>): FreeLivingSession {
        persistJournal(Journal(session, archived))
        return session
    }

    private fun persistJournal(journal: Journal) {
        validateJournal(journal)
        val payload = encode(journal.current)
        val archives = JsonArray().apply { journal.archived.forEach { add(encode(it)) } }
        val envelope = JsonObject().apply {
            addProperty("journal_version", 13)
            add("session", payload)
            add("archived_sessions", archives)
            addProperty("sha256", digest(journalPayload(payload, archives).toString()))
        }
        val directory = file.parentFile ?: throw IOException("采集段目录无效")
        // Android provides a durable app-private files directory. Creating another directory tree
        // here would also require persisting every new ancestor before any BLE command is sent.
        if (!directory.isDirectory) throw IOException("采集段目录尚未就绪")
        val temporary = File.createTempFile("session-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(envelope.toString().toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            commitFile(temporary, file)
            syncDirectory(directory)
        } finally {
            temporary.delete()
        }
    }

    private fun verifyLocalFiles(session: FreeLivingSession, files: List<SessionRawFile>) {
        validateFiles(session, files)
        val directory = requireNotNull(file.parentFile)
        for (entry in files) {
            val source = File(directory, entry.fileName).canonicalFile
            require(source.parentFile == directory && source.name == entry.fileName && source != file) { "文件位置与采集段不匹配" }
            if (!source.isFile || source.length() != entry.bytes) throw IOException("采集文件尚未保存完整")
            val hash = MessageDigest.getInstance("SHA-256")
            source.inputStream().use { stream ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    hash.update(buffer, 0, count)
                }
            }
            if (hex(hash.digest()) != entry.sha256) throw IOException("采集文件校验失败，已保留原记录")
            FileOutputStream(source, true).use { it.fd.sync() }
        }
        syncDirectory(directory)
    }

    companion object {
        private val processLock = Any()
        private const val LEGACY_COMMAND_OWNER_ID = "00000000-0000-0000-0000-000000000000"
        private val participantPattern = Regex("^[a-z0-9]{3,24}$")
        private val ringAddressPattern = Regex("^(?:[0-9A-F]{2}:){5}[0-9A-F]{2}$")

        private fun validateJournal(journal: Journal) {
            val sessions = journal.archived + journal.current
            require(sessions.map { it.sessionId }.distinct().size == sessions.size) { "采集段标识重复" }
            sessions.forEach(::validateSession)
            require(journal.archived.all { !it.isPending }) { "尚未保存完整的采集段不能归档" }
        }

        private fun validateReference(reference: SessionReference) {
            require(reference.recordedAtMs > 0)
            require(reference.steps == null || reference.steps >= 0) { "请输入非负整数步数" }
            require(reference.reason == null || (reference.reason.isNotBlank() && reference.reason == reference.reason.trim()))
            when (reference.status) {
                ReferenceStatus.VALID -> require(reference.steps != null && reference.reason == null)
                ReferenceStatus.MISSING -> require(reference.steps == null && reference.reason != null)
                ReferenceStatus.UNRELIABLE -> require(reference.steps != null && reference.reason != null)
            }
        }

        private fun validateFiles(session: FreeLivingSession, files: List<SessionRawFile>) {
            require(files.isNotEmpty() && files.map { it.fileName }.distinct().size == files.size) { "文件清单为空或重复" }
            require(files.map { it.simulated }.distinct().size == 1) { "模拟文件与真实文件不能混用" }
            files.forEach {
                require(it.fileName.startsWith("${session.sessionId}-") &&
                    Regex("^[a-zA-Z0-9._-]+$").matches(it.fileName)) { "文件不属于当前采集段" }
                require(it.deviceSessionId == session.deviceSessionId) { "文件戒指记录不匹配" }
                require(it.bytes > 0 && Regex("^[a-f0-9]{64}$").matches(it.sha256)) { "文件校验信息无效" }
            }
        }

        private fun validatePreparation(preparation: PreparationSnapshot) {
            require(participantPattern.matches(preparation.participantId)) { "请使用已登记的规范编号" }
            require(UUID.fromString(preparation.installationId).toString() == preparation.installationId)
            requireNotNull(preparation.placement) { "请先确认佩戴位置" }
            val ring = requireNotNull(preparation.ring) { "请先选择戒指" }
            require(ringAddressPattern.matches(ring.address) && ring.name.isNotBlank() && ring.name == ring.name.trim())
        }

        private fun validateReply(current: FreeLivingSession, address: String, status: HealthMessage.Status,
            receivedAtMs: Long, collecting: Boolean) {
            require(current.preparation.ring?.address == address.uppercase(Locale.ROOT)) { "戒指不匹配，已保留原记录" }
            require(receivedAtMs > 0 && status.collecting == collecting && status.errorCode == 0) { "戒指未确认所需采集状态" }
            require(status.sessionId in 0..65535 && status.bytes in 0..0xFFFF_FFFFL &&
                status.records in 0..0xFFFF_FFFFL) { "戒指状态无效" }
            require(current.deviceSessionId == null || current.deviceSessionId == status.sessionId) { "戒指记录不属于本采集段" }
        }

        private fun validateBoundary(boundary: DeviceBoundaryEvidence?) {
            if (boundary == null) return
            require(boundary.epochMs > 0 && (boundary.deviceUptimeMs == null || boundary.deviceUptimeMs in 0..0xFFFF_FFFFL))
            require(boundary.rawEvidence.isNotBlank()) { "设备时间须保留来源证据" }
        }

        private fun validateDeviceStatus(status: HealthMessage.Status) {
            require(status.sessionId in 0..65535 && status.bytes in 0..0xFFFF_FFFFL &&
                status.records in 0..0xFFFF_FFFFL && status.errorCode == 0)
        }

        private fun validateStartBaseline(baseline: DeviceStartBaseline) {
            val recovery = baseline.chargingRecoveryEvidence
            if (recovery == null) validateDeviceStatus(baseline.status)
            else {
                recovery.validate(baseline.status, baseline.observedAtMs)
                validateDeviceStatus(baseline.status.copy(errorCode = 0))
            }
            require(!baseline.status.collecting && baseline.observedAtMs > 0)
            require(baseline.records.size <= 255 && baseline.records.map { it.sessionId }.distinct().size == baseline.records.size)
            baseline.records.forEach(::validateDeviceRecord)
            if (baseline.records.isEmpty()) require(baseline.status.bytes == 0L && baseline.status.records == 0L)
            else require(baseline.records.any { it.sessionId == baseline.status.sessionId &&
                it.bytes == baseline.status.bytes && it.records == baseline.status.records })
            baseline.unknownTimeStartEvidence?.validate(baseline)
        }

        private fun validateDeviceRecord(record: HealthMessage.ListItem) {
            require(record.sessionId in 1..65535 && record.bytes in 0..0xFFFF_FFFFL &&
                record.records in 0..0xFFFF_FFFFL && record.uptimeMs in 0..0xFFFF_FFFFL && record.unixMs >= 0)
        }

        private fun validateStartAttemptArchive(session: FreeLivingSession, audit: StartAttemptArchive) {
            require(session.phase == FreeLivingSessionPhase.START_REQUESTED && session.startConfirmedAtMs == null &&
                session.stopRequestedAtMs == null && session.stopObservedAtMs == null && session.stopOrigin == null &&
                session.stopConfirmedAtMs == null && session.stopCommandDispatch == null &&
                session.deviceRecordEvidence == null && !session.deviceAssociationInvalidated &&
                session.reference == null && session.localData == null) { "本次记录仍需保留，请联系研究者" }
            require(audit.archivedAtMs > 0 && audit.reason.length in 1..200 && audit.reason == audit.reason.trim()) {
                "请填写简短原因（200 字以内）"
            }
            val baseline = requireNotNull(session.startBaseline) { "缺少开始前记录，请联系研究者" }
            validateStartBaseline(baseline)
            val observed = audit.observation
            // Observed -16 remains opaque; unchanged records and verified originals govern archival.
            require(observed.address == session.preparation.ring?.address && observed.connectionGeneration > 0 &&
                observed.statusReceivedAtMs > 0 && !observed.status.collecting &&
                observed.status.errorCode in setOf(0, -16) &&
                observed.status.copy(errorCode = 0) == baseline.status.copy(errorCode = 0) &&
                observed.records.size == baseline.records.size && observed.records.toSet() == baseline.records.toSet()) {
                "戒指状态或记录有变化，请联系研究者"
            }
            audit.unknownPreservation?.validate(observed)
        }

        fun sameDeviceRecord(first: HealthMessage.ListItem, second: HealthMessage.ListItem): Boolean =
            first.sessionId == second.sessionId && first.uptimeMs == second.uptimeMs && first.unixMs == second.unixMs

        /** A reused numeric ID needs two changed, nonzero anchors in the reviewed idle record. */
        fun isDistinctStartRecord(baseline: DeviceStartBaseline, candidate: HealthMessage.ListItem): Boolean {
            if (baseline.unknownTimeStartEvidence?.isDistinct(candidate) == false) return false
            val previous = baseline.records.singleOrNull { it.sessionId == candidate.sessionId }
                ?: return candidate.sessionId != baseline.status.sessionId
            if (previous.unixMs == 0L) return baseline.unknownTimeStartEvidence?.let {
                it.record == previous && it.isDistinct(candidate)
            } == true
            return previous.unixMs > 0 && candidate.unixMs > 0 && previous.uptimeMs > 0 && candidate.uptimeMs > 0 &&
                previous.unixMs != candidate.unixMs && previous.uptimeMs != candidate.uptimeMs
        }

        private fun validateSession(session: FreeLivingSession) {
            require(UUID.fromString(session.sessionId).toString() == session.sessionId)
            validatePreparation(session.preparation)
            require(session.startRequestedAtMs > 0)
            ZoneId.of(session.timeZoneId)
            // Preserve the offset captured at start; later system tzdb updates can change rules.
            require(session.utcOffsetSeconds in -64_800..64_800)
            val hasStart = session.phase != FreeLivingSessionPhase.START_REQUESTED
            val hasStopTransition = session.phase in setOf(FreeLivingSessionPhase.STOP_REQUESTED, FreeLivingSessionPhase.AWAITING_REFERENCE)
            val hasStop = session.phase == FreeLivingSessionPhase.AWAITING_REFERENCE
            require((session.startConfirmedAtMs != null) == hasStart && (session.startStatusEvidence != null) == hasStart)
            require((session.stopOrigin != null) == hasStopTransition)
            when (session.stopOrigin) {
                null -> require(session.stopRequestedAtMs == null && session.stopObservedAtMs == null &&
                    session.stopCommandDispatch == null)
                StopOrigin.USER_REQUEST -> require(session.stopRequestedAtMs != null && session.stopObservedAtMs == null &&
                    session.stopCommandDispatch != null)
                StopOrigin.DEVICE_OBSERVED -> require(session.stopRequestedAtMs == null && session.stopObservedAtMs != null &&
                    session.stopCommandDispatch == null)
                StopOrigin.LEGACY_UNSPECIFIED -> require(session.stopRequestedAtMs != null && session.stopObservedAtMs == null &&
                    session.stopCommandDispatch == null)
            }
            require((session.stopConfirmedAtMs != null) == hasStop && (session.stopStatusEvidence != null) == hasStop)
            require(hasStart || session.startBoundaryEvidence == null)
            require(hasStop || session.endBoundaryEvidence == null)
            session.startStatusEvidence?.let { validateReply(session, session.preparation.ring!!.address, it, session.startConfirmedAtMs!!, true) }
            session.stopStatusEvidence?.let { validateReply(session, session.preparation.ring!!.address, it, session.stopConfirmedAtMs!!, false) }
            session.stopRequestedAtMs?.let { require(it > 0) }
            session.stopObservedAtMs?.let { require(it > 0) }
            session.startCommandDispatch?.let { dispatch ->
                require(session.startBaseline != null && dispatch.preparedAtMs > 0)
                dispatch.acceptedAtMs?.let { require(it > 0) }
                require((dispatch.ownerId == null) == (dispatch.connectionGeneration == null))
                dispatch.ownerId?.let { require(UUID.fromString(it).toString() == it) }
                dispatch.connectionGeneration?.let { require(it > 0) }
            }
            session.stopCommandDispatch?.let { dispatch ->
                require(session.stopOrigin == StopOrigin.USER_REQUEST && dispatch.preparedAtMs > 0 &&
                    dispatch.preparedAtMs >= requireNotNull(session.stopRequestedAtMs))
                dispatch.acceptedAtMs?.let { require(it > 0) }
                require((dispatch.ownerId == null) == (dispatch.connectionGeneration == null))
                dispatch.ownerId?.let { require(UUID.fromString(it).toString() == it) }
                dispatch.connectionGeneration?.let { require(it > 0) }
            }
            validateBoundary(session.startBoundaryEvidence)
            validateBoundary(session.endBoundaryEvidence)
            session.startBaseline?.let { baseline ->
                validateStartBaseline(baseline)
                baseline.unknownTimeStartEvidence?.let {
                    require(it.clock.ringAddress == session.preparation.ring?.address) { "校时证据不属于本枚戒指" }
                }
            }
            session.deviceRecordEvidence?.let { evidence ->
                require(hasStart && session.startBaseline != null && evidence.observedAtMs > 0)
                validateDeviceStatus(evidence.status)
                validateDeviceRecord(evidence.record)
                require(evidence.record.unixMs > 0 || evidence.record.uptimeMs > 0)
                require(evidence.record.sessionId == session.deviceSessionId && evidence.status.sessionId == session.deviceSessionId)
                require(isDistinctStartRecord(session.startBaseline, evidence.record))
                // An unsolicited STATUS may be newer than the most recently completed LIST.
                if (hasStop) require(!evidence.status.collecting && evidence.record.bytes == evidence.status.bytes &&
                    evidence.record.records == evidence.status.records)
                // A requested stop may preserve an early stopped reply while its LIST is pending.
                else if (!hasStopTransition) require(evidence.status.collecting)
            }
            session.reference?.let {
                validateReference(it)
                require(hasStopTransition && (hasStop || it.status != ReferenceStatus.VALID))
            }
            session.referenceRevisions.forEachIndexed { index, revision ->
                validateReference(revision.previous)
                require(revision.replacedAtMs > 0)
                val replacement = session.referenceRevisions.getOrNull(index + 1)?.previous ?: session.reference
                require(replacement != null && revision.replacedAtMs == replacement.recordedAtMs) {
                    "计步器读数修订记录不连续"
                }
            }
            if (session.referenceRevisions.isNotEmpty()) require(session.localData != null)
            session.localData?.let {
                require(hasStop && session.reference != null && it.completedAtMs > 0)
                validateFiles(session, it.files)
            }
            val transfer = session.transfer
            require(transfer.attempts >= 0)
            require((transfer.receipt != null) == (transfer.status == SessionTransferStatus.COMPLETE))
            if (transfer.status == SessionTransferStatus.PENDING) require(transfer.attempts == 0)
            else require(session.localData != null && transfer.attempts > 0)
            transfer.receipt?.let {
                require(it.receiptId.isNotBlank() && it.receivedAtMs > 0 && it.sessionId == session.sessionId)
                require(session.localData!!.files.all { file -> file.simulated == it.simulated })
            }
            session.startAttemptArchive?.let { validateStartAttemptArchive(session, it) }
            session.startAbort?.validate(session)
            if (session.completionPolicy != null) require(hasStop)
            if (session.completionPolicy == CompletionPolicy.SAVE_LATER) require(transfer.attempts == 0)
            if (session.completionPolicy == CompletionPolicy.DEFER_ON_RING) {
                require(hasStop && session.reference != null && session.localData == null &&
                    transfer.status == SessionTransferStatus.PENDING && transfer.attempts == 0 && transfer.receipt == null)
            }
            session.discarded?.let { discarded ->
                require(hasStop && session.startAttemptArchive == null && discarded.discardedAtMs > 0)
                require(transfer.status == SessionTransferStatus.PENDING && transfer.attempts == 0 && transfer.receipt == null)
                require((discarded.connectionOwnerId == null) == (discarded.connectionGeneration == null))
                discarded.connectionOwnerId?.let { require(UUID.fromString(it).toString() == it) }
                discarded.connectionGeneration?.let { require(it > 0) }
            }
        }

        private fun encode(s: FreeLivingSession, version: Long = 13L) = JsonObject().apply {
            require(version >= 13L || s.completionPolicy != CompletionPolicy.DEFER_ON_RING)
            addProperty("session_id", s.sessionId)
            addProperty("participant_id", s.preparation.participantId)
            addProperty("participant_name", s.preparation.participantId)
            addProperty("installation_id", s.preparation.installationId)
            addProperty("ring_placement", s.preparation.placement!!.wireValue)
            addProperty("ring_address", s.preparation.ring!!.address)
            addProperty("ring_name", s.preparation.ring.name)
            addProperty("phase", s.phase.wireValue)
            addProperty("time_zone_id", s.timeZoneId)
            addProperty("utc_offset_seconds", s.utcOffsetSeconds)
            addProperty("capture_purpose", "daily_activity")
            addActivity(if (version >= 6L) s.activity else SessionActivity.FREE_LIVING)
            addProperty("activity_label_status", "unlabelled")
            addProperty("activity_label_source", "none")
            addProperty("ground_truth_source", "external_pedometer")
            addProperty("ground_truth_status", s.reference?.status?.wireValue ?: "missing")
            addNullable("ground_truth_steps", s.reference?.steps)
            addNullable("ground_truth_recorded_at_ms", s.reference?.groundTruthRecordedAtMs)
            addProperty("data_integrity_status", if (s.localData != null) "complete" else "pending")
            addNullable("download_completed_at_ms", s.localData?.completedAtMs)
            if (version >= 2L) {
                addNullable("reference_saved_at_ms", s.reference?.recordedAtMs)
                add("ground_truth_reason", s.reference?.reason?.let { com.google.gson.JsonPrimitive(it) } ?: JsonNull.INSTANCE)
                add("raw_files", JsonArray().apply { s.localData?.files?.forEach { add(encodeFile(it)) } })
                add("transfer", JsonObject().apply {
                    addProperty("status", s.transfer.status.wireValue)
                    addProperty("attempts", s.transfer.attempts)
                    add("receipt", s.transfer.receipt?.let { receipt -> JsonObject().apply {
                        addProperty("receipt_id", receipt.receiptId)
                        addProperty("received_at_ms", receipt.receivedAtMs)
                        addProperty("simulated", receipt.simulated)
                        addProperty("session_id", receipt.sessionId)
                    } } ?: JsonNull.INSTANCE)
                })
            }
            addProperty("start_requested_at_ms", s.startRequestedAtMs)
            addNullable("start_confirmed_at_ms", s.startConfirmedAtMs)
            addNullable("stop_requested_at_ms", s.stopRequestedAtMs)
            if (version >= 12L) {
                addNullable("stop_observed_at_ms", s.stopObservedAtMs)
                add("stop_origin", s.stopOrigin?.let { com.google.gson.JsonPrimitive(it.wireValue) } ?: JsonNull.INSTANCE)
            }
            addNullable("stop_confirmed_at_ms", s.stopConfirmedAtMs)
            addNullable("started_at_ms", s.startedAtMs)
            addNullable("ended_at_ms", s.endedAtMs)
            addNullable("device_session_id", s.deviceSessionId?.toLong())
            addProperty("capture_boundary_status", s.captureBoundaryStatus)
            add("timing_warnings", JsonArray().apply { s.timingWarnings.forEach(::add) })
            add("start_status_evidence", encodeStatus(s.startStatusEvidence))
            add("stop_status_evidence", encodeStatus(s.stopStatusEvidence))
            add("start_boundary_evidence", encodeBoundary(s.startBoundaryEvidence))
            add("end_boundary_evidence", encodeBoundary(s.endBoundaryEvidence))
            if (version >= 3L) {
                add("start_baseline", s.startBaseline?.let { baseline -> JsonObject().apply {
                    add("status", encodeStatus(baseline.status))
                    add("records", JsonArray().apply { baseline.records.forEach { add(encodeRecord(it)) } })
                    addProperty("observed_at_ms", baseline.observedAtMs)
                    if (version >= 5L) add("charging_recovery_evidence",
                        baseline.chargingRecoveryEvidence?.let(::encodeChargingRecovery) ?: JsonNull.INSTANCE)
                    if (version >= 7L) add("unknown_time_start_evidence",
                        baseline.unknownTimeStartEvidence?.encode() ?: JsonNull.INSTANCE)
                } } ?: JsonNull.INSTANCE)
                add("device_record_evidence", s.deviceRecordEvidence?.let { evidence -> JsonObject().apply {
                    add("record", encodeRecord(evidence.record))
                    add("status", encodeStatus(evidence.status))
                    addProperty("observed_at_ms", evidence.observedAtMs)
                } } ?: JsonNull.INSTANCE)
                addProperty("device_association_invalidated", s.deviceAssociationInvalidated)
            }
            if (version >= 4L) add("start_attempt_archive", s.startAttemptArchive?.let { audit -> JsonObject().apply {
                addProperty("archived_at_ms", audit.archivedAtMs)
                addProperty("reason", audit.reason)
                addProperty("ring_address", audit.observation.address)
                addProperty("connection_generation", audit.observation.connectionGeneration)
                addProperty("observed_at_ms", audit.observation.statusReceivedAtMs)
                add("status", encodeStatus(audit.observation.status))
                add("records", JsonArray().apply { audit.observation.records.forEach { add(encodeRecord(it)) } })
                if (version >= 7L) add("unknown_preservation", audit.unknownPreservation?.encode() ?: JsonNull.INSTANCE)
            } } ?: JsonNull.INSTANCE)
            if (version >= 8L) {
                add("completion_policy", s.completionPolicy?.let { com.google.gson.JsonPrimitive(it.wireValue) } ?: JsonNull.INSTANCE)
                add("discarded", s.discarded?.let { discarded -> JsonObject().apply {
                    addProperty("discarded_at_ms", discarded.discardedAtMs)
                    add("connection_owner_id", discarded.connectionOwnerId?.let { com.google.gson.JsonPrimitive(it) } ?: JsonNull.INSTANCE)
                    addNullable("connection_generation", discarded.connectionGeneration)
                } } ?: JsonNull.INSTANCE)
            }
            if (version >= 9L) add("start_abort", s.startAbort?.let { audit -> JsonObject().apply {
                addProperty("requested_at_ms", audit.requestedAtMs)
                addProperty("owner_id", audit.ownerId)
                add("collecting_observation", encodeObservation(audit.collectingObservation))
                add("stopped_observation", audit.stoppedObservation?.let(::encodeObservation) ?: JsonNull.INSTANCE)
                add("preservation", audit.preservation?.encode() ?: JsonNull.INSTANCE)
                add("preserved_observation", audit.preservedObservation?.let(::encodeObservation) ?: JsonNull.INSTANCE)
                addNullable("completed_at_ms", audit.completedAtMs)
                add("completion_owner_id", audit.completionOwnerId?.let { com.google.gson.JsonPrimitive(it) } ?: JsonNull.INSTANCE)
            } } ?: JsonNull.INSTANCE)
            if (version >= 10L) add("reference_revisions", JsonArray().apply {
                s.referenceRevisions.forEach { revision -> add(JsonObject().apply {
                    add("previous", encodeReference(revision.previous))
                    addProperty("replaced_at_ms", revision.replacedAtMs)
                }) }
            })
            if (version >= 11L) add("start_command_dispatch", s.startCommandDispatch?.let { dispatch ->
                JsonObject().apply {
                    addProperty("prepared_at_ms", dispatch.preparedAtMs)
                    addNullable("accepted_at_ms", dispatch.acceptedAtMs)
                    if (version >= 12L) {
                        add("owner_id", dispatch.ownerId?.let { com.google.gson.JsonPrimitive(it) } ?: JsonNull.INSTANCE)
                        addNullable("connection_generation", dispatch.connectionGeneration)
                    }
                }
            } ?: JsonNull.INSTANCE)
            if (version >= 12L) add("stop_command_dispatch", s.stopCommandDispatch?.let { dispatch ->
                JsonObject().apply {
                    addProperty("prepared_at_ms", dispatch.preparedAtMs)
                    addNullable("accepted_at_ms", dispatch.acceptedAtMs)
                    add("owner_id", dispatch.ownerId?.let { com.google.gson.JsonPrimitive(it) } ?: JsonNull.INSTANCE)
                    addNullable("connection_generation", dispatch.connectionGeneration)
                }
            } ?: JsonNull.INSTANCE)
        }

        private fun decode(p: JsonObject, version: Long): FreeLivingSession {
            val extended = version >= 2L
            val s = FreeLivingSession(
                sessionId = p.strictString("session_id"),
                preparation = PreparationSnapshot(p.strictString("participant_id"), p.strictString("installation_id"),
                    requireNotNull(RingPlacement.fromWireValue(p.strictString("ring_placement"))),
                    PreparedRing(p.strictString("ring_address"), p.strictString("ring_name"))),
                phase = FreeLivingSessionPhase.entries.single { it.wireValue == p.strictString("phase") },
                timeZoneId = p.strictString("time_zone_id"),
                utcOffsetSeconds = Math.toIntExact(p.strictLong("utc_offset_seconds")),
                startRequestedAtMs = p.strictLong("start_requested_at_ms"),
                startConfirmedAtMs = p.nullableLong("start_confirmed_at_ms"),
                stopRequestedAtMs = p.nullableLong("stop_requested_at_ms"),
                stopObservedAtMs = if (version < 12L) null else p.nullableLong("stop_observed_at_ms"),
                stopOrigin = if (version < 12L) {
                    if (p.nullableLong("stop_requested_at_ms") == null) null else StopOrigin.LEGACY_UNSPECIFIED
                } else if (p.required("stop_origin").isJsonNull) null else
                    StopOrigin.entries.single { it.wireValue == p.strictString("stop_origin") },
                stopConfirmedAtMs = p.nullableLong("stop_confirmed_at_ms"),
                startStatusEvidence = decodeStatus(p.required("start_status_evidence")),
                stopStatusEvidence = decodeStatus(p.required("stop_status_evidence")),
                startBoundaryEvidence = decodeBoundary(p.required("start_boundary_evidence")),
                endBoundaryEvidence = decodeBoundary(p.required("end_boundary_evidence")),
                reference = if (!extended || p.nullableLong("reference_saved_at_ms") == null) null else SessionReference(
                    ReferenceStatus.entries.single { it.wireValue == p.strictString("ground_truth_status") },
                    p.nullableLong("ground_truth_steps"), p.strictLong("reference_saved_at_ms"),
                    if (p.required("ground_truth_reason").isJsonNull) null else p.strictString("ground_truth_reason")),
                localData = if (!extended || p.nullableLong("download_completed_at_ms") == null) null else SessionLocalData(
                    p.required("raw_files").asJsonArray.map { decodeFile(it.asJsonObject) },
                    p.strictLong("download_completed_at_ms")),
                transfer = if (extended) decodeTransfer(p.required("transfer").asJsonObject) else SessionTransfer(),
                startBaseline = if (version < 3L || p.required("start_baseline").isJsonNull) null else
                    p.getAsJsonObject("start_baseline").let { DeviceStartBaseline(requireNotNull(decodeStatus(it.required("status"))),
                        it.required("records").asJsonArray.map { record -> decodeRecord(record.asJsonObject) }, it.strictLong("observed_at_ms"),
                        if (version < 5L || it.required("charging_recovery_evidence").isJsonNull) null else
                            decodeChargingRecovery(it.getAsJsonObject("charging_recovery_evidence")),
                        if (version < 7L || it.required("unknown_time_start_evidence").isJsonNull) null else
                            UnknownTimeStartEvidence.decode(it.getAsJsonObject("unknown_time_start_evidence"))) },
                deviceRecordEvidence = if (version < 3L || p.required("device_record_evidence").isJsonNull) null else
                    p.getAsJsonObject("device_record_evidence").let { DeviceRecordEvidence(decodeRecord(it.getAsJsonObject("record")),
                        requireNotNull(decodeStatus(it.required("status"))), it.strictLong("observed_at_ms")) },
                deviceAssociationInvalidated = version >= 3L && p.strictBoolean("device_association_invalidated"),
                startAttemptArchive = if (version < 4L || p.required("start_attempt_archive").isJsonNull) null else
                    p.getAsJsonObject("start_attempt_archive").let { StartAttemptArchive(it.strictLong("archived_at_ms"),
                        it.strictString("reason"), HealthRecordObservation(it.strictString("ring_address"),
                            it.strictLong("connection_generation"), requireNotNull(decodeStatus(it.required("status"))),
                            it.strictLong("observed_at_ms"), it.required("records").asJsonArray.map { row -> decodeRecord(row.asJsonObject) }),
                        if (version < 7L || it.required("unknown_preservation").isJsonNull) null else
                            UnknownTimeRecordProof.decode(it.getAsJsonObject("unknown_preservation"))) },
                activity = if (version < 6L) SessionActivity.FREE_LIVING else
                    requireNotNull(SessionActivity.fromWireValue(p.strictString("activity_code"))) { "活动类型无效" },
                completionPolicy = if (version < 8L) {
                    if (extended && p.nullableLong("reference_saved_at_ms") != null &&
                        p.nullableLong("stop_confirmed_at_ms") != null) CompletionPolicy.SAVE_UPLOAD else null
                } else if (p.required("completion_policy").isJsonNull) null else
                    CompletionPolicy.entries.single { it.wireValue == p.strictString("completion_policy") }.also {
                        require(version >= 13L || it != CompletionPolicy.DEFER_ON_RING) { "完成方式版本不受支持" }
                    },
                discarded = if (version < 8L || p.required("discarded").isJsonNull) null else
                    p.getAsJsonObject("discarded").let { SessionDiscard(it.strictLong("discarded_at_ms"),
                        if (it.required("connection_owner_id").isJsonNull) null else it.strictString("connection_owner_id"),
                        it.nullableLong("connection_generation")) },
                startAbort = if (version < 9L || p.required("start_abort").isJsonNull) null else
                    p.getAsJsonObject("start_abort").let { UnconfirmedStartAbort(it.strictLong("requested_at_ms"),
                        it.strictString("owner_id"), decodeObservation(it.getAsJsonObject("collecting_observation")),
                        if (it.required("stopped_observation").isJsonNull) null else decodeObservation(it.getAsJsonObject("stopped_observation")),
                        if (it.required("preservation").isJsonNull) null else UnknownTimeRecordProof.decode(it.getAsJsonObject("preservation")),
                        if (it.required("preserved_observation").isJsonNull) null else decodeObservation(it.getAsJsonObject("preserved_observation")),
                        it.nullableLong("completed_at_ms"),
                        if (it.required("completion_owner_id").isJsonNull) null else it.strictString("completion_owner_id")) },
                referenceRevisions = if (version < 10L) emptyList() else
                    p.required("reference_revisions").asJsonArray.map { item -> item.asJsonObject.let {
                        SessionReferenceRevision(decodeReference(it.getAsJsonObject("previous")),
                            it.strictLong("replaced_at_ms"))
                    } },
                startCommandDispatch = if (version < 11L || p.required("start_command_dispatch").isJsonNull) null else
                    p.getAsJsonObject("start_command_dispatch").let {
                        StartCommandDispatch(it.strictLong("prepared_at_ms"), it.nullableLong("accepted_at_ms"),
                            if (version < 12L || it.required("owner_id").isJsonNull) null else it.strictString("owner_id"),
                            if (version < 12L) null else it.nullableLong("connection_generation"))
                    },
                stopCommandDispatch = if (version < 12L || p.required("stop_command_dispatch").isJsonNull) null else
                    p.getAsJsonObject("stop_command_dispatch").let {
                        StopCommandDispatch(it.strictLong("prepared_at_ms"), it.nullableLong("accepted_at_ms"),
                            if (it.required("owner_id").isJsonNull) null else it.strictString("owner_id"),
                            it.nullableLong("connection_generation"))
                    },
            )
            // Re-encoding checks required fields, fixed metadata, explicit nulls and derived values.
            require(encode(s, version) == p) { "采集段字段不完整或数据含义不一致" }
            return s
        }

        private fun JsonObject.addActivity(activity: SessionActivity) {
            addProperty("activity_schema", if (activity == SessionActivity.FREE_LIVING) "daily_activity_v2" else "daily_activity_v3")
            addProperty("activity_code", activity.wireValue)
            if (activity != SessionActivity.FREE_LIVING) addProperty("activity_selection_source", "participant")
        }

        private fun encodeReference(reference: SessionReference) = JsonObject().apply {
            addProperty("status", reference.status.wireValue)
            addNullable("steps", reference.steps)
            addProperty("recorded_at_ms", reference.recordedAtMs)
            add("reason", reference.reason?.let { com.google.gson.JsonPrimitive(it) } ?: JsonNull.INSTANCE)
        }

        private fun decodeReference(value: JsonObject) = SessionReference(
            ReferenceStatus.entries.single { it.wireValue == value.strictString("status") },
            value.nullableLong("steps"), value.strictLong("recorded_at_ms"),
            if (value.required("reason").isJsonNull) null else value.strictString("reason"),
        )

        private fun encodeObservation(observation: HealthRecordObservation) = JsonObject().apply {
            addProperty("ring_address", observation.address)
            addProperty("connection_generation", observation.connectionGeneration)
            addProperty("observed_at_ms", observation.statusReceivedAtMs)
            add("status", encodeStatus(observation.status))
            add("records", JsonArray().apply { observation.records.forEach { add(encodeRecord(it)) } })
        }

        private fun decodeObservation(p: JsonObject) = HealthRecordObservation(p.strictString("ring_address"),
            p.strictLong("connection_generation"), requireNotNull(decodeStatus(p.required("status"))),
            p.strictLong("observed_at_ms"), p.required("records").asJsonArray.map { decodeRecord(it.asJsonObject) })

        private fun encodeFile(file: SessionRawFile) = JsonObject().apply {
            addProperty("file_name", file.fileName)
            addProperty("device_session_id", file.deviceSessionId)
            addProperty("bytes", file.bytes)
            addProperty("sha256", file.sha256)
            addProperty("simulated", file.simulated)
        }

        private fun encodeChargingRecovery(evidence: ChargingRecoveryEvidence) = JsonObject().apply {
            addProperty("status_error_reason", evidence.statusErrorReason)
            addProperty("battery_charge_status", evidence.batteryChargeStatus)
            addProperty("battery_received_at_ms", evidence.batteryReceivedAtMs)
            addProperty("status_received_at_ms", evidence.statusReceivedAtMs)
            addProperty("checked_at_ms", evidence.checkedAtMs)
            addProperty("status_connection_generation", evidence.statusConnectionGeneration)
            addProperty("battery_connection_generation", evidence.batteryConnectionGeneration)
        }

        private fun decodeChargingRecovery(p: JsonObject) = ChargingRecoveryEvidence(
            statusErrorReason = Math.toIntExact(p.strictLong("status_error_reason")),
            batteryChargeStatus = Math.toIntExact(p.strictLong("battery_charge_status")),
            batteryReceivedAtMs = p.strictLong("battery_received_at_ms"),
            statusReceivedAtMs = p.strictLong("status_received_at_ms"),
            checkedAtMs = p.strictLong("checked_at_ms"),
            statusConnectionGeneration = p.strictLong("status_connection_generation"),
            batteryConnectionGeneration = p.strictLong("battery_connection_generation"),
        )

        private fun encodeRecord(record: HealthMessage.ListItem) = JsonObject().apply {
            addProperty("device_session_id", record.sessionId)
            addProperty("bytes", record.bytes)
            addProperty("records", record.records)
            addProperty("uptime_ms", record.uptimeMs)
            addProperty("unix_ms", record.unixMs)
        }

        private fun decodeRecord(p: JsonObject) = HealthMessage.ListItem(Math.toIntExact(p.strictLong("device_session_id")),
            p.strictLong("bytes"), p.strictLong("records"), p.strictLong("uptime_ms"), p.strictLong("unix_ms"))

        private fun decodeFile(p: JsonObject) = SessionRawFile(p.strictString("file_name"),
            Math.toIntExact(p.strictLong("device_session_id")), p.strictLong("bytes"),
            p.strictString("sha256"), p.strictBoolean("simulated"))

        private fun decodeTransfer(p: JsonObject): SessionTransfer {
            val receipt = p.required("receipt")
            return SessionTransfer(SessionTransferStatus.entries.single { it.wireValue == p.strictString("status") },
                Math.toIntExact(p.strictLong("attempts")), if (receipt.isJsonNull) null else receipt.asJsonObject.let {
                    SessionTransferReceipt(it.strictString("receipt_id"), it.strictLong("received_at_ms"),
                        it.strictBoolean("simulated"), it.strictString("session_id"))
                })
        }

        private fun journalPayload(session: JsonObject, archives: JsonArray) = JsonObject().apply {
            add("session", session)
            add("archived_sessions", archives)
        }

        private fun encodeStatus(status: HealthMessage.Status?): JsonElement = status?.let {
            JsonObject().apply {
                addProperty("collecting", it.collecting); addProperty("bytes", it.bytes)
                addProperty("records", it.records); addProperty("error_code", it.errorCode)
                addProperty("device_session_id", it.sessionId)
            }
        } ?: JsonNull.INSTANCE

        private fun decodeStatus(value: JsonElement): HealthMessage.Status? {
            if (value.isJsonNull) return null
            val p = value.asJsonObject
            val collecting = p.required("collecting")
            require(collecting.isJsonPrimitive && collecting.asJsonPrimitive.isBoolean)
            return HealthMessage.Status(collecting.asBoolean, p.strictLong("bytes"), p.strictLong("records"),
                Math.toIntExact(p.strictLong("error_code")), Math.toIntExact(p.strictLong("device_session_id")))
        }

        private fun encodeBoundary(evidence: DeviceBoundaryEvidence?): JsonElement = evidence?.let {
            JsonObject().apply {
                addProperty("source", it.source.wireValue); addProperty("epoch_ms", it.epochMs)
                addNullable("device_uptime_ms", it.deviceUptimeMs); addProperty("raw_evidence", it.rawEvidence)
            }
        } ?: JsonNull.INSTANCE

        private fun decodeBoundary(value: JsonElement): DeviceBoundaryEvidence? {
            if (value.isJsonNull) return null
            val p = value.asJsonObject
            return DeviceBoundaryEvidence(DeviceBoundarySource.entries.single { it.wireValue == p.strictString("source") },
                p.strictLong("epoch_ms"), p.nullableLong("device_uptime_ms"), p.strictString("raw_evidence"))
        }

        private fun JsonObject.required(name: String): JsonElement = requireNotNull(get(name)) { "缺少字段 $name" }
        private fun JsonObject.strictString(name: String): String = required(name).let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isString); it.asString
        }
        private fun JsonObject.strictBoolean(name: String): Boolean = required(name).let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean); it.asBoolean
        }
        private fun JsonObject.strictLong(name: String): Long = required(name).let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber && Regex("-?[0-9]+").matches(it.asString))
            it.asString.toLong()
        }
        private fun JsonObject.nullableLong(name: String): Long? =
            if (required(name).isJsonNull) null else strictLong(name)
        private fun JsonObject.addNullable(name: String, value: Long?) {
            if (value == null) add(name, JsonNull.INSTANCE) else addProperty(name, value)
        }
        private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).let(::hex)
        private fun hex(value: ByteArray): String = value.joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
    }
}
