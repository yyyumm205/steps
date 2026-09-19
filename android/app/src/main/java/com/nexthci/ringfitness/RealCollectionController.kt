package com.nexthci.ringfitness

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** The service supplies a fresh connection generation and serializes every callback. */
interface RealCollectionPort : HealthControlPort {
    fun connect(ring: PreparedRing, generation: Long): Boolean
    fun disconnect()
    fun read(sessionId: Int, offset: Long, length: Int): Boolean
}

fun interface CollectionScheduler { fun schedule(delayMs: Long, action: () -> Unit) }

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
    private var downloadTimeout = 0L
    private var reconnectCount = 0
    private var closed = false
    private var saving = false
    private var stopEvidenceGeneration: Long? = null
    private data class Inspection(val id: Long, var status: SensorPacket.Health? = null,
        val records: MutableList<HealthMessage.ListItem> = mutableListOf())

    @Volatile override var state = CollectionFlowState(isSimulation = false, uploadAvailable = false,
        connected = false, connecting = true, busy = true, hasProfile = true)
        private set

    private val coordinator: FreeLivingCaptureCoordinator = FreeLivingCaptureCoordinator(store, port, clock) { control ->
        control.observation?.let(::recordDiagnostic)
        when (control.phase) {
            CaptureControlPhase.IDLE -> Unit
            CaptureControlPhase.CHECKING -> publish(if (store.readPending() == null) CollectionPage.STARTING else CollectionPage.RECOVERY)
            CaptureControlPhase.STARTING -> publish(CollectionPage.STARTING)
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
        browsingHome = current == null || current.localData != null
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
        query = null
        coordinator.onConnected(requireNotNull(profile?.ring).address, connection)
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
        connected = false; connecting = false; query = null; lastIdle = null
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
                        publish(CollectionPage.COMPLETE)
                    } else requestWindow(download)
                }
                else -> Unit
            }
            return@safely
        }
        val round = query
        if (round == null) {
            coordinator.onHealth(connection, packet)
            // A restored STOP may already have a preserved reference; inspect its final record.
            if (query == null && coordinator.state.timeoutOperationId == null &&
                coordinator.state.phase == CaptureControlPhase.AWAITING_REFERENCE && store.readPending()?.reference != null) inspect()
            return@safely
        }
        when (val message = packet.message) {
            is HealthMessage.Status -> {
                if (round.status != null) return@safely
                round.status = packet
                check(port.queryRecords()) { "查询未完成，请重新连接" }
            }
            is HealthMessage.ListItem -> {
                if (round.status == null) return@safely
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
                    lastIdle = observation.takeIf { !it.status.collecting && it.status.errorCode == 0 }
                    if (lastIdle == null) publish(CollectionPage.RECOVERY, "戒指状态需要核对，请联系研究者")
                    else { taskPage = null; taskError = null; publish(CollectionPage.HOME) }
                } else if (current.phase == FreeLivingSessionPhase.AWAITING_REFERENCE && current.reference != null) {
                    beginDownload(current, observation)
                }
            }
            else -> Unit
        }
    }

    override fun register(participantId: String, placement: RingPlacement) = Unit // Preparation owns registration.

    override fun start() = safely {
        if (!state.canStart || connecting || query != null || downloader != null || saving) return@safely
        browsingHome = false; lastIdle = null
        coordinator.refresh()
        coordinator.requestStart(requireNotNull(profile), authorizedExisting())
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
        if (saving || downloader != null || query != null || coordinator.state.timeoutOperationId != null) {
            publish(taskPage ?: CollectionPage.RECOVERY, taskError); return@safely
        }
        val pending = store.readPending()
        when {
            pending == null -> { if (store.read()?.localData != null) publish(CollectionPage.COMPLETE) else connect() }
            pending.phase == FreeLivingSessionPhase.AWAITING_REFERENCE && pending.reference == null -> publish(CollectionPage.REFERENCE)
            coordinator.state.phase == CaptureControlPhase.COLLECTING && connected -> publish(CollectionPage.COLLECTING)
            else -> connect() // A fresh GATT channel separates untagged late replies from retry queries.
        }
    }

    override fun retryUpload(sessionId: String) = Unit
    override fun home() = safely { browsingHome = true; publish(CollectionPage.HOME) }
    override fun setFault(fault: FlowTestFault) = Unit
    override fun disconnect() = Unit // A page cannot tear down the collection connection.
    override fun reconnect() = safely { browsingHome = false; reconnectCount = 0; connect() }

    private fun connect() {
        if (connecting) return
        closeDownload(); query = null; lastIdle = null
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

    private fun inspect() {
        if (query != null || downloader != null || !connected) return
        val id = ++operation
        query = Inspection(id)
        if (store.readPending()?.reference != null) publish(CollectionPage.DOWNLOADING)
        else publish(CollectionPage.HOME)
        check(port.queryStatus()) { "查询未完成，请重新连接" }
        scheduler.schedule(30_000) {
            if (!closed && query?.id == id) safely {
                query = null; lastIdle = null
                port.disconnect()
                onDisconnected(generation, "查询超时，请重新连接戒指")
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
        previous?.close()
    }

    fun close() {
        closed = true; generation++; query = null
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
    fun canReleaseIfIdle(): Boolean = !closed && initialized && !saving && downloader == null &&
        coordinator.state.timeoutOperationId == null && store.readPending() == null

    private fun publish(page: CollectionPage, error: String? = null) {
        val current = store.read()
        val pending = current?.takeIf { it.localData == null }
        if (page != CollectionPage.HOME) { taskPage = page; taskError = error }
        val visible = if (browsingHome) CollectionPage.HOME else page
        val idle = lastIdle
        val canStart = pending == null && idle != null && !connecting && connected && query == null
        state = CollectionFlowState(page = visible, taskPage = taskPage, isSimulation = false, uploadAvailable = false,
            hasProfile = profile?.ring != null, participantId = (pending?.preparation ?: profile)?.participantId.orEmpty(),
            placement = (pending?.preparation ?: profile)?.placement, session = current,
            connected = connected, connecting = connecting, busy = connecting || query != null ||
                (visible != CollectionPage.HOME && visible in setOf(CollectionPage.STARTING, CollectionPage.STOPPING, CollectionPage.SAVING, CollectionPage.DOWNLOADING)),
            error = if (visible == CollectionPage.HOME) taskError else error,
            savedSteps = current?.reference?.steps, referenceStatus = current?.reference?.status?.wireValue,
            canStart = canStart, canStop = connected && coordinator.state.phase == CaptureControlPhase.COLLECTING,
            canRetry = !connecting && query == null && downloader == null,
            records = store.listSessions().map { FlowRecordSummary(it.sessionId, it.reference?.steps, it.reference?.status?.wireValue,
                it.transfer.status.wireValue, it.localData != null) })
        observers.forEach { observer -> notifyObserver { if (observer in observers) observer(state) } }
    }

    private fun safely(failurePage: CollectionPage = CollectionPage.ERROR, action: () -> Unit) {
        if (closed) return
        try { action() } catch (error: Exception) {
            reportError(error)
            runCatching { closeDownload() }
            query = null; lastIdle = null; connecting = false
            val message = error.message?.takeIf { it.length < 70 && it.any { c -> c.code > 127 } }
                ?: "暂时无法完成，本次记录已保留"
            try { publish(failurePage, message) } catch (_: Exception) {
                state = state.copy(page = CollectionPage.ERROR, busy = false, canStart = false, canStop = false,
                    connecting = false, error = "记录读取失败，请联系研究者", canRetry = false)
                observers.forEach { observer -> notifyObserver { if (observer in observers) observer(state) } }
            }
        }
    }

    private fun recordDiagnostic(observation: HealthRecordObservation) {
        // The journal owns required evidence. An auxiliary diagnostic must not split its transaction.
        runCatching { recordObservation(observation) }.onFailure { reportError(it as? Exception ?: Exception(it)) }
    }
}
