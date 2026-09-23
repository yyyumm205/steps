package com.nexthci.ringfitness

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Rendering and input only; the flow owner survives page changes and owns durable state. */
abstract class StepCollectionActivity : Activity() {
    protected abstract fun provideFlow(): CollectionFlow
    private lateinit var flow: CollectionFlow
    private lateinit var ui: QuietUi
    private var subscription: AutoCloseable? = null
    private val handler = Handler(Looper.getMainLooper())
    private var current: CollectionFlowState? = null
    private var stepsInput: EditText? = null
    private var participantInput: EditText? = null
    private var placementPicker: Spinner? = null
    private var elapsedLabel: TextView? = null
    private var pageScroll: ScrollView? = null
    private var finishInputError: TextView? = null
    private var imeWasVisible = false
    private var stepsDraft = ""
    private var participantDraft = ""
    private var placementDraft = 0
    private var draftSessionId: String? = null
    private var dialog: AlertDialog? = null
    private var showingRecords = false
    private data class PendingReferenceRevision(
        val sessionId: String,
        val target: SessionReference,
        val editor: AlertDialog,
        val steps: EditText,
    )
    private var pendingReferenceRevision: PendingReferenceRevision? = null
    private val ticker = object : Runnable {
        override fun run() { updateElapsed(); handler.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        ui = QuietUi(this)
        flow = provideFlow()
        stepsDraft = savedInstanceState?.getString("steps").orEmpty()
        participantDraft = savedInstanceState?.getString("participant").orEmpty()
        placementDraft = savedInstanceState?.getInt("placement") ?: 0
        draftSessionId = savedInstanceState?.getString("draft_session")
        if (Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { back() }
    }

    override fun onStart() {
        super.onStart()
        subscription = flow.observe { state -> if (!isDestroyed) render(state) }
        handler.post(ticker)
    }

    override fun onStop() {
        rememberDrafts()
        subscription?.close()
        subscription = null
        handler.removeCallbacks(ticker)
        dialog?.dismiss()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        rememberDrafts()
        outState.putString("steps", stepsDraft)
        outState.putString("participant", participantDraft)
        outState.putInt("placement", placementDraft)
        outState.putString("draft_session", draftSessionId)
        super.onSaveInstanceState(outState)
    }

    // API 33+ is handled by the OnBackInvoked callback registered in onCreate.
    @SuppressLint("GestureBackNavigation")
    @Deprecated("Legacy Android back callback")
    override fun onBackPressed() = back()

    private fun back() {
        val state = current
        when {
            showingRecords -> {
                showingRecords = false
                redraw()
            }
            state?.isSimulation == false && state.session == null &&
                state.page in setOf(CollectionPage.RECOVERY, CollectionPage.ERROR) &&
                !RealCollectionBridge.isRunning() -> openPreparationSettings()
            state?.let(::usesHomeSurface) == true -> finish()
            else -> flow.home()
        }
    }

    private fun redraw() {
        val state = current ?: return
        current = null
        render(state)
    }

    private fun rememberDrafts() {
        stepsInput?.let { stepsDraft = it.text.toString() }
        participantInput?.let { participantDraft = it.text.toString() }
        placementPicker?.let { placementDraft = it.selectedItemPosition }
    }

    private fun render(state: CollectionFlowState) {
        rememberDrafts()
        val old = current
        current = state
        if (!usesHomeSurface(state)) showingRecords = false
        resolveReferenceRevision(state)
        if (state.session?.sessionId != null && draftSessionId != state.session.sessionId) {
            draftSessionId = state.session.sessionId
            stepsDraft = ""
        }
        state.session?.reference?.let { saved ->
            stepsDraft = saved.steps?.toString().orEmpty()
        }
        if (old != null && old.copy(downloadSavedBytes = state.downloadSavedBytes,
                downloadTotalBytes = state.downloadTotalBytes, downloadFinalizing = state.downloadFinalizing) == state) {
            updateDownloadProgress(state)
            return
        }
        // State updates unrelated to the form must not steal focus or replace a user's input.
        if (old == state) return
        if (state.page in setOf(CollectionPage.FINISH, CollectionPage.REFERENCE) && old?.page == state.page &&
            old.copy(records = state.records, fault = state.fault, connected = state.connected,
                connecting = state.connecting, canRetry = state.canRetry, error = state.error) == state) {
            window.decorView.findViewWithTag<TextView>("flow_error")?.apply {
                text = displayError(state.error).orEmpty()
                visibility = if (state.error == null) View.GONE else View.VISIBLE
            }
            return
        }
        stepsInput = null; participantInput = null; placementPicker = null; elapsedLabel = null
        finishInputError = null
        val root = ui.column().apply {
            setBackgroundColor(ui.background)
            isFocusableInTouchMode = true
            requestFocus()
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                val imeVisible = insets.isVisible(WindowInsets.Type.ime())
                if (imeWasVisible && !imeVisible) pageScroll?.post { pageScroll?.scrollTo(0, 0) }
                imeWasVisible = imeVisible
                if (imeVisible && finishInputError?.visibility == View.VISIBLE) {
                    finishInputError?.post {
                        (currentFocus as? EditText)?.let(::revealRequiredFinishInput)
                    }
                }
                insets
            }
        }
        setContentView(root)
        val header = ui.column(root, 20).apply { setPadding(ui.dp(20), ui.dp(4), ui.dp(20), ui.dp(4)) }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(row)
        val homeSurface = usesHomeSurface(state)
        if (!homeSurface || showingRecords || state.isSimulation) {
            val returnLabel = if (homeSurface && state.isSimulation && !showingRecords) "退出演示" else "首页"
            val returnButton = ui.button(row, returnLabel, tag = "flow_back") { back() }
            returnButton.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            returnButton.minHeight = ui.dp(48); returnButton.minimumHeight = ui.dp(48)
            returnButton.background = ui.linkBackground()
            returnButton.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        } else {
            ui.text(row, "步数采集", 20f, bold = true).apply {
                tag = "flow_heading"
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
        }
        if (state.isSimulation) {
            val options = ui.button(row, "演示选项", tag = "flow_options") { showOptions() }
            options.layoutParams = LinearLayout.LayoutParams(-2, -2)
            options.minHeight = ui.dp(48); options.minimumHeight = ui.dp(48)
            options.background = ui.linkBackground()
        } else {
            val settings = ui.button(row, if (state.session?.isPending == true) "信息" else "设置",
                tag = "flow_settings") { openPreparationSettings() }
            settings.layoutParams = LinearLayout.LayoutParams(-2, -2)
            settings.minHeight = ui.dp(48); settings.minimumHeight = ui.dp(48)
            settings.background = ui.linkBackground()
        }
        if (state.isSimulation) {
            ui.text(header, "流程演示 · 设备与传输为模拟", 13f).apply {
                tag = "demo_marker"
                background = ui.shape(ui.ink, 12)
                setTextColor(ui.surface)
                setPadding(ui.dp(12), ui.dp(9), ui.dp(12), ui.dp(9))
            }
        }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        pageScroll = scroll
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val body = ui.column(padding = 20).apply { setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(12)) }
        scroll.addView(body)
        val footer = ui.column(root, 20).apply { setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12)) }
        if (state.page in setOf(CollectionPage.FINISH, CollectionPage.REFERENCE) ||
            (state.error != null && state.page !in setOf(CollectionPage.HOME, CollectionPage.RECOVERY, CollectionPage.ERROR))) {
            ui.text(footer, displayError(state.error).orEmpty(), 14f).apply {
                tag = "flow_error"
                visibility = if (state.error == null) View.GONE else View.VISIBLE
                setTextColor(ui.error)
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
        }
        if (!state.hasProfile && state.busy) {
            title(body, "正在读取记录")
            body.addView(ui.progress(), LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply { gravity = Gravity.CENTER })
        }
        else if (!state.hasProfile) registration(body, footer, state)
        else renderTask(body, footer, state)
        if (old?.page != state.page) {
            scroll.post {
                root.requestFocus()
                scroll.scrollTo(0, 0)
            }
        }
    }

