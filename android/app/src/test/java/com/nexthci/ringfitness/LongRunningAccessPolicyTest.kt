package com.nexthci.ringfitness

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LongRunningAccessPolicyTest {
    @Test fun androidThirteenRequestsNotificationOnlyBeforeTheFirstDecision() {
        val first = LongRunningAccessPolicy.evaluate(33, notificationGranted = false,
            notificationPermissionRequested = false, batteryOptimizationExempt = true)
        assertTrue(first.requestNotificationPermission)
        assertFalse(first.showNotificationSettings)

        val denied = LongRunningAccessPolicy.evaluate(33, notificationGranted = false,
            notificationPermissionRequested = true, batteryOptimizationExempt = true)
        assertFalse(denied.requestNotificationPermission)
        assertTrue(denied.showNotificationSettings)
    }

    @Test fun grantedNotificationAndUnrestrictedBatteryHideBothSettingsActions() {
        val state = LongRunningAccessPolicy.evaluate(36, notificationGranted = true,
            notificationPermissionRequested = true, batteryOptimizationExempt = true)
        assertFalse(state.requestNotificationPermission)
        assertFalse(state.showSettings)
    }

    @Test fun batteryActionAppearsOnlyWhileTheSystemStillRestrictsBackgroundWork() {
        val restricted = LongRunningAccessPolicy.evaluate(31, notificationGranted = true,
            notificationPermissionRequested = false, batteryOptimizationExempt = false)
        assertTrue(restricted.showBatterySettings)
        assertTrue(restricted.showSettings)

        val unrestricted = LongRunningAccessPolicy.evaluate(31, notificationGranted = true,
            notificationPermissionRequested = false, batteryOptimizationExempt = true)
        assertFalse(unrestricted.showBatterySettings)
        assertFalse(unrestricted.showSettings)
    }

    @Test fun olderAndroidVersionsNeverShowARuntimeNotificationAction() {
        val state = LongRunningAccessPolicy.evaluate(32, notificationGranted = false,
            notificationPermissionRequested = false, batteryOptimizationExempt = true)
        assertFalse(state.requestNotificationPermission)
        assertFalse(state.showNotificationSettings)
    }
}
