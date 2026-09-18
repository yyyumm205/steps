package com.nexthci.ringfitness

import java.util.ArrayDeque
import java.util.Locale

/** Acceptance means queued locally, not acknowledged by the ring. No download commands belong here. */
interface HealthControlPort {
    fun queryStatus(): Boolean
    fun queryRecords(): Boolean
    fun start(): Boolean
    fun stop(): Boolean
}

interface CaptureClock {
    fun nowEpochMs(): Long
    fun timeZoneId(): String
}

enum class CaptureControlPhase {
    IDLE, CHECKING, STARTING, COLLECTING, STOPPING, AWAITING_REFERENCE, NEEDS_REVIEW, STORAGE_ERROR,
}

enum class CaptureControlIssue {
    NOT_CONNECTED, WRONG_RING, CONNECTION_LOST, RECONNECT_REQUIRED, QUERY_TIMEOUT,
    COMMAND_NOT_ACCEPTED, DEVICE_ERROR, EXISTING_RECORDS, INVALID_OBSERVATION,
    RECORD_ORIGIN_UNCERTAIN, RECORD_CHANGED, UNEXPECTED_DEVICE_STATE, RECOVERY_REQUIRES_REVIEW,
    STORAGE_FAILURE,
}

data class HealthRecordObservation(
    val address: String,
    val connectionGeneration: Long,
    val status: HealthMessage.Status,
    val statusReceivedAtMs: Long,
    val records: List<HealthMessage.ListItem>,
)

data class CaptureControlState(
    val phase: CaptureControlPhase = CaptureControlPhase.IDLE,
    val session: FreeLivingSession? = null,
    val issue: CaptureControlIssue? = null,
    val observation: HealthRecordObservation? = null,
    val timeoutOperationId: Long? = null,
)

/**
 * T2b.1 software coordinator; it is not wired to an Android service or a real capture entry.
 * A future foreground service must be the sole owner of this coordinator, journal and transport.
 * Call every public method on one serial execution context. Synchronous port/listener callbacks
 * are queued to finish the current transition before processing the next event.
 *
 * STATUS/LIST have no response nonce or boot ID. A local operation ID only guards timeouts.
 * The current conservative policy requires an empty baseline, a different nonzero record ID,
 * and a complete matching LIST on one uninterrupted connection. Its firmware assumptions still
 * need device validation. LIST anchors identify observed candidates; they never supply capture
 * boundaries here. After a disconnect/process restart, T2a lacks durable association evidence,
 * so queries preserve the journal and require review instead of adopting a same-ID recording.
 */
