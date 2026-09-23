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
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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

    /**
     * Review export only: opt in with captureUiCatalog, verifyCollectionFlow and captureFlowScreens.
     * All states below are in-memory test doubles rendered by the production Activity. In particular,
     * isSimulation=false selects the production layout; the raw-file and receipt objects are only
     * rendering evidence and never reach a production store, BLE connection or upload service.
     * Transient states are held explicitly so their screenshots do not depend on device/network timing.
     */
    @Test fun exportCurrentCollectionUiCatalog() {
        assumeTrue("Explicit UI catalog opt-in is required",
            InstrumentationRegistry.getArguments().getString("captureUiCatalog") == "true")
        assumeTrue("UI catalog export requires screenshot capture",
            InstrumentationRegistry.getArguments().getString("captureFlowScreens") == "true")
        withFlow { handle ->
            launch().use { scenario ->
                register(scenario)
                val preparation = requireNotNull(PreparationStore(File(handle.directory, "profile")).read())
                val now = System.currentTimeMillis()
                val requested = FreeLivingSession("catalog-session", preparation,
                    FreeLivingSessionPhase.START_REQUESTED, "Asia/Shanghai", 28_800, now - 490_000,
                    activity = SessionActivity.WALKING)
                val collecting = requested.copy(phase = FreeLivingSessionPhase.COLLECTING,
                    startConfirmedAtMs = now - 480_000)
                val stopping = collecting.copy(phase = FreeLivingSessionPhase.STOP_REQUESTED,
                    stopRequestedAtMs = now - 20_000)
                val stopped = stopping.copy(phase = FreeLivingSessionPhase.AWAITING_REFERENCE,
                    stopConfirmedAtMs = now - 19_000)
                val reference = stopped.copy(completionPolicy = CompletionPolicy.SAVE_UPLOAD)
                val recorded = reference.copy(reference = SessionReference(ReferenceStatus.VALID, 562, now - 15_000))
                val local = recorded.copy(localData = SessionLocalData(
                    listOf(SessionRawFile("catalog-rendering-only.rfbin", 7, 800, "0".repeat(64))), now - 10_000))
                val base = CollectionFlowState(isSimulation = false, hasProfile = true,
                    participantId = preparation.participantId, placement = preparation.placement,
                    connected = true, uploadAvailable = true)
                val fixture = RenderingFlow(base)
                val names = mutableSetOf<String>()

                fun show(state: CollectionFlowState) {
                    fixture.state = state
                    renderFixture(scenario, fixture)
                    scenario.onActivity {
                        assertNull(taggedOrNull<View>(it, "demo_marker"))
                        assertNull(taggedOrNull<View>(it, "flow_options"))
                    }
                }
                fun capture(name: String, anchorTag: String? = null) {
                    require(name.startsWith("catalog_") && names.add(name))
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { activity ->
                        val scroll = StepCollectionActivity::class.java.getDeclaredField("pageScroll")
                            .apply { isAccessible = true }.get(activity) as android.widget.ScrollView
                        if (anchorTag == null) scroll.scrollTo(0, 0) else {
                            val card = tagged<View>(activity, anchorTag).parent as View
                            val bounds = android.graphics.Rect(0, 0, card.width, card.height)
                            scroll.offsetDescendantRectToMyCoords(card, bounds)
                            scroll.scrollTo(0, bounds.top)
                        }
                    }
                    captureReviewScreen(scenario, name)
                }
                fun screen(name: String, state: CollectionFlowState) { show(state); capture(name) }
                fun savedState(session: FreeLivingSession = local, inFlight: Boolean = false,
                    needsReview: Boolean = false) = base.copy(page = CollectionPage.COMPLETE,
                    taskPage = CollectionPage.COMPLETE, session = session, canStart = true,
                    records = listOf(FlowRecordSummary(session.sessionId, session.reference?.steps,
                        session.reference?.status?.wireValue, session.transfer.status.wireValue, true,
                        transferInFlight = inFlight, localReviewRequired = needsReview,
                        activity = session.activity, uploadDeferred = session.completionPolicy == CompletionPolicy.SAVE_LATER,
                        uploadRequeueAvailable = session.completionPolicy == CompletionPolicy.SAVE_UPLOAD)))

                screen("catalog_home_unselected", base.copy(canStart = true))
                screen("catalog_home_walking", base.copy(canStart = true, selectedActivity = SessionActivity.WALKING))
                screen("catalog_home_running", base.copy(canStart = true, selectedActivity = SessionActivity.RUNNING))
                screen("catalog_starting", base.copy(page = CollectionPage.STARTING,
                    taskPage = CollectionPage.STARTING, session = requested, busy = true))
                screen("catalog_collecting", base.copy(page = CollectionPage.COLLECTING,
                    taskPage = CollectionPage.COLLECTING, session = collecting, canStop = true))
                screen("catalog_stopping", base.copy(page = CollectionPage.STOPPING,
                    taskPage = CollectionPage.STOPPING, session = stopping, busy = true))
                screen("catalog_finish", base.copy(page = CollectionPage.FINISH,
                    taskPage = CollectionPage.FINISH, session = stopped))
                scenario.onActivity { activity ->
                    assertNotNull(taggedOrNull<TextView>(activity, "no_reference_hint"))
                    assertNull(taggedOrNull<View>(activity, "reference_options"))
                    assertNull(taggedOrNull<View>(activity, "flow_reason"))
                }
                click(scenario, "finish_discard")
                capture("catalog_discard_confirmation")
                scenario.onActivity { startAttemptDialog(it).getButton(AlertDialog.BUTTON_NEGATIVE).performClick() }

                val referenceState = base.copy(page = CollectionPage.REFERENCE,
                    taskPage = CollectionPage.REFERENCE, session = stopping)
                screen("catalog_reference_empty", referenceState)
                type(scenario, "flow_steps", "562")
                capture("catalog_reference_number")
                scenario.onActivity { activity ->
                    assertEquals("返回结束确认", tagged<Button>(activity, "reference_resume_stop").text.toString())
                    assertNull(taggedOrNull<View>(activity, "reference_options"))
                    assertNull(taggedOrNull<View>(activity, "flow_reason"))
                }
                scenario.onActivity { activity ->
                    val input = tagged<EditText>(activity, "flow_steps")
                    input.requestFocus()
                    input.setSelection(input.text.length)
                    activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                        .showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }
                await(scenario, "catalog reference keyboard visible") {
                    it.window.decorView.rootWindowInsets.isVisible(android.view.WindowInsets.Type.ime())
                }
                capture("catalog_reference_keyboard")
                scenario.onActivity { activity ->
                    val input = tagged<EditText>(activity, "flow_steps")
                    activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                        .hideSoftInputFromWindow(input.windowToken, 0)
                    input.clearFocus()
                }
                await(scenario, "catalog reference keyboard hidden") {
                    !it.window.decorView.rootWindowInsets.isVisible(android.view.WindowInsets.Type.ime())
                }
                show(referenceState.copy(error = "请输入计步器上的整数"))
                type(scenario, "flow_steps", "")
                capture("catalog_reference_validation")
                type(scenario, "flow_steps", "562")
                screen("catalog_save_failure", referenceState.copy(error = "暂时无法完成，本次记录已保留"))
                screen("catalog_saving", base.copy(page = CollectionPage.SAVING,
                    taskPage = CollectionPage.SAVING, session = reference, busy = true))
                screen("catalog_downloading", base.copy(page = CollectionPage.DOWNLOADING,
                    taskPage = CollectionPage.DOWNLOADING, session = recorded, busy = true,
                    downloadSavedBytes = 3_145_728, downloadTotalBytes = 12_582_912))
                screen("catalog_upload_queue", savedState())
                val uploading = local.copy(transfer = SessionTransfer(SessionTransferStatus.TRANSFERRING, 1))
                screen("catalog_uploading", savedState(uploading, inFlight = true))
                screen("catalog_uploaded", savedState(local.copy(transfer = SessionTransfer(
                    SessionTransferStatus.COMPLETE, 1,
                    SessionTransferReceipt("catalog-rendering-only-receipt", now - 5_000, false, local.sessionId)))))
                screen("catalog_upload_deferred", savedState(local.copy(completionPolicy = CompletionPolicy.SAVE_LATER)))
                screen("catalog_upload_failure", savedState(local.copy(transfer = SessionTransfer(SessionTransferStatus.FAILED, 1))))
                screen("catalog_local_file_review", savedState(needsReview = true))

                val startRecovery = base.copy(page = CollectionPage.RECOVERY, taskPage = CollectionPage.RECOVERY,
                    session = requested, canRetry = true, canEndStartAttempt = true,
                    error = "暂未收到确认，请重新检查戒指")
                screen("catalog_recovery_start", startRecovery)
                show(startRecovery)
                scenario.onActivity { activity ->
                    assertEquals("继续恢复", tagged<Button>(activity, "flow_primary").text.toString())
                    assertEquals("结束本次尝试", tagged<Button>(activity, "end_start_attempt").text.toString())
                }
                click(scenario, "end_start_attempt")
                scenario.onActivity { activity ->
                    val confirmation = startAttemptDialog(activity)
                    assertTrue(confirmation.isShowing)
                    assertTrue(visibleTexts(confirmation.window!!.decorView).contains("结束本次？"))
                    assertEquals("继续等待", confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).text.toString())
                    assertEquals("结束本次", confirmation.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
                }
                capture("catalog_end_attempt_confirmation")
                scenario.onActivity { startAttemptDialog(it).getButton(AlertDialog.BUTTON_NEGATIVE).performClick() }
                screen("catalog_recovery_stop", base.copy(page = CollectionPage.RECOVERY,
                    taskPage = CollectionPage.RECOVERY, session = stopping, canRetry = true,
                    error = "暂未收到确认，请重新检查戒指"))
                screen("catalog_reference_stop_uncertain", referenceState.copy(session = stopping))
                screen("catalog_recovery_disconnected", base.copy(page = CollectionPage.RECOVERY,
                    taskPage = CollectionPage.RECOVERY, session = collecting, connected = false,
                    canRetry = true, error = "连接中断，请将戒指放在手机附近"))
                screen("catalog_recovery_reconnecting", base.copy(page = CollectionPage.RECOVERY,
                    taskPage = CollectionPage.RECOVERY, session = collecting, connected = false,
                    connecting = true, busy = true))
                screen("catalog_download_failure", base.copy(page = CollectionPage.ERROR,
                    taskPage = CollectionPage.ERROR, session = recorded, canRetry = true,
                    error = "下载连接中断，请重试"))
                screen("catalog_recovery_device_unready", base.copy(page = CollectionPage.RECOVERY,
                    canRetry = true, error = "时间同步超时，请重新连接后再试"))
                screen("catalog_recovery_no_connection", base.copy(page = CollectionPage.RECOVERY,
                    connected = false, canRetry = true))

                val observation = HealthRecordObservation(preparation.ring?.address ?: "AA:BB:CC:DD:EE:FF", 1,
                    HealthMessage.Status(true, 800, 10, 0, 7), now - 30_000,
                    listOf(HealthMessage.ListItem(7, 800, 10, 200, 0)))
                val protective = requested.copy(startAbort = UnconfirmedStartAbort(now - 29_000,
                    "00000000-0000-0000-0000-000000000001", observation))
                val protectedStop = protective.copy(startAbort = protective.startAbort!!.copy(
                    stoppedObservation = observation.copy(status = observation.status.copy(collecting = false),
                        statusReceivedAtMs = now - 25_000)))
                screen("catalog_protective_stop", base.copy(page = CollectionPage.RECOVERY,
                    taskPage = CollectionPage.RECOVERY, session = requested, canStopUnconfirmedStart = true))
                screen("catalog_protective_stop_pending", base.copy(page = CollectionPage.RECOVERY,
                    taskPage = CollectionPage.RECOVERY, session = protective, canRetry = true))
                screen("catalog_protective_backup", base.copy(page = CollectionPage.DOWNLOADING,
                    taskPage = CollectionPage.DOWNLOADING, session = protectedStop, busy = true,
                    preservingExisting = true))
                screen("catalog_protective_backup_failure", base.copy(page = CollectionPage.ERROR,
                    taskPage = CollectionPage.ERROR, session = protectedStop, canRetry = true,
                    error = "下载连接中断，请重试"))
                screen("catalog_home_existing_backup", base.copy(preservingExisting = true, busy = true))
                screen("catalog_home_checking", base.copy(checkingDevice = true, busy = true))
                screen("catalog_home_reconnecting", base.copy(connected = false, connecting = true, busy = true))
                screen("catalog_home_disconnected", base.copy(connected = false, canRetry = true))
                screen("catalog_home_finish_pending", base.copy(taskPage = CollectionPage.FINISH, session = stopped))
                screen("catalog_home_reference_pending", base.copy(taskPage = CollectionPage.REFERENCE, session = reference))
                screen("catalog_home_stop_uncertain", base.copy(taskPage = CollectionPage.REFERENCE, session = stopping))
                screen("catalog_home_download_failure", base.copy(taskPage = CollectionPage.ERROR,
                    session = recorded, canRetry = true, error = "下载连接中断，请重试"))

                // One genuine scroll container with representative record states, in its native order.
                val history = listOf(
                    FlowRecordSummary("catalog-history-review", 330, "valid", "pending", true,
                        localReviewRequired = true, activity = SessionActivity.RUNNING),
                    FlowRecordSummary("catalog-history-unreliable", 425, "unreliable", "complete", true,
                        activity = SessionActivity.WALKING),
                    FlowRecordSummary("catalog-history-failed", 618, "valid", "failed", true,
                        activity = SessionActivity.RUNNING),
                    FlowRecordSummary("catalog-history-deferred", null, "missing", "pending", true,
                        activity = SessionActivity.WALKING, uploadDeferred = true),
                    FlowRecordSummary("catalog-history-uploading", 736, "valid", "transferring", true,
                        transferInFlight = true, activity = SessionActivity.RUNNING),
                    FlowRecordSummary("catalog-history-queued", 248, "valid", "pending", true,
                        activity = SessionActivity.WALKING, uploadRequeueAvailable = true),
                    FlowRecordSummary("catalog-history-uploaded", 562, "valid", "complete", true,
                        activity = SessionActivity.WALKING))
                screen("catalog_home_history_top", base.copy(canStart = true, records = history))
                click(scenario, "open_records")
                capture("catalog_home_history_uploaded", "record_status_catalog-history-uploaded")
                capture("catalog_home_history_queue", "record_status_catalog-history-queued")
                capture("catalog_home_history_uploading", "record_status_catalog-history-uploading")
                capture("catalog_home_history_deferred", "record_status_catalog-history-deferred")
                capture("catalog_home_history_failed", "record_status_catalog-history-failed")
                capture("catalog_home_history_unreliable", "record_status_catalog-history-unreliable")
                capture("catalog_home_history_review", "record_status_catalog-history-review")
            }
        }
    }

    @Test fun stoppedRecordOffersLocalFinishFromHomeAndRecoveryWhileBluetoothReconnects() = withFlow { handle ->
        launch().use { scenario ->
            register(scenario)
            reachReference(scenario, handle)
            val stopped = session(handle)
            for (policy in listOf(null, CompletionPolicy.SAVE_LATER)) {
                val fixture = RenderingFlow(CollectionFlowState(page = CollectionPage.HOME,
                    taskPage = CollectionPage.RECOVERY, isSimulation = false, hasProfile = true,
                    participantId = stopped.preparation.participantId, placement = stopped.preparation.placement,
                    session = stopped.copy(completionPolicy = policy), connected = false, connecting = true))
                renderFixture(scenario, fixture)
                scenario.onActivity {
                    val primary = tagged<Button>(it, "home_task_action")
                    assertEquals(if (policy == null) "继续收尾" else "填写步数", primary.text.toString())
                    assertTrue(primary.isEnabled)
                    primary.performClick()
                }
                assertEquals(1, fixture.finishEntries)
                assertEquals(0, fixture.reconnects)
                fixture.state = fixture.state.copy(page = CollectionPage.RECOVERY)
                renderFixture(scenario, fixture)
                awaitHeading(scenario, "结束本段")
                scenario.onActivity {
                    assertNotNull(taggedOrNull<EditText>(it, "flow_steps"))
                    assertEquals(if (policy == null) "保存并上传" else "保存，稍后上传",
                        tagged<Button>(it, "flow_primary").text.toString())
                }
                assertEquals(1, fixture.finishEntries)
                assertEquals(0, fixture.reconnects)
            }
        }
    }

    @Test fun bluetoothRecoveryKeepsTheSameReferenceInputFocusAndCursor() = withFlow { handle ->
        launch().use { scenario ->
            register(scenario)
            reachReference(scenario, handle)
            val stopped = session(handle)
            val fixture = RenderingFlow(CollectionFlowState(page = CollectionPage.REFERENCE,
                taskPage = CollectionPage.REFERENCE, isSimulation = false, hasProfile = true,
                participantId = stopped.preparation.participantId, placement = stopped.preparation.placement,
                session = stopped, connected = true))
            renderFixture(scenario, fixture)
            lateinit var input: EditText
            scenario.onActivity {
                input = tagged(it, "flow_steps")
                input.setText("731")
                input.requestFocus()
                input.setSelection(1)
            }
            for (connecting in listOf(false, true, false)) {
                fixture.state = fixture.state.copy(connected = false, connecting = connecting,
                    canRetry = !connecting, error = if (connecting) "正在连接戒指" else "连接中断")
                renderFixture(scenario, fixture)
                scenario.onActivity {
                    assertSame(input, tagged<EditText>(it, "flow_steps"))
                    assertTrue(input.hasFocus())
                    assertEquals("731", input.text.toString())
                    assertEquals(1, input.selectionStart)
                    assertTrue(tagged<Button>(it, "flow_primary").isEnabled)
                }
            }
            click(scenario, "flow_primary")
            assertEquals(true to Triple("731", "valid", ""), fixture.finalizedReference)
        }
    }

    @Test fun stopConfirmationKeepsTheSameDraftInputAndRevealsFinishActionsInPlace() = withFlow { handle ->
        launch().use { scenario ->
            register(scenario)
            val preparation = requireNotNull(PreparationStore(File(handle.directory, "profile")).read())
            val now = System.currentTimeMillis()
            val stopping = FreeLivingSession("stop-draft-view", preparation,
                FreeLivingSessionPhase.STOP_REQUESTED, "Asia/Shanghai", 28_800, now - 10_000,
                startConfirmedAtMs = now - 9_000, stopRequestedAtMs = now - 1_000,
                activity = SessionActivity.WALKING)
            val fixture = RenderingFlow(CollectionFlowState(page = CollectionPage.STOPPING,
                taskPage = CollectionPage.STOPPING, isSimulation = false, hasProfile = true,
                participantId = preparation.participantId, placement = preparation.placement,
                session = stopping, connected = true, busy = true))
            renderFixture(scenario, fixture)

            lateinit var input: EditText
            scenario.onActivity { activity ->
                assertEquals("结束本段", tagged<TextView>(activity, "flow_heading").text.toString())
                input = tagged(activity, "flow_steps")
                input.setText("731")
                input.requestFocus()
                input.setSelection(1)
                assertEquals(View.GONE, tagged<View>(activity, "finish_actions").visibility)
                assertNull(taggedOrNull<View>(activity, "finish_defer")?.takeIf { it.isShown })
                assertNull(taggedOrNull<View>(activity, "finish_discard")?.takeIf { it.isShown })
            }
            assertEquals(listOf("731"), fixture.referenceDraftUpdates)
            assertNull(fixture.state.session!!.reference)

            fixture.state = fixture.state.copy(page = CollectionPage.RECOVERY,
                taskPage = CollectionPage.RECOVERY, busy = false, canRetry = true,
                error = "暂未收到确认，请重新检查戒指", referenceDraft = "731",
                referenceDraftError = "步数还没有保存在手机，请重新保存")
            renderFixture(scenario, fixture)
            scenario.onActivity { activity ->
                assertSame(input, tagged<EditText>(activity, "flow_steps"))
                assertTrue(input.hasFocus())
                assertEquals("731", input.text.toString())
                assertEquals(1, input.selectionStart)
                assertTrue(tagged<Button>(activity, "finish_stop_recovery").isShown)
                assertEquals(View.GONE, tagged<View>(activity, "finish_actions").visibility)
                assertTrue(tagged<TextView>(activity, "draft_save_error").isShown)
                assertTrue(tagged<Button>(activity, "draft_save_retry").isShown)
            }
            click(scenario, "draft_save_retry")
            assertEquals("731", fixture.referenceDraftUpdates.last())
            renderFixture(scenario, fixture)
            scenario.onActivity { activity ->
                assertEquals(View.GONE, tagged<View>(activity, "draft_save_error").visibility)
                assertEquals(View.GONE, tagged<View>(activity, "draft_save_retry").visibility)
            }
            assertNull(fixture.state.session!!.reference)

            fixture.state = fixture.state.copy(page = CollectionPage.FINISH,
                taskPage = CollectionPage.FINISH, busy = false, canRetry = false, error = null,
                session = stopping.copy(phase = FreeLivingSessionPhase.AWAITING_REFERENCE,
                    stopConfirmedAtMs = now), referenceDraft = "731")
            renderFixture(scenario, fixture)
            scenario.onActivity { activity ->
                assertSame(input, tagged<EditText>(activity, "flow_steps"))
                assertTrue(input.hasFocus())
                assertEquals("731", input.text.toString())
                assertEquals(1, input.selectionStart)
                assertEquals(View.GONE, tagged<View>(activity, "stop_confirmation").visibility)
                assertEquals(View.VISIBLE, tagged<View>(activity, "finish_actions").visibility)
                assertTrue(tagged<Button>(activity, "flow_primary").isEnabled)
                assertTrue(tagged<Button>(activity, "finish_defer").isEnabled)
                assertTrue(tagged<Button>(activity, "finish_discard").isEnabled)
            }
            assertNull(fixture.state.session!!.reference)
            click(scenario, "flow_primary")
            assertEquals(true to Triple("731", "valid", ""), fixture.finalizedReference)
        }
    }

    @Test fun earlierReferenceWaitsForStopConfirmationBeforeShowingFinishActions() = withFlow { handle ->
        launch().use { scenario ->
            register(scenario)
            val preparation = requireNotNull(PreparationStore(File(handle.directory, "profile")).read())
            val now = System.currentTimeMillis()
            val stopping = FreeLivingSession("earlier-reference-stop", preparation,
                FreeLivingSessionPhase.STOP_REQUESTED, "Asia/Shanghai", 28_800, now - 10_000,
                startConfirmedAtMs = now - 9_000, stopRequestedAtMs = now - 1_000,
                reference = SessionReference(ReferenceStatus.VALID, 731, now - 500),
                activity = SessionActivity.WALKING)
            val fixture = RenderingFlow(CollectionFlowState(page = CollectionPage.RECOVERY,
                taskPage = CollectionPage.RECOVERY, isSimulation = false, hasProfile = true,
                participantId = preparation.participantId, placement = preparation.placement,
                session = stopping, connected = true, busy = false, canRetry = true))
            renderFixture(scenario, fixture)

            scenario.onActivity { activity ->
                assertEquals("正在结束", tagged<TextView>(activity, "flow_heading").text.toString())
                assertEquals("继续确认结束", tagged<Button>(activity, "flow_primary").text.toString())
                assertNull(taggedOrNull<View>(activity, "finish_actions"))
                assertNull(taggedOrNull<View>(activity, "finish_discard"))
                assertNull(taggedOrNull<EditText>(activity, "flow_steps"))
            }
        }
    }

    @Test fun ringDeferredSaveReopensWithTheSameZeroAndOnlyDownloadsFromTheRecordAction() = withFlow { handle ->
        lateinit var saved: FreeLivingSession
        launch().use { scenario ->
            register(scenario)
            reachFinish(scenario, handle, SessionActivity.RUNNING)
            captureReviewScreen(scenario, "finish")
            type(scenario, "flow_steps", "0")
            click(scenario, "finish_defer")
            awaitFlow(handle, "ring defer persists") { it.session?.isRingDeferred == true }
            awaitHeading(scenario, "步数采集")
            assertEquals(CompletionPolicy.DEFER_ON_RING, session(handle).completionPolicy)
            saved = session(handle)
            assertEquals(SessionActivity.RUNNING, saved.activity)
            assertEquals(0L, saved.reference!!.steps)
            assertNull(saved.localData)
            assertEquals(0, saved.transfer.attempts)
            assertNull(saved.transfer.receipt)
            captureReviewScreen(scenario, "deferred_saved")
            awaitHeading(scenario, "步数采集")
            openRecords(scenario)
            scenario.onActivity {
                assertEquals("已暂存到戒指", tagged<TextView>(it, "record_status_${saved.sessionId}").text.toString())
                assertNull(taggedOrNull<Button>(it, "flow_primary"))
            }
            captureReviewScreen(scenario, "deferred_history")
        }
        launch().use { reopened ->
            awaitHeading(reopened, "步数采集")
            assertEquals(saved, session(handle))
            openRecords(reopened)
            click(reopened, "discard_record_${saved.sessionId}")
            reopened.onActivity { startAttemptDialog(it).getButton(AlertDialog.BUTTON_NEGATIVE).performClick() }
            assertEquals(saved, session(handle))
            click(reopened, "retry_upload_${saved.sessionId}")
            awaitFlow(handle, "manual upload receipt") { it.records.single().transferStatus == "complete" }
            assertEquals(saved.reference, session(handle).reference)
            assertNotNull(session(handle).localData)
            assertEquals(1, session(handle).transfer.attempts)
            assertEquals(CompletionPolicy.SAVE_UPLOAD, session(handle).completionPolicy)
            assertNotNull(session(handle).transfer.receipt)
        }
    }

    @Test fun discardConfirmationCanBeCancelledThenRemovesOnlyTheSelectedStoppedSession() = withFlow { handle ->
        launch().use { scenario ->
            register(scenario)
            reachReference(scenario, handle)
            type(scenario, "flow_steps", "73")
            click(scenario, "flow_primary")
            awaitHeading(scenario, "这一段已保存")
            awaitFlow(handle, "previous record upload completes before discard isolation check") {
                it.records.single().transferStatus == "complete"
            }
            val previous = session(handle)
            val otherFiles = handle.directory.listFiles()!!.filter { it.name.startsWith(previous.sessionId) }
                .associate { it.name to hash(it.readBytes()) }
            awaitHeading(scenario, "步数采集")
            val discardedId = reachFinish(scenario, handle, SessionActivity.RUNNING)
            val stopped = session(handle)
            type(scenario, "flow_steps", "29")
            scenario.onActivity { activity ->
                assertEquals("无法提供本次步数时，可放弃本段。",
                    tagged<TextView>(activity, "no_reference_hint").text.toString())
                assertNull(taggedOrNull<View>(activity, "reference_options"))
                assertNull(taggedOrNull<View>(activity, "flow_reason"))
            }
            click(scenario, "finish_discard")
            captureReviewScreen(scenario, "discard_confirmation")
            scenario.onActivity {
                val confirmation = startAttemptDialog(it)
                assertEquals("删除本段记录，不会上传。此操作无法撤销。",
                    confirmation.findViewById<TextView>(android.R.id.message).text.toString())
                assertEquals("返回", confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).text.toString())
                assertEquals("确认放弃", confirmation.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
                confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            }
            awaitHeading(scenario, "结束本段")
            assertEquals(stopped, session(handle))
            assertNull(session(handle).reference)
            scenario.onActivity { assertEquals("29", tagged<EditText>(it, "flow_steps").text.toString()) }
            type(scenario, "flow_steps", "")
            click(scenario, "finish_discard")
            scenario.onActivity {
                val confirm = startAttemptDialog(it).getButton(AlertDialog.BUTTON_POSITIVE)
                assertTrue(confirm.performClick())
                confirm.performClick()
            }
            awaitHeading(scenario, "步数采集")
            val store = FreeLivingSessionStore(File(handle.directory, "session.json"))
            assertTrue(store.read(discardedId)!!.isDiscarded)
            assertNull(store.read(discardedId)!!.reference)
            assertNull(store.read(discardedId)!!.localData)
            assertEquals(previous, store.read(previous.sessionId))
            otherFiles.forEach { (name, digest) -> assertEquals(name, digest, hash(File(handle.directory, name).readBytes())) }
            assertTrue(handle.directory.listFiles()!!.none {
                it.name.startsWith(discardedId) && (it.name.contains("signal") || it.name.contains("receipt"))
            })
            assertEquals(listOf(previous.sessionId), handle.flow.state.records.map { it.sessionId })
            scenario.recreate()
            awaitHeading(scenario, "步数采集")
            assertTrue(handle.flow.state.records.none { it.sessionId == discardedId })
            assertNull(handle.flow.state.selectedActivity)
            scenario.onActivity { assertNull(taggedOrNull<Button>(it, "flow_primary")) }
            captureReviewScreen(scenario, "discarded_home")
        }
    }

    @Test fun parkedRecordHasOneResumeActionAndDiscardCancellationKeepsItsSavedZero() = withFlow { handle ->
        launch().use { scenario ->
            register(scenario)
            reachFinish(scenario, handle, SessionActivity.WALKING)
            type(scenario, "flow_steps", "0")
            click(scenario, "finish_defer")
            awaitFlow(handle, "deferred reference durable") { it.session?.isRingDeferred == true }
            awaitHeading(scenario, "步数采集")
            val saved = session(handle)
            scenario.onActivity { activity ->
                assertEquals("已暂存到戒指", tagged<TextView>(activity, "home_task_status").text.toString())
                assertEquals("下载并上传", tagged<Button>(activity, "home_task_action").text.toString())
                assertNull(taggedOrNull<Button>(activity, "flow_primary"))
                assertNull(taggedOrNull<Button>(activity, "activity_walking"))
            }
            click(scenario, "recovery_discard")
            scenario.onActivity { startAttemptDialog(it).getButton(AlertDialog.BUTTON_NEGATIVE).performClick() }
            assertEquals(saved, session(handle))
            scenario.recreate()
            awaitHeading(scenario, "步数采集")
            handle.flow.disconnect()
            awaitFlow(handle, "ring temporarily disconnected") { !it.connected }
            scenario.onActivity {
                assertTrue(tagged<Button>(it, "home_task_action").isEnabled)
                assertNull(taggedOrNull<Button>(it, "home_reconnect"))
            }
            click(scenario, "recovery_discard")
            scenario.onActivity { startAttemptDialog(it).getButton(AlertDialog.BUTTON_POSITIVE).performClick() }
            awaitFlow(handle, "discarded record removed from page") { it.records.isEmpty() }
            val discarded = FreeLivingSessionStore(File(handle.directory, "session.json")).read(saved.sessionId)!!
            assertTrue(discarded.isDiscarded)
            assertNull(discarded.localData)
            assertNull(discarded.transfer.receipt)
            handle.flow.reconnect()
            awaitFlow(handle, "next session available after discard") { it.canStart }
        }
    }

    @Test fun startingANewSegmentDoesNotPresentThePreviousCompletedActivity() = withFlow { handle ->
        launch().use { scenario ->
            register(scenario)
            val preparation = requireNotNull(PreparationStore(File(handle.directory, "profile")).read())
            val previous = renderingSession(preparation).copy(activity = SessionActivity.RUNNING,
                localData = SessionLocalData(emptyList(), 5_000))
            val fixture = RenderingFlow(CollectionFlowState(page = CollectionPage.STARTING,
                taskPage = CollectionPage.STARTING, isSimulation = false, hasProfile = true,
                session = previous, connected = true, busy = true))
            renderFixture(scenario, fixture)
            scenario.onActivity { activity ->
                val texts = visibleTexts(activity.findViewById(android.R.id.content))
                assertTrue(texts.contains("正在准备"))
                assertFalse(texts.contains("跑步"))
            }
        }
    }

    @Test fun eachNewCaptureRequiresAVisibleFreshChoiceAndKeepsTheDeclaredActivity() = withFlow { handle ->
        launch().use { scenario ->
            register(scenario)
            scenario.onActivity {
                assertNull(taggedOrNull<Button>(it, "flow_primary"))
                assertTrue(tagged<Button>(it, "activity_walking").isShown)
                assertTrue(tagged<Button>(it, "activity_running").isShown)
            }
            reachReference(scenario, handle)
            assertEquals(SessionActivity.WALKING, session(handle).activity)
            assertNull(handle.flow.state.selectedActivity)
            type(scenario, "flow_steps", "12")
            click(scenario, "flow_primary")
            awaitHeading(scenario, "这一段已保存")
            val first = session(handle)
            awaitHeading(scenario, "步数采集")
            scenario.onActivity { assertNull(taggedOrNull<Button>(it, "flow_primary")) }
            reachFinish(scenario, handle, SessionActivity.RUNNING)
            assertEquals(SessionActivity.RUNNING, session(handle).activity)
            assertNotEquals(first.sessionId, session(handle).sessionId)
            val persistedFirst = requireNotNull(
                FreeLivingSessionStore(File(handle.directory, "session.json")).read(first.sessionId))
            // The previous upload may finish while the next activity is collected.
            assertEquals(first.copy(transfer = persistedFirst.transfer), persistedFirst)
            awaitFlow(handle, "previous activity upload completes independently") {
                it.records.single { record -> record.sessionId == first.sessionId }.transferStatus == "complete"
            }
        }
    }

    @Test fun connectionRecoveryDoesNotDescribeTheLastSavedSessionAsUnfinished() = withFlow { handle ->
        launch().use { scenario ->
            register(scenario)
            val preparation = requireNotNull(PreparationStore(File(handle.directory, "profile")).read())
            val saved = FreeLivingSession("saved-view", preparation, FreeLivingSessionPhase.AWAITING_REFERENCE,
                "Asia/Shanghai", 28_800, 1_000, localData = SessionLocalData(emptyList(), 5_000))
            val fixture = RenderingFlow(CollectionFlowState(page = CollectionPage.RECOVERY, isSimulation = false,
                hasProfile = true, participantId = preparation.participantId, placement = preparation.placement,
                session = saved, connected = true, canRetry = true, error = "时间同步超时，请重新连接后再试"))
            renderFixture(scenario, fixture)
            scenario.onActivity { activity ->
                assertEquals("正在恢复", tagged<TextView>(activity, "flow_heading").text.toString())
                assertEquals("继续恢复", tagged<Button>(activity, "flow_primary").text.toString())
                assertTrue(tagged<Button>(activity, "flow_primary").isEnabled)
                assertEquals("需要检查", tagged<TextView>(activity, "flow_detail_戒指").text.toString())
                assertNull(taggedOrNull<View>(activity, "saved_reference"))
                assertNull(taggedOrNull<View>(activity, "preserve_reference"))
                assertNull(taggedOrNull<View>(activity, "end_start_attempt"))
            }
            click(scenario, "flow_primary")
            assertEquals(1, fixture.retries)
        }
    }

    @Test fun uncertainStartOffersProtectiveStopWithoutClaimingSuccessfulCollection() = withFlow { handle ->
        launch().use { scenario ->
            register(scenario)
            val preparation = requireNotNull(PreparationStore(File(handle.directory, "profile")).read())
            val pending = FreeLivingSession("protective-view", preparation, FreeLivingSessionPhase.START_REQUESTED,
                "Asia/Shanghai", 28_800, 1_000)
            val fixture = RenderingFlow(CollectionFlowState(page = CollectionPage.RECOVERY, isSimulation = false,
                hasProfile = true, participantId = preparation.participantId, placement = preparation.placement,
                session = pending, connected = true, canStopUnconfirmedStart = true))
            renderFixture(scenario, fixture)
            scenario.onActivity { activity ->
                assertEquals("需要结束本次记录", tagged<TextView>(activity, "flow_heading").text.toString())
                assertEquals("结束并保存数据", tagged<Button>(activity, "flow_primary").text.toString())
                assertNull(taggedOrNull<View>(activity, "end_start_attempt"))
            }
            click(scenario, "flow_primary")
            assertEquals(1, fixture.protectiveStops)
            assertNull(fixture.state.session!!.startConfirmedAtMs)
            fixture.state = fixture.state.copy(busy = true)
            renderFixture(scenario, fixture)
            scenario.onActivity { assertNull(taggedOrNull<Button>(it, "flow_primary")) }
            fixture.state = fixture.state.copy(page = CollectionPage.HOME, taskPage = CollectionPage.RECOVERY, busy = false)
            renderFixture(scenario, fixture)
            scenario.onActivity {
                assertEquals("需要结束戒指记录", tagged<TextView>(it, "home_task_status").text.toString())
                assertEquals("结束并保存可用数据", tagged<Button>(it, "home_task_action").text.toString())
            }
            click(scenario, "home_task_action")
            assertEquals(2, fixture.protectiveStops)
        }
    }

    @Test fun protectiveStopBackupAndRecoveryKeepConfirmedStopSeparateFromSavedResearchData() = withFlow { handle ->
        launch().use { scenario ->
            register(scenario)
            val preparation = requireNotNull(PreparationStore(File(handle.directory, "profile")).read())
            val collecting = HealthRecordObservation(preparation.ring?.address ?: "AA:BB:CC:DD:EE:FF", 1,
                HealthMessage.Status(true, 100, 1, 0, 7), 1_500,
                listOf(HealthMessage.ListItem(7, 100, 1, 200, 0)))
            val stopped = collecting.copy(status = collecting.status.copy(collecting = false), statusReceivedAtMs = 2_000)
            val pending = FreeLivingSession("protective-stopped-view", preparation, FreeLivingSessionPhase.START_REQUESTED,
                "Asia/Shanghai", 28_800, 1_000, startAbort = UnconfirmedStartAbort(1_600,
                    "00000000-0000-0000-0000-000000000001", collecting, stoppedObservation = stopped))
            val fixture = RenderingFlow(CollectionFlowState(page = CollectionPage.DOWNLOADING,
                taskPage = CollectionPage.DOWNLOADING, isSimulation = false, uploadAvailable = true,
                hasProfile = true, participantId = preparation.participantId, placement = preparation.placement,
                session = pending, connected = true, busy = true, preservingExisting = true))
            fun visibleText(view: View): List<String> = when (view) {
                is ViewGroup -> (0 until view.childCount).flatMap { visibleText(view.getChildAt(it)) }
                is TextView -> listOf(view.text.toString())
                else -> emptyList()
            }
            fun assertNoResearchCompletion(activity: DemoCollectionActivity) {
                listOf("saved_reference", "flow_detail_计步器读数", "flow_detail_上传", "preserve_reference",
                    "end_start_attempt", "retry_upload_${pending.sessionId}").forEach { tag ->
                    assertNull("A stopped unconfirmed start must not expose $tag", taggedOrNull<View>(activity, tag))
                }
                val text = visibleText(activity.window.decorView)
                listOf("步数已保存", "待保存读数", "这一段已保存", "开始状态待确认").forEach { phrase ->
                    assertTrue("Unexpected research or stale start state: $phrase", text.none { it.contains(phrase) })
                }
            }
            renderFixture(scenario, fixture)
            scenario.onActivity {
                assertEquals("步数采集", tagged<TextView>(it, "flow_heading").text.toString())
                assertEquals("戒指已停止", tagged<TextView>(it, "home_task_status").text.toString())
                assertNull(taggedOrNull<Button>(it, "flow_primary"))
                assertNull(taggedOrNull<Button>(it, "home_task_action"))
                assertNoResearchCompletion(it)
            }
            fixture.state = fixture.state.copy(page = CollectionPage.HOME)
            renderFixture(scenario, fixture)
            scenario.onActivity {
                assertEquals("步数采集", tagged<TextView>(it, "flow_heading").text.toString())
                assertEquals("戒指已停止", tagged<TextView>(it, "home_task_status").text.toString())
                assertNull(taggedOrNull<View>(it, "home_task_action"))
                assertNoResearchCompletion(it)
            }
            for (failurePage in listOf(CollectionPage.ERROR, CollectionPage.RECOVERY)) {
                fixture.state = fixture.state.copy(page = failurePage, taskPage = failurePage,
                    busy = false, preservingExisting = false, canRetry = true, error = "下载中断，请重试")
                renderFixture(scenario, fixture)
                scenario.onActivity {
                    assertEquals("正在保存数据", tagged<TextView>(it, "flow_heading").text.toString())
                    assertEquals("已停止", tagged<TextView>(it, "flow_detail_戒指").text.toString())
                    assertEquals("继续恢复", tagged<Button>(it, "flow_primary").text.toString())
                    assertTrue(tagged<Button>(it, "flow_primary").isEnabled)
                    assertNoResearchCompletion(it)
                }
                fixture.state = fixture.state.copy(page = CollectionPage.HOME)
                renderFixture(scenario, fixture)
                scenario.onActivity {
                    assertEquals("步数采集", tagged<TextView>(it, "flow_heading").text.toString())
                    assertEquals("戒指已停止", tagged<TextView>(it, "home_task_status").text.toString())
                    assertEquals("继续保存", tagged<Button>(it, "home_task_action").text.toString())
                    assertTrue(tagged<Button>(it, "home_task_action").isEnabled)
                    assertNoResearchCompletion(it)
                }
            }
            assertEquals(stopped, fixture.state.session!!.startAbort!!.stoppedObservation)
            assertNull(fixture.state.session!!.startConfirmedAtMs)
            assertNull(fixture.state.session!!.reference)
            assertNull(fixture.state.session!!.localData)
            assertEquals(0, fixture.protectiveStops)
            assertTrue(fixture.uploadRetries.isEmpty())
        }
    }

    @Test fun preservingOldDeviceDataShowsOneWaitingTaskWithoutInventingReferenceSuccess() = withFlow {
        launch().use { scenario ->
            val fixture = RenderingFlow(CollectionFlowState(page = CollectionPage.HOME, isSimulation = false,
                hasProfile = true, participantId = "preview001", placement = RingPlacement.RIGHT_INDEX,
                preservingExisting = true, connected = true, busy = true))
            renderFixture(scenario, fixture)
            scenario.onActivity { activity ->
                assertEquals("步数采集", tagged<TextView>(activity, "flow_heading").text.toString())
                assertEquals("请稍候", tagged<TextView>(activity, "home_task_status").text.toString())
                assertNull(taggedOrNull<Button>(activity, "flow_primary"))
                assertNull(taggedOrNull<Button>(activity, "home_task_action"))
            }
            fixture.state = fixture.state.copy(preservingExisting = false, busy = false, canStart = true,
                selectedActivity = SessionActivity.WALKING)
            renderFixture(scenario, fixture)
            scenario.onActivity { activity ->
                assertEquals("步数采集", tagged<TextView>(activity, "flow_heading").text.toString())
                // Completing the automatic backup must return to the usable activity chooser.
                // A green progress card without these actions is a dead-end for participants.
                assertTrue(tagged<Button>(activity, "activity_walking").isShown)
                assertTrue(tagged<Button>(activity, "activity_running").isShown)
                assertEquals("开始采集", tagged<Button>(activity, "flow_primary").text.toString())
                assertTrue(tagged<Button>(activity, "flow_primary").isEnabled)
            }
        }
    }

    @Test fun backupProgressKeepsItsViewsAndPausedDownloadOffersAWorkingReconnect() = withFlow {
        launch().use { scenario ->
            val saving = CollectionFlowState(page = CollectionPage.HOME, taskPage = CollectionPage.HOME,
                isSimulation = false, hasProfile = true, participantId = "p001",
                placement = RingPlacement.RIGHT_INDEX, preservingExisting = true, connected = true, busy = true)
            val fixture = RenderingFlow(saving)
            renderFixture(scenario, fixture)
            var savingView: View? = null
            scenario.onActivity { activity ->
                savingView = tagged<TextView>(activity, "home_task_status")
                    assertEquals("正在准备", tagged<TextView>(activity, "home_device_status").text.toString())
            }
            repeat(8) {
                fixture.state = saving.copy()
                renderFixture(scenario, fixture)
                scenario.onActivity { activity ->
                    assertSame("Unchanged download state must keep the existing view", savingView,
                        tagged<TextView>(activity, "home_task_status"))
                    assertNull(taggedOrNull<Button>(activity, "home_task_action"))
                }
            }
            captureReviewScreen(scenario, "stable_existing_backup")
            fixture.state = saving.copy(taskPage = CollectionPage.RECOVERY, preservingExisting = false,
                connected = false, busy = false, canRetry = true,
                error = RealSessionDownload.DownloadNoProgressException().message)
            renderFixture(scenario, fixture)
            scenario.onActivity { activity ->
                val reconnect = tagged<Button>(activity, "home_reconnect")
                assertEquals("重新连接", reconnect.text.toString())
                assertTrue(reconnect.isEnabled)
                assertTrue(visibleTexts(activity.window.decorView).contains(
                    "接收暂时中断，已保存进度。请将戒指靠近手机后重新连接。"))
                reconnect.performClick()
            }
            assertEquals(1, fixture.reconnects)
            captureReviewScreen(scenario, "stalled_download_reconnect")
        }
    }

    @Test fun longDownloadShowsDurableProgressAndUpdatesWithoutRebuildingThePage() = withFlow { handle ->
        launch().use { scenario ->
            register(scenario)
            val preparation = requireNotNull(PreparationStore(File(handle.directory, "profile")).read())
            val now = System.currentTimeMillis()
            val session = FreeLivingSession("long-download-view", preparation,
                FreeLivingSessionPhase.AWAITING_REFERENCE, "Asia/Shanghai", 28_800, now - 10_800_000,
                stopConfirmedAtMs = now - 10_000, completionPolicy = CompletionPolicy.SAVE_UPLOAD,
                reference = SessionReference(ReferenceStatus.VALID, 20_000, now - 9_000),
                activity = SessionActivity.WALKING)
            val initial = CollectionFlowState(page = CollectionPage.DOWNLOADING,
                taskPage = CollectionPage.DOWNLOADING, isSimulation = false, uploadAvailable = true,
                hasProfile = true, participantId = preparation.participantId, placement = preparation.placement,
                session = session, connected = true, busy = true,
                downloadSavedBytes = 2_621_440, downloadTotalBytes = 10_485_760)
            val fixture = RenderingFlow(initial)
            renderFixture(scenario, fixture)
            var originalStatus: View? = null
            scenario.onActivity { activity ->
                originalStatus = tagged<TextView>(activity, "home_task_status")
                assertEquals("步数已保存", (originalStatus as TextView).text.toString())
                assertTrue(tagged<TextView>(activity, "home_task_hint").text.contains("25%"))
                assertEquals(250, tagged<ProgressBar>(activity, "home_download_progress").progress)
                assertNull(taggedOrNull<Button>(activity, "home_task_action"))
            }

            fixture.state = initial.copy(downloadSavedBytes = 7_864_320)
            renderFixture(scenario, fixture)
            scenario.onActivity { activity ->
                assertSame(originalStatus, tagged<TextView>(activity, "home_task_status"))
                assertTrue(tagged<TextView>(activity, "home_task_hint").text.contains("75%"))
                assertEquals(750, tagged<ProgressBar>(activity, "home_download_progress").progress)
            }

            fixture.state = initial.copy(downloadSavedBytes = 10_485_760, downloadFinalizing = true)
            renderFixture(scenario, fixture)
            scenario.onActivity { activity ->
                assertSame(originalStatus, tagged<TextView>(activity, "home_task_status"))
                assertEquals("原始数据已接收完成，正在检查并保存文件。",
                    tagged<TextView>(activity, "home_task_hint").text.toString())
                assertEquals(1_000, tagged<ProgressBar>(activity, "home_download_progress").progress)
            }
        }
    }

    @Test fun permissionFailureWithoutOwnerHasWorkingToolbarAndSystemBack() = withFlow {
        assumeTrue("A running real owner must be left untouched", !RealCollectionBridge.isRunning())
        listOf(false, true).forEach { systemBack ->
            val monitor = instrumentation.addMonitor(StepPreparationActivity::class.java.name, null, true)
            try {
                launch().use { scenario ->
                    val fixture = RenderingFlow(CollectionFlowState(page = CollectionPage.ERROR,
                        isSimulation = false, uploadAvailable = false, hasProfile = true,
                        connected = false, error = "请允许蓝牙权限后继续"))
                    renderFixture(scenario, fixture)
                    scenario.onActivity { activity ->
                        assertNull(taggedOrNull<View>(activity, "flow_participant"))
                        assertNull(taggedOrNull<View>(activity, "open_records"))
                        assertNotNull(taggedOrNull<Button>(activity, "recovery_settings"))
                        if (systemBack) activity.onBackPressed()
                        else tagged<Button>(activity, "flow_back").performClick()
                        assertTrue(activity.isFinishing)
                    }
                    assertEquals("Return must reach user/device settings", 1, monitor.hits)
                }
            } finally { instrumentation.removeMonitor(monitor) }
        }
    }

    @Test fun endStartAttemptRequiresConfirmationAndWaitsForTheOwnerResult() = withFlow { handle ->
        launch().use { scenario ->
            register(scenario)
            val preparation = requireNotNull(PreparationStore(File(handle.directory, "profile")).read())
            val request = FreeLivingSession("view-start-request", preparation,
                FreeLivingSessionPhase.START_REQUESTED, "Asia/Shanghai", 28_800, 1_000)
            val fixture = RenderingFlow(CollectionFlowState(page = CollectionPage.RECOVERY, isSimulation = false,
                hasProfile = true, participantId = preparation.participantId, placement = preparation.placement,
                session = request, connected = true, canRetry = true))
            renderFixture(scenario, fixture)
            scenario.onActivity { assertNull(taggedOrNull<View>(it, "end_start_attempt")) }
            fixture.state = fixture.state.copy(page = CollectionPage.HOME, canEndStartAttempt = true)
            renderFixture(scenario, fixture)
            scenario.onActivity { assertNull(taggedOrNull<View>(it, "end_start_attempt")) }
            fixture.state = fixture.state.copy(page = CollectionPage.RECOVERY)
            renderFixture(scenario, fixture)
            scenario.onActivity {
                assertEquals("结束本次尝试", tagged<Button>(it, "end_start_attempt").text.toString())
                assertEquals("继续恢复", tagged<Button>(it, "flow_primary").text.toString())
            }
            click(scenario, "end_start_attempt")
            scenario.onActivity { activity ->
                val confirmation = startAttemptDialog(activity)
                assertTrue(confirmation.isShowing)
                assertTrue(visibleTexts(confirmation.window!!.decorView).contains("结束本次？"))
                assertEquals("继续等待", confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).text.toString())
                assertEquals("结束本次", confirmation.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
                assertTrue(fixture.endedStartAttempts.isEmpty())
                assertTrue(confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).performClick())
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertFalse(startAttemptDialog(activity).isShowing)
                assertTrue(fixture.endedStartAttempts.isEmpty())
                assertEquals("正在确认开始", tagged<TextView>(activity, "flow_heading").text.toString())
            }
            click(scenario, "end_start_attempt")
            scenario.onActivity { activity ->
                val confirmation = startAttemptDialog(activity)
                val confirm = confirmation.getButton(AlertDialog.BUTTON_POSITIVE)
                assertTrue(confirm.performClick())
                assertFalse(confirmation.isShowing)
                confirm.performClick() // A queued second activation must not dispatch again.
                assertEquals(listOf("用户选择结束未确认的开始请求"), fixture.endedStartAttempts)
                assertEquals("正在确认开始", tagged<TextView>(activity, "flow_heading").text.toString())
                assertEquals(CollectionPage.RECOVERY, fixture.state.page)
                assertEquals(request, fixture.state.session)
                assertNull(fixture.state.session!!.startAttemptArchive)
                assertNull(fixture.state.session!!.localData)
            }
            fixture.state = fixture.state.copy(page = CollectionPage.HOME, taskPage = CollectionPage.RECOVERY,
                session = null, canEndStartAttempt = false, error = "戒指返回异常，请重新连接后再试")
            renderFixture(scenario, fixture)
            scenario.onActivity { activity ->
                assertEquals("步数采集", tagged<TextView>(activity, "flow_heading").text.toString())
                assertEquals("需要检查", tagged<TextView>(activity, "home_device_status").text.toString())
                assertEquals("戒指暂未就绪", tagged<TextView>(activity, "home_task_status").text.toString())
                assertEquals("重新检查", tagged<Button>(activity, "home_task_action").text.toString())
                assertNull(taggedOrNull<View>(activity, "home_recheck"))
                assertNull(taggedOrNull<View>(activity, "end_start_attempt"))
            }
            fixture.state = fixture.state.copy(page = CollectionPage.HOME)
            renderFixture(scenario, fixture)
            scenario.onActivity { activity ->
                assertEquals("戒指暂未就绪", tagged<TextView>(activity, "home_task_status").text.toString())
                assertNull(taggedOrNull<View>(activity, "flow_error"))
                assertEquals("重新检查", tagged<Button>(activity, "home_task_action").text.toString())
            }
            fixture.state = fixture.state.copy(checkingDevice = true, busy = true, error = null)
            renderFixture(scenario, fixture)
            scenario.onActivity { activity ->
                assertEquals("正在检查戒指", tagged<TextView>(activity, "home_task_status").text.toString())
                assertEquals("正在检查", tagged<TextView>(activity, "home_device_status").text.toString())
                assertNull(taggedOrNull<Button>(activity, "flow_primary"))
                assertNull(taggedOrNull<Button>(activity, "home_task_action"))
            }
        }
    }

    @Test fun allUnuploadedHistoryRemainsReachableAlongsideOnlyTheLastThreeUploadedRecords() = withFlow { _ ->
        launch().use { scenario ->
            val history = listOf(
                FlowRecordSummary("uploaded-old", 1, "valid", "complete", true),
                FlowRecordSummary("failed-old", 2, "valid", "failed", true),
                FlowRecordSummary("uploaded-a", 3, "valid", "complete", true),
                FlowRecordSummary("failed-b", 4, "valid", "failed", true),
                FlowRecordSummary("uploaded-b", 5, "valid", "complete", true),
                FlowRecordSummary("failed-c", 6, "valid", "failed", true),
                FlowRecordSummary("pending-d", 7, "valid", "pending", true),
                FlowRecordSummary("uploaded-c", 8, "valid", "complete", true),
                FlowRecordSummary("failed-new", 9, "valid", "failed", true))
            val fixture = RenderingFlow(CollectionFlowState(isSimulation = false, uploadAvailable = true,
                hasProfile = true, participantId = "view001", placement = RingPlacement.LEFT_INDEX,
                connected = true, canStart = true, records = history, selectedActivity = SessionActivity.WALKING))
            renderFixture(scenario, fixture)
            scenario.onActivity {
                assertNotNull(taggedOrNull<View>(it, "home_records_summary"))
                assertEquals("开始采集", tagged<Button>(it, "flow_primary").text.toString())
            }
            openRecords(scenario)
            scenario.onActivity { activity ->
                fun statuses(view: View): List<String> = buildList {
                    (view.tag as? String)?.takeIf { it.startsWith("record_status_") }?.let { add(it.removePrefix("record_status_")) }
                    if (view is ViewGroup) repeat(view.childCount) { addAll(statuses(view.getChildAt(it))) }
                }
                assertEquals(history.reversed().map { it.sessionId },
                    statuses(activity.findViewById(android.R.id.content)))
                history.filter { it.transferStatus == "failed" }.forEach {
                    val retry = tagged<Button>(activity, "retry_upload_${it.sessionId}")
                    assertTrue(retry.isEnabled)
                    retry.performClick()
                }
                assertNull(taggedOrNull<View>(activity, "retry_upload_pending-d"))
            }
            assertEquals(history.filter { it.transferStatus == "failed" }.map { it.sessionId }, fixture.uploadRetries)
        }
    }

    @Test fun historyKeepsTheSessionTimezoneWhenThePhoneTimezoneChanges() = withFlow { _ ->
        val originalDefault = TimeZone.getDefault()
        try {
            val start = Instant.parse("2026-09-18T16:05:00Z").toEpochMilli()
            val fixture = RenderingFlow(CollectionFlowState(isSimulation = false, uploadAvailable = false,
                hasProfile = true, participantId = "view001", placement = RingPlacement.LEFT_INDEX,
                connected = true, canStart = true, records = listOf(FlowRecordSummary(
                    "timezone-history", 12, "valid", "complete", true,
                    activity = SessionActivity.WALKING, startedAtMs = start, timeZoneId = "Asia/Shanghai"))))
            launch().use { scenario ->
                TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
                renderFixture(scenario, fixture)
                openRecords(scenario)
                scenario.onActivity {
                    assertEquals("2026-09-19 00:05 · 走路",
                        tagged<TextView>(it, "record_time_timezone-history").text.toString())
                }
                TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
                renderFixture(scenario, fixture)
                scenario.onActivity {
                    assertEquals("2026-09-19 00:05 · 走路",
                        tagged<TextView>(it, "record_time_timezone-history").text.toString())
                }
            }
        } finally {
            TimeZone.setDefault(originalDefault)
        }
    }

    @Test fun historyUsesPhoneStartWhenTheSampleBoundaryIsUnknown() = withFlow { _ ->
        val phoneStart = Instant.parse("2026-09-19T01:06:00Z").toEpochMilli()
        val sampleStart = Instant.parse("2026-09-19T01:08:00Z").toEpochMilli()
        val fixture = RenderingFlow(CollectionFlowState(isSimulation = false, uploadAvailable = false,
            hasProfile = true, participantId = "view001", placement = RingPlacement.LEFT_INDEX,
            connected = true, canStart = true, records = listOf(
                FlowRecordSummary("phone-time", 12, "valid", "complete", true,
                    activity = SessionActivity.WALKING, startedAtMs = null,
                    timeZoneId = "Asia/Shanghai", phoneStartAtMs = phoneStart),
                FlowRecordSummary("sample-time", 18, "valid", "complete", true,
                    activity = SessionActivity.RUNNING, startedAtMs = sampleStart,
                    timeZoneId = "Asia/Shanghai", phoneStartAtMs = phoneStart),
            )))

        launch().use { scenario ->
            renderFixture(scenario, fixture)
            openRecords(scenario)
            scenario.onActivity {
                assertEquals("2026-09-19 09:06 · 走路",
                    tagged<TextView>(it, "record_time_phone-time").text.toString())
                assertEquals("2026-09-19 09:08 · 跑步",
                    tagged<TextView>(it, "record_time_sample-time").text.toString())
            }
        }
    }

    @Test fun realUploadProgressAndRetryStayInsideTheSavedRecordWithoutReplacingThePrimaryAction() = withFlow { handle ->
        launch().use { scenario ->
            register(scenario)
            val preparation = requireNotNull(PreparationStore(File(handle.directory, "profile")).read())
            val session = renderingSession(preparation).copy(
                reference = SessionReference(ReferenceStatus.VALID, 17, 4_000),
                localData = SessionLocalData(listOf(SessionRawFile("view.rfbin", 1, 1, "0".repeat(64))), 5_000),
                transfer = SessionTransfer(SessionTransferStatus.TRANSFERRING, 1))
            val fixture = RenderingFlow(CollectionFlowState(page = CollectionPage.COMPLETE, isSimulation = false,
                uploadAvailable = true, hasProfile = true, participantId = preparation.participantId,
                placement = preparation.placement, session = session, connected = true, canStart = true,
                selectedActivity = SessionActivity.WALKING,
                records = listOf(FlowRecordSummary(session.sessionId, 17, "valid", "transferring", true, true))))
            renderFixture(scenario, fixture)
            scenario.onActivity { activity ->
                assertEquals("开始采集", tagged<Button>(activity, "flow_primary").text.toString())
                assertNull(taggedOrNull<View>(activity, "flow_error"))
            }
            openRecords(scenario)
            scenario.onActivity { activity ->
                assertEquals("正在上传", tagged<TextView>(activity, "record_status_${session.sessionId}").text.toString())
                assertNull(taggedOrNull<View>(activity, "retry_upload_${session.sessionId}"))
            }
            fixture.state = fixture.state.copy(session = session.copy(
                transfer = SessionTransfer(SessionTransferStatus.FAILED, 1)),
                records = listOf(FlowRecordSummary(session.sessionId, 17, "valid", "failed", true)))
            renderFixture(scenario, fixture)
            scenario.onActivity { activity ->
                assertEquals("上传失败，数据已保存在手机",
                    tagged<TextView>(activity, "record_status_${session.sessionId}").text.toString())
                assertEquals("重试上传", tagged<Button>(activity, "retry_upload_${session.sessionId}").text.toString())
            }
            click(scenario, "retry_upload_${session.sessionId}")
            assertEquals(listOf(session.sessionId), fixture.uploadRetries)
            fixture.state = fixture.state.copy(page = CollectionPage.HOME)
            renderFixture(scenario, fixture)
            click(scenario, "flow_back")
            scenario.onActivity { activity ->
                assertEquals("开始采集", tagged<Button>(activity, "flow_primary").text.toString())
                assertNull(taggedOrNull<View>(activity, "flow_error"))
            }
            openRecords(scenario)
            click(scenario, "retry_upload_${session.sessionId}")
            assertEquals(listOf(session.sessionId, session.sessionId), fixture.uploadRetries)
            fixture.state = fixture.state.copy(page = CollectionPage.COMPLETE,
                session = session.copy(transfer = SessionTransfer(SessionTransferStatus.COMPLETE, 1,
                    SessionTransferReceipt("view-receipt", 6_000, false, session.sessionId))),
                records = listOf(FlowRecordSummary(session.sessionId, 17, "valid", "complete", true)))
            renderFixture(scenario, fixture)
            scenario.onActivity { activity ->
                assertEquals("已上传", tagged<TextView>(activity, "record_status_${session.sessionId}").text.toString())
                assertNull(taggedOrNull<View>(activity, "retry_upload_${session.sessionId}"))
            }
        }
    }

    @Test fun pendingImmediateUploadCanBeQueuedAgainFromItsSavedRecord() = withFlow { handle ->
        launch().use { scenario ->
            register(scenario)
            val preparation = requireNotNull(PreparationStore(File(handle.directory, "profile")).read())
            val session = renderingSession(preparation).copy(
                reference = SessionReference(ReferenceStatus.VALID, 17, 4_000),
                localData = SessionLocalData(listOf(SessionRawFile("view.rfbin", 1, 1, "0".repeat(64))), 5_000),
                completionPolicy = CompletionPolicy.SAVE_UPLOAD)
            val fixture = RenderingFlow(CollectionFlowState(page = CollectionPage.HOME, isSimulation = false,
                uploadAvailable = true, hasProfile = true, participantId = preparation.participantId,
                placement = preparation.placement, session = session, connected = true, canStart = true,
                selectedActivity = SessionActivity.WALKING,
                records = listOf(FlowRecordSummary(session.sessionId, 17, "valid", "pending", true,
                    uploadRequeueAvailable = true))))
            renderFixture(scenario, fixture)
            openRecords(scenario)
            scenario.onActivity { activity ->
                assertEquals("已保存，等待上传",
                    tagged<TextView>(activity, "record_status_${session.sessionId}").text.toString())
                assertEquals("重试上传",
                    tagged<Button>(activity, "retry_upload_${session.sessionId}").text.toString())
            }
            click(scenario, "retry_upload_${session.sessionId}")
            assertEquals(listOf(session.sessionId), fixture.uploadRetries)
        }
    }

    @Test fun claimedUploadInterruptedBeforePayloadCanBeQueuedAgainFromItsSavedRecord() = withFlow { handle ->
        launch().use { scenario ->
            register(scenario)
            val preparation = requireNotNull(PreparationStore(File(handle.directory, "profile")).read())
            val session = renderingSession(preparation).copy(
                reference = SessionReference(ReferenceStatus.VALID, 17, 4_000),
                localData = SessionLocalData(listOf(SessionRawFile("view.rfbin", 1, 1, "0".repeat(64))), 5_000),
                completionPolicy = CompletionPolicy.SAVE_UPLOAD,
                transfer = SessionTransfer(SessionTransferStatus.TRANSFERRING, 1))
            val fixture = RenderingFlow(CollectionFlowState(page = CollectionPage.HOME, isSimulation = false,
                uploadAvailable = true, hasProfile = true, participantId = preparation.participantId,
                placement = preparation.placement, session = session, connected = true, canStart = true,
                selectedActivity = SessionActivity.WALKING,
                records = listOf(FlowRecordSummary(session.sessionId, 17, "valid", "transferring", true,
                    uploadRequeueAvailable = true))))
            renderFixture(scenario, fixture)
            openRecords(scenario)
            scenario.onActivity { activity ->
                assertEquals("已保存，等待上传",
                    tagged<TextView>(activity, "record_status_${session.sessionId}").text.toString())
                assertEquals("重试上传",
                    tagged<Button>(activity, "retry_upload_${session.sessionId}").text.toString())
            }
            click(scenario, "retry_upload_${session.sessionId}")
            assertEquals(listOf(session.sessionId), fixture.uploadRetries)
        }
    }

    @Test fun savedFinishReferenceContinuesWithoutReplacingItsValueOrUploadChoice() = withFlow { handle ->
        launch().use { scenario ->
            register(scenario)
            val preparation = requireNotNull(PreparationStore(File(handle.directory, "profile")).read())
            for (policy in listOf(CompletionPolicy.SAVE_UPLOAD, CompletionPolicy.SAVE_LATER)) {
                for (available in listOf(true, false)) {
                    val session = renderingSession(preparation).copy(
                        reference = SessionReference(ReferenceStatus.VALID, 0, 4_000), completionPolicy = policy)
                    val fixture = RenderingFlow(CollectionFlowState(page = CollectionPage.FINISH,
                        isSimulation = false, uploadAvailable = available, hasProfile = true,
                        participantId = preparation.participantId, placement = preparation.placement, session = session))
                    renderFixture(scenario, fixture)
                    scenario.onActivity { activity ->
                        assertNull(taggedOrNull<EditText>(activity, "flow_steps"))
                    }
                    click(scenario, "flow_primary")
                    assertEquals(1, fixture.retries)
                    assertNull(fixture.finalizedReference)
                    assertNull(fixture.savedReference)
                    assertEquals(session, fixture.state.session)
                }
            }
        }
    }

    @Test fun pendingRecordInformationKeepsReferenceDraftOnTheSamePage() = withFlow { handle ->
        launch().use { scenario ->
            register(scenario)
            val preparation = requireNotNull(PreparationStore(File(handle.directory, "profile")).read())
            val fixture = RenderingFlow(CollectionFlowState(page = CollectionPage.FINISH, isSimulation = false,
                uploadAvailable = true, hasProfile = true, participantId = preparation.participantId,
                placement = preparation.placement, ringName = "Ringo test", session = renderingSession(preparation)))
            renderFixture(scenario, fixture)
            type(scenario, "flow_steps", "123")
            scenario.onActivity { activity ->
                assertEquals("信息", tagged<Button>(activity, "flow_settings").text.toString())
                tagged<Button>(activity, "flow_settings").performClick()
                val dialog = StepCollectionActivity::class.java.getDeclaredField("dialog")
                    .apply { isAccessible = true }.get(activity) as AlertDialog
                assertTrue(dialog.isShowing)
                val message = dialog.findViewById<TextView>(android.R.id.message).text.toString()
                assertTrue(message.contains(preparation.participantId))
                assertTrue(message.contains("当前记录完成前，设置暂不可修改"))
                assertTrue(message.contains("应用版本：${BuildConfig.VERSION_NAME}"))
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                assertFalse(activity.isFinishing)
                assertEquals("123", tagged<EditText>(activity, "flow_steps").text.toString())
            }
            click(scenario, "flow_primary")
            assertEquals("123", fixture.finalizedReference!!.second.first)
        }
    }

    @Test fun localFileProblemShowsReviewAndPreservesTheSavedReference() = withFlow { handle ->
        launch().use { scenario ->
            register(scenario)
            val preparation = requireNotNull(PreparationStore(File(handle.directory, "profile")).read())
            val session = renderingSession(preparation).copy(
                reference = SessionReference(ReferenceStatus.VALID, 17, 4_000),
                localData = SessionLocalData(listOf(SessionRawFile("view.rfbin", 1, 1, "0".repeat(64))), 5_000))
            val fixture = RenderingFlow(CollectionFlowState(page = CollectionPage.COMPLETE, isSimulation = false,
                uploadAvailable = true, hasProfile = true, participantId = preparation.participantId,
                placement = preparation.placement, session = session,
                records = listOf(FlowRecordSummary(session.sessionId, 17, "valid", "pending", true,
                    localReviewRequired = true))))
            renderFixture(scenario, fixture)
            openRecords(scenario)
            scenario.onActivity { activity ->
                assertEquals("17 步", tagged<TextView>(activity, "record_steps_${session.sessionId}").text.toString())
                assertEquals("上传记录需要研究者核对", tagged<TextView>(activity, "record_status_${session.sessionId}").text.toString())
                assertNull(taggedOrNull<Button>(activity, "retry_upload_${session.sessionId}"))
            }
            assertTrue(fixture.uploadRetries.isEmpty())
            fixture.state = fixture.state.copy(page = CollectionPage.HOME)
            renderFixture(scenario, fixture)
            scenario.onActivity { activity ->
                assertEquals("上传记录需要研究者核对", tagged<TextView>(activity, "record_status_${session.sessionId}").text.toString())
                assertNull(taggedOrNull<Button>(activity, "retry_upload_${session.sessionId}"))
            }
            assertTrue(fixture.uploadRetries.isEmpty())
        }
    }

    @Test fun realConnectionPagesHideDevelopmentControlsAndPreventDuplicateConnect() = withFlow { _ ->
        launch().use { scenario ->
            val fixture = RenderingFlow(CollectionFlowState(isSimulation = false, uploadAvailable = false,
                hasProfile = true, participantId = "view001", placement = RingPlacement.LEFT_INDEX,
                connected = false, connecting = true))
            renderFixture(scenario, fixture)
            scenario.onActivity { activity ->
                assertNull(taggedOrNull<View>(activity, "demo_marker"))
                assertNull(taggedOrNull<View>(activity, "flow_options"))
                assertNull(taggedOrNull<View>(activity, "home_manage"))
                assertTrue(tagged<Button>(activity, "flow_settings").isEnabled)
                assertNull(taggedOrNull<View>(activity, "flow_back"))
                assertEquals("正在连接", tagged<TextView>(activity, "home_device_status").text.toString())
                assertNull(taggedOrNull<View>(activity, "home_task_status"))
                assertNull(taggedOrNull<Button>(activity, "flow_primary"))
                assertNull(taggedOrNull<Button>(activity, "home_task_action"))
            }
            fixture.state = fixture.state.copy(connecting = false, canRetry = true)
            renderFixture(scenario, fixture)
            scenario.onActivity { activity ->
                assertEquals("未连接", tagged<TextView>(activity, "home_device_status").text.toString())
                assertEquals("重新连接", tagged<Button>(activity, "home_reconnect").text.toString())
                assertNull("The disconnected home has one reconnect action",
                    taggedOrNull<Button>(activity, "home_task_action"))
            }
            click(scenario, "home_reconnect")
            assertEquals(1, fixture.reconnects)
            fixture.state = fixture.state.copy(page = CollectionPage.RECOVERY, canRetry = true)
            renderFixture(scenario, fixture)
            scenario.onActivity { activity ->
                assertEquals("正在恢复", tagged<TextView>(activity, "flow_heading").text.toString())
                assertNull(taggedOrNull<View>(activity, "flow_detail_本次记录"))
                assertEquals("重新连接", tagged<Button>(activity, "flow_primary").text.toString())
            }
            click(scenario, "flow_primary")
            assertEquals(2, fixture.reconnects)
            assertEquals(0, fixture.retries)
            fixture.state = fixture.state.copy(connected = true, canStart = true,
                page = CollectionPage.HOME, selectedActivity = SessionActivity.WALKING,
                records = listOf(FlowRecordSummary("view-session", 0, "valid", "pending", true)))
            renderFixture(scenario, fixture)
            scenario.onActivity { activity ->
                assertEquals("可以开始", tagged<TextView>(activity, "home_device_status").text.toString())
                assertEquals("开始采集", tagged<Button>(activity, "flow_primary").text.toString())
                assertNull(taggedOrNull<View>(activity, "retry_upload_view-session"))
            }
            fixture.state = fixture.state.copy(canStart = false, checkingDevice = true, busy = true)
            renderFixture(scenario, fixture)
            scenario.onActivity { activity ->
                assertEquals("正在检查", tagged<TextView>(activity, "home_device_status").text.toString())
                assertTrue(tagged<Button>(activity, "flow_settings").isEnabled)
                assertNull(taggedOrNull<View>(activity, "home_manage"))
            }
        }
    }

    @Test fun finishWithoutUploadConfigurationOffersLocalSaveAndKeepsAnExistingChoice() = withFlow { handle ->
        launch().use { scenario ->
            register(scenario)
            val preparation = requireNotNull(PreparationStore(File(handle.directory, "profile")).read())
            val stopped = renderingSession(preparation)
            val fixture = RenderingFlow(CollectionFlowState(page = CollectionPage.FINISH,
                isSimulation = false, uploadAvailable = false, hasProfile = true,
                participantId = preparation.participantId, placement = preparation.placement,
                session = stopped))
            renderFixture(scenario, fixture)
            scenario.onActivity { activity ->
                assertEquals("保存到手机", tagged<Button>(activity, "flow_primary").text.toString())
                assertNull(taggedOrNull<View>(activity, "finish_defer"))
            }
            type(scenario, "flow_steps", "0")
            click(scenario, "flow_primary")
            assertEquals(true to Triple("0", "valid", ""), fixture.finalizedReference)

            fixture.finalizedReference = null
            fixture.state = fixture.state.copy(session = stopped.copy(completionPolicy = CompletionPolicy.SAVE_UPLOAD))
            renderFixture(scenario, fixture)
            scenario.onActivity { activity ->
                assertEquals("保存到手机", tagged<Button>(activity, "flow_primary").text.toString())
                assertNull(taggedOrNull<View>(activity, "finish_defer"))
            }
            click(scenario, "flow_primary")
            assertEquals(true to Triple("0", "valid", ""), fixture.finalizedReference)
        }
    }

    @Test fun realReferenceActionSavesTheValueWithoutOfferingAnUnavailableUpload() = withFlow { handle ->
        launch().use { scenario ->
            register(scenario)
            val preparation = requireNotNull(PreparationStore(File(handle.directory, "profile")).read())
            val session = renderingSession(preparation)
            val fixture = RenderingFlow(CollectionFlowState(page = CollectionPage.REFERENCE, isSimulation = false,
                uploadAvailable = false, hasProfile = true, participantId = preparation.participantId,
                placement = preparation.placement, session = session))
            renderFixture(scenario, fixture)
            scenario.onActivity { activity ->
                assertEquals("保存到手机", tagged<Button>(activity, "flow_primary").text.toString())
                assertNull(taggedOrNull<View>(activity, "flow_options"))
            }
            type(scenario, "flow_steps", "0")
            click(scenario, "flow_primary")
            assertEquals(true to Triple("0", "valid", ""), fixture.finalizedReference)
        }
    }

    @Test fun realResultRequiresLocalEvidenceAndDoesNotTreatPendingOrSimulatedUploadAsComplete() = withFlow { handle ->
        launch().use { scenario ->
            register(scenario)
            val preparation = requireNotNull(PreparationStore(File(handle.directory, "profile")).read())
            val session = renderingSession(preparation)
            val fixture = RenderingFlow(CollectionFlowState(page = CollectionPage.COMPLETE, isSimulation = false,
                uploadAvailable = false, hasProfile = true, participantId = preparation.participantId,
                placement = preparation.placement, session = session, savedSteps = 562))
            renderFixture(scenario, fixture)
            scenario.onActivity { activity ->
                assertNull(taggedOrNull<View>(activity, "home_records_summary"))
                assertTrue(visibleTexts(activity.window.decorView).none { it.contains("已上传") })
            }
            // These are UI-only evidence fixtures. No BLE, real raw file or cloud request is made.
            val local = session.copy(reference = SessionReference(ReferenceStatus.VALID, 0, 4_000),
                localData = SessionLocalData(listOf(SessionRawFile("view.rfbin", 1, 1, "0".repeat(64))), 5_000))
            val pendingRecord = FlowRecordSummary(local.sessionId, 0, "valid", "pending", true)
            fixture.state = fixture.state.copy(session = local, records = listOf(pendingRecord))
            renderFixture(scenario, fixture)
            openRecords(scenario)
            scenario.onActivity { activity ->
                assertEquals("0 步", tagged<TextView>(activity, "record_steps_${local.sessionId}").text.toString())
                assertEquals("已保存在手机",
                    tagged<TextView>(activity, "record_status_${local.sessionId}").text.toString())
            }
            fixture.state = fixture.state.copy(session = local.copy(transfer = SessionTransfer(
                SessionTransferStatus.COMPLETE, 1, SessionTransferReceipt("view-receipt", 6_000, true, local.sessionId))),
                records = listOf(pendingRecord))
            renderFixture(scenario, fixture)
            scenario.onActivity { activity ->
                assertEquals("A simulated receipt cannot confirm a real upload", "已保存在手机",
                    tagged<TextView>(activity, "record_status_${local.sessionId}").text.toString())
            }
        }
    }

    @Test fun complete562PathSavesRealFilesAndReceiptThenReopensTheSameResult() = withFlow { handle ->
        launch().use { scenario ->
            awaitHeading(scenario, "准备开始")
            captureReviewScreen(scenario, "registration")
            register(scenario)
            captureReviewScreen(scenario, "home")
            val id = reachReference(scenario, handle, capture = true)
            type(scenario, "flow_steps", "562")
            click(scenario, "flow_primary")
            awaitHeading(scenario, "这一段已保存")
            awaitFlow(handle, "simulated upload receipt") {
                session(handle).transfer.status == SessionTransferStatus.COMPLETE &&
                    it.records.singleOrNull { record -> record.sessionId == id }?.transferStatus == "complete"
            }
            captureReviewScreen(scenario, "complete")
            openRecords(scenario)
            await(scenario, "receipt reflected in record") {
                taggedOrNull<TextView>(it, "record_status_$id")?.text?.toString() == "模拟上传完成"
            }
            scenario.onActivity { activity ->
                assertEquals("562 步", tagged<TextView>(activity, "record_steps_$id").text.toString())
                assertEquals("模拟上传完成", tagged<TextView>(activity, "record_status_$id").text.toString())
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
            awaitHeading(scenario, "步数采集")
            assertEquals(saved, session(handle))
            openRecords(scenario)
            scenario.onActivity {
                assertNull(taggedOrNull<Button>(it, "flow_primary"))
                assertEquals("562 步", tagged<TextView>(it, "record_steps_$id").text.toString())
            }
            captureReviewScreen(scenario, "history")
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
            awaitHeading(scenario, "步数采集")
            val saved = session(handle)
            val reference = requireNotNull(saved.reference)
            assertEquals(0L, reference.steps)
            assertEquals(ReferenceStatus.VALID, reference.status)
            assertNotNull(reference.groundTruthRecordedAtMs)
            openRecords(scenario)
            scenario.onActivity { assertEquals("0 步", tagged<TextView>(it, "record_steps_${saved.sessionId}").text.toString()) }
        }
    }

    @Test fun historicalMissingAndUnreliableReferencesRenderAndRetryWithoutBeingRewritten() = withFlow { handle ->
        launch().use { scenario ->
            register(scenario)
            val store = FreeLivingSessionStore(File(handle.directory, "session.json"))
            val seeded = listOf(
                Triple(ReferenceStatus.MISSING, null, "计步器意外清零"),
                Triple(ReferenceStatus.UNRELIABLE, 562L, "忘记清零"),
            ).map { (status, steps, reason) ->
                val id = reachFinish(scenario, handle, SessionActivity.WALKING)
                store.setCompletionPolicy(id, CompletionPolicy.SAVE_LATER)
                handle.flow.finalizeSession(false, steps?.toString().orEmpty(), status.wireValue, reason)
                awaitFlow(handle, "historical ${status.wireValue} reference is locally complete") { state ->
                    state.records.singleOrNull { it.sessionId == id }?.localComplete == true
                }
                awaitHeading(scenario, "这一段已保存")
                val saved = requireNotNull(store.read(id))
                assertEquals(status, saved.reference?.status)
                assertEquals(steps, saved.reference?.steps)
                assertEquals(reason, saved.reference?.reason)
                id to requireNotNull(saved.reference)
            }

            openRecords(scenario)
            scenario.onActivity { activity ->
                assertEquals("未提供读数", tagged<TextView>(activity, "record_steps_${seeded[0].first}").text.toString())
                assertEquals("562 步", tagged<TextView>(activity, "record_steps_${seeded[1].first}").text.toString())
            }
            for ((id, reference) in seeded) {
                click(scenario, "retry_upload_$id")
                awaitFlow(handle, "historical reference upload retry completes") { state ->
                    state.records.singleOrNull { it.sessionId == id }?.transferStatus == "complete"
                }
                assertEquals(reference, store.read(id)?.reference)
            }
        }
    }

    @Test fun bothEmptySaveActionsExplainTheMissingInputAndZeroStillSaves() {
        for (saveTag in listOf("flow_primary", "finish_defer")) withFlow { handle ->
            launch().use { scenario ->
                register(scenario)
                val id = reachReference(scenario, handle)
                val before = session(handle)
                scenario.onActivity { activity ->
                    val buttons = listOf("flow_primary", "finish_defer", "finish_discard").map {
                        tagged<Button>(activity, it)
                    }
                    assertTrue(buttons.all { it.parent === buttons.first().parent })
                    assertEquals(buttons[0].left, buttons[1].left)
                    assertEquals(buttons[0].width, buttons[1].width)
                    assertEquals(buttons[0].width, buttons[2].width)
                    assertTrue(buttons[0].bottom <= buttons[1].top && buttons[1].bottom <= buttons[2].top)
                    assertTrue(buttons.take(2).all { it.lineCount == 1 })
                }
                click(scenario, saveTag)
                await(scenario, "empty steps explain what to fill") {
                    tagged<TextView>(it, "finish_input_error").text.toString() == "请先填写计步器总步数" &&
                        tagged<EditText>(it, "flow_steps").hasFocus() &&
                        it.window.decorView.rootWindowInsets.isVisible(android.view.WindowInsets.Type.ime())
                }
                assertEquals(before, session(handle))
                scenario.onActivity { activity ->
                    val field = tagged<EditText>(activity, "flow_steps")
                    val error = tagged<TextView>(activity, "finish_input_error")
                    val rect = android.graphics.Rect()
                    assertTrue(field.getGlobalVisibleRect(rect))
                    assertEquals(field.height, rect.height())
                    assertTrue(error.getGlobalVisibleRect(rect))
                    assertEquals(error.height, rect.height())
                }
                captureReviewScreen(scenario, "finish_empty_steps")
                type(scenario, "flow_steps", "0")
                scenario.onActivity { activity ->
                    assertEquals(View.GONE, tagged<TextView>(activity, "finish_input_error").visibility)
                    activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                        .hideSoftInputFromWindow(tagged<EditText>(activity, "flow_steps").windowToken, 0)
                }
                await(scenario, "keyboard hidden before choosing save") {
                    !it.window.decorView.rootWindowInsets.isVisible(android.view.WindowInsets.Type.ime())
                }
                scenario.onActivity { activity ->
                    val button = tagged<Button>(activity, "finish_discard")
                    button.requestRectangleOnScreen(android.graphics.Rect(0, 0, button.width, button.height), true)
                    for (tag in listOf("flow_primary", "finish_defer", "finish_discard")) {
                        val action = tagged<Button>(activity, tag)
                        val rect = android.graphics.Rect()
                        assertTrue(action.getGlobalVisibleRect(rect))
                        assertEquals(action.height, rect.height())
                    }
                }
                captureReviewScreen(scenario, "finish_aligned_actions")
                click(scenario, saveTag)
                if (saveTag == "finish_defer") {
                    awaitFlow(handle, "ring defer saves zero") { it.session?.isRingDeferred == true }
                    awaitHeading(scenario, "步数采集")
                    assertNull(session(handle).localData)
                } else awaitHeading(scenario, "这一段已保存")
                assertEquals(id, session(handle).sessionId)
                assertEquals(0L, session(handle).reference!!.steps)
                assertEquals(if (saveTag == "finish_defer") CompletionPolicy.DEFER_ON_RING else CompletionPolicy.SAVE_UPLOAD,
                    session(handle).completionPolicy)
            }
        }
    }

    @Test fun referenceCorrectionIsNumericAndPreservesOnlyHistoricalUnreliableMeaning() = withFlow { handle ->
        launch().use { scenario ->
            register(scenario)
            val preparation = requireNotNull(PreparationStore(File(handle.directory, "profile")).read())
            val cases = listOf(
                FlowRecordSummary("edit-valid", 12, "valid", "pending", true,
                    referenceEditable = true),
                FlowRecordSummary("edit-missing", null, "missing", "pending", true,
                    referenceEditable = true, referenceReason = "计步器意外清零"),
                FlowRecordSummary("edit-unreliable", 18, "unreliable", "pending", true,
                    referenceEditable = true, referenceReason = "忘记清零"),
            )
            val fixture = RenderingFlow(CollectionFlowState(isSimulation = false, hasProfile = true,
                participantId = preparation.participantId, placement = preparation.placement,
                connected = true, canStart = true, records = cases))
            renderFixture(scenario, fixture)
            openRecords(scenario)

            for ((index, record) in cases.withIndex()) {
                click(scenario, "edit_reference_${record.sessionId}")
                scenario.onActivity { activity ->
                    val editor = startAttemptDialog(activity)
                    assertTrue(editor.isShowing)
                    assertNull(editor.listView)
                    val input = editor.window!!.decorView.findViewWithTag<EditText>("edit_reference_steps")
                    assertNotNull(input)
                    input!!.setText((40 + index).toString())
                    editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                }
                val revision = fixture.referenceRevisions.last()
                assertEquals(record.sessionId, revision.first)
                assertEquals((40 + index).toString(), revision.second)
                if (record.referenceStatus == "unreliable") {
                    assertEquals("unreliable", revision.third.first)
                    assertEquals("忘记清零", revision.third.second)
                } else {
                    assertEquals("valid", revision.third.first)
                    assertEquals("", revision.third.second)
                }
                renderFixture(scenario, fixture)
            }
        }
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
            captureReviewScreen(scenario, "save_failure")
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
            awaitHeading(scenario, "步数采集")
            val id = reachReference(scenario, handle)
            type(scenario, "flow_steps", "83")
            scenario.recreate()
            awaitHeading(scenario, "结束本段")
            scenario.onActivity {
                assertEquals("83", tagged<EditText>(it, "flow_steps").text.toString())
                assertNull(taggedOrNull<View>(it, "flow_reason"))
                assertNull(taggedOrNull<View>(it, "reference_options"))
            }
            assertEquals(id, session(handle).sessionId)
            assertNull(session(handle).reference)
            click(scenario, "flow_primary")
            awaitHeading(scenario, "这一段已保存")
            assertEquals(ReferenceStatus.VALID, session(handle).reference!!.status)
            assertEquals(83L, session(handle).reference!!.steps)
        }
    }

    @Test fun leavingThePageAndReopeningRestoresAnUnfinishedTaskWithoutAnotherStart() = withFlow { handle ->
        lateinit var id: String
        launch().use { scenario ->
            register(scenario)
            startWalking(scenario)
            awaitHeading(scenario, "正在采集")
            id = session(handle).sessionId
            scenario.moveToState(Lifecycle.State.CREATED)
            assertEquals(FreeLivingSessionPhase.COLLECTING, session(handle).phase)
            assertNull(session(handle).stopRequestedAtMs)
            scenario.moveToState(Lifecycle.State.RESUMED)
            awaitHeading(scenario, "正在采集")
            click(scenario, "flow_primary")
            chooseSaveAfterStop(scenario)
            awaitHeading(scenario, "结束本段")
            click(scenario, "flow_back")
            awaitHeading(scenario, "步数采集")
            await(scenario, "unfinished stopped session is reachable from home") {
                taggedOrNull<Button>(it, "home_task_action")?.text?.toString() == "继续收尾"
            }
            assertNull(session(handle).completionPolicy)
            captureReviewScreen(scenario, "reference_pending_home")
        }
        launch().use { reopened ->
            awaitHeading(reopened, "步数采集")
            click(reopened, "home_task_action")
            awaitHeading(reopened, "结束本段")
            assertEquals(id, session(handle).sessionId)
            assertNull(session(handle).completionPolicy)
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
            awaitHeading(scenario, "这一段已保存")
            awaitFlow(handle, "upload reaches its failed terminal state") {
                session(handle).transfer.status == SessionTransferStatus.FAILED
            }
            assertEquals(SessionTransferStatus.FAILED, session(handle).transfer.status)
            captureReviewScreen(scenario, "upload_failure")
            awaitHeading(scenario, "步数采集")
            captureReviewScreen(scenario, "upload_retry_home")
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
                assertEquals("结束本段", tagged<TextView>(it, "flow_heading").text.toString())
            }
            val store = FreeLivingSessionStore(File(handle.directory, "session.json"))
            assertEquals(101L, store.read(first)!!.reference!!.steps)
            assertNull(store.read(second)!!.reference)
            assertEquals(second, store.read()!!.sessionId)
        }
    }

    @Test fun disconnectedCollectionDoesNotClaimLiveCaptureAndKeepsRecoveryActionInPlace() = withFlow { handle ->
        launch().use { scenario ->
            register(scenario)
            val preparation = requireNotNull(PreparationStore(File(handle.directory, "profile")).read())
            val collecting = renderingSession(preparation).copy(
                phase = FreeLivingSessionPhase.COLLECTING, stopRequestedAtMs = null, stopConfirmedAtMs = null)
            val fixture = RenderingFlow(CollectionFlowState(page = CollectionPage.RECOVERY,
                taskPage = CollectionPage.RECOVERY, isSimulation = false, hasProfile = true,
                participantId = preparation.participantId, placement = preparation.placement,
                session = collecting, connected = false, canRetry = true))
            renderFixture(scenario, fixture)
            lateinit var recoveryAction: Button
            lateinit var heading: TextView
            scenario.onActivity { activity ->
                heading = tagged(activity, "flow_heading")
                assertEquals("采集状态待确认", heading.text.toString())
                assertNull("An old start time must not keep a live capture timer running",
                    taggedOrNull<TextView>(activity, "flow_elapsed"))
                assertEquals("连接已中断", tagged<TextView>(activity, "flow_detail_戒指").text.toString())
                recoveryAction = tagged(activity, "flow_primary")
                assertEquals("重新连接", recoveryAction.text.toString())
                assertTrue(recoveryAction.isEnabled)
            }
            repeat(3) {
                fixture.state = fixture.state.copy(connecting = true, busy = true, canRetry = false,
                    error = "正在连接戒指")
                renderFixture(scenario, fixture)
                scenario.onActivity { activity ->
                    assertSame("A transport retry must retain the visible recovery action", recoveryAction,
                        tagged<Button>(activity, "flow_primary"))
                    assertSame(heading, tagged<TextView>(activity, "flow_heading"))
                    assertEquals("连接中…", recoveryAction.text.toString())
                    assertFalse(recoveryAction.isEnabled)
                    assertNull(taggedOrNull<TextView>(activity, "flow_elapsed"))
                }
                fixture.state = fixture.state.copy(connecting = false, busy = false, canRetry = true,
                    error = "连接超时，请唤醒戒指后重试")
                renderFixture(scenario, fixture)
                scenario.onActivity { activity ->
                    assertSame(recoveryAction, tagged<Button>(activity, "flow_primary"))
                    assertEquals("重新连接", recoveryAction.text.toString())
                    assertTrue(recoveryAction.isEnabled)
                }
            }
            captureReviewScreen(scenario, "real_collecting_disconnected")
            click(scenario, "flow_primary")
            assertEquals(1, fixture.reconnects)

            // GATT connected alone is insufficient: wait for the current STATUS to confirm.
            fixture.state = fixture.state.copy(connected = true, busy = true, canRetry = false, error = null)
            renderFixture(scenario, fixture)
            scenario.onActivity { activity ->
                assertEquals("采集状态待确认", tagged<TextView>(activity, "flow_heading").text.toString())
                assertNull(taggedOrNull<TextView>(activity, "flow_elapsed"))
                assertNull(taggedOrNull<Button>(activity, "flow_primary"))
            }
            fixture.state = fixture.state.copy(busy = false, canRetry = true,
                error = "暂未收到确认，请重新检查戒指")
            renderFixture(scenario, fixture)
            scenario.onActivity { activity ->
                assertEquals("重新检查", tagged<Button>(activity, "flow_primary").text.toString())
                assertTrue(tagged<Button>(activity, "flow_primary").isEnabled)
            }
            click(scenario, "flow_primary")
            assertEquals(1, fixture.retries)
            fixture.state = fixture.state.copy(page = CollectionPage.COLLECTING, taskPage = CollectionPage.COLLECTING,
                canStop = true, busy = false, error = null)
            renderFixture(scenario, fixture)
            scenario.onActivity { activity ->
                assertEquals("正在采集", tagged<TextView>(activity, "flow_heading").text.toString())
                assertNotNull(taggedOrNull<TextView>(activity, "flow_elapsed"))
                assertEquals("结束采集", tagged<Button>(activity, "flow_primary").text.toString())
            }
            assertEquals(collecting, fixture.state.session)

            fixture.state = fixture.state.copy(page = CollectionPage.RECOVERY, taskPage = CollectionPage.RECOVERY,
                session = collecting.copy(phase = FreeLivingSessionPhase.START_REQUESTED, startConfirmedAtMs = null),
                connected = false, connecting = true, canStop = false, busy = true, canRetry = false)
            renderFixture(scenario, fixture)
            scenario.onActivity { activity ->
                assertEquals("开始状态待确认", tagged<TextView>(activity, "flow_heading").text.toString())
                assertNull(taggedOrNull<TextView>(activity, "flow_elapsed"))
                assertEquals("连接中…", tagged<Button>(activity, "flow_primary").text.toString())
            }
        }
    }

    @Test fun disconnectedCollectionHomeKeepsOneStableRecoveryAction() = withFlow { handle ->
        launch().use { scenario ->
            register(scenario)
            val preparation = requireNotNull(PreparationStore(File(handle.directory, "profile")).read())
            val collecting = renderingSession(preparation).copy(
                phase = FreeLivingSessionPhase.COLLECTING, stopRequestedAtMs = null, stopConfirmedAtMs = null)
            val fixture = RenderingFlow(CollectionFlowState(page = CollectionPage.HOME,
                taskPage = CollectionPage.RECOVERY, isSimulation = false, hasProfile = true,
                participantId = preparation.participantId, placement = preparation.placement,
                session = collecting, connected = false, canRetry = true))
            renderFixture(scenario, fixture)
            lateinit var action: Button
            scenario.onActivity { activity ->
                action = tagged(activity, "home_reconnect")
                assertEquals("连接已中断", tagged<TextView>(activity, "home_task_status").text.toString())
                assertEquals("连接已中断", tagged<TextView>(activity, "home_device_status").text.toString())
                assertNull(taggedOrNull<Button>(activity, "home_task_action"))
            }
            fixture.state = fixture.state.copy(connecting = true, busy = true, canRetry = false)
            renderFixture(scenario, fixture)
            scenario.onActivity { activity ->
                assertSame(action, tagged<Button>(activity, "home_reconnect"))
                assertEquals("连接中…", action.text.toString())
                assertFalse(action.isEnabled)
                assertEquals("连接已中断", tagged<TextView>(activity, "home_task_status").text.toString())
                assertNull(taggedOrNull<Button>(activity, "home_task_action"))
            }
            fixture.state = fixture.state.copy(connecting = false, busy = false, canRetry = true)
            renderFixture(scenario, fixture)
            scenario.onActivity { activity ->
                assertSame(action, tagged<Button>(activity, "home_reconnect"))
                assertTrue(action.isEnabled)
                assertEquals("重新连接", action.text.toString())
            }
        }
    }

    @Test fun disconnectAndReconnectKeepTheSameCollectingTaskAndRestoreItsStopAction() = withFlow { handle ->
        launch().use { scenario ->
            register(scenario)
            startWalking(scenario)
            awaitHeading(scenario, "正在采集")
            val original = session(handle)
            handle.flow.disconnect()
            awaitHeading(scenario, "采集状态待确认")
            captureReviewScreen(scenario, "disconnected")
            assertEquals(original, session(handle))
            click(scenario, "flow_back")
            awaitHeading(scenario, "步数采集")
            scenario.onActivity {
                assertEquals("连接已中断", tagged<TextView>(it, "home_task_status").text.toString())
                assertEquals("重新连接", tagged<Button>(it, "home_reconnect").text.toString())
                assertNull("The disconnected home has one reconnect action",
                    taggedOrNull<Button>(it, "home_task_action"))
            }
            captureReviewScreen(scenario, "reconnect_home")
            click(scenario, "home_reconnect")
            awaitHeading(scenario, "正在采集")
            scenario.onActivity { assertTrue(tagged<Button>(it, "flow_primary").isEnabled) }
            assertEquals(original.sessionId, session(handle).sessionId)
            click(scenario, "flow_primary")
            awaitHeading(scenario, "结束本段")
            assertEquals(original.sessionId, session(handle).sessionId)
        }
    }

    @Test fun collectingHomeShowsCurrentTaskAndReturnsWithoutStoppingOrCreatingAnotherSession() = withFlow { handle ->
        launch().use { scenario ->
            register(scenario)
            startWalking(scenario)
            awaitHeading(scenario, "正在采集")
            val original = session(handle)
            click(scenario, "flow_back")
            awaitHeading(scenario, "步数采集")
            scenario.onActivity {
                assertEquals("正在采集", tagged<TextView>(it, "home_task_status").text.toString())
                assertEquals("查看采集", tagged<Button>(it, "home_task_action").text.toString())
            }
            captureReviewScreen(scenario, "collecting_home")
            assertEquals(original, session(handle))
            assertNull(session(handle).stopRequestedAtMs)
            scenario.recreate()
            awaitHeading(scenario, "步数采集")
            click(scenario, "home_task_action")
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
            awaitHeading(scenario, "步数采集")
            scenario.onActivity { assertNull(taggedOrNull<Button>(it, "home_task_action")) }
            awaitFlow(handle, "download failure remains at home") {
                it.page in setOf(CollectionPage.HOME, CollectionPage.ERROR) && it.taskPage == CollectionPage.ERROR
            }
            awaitHeading(scenario, "步数采集")
            scenario.onActivity {
                assertEquals("步数已保存", tagged<TextView>(it, "home_task_status").text.toString())
                assertEquals("重试下载", tagged<Button>(it, "home_task_action").text.toString())
                assertTrue(tagged<Button>(it, "home_task_action").isEnabled)
            }
            captureReviewScreen(scenario, "download_retry_home")
            val saved = session(handle)
            assertEquals(id, saved.sessionId)
            assertEquals(562L, saved.reference!!.steps)
            assertNull(saved.localData)
            assertFalse(handle.flow.state.canStart)
            click(scenario, "home_task_action")
            awaitHeading(scenario, "这一段已保存")
            awaitFlow(handle, "retried download reaches upload receipt") {
                it.records.single { record -> record.sessionId == id }.transferStatus == "complete"
            }
            assertEquals(id, session(handle).sessionId)
            assertEquals(saved.reference, session(handle).reference)
            assertNotNull(session(handle).localData)
            assertEquals(SessionTransferStatus.COMPLETE, session(handle).transfer.status)
        }
    }

    @Test fun failedDownloadCanCancelDiscardOrConfirmItThenReopenHome() = withFlow { handle ->
        launch().use { scenario ->
            register(scenario)
            val id = reachReference(scenario, handle)
            handle.flow.setFault(FlowTestFault.DOWNLOAD_FAILURE)
            awaitFlow(handle, "download failure armed") { it.fault == FlowTestFault.DOWNLOAD_FAILURE }
            type(scenario, "flow_steps", "0")
            click(scenario, "flow_primary")
            awaitFlow(handle, "failed download is recoverable at home") { it.taskPage == CollectionPage.ERROR && !it.busy }
            await(scenario, "discard available after confirmed stop") { taggedOrNull<Button>(it, "recovery_discard") != null }
            val saved = session(handle)
            click(scenario, "recovery_discard")
            scenario.onActivity { startAttemptDialog(it).getButton(AlertDialog.BUTTON_NEGATIVE).performClick() }
            assertEquals(saved, session(handle))
            scenario.recreate()
            await(scenario, "recovery action survives recreation") { taggedOrNull<Button>(it, "recovery_discard") != null }
            click(scenario, "recovery_discard")
            captureReviewScreen(scenario, "download_discard_confirmation")
            scenario.onActivity { startAttemptDialog(it).getButton(AlertDialog.BUTTON_POSITIVE).performClick() }
            awaitFlow(handle, "discard releases original task") { it.canStart && !it.busy }
            val store = FreeLivingSessionStore(File(handle.directory, "session.json"))
            assertTrue(store.read(id)!!.isDiscarded)
            assertEquals(0L, store.read(id)!!.reference!!.steps)
            assertNull(store.readPending())
            assertTrue(handle.flow.state.records.none { it.sessionId == id })
            scenario.recreate()
            awaitHeading(scenario, "步数采集")
            scenario.onActivity { assertNull(taggedOrNull<Button>(it, "recovery_discard")) }
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
                scenario.onActivity { assertEquals("2026-09-19 00:05", tagged<TextView>(it, "flow_detail_开始时间").text.toString()) }
                TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
                scenario.recreate()
                awaitHeading(scenario, "正在采集")
                scenario.onActivity { assertEquals("2026-09-19 00:05", tagged<TextView>(it, "flow_detail_开始时间").text.toString()) }
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
            startWalking(scenario)
            awaitHeading(scenario, "正在采集")
            val id = session(handle).sessionId
            handle.flow.setFault(FlowTestFault.STOP_TIMEOUT)
            awaitFlow(handle, "stop timeout armed") { it.fault == FlowTestFault.STOP_TIMEOUT }
            click(scenario, "flow_primary")
            awaitFlow(handle, "stop remains unconfirmed") { it.page == CollectionPage.RECOVERY }
            awaitHeading(scenario, "结束本段")
            assertEquals(FreeLivingSessionPhase.STOP_REQUESTED, session(handle).phase)
            assertNull(session(handle).stopConfirmedAtMs)
            captureReviewScreen(scenario, "stop_unconfirmed")
            scenario.onActivity { activity ->
                assertEquals(View.GONE, tagged<View>(activity, "finish_actions").visibility)
                assertTrue(tagged<Button>(activity, "finish_stop_recovery").isShown)
            }
            type(scenario, "flow_steps", "0")
            awaitFlow(handle, "reference draft is durable without becoming research data") {
                it.referenceDraft == "0"
            }
            assertNull(session(handle).reference)
            click(scenario, "flow_back")
            awaitHeading(scenario, "步数采集")
            captureReviewScreen(scenario, "stop_unconfirmed_home")
            val beforeReturn = session(handle)
            assertNull(beforeReturn.stopConfirmedAtMs)
            assertFalse(handle.flow.state.canStart)
            click(scenario, "home_task_action")
            chooseSaveAfterStop(scenario)
            assertNotNull(session(handle).stopConfirmedAtMs)
            assertEquals(id, session(handle).sessionId)
            assertNull(session(handle).reference)
            assertNull(session(handle).completionPolicy)
            assertNull(session(handle).localData)
            assertEquals(0, session(handle).transfer.attempts)
            scenario.onActivity { activity ->
                assertEquals("0", tagged<EditText>(activity, "flow_steps").text.toString())
            }
            captureReviewScreen(scenario, "finish_after_recovery")
            click(scenario, "finish_defer")
            awaitFlow(handle, "recovered reference deferred on ring") { it.session?.isRingDeferred == true }
            awaitHeading(scenario, "步数采集")
            assertEquals(ReferenceStatus.VALID, session(handle).reference!!.status)
            assertEquals(0L, session(handle).reference!!.steps)
            assertEquals(CompletionPolicy.DEFER_ON_RING, session(handle).completionPolicy)
            assertNull(session(handle).localData)
            assertNull(session(handle).transfer.receipt)
        }
    }

    @Test fun unconfirmedStopCanReturnWithoutSavingAnEmptyReference() = withFlow { handle ->
        launch().use { scenario ->
            register(scenario)
            startWalking(scenario)
            awaitHeading(scenario, "正在采集")
            handle.flow.setFault(FlowTestFault.STOP_TIMEOUT)
            awaitFlow(handle, "stop timeout armed") { it.fault == FlowTestFault.STOP_TIMEOUT }
            click(scenario, "flow_primary")
            awaitFlow(handle, "stop remains unconfirmed") { it.page == CollectionPage.RECOVERY }
            awaitHeading(scenario, "结束本段")
            scenario.onActivity { activity ->
                assertEquals("", tagged<EditText>(activity, "flow_steps").text.toString())
                assertEquals(View.GONE, tagged<View>(activity, "finish_actions").visibility)
                assertTrue(tagged<Button>(activity, "finish_stop_recovery").isShown)
            }
            click(scenario, "finish_stop_recovery")
            chooseSaveAfterStop(scenario)
            assertNull(session(handle).reference)
            assertNotNull(session(handle).stopConfirmedAtMs)
            assertEquals(FreeLivingSessionPhase.AWAITING_REFERENCE, session(handle).phase)
            assertNull(session(handle).completionPolicy)
            assertNull(session(handle).localData)
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

    private fun renderingSession(preparation: PreparationSnapshot) = FreeLivingSession(
        "view-session", preparation, FreeLivingSessionPhase.AWAITING_REFERENCE, "Asia/Shanghai", 28_800,
        1_000, startConfirmedAtMs = 1_100, stopRequestedAtMs = 2_900, stopConfirmedAtMs = 3_000)

    private fun renderFixture(scenario: ActivityScenario<DemoCollectionActivity>, fixture: RenderingFlow) {
        scenario.onActivity { activity ->
            StepCollectionActivity::class.java.getDeclaredField("flow").apply { isAccessible = true }
                .set(activity, fixture)
            StepCollectionActivity::class.java.getDeclaredMethod("render", CollectionFlowState::class.java)
                .apply { isAccessible = true }.invoke(activity, fixture.state)
        }
    }

    /** Rendering checks share the native Activity while isolating all collection and network calls. */
    private class RenderingFlow(override var state: CollectionFlowState) : CollectionFlow {
        var reconnects = 0
        var retries = 0
        var referenceEntries = 0
        var protectiveStops = 0
        val uploadRetries = mutableListOf<String>()
        val endedStartAttempts = mutableListOf<String>()
        val referenceRevisions = mutableListOf<Triple<String, String, Pair<String, String>>>()
        val referenceDraftUpdates = mutableListOf<String>()
        var savedReference: Triple<String, String, String>? = null
        var finalizedReference: Pair<Boolean, Triple<String, String, String>>? = null
        var finishEntries = 0
        override fun observe(observer: (CollectionFlowState) -> Unit): AutoCloseable {
            observer(state)
            return AutoCloseable {}
        }
        override fun saveReference(stepsText: String, status: String, reason: String) {
            savedReference = Triple(stepsText, status, reason)
        }
        override fun updateReferenceDraft(stepsText: String) {
            referenceDraftUpdates += stepsText
            state = state.copy(referenceDraft = stepsText.ifEmpty { null }, referenceDraftError = null)
        }
        override fun finalizeSession(uploadNow: Boolean, stepsText: String, status: String, reason: String) {
            finalizedReference = uploadNow to Triple(stepsText, status, reason)
        }
        override fun enterFinish() { finishEntries++ }
        override fun reconnect() { reconnects++ }
        override fun register(participantId: String, placement: RingPlacement) = error("Unexpected registration")
        override fun start() = error("Unexpected start")
        override fun stop() {
            check(state.canStopUnconfirmedStart) { "Unexpected stop" }
            protectiveStops++
        }
        override fun enterReference() { referenceEntries++ }
        override fun retry() { retries++ }
        override fun endStartAttempt(reason: String) { endedStartAttempts += reason }
        override fun retryUpload(sessionId: String) { uploadRetries += sessionId }
        override fun reviseReference(sessionId: String, stepsText: String, status: String, reason: String) {
            referenceRevisions += Triple(sessionId, stepsText, status to reason)
            state = state.copy(records = state.records.map { record ->
                if (record.sessionId != sessionId) record else record.copy(
                    steps = stepsText.toLong(),
                    referenceStatus = status,
                    referenceReason = reason.ifBlank { null },
                )
            })
        }
        override fun home() = error("Unexpected navigation")
        override fun setFault(fault: FlowTestFault) = error("Unexpected development option")
        override fun disconnect() = error("Unexpected disconnect")
    }

    private fun startAttemptDialog(activity: DemoCollectionActivity): AlertDialog =
        StepCollectionActivity::class.java.getDeclaredField("dialog").apply { isAccessible = true }
            .get(activity) as AlertDialog

    private fun launch(): ActivityScenario<DemoCollectionActivity> = ActivityScenario.launch(
        Intent(instrumentation.targetContext, DemoCollectionActivity::class.java))

    private fun register(scenario: ActivityScenario<DemoCollectionActivity>) {
        awaitHeading(scenario, "准备开始")
        type(scenario, "flow_participant", "FLOW001")
        scenario.onActivity { tagged<Spinner>(it, "flow_placement").setSelection(RingPlacement.LEFT_INDEX.ordinal + 1) }
        click(scenario, "flow_primary")
        awaitHeading(scenario, "步数采集")
    }

    private fun reachReference(scenario: ActivityScenario<DemoCollectionActivity>, handle: DemoFlowRuntime.TestHandle,
        capture: Boolean = false): String {
        startWalking(scenario)
        awaitHeading(scenario, "正在采集")
        if (capture) captureReviewScreen(scenario, "collecting")
        val id = session(handle).sessionId
        click(scenario, "flow_primary")
        chooseSaveAfterStop(scenario, capture)
        awaitHeading(scenario, "结束本段")
        if (capture) captureReviewScreen(scenario, "reference")
        if (capture && InstrumentationRegistry.getArguments().getString("captureFlowScreens") == "true") {
            scenario.onActivity { activity ->
                val input = tagged<EditText>(activity, "flow_steps")
                input.requestFocus()
                activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    .showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
            await(scenario, "reference keyboard visible") {
                it.window.decorView.rootWindowInsets.isVisible(android.view.WindowInsets.Type.ime())
            }
            captureReviewScreen(scenario, "reference_keyboard")
            scenario.onActivity { activity ->
                val root = activity.window.decorView
                val ime = root.rootWindowInsets.getInsets(android.view.WindowInsets.Type.ime()).bottom
                val input = tagged<EditText>(activity, "flow_steps")
                val visible = android.graphics.Rect()
                assertTrue("The input remains visible above the keyboard", input.getGlobalVisibleRect(visible))
                assertEquals(input.height, visible.height())
                assertTrue(visible.bottom <= root.height - ime)
                activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    .hideSoftInputFromWindow(input.windowToken, 0)
            }
            await(scenario, "reference keyboard hidden") {
                !it.window.decorView.rootWindowInsets.isVisible(android.view.WindowInsets.Type.ime())
            }
        }
        assertEquals(id, session(handle).sessionId)
        return id
    }

    private fun startWalking(scenario: ActivityScenario<DemoCollectionActivity>) {
        click(scenario, "activity_walking")
        await(scenario, "walking selection enables start") {
            taggedOrNull<Button>(it, "flow_primary")?.let { button -> button.isShown && button.isEnabled } == true
        }
        click(scenario, "flow_primary")
    }

    private fun chooseSaveAfterStop(scenario: ActivityScenario<DemoCollectionActivity>, capture: Boolean = false) {
        awaitHeading(scenario, "结束本段")
        await(scenario, "the merged finish page presents reference input and all finish choices") {
            taggedOrNull<Button>(it, "flow_primary")?.let { button ->
                button.text.toString() == "保存并上传" && button.isShown && button.isEnabled
            } == true &&
                taggedOrNull<Button>(it, "finish_defer")?.let { button -> button.isShown && button.isEnabled } == true &&
                taggedOrNull<EditText>(it, "flow_steps") != null
        }
        if (capture) captureReviewScreen(scenario, "finish")
    }

    private fun reachFinish(scenario: ActivityScenario<DemoCollectionActivity>, handle: DemoFlowRuntime.TestHandle,
        activity: SessionActivity): String {
        click(scenario, "activity_${activity.wireValue}")
        await(scenario, "activity selection enables start") {
            taggedOrNull<Button>(it, "flow_primary")?.let { button -> button.isShown && button.isEnabled } == true
        }
        click(scenario, "flow_primary")
        awaitHeading(scenario, "正在采集")
        val id = session(handle).sessionId
        click(scenario, "flow_primary")
        chooseSaveAfterStop(scenario)
        assertNotNull(session(handle).stopConfirmedAtMs)
        assertNull(session(handle).reference)
        assertNull(session(handle).completionPolicy)
        return id
    }

    private fun captureReviewScreen(scenario: ActivityScenario<DemoCollectionActivity>, page: String) {
        if (InstrumentationRegistry.getArguments().getString("captureFlowScreens") != "true") return
        require(page.matches(Regex("[a-z_]+")))
        // Let dialog dimming and IME transitions settle before committing the review frame.
        SystemClock.sleep(800)
        val frameDrawn = CountDownLatch(1)
        scenario.onActivity { activity ->
            val dialog = StepCollectionActivity::class.java.getDeclaredField("dialog").apply { isAccessible = true }
                .get(activity) as AlertDialog?
            val root = dialog?.takeIf { it.isShowing }?.window?.decorView ?: activity.window.decorView
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
        await(scenario, "page $text") {
            when (text) {
                "步数采集" -> taggedOrNull<View>(it, "home_device_status") != null
                "这一段已保存" -> taggedOrNull<View>(it, "home_records_summary") != null &&
                    taggedOrNull<View>(it, "home_task_status") == null
                else -> taggedOrNull<TextView>(it, "flow_heading")?.text?.toString() == text
            }
        }

    private fun openRecords(scenario: ActivityScenario<DemoCollectionActivity>) {
        click(scenario, "open_records")
        awaitHeading(scenario, "采集记录")
    }

    private fun await(scenario: ActivityScenario<DemoCollectionActivity>, description: String,
        condition: (DemoCollectionActivity) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        do {
            var satisfied = false
            scenario.onActivity { satisfied = condition(it) }
            if (satisfied) return
            SystemClock.sleep(25)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("Timed out waiting for $description")
    }

    private fun awaitFlow(handle: DemoFlowRuntime.TestHandle, description: String, condition: (CollectionFlowState) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
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

    private fun visibleTexts(view: View): List<String> = when (view) {
        is ViewGroup -> (0 until view.childCount).flatMap { visibleTexts(view.getChildAt(it)) }
        is TextView -> listOf(view.text.toString())
        else -> emptyList()
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
