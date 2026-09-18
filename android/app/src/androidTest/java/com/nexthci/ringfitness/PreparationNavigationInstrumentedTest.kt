package com.nexthci.ringfitness

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
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
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
 * Opt-in emulator checks of the real Activity and private storage. No BLE operations are requested.
 * The durable backup stays in filesDir if instrumentation is interrupted; a later run refuses to
 * overwrite it. The ordinary instrumented suite therefore never replaces a real phone's profile.
 */
@RunWith(AndroidJUnit4::class)
class PreparationNavigationInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun firstUseMovesFromParticipantToPlacementToDeviceAndReturnsToOverview() = withProfile { profile ->
        launch().use { scenario ->
            awaitHeading(scenario, "欢迎使用步数采集")
            typeParticipant(scenario, "NAV001")
            click(scenario, "primary")
            awaitHeading(scenario, "确认佩戴位置")
            val registered = requireNotNull(PreparationStore(profile).read())
            assertEquals("nav001", registered.participantId)
            assertNull(registered.placement)
            assertNull(registered.ring)

            choosePlacement(scenario, RingPlacement.RIGHT_RING)
            click(scenario, "primary")
            awaitHeading(scenario, "连接戒指")
            val prepared = requireNotNull(PreparationStore(profile).read())
            assertEquals(registered.copy(placement = RingPlacement.RIGHT_RING), prepared)
            click(scenario, "back")
            awaitHeading(scenario, "步数采集")
            assertNoEnabledCaptureOrLegacyEntry(scenario)
        }
        launch().use { reopened ->
            awaitHeading(reopened, "步数采集")
            assertVisibleText(reopened, "被试编号：nav001")
            assertVisibleText(reopened, RingPlacement.RIGHT_RING.displayName)
            click(reopened, "open_device")
            awaitHeading(reopened, "连接戒指")
            click(reopened, "back")
            awaitHeading(reopened, "步数采集")
        }
    }

    @Test
    fun unchangedPlacementCanContinueAndCancelledDraftDoesNotAlterConfirmedDetails() = withProfile { profile ->
        seedProfile(profile)
        val original = profile.readBytes()
        launch().use { scenario ->
            awaitHeading(scenario, "步数采集")
            click(scenario, "edit_placement")
            awaitHeading(scenario, "确认佩戴位置")
            choosePlacement(scenario, RingPlacement.LEFT_RING)
            click(scenario, "back")
            awaitHeading(scenario, "步数采集")
            assertArrayEquals(original, profile.readBytes())
            assertVisibleText(scenario, RingPlacement.RIGHT_RING.displayName)

            click(scenario, "edit_placement")
            awaitHeading(scenario, "确认佩戴位置")
            scenario.onActivity { activity ->
                val picker = tagged<Spinner>(activity, "placement_picker")
                assertEquals(RingPlacement.RIGHT_RING.ordinal + 1, picker.selectedItemPosition)
                assertTrue(tagged<Button>(activity, "primary").isEnabled)
            }
            click(scenario, "primary")
            awaitHeading(scenario, "步数采集")
            assertArrayEquals(original, profile.readBytes())
            assertNoEnabledCaptureOrLegacyEntry(scenario)
        }
    }

    @Test
    fun recreationKeepsDraftAndPageWhileColdOpenUsesOnlyConfirmedPreparation() = withProfile { profile ->
        launch().use { scenario ->
            awaitHeading(scenario, "欢迎使用步数采集")
            typeParticipant(scenario, "NAVDRAFT")
            scenario.recreate()
            awaitHeading(scenario, "欢迎使用步数采集")
            scenario.onActivity { activity ->
                assertEquals("NAVDRAFT", tagged<EditText>(activity, "participant_input").text.toString())
            }
            assertFalse(profile.exists())
            click(scenario, "primary")
            awaitHeading(scenario, "确认佩戴位置")
            choosePlacement(scenario, RingPlacement.LEFT_RING)
            scenario.recreate()
            awaitHeading(scenario, "确认佩戴位置")
            await(scenario, "restored unsaved placement") { activity ->
                tagged<Spinner>(activity, "placement_picker").selectedItemPosition == RingPlacement.LEFT_RING.ordinal + 1
            }
            assertNull(PreparationStore(profile).read()?.placement)
        }
        launch().use { reopened ->
            awaitHeading(reopened, "步数采集")
            assertVisibleText(reopened, "被试编号：navdraft")
            assertVisibleText(reopened, "尚未确认")
            click(reopened, "primary")
            awaitHeading(reopened, "确认佩戴位置")
            reopened.onActivity { activity ->
                assertEquals(0, tagged<Spinner>(activity, "placement_picker").selectedItemPosition)
            }
        }
    }

    @Test
    fun saveFailureKeepsRegistrationAndAllowsRetryAfterTheFileProblemIsRemoved() = withProfile { profile ->
        launch().use { scenario ->
            awaitHeading(scenario, "欢迎使用步数采集")
            // An empty directory at the file path simulates an unavailable save target.
            assertTrue(profile.mkdirs())
            typeParticipant(scenario, "NAVRETRY")
            click(scenario, "primary")
            await(scenario, "save failure feedback") { activity ->
                tagged<TextView>(activity, "feedback").text.contains("保存未成功") &&
                    tagged<Button>(activity, "primary").isEnabled
            }
            awaitHeading(scenario, "欢迎使用步数采集")
            assertTrue(profile.isDirectory)
            assertTrue(requireNotNull(profile.list()).isEmpty())
            scenario.onActivity { activity ->
                assertEquals("NAVRETRY", tagged<EditText>(activity, "participant_input").text.toString())
            }
            assertTrue(profile.delete())
            click(scenario, "primary")
            awaitHeading(scenario, "确认佩戴位置")
            assertEquals("navretry", PreparationStore(profile).read()?.participantId)
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
    fun deviceProgressTimeoutAndExistingRecordsOfferTheCorrespondingNavigation() = withProfile { profile ->
        seedProfile(profile)
        val original = profile.readBytes()
        val ring = requireNotNull(PreparationStore(profile).read()?.ring)
        val transport = NavigationTransport()
        lateinit var controller: RingPreparationController
        launch().use { scenario ->
            awaitHeading(scenario, "步数采集")
            scenario.onActivity { activity ->
                val controllerField = StepPreparationActivity::class.java.getDeclaredField("controller")
                    .apply { isAccessible = true }
                val updateViews = StepPreparationActivity::class.java.getDeclaredMethod("updateViews")
                    .apply { isAccessible = true }
                (controllerField.get(activity) as RingPreparationController).disconnect()
                controller = RingPreparationController(transport) { updateViews.invoke(activity) }
                controllerField.set(activity, controller)
                controller.connect(ring)
                val primary = tagged<Button>(activity, "primary")
                assertEquals("正在检查戒指…", primary.text.toString())
                assertFalse(primary.isEnabled)

                transport.listener.onConnection("测试连接完成", true)
                transport.listener.onPacket(SensorPacket.Health(HealthMessage.Status(false, 0, 0, 0, 1), 1))
                // STATUS alone cannot complete the screen's battery / firmware / status check.
                assertEquals("正在检查戒指…", primary.text.toString())
                assertFalse(primary.isEnabled)
                controller.timeout(controller.state.attemptId)
                assertEquals("重新检查戒指", primary.text.toString())
                assertTrue(primary.isEnabled)
            }
            click(scenario, "primary")
            awaitHeading(scenario, "连接戒指")
            scenario.onActivity { activity ->
                val primary = tagged<Button>(activity, "primary")
                assertEquals("重新连接检查", primary.text.toString())
                assertTrue(primary.isEnabled)
                assertEquals(1, transport.connectionCount)
            }
            click(scenario, "back")
            awaitHeading(scenario, "步数采集")
            scenario.onActivity { activity ->
                // The same connection supplies its remaining metadata and reports stored records.
                transport.listener.onPacket(SensorPacket.Battery(3900, 68, null, 2))
                transport.listener.onPacket(SensorPacket.Info(1, 1, 1, 2, 66, 0, 1, 2))
                transport.listener.onPacket(SensorPacket.Health(HealthMessage.Status(false, 32, 2, 0, 1), 2))
                assertFalse(controller.state.queryTimedOut)
                val primary = tagged<Button>(activity, "primary")
                assertEquals("查看待核对事项", primary.text.toString())
                assertTrue(primary.isEnabled)
            }
            click(scenario, "primary")
            awaitHeading(scenario, "连接戒指")
            assertVisibleText(scenario, "戒指已有记录待核对，请联系研究者处理")
            scenario.onActivity { activity ->
                assertEquals("返回准备概览", tagged<Button>(activity, "primary").text.toString())
            }
            click(scenario, "primary")
            awaitHeading(scenario, "步数采集")
            assertEquals("Opening details and returning must not reconnect", 1, transport.connectionCount)
            assertEquals(listOf("battery", "info", "status"), transport.queries)
            assertArrayEquals(original, profile.readBytes())
            assertNoEnabledCaptureOrLegacyEntry(scenario)
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

    private fun writeDurably(file: File, bytes: ByteArray) {
        val directory = requireNotNull(file.parentFile)
        check(directory.isDirectory || directory.mkdirs())
        FileOutputStream(file).use { stream -> stream.write(bytes); stream.fd.sync() }
    }

    private fun seedProfile(profile: File) {
        PreparationStore(profile).apply {
            register("NAV001")
            savePlacement(RingPlacement.RIGHT_RING)
            selectRing(PreparedRing("AA:BB:CC:DD:EE:01", "Ringo navigation test"))
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
        val queries = mutableListOf<String>()

        override fun connect(ring: PreparedRing, listener: PreparationTransport.Listener): Boolean {
            connectionCount += 1
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
