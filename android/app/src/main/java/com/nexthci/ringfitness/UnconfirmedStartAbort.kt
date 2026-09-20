package com.nexthci.ringfitness

import java.util.UUID

/** Stop-only evidence for this owner's anomalous START reply; never a research session. */
data class UnconfirmedStartAbort(
    val requestedAtMs: Long,
    val ownerId: String,
    val collectingObservation: HealthRecordObservation,
    val stoppedObservation: HealthRecordObservation? = null,
    val preservation: UnknownTimeRecordProof? = null,
    val preservedObservation: HealthRecordObservation? = null,
    val completedAtMs: Long? = null,
    val completionOwnerId: String? = null,
) {
    val record: HealthMessage.ListItem get() = collectingObservation.records.single {
        it.sessionId == collectingObservation.status.sessionId
    }

    fun validate(session: FreeLivingSession) {
        require(session.phase == FreeLivingSessionPhase.START_REQUESTED && session.startConfirmedAtMs == null &&
            session.stopRequestedAtMs == null && session.stopConfirmedAtMs == null && session.deviceRecordEvidence == null &&
            session.reference == null && session.localData == null && session.startAttemptArchive == null &&
            session.discarded == null && session.completionPolicy == null)
        val baseline = requireNotNull(session.startBaseline)
        val clockProof = requireNotNull(baseline.unknownTimeStartEvidence)
        val active = collectingObservation
        require(UUID.fromString(ownerId).toString() == ownerId && ownerId == clockProof.ownerId)
        require(requestedAtMs > 0 && active.statusReceivedAtMs > 0 &&
            active.address == session.preparation.ring?.address &&
            active.connectionGeneration == clockProof.connectionGeneration)
        require(active.status.collecting && active.status.errorCode == 0)
        validateObservation(active, exactCounters = false)
        val candidate = record
        val previous = baseline.records.singleOrNull { it.sessionId == candidate.sessionId }
        require(candidate.unixMs == 0L && candidate.uptimeMs > 0 &&
            candidate.uptimeMs >= clockProof.clock.deviceUptimeMs &&
            candidate.uptimeMs - clockProof.clock.deviceUptimeMs <= PhoneClockSync.MAX_START_AGE_MS)
        require(previous == null || candidate.uptimeMs != previous.uptimeMs)
        require(active.records.all { it == candidate || it in baseline.records })
        stoppedObservation?.let { stopped ->
            require(stopped.address == active.address && stopped.connectionGeneration == active.connectionGeneration &&
                !stopped.status.collecting && stopped.status.errorCode == 0 && stopped.status.sessionId == active.status.sessionId)
            validateObservation(stopped)
            val final = stopped.records.single { it.sessionId == candidate.sessionId }
            require(FreeLivingSessionStore.sameDeviceRecord(candidate, final) && final.bytes >= candidate.bytes &&
                final.records >= candidate.records && stopped.records.all { it == final || it in baseline.records })
        }
        require((completedAtMs == null) == (preservedObservation == null) && (completedAtMs == null) == (completionOwnerId == null))
        if (completedAtMs == null) require(preservation == null)
        if (completedAtMs != null) {
            val stopped = requireNotNull(stoppedObservation)
            val preserved = requireNotNull(preservedObservation)
            require(completedAtMs > 0 && UUID.fromString(completionOwnerId).toString() == completionOwnerId)
            require(preserved.address == stopped.address &&
                (completionOwnerId != ownerId || preserved.connectionGeneration > stopped.connectionGeneration) &&
                preserved.status == stopped.status && preserved.records.toSet() == stopped.records.toSet() &&
                preserved.records.size == stopped.records.size)
            validateObservation(preserved)
            if (preservation != null) {
                require(preservation.ownerId == completionOwnerId)
                // The old uncalibrated baseline remains separately preserved; this proof covers only the aborted record.
                preservation.validate(preserved.copy(records = listOf(preservation.record)))
                require(preservation.record.sessionId == stopped.status.sessionId && preservation.record in preserved.records)
            } else require(preserved.status.bytes == 0L && preserved.status.records == 0L)
        }
    }

    private fun validateObservation(observation: HealthRecordObservation, exactCounters: Boolean = true) {
        require(observation.statusReceivedAtMs > 0 && observation.connectionGeneration > 0 &&
            observation.status.sessionId in 1..65535 && observation.status.bytes in 0..0xFFFF_FFFFL &&
            observation.status.records in 0..0xFFFF_FFFFL && observation.records.size <= 255 &&
            observation.records.map { it.sessionId }.distinct().size == observation.records.size)
        observation.records.forEach { record ->
            require(record.sessionId in 1..65535 && record.bytes in 0..0xFFFF_FFFFL &&
                record.records in 0..0xFFFF_FFFFL && record.uptimeMs in 0..0xFFFF_FFFFL && record.unixMs >= 0)
        }
        require(observation.records.any { it.sessionId == observation.status.sessionId &&
            (if (exactCounters) it.bytes == observation.status.bytes && it.records == observation.status.records
            else it.bytes >= observation.status.bytes && it.records >= observation.status.records) })
    }
}
