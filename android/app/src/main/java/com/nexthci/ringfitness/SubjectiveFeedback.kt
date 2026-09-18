package com.nexthci.ringfitness

enum class SubjectiveFeedbackTarget(val wireValue: String) {
    SLEEP("sleep"),
    DAILY_ACTIVITY("daily_activity");

    companion object {
        fun fromWireValue(value: String?): SubjectiveFeedbackTarget? = entries.firstOrNull {
            it.wireValue == value
        }
    }
}

data class SubjectiveFeedback(
    val target: SubjectiveFeedbackTarget,
    val score: Int?,
    val comment: String,
    val activityDetail: String? = null,
    val recordedAtMs: Long = System.currentTimeMillis(),
) {
    init {
        require(score == null || score in 1..10) { "Subjective score must be between 1 and 10" }
        require(comment.length <= MAX_COMMENT_LENGTH) { "Subjective comment is too long" }
        require(activityDetail == null || activityDetail.length <= MAX_ACTIVITY_DETAIL_LENGTH) {
            "Activity detail is too long"
        }
    }

    companion object {
        const val SCHEMA = "subjective_feedback_v1"
        const val MAX_COMMENT_LENGTH = 500
        const val MAX_ACTIVITY_DETAIL_LENGTH = 500

        fun normalizedComment(value: String): String = value.trim().take(MAX_COMMENT_LENGTH)

        fun normalizedActivityDetail(value: String): String? = value.trim()
            .take(MAX_ACTIVITY_DETAIL_LENGTH)
            .takeIf { it.isNotEmpty() }
    }
}
