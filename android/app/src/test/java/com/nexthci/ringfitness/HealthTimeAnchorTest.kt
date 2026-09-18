package com.nexthci.ringfitness

import org.junit.Assert.assertEquals
import org.junit.Test

class HealthTimeAnchorTest {
    @Test
    fun validRingUnixAnchorTakesPriority() {
        val actual = resolveHealthEpochMs(
            uptimeMs = 1_225,
            anchorUptimeMs = 1_000,
            anchorUnixMs = 1_787_289_600_000,
            fallbackStartedAtMs = 1_700_000_000_000,
        )

        assertEquals(1_787_289_600_225, actual)
    }

    @Test
    fun zeroRingUnixAnchorFallsBackToPersistedCaptureStart() {
        val actual = resolveHealthEpochMs(
            uptimeMs = 6_866_041,
            anchorUptimeMs = 6_865_816,
            anchorUnixMs = 0,
            fallbackStartedAtMs = 1_787_289_245_695,
        )

        assertEquals(1_787_289_245_920, actual)
    }

    @Test
    fun uptimeWraparoundIsPreserved() {
        val actual = resolveHealthEpochMs(
            uptimeMs = 20,
            anchorUptimeMs = 0xFFFF_FFF0L,
            anchorUnixMs = 0,
            fallbackStartedAtMs = 1_787_289_245_695,
        )

        assertEquals(1_787_289_245_731, actual)
    }

    @Test
    fun uptimeResetCannotCreateA49DayTimestampJump() {
        val startedAt = 1_787_306_333_392L
        val previousEnd = startedAt + 7_955L
        val wrappedCandidate = startedAt + 4_292_856_410L

        val actual = normalizeHealthPacketEndEpoch(
            candidateEpochMs = wrappedCandidate,
            fallbackStartedAtMs = startedAt,
            previousPacketEndEpochMs = previousEnd,
            sampleCount = 10,
            sampleIntervalMs = 20L,
        )

        assertEquals(previousEnd + 200L, actual)
    }

    @Test
    fun outOfOrderPacketIsAppendedAtNominalSampleRate() {
        val startedAt = 1_787_306_333_392L
        val previousEnd = startedAt + 4_000L

        val actual = normalizeHealthPacketEndEpoch(
            candidateEpochMs = startedAt + 500L,
            fallbackStartedAtMs = startedAt,
            previousPacketEndEpochMs = previousEnd,
            sampleCount = 4,
            sampleIntervalMs = 40L,
        )

        assertEquals(previousEnd + 160L, actual)
    }
}
