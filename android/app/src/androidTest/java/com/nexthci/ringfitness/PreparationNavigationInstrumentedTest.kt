package com.nexthci.ringfitness

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Instrumentation
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.Spinner
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Emulator-only checks of the launcher/setup boundary. No START or STOP command is sent. */
@RunWith(AndroidJUnit4::class)
class PreparationNavigationInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val preparationFile get() = File(context.filesDir, "preparation/profile.properties")
    private val journalFile get() = File(context.filesDir, "collection-real/session.json")
    private val ring = PreparedRing("AA:BB:CC:DD:EE:01", "Ringo navigation test")

    @Test
    fun firstUseAcceptsNameAndKeepsAllSixOriginalPlacementChoices() = withIsolatedFiles {
        launch().use { scenario ->
            awaitHeading(scenario, "开始使用")
            click(scenario, "identity_type_local")
            scenario.onActivity { activity ->
                val spinner = tagged<Spinner>(activity, "placement_picker")
                assertEquals(1 + RingPlacement.entries.size, spinner.adapter.count)
                assertEquals(
                    RingPlacement.entries.map { it.displayName },
                    (1 until spinner.adapter.count).map { spinner.adapter.getItem(it).toString() },
                )
                tagged<EditText>(activity, "participant_input").setText("Alice")
                assertEquals(
                    "仅保存在本机，研究数据使用匿名编号",
                    tagged<TextView>(activity, "identity_help").text.toString(),
                )
            }
            scenario.recreate()
            awaitHeading(scenario, "开始使用")
            scenario.onActivity { activity ->
                assertTrue(tagged<RadioButton>(activity, "identity_type_local").isChecked)
                assertEquals("Alice", tagged<EditText>(activity, "participant_input").text.toString())
            }
            choosePlacement(scenario, RingPlacement.RIGHT_INDEX)
            click(scenario, "primary")
            awaitHeading(scenario, "选择戒指")
            val saved = requireNotNull(PreparationStore(preparationFile).read())
            assertEquals("Alice", saved.displayLabel)
            assertEquals(PreparationIdentityType.LOCAL_NAME, saved.identityType)
            assertTrue(saved.participantId.matches(Regex("^local[a-f0-9]{16}$")))
            assertEquals(RingPlacement.RIGHT_INDEX, saved.placement)
            assertNull(saved.ring)
        }
    }

    @Test
    fun loadingAndSavingUseProgressInsteadOfDisabledActionButtons() = withIsolatedFiles {
        launch().use { scenario ->
            awaitHeading(scenario, "开始使用")
            scenario.onActivity { activity ->
                setBoolean(activity, "loaded", false)
                setBoolean(activity, "collectionLoaded", false)
                updateViews(activity)
                assertEquals("正在读取本机信息", tagged<TextView>(activity, "loading_status").text.toString())
                assertTrue(tagged<View>(activity, "loading_state").isShown)
                assertFalse(tagged<Button>(activity, "primary").isShown)
                assertNoVisiblePseudoAction(activity)

                setBoolean(activity, "loaded", true)
                setBoolean(activity, "collectionLoaded", true)
                setBoolean(activity, "busy", true)
                updateViews(activity)
                assertEquals("正在保存", tagged<TextView>(activity, "loading_status").text.toString())
                assertTrue(tagged<View>(activity, "loading_state").isShown)
                assertFalse(tagged<Button>(activity, "primary").isShown)
                assertNoVisiblePseudoAction(activity)

                setBoolean(activity, "busy", false)
                updateViews(activity)
                assertFalse(tagged<View>(activity, "loading_state").isShown)
                assertTrue(tagged<Button>(activity, "primary").let { it.isShown && it.isEnabled })
            }
        }
    }

    @Test
    fun selectedRingIsDurableBeforeTheCollectionHomeIsOpened() = withIsolatedFiles {
        grantBluetoothPermissions()
        val monitor = blockingCollectionMonitor()
        try {
            launch().use { scenario ->
                awaitHeading(scenario, "开始使用")
                scenario.onActivity { activity ->
                    assertTrue(tagged<RadioButton>(activity, "identity_type_research").isChecked)
                    tagged<EditText>(activity, "participant_input").setText("P001")
                }
                choosePlacement(scenario, RingPlacement.RIGHT_RING)
                click(scenario, "primary")
                awaitHeading(scenario, "选择戒指")
                deliverScanResult(scenario, ScannedRing(ring.address, ring.name, -45))
                scenario.onActivity { activity ->
                    val visible = descendants(activity.findViewById(android.R.id.content))
                        .filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }.toList()
                    assertFalse(visible.any { it in setOf("已连接", "准备完成", "连接尚未完成") })
                }
                clickScannedRing(scenario, ring)
                awaitMonitorHit(monitor)
                val saved = requireNotNull(PreparationStore(preparationFile).read())
                assertEquals(ring, saved.ring)
                assertEquals(RingPlacement.RIGHT_RING, saved.placement)
                assertEquals(PreparationIdentityType.RESEARCH_ID, saved.identityType)
                val stored = preparationFile.readText(Charsets.UTF_8)
                assertFalse(stored.contains("connected", ignoreCase = true))
                assertFalse(stored.contains("ready", ignoreCase = true))
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    fun completeProfileAndPendingSessionBothBypassPreparationOnNormalLaunch() = withIsolatedFiles {
        val complete = completeProfile("P001")
        assertNormalLaunchRedirects()

        requireNotNull(journalFile.parentFile).mkdirs()
        FreeLivingSessionStore(journalFile).requestStart(complete, 1_000, "Asia/Shanghai")
        assertNormalLaunchRedirects()
    }

    @Test
    fun explicitSettingsProvidesOnlyRealProfileAndRingActions() = withIsolatedFiles {
        val original = completeProfile("P001")
        launch(settings = true).use { scenario ->
            awaitHeading(scenario, "设置")
            scenario.onActivity { activity ->
                assertEquals("P001", tagged<TextView>(activity, "participant_summary").text.toString())
                for (tag in listOf("change_identity", "edit_placement", "change_ring", "clear_profile")) {
                    assertTrue("$tag is a real action", tagged<Button>(activity, tag).let { it.isShown && it.isEnabled })
                }
                assertFalse(descendants(activity.findViewById(android.R.id.content)).filterIsInstance<TextView>()
                    .any { it.isShown && it.text.toString() == "准备完成" })
                tagged<Button>(activity, "change_identity").performClick()
            }
            val dialog = awaitIdentityDialog(scenario)
            scenario.onActivity {
                val root = requireNotNull(dialog.window).decorView
                val local = requireNotNull(root.findViewWithTag<RadioButton>("identity_dialog_type_local"))
                local.performClick()
                assertTrue(local.isChecked)
                val input = requireNotNull(root.findViewWithTag<EditText>("identity_dialog_input"))
                input.setText("Alice")
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            awaitHeading(scenario, "开始使用")
            val replacement = requireNotNull(PreparationStore(preparationFile).read())
            assertEquals("Alice", replacement.displayLabel)
            assertEquals(PreparationIdentityType.LOCAL_NAME, replacement.identityType)
            assertTrue(replacement.participantId.matches(Regex("^local[a-f0-9]{16}$")))
            assertEquals(original.installationId, replacement.installationId)
            assertNull(replacement.placement)
            assertNull(replacement.ring)
        }
    }

    @Test
    fun pendingSessionSettingsExposeOnlyReturnToCurrentRecord() = withIsolatedFiles {
        val complete = completeProfile("P001")
        requireNotNull(journalFile.parentFile).mkdirs()
        FreeLivingSessionStore(journalFile).requestStart(complete, 1_000, "Asia/Shanghai")
        val monitor = blockingCollectionMonitor()
        try {
            launch(settings = true).use { scenario ->
                awaitHeading(scenario, "设置")
                scenario.onActivity { activity ->
                    for (tag in listOf("change_identity", "edit_placement", "change_ring", "clear_profile")) {
                        assertFalse("$tag is hidden while a session is pending", tagged<Button>(activity, tag).isShown)
                    }
                    val primary = tagged<Button>(activity, "primary")
                    assertEquals("返回当前记录", primary.text.toString())
                    assertTrue(primary.performClick())
                }
                awaitMonitorHit(monitor)
            }
            val unchanged = requireNotNull(PreparationStore(preparationFile).read())
            assertEquals(complete, unchanged)
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    fun placementOpenedBeforeOwnerStartsCannotChangeTheProfile() = withIsolatedFiles {
        val original = completeProfile("P001")
        val monitor = blockingCollectionMonitor()
        val owner = Any()
        var releaseDisk: CountDownLatch? = null
        try {
            launch(settings = true).use { scenario ->
                awaitHeading(scenario, "设置")
                click(scenario, "edit_placement")
                val dialog = awaitPlacementDialog(scenario)
                releaseDisk = holdPreparationDisk()
                scenario.onActivity {
                    val position = RingPlacement.LEFT_RING.ordinal
                    assertTrue(dialog.listView.performItemClick(null, position, dialog.listView.adapter.getItemId(position)))
                    RealCollectionBridge.begin(owner)
                }
                releaseDisk!!.countDown()
                awaitMonitorHit(monitor)
            }
            assertEquals(original, PreparationStore(preparationFile).read())
        } finally {
            releaseDisk?.countDown()
            instrumentation.runOnMainSync { RealCollectionBridge.detach(owner) }
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    fun scannedRingSelectedBeforeOwnerStartsCannotReplaceTheProfileRing() = withIsolatedFiles {
        grantBluetoothPermissions()
        val original = completeProfile("P001")
        val replacement = PreparedRing("AA:BB:CC:DD:EE:02", "Ringo replacement")
        val monitor = blockingCollectionMonitor()
        val owner = Any()
        var releaseDisk: CountDownLatch? = null
        try {
            launch(settings = true).use { scenario ->
                awaitHeading(scenario, "设置")
                click(scenario, "change_ring")
                awaitHeading(scenario, "选择戒指")
                deliverScanResult(scenario, ScannedRing(replacement.address, replacement.name, -40))
                releaseDisk = holdPreparationDisk()
                clickScannedRing(scenario, replacement)
                scenario.onActivity { RealCollectionBridge.begin(owner) }
                releaseDisk!!.countDown()
                awaitMonitorHit(monitor)
            }
            assertEquals(original, PreparationStore(preparationFile).read())
        } finally {
            releaseDisk?.countDown()
            instrumentation.runOnMainSync { RealCollectionBridge.detach(owner) }
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    fun clearIdentityNeedsConfirmationAndKeepsCollectionFiles() = withIsolatedFiles {
        completeProfile("P001")
        val marker = File(context.filesDir, "collection-real/history-kept.txt").apply {
            parentFile!!.mkdirs()
            writeText("kept", Charsets.UTF_8)
        }
        launch(settings = true).use { scenario ->
            awaitHeading(scenario, "设置")
            click(scenario, "clear_profile")
            val dialog = awaitClearDialog(scenario)
            scenario.onActivity { dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick() }
            awaitHeading(scenario, "开始使用")
            assertNull(PreparationStore(preparationFile).read())
            assertEquals("kept", marker.readText(Charsets.UTF_8))
        }
    }

    @Test
    fun corruptPrimaryAndBackupOfferSelfServiceReregistrationWithoutDeletingHistory() = withIsolatedFiles {
        completeProfile("P001")
        preparationFile.writeText("damaged primary", Charsets.UTF_8)
        File(preparationFile.parentFile, "${preparationFile.name}.last-good")
            .writeText("damaged backup", Charsets.UTF_8)
        val marker = File(context.filesDir, "collection-real/history-kept.txt").apply {
            parentFile!!.mkdirs()
            writeText("kept", Charsets.UTF_8)
        }

        launch(settings = true).use { scenario ->
            awaitHeading(scenario, "重新登记")
            scenario.onActivity { activity ->
                val feedback = tagged<TextView>(activity, "feedback").text.toString()
                assertTrue(feedback.contains("重新登记"))
                assertFalse(feedback.contains("联系研究者"))
            }
            click(scenario, "primary")
            awaitHeading(scenario, "开始使用")
            assertNull(PreparationStore(preparationFile).read())
            assertEquals("kept", marker.readText(Charsets.UTF_8))
        }
    }

    private fun completeProfile(label: String): PreparationSnapshot {
        val store = PreparationStore(preparationFile)
        store.register(
            label,
            RingPlacement.RIGHT_INDEX,
            PreparationIdentityType.RESEARCH_ID,
        )
        return store.selectRing(ring)
    }

    private fun assertNormalLaunchRedirects() {
        val monitor = blockingCollectionMonitor()
        try {
            context.startActivity(Intent(context, StepPreparationActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
            awaitMonitorHit(monitor)
            instrumentation.waitForIdleSync()
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun blockingCollectionMonitor(): Instrumentation.ActivityMonitor {
        val result = Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
        return Instrumentation.ActivityMonitor(RealCollectionActivity::class.java.name, result, true).also {
            instrumentation.addMonitor(it)
        }
    }

    private fun awaitMonitorHit(monitor: Instrumentation.ActivityMonitor) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (monitor.hits == 0 && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(25)
        assertTrue("RealCollectionActivity must be opened", monitor.hits > 0)
    }

    private fun launch(settings: Boolean = false): ActivityScenario<StepPreparationActivity> {
        val intent = Intent(context, StepPreparationActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(StepPreparationActivity.EXTRA_OPEN_SETTINGS, settings)
        }
        return ActivityScenario.launch(intent)
    }

    private fun grantBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            for (permission in listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)) {
                instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} $permission").close()
            }
        } else {
            instrumentation.uiAutomation.executeShellCommand(
                "pm grant ${context.packageName} ${Manifest.permission.ACCESS_FINE_LOCATION}",
            ).close()
        }
    }

    private fun deliverScanResult(scenario: ActivityScenario<StepPreparationActivity>, scanned: ScannedRing) {
        await(scenario, "scanner ready") { activity ->
            StepPreparationActivity::class.java.getDeclaredField("scanner").apply { isAccessible = true }
                .get(activity) != null
        }
        scenario.onActivity { activity ->
            val scanner = StepPreparationActivity::class.java.getDeclaredField("scanner")
                .apply { isAccessible = true }.get(activity) as RingBleClient
            scanner.stop()
            val listener = RingBleClient::class.java.getDeclaredField("listener")
                .apply { isAccessible = true }.get(scanner) as RingBleClient.Listener
            listener.onRingsFound(listOf(scanned))
            listener.onBleState("搜索完成，请选择要连接的戒指", false)
        }
        await(scenario, "ring result") { activity ->
            scannedRingButton(activity, scanned)?.let { it.isShown && it.isEnabled } == true
        }
    }

    private fun clickScannedRing(scenario: ActivityScenario<StepPreparationActivity>, prepared: PreparedRing) {
        scenario.onActivity { activity ->
            val button = requireNotNull(scannedRingButton(activity, ScannedRing(prepared.address, prepared.name, -45)))
            assertTrue(button.performClick())
        }
    }

    private fun scannedRingButton(activity: StepPreparationActivity, scanned: ScannedRing): Button? =
        descendants(activity.findViewById(android.R.id.content)).filterIsInstance<Button>()
            .firstOrNull { it.text.toString() == "${scanned.name} · ${scanned.address.takeLast(5)}" }

    private fun awaitIdentityDialog(scenario: ActivityScenario<StepPreparationActivity>): AlertDialog {
        lateinit var result: AlertDialog
        await(scenario, "identity dialog") { activity ->
            val dialog = StepPreparationActivity::class.java.getDeclaredField("identityDialog")
                .apply { isAccessible = true }.get(activity) as AlertDialog?
            if (dialog?.isShowing == true) result = dialog
            dialog?.isShowing == true
        }
        return result
    }

    private fun awaitPlacementDialog(scenario: ActivityScenario<StepPreparationActivity>): AlertDialog {
        lateinit var result: AlertDialog
        await(scenario, "placement dialog") { activity ->
            val dialog = StepPreparationActivity::class.java.getDeclaredField("placementDialog")
                .apply { isAccessible = true }.get(activity) as AlertDialog?
            if (dialog?.isShowing == true) result = dialog
            dialog?.isShowing == true
        }
        return result
    }

    private fun awaitClearDialog(scenario: ActivityScenario<StepPreparationActivity>): AlertDialog {
        lateinit var result: AlertDialog
        await(scenario, "clear confirmation") { activity ->
            val dialog = StepPreparationActivity::class.java.getDeclaredField("clearDialog")
                .apply { isAccessible = true }.get(activity) as AlertDialog?
            if (dialog?.isShowing == true) result = dialog
            dialog?.isShowing == true
        }
        return result
    }

    private fun click(scenario: ActivityScenario<StepPreparationActivity>, tag: String) {
        scenario.onActivity { activity ->
            val button = tagged<Button>(activity, tag)
            assertTrue("$tag is visible and enabled", button.isShown && button.isEnabled)
            val handled = button.performClick()
            if (button is RadioButton) assertTrue("$tag must become selected", button.isChecked)
            else assertTrue("$tag must handle the click", handled)
        }
    }

    private fun choosePlacement(
        scenario: ActivityScenario<StepPreparationActivity>,
        placement: RingPlacement,
    ) {
        val position = placement.ordinal + 1
        scenario.onActivity { activity ->
            tagged<Spinner>(activity, "placement_picker").setSelection(position)
        }
        await(scenario, "placement ${placement.name}") { activity ->
            tagged<Spinner>(activity, "placement_picker").selectedItemPosition == position
        }
        instrumentation.waitForIdleSync()
    }

    private fun awaitHeading(scenario: ActivityScenario<StepPreparationActivity>, value: String) {
        await(scenario, "heading $value") { activity ->
            tagged<TextView>(activity, "heading").text.toString() == value &&
                tagged<Button>(activity, "primary").let { it.isShown && it.isEnabled }
        }
    }

    private fun setBoolean(activity: StepPreparationActivity, field: String, value: Boolean) {
        StepPreparationActivity::class.java.getDeclaredField(field).apply { isAccessible = true }
            .setBoolean(activity, value)
    }

    private fun updateViews(activity: StepPreparationActivity) {
        StepPreparationActivity::class.java.getDeclaredMethod("updateViews").apply { isAccessible = true }
            .invoke(activity)
    }

    private fun assertNoVisiblePseudoAction(activity: StepPreparationActivity) {
        val pseudoActions = setOf("正在读取…", "正在保存…", "请稍候", "正在读取本机信息", "正在保存")
        assertFalse(descendants(activity.findViewById(android.R.id.content)).filterIsInstance<Button>()
            .any { it.isShown && it.text.toString() in pseudoActions })
    }

    private fun await(
        scenario: ActivityScenario<StepPreparationActivity>,
        description: String,
        condition: (StepPreparationActivity) -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        do {
            var satisfied = false
            scenario.onActivity { satisfied = condition(it) }
            if (satisfied) return
            SystemClock.sleep(25)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("Timed out waiting for $description")
    }

    private fun <T : View> tagged(activity: StepPreparationActivity, tag: String): T {
        val view = activity.findViewById<View>(android.R.id.content).findViewWithTag<T>(tag)
        assertNotNull("Missing tagged view $tag", view)
        return requireNotNull(view)
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun withIsolatedFiles(test: () -> Unit) {
        assumeTrue(
            "Navigation storage tests run only on an emulator",
            Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("emulator") ||
                Build.MODEL.contains("Android SDK") || Build.PRODUCT.contains("sdk"),
        )
        waitForPreparationDisk()
        val roots = listOf(File(context.filesDir, "preparation"), File(context.filesDir, "collection-real"))
        val backup = File(context.cacheDir, "preparation-navigation-backup-${System.nanoTime()}")
        check(backup.mkdirs())
        roots.forEachIndexed { index, root -> if (root.exists()) root.copyRecursively(File(backup, index.toString()), true) }
        roots.forEach { if (it.exists()) check(it.deleteRecursively()) }
        try {
            test()
        } finally {
            waitForPreparationDisk()
            roots.forEach { if (it.exists()) check(it.deleteRecursively()) }
            roots.forEachIndexed { index, root ->
                val saved = File(backup, index.toString())
                if (saved.exists()) check(saved.copyRecursively(root, true))
            }
            check(backup.deleteRecursively())
        }
    }

    private fun waitForPreparationDisk() {
        val disk = StepPreparationActivity::class.java.getDeclaredField("disk").apply { isAccessible = true }
            .get(null) as ExecutorService
        disk.submit {}.get(10, TimeUnit.SECONDS)
    }

    private fun holdPreparationDisk(): CountDownLatch {
        val disk = StepPreparationActivity::class.java.getDeclaredField("disk").apply { isAccessible = true }
            .get(null) as ExecutorService
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        disk.submit {
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
        }
        assertTrue("Preparation disk queue must be held", entered.await(10, TimeUnit.SECONDS))
        return release
    }
}
