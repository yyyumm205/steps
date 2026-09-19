package com.nexthci.ringfitness

import java.util.ArrayDeque
import java.util.Locale

/** Acceptance means queued locally, not acknowledged by the ring. No download commands belong here. */
interface HealthControlPort {
    fun queryBattery(): Boolean = false
    fun queryStatus(): Boolean
    fun queryRecords(): Boolean
    fun start(): Boolean
    fun stop(): Boolean
}

interface CaptureClock {
    fun nowEpochMs(): Long
    fun timeZoneId(): String
    fun nowElapsedMs(): Long = System.nanoTime() / 1_000_000L
}

enum class CaptureControlPhase {
    IDLE, CHECKING, STARTING, COLLECTING, STOPPING, AWAITING_REFERENCE, NEEDS_REVIEW, STORAGE_ERROR,
}

enum class CaptureControlIssue {
    NOT_CONNECTED, WRONG_RING, CONNECTION_LOST, RECONNECT_REQUIRED, QUERY_TIMEOUT,
    COMMAND_NOT_ACCEPTED, DEVICE_ERROR, EXISTING_RECORDS, INVALID_OBSERVATION,
    RECORD_ORIGIN_UNCERTAIN, RECORD_CHANGED, UNEXPECTED_DEVICE_STATE, RECOVERY_REQUIRES_REVIEW,
    STORAGE_FAILURE, START_ARCHIVE_BLOCKED,
}

data class HealthRecordObservation(
    val address: String,
    val connectionGeneration: Long,
    val status: HealthMessage.Status,
    val statusReceivedAtMs: Long,
    val records: List<HealthMessage.ListItem>,
)

/** Scope for one reviewed device baseline. It never authorizes deleting unrelated records. */
data class ExistingRecordAuthorization(
    val ringAddress: String,
    val status: HealthMessage.Status,
    val records: List<HealthMessage.ListItem>,
)

data class CaptureControlState(
    val phase: CaptureControlPhase = CaptureControlPhase.IDLE,
    val session: FreeLivingSession? = null,
    val issue: CaptureControlIssue? = null,
    val observation: HealthRecordObservation? = null,
    val timeoutOperationId: Long? = null,
    val settling: Boolean = false,
)

/**
 * The foreground service is the sole owner of this coordinator, journal and transport.
 * Call every public method on one serial execution context. Synchronous port/listener callbacks
 * are queued to finish the current transition before processing the next event.
 *
 * STATUS/LIST have no response nonce or boot ID. A local operation ID only guards timeouts.
 * A start requires a reviewed idle baseline and one distinct record on the issuing connection.
 * Reusing a numeric ID requires both nonzero anchors to change from the approved baseline.
 * A proved fingerprint and counter high-water marks are persisted with confirmation.
 * Recovery queries require that same fingerprint and monotonic counters. LIST anchors identify
 * records; they never supply capture boundaries. Firmware assumptions require device validation.
 */
