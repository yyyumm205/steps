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
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors

/** Preparation stays separate from capture requests, downloads and uploads. */
class StepPreparationActivity : Activity() {
    private enum class Page { REGISTER, HOME, DEVICES }
    private enum class HomeAction { PLACEMENT, CONNECT, SETTINGS, DETAILS, CAPTURE, NONE }
    private data class HomeUi(
        val status: String,
        val actionText: String,
        val action: HomeAction,
        val explanation: String = "",
        val waiting: Boolean = false,
    )
    private val main = Handler(Looper.getMainLooper())
    private val ui by lazy { QuietUi(this) }
    private lateinit var store: PreparationStore
    private lateinit var controller: RingPreparationController
    private var snapshot: PreparationSnapshot? = null
    private var page = Page.REGISTER
    private var restoredPage: Page? = null
    private var placementDraft = 0
    private var loaded = false
    private var busy = false
    private var storageProblem: String? = null
    private var feedback: String? = null
    private var visible = false
    private var permissionDenied = false
    private var autoConnectPending = false
    private var pendingBluetoothAction: (() -> Unit)? = null
    private var scanner: RingBleClient? = null
    private var scanning = false
    private var scanGeneration = 0L
    private var scanMessage: String? = null
    private var timedAttempt = -1L
    private lateinit var collectionJournal: File
    private var collectionLoaded = false
    private var collectionPending = false
    private var collectionHistory = false
    private var collectionProblem: String? = null
    private var collectionReadGeneration = 0L
    private var collectionSubscription: AutoCloseable? = null
    private var collectionOwnerSnapshot: Triple<Boolean, String?, Boolean>? = null
    private var placementDialog: AlertDialog? = null
    private var moreMenu: PopupMenu? = null
    private lateinit var back: Button
    private lateinit var heading: TextView
    private lateinit var subtitle: TextView
    private lateinit var details: Button
    private lateinit var scroll: ScrollView
    private lateinit var registration: LinearLayout
    private lateinit var participantInput: EditText
    private lateinit var placementPicker: Spinner
    private lateinit var devices: LinearLayout
    private lateinit var scanStatus: TextView
    private lateinit var results: LinearLayout
    private lateinit var overview: LinearLayout
    private lateinit var participantLabel: TextView
    private lateinit var editPlacement: Button
    private lateinit var homeStatus: TextView
    private lateinit var homeExplanation: TextView
    private lateinit var cancelConnection: Button
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
        RealUploadScheduler.restore(applicationContext)
        window.setDecorFitsSystemWindows(false)
        store = PreparationStore(File(filesDir, "preparation/profile.properties"))
        collectionJournal = File(filesDir, "collection-real/session.json")
        restoredPage = savedInstanceState?.getString("page")?.let { runCatching { Page.valueOf(it) }.getOrNull() }
        placementDraft = savedInstanceState?.getInt("placement_draft") ?: 0
        permissionDenied = savedInstanceState?.getBoolean("permission_denied") ?: false
        autoConnectPending = savedInstanceState == null
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
        collectionSubscription = RealCollectionBridge.observe { state ->
            if (!visible || isDestroyed) return@observe
            val ownership = Triple(RealCollectionBridge.isRunning(), state.session?.sessionId, state.session?.localData != null)
            if (ownership != collectionOwnerSnapshot) {
                collectionOwnerSnapshot = ownership
                if (ownership.first) {
                    stopScan()
                    controller.disconnect("未连接")
                    placementDialog?.dismiss()
                }
                refreshCollectionState()
            }
        }
    }
    override fun onResume() {
        super.onResume()
        refreshCollectionState()
    }
    override fun onStop() {
        visible = false
        collectionSubscription?.close()
        collectionSubscription = null
        moreMenu?.dismiss()
        pendingBluetoothAction = null
        stopScan()
        controller.disconnect("未连接")
        unregisterReceiver(bluetoothReceiver)
        super.onStop()
    }
    override fun onDestroy() { placementDialog?.dismiss(); main.removeCallbacksAndMessages(null); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("page", page.name)
        outState.putString("participant_draft", participantInput.text.toString())
        outState.putInt("placement_draft", placementDraft)
        outState.putBoolean("permission_denied", permissionDenied)
        super.onSaveInstanceState(outState)
    }
    @Deprecated("Legacy Android back callback")
    override fun onBackPressed() = goBack()

    private fun goBack() {
        if (busy) return
        if (page == Page.DEVICES) navigate(Page.HOME) else finish()
    }
    private fun navigate(next: Page) {
        if (busy) return
        if (page == Page.DEVICES && next != Page.DEVICES) stopScan()
        page = next
        feedback = null
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(participantInput.windowToken, 0)
        updateViews()
        scroll.scrollTo(0, 0)
    }

    private fun buildPages(draft: String) {
        val frame = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ui.background)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        setContentView(frame)
        val header = section(frame, 20)
        back = link(header, "返回") { goBack() }.apply {
            tag = "back"
            layoutParams = LinearLayout.LayoutParams(-2, -2)
        }
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        header.addView(titleRow, LinearLayout.LayoutParams(-1, -2))
        heading = label(titleRow, "步数采集", 28f).apply {
            tag = "heading"; setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        details = link(titleRow, if (BuildConfig.DEBUG) "更多" else "设备详情") {
            if (BuildConfig.DEBUG) showMore() else showDetails()
        }.apply {
            tag = "details"; textSize = 14f
            layoutParams = LinearLayout.LayoutParams(-2, -2)
        }
        subtitle = label(header, "", 14f).apply { setTextColor(ui.secondary) }
        scroll = ScrollView(this).apply { isFillViewport = true }
        frame.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(12)) }
        scroll.addView(body)
        registration = ui.card(body)
        label(registration, "被试编号", 16f)
        participantInput = EditText(this).apply {
            tag = "participant_input"
            hint = "研究者提供的编号"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(draft)
            ui.styleInput(this)
        }
        registration.addView(participantInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        label(registration, "戒指佩戴位置", 16f).setPadding(0, dp(24), 0, dp(4))
        placementPicker = Spinner(this).apply {
            tag = "placement_picker"
            adapter = ui.placementAdapter(listOf("请选择") + RingPlacement.entries.map { it.displayName })
            setSelection(placementDraft)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    placementDraft = position
                    updateViews()
                }
            }
        }
        placementPicker.minimumHeight = dp(56)
        registration.addView(placementPicker, LinearLayout.LayoutParams(-1, -2))
        overview = section(body)
        val profileCard = ui.card(overview)
        participantLabel = label(profileCard, "", 16f).apply { tag = "participant_summary" }
        editPlacement = link(profileCard, "") { showPlacementPicker() }.apply { tag = "edit_placement" }
        val connectionCard = ui.card(overview, ui.statusSurface)
        homeStatus = label(connectionCard, "", 28f).apply {
            tag = "device_status"; setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        homeExplanation = label(connectionCard, "", 16f)
        cancelConnection = link(connectionCard, "取消连接") {
            controller.disconnect("已取消连接")
        }.apply { tag = "connection_cancel"; setTextColor(ui.ink) }
        devices = section(body)
        val scanCard = ui.card(devices)
        scanStatus = label(scanCard, "", 16f)
        results = section(devices)
        val footer = section(frame, 20)
        message = label(footer, "", 14f).apply { tag = "feedback"; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        primary = button(footer, "", true) { primaryAction() }.apply { tag = "primary" }
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
                }.onFailure { storageProblem = "准备信息读取失败，请联系研究者。原信息已保留。" }
                restoredPage = null
                updateViews()
                prepareOnOpen()
            }
        }
    }

    /** Pending work locks preparation; completed history keeps only its navigation entry. */
    private fun refreshCollectionState() {
        val generation = ++collectionReadGeneration
        val journal = collectionJournal
        collectionLoaded = false
        updateViews()
        disk.execute {
            val result = runCatching { FreeLivingSessionStore(journal).read() }
            main.post {
                if (isDestroyed || generation != collectionReadGeneration) return@post
                collectionLoaded = true
                result.onSuccess { session ->
                    collectionPending = session?.isPending == true
                    collectionHistory = session != null
                    collectionProblem = null
                }.onFailure {
                    collectionPending = true
                    collectionHistory = true
                    collectionProblem = "采集记录读取失败，请联系研究者。"
                }
                updateViews()
                prepareOnOpen()
            }
        }
    }
    private fun persist(afterSave: (PreparationSnapshot) -> Unit, onFailure: (() -> Unit)? = null, write: () -> PreparationSnapshot) {
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
                    feedback = if (it is IllegalArgumentException) it.message else "保存失败，请检查手机空间后重试。"
                    onFailure?.invoke()
                }
                updateViews()
            }
        }
    }
    private fun checking(): Boolean = controller.state.let {
        it.connecting || (it.connected && !it.queryTimedOut &&
            (it.healthStatus == null || it.batteryPercent == null || it.firmwareVersion == null))
    }
    private fun homeUi(): HomeUi {
        val state = controller.state
        val disconnected = state.message in setOf("未连接", "手机蓝牙已关闭", "尚未允许蓝牙权限")
        return when {
            snapshot?.placement == null -> HomeUi("补充佩戴位置", "选择佩戴位置", HomeAction.PLACEMENT)
            requiredPermissions().any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED } -> {
                val permission = if (Build.VERSION.SDK_INT >= 31) "蓝牙" else "定位"
                HomeUi("需要${permission}权限", if (permissionDenied) "打开权限设置" else "允许${permission}权限",
                    if (permissionDenied) HomeAction.SETTINGS else HomeAction.CONNECT)
            }
            !bluetoothEnabled() -> HomeUi("手机蓝牙已关闭", "打开蓝牙", HomeAction.SETTINGS)
            !locationEnabled() -> HomeUi("手机定位已关闭", "打开定位", HomeAction.SETTINGS)
            !collectionLoaded -> HomeUi("正在读取记录", "正在读取…", HomeAction.NONE, waiting = true)
            collectionOwnsDevice() || collectionHistory -> HomeUi("采集记录", "进入采集", HomeAction.CAPTURE,
                collectionProblem.orEmpty())
            state.connecting -> HomeUi("正在连接戒指", "正在连接…", HomeAction.NONE, waiting = true)
            checking() -> HomeUi("正在检查戒指", "正在检查…", HomeAction.NONE, waiting = true)
            state.queryTimedOut -> HomeUi("连接检查未完成", "重试连接", HomeAction.CONNECT, state.message)
            state.connected && !state.canPrepare -> HomeUi(
                "戒指已连接", "进入采集", HomeAction.CAPTURE, state.message,
            )
            state.canPrepare -> HomeUi("准备完成", "进入采集", HomeAction.CAPTURE)
            else -> HomeUi(
                if (disconnected) "戒指未连接" else state.message,
                if (disconnected || state.message == "已取消连接") "连接戒指" else "重试连接",
                HomeAction.CONNECT,
            )
        }
    }

    private fun primaryAction() {
        if (busy || !loaded || storageProblem != null) return
        feedback = null
        when (page) {
            Page.REGISTER -> {
                val selected = RingPlacement.entries.getOrNull(placementDraft - 1) ?: return
                val raw = participantInput.text.toString()
                persist(afterSave = {
                    navigate(Page.HOME)
                    if (visible) connectPreparedRing()
                }) { store.register(raw, selected) }
            }
            Page.HOME -> when (homeUi().action) {
                HomeAction.PLACEMENT -> showPlacementPicker()
                HomeAction.CONNECT -> connectPreparedRing()
                HomeAction.SETTINGS -> openNeededSettings()
                HomeAction.DETAILS -> showDetails()
                HomeAction.CAPTURE -> openCollection()
                HomeAction.NONE -> Unit
            }
            Page.DEVICES -> if (scanning) {
                stopScan()
                scanMessage = "已停止搜索"
                updateViews()
            } else withBluetooth(::scan)
        }
    }

    private fun connectPreparedRing() {
        autoConnectPending = false
        withBluetooth {
            snapshot?.ring?.let(::connect) ?: run { navigate(Page.DEVICES); scan() }
        }
    }

    private fun prepareOnOpen() {
        if (!autoConnectPending || !visible || !loaded || busy || !collectionLoaded) return
        autoConnectPending = false
        if (collectionOwnsDevice() || collectionHistory) return
        if (storageProblem != null || snapshot?.placement == null) return
        val ring = snapshot?.ring ?: return
        if (requiredPermissions().any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) return
        if (bluetoothEnabled() && locationEnabled()) connect(ring)
    }

    private fun updateViews() {
        if (!::primary.isInitialized) return
        val usable = loaded && storageProblem == null
        val ui = homeUi()
        registration.visibility = if (usable && page == Page.REGISTER) View.VISIBLE else View.GONE
        overview.visibility = if (usable && page == Page.HOME) View.VISIBLE else View.GONE
        devices.visibility = if (usable && page == Page.DEVICES) View.VISIBLE else View.GONE
        back.visibility = if (usable && page == Page.DEVICES) View.VISIBLE else View.GONE
        back.isEnabled = !busy
        details.visibility = if (BuildConfig.DEBUG || (usable && page == Page.HOME && ui.action != HomeAction.DETAILS)) View.VISIBLE else View.GONE
        details.isEnabled = !busy && (BuildConfig.DEBUG || !ui.waiting)
        heading.text = when (page) { Page.REGISTER -> "填写准备信息"; Page.HOME -> "步数采集"; Page.DEVICES -> "选择戒指" }
        subtitle.text = when (page) {
            Page.REGISTER -> "第 1 步 · 填写信息"
            Page.HOME -> when {
                snapshot?.placement == null -> "补充准备信息"
                ui.action in setOf(HomeAction.DETAILS, HomeAction.CAPTURE) || (ui.action == HomeAction.NONE && !ui.waiting) -> "采集准备"
                else -> "第 2 步 · 连接戒指"
            }
            Page.DEVICES -> "点击你的戒指即可连接"
        }
        participantInput.isEnabled = !busy
        placementPicker.isEnabled = !busy
        participantLabel.text = "编号：${snapshot?.participantId.orEmpty()}"
        editPlacement.text = snapshot?.placement?.let { "${it.displayName} · 修改" } ?: "选择佩戴位置"
        editPlacement.isEnabled = !busy && !ui.waiting && !collectionOwnsDevice()
        homeStatus.text = ui.status
        homeExplanation.text = ui.explanation
        homeExplanation.visibility = if (ui.explanation.isEmpty()) View.GONE else View.VISIBLE
        cancelConnection.visibility = if (ui.waiting && checking()) View.VISIBLE else View.GONE
        cancelConnection.isEnabled = !busy
        scanStatus.text = scanMessage ?: "将戒指放在手机附近，点击重新搜索。"
        primary.text = when {
            !loaded -> "正在读取…"
            busy -> "正在保存…"
            storageProblem != null -> "准备信息需要处理"
            page == Page.REGISTER -> "保存并连接戒指"
            page == Page.HOME -> ui.actionText
            scanning -> "停止搜索"
            else -> "重新搜索"
        }
        primary.isEnabled = usable && !busy && when (page) {
            Page.REGISTER -> placementDraft > 0
            Page.HOME -> ui.action != HomeAction.NONE
            Page.DEVICES -> collectionLoaded
        }
        message.text = storageProblem ?: feedback.orEmpty()
        message.visibility = if (message.text.isEmpty()) View.GONE else View.VISIBLE
        placementDialog?.let { dialog ->
            dialog.setCancelable(!busy)
            dialog.setCanceledOnTouchOutside(!busy)
            dialog.listView.isEnabled = !busy
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = !busy
        }
    }

    private fun showMore() {
        if (!BuildConfig.DEBUG || busy) return
        moreMenu?.dismiss()
        moreMenu = PopupMenu(this, details).apply {
            if (snapshot != null && page == Page.HOME) menu.add(0, 1, 0, "设备详情").isEnabled = !checking()
            menu.add(0, 2, 1, "流程演示（模拟）")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> showDetails()
                    2 -> startActivity(Intent().setClassName(packageName, "com.nexthci.ringfitness.DemoCollectionActivity"))
                }
                true
            }
            setOnDismissListener { moreMenu = null }
            show()
        }
    }

    private fun showPlacementPicker() {
        if (busy || collectionOwnsDevice() || snapshot == null || storageProblem != null || placementDialog?.isShowing == true) return
        feedback = null
        val dialog = AlertDialog.Builder(this)
            .setTitle("佩戴位置")
            .setSingleChoiceItems(RingPlacement.entries.map { it.displayName }.toTypedArray(), snapshot?.placement?.ordinal ?: -1) { _, index ->
                if (busy || collectionOwnsDevice()) return@setSingleChoiceItems
                val chosen = RingPlacement.entries[index]
                if (chosen == snapshot?.placement) placementDialog?.dismiss()
                else persist(
                    afterSave = {
                        placementDialog?.dismiss()
                        feedback = "佩戴位置已保存"
                    },
                    onFailure = {
                        placementDialog?.setTitle("保存失败，请重新选择")
                        placementDialog?.listView?.apply {
                            clearChoices()
                            snapshot?.placement?.ordinal?.let { setItemChecked(it, true) }
                            requestLayout()
                        }
                    },
                ) { store.savePlacement(chosen) }
            }
            .setNegativeButton("取消", null)
            .create()
        placementDialog = dialog
        dialog.setOnDismissListener { if (placementDialog === dialog) placementDialog = null }
        dialog.show()
        updateViews()
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
        permissionDenied = requiredPermissions().any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (!permissionDenied) {
            if (visible && action != null) withBluetooth(action)
        } else controller.disconnect("尚未允许蓝牙权限")
        updateViews()
    }
    private fun requiredPermissions() = if (Build.VERSION.SDK_INT >= 31) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    private fun scan() {
        if (!collectionLoaded) return
        if (collectionOwnsDevice()) { openCollection(); return }
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
                        if (!busy && !checking() && !collectionOwnsDevice()) {
                            stopScan()
                            controller.disconnect("未连接")
                            persist(afterSave = { saved ->
                                navigate(Page.HOME)
                                if (visible) withBluetooth { connect(requireNotNull(saved.ring)) }
                            }) {
                                store.selectRing(PreparedRing(ring.address, ring.name))
                            }
                        }
                    }
                }
            }
        })
        scanner = client
        runCatching { client.scanForRings() }.onFailure { stopScan(); scanMessage = "搜索失败，请检查蓝牙和权限后重试。" }
        updateViews()
    }
    private fun connect(ring: PreparedRing) {
        if (collectionOwnsDevice()) return
        stopScan(); controller.connect(ring)
    }
    private fun collectionOwnsDevice() = RealCollectionBridge.isRunning() || !collectionLoaded || collectionPending
    private fun openCollection() {
        stopScan()
        controller.disconnect("未连接")
        autoConnectPending = false
        startActivity(Intent(this, RealCollectionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }
    private fun stopScan() {
        ++scanGeneration
        scanning = false
        scanMessage = null
        runCatching { scanner?.stop() }
        scanner = null
        if (::results.isInitialized) results.removeAllViews()
    }
    private fun showDetails() {
        if (busy || checking()) return
        val state = controller.state
        AlertDialog.Builder(this).setTitle("设备详情").setMessage(
            "戒指：${snapshot?.ring?.name ?: "尚未选择"}\n" +
                "地址：${snapshot?.ring?.address ?: "—"}\n" +
                "电量：${state.batteryPercent?.let { "$it%" } ?: "待查询"}\n" +
                "固件：${state.firmwareVersion ?: "待查询"}\n" +
                "记录量：${state.healthStatus?.bytes?.let { "$it bytes" } ?: "待查询"}\n" +
                "记录数：${state.healthStatus?.records ?: "待查询"}\n" +
                "设备记录编号：${state.healthStatus?.sessionId ?: "待查询"}\n" +
                "状态码：${state.healthStatus?.errorCode ?: "待查询"}"
        ).setPositiveButton("关闭", null)
            .setNeutralButton("更换戒指") { _, _ -> if (!collectionOwnsDevice()) withBluetooth { navigate(Page.DEVICES); scan() } }
            .setNegativeButton("系统设置") { _, _ -> openNeededSettings() }
            .show()
            .getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = !collectionOwnsDevice()
    }
    private fun section(parent: LinearLayout, padding: Int = 0) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padding), dp(if (padding > 0) 8 else 0), dp(padding), dp(if (padding > 0) 8 else 0))
        parent.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
    private fun label(parent: LinearLayout, value: String, size: Float) = ui.text(parent, value, size).apply {
        setPadding(0, dp(4), 0, dp(4))
    }
    private fun button(parent: LinearLayout, value: String, prominent: Boolean, action: () -> Unit) =
        ui.button(parent, value, primary = prominent, action = action)
    private fun link(parent: LinearLayout, value: String, action: () -> Unit) = ui.button(parent, value, action = action).apply {
        background = ui.linkBackground()
        minHeight = dp(48)
        minimumHeight = dp(48)
        gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        setPadding(0, dp(12), 0, dp(12))
        (layoutParams as LinearLayout.LayoutParams).topMargin = 0
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object { private val disk = Executors.newSingleThreadExecutor() }
}
