package com.nexthci.ringfitness

import android.app.AlertDialog
import android.content.Intent
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
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the production pages with cache-only demo stores and synthetic H10 state. */
@RunWith(AndroidJUnit4::class)
class MultisportFlowInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun nineActivitiesShareOnePageAndOnlyWalkingAndRunningRequestPedometerReset() = withFlow { handle ->
        launch().use { scenario ->
            register(scenario)
            scenario.onActivity { activity ->
                val buttons = SessionActivity.selectable.map { tagged<Button>(activity, "activity_${it.wireValue}") }
                assertEquals(9, buttons.size)
                assertEquals(3, buttons.map { it.parent }.distinct().size)
                assertEquals(1, buttons.map { it.parent.parent }.distinct().size)
                assertNull(taggedOrNull<View>(activity, "activity_free_living"))
            }
            for (sport in SessionActivity.selectable) {
                click(scenario, "activity_${sport.wireValue}")
                awaitFlow(handle) { it.selectedActivity == sport }
                await(scenario) { tagged<Button>(it, "activity_${sport.wireValue}").isSelected }
                scenario.onActivity { activity ->
                    val hint = tagged<TextView>(activity, "home_preparation_hint").text.toString()
                    assertEquals(sport.requiresReferenceSteps, hint.contains("计步器清零"))
                    assertEquals(1, SessionActivity.selectable.count {
                        tagged<Button>(activity, "activity_${it.wireValue}").isSelected
                    })
                    SessionActivity.selectable.forEach {
                        val button = tagged<Button>(activity, "activity_${it.wireValue}")
                        assertTrue(button.isShown && button.isEnabled)
                        assertTrue(button.width > 0 && button.height >= button.minimumHeight)
                        assertTrue(button.left >= 0 && button.right <= (button.parent as View).width)
                        val layout = requireNotNull(button.layout)
                        assertTrue((0 until layout.lineCount).all { line -> layout.getEllipsisCount(line) == 0 })
                    }
                }
            }
            captureScreen(scenario, "nine_activities_home")
        }
    }

    @Test fun sevenNonStepSportsOmitReadingsAndSubmitNotApplicableForEitherSaveChoice() = withFlow { handle ->
        launch().use { scenario ->
            register(scenario)
            val preparation = requireNotNull(PreparationStore(File(handle.directory, "profile")).read())
            val fixture = RenderingFlow(base())
            for (sport in SessionActivity.selectable.filterNot { it.requiresReferenceSteps }) {
                val stopped = stopped(preparation, sport)
                fixture.state = base().copy(page = CollectionPage.COLLECTING, canStop = true,
                    session = stopped.copy(phase = FreeLivingSessionPhase.COLLECTING,
                        stopRequestedAtMs = null, stopConfirmedAtMs = null))
                render(scenario, fixture)
                scenario.onActivity { activity ->
                    assertNull(taggedOrNull<View>(activity, "heart_rate_status"))
                    assertNull(taggedOrNull<View>(activity, "heart_rate_capture_hint"))
                    val text = visibleTexts(activity.findViewById(android.R.id.content)).joinToString(" ")
                    assertFalse(text.contains("读数") || text.contains("清零") || text.contains("计步器"))
                    assertFalse(text.contains("无需计步") || text.contains("不适用") || text.contains("not_applicable"))
                    assertEquals("结束采集", tagged<Button>(activity, "flow_primary").text.toString())
                }
                fixture.state = base().copy(page = CollectionPage.FINISH,
                    session = stopped)
                render(scenario, fixture)
                scenario.onActivity { activity ->
                    assertNull(taggedOrNull<View>(activity, "flow_steps"))
                    assertNull(taggedOrNull<View>(activity, "no_reference_hint"))
                    assertNull(taggedOrNull<View>(activity, "preserve_reference"))
                    val text = visibleTexts(activity.findViewById(android.R.id.content)).joinToString(" ")
                    assertFalse(text.contains("读数") || text.contains("清零") || text.contains("计步器"))
                    assertFalse(text.contains("无需计步") || text.contains("不适用") || text.contains("not_applicable"))
                    listOf("flow_primary", "finish_defer", "finish_discard").forEach {
                        assertTrue(tagged<Button>(activity, it).isEnabled)
                    }
                }
                click(scenario, "flow_primary")
                assertEquals(true to Triple("", "not_applicable", ""), fixture.finalized)
                click(scenario, "finish_defer")
                assertEquals(false to Triple("", "not_applicable", ""), fixture.finalized)
                assertEquals(emptyList<String>(), fixture.draftUpdates)
                fixture.state = fixture.state.copy(error = "本次未保存，请重试")
                render(scenario, fixture)
                scenario.onActivity {
                    assertEquals("本段记录未保存，请重试。", tagged<TextView>(it, "flow_error").text.toString())
                }
            }
        }
    }

    @Test fun walkingAndRunningRequireAReadingAndPreserveExplicitZero() = withFlow { handle ->
        launch().use { scenario ->
            register(scenario)
            val preparation = requireNotNull(PreparationStore(File(handle.directory, "profile")).read())
            val fixture = RenderingFlow(base())
            for (sport in listOf(SessionActivity.WALKING, SessionActivity.RUNNING)) {
                fixture.finalized = null
                fixture.state = base().copy(page = CollectionPage.FINISH,
                    session = stopped(preparation, sport))
                render(scenario, fixture)
                click(scenario, "flow_primary")
                assertNull(fixture.finalized)
                scenario.onActivity { activity ->
                    assertEquals(View.VISIBLE, tagged<TextView>(activity, "finish_input_error").visibility)
                    tagged<EditText>(activity, "flow_steps").setText("0")
                }
                click(scenario, "flow_primary")
                assertEquals(true to Triple("0", "valid", ""), fixture.finalized)
                assertEquals("0", fixture.draftUpdates.last())
            }
        }
    }

    @Test fun optionalH10BlocksStartUntilReadyAndTurningItOffRestoresIndependentCapture() = withFlow { handle ->
        launch().use { scenario ->
            register(scenario)
            select(scenario, handle, SessionActivity.TENNIS)
            scenario.onActivity {
                assertEquals(View.GONE, tagged<View>(it, "heart_rate_details").visibility)
                assertTrue(tagged<Button>(it, "flow_primary").isEnabled)
            }
            click(scenario, "heart_rate_toggle")
            await(scenario) { !tagged<Button>(it, "flow_primary").isEnabled }
            assertTrue(handle.flow.state.heartRateEnabled)
            assertFalse(handle.flow.state.heartRateState.ready)
            scenario.onActivity { assertEquals("已连接", tagged<TextView>(it, "home_device_status").text.toString()) }
            scenario.recreate()
            await(scenario) { taggedOrNull<Button>(it, "heart_rate_scan") != null }
            scenario.onActivity {
                assertEquals("已连接", tagged<TextView>(it, "home_device_status").text.toString())
                assertFalse(tagged<Button>(it, "flow_primary").isEnabled)
            }
            connectH10(scenario, handle)
            await(scenario) { tagged<Button>(it, "flow_primary").isEnabled }
            scenario.onActivity {
                assertEquals("Polar H10 已就绪", tagged<TextView>(it, "heart_rate_status").text.toString())
            }
            captureScreen(scenario, "h10_ready", anchorTag = "heart_rate_controls")
            click(scenario, "heart_rate_toggle")
            awaitFlow(handle) { !it.heartRateEnabled }
            await(scenario) { tagged<View>(it, "heart_rate_details").visibility == View.GONE }
            click(scenario, "flow_primary")
            awaitFlow(handle) { it.page == CollectionPage.COLLECTING }
            assertNull(session(handle).heartRate)
        }
    }

    @Test fun heartRateRecoveryRefreshesInPlaceAndKeepsTheRingStopActionEnabled() = withFlow { handle ->
        launch().use { scenario ->
            register(scenario)
            val preparation = requireNotNull(PreparationStore(File(handle.directory, "profile")).read())
            val now = System.currentTimeMillis()
            val session = stopped(preparation, SessionActivity.BASKETBALL).copy(
                phase = FreeLivingSessionPhase.COLLECTING, stopRequestedAtMs = null, stopConfirmedAtMs = null,
                startRequestedAtMs = now - 190_000, startConfirmedAtMs = now - 180_000,
                heartRate = SessionHeartRate("TEST-H10", "Polar H10", now - 180_000))
            val ready = PolarUiState(selectedDeviceId = "TEST-H10", connected = true, hrReady = true,
                recording = true, lastHeartRate = 72)
            val fixture = RenderingFlow(base().copy(page = CollectionPage.COLLECTING, session = session,
                canStop = true, heartRateEnabled = true, heartRateState = ready))
            render(scenario, fixture)
            var status: TextView? = null
            var stop: Button? = null
            scenario.onActivity {
                status = tagged(it, "heart_rate_status")
                stop = tagged(it, "flow_primary")
                assertEquals("72 bpm · 正在采集", status!!.text.toString())
            }
            // Retaining a last sample and the old recording flag must not imply a live stream.
            fixture.state = fixture.state.copy(heartRateState = ready.copy(connected = false, hrReady = false))
            render(scenario, fixture)
            scenario.onActivity {
                assertSame(status, tagged<TextView>(it, "heart_rate_status"))
                assertSame(stop, tagged<Button>(it, "flow_primary"))
                assertEquals("心率带连接已中断，等待恢复", status!!.text.toString())
                assertTrue(stop!!.isEnabled)
                assertEquals(View.VISIBLE, tagged<View>(it, "heart_rate_capture_hint").visibility)
            }
            captureScreen(scenario, "h10_disconnected_capture")
            fixture.state = fixture.state.copy(heartRateState = fixture.state.heartRateState.copy(connecting = true))
            render(scenario, fixture)
            scenario.onActivity { assertEquals("心率带正在重连…", status!!.text.toString()) }
            fixture.state = fixture.state.copy(heartRateState = ready.copy(recording = false))
            render(scenario, fixture)
            scenario.onActivity { assertEquals("已连接，等待 HR/RR 数据", status!!.text.toString()) }
            fixture.state = fixture.state.copy(heartRateState = ready.copy(lastHeartRate = 86))
            render(scenario, fixture)
            scenario.onActivity {
                assertSame(status, tagged<TextView>(it, "heart_rate_status"))
                assertEquals("86 bpm · 正在采集", status!!.text.toString())
                assertEquals(View.GONE, tagged<View>(it, "heart_rate_capture_hint").visibility)
                assertTrue(stop!!.isEnabled)
            }
            fixture.state = base().copy(selectedActivity = SessionActivity.TENNIS, heartRateEnabled = true,
                heartRateState = ready.copy(connected = false, hrReady = false))
            render(scenario, fixture)
            scenario.onActivity {
                assertEquals("连接已中断，请重新连接", tagged<TextView>(it, "heart_rate_status").text.toString())
                assertFalse(tagged<Button>(it, "flow_primary").isEnabled)
            }
        }
    }

    @Test fun deferredNonStepHeartRateSurvivesReopeningAndDiscardKeepsEarlierRecords() = withFlow { handle ->
        launch().use { scenario ->
            register(scenario)
            collectAndStop(scenario, handle, SessionActivity.VOLLEYBALL)
            click(scenario, "flow_primary")
            awaitFlow(handle) { it.session?.transfer?.status == SessionTransferStatus.COMPLETE }
            await(scenario) { taggedOrNull<Button>(it, "activity_badminton") != null }
            val earlier = session(handle)
            val earlierRaw = File(handle.directory, earlier.localData!!.files.single().fileName)
            val earlierHash = hash(earlierRaw.readBytes())

            click(scenario, "heart_rate_toggle")
            awaitFlow(handle) { it.heartRateEnabled }
            connectH10(scenario, handle)
            collectAndStop(scenario, handle, SessionActivity.BADMINTON)
            val stopped = session(handle)
            val heartRate = requireNotNull(stopped.heartRate)
            val hrFile = File(handle.directory, requireNotNull(heartRate.file).fileName)
            assertNotNull(heartRate.endedAtMs)
            assertTrue(hrFile.isFile)
            val hrHash = hash(hrFile.readBytes())
            captureScreen(scenario, "badminton_finish")
            click(scenario, "finish_defer")
            awaitFlow(handle) { it.session?.isRingDeferred == true }
            scenario.recreate()
            await(scenario) { taggedOrNull<Button>(it, "home_task_action")?.text?.toString() == "下载并上传" }
            assertEquals(stopped.sessionId, session(handle).sessionId)
            assertEquals(ReferenceStatus.NOT_APPLICABLE, session(handle).reference!!.status)
            assertNull(session(handle).reference!!.steps)
            assertNull(session(handle).localData)
            assertEquals(hrHash, hash(hrFile.readBytes()))
            captureScreen(scenario, "ring_deferred")
            click(scenario, "recovery_discard")
            scenario.onActivity { dialog(it).getButton(AlertDialog.BUTTON_NEGATIVE).performClick() }
            assertFalse(session(handle).isDiscarded)
            click(scenario, "recovery_discard")
            scenario.onActivity { dialog(it).getButton(AlertDialog.BUTTON_POSITIVE).performClick() }
            awaitFlow(handle) { it.session == null }
            assertFalse(hrFile.exists())
            assertEquals(earlierHash, hash(earlierRaw.readBytes()))
            assertEquals(earlier, FreeLivingSessionStore(File(handle.directory, "session.json")).read(earlier.sessionId))
            scenario.recreate()
            await(scenario) { taggedOrNull<Button>(it, "activity_running") != null }
            assertFalse(handle.flow.state.heartRateEnabled)
            collectAndStop(scenario, handle, SessionActivity.RUNNING)
            assertNull(session(handle).heartRate)
            scenario.onActivity { assertEquals("", tagged<EditText>(it, "flow_steps").text.toString()) }
        }
    }

    private fun base() = CollectionFlowState(isSimulation = false, hasProfile = true,
        participantId = "UI001", placement = RingPlacement.LEFT_INDEX, connected = true, canStart = true)

    private fun stopped(preparation: PreparationSnapshot, sport: SessionActivity) = FreeLivingSession(
        "ui-${sport.wireValue}", preparation, FreeLivingSessionPhase.AWAITING_REFERENCE,
        "Asia/Shanghai", 28_800, 1_000, startConfirmedAtMs = 1_100, stopRequestedAtMs = 2_900,
        stopConfirmedAtMs = 3_000, activity = sport)

    private fun render(scenario: ActivityScenario<DemoCollectionActivity>, fixture: RenderingFlow) {
        scenario.onActivity { activity ->
            StepCollectionActivity::class.java.getDeclaredField("flow").apply { isAccessible = true }.set(activity, fixture)
            StepCollectionActivity::class.java.getDeclaredMethod("render", CollectionFlowState::class.java)
                .apply { isAccessible = true }.invoke(activity, fixture.state)
        }
        instrumentation.waitForIdleSync()
    }

    private class RenderingFlow(override var state: CollectionFlowState) : CollectionFlow {
        var finalized: Pair<Boolean, Triple<String, String, String>>? = null
        val draftUpdates = mutableListOf<String>()
        override fun observe(observer: (CollectionFlowState) -> Unit): AutoCloseable {
            observer(state)
            return AutoCloseable {}
        }
        override fun finalizeSession(uploadNow: Boolean, stepsText: String, status: String, reason: String) {
            finalized = uploadNow to Triple(stepsText, status, reason)
        }
        override fun updateReferenceDraft(stepsText: String) { draftUpdates += stepsText }
        override fun register(participantId: String, placement: RingPlacement) = error("Unexpected registration")
        override fun start() = error("Unexpected device start")
        override fun stop() = error("Unexpected device stop")
        override fun enterReference() = error("Unexpected reference page")
        override fun saveReference(stepsText: String, status: String, reason: String) = error("Unexpected reference write")
        override fun retry() = error("Unexpected retry")
        override fun retryUpload(sessionId: String) = error("Unexpected upload")
        override fun home() = error("Unexpected navigation")
        override fun setFault(fault: FlowTestFault) = error("Unexpected fault option")
        override fun disconnect() = error("Unexpected device disconnect")
        override fun reconnect() = error("Unexpected device connection")
    }

    private fun withFlow(test: (DemoFlowRuntime.TestHandle) -> Unit) {
        assumeTrue("Explicit collection-flow opt-in is required",
            InstrumentationRegistry.getArguments().getString("verifyCollectionFlow") == "true")
        assumeTrue("These synthetic device checks run only in an emulator",
            Build.FINGERPRINT.contains("generic", true) || Build.FINGERPRINT.contains("emulator", true) ||
                Build.MODEL.contains("Emulator", true) || Build.MODEL.startsWith("sdk_gphone") ||
                Build.MODEL.startsWith("Android SDK built for"))
        val profile = File(instrumentation.targetContext.filesDir, "preparation/profile.properties")
        val before = profile.takeIf { it.exists() }?.readBytes()?.let(::hash)
        DemoFlowRuntime.installForTesting(instrumentation.targetContext).use { handle ->
            assertTrue(handle.directory.canonicalPath.startsWith(instrumentation.targetContext.cacheDir.canonicalPath + File.separator))
            test(handle)
        }
        assertEquals(before, profile.takeIf { it.exists() }?.readBytes()?.let(::hash))
    }

    private fun launch(): ActivityScenario<DemoCollectionActivity> = ActivityScenario.launch(
        Intent(instrumentation.targetContext, DemoCollectionActivity::class.java))

    private fun register(scenario: ActivityScenario<DemoCollectionActivity>) {
        await(scenario) { taggedOrNull<EditText>(it, "flow_participant") != null }
        scenario.onActivity {
            tagged<EditText>(it, "flow_participant").setText("MULTI001")
            tagged<Spinner>(it, "flow_placement").setSelection(RingPlacement.LEFT_INDEX.ordinal + 1)
        }
        click(scenario, "flow_primary")
        await(scenario) { taggedOrNull<Button>(it, "activity_walking") != null }
    }

    private fun select(scenario: ActivityScenario<DemoCollectionActivity>, handle: DemoFlowRuntime.TestHandle,
        sport: SessionActivity) {
        click(scenario, "activity_${sport.wireValue}")
        awaitFlow(handle) { it.selectedActivity == sport }
        await(scenario) { taggedOrNull<Button>(it, "flow_primary")?.isEnabled == true }
    }

    private fun connectH10(scenario: ActivityScenario<DemoCollectionActivity>, handle: DemoFlowRuntime.TestHandle) {
        await(scenario) { taggedOrNull<Button>(it, "heart_rate_scan")?.isEnabled == true }
        click(scenario, "heart_rate_scan")
        awaitFlow(handle) { it.heartRateState.devices.isNotEmpty() && !it.heartRateState.scanning }
        val id = handle.flow.state.heartRateState.devices.single().deviceId
        await(scenario) { taggedOrNull<Button>(it, "heart_rate_device_$id") != null }
        click(scenario, "heart_rate_device_$id")
        awaitFlow(handle) { it.heartRateState.ready }
    }

    private fun collectAndStop(scenario: ActivityScenario<DemoCollectionActivity>, handle: DemoFlowRuntime.TestHandle,
        sport: SessionActivity) {
        select(scenario, handle, sport)
        click(scenario, "flow_primary")
        awaitFlow(handle) { it.page == CollectionPage.COLLECTING }
        await(scenario) { taggedOrNull<Button>(it, "flow_primary")?.text?.toString() == "结束采集" }
        click(scenario, "flow_primary")
        awaitFlow(handle) { it.page == CollectionPage.FINISH }
        await(scenario) { taggedOrNull<Button>(it, "finish_defer")?.isShown == true }
    }

    /** Optional visual evidence; these PNGs contain isolated demo or in-memory rendering state. */
    private fun captureScreen(scenario: ActivityScenario<DemoCollectionActivity>, name: String, anchorTag: String? = null) {
        if (InstrumentationRegistry.getArguments().getString("captureMultisportScreenshots") != "true") return
        require(name.matches(Regex("[a-z0-9_]+")))
        instrumentation.waitForIdleSync()
        scenario.onActivity { activity ->
            val scroll = StepCollectionActivity::class.java.getDeclaredField("pageScroll")
                .apply { isAccessible = true }.get(activity) as android.widget.ScrollView
            if (anchorTag == null) scroll.scrollTo(0, 0) else {
                val anchor = tagged<View>(activity, anchorTag)
                val bounds = android.graphics.Rect(0, 0, anchor.width, anchor.height)
                scroll.offsetDescendantRectToMyCoords(anchor, bounds)
                scroll.scrollTo(0, bounds.top)
            }
        }
        // Allow scrolling and window transitions to finish before the committed frame.
        SystemClock.sleep(350)
        val frameDrawn = CountDownLatch(1)
        scenario.onActivity { activity ->
            val root = activity.window.decorView
            root.viewTreeObserver.registerFrameCommitCallback { frameDrawn.countDown() }
            root.invalidate()
        }
        assertTrue("The multisport screen must be drawn before capture", frameDrawn.await(5, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val directory = File(instrumentation.targetContext.filesDir, "multisport-qa-screens")
            assertTrue(directory.isDirectory || directory.mkdirs())
            FileOutputStream(File(directory, "$name.png")).use {
                assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
                it.fd.sync()
            }
        } finally { screenshot.recycle() }
    }

    private fun click(scenario: ActivityScenario<DemoCollectionActivity>, tag: String) = scenario.onActivity {
        val button = tagged<Button>(it, tag)
        assertTrue("$tag must be visible and enabled", button.isShown && button.isEnabled)
        assertTrue(button.performClick())
    }

    private fun session(handle: DemoFlowRuntime.TestHandle): FreeLivingSession =
        requireNotNull(FreeLivingSessionStore(File(handle.directory, "session.json")).read())

    private fun dialog(activity: DemoCollectionActivity): AlertDialog =
        StepCollectionActivity::class.java.getDeclaredField("dialog").apply { isAccessible = true }.get(activity) as AlertDialog

    private fun await(scenario: ActivityScenario<DemoCollectionActivity>, condition: (DemoCollectionActivity) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        do {
            var success = false
            scenario.onActivity { success = condition(it) }
            if (success) return
            SystemClock.sleep(25)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("Timed out waiting for multisport UI")
    }

    private fun awaitFlow(handle: DemoFlowRuntime.TestHandle, condition: (CollectionFlowState) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        do {
            if (condition(handle.flow.state)) return
            SystemClock.sleep(25)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("Timed out waiting for multisport state: ${handle.flow.state}")
    }

    private fun <T : View> tagged(activity: DemoCollectionActivity, tag: String): T =
        requireNotNull(taggedOrNull<T>(activity, tag)) { "Missing tagged view $tag" }
    private fun <T : View> taggedOrNull(activity: DemoCollectionActivity, tag: String): T? =
        activity.findViewById<View>(android.R.id.content).findViewWithTag(tag)
    private fun visibleTexts(view: View): List<String> = when {
        view.visibility != View.VISIBLE -> emptyList()
        view is ViewGroup -> (0 until view.childCount).flatMap { visibleTexts(view.getChildAt(it)) }
        view is TextView -> listOf(view.text.toString())
        else -> emptyList()
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
