package com.nexthci.ringfitness

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in emulator checks of the real Activity and private storage. Device replies use a fake
 * transport; no real BLE connection, capture, download, or upload is requested.
 * The durable backup stays in filesDir if instrumentation is interrupted; a later run refuses to
 * overwrite it. The ordinary instrumented suite therefore never replaces a real phone's profile.
 */
@RunWith(AndroidJUnit4::class)
class PreparationNavigationInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun firstUseSavesCompletePreparationAndImmediatelySearchesWhileColdOpenReturnsHome() = withProfile { profile ->
        assumeBluetoothUiPrerequisites()
        val commits = AtomicInteger()
        launch().use { scenario ->
            awaitHeading(scenario, "填写准备信息")
            assertMinimalDebugActions(scenario)
            captureReviewScreen(scenario, "registration")
            replaceStore(scenario, countingStore(profile, commits))
            typeParticipant(scenario, "NAV001")
            scenario.onActivity { activity ->
                assertFalse("A placement is required before the single registration save",
                    tagged<Button>(activity, "primary").isEnabled)
            }
            choosePlacement(scenario, RingPlacement.RIGHT_RING)
            assertFalse("Drafts must not create a partial profile", profile.exists())
            click(scenario, "primary")
            awaitHeading(scenario, "选择戒指")
            captureReviewScreen(scenario, "search")
            val prepared = requireNotNull(PreparationStore(profile).read())
            assertEquals("nav001", prepared.participantId)
            assertEquals(RingPlacement.RIGHT_RING, prepared.placement)
            assertNull(prepared.ring)
            assertEquals("Registration commits one complete profile", 1, commits.get())
            click(scenario, "back")
            awaitHeading(scenario, "步数采集")
            assertMinimalDebugActions(scenario)
            scenario.onActivity { activity ->
                assertEquals("连接戒指", tagged<Button>(activity, "primary").text.toString())
                assertFalse(tagged<Button>(activity, "back").isShown)
            }
            assertNoEnabledCaptureOrLegacyEntry(scenario)
            captureReviewScreen(scenario, "home")
        }
        val original = profile.readBytes()
        launch().use { reopened ->
            awaitHeading(reopened, "步数采集")
            assertMinimalDebugActions(reopened)
            assertVisibleText(reopened, "编号：nav001")
            assertSavedPlacement(reopened, RingPlacement.RIGHT_RING)
            assertArrayEquals(original, profile.readBytes())
        }
    }

    @Test
    fun invalidRegistrationKeepsBothInputsAndCreatesNoProfile() = withProfile { profile ->
        launch().use { scenario ->
            awaitHeading(scenario, "填写准备信息")
            typeParticipant(scenario, "!")
            choosePlacement(scenario, RingPlacement.RIGHT_RING)
            click(scenario, "primary")
            await(scenario, "participant validation") { activity ->
                tagged<TextView>(activity, "feedback").text.contains("3–24") &&
                    tagged<Button>(activity, "primary").isEnabled
            }
            awaitHeading(scenario, "填写准备信息")
            captureReviewScreen(scenario, "invalid_registration")
            scenario.onActivity { activity ->
                assertEquals("!", tagged<EditText>(activity, "participant_input").text.toString())
                assertEquals(RingPlacement.RIGHT_RING.ordinal + 1,
                    tagged<Spinner>(activity, "placement_picker").selectedItemPosition)
            }
            assertFalse(profile.exists())
        }
    }

    @Test
    fun placementCancelAndSameValueKeepBytesAndChangedValueSavesWithoutAnotherConfirmation() = withProfile { profile ->
        seedProfile(profile)
        val original = profile.readBytes()
        val confirmed = requireNotNull(PreparationStore(profile).read())
        val commits = AtomicInteger()
        launch().use { scenario ->
            awaitHeading(scenario, "步数采集")
            replaceStore(scenario, countingStore(profile, commits))
            click(scenario, "edit_placement")
            awaitDialog(scenario)
            captureReviewScreen(scenario, "placement")
            scenario.onActivity { activity ->
                val dialog = requireNotNull(placementDialog(activity))
                assertEquals(RingPlacement.RIGHT_RING.ordinal, dialog.listView.checkedItemPosition)
                assertFalse("No second save confirmation is required",
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isShown == true)
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            }
            awaitDialogClosed(scenario)
            assertArrayEquals(original, profile.readBytes())
            assertSavedPlacement(scenario, RingPlacement.RIGHT_RING)
            click(scenario, "edit_placement")
            chooseDialogPlacement(scenario, RingPlacement.RIGHT_RING)
            awaitDialogClosed(scenario)
            assertArrayEquals(original, profile.readBytes())
            assertEquals("Cancel and unchanged selection never rewrite the profile", 0, commits.get())

            click(scenario, "edit_placement")
            chooseDialogPlacement(scenario, RingPlacement.LEFT_RING)
            awaitDialogClosed(scenario)
            assertSavedPlacement(scenario, RingPlacement.LEFT_RING)
            assertEquals(confirmed.copy(placement = RingPlacement.LEFT_RING), PreparationStore(profile).read())
            assertEquals("One changed selection commits once", 1, commits.get())
            assertNoEnabledCaptureOrLegacyEntry(scenario)
        }
    }

    @Test
    fun recreationKeepsRegistrationDraftButColdOpenDoesNotPretendItWasSaved() = withProfile { profile ->
        launch().use { scenario ->
            awaitHeading(scenario, "填写准备信息")
            typeParticipant(scenario, "NAVDRAFT")
            choosePlacement(scenario, RingPlacement.LEFT_RING)
            scenario.recreate()
            awaitHeading(scenario, "填写准备信息")
            scenario.onActivity { activity ->
                assertEquals("NAVDRAFT", tagged<EditText>(activity, "participant_input").text.toString())
            }
            await(scenario, "restored unsaved placement") { activity ->
                tagged<Spinner>(activity, "placement_picker").selectedItemPosition == RingPlacement.LEFT_RING.ordinal + 1
            }
            assertFalse(profile.exists())
        }
        launch().use { reopened ->
            awaitHeading(reopened, "填写准备信息")
            reopened.onActivity { activity ->
                assertEquals("", tagged<EditText>(activity, "participant_input").text.toString())
                assertEquals(0, tagged<Spinner>(activity, "placement_picker").selectedItemPosition)
            }
            assertFalse(profile.exists())
        }
    }

    @Test
    fun registrationSaveFailureLeavesNoPartialProfileAndCanRetryTheSameInputs() = withProfile { profile ->
        assumeBluetoothUiPrerequisites()
        launch().use { scenario ->
            awaitHeading(scenario, "填写准备信息")
            replaceStore(scenario, PreparationStore(profile) { _, _ -> throw IOException("simulated commit failure") })
            typeParticipant(scenario, "NAVRETRY")
            choosePlacement(scenario, RingPlacement.LEFT_RING)
            click(scenario, "primary")
            await(scenario, "save failure feedback") { activity ->
                tagged<TextView>(activity, "feedback").text.contains("保存失败") &&
                    tagged<Button>(activity, "primary").isEnabled
            }
            awaitHeading(scenario, "填写准备信息")
            assertFalse(profile.exists())
            captureReviewScreen(scenario, "save_failure")
            scenario.onActivity { activity ->
                assertEquals("NAVRETRY", tagged<EditText>(activity, "participant_input").text.toString())
                assertEquals(RingPlacement.LEFT_RING.ordinal + 1,
                    tagged<Spinner>(activity, "placement_picker").selectedItemPosition)
            }
            replaceStore(scenario, PreparationStore(profile))
            click(scenario, "primary")
            awaitHeading(scenario, "选择戒指")
            assertEquals("navretry", PreparationStore(profile).read()?.participantId)
            assertEquals(RingPlacement.LEFT_RING, PreparationStore(profile).read()?.placement)
        }
    }

    @Test
    fun placementSaveFailureRetainsConfirmedValueAndDialogAllowsRetry() = withProfile { profile ->
        seedProfile(profile)
        val original = profile.readBytes()
        launch().use { scenario ->
            awaitHeading(scenario, "步数采集")
            replaceStore(scenario, PreparationStore(profile) { _, _ -> throw IOException("simulated commit failure") })
            click(scenario, "edit_placement")
            chooseDialogPlacement(scenario, RingPlacement.LEFT_RING)
            await(scenario, "failed placement can be retried") { activity ->
                val dialog = placementDialog(activity)
                dialog?.isShowing == true && dialog.listView.isEnabled &&
                    dialog.listView.checkedItemPosition == RingPlacement.RIGHT_RING.ordinal
            }
            assertArrayEquals(original, profile.readBytes())
            assertSavedPlacement(scenario, RingPlacement.RIGHT_RING)
            scenario.onActivity { activity ->
                val dialog = requireNotNull(placementDialog(activity))
                assertTrue(descendants(requireNotNull(dialog.window).decorView).filterIsInstance<TextView>()
                    .any { it.isShown && it.text.toString() == "保存失败，请重新选择" })
            }
            captureReviewScreen(scenario, "placement_retry")
            replaceStore(scenario, PreparationStore(profile))
            chooseDialogPlacement(scenario, RingPlacement.LEFT_RING)
            awaitDialogClosed(scenario)
            assertEquals(RingPlacement.LEFT_RING, PreparationStore(profile).read()?.placement)
            assertSavedPlacement(scenario, RingPlacement.LEFT_RING)
        }
    }

    @Test
    fun savingPlacementBlocksFurtherChoicesAndCancellationUntilDurableCommitCompletes() = withProfile { profile ->
        seedProfile(profile)
        val original = profile.readBytes()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        launch().use { scenario ->
            awaitHeading(scenario, "步数采集")
            replaceStore(scenario, PreparationStore(profile) { source, target ->
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "Test did not release the paused save" }
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            })
            click(scenario, "edit_placement")
            try {
                chooseDialogPlacement(scenario, RingPlacement.LEFT_RING)
                assertTrue("Save reached the commit boundary", entered.await(5, TimeUnit.SECONDS))
                scenario.onActivity { activity ->
                    val dialog = requireNotNull(placementDialog(activity))
                    assertTrue(dialog.isShowing)
                    assertFalse(dialog.listView.isEnabled)
                    assertFalse(dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled)
                    assertFalse(tagged<Button>(activity, "primary").isEnabled)
                    assertFalse(tagged<Button>(activity, "edit_placement").isEnabled)
                }
                assertArrayEquals("Before commit the last confirmed value remains", original, profile.readBytes())
            } finally {
                release.countDown()
            }
            awaitDialogClosed(scenario)
            assertEquals(RingPlacement.LEFT_RING, PreparationStore(profile).read()?.placement)
            assertSavedPlacement(scenario, RingPlacement.LEFT_RING)
        }
    }

    @Test
    fun earlierProfileWithoutPlacementIsCompletedWithoutReplacingIdentityOrRing() = withProfile { profile ->
        val store = PreparationStore(profile)
        store.register("NAVLEGACY")
        store.selectRing(PreparedRing("AA:BB:CC:DD:EE:01", "Ringo navigation test"))
        val original = requireNotNull(store.read())
        val bytes = profile.readBytes()
        launch().use { scenario ->
            awaitHeading(scenario, "步数采集")
            assertVisibleText(scenario, "编号：navlegacy")
            assertArrayEquals("Opening an older profile must not rewrite it", bytes, profile.readBytes())
            click(scenario, "primary")
            chooseDialogPlacement(scenario, RingPlacement.RIGHT_RING)
            awaitDialogClosed(scenario)
            assertEquals(original.copy(placement = RingPlacement.RIGHT_RING), store.read())
            assertSavedPlacement(scenario, RingPlacement.RIGHT_RING)
            assertNoEnabledCaptureOrLegacyEntry(scenario)
        }
    }

    @Test
    fun corruptPreparationIsPreservedAndCannotAdvance() = withProfile { profile ->
        val corrupt = "version=1\nparticipant_id=navcorrupt\n".toByteArray(Charsets.UTF_8)
        writeDurably(profile, corrupt)
        launch().use { scenario ->
            await(scenario, "corrupt profile feedback") { activity ->
                tagged<TextView>(activity, "feedback").text.contains("准备信息读取失败")
            }
            scenario.onActivity { activity ->
                assertFalse(tagged<Button>(activity, "primary").isEnabled)
                assertFalse(tagged<EditText>(activity, "participant_input").isShown)
            }
            scenario.recreate()
            await(scenario, "corrupt profile feedback after recreation") { activity ->
                tagged<TextView>(activity, "feedback").text.contains("准备信息读取失败") &&
                    !tagged<Button>(activity, "primary").isEnabled
            }
            assertArrayEquals(corrupt, profile.readBytes())
            assertNoEnabledCaptureOrLegacyEntry(scenario)
        }
    }

    @Test
    fun oneHomeClickConnectsAndTimeoutRetriesWithoutAConfirmationPageOrStaleSuccess() = withProfile { profile ->
        assumeBluetoothUiPrerequisites()
        seedProfile(profile)
        lateinit var original: ByteArray
        val transport = NavigationTransport()
        lateinit var controller: RingPreparationController
        launch().use { scenario ->
            awaitHeading(scenario, "步数采集")
            scenario.onActivity { activity ->
                controller = attachFakeRing(activity, profile, transport)
                original = profile.readBytes()
            }
            click(scenario, "primary")
            awaitHeading(scenario, "步数采集")
            lateinit var oldListener: PreparationTransport.Listener
            scenario.onActivity { activity ->
                assertEquals("One home click creates exactly one connection", 1, transport.connectionCount)
                val primary = tagged<Button>(activity, "primary")
                assertFalse(primary.isEnabled)
                assertEquals("正在连接…", primary.text.toString())
                transport.listener.onConnection("测试连接完成", true)
                transport.listener.onPacket(SensorPacket.Health(HealthMessage.Status(false, 0, 0, 0, 1), 1))
                assertEquals("正在检查…", primary.text.toString())
                assertFalse(primary.isEnabled)
                assertFalse(tagged<TextView>(activity, "device_status").text.contains("准备完成"))
                assertEquals(listOf("battery", "info", "status"), transport.queries)
                oldListener = transport.listener
                controller.timeout(controller.state.attemptId)
                assertTrue(primary.isEnabled)
            }
            click(scenario, "primary")
            awaitHeading(scenario, "步数采集")
            scenario.onActivity { activity ->
                assertEquals("Retry directly creates just one new connection", 2, transport.connectionCount)
                oldListener.onConnection("迟到的旧连接", true)
                fillMetadata(oldListener)
                assertNull(controller.state.healthStatus)
                assertTrue(controller.state.connecting)
                assertFalse(tagged<Button>(activity, "primary").isEnabled)
                transport.listener.onConnection("重试连接完成", true)
                transport.listener.onConnection("重复连接通知", true)
                fillMetadata(transport.listener)
                assertFalse(controller.state.queryTimedOut)
                assertTrue(controller.state.canPrepare)
                val primary = tagged<Button>(activity, "primary")
                assertFalse("Preparation alone does not enable capture", primary.isEnabled)
                assertEquals("开始采集（待开放）", primary.text.toString())
                assertTrue(tagged<TextView>(activity, "device_status").text.contains("准备完成"))
                assertEquals(listOf("battery", "info", "status", "battery", "info", "status"), transport.queries)
            }
            assertArrayEquals(original, profile.readBytes())
            assertNoEnabledCaptureOrLegacyEntry(scenario)
            captureReviewScreen(scenario, "ready")
        }
    }

    @Test
    fun coldOpenConnectsOnceAndCancellationDoesNotTriggerAnotherAutomaticAttempt() = withProfile { profile ->
        assumeBluetoothUiPrerequisites()
        seedProfile(profile)
        val transport = NavigationTransport()
        lateinit var original: ByteArray
        launch().use { reopened ->
            awaitHeading(reopened, "步数采集")
            reopened.onActivity { activity ->
                attachFakeRing(activity, profile, transport)
                original = profile.readBytes()
                StepPreparationActivity::class.java.getDeclaredField("autoConnectPending")
                    .apply { isAccessible = true }.setBoolean(activity, true)
                val prepareOnOpen = StepPreparationActivity::class.java.getDeclaredMethod("prepareOnOpen")
                    .apply { isAccessible = true }
                prepareOnOpen.invoke(activity)
                prepareOnOpen.invoke(activity)
                assertEquals("A cold open consumes its automatic attempt once", 1, transport.connectionCount)
                assertFalse(tagged<Button>(activity, "primary").isEnabled)
            }
            click(reopened, "connection_cancel")
            reopened.onActivity { activity ->
                StepPreparationActivity::class.java.getDeclaredMethod("prepareOnOpen")
                    .apply { isAccessible = true }.invoke(activity)
                assertEquals("Cancel does not immediately reconnect", 1, transport.connectionCount)
                assertTrue(tagged<Button>(activity, "primary").isEnabled)
                assertFalse(tagged<TextView>(activity, "device_status").text.contains("准备完成"))
            }
            click(reopened, "primary")
            assertEquals("Only the next explicit action retries", 2, transport.connectionCount)
            assertArrayEquals(original, profile.readBytes())
            reopened.recreate()
            awaitHeading(reopened, "步数采集")
            reopened.onActivity { activity ->
                assertEquals("Recreation requires a fresh explicit check, without a second automatic attempt",
                    "连接戒指", tagged<Button>(activity, "primary").text.toString())
                assertFalse(tagged<TextView>(activity, "device_status").text.contains("准备完成"))
            }
            assertEquals(2, transport.connectionCount)
            assertArrayEquals(original, profile.readBytes())
        }
    }

    @Test
    fun selectedScanResultConnectsOnlyAfterItsProfileSaveSucceedsAndCanRetryAfterFailure() = withProfile { profile ->
        assumeBluetoothUiPrerequisites()
        seedProfile(profile)
        val original = profile.readBytes()
        val confirmed = requireNotNull(PreparationStore(profile).read())
        val ring = ScannedRing("AA:BB:CC:DD:EE:02", "Ringo selection test", -42)
        val expectedRing = PreparedRing(ring.address, ring.name)
        val transport = NavigationTransport()
        launch().use { scenario ->
            awaitHeading(scenario, "步数采集")
            scenario.onActivity { activity -> replaceController(activity, transport) }
            replaceStore(scenario, PreparationStore(profile) { _, _ -> throw IOException("simulated ring commit failure") })
            click(scenario, "primary")
            awaitHeading(scenario, "选择戒指")
            deliverScanResult(scenario, ring)
            clickScannedRing(scenario, ring)
            await(scenario, "ring save failure remains retryable") { activity ->
                tagged<TextView>(activity, "feedback").text.contains("保存失败") &&
                    tagged<Button>(activity, "primary").isEnabled
            }
            awaitHeading(scenario, "选择戒指")
            assertEquals("An unsaved selection must not start a connection", 0, transport.connectionCount)
            assertArrayEquals("Failed ring selection preserves the last confirmed profile", original, profile.readBytes())
            assertEquals(confirmed, PreparationStore(profile).read())

            replaceStore(scenario, PreparationStore(profile))
            click(scenario, "primary")
            deliverScanResult(scenario, ring)
            clickScannedRing(scenario, ring)
            awaitHeading(scenario, "步数采集")
            scenario.onActivity { activity ->
                assertEquals("Successful selection connects exactly once", 1, transport.connectionCount)
                assertEquals(listOf(expectedRing), transport.connectedRings)
                assertEquals("正在连接…", tagged<Button>(activity, "primary").text.toString())
                assertFalse(tagged<Button>(activity, "primary").isEnabled)
                assertFalse(tagged<TextView>(activity, "device_status").text.contains("准备完成"))
            }
            assertEquals(confirmed.copy(ring = expectedRing), PreparationStore(profile).read())
            assertNoEnabledCaptureOrLegacyEntry(scenario)
        }
    }

    @Test
    fun deviceRecordWarningAppearsAtHomeWithoutEnablingCaptureOrChangingTheProfile() = withProfile { profile ->
        assumeBluetoothUiPrerequisites()
        seedProfile(profile)
        lateinit var original: ByteArray
        val transport = NavigationTransport()
        launch().use { scenario ->
            awaitHeading(scenario, "步数采集")
            scenario.onActivity { activity ->
                val controller = attachFakeRing(activity, profile, transport)
                original = profile.readBytes()
                controller.connect(requireNotNull(PreparationStore(profile).read()?.ring))
                transport.listener.onConnection("测试连接完成", true)
                fillMetadata(transport.listener, bytes = 32, records = 2)
                assertFalse(controller.state.canPrepare)
                val status = tagged<TextView>(activity, "device_status")
                assertEquals("戒指已连接", status.text.toString())
                val explanation = StepPreparationActivity::class.java.getDeclaredField("homeExplanation")
                    .apply { isAccessible = true }.get(activity) as TextView
                assertTrue(explanation.isShown)
                assertEquals(controller.state.message, explanation.text.toString())
                assertTrue("The record warning is secondary text, below the connection status",
                    explanation.textSize < status.textSize)
            }
            assertVisibleText(scenario, "采集准备")
            captureReviewScreen(scenario, "device_records")
            assertArrayEquals(original, profile.readBytes())
            assertNoEnabledCaptureOrLegacyEntry(scenario)
            assertEquals(listOf("battery", "info", "status"), transport.queries)
        }
    }

    private fun withProfile(test: (File) -> Unit) {
        assumeTrue("Explicit emulator navigation opt-in is required",
            InstrumentationRegistry.getArguments().getString("verifyPreparationNavigation") == "true")
        val emulator = Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
            Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
            Build.MODEL.contains("Emulator", ignoreCase = true) ||
            Build.MODEL.startsWith("sdk_gphone") || Build.MODEL.startsWith("Android SDK built for")
        assumeTrue("Profile-changing navigation checks are restricted to an emulator", emulator)
        val profile = File(instrumentation.targetContext.filesDir, "preparation/profile.properties")
        val directory = requireNotNull(profile.parentFile)
        val backup = File(instrumentation.targetContext.filesDir, "preparation-navigation-recovery")
        check(!backup.exists()) { "Recover the earlier navigation test backup before continuing: $backup" }
        check(!profile.exists() || profile.isFile) { "Unexpected profile path; leaving it untouched" }
        val hadDirectory = directory.exists()
        val original = if (profile.exists()) profile.readBytes() else null
        check(backup.mkdir()) { "Cannot create a durable profile backup" }
        if (original != null) writeDurably(File(backup, "profile.properties.original"), original)
        writeDurably(File(backup, "recovery.txt"),
            ("profile_existed=${original != null}\npreparation_directory_existed=$hadDirectory\n" +
                "target=preparation/profile.properties\n").toByteArray(Charsets.UTF_8))
        try {
            if (profile.exists()) check(profile.delete())
            test(profile)
        } finally {
            // ActivityScenario.use closes each screen before this barrier. Drain queued saves so
            // an asynchronous operation cannot replace the original after restoration.
            val diskField = StepPreparationActivity::class.java.getDeclaredField("disk").apply { isAccessible = true }
            (diskField.get(null) as ExecutorService).submit {}.get(10, TimeUnit.SECONDS)
            if (profile.isDirectory) {
                check(requireNotNull(profile.list()).isEmpty()) { "Unexpected files at profile path; backup retained" }
                check(profile.delete())
            }
            if (original == null) {
                if (profile.exists()) check(profile.delete())
                check(!profile.exists())
            } else {
                val restored = File(directory, "navigation-profile-restore.tmp")
                check(!restored.exists()) { "Recovery staging path already exists; backup retained" }
                writeDurably(restored, original)
                Files.move(restored.toPath(), profile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                assertArrayEquals(original, profile.readBytes())
            }
            if (!hadDirectory && directory.exists() && requireNotNull(directory.list()).isEmpty()) check(directory.delete())
            val saved = File(backup, "profile.properties.original")
            if (saved.exists()) check(saved.delete())
            check(File(backup, "recovery.txt").delete())
            check(backup.delete())
        }
    }

    private fun captureReviewScreen(scenario: ActivityScenario<StepPreparationActivity>, page: String) {
        if (InstrumentationRegistry.getArguments().getString("captureFlowScreens") != "true") return
        require(page.matches(Regex("[a-z_]+")))
        // Screenshots should show the settled page, including the soft-keyboard transition.
        SystemClock.sleep(800)
        val frameDrawn = CountDownLatch(1)
        scenario.onActivity { activity ->
            val root = placementDialog(activity)?.takeIf { it.isShowing }?.window?.decorView
                ?: activity.window.decorView
            root.viewTreeObserver.registerFrameCommitCallback { frameDrawn.countDown() }
            root.invalidate()
        }
        assertTrue("The review page must be drawn before its screenshot", frameDrawn.await(5, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            FileOutputStream(File(instrumentation.targetContext.cacheDir, "prep-review-$page.png")).use {
                assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
                it.fd.sync()
            }
        } finally { screenshot.recycle() }
    }

    private fun writeDurably(file: File, bytes: ByteArray) {
        val directory = requireNotNull(file.parentFile)
        check(directory.isDirectory || directory.mkdirs())
        FileOutputStream(file).use { stream -> stream.write(bytes); stream.fd.sync() }
    }

    private fun seedProfile(profile: File) {
        // The ring is attached only after installing a fake transport. This prevents the real
        // cold-open path from attempting a native connection before ActivityScenario returns.
        PreparationStore(profile).register("NAV001", RingPlacement.RIGHT_RING)
    }

    private fun replaceStore(scenario: ActivityScenario<StepPreparationActivity>, value: PreparationStore) {
        scenario.onActivity { activity ->
            StepPreparationActivity::class.java.getDeclaredField("store")
                .apply { isAccessible = true }.set(activity, value)
        }
    }

    private fun countingStore(profile: File, commits: AtomicInteger) = PreparationStore(profile) { source, target ->
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        commits.incrementAndGet()
    }

    private fun replaceController(activity: StepPreparationActivity, transport: NavigationTransport): RingPreparationController {
        val field = StepPreparationActivity::class.java.getDeclaredField("controller").apply { isAccessible = true }
        val updateViews = StepPreparationActivity::class.java.getDeclaredMethod("updateViews").apply { isAccessible = true }
        (field.get(activity) as RingPreparationController).disconnect()
        return RingPreparationController(transport) { updateViews.invoke(activity) }.also { field.set(activity, it) }
    }

    private fun attachFakeRing(activity: StepPreparationActivity, profile: File,
                               transport: NavigationTransport): RingPreparationController {
        val controller = replaceController(activity, transport)
        val snapshot = PreparationStore(profile).selectRing(PreparedRing("AA:BB:CC:DD:EE:01", "Ringo navigation test"))
        StepPreparationActivity::class.java.getDeclaredField("snapshot").apply { isAccessible = true }
            .set(activity, snapshot)
        StepPreparationActivity::class.java.getDeclaredMethod("updateViews").apply { isAccessible = true }
            .invoke(activity)
        return controller
    }

    private fun assumeBluetoothUiPrerequisites() {
        val context = instrumentation.targetContext
        val permissions = if (Build.VERSION.SDK_INT >= 31) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        assumeTrue("Grant the app's Bluetooth permissions before the direct-action UI check",
            permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED })
        assumeTrue("Enable emulator Bluetooth before the direct-action UI check",
            context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true)
        if (Build.VERSION.SDK_INT <= 30) {
            assumeTrue("Android 11 scanning requires location enabled",
                context.getSystemService(android.location.LocationManager::class.java).isLocationEnabled)
        }
    }

    private fun fillMetadata(listener: PreparationTransport.Listener, bytes: Long = 0, records: Long = 0) {
        listener.onPacket(SensorPacket.Battery(3900, 68, null, 2))
        listener.onPacket(SensorPacket.Info(1, 1, 1, 2, 66, 0, 1, 2))
        listener.onPacket(SensorPacket.Health(HealthMessage.Status(false, bytes, records, 0, 1), 2))
    }

    private fun deliverScanResult(scenario: ActivityScenario<StepPreparationActivity>, ring: ScannedRing) {
        instrumentation.waitForIdleSync()
        scenario.onActivity { activity ->
            val scanner = StepPreparationActivity::class.java.getDeclaredField("scanner")
                .apply { isAccessible = true }.get(activity) as RingBleClient
            // Stop native discovery before providing a deterministic result to the Activity's
            // real callback. Its connection still uses NavigationTransport throughout this test.
            scanner.stop()
            val listener = RingBleClient::class.java.getDeclaredField("listener")
                .apply { isAccessible = true }.get(scanner) as RingBleClient.Listener
            listener.onRingsFound(listOf(ring))
            listener.onBleState("搜索完成，请选择要连接的戒指", false)
        }
        await(scenario, "scan result is selectable") { activity ->
            scannedRingButton(activity, ring)?.let { it.isShown && it.isEnabled } == true
        }
    }

    private fun scannedRingButton(activity: StepPreparationActivity, ring: ScannedRing): Button? =
        descendants(activity.findViewById(android.R.id.content)).filterIsInstance<Button>()
            .firstOrNull { it.text.toString() == "${ring.name} · ${ring.address.takeLast(5)}" }

    private fun clickScannedRing(scenario: ActivityScenario<StepPreparationActivity>, ring: ScannedRing) {
        scenario.onActivity { activity ->
            val button = requireNotNull(scannedRingButton(activity, ring))
            assertTrue(button.isShown && button.isEnabled)
            assertTrue(button.performClick())
        }
    }

    private fun placementDialog(activity: StepPreparationActivity): AlertDialog? =
        StepPreparationActivity::class.java.getDeclaredField("placementDialog")
            .apply { isAccessible = true }.get(activity) as AlertDialog?

    private fun awaitDialog(scenario: ActivityScenario<StepPreparationActivity>) {
        await(scenario, "placement dialog ready") { activity ->
            placementDialog(activity)?.let { it.isShowing && it.listView.isEnabled } == true
        }
    }

    private fun awaitDialogClosed(scenario: ActivityScenario<StepPreparationActivity>) {
        await(scenario, "saved placement returns directly to home") { activity ->
            placementDialog(activity)?.isShowing != true &&
                tagged<TextView>(activity, "heading").text.toString() == "步数采集" &&
                tagged<Button>(activity, "primary").text.toString() != "正在保存…"
        }
    }

    private fun chooseDialogPlacement(scenario: ActivityScenario<StepPreparationActivity>, value: RingPlacement) {
        awaitDialog(scenario)
        scenario.onActivity { activity ->
            val list = requireNotNull(placementDialog(activity)).listView
            val row = list.adapter.getView(value.ordinal, null, list)
            assertTrue(list.performItemClick(row, value.ordinal, list.adapter.getItemId(value.ordinal)))
        }
    }

    private fun assertSavedPlacement(scenario: ActivityScenario<StepPreparationActivity>, value: RingPlacement) {
        scenario.onActivity { activity ->
            val edit = tagged<Button>(activity, "edit_placement")
            assertTrue(edit.isShown)
            assertTrue("Home shows the confirmed placement", edit.text.contains(value.displayName))
        }
    }

    private fun assertMinimalDebugActions(scenario: ActivityScenario<StepPreparationActivity>) {
        scenario.onActivity { activity ->
            val root = activity.findViewById<View>(android.R.id.content)
            assertNull("Demonstration entry belongs in More, not beside the primary preparation action",
                root.findViewWithTag<View>("open_demo"))
            val more = tagged<Button>(activity, "details")
            assertEquals("更多", more.text.toString())
            assertTrue("More is available before registration and after preparation", more.isShown && more.isEnabled)
        }
    }

    private fun launch(): ActivityScenario<StepPreparationActivity> {
        val context = instrumentation.targetContext
        return ActivityScenario.launch(requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName)))
    }

    private fun typeParticipant(scenario: ActivityScenario<StepPreparationActivity>, value: String) {
        scenario.onActivity { activity ->
            val input = tagged<EditText>(activity, "participant_input")
            assertTrue(input.isShown && input.isEnabled)
            input.setText(value)
        }
    }

    private fun choosePlacement(scenario: ActivityScenario<StepPreparationActivity>, value: RingPlacement) {
        scenario.onActivity { activity ->
            val picker = tagged<Spinner>(activity, "placement_picker")
            assertTrue(picker.isShown && picker.isEnabled)
            picker.setSelection(value.ordinal + 1)
        }
        await(scenario, "placement choice") { activity ->
            tagged<Spinner>(activity, "placement_picker").selectedItemPosition == value.ordinal + 1 &&
                tagged<Button>(activity, "primary").isEnabled
        }
        instrumentation.waitForIdleSync()
    }

    private fun click(scenario: ActivityScenario<StepPreparationActivity>, tag: String) {
        scenario.onActivity { activity ->
            val button = tagged<Button>(activity, tag)
            assertTrue("$tag must be visible and enabled", button.isShown && button.isEnabled)
            assertTrue(button.performClick())
        }
    }

    private fun awaitHeading(scenario: ActivityScenario<StepPreparationActivity>, value: String) {
        await(scenario, "page '$value'") { activity ->
            tagged<TextView>(activity, "heading").text.toString() == value &&
                tagged<Button>(activity, "primary").text.toString() !in listOf("正在读取…", "正在保存…")
        }
    }

    private fun await(scenario: ActivityScenario<StepPreparationActivity>, description: String,
                      condition: (StepPreparationActivity) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        do {
            var satisfied = false
            scenario.onActivity { satisfied = condition(it) }
            if (satisfied) return
            SystemClock.sleep(25)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("Timed out waiting for $description")
    }

    private fun assertVisibleText(scenario: ActivityScenario<StepPreparationActivity>, value: String) {
        scenario.onActivity { activity ->
            assertTrue("Visible text '$value'", descendants(activity.findViewById(android.R.id.content))
                .filterIsInstance<TextView>().any { it.isShown && it.text.toString() == value })
        }
    }

    private fun assertNoEnabledCaptureOrLegacyEntry(scenario: ActivityScenario<StepPreparationActivity>) {
        scenario.onActivity { activity ->
            assertFalse(descendants(activity.findViewById(android.R.id.content)).filterIsInstance<Button>().any {
                it.isShown && it.isEnabled && (it.text.contains("开始采集") || it.text.contains("登录") || it.text.contains("Oura"))
            })
        }
    }

    private fun <T : View> tagged(activity: StepPreparationActivity, tag: String): T {
        val view = activity.findViewById<View>(android.R.id.content).findViewWithTag<T>(tag)
        assertNotNull("Missing tagged view '$tag'", view)
        return requireNotNull(view)
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private class NavigationTransport : PreparationTransport {
        lateinit var listener: PreparationTransport.Listener
        var connectionCount = 0
            private set
        val connectedRings = mutableListOf<PreparedRing>()
        val queries = mutableListOf<String>()

        override fun connect(ring: PreparedRing, listener: PreparationTransport.Listener): Boolean {
            connectionCount += 1
            connectedRings += ring
            this.listener = listener
            return true
        }

        override fun disconnect() = Unit
        override fun requestBattery() = recordQuery("battery")
        override fun requestDeviceInfo() = recordQuery("info")
        override fun requestHealthStatus() = recordQuery("status")

        private fun recordQuery(name: String): Boolean {
            queries += name
            return true
        }
    }
}