    private fun renderTask(body: LinearLayout, footer: LinearLayout, state: CollectionFlowState) {
        val session = state.session
        when {
            usesHomeSurface(state) -> if (showingRecords) records(body, state) else home(body, footer, state)
            state.page in setOf(CollectionPage.STARTING, CollectionPage.COLLECTING, CollectionPage.STOPPING) ->
                captureSession(body, footer, state)
            state.page == CollectionPage.SAVING -> saving(body, state)
            state.page == CollectionPage.FINISH -> finishSession(body, state)
            state.page == CollectionPage.REFERENCE && session?.stopConfirmedAtMs != null -> finishSession(body, state)
            state.page == CollectionPage.REFERENCE -> reference(body, footer, state)
            state.page in setOf(CollectionPage.RECOVERY, CollectionPage.ERROR) && session == null -> recovery(body, footer, state)
            state.page in setOf(CollectionPage.RECOVERY, CollectionPage.ERROR) &&
                session?.startAbort?.stoppedObservation != null -> recovery(body, footer, state)
            state.page in setOf(CollectionPage.RECOVERY, CollectionPage.ERROR) &&
                session?.phase == FreeLivingSessionPhase.START_REQUESTED && state.canEndStartAttempt ->
                recovery(body, footer, state)
            state.page in setOf(CollectionPage.RECOVERY, CollectionPage.ERROR) &&
                session?.phase in setOf(FreeLivingSessionPhase.START_REQUESTED, FreeLivingSessionPhase.COLLECTING,
                    FreeLivingSessionPhase.STOP_REQUESTED) -> captureSession(body, footer, state)
            state.page in setOf(CollectionPage.RECOVERY, CollectionPage.ERROR) &&
                session?.stopConfirmedAtMs != null && session.reference == null -> finishSession(body, state)
            state.page in setOf(CollectionPage.RECOVERY, CollectionPage.ERROR) && session?.reference != null ->
                recovery(body, footer, state)
            else -> recovery(body, footer, state)
        }
    }

    private fun usesHomeSurface(state: CollectionFlowState): Boolean =
        state.page == CollectionPage.HOME ||
            state.page in setOf(CollectionPage.RING_PENDING, CollectionPage.DOWNLOADING, CollectionPage.UPLOADING, CollectionPage.COMPLETE) ||
            (state.page in setOf(CollectionPage.RECOVERY, CollectionPage.ERROR) &&
                state.session?.reference != null && state.session.stopConfirmedAtMs != null)

    private fun title(body: LinearLayout, text: String, subtitle: String? = null) {
        ui.text(body, text, 28f, bold = true).tag = "flow_heading"
        subtitle?.let { ui.gap(body, 10); ui.text(body, it, 16f, muted = true) }
        ui.gap(body, 20)
    }

    private fun action(footer: LinearLayout, text: String, enabled: Boolean = true, block: () -> Unit): Button =
        ui.button(footer, text, primary = true, tag = "flow_primary", action = block).apply {
            isEnabled = enabled
        }