class FreeLivingCaptureCoordinator(
    private val store: FreeLivingSessionStore,
    private val port: HealthControlPort,
    private val clock: CaptureClock,
    private val schedule: (Long, () -> Unit) -> Unit,
    private val onState: (CaptureControlState) -> Unit,
) {
    var state = CaptureControlState()
        private set

    private enum class Purpose { PREFLIGHT, START, STOP, INSPECT, ARCHIVE_START }
    private data class Round(
        val id: Long,
        val generation: Long,
        val purpose: Purpose,
        val startedAtElapsedMs: Long,
        var status: SensorPacket.Health? = null,
        val records: MutableList<HealthMessage.ListItem> = mutableListOf(),
        var batteryRequestedAtMs: Long? = null,
        var observation: HealthRecordObservation? = null,
    )
    private data class StartIntent(val preparation: PreparationSnapshot, val atMs: Long, val zone: String,
        val allowedExisting: ExistingRecordAuthorization?)
    private data class RecordIdentity(val sessionId: Int, val uptimeMs: Long, val unixMs: Long)
    private enum class StartWaitStage { BEFORE_START, BEFORE_STATUS }
    private data class StartWait(val id: Long, val generation: Long, val sessionId: String, val stage: StartWaitStage)

    private val events = ArrayDeque<() -> Unit>()
    private var draining = false
    private var restored = false
    private var session: FreeLivingSession? = null
    private var address: String? = null
    private var generation: Long? = null
    private var highestGeneration = Long.MIN_VALUE
    private var tainted = false
    private var operation = 0L
    private var round: Round? = null
    private var intent: StartIntent? = null
    private var baseline: HealthRecordObservation? = null
    private var ownership: RecordIdentity? = null
    private var lastRecord: HealthMessage.ListItem? = null
    private var lastStatus: HealthMessage.Status? = null
    private var archiveReason: String? = null
    private var startWait: StartWait? = null
    private var startPollCount = 0
    private var closed = false
    private var chargingRecoveryDeadline: Long? = null

    /** Initialization only; repeated calls cannot replace an active untagged response round. */
    fun restore() = dispatch { if (!restored) readJournal() }

    /** The sole owner calls this after reference/file persistence, when no response is pending. */
    fun refresh() = dispatch {
        if (round == null) {
            if (startWait != null) clearAssociation()
            readJournal()
        }
    }

    /** The owner cancels pending delays before releasing its transport. */
    fun close() = dispatch { closed = true; clearAssociation() }

    /** Generation must increase for every native connection, including a same-address reconnect. */
    fun onConnected(ringAddress: String, connectionGeneration: Long) = dispatch {
        if (connectionGeneration <= highestGeneration) return@dispatch
        highestGeneration = connectionGeneration
        address = ringAddress.uppercase(Locale.ROOT)
        generation = connectionGeneration
        tainted = false
        clearAssociation()
        if (!restored) readJournal() else publishRestored()
    }

    fun onDisconnected(connectionGeneration: Long) = dispatch {
        if (connectionGeneration != generation) return@dispatch
        generation = null
        address = null
        clearAssociation()
        publish(CaptureControlPhase.NEEDS_REVIEW, CaptureControlIssue.CONNECTION_LOST)
    }

    fun requestStart(preparation: PreparationSnapshot, allowedExisting: ExistingRecordAuthorization? = null) = dispatch {
        if (!ensureRestored() || round != null || intent != null || session != null) return@dispatch
        if (!connectedTo(preparation.ring?.address)) return@dispatch
        intent = StartIntent(preparation, clock.nowEpochMs(), clock.timeZoneId(), allowedExisting)
        beginRound(Purpose.PREFLIGHT)
    }

    fun requestStop() = dispatch {
        if (!ensureRestored() || round != null || startWait != null) return@dispatch
        val current = session ?: return@dispatch
        if (current.phase == FreeLivingSessionPhase.AWAITING_REFERENCE) return@dispatch
        if (!connectedTo(current.preparation.ring?.address)) return@dispatch
        if (ownership == null || current.deviceAssociationInvalidated) {
            review(CaptureControlIssue.RECOVERY_REQUIRES_REVIEW)
            return@dispatch
        }
        if (current.phase != FreeLivingSessionPhase.COLLECTING ||
            state.phase !in setOf(CaptureControlPhase.COLLECTING, CaptureControlPhase.STORAGE_ERROR)) return@dispatch
        if (save { store.requestStop(current.sessionId, clock.nowEpochMs()) } == null) return@dispatch
        publish(CaptureControlPhase.STOPPING)
        if (!send(port::stop)) return@dispatch
        beginRound(Purpose.STOP)
    }

    /** Recovery only queries. Stable collecting/reference states keep their normal action. */
    fun reconcile() = dispatch {
        if (!ensureRestored() || round != null || startWait != null) return@dispatch
        if (state.phase == CaptureControlPhase.COLLECTING || state.phase == CaptureControlPhase.AWAITING_REFERENCE) return@dispatch
        val current = session ?: return@dispatch
        if (!connectedTo(current.preparation.ring?.address)) return@dispatch
        val sameConnection = baseline?.connectionGeneration == generation
        val purpose = when {
            sameConnection && current.phase == FreeLivingSessionPhase.START_REQUESTED -> Purpose.START
            sameConnection && ownership != null && current.phase == FreeLivingSessionPhase.STOP_REQUESTED -> Purpose.STOP
            else -> Purpose.INSPECT
        }
        beginRound(purpose)
    }

    /** An explicit user action always collects a new round; cached observations cannot release data. */
    fun archiveUnconfirmedStart(reason: String) = dispatch {
        if (!ensureRestored() || round != null || startWait != null) return@dispatch
        val current = session ?: return@dispatch
        if (current.phase != FreeLivingSessionPhase.START_REQUESTED || current.startAttemptArchive != null ||
            !connectedTo(current.preparation.ring?.address)) return@dispatch
        val value = reason.trim()
        if (value.length !in 1..200) { review(CaptureControlIssue.START_ARCHIVE_BLOCKED); return@dispatch }
        archiveReason = value
        beginRound(Purpose.ARCHIVE_START)
    }

    fun onHealth(connectionGeneration: Long, packet: SensorPacket.Health) = dispatch {
        if (connectionGeneration != generation) return@dispatch
        startWait?.let { waiting ->
            // No response round is open during settling. In particular, a spontaneous START
            // response cannot confirm the request before the delayed STATUS/LIST query.
            if (waiting.stage == StartWaitStage.BEFORE_START) {
                val before = baseline
                val consistent = before != null && packet.receivedEpochMs > 0 && when (val message = packet.message) {
                    is HealthMessage.Status -> message == before.status &&
                        (session?.startBaseline?.chargingRecoveryEvidence == null || packet.statusErrorReason == 1)
                    is HealthMessage.ListItem -> message in before.records
                    is HealthMessage.ListEnd -> message.count == before.records.size
                    else -> false
                }
                if (!consistent) invalidateRound(CaptureControlIssue.UNEXPECTED_DEVICE_STATE)
            }
            return@dispatch
        }
        val active = round
        if (active == null) {
            val status = packet.message as? HealthMessage.Status ?: return@dispatch
            val watchingOwnedCollection = ownership != null && session?.phase == FreeLivingSessionPhase.COLLECTING
            if (watchingOwnedCollection &&
                (!validStatus(status) || !status.collecting || status.errorCode != 0 ||
                    status.sessionId != ownership?.sessionId || statusRegressed(status))) {
                rejectAssociation(CaptureControlIssue.UNEXPECTED_DEVICE_STATE)
            } else if (watchingOwnedCollection) {
                val previous = session?.deviceRecordEvidence
                if (previous != null && save { store.updateDeviceEvidence(requireNotNull(session).sessionId,
                    previous.copy(status = status, observedAtMs = packet.receivedEpochMs)) } == null) return@dispatch
                lastStatus = status
            }
            return@dispatch
        }
        if (active.generation != connectionGeneration) return@dispatch
        if (active.observation != null) {
            val consistent = when (val message = packet.message) {
                is HealthMessage.Status -> message == active.status?.message && packet.statusErrorReason == active.status?.statusErrorReason
                is HealthMessage.ListItem -> message in active.records
                is HealthMessage.ListEnd -> message.count == active.records.size
                else -> false
            }
            if (!consistent) invalidateRound(CaptureControlIssue.INVALID_OBSERVATION)
            return@dispatch
        }
        when (val message = packet.message) {
            is HealthMessage.Status -> {
                if (active.status != null) {
                    if ((active.purpose == Purpose.ARCHIVE_START || active.purpose == Purpose.PREFLIGHT) &&
                        (message != active.status?.message || packet.statusErrorReason != active.status?.statusErrorReason)) {
                        invalidateRound(CaptureControlIssue.INVALID_OBSERVATION)
                    }
                    return@dispatch
                }
                if (!validStatus(message) || packet.receivedEpochMs <= 0) {
                    invalidateRound(CaptureControlIssue.INVALID_OBSERVATION)
                    return@dispatch
                }
                active.status = packet
                send(port::queryRecords)
            }
            is HealthMessage.ListItem -> {
                if (active.status == null) return@dispatch
                if (!validRecord(message) || active.records.any { it.sessionId == message.sessionId } || active.records.size >= 255) {
                    invalidateRound(CaptureControlIssue.INVALID_OBSERVATION)
                    return@dispatch
                }
                active.records += message
            }
            is HealthMessage.ListEnd -> {
                val statusPacket = active.status ?: return@dispatch
                if (message.count != active.records.size) {
                    invalidateRound(CaptureControlIssue.INVALID_OBSERVATION)
                    return@dispatch
                }
                val observation = HealthRecordObservation(requireNotNull(address), connectionGeneration,
                    statusPacket.message as HealthMessage.Status, statusPacket.receivedEpochMs, active.records.toList())
                if (active.purpose == Purpose.PREFLIGHT && ChargingStartCompatibility.isCandidate(observation.status, statusPacket.statusErrorReason)) {
                    active.observation = observation
                    active.batteryRequestedAtMs = clock.nowEpochMs()
                    send(port::queryBattery)
                    return@dispatch
                }
                round = null
                completeRound(active.purpose, observation, statusPacket.statusErrorReason)
            }
            else -> Unit
        }
    }

    fun onBattery(connectionGeneration: Long, packet: SensorPacket.Battery) = dispatch {
        if (connectionGeneration != generation) return@dispatch
        if (startWait?.stage == StartWaitStage.BEFORE_START && session?.startBaseline?.chargingRecoveryEvidence != null) {
            if (packet.chargeStatus != 0) invalidateRound(CaptureControlIssue.DEVICE_ERROR)
            return@dispatch
        }
        val active = round ?: return@dispatch
        val observed = active.observation ?: return@dispatch
        if (clock.nowElapsedMs() - active.startedAtElapsedMs !in 0..ChargingStartCompatibility.FRESHNESS_MS) {
            invalidateRound(CaptureControlIssue.QUERY_TIMEOUT); return@dispatch
        }
        val evidence = ChargingStartCompatibility.evidence(requireNotNull(active.status), packet, connectionGeneration,
            requireNotNull(active.batteryRequestedAtMs), clock.nowEpochMs())
        if (evidence == null) { invalidateRound(CaptureControlIssue.DEVICE_ERROR); return@dispatch }
        chargingRecoveryDeadline = active.startedAtElapsedMs + ChargingStartCompatibility.FRESHNESS_MS
        round = null
        completeRound(active.purpose, observed, active.status?.statusErrorReason, evidence)
    }

    /** Host schedules this using elapsed time, never by comparing wall-clock timestamps. */
    fun onTimeout(operationId: Long) = dispatch {
        if (round?.id != operationId) return@dispatch
        invalidateRound(CaptureControlIssue.QUERY_TIMEOUT)
    }

    private fun beginRound(purpose: Purpose) {
        val connectedGeneration = generation ?: return
        if (tainted) { review(CaptureControlIssue.RECONNECT_REQUIRED); return }
        if (purpose == Purpose.START) startPollCount++
        round = Round(++operation, connectedGeneration, purpose, clock.nowElapsedMs())
        publish(when (purpose) {
            Purpose.PREFLIGHT, Purpose.INSPECT, Purpose.ARCHIVE_START -> CaptureControlPhase.CHECKING
            Purpose.START -> CaptureControlPhase.STARTING
            Purpose.STOP -> CaptureControlPhase.STOPPING
        })
        send(port::queryStatus)
    }

    private fun completeRound(purpose: Purpose, observed: HealthRecordObservation, statusReason: Int? = null,
        chargingRecovery: ChargingRecoveryEvidence? = null) {
        if (purpose == Purpose.ARCHIVE_START) {
            val reason = archiveReason ?: return
            archiveReason = null
            val current = session ?: return
            try {
                store.archiveStartAttempt(current.sessionId, observed, clock.nowEpochMs(), reason)
                session = store.readPending()
                clearAssociation()
                publish(CaptureControlPhase.IDLE, observed = observed)
            } catch (_: IllegalArgumentException) {
                review(CaptureControlIssue.START_ARCHIVE_BLOCKED, observed)
            } catch (_: Exception) {
                storageFailure()
            }
            return
        }
        if (purpose == Purpose.START && startPollCount < START_POLL_LIMIT && unchangedIdleBaseline(observed, statusReason)) {
            // A complete idle reply may precede firmware readiness. Recheck only that same
            // baseline; an unfamiliar record or collecting error keeps the protective exit.
            waitForStart(StartWaitStage.BEFORE_STATUS, START_POLL_INTERVAL_MS, observed) {
                beginRound(Purpose.START)
            }
            return
        }
        if (observed.status.errorCode != 0 && !(purpose == Purpose.PREFLIGHT && chargingRecovery != null)) {
            clearAssociation()
            review(CaptureControlIssue.DEVICE_ERROR, observed)
            return
        }
        when (purpose) {
            Purpose.PREFLIGHT -> {
                val requested = intent ?: return
                intent = null
                if (!allowedBaseline(observed, requested.allowedExisting)) {
                    if (state.phase != CaptureControlPhase.STORAGE_ERROR) review(CaptureControlIssue.EXISTING_RECORDS, observed)
                    return
                }
                // A second owner of this journal is unsupported. Recheck before issuing a command.
                val existing = try { store.readPending() } catch (_: Exception) { storageFailure(); return }
                if (existing != null) { session = existing; publishRestored(); return }
                val saved = save { store.requestStart(requested.preparation, requested.atMs, requested.zone,
                    DeviceStartBaseline(observed.status, observed.records, observed.statusReceivedAtMs, chargingRecovery)) } ?: return
                baseline = observed
                startPollCount = 0
                check(saved.phase == FreeLivingSessionPhase.START_REQUESTED)
                waitForStart(StartWaitStage.BEFORE_START, START_SETTLE_DELAY_MS) {
                    if (chargingRecovery != null && (clock.nowElapsedMs() > (chargingRecoveryDeadline ?: Long.MIN_VALUE) ||
                            runCatching { chargingRecovery.copy(checkedAtMs = clock.nowEpochMs()).validate(observed.status, observed.statusReceivedAtMs) }.isFailure)) {
                        invalidateRound(CaptureControlIssue.INVALID_OBSERVATION)
                        return@waitForStart
                    }
                    if (send(port::start)) {
                        // This delay starts at local enqueue, not at confirmed device execution.
                        waitForStart(StartWaitStage.BEFORE_STATUS, START_FIRST_POLL_DELAY_MS) {
                            beginRound(Purpose.START)
                        }
                    }
                }
            }
            Purpose.START -> confirmStart(observed)
            Purpose.STOP -> confirmStop(observed)
            Purpose.INSPECT -> recoverAssociation(observed)
            Purpose.ARCHIVE_START -> error("Handled before capture confirmation")
        }
    }

    private fun unchangedIdleBaseline(observed: HealthRecordObservation, reason: Int?): Boolean {
        val before = baseline ?: return false
        if (session?.startBaseline?.chargingRecoveryEvidence != null && observed.status.errorCode != 0 &&
            !ChargingStartCompatibility.isCandidate(observed.status, reason)) return false
        return !observed.status.collecting && observed.address == before.address &&
            observed.connectionGeneration == before.connectionGeneration &&
            observed.status.copy(errorCode = 0) == before.status.copy(errorCode = 0) &&
            observed.records.size == before.records.size && observed.records.toSet() == before.records.toSet()
    }

    private fun waitForStart(stage: StartWaitStage, delayMs: Long,
        observed: HealthRecordObservation? = baseline, action: () -> Unit) {
        val current = session ?: return
        val connection = generation ?: return
        val waiting = StartWait(++operation, connection, current.sessionId, stage)
        startWait = waiting
        publish(CaptureControlPhase.STARTING, observed = observed)
        try {
            schedule(delayMs) {
                dispatch callback@{
                    if (startWait != waiting || generation != waiting.generation || session?.sessionId != waiting.sessionId) return@callback
                    // A durable request remains the prerequisite even if storage changed while waiting.
                    val durable = try { store.readPending() } catch (_: Exception) { storageFailure(); return@callback }
                    if (durable != session || durable?.phase != FreeLivingSessionPhase.START_REQUESTED) {
                        clearAssociation()
                        readJournal()
                        return@callback
                    }
                    startWait = null
                    action()
                }
            }
        } catch (_: Exception) {
            invalidateRound(CaptureControlIssue.COMMAND_NOT_ACCEPTED)
        }
    }

    private fun confirmStart(observed: HealthRecordObservation) {
        val before = baseline
        val current = session ?: return
        val candidate = observed.records.singleOrNull { record -> before?.let {
            FreeLivingSessionStore.isDistinctStartRecord(DeviceStartBaseline(it.status, it.records, it.statusReceivedAtMs), record)
        } == true }
        if (before == null || before.connectionGeneration != generation || !observed.status.collecting || candidate == null ||
            candidate.sessionId == 0 ||
            candidate.sessionId != observed.status.sessionId || (candidate.unixMs == 0L && candidate.uptimeMs == 0L) ||
            candidate.bytes < observed.status.bytes || candidate.records < observed.status.records ||
            !onlyKnownRecords(observed, candidate)) {
            review(CaptureControlIssue.RECORD_ORIGIN_UNCERTAIN, observed)
            return
        }
        if (save { store.confirmStart(current.sessionId, observed.address, observed.status, observed.statusReceivedAtMs,
                recordEvidence = DeviceRecordEvidence(candidate, observed.status, observed.statusReceivedAtMs)) } == null) return
        ownership = identity(candidate)
        lastRecord = candidate
        lastStatus = observed.status
        publish(CaptureControlPhase.COLLECTING, observed = observed)
    }

    private fun confirmStop(observed: HealthRecordObservation) {
        val current = session ?: return
        val candidate = observed.records.singleOrNull { it.sessionId == current.deviceSessionId }
        val previous = lastRecord
        if (observed.status.collecting) { review(CaptureControlIssue.UNEXPECTED_DEVICE_STATE, observed); return }
        if (ownership == null || candidate == null || identity(candidate) != ownership ||
            observed.status.sessionId != candidate.sessionId || previous == null ||
            statusRegressed(observed.status) ||
            candidate.bytes < previous.bytes || candidate.records < previous.records ||
            candidate.bytes != observed.status.bytes || candidate.records != observed.status.records ||
            !onlyKnownRecords(observed, candidate)) {
            rejectAssociation(CaptureControlIssue.RECORD_CHANGED, observed)
            return
        }
        if (save { store.confirmStop(current.sessionId, observed.address, observed.status, observed.statusReceivedAtMs,
                recordEvidence = DeviceRecordEvidence(candidate, observed.status, observed.statusReceivedAtMs)) } == null) return
        lastRecord = candidate
        lastStatus = observed.status
        publish(CaptureControlPhase.AWAITING_REFERENCE, observed = observed)
    }

    private fun allowedBaseline(observed: HealthRecordObservation, authorization: ExistingRecordAuthorization?): Boolean {
        if (observed.status.collecting) return false
        if (observed.records.isEmpty()) return observed.status.bytes == 0L && observed.status.records == 0L
        if (observed.records.none { it.sessionId == observed.status.sessionId && it.bytes == observed.status.bytes &&
                it.records == observed.status.records }) return false
        if (authorization?.ringAddress?.uppercase(Locale.ROOT) == observed.address && authorization.status == observed.status &&
            authorization.records.toSet() == observed.records.toSet()) return true
        return try { store.hasPreservedDeviceRecords(observed.address, observed.records) }
        catch (_: Exception) { storageFailure(); false }
    }

    private fun onlyKnownRecords(observed: HealthRecordObservation, candidate: HealthMessage.ListItem): Boolean {
        val known = session?.startBaseline?.records ?: return false
        return observed.records.all { it == candidate || it in known }
    }

    private fun recoverAssociation(observed: HealthRecordObservation) {
        val current = session ?: return
        val evidence = current.deviceRecordEvidence
        // Zero UNIX means the device clock is unknown. An uninterrupted issuing connection can
        // prove a new ID plus uptime, but uptime alone cannot disambiguate another boot here.
        if (evidence == null || current.deviceAssociationInvalidated || evidence.record.unixMs == 0L) {
            review(CaptureControlIssue.RECOVERY_REQUIRES_REVIEW, observed)
            return
        }
        val candidate = observed.records.singleOrNull { it.sessionId == evidence.record.sessionId }
        if (candidate == null || !FreeLivingSessionStore.sameDeviceRecord(candidate, evidence.record) ||
            observed.status.sessionId != candidate.sessionId || !onlyKnownRecords(observed, candidate) ||
            candidate.bytes < evidence.record.bytes || candidate.records < evidence.record.records ||
            observed.status.bytes < evidence.status.bytes || observed.status.records < evidence.status.records ||
            candidate.bytes < observed.status.bytes || candidate.records < observed.status.records) {
            rejectAssociation(CaptureControlIssue.RECORD_CHANGED, observed)
            return
        }
        lastRecord = evidence.record
        lastStatus = evidence.status
        if (current.phase == FreeLivingSessionPhase.STOP_REQUESTED) {
            ownership = identity(candidate)
            confirmStop(observed)
        } else if (current.phase == FreeLivingSessionPhase.COLLECTING && observed.status.collecting) {
            if (save { store.updateDeviceEvidence(current.sessionId,
                    DeviceRecordEvidence(candidate, observed.status, observed.statusReceivedAtMs)) } == null) return
            ownership = identity(candidate)
            lastRecord = candidate
            lastStatus = observed.status
            publish(CaptureControlPhase.COLLECTING, observed = observed)
        } else {
            // An unsolicited stop has no user stop request. Preserve the record and unknown end.
            review(CaptureControlIssue.UNEXPECTED_DEVICE_STATE, observed)
        }
    }

    private fun rejectAssociation(issue: CaptureControlIssue, observed: HealthRecordObservation? = null) {
        val current = session
        clearAssociation()
        if (current?.deviceRecordEvidence != null && save { store.invalidateDeviceAssociation(current.sessionId) } == null) return
        review(issue, observed)
    }

    private fun connectedTo(expected: String?): Boolean {
        if (generation == null) { review(CaptureControlIssue.NOT_CONNECTED); return false }
        if (expected == null || address != expected.uppercase(Locale.ROOT)) { review(CaptureControlIssue.WRONG_RING); return false }
        if (tainted) { review(CaptureControlIssue.RECONNECT_REQUIRED); return false }
        return true
    }

    private fun send(command: () -> Boolean): Boolean {
        val accepted = try { command() } catch (_: Exception) { false }
        if (!accepted) invalidateRound(CaptureControlIssue.COMMAND_NOT_ACCEPTED)
        return accepted
    }

    private fun invalidateRound(issue: CaptureControlIssue) {
        // A failed/timed-out round can still produce untagged replies. Start a fresh connection
        // before another query; a new local query number cannot disambiguate those old replies.
        tainted = true
        clearAssociation()
        review(issue)
    }

    private fun clearAssociation() {
        chargingRecoveryDeadline = null
        startWait = null
        startPollCount = 0
        round = null
        intent = null
        baseline = null
        ownership = null
        lastRecord = null
        lastStatus = null
        archiveReason = null
    }

    private fun ensureRestored(): Boolean {
        if (!restored) readJournal()
        return restored
    }

    private fun readJournal() {
        try {
            session = store.readPending()
            restored = true
            publishRestored()
        } catch (_: Exception) {
            restored = false
            storageFailure()
        }
    }

    private fun publishRestored() = when (session?.phase) {
        null -> publish(CaptureControlPhase.IDLE)
        FreeLivingSessionPhase.AWAITING_REFERENCE -> publish(CaptureControlPhase.AWAITING_REFERENCE)
        else -> review(CaptureControlIssue.RECOVERY_REQUIRES_REVIEW)
    }

    private fun save(write: () -> FreeLivingSession): FreeLivingSession? = try {
        write().also { session = it }
    } catch (_: Exception) {
        storageFailure()
        null
    }

    private fun storageFailure() {
        startWait = null
        round = null
        intent = null
        archiveReason = null
        publish(CaptureControlPhase.STORAGE_ERROR, CaptureControlIssue.STORAGE_FAILURE)
    }

    private fun review(issue: CaptureControlIssue, observed: HealthRecordObservation? = null) {
        startWait = null
        round = null
        intent = null
        archiveReason = null
        publish(CaptureControlPhase.NEEDS_REVIEW, issue, observed)
    }

    private fun publish(phase: CaptureControlPhase, issue: CaptureControlIssue? = null, observed: HealthRecordObservation? = null) {
        state = CaptureControlState(phase, session, issue, observed, round?.id, settling = startWait != null)
        onState(state)
    }

    private fun dispatch(action: () -> Unit) {
        if (closed) return
        events.addLast(action)
        if (draining) return
        draining = true
        try {
            while (!closed && events.isNotEmpty()) events.removeFirst().invoke()
        } finally {
            events.clear()
            draining = false
        }
    }

    private fun identity(record: HealthMessage.ListItem) = RecordIdentity(record.sessionId, record.uptimeMs, record.unixMs)
    // Compare STATUS with STATUS, not with the later LIST whose counters can already be larger.
    private fun statusRegressed(status: HealthMessage.Status) = lastStatus?.let {
        status.bytes < it.bytes || status.records < it.records
    } == true
    private fun validStatus(status: HealthMessage.Status) =
        status.sessionId in 0..65535 && status.bytes in 0..0xFFFF_FFFFL && status.records in 0..0xFFFF_FFFFL
    private fun validRecord(record: HealthMessage.ListItem) =
        record.sessionId in 0..65535 && record.bytes in 0..0xFFFF_FFFFL && record.records in 0..0xFFFF_FFFFL &&
            record.uptimeMs in 0..0xFFFF_FFFFL && record.unixMs >= 0

    companion object {
        const val QUERY_TIMEOUT_MS = 30_000L
        const val START_SETTLE_DELAY_MS = 500L
        const val START_FIRST_POLL_DELAY_MS = 1_000L
        const val START_POLL_INTERVAL_MS = 1_500L
        const val START_POLL_LIMIT = 3
    }
}
