package com.nexthci.ringfitness

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors

/** T1 navigation only. Preparation never starts the legacy capture or upload services. */
class StepPreparationActivity : Activity() {
    private enum class Page { REGISTER, PLACEMENT, DEVICE, HOME }
    private val main = Handler(Looper.getMainLooper())
    private lateinit var store: PreparationStore
    private lateinit var controller: RingPreparationController
    private var snapshot: PreparationSnapshot? = null
    private var page = Page.REGISTER
    private var restoredPage: Page? = null
    private var placementFromHome = false
    private var placementDraft = 0
    private var loaded = false
    private var busy = false
    private var storageProblem: String? = null
    private var feedback: String? = null
    private var visible = false
    private var pendingBluetoothAction: (() -> Unit)? = null
    private var scanner: RingBleClient? = null
    private var scanning = false
    private var scanGeneration = 0L
    private var scanMessage: String? = null
    private var timedAttempt = -1L
    private lateinit var back: Button
    private lateinit var heading: TextView
    private lateinit var subtitle: TextView
    private lateinit var scroll: ScrollView
    private lateinit var registration: LinearLayout
    private lateinit var participantInput: EditText
    private lateinit var placement: LinearLayout
    private lateinit var placementPicker: Spinner
    private lateinit var device: LinearLayout
    private lateinit var ringLabel: TextView
    private lateinit var connectionLabel: TextView
    private lateinit var deviceAction: Button
    private lateinit var settingsAction: Button
    private lateinit var results: LinearLayout
    private lateinit var overview: LinearLayout
    private lateinit var participantLabel: TextView
    private lateinit var placementLabel: TextView
    private lateinit var homeRingLabel: TextView
    private lateinit var homeStatus: TextView
    private lateinit var message: TextView
    private lateinit var primary: Button
    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) != BluetoothAdapter.STATE_ON) {
                stopScan()
                controller.disconnect("手机蓝牙已关闭")
            }
            updateViews()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = PreparationStore(File(filesDir, "preparation/profile.properties"))
        restoredPage = savedInstanceState?.getString("page")?.let { runCatching { Page.valueOf(it) }.getOrNull() }
        placementDraft = savedInstanceState?.getInt("placement_draft") ?: 0
        placementFromHome = savedInstanceState?.getBoolean("placement_from_home") ?: false
        controller = RingPreparationController(AndroidPreparationTransport(this)) { state ->
            if (::primary.isInitialized) updateViews()
            if (state.connecting && state.attemptId != timedAttempt) {
                timedAttempt = state.attemptId
                main.postDelayed({ if (visible) controller.timeout(state.attemptId) }, 30_000)
            }
        }
        buildPages(savedInstanceState?.getString("participant_draft").orEmpty())
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { goBack() }
        }
        loadProfile()
    }

    override fun onStart() {
        super.onStart()
        visible = true
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_EXPORTED)
        else registerReceiver(bluetoothReceiver, filter)
    }
    override fun onResume() { super.onResume(); if (::primary.isInitialized) updateViews() }
    override fun onStop() {
        visible = false
        pendingBluetoothAction = null
        stopScan()
        controller.disconnect("未连接")
        unregisterReceiver(bluetoothReceiver)
        super.onStop()
    }
    override fun onDestroy() { main.removeCallbacksAndMessages(null); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("page", page.name)
        outState.putString("participant_draft", participantInput.text.toString())
        outState.putInt("placement_draft", placementDraft)
        outState.putBoolean("placement_from_home", placementFromHome)
        super.onSaveInstanceState(outState)
    }
    @Deprecated("Legacy Android back callback")
    override fun onBackPressed() = goBack()

    private fun goBack() {
        if (busy) return
        if (page == Page.HOME || snapshot == null) finish() else navigate(Page.HOME)
    }
    private fun navigate(next: Page) {
        if (busy) return
        if (page == Page.DEVICE && next != Page.DEVICE) stopScan()
        if (next == Page.PLACEMENT) {
            placementFromHome = page == Page.HOME
            placementDraft = snapshot?.placement?.ordinal?.plus(1) ?: 0
            placementPicker.setSelection(placementDraft)
        }
        page = next
        feedback = null
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(participantInput.windowToken, 0)
        updateViews()
        scroll.scrollTo(0, 0)
    }

    private fun buildPages(draft: String) {
        val frame = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(246, 248, 247))
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        setContentView(frame)
        val header = section(frame, 20)
        back = button(header, "返回准备概览", false) { goBack() }.apply { tag = "back" }
        heading = label(header, "步数采集", 25f).apply { tag = "heading"; setTypeface(null, Typeface.BOLD) }
        subtitle = label(header, "", 14f)
        scroll = ScrollView(this).apply { isFillViewport = true }
        frame.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), dp(12)) }
        scroll.addView(body)
        registration = section(body)
        label(registration, "被试编号", 16f)
        participantInput = EditText(this).apply {
            tag = "participant_input"
            hint = "例如 P001"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(draft)
        }
        registration.addView(participantInput, LinearLayout.LayoutParams(-1, -2))
        label(registration, "填写研究者分配的编号，之后会自动记住。", 14f)
        placement = section(body)
        label(placement, "戒指戴在哪根手指？", 19f)
        placementPicker = Spinner(this).apply {
            tag = "placement_picker"
            adapter = ArrayAdapter(this@StepPreparationActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("请选择佩戴位置") + RingPlacement.entries.map { it.displayName })
            setSelection(placementDraft)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    placementDraft = position
                    updateViews()
                }
            }
        }
        placement.addView(placementPicker, LinearLayout.LayoutParams(-1, dp(64)))
        label(placement, "按现在的佩戴位置选择。以后可在准备概览中修改。", 14f)
        device = section(body)
        ringLabel = label(device, "", 20f)
        connectionLabel = label(device, "", 16f)
        label(device, "让戒指保持有电，并放在手机附近。", 14f)
        results = section(device)
        deviceAction = button(device, "搜索其他戒指", false) { deviceSecondaryAction() }
        settingsAction = button(device, "应用权限设置", false) { openNeededSettings() }
        button(device, "设备详情", false) { showDetails() }
        overview = section(body)
        participantLabel = label(overview, "", 15f).apply { setPadding(0, dp(4), 0, dp(16)) }
        val placementCard = card(overview)
        label(placementCard, "佩戴位置", 13f)
        placementLabel = label(placementCard, "", 19f)
        button(placementCard, "修改佩戴位置", false) { navigate(Page.PLACEMENT) }.tag = "edit_placement"
        val ringCard = card(overview)
        label(ringCard, "戒指", 13f)
        homeRingLabel = label(ringCard, "", 19f)
        homeStatus = label(ringCard, "", 14f)
        button(ringCard, "连接与设备详情", false) { navigate(Page.DEVICE) }.tag = "open_device"
        val footer = section(frame, 20)
        message = label(footer, "", 14f).apply { tag = "feedback"; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        primary = button(footer, "", true) { primaryAction() }.apply { tag = "primary" }
        label(footer, "准备版 · 采集功能待开放", 12f)
        updateViews()
    }

    private fun loadProfile() {
        busy = true
        updateViews()
        disk.execute {
            val result = runCatching { store.read() }
            main.post {
                if (isDestroyed) return@post
                busy = false
                loaded = true
                result.onSuccess {
                    snapshot = it
                    storageProblem = null
                    page = if (it == null) Page.REGISTER else restoredPage?.takeUnless { p -> p == Page.REGISTER } ?: Page.HOME
                    if (restoredPage != Page.PLACEMENT) placementDraft = it?.placement?.ordinal?.plus(1) ?: 0
                    placementPicker.setSelection(placementDraft)
                }.onFailure { storageProblem = "准备信息读取失败，原文件已保留。请联系研究者。" }
                restoredPage = null
                updateViews()
            }
        }
    }
    private fun persist(afterSave: (PreparationSnapshot) -> Unit, write: () -> PreparationSnapshot) {
        if (busy || storageProblem != null) return
        busy = true
        feedback = null
        updateViews()
        disk.execute {
            val result = runCatching(write)
            main.post {
                if (isDestroyed) return@post
                busy = false
                result.onSuccess { snapshot = it; afterSave(it) }.onFailure {
                    feedback = if (it is IllegalArgumentException) it.message else "保存未成功，请检查手机空间后重试。"
                }
                updateViews()
            }
        }
    }
    private fun checking(): Boolean = controller.state.let {
        it.connecting || (it.connected && !it.queryTimedOut &&
            (it.healthStatus == null || it.batteryPercent == null || it.firmwareVersion == null))
    }
    private fun primaryAction() {
        if (busy || !loaded || storageProblem != null) return
        when (page) {
            Page.REGISTER -> {
                val raw = participantInput.text.toString()
                persist(afterSave = { navigate(Page.PLACEMENT) }) { store.register(raw) }
            }
            Page.PLACEMENT -> {
                val value = RingPlacement.entries.getOrNull(placementDraft - 1) ?: return
                val next = if (placementFromHome) Page.HOME else Page.DEVICE
                if (value == snapshot?.placement) navigate(next)
                else persist(afterSave = { navigate(next) }) { store.savePlacement(value) }
            }
            Page.HOME -> when {
                snapshot?.placement == null -> navigate(Page.PLACEMENT)
                controller.state.queryTimedOut || !controller.state.canPrepare -> navigate(Page.DEVICE)
            }
            Page.DEVICE -> when {
                scanning || checking() -> Unit
                controller.state.connected && !controller.state.queryTimedOut -> navigate(Page.HOME)
                else -> withBluetooth { snapshot?.ring?.let(::connect) ?: scan() }
            }
        }
    }
    private fun updateViews() {
        if (!::primary.isInitialized) return
        val usable = loaded && storageProblem == null
        registration.visibility = if (usable && page == Page.REGISTER) View.VISIBLE else View.GONE
        placement.visibility = if (usable && page == Page.PLACEMENT) View.VISIBLE else View.GONE
        device.visibility = if (usable && page == Page.DEVICE) View.VISIBLE else View.GONE
        overview.visibility = if (usable && page == Page.HOME) View.VISIBLE else View.GONE
        back.visibility = if (usable && snapshot != null && page != Page.HOME) View.VISIBLE else View.GONE
        back.isEnabled = !busy
        heading.text = when (page) { Page.REGISTER -> "欢迎使用步数采集"; Page.PLACEMENT -> "确认佩戴位置"; Page.DEVICE -> "连接戒指"; Page.HOME -> "步数采集" }
        subtitle.text = when (page) {
            Page.REGISTER -> "首次使用 · 登记编号"
            Page.PLACEMENT -> "${snapshot?.participantId.orEmpty()} · 佩戴信息"
            Page.DEVICE -> "${snapshot?.participantId.orEmpty()} · 设备检查"
            Page.HOME -> "采集准备"
        }
        participantInput.isEnabled = !busy
        placementPicker.isEnabled = !busy
        participantLabel.text = "被试编号：${snapshot?.participantId.orEmpty()}"
        placementLabel.text = snapshot?.placement?.displayName ?: "尚未确认"
        val state = controller.state
        ringLabel.text = snapshot?.ring?.name ?: "搜索你的戒指"
        homeRingLabel.text = snapshot?.ring?.name ?: "尚未选择"
        connectionLabel.text = scanMessage ?: state.message
        homeStatus.text = if (state.connected) state.message else if (checking()) "正在连接并检查…" else "未连接 · 需要重新检查"
        deviceAction.visibility = if (snapshot?.ring != null || scanning || checking()) View.VISIBLE else View.GONE
        deviceAction.text = when { scanning -> "停止搜索"; checking() -> "取消连接检查"; else -> "搜索其他戒指" }
        deviceAction.isEnabled = !busy
        settingsAction.text = when {
            requiredPermissions().any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED } -> "应用权限设置"
            !bluetoothEnabled() -> "打开蓝牙设置"
            !locationEnabled() -> "打开定位设置"
            else -> "蓝牙设置"
        }
        settingsAction.isEnabled = !busy && !scanning && !checking()
        primary.text = when {
            !loaded -> "正在读取…"
            busy -> "正在保存…"
            storageProblem != null -> "准备信息需要处理"
            page == Page.REGISTER -> "保存并继续"
            page == Page.PLACEMENT -> if (placementFromHome) "保存位置" else "保存并继续"
            page == Page.HOME -> when {
                snapshot?.placement == null -> "确认佩戴位置"
                checking() -> "正在检查戒指…"
                state.queryTimedOut -> "重新检查戒指"
                state.connected && !state.canPrepare -> "查看待核对事项"
                !state.canPrepare -> "连接并检查戒指"
                else -> "开始采集（待开放）"
            }
            scanning -> "正在搜索…"
            checking() -> "正在检查戒指…"
            state.queryTimedOut -> "重新连接检查"
            state.connected -> if (state.canPrepare) "完成连接检查" else "返回准备概览"
            snapshot?.ring == null -> "搜索戒指"
            else -> "连接戒指"
        }
        primary.isEnabled = usable && !busy && when (page) {
            Page.PLACEMENT -> placementDraft > 0
            Page.DEVICE -> !scanning && !checking()
            Page.HOME -> !checking() && (snapshot?.placement == null || state.queryTimedOut || !state.canPrepare)
            Page.REGISTER -> true
        }
        message.text = storageProblem ?: feedback ?: when {
            !loaded -> "正在读取准备信息"
            busy -> "保存成功后会自动继续"
            page == Page.HOME && snapshot?.placement == null -> "下一步：确认戒指的佩戴位置。"
            page == Page.HOME && checking() -> "正在读取戒指信息，请稍候。"
            page == Page.HOME && state.queryTimedOut -> "设备信息尚未读全，请重新连接检查。"
            page == Page.HOME && state.connected && !state.canPrepare -> "戒指状态需要研究者核对，已保留准备信息。"
            page == Page.HOME && !state.canPrepare -> "下一步：检查戒指连接和状态。"
            page == Page.HOME -> "准备检查已完成，等待采集版本。"
            else -> ""
        }
        message.visibility = if (message.text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun bluetoothEnabled() = getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
    private fun locationEnabled() = Build.VERSION.SDK_INT > 30 || getSystemService(android.location.LocationManager::class.java).isLocationEnabled
    private fun withBluetooth(action: () -> Unit) {
        val missing = requiredPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            pendingBluetoothAction = action
            requestPermissions(missing.toTypedArray(), 10)
            return
        }
        if (!bluetoothEnabled() || !locationEnabled()) { openNeededSettings(); return }
        action()
    }
    private fun openNeededSettings() {
        val intent = when {
            requiredPermissions().any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED } ->
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            !bluetoothEnabled() -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            !locationEnabled() -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            else -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        }
        startActivity(intent)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 10) return
        val action = pendingBluetoothAction
        pendingBluetoothAction = null
        if (requiredPermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            if (visible && action != null) withBluetooth(action)
        } else controller.disconnect("尚未允许蓝牙权限，请在应用权限设置中开启后重试。")
    }
    private fun requiredPermissions() = if (Build.VERSION.SDK_INT >= 31) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    private fun deviceSecondaryAction() {
        if (busy) return
        when { scanning -> stopScan(); checking() -> controller.disconnect("已取消检查，可重新连接"); else -> withBluetooth(::scan) }
        updateViews()
    }
    private fun scan() {
        stopScan()
        controller.disconnect("未连接")
        scanMessage = "正在搜索附近的戒指…"
        results.removeAllViews()
        scanning = true
        val generation = scanGeneration
        val client = RingBleClient(this, object : RingBleClient.Listener {
            private fun deliver(action: () -> Unit) { main.post { if (visible && generation == scanGeneration) action() } }
            override fun onBleState(message: String, ready: Boolean) = deliver {
                scanMessage = message
                if (message.startsWith("搜索完成") || message.startsWith("没有发现")) scanning = false
                updateViews()
            }
            override fun onBleError(message: String) = deliver { scanning = false; scanMessage = message; updateViews() }
            override fun onSensorPacket(packet: SensorPacket) = Unit
            override fun onRingsFound(rings: List<ScannedRing>) = deliver {
                results.removeAllViews()
                rings.forEach { ring ->
                    button(results, "${ring.name} · ${ring.address.takeLast(5)}", false) {
                        if (!busy && !checking()) {
                            stopScan()
                            controller.disconnect("未连接")
                            persist(afterSave = { saved -> if (visible) withBluetooth { connect(requireNotNull(saved.ring)) } }) {
                                store.selectRing(PreparedRing(ring.address, ring.name))
                            }
                        }
                    }
                }
            }
        })
        scanner = client
        runCatching { client.scanForRings() }.onFailure { stopScan(); controller.disconnect("搜索失败，请检查蓝牙和权限后重试") }
        updateViews()
    }
    private fun connect(ring: PreparedRing) { stopScan(); controller.connect(ring) }
    private fun stopScan() {
        ++scanGeneration
        scanning = false
        scanMessage = null
        runCatching { scanner?.stop() }
        scanner = null
        if (::results.isInitialized) results.removeAllViews()
    }
    private fun showDetails() {
        val state = controller.state
        AlertDialog.Builder(this).setTitle("设备详情").setMessage(
            "戒指：${snapshot?.ring?.name ?: "尚未选择"}\n" +
                "地址：${snapshot?.ring?.address ?: "—"}\n" +
                "电量：${state.batteryPercent?.let { "$it%" } ?: "待查询"}\n" +
                "固件：${state.firmwareVersion ?: "待查询"}\n" +
                "记录量：${state.healthStatus?.bytes?.let { "$it bytes" } ?: "待查询"}\n" +
                "记录数：${state.healthStatus?.records ?: "待查询"}\n" +
                "设备记录编号：${state.healthStatus?.sessionId ?: "待查询"}\n" +
                "状态码：${state.healthStatus?.errorCode ?: "待查询"}\n\n${state.message}"
        ).setPositiveButton("关闭", null).show()
    }
    private fun section(parent: LinearLayout, padding: Int = 0) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padding), dp(if (padding > 0) 8 else 0), dp(padding), dp(if (padding > 0) 8 else 0))
        parent.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
    private fun card(parent: LinearLayout) = section(parent, 16).apply {
        background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(12).toFloat() }
        (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(12)
    }
    private fun label(parent: LinearLayout, value: String, size: Float) = TextView(this).apply {
        text = value; textSize = size; setTextColor(Color.rgb(30, 49, 45)); setPadding(0, dp(4), 0, dp(4))
        parent.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
    private fun button(parent: LinearLayout, value: String, prominent: Boolean, action: () -> Unit) = Button(this).apply {
        text = value; textSize = 16f; isAllCaps = false; minHeight = dp(48)
        if (prominent) {
            backgroundTintList = ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(Color.rgb(210, 218, 214), Color.rgb(26, 92, 73)))
            setTextColor(Color.WHITE)
        } else {
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            setTextColor(Color.rgb(26, 75, 61))
            gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            elevation = 0f
            stateListAnimator = null
        }
        setOnClickListener { action() }
        parent.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object { private val disk = Executors.newSingleThreadExecutor() }
}
