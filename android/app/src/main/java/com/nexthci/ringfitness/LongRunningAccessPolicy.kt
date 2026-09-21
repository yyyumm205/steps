package com.nexthci.ringfitness

/** Visibility policy for optional system support used by collection and upload services. */
data class LongRunningAccessState(
    val requestNotificationPermission: Boolean,
    val showNotificationSettings: Boolean,
    val showBatterySettings: Boolean,
) {
    val showSettings: Boolean get() = showNotificationSettings || showBatterySettings
}

object LongRunningAccessPolicy {
    fun evaluate(
        sdkInt: Int,
        notificationGranted: Boolean,
        notificationPermissionRequested: Boolean,
        batteryOptimizationExempt: Boolean,
    ) = LongRunningAccessState(
        requestNotificationPermission = sdkInt >= 33 && !notificationGranted && !notificationPermissionRequested,
        showNotificationSettings = sdkInt >= 33 && !notificationGranted && notificationPermissionRequested,
        showBatterySettings = sdkInt >= 23 && !batteryOptimizationExempt,
    )
}
