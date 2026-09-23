package com.nexthci.ringfitness

import android.app.Activity
import android.app.AlertDialog
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run on the fresh recoveryqa installation with Bluetooth permissions denied externally.
 * This suite never revokes its own permissions or touches the participant installation. */
@RunWith(AndroidJUnit4::class)
class PermissionRecoveryInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun firstUseKeepsRegistrationEditableBeforeBluetoothAccess() = withDeniedQaAccess {
        assumeTrue(!File(context.filesDir, "preparation").exists())
        ActivityScenario.launch(StepPreparationActivity::class.java).use { scenario ->
            awaitRegistration(scenario)
            scenario.onActivity { activity ->
                val input = tagged<EditText>(activity, "participant_input")
                input.setText("PermissionQA")
                assertEquals("PermissionQA", input.text.toString())
                assertTrue(input.isShown && input.isEnabled)
                assertFalse(RealCollectionBridge.isRunning())
            }
        }
    }

    @Test fun permanentlyDeniedScanOpensAppSettingsFromItsPrimaryAction() = withDeniedQaAccess {
        val preparation = File(context.filesDir, "preparation")
        assumeTrue("This test creates only a fresh QA preparation fixture", !preparation.exists())
        BluetoothPermissionRecovery.recordRequest(context, BluetoothPermissionRecovery.missing(context))
        val monitor = settingsMonitor()
        try {
            ActivityScenario.launch(StepPreparationActivity::class.java).use { scenario ->
                awaitRegistration(scenario)
                scenario.onActivity { activity ->
                    assumeTrue("Requires fresh denial or permanent denial, rather than a first rejected prompt",
                        BluetoothPermissionRecovery.requiresSettings(activity, BluetoothPermissionRecovery.missing(activity)))
                    tagged<EditText>(activity, "participant_input").setText("PermissionQA")
                    tagged<Spinner>(activity, "placement_picker").setSelection(RingPlacement.RIGHT_INDEX.ordinal + 1)
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { tagged<Button>(it, "primary").performClick() }
                awaitDevicePage(scenario)
                scenario.onActivity { tagged<Button>(it, "primary").performClick() }
                awaitSettings(monitor)
                scenario.onActivity { activity ->
                    val primary = tagged<Button>(activity, "primary")
                    assertEquals(BluetoothPermissionRecovery.actionLabel, primary.text.toString())
                    assertTrue(primary.isEnabled)
                    assertFalse(RealCollectionBridge.isRunning())
                }
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            preparationDisk().submit {}.get(10, TimeUnit.SECONDS)
            // The directory was absent on entry and contains only this test's registration.
            if (preparation.exists()) check(preparation.deleteRecursively())
        }
    }

    @Test fun deniedDevicePageStaysVisibleAcrossResumeUntilPrimaryAction() = withDeniedQaAccess {
        val preparation = File(context.filesDir, "preparation")
        assumeTrue("This test creates only a QA profile with no selected ring", !preparation.exists())
        PreparationStore(preparation.resolve("profile.properties"))
            .registerUsername("PermissionQA", RingPlacement.RIGHT_INDEX)
        BluetoothPermissionRecovery.recordRequest(context, BluetoothPermissionRecovery.missing(context))
        val monitor = settingsMonitor()
        try {
            ActivityScenario.launch(StepPreparationActivity::class.java).use { scenario ->
                awaitDevicePage(scenario)
                scenario.recreate()
                awaitDevicePage(scenario)
                scenario.onActivity { activity ->
                    assertEquals(0, monitor.hits)
                    assertEquals(BluetoothPermissionRecovery.actionLabel,
                        tagged<Button>(activity, "primary").text.toString())
                    assertTrue(tagged<Button>(activity, "primary").isEnabled)
                }
                // The explicit action remains the only path to system settings after denial.
                scenario.onActivity { tagged<Button>(it, "primary").performClick() }
                awaitSettings(monitor)
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            preparationDisk().submit {}.get(10, TimeUnit.SECONDS)
            if (preparation.exists()) check(preparation.deleteRecursively())
        }
    }

    @Test fun deniedCollectionOffersSettingsAndKeepsOwnerStopped() = withDeniedQaAccess {
        BluetoothPermissionRecovery.recordRequest(context, BluetoothPermissionRecovery.missing(context))
        val monitor = settingsMonitor()
        try {
            ActivityScenario.launch(RealCollectionActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assumeTrue("Requires fresh denial or permanent denial",
                        BluetoothPermissionRecovery.requiresSettings(activity, BluetoothPermissionRecovery.missing(activity)))
                    val dialog = RealCollectionActivity::class.java.getDeclaredField("permissionDialog")
                        .apply { isAccessible = true }.get(activity) as? AlertDialog
                    assertTrue("Denied access must offer an actionable settings dialog", dialog?.isShowing == true)
                    assertFalse(RealCollectionBridge.isRunning())
                    assertEquals(null, RealCollectionBridge.state.session)
                    requireNotNull(dialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                }
                awaitSettings(monitor)
                assertFalse(RealCollectionBridge.isRunning())
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun settingsMonitor() = Instrumentation.ActivityMonitor(
        IntentFilter(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { addDataScheme("package") },
        Instrumentation.ActivityResult(Activity.RESULT_CANCELED, Intent()), true,
    ).also(instrumentation::addMonitor)

    private fun awaitSettings(monitor: Instrumentation.ActivityMonitor) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (monitor.hits == 0 && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(25)
        assertTrue("An actionable permission exit must open system app settings", monitor.hits > 0)
    }

    private fun awaitRegistration(scenario: ActivityScenario<StepPreparationActivity>) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        do {
            var ready = false
            scenario.onActivity { activity ->
                ready = tagged<Button>(activity, "primary").let { it.isShown && it.isEnabled } &&
                    tagged<EditText>(activity, "participant_input").isShown
            }
            if (ready) return
            SystemClock.sleep(25)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("Registration must remain available without Bluetooth permission")
    }

    private fun awaitDevicePage(scenario: ActivityScenario<StepPreparationActivity>) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        do {
            var ready = false
            scenario.onActivity { activity ->
                ready = tagged<TextView>(activity, "heading").text.toString() == "选择戒指" &&
                    tagged<Button>(activity, "primary").isShown && tagged<Button>(activity, "primary").isEnabled
            }
            if (ready) return
            SystemClock.sleep(25)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("Denied access must leave the device-selection page actionable")
    }

    private fun preparationDisk() = StepPreparationActivity::class.java.getDeclaredField("disk")
        .apply { isAccessible = true }.get(null) as ExecutorService

    private fun <T : View> tagged(activity: Activity, tag: String): T =
        requireNotNull(activity.findViewById<View>(android.R.id.content).findViewWithTag<T>(tag))

    private fun withDeniedQaAccess(test: () -> Unit) {
        assumeTrue("Only the separate recovery QA application is in scope", context.packageName.endsWith(".recoveryqa"))
        assumeTrue("Revoke Bluetooth permissions before starting instrumentation",
            BluetoothPermissionRecovery.missing(context).isNotEmpty())
        assumeTrue("No collection owner or participant journal may be present",
            !RealCollectionBridge.isRunning() && !File(context.filesDir, "collection-real/session.json").exists())
        val preferences = context.getSharedPreferences("bluetooth_access", Context.MODE_PRIVATE)
        val previous = preferences.getStringSet("requested_permissions", null)?.toSet()
        try {
            preferences.edit().remove("requested_permissions").commit()
            test()
        } finally {
            val editor = preferences.edit()
            if (previous == null) editor.remove("requested_permissions")
            else editor.putStringSet("requested_permissions", previous)
            editor.commit()
        }
    }
}
