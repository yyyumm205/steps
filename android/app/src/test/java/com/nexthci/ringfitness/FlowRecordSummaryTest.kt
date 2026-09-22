package com.nexthci.ringfitness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FlowRecordSummaryTest {
    @Test fun displayTimePrefersTheVerifiedSampleBoundary() {
        val summary = summary(startedAtMs = 1_000, phoneStartAtMs = 2_000)

        assertEquals(1_000L, summary.displayStartedAtMs)
    }

    @Test fun displayTimeFallsBackToThePhoneStartAnchorWithoutInventingASampleBoundary() {
        val summary = summary(startedAtMs = null, phoneStartAtMs = 2_000)

        assertNull(summary.startedAtMs)
        assertEquals(2_000L, summary.displayStartedAtMs)
    }

    @Test fun phoneAnchorPrefersConfirmationAndFallsBackToTheRequestForLegacyHistory() {
        val confirmed = session(startRequestedAtMs = 1_000, startConfirmedAtMs = 1_100)
        val legacy = session(startRequestedAtMs = 2_000, startConfirmedAtMs = null)

        assertEquals(1_100L, confirmed.phoneStartAnchorMs())
        assertEquals(2_000L, legacy.phoneStartAnchorMs())
    }

    private fun summary(startedAtMs: Long?, phoneStartAtMs: Long?) = FlowRecordSummary(
        sessionId = "summary", steps = 10, referenceStatus = "valid", transferStatus = "complete",
        localComplete = true, startedAtMs = startedAtMs, phoneStartAtMs = phoneStartAtMs,
    )

    private fun session(startRequestedAtMs: Long, startConfirmedAtMs: Long?) = FreeLivingSession(
        sessionId = "00000000-0000-4000-8000-000000000001",
        preparation = PreparationSnapshot("p001", "00000000-0000-4000-8000-000000000002",
            RingPlacement.LEFT_INDEX, PreparedRing("AA:BB:CC:DD:EE:01", "Ringo")),
        phase = FreeLivingSessionPhase.AWAITING_REFERENCE,
        timeZoneId = "Asia/Shanghai",
        utcOffsetSeconds = 28_800,
        startRequestedAtMs = startRequestedAtMs,
        startConfirmedAtMs = startConfirmedAtMs,
    )
}
