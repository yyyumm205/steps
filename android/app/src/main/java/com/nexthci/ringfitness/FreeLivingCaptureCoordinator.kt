package com.nexthci.ringfitness

import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID

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
    val connectionGeneration: Long? = null,
    val unknownTimeStartEvidence: UnknownTimeStartEvidence? = null,
)

data class CaptureControlState(
    val phase: CaptureControlPhase = CaptureControlPhase.IDLE,
    val session: FreeLivingSession? = null,
    val issue: CaptureControlIssue? = null,
    val observation: HealthRecordObservation? = null,
    val timeoutOperationId: Long? = null,
    val settling: Boolean = false,
    val unconfirmedStartStopCandidate: HealthRecordObservation? = null,
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

    private enum class Purpose { PREFLIGHT, START, RECOVER_START, STOP, INSPECT, ARCHIVE_START }
    private data class Round(
        val id: Long,
        val generation: Long,
        val purpose: Purpose,
        val startedAtElapsedMs: Long,
        var status: SensorPacket.Health? = null,
        val records: MutableList<HealthMessage.ListItem> = mutableListOf(),
        var batteryRequestedAtMs: Long? = null,
        var observation: HealthRecordObservation? = null,
        var latestStopStatus: SensorPacket.Health? = null,
    )
    private data class StartIntent(val preparation: PreparationSnapshot, val atMs: Long, val zone: String,
        val allowedExisting: ExistingRecordAuthorization?, val activity: SessionActivity)
    private data class RecordIdentity(val sessionId: Int, val uptimeMs: Long, val unixMs: Long)
    private enum class StartWaitStage { BEFORE_START, BEFORE_STATUS }
    private data class StartWait(val id: Long, val generation: Long, val sessionId: String, val stage: StartWaitStage)
    private data class StopWait(val id: Long, val generation: Long, val sessionId: String)

    private val events = ArrayDeque<() -> Unit>()
    private val commandOwnerId = UUID.randomUUID().toString()
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
    private var stopWait: StopWait? = null
    private var stopPollCount = 0
    private var stopListRetryCount = 0
    private var firstStoppedObservation: HealthRecordObservation? = null
    private var closed = false
    private var chargingRecoveryDeadline: Long? = null
    private var startCommandIssued = false
    private var stopCommandIssued = false
    private var unconfirmedStartStopCandidate: HealthRecordObservation? = null

    /** Initialization only; repeated calls cannot replace an active untagged response round. */
    fun restore() = dispatch { if (!restored) readJournal() }

    /** The sole owner calls this after reference/file persistence, when no response is pending. */
    fun refresh() = dispatch {
        if (round == null) {
            if (startWait != null || stopWait != null) clearAssociation()
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

    fun requestStart(preparation: PreparationSnapshot, allowedExisting: ExistingRecordAuthorization? = null,
        activity: SessionActivity = SessionActivity.FREE_LIVING) = dispatch {
        if (!ensureRestored() || round != null || intent != null || session != null) return@dispatch
        if (!connectedTo(preparation.ring?.address)) return@dispatch
        intent = StartIntent(preparation, clock.nowEpochMs(), clock.timeZoneId(), allowedExisting, activity)
        beginRound(Purpose.PREFLIGHT)
    }

    fun requestStop() = dispatch {
        if (!ensureRestored() || round != null || startWait != null || stopWait != null) return@dispatch
        val current = session ?: return@dispatch
        if (current.phase == FreeLivingSessionPhase.AWAITING_REFERENCE) return@dispatch
        if (!connectedTo(current.preparation.ring?.address)) return@dispatch
        if (ownership == null || current.deviceAssociationInvalidated) {
            review(CaptureControlIssue.RECOVERY_REQUIRES_REVIEW)
            return@dispatch
        }
        if (current.phase != FreeLivingSessionPhase.COLLECTING ||
            state.phase !in setOf(CaptureControlPhase.COLLECTING, CaptureControlPhase.STORAGE_ERROR)) return@dispatch
        val connection = requireNotNull(generation)
        if (save { store.requestStop(current.sessionId, clock.nowEpochMs(), commandOwnerId, connection) } == null) return@dispatch
        publish(CaptureControlPhase.STOPPING)
        stopCommandIssued = true
        val accepted = try { port.stop() } catch (_: Exception) { false }
        if (!accepted) {
            stopCommandIssued = false
            if (save { store.cancelStopRequest(current.sessionId) } == null) return@dispatch
            publish(CaptureControlPhase.COLLECTING, CaptureControlIssue.COMMAND_NOT_ACCEPTED)
            return@dispatch
        }
        if (save { store.confirmStopCommandAccepted(current.sessionId, clock.nowEpochMs()) } == null) return@dispatch
        stopPollCount = 0
        stopListRetryCount = 0
        firstStoppedObservation = null
        waitForStop(STOP_FIRST_POLL_DELAY_MS)
    }

    /** Recovery only queries. Stable collecting/reference states keep their normal action. */
    fun reconcile() = dispatch {
        if (!ensureRestored() || round != null || startWait != null || stopWait != null) return@dispatch
        if (state.phase == CaptureControlPhase.COLLECTING || state.phase == CaptureControlPhase.AWAITING_REFERENCE) return@dispatch
        val current = session ?: return@dispatch
        if (!connectedTo(current.preparation.ring?.address)) return@dispatch
        val sameConnection = baseline?.connectionGeneration == generation
        val purpose = when {
            current.phase == FreeLivingSessionPhase.START_REQUESTED && current.startCommandDispatch?.let {
                startCommandIssued && it.ownerId == commandOwnerId && it.connectionGeneration == generation
            } == true -> {
                val durable = requireNotNull(current.startBaseline)
                baseline = HealthRecordObservation(requireNotNull(address), requireNotNull(generation), durable.status,
                    durable.observedAtMs, durable.records)
                startCommandIssued = true
                Purpose.RECOVER_START
            }
            current.phase == FreeLivingSessionPhase.START_REQUESTED -> Purpose.INSPECT
            sameConnection && ownership != null && current.phase == FreeLivingSessionPhase.STOP_REQUESTED -> Purpose.STOP
            else -> Purpose.INSPECT
        }
        beginRound(purpose)
    }

    /** An explicit user action always collects a new round; cached observations cannot release data. */
    fun archiveUnconfirmedStart(reason: String) = dispatch {
        if (!ensureRestored() || round != null || startWait != null || stopWait != null) return@dispatch
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
        val pendingStop = session?.takeIf { it.phase == FreeLivingSessionPhase.STOP_REQUESTED }
        if (packet.message is HealthMessage.Status && packet.message.collecting &&
            pendingStop?.deviceRecordEvidence?.status?.collecting == false) {
            // Also check duplicate replies inside a query round before they can be ignored.
            rejectAssociation(CaptureControlIssue.RECORD_CHANGED)
            return@dispatch
        }
        if (stopWait != null) {
            // A spontaneous reply cannot confirm STOP. Contradictory identity/counters still
            // revoke the pending query immediately; confirmation needs a complete fresh round.
            when (val message = packet.message) {
                is HealthMessage.Status -> when {
                    !validStatus(message) || packet.receivedEpochMs <= 0 || message.errorCode != 0 ->
                        invalidateRound(CaptureControlIssue.DEVICE_ERROR)
                    message.sessionId != ownership?.sessionId || statusRegressed(message) ->
                        rejectAssociation(CaptureControlIssue.RECORD_CHANGED)
                    else -> {
                        val current = session ?: return@dispatch
                        val previous = current.deviceRecordEvidence ?: return@dispatch
                        if (message != previous.status) {
                            // Keep the actual STATUS, including an early stopped reply, without
                            // completing STOP. A later full STATUS/LIST must retain this high-water
                            // mark and the same record before the reference page can be shown.
                            if (save { store.updateDeviceEvidence(current.sessionId,
                                    previous.copy(status = message, observedAtMs = packet.receivedEpochMs)) } == null) return@dispatch
                            lastStatus = message
                        }
                    }
                }
                is HealthMessage.ListItem -> {
                    val previous = lastRecord
                    val same = previous != null && identity(message) == ownership &&
                        message.bytes >= previous.bytes && message.records >= previous.records
                    val known = if (message.sessionId == ownership?.sessionId) same
                        else message in session?.startBaseline?.records.orEmpty()
                    if (!validRecord(message) || packet.receivedEpochMs <= 0 || !known) {
                        rejectAssociation(CaptureControlIssue.RECORD_CHANGED)
                    } else if (same && message != previous) {
                        val current = session ?: return@dispatch
                        val evidence = current.deviceRecordEvidence ?: return@dispatch
                        if (save { store.updateDeviceEvidence(current.sessionId,
                                evidence.copy(record = message, observedAtMs = packet.receivedEpochMs)) } == null) return@dispatch
                        lastRecord = message
                    }
                }
                else -> Unit
            }
            return@dispatch
        }
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
                (!validStatus(status) || packet.receivedEpochMs <= 0 || status.errorCode != 0 ||
                    status.sessionId != ownership?.sessionId || statusRegressed(status))) {
                rejectAssociation(CaptureControlIssue.UNEXPECTED_DEVICE_STATE)
            } else if (watchingOwnedCollection && !status.collecting) {
                // STATUS alone cannot finalize a record. Query a complete STATUS/LIST pair and
                // retain the proved association while the ring settles its Flash tail.
                beginRound(Purpose.INSPECT)
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
                    if (session?.phase == FreeLivingSessionPhase.STOP_REQUESTED &&
                        active.purpose in setOf(Purpose.STOP, Purpose.INSPECT)) {
                        observeRepeatedStopStatus(active, packet)
                        return@dispatch
                    }
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
                if (active.purpose == Purpose.STOP && ownership != null) {
                    if (message.errorCode != 0) {
                        invalidateRound(CaptureControlIssue.DEVICE_ERROR)
                        return@dispatch
                    }
                    if (message.sessionId != ownership?.sessionId || statusRegressed(message)) {
                        rejectAssociation(CaptureControlIssue.RECORD_CHANGED)
                        return@dispatch
                    }
                    val current = session ?: return@dispatch
                    val evidence = current.deviceRecordEvidence ?: return@dispatch
                    // Ownership is already established on this connection. Persist the first
                    // STATUS before waiting for LIST so reopening retains every seen counter.
                    // The full round is still required to confirm STOP.
                    if (message != evidence.status && save { store.updateDeviceEvidence(current.sessionId,
                            evidence.copy(status = message, observedAtMs = packet.receivedEpochMs)) } == null) return@dispatch
                    lastStatus = message
                    if (firstStoppedObservation == null) {
                        round = null
                        stopPollCount++
                        val snapshot = HealthRecordObservation(
                            address = requireNotNull(address),
                            connectionGeneration = connectionGeneration,
                            status = message,
                            statusReceivedAtMs = packet.receivedEpochMs,
                            records = current.startBaseline?.records.orEmpty()
                                .filter { it.sessionId != evidence.record.sessionId } + evidence.record,
                        )
                        if (message.collecting) {
                            waitForStop(STOP_POLL_INTERVAL_MS, snapshot)
                        } else {
                            firstStoppedObservation = snapshot
                            stopListRetryCount = 0
                            waitForStop(STOP_FLASH_SETTLE_DELAY_MS, snapshot)
                        }
                        return@dispatch
                    }
                    if (message.collecting) {
                        rejectAssociation(CaptureControlIssue.RECORD_CHANGED)
                        return@dispatch
                    }
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
                if (active.latestStopStatus != null) completeUpdatedStopRound(active, observation)
                else completeRound(active.purpose, observation, statusPacket.statusErrorReason)
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

    private fun observeRepeatedStopStatus(active: Round, packet: SensorPacket.Health) {
        val current = session ?: return
        val message = packet.message as HealthMessage.Status
        val previous = (active.latestStopStatus ?: requireNotNull(active.status)).message as HealthMessage.Status
        if (!validStatus(message) || packet.receivedEpochMs <= 0 || message.errorCode != 0 || previous.errorCode != 0) {
            invalidateRound(CaptureControlIssue.DEVICE_ERROR)
            return
        }
        if (message.sessionId != current.deviceSessionId || message.sessionId != previous.sessionId ||
            message.bytes < previous.bytes || message.records < previous.records ||
            (!previous.collecting && message.collecting)) {
            rejectAssociation(CaptureControlIssue.RECORD_CHANGED)
            return
        }
        if (message == previous) return
        active.latestStopStatus = packet
        if (active.purpose == Purpose.STOP && ownership != null) {
            val evidence = current.deviceRecordEvidence ?: return
            if (statusRegressed(message)) { rejectAssociation(CaptureControlIssue.RECORD_CHANGED); return }
            if (save { store.updateDeviceEvidence(current.sessionId,
                    evidence.copy(status = message, observedAtMs = packet.receivedEpochMs)) } == null) return
            lastStatus = message
        }
        // Finish consuming this LIST before querying again. During INSPECT the returned
        // fingerprint must prove the device record before its new STATUS can be persisted.
    }

    private fun completeUpdatedStopRound(active: Round, observed: HealthRecordObservation) {
        val current = session ?: return
        val evidence = current.deviceRecordEvidence
        val sameOwnedConnection = ownership != null && active.generation == generation &&
            evidence?.record?.let(::identity) == ownership
        if (evidence == null || current.deviceAssociationInvalidated ||
            (active.purpose == Purpose.INSPECT && evidence.record.unixMs == 0L && !sameOwnedConnection)) {
            review(CaptureControlIssue.RECOVERY_REQUIRES_REVIEW, observed)
            return
        }
        val packet = requireNotNull(active.latestStopStatus)
        val latest = packet.message as HealthMessage.Status
        val candidate = observed.records.singleOrNull { it.sessionId == evidence.record.sessionId }
        if (candidate == null || !FreeLivingSessionStore.sameDeviceRecord(candidate, evidence.record) ||
            latest.sessionId != candidate.sessionId || observed.status.sessionId != candidate.sessionId ||
            !onlyKnownRecords(observed, candidate) || candidate.bytes < evidence.record.bytes ||
            candidate.records < evidence.record.records || candidate.bytes < observed.status.bytes ||
            candidate.records < observed.status.records || latest.bytes < evidence.status.bytes ||
            latest.records < evidence.status.records || (!evidence.status.collecting && latest.collecting) ||
            (active.purpose == Purpose.INSPECT && (observed.status.bytes < evidence.status.bytes ||
                observed.status.records < evidence.status.records))) {
            rejectAssociation(CaptureControlIssue.RECORD_CHANGED, observed)
            return
        }
        val latestObserved = observed.copy(status = latest, statusReceivedAtMs = packet.receivedEpochMs)
        if (save { store.updateDeviceEvidence(current.sessionId,
                DeviceRecordEvidence(candidate, latest, packet.receivedEpochMs)) } == null) return
        ownership = identity(candidate)
        lastRecord = candidate
        lastStatus = latest
        stopPollCount++
        if (!latest.collecting && candidate.bytes == latest.bytes && candidate.records == latest.records) {
            firstStoppedObservation = latestObserved
            stopListRetryCount = 0
            waitForStop(STOP_FLASH_SETTLE_DELAY_MS, latestObserved)
        } else {
            waitForStop(STOP_POLL_INTERVAL_MS, latestObserved)
        }
    }

    /** Host schedules this using elapsed time, never by comparing wall-clock timestamps. */
    fun onTimeout(operationId: Long) = dispatch {
        if (round?.id != operationId) return@dispatch
        invalidateRound(CaptureControlIssue.QUERY_TIMEOUT)
    }

    private fun beginRound(purpose: Purpose) {
        val connectedGeneration = generation ?: return
        if (tainted) { review(CaptureControlIssue.RECONNECT_REQUIRED); return }
        if (purpose == Purpose.START || purpose == Purpose.RECOVER_START) startPollCount++
        round = Round(++operation, connectedGeneration, purpose, clock.nowElapsedMs())
        publish(when (purpose) {
            Purpose.PREFLIGHT, Purpose.INSPECT, Purpose.ARCHIVE_START -> CaptureControlPhase.CHECKING
            Purpose.START, Purpose.RECOVER_START -> CaptureControlPhase.STARTING
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
        if (purpose in setOf(Purpose.START, Purpose.RECOVER_START) && startPollCount < START_POLL_LIMIT &&
            unchangedIdleBaseline(observed, statusReason)) {
            // A complete idle reply may precede firmware readiness. Recheck only that same
            // baseline; an unfamiliar record or collecting error keeps the protective exit.
            waitForStart(StartWaitStage.BEFORE_STATUS, START_POLL_INTERVAL_MS, observed) {
                beginRound(purpose)
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
                if (existing != null) {
                    session = existing
                    val sameRing = existing.preparation.ring?.address?.uppercase(Locale.ROOT) == observed.address
                    if (sameRing && existing.phase == FreeLivingSessionPhase.START_REQUESTED &&
                        existing.startCommandDispatch == null) {
                        // A durable request can survive a directory-sync failure even though START
                        // was never sent. A fresh, unchanged read proves that this was an empty
                        // attempt and lets the user return to idle without weakening data recovery.
                        recoverAssociation(observed)
                    } else {
                        publishRestored()
                    }
                    return
                }
                val saved = save { store.requestStart(requested.preparation, requested.atMs, requested.zone,
                    DeviceStartBaseline(observed.status, observed.records, observed.statusReceivedAtMs, chargingRecovery,
                        requested.allowedExisting?.unknownTimeStartEvidence),
                    requested.activity) } ?: return
                baseline = observed
                startPollCount = 0
                check(saved.phase == FreeLivingSessionPhase.START_REQUESTED)
                waitForStart(StartWaitStage.BEFORE_START, START_SETTLE_DELAY_MS) {
                    if (chargingRecovery != null && (clock.nowElapsedMs() > (chargingRecoveryDeadline ?: Long.MIN_VALUE) ||
                            runCatching { chargingRecovery.copy(checkedAtMs = clock.nowEpochMs()).validate(observed.status, observed.statusReceivedAtMs) }.isFailure)) {
                        invalidateRound(CaptureControlIssue.INVALID_OBSERVATION)
                        return@waitForStart
                    }
                    val current = session ?: return@waitForStart
                    val connection = requireNotNull(generation)
                    if (save { store.prepareStartCommand(current.sessionId, clock.nowEpochMs(),
                            commandOwnerId, connection) } == null) return@waitForStart
                    // Persist before crossing the BLE side-effect boundary. A synchronous callback
                    // is dispatched only after this transition finishes and sees the same evidence.
                    startCommandIssued = true
                    val accepted = try { port.start() } catch (_: Exception) { false }
                    if (!accepted) {
                        startCommandIssued = false
                        if (save { store.cancelPreparedStartCommand(current.sessionId) } == null) return@waitForStart
                        invalidateRound(CaptureControlIssue.COMMAND_NOT_ACCEPTED)
                        return@waitForStart
                    }
                    if (save { store.confirmStartCommandAccepted(current.sessionId, clock.nowEpochMs()) } == null) return@waitForStart
                    // This delay starts at local enqueue, not at confirmed device execution.
                    waitForStart(StartWaitStage.BEFORE_STATUS, START_FIRST_POLL_DELAY_MS) {
                        beginRound(Purpose.START)
                    }
                }
            }
            Purpose.START -> confirmStart(observed)
            Purpose.RECOVER_START -> confirmStart(observed)
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
            FreeLivingSessionStore.isDistinctStartRecord(requireNotNull(current.startBaseline), record)
        } == true }
        if (before == null || before.connectionGeneration != generation || !observed.status.collecting || candidate == null ||
            candidate.sessionId == 0 ||
            candidate.sessionId != observed.status.sessionId || (candidate.unixMs == 0L && candidate.uptimeMs == 0L) ||
            candidate.bytes < observed.status.bytes || candidate.records < observed.status.records ||
            !onlyKnownRecords(observed, candidate)) {
            val pendingRecord = observed.records.singleOrNull { it.sessionId == observed.status.sessionId }
            if (startCommandIssued && before?.connectionGeneration == generation && observed.status.collecting &&
                observed.status.errorCode == 0 && pendingRecord != null &&
                pendingRecord.bytes >= observed.status.bytes &&
                pendingRecord.records >= observed.status.records && onlyKnownRecords(observed, pendingRecord)) {
                // The record is not strong enough to become research data, but the same-connection
                // idle -> START -> collecting transition is enough to offer a protective STOP and
                // raw-data preservation path instead of leaving the ring collecting indefinitely.
                unconfirmedStartStopCandidate = observed
            }
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
        val previous = lastRecord
        val owner = ownership
        if (owner == null || previous == null || observed.status.sessionId != owner.sessionId ||
            statusRegressed(observed.status)) {
            rejectAssociation(CaptureControlIssue.RECORD_CHANGED, observed)
            return
        }
        val candidate = observed.records.singleOrNull { it.sessionId == current.deviceSessionId }
        val knownWithoutTarget = current.startBaseline?.records.orEmpty()
            .filter { it.sessionId != current.deviceSessionId }.toSet()
        if (observed.records.any { it.sessionId != current.deviceSessionId && it !in knownWithoutTarget }) {
            rejectAssociation(CaptureControlIssue.RECORD_CHANGED, observed)
            return
        }
        if (candidate == null) {
            retryStopList(observed)
            return
        }
        if (identity(candidate) != owner || candidate.bytes < previous.bytes || candidate.records < previous.records) {
            rejectAssociation(CaptureControlIssue.RECORD_CHANGED, observed)
            return
        }
        if (observed.status.collecting) {
            if (firstStoppedObservation != null || candidate.bytes < observed.status.bytes ||
                candidate.records < observed.status.records) {
                rejectAssociation(CaptureControlIssue.RECORD_CHANGED, observed)
                return
            }
            if (save { store.updateDeviceEvidence(current.sessionId,
                    DeviceRecordEvidence(candidate, observed.status, observed.statusReceivedAtMs)) } == null) return
            lastRecord = candidate
            lastStatus = observed.status
            stopPollCount++
            waitForStop(STOP_POLL_INTERVAL_MS, observed)
            return
        }
        if (candidate.bytes < observed.status.bytes || candidate.records < observed.status.records) {
            retryStopList(observed)
            return
        }
        if (candidate.bytes > observed.status.bytes || candidate.records > observed.status.records) {
            if (save { store.updateDeviceEvidence(current.sessionId,
                    DeviceRecordEvidence(candidate, observed.status, observed.statusReceivedAtMs)) } == null) return
            lastRecord = candidate
            lastStatus = observed.status
            firstStoppedObservation = null
            waitForStop(STOP_POLL_INTERVAL_MS, observed)
            return
        }
        if (firstStoppedObservation == null) {
            if (save { store.updateDeviceEvidence(current.sessionId,
                    DeviceRecordEvidence(candidate, observed.status, observed.statusReceivedAtMs)) } == null) return
            firstStoppedObservation = observed
            stopListRetryCount = 0
            lastRecord = candidate
            lastStatus = observed.status
            waitForStop(STOP_FLASH_SETTLE_DELAY_MS, observed)
            return
        }
        if (save { store.confirmStop(current.sessionId, observed.address, observed.status, observed.statusReceivedAtMs,
                recordEvidence = DeviceRecordEvidence(candidate, observed.status, observed.statusReceivedAtMs)) } == null) return
        lastRecord = candidate
        lastStatus = observed.status
        publish(CaptureControlPhase.AWAITING_REFERENCE, observed = observed)
    }

    private fun retryStopList(observed: HealthRecordObservation) {
        if (stopListRetryCount < STOP_LIST_RETRY_LIMIT) {
            stopListRetryCount++
            waitForStop(STOP_LIST_RETRY_INTERVAL_MS, observed)
        } else {
            review(CaptureControlIssue.UNEXPECTED_DEVICE_STATE, observed)
        }
    }

    private fun waitForStop(delayMs: Long, observed: HealthRecordObservation? = null) {
        val current = session ?: return
        val connection = generation ?: return
        val waiting = StopWait(++operation, connection, current.sessionId)
        stopWait = waiting
        publish(CaptureControlPhase.STOPPING, observed = observed)
        try {
            schedule(delayMs) {
                dispatch callback@{
                    if (stopWait != waiting || generation != waiting.generation || session?.sessionId != waiting.sessionId) return@callback
                    val durable = try { store.readPending() } catch (_: Exception) { storageFailure(); return@callback }
                    if (durable != session || durable?.phase != FreeLivingSessionPhase.STOP_REQUESTED) {
                        clearAssociation()
                        readJournal()
                        return@callback
                    }
                    stopWait = null
                    if (firstStoppedObservation == null) beginRound(Purpose.STOP)
                    else beginStopListRound()
                }
            }
        } catch (_: Exception) {
            invalidateRound(CaptureControlIssue.COMMAND_NOT_ACCEPTED)
        }
    }

    private fun beginStopListRound() {
        val connection = generation ?: return
        val stopped = firstStoppedObservation ?: return
        val latest = session?.deviceRecordEvidence
        if (tainted) { review(CaptureControlIssue.RECONNECT_REQUIRED); return }
        round = Round(
            id = ++operation,
            generation = connection,
            purpose = Purpose.STOP,
            startedAtElapsedMs = clock.nowElapsedMs(),
            status = SensorPacket.Health(latest?.status ?: stopped.status,
                latest?.observedAtMs ?: stopped.statusReceivedAtMs),
        )
        publish(CaptureControlPhase.STOPPING)
        send(port::queryRecords)
    }

    private fun allowedBaseline(observed: HealthRecordObservation, authorization: ExistingRecordAuthorization?): Boolean {
        if (observed.status.collecting) return false
        if (observed.records.isEmpty()) return observed.status.bytes == 0L && observed.status.records == 0L
        if (observed.records.none { it.sessionId == observed.status.sessionId && it.bytes == observed.status.bytes &&
                it.records == observed.status.records }) return false
        if (authorization?.ringAddress?.uppercase(Locale.ROOT) == observed.address && authorization.status == observed.status &&
            authorization.records.toSet() == observed.records.toSet()) {
            val proof = authorization.unknownTimeStartEvidence
            if (proof != null) {
                if (authorization.connectionGeneration != generation || proof.connectionGeneration != generation ||
                    proof.clock.ringAddress != observed.address) return false
                return runCatching { proof.validate(DeviceStartBaseline(observed.status, observed.records,
                    observed.statusReceivedAtMs)) }.isSuccess
            }
            return authorization.connectionGeneration == null || authorization.connectionGeneration == generation
        }
        return try { store.hasPreservedDeviceRecords(observed.address, observed.records) }
        catch (_: Exception) { storageFailure(); false }
    }

    private fun onlyKnownRecords(observed: HealthRecordObservation, candidate: HealthMessage.ListItem): Boolean {
        val known = session?.startBaseline?.records ?: return false
        return observed.records.all { it == candidate || it in known }
    }

    private fun recoverAssociation(observed: HealthRecordObservation) {
        val current = session ?: return
        if (current.phase == FreeLivingSessionPhase.START_REQUESTED) {
            val startBaseline = current.startBaseline
            if (current.startCommandDispatch == null && startBaseline != null &&
                !observed.status.collecting && observed.status.errorCode in setOf(0, -16) &&
                observed.status.copy(errorCode = 0) == startBaseline.status.copy(errorCode = 0) &&
                observed.records.size == startBaseline.records.size &&
                observed.records.toSet() == startBaseline.records.toSet()) {
                try {
                    store.archiveStartAttempt(current.sessionId, observed, clock.nowEpochMs(),
                        INTERRUPTED_BEFORE_START_REASON)
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
            val candidate = startBaseline?.let { baseline -> observed.records.singleOrNull { record ->
                FreeLivingSessionStore.isDistinctStartRecord(baseline, record)
            } }
            if (observed.status.errorCode == 0 && observed.status.collecting && candidate != null &&
                candidate.sessionId == observed.status.sessionId && candidate.sessionId != 0 &&
                (candidate.unixMs > 0L || candidate.uptimeMs > 0L) &&
                candidate.bytes >= observed.status.bytes && candidate.records >= observed.status.records &&
                onlyKnownRecords(observed, candidate)) {
                // A different connection cannot prove which phone sent START. Preserve the record
                // and offer a controlled STOP, but never promote it to this research session.
                unconfirmedStartStopCandidate = observed
                review(CaptureControlIssue.RECORD_ORIGIN_UNCERTAIN, observed)
            } else {
                review(CaptureControlIssue.RECOVERY_REQUIRES_REVIEW, observed)
            }
            return
        }
        val evidence = current.deviceRecordEvidence
        // Zero UNIX means the device clock is unknown. An uninterrupted issuing connection can
        // prove a new ID plus uptime, but uptime alone cannot disambiguate another boot here.
        val sameOwnedConnection = ownership != null && observed.connectionGeneration == generation &&
            evidence?.record?.let(::identity) == ownership
        if (evidence == null || current.deviceAssociationInvalidated ||
            (evidence.record.unixMs == 0L && !sameOwnedConnection)) {
            review(CaptureControlIssue.RECOVERY_REQUIRES_REVIEW, observed)
            return
        }
        val candidate = observed.records.singleOrNull { it.sessionId == evidence.record.sessionId }
        if (candidate == null || !FreeLivingSessionStore.sameDeviceRecord(candidate, evidence.record) ||
            observed.status.sessionId != candidate.sessionId || !onlyKnownRecords(observed, candidate) ||
            candidate.bytes < evidence.record.bytes || candidate.records < evidence.record.records ||
            observed.status.bytes < evidence.status.bytes || observed.status.records < evidence.status.records ||
            (!evidence.status.collecting && observed.status.collecting) ||
            candidate.bytes < observed.status.bytes || candidate.records < observed.status.records) {
            rejectAssociation(CaptureControlIssue.RECORD_CHANGED, observed)
            return
        }
        lastRecord = evidence.record
        lastStatus = evidence.status
        if (current.phase == FreeLivingSessionPhase.STOP_REQUESTED) {
            ownership = identity(candidate)
            lastRecord = evidence.record
            lastStatus = evidence.status
            val dispatch = current.stopCommandDispatch
            if (current.stopOrigin == StopOrigin.USER_REQUEST && dispatch != null &&
                dispatch.acceptedAtMs == null && observed.status.collecting) {
                val nextEvidence = DeviceRecordEvidence(candidate, observed.status, observed.statusReceivedAtMs)
                if (save { store.updateDeviceEvidence(current.sessionId, nextEvidence) } == null) return
                lastRecord = candidate
                lastStatus = observed.status
                publish(CaptureControlPhase.STOPPING, observed = observed)
                val issuedByThisOwner = stopCommandIssued && dispatch.ownerId == commandOwnerId &&
                    dispatch.connectionGeneration == generation
                if (issuedByThisOwner) {
                    stopPollCount = 0
                    stopListRetryCount = 0
                    firstStoppedObservation = null
                    waitForStop(STOP_FIRST_POLL_DELAY_MS, observed)
                    return
                }
                val connection = requireNotNull(generation)
                if (save { store.prepareRecoveredStopCommand(current.sessionId, clock.nowEpochMs(),
                        commandOwnerId, connection) } == null) return
                stopCommandIssued = true
                val accepted = try { port.stop() } catch (_: Exception) { false }
                if (!accepted) {
                    stopCommandIssued = false
                    review(CaptureControlIssue.COMMAND_NOT_ACCEPTED, observed)
                    return
                }
                if (save { store.confirmStopCommandAccepted(current.sessionId, clock.nowEpochMs()) } == null) return
                stopPollCount = 0
                stopListRetryCount = 0
                firstStoppedObservation = null
                waitForStop(STOP_FIRST_POLL_DELAY_MS, observed)
            } else {
                confirmStop(observed)
            }
        } else if (current.phase == FreeLivingSessionPhase.COLLECTING) {
            val nextEvidence = DeviceRecordEvidence(candidate, observed.status, observed.statusReceivedAtMs)
            if (observed.status.collecting) {
                if (save { store.updateDeviceEvidence(current.sessionId, nextEvidence) } == null) return
            } else {
                // Match the original 0.5.3 recovery behavior: a proved active record that has
                // stopped advances into Flash finalization. No second STOP command is sent.
                if (save { store.beginObservedStopFinalization(current.sessionId,
                        observed.statusReceivedAtMs, nextEvidence) } == null) return
            }
            ownership = identity(candidate)
            lastRecord = candidate
            lastStatus = observed.status
            if (observed.status.collecting) {
                publish(CaptureControlPhase.COLLECTING, observed = observed)
            } else {
                stopPollCount = 0
                stopListRetryCount = 0
                firstStoppedObservation = observed
                waitForStop(STOP_FLASH_SETTLE_DELAY_MS, observed)
            }
        } else {
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
        startCommandIssued = false
        stopCommandIssued = false
        unconfirmedStartStopCandidate = null
        chargingRecoveryDeadline = null
        startWait = null
        startPollCount = 0
        stopWait = null
        stopPollCount = 0
        stopListRetryCount = 0
        firstStoppedObservation = null
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
        FreeLivingSessionPhase.START_REQUESTED -> publish(CaptureControlPhase.STARTING)
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
        stopWait = null
        round = null
        intent = null
        archiveReason = null
        publish(CaptureControlPhase.STORAGE_ERROR, CaptureControlIssue.STORAGE_FAILURE)
    }

    private fun review(issue: CaptureControlIssue, observed: HealthRecordObservation? = null) {
        startWait = null
        stopWait = null
        round = null
        intent = null
        archiveReason = null
        publish(CaptureControlPhase.NEEDS_REVIEW, issue, observed)
    }

    private fun publish(phase: CaptureControlPhase, issue: CaptureControlIssue? = null, observed: HealthRecordObservation? = null) {
        state = CaptureControlState(phase, session, issue, observed, round?.id, settling = startWait != null || stopWait != null,
            unconfirmedStartStopCandidate = unconfirmedStartStopCandidate)
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
        status.bytes < it.bytes || status.records < it.records || (!it.collecting && status.collecting)
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
        const val STOP_FIRST_POLL_DELAY_MS = 500L
        const val STOP_POLL_INTERVAL_MS = 1_000L
        const val STOP_FLASH_SETTLE_DELAY_MS = 5_000L
        const val STOP_LIST_RETRY_INTERVAL_MS = 2_000L
        const val STOP_LIST_RETRY_LIMIT = 30
        const val STOP_RECOVERY_POLL_LIMIT = 30
        private const val INTERRUPTED_BEFORE_START_REASON =
            "App 在发送开始命令前关闭，本次未开始采集"
    }
}
