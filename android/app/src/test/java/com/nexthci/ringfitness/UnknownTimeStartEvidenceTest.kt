package com.nexthci.ringfitness

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class UnknownTimeStartEvidenceTest {
    private val now = 1_789_804_800_000L
    private val record = HealthMessage.ListItem(7, 100, 3, 900_000, 0)
    private val clock = PhoneClockSyncEvidence(UUID.randomUUID().toString(), "AA:BB:CC:DD:EE:01", 2,
        now, now + 10, 100, 110, now + 5, 1_000)
    private val proof = UnknownTimeStartEvidence(record, UUID.randomUUID().toString(), "a".repeat(64),
        UUID.randomUUID().toString(), 2, now - 100, clock)
    private val baseline = DeviceStartBaseline(HealthMessage.Status(false, 100, 3, 0, 7), listOf(record), now + 20,
        unknownTimeStartEvidence = proof)

    @Test fun aPreservedPreviousBootRecordAllowsTheNewClockProvedRecordAndRoundTrips() {
        proof.validate(baseline)
        assertEquals(proof, UnknownTimeStartEvidence.decode(proof.encode()))
        val candidate = record.copy(uptimeMs = 1_100, unixMs = now + 105)
        assertTrue(FreeLivingSessionStore.isDistinctStartRecord(baseline, candidate))
        assertFalse(FreeLivingSessionStore.isDistinctStartRecord(baseline.copy(unknownTimeStartEvidence = null), candidate))
        assertTrue(FreeLivingSessionStore.isDistinctStartRecord(baseline, candidate.copy(sessionId = 8)))
        assertFalse(FreeLivingSessionStore.isDistinctStartRecord(baseline, candidate.copy(sessionId = 8, unixMs = 0)))
    }

    @Test fun unchangedUnknownOrOutOfClockWindowRecordsStayUnconfirmed() {
        listOf(record, record.copy(uptimeMs = 999, unixMs = now),
            record.copy(uptimeMs = 1_100, unixMs = now + 1_000),
            record.copy(uptimeMs = 50_000, unixMs = now + 49_005)).forEach {
            assertFalse(proof.isDistinct(it))
        }
        assertThrows(IllegalArgumentException::class.java) { proof.copy(connectionGeneration = 3).validate(baseline) }
        assertThrows(IllegalArgumentException::class.java) { proof.copy(rawSha256 = "bad").validate(baseline) }
        assertThrows(IllegalArgumentException::class.java) { proof.validate(baseline.copy(observedAtMs = now + 40_000)) }
    }

    @Test fun storedEvidenceRejectsCoercedNumericFieldsAndExtraFields() {
        listOf("1", "1.0", "true").forEach { value ->
            val json = proof.encode().apply { add("version", com.google.gson.JsonParser.parseString(value)) }
            if (value == "1") assertEquals(proof, UnknownTimeStartEvidence.decode(json))
            else assertThrows(IllegalArgumentException::class.java) { UnknownTimeStartEvidence.decode(json) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            UnknownTimeStartEvidence.decode(proof.encode().apply { addProperty("ignore_errors", true) })
        }
    }

    @Test fun stopOnlyCandidateRequiresNonzeroUptimeAndNeverConfirmsResearchStart() {
        val candidate = record.copy(sessionId = 8, uptimeMs = 1_100)
        assertTrue(proof.canStopWithoutUnix(candidate))
        assertFalse(proof.isDistinct(candidate))
        assertFalse(proof.copy(clock = clock.copy(deviceUptimeMs = 0)).canStopWithoutUnix(candidate.copy(uptimeMs = 0)))
        assertFalse(proof.canStopWithoutUnix(record))
    }
}
