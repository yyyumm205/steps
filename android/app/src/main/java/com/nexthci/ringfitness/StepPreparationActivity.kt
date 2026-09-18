package com.nexthci.ringfitness

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
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

/** T1 prepares a participant and device; it never starts the legacy capture/upload services. */
class StepPreparationActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var store: PreparationStore
    private lateinit var controller: RingPreparationController
    private var snapshot: PreparationSnapshot? = null
    private var loaded = false
    private var busy = false
    private var storageProblem: String? = null
    private var visible = false
    private var pendingBluetoothAction: (() -> Unit)? = null
    private var scanner: RingBleClient? = null
    private var scanGeneration = 0L
    private var scanMessage: String? = null
    private var placementDraft = 0
    private var hasRestoredDraft = false
    private var timedAttempt = -1L
    private lateinit var root: LinearLayout
    private lateinit var registration: LinearLayout
    private lateinit var participantInput: EditText
    private lateinit var registerButton: Button
    private lateinit var participantLabel: TextView
    private lateinit var storageLabel: TextView
    private lateinit var preparation: LinearLayout
    private lateinit var placementPicker: Spinner
    private lateinit var placementLabel: TextView
    private lateinit var savePlacement: Button
    private lateinit var ringLabel: TextView
    private lateinit var connectionLabel: TextView
    private lateinit var metadataLabel: TextView
    private lateinit var scanButton: Button
    private lateinit var reconnectButton: Button
    private lateinit var results: LinearLayout
    private lateinit var readinessLabel: TextView
    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) != BluetoothAdapter.STATE_ON) {
                stopScan()
                controller.disconnect("手机蓝牙已关闭，请打开后重新连接")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = PreparationStore(File(filesDir, "preparation/profile.properties"))
        placementDraft = savedInstanceState?.getInt("placement_draft") ?: 0
        hasRestoredDraft = savedInstanceState != null
        controller = RingPreparationController(AndroidPreparationTransport(this)) { state ->
            if (::connectionLabel.isInitialized) updateViews()
            if (state.connecting && state.attemptId != timedAttempt) {
                timedAttempt = state.attemptId
                main.postDelayed({ if (visible) controller.timeout(state.attemptId) }, 30_000)
            }
        }
        buildPage(savedInstanceState?.getString("participant_draft").orEmpty())
        loadProfile()
    }

    override fun onStart() {
        super.onStart()
        visible = true
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_EXPORTED)
        else registerReceiver(bluetoothReceiver, filter)
    }

    override fun onStop() {
        visible = false
        pendingBluetoothAction = null
        stopScan()
        controller.disconnect("未连接；重新打开后请连接并核对戒指状态")
        unregisterReceiver(bluetoothReceiver)
        super.onStop()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("participant_draft", participantInput.text.toString())
        outState.putInt("placement_draft", placementDraft)
        super.onSaveInstanceState(outState)
    }

    private fun buildPage(draft: String) {
        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(32))
            setBackgroundColor(Color.rgb(248, 250, 249))
        }
        scroll.addView(root)
        // Keep controls clear of system bars on Android 15/16 edge-to-edge layouts.
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
        label(root, "步数采集", 28f)
        label(root, "采集准备 · 测试版", 18f)
        label(root, "本版用于登记信息和检查戒指连接。完成这些检查后，等待研究者提供采集版本。")
        storageLabel = label(root, "正在读取本地准备信息…")
        participantLabel = label(root, "")
        registration = section(root)
        label(registration, "1  登记被试编号", 20f)
        label(registration, "填写研究者分配的编号，例如 P001。确认后会记在这台手机上。")
        participantInput = EditText(this).apply {
            hint = "3–24 位英文字母或数字"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(draft)
        }
        registration.addView(participantInput)
        registerButton = button(registration, "确认编号并保存") {
            val raw = participantInput.text.toString()
            persist { store.register(raw) }
        }
        preparation = section(root)
        label(preparation, "2  确认戒指佩戴位置", 20f)
        placementLabel = label(preparation, "")
        placementPicker = Spinner(this).apply {
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
        preparation.addView(placementPicker)
        savePlacement = button(preparation, "确认并记住位置") {
            RingPlacement.entries.getOrNull(placementDraft - 1)?.let { placement ->
                persist { store.savePlacement(placement) }
            }
        }
        label(preparation, "3  连接测试戒指", 20f)
        ringLabel = label(preparation, "")
        connectionLabel = label(preparation, "")
        metadataLabel = label(preparation, "")
        reconnectButton = button(preparation, "连接已记住的戒指 / 重新检查") {
            withBluetooth { snapshot?.ring?.let(::connect) }
        }
        scanButton = button(preparation, "搜索戒指") { withBluetooth(::scan) }
        button(preparation, "蓝牙或权限设置") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        results = section(preparation)
        readinessLabel = label(preparation, "")
        label(root, "每次正式采集前：站定、佩戴好戒指和计步器，将计步器清零。等待戒指确认开始后，再正常活动。")
        button(root, "开始采集（后续版本开放）") {}.isEnabled = false
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
                    storageLabel.text = if (it == null) "编号和佩戴位置可在离线状态保存" else "已恢复本机保存的准备信息"
                    if (!hasRestoredDraft) {
                        placementDraft = it?.placement?.ordinal?.plus(1) ?: 0
                        placementPicker.setSelection(placementDraft)
                    }
                }.onFailure {
                    storageProblem = "准备信息读取失败，已保留原文件。请联系研究者处理后重新打开。"
                }
                updateViews()
            }
        }
    }

    private fun persist(afterSave: (PreparationSnapshot) -> Unit = {}, write: () -> PreparationSnapshot) {
        if (busy || storageProblem != null) return
        busy = true
        storageLabel.text = "正在保存，请稍候…"
        updateViews()
        disk.execute {
            val result = runCatching(write)
            main.post {
                if (isDestroyed) return@post
                busy = false
                result.onSuccess {
                    snapshot = it
                    storageLabel.text = "准备信息已保存在本机"
                    if (visible) afterSave(it)
                }.onFailure {
                    storageLabel.text = if (it is IllegalArgumentException) it.message
                    else "保存未成功；请检查手机剩余空间后重试，原已保存信息保持不变。"
                }
                updateViews()
            }
        }
    }

    private fun updateViews() {
        if (!::readinessLabel.isInitialized) return
        storageProblem?.let { storageLabel.text = it }
        registration.visibility = if (loaded && snapshot == null && storageProblem == null) View.VISIBLE else View.GONE
        preparation.visibility = if (snapshot != null && storageProblem == null) View.VISIBLE else View.GONE
        registerButton.isEnabled = loaded && !busy && storageProblem == null
        participantInput.isEnabled = !busy
        participantLabel.text = snapshot?.let { "被试编号：${it.participantId}（本机已记住）" }.orEmpty()
        placementLabel.text = "已确认位置：${snapshot?.placement?.displayName ?: "待确认"}"
        val draft = RingPlacement.entries.getOrNull(placementDraft - 1)
        placementPicker.isEnabled = !busy
        savePlacement.isEnabled = !busy && draft != null && draft != snapshot?.placement
        ringLabel.text = snapshot?.ring?.let { "已记住：${it.name}\n${it.address}" } ?: "尚未选择戒指"
        val state = controller.state
        connectionLabel.text = scanMessage ?: state.message
        metadataLabel.text = "电量：${state.batteryPercent?.let { "$it%" } ?: "未取得"}　固件：${state.firmwareVersion ?: "未取得"}"
        reconnectButton.isEnabled = !busy && snapshot?.ring != null
        scanButton.isEnabled = !busy
        val placementConfirmed = draft != null && draft == snapshot?.placement
        readinessLabel.text = when {
            busy -> "正在保存准备信息…"
            !placementConfirmed -> "请先确认并保存当前佩戴位置"
            !state.canPrepare -> "请完成戒指状态检查"
            state.ring != snapshot?.ring -> "请重新连接已保存的戒指"
            else -> "准备信息已齐全。此版本停在准备阶段，尚未开始采集。"
        }
    }

    private fun withBluetooth(action: () -> Unit) {
        val missing = requiredPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            pendingBluetoothAction = action
            requestPermissions(missing.toTypedArray(), 10)
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter?.isEnabled != true) {
            controller.disconnect("请在手机设置中打开蓝牙，然后重试")
            return
        }
        if (Build.VERSION.SDK_INT <= 30 && !getSystemService(android.location.LocationManager::class.java).isLocationEnabled) {
            controller.disconnect("Android 11 搜索戒指需要打开系统定位开关，请打开后重试")
            return
        }
        action()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 10) return
        val action = pendingBluetoothAction
        pendingBluetoothAction = null
        if (requiredPermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            if (visible && action != null) withBluetooth(action)
        } else controller.disconnect("蓝牙权限尚未允许；可在应用设置中允许后重试，已保存信息仍保留")
    }

    private fun requiredPermissions() = if (Build.VERSION.SDK_INT >= 31) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else listOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun scan() {
        stopScan()
        controller.disconnect("正在搜索附近的 Ringo 戒指…")
        results.removeAllViews()
        val generation = scanGeneration
        val client = RingBleClient(this, object : RingBleClient.Listener {
            private fun deliver(action: () -> Unit) { main.post { if (visible && generation == scanGeneration) action() } }
            override fun onBleState(message: String, ready: Boolean) = deliver {
                scanMessage = message
                updateViews()
            }
            override fun onBleError(message: String) = deliver {
                scanMessage = message
                updateViews()
            }
            override fun onSensorPacket(packet: SensorPacket) = Unit
            override fun onRingsFound(rings: List<ScannedRing>) = deliver {
                results.removeAllViews()
                rings.forEach { ring ->
                    button(results, "${ring.name} · ${ring.address}") {
                        if (!busy) {
                            stopScan()
                            results.removeAllViews()
                            controller.disconnect("未连接；保存所选戒指后继续连接")
                            val selected = PreparedRing(ring.address, ring.name)
                            persist(afterSave = { saved -> withBluetooth { connect(requireNotNull(saved.ring)) } }) {
                                store.selectRing(selected)
                            }
                        }
                    }
                }
            }
        })
        scanner = client
        runCatching { client.scanForRings() }.onFailure {
            stopScan()
            controller.disconnect("搜索失败，请检查蓝牙和权限后重试")
        }
    }

    private fun connect(ring: PreparedRing) {
        stopScan()
        results.removeAllViews()
        controller.connect(ring)
    }

    private fun stopScan() {
        ++scanGeneration
        scanMessage = null
        runCatching { scanner?.stop() }
        scanner = null
    }

    private fun section(parent: LinearLayout) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(12), 0, dp(12))
        parent.addView(this)
    }

    private fun label(parent: LinearLayout, value: String, size: Float = 16f) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.rgb(30, 49, 45))
        setPadding(0, dp(8), 0, dp(8))
        parent.addView(this)
    }

    private fun button(parent: LinearLayout, value: String, action: () -> Unit) = Button(this).apply {
        text = value
        isAllCaps = false
        setOnClickListener { action() }
        parent.addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        // Reads after Activity recreation follow writes accepted by the previous Activity.
        private val disk = Executors.newSingleThreadExecutor()
    }
}
