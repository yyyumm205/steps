package com.nexthci.ringfitness

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

fun interface DemoFlowScheduler { fun schedule(delayMs: Long, action: () -> Unit) }

/**
 * A serial owner for the development flow. Only device and transfer outcomes are synthetic;
 * preparation, reference records, files and task state use the shared durable stores.
 * No Android Activity, BLE client, network client or production preparation path is referenced.
 */
class DemoFlowController(
    directory: File,
    private val scheduler: DemoFlowScheduler,
    private val clock: CaptureClock,
    private val notifyObserver: (() -> Unit) -> Unit = { it() },
    private val syncDirectory: (File) -> Unit = ::syncDemoDirectory,
    private val beforeSessionCommit: (File) -> Unit = {},
) : CollectionFlow {
    private val directory = directory.canonicalFile.also { require(it.name == "collection-demo") }
    private val observers = CopyOnWriteArrayList<(CollectionFlowState) -> Unit>()
    private val preparation: PreparationStore
    private val store: FreeLivingSessionStore
    private val device: DemoDeviceStore
    private var coordinator: FreeLivingCaptureCoordinator? = null
    private var connected = true
    private var generation = 0L
    private var initialized = false
    private var blockedQuery = false
    private var fault = FlowTestFault.NONE
    private var operationEpoch = 0L
    private val transferring = mutableSetOf<String>()
    private val downloading = mutableSetOf<String>()
    private var recoveryOwner = false
    private var savingReference = false
    private var failNextReferenceCommit = false
    private var browsingHome = false
    private var taskPage: CollectionPage? = null
    private var taskError: String? = null
    private var selectedActivity: SessionActivity? = null

    @Volatile override var state = CollectionFlowState(isSimulation = true, busy = true)
        private set

    init {
        if (!this.directory.isDirectory) {
            check(this.directory.mkdir()) { "无法创建体验数据目录" }
            syncDirectory(requireNotNull(this.directory.parentFile))
        }
        preparation = PreparationStore(File(this.directory, "profile"))
        store = FreeLivingSessionStore(File(this.directory, "session.json"), { source, target ->
            if (failNextReferenceCommit) {
                failNextReferenceCommit = false
                throw IOException("本次未保存，请重试")
            }
            beforeSessionCommit(source)
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }, syncDirectory)
        device = DemoDeviceStore(File(this.directory, "device.json"), syncDirectory)
    }

    /** Called once by the process owner, not by page creation or subscription. */
    fun initialize() = safely {
        if (initialized) return@safely
        initialized = true
        store.listSessions().filter { it.isDiscarded }.forEach { runCatching { store.cleanupDiscardedSession(it.sessionId) } }
        recoverStored(resumeTransfer = true)
        val currentId = store.read()?.sessionId
        store.listSessions().filter {
            it.sessionId != currentId && it.uploadAllowed && it.localData != null &&
                it.transfer.status in setOf(SessionTransferStatus.PENDING, SessionTransferStatus.TRANSFERRING)
        }.forEach { upload(it.sessionId) }
    }

    override fun observe(observer: (CollectionFlowState) -> Unit): AutoCloseable {
        observers += observer
        notifyObserver { if (observer in observers) observer(state) }
        return AutoCloseable { observers -= observer }
    }

    override fun register(participantId: String, placement: RingPlacement) = safely {
        require(store.read()?.localData != null || store.read() == null) { "请先完成本次记录" }
        preparation.registerUsername(participantId, placement)
        if (preparation.read()?.ring == null) preparation.selectRing(DEMO_RING)
        publish(CollectionPage.HOME)
    }

    override fun start() = safely {
        browsingHome = false
        inFlightPage()?.takeUnless { it == CollectionPage.UPLOADING }?.let {
            publish(it)
            return@safely
        }
        val profile = requireNotNull(preparation.read()) { "请先填写用户名" }
        require(profile.ring == DEMO_RING && profile.placement != null) { "请先完成准备" }
        val current = store.read()
        if (current?.isPending == true) { showStored(current); return@safely }
        val activity = requireNotNull(selectedActivity) { "请选择本次活动" }
        require(activity != SessionActivity.FREE_LIVING)
        if (!connected) { publish(CollectionPage.RECOVERY, "连接已断开，请重新连接"); return@safely }
        // This synthetic reset is allowed only after the previous record is verified on disk.
        if (current != null && !current.isDiscarded) verifyLocalFiles(current)
        device.save(null)
        recoveryOwner = false
        blockedQuery = false
        operationEpoch++
        attachCoordinator()
        selectedActivity = null
        coordinator!!.requestStart(profile, activity = activity)
    }

    override fun selectActivity(activity: SessionActivity) = safely {
        require(activity != SessionActivity.FREE_LIVING)
        if (store.readPending() != null || state.busy) return@safely
        selectedActivity = activity
        publish(state.page, state.error)
    }

    override fun enterFinish() = safely {
        val current = requireNotNull(store.readPending())
        require(current.stopConfirmedAtMs != null && current.localData == null && !savingReference)
        browsingHome = false
        publish(CollectionPage.FINISH)
    }

    override fun chooseFinish(uploadNow: Boolean) = safely(CollectionPage.FINISH) {
        val current = requireNotNull(store.readPending())
        if (!uploadNow && current.reference == null && current.completionPolicy == null) {
            publish(CollectionPage.FINISH)
            return@safely
        }
        val saved = store.setCompletionPolicy(current.sessionId, current.completionPolicy ?:
            if (uploadNow) CompletionPolicy.SAVE_UPLOAD else CompletionPolicy.DEFER_ON_RING)
        browsingHome = false
        if (saved.isRingDeferred) publish(CollectionPage.RING_PENDING)
        else if (saved.reference != null) download(saved.sessionId) else publish(CollectionPage.REFERENCE)
    }

    override fun finalizeSession(uploadNow: Boolean, stepsText: String, status: String, reason: String) =
        safely(CollectionPage.FINISH) {
            browsingHome = false
            if (savingReference) { publish(CollectionPage.SAVING); return@safely }
            val current = requireNotNull(store.readPending())
            val policy = current.completionPolicy ?:
                if (uploadNow) CompletionPolicy.SAVE_UPLOAD else CompletionPolicy.DEFER_ON_RING
            val reference = sessionReferenceFromInput(stepsText, status, reason, clock.nowEpochMs())
            savingReference = true
            try {
                publish(CollectionPage.SAVING)
                later(350, CollectionPage.FINISH) {
                    try {
                        failNextReferenceCommit = consume(FlowTestFault.SAVE_FAILURE)
                        val saved = store.finalizeStoppedSession(current.sessionId, policy, reference)
                        val persisted = requireNotNull(store.read(current.sessionId))
                        check(persisted.completionPolicy == saved.completionPolicy && persisted.reference == saved.reference)
                        if (saved.isRingDeferred) publish(CollectionPage.RING_PENDING) else download(saved.sessionId)
                    } finally { savingReference = false; failNextReferenceCommit = false }
                }
            } catch (error: Exception) {
                savingReference = false
                throw error
            }
        }

    override fun discardSession() = safely(CollectionPage.FINISH) {
        val current = requireNotNull(store.readPending())
        require(!savingReference && current.sessionId !in transferring)
        store.discardStoppedSession(current.sessionId, clock.nowEpochMs())
        val cleaned = runCatching { store.cleanupDiscardedSession(current.sessionId) }.isSuccess
        operationEpoch++
        coordinator?.close(); coordinator = null
        downloading.remove(current.sessionId)
        selectedActivity = null
        taskPage = null
        taskError = if (cleaned) null else "本段已放弃，剩余文件将在下次打开时继续清理。"
        browsingHome = true
        publish(CollectionPage.HOME)
    }

    override fun stop() = safely {
        browsingHome = false
        val current = store.read() ?: return@safely
        if (current.phase != FreeLivingSessionPhase.COLLECTING || !connected) return@safely
        if (!recoveryOwner) {
            coordinator?.requestStop()
        } else {
            // A restarted synthetic device has an explicit durable UUID witness. This stronger
            // evidence is unavailable on the real protocol and is confined to this debug adapter.
            val record = requireOwnedDevice(current)
            require(record.collecting) { "请先重新检查戒指状态" }
            store.requestStop(current.sessionId, clock.nowEpochMs())
            publish(CollectionPage.STOPPING)
            val stopped = record.copy(endedAtMs = clock.nowEpochMs())
            device.save(stopped)
            if (consume(FlowTestFault.STOP_TIMEOUT)) {
                later(1800) { publish(CollectionPage.RECOVERY, "暂未收到结束确认，请重新检查") }
            } else later(900) { recoverStored(false) }
        }
    }

    override fun enterReference() = safely {
        browsingHome = false
        if (savingReference) { publish(CollectionPage.SAVING); return@safely }
        val current = store.read() ?: return@safely
        require(current.phase in setOf(FreeLivingSessionPhase.STOP_REQUESTED, FreeLivingSessionPhase.AWAITING_REFERENCE))
        publish(if (current.stopConfirmedAtMs != null && current.completionPolicy == null) CollectionPage.FINISH else CollectionPage.REFERENCE)
    }

    override fun saveReference(stepsText: String, status: String, reason: String) = safely(CollectionPage.REFERENCE) {
        browsingHome = false
        if (savingReference) { publish(CollectionPage.SAVING); return@safely }
        val current = requireNotNull(store.read())
        if (current.reference != null) { showStored(current); return@safely }
        require(current.stopConfirmedAtMs == null || current.completionPolicy != null) { "请选择保存方式" }
        val reference = sessionReferenceFromInput(stepsText, status, reason, clock.nowEpochMs())
        if (current.stopConfirmedAtMs == null && reference.status == ReferenceStatus.VALID) {
            throw IllegalArgumentException("请先确认结束，或注明本次读数的异常")
        }
        savingReference = true
        try {
            publish(CollectionPage.SAVING)
            later(350, CollectionPage.REFERENCE) {
                try {
                    failNextReferenceCommit = consume(FlowTestFault.SAVE_FAILURE)
                    val saved = store.saveReference(current.sessionId, reference)
                    check(store.read(current.sessionId)?.reference == saved.reference)
                    if (saved.isRingDeferred) publish(CollectionPage.RING_PENDING)
                    else if (saved.stopConfirmedAtMs != null) download(saved.sessionId)
                    else publish(CollectionPage.RECOVERY, "读数已保存，请重新检查戒指")
                } finally { savingReference = false; failNextReferenceCommit = false }
            }
        } catch (error: Exception) {
            savingReference = false
            throw error
        }
    }

    override fun retry() = safely {
        if (state.page == CollectionPage.HOME) {
            browsingHome = false
            inFlightPage()?.let { publish(it); return@safely }
        }
        if (state.busy) return@safely
        blockedQuery = false
        val current = store.read()
        if (current == null || current.isDiscarded) {
            current?.let { store.cleanupDiscardedSession(it.sessionId) }
            taskPage = null; taskError = null; publish(CollectionPage.HOME); return@safely
        }
        if (current.isRingDeferred) { publish(CollectionPage.RING_PENDING); return@safely }
        if (current.localData != null) {
            if (current.transfer.status == SessionTransferStatus.COMPLETE) publish(CollectionPage.COMPLETE)
            else if (current.uploadAllowed) upload(current.sessionId) else publish(CollectionPage.COMPLETE)
        } else if (current.reference != null && current.stopConfirmedAtMs != null && current.completionPolicy != null) download(current.sessionId)
        else recoverStored(false)
    }

    override fun retryUpload(sessionId: String) = safely { store.allowUpload(sessionId); upload(sessionId) }

    override fun resumeRingTransfer() = safely {
        val current = store.readPending() ?: return@safely
        if (!current.isRingDeferred) return@safely
        store.resumeRingTransfer(current.sessionId)
        browsingHome = false
        if (!connected) reconnect() else recoverStored(false)
    }

    override fun reviseReference(sessionId: String, stepsText: String, status: String, reason: String) = safely {
        val reference = sessionReferenceFromInput(stepsText, status, reason, clock.nowEpochMs())
        store.reviseReferenceBeforeUpload(sessionId, reference)
        publish(CollectionPage.HOME)
    }

    override fun home() = safely { browsingHome = true; publish(CollectionPage.HOME) }

    override fun setFault(fault: FlowTestFault) {
        this.fault = fault
        publish(state.page, state.error)
    }

    override fun disconnect() = safely {
        connected = false
        operationEpoch++
        coordinator?.onDisconnected(generation)
        if (store.read()?.isRingDeferred == true) publish(CollectionPage.RING_PENDING)
        else publish(CollectionPage.RECOVERY, "连接已断开，本次记录已保留")
    }

    override fun reconnect() = safely {
        browsingHome = false
        connected = true
        blockedQuery = false
        coordinator?.close()
        coordinator = null
        operationEpoch++
        recoverStored(false)
    }

    private fun attachCoordinator() {
        coordinator?.close()
        val activeGeneration = ++generation
        val epoch = operationEpoch
        val port = object : HealthControlPort {
            override fun queryStatus(): Boolean {
                if (!connected) return false
                if (!blockedQuery) scheduler.schedule(450) {
                    if (epoch != operationEpoch || !connected) return@schedule
                    safely {
                        val status = device.read()?.status() ?: HealthMessage.Status(false, 0, 0, 0, 0)
                        coordinator?.onHealth(activeGeneration, SensorPacket.Health(status, clock.nowEpochMs()))
                    }
                }
                return true
            }

            override fun queryRecords(): Boolean {
                if (!connected) return false
                if (!blockedQuery) scheduler.schedule(150) {
                    if (epoch != operationEpoch || !connected) return@schedule
                    safely {
                        val record = device.read()
                        record?.let { coordinator?.onHealth(activeGeneration, SensorPacket.Health(it.listItem(), clock.nowEpochMs())) }
                        coordinator?.onHealth(activeGeneration, SensorPacket.Health(HealthMessage.ListEnd(if (record == null) 0 else 1), clock.nowEpochMs()))
                    }
                }
                return true
            }

            override fun start(): Boolean {
                val session = requireNotNull(store.read())
                check(device.read() == null)
                device.save(DemoDeviceRecord(session.sessionId, (UUID.randomUUID().hashCode() and 0x7fffffff) % 65535 + 1, clock.nowEpochMs()))
                blockedQuery = consume(FlowTestFault.START_TIMEOUT)
                return true
            }

            override fun stop(): Boolean {
                val session = requireNotNull(store.read())
                val record = requireOwnedDevice(session)
                device.save(record.copy(endedAtMs = clock.nowEpochMs()))
                blockedQuery = consume(FlowTestFault.STOP_TIMEOUT)
                return true
            }
        }
        coordinator = FreeLivingCaptureCoordinator(store, port, clock, { delay, action -> scheduler.schedule(delay, action) }) { control ->
            when (control.phase) {
                CaptureControlPhase.IDLE -> Unit
                CaptureControlPhase.CHECKING, CaptureControlPhase.STARTING -> publish(CollectionPage.STARTING)
                CaptureControlPhase.COLLECTING -> publish(CollectionPage.COLLECTING)
                CaptureControlPhase.STOPPING -> publish(CollectionPage.STOPPING)
                CaptureControlPhase.AWAITING_REFERENCE -> publish(CollectionPage.FINISH)
                CaptureControlPhase.NEEDS_REVIEW -> publish(CollectionPage.RECOVERY, "暂未收到确认，请重新检查")
                CaptureControlPhase.STORAGE_ERROR -> publish(CollectionPage.ERROR, "暂时无法保存，请重试")
            }
            control.timeoutOperationId?.let { id ->
                scheduler.schedule(1800) {
                    if (epoch == operationEpoch) coordinator?.onTimeout(id)
                }
            }
        }
        coordinator!!.onConnected(DEMO_RING.address, activeGeneration)
    }

    private fun recoverStored(resumeTransfer: Boolean) {
        recoveryOwner = false
        val current = store.read()
        if (current == null || current.isDiscarded) { taskPage = null; taskError = null; publish(CollectionPage.HOME); return }
        if (current.isRingDeferred) { publish(CollectionPage.RING_PENDING); return }
        if (current.localData != null) {
            verifyLocalFiles(current)
            if (resumeTransfer && current.transfer.status == SessionTransferStatus.COMPLETE) {
                taskPage = CollectionPage.COMPLETE
                browsingHome = true
                publish(CollectionPage.HOME)
            }
            else if (resumeTransfer && current.uploadAllowed && current.transfer.status in setOf(SessionTransferStatus.PENDING, SessionTransferStatus.TRANSFERRING)) upload(current.sessionId)
            else showStored(current)
            return
        }
        if (!connected) { publish(CollectionPage.RECOVERY, "请重新连接，继续本次记录"); return }
        val record = device.read()
        if (record == null || record.ownerSessionId != current.sessionId ||
            (current.deviceSessionId != null && record.deviceSessionId != current.deviceSessionId)) {
            publish(CollectionPage.RECOVERY, "暂时无法确认戒指状态，本次记录已保留")
            return
        }
        recoveryOwner = true
        var recovered = current
        if (recovered.phase == FreeLivingSessionPhase.START_REQUESTED && record.collecting) {
            val observedAtMs = clock.nowEpochMs()
            recovered = store.confirmStart(recovered.sessionId, DEMO_RING.address, record.status(), observedAtMs,
                recordEvidence = recovered.startBaseline?.let {
                    DeviceRecordEvidence(record.listItem(), record.status(), observedAtMs)
                })
        }
        if (recovered.phase == FreeLivingSessionPhase.STOP_REQUESTED && !record.collecting) {
            val observedAtMs = clock.nowEpochMs()
            // Keep the shared journal's fingerprint and counter checks when recovering a v3
            // demo session. Older demo journals retain their absent association evidence.
            recovered = store.confirmStop(recovered.sessionId, DEMO_RING.address, record.status(), observedAtMs,
                recordEvidence = recovered.deviceRecordEvidence?.let {
                    DeviceRecordEvidence(record.listItem(), record.status(), observedAtMs)
                })
        }
        if ((recovered.phase == FreeLivingSessionPhase.COLLECTING) != record.collecting &&
            recovered.phase != FreeLivingSessionPhase.AWAITING_REFERENCE) {
            publish(CollectionPage.RECOVERY, "请重新检查戒指，本次记录已保留")
            return
        }
        if (recovered.reference != null && recovered.stopConfirmedAtMs != null && recovered.completionPolicy != null) download(recovered.sessionId)
        else showStored(recovered)
    }

    private fun showStored(current: FreeLivingSession) {
        when {
            current.isRingDeferred -> publish(CollectionPage.RING_PENDING)
            current.stopConfirmedAtMs != null && current.completionPolicy == null -> publish(CollectionPage.FINISH)
            current.localData != null && current.transfer.status == SessionTransferStatus.COMPLETE -> publish(CollectionPage.COMPLETE)
            current.localData != null -> publish(CollectionPage.COMPLETE)
            current.reference != null && current.stopConfirmedAtMs == null -> publish(CollectionPage.RECOVERY, "读数已保存，请重新检查戒指")
            current.reference != null -> publish(CollectionPage.ERROR, "读数已保存，请继续保存戒指数据")
            current.phase == FreeLivingSessionPhase.AWAITING_REFERENCE -> publish(if (current.completionPolicy == null) CollectionPage.FINISH else CollectionPage.REFERENCE)
            current.phase == FreeLivingSessionPhase.COLLECTING -> publish(CollectionPage.COLLECTING)
            else -> publish(CollectionPage.RECOVERY, "本次记录已保留，请重新检查")
        }
    }

    private fun download(sessionId: String) {
        val session = requireNotNull(store.read(sessionId))
        if (session.isDiscarded) return
        if (session.isRingDeferred) { publish(CollectionPage.RING_PENDING); return }
        if (session.localData != null) { upload(sessionId); return }
        require(session.stopConfirmedAtMs != null && session.reference != null)
        if (!connected) { publish(CollectionPage.RECOVERY, "读数已保存，请重新连接下载数据"); return }
        if (!downloading.add(sessionId)) { publish(CollectionPage.DOWNLOADING); return }
        try {
            publish(CollectionPage.DOWNLOADING)
            // Transfer tasks retain their owner when pages close and use session IDs, never a mutable current pointer.
            scheduler.schedule(1200) {
                downloading.remove(sessionId)
                safely {
                    if (!connected) throw IOException("读数已保存，请重新连接下载数据")
                    if (consume(FlowTestFault.DOWNLOAD_FAILURE)) throw IOException("读数已保存，数据下载请重试")
                    val current = requireNotNull(store.read(sessionId))
                    if (current.isDiscarded) return@safely
                    if (current.isRingDeferred) { publish(CollectionPage.RING_PENDING); return@safely }
                    val record = requireOwnedDevice(current)
                    require(!record.collecting)
                    val raw = File(directory, "$sessionId-simulated-signal.txt")
                    val payload = "SIMULATED_RING_SIGNAL\nsession_id=$sessionId\ndevice_session_id=${record.deviceSessionId}\nstarted_at_ms=${record.startedAtMs}\nended_at_ms=${record.endedAtMs}\n".toByteArray()
                    if (raw.exists()) require(raw.readBytes().contentEquals(payload)) { "已有数据需要核对" }
                    else writeDemoFile(raw, payload, syncDirectory)
                    store.completeLocalData(sessionId, listOf(SessionRawFile(raw.name, record.deviceSessionId,
                        raw.length(), demoDigest(payload), simulated = true)), clock.nowEpochMs())
                    verifyLocalFiles(requireNotNull(store.read(sessionId)))
                    upload(sessionId)
                }
            }
        } catch (error: Exception) {
            downloading.remove(sessionId)
            throw error
        }
    }

    private fun upload(sessionId: String) {
        val current = requireNotNull(store.read(sessionId))
        if (!current.uploadAllowed) {
            if (!current.isDiscarded && isCurrentTransferTask(sessionId)) publish(CollectionPage.COMPLETE)
            return
        }
        requireNotNull(current.localData)
        verifyLocalFiles(current)
        if (current.transfer.status == SessionTransferStatus.COMPLETE) {
            publish(if (isCurrentTransferTask(sessionId)) CollectionPage.COMPLETE else state.page)
            return
        }
        if (sessionId in transferring) {
            if (isCurrentTransferTask(sessionId)) publish(CollectionPage.UPLOADING)
            return
        }
        store.markTransferStarted(sessionId)
        transferring.add(sessionId)
        try {
            val displayed = isCurrentTransferTask(sessionId)
            if (displayed) publish(CollectionPage.UPLOADING)
            scheduler.schedule(1200) {
                transferring.remove(sessionId)
                safely {
                    if (store.read(sessionId)?.uploadAllowed != true) return@safely
                    if (consume(FlowTestFault.UPLOAD_FAILURE)) {
                        store.markTransferFailed(sessionId)
                        if (isCurrentTransferTask(sessionId)) publish(CollectionPage.COMPLETE, "数据已保存在手机，上传请重试")
                        else publish(state.page)
                        return@safely
                    }
                    val receiptId = "demo-$sessionId"
                    val receiptFile = File(directory, "$sessionId-simulated-receipt.txt")
                    val payload = "SIMULATED_UPLOAD_RECEIPT\nreceipt_id=$receiptId\nsession_id=$sessionId\n".toByteArray()
                    if (receiptFile.exists()) require(receiptFile.readBytes().contentEquals(payload))
                    else writeDemoFile(receiptFile, payload, syncDirectory)
                    store.completeTransfer(sessionId, SessionTransferReceipt(receiptId, clock.nowEpochMs(), simulated = true, sessionId = sessionId))
                    if (isCurrentTransferTask(sessionId)) publish(CollectionPage.COMPLETE) else publish(state.page)
                }
            }
        } catch (error: Exception) {
            transferring.remove(sessionId)
            throw error
        }
    }

    private fun verifyLocalFiles(session: FreeLivingSession) {
        val local = requireNotNull(session.localData)
        require(local.files.isNotEmpty())
        local.files.forEach {
            val file = File(directory, it.fileName).canonicalFile
            require(file.parentFile == directory && it.simulated && file.length() == it.bytes && demoDigest(file.readBytes()) == it.sha256)
        }
    }

    private fun requireOwnedDevice(session: FreeLivingSession): DemoDeviceRecord {
        val record = requireNotNull(device.read()) { "请重新检查戒指状态" }
        require(record.ownerSessionId == session.sessionId &&
            (session.deviceSessionId == null || record.deviceSessionId == session.deviceSessionId)) { "戒指记录需要核对" }
        return record
    }

    private fun consume(expected: FlowTestFault): Boolean {
        if (fault != expected) return false
        fault = FlowTestFault.NONE
        return true
    }

    private fun later(delayMs: Long, failurePage: CollectionPage = CollectionPage.ERROR, action: () -> Unit) {
        scheduler.schedule(delayMs) { safely(failurePage, action) }
    }

    private fun safely(failurePage: CollectionPage = CollectionPage.ERROR, action: () -> Unit) {
        try { action() } catch (error: Exception) {
            val message = when (error) {
                is IllegalArgumentException -> error.message?.takeIf { it.length < 45 && it.any { c -> c.code > 127 } }
                is IOException -> error.message?.takeIf { it.length < 45 && it.any { c -> c.code > 127 } }
                else -> null
            } ?: "暂时无法完成，已有记录已保留"
            try { publish(failurePage, message) } catch (_: Exception) {
                taskPage = CollectionPage.ERROR
                taskError = "记录暂时无法读取，请联系研究者"
                state = state.copy(page = if (browsingHome) CollectionPage.HOME else CollectionPage.ERROR,
                    taskPage = taskPage, busy = false, error = taskError, canStart = false, canStop = false, canRetry = false)
                broadcast()
            }
        }
    }

    private fun publish(page: CollectionPage, error: String? = null) {
        val profile = preparation.read()
        val session = store.read()?.takeUnless { it.isDiscarded }
        val reference = session?.reference
        if (page != CollectionPage.HOME) {
            taskPage = page
            taskError = error
        }
        val visiblePage = if (browsingHome) CollectionPage.HOME else page
        // Uploading is independent background work; a locally complete record must not block
        // choosing and starting the next session.
        val busy = visiblePage in waitingPages && visiblePage != CollectionPage.UPLOADING
        val blockingWork = inFlightPage()?.let { it != CollectionPage.UPLOADING } == true
        state = CollectionFlowState(page = visiblePage, taskPage = taskPage, isSimulation = true, hasProfile = profile?.ring != null,
            participantId = profile?.participantId.orEmpty(), participantLabel = profile?.displayLabel.orEmpty(),
            ringName = profile?.ring?.name.orEmpty(), placement = profile?.placement,
            session = session, connected = connected, busy = busy, error = if (visiblePage == CollectionPage.HOME) taskError else error,
            savedSteps = reference?.steps, referenceStatus = reference?.status?.name?.lowercase(),
            canStart = !blockingWork && connected && profile?.ring != null && (session == null || session.localData != null),
            canStop = !busy && connected && session?.phase == FreeLivingSessionPhase.COLLECTING &&
                (recoveryOwner || coordinator?.state?.phase == CaptureControlPhase.COLLECTING),
            canRetry = !busy && (taskPage != null || session != null || !connected),
            selectedActivity = selectedActivity,
            records = store.listSessions().filterNot { it.isDiscarded }.map { FlowRecordSummary(it.sessionId, it.reference?.steps,
                it.reference?.status?.name?.lowercase(), it.transfer.status.name.lowercase(), it.localData != null,
                transferInFlight = it.sessionId in transferring, activity = it.activity,
                uploadDeferred = it.completionPolicy == CompletionPolicy.SAVE_LATER,
                startedAtMs = it.startedAtMs,
                timeZoneId = it.timeZoneId,
                referenceEditable = it.localData != null && it.reference != null &&
                    it.completionPolicy == CompletionPolicy.SAVE_LATER &&
                    it.transfer.status == SessionTransferStatus.PENDING && it.transfer.attempts == 0 &&
                    it.sessionId !in transferring,
                referenceReason = it.reference?.reason, ringDeferred = it.isRingDeferred,
                phoneStartAtMs = it.phoneStartAnchorMs()) }, fault = fault)
        broadcast()
    }

    private fun broadcast() = observers.forEach { observer -> notifyObserver { if (observer in observers) observer(state) } }

    private fun inFlightPage(): CollectionPage? = when {
        savingReference -> CollectionPage.SAVING
        coordinator?.state?.phase in setOf(CaptureControlPhase.CHECKING, CaptureControlPhase.STARTING) -> CollectionPage.STARTING
        coordinator?.state?.phase == CaptureControlPhase.STOPPING -> CollectionPage.STOPPING
        taskPage == CollectionPage.STOPPING && recoveryOwner -> CollectionPage.STOPPING
        store.read()?.sessionId in downloading -> CollectionPage.DOWNLOADING
        store.read()?.sessionId in transferring -> CollectionPage.UPLOADING
        else -> null
    }

    private fun isCurrentTransferTask(sessionId: String): Boolean = store.read()?.sessionId == sessionId &&
        coordinator?.state?.phase !in setOf(CaptureControlPhase.CHECKING, CaptureControlPhase.STARTING)

    companion object {
        private val waitingPages = setOf(CollectionPage.STARTING, CollectionPage.STOPPING, CollectionPage.SAVING, CollectionPage.DOWNLOADING, CollectionPage.UPLOADING)
        val DEMO_RING = PreparedRing("02:00:00:00:00:01", "体验戒指")
    }
}
