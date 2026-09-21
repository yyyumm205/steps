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
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors

/** Collects durable setup choices; the foreground collection service owns every ring connection. */
class StepPreparationActivity : Activity() {
    private enum class Page { REGISTER, DEVICES, SETTINGS }

    private val main = Handler(Looper.getMainLooper())
    private val ui by lazy { QuietUi(this) }
    private lateinit var store: PreparationStore
    private lateinit var collectionJournal: File
    private var snapshot: PreparationSnapshot? = null
    private var page = Page.REGISTER
    private var restoredPage: Page? = null
    private var settingsRequested = false
    private var returnToSettings = false
    private var placementDraft = 0
    private var loaded = false
    private var busy = false
    private var visible = false
    private var routeStarted = false
    private var storageProblem: String? = null
    private var profileUnreadable = false
    private var feedback: String? = null
    private var permissionDenied = false
    private var pendingBluetoothAction: (() -> Unit)? = null
    private var scanner: RingBleClient? = null
    private var scanning = false
    private var scanGeneration = 0L
    private var scanMessage: String? = null
    private var collectionLoaded = false
    private var collectionPending = false
    private var collectionOwnerBusy = false
    private var collectionProblem: String? = null
    private var collectionReadGeneration = 0L
    private var collectionSubscription: AutoCloseable? = null
    private var collectionOwnerSnapshot: Triple<String?, Boolean, Boolean>? = null
    private var placementDialog: AlertDialog? = null
    private var identityDialog: AlertDialog? = null
    private var clearDialog: AlertDialog? = null
    private var moreMenu: PopupMenu? = null
    private var continueToCollectionAfterNotification = false

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
    private lateinit var settings: LinearLayout
    private lateinit var participantLabel: TextView
    private lateinit var placementLabel: TextView
    private lateinit var ringLabel: TextView
    private lateinit var changeIdentity: Button
    private lateinit var editPlacement: Button
    private lateinit var changeRing: Button
    private lateinit var clearProfile: Button
    private lateinit var backgroundAccess: LinearLayout
    private lateinit var notificationSettings: Button
    private lateinit var batterySettings: Button
    private lateinit var message: TextView
    private lateinit var progressRow: LinearLayout
    private lateinit var progressStatus: TextView
    private lateinit var primary: Button

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) != BluetoothAdapter.STATE_ON) {
                stopScan()
                scanMessage = "手机蓝牙已关闭"
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
        settingsRequested = intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)
        restoredPage = savedInstanceState?.getString(STATE_PAGE)?.let { runCatching { Page.valueOf(it) }.getOrNull() }
        placementDraft = savedInstanceState?.getInt(STATE_PLACEMENT) ?: 0
        permissionDenied = savedInstanceState?.getBoolean(STATE_PERMISSION_DENIED) ?: false
        returnToSettings = savedInstanceState?.getBoolean(STATE_RETURN_TO_SETTINGS) ?: false
        buildPages(savedInstanceState?.getString(STATE_PARTICIPANT_DRAFT).orEmpty())
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            ) { goBack() }
        }
        loadProfile()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.getBooleanExtra(EXTRA_OPEN_SETTINGS, false) == true) settingsRequested = true
        routeStarted = false
        routeWhenReady()
    }

    override fun onStart() {
        super.onStart()
        visible = true
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_EXPORTED)
        else registerReceiver(bluetoothReceiver, filter)
        collectionSubscription = RealCollectionBridge.observe { state ->
            if (!visible || isDestroyed) return@observe
            val ownership = Triple(state.session?.sessionId, state.session?.isPending == true,
                RealCollectionBridge.isRunning())
            if (ownership != collectionOwnerSnapshot) {
                collectionOwnerSnapshot = ownership
                refreshCollectionState()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        routeStarted = false
        refreshCollectionState()
    }

    override fun onStop() {
        visible = false
        collectionSubscription?.close()
        collectionSubscription = null
        moreMenu?.dismiss()
        pendingBluetoothAction = null
        stopScan()
        unregisterReceiver(bluetoothReceiver)
        super.onStop()
    }

    override fun onDestroy() {
        placementDialog?.dismiss()
        identityDialog?.dismiss()
        clearDialog?.dismiss()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_PAGE, page.name)
        outState.putString(STATE_PARTICIPANT_DRAFT, participantInput.text.toString())
        outState.putInt(STATE_PLACEMENT, placementDraft)
        outState.putBoolean(STATE_PERMISSION_DENIED, permissionDenied)
        outState.putBoolean(STATE_RETURN_TO_SETTINGS, returnToSettings)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Legacy Android back callback")
    override fun onBackPressed() = goBack()

    private fun goBack() {
        when (page) {
            Page.DEVICES -> {
                stopScan()
                page = if (returnToSettings && snapshot != null) Page.SETTINGS else Page.REGISTER
                feedback = null
                updateViews()
            }
            Page.SETTINGS -> openCollectionAndFinish()
            // An underlying collection page may still hold the previous profile after a switch.
            Page.REGISTER -> finishAffinity()
        }
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
        back = link(header, "关闭") { goBack() }.apply {
            tag = "back"
            layoutParams = LinearLayout.LayoutParams(-2, -2)
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        header.addView(titleRow, LinearLayout.LayoutParams(-1, -2))
        heading = label(titleRow, "开始使用", 28f).apply {
            tag = "heading"
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        details = link(titleRow, "更多") { showMore() }.apply {
            tag = "details"
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(-2, -2)
        }
        subtitle = label(header, "", 14f).apply { setTextColor(ui.secondary) }
        scroll = ScrollView(this).apply { isFillViewport = true }
        frame.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        scroll.addView(body)

        registration = ui.card(body)
        label(registration, "用户名", 16f).setPadding(0, 0, 0, dp(4))
        participantInput = EditText(this).apply {
            tag = "participant_input"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            hint = "3–24 位字母或数字"
            setText(draft)
            ui.styleInput(this)
        }
        registration.addView(participantInput, LinearLayout.LayoutParams(-1, -2))
        label(registration, "戒指佩戴位置", 16f).setPadding(0, dp(24), 0, dp(4))
        placementPicker = Spinner(this).apply {
            tag = "placement_picker"
            adapter = ui.placementAdapter(listOf("请选择") + RingPlacement.entries.map { it.displayName })
            setSelection(placementDraft)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    placementDraft = position
                    feedback = null
                    updateViews()
                }
            }
        }
        placementPicker.minimumHeight = dp(56)
        registration.addView(placementPicker, LinearLayout.LayoutParams(-1, -2))

        devices = section(body)
        val scanCard = ui.card(devices)
        scanStatus = label(scanCard, "", 16f).apply { tag = "scan_status" }
        results = section(devices)

        settings = section(body)
        val profileCard = ui.card(settings)
        participantLabel = label(profileCard, "", 18f).apply {
            tag = "participant_summary"
            setTypeface(null, Typeface.BOLD)
        }
        placementLabel = label(profileCard, "", 16f).apply { tag = "placement_summary" }
        ringLabel = label(profileCard, "", 16f).apply { tag = "ring_summary" }
        changeIdentity = link(profileCard, "更换用户") { showIdentityPicker() }.apply { tag = "change_identity" }
        editPlacement = link(profileCard, "修改佩戴位置") { showPlacementPicker() }.apply { tag = "edit_placement" }
        changeRing = link(profileCard, "更换戒指") { openDeviceSelection(fromSettings = true) }.apply { tag = "change_ring" }
        clearProfile = link(profileCard, "退出当前用户") { confirmClearProfile() }.apply {
            tag = "clear_profile"
        }

        backgroundAccess = ui.card(body).apply { tag = "background_access" }
        label(backgroundAccess, "后台运行", 16f).setTypeface(null, Typeface.BOLD)
        notificationSettings = link(backgroundAccess, "开启通知") { openNotificationSettings() }.apply {
            tag = "notification_settings"
        }
        batterySettings = link(backgroundAccess, "允许后台运行") { openBatterySettings() }.apply {
            tag = "battery_settings"
        }

        val footer = section(frame, 20)
        message = label(footer, "", 14f).apply {
            tag = "feedback"
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        progressRow = LinearLayout(this).apply {
            tag = "loading_state"
            gravity = android.view.Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        footer.addView(progressRow, LinearLayout.LayoutParams(-1, dp(48)))
        progressRow.addView(ProgressBar(this).apply {
            tag = "loading_indicator"
            isIndeterminate = true
        }, LinearLayout.LayoutParams(dp(24), dp(24)))
        progressStatus = label(progressRow, "", 14f).apply {
            tag = "loading_status"
            setTextColor(ui.secondary)
            setPadding(dp(12), 0, 0, 0)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
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
                result.onSuccess { loadedSnapshot ->
                    snapshot = loadedSnapshot
                    storageProblem = null
                    profileUnreadable = false
                    loadedSnapshot?.let {
                        participantInput.setText(it.displayLabel)
                        placementDraft = it.placement?.ordinal?.plus(1) ?: 0
                        placementPicker.setSelection(placementDraft)
                    }
                    restoredPage?.let { page = it }
                }.onFailure { error ->
                    profileUnreadable = error is PreparationProfileUnreadableException
                    storageProblem = if (profileUnreadable) {
                        error.message ?: "用户资料需要重新填写，已有采集记录仍保留"
                    } else {
                        "用户资料暂时无法读取，请重新打开 App"
                    }
                }
                restoredPage = null
                updateViews()
                routeWhenReady()
            }
        }
    }

    private fun refreshCollectionState() {
        val generation = ++collectionReadGeneration
        collectionLoaded = false
        updateViews()
        disk.execute {
            val result = runCatching { FreeLivingSessionStore(collectionJournal).read() }
            main.post {
                if (isDestroyed || generation != collectionReadGeneration) return@post
                collectionLoaded = true
                result.onSuccess { session ->
                    collectionPending = session?.isPending == true
                    collectionOwnerBusy = RealCollectionBridge.isRunning()
                    collectionProblem = if (!collectionPending && collectionOwnerBusy) "正在关闭戒指连接" else null
                }.onFailure {
                    collectionPending = true
                    collectionOwnerBusy = RealCollectionBridge.isRunning()
                    collectionProblem = "当前记录需要恢复，请返回记录继续"
                }
                updateViews()
                routeWhenReady()
            }
        }
    }

    private fun routeWhenReady() {
        if (!visible || !loaded || !collectionLoaded || busy || routeStarted) return
        if (collectionPending && !settingsRequested) {
            openCollectionAndFinish()
            return
        }
        if (settingsRequested && collectionPending) {
            page = Page.SETTINGS
            updateViews()
            return
        }
        if (storageProblem != null) {
            page = Page.REGISTER
            updateViews()
            return
        }
        val current = snapshot
        when {
            current == null || current.placement == null -> page = Page.REGISTER
            current.ring == null -> openDeviceSelection(fromSettings = false)
            settingsRequested -> page = Page.SETTINGS
            else -> {
                openCollectionAndFinish()
                return
            }
        }
        updateViews()
    }

    private fun primaryAction() {
        if (busy || !loaded) return
        if (page == Page.SETTINGS && collectionPending) {
            openCollectionAndFinish()
            return
        }
        if (storageProblem != null) {
            if (profileUnreadable) clearUnreadableProfile() else loadProfile()
            return
        }
        feedback = null
        when (page) {
            Page.REGISTER -> saveRegistration()
            Page.DEVICES -> if (scanning) {
                stopScan()
                scanMessage = "搜索已停止"
                updateViews()
            } else withBluetooth(::scan)
            Page.SETTINGS -> openCollectionAndFinish()
        }
    }

    private fun saveRegistration() {
        val raw = participantInput.text.toString().trim()
        val selected = RingPlacement.entries.getOrNull(placementDraft - 1)
        when {
            raw.isEmpty() -> {
                feedback = "请输入用户名"
                updateViews()
            }
            selected == null -> {
                feedback = "请选择戒指佩戴位置"
                updateViews()
            }
            snapshot == null -> persist(afterSave = { openDeviceSelection(fromSettings = false) }) {
                withIdleCollectionOwner { store.registerUsername(raw, selected) }
            }
            snapshot?.placement != selected -> persist(afterSave = { openDeviceSelection(fromSettings = false) }) {
                withIdleCollectionOwner { store.savePlacement(selected) }
            }
            else -> openDeviceSelection(fromSettings = false)
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
                result.onSuccess {
                    snapshot = it
                    afterSave(it)
                }.onFailure {
                    if (it is CollectionOwnerBusyException) {
                        placementDialog?.dismiss()
                        stopScan()
                        feedback = it.message
                        openCollectionAndFinish()
                        return@onFailure
                    }
                    feedback = when (it) {
                        is IllegalArgumentException, is IllegalStateException -> it.message
                        else -> "保存失败，请检查手机空间后重试"
                    }
                }
                updateViews()
            }
        }
    }

    private fun openDeviceSelection(fromSettings: Boolean) {
        if (collectionPending || collectionOwnerBusy) return
        returnToSettings = fromSettings
        page = Page.DEVICES
        feedback = null
        updateViews()
        if (visible && collectionLoaded) withBluetooth(::scan)
    }

    private fun updateViews() {
        if (!::primary.isInitialized) return
        val loading = !loaded || !collectionLoaded
        val locked = collectionPending || collectionOwnerBusy || !collectionLoaded
        val pendingSettings = page == Page.SETTINGS && collectionPending
        val retiringSettings = page == Page.SETTINGS && !collectionPending && collectionOwnerBusy
        val hasStorageProblem = storageProblem != null && !pendingSettings
        val progressText = when {
            !loaded || !collectionLoaded -> "正在读取本机信息"
            busy -> "正在保存"
            retiringSettings -> "正在关闭戒指连接"
            else -> null
        }
        registration.visibility = if (!loading && !hasStorageProblem && page == Page.REGISTER) View.VISIBLE else View.GONE
        devices.visibility = if (!loading && !hasStorageProblem && page == Page.DEVICES) View.VISIBLE else View.GONE
        settings.visibility = if (!loading && !hasStorageProblem && page == Page.SETTINGS) View.VISIBLE else View.GONE
        val longRunningAccess = longRunningAccess()
        backgroundAccess.visibility = if (!loading && !hasStorageProblem && page == Page.SETTINGS &&
            longRunningAccess.showSettings) View.VISIBLE else View.GONE
        notificationSettings.visibility = if (longRunningAccess.showNotificationSettings) View.VISIBLE else View.GONE
        batterySettings.visibility = if (longRunningAccess.showBatterySettings) View.VISIBLE else View.GONE
        back.visibility = if (loading || page == Page.SETTINGS) View.GONE else View.VISIBLE
        back.text = if (page == Page.DEVICES) "返回" else "关闭"
        back.isEnabled = true
        details.visibility = if (BuildConfig.DEBUG && isEmulator() && !hasStorageProblem && progressText == null) {
            View.VISIBLE
        } else View.GONE
        details.isEnabled = progressText == null
        heading.text = when {
            loading -> "正在准备"
            pendingSettings -> "设置"
            hasStorageProblem && profileUnreadable -> "重新登记"
            hasStorageProblem -> "用户资料"
            page == Page.REGISTER -> "开始使用"
            page == Page.DEVICES -> "选择戒指"
            else -> "设置"
        }
        subtitle.text = when {
            loading -> ""
            pendingSettings -> "当前记录完成后可修改设置"
            retiringSettings -> "正在关闭戒指连接"
            hasStorageProblem -> "已有采集记录会保留"
            page == Page.REGISTER -> "填写用户名和佩戴位置"
            page == Page.DEVICES -> "点击正在使用的戒指"
            locked -> "当前记录完成后可修改设置"
            else -> "用户与戒指"
        }
        subtitle.visibility = if (subtitle.text.isNullOrBlank()) View.GONE else View.VISIBLE
        participantInput.isEnabled = !busy && snapshot == null
        placementPicker.isEnabled = !busy
        participantLabel.text = snapshot?.displayLabel?.let { "用户名：$it" } ?: "用户资料暂时不可用"
        placementLabel.text = "佩戴位置：${snapshot?.placement?.displayName ?: "待选择"}"
        ringLabel.text = "戒指：${snapshot?.ring?.name ?: "待选择"}"
        listOf(changeIdentity, editPlacement, changeRing, clearProfile).forEach {
            it.visibility = if (locked || busy) View.GONE else View.VISIBLE
            it.isEnabled = !busy && !locked
        }
        scanStatus.text = scanMessage ?: when {
            permissionDenied -> "允许蓝牙权限后即可搜索"
            !bluetoothEnabled() -> "打开手机蓝牙后即可搜索"
            else -> "将戒指放在手机附近"
        }
        primary.text = when {
            pendingSettings -> "返回当前记录"
            hasStorageProblem && profileUnreadable -> "重新登记"
            hasStorageProblem -> "重新读取"
            page == Page.REGISTER -> "下一步"
            page == Page.DEVICES && scanning -> "停止搜索"
            page == Page.DEVICES -> "重新搜索"
            else -> "返回采集"
        }
        progressStatus.text = progressText.orEmpty()
        progressRow.visibility = if (progressText == null) View.GONE else View.VISIBLE
        primary.visibility = if (progressText == null) View.VISIBLE else View.GONE
        primary.isEnabled = progressText == null
        val statusMessage = when {
            pendingSettings -> collectionProblem ?: "请先完成当前记录"
            hasStorageProblem -> storageProblem.orEmpty()
            else -> feedback.orEmpty()
        }
        message.text = statusMessage
        message.visibility = if (statusMessage.isEmpty()) View.GONE else View.VISIBLE
        placementDialog?.let { dialog ->
            dialog.setCancelable(!busy)
            dialog.setCanceledOnTouchOutside(!busy)
            dialog.listView.isEnabled = !busy
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = !busy
        }
    }

    private fun showIdentityPicker() {
        if (busy || collectionPending || collectionOwnerBusy || snapshot == null || identityDialog?.isShowing == true) return
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), 0, dp(24), 0)
        }
        val input = EditText(this).apply {
            tag = "identity_dialog_input"
            setSingleLine(true)
            hint = "用户名（3–24 位字母或数字）"
            setText(snapshot?.displayLabel.orEmpty())
            setSelection(text.length)
            ui.styleInput(this)
        }
        content.addView(input, LinearLayout.LayoutParams(-1, -2))
        val dialog = AlertDialog.Builder(this)
            .setTitle("更换用户")
            .setView(content)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()
        identityDialog = dialog
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val raw = input.text.toString().trim()
                if (!PreparationStore.isValidUsername(raw)) {
                    input.error = PreparationStore.USERNAME_RULE
                    return@setOnClickListener
                }
                val previous = snapshot
                dialog.dismiss()
                persist(afterSave = { saved ->
                    if (saved == previous) {
                        page = Page.SETTINGS
                        feedback = "用户名未更改"
                    } else {
                        participantInput.setText(saved.displayLabel)
                        placementDraft = 0
                        placementPicker.setSelection(0)
                        page = Page.REGISTER
                        feedback = null
                    }
                }) {
                    withIdleCollectionOwner {
                        store.replaceCurrentUsername(raw, collectionIsIdle = true)
                    }
                }
            }
        }
        dialog.setOnDismissListener { if (identityDialog === dialog) identityDialog = null }
        dialog.show()
    }

    private fun showPlacementPicker() {
        if (busy || collectionPending || collectionOwnerBusy || snapshot == null || placementDialog?.isShowing == true) return
        feedback = null
        val dialog = AlertDialog.Builder(this)
            .setTitle("佩戴位置")
            .setSingleChoiceItems(
                RingPlacement.entries.map { it.displayName }.toTypedArray(),
                snapshot?.placement?.ordinal ?: -1,
            ) { _, index ->
                if (busy || collectionPending || collectionOwnerBusy || RealCollectionBridge.isRunning()) {
                    placementDialog?.dismiss()
                    openCollectionAndFinish()
                    return@setSingleChoiceItems
                }
                val chosen = RingPlacement.entries[index]
                if (chosen == snapshot?.placement) placementDialog?.dismiss()
                else persist(afterSave = {
                    placementDraft = chosen.ordinal + 1
                    placementPicker.setSelection(placementDraft)
                    placementDialog?.dismiss()
                    page = Page.SETTINGS
                    feedback = "佩戴位置已保存"
                }) { withIdleCollectionOwner { store.savePlacement(chosen) } }
            }
            .setNegativeButton("取消", null)
            .create()
        placementDialog = dialog
        dialog.setOnDismissListener { if (placementDialog === dialog) placementDialog = null }
        dialog.show()
        updateViews()
    }

    private fun confirmClearProfile() {
        if (busy || collectionPending || collectionOwnerBusy || clearDialog?.isShowing == true) return
        val dialog = AlertDialog.Builder(this)
            .setTitle("退出当前用户？")
            .setMessage("用户名和已选戒指将从本机移除。已有采集记录会保留。")
            .setPositiveButton("退出") { _, _ -> clearCurrentProfile() }
            .setNegativeButton("取消", null)
            .create()
        clearDialog = dialog
        dialog.setOnDismissListener { if (clearDialog === dialog) clearDialog = null }
        dialog.show()
    }

    private fun clearCurrentProfile() {
        if (busy || collectionPending || collectionOwnerBusy) return
        busy = true
        feedback = null
        updateViews()
        disk.execute {
            val result = runCatching {
                withIdleCollectionOwner { store.clearCurrentProfile(collectionIsIdle = true) }
            }
            main.post {
                if (isDestroyed) return@post
                busy = false
                result.onSuccess {
                    snapshot = null
                    participantInput.setText("")
                    placementDraft = 0
                    placementPicker.setSelection(0)
                    settingsRequested = false
                    page = Page.REGISTER
                    feedback = null
                }.onFailure {
                    if (it is CollectionOwnerBusyException) {
                        feedback = it.message
                        openCollectionAndFinish()
                        return@onFailure
                    }
                    feedback = it.message ?: "清除失败，请重试"
                }
                updateViews()
            }
        }
    }

    private fun clearUnreadableProfile() {
        if (busy || collectionPending || collectionOwnerBusy) return
        busy = true
        feedback = null
        updateViews()
        disk.execute {
            val result = runCatching {
                withIdleCollectionOwner { store.clearCurrentProfile(collectionIsIdle = true) }
            }
            main.post {
                if (isDestroyed) return@post
                busy = false
                result.onSuccess {
                    snapshot = null
                    storageProblem = null
                    profileUnreadable = false
                    participantInput.setText("")
                    placementDraft = 0
                    placementPicker.setSelection(0)
                    page = Page.REGISTER
                }.onFailure {
                    if (it is CollectionOwnerBusyException) {
                        feedback = it.message
                        openCollectionAndFinish()
                        return@onFailure
                    }
                    storageProblem = it.message ?: "用户资料暂时无法清除，请重试"
                }
                updateViews()
            }
        }
    }

    private fun bluetoothEnabled() = getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
    private fun locationEnabled() = Build.VERSION.SDK_INT > 30 ||
        getSystemService(android.location.LocationManager::class.java).isLocationEnabled

    private fun isEmulator() = Build.FINGERPRINT.contains("generic", true) ||
        Build.FINGERPRINT.contains("emulator", true) || Build.MODEL.contains("Emulator", true) ||
        Build.MODEL.startsWith("sdk_gphone") || Build.MODEL.startsWith("Android SDK built for")

    private fun withBluetooth(action: () -> Unit) {
        val missing = requiredPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            pendingBluetoothAction = action
            requestPermissions(missing.toTypedArray(), REQUEST_BLUETOOTH)
            return
        }
        if (!bluetoothEnabled() || !locationEnabled()) {
            openNeededSettings()
            return
        }
        action()
    }

    private fun openNeededSettings() {
        val settingsIntent = when {
            requiredPermissions().any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED } ->
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            !bluetoothEnabled() -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            !locationEnabled() -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            else -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        }
        startActivity(settingsIntent)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS) {
            val shouldContinue = continueToCollectionAfterNotification
            continueToCollectionAfterNotification = false
            updateViews()
            if (shouldContinue) openCollectionAndFinish()
            return
        }
        if (requestCode != REQUEST_BLUETOOTH) return
        val action = pendingBluetoothAction
        pendingBluetoothAction = null
        permissionDenied = requiredPermissions().any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (!permissionDenied && visible && action != null) withBluetooth(action)
        else scanMessage = "允许蓝牙权限后即可搜索"
        updateViews()
    }

    private fun requiredPermissions() = if (Build.VERSION.SDK_INT >= 31) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun scan() {
        if (!collectionLoaded || collectionPending) return
        stopScan()
        scanMessage = "正在搜索附近的戒指…"
        results.removeAllViews()
        scanning = true
        val generation = scanGeneration
        val client = RingBleClient(this, object : RingBleClient.Listener {
            private fun deliver(action: () -> Unit) {
                main.post { if (visible && generation == scanGeneration) action() }
            }

            override fun onBleState(message: String, ready: Boolean) = deliver {
                scanMessage = when {
                    message.startsWith("搜索完成") -> "选择正在使用的戒指"
                    message.startsWith("没有发现") -> "没有发现戒指，请靠近后重新搜索"
                    else -> message
                }
                if (message.startsWith("搜索完成") || message.startsWith("没有发现")) scanning = false
                updateViews()
            }

            override fun onBleError(message: String) = deliver {
                scanning = false
                scanMessage = "搜索失败，请检查蓝牙后重试"
                updateViews()
            }

            override fun onSensorPacket(packet: SensorPacket) = Unit

            override fun onRingsFound(rings: List<ScannedRing>) = deliver {
                results.removeAllViews()
                rings.forEach { ring ->
                    button(results, "${ring.name} · ${ring.address.takeLast(5)}", false) {
                        if (!busy && !collectionPending && !collectionOwnerBusy && !RealCollectionBridge.isRunning()) {
                            stopScan()
                            persist(afterSave = {
                                openCollectionAndFinish()
                            }) { withIdleCollectionOwner { store.selectRing(PreparedRing(ring.address, ring.name)) } }
                        } else if (collectionPending || collectionOwnerBusy || RealCollectionBridge.isRunning()) {
                            stopScan()
                            openCollectionAndFinish()
                        }
                    }
                }
            }
        })
        scanner = client
        runCatching { client.scanForRings() }.onFailure {
            stopScan()
            scanMessage = "搜索失败，请检查蓝牙后重试"
        }
        updateViews()
    }

    private fun stopScan() {
        ++scanGeneration
        scanning = false
        runCatching { scanner?.stop() }
        scanner = null
        if (::results.isInitialized) results.removeAllViews()
    }

    private fun openCollectionAndFinish() {
        if (routeStarted) return
        val access = longRunningAccess()
        if (access.requestNotificationPermission) {
            accessPreferences().edit().putBoolean(KEY_NOTIFICATION_REQUESTED, true).commit()
            continueToCollectionAfterNotification = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            updateViews()
            return
        }
        routeStarted = true
        stopScan()
        startActivity(
            Intent(this, RealCollectionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }

    private fun longRunningAccess(): LongRunningAccessState {
        val notificationGranted = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val batteryOptimizationExempt = Build.VERSION.SDK_INT < 23 ||
            getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        return LongRunningAccessPolicy.evaluate(
            sdkInt = Build.VERSION.SDK_INT,
            notificationGranted = notificationGranted,
            notificationPermissionRequested = accessPreferences().getBoolean(KEY_NOTIFICATION_REQUESTED, false),
            batteryOptimizationExempt = batteryOptimizationExempt,
        )
    }

    private fun openNotificationSettings() {
        startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        })
    }

    private fun openBatterySettings() {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName"))
        runCatching { startActivity(direct) }.onFailure {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun accessPreferences() = getSharedPreferences(ACCESS_PREFERENCES, Context.MODE_PRIVATE)

    /** Recheck durable work and the live owner immediately before changing profile routing. */
    private fun <T> withIdleCollectionOwner(write: () -> T): T {
        return RealCollectionBridge.withIdleOwner {
            if (FreeLivingSessionStore(collectionJournal).read()?.isPending == true) {
                throw CollectionOwnerBusyException()
            }
            write()
        }
    }

    private fun showMore() {
        if (!BuildConfig.DEBUG || busy) return
        moreMenu?.dismiss()
        moreMenu = PopupMenu(this, details).apply {
            menu.add(0, 1, 0, "流程演示（模拟）")
            setOnMenuItemClickListener {
                if (it.itemId == 1) {
                    startActivity(Intent().setClassName(packageName, "com.nexthci.ringfitness.DemoCollectionActivity"))
                }
                true
            }
            setOnDismissListener { moreMenu = null }
            show()
        }
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

    private fun link(parent: LinearLayout, value: String, action: () -> Unit) =
        ui.button(parent, value, action = action).apply {
            background = ui.linkBackground()
            minHeight = dp(48)
            minimumHeight = dp(48)
            gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            (layoutParams as LinearLayout.LayoutParams).topMargin = 0
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_OPEN_SETTINGS = "com.nexthci.ringfitness.extra.OPEN_PREPARATION_SETTINGS"
        private const val STATE_PAGE = "page"
        private const val STATE_PARTICIPANT_DRAFT = "participant_draft"
        private const val STATE_PLACEMENT = "placement_draft"
        private const val STATE_PERMISSION_DENIED = "permission_denied"
        private const val STATE_RETURN_TO_SETTINGS = "return_to_settings"
        private const val REQUEST_BLUETOOTH = 10
        private const val REQUEST_NOTIFICATIONS = 11
        private const val ACCESS_PREFERENCES = "long_running_access"
        private const val KEY_NOTIFICATION_REQUESTED = "notification_requested"
        private val disk = Executors.newSingleThreadExecutor()
    }
}
