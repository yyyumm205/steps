package com.nexthci.ringfitness

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in checks of native pages and actual isolated storage; device and network remain synthetic. */
@RunWith(AndroidJUnit4::class)
class CollectionFlowInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun complete562PathSavesRealFilesAndReceiptThenReopensTheSameResult() = withFlow { handle ->
        launch().use { scenario ->
            register(scenario)
            captureReviewScreen(scenario, "home")
            val id = reachReference(scenario, handle, capture = true)
            type(scenario, "flow_steps", "562")
            click(scenario, "flow_primary")
            awaitHeading(scenario, "这一段已保存")
            scenario.onActivity { activity ->
                assertEquals("562 步", tagged<TextView>(activity, "saved_reference").text.toString())
                assertTrue(tagged<TextView>(activity, "demo_marker").isShown)
            }
            val saved = session(handle)
            val reference = requireNotNull(saved.reference)
            val receipt = requireNotNull(saved.transfer.receipt)
            assertEquals(id, saved.sessionId)
            assertEquals(562L, reference.steps)
            assertEquals(ReferenceStatus.VALID, reference.status)
            assertEquals(SessionTransferStatus.COMPLETE, saved.transfer.status)
            assertTrue(receipt.simulated)
            assertEquals(id, receipt.sessionId)
            saved.localData!!.files.forEach { entry ->
                val file = File(handle.directory, entry.fileName)
                assertEquals(entry.bytes, file.length())
                assertEquals(entry.sha256, hash(file.readBytes()))
                assertTrue(entry.simulated)
            }
            assertNull("The simulated device must not invent a verified real signal boundary", saved.startedAtMs)
            assertNull(saved.endedAtMs)
            scenario.recreate()
            awaitHeading(scenario, "这一段已保存")
            assertEquals(saved, session(handle))
            click(scenario, "flow_primary")
            awaitHeading(scenario, "开始这一段")
            scenario.onActivity { assertEquals("开始采集", tagged<Button>(it, "flow_primary").text.toString()) }
        }
    }

    @Test fun aRealZeroRemainsValidAfterNativeInputSaveAndRecreation() = withFlow { handle ->
        launch().use { scenario ->
            register(scenario)
            reachReference(scenario, handle)
            type(scenario, "flow_steps", "0")
            click(scenario, "flow_primary")
            awaitHeading(scenario, "这一段已保存")
            scenario.recreate()
            awaitHeading(scenario, "这一段已保存")
            val saved = session(handle)
            val reference = requireNotNull(saved.reference)
            assertEquals(0L, reference.steps)
            assertEquals(ReferenceStatus.VALID, reference.status)
            assertNotNull(reference.groundTruthRecordedAtMs)
            scenario.onActivity { assertEquals("0 步", tagged<TextView>(it, "saved_reference").text.toString()) }
        }
    }

    @Test fun missingAndUnreliableSelectionsPersistTheirDistinctMeaningAndReasons() {
        listOf("missing", "unreliable").forEach { kind -> withFlow { handle ->
            launch().use { scenario ->
                register(scenario)
                reachReference(scenario, handle)
                chooseReferenceKind(scenario, kind)
                if (kind == "unreliable") type(scenario, "flow_steps", "562")
                type(scenario, "flow_reason", if (kind == "missing") "计步器意外清零" else "忘记清零")
                click(scenario, "flow_primary")
                awaitHeading(scenario, "这一段已保存")
                val saved = session(handle)
                val reference = requireNotNull(saved.reference)
                val payload = JsonParser.parseString(File(handle.directory, "session.json").readText())
                    .asJsonObject.getAsJsonObject("session")
                assertNotNull(reference.reason)
                assertTrue(payload.get("reference_saved_at_ms").asLong > 0)
                if (kind == "missing") {
                    assertEquals(ReferenceStatus.MISSING, reference.status)
                    assertNull(reference.steps)
                    assertNull(reference.groundTruthRecordedAtMs)
                    assertTrue(payload.get("ground_truth_recorded_at_ms").isJsonNull)
                } else {
                    assertEquals(ReferenceStatus.UNRELIABLE, reference.status)
                    assertEquals(562L, reference.steps)
                    assertNotNull(reference.groundTruthRecordedAtMs)
                }
            }
        } }
    }

    @Test fun failedReferenceCommitKeepsTheInputAndRetriesTheSameSession() = withFlow { handle ->
        launch().use { scenario ->
            register(scenario)
            val id = reachReference(scenario, handle)
            type(scenario, "flow_steps", "562")
            handle.flow.setFault(FlowTestFault.SAVE_FAILURE)
            awaitFlow(handle, "save failure armed") { it.fault == FlowTestFault.SAVE_FAILURE }
            click(scenario, "flow_primary")
            await(scenario, "save failure remains on reference page") { activity ->
                taggedOrNull<TextView>(activity, "flow_error")?.text?.contains("未保存") == true &&
                    taggedOrNull<EditText>(activity, "flow_steps") != null
            }
            scenario.onActivity { assertEquals("562", tagged<EditText>(it, "flow_steps").text.toString()) }
            assertEquals(id, session(handle).sessionId)
            assertNull(session(handle).reference)
            assertNull(session(handle).localData)
            assertTrue(handle.directory.listFiles()!!.none { it.name.contains("simulated-signal") })
            click(scenario, "flow_primary")
            awaitHeading(scenario, "这一段已保存")
            assertEquals(id, session(handle).sessionId)
            assertEquals(562L, session(handle).reference!!.steps)
        }
    }

    @Test fun recreationKeepsRegistrationAndReferenceDraftsWithoutPretendingTheyAreSaved() = withFlow { handle ->
        launch().use { scenario ->
            awaitHeading(scenario, "准备开始")
            type(scenario, "flow_participant", "FLOW001")
            scenario.onActivity { tagged<Spinner>(it, "flow_placement").setSelection(RingPlacement.LEFT_INDEX.ordinal + 1) }
            scenario.recreate()
            awaitHeading(scenario, "准备开始")
            scenario.onActivity {
                assertEquals("FLOW001", tagged<EditText>(it, "flow_participant").text.toString())
                assertEquals(RingPlacement.LEFT_INDEX.ordinal + 1, tagged<Spinner>(it, "flow_placement").selectedItemPosition)
            }
            assertFalse(File(handle.directory, "profile").exists())
            click(scenario, "flow_primary")
            awaitHeading(scenario, "开始这一段")
            val id = reachReference(scenario, handle)
            chooseReferenceKind(scenario, "unreliable")
            type(scenario, "flow_steps", "83")
            type(scenario, "flow_reason", "途中摘下计步器")
            scenario.recreate()
            awaitHeading(scenario, "本次走了多少步？")
            scenario.onActivity {
                assertEquals("83", tagged<EditText>(it, "flow_steps").text.toString())
                assertEquals("途中摘下计步器", tagged<EditText>(it, "flow_reason").text.toString())
            }
            assertEquals(id, session(handle).sessionId)
            assertNull(session(handle).reference)
            click(scenario, "flow_primary")
            awaitHeading(scenario, "这一段已保存")
            assertEquals(ReferenceStatus.UNRELIABLE, session(handle).reference!!.status)
            assertEquals(83L, session(handle).reference!!.steps)
        }
    }

    @Test fun leavingThePageAndReopeningRestoresAnUnfinishedTaskWithoutAnotherStart() = withFlow { handle ->
        lateinit var id: String
        launch().use { scenario ->
            register(scenario)
            click(scenario, "flow_primary")
            awaitHeading(scenario, "正在采集")
            id = session(handle).sessionId
            scenario.moveToState(Lifecycle.State.CREATED)
            assertEquals(FreeLivingSessionPhase.COLLECTING, session(handle).phase)
            assertNull(session(handle).stopRequestedAtMs)
            scenario.moveToState(Lifecycle.State.RESUMED)
            awaitHeading(scenario, "正在采集")
            click(scenario, "flow_primary")
            awaitHeading(scenario, "本次走了多少步？")
            click(scenario, "flow_back")
            awaitHeading(scenario, "待填写步数")
            scenario.onActivity { assertEquals("填写步数", tagged<Button>(it, "flow_primary").text.toString()) }
        }
        launch().use { reopened ->
            awaitHeading(reopened, "待填写步数")
            click(reopened, "flow_primary")
            awaitHeading(reopened, "本次走了多少步？")
            assertEquals(id, session(handle).sessionId)
            assertNull(session(handle).reference)
            assertFalse(handle.flow.state.canStart)
            type(reopened, "flow_steps", "127")
            click(reopened, "flow_primary")
            awaitHeading(reopened, "这一段已保存")
            assertEquals(id, session(handle).sessionId)
        }
    }

    @Test fun earlierUploadCompletionPreservesCurrentInputViewFocusAndCursor() = withFlow { handle ->
        launch().use { scenario ->
            register(scenario)
            val first = reachReference(scenario, handle)
            handle.flow.setFault(FlowTestFault.UPLOAD_FAILURE)
            awaitFlow(handle, "upload failure armed") { it.fault == FlowTestFault.UPLOAD_FAILURE }
            type(scenario, "flow_steps", "101")
            click(scenario, "flow_primary")
            awaitHeading(scenario, "这一步未完成")
            assertEquals(SessionTransferStatus.FAILED, session(handle).transfer.status)
            click(scenario, "flow_back")
            awaitHeading(scenario, "开始这一段")
            val second = reachReference(scenario, handle)
            assertNotEquals(first, second)
            lateinit var originalInput: EditText
            scenario.onActivity {
                originalInput = tagged(it, "flow_steps")
                originalInput.setText("562")
                assertTrue(originalInput.requestFocus())
                originalInput.setSelection(2)
            }
            handle.flow.retryUpload(first)
            awaitFlow(handle, "earlier transfer completes") { state ->
                state.records.single { it.sessionId == first }.transferStatus == "complete"
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val input = tagged<EditText>(it, "flow_steps")
                assertSame("Unrelated task completion must retain the actual input view", originalInput, input)
                assertEquals("562", input.text.toString())
                assertTrue(input.hasFocus())
                assertEquals(2, input.selectionStart)
                assertEquals("本次走了多少步？", tagged<TextView>(it, "flow_heading").text.toString())
            }
            val store = FreeLivingSessionStore(File(handle.directory, "session.json"))
            assertEquals(101L, store.read(first)!!.reference!!.steps)
            assertNull(store.read(second)!!.reference)
            assertEquals(second, store.read()!!.sessionId)
        }
    }

    @Test fun disconnectAndReconnectKeepTheSameCollectingTaskAndRestoreItsStopAction() = withFlow { handle ->
        launch().use { scenario ->
            register(scenario)
            click(scenario, "flow_primary")
            awaitHeading(scenario, "正在采集")
            val original = session(handle)
            handle.flow.disconnect()
            awaitHeading(scenario, "还需要确认一下")
            assertEquals(original, session(handle))
            click(scenario, "flow_back")
            awaitHeading(scenario, "本次采集")
            scenario.onActivity {
                assertEquals("戒指连接中断", tagged<TextView>(it, "home_task_status").text.toString())
                assertEquals("重新连接", tagged<Button>(it, "flow_primary").text.toString())
            }
            click(scenario, "flow_primary")
            awaitHeading(scenario, "正在采集")
            scenario.onActivity { assertTrue(tagged<Button>(it, "flow_primary").isEnabled) }
            assertEquals(original.sessionId, session(handle).sessionId)
            click(scenario, "flow_primary")
            awaitHeading(scenario, "本次走了多少步？")
            assertEquals(original.sessionId, session(handle).sessionId)
        }
    }

    @Test fun collectingHomeShowsCurrentTaskAndReturnsWithoutStoppingOrCreatingAnotherSession() = withFlow { handle ->
        launch().use { scenario ->
            register(scenario)
            click(scenario, "flow_primary")
            awaitHeading(scenario, "正在采集")
            val original = session(handle)
            click(scenario, "flow_back")
            awaitHeading(scenario, "采集进行中")
            scenario.onActivity {
                assertEquals("正在采集", tagged<TextView>(it, "home_task_status").text.toString())
                assertEquals("查看采集", tagged<Button>(it, "flow_primary").text.toString())
            }
            assertEquals(original, session(handle))
            assertNull(session(handle).stopRequestedAtMs)
            scenario.recreate()
            awaitHeading(scenario, "采集进行中")
            click(scenario, "flow_primary")
            awaitHeading(scenario, "正在采集")
            assertEquals(original, session(handle))
            assertEquals(1, FreeLivingSessionStore(File(handle.directory, "session.json")).listSessions().size)
        }
    }

    @Test fun downloadFailureUpdatesHomeInPlaceAndRetryFinishesTheOriginalSavedReference() = withFlow { handle ->
        launch().use { scenario ->
            register(scenario)
            val id = reachReference(scenario, handle)
            handle.flow.setFault(FlowTestFault.DOWNLOAD_FAILURE)
            awaitFlow(handle, "download failure armed") { it.fault == FlowTestFault.DOWNLOAD_FAILURE }
            type(scenario, "flow_steps", "562")
            click(scenario, "flow_primary")
            awaitFlow(handle, "download is active") { it.page == CollectionPage.DOWNLOADING }
            click(scenario, "flow_back")
            awaitHeading(scenario, "正在下载数据")
            scenario.onActivity { assertEquals("查看下载", tagged<Button>(it, "flow_primary").text.toString()) }
            awaitFlow(handle, "download failure remains at home") {
                it.page == CollectionPage.HOME && it.taskPage == CollectionPage.ERROR
            }
            awaitHeading(scenario, "待下载数据")
            scenario.onActivity {
                assertEquals("步数已保存", tagged<TextView>(it, "home_task_status").text.toString())
                assertEquals("重试下载", tagged<Button>(it, "flow_primary").text.toString())
                assertTrue(tagged<Button>(it, "flow_primary").isEnabled)
            }
            val saved = session(handle)
            assertEquals(id, saved.sessionId)
            assertEquals(562L, saved.reference!!.steps)
            assertNull(saved.localData)
            assertFalse(handle.flow.state.canStart)
            click(scenario, "flow_primary")
            awaitHeading(scenario, "这一段已保存")
            assertEquals(id, session(handle).sessionId)
            assertEquals(saved.reference, session(handle).reference)
            assertNotNull(session(handle).localData)
            assertEquals(SessionTransferStatus.COMPLETE, session(handle).transfer.status)
        }
    }

    @Test fun startDateKeepsTheSessionTimezoneAcrossMidnightAndDefaultTimezoneChanges() = withFlow { handle ->
        val originalDefault = TimeZone.getDefault()
        try {
            handle.flow.register("date001", RingPlacement.LEFT_INDEX)
            awaitFlow(handle, "test registration saved") { it.hasProfile && !it.busy }
            val preparation = requireNotNull(PreparationStore(File(handle.directory, "profile")).read())
            // Seed only this test's isolated journal and synthetic device. The same instant falls
            // on September 18 in UTC and September 19 in the session's recorded Shanghai zone.
            val start = Instant.parse("2026-09-18T16:05:00Z").toEpochMilli()
            val store = FreeLivingSessionStore(File(handle.directory, "session.json"))
            val requested = store.requestStart(preparation, start - 100, "Asia/Shanghai")
            val device = DemoDeviceRecord(requested.sessionId, 41, start)
            store.confirmStart(requested.sessionId, preparation.ring!!.address, device.status(), start)
            DemoDeviceStore(File(handle.directory, "device.json")).save(device)
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            handle.flow.reconnect()
            awaitFlow(handle, "dated task restored") { it.page == CollectionPage.COLLECTING }
            launch().use { scenario ->
                awaitHeading(scenario, "正在采集")
                scenario.onActivity { assertEquals("2026-09-19 00:05", tagged<TextView>(it, "flow_started_at").text.toString()) }
                TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
                scenario.recreate()
                awaitHeading(scenario, "正在采集")
                scenario.onActivity { assertEquals("2026-09-19 00:05", tagged<TextView>(it, "flow_started_at").text.toString()) }
                assertEquals("Asia/Shanghai", session(handle).timeZoneId)
                assertEquals(start, session(handle).startConfirmedAtMs)
                assertEquals(requested.sessionId, session(handle).sessionId)
            }
        } finally {
            TimeZone.setDefault(originalDefault)
        }
    }

    @Test fun unconfirmedStopReferenceHomeKeepsItsWarningAndReturnsWithoutConfirmingTheDevice() = withFlow { handle ->
        launch().use { scenario ->
            register(scenario)
            click(scenario, "flow_primary")
            awaitHeading(scenario, "正在采集")
            val id = session(handle).sessionId
            handle.flow.setFault(FlowTestFault.STOP_TIMEOUT)
            awaitFlow(handle, "stop timeout armed") { it.fault == FlowTestFault.STOP_TIMEOUT }
            click(scenario, "flow_primary")
            awaitHeading(scenario, "还需要确认一下")
            assertEquals(FreeLivingSessionPhase.STOP_REQUESTED, session(handle).phase)
            assertNull(session(handle).stopConfirmedAtMs)
            click(scenario, "preserve_reference")
            awaitHeading(scenario, "本次走了多少步？")
            click(scenario, "flow_back")
            awaitHeading(scenario, "本次采集")
            scenario.onActivity {
                assertEquals("结束状态待确认", tagged<TextView>(it, "home_task_status").text.toString())
                assertEquals("先填写步数", tagged<Button>(it, "flow_primary").text.toString())
            }
            val beforeReturn = session(handle)
            assertNull(beforeReturn.stopConfirmedAtMs)
            assertFalse(handle.flow.state.canStart)
            click(scenario, "flow_primary")
            awaitHeading(scenario, "本次走了多少步？")
            assertEquals("Returning to the form must not query and silently confirm a previously unknown stop",
                beforeReturn, session(handle))
            chooseReferenceKind(scenario, "missing")
            type(scenario, "flow_reason", "计步器意外清零")
            click(scenario, "flow_primary")
            awaitHeading(scenario, "还需要确认一下")
            val saved = session(handle)
            assertEquals(id, saved.sessionId)
            assertEquals(FreeLivingSessionPhase.STOP_REQUESTED, saved.phase)
            assertNull(saved.stopConfirmedAtMs)
            assertNull(saved.endedAtMs)
            assertNull(saved.localData)
            assertEquals(ReferenceStatus.MISSING, saved.reference!!.status)
            assertNull(saved.reference!!.steps)
            assertEquals("计步器意外清零", saved.reference!!.reason)
            assertFalse(handle.flow.state.canStart)
        }
    }

    private fun withFlow(test: (DemoFlowRuntime.TestHandle) -> Unit) {
        assumeTrue("Explicit collection-flow opt-in is required",
            InstrumentationRegistry.getArguments().getString("verifyCollectionFlow") == "true")
        assumeTrue("These simulated flow checks run only in an emulator",
            Build.FINGERPRINT.contains("generic", true) || Build.FINGERPRINT.contains("emulator", true) ||
                Build.MODEL.contains("Emulator", true) || Build.MODEL.startsWith("sdk_gphone") ||
                Build.MODEL.startsWith("Android SDK built for"))
        val profile = File(instrumentation.targetContext.filesDir, "preparation/profile.properties")
        val before = if (profile.exists()) hash(profile.readBytes()) else null
        DemoFlowRuntime.installForTesting(instrumentation.targetContext).use { handle ->
            assertTrue(handle.directory.canonicalPath.startsWith(instrumentation.targetContext.cacheDir.canonicalPath + File.separator))
            assertEquals("collection-demo", handle.directory.name)
            test(handle)
        }
        assertEquals("The production preparation profile remains unchanged", before,
            if (profile.exists()) hash(profile.readBytes()) else null)
    }

    private fun launch(): ActivityScenario<DemoCollectionActivity> = ActivityScenario.launch(
        Intent(instrumentation.targetContext, DemoCollectionActivity::class.java))

    private fun register(scenario: ActivityScenario<DemoCollectionActivity>) {
        awaitHeading(scenario, "准备开始")
        type(scenario, "flow_participant", "FLOW001")
        scenario.onActivity { tagged<Spinner>(it, "flow_placement").setSelection(RingPlacement.LEFT_INDEX.ordinal + 1) }
        click(scenario, "flow_primary")
        awaitHeading(scenario, "开始这一段")
    }

    private fun reachReference(scenario: ActivityScenario<DemoCollectionActivity>, handle: DemoFlowRuntime.TestHandle,
        capture: Boolean = false): String {
        click(scenario, "flow_primary")
        awaitHeading(scenario, "正在采集")
        if (capture) captureReviewScreen(scenario, "collecting")
        val id = session(handle).sessionId
        click(scenario, "flow_primary")
        awaitHeading(scenario, "本次走了多少步？")
        if (capture) captureReviewScreen(scenario, "reference")
        assertEquals(id, session(handle).sessionId)
        return id
    }

    private fun captureReviewScreen(scenario: ActivityScenario<DemoCollectionActivity>, page: String) {
        if (InstrumentationRegistry.getArguments().getString("captureFlowScreens") != "true") return
        require(page in setOf("home", "collecting", "reference"))
        val frameDrawn = CountDownLatch(1)
        scenario.onActivity { activity ->
            val root = activity.window.decorView
            root.viewTreeObserver.registerFrameCommitCallback { frameDrawn.countDown() }
            root.invalidate()
        }
        assertTrue("The review page must be drawn before its screenshot", frameDrawn.await(5, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            FileOutputStream(File(instrumentation.targetContext.cacheDir, "flow-review-$page.png")).use {
                assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
                it.fd.sync()
            }
        } finally { screenshot.recycle() }
    }

    private fun chooseReferenceKind(scenario: ActivityScenario<DemoCollectionActivity>, kind: String) {
        click(scenario, "reference_options")
        scenario.onActivity { activity ->
            val dialog = StepCollectionActivity::class.java.getDeclaredField("dialog").apply { isAccessible = true }
                .get(activity) as AlertDialog
            assertTrue(dialog.isShowing)
            val expected = when (kind) { "missing" -> "无法提供读数"; "unreliable" -> "数字可能不准确"; else -> "读数正常" }
            val list = dialog.listView
            val index = (0 until list.adapter.count).single { list.adapter.getItem(it).toString() == expected }
            assertTrue(list.performItemClick(list.adapter.getView(index, null, list), index, list.adapter.getItemId(index)))
        }
        await(scenario, "reference type selected") { taggedOrNull<EditText>(it, "flow_reason") != null }
    }

    private fun session(handle: DemoFlowRuntime.TestHandle): FreeLivingSession =
        requireNotNull(FreeLivingSessionStore(File(handle.directory, "session.json")).read())

    private fun type(scenario: ActivityScenario<DemoCollectionActivity>, tag: String, value: String) {
        scenario.onActivity {
            val input = tagged<EditText>(it, tag)
            assertTrue(input.isShown && input.isEnabled)
            input.setText(value)
        }
    }

    private fun click(scenario: ActivityScenario<DemoCollectionActivity>, tag: String) {
        scenario.onActivity {
            val button = tagged<Button>(it, tag)
            assertTrue("$tag must be visible and enabled", button.isShown && button.isEnabled)
            assertTrue(button.performClick())
        }
    }

    private fun awaitHeading(scenario: ActivityScenario<DemoCollectionActivity>, text: String) =
        await(scenario, "page $text") { taggedOrNull<TextView>(it, "flow_heading")?.text?.toString() == text }

    private fun await(scenario: ActivityScenario<DemoCollectionActivity>, description: String,
        condition: (DemoCollectionActivity) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        do {
            var satisfied = false
            scenario.onActivity { satisfied = condition(it) }
            if (satisfied) return
            SystemClock.sleep(25)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("Timed out waiting for $description")
    }

    private fun awaitFlow(handle: DemoFlowRuntime.TestHandle, description: String, condition: (CollectionFlowState) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        do {
            if (condition(handle.flow.state)) return
            SystemClock.sleep(25)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("Timed out waiting for $description; state=${handle.flow.state.page}")
    }

    private fun <T : View> tagged(activity: DemoCollectionActivity, tag: String): T =
        requireNotNull(taggedOrNull<T>(activity, tag)) { "Missing tagged view $tag" }

    private fun <T : View> taggedOrNull(activity: DemoCollectionActivity, tag: String): T? =
        activity.findViewById<View>(android.R.id.content).findViewWithTag(tag)

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