    private fun registration(body: LinearLayout, footer: LinearLayout, state: CollectionFlowState) {
        title(body, "准备开始", if (state.isSimulation) "填好信息，就可以体验一次完整记录。" else null)
        val card = ui.card(body)
        ui.text(card, "用户名", bold = true)
        ui.gap(card, 12)
        participantInput = ui.input(card, "3–24 位字母或数字", "flow_participant").apply { setText(participantDraft) }
        ui.gap(card, 24)
        ui.text(card, "戒指佩戴位置", bold = true)
        ui.gap(card, 10)
        placementPicker = Spinner(this).apply {
            tag = "flow_placement"
            adapter = ui.placementAdapter(listOf("请选择") + RingPlacement.entries.map { it.displayName })
            setSelection(placementDraft)
            minimumHeight = ui.dp(56)
            card.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        action(footer, "保存并继续") {
            rememberDrafts()
            val placement = RingPlacement.entries.getOrNull(placementDraft - 1)
            if (placement == null) {
                dialog = AlertDialog.Builder(this).setMessage("请选择戒指佩戴位置。")
                    .setPositiveButton("知道了", null).show()
            } else { hideKeyboard(); flow.register(participantDraft, placement) }
        }
    }

    private fun home(body: LinearLayout, footer: LinearLayout, state: CollectionFlowState) {
        val task = homeTask(state)
        val device = ui.card(body)
        val deviceRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        device.addView(deviceRow, LinearLayout.LayoutParams(-1, -2))
        val deviceText = ui.column(deviceRow)
        deviceText.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        ui.text(deviceText, state.ringName.ifBlank { "戒指" }, 18f, bold = true)
        ui.gap(deviceText, 6)
        ui.text(deviceText, connectionLabel(state), 14f, muted = true).tag = "home_device_status"
        ui.gap(deviceText, 12)
        ui.text(deviceText, listOf(
            state.participantLabel.ifBlank { state.participantId },
            state.placement?.displayName ?: "待选择佩戴位置",
        ).filter { it.isNotBlank() }.joinToString(" · "), 14f, muted = true).tag = "home_profile_summary"
        when {
            state.connecting || state.checkingDevice -> deviceRow.addView(ui.progress(),
                LinearLayout.LayoutParams(ui.dp(28), ui.dp(28)))
            !state.connected && state.canRetry && state.session?.isRingDeferred != true -> ui.button(deviceRow, "重新连接", tag = "home_reconnect") {
                flow.reconnect()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(-2, -2)
                minimumHeight = ui.dp(48); minHeight = ui.dp(48)
            }
        }

        val pendingTask = state.session?.isPending == true || state.preservingExisting ||
            (state.taskPage in setOf(CollectionPage.STARTING, CollectionPage.COLLECTING, CollectionPage.STOPPING,
                CollectionPage.FINISH, CollectionPage.REFERENCE, CollectionPage.SAVING, CollectionPage.DOWNLOADING,
                CollectionPage.RECOVERY, CollectionPage.ERROR) && state.session?.localData == null)
        if (pendingTask) {
            val taskCard = ui.card(body, ui.statusSurface)
            ui.text(taskCard, task.title, 14f, muted = true)
            ui.gap(taskCard, 8)
            ui.text(taskCard, task.status, 24f, bold = true).tag = "home_task_status"
            task.hint?.takeIf { it.isNotBlank() }?.let {
                ui.gap(taskCard, 10)
                ui.text(taskCard, it).tag = "home_task_hint"
            }
            if (state.taskPage == CollectionPage.DOWNLOADING && state.downloadTotalBytes?.let { it > 0 } == true) {
                ui.gap(taskCard, 12)
                taskCard.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    tag = "home_download_progress"
                    max = 1_000
                    progress = downloadProgress(state)
                    isIndeterminate = state.downloadSavedBytes == null
                }, LinearLayout.LayoutParams(-1, ui.dp(8)))
            }
            val actionLabel = task.action.takeUnless { it.endsWith("…") }
            val localFinishAction = state.session?.isRingDeferred == true || state.canRecordReferenceLocally ||
                (state.session?.stopConfirmedAtMs != null && state.session.reference == null &&
                    state.taskPage in setOf(CollectionPage.FINISH, CollectionPage.REFERENCE, CollectionPage.RECOVERY))
            if (actionLabel != null && (state.connected || localFinishAction) && (!state.busy || localFinishAction) &&
                (!state.connecting || localFinishAction)
            ) {
                ui.gap(taskCard, 12)
                ui.button(taskCard, actionLabel, primary = true, tag = "home_task_action") {
                    when {
                        state.session?.isRingDeferred == true -> flow.resumeRingTransfer()
                        state.canRecordReferenceLocally -> flow.enterFinish()
                        !state.connected -> flow.reconnect()
                        state.canStopUnconfirmedStart -> flow.stop()
                        state.taskPage == CollectionPage.FINISH -> flow.enterFinish()
                        state.taskPage == CollectionPage.REFERENCE -> flow.enterReference()
                        else -> flow.retry()
                    }
                }
            }
            val stopped = state.session
            if (!state.busy && stopped?.stopConfirmedAtMs != null && stopped.reference != null &&
                stopped.localData == null && !stopped.isDiscarded &&
                stopped.transfer.status == SessionTransferStatus.PENDING && stopped.transfer.attempts == 0 &&
                stopped.transfer.receipt == null &&
                (stopped.isRingDeferred || state.page in setOf(CollectionPage.RECOVERY, CollectionPage.ERROR) ||
                    state.taskPage in setOf(CollectionPage.RECOVERY, CollectionPage.ERROR))) {
                ui.button(taskCard, "放弃本段", tag = "recovery_discard") { showDiscardConfirmation() }.apply {
                    background = ui.linkBackground()
                }
            }
        } else if (state.canStart) {
            val startCard = ui.card(body, ui.statusSurface)
            ui.text(startCard, "本次活动", 14f, muted = true)
            ui.gap(startCard, 10)
            val choices = LinearLayout(this)
            startCard.addView(choices, LinearLayout.LayoutParams(-1, -2))
            listOf(SessionActivity.WALKING, SessionActivity.RUNNING).forEachIndexed { index, activity ->
                ui.button(choices, activity.label, primary = state.selectedActivity == activity,
                    tag = "activity_${activity.wireValue}") { flow.selectActivity(activity) }.layoutParams =
                    LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = ui.dp(12) }
            }
            ui.gap(startCard, 18)
            ui.text(startCard, "佩戴好设备，站定后将计步器清零。", 15f, muted = true)
        }

        val localRecords = state.records.filter { it.localComplete || it.ringDeferred }
        if (localRecords.isNotEmpty()) {
            val recordsCard = ui.card(body)
            ui.text(recordsCard, "采集记录", 16f, bold = true)
            ui.gap(recordsCard, 10)
            ui.text(recordsCard, recordsSummary(localRecords), 14f, muted = true).tag = "home_records_summary"
            ui.button(recordsCard, "查看记录", tag = "open_records") {
                showingRecords = true
                redraw()
            }
        }
        if (!pendingTask && state.canStart && state.selectedActivity != null && !state.busy) {
            action(footer, "开始采集") { flow.start() }
        } else if (!pendingTask && state.canStart && state.selectedActivity == null) {
            ui.text(footer, "选择走路或跑步后开始。", 14f, muted = true)
        }
    }

    private fun records(body: LinearLayout, state: CollectionFlowState) {
        title(body, "采集记录")
        val records = state.records.filter { it.localComplete || it.ringDeferred }.reversed()
        if (records.isEmpty()) {
            val empty = ui.card(body)
            ui.text(empty, "暂无记录", 16f, muted = true)
            return
        }
        records.forEach { record ->
            val card = ui.card(body)
            ui.text(card, listOfNotNull(
                formatDateTime(record.displayStartedAtMs, record.timeZoneId) ?: "时间未知",
                record.activity.label,
            ).joinToString(" · "), 14f, muted = true).tag = "record_time_${record.sessionId}"
            ui.gap(card, 8)
            ui.text(card, record.steps?.let { "$it 步" } ?: "未提供读数", 22f, bold = true).tag =
                "record_steps_${record.sessionId}"
            ui.gap(card, 8)
            ui.text(card, recordStatus(record, state), 14f, muted = true).tag = "record_status_${record.sessionId}"
            if (record.referenceStatus == "unreliable") {
                ui.gap(card, 8)
                ui.text(card, "读数已注明异常", 14f, muted = true)
            }
            if (record.referenceEditable) {
                ui.button(card, "修改步数", tag = "edit_reference_${record.sessionId}") {
                    showReferenceCorrection(record)
                }
            }
            recordAction(record, state)?.let { (label, action) ->
                ui.button(card, label, tag = "retry_upload_${record.sessionId}") { action() }
            }
            if (record.ringDeferred && record.sessionId == state.session?.sessionId && !state.busy) {
                ui.button(card, "放弃本段", tag = "discard_record_${record.sessionId}") { showDiscardConfirmation() }.apply {
                    background = ui.linkBackground()
                }
            }
        }
    }

    private fun recordsSummary(records: List<FlowRecordSummary>): String {
        val pending = records.count { it.transferStatus != "complete" }
        return when {
            records.any { it.ringDeferred } -> "${records.size} 条记录 · 有数据暂存到戒指"
            records.any { it.localReviewRequired } -> "${records.size} 条记录 · 有数据需要检查"
            records.any { it.transferStatus == "failed" } -> "${records.size} 条记录 · 有上传需要重试"
            pending > 0 -> "${records.size} 条记录 · $pending 条等待上传"
            else -> "${records.size} 条记录 · 已全部上传"
        }
    }

    private fun recordStatus(record: FlowRecordSummary, state: CollectionFlowState): String = when {
        record.ringDeferred -> "已暂存到戒指"
        record.localReviewRequired -> if (record.transferInFlight) "正在校验上传文件" else "上传记录需要研究者核对"
        record.transferStatus == "complete" -> if (state.isSimulation) "模拟上传完成" else "已上传"
        record.transferInFlight -> "正在上传"
        record.uploadDeferred -> "已保存，稍后上传"
        !state.uploadAvailable -> "已保存在手机"
        record.transferStatus == "failed" -> "上传失败，数据已保存在手机"
        else -> "已保存，等待上传"
    }

    private fun recordAction(record: FlowRecordSummary, state: CollectionFlowState): Pair<String, () -> Unit>? {
        if (record.ringDeferred) return "下载并上传" to {
            showingRecords = false
            flow.resumeRingTransfer()
        }
        if (!state.uploadAvailable || record.transferInFlight || record.transferStatus == "complete" ||
            record.localReviewRequired) return null
        val label = when {
            record.uploadDeferred -> "上传"
            record.transferStatus == "failed" -> "重试上传"
            record.uploadRequeueAvailable -> "重试上传"
            else -> return null
        }
        return label to {
            flow.retryUpload(record.sessionId)
            Toast.makeText(this, "已请求上传，结果将在此更新", Toast.LENGTH_SHORT).show()
        }
    }

    private data class HomeTask(val title: String, val status: String, val action: String, val hint: String? = null)

    private fun homeTask(state: CollectionFlowState): HomeTask = when {
        state.session?.isRingDeferred == true -> HomeTask("本段步数已保存", "已暂存到戒指", "下载并上传",
            "下载到手机后，即可开始下一段。")
        state.canRecordReferenceLocally -> if (state.session?.completionPolicy == null)
            HomeTask("采集已结束", "待保存本段", "继续收尾")
        else HomeTask("待填写步数", "采集已结束", "填写步数", "填写计步器显示的本次总数。")
        state.connecting -> HomeTask("设备", "正在连接戒指", "连接中…", "请将戒指放在手机附近。")
        !state.connected -> HomeTask("设备", "戒指未连接", "重新连接",
            displayError(state.error) ?: "将戒指靠近手机后重试。")
        state.session?.startAbort?.stoppedObservation != null && state.session.isPending ->
            if (state.preservingExisting || state.taskPage == CollectionPage.DOWNLOADING)
                HomeTask("正在保留戒指数据", "戒指已停止", "查看进度")
            else HomeTask("数据待保存", "戒指已停止", "继续保存", displayError(state.error))
        state.preservingExisting -> HomeTask("正在准备戒指", "请稍候", "保存中…", "完成后即可选择走路或跑步。")
        state.checkingDevice -> HomeTask("设备", "正在检查戒指", "检查中…")
        state.canStopUnconfirmedStart -> HomeTask("当前记录", "需要结束戒指记录", "结束并保存可用数据", "数据会保留在手机。")
        state.canStart -> HomeTask("开始这一段", "可以开始", "开始采集", "佩戴好设备，站定后将计步器清零。")
        state.session == null -> HomeTask("设备", "戒指暂未就绪", "重新检查", displayError(state.error))
        else -> when (state.taskPage) {
            CollectionPage.STARTING -> HomeTask("本次采集", "正在确认开始", "查看进度", "请站定等候。")
            CollectionPage.COLLECTING -> HomeTask("采集进行中", "正在采集", "查看采集")
            CollectionPage.STOPPING -> HomeTask("本次采集", "正在确认结束", "查看进度", "请站定等候。")
            CollectionPage.FINISH -> HomeTask("采集已结束", "待保存本段", "继续收尾")
            CollectionPage.REFERENCE -> if (state.session?.stopConfirmedAtMs != null)
                HomeTask("待填写步数", "采集已结束", "填写步数", "填写计步器显示的本次总数。")
            else HomeTask("本次采集", "结束状态待确认", "先填写步数", "可以先填写计步器读数。")
            CollectionPage.SAVING -> HomeTask("正在保存步数", "正在保存", "查看进度")
            CollectionPage.DOWNLOADING -> if (state.session.startAbort != null)
                HomeTask("正在保留戒指数据", "戒指已停止", "查看进度")
            else HomeTask("正在保存数据", "步数已保存", "查看下载", downloadHint(state))
            CollectionPage.UPLOADING -> HomeTask("正在上传记录", "数据已保存在手机", "查看上传")
            CollectionPage.ERROR -> when {
                state.session?.localData != null -> if (state.uploadAvailable)
                    HomeTask("待上传记录", "数据已保存在手机", "重试上传")
                else HomeTask("本次记录", "数据已保存在手机", "查看记录")
                state.session?.reference != null && state.session.stopConfirmedAtMs != null ->
                    HomeTask("待下载数据", "步数已保存", "重试下载")
                else -> HomeTask("本次采集", "记录需要检查", "重新检查")
            }
            else -> HomeTask("本次采集", when {
                state.session?.stopRequestedAtMs != null -> "结束状态待确认"
                state.session?.startConfirmedAtMs == null -> "开始状态待确认"
                else -> "戒指状态待确认"
            }, "重新检查")
        }
    }

    private fun updateDownloadProgress(state: CollectionFlowState) {
        window.decorView.findViewWithTag<TextView>("home_task_hint")?.text = downloadHint(state).orEmpty()
        window.decorView.findViewWithTag<ProgressBar>("home_download_progress")?.apply {
            isIndeterminate = state.downloadSavedBytes == null
            progress = downloadProgress(state)
        }
    }

    private fun downloadProgress(state: CollectionFlowState): Int {
        val saved = state.downloadSavedBytes ?: return 0
        val total = state.downloadTotalBytes?.takeIf { it > 0 } ?: return 0
        return ((saved.coerceIn(0, total) * 1_000L) / total).toInt()
    }

    private fun downloadHint(state: CollectionFlowState): String? {
        val saved = state.downloadSavedBytes
        val total = state.downloadTotalBytes?.takeIf { it > 0 }
        if (state.downloadFinalizing || (saved != null && total != null && saved >= total)) {
            return "原始数据已接收完成，正在检查并保存文件。"
        }
        if (saved == null || total == null) return "正在读取戒指原始数据，可离开页面，后台会继续保存。"
        val percent = ((saved.coerceIn(0, total) * 100L) / total).toInt()
        return "原始数据已保存 $percent%（${formatBytes(saved)} / ${formatBytes(total)}），可离开页面，后台会继续。"
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun captureSession(body: LinearLayout, footer: LinearLayout, state: CollectionFlowState) {
        val session = state.session
        val phase = when {
            state.page == CollectionPage.STOPPING || session?.phase == FreeLivingSessionPhase.STOP_REQUESTED ->
                FreeLivingSessionPhase.STOP_REQUESTED
            state.page == CollectionPage.COLLECTING || session?.phase == FreeLivingSessionPhase.COLLECTING ->
                FreeLivingSessionPhase.COLLECTING
            else -> FreeLivingSessionPhase.START_REQUESTED
        }
        val heading = when (phase) {
            FreeLivingSessionPhase.START_REQUESTED -> if (state.canStopUnconfirmedStart) "需要结束本次记录" else "正在开始"
            FreeLivingSessionPhase.COLLECTING -> "正在采集"
            FreeLivingSessionPhase.STOP_REQUESTED -> "正在结束"
            else -> "本次采集"
        }
        val subtitle = when {
            !state.connected -> "正在恢复与戒指的连接。"
            state.canStopUnconfirmedStart -> "结束后会保存已经产生的数据。"
            phase == FreeLivingSessionPhase.START_REQUESTED -> "确认后再开始活动。"
            phase == FreeLivingSessionPhase.STOP_REQUESTED -> "请保持站定，等待戒指停止。"
            else -> null
        }
        title(body, heading, subtitle)
        val card = ui.card(body, ui.statusSurface)
        if (phase == FreeLivingSessionPhase.COLLECTING) {
            ui.text(card, "本次时长", 14f)
            ui.gap(card, 14)
            elapsedLabel = ui.text(card, "00:00:00", 44f, bold = true).apply {
                tag = "flow_elapsed"
                maxLines = 1
                setAutoSizeTextTypeUniformWithConfiguration(24, 44, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            }
            ui.gap(card, 20)
            detail(card, "开始时间", startDateTime(session))
            updateElapsed()
        } else {
            card.addView(ui.progress(), LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply {
                gravity = Gravity.CENTER
            })
            ui.gap(card, 18)
            val progress = when {
                !state.connected -> "记录已保留，正在重新连接"
                phase == FreeLivingSessionPhase.START_REQUESTED -> "正在启动戒指"
                phase == FreeLivingSessionPhase.STOP_REQUESTED -> "正在保存最后数据"
                else -> "正在核对戒指状态"
            }
            ui.text(card, progress,
                16f, bold = true).gravity = Gravity.CENTER
        }
        ui.gap(card, 18)
        detail(card, "活动", session?.takeIf { it.isPending }?.activity?.label ?: "正在准备")
        ui.gap(card, 14)
        detail(card, "戒指", connectionLabel(state))
        if (phase == FreeLivingSessionPhase.STOP_REQUESTED && session?.reference != null) {
            ui.gap(card, 14)
            detail(card, "计步器读数", session.reference.steps?.let { "$it 步 · 已保存" } ?: "已保存")
        }

        when {
            phase == FreeLivingSessionPhase.COLLECTING && state.canStop && !state.busy -> {
                ui.text(footer, "站定后结束，再查看计步器读数。", 14f, muted = true)
                action(footer, "结束采集") { flow.stop() }
            }
            state.canStopUnconfirmedStart && !state.busy ->
                action(footer, "结束并保存数据") { flow.stop() }
            !state.connected && state.canRetry && !state.connecting ->
                action(footer, "重新连接") { flow.reconnect() }
            phase == FreeLivingSessionPhase.START_REQUESTED && state.canEndStartAttempt && !state.busy ->
                action(footer, "结束本次") { showEndStartAttempt() }
            phase == FreeLivingSessionPhase.STOP_REQUESTED && session?.reference == null ->
                ui.button(footer, "记录计步器读数", tag = "preserve_reference") { flow.enterReference() }
            phase == FreeLivingSessionPhase.STOP_REQUESTED && session?.reference != null && state.canRetry && !state.busy ->
                action(footer, "继续确认结束") { flow.retry() }
        }
    }

    private fun saving(body: LinearLayout, state: CollectionFlowState) {
        title(body, "正在保存")
        val card = ui.card(body)
        card.addView(ui.progress(), LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply { gravity = Gravity.CENTER })
        ui.gap(card, 18)
        ui.text(card, "正在把本次读数保存在手机", 16f, bold = true).gravity = Gravity.CENTER
        state.session?.activity?.let { activity ->
            ui.gap(card, 18); detail(card, "活动", activity.label)
        }
    }

    private fun finishSession(body: LinearLayout, state: CollectionFlowState) {
        // Keep the complete form scrollable when the keyboard or larger system text reduces space.
        title(body, "结束本段", "填写计步器显示的本次总数。")
        val summary = ui.card(body, ui.statusSurface)
        detail(summary, "活动", state.session?.activity?.label ?: "—")
        ui.gap(summary, 14)
        detail(summary, "开始时间", startDateTime(state.session))

        val input = ui.card(body)
        val savedReference = state.session?.reference
        if (savedReference != null) {
            ui.text(input, "计步器读数已保存", 14f, muted = true)
            ui.gap(input, 10)
            ui.text(input, savedReference.steps?.let { "$it 步" } ?: "无法提供读数", 24f, bold = true)
            savedReference.reason?.let { reason ->
                ui.gap(input, 12)
                ui.text(input, reason, 14f, muted = true)
            }
        } else {
            referenceInput(input)
            ui.gap(input, 12)
            ui.text(input, "无法提供本次步数时，可放弃本段。", 14f, muted = true).tag = "no_reference_hint"
        }

        val actions = ui.column(body)
        val fixedPolicy = state.session?.completionPolicy
        when {
            !state.uploadAvailable -> action(actions, "保存到手机", !state.busy) {
                submitFinish(true)
            }
            fixedPolicy == CompletionPolicy.SAVE_UPLOAD ->
                action(actions, "保存并上传", !state.busy) { submitFinish(true) }
            fixedPolicy == CompletionPolicy.SAVE_LATER ->
                action(actions, "保存，稍后上传", !state.busy) { submitFinish(false) }
            fixedPolicy == CompletionPolicy.DEFER_ON_RING ->
                action(actions, "下载并上传", !state.busy) { flow.resumeRingTransfer() }
            else -> {
                action(actions, "保存并上传", !state.busy) { submitFinish(true) }
                ui.button(actions, "暂存到戒指", tag = "finish_defer") { submitFinish(false) }.apply {
                    isEnabled = !state.busy
                }
            }
        }
        ui.button(actions, "放弃本段", tag = "finish_discard") { showDiscardConfirmation() }.apply {
            isEnabled = !state.busy
            background = ui.linkBackground()
            minHeight = ui.dp(48); minimumHeight = ui.dp(48)
        }
    }

    private fun submitFinish(uploadNow: Boolean) {
        rememberDrafts()
        if (current?.session?.reference != null) {
            hideKeyboard()
            if (current?.session?.completionPolicy != null) flow.retry()
            else flow.chooseFinish(uploadNow)
            return
        }
        if (stepsDraft.isBlank()) {
            showRequiredFinishInput(stepsInput, "请先填写计步器总步数")
            return
        }
        hideKeyboard()
        flow.finalizeSession(uploadNow, stepsDraft, "valid", "")
    }

    private fun referenceInput(parent: LinearLayout) {
        ui.text(parent, "计步器总步数", 14f, muted = true)
        ui.gap(parent, 10)
        stepsInput = ui.input(parent, "填写步数", "flow_steps", numeric = true).apply { setText(stepsDraft) }
        finishInputError = ui.text(parent, "", 14f).apply {
            tag = "finish_input_error"
            setTextColor(ui.error)
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        stepsInput?.doAfterTextChanged {
            if (!it.isNullOrBlank()) {
                finishInputError?.visibility = View.GONE
            }
        }
    }

    private fun showRequiredFinishInput(field: EditText?, message: String) {
        finishInputError?.apply {
            val input = field?.parent as? LinearLayout
            if (input != null) {
                (parent as? ViewGroup)?.removeView(this)
                input.addView(this, input.indexOfChild(field) + 1)
            }
            text = message
            visibility = View.VISIBLE
        }
        field?.apply {
            requestFocus()
            getSystemService(InputMethodManager::class.java).showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
            post { revealRequiredFinishInput(this) }
        }
    }

    private fun revealRequiredFinishInput(field: EditText) {
        val errorBottom = finishInputError?.takeIf { it.parent === field.parent }?.bottom ?: field.bottom
        field.requestRectangleOnScreen(android.graphics.Rect(0, 0, field.width,
            maxOf(field.height, errorBottom - field.top)), true)
    }

    private fun showDiscardConfirmation() {
        val confirmation = AlertDialog.Builder(this).setTitle("放弃本段？")
            .setMessage("删除本段记录，不会上传。此操作无法撤销。")
            .setNegativeButton("返回", null).setPositiveButton("确认放弃", null).create()
        dialog = confirmation
        confirmation.setOnShowListener {
            confirmation.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!confirmation.isShowing) return@setOnClickListener
                confirmation.dismiss()
                showingRecords = false
                flow.discardSession()
            }
        }
        confirmation.show()
    }

    private fun reference(body: LinearLayout, footer: LinearLayout, state: CollectionFlowState) {
        title(body, "记录计步器读数", "结束确认恢复后，App会继续保存本段。")
        val card = ui.card(body)
        val uncertain = state.session?.stopConfirmedAtMs == null
        referenceInput(card)
        if (uncertain) { ui.gap(body, 14); ui.text(body, "戒指仍在确认结束，读数会先保存在手机。", 14f, muted = true) }
        action(footer, "保存读数", !state.busy) {
            rememberDrafts()
            if (stepsDraft.isBlank()) showRequiredFinishInput(stepsInput, "请先填写计步器总步数")
            else {
                hideKeyboard()
                flow.saveReference(stepsDraft, if (uncertain) "unreliable" else "valid",
                    if (uncertain) "戒指停止尚未确认时记录的计步器读数" else "")
            }
        }
        if (uncertain) ui.button(body, "返回结束确认", tag = "reference_resume_stop") {
            rememberDrafts(); hideKeyboard(); flow.retry()
        }.background = ui.linkBackground()
    }

    private fun showReferenceCorrection(record: FlowRecordSummary) {
        val status = if (record.referenceStatus == "unreliable") "unreliable" else "valid"
        val reasonText = if (status == "unreliable") record.referenceReason.orEmpty() else ""
        val content = ui.column(padding = 20)
        val steps = ui.input(content, "填写步数", "edit_reference_steps", numeric = true).apply {
            setText(record.steps?.toString().orEmpty())
        }
        val editor = AlertDialog.Builder(this).setTitle("本次计步器读数")
            .setView(content).setNegativeButton("取消", null).setPositiveButton("保存修改", null).create()
        dialog = editor
        editor.setOnShowListener {
            editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val stepsText = steps.text.toString()
                val target = runCatching {
                    sessionReferenceFromInput(stepsText, status, reasonText, System.currentTimeMillis())
                }.onFailure { error ->
                    steps.error = error.message ?: "请核对读数"
                }.getOrNull() ?: return@setOnClickListener
                if (record.referenceStatus == target.status.wireValue && record.steps == target.steps &&
                    record.referenceReason == target.reason) {
                    editor.dismiss()
                    return@setOnClickListener
                }
                val pending = PendingReferenceRevision(record.sessionId, target, editor, steps)
                pendingReferenceRevision = pending
                editor.setCancelable(false)
                editor.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                editor.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                    isEnabled = false
                    text = "正在保存…"
                }
                flow.reviseReference(record.sessionId, stepsText, status, reasonText)
                handler.postDelayed({
                    if (pendingReferenceRevision === pending && editor.isShowing) {
                        editor.setCancelable(true)
                        editor.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                        editor.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                            isEnabled = true
                            text = "保存修改"
                        }
                        steps.error = "保存时间较长，请重试"
                    }
                }, 10_000)
            }
        }
        editor.show()
    }

    private fun resolveReferenceRevision(state: CollectionFlowState) {
        val pending = pendingReferenceRevision ?: return
        val record = state.records.singleOrNull { it.sessionId == pending.sessionId }
        if (record?.referenceStatus == pending.target.status.wireValue && record.steps == pending.target.steps &&
            record.referenceReason == pending.target.reason) {
            pendingReferenceRevision = null
            if (pending.editor.isShowing) pending.editor.dismiss()
            if (dialog === pending.editor) dialog = null
            Toast.makeText(this, "步数已更新", Toast.LENGTH_SHORT).show()
            return
        }
        val error = state.error ?: return
        pendingReferenceRevision = null
        if (!pending.editor.isShowing) return
        pending.editor.setCancelable(true)
        pending.editor.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
        pending.editor.getButton(AlertDialog.BUTTON_POSITIVE).apply {
            isEnabled = true
            text = "保存修改"
        }
        pending.steps.error = displayError(error) ?: "保存未完成，请重试"
    }

    private fun recovery(body: LinearLayout, footer: LinearLayout, state: CollectionFlowState) {
        val session = state.session?.takeIf { it.isPending }
        val abortedStopConfirmed = session?.startAbort?.stoppedObservation != null
        val needsStop = session != null && session.stopConfirmedAtMs == null && !abortedStopConfirmed
        val unconfirmedStart = session?.phase == FreeLivingSessionPhase.START_REQUESTED
        title(body, when {
            state.canStopUnconfirmedStart -> "正在安全结束"
            abortedStopConfirmed -> "正在保存数据"
            session?.startAbort != null -> "正在确认停止"
            unconfirmedStart -> "正在确认开始"
            needsStop -> "正在恢复本次记录"
            else -> "正在恢复"
        }, when {
            state.canStopUnconfirmedStart -> "App会结束戒指记录并保存可用数据。"
            abortedStopConfirmed -> "戒指已停止，连接恢复后继续保存。"
            needsStop -> "将戒指靠近手机，App会继续同一条记录。"
            else -> "本次内容已保留。"
        })
        displayError(state.error)?.let { message ->
            ui.text(body, message, 15f).apply {
                tag = "flow_recovery_message"
                setTextColor(ui.error)
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            ui.gap(body, 16)
        }
        val card = ui.card(body)
        if (session == null) detail(card, "戒指", connectionLabel(state)) else {
            detail(card, "本次记录", "已保留")
            if (abortedStopConfirmed) {
                ui.gap(card, 18)
                detail(card, "戒指", "已停止")
            } else if (!unconfirmedStart) {
                ui.gap(card, 18)
                detail(card, "计步器读数", session.reference?.steps?.let { "$it 步" }
                    ?: if (session.reference != null) "已记录原因" else "待填写")
            }
        }
        if (state.canRecordReferenceLocally) {
            ui.button(body, "继续收尾", tag = "preserve_reference") { flow.enterFinish() }
        } else if (session?.phase == FreeLivingSessionPhase.STOP_REQUESTED && state.referenceStatus == null) {
            ui.button(body, "记录计步器读数", tag = "preserve_reference") { flow.enterReference() }
        }
        if (state.canEndStartAttempt && !state.canStopUnconfirmedStart) {
            ui.button(body, "结束本次尝试", tag = "end_start_attempt") { showEndStartAttempt() }
        }
        val retryLabel = if (!state.connected) "重新连接" else "继续恢复"
        val retryEnabled = state.canRetry && !state.busy && !state.connecting
        val retry = {
            if (!state.connected) flow.reconnect() else flow.retry()
        }
        if (state.canStopUnconfirmedStart) {
            action(footer, "停止并保留数据", !state.busy) { flow.stop() }
        } else if (retryEnabled) action(footer, retryLabel, block = retry)
        if (session == null && !state.isSimulation && !state.busy && !RealCollectionBridge.isRunning()) {
            ui.button(body, "检查用户与戒指", tag = "recovery_settings") { openPreparationSettings() }
        }
    }

    private fun showEndStartAttempt() {
        val confirmation = AlertDialog.Builder(this).setTitle("结束本次？")
            .setMessage("App会先核对戒指状态，并保留已经产生的数据。")
            .setNegativeButton("继续等待", null).setPositiveButton("结束本次", null).create()
        dialog = confirmation
        confirmation.setOnShowListener {
            confirmation.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!confirmation.isShowing) return@setOnClickListener
                confirmation.dismiss()
                flow.endStartAttempt("用户选择结束未确认的开始请求")
            }
        }
        confirmation.show()
    }

    private fun detail(parent: LinearLayout, key: String, value: String) {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        parent.addView(row, LinearLayout.LayoutParams(-1, -2))
        ui.text(row, key, 14f, muted = true).layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        ui.text(row, value, 16f, bold = true).apply {
            tag = "flow_detail_$key"
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, -2, 1.2f)
        }
    }

    private fun connectionLabel(state: CollectionFlowState): String = when {
        state.connecting -> "正在连接"
        !state.connected -> "未连接"
        state.preservingExisting -> "正在准备"
        state.checkingDevice -> "正在检查"
        state.session?.isPending == true -> "已连接"
        state.canStart -> "可以开始"
        else -> "需要检查"
    }

    private fun openPreparationSettings() {
        val state = current ?: return
        if (state.isSimulation) return
        if (state.session?.isPending == true) {
            dialog?.dismiss()
            dialog = AlertDialog.Builder(this)
                .setTitle("用户与戒指")
                .setMessage(listOf(
                    "用户名：${state.participantLabel.ifBlank { state.participantId }}",
                    "戒指：${state.ringName.ifBlank { "已选戒指" }}",
                    "佩戴位置：${state.placement?.displayName ?: "待选择"}",
                    "当前记录完成前，设置暂不可修改",
                    "应用版本：${BuildConfig.VERSION_NAME}",
                ).joinToString("\n"))
                .setPositiveButton("关闭", null)
                .show()
            return
        }
        startActivity(Intent(this, StepPreparationActivity::class.java)
            .putExtra(StepPreparationActivity.EXTRA_OPEN_SETTINGS, true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    private fun displayError(message: String?): String? = message?.let { raw ->
        when {
            raw.contains("权限") -> "请允许蓝牙权限后重试。"
            raw.contains("没有进展") -> "接收暂时中断，已保存进度。请将戒指靠近手机后重新连接。"
            raw.contains("仍在整理") -> "数据仍在整理，已保存当前进度，请稍后点击重试。"
            raw.contains("蓝牙已关闭") -> "请打开手机蓝牙后重试。"
            raw.contains("空间") -> "手机存储空间不足，请清理空间后重试。"
            raw.contains("-16") || raw.contains("充电") -> "请将戒指取出充电盒，等待 10 秒后重新检查。"
            raw.contains("仍在采集") || raw.contains("仍在记录") -> "戒指仍在记录，请结束并保存当前数据。"
            raw.contains("指纹") || raw.contains("校验") || raw.contains("不完整") ->
                "数据检查未完成，请重试。"
            raw.contains("连接") || raw.contains("超时") || raw.contains("靠近") -> "请将戒指靠近手机后重试。"
            raw.contains("上传") || raw.contains("网络") -> "数据已保存在手机，联网后可继续上传。"
            raw.contains("下载") || raw.contains("文件") -> "数据下载未完成，请重试。"
            raw.contains("未保存") || raw.contains("保存失败") -> "步数未保存，请重试。"
            raw.contains("身份") || raw.contains("用户") || raw.contains("佩戴") -> "请检查用户名与戒指设置。"
            raw.contains("联系研究者") -> "本次记录需要检查，请重试。"
            else -> "暂时无法完成，请重试。"
        }
    }

    private fun formatDateTime(atMs: Long?, timeZoneId: String?): String? = atMs?.takeIf { it > 0 }?.let {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).apply {
            timeZone = runCatching { timeZoneId?.let(TimeZone::getTimeZone) }.getOrNull() ?: TimeZone.getDefault()
        }.format(Date(it))
    }

    private fun showOptions() {
        val choices = arrayOf("正常流程", "开始确认超时", "结束确认超时", "保存失败一次", "下载失败一次", "上传失败一次", "模拟断开连接", "恢复连接")
        dialog = AlertDialog.Builder(this).setTitle("演示选项")
            .setItems(choices) { _, index ->
                when (index) {
                    6 -> flow.disconnect()
                    7 -> flow.reconnect()
                    else -> flow.setFault(FlowTestFault.entries[index])
                }
            }.setNegativeButton("返回", null).show()
    }

    private fun updateElapsed() {
        val started = current?.session?.startConfirmedAtMs ?: return
        val seconds = ((System.currentTimeMillis() - started) / 1000).coerceAtLeast(0)
        elapsedLabel?.text = String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    }

    private fun startDateTime(session: FreeLivingSession?): String = session?.startConfirmedAtMs?.let { atMs ->
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone(session.timeZoneId)
        }.format(Date(atMs))
    } ?: "—"
    private fun hideKeyboard() = getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(currentFocus?.windowToken, 0)
}
