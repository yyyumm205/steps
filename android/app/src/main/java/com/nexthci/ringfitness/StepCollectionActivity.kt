package com.nexthci.ringfitness

import android.app.Activity
import android.app.AlertDialog
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
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
    private var reasonInput: EditText? = null
    private var participantInput: EditText? = null
    private var placementPicker: Spinner? = null
    private var elapsedLabel: TextView? = null
    private var pageScroll: ScrollView? = null
    private var imeWasVisible = false
    private var stepsDraft = ""
    private var reasonDraft = ""
    private var participantDraft = ""
    private var placementDraft = 0
    private var referenceKind = "valid"
    private var draftSessionId: String? = null
    private var dialog: AlertDialog? = null
    private val ticker = object : Runnable {
        override fun run() { updateElapsed(); handler.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        ui = QuietUi(this)
        flow = provideFlow()
        stepsDraft = savedInstanceState?.getString("steps").orEmpty()
        reasonDraft = savedInstanceState?.getString("reason").orEmpty()
        participantDraft = savedInstanceState?.getString("participant").orEmpty()
        placementDraft = savedInstanceState?.getInt("placement") ?: 0
        referenceKind = savedInstanceState?.getString("reference_kind") ?: "valid"
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
        outState.putString("reason", reasonDraft)
        outState.putString("participant", participantDraft)
        outState.putInt("placement", placementDraft)
        outState.putString("reference_kind", referenceKind)
        outState.putString("draft_session", draftSessionId)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Legacy Android back callback")
    override fun onBackPressed() = back()

    private fun back() {
        if (current?.page == CollectionPage.HOME) finish() else flow.home()
    }

    private fun rememberDrafts() {
        stepsInput?.let { stepsDraft = it.text.toString() }
        reasonInput?.let { reasonDraft = it.text.toString() }
        participantInput?.let { participantDraft = it.text.toString() }
        placementPicker?.let { placementDraft = it.selectedItemPosition }
    }

    private fun render(state: CollectionFlowState) {
        rememberDrafts()
        val old = current
        current = state
        if (state.session?.sessionId != null && draftSessionId != state.session.sessionId) {
            draftSessionId = state.session.sessionId
            stepsDraft = ""
            reasonDraft = ""
            referenceKind = "valid"
        }
        // State updates unrelated to the form must not steal focus or replace a user's input.
        if (old == state) return
        if (state.page == CollectionPage.REFERENCE && old?.page == CollectionPage.REFERENCE &&
            old.copy(records = state.records, fault = state.fault) == state) return
        stepsInput = null; reasonInput = null; participantInput = null; placementPicker = null; elapsedLabel = null
        val root = ui.column().apply {
            setBackgroundColor(ui.background)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                val imeVisible = insets.isVisible(WindowInsets.Type.ime())
                if (imeWasVisible && !imeVisible) pageScroll?.post { pageScroll?.scrollTo(0, 0) }
                imeWasVisible = imeVisible
                insets
            }
        }
        setContentView(root)
        val header = ui.column(root, 20).apply { setPadding(ui.dp(20), ui.dp(4), ui.dp(20), ui.dp(4)) }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(row)
        val returnLabel = when {
            state.page != CollectionPage.HOME -> "首页"
            state.isSimulation -> "退出演示"
            else -> "返回"
        }
        val returnButton = ui.button(row, returnLabel, tag = "flow_back") { back() }
        returnButton.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        returnButton.minHeight = ui.dp(48); returnButton.minimumHeight = ui.dp(48)
        returnButton.background = ui.linkBackground()
        returnButton.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        if (state.isSimulation) {
            val options = ui.button(row, "演示选项", tag = "flow_options") { showOptions() }
            options.layoutParams = LinearLayout.LayoutParams(-2, -2)
            options.minHeight = ui.dp(48); options.minimumHeight = ui.dp(48)
            options.background = ui.linkBackground()
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
        if (state.error != null && !(state.page == CollectionPage.HOME && homeTask(state).hint == state.error)) {
            ui.text(footer, state.error, 14f).apply {
                tag = "flow_error"
                setTextColor(ui.error)
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
        }
        if (!state.hasProfile && state.busy) {
            title(body, "正在读取记录")
            body.addView(ui.progress(), LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply { gravity = Gravity.CENTER })
            action(footer, "请稍等…", false) {}
        }
        else if (!state.hasProfile) registration(body, footer, state)
        else when (state.page) {
            CollectionPage.HOME -> home(body, footer, state)
            CollectionPage.STARTING, CollectionPage.STOPPING, CollectionPage.SAVING -> waiting(body, footer, state)
            CollectionPage.COLLECTING -> collecting(body, footer, state)
            CollectionPage.REFERENCE -> reference(body, footer, state)
            CollectionPage.DOWNLOADING, CollectionPage.UPLOADING, CollectionPage.COMPLETE -> transfer(body, footer, state)
            CollectionPage.RECOVERY, CollectionPage.ERROR -> recovery(body, footer, state)
        }
    }

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
        ui.text(card, "被试编号", bold = true)
        ui.gap(card, 12)
        participantInput = ui.input(card, if (state.isSimulation) "例如 demo001" else "填写被试编号", "flow_participant").apply { setText(participantDraft) }
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
        title(body, task.title)
        val ready = ui.card(body, if (state.canStart) ui.statusSurface else ui.surface)
        ui.text(ready, task.status, 24f, bold = true).tag = "home_task_status"
        task.hint?.let { ui.gap(ready, 12); ui.text(ready, it) }
        val profile = ui.card(body)
        detail(profile, "被试编号", state.participantId)
        ui.gap(profile, 18)
        detail(profile, "佩戴位置", state.placement?.displayName ?: "待填写")
        ui.gap(profile, 18)
        detail(profile, "戒指", connectionLabel(state))
        val localRecords = state.records.filter { it.localComplete }
        val recentUploaded = localRecords.filter { it.transferStatus == "complete" }.takeLast(3).map { it.sessionId }.toSet()
        val recentRecords = localRecords.filter { it.transferStatus != "complete" || it.sessionId in recentUploaded }.reversed()
        if (recentRecords.isNotEmpty()) {
            ui.text(body, "采集记录", 16f, bold = true)
            ui.gap(body, 12)
            recentRecords.forEach { record ->
                val card = ui.card(body)
                ui.text(card, record.steps?.let { "$it 步" } ?: "未提供读数", 22f, bold = true)
                ui.gap(card, 8)
                ui.text(card, when {
                    record.localReviewRequired -> "戒指数据需要检查"
                    record.transferStatus == "complete" -> if (state.isSimulation) "已保存 · 模拟上传完成" else "已上传"
                    record.transferInFlight -> "已保存 · 正在上传"
                    record.transferStatus in setOf("failed", "transferring") -> "已保存 · 上传待重试"
                    else -> "已保存 · 等待上传"
                }, 14f, muted = true).tag = "record_status_${record.sessionId}"
                if (record.referenceStatus == "unreliable") { ui.gap(card, 8); ui.text(card, "读数有异常", 14f, muted = true) }
                if (state.uploadAvailable && !record.transferInFlight && record.transferStatus != "complete") {
                    ui.button(card, when {
                        record.localReviewRequired -> "重新检查"
                        record.transferStatus == "pending" -> "上传记录"
                        else -> "重试上传"
                    },
                        tag = "retry_upload_${record.sessionId}") { flow.retryUpload(record.sessionId) }
                }
            }
        }
        action(footer, task.action, !state.busy && !state.connecting) {
            when {
                !state.connected -> flow.reconnect()
                state.canStart -> flow.start()
                state.taskPage == CollectionPage.REFERENCE -> flow.enterReference()
                else -> flow.retry()
            }
        }
    }

    private data class HomeTask(val title: String, val status: String, val action: String, val hint: String? = null)

    private fun homeTask(state: CollectionFlowState): HomeTask = when {
        state.connecting -> HomeTask("本次采集", "正在连接戒指", "连接中…", "请将戒指放在手机附近。")
        !state.connected -> HomeTask("本次采集", "戒指连接中断", "重新连接", "请将戒指放在手机附近。")
        state.preservingExisting -> HomeTask("准备戒指", "正在保存已有数据", "保存中…", "保存完成后即可开始。")
        state.checkingDevice -> HomeTask("采集准备", "正在检查戒指", "检查中…")
        state.canStart -> HomeTask("开始这一段", "采集准备", "开始采集", "佩戴好设备，站定后将计步器清零。")
        state.session == null -> HomeTask("采集准备", "戒指暂未就绪", "重新检查", state.error)
        else -> when (state.taskPage) {
            CollectionPage.STARTING -> HomeTask("本次采集", "正在确认开始", "查看进度", "请站定等候。")
            CollectionPage.COLLECTING -> HomeTask("采集进行中", "正在采集", "查看采集")
            CollectionPage.STOPPING -> HomeTask("本次采集", "正在确认结束", "查看进度", "请站定等候。")
            CollectionPage.REFERENCE -> if (state.session?.stopConfirmedAtMs != null)
                HomeTask("待填写步数", "采集已结束", "填写步数", "填写计步器显示的本次总数。")
            else HomeTask("本次采集", "结束状态待确认", "先填写步数", "可以先填写计步器读数。")
            CollectionPage.SAVING -> HomeTask("正在保存步数", "正在保存", "查看进度")
            CollectionPage.DOWNLOADING -> HomeTask("正在下载数据", "步数已保存", "查看下载")
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

    private fun collecting(body: LinearLayout, footer: LinearLayout, state: CollectionFlowState) {
        title(body, "正在采集")
        val card = ui.card(body, ui.statusSurface)
        ui.text(card, "本次记录时长", 14f)
        ui.gap(card, 18)
        elapsedLabel = ui.text(card, "00:00:00", 44f, bold = true).apply {
            tag = "flow_elapsed"
            maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(24, 44, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        ui.gap(card, 24)
        ui.text(card, "开始时间", 14f)
        ui.gap(card, 8)
        ui.text(card, startDateTime(state.session), 18f, bold = true).tag = "flow_started_at"
        val device = ui.card(body)
        detail(device, "戒指", connectionLabel(state))
        ui.gap(device, 16)
        detail(device, "佩戴位置", state.placement?.displayName ?: "—")
        ui.text(footer, "站定后结束，再查看计步器读数。", 14f, muted = true)
        action(footer, "结束采集", state.canStop && !state.busy) { flow.stop() }
        updateElapsed()
    }

    private fun waiting(body: LinearLayout, footer: LinearLayout, state: CollectionFlowState) {
        val heading = when (state.page) {
            CollectionPage.STARTING -> "正在开始"
            CollectionPage.STOPPING -> "正在结束"
            else -> "正在保存"
        }
        title(body, heading, when (state.page) {
            CollectionPage.STARTING -> "请稍等，确认开始后再活动。"
            CollectionPage.STOPPING -> "请保持站定，等待戒指停止。"
            else -> "正在把本次读数保存在手机。"
        })
        val card = ui.card(body)
        card.addView(ui.progress(), LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply { gravity = Gravity.CENTER })
        action(footer, "$heading…", false) {}
    }

    private fun reference(body: LinearLayout, footer: LinearLayout, state: CollectionFlowState) {
        title(body, "本次走了多少步？", "等计步器数字稳定后，填写显示的总数。")
        val card = ui.card(body)
        val uncertain = state.session?.stopConfirmedAtMs == null
        if (uncertain && referenceKind == "valid") referenceKind = "unreliable"
        if (referenceKind != "missing") {
            ui.text(card, "计步器总步数", 14f, muted = true)
            ui.gap(card, 12)
            stepsInput = ui.input(card, "填写步数", "flow_steps", numeric = true).apply { setText(stepsDraft) }
        } else ui.text(card, "本次无法提供读数", 20f, bold = true)
        if (referenceKind != "valid") {
            ui.gap(card, 20)
            ui.text(card, "简单说明原因", 14f, muted = true)
            ui.gap(card, 10)
            reasonInput = ui.input(card, "例如：计步器意外清零", "flow_reason").apply {
                setSingleLine(false); maxLines = 3; setText(reasonDraft)
            }
        }
        ui.button(body, when (referenceKind) {
            "missing" -> "改为填写步数"
            "unreliable" -> "读数有异常 · 修改"
            else -> "读数有问题？"
        }, tag = "reference_options") { showReferenceOptions(uncertain) }
        if (uncertain) { ui.gap(body, 14); ui.text(body, "还未确认结束，可以先记下读数。", 14f, muted = true) }
        action(footer, if (state.uploadAvailable) "保存并上传" else "保存本次记录", !state.busy) {
            rememberDrafts(); hideKeyboard()
            flow.saveReference(stepsDraft, referenceKind, if (referenceKind == "valid") "" else reasonDraft)
        }
    }

    private fun transfer(body: LinearLayout, footer: LinearLayout, state: CollectionFlowState) {
        val complete = state.page == CollectionPage.COMPLETE
        val session = state.session
        val reference = session?.reference
        val localReviewRequired = state.records.any { it.sessionId == session?.sessionId && it.localReviewRequired }
        val localComplete = session?.localData?.files?.let { files ->
            files.isNotEmpty() && files.all { it.simulated == state.isSimulation }
        } == true
        val uploaded = !localReviewRequired && localComplete && session?.transfer?.status == SessionTransferStatus.COMPLETE &&
            session.transfer.receipt?.let { it.sessionId == session.sessionId && it.simulated == state.isSimulation } == true
        val uploadInFlight = localComplete && (state.records.any { it.sessionId == session?.sessionId && it.transferInFlight } ||
            state.isSimulation && state.page == CollectionPage.UPLOADING)
        title(body, when {
            localReviewRequired -> "戒指数据需要检查"
            complete && localComplete -> "这一段已保存"
            complete -> "记录尚未完整保存"
            else -> "正在整理记录"
        }, when {
            localReviewRequired -> "步数已保存，请重新检查文件；仍未成功时联系研究者。"
            localComplete -> "本次记录已经保存在手机。"
            reference != null -> "步数已保存，请稍等片刻。"
            else -> "正在核对本次记录。"
        })
        val summary = ui.card(body, ui.statusSurface)
        ui.text(summary, reference?.steps?.let { "$it 步" } ?: if (reference != null) "未提供读数" else "待保存读数",
            36f, bold = true).tag = "saved_reference"
        ui.gap(summary, 14)
        ui.text(summary, if (reference?.status == ReferenceStatus.UNRELIABLE) "已附上异常说明" else "本次计步器读数", 14f)
        val progress = ui.card(body)
        detail(progress, "计步器读数", if (reference != null) "已保存" else "待保存")
        ui.gap(progress, 20)
        detail(progress, "戒指数据", when {
            localReviewRequired -> "待检查"
            localComplete -> "已保存"
            state.page == CollectionPage.DOWNLOADING -> "正在下载…"
            else -> "待下载"
        })
        ui.gap(progress, 20)
        detail(progress, if (state.isSimulation) "模拟上传" else "上传", when {
            localReviewRequired -> "等待文件检查"
            uploaded -> "已完成"
            !state.uploadAvailable -> "待上传"
            uploadInFlight -> "正在上传…"
            localComplete && session?.transfer?.status in setOf(SessionTransferStatus.FAILED, SessionTransferStatus.TRANSFERRING) -> "上传待重试"
            localComplete -> "待上传"
            else -> "等待下载完成"
        })
        if (state.uploadAvailable && localComplete && !uploaded && !uploadInFlight && session != null) {
            ui.button(progress, when {
                localReviewRequired -> "重新检查"
                session.transfer.status == SessionTransferStatus.PENDING -> "上传记录"
                else -> "重试上传"
            },
                tag = "retry_upload_${session.sessionId}") { flow.retryUpload(session.sessionId) }
        }
        if (!complete || uploadInFlight) progress.addView(ui.progress(), LinearLayout.LayoutParams(ui.dp(28), ui.dp(28)).apply {
            topMargin = ui.dp(20); gravity = Gravity.CENTER
        })
        action(footer, "返回首页") { flow.home() }
    }

    private fun recovery(body: LinearLayout, footer: LinearLayout, state: CollectionFlowState) {
        val session = state.session?.takeIf { it.isPending }
        val needsStop = session != null && session.stopConfirmedAtMs == null
        val unconfirmedStart = session?.phase == FreeLivingSessionPhase.START_REQUESTED
        title(body, when {
            session == null && state.connected -> "戒指暂未就绪"
            session == null -> "连接尚未完成"
            unconfirmedStart -> "开始待确认"
            needsStop -> "还需要确认一下"
            else -> "这一步未完成"
        }, when {
            session == null && state.connected -> null
            session == null -> "请将戒指放在手机附近后重试。"
            needsStop -> "连接恢复后，重新检查戒指状态。"
            else -> "已保存的内容会保留，可以重试。"
        })
        val card = ui.card(body)
        if (session == null) detail(card, "戒指", connectionLabel(state)) else {
            detail(card, "本次记录", "已保留")
            if (!unconfirmedStart) {
                ui.gap(card, 18)
                detail(card, "计步器读数", session.reference?.steps?.let { "$it 步" }
                    ?: if (session.reference != null) "已记录原因" else "待填写")
            }
        }
        if (session?.phase == FreeLivingSessionPhase.STOP_REQUESTED && state.referenceStatus == null) {
            ui.button(body, "先记下步数", tag = "preserve_reference") { flow.enterReference() }
        }
        if (state.canEndStartAttempt) {
            ui.button(body, "结束本次尝试", tag = "end_start_attempt") { showEndStartAttempt() }
        }
        action(footer, if (state.connecting) "连接中…" else if (!state.connected) "重新连接" else if (needsStop) "重新检查" else "重试",
            state.canRetry && !state.busy && !state.connecting) {
            if (!state.connected) flow.reconnect() else flow.retry()
        }
    }

    private fun showEndStartAttempt() {
        val input = EditText(this).apply {
            hint = "填写简短原因"
            tag = "end_start_attempt_reason"
            filters = arrayOf(android.text.InputFilter.LengthFilter(200))
        }
        val confirmation = AlertDialog.Builder(this).setTitle("结束本次尝试")
            .setMessage("将重新检查戒指，并保存这次尝试的原因。")
            .setView(input).setNegativeButton("返回", null).setPositiveButton("确认", null).create()
        dialog = confirmation
        confirmation.setOnShowListener {
            confirmation.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!confirmation.isShowing) return@setOnClickListener
                val reason = input.text.toString().trim()
                if (reason.isBlank()) input.error = "请填写原因" else {
                    confirmation.dismiss()
                    flow.endStartAttempt(reason)
                }
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
        state.connecting -> "正在连接…"
        state.connected -> "已连接"
        else -> "连接已中断"
    }

    private fun showReferenceOptions(uncertain: Boolean) {
        rememberDrafts()
        val labels = if (uncertain) arrayOf("数字可能不准确", "无法提供读数") else arrayOf("读数正常", "数字可能不准确", "无法提供读数")
        val values = if (uncertain) listOf("unreliable", "missing") else listOf("valid", "unreliable", "missing")
        dialog = AlertDialog.Builder(this).setTitle("计步器读数")
            .setSingleChoiceItems(labels, values.indexOf(referenceKind)) { d, index ->
                referenceKind = values[index]; d.dismiss()
                val state = requireNotNull(current); current = null; render(state)
            }.setNegativeButton("返回", null).show()
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
