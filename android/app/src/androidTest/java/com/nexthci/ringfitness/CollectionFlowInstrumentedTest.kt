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

    @Test fun endStartAttemptRequiresAnExplicitReasonAndWaitsForTheOwnerResult() = withFlow { handle ->
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
            click(scenario, "end_start_attempt")
            scenario.onActivity { activity ->
                val confirmation = startAttemptDialog(activity)
                val input = confirmation.window!!.decorView.findViewWithTag<EditText>("end_start_attempt_reason")
                assertTrue(confirmation.isShowing)
                input.setText("   ")
                assertTrue(confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick())
                assertTrue(confirmation.isShowing)
                assertEquals("请填写原因", input.error.toString())
                assertTrue(fixture.endedStartAttempts.isEmpty())
                assertTrue(confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).performClick())
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertFalse(startAttemptDialog(activity).isShowing)
                assertTrue(fixture.endedStartAttempts.isEmpty())
                assertEquals("开始待确认", tagged<TextView>(activity, "flow_heading").text.toString())
            }
            click(scenario, "end_start_attempt")
            scenario.onActivity { activity ->
                val confirmation = startAttemptDialog(activity)
                confirmation.window!!.decorView.findViewWithTag<EditText>("end_start_attempt_reason")
                    .setText("  体验点击，未进行正式采集  ")
                val confirm = confirmation.getButton(AlertDialog.BUTTON_POSITIVE)
                assertTrue(confirm.performClick())
                assertFalse(confirmation.isShowing)
                confirm.performClick() // A queued second activation must not dispatch again.
                assertEquals(listOf("体验点击，未进行正式采集"), fixture.endedStartAttempts)
                assertEquals("开始待确认", tagged<TextView>(activity, "flow_heading").text.toString())
                assertEquals(CollectionPage.RECOVERY, fixture.state.page)
                assertEquals(request, fixture.state.session)
                assertNull(fixture.state.session!!.startAttemptArchive)
                assertNull(fixture.state.session!!.localData)
            }
            fixture.state = fixture.state.copy(session = null, canEndStartAttempt = false,
                error = "戒指返回异常，请重新连接后再试")
            renderFixture(scenario, fixture)
            scenario.onActivity { activity ->
                assertEquals("戒指暂未就绪", tagged<TextView>(activity, "flow_heading").text.toString())
                assertNull(taggedOrNull<View>(activity, "end_start_attempt"))
            }
            fixture.state = fixture.state.copy(page = CollectionPage.HOME)
            renderFixture(scenario, fixture)
            scenario.onActivity { activity ->
                assertEquals("戒指暂未就绪", tagged<TextView>(activity, "home_task_status").text.toString())
                assertNull(taggedOrNull<View>(activity, "flow_error"))
                assertEquals("重新检查", tagged<Button>(activity, "flow_primary").text.toString())
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
                connected = true, canStart = true, records = history))
            renderFixture(scenario, fixture)
            scenario.onActivity { activity ->
                fun statuses(view: View): List<String> = buildList {
                    (view.tag as? String)?.takeIf { it.startsWith("record_status_") }?.let { add(it.removePrefix("record_status_")) }
                    if (view is ViewGroup) repeat(view.childCount) { addAll(statuses(view.getChildAt(it))) }
                }
                assertEquals(history.drop(1).reversed().map { it.sessionId },
                    statuses(activity.findViewById(android.R.id.content)))
                assertNull(taggedOrNull<View>(activity, "record_status_uploaded-old"))
                assertEquals("开始采集", tagged<Button>(activity, "flow_primary").text.toString())
                history.filter { it.transferStatus != "complete" }.forEach {
                    val retry = tagged<Button>(activity, "retry_upload_${it.sessionId}")
                    assertTrue(retry.isEnabled)
                    retry.performClick()
                }
            }
            assertEquals(history.filter { it.transferStatus != "complete" }.map { it.sessionId }, fixture.uploadRetries)
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
                records = listOf(FlowRecordSummary(session.sessionId, 17, "valid", "transferring", true, true))))
            renderFixture(scenario, fixture)
            scenario.onActivity { activity ->
                assertEquals("这一段已保存", tagged<TextView>(activity, "flow_heading").text.toString())
                assertEquals("正在上传…", tagged<TextView>(activity, "flow_detail_上传").text.toString())
                assertEquals("返回首页", tagged<Button>(activity, "flow_primary").text.toString())
                assertNull(taggedOrNull<View>(activity, "retry_upload_${session.sessionId}"))
                assertNull(taggedOrNull<View>(activity, "flow_error"))
            }
            fixture.state = fixture.state.copy(session = session.copy(
                transfer = SessionTransfer(SessionTransferStatus.FAILED, 1)),
                records = listOf(FlowRecordSummary(session.sessionId, 17, "valid", "failed", true)))
            renderFixture(scenario, fixture)
            scenario.onActivity { activity ->
                assertEquals("上传待重试", tagged<TextView>(activity, "flow_detail_上传").text.toString())
                assertEquals("返回首页", tagged<Button>(activity, "flow_primary").text.toString())
            }
            click(scenario, "retry_upload_${session.sessionId}")
            assertEquals(listOf(session.sessionId), fixture.uploadRetries)
            fixture.state = fixture.state.copy(page = CollectionPage.HOME)
            renderFixture(scenario, fixture)
            scenario.onActivity { activity ->
                assertEquals("开始采集", tagged<Button>(activity, "flow_primary").text.toString())
                assertNull(taggedOrNull<View>(activity, "flow_error"))
            }
            click(scenario, "retry_upload_${session.sessionId}")
            assertEquals(listOf(session.sessionId, session.sessionId), fixture.uploadRetries)
            fixture.state = fixture.state.copy(page = CollectionPage.COMPLETE,
                session = session.copy(transfer = SessionTransfer(SessionTransferStatus.COMPLETE, 1,
                    SessionTransferReceipt("view-receipt", 6_000, false, session.sessionId))),
                records = listOf(FlowRecordSummary(session.sessionId, 17, "valid", "complete", true)))
            renderFixture(scenario, fixture)
            scenario.onActivity { activity ->
                assertEquals("已完成", tagged<TextView>(activity, "flow_detail_上传").text.toString())
                assertNull(taggedOrNull<View>(activity, "retry_upload_${session.sessionId}"))
                assertEquals("返回首页", tagged<Button>(activity, "flow_primary").text.toString())
            }
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
            scenario.onActivity { activity ->
                assertEquals("戒指数据需要检查", tagged<TextView>(activity, "flow_heading").text.toString())
                assertEquals("17 步", tagged<TextView>(activity, "saved_reference").text.toString())
                assertEquals("已保存", tagged<TextView>(activity, "flow_detail_计步器读数").text.toString())
                assertEquals("待检查", tagged<TextView>(activity, "flow_detail_戒指数据").text.toString())
                assertEquals("等待文件检查", tagged<TextView>(activity, "flow_detail_上传").text.toString())
                assertEquals("重新检查", tagged<Button>(activity, "retry_upload_${session.sessionId}").text.toString())
            }
            click(scenario, "retry_upload_${session.sessionId}")
            assertEquals(listOf(session.sessionId), fixture.uploadRetries)
            fixture.state = fixture.state.copy(page = CollectionPage.HOME)
            renderFixture(scenario, fixture)
            scenario.onActivity { activity ->
                assertEquals("戒指数据需要检查", tagged<TextView>(activity, "record_status_${session.sessionId}").text.toString())
                assertEquals("重新检查", tagged<Button>(activity, "retry_upload_${session.sessionId}").text.toString())
            }
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
                assertEquals("返回", tagged<Button>(activity, "flow_back").text.toString())
                assertEquals("正在连接戒指", tagged<TextView>(activity, "home_task_status").text.toString())
                assertFalse(tagged<Button>(activity, "flow_primary").isEnabled)
            }
            fixture.state = fixture.state.copy(connecting = false)
            renderFixture(scenario, fixture)
            click(scenario, "flow_primary")
            assertEquals(1, fixture.reconnects)
            fixture.state = fixture.state.copy(page = CollectionPage.RECOVERY, canRetry = true)
            renderFixture(scenario, fixture)
            scenario.onActivity { activity ->
                assertEquals("连接尚未完成", tagged<TextView>(activity, "flow_heading").text.toString())
                assertNull(taggedOrNull<View>(activity, "flow_detail_本次记录"))
            }
            fixture.state = fixture.state.copy(connected = true, canStart = true,
                page = CollectionPage.HOME,
                records = listOf(FlowRecordSummary("view-session", 0, "valid", "pending", true)))
            renderFixture(scenario, fixture)
            scenario.onActivity { activity ->
                assertEquals("已连接", tagged<TextView>(activity, "flow_detail_戒指").text.toString())
                assertEquals("开始采集", tagged<Button>(activity, "flow_primary").text.toString())
                assertNull(taggedOrNull<View>(activity, "retry_upload_view-session"))
            }
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
                assertEquals("保存本次记录", tagged<Button>(activity, "flow_primary").text.toString())
                assertNull(taggedOrNull<View>(activity, "flow_options"))
            }
            type(scenario, "flow_steps", "0")
            click(scenario, "flow_primary")
            assertEquals(Triple("0", "valid", ""), fixture.savedReference)
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
                assertEquals("记录尚未完整保存", tagged<TextView>(activity, "flow_heading").text.toString())
                assertEquals("待保存读数", tagged<TextView>(activity, "saved_reference").text.toString())
                assertEquals("待保存", tagged<TextView>(activity, "flow_detail_计步器读数").text.toString())
                assertEquals("待下载", tagged<TextView>(activity, "flow_detail_戒指数据").text.toString())
            }
            // These are UI-only evidence fixtures. No BLE, real raw file or cloud request is made.
            val local = session.copy(reference = SessionReference(ReferenceStatus.VALID, 0, 4_000),
                localData = SessionLocalData(listOf(SessionRawFile("view.rfbin", 1, 1, "0".repeat(64))), 5_000))
            fixture.state = fixture.state.copy(session = local)
            renderFixture(scenario, fixture)
            scenario.onActivity { activity ->
                assertEquals("这一段已保存", tagged<TextView>(activity, "flow_heading").text.toString())
                assertEquals("0 步", tagged<TextView>(activity, "saved_reference").text.toString())
                assertEquals("已保存", tagged<TextView>(activity, "flow_detail_戒指数据").text.toString())
                assertEquals("待上传", tagged<TextView>(activity, "flow_detail_上传").text.toString())
            }
            fixture.state = fixture.state.copy(session = local.copy(transfer = SessionTransfer(
                SessionTransferStatus.COMPLETE, 1, SessionTransferReceipt("view-receipt", 6_000, true, local.sessionId))))
            renderFixture(scenario, fixture)
            scenario.onActivity { activity ->
                assertEquals("A simulated receipt cannot confirm a real upload", "待上传",
                    tagged<TextView>(activity, "flow_detail_上传").text.toString())
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
            captureReviewScreen(scenario, "complete")
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
                captureReviewScreen(scenario, "reference_$kind")
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
            captureReviewScreen(scenario, "reference_pending_home")
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
            captureReviewScreen(scenario, "upload_failure")
            click(scenario, "flow_back")
            awaitHeading(scenario, "开始这一段")
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
            captureReviewScreen(scenario, "disconnected")
            assertEquals(original, session(handle))
            click(scenario, "flow_back")
            awaitHeading(scenario, "本次采集")
            scenario.onActivity {
                assertEquals("戒指连接中断", tagged<TextView>(it, "home_task_status").text.toString())
                assertEquals("重新连接", tagged<Button>(it, "flow_primary").text.toString())
            }
            captureReviewScreen(scenario, "reconnect_home")
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
            captureReviewScreen(scenario, "collecting_home")
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
            captureReviewScreen(scenario, "download_retry_home")
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
            captureReviewScreen(scenario, "stop_unconfirmed")
            click(scenario, "preserve_reference")
            awaitHeading(scenario, "本次走了多少步？")
            click(scenario, "flow_back")
            awaitHeading(scenario, "本次采集")
            scenario.onActivity {
                assertEquals("结束状态待确认", tagged<TextView>(it, "home_task_status").text.toString())
                assertEquals("先填写步数", tagged<Button>(it, "flow_primary").text.toString())
            }
            captureReviewScreen(scenario, "stop_unconfirmed_home")
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
        val uploadRetries = mutableListOf<String>()
        val endedStartAttempts = mutableListOf<String>()
        var savedReference: Triple<String, String, String>? = null
        override fun observe(observer: (CollectionFlowState) -> Unit): AutoCloseable {
            observer(state)
            return AutoCloseable {}
        }
        override fun saveReference(stepsText: String, status: String, reason: String) {
            savedReference = Triple(stepsText, status, reason)
        }
        override fun reconnect() { reconnects++ }
        override fun register(participantId: String, placement: RingPlacement) = error("Unexpected registration")
        override fun start() = error("Unexpected start")
        override fun stop() = error("Unexpected stop")
        override fun enterReference() = error("Unexpected navigation")
        override fun retry() = error("Unexpected retry")
        override fun endStartAttempt(reason: String) { endedStartAttempts += reason }
        override fun retryUpload(sessionId: String) { uploadRetries += sessionId }
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
                val button = tagged<Button>(activity, "flow_primary")
                val visible = android.graphics.Rect()
                assertTrue("The save action remains visible above the keyboard", button.getGlobalVisibleRect(visible))
                assertEquals(button.height, visible.height())
                assertTrue(visible.bottom <= root.height - ime)
                activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    .hideSoftInputFromWindow(button.windowToken, 0)
            }
            await(scenario, "reference keyboard hidden") {
                !it.window.decorView.rootWindowInsets.isVisible(android.view.WindowInsets.Type.ime())
            }
        }
        assertEquals(id, session(handle).sessionId)
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

    private fun chooseReferenceKind(scenario: ActivityScenario<DemoCollectionActivity>, kind: String) {
        click(scenario, "reference_options")
        captureReviewScreen(scenario, "reference_options")
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
