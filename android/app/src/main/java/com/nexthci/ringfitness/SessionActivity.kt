package com.nexthci.ringfitness

/** The participant's task for a whole session; sample-level activity remains unlabelled. */
enum class SessionActivity(val wireValue: String, val label: String) {
    /** Retained solely for historical sessions and their frozen upload packages. */
    FREE_LIVING("free_living", "自由活动"),
    WALKING("walking", "走路"),
    RUNNING("running", "跑步");

    companion object {
        fun fromWireValue(value: String): SessionActivity? = entries.singleOrNull { it.wireValue == value }
    }
}
