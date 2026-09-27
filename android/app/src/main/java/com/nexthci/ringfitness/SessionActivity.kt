package com.nexthci.ringfitness

/** The participant's task for a whole session; sample-level activity remains unlabelled. */
enum class SessionActivity(val wireValue: String, val label: String, val requiresReferenceSteps: Boolean = true) {
    /** Retained solely for historical sessions and their frozen upload packages. */
    FREE_LIVING("free_living", "自由活动"),
    WALKING("walking", "走路"),
    RUNNING("running", "跑步"),
    BADMINTON("badminton", "羽毛球", false),
    FOOTBALL("football", "足球", false),
    BASKETBALL("basketball", "篮球", false),
    TENNIS("tennis", "网球", false),
    TABLE_TENNIS("table_tennis", "乒乓球", false),
    VOLLEYBALL("volleyball", "排球", false),
    STRENGTH_TRAINING("strength_training", "力量训练", false);

    companion object {
        val selectable: List<SessionActivity> = entries.filter { it != FREE_LIVING }
        fun fromWireValue(value: String): SessionActivity? = entries.singleOrNull { it.wireValue == value }
    }
}
