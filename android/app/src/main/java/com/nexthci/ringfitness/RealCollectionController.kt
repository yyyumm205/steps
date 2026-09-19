package com.nexthci.ringfitness

import java.io.File
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
    fun enqueue(sessionId: String, retry: Boolean = false)
    fun isInFlight(sessionId: String): Boolean
    fun needsLocalReview(sessionId: String): Boolean = false
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
    private var backupDownloader: RealSessionDownload? = null
    private var backupObservation: HealthRecordObservation? = null
    private var backupRecord: HealthMessage.ListItem? = null
    private var downloadTimeout = 0L
    private var reconnectCount = 0
    private var closed = false
    private var saving = false
    private var stopEvidenceGeneration: Long? = null
    private var endStartAttemptReason: String? = null
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

    @Volatile override var state = CollectionFlowState(isSimulation = false, uploadAvailable = uploads != null,
        connected = false, connecting = true, busy = true, hasProfile = true)
        private set

    private val coordinator: FreeLivingCaptureCoordinator = FreeLivingCaptureCoordinator(store,
        object : HealthControlPort by port {
            override fun start(): Boolean {
                if (syncClockBeforeStart) requireFreshClockEvidence(requireNotNull(boundClockEvidence))
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
                if (current?.reference == null) publish(CollectionPage.REFERENCE)
                else publish(CollectionPage.RECOVERY, "步数已保存，正在核对戒指数据")
            }
            CaptureControlPhase.NEEDS_REVIEW -> publish(CollectionPage.RECOVERY, when (control.issue) {
                CaptureControlIssue.EXISTING_RECORDS -> "戒指中有待处理记录，请联系研究者"
                CaptureControlIssue.RECORD_CHANGED, CaptureControlIssue.RECORD_ORIGIN_UNCERTAIN,
                CaptureControlIssue.RECOVERY_REQUIRES_REVIEW -> "本次记录需要核对，请保留戒指数据"
                CaptureControlIssue.CONNECTION_LOST, CaptureControlIssue.NOT_CONNECTED -> "连接中断，请将戒指放在手机附近"
                CaptureControlIssue.START_ARCHIVE_BLOCKED -> "本次记录仍需核对，请联系研究者"
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
        profile = store.readPending()?.preparation ?: requireNotNull(preparation.read()) { "请先完成准备信息" }
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
            pending == null -> inspect()
            pending.phase == FreeLivingSessionPhase.AWAITING_REFERENCE -> {
                if (pending.reference != null) inspect() else publish(CollectionPage.REFERENCE)
            }
            else -> coordinator.reconcile()
        }
    }

    fun onDisconnected(connection: Long, message: String) = safely {
        if (closed || connection != generation) return@safely
        connected = false; connecting = false; query = null; readinessWait = null; lastIdle = null
        timeRound = null; startingClockEvidence = null; boundClockEvidence = null
        endStartAttemptReason = null
        stopEvidenceGeneration = null
        closeDownload()
        coordinator.onDisconnected(connection)
        publish(CollectionPage.RECOVERY, message)
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
        val backup = backupDownloader
        if (backup != null && query == null) {
            when (val message = packet.message) {
                is HealthMessage.DataChunk -> { backup.append(message); scheduleBackupTimeout() }
                is HealthMessage.ReadEnd -> {
                    if (backup.checkpoint(message)) {
                        val completed = backup.finish(message)
                        backups.accept(requireNotNull(profile?.ring).address, requireNotNull(backupRecord), completed, clock.nowEpochMs())
                        backupDownloader = null; backupRecord = null; downloadTimeout++
                        backup.close()
                        runCatching { backup.releaseTemporary() }.onFailure { reportError(it as? Exception ?: Exception(it)) }
                        // DATA has no record ID. A fresh GATT generation separates the next
                        // record's bytes from any late notifications belonging to this backup.
                        connect()
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
                    if (download.checkpoint(message)) {
                        val current = requireNotNull(store.readPending())
                        val completed = download.finish(message)
                        store.completeLocalData(current.sessionId, listOf(completed.file), clock.nowEpochMs())
                        downloader = null; downloadTimeout++
                        runCatching { download.close() }.onFailure { reportError(it as? Exception ?: Exception(it)) }
                        runCatching { download.releaseTemporary() }.onFailure { reportError(it as? Exception ?: Exception(it)) }
                        coordinator.refresh()
                        enqueueSavedRecords()
                        publish(CollectionPage.COMPLETE)
                    } else requestWindow(download)
                }
                else -> Unit
            }
            return@safely
        }
        val round = query
        if (round == null) {
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
                coordinator.state.phase == CaptureControlPhase.AWAITING_REFERENCE && store.readPending()?.reference != null) inspect()
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
                if (current == null) {
                    if (backupObservation != null) {
                        continueExistingBackup(observation)
                    } else if (ChargingStartCompatibility.isCandidate(observation.status, statusPacket.statusErrorReason)) {
                        query = round
                        round.observation = observation
                        round.batteryRequestedAtMs = clock.nowEpochMs()
                        check(port.queryBattery()) { "未能读取充电状态，请重新连接" }
                    } else finishReadinessInspection(round, observation)
                } else if (current.phase == FreeLivingSessionPhase.AWAITING_REFERENCE && current.reference != null) {
                    beginDownload(current, observation)
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

    override fun start() = safely {
        if (!state.canStart || connecting || query != null || readinessWait != null || timeRound != null || downloader != null || backupObservation != null || saving) return@safely
        if (syncClockBeforeStart) {
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
        browsingHome = false; lastIdle = null
        coordinator.refresh()
        coordinator.requestStart(requireNotNull(profile), authorizedExisting())
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
        if (!state.canStop) return@safely
        browsingHome = false
        coordinator.requestStop()
    }

    override fun enterReference() = safely {
        val current = requireNotNull(store.readPending())
        require(current.phase in setOf(FreeLivingSessionPhase.STOP_REQUESTED, FreeLivingSessionPhase.AWAITING_REFERENCE))
        if (current.reference != null) { retry(); return@safely }
        browsingHome = false
        publish(CollectionPage.REFERENCE)
    }

    override fun saveReference(stepsText: String, status: String, reason: String) = safely(CollectionPage.REFERENCE) {
        if (saving || downloader != null) return@safely
        browsingHome = false
        val current = requireNotNull(store.readPending())
        if (current.reference != null) { retry(); return@safely }
        val kind = ReferenceStatus.entries.singleOrNull { it.wireValue == status }
            ?: throw IllegalArgumentException("请选择读数状态")
        val steps = when {
            kind == ReferenceStatus.MISSING -> null
            stepsText.trim().matches(Regex("[0-9]+")) -> stepsText.trim().toLongOrNull()
                ?: throw IllegalArgumentException("步数太大，请核对读数")
            kind == ReferenceStatus.UNRELIABLE && stepsText.isBlank() -> null
            else -> throw IllegalArgumentException("请输入计步器上的整数")
        }
        require(kind == ReferenceStatus.VALID || reason.isNotBlank()) { "请填写简短原因" }
        val reference = SessionReference(kind, steps, clock.nowEpochMs(), if (kind == ReferenceStatus.VALID) null else reason.trim())
        saving = true
        try {
            publish(CollectionPage.SAVING)
            val saved = store.saveReference(current.sessionId, reference)
            check(store.read(current.sessionId)?.reference == saved.reference)
            if (saved.stopConfirmedAtMs == null) publish(CollectionPage.RECOVERY, "读数已保存，请重新检查戒指")
            else if (connected) inspect() else publish(CollectionPage.RECOVERY, "读数已保存，请重新连接以下载数据")
        } finally { saving = false }
    }

    override fun retry() = safely {
        browsingHome = false
        if (saving || downloader != null || backupObservation != null || query != null || readinessWait != null ||
            coordinator.state.timeoutOperationId != null || coordinator.state.settling) {
            publish(taskPage ?: CollectionPage.RECOVERY, taskError); return@safely
        }
        val pending = store.readPending()
        when {
            pending == null -> {
                if (store.read()?.localData != null && lastIdle != null) publish(CollectionPage.COMPLETE)
                else connect()
            }
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
        endStartAttemptReason = value
        connect()
    }

    override fun retryUpload(sessionId: String) {
        if (closed) return
        val upload = uploads ?: return
        runCatching {
            val session = store.read(sessionId) ?: return@runCatching
            if (isRealLocal(session) && session.transfer.status != SessionTransferStatus.COMPLETE &&
                !upload.isInFlight(sessionId)) upload.enqueue(sessionId, retry = true)
        }.onFailure { reportError(it as? Exception ?: Exception(it)) }
        refreshUploads()
    }

    /** Refresh transfer evidence without changing navigation, BLE queries or capture actions. */
    fun refreshUploads() {
        if (closed || !initialized) return
        runCatching {
            state = state.copy(session = store.read()?.takeUnless { it.startAttemptArchive != null }, records = recordSummaries())
            observers.forEach { observer -> notifyObserver { if (observer in observers) observer(state) } }
        }.onFailure { reportError(it as? Exception ?: Exception(it)) }
    }

    private fun enqueueSavedRecords() {
        val upload = uploads ?: return
        runCatching {
            store.listSessions().filter { isRealLocal(it) && it.transfer.status in
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

    private fun recordSummaries() = store.listSessions().filter { it.startAttemptArchive == null && (it.localData == null || isRealLocal(it)) }
        .map { FlowRecordSummary(it.sessionId, it.reference?.steps, it.reference?.status?.wireValue,
            it.transfer.status.wireValue, isRealLocal(it), uploads?.isInFlight(it.sessionId) == true,
            uploads?.needsLocalReview(it.sessionId) == true) }

    override fun home() = safely { browsingHome = true; publish(CollectionPage.HOME) }
    override fun setFault(fault: FlowTestFault) = Unit
    override fun disconnect() = Unit // A page cannot tear down the collection connection.
    override fun reconnect() = safely { browsingHome = false; reconnectCount = 0; connect() }

    private fun connect() {
        if (connecting) return
        timeRound = null; startingClockEvidence = null; boundClockEvidence = null
        closeDownload(); query = null; readinessWait = null; lastIdle = null
        stopEvidenceGeneration = null
        if (connected) coordinator.onDisconnected(generation)
        connected = false; connecting = true
        val id = ++generation
        port.disconnect()
        publish(if (store.readPending() == null) CollectionPage.HOME else CollectionPage.RECOVERY, "正在连接戒指")
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
        if (store.readPending()?.reference != null) publish(CollectionPage.DOWNLOADING)
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
            publish(CollectionPage.RECOVERY, "戒指记录需要核对，请联系研究者")
            return
        }
        if (observed.status.errorCode == 0 || chargingRecovery != null) {
            if (observed.records.isNotEmpty() && !store.hasPreservedDeviceRecords(observed.address, observed.records)) {
                val approved = authorizedExisting()
                if (approved?.ringAddress != observed.address || approved.status != observed.status ||
                    approved.records.toSet() != observed.records.toSet()) {
                    require(observed.records.all { it.unixMs > 0 && it.uptimeMs > 0 && it.bytes > 0 && it.records > 0 } &&
                        observed.records.any { it.sessionId == observed.status.sessionId && it.bytes == observed.status.bytes && it.records == observed.status.records }) {
                        "戒指记录信息不完整，请联系研究者"
                    }
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
            publish(CollectionPage.RECOVERY, "戒指仍返回异常，请联系研究者")
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

    private fun continueExistingBackup(observed: HealthRecordObservation) {
        val original = requireNotNull(backupObservation)
        require(observed.connectionGeneration == original.connectionGeneration && observed.address == original.address &&
            !observed.status.collecting && observed.status.errorCode in setOf(0, -16) &&
            observed.status.copy(errorCode = 0) == original.status.copy(errorCode = 0) &&
            observed.records.size == original.records.size && observed.records.toSet() == original.records.toSet()) {
            "戒指记录发生变化，已保存的内容会保留，请重新检查"
        }
        if (backupDownloader == null) {
            val next = observed.records.firstOrNull { !store.hasPreservedDeviceRecords(observed.address, listOf(it)) }
            if (next == null) {
                backupObservation = null
                taskPage = null; taskError = null
                inspect() // Fresh STATUS and, when needed, BATTERY before exposing start again.
                return
            }
            backupRecord = next
            backupDownloader = backups.open(observed.address, next)
        }
        browsingHome = true
        taskPage = null; taskError = null
        publish(CollectionPage.HOME)
        check(port.read(requireNotNull(backupRecord).sessionId, requireNotNull(backupDownloader).nextOffset, 16_384)) {
            "保存中断，请重新连接戒指后继续"
        }
        scheduleBackupTimeout()
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
            "戒指时间信息不足，请联系研究者核对本次记录"
        }
        require(!observed.status.collecting && observed.status.errorCode == 0 && observed.status.sessionId == expected.sessionId)
        val actual = observed.records.singleOrNull { it.sessionId == expected.sessionId }
        require(actual == expected && observed.status.bytes == expected.bytes && observed.status.records == expected.records) {
            "戒指记录发生变化，已保留本次信息"
        }
        require(observed.records.all { it == expected || it in current.startBaseline?.records.orEmpty() }) {
            "戒指中出现其他记录，请联系研究者核对"
        }
        val next = downloadFactory(directory, current, expected)
        lastIdle = observed
        downloader = next
        publish(CollectionPage.DOWNLOADING)
        requestWindow(next)
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
        val previousBackup = backupDownloader
        backupDownloader = null; backupObservation = null; backupRecord = null
        try { previous?.close() } finally { previousBackup?.close() }
    }

    fun close() {
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
    fun canReleaseIfIdle(): Boolean = !closed && initialized && !saving && timeRound == null && downloader == null && backupObservation == null &&
        coordinator.state.timeoutOperationId == null && !coordinator.state.settling && store.readPending() == null

    private fun publish(page: CollectionPage, error: String? = null) {
        val current = store.read()?.takeUnless { it.startAttemptArchive != null }
        val pending = current?.takeIf { it.isPending }
        if (page != CollectionPage.HOME) { taskPage = page; taskError = error }
        val visible = if (browsingHome) CollectionPage.HOME else page
        val idle = lastIdle
        val checkingDevice = pending == null && connected && (query != null || readinessWait != null || timeRound != null)
        val canStart = pending == null && idle != null && !connecting && connected && query == null && readinessWait == null && timeRound == null && backupObservation == null
        state = CollectionFlowState(page = visible, taskPage = taskPage, isSimulation = false, uploadAvailable = uploads != null,
            hasProfile = profile?.ring != null, participantId = (pending?.preparation ?: profile)?.participantId.orEmpty(),
            placement = (pending?.preparation ?: profile)?.placement, session = current,
            connected = connected, connecting = connecting, checkingDevice = checkingDevice,
            preservingExisting = backupObservation != null,
            busy = connecting || query != null || readinessWait != null || timeRound != null || backupObservation != null || coordinator.state.settling || coordinator.state.timeoutOperationId != null ||
                (visible != CollectionPage.HOME && visible in setOf(CollectionPage.STARTING, CollectionPage.STOPPING, CollectionPage.SAVING, CollectionPage.DOWNLOADING)),
            error = if (visible == CollectionPage.HOME) taskError else error,
            savedSteps = current?.reference?.steps, referenceStatus = current?.reference?.status?.wireValue,
            canStart = canStart, canStop = connected && coordinator.state.phase == CaptureControlPhase.COLLECTING,
            canRetry = !connecting && query == null && readinessWait == null && timeRound == null && downloader == null && backupObservation == null && !coordinator.state.settling,
            canEndStartAttempt = pending?.phase == FreeLivingSessionPhase.START_REQUESTED && connected && !connecting &&
                query == null && downloader == null && !saving && !coordinator.state.settling && coordinator.state.timeoutOperationId == null &&
                coordinator.state.phase == CaptureControlPhase.NEEDS_REVIEW,
            records = recordSummaries())
        observers.forEach { observer -> notifyObserver { if (observer in observers) observer(state) } }
    }

    private fun safely(failurePage: CollectionPage = CollectionPage.ERROR, action: () -> Unit) {
        if (closed) return
        try { action() } catch (error: Exception) {
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
                    connecting = false, error = "记录读取失败，请联系研究者", canRetry = false, canEndStartAttempt = false)
                observers.forEach { observer -> notifyObserver { if (observer in observers) observer(state) } }
            }
        }
    }

    private fun recordDiagnostic(observation: HealthRecordObservation) {
        // The journal owns required evidence. An auxiliary diagnostic must not split its transaction.
        runCatching { recordObservation(observation) }.onFailure { reportError(it as? Exception ?: Exception(it)) }
    }

    companion object {
        internal const val READINESS_CHECK_LIMIT = 3
        internal const val READINESS_CHECK_INTERVAL_MS = 1_500L
    }
}
