package com.nexthci.ringfitness

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsService
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

class MainActivity : Activity(), RingCaptureService.UiListener {
    private enum class Page { LOGIN, RING, MODE, SLEEP_COLLECTION, ACTIVITY_COLLECTION }

    private lateinit var root: LinearLayout
    private lateinit var profileStore: ParticipantProfileStore
    private lateinit var uploadStore: UploadSessionStore
    private val io = Executors.newSingleThreadExecutor()
    private val csvIo = Executors.newFixedThreadPool(2)
    private var service: RingCaptureService? = null
    private var serviceState: CaptureServiceState? = null
    private var rings: List<ScannedRing> = emptyList()
    private var selectedRing: ScannedRing? = null
    private var page = Page.LOGIN
    private var loginBusy = false
    private var ouraBusy = false
    private var ouraConnected = false
    private var useOura = true
    private var usePolar = false
    private var useActivityPolar = false
    private var selectedActivity: DailyActivity? = null
    private var selectedRingPlacement: RingPlacement? = null
    private var waitingForRingConnection = false
    private var bound = false
    private var subjectiveFeedbackDialog: AlertDialog? = null
    private val csvExportProgress = java.util.concurrent.ConcurrentHashMap<String, Double>()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as RingCaptureService.LocalBinder).service().also { it.setUiListener(this@MainActivity) }
            bound = true
            val state = serviceState
            if (state != null && hasActiveCapture(state) && page != Page.LOGIN) {
                restoreCollectionModes(state)
                page = collectionPageFor(state)
            } else if (page == Page.RING) {
                service?.resetRingSelection()
            }
            render()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            service = null
            serviceState = null
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profileStore = ParticipantProfileStore(this)
        uploadStore = UploadSessionStore(this)
        page = if (profileStore.get() == null || !profileStore.isCurrentBuildConfirmed()) {
            Page.LOGIN
        } else {
            savedInstanceState?.getString(STATE_PAGE)
                ?.let { saved -> runCatching { Page.valueOf(saved) }.getOrNull() }
                ?: Page.RING
        }
        useOura = savedInstanceState?.getBoolean(STATE_USE_OURA, true) ?: true
        usePolar = savedInstanceState?.getBoolean(STATE_USE_POLAR, false) ?: false
        useActivityPolar = savedInstanceState?.getBoolean(STATE_USE_ACTIVITY_POLAR, false) ?: false
        selectedActivity = savedInstanceState?.getString(STATE_SELECTED_ACTIVITY)
            ?.let(DailyActivity::fromWireValue)
        selectedRingPlacement = savedInstanceState?.getString(STATE_SELECTED_RING_PLACEMENT)
            ?.let(RingPlacement::fromWireValue)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }
        setContentView(ScrollView(this).apply { addView(root) })
        requestRuntimePermissions()
        val captureServiceIntent = Intent(this, RingCaptureService::class.java)
        // Keep the capture service in the started state as well as bound. Once a
        // session begins it promotes itself to a connected-device foreground
        // service, so leaving or swiping away the Activity does not stop BLE I/O.
        startService(captureServiceIntent)
        bindService(captureServiceIntent, connection, Context.BIND_AUTO_CREATE)
        handleDeepLink(intent)
        render()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    override fun onDestroy() {
        subjectiveFeedbackDialog?.dismiss()
        subjectiveFeedbackDialog = null
        if (bound) {
            service?.setUiListener(null)
            unbindService(connection)
        }
        io.shutdownNow()
        csvIo.shutdownNow()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_PAGE, page.name)
        outState.putBoolean(STATE_USE_OURA, useOura)
        outState.putBoolean(STATE_USE_POLAR, usePolar)
        outState.putBoolean(STATE_USE_ACTIVITY_POLAR, useActivityPolar)
        selectedActivity?.let { outState.putString(STATE_SELECTED_ACTIVITY, it.wireValue) }
        selectedRingPlacement?.let { outState.putString(STATE_SELECTED_RING_PLACEMENT, it.wireValue) }
        super.onSaveInstanceState(outState)
    }

    override fun onBackPressed() {
        when (page) {
            Page.SLEEP_COLLECTION,
            Page.ACTIVITY_COLLECTION,
            -> {
                if (serviceState?.let(::hasActiveCapture) == true) {
                    toast("采集期间不能返回，请先结束本次采集")
                } else {
                    page = Page.MODE
                    render()
                }
            }
            Page.MODE -> returnToRingConnection()
            Page.RING -> super.onBackPressed()
            Page.LOGIN -> super.onBackPressed()
        }
    }

    override fun onServiceState(state: CaptureServiceState) {
        runOnUiThread {
            serviceState = state
            if (page != Page.LOGIN && profileStore.get() != null && hasActiveCapture(state)) {
                restoreCollectionModes(state)
                page = collectionPageFor(state)
            }
            if (waitingForRingConnection && state.ready) {
                enterModeSelectionPage()
            }
            render()
            if (state.subjectiveFeedbackRequired && page != Page.LOGIN && profileStore.get() != null) {
                showSubjectiveFeedback(state)
            } else {
                subjectiveFeedbackDialog?.dismiss()
                subjectiveFeedbackDialog = null
            }
        }
    }

    override fun onRingsFound(rings: List<ScannedRing>) {
        runOnUiThread { this.rings = rings; render() }
    }

    override fun onServiceError(message: String) = runOnUiThread { toast(message); render() }

    override fun onCaptureSessionCompleted(session: UploadSessionSummary) = runOnUiThread { render() }

    private fun render() {
        if (!::root.isInitialized) return
        if (isHealthTransportBusy(serviceState)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        root.removeAllViews()
        when (page) {
            Page.LOGIN -> renderLogin()
            Page.RING -> renderRingConnection()
            Page.MODE -> renderModeSelection()
            Page.SLEEP_COLLECTION -> renderCollection()
            Page.ACTIVITY_COLLECTION -> renderActivityCollection()
        }
    }

    private fun renderLogin() {
        title("RingFitness")
        paragraph("请选择新用户注册，或使用已有用户名登录。无需密码。")
        val username = EditText(this).apply {
            hint = "用户名（3–24 位字母或数字）"
            isSingleLine = true
            isEnabled = !loginBusy
        }
        root.addView(username, matchWrap())
        spacer(10)
        val progress = ProgressBar(this).apply { visibility = if (loginBusy) View.VISIBLE else View.GONE }
        root.addView(progress)
        action("老用户登录", enabled = !loginBusy) { submitUsername(username.text.toString(), register = false) }
        action("新用户注册", enabled = !loginBusy) { submitUsername(username.text.toString(), register = true) }
        paragraph("用户名不区分大小写；注册时由云端数据库原子检查重复。")
    }

    private fun submitUsername(raw: String, register: Boolean) {
        if (!ParticipantProfileStore.isValidUsername(raw)) {
            toast(ParticipantProfileStore.USERNAME_RULE)
            return
        }
        val base = BuildConfig.STUDY_AUTHORIZATION_URL.trim()
        val code = BuildConfig.STUDY_ENROLLMENT_CODE.trim()
        if (base.isBlank() || code.isBlank()) {
            toast("尚未配置用户服务")
            return
        }
        loginBusy = true
        render()
        io.execute {
            runCatching {
                if (register) RingFitnessApi.register(base, code, raw, profileStore.installationId)
                else RingFitnessApi.resolve(base, code, raw, profileStore.installationId)
            }.onSuccess { user ->
                val profile = profileStore.createProfile(user.username, user.participantId)
                profileStore.save(profile)
                profileStore.markCurrentBuildConfirmed()
                uploadStore.migrateUploadLink(profile.uploadLink)
                runOnUiThread {
                    loginBusy = false
                    val activeState = serviceState
                    if (activeState != null && hasActiveCapture(activeState)) {
                        restoreCollectionModes(activeState)
                        page = collectionPageFor(activeState)
                    } else {
                        page = Page.RING
                        selectedRing = null
                        rings = emptyList()
                        service?.resetRingSelection()
                    }
                    render()
                }
            }.onFailure { error -> runOnUiThread { loginBusy = false; toast(error.message ?: "登录失败"); render() } }
        }
    }

    private fun renderRingConnection() {
        val profile = profileStore.get()
        if (profile == null) { page = Page.LOGIN; render(); return }
        title("连接戒指")
        paragraph("当前用户：${profile.displayName}")
        action("切换用户") {
            if (serviceState?.healthPhase != HealthPhase.IDLE) toast("采集期间不能切换用户")
            else {
                profileStore.clearCurrentUser()
                OuraAuthorizationStore(this).clear()
                service?.resetRingSelection()
                selectedRing = null
                page = Page.LOGIN
                render()
            }
        }
        action(if (rings.isEmpty()) "搜索戒指" else "重新搜索戒指") {
            selectedRing = null
            waitingForRingConnection = false
            service?.resetRingSelection()
            service?.searchForRings()
        }
        if (rings.isEmpty()) paragraph(serviceState?.connectionMessage ?: "等待搜索附近戒指")
        rings.forEach { ring ->
            val chosen = selectedRing?.address == ring.address
            action("${if (chosen) "✓ " else ""}${ring.name}  (${ring.rssi} dBm)") {
                selectedRing = ring
                waitingForRingConnection = false
                if (service?.selectRingAndReadBattery(ring) != true) {
                    toast("无法读取所选戒指电量，请重新点击")
                }
                render()
            }
        }
        selectedRing?.let { ring ->
            val state = serviceState
            paragraph(
                if (state?.selectedRingAddress == ring.address) state.batteryText
                else "点击戒指后读取电量",
            )
        }
        action(
            if (waitingForRingConnection) "正在连接…" else "连接戒指",
            enabled = selectedRing != null && !waitingForRingConnection,
        ) {
            val selected = selectedRing ?: return@action
            val accepted = service?.confirmRingConnection(selected) == true
            if (!accepted) {
                toast("无法连接所选戒指，请重新搜索")
            } else if (serviceState?.ready == true) {
                enterModeSelectionPage()
                return@action
            } else {
                waitingForRingConnection = true
            }
            render()
        }
    }

    private fun renderModeSelection() {
        val profile = profileStore.get()
        if (profile == null) { page = Page.LOGIN; render(); return }
        val state = serviceState
        if (state != null && hasActiveCapture(state)) {
            page = collectionPageFor(state)
            render()
            return
        }
        title("选择采集模式")
        paragraph("用户：${profile.displayName}")
        paragraph("戒指：${state?.selectedRingName ?: "未连接"}")
        paragraph("固件版本：${if (state?.ready == true) state.firmwareVersionText else "未连接"}")
        action(
            state?.batteryText ?: "查看戒指电量",
            enabled = state?.ready == true && state.batteryBusy != true,
        ) { service?.requestBattery() }
        action("睡眠 / 心率采集", enabled = state?.ready == true) {
            page = Page.SLEEP_COLLECTION
            refreshOuraStatus()
            render()
        }
        action("日常活动采集", enabled = state?.ready == true) {
            page = Page.ACTIVITY_COLLECTION
            render()
        }
        action("返回戒指页面") { returnToRingConnection() }
        uploadStore.anyRingTransferTask()?.let { task ->
            paragraph("戒指中存在 ${task.participantName} 的待传任务；处理完成前两个模式都不能开始新采集。")
            action("处理戒指待传任务") { showRingTransferTask() }
        }
    }

    private fun renderActivityCollection() {
        val profile = profileStore.get()
        if (profile == null) { page = Page.LOGIN; render(); return }
        val state = serviceState
        val active = state?.let(::hasActiveCapture) == true
        title("日常活动采集")
        paragraph("用户：${profile.displayName}")
        paragraph("戒指：${state?.selectedRingName ?: "未连接"}")
        paragraph("固件版本：${if (state?.ready == true) state.firmwareVersionText else "未连接"}")
        action(
            state?.batteryText ?: "查看戒指电量",
            enabled = state?.ready == true && state.batteryBusy != true,
        ) { service?.requestBattery() }
        action("返回模式选择", enabled = !active) { page = Page.MODE; render() }

        section("选择活动")
        DailyActivity.entries.forEach { activity ->
            val selected = selectedActivity == activity
            action("${if (selected) "✓ " else ""}${activity.displayName}", enabled = !active) {
                selectedActivity = activity
                render()
            }
        }

        section("选择戒指佩戴位置")
        paragraph("开始日常活动采集前必须选择当前佩戴位置。")
        RingPlacement.entries.forEach { placement ->
            val selected = selectedRingPlacement == placement
            action("${if (selected) "✓ " else ""}${placement.displayName}", enabled = !active) {
                selectedRingPlacement = placement
                render()
            }
        }

        section("可选心率采集")
        root.addView(CheckBox(this).apply {
            text = "同时采集 Polar H10 实时 HR/RR"
            isChecked = useActivityPolar
            isEnabled = !active
            setOnCheckedChangeListener { _, checked ->
                if (useActivityPolar != checked) {
                    useActivityPolar = checked
                    service?.resetPolarH10Selection()
                }
                render()
            }
        })
        if (useActivityPolar) renderPolar(state?.polar ?: PolarH10State(), active)

        section("采集")
        val phaseText = healthPhaseText(state)
        if (phaseText.isNotEmpty()) paragraph(phaseText)
        if (isHealthTransportBusy(state)) {
            root.addView(TextView(this).apply {
                text = "请不要关闭屏幕或离开本页面"
                setTextColor(Color.RED)
                textSize = 17f
                setPadding(0, dp(6), 0, dp(10))
            }, matchWrap())
        }
        if (!active) {
            val pendingRingTask = uploadStore.anyRingTransferTask() != null
            action(
                "开始采集",
                enabled = state?.ready == true && selectedActivity != null && selectedRingPlacement != null && !pendingRingTask,
            ) {
                when {
                    useActivityPolar && state?.polar?.let { it.connected && it.hrReady } != true ->
                        toast("请先连接并等待 Polar H10 实时 HR/RR 服务就绪")
                    else -> service?.startCollection(
                        ouraEnabled = false,
                        polarEnabled = useActivityPolar,
                        purpose = CapturePurpose.DAILY_ACTIVITY,
                        activity = selectedActivity,
                        placement = selectedRingPlacement,
                    )
                }
            }
            if (pendingRingTask) paragraph("戒指存在待传数据，请先处理后再开始新采集。")
        } else if (state?.healthPhase == HealthPhase.COLLECTING) {
            paragraph("当前活动：${state.activityCode?.displayName ?: selectedActivity?.displayName ?: "未知"}")
            paragraph("戒指佩戴位置：${state.ringPlacement?.displayName ?: selectedRingPlacement?.displayName ?: "未知"}")
            action("结束采集") { showStopChoices() }
        }

        renderSessionRecords(profile, CapturePurpose.DAILY_ACTIVITY)
    }

    private fun healthPhaseText(state: CaptureServiceState?): String = when (state?.healthPhase) {
        HealthPhase.STARTING -> "正在启动戒指健康采集…"
        HealthPhase.COLLECTING -> "采集中"
        HealthPhase.STOPPING -> "正在停止戒指…"
        HealthPhase.FINALIZING -> "戒指 Flash 收尾中，正在等待记录可用…"
        HealthPhase.LISTING -> "正在定位戒指记录…"
        HealthPhase.DOWNLOADING -> {
            val percent = if (state.healthTotalBytes > 0L) {
                (state.healthDownloadedBytes * 100L / state.healthTotalBytes).coerceIn(0L, 100L)
            } else 0L
            "正在从戒指下载：$percent%（${state.healthDownloadedBytes}/${state.healthTotalBytes} bytes）"
        }
        HealthPhase.PROCESSING -> "正在保存并校验原始数据…"
        else -> ""
    }

    private fun renderSessionRecords(profile: ParticipantProfile, purpose: CapturePurpose) {
        val ringTask = uploadStore.ringTaskForParticipant(profile.participantId)
        val failedUploads = uploadStore.failedUploadsForParticipant(profile.participantId, purpose)
        val uploaded = uploadStore.uploadedForParticipant(profile.participantId, purpose)
        val firstRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@MainActivity).apply {
                text = "戒指待传"
                isEnabled = ringTask != null
                setOnClickListener { showRingTransferTask() }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            })
            addView(Button(this@MainActivity).apply {
                text = "本地待上传"
                isEnabled = failedUploads.isNotEmpty()
                setOnClickListener {
                    showSessionList("本地待上传", retryUploads = true) {
                        profileStore.get()?.let {
                            uploadStore.failedUploadsForParticipant(it.participantId, purpose)
                        }.orEmpty()
                    }
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(6)
            })
        }
        root.addView(firstRow, matchWrap())
        action("已上传记录", enabled = uploaded.isNotEmpty()) {
            showSessionList("已上传记录", retryUploads = false) {
                profileStore.get()?.let {
                    uploadStore.uploadedForParticipant(it.participantId, purpose)
                }.orEmpty()
            }
        }
        paragraph("戒指数据完整保存到手机后即可开始下一次采集；云盘上传失败可在“本地待上传”中重试。")
    }

    private fun renderCollection() {
        val profile = profileStore.get()
        if (profile == null) { page = Page.LOGIN; render(); return }
        val state = serviceState
        val active = state?.let(::hasActiveCapture) == true
        title("RingFitness 采集")
        paragraph("用户：${profile.displayName}")
        paragraph("戒指：${state?.selectedRingName ?: "未连接"}")
        paragraph("固件版本：${if (state?.ready == true) state.firmwareVersionText else "未连接"}")
        action(
            state?.batteryText ?: "查看戒指电量",
            enabled = state?.ready == true && state?.batteryBusy != true,
        ) { service?.requestBattery() }
        action("返回模式选择", enabled = !active) { page = Page.MODE; render() }

        section("选择标签来源")
        root.addView(CheckBox(this).apply {
            text = "睡眠分期：Oura"
            isChecked = useOura
            isEnabled = !active
            setOnCheckedChangeListener { _, checked -> useOura = checked; render() }
        })
        if (useOura) {
            paragraph(when {
                ouraBusy -> "正在检查当前手机的 Oura 授权…"
                ouraConnected -> "Oura 已授权：当前用户 + 当前手机"
                else -> "Oura 尚未授权，或授权属于另一台手机"
            })
            action(if (ouraConnected) "检查 Oura" else "连接 Oura", enabled = !active && !ouraBusy) {
                if (ouraConnected) refreshOuraStatus() else startOuraAuthorization(forceReauthorize = false)
            }
            if (ouraConnected) {
                action("重新授权 Oura", enabled = !active && !ouraBusy) {
                    AlertDialog.Builder(this)
                        .setTitle("重新授权 Oura？")
                        .setMessage("将打开无痕登录页。必须继续使用这个 RingFitness 用户最初绑定的 Oura 账号，否则授权会被拒绝。")
                        .setPositiveButton("继续") { _, _ -> startOuraAuthorization(forceReauthorize = true) }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
        }
        root.addView(CheckBox(this).apply {
            text = "心率 / HRV：Polar H10（实时 HR/RR）"
            isChecked = usePolar
            isEnabled = !active
            setOnCheckedChangeListener { _, checked ->
                if (usePolar != checked) {
                    usePolar = checked
                    service?.resetPolarH10Selection()
                }
                render()
            }
        })
        if (usePolar) renderPolar(state?.polar ?: PolarH10State(), active)

        section("采集")
        val phaseText = when (state?.healthPhase) {
            HealthPhase.STARTING -> "正在启动戒指健康采集…"
            HealthPhase.COLLECTING -> "采集中"
            HealthPhase.STOPPING -> "正在停止戒指…"
            HealthPhase.FINALIZING -> "戒指 Flash 收尾中，正在等待记录可用…"
            HealthPhase.LISTING -> "正在定位戒指记录…"
            HealthPhase.DOWNLOADING -> {
                val percent = if (state.healthTotalBytes > 0L) {
                    (state.healthDownloadedBytes * 100L / state.healthTotalBytes).coerceIn(0L, 100L)
                } else 0L
                "正在从戒指下载：$percent%（${state.healthDownloadedBytes}/${state.healthTotalBytes} bytes）"
            }
            HealthPhase.PROCESSING -> "正在生成并校验 CSV…"
            else -> ""
        }
        if (phaseText.isNotEmpty()) paragraph(phaseText)
        if (isHealthTransportBusy(state)) {
            root.addView(TextView(this).apply {
                text = "请不要关闭屏幕或离开本页面"
                setTextColor(Color.RED)
                textSize = 17f
                setPadding(0, dp(6), 0, dp(10))
            }, matchWrap())
        }
        if (!active) {
            val pendingRingTask = uploadStore.anyRingTransferTask() != null
            action("开始采集", enabled = state?.ready == true && (useOura || usePolar) && !pendingRingTask) {
                when {
                    useOura && !ouraConnected -> toast("请先完成当前手机的 Oura 授权")
                    usePolar && state?.polar?.let { it.connected && it.hrReady } != true ->
                        toast("请先连接并等待 Polar H10 实时 HR/RR 服务就绪")
                    else -> service?.startCollection(
                        ouraEnabled = useOura,
                        polarEnabled = usePolar,
                        purpose = CapturePurpose.SLEEP_HR,
                    )
                }
            }
            if (pendingRingTask) paragraph("戒指存在待传数据，请先处理后再开始新采集。")
        } else if (state?.healthPhase == HealthPhase.COLLECTING) {
            action("结束采集") { showStopChoices() }
        }
        val ringTask = uploadStore.ringTaskForParticipant(profile.participantId)
        val failedUploads = uploadStore.failedUploadsForParticipant(profile.participantId, CapturePurpose.SLEEP_HR)
        val uploaded = uploadStore.uploadedForParticipant(profile.participantId, CapturePurpose.SLEEP_HR)
        val firstRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@MainActivity).apply {
                text = "戒指待传"
                isEnabled = ringTask != null
                setOnClickListener { showRingTransferTask() }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            })
            addView(Button(this@MainActivity).apply {
                text = "本地待上传"
                isEnabled = failedUploads.isNotEmpty()
                setOnClickListener {
                    showSessionList("本地待上传", retryUploads = true) {
                        profileStore.get()?.let {
                            uploadStore.failedUploadsForParticipant(it.participantId, CapturePurpose.SLEEP_HR)
                        }.orEmpty()
                    }
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(6)
            })
        }
        root.addView(firstRow, matchWrap())
        action("已上传记录", enabled = uploaded.isNotEmpty()) {
            showSessionList("已上传记录", retryUploads = false) {
                profileStore.get()?.let {
                    uploadStore.uploadedForParticipant(it.participantId, CapturePurpose.SLEEP_HR)
                }.orEmpty()
            }
        }
        paragraph("戒指数据完整保存到手机后即可开始下一次采集；云盘上传失败可在“本地待上传”中重试。")
    }

    private fun renderPolar(polar: PolarH10State, active: Boolean) {
        action(if (polar.scanning) "正在搜索 H10…" else "搜索 Polar H10", enabled = !active && !polar.scanning) {
            service?.searchForPolarH10()
        }
        polar.devices.forEach { device ->
            val current = polar.selectedDeviceId == device.deviceId
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(Button(this).apply {
                text = "${device.name} (${device.deviceId}, ${device.rssi} dBm)"
                isEnabled = !active && !polar.connecting && !polar.hrReady
                setOnClickListener { service?.connectPolarH10(device.deviceId) }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (current) {
                when {
                    polar.hrReady -> row.addView(TextView(this).apply {
                        text = "✓"
                        textSize = 24f
                        setPadding(dp(10), 0, dp(4), 0)
                    })
                    polar.connecting -> row.addView(ProgressBar(this).apply { isIndeterminate = true },
                        LinearLayout.LayoutParams(dp(42), dp(42)))
                    polar.connectionFailed -> row.addView(TextView(this).apply {
                        text = "!"
                        textSize = 24f
                        setPadding(dp(10), 0, dp(4), 0)
                    })
                }
            }
            root.addView(row, matchWrap())
        }
    }

    private fun hasActiveCapture(state: CaptureServiceState): Boolean =
        state.healthPhase != HealthPhase.IDLE || state.polarCaptureActive || state.subjectiveFeedbackRequired

    private fun isHealthTransportBusy(state: CaptureServiceState?): Boolean = when (state?.healthPhase) {
        HealthPhase.STOPPING,
        HealthPhase.FINALIZING,
        HealthPhase.LISTING,
        HealthPhase.DOWNLOADING,
        HealthPhase.PROCESSING,
        -> true
        else -> false
    }

    private fun restoreCollectionModes(state: CaptureServiceState) {
        if (state.capturePurpose == CapturePurpose.DAILY_ACTIVITY) {
            selectedActivity = state.activityCode
            selectedRingPlacement = state.ringPlacement
            useActivityPolar = state.polarEnabledForSession || state.polarCaptureActive
        } else {
            useOura = state.ouraEnabledForSession
            usePolar = state.polarEnabledForSession || state.polarCaptureActive
        }
    }

    private fun collectionPageFor(state: CaptureServiceState): Page =
        if (state.capturePurpose == CapturePurpose.DAILY_ACTIVITY) {
            Page.ACTIVITY_COLLECTION
        } else {
            Page.SLEEP_COLLECTION
        }

    private fun enterModeSelectionPage() {
        waitingForRingConnection = false
        page = Page.MODE
        render()
    }

    private fun returnToRingConnection() {
        waitingForRingConnection = false
        selectedRing = null
        rings = emptyList()
        page = Page.RING
        service?.resetRingSelection()
        render()
    }

    private fun refreshOuraStatus() {
        val profile = profileStore.get() ?: return
        val base = profile.authorizationBaseUrl ?: return
        val code = profile.enrollmentCode ?: return
        ouraBusy = true
        render()
        io.execute {
            runCatching { RingFitnessApi.ouraStatus(base, code, profile.displayName, profileStore.installationId) }
                .onSuccess { connected -> runOnUiThread { ouraConnected = connected; ouraBusy = false; render() } }
                .onFailure { error -> runOnUiThread { ouraConnected = false; ouraBusy = false; toast(error.message ?: "Oura 状态检查失败"); render() } }
        }
    }

    private fun startOuraAuthorization(forceReauthorize: Boolean) {
        val profile = profileStore.get() ?: return
        val browserPackage = secureOuraBrowserPackage()
        if (browserPackage == null) {
            toast("没有找到支持账号隔离的浏览器，请安装或更新 Chrome 后重试")
            return
        }
        ouraBusy = true
        render()
        io.execute {
            runCatching { OuraAuthorizationClient.start(profile, profileStore.installationId, forceReauthorize) }
                .onSuccess { enrollment ->
                    OuraAuthorizationStore(this).savePending(enrollment.participantName, enrollment.sessionToken)
                    runOnUiThread {
                        ouraBusy = false
                        val customTab = CustomTabsIntent.Builder()
                            .setEphemeralBrowsingEnabled(true)
                            .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
                            .build()
                        customTab.intent.setPackage(browserPackage)
                        customTab.launchUrl(this, Uri.parse(enrollment.authorizationUrl))
                        render()
                    }
                }
                .onFailure { error -> runOnUiThread { ouraBusy = false; toast(error.message ?: "无法启动 Oura 授权"); render() } }
        }
    }

    @Suppress("DEPRECATION")
    private fun secureOuraBrowserPackage(): String? {
        val candidates = linkedSetOf("com.android.chrome")
        CustomTabsClient.getPackageName(this, null)?.let(candidates::add)
        packageManager.queryIntentServices(
            Intent(CustomTabsService.ACTION_CUSTOM_TABS_CONNECTION),
            0,
        ).mapNotNullTo(candidates) { it.serviceInfo?.packageName }
        return candidates.firstOrNull { packageName ->
            runCatching { CustomTabsClient.isEphemeralBrowsingSupported(this, packageName) }
                .getOrDefault(false)
        }
    }

    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme.equals("ringfitness", true) && data.host == "oura-complete") {
            if (::root.isInitialized) {
                page = Page.SLEEP_COLLECTION
                val error = data.getQueryParameter("error")
                if (error.isNullOrBlank()) {
                    refreshOuraStatus()
                } else {
                    ouraConnected = false
                    ouraBusy = false
                    toast(error)
                    render()
                }
            }
        }
    }

    private fun showStopChoices() {
        AlertDialog.Builder(this)
            .setTitle("结束本次采集")
            .setMessage("请选择数据处理方式；“继续采集”不会向设备发送停止命令。")
            .setPositiveButton("结束并立即上传") { _, _ -> service?.stopHealth(HealthStopDisposition.UPLOAD_NOW) }
            .setNegativeButton("结束并暂存到戒指") { _, _ -> service?.stopHealth(HealthStopDisposition.DEFER) }
            .setNeutralButton("更多") { _, _ ->
                AlertDialog.Builder(this)
                    .setItems(arrayOf("删除数据", "继续采集")) { dialog, index ->
                        if (index == 0) {
                            AlertDialog.Builder(this)
                                .setTitle("确认删除本次数据？")
                                .setPositiveButton("删除") { _, _ -> service?.stopHealth(HealthStopDisposition.DISCARD) }
                                .setNegativeButton("取消", null)
                                .show()
                        } else dialog.dismiss()
                    }.show()
            }.show()
    }

    private fun showSubjectiveFeedback(state: CaptureServiceState) {
        if (subjectiveFeedbackDialog?.isShowing == true) return
        val target = state.subjectiveFeedbackTarget ?: return
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }

        val includeScore = CheckBox(this).apply {
            text = "填写评分（可选）"
            isChecked = false
        }
        container.addView(includeScore, matchWrap())

        val scoreLabel = TextView(this).apply {
            text = "当前分数：5"
            textSize = 20f
            setPadding(0, dp(8), 0, 0)
            visibility = View.GONE
        }
        container.addView(scoreLabel, matchWrap())

        val slider = SeekBar(this).apply {
            max = 9
            progress = 4
            visibility = View.GONE
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    scoreLabel.text = "当前分数：${progress + 1}"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        container.addView(slider, matchWrap())

        includeScore.setOnCheckedChangeListener { _, checked ->
            scoreLabel.visibility = if (checked) View.VISIBLE else View.GONE
            slider.visibility = if (checked) View.VISIBLE else View.GONE
        }

        val activityDetail: EditText? =
            if (target == SubjectiveFeedbackTarget.DAILY_ACTIVITY) {
                container.addView(TextView(this).apply {
                    text = "活动细节（可选，最多 500 字）"
                    setPadding(0, dp(12), 0, dp(4))
                }, matchWrap())
                EditText(this).apply {
                    hint = when (state.activityCode) {
                        DailyActivity.WORKING -> "例如：具体完成了什么工作"
                        DailyActivity.EATING -> "例如：吃了什么食物"
                        DailyActivity.WALKING -> "例如：从哪里走到哪里"
                        DailyActivity.CYCLING -> "例如：骑行路线或目的地"
                        DailyActivity.RUNNING -> "例如：跑步路线或训练内容"
                        else -> "例如：具体做了什么"
                    }
                    minLines = 3
                    gravity = Gravity.TOP
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    filters = arrayOf(
                        InputFilter.LengthFilter(SubjectiveFeedback.MAX_ACTIVITY_DETAIL_LENGTH),
                    )
                    container.addView(this, matchWrap())
                }
            } else null

        container.addView(TextView(this).apply {
            text = "主观评价（可选，最多 500 字）"
            setPadding(0, dp(12), 0, dp(4))
        }, matchWrap())
        val comment = EditText(this).apply {
            hint = "请输入这一段的主观感受"
            minLines = 4
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            filters = arrayOf(InputFilter.LengthFilter(SubjectiveFeedback.MAX_COMMENT_LENGTH))
        }
        container.addView(comment, matchWrap())

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (target == SubjectiveFeedbackTarget.SLEEP) "本段睡眠评价" else "本段活动评价")
            .setView(container)
            .setPositiveButton("保存评价并继续", null)
            .setCancelable(false)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selectedScore = if (includeScore.isChecked) slider.progress + 1 else null
                if (service?.submitSubjectiveFeedback(
                        score = selectedScore,
                        activityDetail = activityDetail?.text?.toString().orEmpty(),
                        comment = comment.text.toString(),
                    ) == true
                ) {
                    dialog.dismiss()
                    subjectiveFeedbackDialog = null
                } else {
                    toast("评价保存失败，请重试")
                }
            }
        }
        dialog.setOnDismissListener {
            if (subjectiveFeedbackDialog === dialog) subjectiveFeedbackDialog = null
        }
        subjectiveFeedbackDialog = dialog
        dialog.show()
    }

    private fun showRingTransferTask() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        lateinit var dialog: AlertDialog
        lateinit var refresher: Runnable

        fun refresh() {
            container.removeAllViews()
            val profile = profileStore.get()
            val task = profile?.let { uploadStore.ringTaskForParticipant(it.participantId) }
            if (task == null) {
                container.addView(TextView(this).apply { text = "当前没有戒指待传任务" })
                return
            }
            val live = serviceState
            val downloaded = if (live?.healthPhase == HealthPhase.DOWNLOADING) {
                live.healthDownloadedBytes
            } else task.ringDownloadedBytes
            val total = if ((live?.healthTotalBytes ?: 0L) > 0L) live!!.healthTotalBytes else task.ringTotalBytes
            val percent = if (total > 0L) (downloaded * 100L / total).coerceIn(0L, 100L) else null
            val status = when (live?.healthPhase) {
                HealthPhase.STOPPING -> "正在停止戒指"
                HealthPhase.FINALIZING -> "等待 Flash 收尾"
                HealthPhase.LISTING -> "正在查询 Flash 记录"
                HealthPhase.DOWNLOADING -> "正在从戒指下载"
                HealthPhase.PROCESSING -> "正在生成并校验手机文件"
                else -> task.error ?: "等待下载到手机"
            }
            container.addView(TextView(this).apply {
                text = "用户：${task.participantName}\n戒指：${task.ringName ?: "未知"}\n" +
                    "采集时间：${formatTime(task.startedAtMs)}\n状态：$status"
                setPadding(0, 0, 0, dp(12))
            })
            if (isHealthTransportBusy(live)) {
                container.addView(TextView(this).apply {
                    text = "请不要关闭屏幕或离开本页面"
                    setTextColor(Color.RED)
                    textSize = 17f
                    setPadding(0, 0, 0, dp(10))
                })
            }
            if (percent != null) {
                container.addView(android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    progress = percent.toInt()
                }, matchWrap())
                container.addView(TextView(this).apply {
                    text = "$percent% · $downloaded / $total bytes"
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(0, dp(4), 0, dp(8))
                })
            } else {
                container.addView(TextView(this).apply {
                    text = "正在等待戒指返回可用的数据大小…"
                    setPadding(0, dp(4), 0, dp(8))
                })
            }
            val canResume = task.state == UploadSessionStore.STATE_RING_PENDING &&
                serviceState?.healthPhase == HealthPhase.IDLE
            container.addView(Button(this).apply {
                text = "下载到手机并上传"
                isEnabled = canResume && serviceState?.ready == true
                setOnClickListener {
                    if (service?.resumeRingTransfer(task.sessionId) != true) {
                        toast("请连接任务原先使用的戒指后重试")
                    }
                    refresh()
                }
            }, matchWrap())
            container.addView(Button(this).apply {
                text = "从 0 重新下载"
                isEnabled = canResume && serviceState?.ready == true
                setTextColor(Color.rgb(230, 126, 34))
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("确认从 0 重新下载？")
                        .setMessage(
                            "只会删除手机上已下载的部分临时文件和断点进度。" +
                                "原用户、原戒指、session 元数据和戒指 Flash 记录都会保留。",
                        )
                        .setPositiveButton("从 0 重新下载") { _, _ ->
                            if (service?.restartPendingRingTransferFromZero(task.sessionId) != true) {
                                toast("请连接任务原先使用的戒指后重试")
                            }
                            refresh()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }, matchWrap())
            container.addView(Button(this).apply {
                text = "放弃戒指待传数据"
                isEnabled = canResume && serviceState?.ready == true
                setTextColor(Color.RED)
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("确认放弃这段戒指数据？")
                        .setMessage(
                            "这会删除手机上的待传任务并允许开始下一次采集。" +
                                "不会向戒指发送物理擦除命令；旧记录之后可由戒指固件自然覆盖。此操作不可撤销。",
                        )
                        .setPositiveButton("确认放弃") { _, _ ->
                            if (service?.discardPendingRingTransfer(task.sessionId) == true) {
                                toast("已放弃待传任务，可以开始新采集")
                            }
                            refresh()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }, matchWrap())
        }

        dialog = AlertDialog.Builder(this)
            .setTitle("戒指待传")
            .setView(ScrollView(this).apply { addView(container) })
            .setPositiveButton("关闭", null)
            .create()
        refresher = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return
                refresh()
                container.postDelayed(this, 750L)
            }
        }
        dialog.setOnShowListener { refresh(); container.postDelayed(refresher, 750L) }
        dialog.setOnDismissListener { container.removeCallbacks(refresher); render() }
        dialog.show()
    }

    private fun showSessionList(
        title: String,
        retryUploads: Boolean,
        sessionsProvider: () -> List<UploadSessionSummary>,
    ) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        lateinit var sessionsDialog: AlertDialog

        fun refreshSessions() {
            container.removeAllViews()
            val sessions = sessionsProvider()
            sessionsDialog.setTitle(title)
            if (sessions.isEmpty()) {
                container.addView(TextView(this).apply {
                    text = "当前没有记录"
                    setPadding(0, dp(16), 0, dp(16))
                })
                return
            }
            sessions.forEach { session ->
                container.addView(TextView(this).apply {
                    val activity = session.activityCode?.displayName?.let { "$it · " }.orEmpty()
                    text = "$activity${formatTime(session.startedAtMs)} · ${session.ringName ?: "未知戒指"} · " +
                        "${session.captureCount} 个文件" + (session.error?.let { "\n$it" } ?: "")
                    setPadding(0, dp(10), 0, dp(4))
                })
                if (retryUploads) {
                    container.addView(Button(this).apply {
                        text = "重新上传云盘"
                        setOnClickListener {
                            UploadScheduler.enqueue(this@MainActivity, session.sessionId)
                            toast("已加入上传队列")
                            refreshSessions()
                            render()
                        }
                    }, matchWrap())
                }
                container.addView(Button(this).apply {
                    text = "删除本地"
                    setOnClickListener {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("删除这个 session 的本地文件？")
                            .setMessage("只有点击“删除”才会删除采集文件和本地记录。")
                            .setNegativeButton("取消", null)
                            .setPositiveButton("删除") { _, _ ->
                                val failures = uploadStore.deleteSessionData(session.sessionId)
                                if (failures == 0) toast("本地文件已删除")
                                else toast("有 $failures 个文件未能删除")
                                refreshSessions()
                                render()
                            }
                            .show()
                    }
                }, matchWrap())
                val existingExports = csvFilesFor(session.sessionId)
                val exportProgress = csvExportProgress[session.sessionId]
                if (exportProgress != null) {
                    container.addView(TextView(this).apply {
                        text = "正在导出 CSV · ${(exportProgress * 100).toInt()}%"
                    })
                    container.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                        max = 1000
                        progress = (exportProgress * 1000).toInt()
                    }, matchWrap())
                }
                container.addView(Button(this).apply {
                    text = if (existingExports.isEmpty()) "导出 CSV" else "预览 CSV"
                    isEnabled = exportProgress == null
                    setOnClickListener {
                        if (existingExports.isNotEmpty()) {
                            previewCSV(existingExports)
                        } else {
                            exportSessionCSV(session.sessionId) { refreshSessions() }
                            refreshSessions()
                        }
                    }
                }, matchWrap())
            }
        }

        sessionsDialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(ScrollView(this).apply { addView(container) })
            .setPositiveButton("关闭", null)
            .create()
        val refresher = object : Runnable {
            override fun run() {
                if (!sessionsDialog.isShowing) return
                if (csvExportProgress.isNotEmpty()) refreshSessions()
                container.postDelayed(this, 750L)
            }
        }
        sessionsDialog.setOnShowListener {
            refreshSessions()
            container.postDelayed(refresher, 750L)
        }
        sessionsDialog.setOnDismissListener { container.removeCallbacks(refresher) }
        sessionsDialog.show()
    }

    private fun csvFilesFor(sessionId: String): List<ExportedCSV> {
        val exported = runCatching { uploadStore.exportedCSV(sessionId) }.getOrDefault(emptyList())
        if (exported.isNotEmpty()) return exported
        return runCatching { uploadStore.captures(sessionId) }.getOrDefault(emptyList())
            .filter { it.optString("file").endsWith(".csv", ignoreCase = true) }
            .map { ExportedCSV(Uri.parse(it.getString("uri")), it.getString("file")) }
    }

    private fun exportSessionCSV(sessionId: String, onFinished: () -> Unit) {
        if (csvExportProgress.putIfAbsent(sessionId, 0.0) != null) return
        csvIo.execute {
            runCatching {
                val raw = uploadStore.captures(sessionId).firstOrNull {
                    it.optString("signal") == CaptureKind.HEALTH_RAW_V2.fileLabel
                } ?: error("这个 session 没有可导出的 v2 戒指原始数据")
                HealthRawV2.exportCSV(
                    this,
                    Uri.parse(raw.getString("uri")),
                    sessionId,
                ) { value ->
                    csvExportProgress[sessionId] = value
                }.also { uploadStore.saveCSVExports(sessionId, it) }
            }.onSuccess {
                runOnUiThread { toast("CSV 导出完成"); onFinished() }
            }.onFailure {
                runOnUiThread { toast("CSV 导出失败：${it.message}"); onFinished() }
            }
            csvExportProgress.remove(sessionId)
        }
    }

    private fun previewCSV(files: List<ExportedCSV>) {
        fun open(file: ExportedCSV) {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(file.uri, "text/csv")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching { startActivity(Intent.createChooser(intent, "预览 CSV")) }
                .onFailure { toast("手机中没有可快速查看 CSV 的应用") }
        }
        if (files.size == 1) return open(files.first())
        AlertDialog.Builder(this)
            .setTitle("选择要预览的 CSV")
            .setItems(files.map { it.displayName }.toTypedArray()) { _, index -> open(files[index]) }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun requestRuntimePermissions() {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (permissions.isNotEmpty()) requestPermissions(permissions.toTypedArray(), 1001)
        if (Build.VERSION.SDK_INT >= 23 && !getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) {
            runCatching {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
        }
    }

    private fun title(text: String) = root.addView(TextView(this).apply {
        this.text = text; textSize = 28f; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, 0, 0, dp(16))
    })
    private fun section(text: String) = root.addView(TextView(this).apply { this.text = text; textSize = 20f; setPadding(0, dp(18), 0, dp(8)) })
    private fun paragraph(text: String) = root.addView(TextView(this).apply { this.text = text; textSize = 15f; setPadding(0, dp(5), 0, dp(5)) })
    private fun action(text: String, enabled: Boolean = true, block: () -> Unit) = root.addView(Button(this).apply {
        this.text = text; isEnabled = enabled; setOnClickListener { block() }
    }, matchWrap())
    private fun spacer(height: Int) = root.addView(View(this), LinearLayout.LayoutParams(1, dp(height)))
    private fun matchWrap() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    companion object {
        private const val STATE_PAGE = "page"
        private const val STATE_USE_OURA = "use_oura"
        private const val STATE_USE_POLAR = "use_polar"
        private const val STATE_USE_ACTIVITY_POLAR = "use_activity_polar"
        private const val STATE_SELECTED_ACTIVITY = "selected_activity"
        private const val STATE_SELECTED_RING_PLACEMENT = "selected_ring_placement"
        private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault())
        private fun formatTime(epochMs: Long): String = timeFormat.format(Instant.ofEpochMilli(epochMs))
    }
}
