package com.nexthci.ringfitness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CapturePurposeTest {
    @Test
    fun legacySessionsDefaultToSleepHeartRate() {
        assertEquals(CapturePurpose.SLEEP_HR, CapturePurpose.fromWireValue(null))
        assertEquals(CapturePurpose.SLEEP_HR, CapturePurpose.fromWireValue("unknown"))
    }

    @Test
    fun activityCodesUseStableWireValues() {
        assertEquals(DailyActivity.WALKING, DailyActivity.fromWireValue("walking"))
        assertEquals(DailyActivity.OTHER, DailyActivity.fromWireValue("other"))
        assertNull(DailyActivity.fromWireValue("unlabeled"))
    }

    @Test
    fun ringPlacementsUseStableWireValuesAndComponents() {
        val placement = RingPlacement.fromWireValue("left_ring")
        assertEquals(RingPlacement.LEFT_RING, placement)
        assertEquals("left", placement?.hand)
        assertEquals("ring", placement?.finger)
        assertEquals("hand_finger_v1", RingPlacement.SCHEMA)
        assertNull(RingPlacement.fromWireValue("left_thumb"))
    }

    @Test
    fun subjectiveFeedbackUsesStableSchemaAndTrimsComment() {
        val feedback = SubjectiveFeedback(
            target = SubjectiveFeedbackTarget.DAILY_ACTIVITY,
            score = 8,
            comment = "状态不错",
            activityDetail = SubjectiveFeedback.normalizedActivityDetail("  从实验室走到宿舍  "),
            recordedAtMs = 1234L,
        )

        assertEquals("subjective_feedback_v1", SubjectiveFeedback.SCHEMA)
        assertEquals("daily_activity", feedback.target.wireValue)
        assertEquals(8, feedback.score)
        assertEquals("从实验室走到宿舍", feedback.activityDetail)
        assertEquals("状态不错", SubjectiveFeedback.normalizedComment("  状态不错  "))
    }

    @Test
    fun subjectiveScoreAndActivityDetailCanBeOmitted() {
        val feedback = SubjectiveFeedback(
            target = SubjectiveFeedbackTarget.DAILY_ACTIVITY,
            score = null,
            comment = "",
            activityDetail = SubjectiveFeedback.normalizedActivityDetail("   "),
            recordedAtMs = 1234L,
        )

        assertNull(feedback.score)
        assertNull(feedback.activityDetail)
    }
}
