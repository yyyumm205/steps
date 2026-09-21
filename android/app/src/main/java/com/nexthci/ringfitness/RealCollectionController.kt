package com.nexthci.ringfitness

import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** The service supplies a fresh connection generation and serializes every callback. */
interface RealCollectionPort : HealthControlPort {
    fun syncTime(unixMs: Long): Boolean = false
    fun connect(ring: PreparedRing, generation: Long): Boolean
    fun disconnect()
    fun read(sessionId: Int, offset: Long, length: Int): Boolean
}

fun interface CollectionScheduler { fun schedule(delayMs: Long, action: () -> Unit) }

/** Upload execution has an independent owner and never controls the collection connection. */
interface RealUploadPort {
    /** True only when this build has a syntactically valid destination ready for new tasks. */
    val available: Boolean get() = true
    fun enqueue(sessionId: String, retry: Boolean = false)
    fun isInFlight(sessionId: String): Boolean
    fun needsLocalReview(sessionId: String): Boolean = false
    fun discard(sessionId: String, atMs: Long, ownerId: String, generation: Long) = Unit
}

/** Single serial owner for real device control, reference commits and recoverable downloads. */
class RealCollectionController(
    private val directory: File,
    private val preparation: PreparationStore,
    private val store: FreeLivingSessionStore,
    private val port: RealCollectionPort,
    private val scheduler: CollectionScheduler,
    private val clock: CaptureClock,
    private val authorizedExisting: () -> ExistingRecordAuthorization? = { null },
    private val notifyObserver: (() -> Unit) -> Unit = { it() },
    private val recordObservation: (HealthRecordObservation) -> Unit = {},
    private val reportError: (Exception) -> Unit = {},
    private val downloadFactory: (File, FreeLivingSession, HealthMessage.ListItem) -> RealSessionDownload = { folder, session, record ->
        RealSessionDownload(folder, session.sessionId, requireNotNull(session.preparation.ring).address, record,
            session.startedAtMs ?: 0L, session.endedAtMs ?: 0L)
    },
    private val uploads: RealUploadPort? = null,
    private val backups: DeviceRecordBackupStore = DeviceRecordBackupStore(File(directory, "device-backups")),
    private val syncClockBeforeStart: Boolean = false,
    private val saveClockEvidence: (PhoneClockSyncEvidence, String?) -> Unit = { evidence, sessionId ->
        PhoneClockSync.save(directory, evidence, sessionId)
    },
) : CollectionFlow {
    private val observers = CopyOnWriteArrayList<(CollectionFlowState) -> Unit>()
    private val uploadAvailable: Boolean get() = uploads?.available == true
    /** Current local profile. Session journals intentionally omit its display label. */
    private var localProfile: PreparationSnapshot? = null
    private var profile: PreparationSnapshot? = null
    private var connected = false
    private var connecting = false
    private var generation = 0L
    private var initialized = false
    private var browsingHome = false
    private var taskPage: CollectionPage? = null
    private var taskError: String? = null
    private var query: Inspection? = null
    private var operation = 0L
    private var lastIdle: HealthRecordObservation? = null
    private var downloader: RealSessionDownload? = null
    private data class FinalizingDownload(
        val download: RealSessionDownload,
        val completed: RealSessionDownload.Completed,
        val record: HealthMessage.ListItem,
    )
    private var finalizingDownload: FinalizingDownload? = null
    private var backupDownloader: RealSessionDownload? = null
    private var backupObservation: HealthRecordObservation? = null
    private var backupRecord: HealthMessage.ListItem? = null
    private val preservationOwnerId = UUID.randomUUID().toString()
    private var unknownBackupAttempt: String? = null
    private data class UnknownPreservation(val record: HealthMessage.ListItem, val backupId: String,
        val rawSha256: String, val generation: Long, val savedAtMs: Long)
    private var unknownPreservation: UnknownPreservation? = null
    private var unknownReadGeneration: Long? = null
    private var downloadTimeout = 0L
    private var reconnectCount = 0
    private var closed = false
    private var saving = false
    private var selectedActivity: SessionActivity? = null
    private var requestedActivity: SessionActivity? = null
    private var stopEvidenceGeneration: Long? = null
    private var endStartAttemptReason: String? = null
    private var unknownArchiveReason: String? = null
    private var abortWaitOperation: Long? = null
    private var abortStopPollCount = 0
    private var abortHighWater: HealthRecordObservation? = null
    private var abortPrecheck: HealthRecordObservation? = null
    private var abortCandidateInvalidated = false
    private var devicePreparationRequired = false
    private var readinessWait: Long? = null
    private data class TimeRound(val id: Long, val before: HealthRecordObservation,
        val requestedAtMs: Long, val requestedElapsedMs: Long, var awaitingReply: Boolean = false,
        var evidence: PhoneClockSyncEvidence? = null)
    private var timeRound: TimeRound? = null
    private var startingClockEvidence: PhoneClockSyncEvidence? = null
    private var boundClockEvidence: PhoneClockSyncEvidence? = null
    private data class Inspection(val id: Long, val attempt: Int = 1,
        val errorBaseline: HealthRecordObservation? = null, var status: SensorPacket.Health? = null,
        val records: MutableList<HealthMessage.ListItem> = mutableListOf(),
        var batteryRequestedAtMs: Long? = null, var observation: HealthRecordObservation? = null,
        val startedAtElapsedMs: Long)

    @Volatile override var state = CollectionFlowState(isSimulation = false, uploadAvailable = uploadAvailable,
        connected = false, connecting = true, busy = true, hasProfile = true)
        private set

    private val coordinator: FreeLivingCaptureCoordinator = FreeLivingCaptureCoordinator(store,
        object : HealthControlPort by port {
            override fun start(): Boolean {
                if (syncClockBeforeStart || unknownPreservation != null) requireFreshClockEvidence(requireNotNull(boundClockEvidence))
                unknownPreservation?.let { proof ->
                    require(proof.generation == generation &&
                        backups.verifyUnknown(requireNotNull(profile?.ring).address, proof.record, proof.backupId).sha256 == proof.rawSha256)
                }
                return port.start()
            }
        }, clock,
        schedule = { delay, action -> scheduler.schedule(delay) { if (!closed) safely(action = action) } }) { control ->
        control.observation?.let(::recordDiagnostic)
        when (control.phase) {
            CaptureControlPhase.IDLE -> if (control.observation != null) {
                lastIdle = control.observation.takeIf { !it.status.collecting && it.status.errorCode == 0 }
                browsingHome = true; taskPage = null; taskError = null
                if (lastIdle == null) taskError = "本次尝试已结束，请重新连接戒指后再试。"
                publish(CollectionPage.HOME)
            }
            CaptureControlPhase.CHECKING -> publish(if (store.readPending() == null) CollectionPage.STARTING else CollectionPage.RECOVERY)
            CaptureControlPhase.STARTING -> {
                startingClockEvidence?.let { evidence ->
                    val session = requireNotNull(control.session)
                    requireFreshClockEvidence(evidence)
                    saveClockEvidence(evidence, session.sessionId)
                    boundClockEvidence = evidence
                    startingClockEvidence = null
                }
                publish(CollectionPage.STARTING)
            }
            CaptureControlPhase.COLLECTING -> publish(CollectionPage.COLLECTING)
            CaptureControlPhase.STOPPING -> publish(CollectionPage.STOPPING)
            CaptureControlPhase.AWAITING_REFERENCE -> {
                if (control.observation != null) stopEvidenceGeneration = generation
                val current = store.readPending()
                if (current?.completionPolicy == null) publish(CollectionPage.FINISH)
                else if (current.reference == null) publish(CollectionPage.REFERENCE)
                else publish(CollectionPage.RECOVERY, "步数已保存，正在核对戒指数据")
            }
            CaptureControlPhase.NEEDS_REVIEW -> publish(CollectionPage.RECOVERY, when (control.issue) {
                CaptureControlIssue.EXISTING_RECORDS -> "戒指记录已变化，请重新检查"
                CaptureControlIssue.RECORD_CHANGED, CaptureControlIssue.RECORD_ORIGIN_UNCERTAIN,
                CaptureControlIssue.RECOVERY_REQUIRES_REVIEW -> "本次记录需要核对，请保留戒指数据"
                CaptureControlIssue.CONNECTION_LOST, CaptureControlIssue.NOT_CONNECTED -> "连接中断，请将戒指放在手机附近"
                CaptureControlIssue.START_ARCHIVE_BLOCKED -> "请先完成本次记录的数据保全"
                CaptureControlIssue.DEVICE_ERROR -> "戒指返回异常，请重新连接后再试"
                else -> "暂未收到确认，请重新检查戒指"
            })
            CaptureControlPhase.STORAGE_ERROR -> publish(CollectionPage.ERROR, "暂时无法保存，请检查手机空间后重试")
        }
        control.timeoutOperationId?.let { id ->
            val connection = generation
            scheduler.schedule(FreeLivingCaptureCoordinator.QUERY_TIMEOUT_MS) {
                if (!closed && connection == generation) safely { coordinatorTimeout(id) }
            }
        }
    }

    // Kept outside the initializer to avoid invoking an incompletely constructed property.
    private fun coordinatorTimeout(id: Long) { coordinator.onTimeout(id) }

    fun initialize() = safely {
        if (initialized) return@safely
        initialized = true
        require(directory.isDirectory)
        store.listSessions().filter { it.isDiscarded }.forEach {
            runCatching { store.cleanupDiscardedSession(it.sessionId) }.onFailure { error -> reportError(error as? Exception ?: Exception(error)) }
        }
        store.listSessions().filter { !it.isDiscarded && isRealLocal(it) }.forEach { session ->
            runCatching { RealSessionDownload.cleanupCommittedTemporary(directory, session) }
                .onFailure { error -> reportError(error as? Exception ?: Exception(error)) }
        }
        localProfile = requireNotNull(preparation.read()) { "请先完成准备信息" }
        profile = store.readPending()?.preparation ?: localProfile
        require(profile?.ring != null && profile?.placement != null) { "请先选择戒指与佩戴位置" }
        coordinator.restore()
        val current = store.read()
        browsingHome = current == null || !current.isPending
        enqueueSavedRecords()
        connect()
    }

    override fun observe(observer: (CollectionFlowState) -> Unit): AutoCloseable {
        observers += observer
        notifyObserver { if (observer in observers) observer(state) }
        return AutoCloseable { observers -= observer }
    }

    fun onConnected(connection: Long) = safely {
        if (closed || connection != generation || connected) return@safely
        connected = true; connecting = false; reconnectCount = 0
        query = null; readinessWait = null
        coordinator.onConnected(requireNotNull(profile?.ring).address, connection)
        endStartAttemptReason?.let { reason ->
            endStartAttemptReason = null
            coordinator.archiveUnconfirmedStart(reason)
            return@safely
        }
        val pending = store.readPending()
        when {
            pending?.startAbort != null -> inspect()
            unknownArchiveReason != null -> inspect()
            pending == null -> inspect()
            pending.phase == FreeLivingSessionPhase.AWAITING_REFERENCE -> {
                if (pending.completionPolicy == null) publish(CollectionPage.FINISH)
                else if (pending.reference != null) inspect() else publish(CollectionPage.REFERENCE)
            }
            else -> coordinator.reconcile()
        }
    }

    fun onDisconnected(connection: Long, message: String) = safely {
        if (closed || connection != generation) return@safely
        connected = false; connecting = false; query = null; readinessWait = null; lastIdle = null
        abortWaitOperation = null; abortHighWater = null; abortPrecheck = null
        unknownPreservation = null
        timeRound = null; startingClockEvidence = null; boundClockEvidence = null
        endStartAttemptReason = null
        stopEvidenceGeneration = null
        closeDownload()
        coordinator.onDisconnected(connection)
        publish(pendingReferencePage() ?: CollectionPage.RECOVERY, message)
        if (store.readPending() != null && reconnectCount < 3) {
            val failedGeneration = generation
            reconnectCount++
            scheduler.schedule(3000) {
                if (!closed && generation == failedGeneration && !connected && !connecting) safely { connect() }
            }
        }
    }

    fun onHealth(connection: Long, packet: SensorPacket.Health) = safely {
        if (closed || connection != generation || !connected) return@safely
        if (query != null && (abortPrecheck != null || store.readPending()?.startAbort?.let { it.stoppedObservation == null } == true)) {
            val previous = abortHighWater ?: abortPrecheck ?: requireNotNull(store.readPending()?.startAbort).collectingObservation
            try {
                abortHighWater = rememberAbortProgress(previous, packet, requireCollecting = abortPrecheck != null)
            } catch (error: Exception) {
                if (abortPrecheck != null) abortCandidateInvalidated = true
                throw error
            }
        }
        if (abortWaitOperation != null) {
            val before = requireNotNull(abortHighWater)
            when (val message = packet.message) {
                is HealthMessage.Status -> {
                    require(message.errorCode == 0 && message.sessionId == before.status.sessionId &&
                        message.bytes >= before.status.bytes && message.records >= before.status.records &&
                        (before.status.collecting || !message.collecting)) { "停止状态有变化，请保留戒指并重新检查" }
                    abortHighWater = before.copy(status = message, statusReceivedAtMs = packet.receivedEpochMs)
                }
                is HealthMessage.ListItem -> {
                    val previous = before.records.singleOrNull { it.sessionId == message.sessionId }
                    require(previous != null && FreeLivingSessionStore.sameDeviceRecord(previous, message) &&
                        message.bytes >= previous.bytes && message.records >= previous.records) { "停止记录有变化，请保留戒指并重新检查" }
                    abortHighWater = before.copy(records = before.records.map { if (it.sessionId == message.sessionId) message else it })
                }
                else -> Unit
            }
            return@safely
        }
        val backup = backupDownloader
        if (backup != null && query == null) {
            when (val message = packet.message) {
                is HealthMessage.DataChunk -> { backup.append(message); scheduleBackupTimeout() }
                is HealthMessage.ReadEnd -> {
                    if (backup.checkpoint(message)) {
                        val completed = backup.finish(message)
                        val record = requireNotNull(backupRecord)
                        val attempt = unknownBackupAttempt
                        val savedAtMs = clock.nowEpochMs()
                        backups.accept(requireNotNull(profile?.ring).address, record, completed, savedAtMs, attempt)
                        if (attempt != null) {
                            unknownPreservation = UnknownPreservation(record, attempt, completed.file.sha256, generation, savedAtMs)
                            unknownReadGeneration = generation
                        }
                        backupDownloader = null; backupRecord = null; downloadTimeout++
                        unknownBackupAttempt = null
                        backup.close()
                        runCatching { backup.releaseTemporary() }.onFailure { reportError(it as? Exception ?: Exception(it)) }
                        // DATA has no record ID. A fresh GATT generation separates the next
                        // record's bytes from any late notifications belonging to this backup.
                        if (attempt == null) connect() else {
                            // The last unknown-clock original is proved only on this connection.
                            // No further READ is allowed here; a future session download reconnects.
                            backupObservation = null
                            inspect()
                        }
                    } else {
                        // Each window rechecks the same frozen record before resuming READ.
                        inspect()
                    }
                }
                is HealthMessage.Status -> require(!message.collecting &&
                    message.copy(errorCode = 0) == backupObservation?.status?.copy(errorCode = 0)) { "戒指记录发生变化，请重新检查" }
                else -> Unit
            }
            return@safely
        }
        val download = downloader
        if (download != null) {
            when (val message = packet.message) {
                is HealthMessage.DataChunk -> { download.append(message); scheduleDownloadTimeout() }
                is HealthMessage.ReadEnd -> {
                    if (download.isDelayedCompletedPrefix(message)) {
                        scheduleDownloadTimeout()
                        return@safely
                    }
                    if (download.checkpoint(message)) {
                        val completed = download.finish(message)
                        downloader = null; downloadTimeout++
                        download.close()
                        finalizingDownload = FinalizingDownload(download, completed,
                            requireNotNull(store.readPending()?.deviceRecordEvidence).record)
                        inspect()
                    } else requestWindow(download)
                }
                else -> Unit
            }
            return@safely
        }
        val round = query
        if (round == null) {
            coordinator.state.unconfirmedStartStopCandidate?.takeIf { store.readPending()?.startAbort == null }?.let { candidate ->
                val previous = abortHighWater ?: candidate
                val changed = when (val message = packet.message) {
                    is HealthMessage.Status -> message.errorCode != 0 || !message.collecting ||
                        message.sessionId != previous.status.sessionId || message.bytes < previous.status.bytes ||
                        message.records < previous.status.records
                    is HealthMessage.ListItem -> previous.records.singleOrNull { it.sessionId == message.sessionId }?.let {
                        !FreeLivingSessionStore.sameDeviceRecord(it, message) || message.bytes < it.bytes || message.records < it.records ||
                            (it.sessionId != previous.status.sessionId && it != message)
                    } ?: true
                    else -> false
                }
                if (changed) {
                    abortCandidateInvalidated = true
                    publish(CollectionPage.RECOVERY, "戒指状态已变化，请保留数据并重新检查")
                    return@safely
                }
                abortHighWater = when (val message = packet.message) {
                    is HealthMessage.Status -> previous.copy(status = message, statusReceivedAtMs = packet.receivedEpochMs)
                    is HealthMessage.ListItem -> previous.copy(records = previous.records.map { if (it.sessionId == message.sessionId) message else it })
                    else -> previous
                }
            }
            val idle = lastIdle
            if (idle != null && store.readPending() == null && when (val message = packet.message) {
                    is HealthMessage.Status -> message != idle.status
                    is HealthMessage.ListItem -> message !in idle.records
                    else -> false
                }) {
                lastIdle = null
                publish(CollectionPage.RECOVERY, "戒指状态已变化，请重新检查")
                return@safely
            }
            coordinator.onHealth(connection, packet)
            // A restored STOP may already have a preserved reference; inspect its final record.
            if (query == null && coordinator.state.timeoutOperationId == null &&
                coordinator.state.phase == CaptureControlPhase.AWAITING_REFERENCE && store.readPending()?.let {
                    it.reference != null && it.completionPolicy != null
                } == true) inspect()
            return@safely
        }
        if (round.observation != null) {
            val consistent = when (val message = packet.message) {
                is HealthMessage.Status -> message == round.status?.message && packet.statusErrorReason == round.status?.statusErrorReason
                is HealthMessage.ListItem -> message in round.records
                is HealthMessage.ListEnd -> message.count == round.records.size
                else -> false
            }
            check(consistent) { "戒指状态发生变化，请重新检查" }
            return@safely
        }
        when (val message = packet.message) {
            is HealthMessage.Status -> {
                require(message.sessionId in 0..65535 && message.bytes in 0..0xFFFF_FFFFL &&
                    message.records in 0..0xFFFF_FFFFL && packet.receivedEpochMs > 0) { "戒指状态需要重新核对" }
                if (round.status != null) {
                    require(round.status?.message == message && round.status?.statusErrorReason == packet.statusErrorReason) {
                        "戒指状态发生变化，请重新检查"
                    }
                    return@safely
                }
                round.status = packet
                check(port.queryRecords()) { "查询未完成，请重新连接" }
            }
            is HealthMessage.ListItem -> {
                if (round.status == null) return@safely
                require(message.sessionId in 0..65535 && message.bytes in 0..0xFFFF_FFFFL &&
                    message.records in 0..0xFFFF_FFFFL && message.uptimeMs in 0..0xFFFF_FFFFL && message.unixMs >= 0)
                require(round.records.size < 255 && round.records.none { it.sessionId == message.sessionId })
                round.records += message
            }
            is HealthMessage.ListEnd -> {
                val statusPacket = round.status ?: return@safely
                require(message.count == round.records.size) { "戒指记录列表不完整，请重试" }
                query = null
                val observation = HealthRecordObservation(requireNotNull(profile?.ring).address, generation,
                    statusPacket.message as HealthMessage.Status, statusPacket.receivedEpochMs, round.records.toList())
                recordDiagnostic(observation)
                val current = store.readPending()
                if (abortPrecheck != null) {
                    beginUnconfirmedStartAbort(observation)
                } else if (current?.startAbort != null) {
                    continueUnconfirmedStartAbort(observation)
                } else if (unknownArchiveReason != null) {
                    continueUnknownArchive(observation)
                } else if (current == null) {
                    if (backupObservation != null) {
                        continueExistingBackup(observation)
                    } else if (ChargingStartCompatibility.isCandidate(observation.status, statusPacket.statusErrorReason)) {
                        query = round
                        round.observation = observation
                        round.batteryRequestedAtMs = clock.nowEpochMs()
                        check(port.queryBattery()) { "未能读取充电状态，请重新连接" }
                    } else finishReadinessInspection(round, observation)
                } else if (current.phase == FreeLivingSessionPhase.AWAITING_REFERENCE && current.reference != null && current.completionPolicy != null) {
                    finalizingDownload?.let { reconcileCompletedDownload(current, observation, it) }
                        ?: beginDownload(current, observation)
                }
            }
            else -> Unit
        }
    }

    fun onBattery(connection: Long, packet: SensorPacket.Battery) = safely {
        if (closed || connection != generation || !connected) return@safely
        val round = query
        val observed = round?.observation
        if (observed == null) {
            coordinator.onBattery(connection, packet)
            if (lastIdle != null && packet.chargeStatus != 0 && store.readPending() == null && downloader == null && backupObservation == null) {
                lastIdle = null
                publish(CollectionPage.RECOVERY, "请将戒指取出充电盒后重试")
            }
            return@safely
        }
        query = null
        val recovery = ChargingStartCompatibility.evidence(requireNotNull(round.status), packet, connection,
            requireNotNull(round.batteryRequestedAtMs), clock.nowEpochMs())
        if (recovery == null || clock.nowElapsedMs() - round.startedAtElapsedMs !in 0..ChargingStartCompatibility.FRESHNESS_MS) {
            timeRound = null
            lastIdle = null
            publish(CollectionPage.RECOVERY, if (packet.chargeStatus != null && packet.chargeStatus != 0)
                "请将戒指取出充电盒后重试" else "充电状态尚未确认，请重新检查戒指")
        } else finishReadinessInspection(round, observed, recovery)
    }

    override fun register(participantId: String, placement: RingPlacement) = Unit // Preparation owns registration.

    override fun selectActivity(activity: SessionActivity) = safely {
        require(activity in setOf(SessionActivity.WALKING, SessionActivity.RUNNING))
        if (!state.canStart || state.busy) return@safely
        selectedActivity = activity
        publish(state.page, state.error)
    }

    override fun start() = safely {
        if (!state.canStart || connecting || query != null || readinessWait != null || timeRound != null || downloader != null || backupObservation != null || saving) return@safely
        requestedActivity = requireNotNull(selectedActivity) { "请选择本次活动" }
        selectedActivity = null
        if (syncClockBeforeStart || unknownPreservation != null) {
            val before = requireNotNull(lastIdle)
            require(!before.status.collecting && store.readPending() == null)
            val round = TimeRound(++operation, before, clock.nowEpochMs(), clock.nowElapsedMs())
            timeRound = round
            browsingHome = false; lastIdle = null
            inspect(errorBaseline = before)
            return@safely
        }
        beginCapture()
    }

    fun onTime(connection: Long, packet: SensorPacket.TimeStatus) = safely {
        val round = timeRound ?: return@safely
        if (!connected || generation != connection || round.before.connectionGeneration != connection ||
            !round.awaitingReply || round.evidence != null) return@safely
        val evidence = PhoneClockSync.accept(round.before.address, connection, round.requestedAtMs,
            round.requestedElapsedMs, clock.nowElapsedMs(), packet)
        saveClockEvidence(evidence, null)
        round.evidence = evidence
        // Re-read the complete idle snapshot: a clock write must not silently relabel old records.
        inspect(errorBaseline = round.before)
    }

    private fun beginCapture() {
        val before = requireNotNull(lastIdle) { "请重新检查戒指后开始" }
        require(isUsableStartBaseline(before)) { "戒指记录尚未完整返回，请重新检查" }
        val unknown = unknownPreservation?.takeIf { it.generation == generation }
        val reviewed = authorizedExisting()?.takeIf {
            it.ringAddress.equals(before.address, ignoreCase = true) && it.status == before.status &&
                it.records.size == before.records.size && it.records.toSet() == before.records.toSet()
        }
        val unknownEvidence = if (unknown != null && unknown.record in before.records &&
            hasPreserved(before.address, listOf(unknown.record))) {
            val timing = requireNotNull(startingClockEvidence) { "请重新检查戒指时间后开始" }
            UnknownTimeStartEvidence(unknown.record, unknown.backupId, unknown.rawSha256,
                preservationOwnerId, generation, unknown.savedAtMs, timing)
        } else reviewed?.unknownTimeStartEvidence
        // The exact idle snapshot authorizes this one connection. Existing Flash records are a
        // baseline for identifying the new record; their presence is not a reason to lock the ring.
        val allowed = ExistingRecordAuthorization(before.address, before.status, before.records,
            generation, unknownEvidence)
        browsingHome = false; lastIdle = null
        coordinator.refresh()
        coordinator.requestStart(requireNotNull(profile), allowed, requireNotNull(requestedActivity))
    }

    private fun beginClockSync(before: HealthRecordObservation) {
        val round = TimeRound(++operation, before, clock.nowEpochMs(), clock.nowElapsedMs())
        timeRound = round
        publish(CollectionPage.STARTING)
        check(port.syncTime(round.requestedAtMs)) { "时间同步未完成，请重新连接后再试" }
        round.awaitingReply = true
        scheduler.schedule(PhoneClockSync.TIMEOUT_MS) {
            if (!closed && timeRound?.id == round.id && timeRound?.evidence == null) safely {
                timeRound = null; lastIdle = null
                publish(CollectionPage.RECOVERY, "时间同步超时，请重新连接后再试")
            }
        }
    }

    private fun requireFreshClockEvidence(evidence: PhoneClockSyncEvidence) {
        val elapsed = clock.nowElapsedMs() - evidence.receivedElapsedMs
        require(elapsed in 0..PhoneClockSync.MAX_START_AGE_MS &&
            kotlin.math.abs((clock.nowEpochMs() - evidence.receivedAtMs) - elapsed) <= PhoneClockSync.MAX_WALL_CLOCK_SKEW_MS) {
            "时间同步已过期，请重新连接后再试"
        }
    }

    override fun stop() = safely {
        if (state.canStopUnconfirmedStart) {
            val observation = requireNotNull(coordinator.state.unconfirmedStartStopCandidate)
            require(observation.connectionGeneration == generation && connected)
            abortPrecheck = abortHighWater ?: observation
            browsingHome = false
            inspect()
            return@safely
        }
        if (!state.canStop) return@safely
        browsingHome = false
        coordinator.requestStop()
    }

    override fun enterFinish() = safely {
        val current = requireNotNull(store.readPending())
        require(current.stopConfirmedAtMs != null && current.localData == null && !saving && downloader == null)
        browsingHome = false
        publish(CollectionPage.FINISH)
    }

    override fun chooseFinish(uploadNow: Boolean) = safely(CollectionPage.FINISH) {
        if (saving || downloader != null || query != null) return@safely
        val current = requireNotNull(store.readPending())
        store.setCompletionPolicy(current.sessionId,
            if (uploadNow && uploadAvailable) CompletionPolicy.SAVE_UPLOAD else CompletionPolicy.SAVE_LATER)
        browsingHome = false
        if (current.reference == null) publish(CollectionPage.REFERENCE)
        else if (connected) inspect() else publish(CollectionPage.RECOVERY, "读数已保存，请重新连接以下载数据")
    }

    override fun finalizeSession(uploadNow: Boolean, stepsText: String, status: String, reason: String) =
        safely(CollectionPage.FINISH) {
            if (saving || downloader != null || query != null) return@safely
            browsingHome = false
            val current = requireNotNull(store.readPending())
            val policy = current.completionPolicy ?: if (uploadNow && uploadAvailable) {
                CompletionPolicy.SAVE_UPLOAD
            } else CompletionPolicy.SAVE_LATER
            val reference = sessionReferenceFromInput(stepsText, status, reason, clock.nowEpochMs())
            saving = true
            val saved = try {
                publish(CollectionPage.SAVING)
                store.finalizeStoppedSession(current.sessionId, policy, reference).also {
                    val persisted = requireNotNull(store.read(current.sessionId))
                    check(persisted.completionPolicy == it.completionPolicy && persisted.reference == it.reference)
                }
            } finally { saving = false }
            if (connected) inspect()
            else publish(CollectionPage.RECOVERY, "记录已保存，请重新连接以下载数据")
        }

    override fun discardSession() = safely(CollectionPage.FINISH) {
        if (saving) return@safely
        val current = requireNotNull(store.readPending())
        require(current.stopConfirmedAtMs != null)
        val atMs = clock.nowEpochMs()
        store.discardStoppedSession(current.sessionId, atMs, preservationOwnerId, generation)
        closeDownload()
        query = null; readinessWait = null; selectedActivity = null; requestedActivity = null
        val cleaned = runCatching { store.cleanupDiscardedSession(current.sessionId) }
        cleaned.exceptionOrNull()?.let { reportError(it as? Exception ?: Exception(it)) }
        uploads?.discard(current.sessionId, atMs, preservationOwnerId, generation)
        coordinator.refresh()
        browsingHome = true; taskPage = null; taskError = null
        connect() // Retire any late, untagged DATA from the cancelled download before another task.
    }

    override fun enterReference() = safely {
        val current = requireNotNull(store.readPending())
        require(current.phase in setOf(FreeLivingSessionPhase.STOP_REQUESTED, FreeLivingSessionPhase.AWAITING_REFERENCE))
        if (current.reference != null) { retry(); return@safely }
        browsingHome = false
        publish(if (current.stopConfirmedAtMs != null && current.completionPolicy == null) CollectionPage.FINISH else CollectionPage.REFERENCE)
    }

    override fun saveReference(stepsText: String, status: String, reason: String) = safely(CollectionPage.REFERENCE) {
        if (saving || downloader != null) return@safely
        browsingHome = false
        val current = requireNotNull(store.readPending())
        if (current.reference != null) { retry(); return@safely }
        require(current.stopConfirmedAtMs == null || current.completionPolicy != null) { "请选择保存方式" }
        val reference = sessionReferenceFromInput(stepsText, status, reason, clock.nowEpochMs())
        saving = true
        val saved = try {
            publish(CollectionPage.SAVING)
            store.saveReference(current.sessionId, reference).also {
                check(store.read(current.sessionId)?.reference == it.reference)
            }
        } finally { saving = false }
        if (saved.stopConfirmedAtMs == null) publish(CollectionPage.RECOVERY, "读数已保存，请重新检查戒指")
        else if (connected) inspect() else publish(CollectionPage.RECOVERY, "读数已保存，请重新连接以下载数据")
    }

    override fun retry() = safely {
        browsingHome = false
        if (saving || downloader != null || backupObservation != null || query != null || readinessWait != null || abortWaitOperation != null ||
            coordinator.state.timeoutOperationId != null || coordinator.state.settling) {
            publish(taskPage ?: CollectionPage.RECOVERY, taskError); return@safely
        }
        val pending = store.readPending()
        when {
            pending?.startAbort != null -> {
                val abort = requireNotNull(pending.startAbort)
                if (connected && abort.stoppedObservation == null && abort.ownerId == preservationOwnerId &&
                    abort.collectingObservation.connectionGeneration == generation) {
                    abortStopPollCount = 0
                    inspect() // Keep the original issuing connection; retry only the read-only checks.
                } else connect()
            }
            pending == null -> {
                if (store.read()?.localData != null && lastIdle != null) publish(CollectionPage.COMPLETE)
                else connect()
            }
            pending.phase == FreeLivingSessionPhase.AWAITING_REFERENCE && pending.completionPolicy == null -> publish(CollectionPage.FINISH)
            pending.phase == FreeLivingSessionPhase.AWAITING_REFERENCE && pending.reference == null -> publish(CollectionPage.REFERENCE)
            coordinator.state.phase == CaptureControlPhase.COLLECTING && connected -> publish(CollectionPage.COLLECTING)
            else -> connect() // A fresh GATT channel separates untagged late replies from retry queries.
        }
    }

    override fun endStartAttempt(reason: String) = safely {
        if (!state.canEndStartAttempt || saving || downloader != null || query != null ||
            coordinator.state.timeoutOperationId != null || coordinator.state.settling) return@safely
        val value = reason.trim()
        require(value.length in 1..200) { "请填写简短原因（200 字以内）" }
        browsingHome = false; lastIdle = null
        // A fresh GATT connection excludes late replies from the failed START round.
        if (store.readPending()?.startBaseline?.records?.any { it.unixMs == 0L } == true) unknownArchiveReason = value
        else endStartAttemptReason = value
        connect()
    }

    override fun retryUpload(sessionId: String) {
        if (closed) return
        val upload = uploads?.takeIf { it.available } ?: return
        runCatching {
            val session = store.read(sessionId) ?: return@runCatching
            if (session.preparation.participantId == localProfile?.participantId &&
                !session.isDiscarded && isRealLocal(session) && session.transfer.status != SessionTransferStatus.COMPLETE &&
                !upload.isInFlight(sessionId)) {
                store.allowUpload(sessionId)
                upload.enqueue(sessionId, retry = true)
            }
        }.onFailure { reportError(it as? Exception ?: Exception(it)) }
        refreshUploads()
    }

    override fun reviseReference(sessionId: String, stepsText: String, status: String, reason: String) = safely {
        val session = requireNotNull(store.read(sessionId)) { "没有找到这条记录" }
        require(session.preparation.participantId == localProfile?.participantId && isRealLocal(session)) {
            "这条记录不属于当前身份"
        }
        require(session.completionPolicy in setOf(CompletionPolicy.SAVE_LATER, CompletionPolicy.SAVE_UPLOAD) &&
            session.transfer.status == SessionTransferStatus.PENDING && session.transfer.attempts == 0 &&
            uploads?.isInFlight(sessionId) != true) { "本段已确认上传，原读数保持不变" }
        val reference = sessionReferenceFromInput(stepsText, status, reason, clock.nowEpochMs())
        FreeLivingSessionPackage(directory, store).reviseUnpublishedReference(sessionId, reference)
        publish(CollectionPage.HOME)
    }

    /** Refresh transfer evidence without changing navigation, BLE queries or capture actions. */
    fun refreshUploads() {
        if (closed || !initialized) return
        runCatching {
            state = state.copy(session = visibleCurrentSession(), records = recordSummaries())
            observers.forEach { observer -> notifyObserver { if (observer in observers) observer(state) } }
        }.onFailure { reportError(it as? Exception ?: Exception(it)) }
    }

    private fun enqueueSavedRecords() {
        val upload = uploads?.takeIf { it.available } ?: return
        runCatching {
            store.listSessions().filter { it.uploadAllowed && isRealLocal(it) && it.transfer.status in
                setOf(SessionTransferStatus.PENDING, SessionTransferStatus.TRANSFERRING) }.forEach { session ->
                runCatching {
                    if (!upload.isInFlight(session.sessionId)) upload.enqueue(session.sessionId)
                }.onFailure { reportError(it as? Exception ?: Exception(it)) }
            }
        }.onFailure { reportError(it as? Exception ?: Exception(it)) }
    }

    private fun isRealLocal(session: FreeLivingSession) = session.localData?.files?.let { files ->
        files.isNotEmpty() && files.all { !it.simulated }
    } == true

    /** A pending capture remains recoverable; completed history belongs to the selected identity. */
    private fun visibleCurrentSession() = store.read()?.takeUnless {
        it.startAttemptArchive != null || it.isDiscarded || it.startAbort?.completedAtMs != null
    }?.takeIf { it.isPending || it.preparation.participantId == localProfile?.participantId }

    private fun recordSummaries() = store.listSessions().filter {
        it.preparation.participantId == localProfile?.participantId && !it.isDiscarded &&
            it.startAttemptArchive == null && it.startAbort == null && (it.localData == null || isRealLocal(it))
    }
        .map { FlowRecordSummary(it.sessionId, it.reference?.steps, it.reference?.status?.wireValue,
            it.transfer.status.wireValue, isRealLocal(it), uploads?.isInFlight(it.sessionId) == true,
            uploads?.needsLocalReview(it.sessionId) == true, activity = it.activity,
            uploadDeferred = it.completionPolicy == CompletionPolicy.SAVE_LATER,
            startedAtMs = it.startedAtMs,
            timeZoneId = it.timeZoneId,
            referenceEditable = isRealLocal(it) && it.reference != null &&
                it.completionPolicy in setOf(CompletionPolicy.SAVE_LATER, CompletionPolicy.SAVE_UPLOAD) &&
                it.transfer.status == SessionTransferStatus.PENDING && it.transfer.attempts == 0 &&
                uploads?.isInFlight(it.sessionId) != true,
            referenceReason = it.reference?.reason) }

    override fun home() = safely {
        browsingHome = !devicePreparationRequired
        publish(if (devicePreparationRequired) CollectionPage.RECOVERY else CollectionPage.HOME, taskError)
    }
    override fun setFault(fault: FlowTestFault) = Unit
    override fun disconnect() = Unit // A page cannot tear down the collection connection.
    override fun reconnect() = safely { browsingHome = false; reconnectCount = 0; connect() }

    private fun connect() {
        unknownPreservation = null
        if (connecting) return
        abortPrecheck = null; abortWaitOperation = null; abortHighWater = null; abortCandidateInvalidated = false
        devicePreparationRequired = false
        timeRound = null; startingClockEvidence = null; boundClockEvidence = null
        closeDownload(); query = null; readinessWait = null; lastIdle = null
        stopEvidenceGeneration = null
        if (connected) coordinator.onDisconnected(generation)
        connected = false; connecting = true
        val id = ++generation
        port.disconnect()
        publish(pendingReferencePage() ?: if (store.readPending() == null) CollectionPage.HOME else CollectionPage.RECOVERY,
            "正在连接戒指")
        check(port.connect(requireNotNull(profile?.ring), id)) { "连接未成功，请检查手机蓝牙" }
        scheduler.schedule(30_000) {
            if (!closed && generation == id && connecting) safely {
                port.disconnect()
                onDisconnected(id, "连接超时，请唤醒戒指后重试")
            }
        }
    }

    private fun inspect(attempt: Int = 1, errorBaseline: HealthRecordObservation? = null) {
        if (query != null || readinessWait != null || downloader != null || !connected) return
        val id = ++operation
        query = Inspection(id, attempt, errorBaseline, startedAtElapsedMs = clock.nowElapsedMs())
        if (abortPrecheck != null || store.readPending()?.startAbort != null) publish(CollectionPage.STOPPING)
        else if (store.readPending()?.reference != null) publish(CollectionPage.DOWNLOADING)
        else publish(if (timeRound != null) CollectionPage.STARTING else CollectionPage.HOME)
        check(port.queryStatus()) { "查询未完成，请重新连接" }
        scheduler.schedule(30_000) {
            if (!closed && query?.id == id) safely {
                query = null; lastIdle = null
                port.disconnect()
                onDisconnected(generation, "查询超时，请重新连接戒指")
            }
        }
    }

    private fun finishReadinessInspection(round: Inspection, observed: HealthRecordObservation,
        chargingRecovery: ChargingRecoveryEvidence? = null) {
        lastIdle = null
        val baseline = round.errorBaseline
        val unchanged = baseline == null || (observed.address == baseline.address &&
            observed.connectionGeneration == baseline.connectionGeneration &&
            observed.status.copy(errorCode = 0) == baseline.status.copy(errorCode = 0) &&
            observed.records.size == baseline.records.size && observed.records.toSet() == baseline.records.toSet())
        if (observed.status.collecting || !unchanged) {
            timeRound = null
            publish(CollectionPage.RECOVERY, "戒指状态发生变化，请重新连接后检查")
            return
        }
        if (observed.status.errorCode == 0 || chargingRecovery != null) {
            if (!isUsableStartBaseline(observed)) {
                timeRound = null
                publish(CollectionPage.RECOVERY, "戒指记录尚未完整返回，请重新检查")
                return
            }
            val hasUnresolvedDiscard = observed.records.any {
                store.hasUnresolvedDiscardedRecord(observed.address, it)
            }
            val canBackUpBeforeStart = !hasUnresolvedDiscard &&
                observed.records.count { it.unixMs == 0L } <= 1 &&
                observed.records.all { it.uptimeMs > 0 && it.bytes > 0 && it.records > 0 }
            if (canBackUpBeforeStart && observed.records.isNotEmpty() &&
                !hasPreserved(observed.address, observed.records)) {
                val approved = authorizedExisting()
                if (approved?.ringAddress != observed.address || approved.status != observed.status ||
                    approved.records.toSet() != observed.records.toSet()) {
                    backupObservation = observed
                    continueExistingBackup(observed)
                    return
                }
            }
            lastIdle = observed
            timeRound?.let { timing ->
                val evidence = timing.evidence
                if (evidence == null) { beginClockSync(observed); return }
                requireFreshClockEvidence(evidence)
                timeRound = null
                startingClockEvidence = evidence
                beginCapture()
                return
            }
            taskPage = null; taskError = null
            publish(CollectionPage.HOME)
            return
        }
        if (round.attempt >= READINESS_CHECK_LIMIT) {
            timeRound = null
            publish(CollectionPage.RECOVERY, "请将戒指完全取出充电盒，靠近手机后重试")
            return
        }
        // A bounded read-only follow-up can observe an error clearing; it cannot explain its semantics.
        val token = ++operation
        val connection = generation
        readinessWait = token
        taskPage = null; taskError = null
        publish(CollectionPage.HOME)
        scheduler.schedule(READINESS_CHECK_INTERVAL_MS) {
            if (!closed && connected && generation == connection && readinessWait == token) safely {
                readinessWait = null
                inspect(round.attempt + 1, baseline ?: observed)
            }
        }
    }

    /** Mirrors the durable baseline contract before the UI can promise that START is available. */
    private fun isUsableStartBaseline(observed: HealthRecordObservation): Boolean {
        if (observed.status.collecting || observed.records.size > 255 ||
            observed.records.map { it.sessionId }.distinct().size != observed.records.size ||
            observed.records.any { it.sessionId !in 1..65535 || it.bytes !in 0..0xFFFF_FFFFL ||
                it.records !in 0..0xFFFF_FFFFL || it.uptimeMs !in 0..0xFFFF_FFFFL || it.unixMs < 0 }) return false
        if (observed.records.isEmpty()) return observed.status.bytes == 0L && observed.status.records == 0L
        return observed.records.any { it.sessionId == observed.status.sessionId &&
            it.bytes == observed.status.bytes && it.records == observed.status.records }
    }

    private fun continueExistingBackup(observed: HealthRecordObservation, onlyRecord: HealthMessage.ListItem? = null) {
        val original = requireNotNull(backupObservation)
        require(observed.connectionGeneration == original.connectionGeneration && observed.address == original.address &&
            !observed.status.collecting && observed.status.errorCode in setOf(0, -16) &&
            observed.status.copy(errorCode = 0) == original.status.copy(errorCode = 0) &&
            observed.records.size == original.records.size && observed.records.toSet() == original.records.toSet()) {
            "戒指记录发生变化，已保存的内容会保留，请重新检查"
        }
        if (backupDownloader == null) {
            val next = (onlyRecord?.let { listOf(it) } ?: observed.records).sortedBy { it.unixMs == 0L }
                .firstOrNull { !hasPreserved(observed.address, listOf(it)) }
            if (next == null) {
                backupObservation = null
                taskPage = null; taskError = null
                inspect() // Fresh STATUS and, when needed, BATTERY before exposing start again.
                return
            }
            backupRecord = next
            unknownBackupAttempt = if (next.unixMs == 0L) UUID.randomUUID().toString() else null
            backupDownloader = backups.open(observed.address, next, unknownBackupAttempt)
        }
        browsingHome = onlyRecord == null
        taskPage = null; taskError = null
        publish(if (onlyRecord == null) CollectionPage.HOME else CollectionPage.DOWNLOADING)
        check(port.read(requireNotNull(backupRecord).sessionId, requireNotNull(backupDownloader).nextOffset, 16_384)) {
            "保存中断，请重新连接戒指后继续"
        }
        scheduleBackupTimeout()
    }

    private fun continueUnknownArchive(observed: HealthRecordObservation) {
        val current = requireNotNull(store.readPending())
        val before = requireNotNull(current.startBaseline)
        require(current.phase == FreeLivingSessionPhase.START_REQUESTED && current.deviceRecordEvidence == null &&
            current.reference == null && current.localData == null && !observed.status.collecting &&
            observed.status.errorCode in setOf(0, -16) &&
            observed.status.copy(errorCode = 0) == before.status.copy(errorCode = 0) &&
            observed.records.toSet() == before.records.toSet() && observed.records.size == before.records.size) {
            "记录已有变化，请保留戒指数据并重新检查"
        }
        val canAttachUnknownProof = observed.records.count { it.unixMs == 0L } == 1 &&
            observed.records.all { it.uptimeMs > 0 && it.bytes > 0 && it.records > 0 }
        if (canAttachUnknownProof && !hasPreserved(observed.address, observed.records)) {
            if (backupObservation == null) backupObservation = observed
            continueExistingBackup(observed)
            return
        }
        val proof = unknownPreservation?.takeIf { canAttachUnknownProof }?.let {
            UnknownTimeRecordProof(it.record, it.backupId, it.rawSha256, preservationOwnerId, generation, it.savedAtMs)
        }
        store.archiveStartAttempt(current.sessionId, observed, clock.nowEpochMs(), requireNotNull(unknownArchiveReason), proof)
        unknownArchiveReason = null
        coordinator.refresh()
        lastIdle = null
        inspect() // Restore readiness only after the archive is durable and device checks complete.
    }

    private fun beginUnconfirmedStartAbort(observed: HealthRecordObservation) {
        val before = abortHighWater ?: requireNotNull(abortPrecheck)
        abortPrecheck = null
        val current = requireNotNull(store.readPending())
        val candidate = before.records.single { it.sessionId == before.status.sessionId }
        val actual = observed.records.singleOrNull { it.sessionId == candidate.sessionId }
        require(!abortCandidateInvalidated && observed.address == before.address && observed.connectionGeneration == before.connectionGeneration &&
            observed.status.collecting && observed.status.errorCode == 0 && observed.status.sessionId == candidate.sessionId &&
            observed.status.bytes >= before.status.bytes && observed.status.records >= before.status.records &&
            actual != null && FreeLivingSessionStore.sameDeviceRecord(candidate, actual) &&
            actual.bytes >= candidate.bytes && actual.records >= candidate.records &&
            actual.bytes >= observed.status.bytes && actual.records >= observed.status.records &&
            observed.records.filter { it != actual }.toSet() == before.records.filter { it != candidate }.toSet()) {
            abortCandidateInvalidated = true
            "戒指状态已变化，请保留数据并重新检查"
        }
        store.requestUnconfirmedStartAbort(current.sessionId, observed, clock.nowEpochMs(), preservationOwnerId)
        coordinator.refresh()
        abortStopPollCount = 0
        abortHighWater = observed
        require(port.stop()) { "停止请求未发送，请保留戒指并重新检查" }
        scheduleAbortStopCheck(FreeLivingCaptureCoordinator.STOP_FLASH_SETTLE_DELAY_MS)
    }

    /** Keep every observed counter across incomplete or rejected STOP query rounds. */
    private fun rememberAbortProgress(before: HealthRecordObservation, packet: SensorPacket.Health,
        requireCollecting: Boolean): HealthRecordObservation = when (val message = packet.message) {
        is HealthMessage.Status -> {
            require(message.errorCode == 0 && message.sessionId == before.status.sessionId &&
                message.bytes in before.status.bytes..0xFFFF_FFFFL && message.records in before.status.records..0xFFFF_FFFFL &&
                (before.status.collecting || !message.collecting) && (!requireCollecting || message.collecting)) {
                "停止状态有变化，请保留戒指并重新检查"
            }
            before.copy(status = message, statusReceivedAtMs = packet.receivedEpochMs)
        }
        is HealthMessage.ListItem -> {
            val previous = before.records.singleOrNull { it.sessionId == message.sessionId }
            require(previous != null && FreeLivingSessionStore.sameDeviceRecord(previous, message) &&
                message.bytes in previous.bytes..0xFFFF_FFFFL && message.records in previous.records..0xFFFF_FFFFL &&
                (message.sessionId == before.status.sessionId || message == previous)) {
                "停止记录有变化，请保留戒指并重新检查"
            }
            before.copy(records = before.records.map { if (it.sessionId == message.sessionId) message else it })
        }
        else -> before
    }

    private fun scheduleAbortStopCheck(delayMs: Long) {
        val token = ++operation
        val connection = generation
        abortWaitOperation = token
        browsingHome = false
        publish(CollectionPage.STOPPING)
        scheduler.schedule(delayMs) {
            if (!closed && connected && connection == generation && abortWaitOperation == token) safely {
                abortWaitOperation = null
                inspect()
            }
        }
    }

    private fun continueUnconfirmedStartAbort(observed: HealthRecordObservation) {
        val current = requireNotNull(store.readPending())
        val abort = requireNotNull(current.startAbort)
        val stopped = abort.stoppedObservation
        if (stopped == null) {
            require(abort.ownerId == preservationOwnerId &&
                observed.connectionGeneration == abort.collectingObservation.connectionGeneration) {
                "停止尚未确认，请将戒指靠近手机后继续恢复"
            }
            val previous = abortHighWater ?: abort.collectingObservation
            val before = abort.collectingObservation.records.single { it.sessionId == previous.status.sessionId }
            val actual = observed.records.singleOrNull { it.sessionId == before.sessionId }
            require(observed.status.errorCode == 0 && actual != null &&
                FreeLivingSessionStore.sameDeviceRecord(before, actual) && observed.status.sessionId == actual.sessionId &&
                observed.status.bytes >= previous.status.bytes && observed.status.records >= previous.status.records &&
                (previous.status.collecting || !observed.status.collecting) &&
                actual.bytes >= (previous.records.single { it.sessionId == before.sessionId }.bytes) &&
                actual.records >= (previous.records.single { it.sessionId == before.sessionId }.records) &&
                actual.bytes >= observed.status.bytes && actual.records >= observed.status.records &&
                observed.records.all { it == actual || it in abort.collectingObservation.records }) {
                "停止记录有变化，请保留戒指并重新检查"
            }
            abortHighWater = observed
            abortStopPollCount++
            if (observed.status.collecting) {
                if (abortStopPollCount < FreeLivingCaptureCoordinator.STOP_RECOVERY_POLL_LIMIT)
                    scheduleAbortStopCheck(FreeLivingCaptureCoordinator.STOP_POLL_INTERVAL_MS)
                else publish(CollectionPage.RECOVERY, "暂未确认停止，请保留戒指并重新检查")
                return
            }
            require(actual.bytes == observed.status.bytes && actual.records == observed.status.records) {
                "戒指仍在整理记录，请重新检查"
            }
            store.confirmUnconfirmedStartAbortStop(current.sessionId, observed)
            abortHighWater = null
            connect() // A separate READ stream preserves unassigned bytes without late previous-record DATA.
            return
        }
        require(observed.address == stopped.address && observed.status == stopped.status &&
            observed.records.size == stopped.records.size && observed.records.toSet() == stopped.records.toSet()) {
            "待保留的记录有变化，请保留戒指数据并重新检查"
        }
        val target = observed.records.single { it.sessionId == stopped.status.sessionId }
        val empty = target.bytes == 0L && target.records == 0L
        if (!empty && !hasPreserved(observed.address, listOf(target))) {
            if (backupObservation == null) backupObservation = observed
            continueExistingBackup(observed, onlyRecord = target)
            return
        }
        val proof = if (empty) null else requireNotNull(unknownPreservation).let {
            UnknownTimeRecordProof(it.record, it.backupId, it.rawSha256, preservationOwnerId, generation, it.savedAtMs)
        }
        store.completeUnconfirmedStartAbort(current.sessionId, proof, observed, clock.nowEpochMs(), preservationOwnerId)
        coordinator.refresh()
        browsingHome = true; taskPage = null
        taskError = null
        lastIdle = null
        selectedActivity = null; requestedActivity = null
        inspect() // Use the normal readiness rules, including the multiple-unknown-record guard.
    }

    private fun scheduleBackupTimeout() {
        val id = ++downloadTimeout
        scheduler.schedule(30_000) {
            if (!closed && backupDownloader != null && query == null && id == downloadTimeout) safely {
                port.disconnect()
                onDisconnected(generation, "保存中断，请重新连接戒指后继续")
            }
        }
    }

    private fun beginDownload(current: FreeLivingSession, observed: HealthRecordObservation) {
        val expected = requireNotNull(current.deviceRecordEvidence) { "本次戒指记录需要核对" }.record
        require(!current.deviceAssociationInvalidated && observed.address == current.preparation.ring?.address)
        require(expected.unixMs > 0L || stopEvidenceGeneration == generation) {
            "戒指时间信息不足，请重新连接后继续保存"
        }
        require(!observed.status.collecting && observed.status.errorCode == 0 && observed.status.sessionId == expected.sessionId)
        val actual = observed.records.singleOrNull { it.sessionId == expected.sessionId }
        require(actual != null && FreeLivingSessionStore.sameDeviceRecord(expected, actual) &&
            actual.bytes >= expected.bytes && actual.records >= expected.records &&
            observed.status.bytes == actual.bytes && observed.status.records == actual.records) {
            "戒指记录发生变化，已保留本次信息"
        }
        require(otherRecordsAreStable(current, observed, actual)) {
            "戒指记录发生变化，请重新连接后继续保存"
        }
        if (unknownReadGeneration == generation) {
            require(expected.unixMs > 0) { "本次时间尚未确认，请保留戒指数据并重新检查" }
            connect() // Separate untagged DATA from the previously preserved old original.
            return
        }
        if (actual != expected) store.updateDeviceEvidence(current.sessionId,
            DeviceRecordEvidence(actual, observed.status, observed.statusReceivedAtMs))
        val next = downloadFactory(directory, current, actual)
        lastIdle = observed
        if (next.nextOffset == actual.bytes) {
            val completed = next.finish(HealthMessage.ReadEnd(actual.bytes, true))
            finalizingDownload = FinalizingDownload(next, completed, actual)
            reconcileCompletedDownload(current, observed, requireNotNull(finalizingDownload))
            return
        }
        downloader = next
        publish(CollectionPage.DOWNLOADING)
        requestWindow(next)
    }

    private fun reconcileCompletedDownload(
        current: FreeLivingSession,
        observed: HealthRecordObservation,
        pending: FinalizingDownload,
    ) {
        val expected = pending.record
        require(!observed.status.collecting && observed.status.errorCode == 0 &&
            observed.status.sessionId == expected.sessionId) {
            "下载完成后戒指状态尚未稳定，原始文件已保留"
        }
        val actual = observed.records.singleOrNull { it.sessionId == expected.sessionId }
            ?: throw IllegalStateException("下载完成后未找到同一戒指记录，原始文件已保留")
        require(FreeLivingSessionStore.sameDeviceRecord(expected, actual)) {
            "下载完成后戒指记录身份发生变化，原始文件已保留"
        }
        require(actual.bytes >= expected.bytes && actual.records >= expected.records &&
            observed.status.bytes == actual.bytes && observed.status.records == actual.records) {
            "下载完成后戒指计数尚未稳定，原始文件已保留"
        }
        require(otherRecordsAreStable(current, observed, actual)) {
            "下载完成后出现其他记录变化，原始文件已保留"
        }
        if (actual != expected) {
            store.updateDeviceEvidence(current.sessionId,
                DeviceRecordEvidence(actual, observed.status, observed.statusReceivedAtMs))
            pending.download.extendTo(actual)
            finalizingDownload = null
            downloader = pending.download
            lastIdle = observed
            publish(CollectionPage.DOWNLOADING)
            requestWindow(pending.download)
            return
        }
        store.completeLocalData(current.sessionId, listOf(pending.completed.file), clock.nowEpochMs())
        finalizingDownload = null
        runCatching { pending.download.releaseTemporary() }
            .onFailure { reportError(it as? Exception ?: Exception(it)) }
        coordinator.refresh()
        enqueueSavedRecords()
        publish(CollectionPage.COMPLETE)
    }

    private fun otherRecordsAreStable(
        current: FreeLivingSession,
        observed: HealthRecordObservation,
        target: HealthMessage.ListItem,
    ): Boolean {
        val baseline = current.startBaseline?.records.orEmpty()
            .filter { it.sessionId != target.sessionId }.toSet()
        val currentOthers = observed.records.filter { it.sessionId != target.sessionId }.toSet()
        // The original firmware may naturally overwrite an older Flash record after START.
        // Preparation already preserves eligible old records; a missing baseline item is allowed,
        // while a new or changed unrelated item must never be folded into this session.
        return currentOthers.all { it in baseline }
    }

    private fun requestWindow(download: RealSessionDownload) {
        val id = requireNotNull(store.readPending()?.deviceSessionId)
        check(port.read(id, download.nextOffset, 16_384)) { "下载连接中断，请重试" }
        scheduleDownloadTimeout()
    }

    private fun scheduleDownloadTimeout() {
        val id = ++downloadTimeout
        scheduler.schedule(30_000) {
            if (!closed && downloader != null && id == downloadTimeout) safely {
                closeDownload()
                port.disconnect()
                onDisconnected(generation, "下载中断，已保存的部分将继续保留")
            }
        }
    }

    private fun closeDownload() {
        downloadTimeout++
        val previous = downloader
        downloader = null
        val pendingFinalization = finalizingDownload
        finalizingDownload = null
        val previousBackup = backupDownloader
        backupDownloader = null; backupObservation = null; backupRecord = null
        unknownBackupAttempt = null
        try { previous?.close() } finally {
            try { pendingFinalization?.download?.close() } finally { previousBackup?.close() }
        }
    }

    fun close() {
        abortWaitOperation = null; abortHighWater = null; abortPrecheck = null
        unknownPreservation = null
        closed = true; generation++; query = null; readinessWait = null; timeRound = null; startingClockEvidence = null
        coordinator.close()
        runCatching { closeDownload() }.onFailure { reportError(it as? Exception ?: Exception(it)) }
        runCatching { port.disconnect() }.onFailure { reportError(it as? Exception ?: Exception(it)) }
    }

    /** Release the transport when leaving an idle page; pending captures keep their owner. */
    fun releaseIfIdle(): Boolean {
        if (!canReleaseIfIdle()) return false
        close()
        return true
    }

    /** Called on the serial owner executor before committing a UI-requested release. */
    fun canReleaseIfIdle(): Boolean = !closed && initialized && !saving && timeRound == null && downloader == null && finalizingDownload == null && backupObservation == null &&
        coordinator.state.timeoutOperationId == null && !coordinator.state.settling && store.readPending() == null

    /** Confirmed-stop choices and reference entry are local work, including during BLE recovery. */
    private fun pendingReferencePage(): CollectionPage? = store.readPending()?.takeIf {
        it.stopConfirmedAtMs != null && it.reference == null && it.startAbort == null
    }?.let { if (it.completionPolicy == null) CollectionPage.FINISH else CollectionPage.REFERENCE }

    private fun publish(page: CollectionPage, error: String? = null) {
        val current = visibleCurrentSession()
        val pending = current?.takeIf { it.isPending }
        val task = if (page == CollectionPage.RECOVERY) pendingReferencePage() ?: page else page
        if (task != CollectionPage.HOME) { taskPage = task; taskError = error }
        val visible = if (browsingHome) CollectionPage.HOME else task
        val localReference = pending?.stopConfirmedAtMs != null && pending.reference == null && pending.startAbort == null
        val idle = lastIdle
        val checkingDevice = pending == null && connected && (query != null || readinessWait != null || timeRound != null)
        val canStart = pending == null && idle != null && !connecting && connected && query == null && readinessWait == null && timeRound == null && backupObservation == null
        val visibleProfile = pending?.preparation ?: profile
        val participantLabel = localProfile?.takeIf {
            it.participantId == visibleProfile?.participantId
        }?.displayLabel ?: visibleProfile?.participantId.orEmpty()
        state = CollectionFlowState(page = visible, taskPage = taskPage, isSimulation = false, uploadAvailable = uploadAvailable,
            hasProfile = profile?.ring != null, participantId = visibleProfile?.participantId.orEmpty(),
            participantLabel = participantLabel, ringName = visibleProfile?.ring?.name.orEmpty(),
            placement = visibleProfile?.placement, session = current,
            connected = connected, connecting = connecting, checkingDevice = checkingDevice,
            preservingExisting = backupObservation != null,
            busy = saving || (!localReference && (connecting || query != null || readinessWait != null || timeRound != null || abortWaitOperation != null || backupObservation != null || coordinator.state.settling || coordinator.state.timeoutOperationId != null)) ||
                (visible != CollectionPage.HOME && visible in setOf(CollectionPage.STARTING, CollectionPage.STOPPING, CollectionPage.SAVING, CollectionPage.DOWNLOADING)),
            error = if (visible == CollectionPage.HOME) taskError else error,
            savedSteps = current?.reference?.steps, referenceStatus = current?.reference?.status?.wireValue,
            canStart = canStart, canStop = connected && coordinator.state.phase == CaptureControlPhase.COLLECTING,
            canRetry = !connecting && query == null && readinessWait == null && timeRound == null && abortWaitOperation == null && downloader == null && backupObservation == null && !coordinator.state.settling,
            canEndStartAttempt = pending?.phase == FreeLivingSessionPhase.START_REQUESTED && connected && !connecting &&
                pending.startAbort == null && coordinator.state.unconfirmedStartStopCandidate == null &&
                query == null && downloader == null && !saving && !coordinator.state.settling && coordinator.state.timeoutOperationId == null &&
                coordinator.state.phase == CaptureControlPhase.NEEDS_REVIEW,
            records = recordSummaries(), selectedActivity = selectedActivity,
            canStopUnconfirmedStart = connected && !connecting && query == null && abortWaitOperation == null &&
                !abortCandidateInvalidated && pending?.phase == FreeLivingSessionPhase.START_REQUESTED && pending.startAbort == null &&
                coordinator.state.unconfirmedStartStopCandidate?.connectionGeneration == generation)
        observers.forEach { observer -> notifyObserver { if (observer in observers) observer(state) } }
    }

    private fun safely(failurePage: CollectionPage = CollectionPage.ERROR, action: () -> Unit) {
        if (closed) return
        try { action() } catch (error: Exception) {
            abortWaitOperation = null
            abortPrecheck = null
            val clockStartFailed = startingClockEvidence != null
            timeRound = null; startingClockEvidence = null
            if (clockStartFailed) {
                // Binding failed inside the coordinator's pre-START callback. Cancel its
                // unscheduled wait and require fresh recovery queries on a new connection.
                connected = false
                runCatching { coordinator.onDisconnected(generation) }
                runCatching { port.disconnect() }
            }
            endStartAttemptReason = null
            reportError(error)
            runCatching { closeDownload() }
            query = null; readinessWait = null; lastIdle = null; connecting = false
            val message = error.message?.takeIf { it.length < 70 && it.any { c -> c.code > 127 } }
                ?: "暂时无法完成，本次记录已保留"
            try { publish(failurePage, message) } catch (_: Exception) {
                state = state.copy(page = CollectionPage.ERROR, busy = false, canStart = false, canStop = false,
                    connecting = false, error = "记录读取失败，请检查身份与戒指设置", canRetry = false, canEndStartAttempt = false)
                observers.forEach { observer -> notifyObserver { if (observer in observers) observer(state) } }
            }
        }
    }

    private fun recordDiagnostic(observation: HealthRecordObservation) {
        // The journal owns required evidence. An auxiliary diagnostic must not split its transaction.
        runCatching { recordObservation(observation) }.onFailure { reportError(it as? Exception ?: Exception(it)) }
    }

    private fun hasPreserved(address: String, records: List<HealthMessage.ListItem>): Boolean = records.all { record ->
        if (record.unixMs > 0) store.hasPreservedDeviceRecords(address, listOf(record)) else {
            val proof = unknownPreservation
            proof != null && proof.generation == generation && proof.record == record &&
                backups.verifyUnknown(address, record, proof.backupId).sha256 == proof.rawSha256
        }
    }

    companion object {
        internal const val READINESS_CHECK_LIMIT = 3
        internal const val READINESS_CHECK_INTERVAL_MS = 1_500L
    }
}
