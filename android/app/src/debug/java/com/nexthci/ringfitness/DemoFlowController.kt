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
        recoverStored(resumeTransfer = true)
        val currentId = store.read()?.sessionId
        store.listSessions().filter {
            it.sessionId != currentId && it.localData != null &&
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
        preparation.register(participantId, placement)
        if (preparation.read()?.ring == null) preparation.selectRing(DEMO_RING)
        publish(CollectionPage.HOME)
    }

    override fun start() = safely {
        if (coordinator?.state?.phase in setOf(CaptureControlPhase.CHECKING, CaptureControlPhase.STARTING, CaptureControlPhase.STOPPING)) return@safely
        val profile = requireNotNull(preparation.read()) { "请先填写体验编号" }
        require(profile.ring == DEMO_RING && profile.placement != null) { "请先完成准备" }
        val current = store.read()
        if (current != null && current.localData == null) { showStored(current); return@safely }
        if (!connected) { publish(CollectionPage.RECOVERY, "连接已断开，请重新连接"); return@safely }
        // This synthetic reset is allowed only after the previous record is verified on disk.
        if (current != null) verifyLocalFiles(current)
        device.save(null)
        recoveryOwner = false
        blockedQuery = false
        operationEpoch++
        attachCoordinator()
        coordinator!!.requestStart(profile)
    }

    override fun stop() = safely {
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
        val current = store.read() ?: return@safely
        require(current.phase in setOf(FreeLivingSessionPhase.STOP_REQUESTED, FreeLivingSessionPhase.AWAITING_REFERENCE))
        publish(CollectionPage.REFERENCE)
    }

    override fun saveReference(stepsText: String, status: String, reason: String) = safely(CollectionPage.REFERENCE) {
        if (savingReference) return@safely
        val current = requireNotNull(store.read())
        if (current.reference != null) { showStored(current); return@safely }
        val referenceStatus = when (status) {
            "valid" -> ReferenceStatus.VALID
            "missing" -> ReferenceStatus.MISSING
            "unreliable" -> ReferenceStatus.UNRELIABLE
            else -> throw IllegalArgumentException("请选择读数状态")
        }
        val steps = when {
            referenceStatus == ReferenceStatus.MISSING -> null
            stepsText.trim().matches(Regex("[0-9]+")) -> stepsText.trim().toLongOrNull()
                ?: throw IllegalArgumentException("步数太大，请核对读数")
            referenceStatus == ReferenceStatus.UNRELIABLE && stepsText.isBlank() -> null
            else -> throw IllegalArgumentException("请输入计步器上的整数")
        }
        require(referenceStatus == ReferenceStatus.VALID || reason.isNotBlank()) { "请填写简短原因" }
        if (current.stopConfirmedAtMs == null && referenceStatus == ReferenceStatus.VALID) {
            throw IllegalArgumentException("请先确认结束，或注明本次读数的异常")
        }
        val reference = SessionReference(referenceStatus, steps, clock.nowEpochMs(),
            if (referenceStatus == ReferenceStatus.VALID) null else reason.trim().ifEmpty { null })
        savingReference = true
        try {
            publish(CollectionPage.SAVING)
            later(350, CollectionPage.REFERENCE) {
                try {
                    failNextReferenceCommit = consume(FlowTestFault.SAVE_FAILURE)
                    val saved = store.saveReference(current.sessionId, reference)
                    check(store.read(current.sessionId)?.reference == saved.reference)
                    if (saved.stopConfirmedAtMs != null) download(saved.sessionId)
                    else publish(CollectionPage.RECOVERY, "读数已保存，请重新检查戒指")
                } finally { savingReference = false; failNextReferenceCommit = false }
            }
        } catch (error: Exception) {
            savingReference = false
            throw error
        }
    }

    override fun retry() = safely {
        if (state.busy) return@safely
        blockedQuery = false
        val current = store.read()
        if (current == null) { publish(CollectionPage.HOME); return@safely }
        if (current.localData != null) {
            if (current.transfer.status == SessionTransferStatus.COMPLETE) publish(CollectionPage.COMPLETE)
            else upload(current.sessionId)
        } else if (current.reference != null && current.stopConfirmedAtMs != null) download(current.sessionId)
        else recoverStored(false)
    }

    override fun retryUpload(sessionId: String) = safely { upload(sessionId) }

    override fun home() = safely { publish(CollectionPage.HOME) }

    override fun setFault(fault: FlowTestFault) {
        this.fault = fault
        publish(state.page, state.error)
    }

    override fun disconnect() = safely {
        connected = false
        operationEpoch++
        coordinator?.onDisconnected(generation)
        publish(CollectionPage.RECOVERY, "连接已断开，本次记录已保留")
    }

    override fun reconnect() = safely {
        connected = true
        blockedQuery = false
        coordinator = null
        operationEpoch++
        recoverStored(false)
    }

    private fun attachCoordinator() {
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
        coordinator = FreeLivingCaptureCoordinator(store, port, clock) { control ->
            when (control.phase) {
                CaptureControlPhase.IDLE -> Unit
                CaptureControlPhase.CHECKING, CaptureControlPhase.STARTING -> publish(CollectionPage.STARTING)
                CaptureControlPhase.COLLECTING -> publish(CollectionPage.COLLECTING)
                CaptureControlPhase.STOPPING -> publish(CollectionPage.STOPPING)
                CaptureControlPhase.AWAITING_REFERENCE -> publish(CollectionPage.REFERENCE)
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
        if (current == null) { publish(CollectionPage.HOME); return }
        if (current.localData != null) {
            verifyLocalFiles(current)
            if (resumeTransfer && current.transfer.status == SessionTransferStatus.COMPLETE) publish(CollectionPage.HOME)
            else if (resumeTransfer && current.transfer.status in setOf(SessionTransferStatus.PENDING, SessionTransferStatus.TRANSFERRING)) upload(current.sessionId)
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
            recovered = store.confirmStart(recovered.sessionId, DEMO_RING.address, record.status(), clock.nowEpochMs())
        }
        if (recovered.phase == FreeLivingSessionPhase.STOP_REQUESTED && !record.collecting) {
            recovered = store.confirmStop(recovered.sessionId, DEMO_RING.address, record.status(), clock.nowEpochMs())
        }
        if ((recovered.phase == FreeLivingSessionPhase.COLLECTING) != record.collecting &&
            recovered.phase != FreeLivingSessionPhase.AWAITING_REFERENCE) {
            publish(CollectionPage.RECOVERY, "请重新检查戒指，本次记录已保留")
            return
        }
        if (recovered.reference != null && recovered.stopConfirmedAtMs != null) download(recovered.sessionId)
        else showStored(recovered)
    }

    private fun showStored(current: FreeLivingSession) {
        when {
            current.localData != null && current.transfer.status == SessionTransferStatus.COMPLETE -> publish(CollectionPage.COMPLETE)
            current.localData != null -> publish(CollectionPage.ERROR, "数据已保存在手机，传输可以重试")
            current.reference != null && current.stopConfirmedAtMs == null -> publish(CollectionPage.RECOVERY, "读数已保存，请重新检查戒指")
            current.reference != null -> publish(CollectionPage.ERROR, "读数已保存，请继续保存戒指数据")
            current.phase == FreeLivingSessionPhase.AWAITING_REFERENCE -> publish(CollectionPage.REFERENCE)
            current.phase == FreeLivingSessionPhase.COLLECTING -> publish(CollectionPage.COLLECTING)
            else -> publish(CollectionPage.RECOVERY, "本次记录已保留，请重新检查")
        }
    }

    private fun download(sessionId: String) {
        val session = requireNotNull(store.read(sessionId))
        if (session.localData != null) { upload(sessionId); return }
        require(session.stopConfirmedAtMs != null && session.reference != null)
        if (!connected) { publish(CollectionPage.RECOVERY, "读数已保存，请重新连接下载数据"); return }
        if (!downloading.add(sessionId)) return
        try {
            publish(CollectionPage.DOWNLOADING)
            // Transfer tasks retain their owner when pages close and use session IDs, never a mutable current pointer.
            scheduler.schedule(1200) {
                downloading.remove(sessionId)
                safely {
                    if (!connected) throw IOException("读数已保存，请重新连接下载数据")
                    if (consume(FlowTestFault.DOWNLOAD_FAILURE)) throw IOException("读数已保存，数据下载请重试")
                    val current = requireNotNull(store.read(sessionId))
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
        requireNotNull(current.localData)
        verifyLocalFiles(current)
        if (current.transfer.status == SessionTransferStatus.COMPLETE) {
            publish(if (store.read()?.sessionId == sessionId) CollectionPage.COMPLETE else state.page)
            return
        }
        if (sessionId in transferring) return
        store.markTransferStarted(sessionId)
        transferring.add(sessionId)
        try {
            val displayed = store.read()?.sessionId == sessionId
            if (displayed) publish(CollectionPage.UPLOADING)
            scheduler.schedule(1200) {
                transferring.remove(sessionId)
                safely {
                    if (consume(FlowTestFault.UPLOAD_FAILURE)) {
                        store.markTransferFailed(sessionId)
                        if (store.read()?.sessionId == sessionId) publish(CollectionPage.ERROR, "数据已保存在手机，上传请重试")
                        else publish(state.page)
                        return@safely
                    }
                    val receiptId = "demo-$sessionId"
                    val receiptFile = File(directory, "$sessionId-simulated-receipt.txt")
                    val payload = "SIMULATED_UPLOAD_RECEIPT\nreceipt_id=$receiptId\nsession_id=$sessionId\n".toByteArray()
                    if (receiptFile.exists()) require(receiptFile.readBytes().contentEquals(payload))
                    else writeDemoFile(receiptFile, payload, syncDirectory)
                    store.completeTransfer(sessionId, SessionTransferReceipt(receiptId, clock.nowEpochMs(), simulated = true, sessionId = sessionId))
                    if (store.read()?.sessionId == sessionId) publish(CollectionPage.COMPLETE) else publish(state.page)
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
                state = state.copy(page = CollectionPage.ERROR, busy = false, error = "记录暂时无法读取，请联系研究者", canStart = false, canStop = false, canRetry = false)
                broadcast()
            }
        }
    }

    private fun publish(page: CollectionPage, error: String? = null) {
        val profile = preparation.read()
        val session = store.read()
        val reference = session?.reference
        val busy = page in setOf(CollectionPage.STARTING, CollectionPage.STOPPING, CollectionPage.SAVING, CollectionPage.DOWNLOADING, CollectionPage.UPLOADING)
        state = CollectionFlowState(page = page, isSimulation = true, hasProfile = profile?.ring != null,
            participantId = profile?.participantId.orEmpty(), placement = profile?.placement,
            session = session, connected = connected, busy = busy, error = error,
            savedSteps = reference?.steps, referenceStatus = reference?.status?.name?.lowercase(),
            canStart = !busy && connected && profile?.ring != null && (session == null || session.localData != null),
            canStop = !busy && connected && session?.phase == FreeLivingSessionPhase.COLLECTING &&
                (recoveryOwner || coordinator?.state?.phase == CaptureControlPhase.COLLECTING),
            canRetry = !busy && (session != null || !connected),
            records = store.listSessions().map { FlowRecordSummary(it.sessionId, it.reference?.steps,
                it.reference?.status?.name?.lowercase(), it.transfer.status.name.lowercase(), it.localData != null) }, fault = fault)
        broadcast()
    }

    private fun broadcast() = observers.forEach { observer -> notifyObserver { if (observer in observers) observer(state) } }

    companion object {
        val DEMO_RING = PreparedRing("02:00:00:00:00:01", "体验戒指")
    }
}
