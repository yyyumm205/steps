package com.nexthci.ringfitness

enum class CapturePurpose(val wireValue: String) {
    SLEEP_HR("sleep_hr"),
    DAILY_ACTIVITY("daily_activity");

    companion object {
        fun fromWireValue(value: String?): CapturePurpose =
            entries.firstOrNull { it.wireValue == value } ?: SLEEP_HR
    }
}

enum class DailyActivity(val wireValue: String, val displayName: String) {
    WALKING("walking", "走路"),
    CYCLING("cycling", "骑车"),
    RUNNING("running", "跑步"),
    WORKING("working", "工作"),
    EATING("eating", "吃饭"),
    OTHER("other", "其他活动");

    companion object {
        fun fromWireValue(value: String?): DailyActivity? =
            entries.firstOrNull { it.wireValue == value }
    }
}

enum class RingPlacement(
    val wireValue: String,
    val displayName: String,
    val hand: String,
    val finger: String,
) {
    LEFT_INDEX("left_index", "左手食指", "left", "index"),
    LEFT_MIDDLE("left_middle", "左手中指", "left", "middle"),
    LEFT_RING("left_ring", "左手无名指", "left", "ring"),
    RIGHT_INDEX("right_index", "右手食指", "right", "index"),
    RIGHT_MIDDLE("right_middle", "右手中指", "right", "middle"),
    RIGHT_RING("right_ring", "右手无名指", "right", "ring");

    companion object {
        const val SCHEMA = "hand_finger_v1"

        fun fromWireValue(value: String?): RingPlacement? =
            entries.firstOrNull { it.wireValue == value }
    }
}