class FreeLivingCaptureCoordinator(
    private val store: FreeLivingSessionStore,
    private val port: HealthControlPort,
    private val clock: CaptureClock,
    private val onState: (CaptureControlState) -> Unit,
) {
    var state = CaptureControlState()
        private set

    private enum class Purpose { PREFLIGHT, START, STOP, INSPECT }
    private data class Round(
        val id: Long,
        val generation: Long,
        val purpose: Purpose,
        var status: SensorPacket.Health? = null,
        val records: MutableList<HealthMessage.ListItem> = mutableListOf(),
    )
    private data class StartIntent(val preparation: PreparationSnapshot, val atMs: Long, val zone: String)
    private data class RecordIdentity(val sessionId: Int, val uptimeMs: Long, val unixMs: Long)

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

    /** Initialization only; repeated calls cannot replace an active untagged response round. */
    fun restore() = dispatch { if (!restored) readJournal() }

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

    fun requestStart(preparation: PreparationSnapshot) = dispatch {
        if (!ensureRestored() || round != null || intent != null || session != null) return@dispatch
        if (!connectedTo(preparation.ring?.address)) return@dispatch
        intent = StartIntent(preparation, clock.nowEpochMs(), clock.timeZoneId())
        beginRound(Purpose.PREFLIGHT)
    }

    fun requestStop() = dispatch {
        if (!ensureRestored() || round != null) return@dispatch
        val current = session ?: return@dispatch
        if (current.phase == FreeLivingSessionPhase.AWAITING_REFERENCE) return@dispatch
        if (!connectedTo(current.preparation.ring?.address)) return@dispatch
        if (ownership == null || baseline?.connectionGeneration != generation) {
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
        if (!ensureRestored() || round != null) return@dispatch
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

    fun onHealth(connectionGeneration: Long, packet: SensorPacket.Health) = dispatch {
        if (connectionGeneration != generation) return@dispatch
        val active = round
        if (active == null) {
            val status = packet.message as? HealthMessage.Status ?: return@dispatch
            val watchingOwnedCollection = ownership != null && session?.phase == FreeLivingSessionPhase.COLLECTING
            if (watchingOwnedCollection &&
                (!validStatus(status) || !status.collecting || status.errorCode != 0 ||
                    status.sessionId != ownership?.sessionId || statusRegressed(status))) {
                clearAssociation()
                review(CaptureControlIssue.UNEXPECTED_DEVICE_STATE)
            } else if (watchingOwnedCollection) {
                lastStatus = status
            }
            return@dispatch
        }
        if (active.generation != connectionGeneration) return@dispatch
        when (val message = packet.message) {
            is HealthMessage.Status -> {
                if (active.status != null) return@dispatch
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
                round = null
                completeRound(active.purpose, observation)
            }
            else -> Unit
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
        round = Round(++operation, connectedGeneration, purpose)
        publish(when (purpose) {
            Purpose.PREFLIGHT, Purpose.INSPECT -> CaptureControlPhase.CHECKING
            Purpose.START -> CaptureControlPhase.STARTING
            Purpose.STOP -> CaptureControlPhase.STOPPING
        })
        send(port::queryStatus)
    }

    private fun completeRound(purpose: Purpose, observed: HealthRecordObservation) {
        if (observed.status.errorCode != 0) {
            clearAssociation()
            review(CaptureControlIssue.DEVICE_ERROR, observed)
            return
        }
        when (purpose) {
            Purpose.PREFLIGHT -> {
                val requested = intent ?: return
                intent = null
                if (observed.status.collecting || observed.status.bytes != 0L || observed.status.records != 0L || observed.records.isNotEmpty()) {
                    review(CaptureControlIssue.EXISTING_RECORDS, observed)
                    return
                }
                // A second owner of this journal is unsupported. Recheck before issuing a command.
                val existing = try { store.readPending() } catch (_: Exception) { storageFailure(); return }
                if (existing != null) { session = existing; publishRestored(); return }
                val saved = save { store.requestStart(requested.preparation, requested.atMs, requested.zone) } ?: return
                baseline = observed
                publish(CaptureControlPhase.STARTING, observed = observed)
                if (!send(port::start)) return
                check(saved.phase == FreeLivingSessionPhase.START_REQUESTED)
                beginRound(Purpose.START)
            }
            Purpose.START -> confirmStart(observed)
            Purpose.STOP -> confirmStop(observed)
            Purpose.INSPECT -> review(CaptureControlIssue.RECOVERY_REQUIRES_REVIEW, observed)
        }
    }

    private fun confirmStart(observed: HealthRecordObservation) {
        val before = baseline
        val current = session ?: return
        val candidate = observed.records.singleOrNull()
        if (before == null || before.connectionGeneration != generation || !observed.status.collecting || candidate == null ||
            candidate.sessionId == 0 || candidate.sessionId == before.status.sessionId ||
            candidate.sessionId != observed.status.sessionId || candidate.unixMs <= 0 ||
            candidate.bytes < observed.status.bytes || candidate.records < observed.status.records) {
            review(CaptureControlIssue.RECORD_ORIGIN_UNCERTAIN, observed)
            return
        }
        if (save { store.confirmStart(current.sessionId, observed.address, observed.status, observed.statusReceivedAtMs) } == null) return
        ownership = identity(candidate)
        lastRecord = candidate
        lastStatus = observed.status
        publish(CaptureControlPhase.COLLECTING, observed = observed)
    }

    private fun confirmStop(observed: HealthRecordObservation) {
        val current = session ?: return
        val candidate = observed.records.singleOrNull()
        val previous = lastRecord
        if (observed.status.collecting) { review(CaptureControlIssue.UNEXPECTED_DEVICE_STATE, observed); return }
        if (ownership == null || candidate == null || identity(candidate) != ownership ||
            observed.status.sessionId != candidate.sessionId || previous == null ||
            statusRegressed(observed.status) ||
            candidate.bytes < previous.bytes || candidate.records < previous.records ||
            candidate.bytes < observed.status.bytes || candidate.records < observed.status.records) {
            clearAssociation()
            review(CaptureControlIssue.RECORD_CHANGED, observed)
            return
        }
        if (save { store.confirmStop(current.sessionId, observed.address, observed.status, observed.statusReceivedAtMs) } == null) return
        lastRecord = candidate
        lastStatus = observed.status
        publish(CaptureControlPhase.AWAITING_REFERENCE, observed = observed)
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
        round = null
        intent = null
        baseline = null
        ownership = null
        lastRecord = null
        lastStatus = null
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
        round = null
        intent = null
        publish(CaptureControlPhase.STORAGE_ERROR, CaptureControlIssue.STORAGE_FAILURE)
    }

    private fun review(issue: CaptureControlIssue, observed: HealthRecordObservation? = null) {
        round = null
        intent = null
        publish(CaptureControlPhase.NEEDS_REVIEW, issue, observed)
    }

    private fun publish(phase: CaptureControlPhase, issue: CaptureControlIssue? = null, observed: HealthRecordObservation? = null) {
        state = CaptureControlState(phase, session, issue, observed, round?.id)
        onState(state)
    }

    private fun dispatch(action: () -> Unit) {
        events.addLast(action)
        if (draining) return
        draining = true
        try {
            while (events.isNotEmpty()) events.removeFirst().invoke()
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
    }
}
