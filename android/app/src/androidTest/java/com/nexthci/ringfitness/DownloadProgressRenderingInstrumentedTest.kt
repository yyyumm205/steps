package com.nexthci.ringfitness

import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Native rendering checks; stores and device/transport events are isolated demo fixtures. */
@RunWith(AndroidJUnit4::class)
class DownloadProgressRenderingInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun startingANewActivityDoesNotShowThePreviousCompletedSessionsTime() = withFlow { handle ->
        launch().use { scenario ->
            val previous = stopped(handle).copy(localData = SessionLocalData(emptyList(), 4_000))
            val starting = base(previous).copy(page = CollectionPage.STARTING, busy = true,
                selectedActivity = SessionActivity.STRENGTH_TRAINING)
            render(scenario, starting)
            scenario.onActivity {
                val texts = allText(it.findViewById(android.R.id.content))
                assertFalse(texts.contains("开始时间"))
                assertFalse(texts.contains("羽毛球"))
                assertTrue(texts.contains("正在启动戒指"))
            }
            val pending = stopped(handle).copy(sessionId = "new-capture-rendering",
                activity = SessionActivity.STRENGTH_TRAINING,
                phase = FreeLivingSessionPhase.COLLECTING, stopRequestedAtMs = null,
                stopConfirmedAtMs = null, reference = null)
            for (state in listOf(
                starting.copy(page = CollectionPage.COLLECTING, session = pending, busy = false, canStop = true),
                starting.copy(page = CollectionPage.STOPPING,
                    session = pending.copy(phase = FreeLivingSessionPhase.STOP_REQUESTED,
                        stopRequestedAtMs = 2_900)),
            )) {
                render(scenario, state)
                scenario.onActivity {
                    val texts = allText(it.findViewById(android.R.id.content))
                    assertTrue(texts.contains("开始时间"))
                    assertTrue(texts.contains("力量训练"))
                    assertFalse(texts.contains("羽毛球"))
                }
            }
        }
    }

    @Test fun repeatedStatesAndResidualProgressKeepTheCurrentTaskHint() = withFlow { handle ->
        launch().use { scenario ->
            val parked = base(stopped(handle).copy(completionPolicy = CompletionPolicy.DEFER_ON_RING))
                .copy(page = CollectionPage.RING_PENDING, taskPage = CollectionPage.RING_PENDING)
            val preparing = base(null).copy(preservingExisting = true, busy = true)
            val connecting = parked.copy(session = stopped(handle), connected = false, connecting = true)
            for ((initial, expected) in listOf(
                parked to "下载到手机后，即可开始下一段。",
                preparing to "完成后即可选择本次活动。",
                connecting to "请将戒指放在手机附近。",
            )) {
                render(scenario, initial)
                lateinit var hint: TextView
                lateinit var status: TextView
                scenario.onActivity {
                    hint = tagged(it, "home_task_hint")
                    status = tagged(it, "home_task_status")
                    assertEquals(expected, hint.text.toString())
                }
                for (state in listOf(initial.copy(), initial.copy(downloadSavedBytes = 32,
                    downloadTotalBytes = 128), initial.copy(downloadSavedBytes = 128,
                    downloadTotalBytes = 128, downloadFinalizing = true))) {
                    render(scenario, state)
                    scenario.onActivity {
                        assertSame(hint, tagged<TextView>(it, "home_task_hint"))
                        assertSame(status, tagged<TextView>(it, "home_task_status"))
                        assertEquals(expected, hint.text.toString())
                        assertNull(taggedOrNull<ProgressBar>(it, "home_download_progress"))
                    }
                }
            }
            // Persisted defer consent takes precedence even if a stale task/counter arrives.
            val staleDownload = parked.copy(taskPage = CollectionPage.DOWNLOADING,
                downloadSavedBytes = 32, downloadTotalBytes = 128)
            render(scenario, staleDownload)
            render(scenario, staleDownload.copy(downloadSavedBytes = 64))
            scenario.onActivity {
                assertEquals("下载到手机后，即可开始下一段。",
                    tagged<TextView>(it, "home_task_hint").text.toString())
                assertEquals("下载并上传", tagged<Button>(it, "home_task_action").text.toString())
                assertNull(taggedOrNull<ProgressBar>(it, "home_download_progress"))
            }
        }
    }

    @Test fun activeDownloadProgressAndFinalizationUpdateTheExistingViews() = withFlow { handle ->
        launch().use { scenario ->
            val initial = base(stopped(handle)).copy(page = CollectionPage.DOWNLOADING,
                taskPage = CollectionPage.DOWNLOADING, busy = true,
                downloadSavedBytes = 256, downloadTotalBytes = 1_024)
            render(scenario, initial)
            lateinit var hint: TextView
            lateinit var progress: ProgressBar
            lateinit var status: TextView
            scenario.onActivity {
                hint = tagged(it, "home_task_hint")
                progress = tagged(it, "home_download_progress")
                status = tagged(it, "home_task_status")
                assertTrue(hint.text.toString().startsWith("蓝牙传输 25%"))
                assertEquals(250, progress.progress)
            }
            render(scenario, initial.copy())
            render(scenario, initial.copy(downloadSavedBytes = 768))
            scenario.onActivity {
                assertSame(hint, tagged<TextView>(it, "home_task_hint"))
                assertSame(progress, tagged<ProgressBar>(it, "home_download_progress"))
                assertSame(status, tagged<TextView>(it, "home_task_status"))
                assertTrue(hint.text.toString().startsWith("蓝牙传输 75%"))
                assertEquals(750, progress.progress)
            }
            render(scenario, initial.copy(downloadSavedBytes = 1_024, downloadFinalizing = true))
            scenario.onActivity {
                assertSame(hint, tagged<TextView>(it, "home_task_hint"))
                assertSame(progress, tagged<ProgressBar>(it, "home_download_progress"))
                assertSame(status, tagged<TextView>(it, "home_task_status"))
                assertTrue(hint.text.toString().startsWith("蓝牙传输已完成，正在检查文件。"))
                assertEquals(1_000, progress.progress)
            }
            // Internal backup progress must preserve its higher-priority preparation message.
            val preserving = initial.copy(preservingExisting = true)
            render(scenario, preserving)
            render(scenario, preserving.copy(downloadSavedBytes = 768))
            scenario.onActivity {
                assertEquals("完成后即可选择本次活动。",
                    tagged<TextView>(it, "home_task_hint").text.toString())
                assertEquals(750, tagged<ProgressBar>(it, "home_download_progress").progress)
            }
        }
    }

    @Test fun deferredNonStepSessionReopensWithItsManualDownloadHintAndNoTransfer() = withFlow { handle ->
        launch().use { scenario ->
            handle.flow.selectActivity(SessionActivity.BADMINTON)
            awaitFlow(handle) { it.selectedActivity == SessionActivity.BADMINTON && it.canStart }
            handle.flow.start()
            awaitFlow(handle) { it.page == CollectionPage.COLLECTING }
            handle.flow.stop()
            awaitFlow(handle) { it.page == CollectionPage.FINISH }
            awaitView(scenario) { taggedOrNull<Button>(it, "finish_defer")?.isEnabled == true }
            scenario.onActivity { assertTrue(tagged<Button>(it, "finish_defer").performClick()) }
            awaitFlow(handle) { it.session?.isRingDeferred == true }
            awaitView(scenario) { taggedOrNull<Button>(it, "home_task_action")?.text == "下载并上传" }
            val saved = requireNotNull(FreeLivingSessionStore(File(handle.directory, "session.json")).read())
            val reference = requireNotNull(saved.reference)
            assertEquals(ReferenceStatus.NOT_APPLICABLE, reference.status)
            assertNull(reference.steps)
            assertNull(saved.localData)
            assertEquals(0, saved.transfer.attempts)
            scenario.recreate()
            awaitView(scenario) { taggedOrNull<Button>(it, "home_task_action")?.text == "下载并上传" }
            repeat(3) { render(scenario, handle.flow.state.copy()) }
            scenario.onActivity {
                assertEquals("已暂存到戒指", tagged<TextView>(it, "home_task_status").text.toString())
                assertEquals("下载到手机后，即可开始下一段。",
                    tagged<TextView>(it, "home_task_hint").text.toString())
                assertEquals("下载并上传", tagged<Button>(it, "home_task_action").text.toString())
                assertTrue(tagged<Button>(it, "home_task_action").isEnabled)
                assertNull(taggedOrNull<ProgressBar>(it, "home_download_progress"))
            }
            assertEquals(saved, FreeLivingSessionStore(File(handle.directory, "session.json")).read())
        }
    }

    private fun base(session: FreeLivingSession?) = CollectionFlowState(
        isSimulation = false, hasProfile = true, participantId = "PROGRESS001",
        placement = RingPlacement.RIGHT_INDEX, connected = true, session = session)

    private fun stopped(handle: DemoFlowRuntime.TestHandle) = FreeLivingSession(
        "progress-rendering", requireNotNull(PreparationStore(File(handle.directory, "profile")).read()),
        FreeLivingSessionPhase.AWAITING_REFERENCE, "Asia/Shanghai", 28_800, 1_000,
        startConfirmedAtMs = 1_100, stopRequestedAtMs = 2_900, stopConfirmedAtMs = 3_000,
        reference = SessionReference(ReferenceStatus.NOT_APPLICABLE, null, 3_100),
        activity = SessionActivity.BADMINTON, completionPolicy = CompletionPolicy.SAVE_UPLOAD)

    private fun withFlow(test: (DemoFlowRuntime.TestHandle) -> Unit) {
        assumeTrue("Explicit collection-flow opt-in is required",
            InstrumentationRegistry.getArguments().getString("verifyCollectionFlow") == "true")
        assumeTrue("Synthetic device checks run only in an emulator",
            Build.FINGERPRINT.contains("generic", true) || Build.FINGERPRINT.contains("emulator", true) ||
                Build.MODEL.contains("Emulator", true) || Build.MODEL.startsWith("sdk_gphone") ||
                Build.MODEL.startsWith("Android SDK built for"))
        val profile = File(instrumentation.targetContext.filesDir, "preparation/profile.properties")
        val before = profile.takeIf { it.exists() }?.readBytes()?.let(::hash)
        DemoFlowRuntime.installForTesting(instrumentation.targetContext).use { handle ->
            assertTrue(handle.directory.canonicalPath.startsWith(
                instrumentation.targetContext.cacheDir.canonicalPath + File.separator))
            handle.flow.register("PROGRESS001", RingPlacement.RIGHT_INDEX)
            awaitFlow(handle) { it.hasProfile && it.canStart }
            test(handle)
        }
        assertEquals(before, profile.takeIf { it.exists() }?.readBytes()?.let(::hash))
    }

    private fun launch(): ActivityScenario<DemoCollectionActivity> = ActivityScenario.launch<DemoCollectionActivity>(
        Intent(instrumentation.targetContext, DemoCollectionActivity::class.java)).also { scenario ->
        awaitView(scenario) { taggedOrNull<TextView>(it, "home_profile_summary") != null }
    }

    private fun render(scenario: ActivityScenario<DemoCollectionActivity>, state: CollectionFlowState) {
        scenario.onActivity {
            StepCollectionActivity::class.java.getDeclaredMethod("render", CollectionFlowState::class.java)
                .apply { isAccessible = true }.invoke(it, state)
        }
        instrumentation.waitForIdleSync()
    }

    private fun awaitFlow(handle: DemoFlowRuntime.TestHandle, condition: (CollectionFlowState) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        do {
            if (condition(handle.flow.state)) return
            SystemClock.sleep(25)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("Timed out waiting for isolated download rendering state")
    }

    private fun awaitView(scenario: ActivityScenario<DemoCollectionActivity>, condition: (DemoCollectionActivity) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        do {
            var matched = false
            scenario.onActivity { matched = condition(it) }
            if (matched) return
            SystemClock.sleep(25)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("Timed out waiting for isolated download rendering view")
    }

    private fun <T : View> tagged(activity: DemoCollectionActivity, tag: String): T =
        requireNotNull(taggedOrNull<T>(activity, tag)) { "Missing tagged view $tag" }
    private fun <T : View> taggedOrNull(activity: DemoCollectionActivity, tag: String): T? =
        activity.findViewById<View>(android.R.id.content).findViewWithTag(tag)
    private fun allText(view: View): List<String> = when (view) {
        is ViewGroup -> (0 until view.childCount).flatMap { allText(view.getChildAt(it)) }
        is TextView -> listOf(view.text.toString())
        else -> emptyList()
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
