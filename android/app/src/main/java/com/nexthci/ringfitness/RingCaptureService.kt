package com.nexthci.ringfitness

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.Executors

enum class HealthPhase { IDLE, STARTING, COLLECTING, STOPPING, FINALIZING, LISTING, DOWNLOADING, PROCESSING }
enum class HealthStopDisposition { UPLOAD_NOW, DEFER, DISCARD }

data class CaptureServiceState(
    val ready: Boolean,
    val connectionMessage: String,
    val selectedRingAddress: String?,
    val selectedRingName: String?,
    val hasSelectedRing: Boolean,
    val reconnecting: Boolean,
    val imuActive: Boolean,
    val imuStartedAtMs: Long?,
    val imu25Active: Boolean,
    val imu25StartedAtMs: Long?,
    val imuLp25Active: Boolean,
    val imuLp25StartedAtMs: Long?,
    val imuLp50Active: Boolean,
    val imuLp50StartedAtMs: Long?,
    val motionTransitioning: Boolean,
    val ppgMode: PpgMode?,
    val ppgStartedAtMs: Long?,
    val ppgTransitioning: Boolean,
    val batteryText: String,
    val batteryBusy: Boolean,
    val firmwareVersionText: String,
    val healthPhase: HealthPhase,
    val healthStartedAtMs: Long?,
    val healthDownloadedBytes: Long,
    val healthTotalBytes: Long,
    val ouraEnabledForSession: Boolean,
    val polarEnabledForSession: Boolean,
    val capturePurpose: CapturePurpose,
    val activityCode: DailyActivity?,
    val ringPlacement: RingPlacement?,
    val subjectiveFeedbackRequired: Boolean,
    val subjectiveFeedbackTarget: SubjectiveFeedbackTarget?,
    val polarCaptureActive: Boolean,
    val polar: PolarH10State,
)

class RingCaptureService : Service(), RingBleClient.Listener {
    interface UiListener {
        fun onServiceState(state: CaptureServiceState)
        fun onRingsFound(rings: List<ScannedRing>)
        fun onServiceError(message: String)
        fun onCaptureSessionCompleted(session: UploadSessionSummary)
    }

    inner class LocalBinder : Binder() {
        fun service(): RingCaptureService = this@RingCaptureService
    }

    private data class ActiveCapture(
        val sessionId: String,
        val uploadGroupId: String,
        val kind: CaptureKind,
        val file: CaptureFile,
        val startedAtMs: Long,
    )

    private val binder = LocalBinder()
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }
    private lateinit var bleClient: RingBleClient
    private lateinit var summaryStore: DailySummaryStore
    private lateinit var uploadStore: UploadSessionStore
    private var uiListener: UiListener? = null
    private var latestRings: List<ScannedRing> = emptyList()
    private var ready = false
    private var connectionMessage = "正在初始化蓝牙…"
    private var selectedAddress: String? = null
    private var selectedName: String? = null
    private var reconnectStartedAtMs: Long? = null
    private var imuCapture: ActiveCapture? = null
    private var ppgCapture: ActiveCapture? = null
    private var activeUploadGroupId: String? = null
    private var activePpgMode: PpgMode? = null
    private var ppgTransitioning = false
    private var motionTransitioning = false
    private var batteryText = BATTERY_DEFAULT_TEXT
    private var batteryBusy = false
    private var batteryProbePending = false
    private var healthPhase = HealthPhase.IDLE
    private var healthStartedAtMs: Long? = null
    private var healthSessionId = 0
    private var healthDownloadedBytes = 0L
    private var healthTotalBytes = 0L
    private var healthNextOffset = 0L
    private var healthDownloadRetryCount = 0
    private var healthListRetryCount = 0
    private var healthPostStopReconnectCount = 0
    private var healthResumeItem: HealthMessage.ListItem? = null
    private var healthStartCommandSent = false
    private var healthStartAttemptCount = 0
    private var healthStartPollCount = 0
    private var healthLastStartErrorCode = 0
    private var healthSilentTimeoutCount = 0
    private var healthTransportReconnectCount = 0
    private var healthStopDisposition: HealthStopDisposition? = null
    private var connectionRecoveryPending = false
    private var batteryRequestPending = false
    private var batteryRetryCount = 0
    private var firmwareVersionText = "尚未查询"
    private var firmwareQueryPending = false
    private val healthListItems = mutableListOf<HealthMessage.ListItem>()
    private var healthDownload: HealthFlashDownload? = null
    private val fileExecutor = Executors.newSingleThreadExecutor()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var polarClient: PolarH10Client
    private var polarCapture: PolarHrRrCapture? = null
    private var polarCaptureStartedAtMs: Long? = null
    private var polarDeviceId: String? = null
    private var polarEnabledForSession = false
    private var ouraEnabledForSession = false
    private var capturePurpose = CapturePurpose.SLEEP_HR
    private var activityCode: DailyActivity? = null
    private var ringPlacement: RingPlacement? = null
    private var subjectiveFeedbackRequired = false
    private var subjectiveFeedbackTarget: SubjectiveFeedbackTarget? = null
    private var subjectiveFeedbackSessionId: String? = null
    private var healthFlashFinalizeReadyAtMs: Long? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var foreground = false

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val adapterState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            if (adapterState == BluetoothAdapter.STATE_ON) {
                bleClient.retryAutoReconnectNow()
            } else if (adapterState == BluetoothAdapter.STATE_TURNING_OFF || adapterState == BluetoothAdapter.STATE_OFF) {
                bleClient.handleBluetoothUnavailable()
            }
        }
    }

    private val summaryTicker = object : Runnable {
        override fun run() {
            if (!hasActiveCapture()) return
            updateSummaryAndPersistence()
            updateForegroundNotification()
            handler.postDelayed(this, SUMMARY_UPDATE_MS)
        }
    }

    private val batteryReset = Runnable {
        batteryRequestPending = false
        batteryText = BATTERY_DEFAULT_TEXT
        batteryBusy = false
        publishState()
    }

    private val batteryResponseTimeout = Runnable {
        if (!batteryRequestPending) return@Runnable
        if (batteryRetryCount < BATTERY_RETRY_LIMIT && recoverRingConnection("电量查询无响应，正在重连戒指后重试…")) {
            batteryRetryCount += 1
            batteryText = "电量查询无响应，正在重连后重试…"
        } else {
            batteryRequestPending = false
            batteryBusy = false
            batteryText = "电量读取失败，请重新连接戒指"
        }
        publishState()
    }

    private val batteryProbeTimeout = Runnable {
        if (!batteryProbePending) return@Runnable
        batteryProbePending = false
        batteryBusy = false
        batteryText = "电量读取失败，请重新点击戒指"
        bleClient.stop()
        bleClient.forgetSelectedDevice()
        bleClient.start()
        ready = false
        publishState()
    }

    private val firmwareQueryTimeout = Runnable {
        if (!firmwareQueryPending) return@Runnable
        firmwareQueryPending = false
        firmwareVersionText = "当前固件不支持版本查询"
        publishState()
    }

    private val lowPowerStartTimeout = Runnable {
        val capture = imuCapture ?: return@Runnable
        if (!ready || !capture.kind.isLowPower || capture.file.count() > 0L) return@Runnable
        stopImu(manual = true)
        reportError("当前戒指固件不支持低功耗 IMU（5 秒内未收到 ACC 数据）")
    }

    private val healthCommandTimeout = Runnable {
        if (healthPhase == HealthPhase.DOWNLOADING) {
            retryHealthDownload(healthNextOffset, "Flash 下载暂时无响应，正在自动续传")
        } else if (healthPhase == HealthPhase.STARTING &&
            healthSilentTimeoutCount < HEALTH_SILENT_RETRY_LIMIT
        ) {
            healthSilentTimeoutCount += 1
            connectionMessage = "戒指健康命令暂时无响应，正在重新确认（第 $healthSilentTimeoutCount 次）…"
            publishState()
            if (bleClient.requestHealthStatus()) scheduleHealthTimeout() else reconnectHealthTransport()
        } else if (healthPhase == HealthPhase.STARTING &&
            healthTransportReconnectCount < HEALTH_TRANSPORT_RECONNECT_LIMIT
        ) {
            reconnectHealthTransport()
        } else if ((healthPhase == HealthPhase.STOPPING || healthPhase == HealthPhase.LISTING) &&
            healthPostStopReconnectCount < HEALTH_POST_STOP_RECONNECT_LIMIT
        ) {
            healthPostStopReconnectCount += 1
            if (!recoverRingConnection(
                    if (healthPhase == HealthPhase.STOPPING) {
                        "停止状态确认无响应，正在重连戒指后继续…"
                    } else {
                        "Flash 列表查询无响应，正在重连戒指后重试…"
                    },
                )
            ) {
                abortCurrentHealthCommand()
            }
        } else if (healthPhase == HealthPhase.STARTING || healthPhase == HealthPhase.STOPPING ||
            healthPhase == HealthPhase.LISTING) {
            abortCurrentHealthCommand()
        }
    }

    private val healthListRetry = Runnable {
        if (healthPhase == HealthPhase.FINALIZING || healthPhase == HealthPhase.LISTING) {
            if (subjectiveFeedbackRequired) {
                connectionMessage = "戒指 Flash 收尾已完成，等待提交主观评价…"
                saveActiveState()
                publishState()
            } else {
                requestHealthList()
            }
        }
    }

    private val healthStartAttempt = Runnable {
        if (healthPhase == HealthPhase.STARTING && ready && !healthStartCommandSent) {
            sendHealthStartAttempt()
        }
    }

    private val healthStartStatusPoll = Runnable {
        if (healthPhase == HealthPhase.STARTING && ready && healthStartCommandSent) {
            if (!bleClient.requestHealthStatus()) {
                abortHealth("戒指连接已中断，无法确认健康采集状态")
            } else {
                scheduleHealthTimeout()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        summaryStore = DailySummaryStore(this)
        uploadStore = UploadSessionStore(this)
        bleClient = RingBleClient(this, this)
        polarClient = PolarH10Client(this) { state -> handler.post { handlePolarStateChanged(state) } }
        val bluetoothFilter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(bluetoothStateReceiver, bluetoothFilter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(bluetoothStateReceiver, bluetoothFilter)
        }
        restoreActiveState()
        if (polarCapture != null) {
            polarDeviceId?.let(polarClient::markCaptureRestored)
        }
        if (hasActiveCapture()) {
            val lostAt = prefs.getLong("last_persisted_ms", System.currentTimeMillis())
            reconnectStartedAtMs = lostAt
            summaryStore.markDisconnected(activeSessionIds(), lostAt, "采集服务进程恢复，正在重新连接戒指")
            enterForeground()
            bleClient.start()
            bleClient.setAutoReconnect(true)
            selectedAddress?.let { bleClient.connectKnownAddress(it, selectedName) }
            scheduleSummaryTicker()
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        bleClient.start()
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        updateSummaryAndPersistence()
        runCatching { imuCapture?.file?.close() }
        runCatching { ppgCapture?.file?.close() }
        runCatching { polarCapture?.close() }
        healthDownload?.discard()
        fileExecutor.shutdown()
        bleClient.stop()
        polarClient.shutdown()
        serviceScope.cancel()
        runCatching { unregisterReceiver(bluetoothStateReceiver) }
        handler.removeCallbacksAndMessages(null)
        releaseWakeLock()
        super.onDestroy()
    }

    fun setUiListener(listener: UiListener?) {
        uiListener = listener
        listener?.onServiceState(snapshot())
        listener?.onRingsFound(latestRings)
    }

    fun searchForRings() = bleClient.scanForRings()

    fun resetRingSelection() {
        if (hasActiveCapture()) return
        handler.removeCallbacks(batteryReset)
        handler.removeCallbacks(batteryResponseTimeout)
        handler.removeCallbacks(batteryProbeTimeout)
        handler.removeCallbacks(firmwareQueryTimeout)
        batteryProbePending = false
        batteryRequestPending = false
        batteryText = BATTERY_DEFAULT_TEXT
        batteryBusy = false
        firmwareVersionText = "尚未查询"
        firmwareQueryPending = false
        bleClient.stop()
        bleClient.forgetSelectedDevice()
        selectedAddress = null
        selectedName = null
        latestRings = emptyList()
        ready = false
        prefs.edit().remove("selected_address").remove("selected_name").apply()
        bleClient.start()
        connectionMessage = "请选择戒指；本页不会记住上次连接"
        uiListener?.onRingsFound(emptyList())
        publishState()
    }

    fun searchForPolarH10() = polarClient.search()

    fun resetPolarH10Selection() = polarClient.beginFreshSelection()

    fun connectPolarH10(deviceId: String) = polarClient.connect(deviceId)

    /**
     * A battery read requires a GATT connection. The UI still treats this as selecting/probing;
     * only confirmRingConnection is allowed to navigate into the collection workflow.
     */
    fun selectRingAndReadBattery(ring: ScannedRing): Boolean {
        if (hasActiveCapture()) return false
        if (selectedAddress == ring.address && ready) {
            requestBattery()
            return true
        }
        handler.removeCallbacks(batteryReset)
        handler.removeCallbacks(batteryProbeTimeout)
        batteryProbePending = false
        batteryText = "正在连接并读取电量…"
        batteryBusy = false
        firmwareVersionText = "尚未查询"
        firmwareQueryPending = false
        handler.removeCallbacks(firmwareQueryTimeout)
        if (selectedAddress != null || ready) {
            bleClient.stop()
            bleClient.forgetSelectedDevice()
            bleClient.start()
            ready = false
        }
        selectedAddress = ring.address
        selectedName = ring.name
        saveActiveState()
        batteryProbePending = true
        val result = bleClient.connect(ring.address)
        if (!result) {
            batteryProbePending = false
            batteryText = "电量读取失败，请重新点击戒指"
        } else {
            handler.postDelayed(batteryProbeTimeout, BATTERY_PROBE_TIMEOUT_MS)
        }
        publishState()
        return result
    }

    fun confirmRingConnection(ring: ScannedRing): Boolean {
        if (hasActiveCapture()) return false
        if (selectedAddress == ring.address && (ready || batteryProbePending)) return true
        return selectRingAndReadBattery(ring)
    }

    fun toggleImuLowPower25() {
        toggleMotion(CaptureKind.IMU_LP_25)
    }

    fun toggleImuLowPower50() {
        toggleMotion(CaptureKind.IMU_LP_50)
    }

    fun togglePpg(mode: PpgMode) {
        if (healthPhase != HealthPhase.IDLE) return
        if (ppgTransitioning) return
        if (activePpgMode == mode) {
            stopPpg(manual = true)
            return
        }
        if (!ready) return
        if (activePpgMode != null) {
            ppgTransitioning = true
            stopPpg(manual = true)
            publishState()
            handler.postDelayed({
                ppgTransitioning = false
                startPpg(mode)
            }, PPG_SWITCH_DELAY_MS)
        } else {
            startPpg(mode)
        }
    }

    fun requestBattery() {
        if (!ready || batteryBusy) return
        batteryRetryCount = 0
        sendBatteryRequest()
    }

    private fun sendBatteryRequest() {
        if (!ready || !bleClient.requestBattery()) {
            batteryRequestPending = false
            batteryBusy = false
            batteryText = "电量读取失败，请重新连接戒指"
            publishState()
            return
        }
        batteryRequestPending = true
        batteryBusy = true
        batteryText = "正在读取电量…"
        handler.removeCallbacks(batteryReset)
        handler.removeCallbacks(batteryResponseTimeout)
        handler.postDelayed(batteryResponseTimeout, BATTERY_RESPONSE_TIMEOUT_MS)
        publishState()
    }

    private fun requestFirmwareVersionIfSafe() {
        if (!ready || hasActiveCapture() || healthPhase != HealthPhase.IDLE || firmwareQueryPending) return
        if (firmwareVersionText.matches(Regex("\\d+\\.\\d+\\.\\d+(?:\\.\\d+)?"))) return
        handler.removeCallbacks(firmwareQueryTimeout)
        firmwareQueryPending = true
        firmwareVersionText = "正在读取…"
        if (!bleClient.requestDeviceInfo()) {
            firmwareQueryPending = false
            firmwareVersionText = "版本查询失败"
        } else {
            handler.postDelayed(firmwareQueryTimeout, FIRMWARE_QUERY_TIMEOUT_MS)
        }
        publishState()
    }

    fun toggleHealth() {
        when (healthPhase) {
            HealthPhase.IDLE -> startHealth()
            else -> Unit
        }
    }

    /** Ring HEALTH is always active; Oura selects later sleep labels and Polar adds live HR/RR. */
    fun startCollection(
        ouraEnabled: Boolean,
        polarEnabled: Boolean,
        purpose: CapturePurpose = CapturePurpose.SLEEP_HR,
        activity: DailyActivity? = null,
        placement: RingPlacement? = null,
    ): Boolean {
        if (purpose == CapturePurpose.SLEEP_HR && !ouraEnabled && !polarEnabled) {
            reportError("请至少选择睡眠分期（Oura）或心率/HRV（Polar H10）")
            return false
        }
        if (purpose == CapturePurpose.DAILY_ACTIVITY && activity == null) {
            reportError("请选择本次采集的日常活动")
            return false
        }
        if (purpose == CapturePurpose.DAILY_ACTIVITY && placement == null) {
            reportError("请选择戒指当前佩戴的手指")
            return false
        }
        if (!ready || healthPhase != HealthPhase.IDLE) return false
        if (polarEnabled && !polarClient.snapshot().let { it.connected && it.hrReady }) {
            reportError("请先连接并确认 Polar H10 的实时 HR/RR 服务已就绪")
            return false
        }
        val profile = ParticipantProfileStore(this).get() ?: return false
        uploadStore.anyRingTransferTask()?.let { pending ->
            val owner = if (pending.participantId == profile.participantId) "当前用户" else "用户 ${pending.participantName}"
            reportError("存在 $owner 的戒指待传数据，请先完成戒指到手机的传输")
            return false
        }
        val startedAt = System.currentTimeMillis()
        capturePurpose = purpose
        activityCode = activity
        ringPlacement = placement
        val groupId = ensureUploadGroup(profile, startedAt)
        ouraEnabledForSession = ouraEnabled
        polarEnabledForSession = polarEnabled
        uploadStore.setCollectionModes(groupId, ouraEnabled, polarEnabled)
        saveActiveState()
        if (polarEnabled && !startPolarCapture(startedAt)) {
            uploadStore.discardSession(groupId)
            activeUploadGroupId = null
            clearCollectionModes()
            return false
        }
        startHealth()
        return true
    }

    fun stopHealth(disposition: HealthStopDisposition) {
        if (healthPhase != HealthPhase.COLLECTING) return
        if (!ready) {
            reportError("请先重新连接戒指，再结束健康采集")
            return
        }
        healthStopDisposition = disposition
        val feedbackTarget = if (disposition != HealthStopDisposition.DISCARD && shouldRequestSubjectiveFeedback()) {
            if (capturePurpose == CapturePurpose.SLEEP_HR) {
                SubjectiveFeedbackTarget.SLEEP
            } else {
                SubjectiveFeedbackTarget.DAILY_ACTIVITY
            }
        } else null
        activeUploadGroupId?.let { groupId ->
            uploadStore.markUserDecision(
                groupId,
                if (disposition == HealthStopDisposition.UPLOAD_NOW) {
                    UploadSessionStore.DECISION_UPLOAD_NOW
                } else {
                    UploadSessionStore.DECISION_DEFERRED
                },
            )
            if (disposition != HealthStopDisposition.DISCARD) {
                uploadStore.markRingTransfer(
                    groupId,
                    UploadSessionStore.STATE_RING_FINALIZING,
                    healthSessionId.takeIf { it != 0 },
                    healthTotalBytes,
                    healthDownloadedBytes,
                )
            }
        }
        saveActiveState()
        stopPolarCapture(discard = disposition == HealthStopDisposition.DISCARD)
        if (stopHealthAndDownload() && feedbackTarget != null) {
            subjectiveFeedbackTarget = feedbackTarget
            subjectiveFeedbackRequired = true
            subjectiveFeedbackSessionId = activeUploadGroupId
            activeUploadGroupId?.let { sessionId ->
                uploadStore.markSubjectiveFeedbackRequired(sessionId, feedbackTarget)
            }
            saveActiveState()
            publishState()
        }
    }

    fun submitSubjectiveFeedback(score: Int?, activityDetail: String, comment: String): Boolean {
        val target = subjectiveFeedbackTarget ?: return false
        val sessionId = subjectiveFeedbackSessionId ?: activeUploadGroupId ?: return false
        if (!subjectiveFeedbackRequired || (score != null && score !in 1..10)) return false
        val feedback = SubjectiveFeedback(
            target = target,
            score = score,
            comment = SubjectiveFeedback.normalizedComment(comment),
            activityDetail = if (target == SubjectiveFeedbackTarget.DAILY_ACTIVITY) {
                SubjectiveFeedback.normalizedActivityDetail(activityDetail)
            } else null,
        )
        uploadStore.saveSubjectiveFeedback(sessionId, feedback)
        subjectiveFeedbackRequired = false
        subjectiveFeedbackTarget = null
        subjectiveFeedbackSessionId = null
        saveActiveState()
        if (healthPhase == HealthPhase.FINALIZING) {
            val delay = ((healthFlashFinalizeReadyAtMs ?: 0L) - System.currentTimeMillis()).coerceAtLeast(0L)
            connectionMessage = if (delay > 0L) {
                "主观评价已保存，戒指正在完成 Flash 收尾…"
            } else {
                "主观评价已保存，正在读取戒指 Flash 记录…"
            }
            handler.removeCallbacks(healthListRetry)
            handler.postDelayed(healthListRetry, delay)
        }
        captureStoppedIfNeeded()
        publishState()
        return true
    }

    private fun shouldRequestSubjectiveFeedback(): Boolean = when (capturePurpose) {
        CapturePurpose.SLEEP_HR -> ouraEnabledForSession
        CapturePurpose.DAILY_ACTIVITY -> activityCode != null && activityCode != DailyActivity.OTHER
    }

    private fun startPolarCapture(startedAtMs: Long): Boolean {
        val capture = runCatching { PolarHrRrCapture.create(this) }
            .onFailure { reportError("无法创建 Polar HR/RR 文件：${it.message}") }
            .getOrNull() ?: return false
        polarCapture = capture
        polarCaptureStartedAtMs = startedAtMs
        polarDeviceId = polarClient.snapshot().selectedDeviceId
        saveActiveState()
        val started = polarClient.startRecording(capture::write)
        if (!started) {
            runCatching { capture.close() }
            contentResolver.delete(capture.uri, null, null)
            polarCapture = null
            polarCaptureStartedAtMs = null
            clearPolarState()
            return false
        }
        enterForeground()
        publishState()
        return true
    }

    private fun stopPolarCapture(discard: Boolean) {
        val capture = polarCapture ?: return
        polarClient.stopRecording()
        polarCapture = null
        val startedAt = polarCaptureStartedAtMs ?: System.currentTimeMillis()
        val groupId = activeUploadGroupId
        val endedAt = System.currentTimeMillis()
        val count = runCatching { capture.close() }.getOrDefault(capture.count())
        if (discard || groupId == null) {
            runCatching { contentResolver.delete(capture.uri, null, null) }
        } else {
            uploadStore.recordCapture(
                ClosedCapture(groupId, CaptureKind.POLAR_HR_RR, capture.uri, capture.displayName, startedAt, endedAt, count),
            )
        }
        clearPolarState()
        saveActiveState()
        captureStoppedIfNeeded()
    }

    private fun toggleMotion(kind: CaptureKind) {
        if (healthPhase != HealthPhase.IDLE) return
        if (motionTransitioning) return
        if (imuCapture?.kind == kind) {
            stopImu(manual = true)
            return
        }
        if (!ready) return
        if (imuCapture != null) {
            motionTransitioning = true
            stopImu(manual = true)
            publishState()
            handler.postDelayed({
                motionTransitioning = false
                startMotion(kind)
            }, MOTION_SWITCH_DELAY_MS)
        } else {
            startMotion(kind)
        }
    }

    private fun startMotion(kind: CaptureKind) {
        require(kind.isMotion)
        val profile = ParticipantProfileStore(this).get()
        if (profile == null) {
            reportError("请先绑定被试，再开始采集")
            return
        }
        if (!ready) {
            motionTransitioning = false
            publishState()
            return
        }
        val label = motionLabel(kind)
        val file = runCatching { CaptureFile.create(this, kind) }
            .getOrElse { reportError("创建 $label 文件失败：${it.message}"); return }
        val started = when (kind) {
            CaptureKind.IMU -> bleClient.startImu50Hz()
            CaptureKind.IMU_25 -> bleClient.startImu25Hz()
            CaptureKind.IMU_LP_25 -> bleClient.startImuLowPower25Hz()
            CaptureKind.IMU_LP_50 -> bleClient.startImuLowPower50Hz()
            else -> false
        }
        if (!started) {
            runCatching { file.close() }
            contentResolver.delete(file.uri, null, null)
            reportError("戒指未连接，无法启动 $label")
            return
        }
        val startedAt = System.currentTimeMillis()
        val uploadGroupId = ensureUploadGroup(profile, startedAt)
        val capture = ActiveCapture(UUID.randomUUID().toString(), uploadGroupId, kind, file, startedAt)
        imuCapture = capture
        motionTransitioning = false
        summaryStore.startSession(capture.sessionId, capture.kind, file.displayName, startedAt, connected = true)
        captureStarted()
        handler.removeCallbacks(lowPowerStartTimeout)
        if (kind.isLowPower) handler.postDelayed(lowPowerStartTimeout, LOW_POWER_START_TIMEOUT_MS)
    }

    private fun startPpg(mode: PpgMode) {
        val profile = ParticipantProfileStore(this).get()
        if (profile == null) {
            reportError("请先绑定被试，再开始采集")
            return
        }
        if (!ready) {
            ppgTransitioning = false
            publishState()
            return
        }
        val kind = if (mode == PpgMode.HRS) CaptureKind.PPG_HRS else CaptureKind.PPG_SPO2
        val file = runCatching { CaptureFile.create(this, kind) }
            .getOrElse { reportError("创建 PPG 文件失败：${it.message}"); return }
        if (!bleClient.startPpg(mode)) {
            runCatching { file.close() }
            contentResolver.delete(file.uri, null, null)
            reportError("戒指未连接，无法启动 PPG")
            return
        }
        val startedAt = System.currentTimeMillis()
        val uploadGroupId = ensureUploadGroup(profile, startedAt)
        val capture = ActiveCapture(UUID.randomUUID().toString(), uploadGroupId, kind, file, startedAt)
        ppgCapture = capture
        activePpgMode = mode
        ppgTransitioning = false
        summaryStore.startSession(capture.sessionId, capture.kind, file.displayName, startedAt, connected = true)
        captureStarted()
    }

    private fun captureStarted() {
        startService(Intent(this, RingCaptureService::class.java))
        bleClient.setAutoReconnect(true)
        enterForeground()
        scheduleSummaryTicker()
        updateSummaryAndPersistence()
        publishState()
    }

    private fun startHealth() {
        val profile = ParticipantProfileStore(this).get()
        if (profile == null) {
            reportError("请先绑定被试，再开始健康采集")
            return
        }
        if (!ready || hasLiveCapture()) return
        clearSubjectiveFeedbackState()
        healthSessionId = 0
        healthPhase = HealthPhase.STARTING
        healthStartCommandSent = false
        healthStartAttemptCount = 0
        healthStartPollCount = 0
        healthLastStartErrorCode = 0
        healthSilentTimeoutCount = 0
        healthTransportReconnectCount = 0
        healthPostStopReconnectCount = 0
        healthResumeItem = null
        healthStopDisposition = null
        prefs.edit().remove("health_stop_disposition").apply()
        healthDownloadedBytes = 0
        healthTotalBytes = 0
        healthNextOffset = 0
        healthDownloadRetryCount = 0
        connectionMessage = "正在确认戒指健康采集状态…"
        saveActiveState()
        publishState()
        if (!bleClient.requestHealthStatus()) {
            clearHealthState()
            reportError("戒指未连接，无法确认健康采集状态")
            return
        }
        scheduleHealthTimeout()
    }

    private fun scheduleHealthStartAttempt() {
        handler.removeCallbacks(healthStartAttempt)
        connectionMessage = if (healthStartAttemptCount == 0) {
            "戒指当前未采集，正在准备启动健康采集…"
        } else {
            "首次启动未确认，正在重新发送健康采集命令…"
        }
        publishState()
        handler.postDelayed(healthStartAttempt, HEALTH_START_SETTLE_DELAY_MS)
    }

    private fun sendHealthStartAttempt() {
        val profile = ParticipantProfileStore(this).get()
            ?: return abortHealth("请先绑定被试，再开始健康采集")
        if (!bleClient.startHealth()) return abortHealth("戒指未连接，无法启动健康采集")
        val startedAt = healthStartedAtMs ?: System.currentTimeMillis()
        if (healthStartedAtMs == null) {
            ensureUploadGroup(profile, startedAt)
            healthStartedAtMs = startedAt
        }
        healthStartAttemptCount += 1
        healthStartPollCount = 0
        healthStartCommandSent = true
        connectionMessage = "正在启动戒指健康采集（第 $healthStartAttemptCount 次尝试）…"
        captureStarted()
        scheduleHealthTimeout()
        handler.removeCallbacks(healthStartStatusPoll)
        handler.postDelayed(healthStartStatusPoll, HEALTH_START_FIRST_POLL_DELAY_MS)
    }

    private fun adoptRunningHealth(message: HealthMessage.Status) {
        val profile = ParticipantProfileStore(this).get()
            ?: return abortHealth("检测到戒指仍在采集，但手机尚未绑定被试")
        val now = System.currentTimeMillis()
        val recoveredStart = healthStartedAtMs
            ?: prefs.getLong("health_started", 0L).takeIf { it in MIN_PLAUSIBLE_UNIX_MS..now }
            ?: now
        healthStartedAtMs = recoveredStart
        healthTotalBytes = message.bytes
        healthPhase = HealthPhase.COLLECTING
        healthStartCommandSent = false
        cancelHealthStartCallbacks()
        ensureUploadGroup(profile, recoveredStart)
        handler.removeCallbacks(healthCommandTimeout)
        connectionMessage = "检测到戒指仍在健康采集，已自动接管；再次点击即可结束并下载"
        captureStarted()
    }

    private fun stopHealthAndDownload(): Boolean {
        if (!ready) {
            clearHealthStopDisposition()
            reportError("请先重新连接戒指，再结束健康采集并下载 Flash 数据")
            return false
        }
        if (!bleClient.stopHealth()) {
            clearHealthStopDisposition()
            reportError("无法向戒指发送健康采集停止命令")
            return false
        }
        healthPhase = HealthPhase.STOPPING
        healthPostStopReconnectCount = 0
        connectionMessage = if (healthStopDisposition == HealthStopDisposition.DISCARD) {
            "正在停止健康采集；确认停止后将不下载本次数据…"
        } else {
            "正在停止健康采集；随后将从戒指下载并生成 CSV…"
        }
        saveActiveState()
        scheduleHealthTimeout()
        handler.postDelayed({ bleClient.requestHealthStatus() }, HEALTH_STATUS_DELAY_MS)
        publishState()
        return true
    }

    fun resumeRingTransfer(sessionId: String): Boolean {
        if (!ready || healthPhase != HealthPhase.IDLE) return false
        val profile = ParticipantProfileStore(this).get() ?: return false
        val payload = runCatching { uploadStore.read(sessionId) }.getOrNull() ?: return false
        if (payload.optBoolean("subjective_feedback_required", false)) {
            reportError("请先完成本次采集的主观评价")
            return false
        }
        if (payload.optString("participant_id") != profile.participantId) {
            reportError("这条戒指待传任务不属于当前用户")
            return false
        }
        val taskRing = payload.optString("ring_address")
        if (taskRing.isNotBlank() && !taskRing.equals(selectedAddress, ignoreCase = true)) {
            reportError("请连接这条任务原先使用的戒指")
            return false
        }
        activeUploadGroupId = sessionId
        capturePurpose = CapturePurpose.fromWireValue(payload.optString("capture_purpose"))
        activityCode = DailyActivity.fromWireValue(payload.optString("activity_code"))
        ringPlacement = RingPlacement.fromWireValue(payload.optString("ring_placement"))
        healthStartedAtMs = payload.optLong("started_at_ms").takeIf { it >= MIN_PLAUSIBLE_UNIX_MS }
        healthSessionId = payload.optInt("health_session_id", 0)
        healthTotalBytes = payload.optLong("ring_total_bytes", 0L)
        healthDownloadedBytes = payload.optLong("ring_downloaded_bytes", 0L)
        healthNextOffset = healthDownloadedBytes
        healthDownloadRetryCount = 0
        healthListRetryCount = 0
        healthPostStopReconnectCount = 0
        healthStopDisposition = HealthStopDisposition.UPLOAD_NOW
        payload.optJSONObject("collection_modes")?.let {
            ouraEnabledForSession = it.optBoolean("oura_sleep", false)
            polarEnabledForSession = it.optBoolean("polar_hr_rr", false)
        }
        healthResumeItem = payload.takeIf {
            it.has("ring_anchor_uptime_ms") && it.has("ring_anchor_unix_ms") && healthSessionId != 0 && healthTotalBytes > 0
        }?.let {
            HealthMessage.ListItem(
                sessionId = healthSessionId,
                bytes = healthTotalBytes,
                records = it.optLong("ring_record_count", 0L),
                uptimeMs = it.optLong("ring_anchor_uptime_ms", 0L),
                unixMs = it.optLong("ring_anchor_unix_ms", 0L),
            )
        }
        healthPhase = HealthPhase.FINALIZING
        uploadStore.markRingTransfer(
            sessionId,
            UploadSessionStore.STATE_RING_FINALIZING,
            healthSessionId.takeIf { it != 0 },
            healthTotalBytes,
            healthDownloadedBytes,
        )
        saveActiveState()
        enterForeground()
        if (!recoverRingConnection("正在重连原戒指并恢复待传任务…")) {
            healthResumeItem?.let(::beginHealthDownload) ?: requestHealthList()
        }
        return true
    }

    private fun requestHealthList() {
        if (subjectiveFeedbackRequired) {
            connectionMessage = "戒指采集已停止，等待提交主观评价…"
            saveActiveState()
            publishState()
            return
        }
        handler.removeCallbacks(healthListRetry)
        healthListItems.clear()
        healthPhase = HealthPhase.LISTING
        connectionMessage = "正在读取戒指 Flash 记录列表…"
        saveActiveState()
        if (!bleClient.requestHealthSessions()) {
            abortHealth("无法读取戒指健康采集列表")
            return
        }
        scheduleHealthTimeout()
        publishState()
    }

    private fun waitForFinalizedHealthRecord() {
        healthPhase = HealthPhase.FINALIZING
        healthListRetryCount = 0
        healthPostStopReconnectCount = 0
        healthFlashFinalizeReadyAtMs = System.currentTimeMillis() + HEALTH_FLASH_FINALIZE_DELAY_MS
        connectionMessage = "戒指正在完成 Flash 收尾，5 秒后查询记录…"
        activeUploadGroupId?.let {
            uploadStore.markRingTransfer(
                it,
                UploadSessionStore.STATE_RING_FINALIZING,
                healthSessionId.takeIf { value -> value != 0 },
                healthTotalBytes,
                healthDownloadedBytes,
            )
        }
        saveActiveState()
        publishState()
        handler.removeCallbacks(healthCommandTimeout)
        handler.removeCallbacks(healthListRetry)
        handler.postDelayed(healthListRetry, HEALTH_FLASH_FINALIZE_DELAY_MS)
    }

    private fun beginHealthDownload(item: HealthMessage.ListItem) {
        healthSessionId = item.sessionId
        healthTotalBytes = item.bytes
        val groupId = activeUploadGroupId ?: return abortHealth("健康采集上传会话丢失")
        val persisted = runCatching { uploadStore.read(groupId).optLong("ring_downloaded_bytes", 0L) }
            .getOrDefault(0L)
            .coerceIn(0L, item.bytes)
        healthDownloadRetryCount = 0
        val fallbackStartedAtMs = healthStartedAtMs
            ?: item.unixMs.takeIf { it >= 946_684_800_000L }
            ?: return abortHealth("健康采集开始时间丢失，无法生成可靠的绝对时间")
        val download = runCatching {
            HealthFlashDownload(this, item, fallbackStartedAtMs, groupId, reset = persisted == 0L)
        }
            .getOrElse { abortHealth("无法创建健康数据临时文件：${it.message}"); return }
        val resumeOffset = minOf(download.existingBytes(), item.bytes)
        download.truncateTo(resumeOffset)
        healthDownload = download
        healthDownloadedBytes = resumeOffset
        healthNextOffset = resumeOffset
        healthPhase = HealthPhase.DOWNLOADING
        connectionMessage = if (resumeOffset > 0L) {
            "正在从戒指续传 Flash 数据…"
        } else {
            "正在从戒指下载 Flash 数据…"
        }
        uploadStore.markRingTransfer(
            groupId,
            UploadSessionStore.STATE_RING_DOWNLOADING,
            item.sessionId,
            item.bytes,
            resumeOffset,
        )
        if (item.bytes > 0L && resumeOffset >= item.bytes) {
            completeHealthDownload()
            return
        }
        if (!bleClient.readHealth(item.sessionId, resumeOffset, HEALTH_READ_WINDOW_BYTES)) {
            abortHealth("无法开始下载戒指 Flash 数据")
            return
        }
        scheduleHealthTimeout()
        publishState()
    }

    private fun completeHealthDownload() {
        val download = healthDownload ?: return abortHealth("健康数据下载状态丢失")
        healthDownload = null
        val groupId = activeUploadGroupId ?: return abortHealth("健康采集上传会话丢失")
        val startedAt = healthStartedAtMs ?: System.currentTimeMillis()
        val endedAt = System.currentTimeMillis()
        handler.removeCallbacks(healthCommandTimeout)
        healthPhase = HealthPhase.PROCESSING
        uploadStore.markRingTransfer(
            groupId,
            UploadSessionStore.STATE_RING_PROCESSING,
            healthSessionId,
            healthTotalBytes,
            healthTotalBytes,
        )
        connectionMessage = "Flash 下载完成，正在保存 v2 原始数据"
        publishState()
        fileExecutor.execute {
            runCatching { download.closeAsV2(endedAt) }
                .onSuccess { raw ->
                    handler.post {
                        uploadStore.recordCapture(
                            ClosedCapture(
                                groupId, CaptureKind.HEALTH_RAW_V2, raw.uri, raw.displayName,
                                startedAt, endedAt, raw.payloadBytes,
                            ),
                        )
                        clearHealthState()
                        connectionMessage = "健康数据已保存为 v2 原始数据"
                        captureStoppedIfNeeded()
                        recoverRingConnection("戒指数据已保存到手机，正在恢复戒指连接…")
                    }
                }
                .onFailure { error -> handler.post { abortHealth("解析健康数据失败：${error.message}") } }
        }
    }

    private fun handleHealth(message: HealthMessage) {
        healthSilentTimeoutCount = 0
        healthTransportReconnectCount = 0
        when (message) {
            is HealthMessage.Status -> {
                if (message.sessionId != 0 || healthSessionId == 0) {
                    healthSessionId = message.sessionId
                }
                healthTotalBytes = message.bytes
                if (message.collecting) {
                    if (healthPhase == HealthPhase.STOPPING) {
                        handler.removeCallbacks(healthCommandTimeout)
                        connectionMessage = "戒指固件正在结束采集，等待停止确认…"
                        saveActiveState()
                        publishState()
                        handler.postDelayed({
                            if (healthPhase == HealthPhase.STOPPING) {
                                if (bleClient.requestHealthStatus()) scheduleHealthTimeout()
                                else if (!recoverRingConnection("停止确认时连接中断，正在重连戒指…")) {
                                    abortCurrentHealthCommand()
                                }
                            }
                        }, HEALTH_STOP_STATUS_POLL_MS)
                        return
                    }
                    if (healthPhase == HealthPhase.IDLE || healthPhase == HealthPhase.STARTING) {
                        adoptRunningHealth(message)
                    } else {
                        handler.removeCallbacks(healthCommandTimeout)
                        cancelHealthStartCallbacks()
                        healthPhase = HealthPhase.COLLECTING
                        healthStartCommandSent = false
                        saveActiveState()
                        publishState()
                    }
                    return
                }
                when (healthPhase) {
                    HealthPhase.STARTING -> {
                        if (message.errorCode != 0) healthLastStartErrorCode = message.errorCode
                        if (!healthStartCommandSent) {
                            scheduleHealthStartAttempt()
                        } else {
                            healthStartPollCount += 1
                            if (healthStartPollCount < HEALTH_START_POLLS_PER_ATTEMPT) {
                                connectionMessage = "等待戒指进入健康采集状态…"
                                publishState()
                                handler.removeCallbacks(healthStartStatusPoll)
                                handler.postDelayed(healthStartStatusPoll, HEALTH_START_POLL_INTERVAL_MS)
                            } else if (healthStartAttemptCount < HEALTH_START_MAX_ATTEMPTS) {
                                healthStartCommandSent = false
                                scheduleHealthStartAttempt()
                            } else {
                                val code = healthLastStartErrorCode.takeIf { it != 0 }
                                    ?.let { "，戒指错误码 $it" }.orEmpty()
                                abortHealth(
                                    "戒指未能进入健康采集状态（已自动重试 $healthStartAttemptCount 次$code）。" +
                                        "请重新连接戒指后重试；若存在戒指待传任务，请先继续传输或放弃该任务。",
                                )
                            }
                        }
                    }
                    HealthPhase.STOPPING, HealthPhase.COLLECTING -> {
                        if (healthStopDisposition == HealthStopDisposition.DISCARD) {
                            discardStoppedHealthCapture()
                        } else {
                            waitForFinalizedHealthRecord()
                        }
                    }
                    HealthPhase.IDLE -> Unit
                    else -> Unit
                }
            }
            is HealthMessage.ListItem -> if (healthPhase == HealthPhase.LISTING) healthListItems += message
            is HealthMessage.ListEnd -> if (healthPhase == HealthPhase.LISTING) {
                handler.removeCallbacks(healthCommandTimeout)
                healthPostStopReconnectCount = 0
                val item = if (healthSessionId != 0) {
                    healthListItems.firstOrNull { it.sessionId == healthSessionId }
                } else {
                    healthListItems.maxWithOrNull(
                        compareBy<HealthMessage.ListItem> { it.unixMs }.thenBy { it.sessionId },
                    )
                }
                if (item == null || item.bytes <= 0L) {
                    if (healthListRetryCount < HEALTH_LIST_RETRY_LIMIT) {
                        healthListRetryCount += 1
                        healthPhase = HealthPhase.FINALIZING
                        connectionMessage = "尚未发现完整 Flash 记录，正在第 $healthListRetryCount 次重新查询…"
                        publishState()
                        handler.postDelayed(healthListRetry, HEALTH_LIST_RETRY_INTERVAL_MS)
                    } else {
                        parkRingTransfer("戒指记录尚未准备好，已保留在“戒指待传”中")
                    }
                } else if (healthStopDisposition == HealthStopDisposition.DEFER) {
                    healthSessionId = item.sessionId
                    healthTotalBytes = item.bytes
                    activeUploadGroupId?.let { uploadStore.saveRingAnchor(it, item) }
                    healthResumeItem = item
                    parkRingTransfer("本次数据已暂存到戒指，可稍后下载并上传", showError = false)
                } else {
                    activeUploadGroupId?.let { uploadStore.saveRingAnchor(it, item) }
                    healthResumeItem = item
                    beginHealthDownload(item)
                }
            }
            is HealthMessage.DataChunk -> if (healthPhase == HealthPhase.DOWNLOADING) {
                val endOffset = message.offset + message.payload.size
                if (message.offset > healthNextOffset) {
                    retryHealthDownload(
                        healthNextOffset,
                        "下载中发现数据缺口，正在从 $healthNextOffset 字节处重试",
                    )
                    return
                }
                if (endOffset <= healthNextOffset) {
                    scheduleHealthTimeout()
                    return
                }
                runCatching { healthDownload?.write(message.offset, message.payload) }
                    .onFailure { return abortHealth("写入健康数据失败：${it.message}") }
                healthNextOffset = endOffset
                healthDownloadRetryCount = 0
                if (healthNextOffset >= healthTotalBytes ||
                    healthNextOffset - healthDownloadedBytes >= HEALTH_PROGRESS_PUBLISH_BYTES
                ) {
                    healthDownloadedBytes = minOf(healthNextOffset, healthTotalBytes)
                    activeUploadGroupId?.let {
                        uploadStore.updateRingProgress(it, healthDownloadedBytes, healthTotalBytes)
                    }
                    saveActiveState()
                    publishState()
                }
                scheduleHealthTimeout()
                if (healthTotalBytes > 0 && healthNextOffset >= healthTotalBytes) {
                    completeHealthDownload()
                }
            }
            is HealthMessage.ReadEnd -> if (healthPhase == HealthPhase.DOWNLOADING) {
                if (message.nextOffset > healthNextOffset) {
                    retryHealthDownload(healthNextOffset, "下载结束标记前存在数据缺口，正在续传")
                    return
                }
                healthDownloadedBytes = minOf(healthNextOffset, healthTotalBytes)
                publishState()
                if (message.done) completeHealthDownload()
                else requestNextHealthWindow(maxOf(message.nextOffset, healthNextOffset))
            }
        }
    }

    private fun requestNextHealthWindow(offset: Long) {
        if (!bleClient.readHealth(healthSessionId, offset, HEALTH_READ_WINDOW_BYTES)) {
            abortHealth("继续下载戒指 Flash 数据失败")
        } else {
            scheduleHealthTimeout()
        }
    }

    private fun retryHealthDownload(offset: Long, message: String) {
        if (healthDownloadRetryCount >= HEALTH_DOWNLOAD_RETRY_LIMIT) {
            parkRingTransfer("戒指 Flash 下载中断，已保留进度；重新连接后可继续")
            return
        }
        healthDownloadRetryCount += 1
        connectionMessage = message
        publishState()
        requestNextHealthWindow(offset)
    }

    private fun scheduleHealthTimeout() {
        handler.removeCallbacks(healthCommandTimeout)
        val timeout = if (healthPhase == HealthPhase.STARTING) {
            HEALTH_START_RESPONSE_TIMEOUT_MS
        } else {
            HEALTH_COMMAND_TIMEOUT_MS
        }
        handler.postDelayed(healthCommandTimeout, timeout)
    }

    private fun reconnectHealthTransport() {
        if (healthPhase != HealthPhase.STARTING || !bleClient.forceReconnect()) {
            abortHealth("戒指连接已中断，无法启动健康采集")
            return
        }
        healthTransportReconnectCount += 1
        healthSilentTimeoutCount = 0
        connectionMessage = "戒指健康命令持续无响应，正在重连后继续尝试…"
        publishState()
    }

    private fun recoverRingConnection(message: String): Boolean {
        connectionRecoveryPending = true
        connectionMessage = message
        val started = bleClient.forceReconnect()
        if (!started) connectionRecoveryPending = false
        if (started) publishState()
        return started
    }

    private fun abortCurrentHealthCommand() {
        when (healthPhase) {
            HealthPhase.STARTING -> abortHealth("启动戒指健康采集时，STATUS 命令持续无响应")
            HealthPhase.STOPPING -> abortHealth(
                if (healthStopDisposition == HealthStopDisposition.DISCARD) {
                    "停止戒指健康采集后，STATUS 命令持续无响应；本次手机端任务已放弃，正在重建戒指连接"
                } else {
                    "停止戒指健康采集后，STATUS 命令持续无响应；待传状态已保留"
                },
            )
            HealthPhase.LISTING -> abortHealth("戒指 Flash 列表查询持续无响应；待传状态已保留")
            else -> abortHealth("戒指健康命令持续无响应")
        }
    }

    private fun abortHealth(message: String) {
        if (healthStopDisposition != HealthStopDisposition.DISCARD &&
            healthPhase in setOf(
                HealthPhase.STOPPING,
                HealthPhase.FINALIZING,
                HealthPhase.LISTING,
                HealthPhase.DOWNLOADING,
                HealthPhase.PROCESSING,
            ) && activeUploadGroupId != null
        ) {
            parkRingTransfer(message)
            return
        }
        val shouldRecoverConnection = selectedAddress != null
        handler.removeCallbacks(healthCommandTimeout)
        cancelHealthStartCallbacks()
        healthDownload?.discard()
        healthDownload = null
        stopPolarCapture(discard = true)
        activeUploadGroupId?.let { uploadStore.discardSession(it) }
        activeUploadGroupId = null
        clearCollectionModes()
        clearHealthState()
        clearHealthStopDisposition()
        clearSubjectiveFeedbackState()
        saveActiveState()
        if (!hasActiveCapture()) leaveForeground()
        reportError(message)
        if (shouldRecoverConnection) {
            recoverRingConnection("健康命令失败，正在重建戒指连接…")
        }
    }

    private fun parkRingTransfer(message: String, showError: Boolean = true) {
        handler.removeCallbacks(healthCommandTimeout)
        handler.removeCallbacks(healthListRetry)
        cancelHealthStartCallbacks()
        healthDownload?.suspendForRetry()
        healthDownload = null
        val groupId = activeUploadGroupId
        if (groupId != null) {
            uploadStore.markRingTransfer(
                groupId,
                UploadSessionStore.STATE_RING_PENDING,
                healthSessionId.takeIf { it != 0 },
                healthTotalBytes,
                healthDownloadedBytes,
                message,
            )
        }
        activeUploadGroupId = null
        clearHealthState()
        clearHealthStopDisposition()
        prefs.edit().remove("upload_group_id").apply()
        saveActiveState()
        connectionMessage = message
        captureStoppedIfNeeded()
        recoverRingConnection("戒指待传任务已保存，正在恢复戒指连接…")
        if (showError) reportError(message)
    }

    private fun discardStoppedHealthCapture() {
        handler.removeCallbacks(healthCommandTimeout)
        cancelHealthStartCallbacks()
        healthDownload?.discard()
        healthDownload = null
        stopPolarCapture(discard = true)
        activeUploadGroupId?.let { uploadStore.discardSession(it) }
        activeUploadGroupId = null
        clearCollectionModes()
        prefs.edit().remove("upload_group_id").apply()
        clearHealthState()
        clearHealthStopDisposition()
        clearSubjectiveFeedbackState()
        connectionMessage = "本次健康采集已停止：未下载、未生成 CSV、未上传（戒指 Flash 记录仍保留）"
        captureStoppedIfNeeded()
        recoverRingConnection("本次数据已放弃，正在恢复戒指连接…")
    }

    /**
     * Forgets one parked transfer on this phone. This deliberately does not issue a physical
     * Flash erase command: the firmware may overwrite the old record during later captures.
     */
    fun discardPendingRingTransfer(sessionId: String): Boolean {
        if (healthPhase != HealthPhase.IDLE || hasLiveCapture()) {
            reportError("戒指正在采集或传输，暂时不能放弃待传数据")
            return false
        }
        val profile = ParticipantProfileStore(this).get() ?: return false
        val payload = runCatching { uploadStore.read(sessionId) }.getOrNull() ?: return false
        if (payload.optString("participant_id") != profile.participantId) {
            reportError("这条戒指待传任务不属于当前用户")
            return false
        }
        if (payload.optString("state") != UploadSessionStore.STATE_RING_PENDING) {
            reportError("戒指任务正在工作，暂时不能放弃")
            return false
        }
        val taskRing = payload.optString("ring_address")
        if (taskRing.isNotBlank() && !taskRing.equals(selectedAddress, ignoreCase = true)) {
            reportError("请连接这条任务原先使用的戒指后再放弃")
            return false
        }
        UploadScheduler.cancel(this, sessionId)
        HealthFlashDownload.discardStoredTransfer(this, sessionId)
        val failures = runCatching { uploadStore.deleteSessionData(sessionId) }.getOrElse { 1 }
        if (failures != 0) {
            reportError("待传任务的手机本地文件未能完全删除，请重试")
            return false
        }
        if (activeUploadGroupId == sessionId) activeUploadGroupId = null
        if (subjectiveFeedbackSessionId == sessionId) clearSubjectiveFeedbackState()
        connectionMessage = "已放弃原戒指待传任务；旧 Flash 记录将由后续采集自然覆盖"
        saveActiveState()
        recoverRingConnection("待传任务已解除，正在恢复戒指连接…")
        return true
    }

    /**
     * Deletes only the phone-side partial Flash file and its saved offset, then resumes the
     * same user/ring/session from byte zero. The upload session metadata and ring Flash record
     * are deliberately preserved.
     */
    fun restartPendingRingTransferFromZero(sessionId: String): Boolean {
        if (!ready || healthPhase != HealthPhase.IDLE || hasLiveCapture()) {
            reportError("请先连接原戒指，并等待当前传输完全暂停")
            return false
        }
        val profile = ParticipantProfileStore(this).get() ?: return false
        val payload = runCatching { uploadStore.read(sessionId) }.getOrNull() ?: return false
        if (payload.optString("participant_id") != profile.participantId) {
            reportError("这条戒指待传任务不属于当前用户")
            return false
        }
        if (payload.optString("state") != UploadSessionStore.STATE_RING_PENDING) {
            reportError("请先等待当前传输暂停后再从 0 重新下载")
            return false
        }
        val taskRing = payload.optString("ring_address")
        if (taskRing.isNotBlank() && !taskRing.equals(selectedAddress, ignoreCase = true)) {
            reportError("请连接这条任务原先使用的戒指")
            return false
        }

        UploadScheduler.cancel(this, sessionId)
        HealthFlashDownload.discardStoredTransfer(this, sessionId)
        uploadStore.markRingTransfer(
            sessionId = sessionId,
            state = UploadSessionStore.STATE_RING_PENDING,
            healthSessionId = payload.optInt("health_session_id").takeIf {
                payload.has("health_session_id") && it != 0
            },
            totalBytes = payload.optLong("ring_total_bytes", 0L),
            downloadedBytes = 0L,
            error = null,
        )
        connectionMessage = "已清除手机端部分下载，正在从 0 重新读取原戒指…"
        publishState()
        return resumeRingTransfer(sessionId)
    }

    private fun clearHealthStopDisposition() {
        healthStopDisposition = null
        prefs.edit().remove("health_stop_disposition").apply()
    }

    private fun clearHealthState() {
        cancelHealthStartCallbacks()
        handler.removeCallbacks(healthCommandTimeout)
        handler.removeCallbacks(healthListRetry)
        healthPhase = HealthPhase.IDLE
        healthStartedAtMs = null
        healthSessionId = 0
        healthDownloadedBytes = 0
        healthTotalBytes = 0
        healthNextOffset = 0
        healthDownloadRetryCount = 0
        healthListRetryCount = 0
        healthStartCommandSent = false
        healthStartAttemptCount = 0
        healthStartPollCount = 0
        healthLastStartErrorCode = 0
        healthSilentTimeoutCount = 0
        healthTransportReconnectCount = 0
        healthPostStopReconnectCount = 0
        healthResumeItem = null
        healthFlashFinalizeReadyAtMs = null
        healthListItems.clear()
        prefs.edit().remove("health_active").remove("health_phase")
            .remove("health_started").remove("health_session_id")
            .remove("health_total_bytes").remove("health_downloaded_bytes")
            .remove("health_next_offset").remove("health_flash_finalize_ready_at_ms").apply()
    }

    private fun stopImu(manual: Boolean) {
        val capture = imuCapture ?: return
        handler.removeCallbacks(lowPowerStartTimeout)
        if (manual && ready) bleClient.stopImu()
        imuCapture = null
        val endedAt = System.currentTimeMillis()
        val count = runCatching { capture.file.close() }.getOrDefault(capture.file.count())
        summaryStore.stopSession(capture.sessionId, endedAt, count)
        uploadStore.recordCapture(
            ClosedCapture(
                capture.uploadGroupId,
                capture.kind,
                capture.file.uri,
                capture.file.displayName,
                capture.startedAtMs,
                endedAt,
                count,
            ),
        )
        captureStoppedIfNeeded()
    }

    private fun stopPpg(manual: Boolean) {
        val capture = ppgCapture ?: return
        if (manual && ready) bleClient.stopPpg()
        ppgCapture = null
        activePpgMode = null
        val endedAt = System.currentTimeMillis()
        val count = runCatching { capture.file.close() }.getOrDefault(capture.file.count())
        summaryStore.stopSession(capture.sessionId, endedAt, count)
        uploadStore.recordCapture(
            ClosedCapture(
                capture.uploadGroupId,
                capture.kind,
                capture.file.uri,
                capture.file.displayName,
                capture.startedAtMs,
                endedAt,
                count,
            ),
        )
        captureStoppedIfNeeded()
    }

    private fun captureStoppedIfNeeded() {
        updateSummaryAndPersistence()
        if (!hasActiveCapture() && !motionTransitioning && !ppgTransitioning) {
            finalizeUploadGroup()
            reconnectStartedAtMs?.let {
                summaryStore.closeOpenDisconnection(System.currentTimeMillis())
                reconnectStartedAtMs = null
            }
            bleClient.setAutoReconnect(false)
            if (!ready) {
                bleClient.stop()
                bleClient.forgetSelectedDevice()
                selectedAddress = null
                selectedName = null
                connectionMessage = "点击“搜索戒指”开始查找"
                bleClient.start()
            }
            handler.removeCallbacks(summaryTicker)
            leaveForeground()
        }
        publishState()
    }

    override fun onBleState(message: String, ready: Boolean) {
        handler.post {
            val wasReady = this.ready
            this.ready = ready
            connectionMessage = message
            if (!ready && wasReady) {
                if (firmwareQueryPending) {
                    handler.removeCallbacks(firmwareQueryTimeout)
                    firmwareQueryPending = false
                    firmwareVersionText = "尚未查询"
                }
                if (hasActiveCapture() || connectionRecoveryPending || batteryRequestPending) {
                    if (healthPhase != HealthPhase.IDLE) handler.removeCallbacks(healthCommandTimeout)
                    val now = System.currentTimeMillis()
                    reconnectStartedAtMs = now
                    summaryStore.markDisconnected(activeSessionIds(), now, message)
                    bleClient.setAutoReconnect(true)
                    enterForeground()
                } else {
                    selectedAddress = null
                    selectedName = null
                    bleClient.forgetSelectedDevice()
                }
            } else if (ready) {
                val shouldResume = reconnectStartedAtMs != null || connectionRecoveryPending
                val completingBatteryProbe = batteryProbePending
                if (completingBatteryProbe) {
                    batteryProbePending = false
                    handler.removeCallbacks(batteryProbeTimeout)
                    handler.postDelayed({ requestBattery() }, BATTERY_PROBE_SETTLE_MS)
                }
                if (reconnectStartedAtMs != null) {
                    val now = System.currentTimeMillis()
                    summaryStore.markReconnected(activeSessionIds(), now)
                    reconnectStartedAtMs = null
                }
                if (shouldResume) {
                    connectionRecoveryPending = false
                    resumeCaptureCommands()
                }
                if (batteryRequestPending) {
                    handler.postDelayed({ sendBatteryRequest() }, BATTERY_PROBE_SETTLE_MS)
                } else if (!completingBatteryProbe && !shouldResume && healthPhase == HealthPhase.IDLE) {
                    handler.postDelayed({ bleClient.requestHealthStatus() }, HEALTH_STATUS_RECONCILE_DELAY_MS)
                }
                handler.postDelayed({ requestFirmwareVersionIfSafe() }, FIRMWARE_QUERY_SETTLE_MS)
            }
            updateSummaryAndPersistence()
            updateForegroundNotification()
            publishState()
        }
    }

    override fun onRingsFound(rings: List<ScannedRing>) {
        latestRings = rings
        uiListener?.onRingsFound(rings)
    }

    override fun onSensorPacket(packet: SensorPacket) {
        try {
            when (packet) {
                is SensorPacket.Imu -> {
                    imuCapture?.file?.writeImu(packet)
                    if (imuCapture?.kind?.isLowPower == true) {
                        handler.removeCallbacks(lowPowerStartTimeout)
                    }
                }
                is SensorPacket.PpgRaw -> ppgCapture?.file?.writePpg(packet)
                is SensorPacket.Battery -> handler.post {
                    handler.removeCallbacks(batteryReset)
                    handler.removeCallbacks(batteryResponseTimeout)
                    batteryRequestPending = false
                    batteryRetryCount = 0
                    batteryBusy = true
                    batteryText = "戒指剩余电量：${packet.percent.coerceIn(0, 100)}%"
                    handler.postDelayed(batteryReset, BATTERY_DISPLAY_MS)
                    requestFirmwareVersionIfSafe()
                    publishState()
                }
                is SensorPacket.Info -> handler.post {
                    handler.removeCallbacks(firmwareQueryTimeout)
                    firmwareQueryPending = false
                    firmwareVersionText = packet.firmwareVersion
                    publishState()
                }
                is SensorPacket.Health -> handler.post { handleHealth(packet.message) }
                is SensorPacket.TimeStatus -> Unit
            }
        } catch (error: Exception) {
            Log.e(TAG, "Capture write failed", error)
            handler.post { reportError("写入采集文件失败：${error.message}") }
        }
    }

    override fun onBleError(message: String) {
        handler.post { reportError(message) }
    }

    private fun resumeCaptureCommands() {
        when (healthPhase) {
            HealthPhase.STARTING, HealthPhase.COLLECTING, HealthPhase.STOPPING -> {
                if (healthPhase == HealthPhase.STARTING) cancelHealthStartCallbacks()
                handler.postDelayed({
                    if (bleClient.requestHealthStatus()) scheduleHealthTimeout()
                    else if (healthPhase == HealthPhase.STARTING) reconnectHealthTransport()
                    else if (!recoverRingConnection("恢复健康任务时连接未就绪，正在再次重连…")) {
                        abortCurrentHealthCommand()
                    }
                }, RECOVERY_COMMAND_SETTLE_MS)
                return
            }
            HealthPhase.FINALIZING -> {
                if (subjectiveFeedbackRequired) return
                handler.postDelayed({
                    healthResumeItem?.let(::beginHealthDownload) ?: requestHealthList()
                }, RECOVERY_COMMAND_SETTLE_MS)
                return
            }
            HealthPhase.LISTING -> {
                handler.postDelayed({ requestHealthList() }, RECOVERY_COMMAND_SETTLE_MS)
                return
            }
            HealthPhase.DOWNLOADING -> {
                handler.postDelayed({
                    if (!bleClient.readHealth(healthSessionId, healthNextOffset, HEALTH_READ_WINDOW_BYTES)) {
                        abortHealth("重新连接后无法继续下载戒指 Flash 数据")
                    } else scheduleHealthTimeout()
                }, RECOVERY_COMMAND_SETTLE_MS)
                return
            }
            HealthPhase.PROCESSING -> return
            HealthPhase.IDLE -> Unit
        }
        if (imuCapture?.kind == CaptureKind.IMU || imuCapture?.kind == CaptureKind.IMU_25) {
            stopImu(manual = true)
            reportError("旧版标准 IMU 采集已安全结束；新版请使用健康采集或低功耗 IMU")
        }
        var delay = 150L
        imuCapture?.let { capture ->
            handler.postDelayed({
                when (capture.kind) {
                    CaptureKind.IMU -> bleClient.startImu50Hz()
                    CaptureKind.IMU_25 -> bleClient.startImu25Hz()
                    CaptureKind.IMU_LP_25 -> bleClient.startImuLowPower25Hz()
                    CaptureKind.IMU_LP_50 -> bleClient.startImuLowPower50Hz()
                    else -> Unit
                }
                if (capture.kind.isLowPower && capture.file.count() == 0L) {
                    handler.removeCallbacks(lowPowerStartTimeout)
                    handler.postDelayed(lowPowerStartTimeout, LOW_POWER_START_TIMEOUT_MS)
                }
            }, delay)
            delay += 250L
        }
        activePpgMode?.let { mode -> handler.postDelayed({ bleClient.startPpg(mode) }, delay) }
    }

    private fun updateSummaryAndPersistence() {
        summaryStore.updateSampleCounts(
            buildMap {
                imuCapture?.let { put(it.sessionId, it.file.count()) }
                ppgCapture?.let { put(it.sessionId, it.file.count()) }
            },
            System.currentTimeMillis(),
        )
        saveActiveState()
    }

    private fun scheduleSummaryTicker() {
        handler.removeCallbacks(summaryTicker)
        if (hasActiveCapture()) handler.postDelayed(summaryTicker, SUMMARY_UPDATE_MS)
    }

    private fun activeSessionIds(): List<String> = listOfNotNull(
        imuCapture?.sessionId,
        ppgCapture?.sessionId,
    )

    private fun hasLiveCapture(): Boolean = imuCapture != null || ppgCapture != null

    private fun hasActiveCapture(): Boolean {
        return hasLiveCapture() || polarCapture != null || healthPhase != HealthPhase.IDLE ||
            subjectiveFeedbackRequired
    }

    private fun handlePolarStateChanged(state: PolarH10State) {
        if (state.connected && state.hrReady && polarCapture != null && !state.recording) {
            polarClient.resumeLiveRecording(polarCapture!!::write)
        }
        updateForegroundNotification()
        publishState()
    }

    private fun ensureUploadGroup(profile: ParticipantProfile, startedAtMs: Long): String {
        activeUploadGroupId?.let { return it }
        val sessionId = UUID.randomUUID().toString()
        val uploadLink = if (capturePurpose == CapturePurpose.DAILY_ACTIVITY) {
            BuildConfig.ACTIVITY_UPLOAD_LINK
        } else {
            profile.uploadLink
        }
        uploadStore.beginSession(
            sessionId = sessionId,
            profile = profile,
            startedAtMs = startedAtMs,
            ringAddress = selectedAddress,
            ringName = selectedName,
            capturePurpose = capturePurpose,
            activityCode = activityCode,
            ringPlacement = ringPlacement,
            uploadLink = uploadLink,
        )
        activeUploadGroupId = sessionId
        saveActiveState()
        return sessionId
    }

    private fun finalizeUploadGroup() {
        val sessionId = activeUploadGroupId ?: return
        val stopDisposition = healthStopDisposition
        val endedAt = System.currentTimeMillis()
        runCatching {
            uploadStore.finalizeSession(sessionId, endedAt, summaryStore.currentDayJson(endedAt))
            uploadStore.list().firstOrNull { it.sessionId == sessionId }
        }.onSuccess { summary ->
            activeUploadGroupId = null
            clearCollectionModes()
            prefs.edit().remove("upload_group_id").apply()
            summary?.let {
                when (stopDisposition) {
                    HealthStopDisposition.UPLOAD_NOW -> {
                        uploadStore.markUserDecision(it.sessionId, UploadSessionStore.DECISION_UPLOAD_NOW)
                        UploadScheduler.enqueue(this, it.sessionId)
                    }
                    HealthStopDisposition.DEFER -> {
                        uploadStore.markUserDecision(it.sessionId, UploadSessionStore.DECISION_DEFERRED)
                    }
                    HealthStopDisposition.DISCARD -> Unit
                    null -> uiListener?.onCaptureSessionCompleted(it)
                }
            }
            clearHealthStopDisposition()
            clearSubjectiveFeedbackState()
        }.onFailure { error ->
            reportError("生成上传会话失败：${error.message}")
        }
    }

    private fun cancelHealthStartCallbacks() {
        handler.removeCallbacks(healthStartAttempt)
        handler.removeCallbacks(healthStartStatusPoll)
    }

    private fun clearCollectionModes() {
        ouraEnabledForSession = false
        polarEnabledForSession = false
        capturePurpose = CapturePurpose.SLEEP_HR
        activityCode = null
        ringPlacement = null
        prefs.edit()
            .remove("session_oura_enabled")
            .remove("session_polar_enabled")
            .remove("capture_purpose")
            .remove("activity_code")
            .remove("ring_placement")
            .apply()
    }

    private fun clearSubjectiveFeedbackState() {
        subjectiveFeedbackRequired = false
        subjectiveFeedbackTarget = null
        subjectiveFeedbackSessionId = null
        prefs.edit()
            .remove("subjective_feedback_required")
            .remove("subjective_feedback_target")
            .remove("subjective_feedback_session_id")
            .apply()
    }

    private fun snapshot() = CaptureServiceState(
        ready = ready,
        connectionMessage = connectionMessage,
        selectedRingAddress = selectedAddress,
        selectedRingName = selectedName,
        hasSelectedRing = selectedAddress != null,
        reconnecting = !ready && hasActiveCapture(),
        imuActive = imuCapture?.kind == CaptureKind.IMU,
        imuStartedAtMs = imuCapture?.takeIf { it.kind == CaptureKind.IMU }?.startedAtMs,
        imu25Active = imuCapture?.kind == CaptureKind.IMU_25,
        imu25StartedAtMs = imuCapture?.takeIf { it.kind == CaptureKind.IMU_25 }?.startedAtMs,
        imuLp25Active = imuCapture?.kind == CaptureKind.IMU_LP_25,
        imuLp25StartedAtMs = imuCapture?.takeIf { it.kind == CaptureKind.IMU_LP_25 }?.startedAtMs,
        imuLp50Active = imuCapture?.kind == CaptureKind.IMU_LP_50,
        imuLp50StartedAtMs = imuCapture?.takeIf { it.kind == CaptureKind.IMU_LP_50 }?.startedAtMs,
        motionTransitioning = motionTransitioning,
        ppgMode = activePpgMode,
        ppgStartedAtMs = ppgCapture?.startedAtMs,
        ppgTransitioning = ppgTransitioning,
        batteryText = batteryText,
        batteryBusy = batteryBusy,
        firmwareVersionText = firmwareVersionText,
        healthPhase = healthPhase,
        healthStartedAtMs = healthStartedAtMs,
        healthDownloadedBytes = healthDownloadedBytes,
        healthTotalBytes = healthTotalBytes,
        ouraEnabledForSession = ouraEnabledForSession,
        polarEnabledForSession = polarEnabledForSession,
        capturePurpose = capturePurpose,
        activityCode = activityCode,
        ringPlacement = ringPlacement,
        subjectiveFeedbackRequired = subjectiveFeedbackRequired,
        subjectiveFeedbackTarget = subjectiveFeedbackTarget,
        polarCaptureActive = polarCapture != null,
        polar = if (::polarClient.isInitialized) polarClient.snapshot() else PolarH10State(),
    )

    private fun publishState() {
        uiListener?.onServiceState(snapshot())
    }

    private fun reportError(message: String) {
        connectionMessage = message
        uiListener?.onServiceError(message)
        publishState()
    }

    private fun enterForeground() {
        if (!foreground) {
            startForeground(NOTIFICATION_ID, buildNotification())
            foreground = true
        } else {
            updateForegroundNotification()
        }
        acquireWakeLock()
    }

    private fun leaveForeground() {
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
        releaseWakeLock()
        if (uiListener == null) stopSelf()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "RingFitness 数据采集",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "保持戒指在手机息屏时持续采集和自动重连" },
        )
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val active = buildList {
            imuCapture?.let { add(motionLabel(it.kind)) }
            activePpgMode?.let { add("PPG ${it.displayName}") }
            if (healthPhase != HealthPhase.IDLE) add("健康采集 IMU + PPG")
        }.joinToString(" + ")
        val status = if (!ready) "蓝牙断连，正在自动重连；文件保持打开" else "$active 正在采集"
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("RingFitness 数据采集运行中")
            .setContentText(status)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun updateForegroundNotification() {
        if (foreground) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:overnight-capture")
            .apply { setReferenceCounted(false); acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun saveActiveState() {
        prefs.edit().apply {
            putLong("last_persisted_ms", System.currentTimeMillis())
            putString("selected_address", selectedAddress)
            putString("selected_name", selectedName)
            putString("upload_group_id", activeUploadGroupId)
            putBoolean("session_oura_enabled", ouraEnabledForSession)
            putBoolean("session_polar_enabled", polarEnabledForSession)
            putString("capture_purpose", capturePurpose.wireValue)
            activityCode?.let { putString("activity_code", it.wireValue) } ?: remove("activity_code")
            ringPlacement?.let { putString("ring_placement", it.wireValue) } ?: remove("ring_placement")
            putBoolean("subjective_feedback_required", subjectiveFeedbackRequired)
            subjectiveFeedbackTarget?.let { putString("subjective_feedback_target", it.wireValue) }
                ?: remove("subjective_feedback_target")
            subjectiveFeedbackSessionId?.let { putString("subjective_feedback_session_id", it) }
                ?: remove("subjective_feedback_session_id")
            healthFlashFinalizeReadyAtMs?.let { putLong("health_flash_finalize_ready_at_ms", it) }
                ?: remove("health_flash_finalize_ready_at_ms")
            saveCapture(this, "imu", imuCapture)
            saveCapture(this, "ppg", ppgCapture)
            putString("ppg_mode", activePpgMode?.name)
            putBoolean("health_active", healthPhase != HealthPhase.IDLE && healthStartedAtMs != null)
            putString("health_phase", healthPhase.name)
            healthStartedAtMs?.let { putLong("health_started", it) } ?: remove("health_started")
            putInt("health_session_id", healthSessionId)
            putLong("health_total_bytes", healthTotalBytes)
            putLong("health_downloaded_bytes", healthDownloadedBytes)
            putLong("health_next_offset", healthNextOffset)
            healthStopDisposition?.let { putString("health_stop_disposition", it.name) }
                ?: remove("health_stop_disposition")
            putString("polar_device_id", polarDeviceId)
            polarCaptureStartedAtMs?.let { putLong("polar_started", it) } ?: remove("polar_started")
            polarCapture?.let {
                putString("polar_live_uri", it.uri.toString())
                putString("polar_live_name", it.displayName)
                putLong("polar_live_count", it.count())
            } ?: run {
                remove("polar_live_uri")
                remove("polar_live_name")
                remove("polar_live_count")
            }
            apply()
        }
    }

    private fun saveCapture(
        editor: android.content.SharedPreferences.Editor,
        prefix: String,
        capture: ActiveCapture?,
    ) {
        if (capture == null) {
            listOf("id", "group", "kind", "uri", "name", "started", "count").forEach {
                editor.remove("${prefix}_$it")
            }
        } else {
            editor.putString("${prefix}_id", capture.sessionId)
            editor.putString("${prefix}_group", capture.uploadGroupId)
            editor.putString("${prefix}_kind", capture.kind.name)
            editor.putString("${prefix}_uri", capture.file.uri.toString())
            editor.putString("${prefix}_name", capture.file.displayName)
            editor.putLong("${prefix}_started", capture.startedAtMs)
            editor.putLong("${prefix}_count", capture.file.count())
        }
    }

    private fun restoreActiveState() {
        selectedAddress = prefs.getString("selected_address", null)
        selectedName = prefs.getString("selected_name", null)
        activeUploadGroupId = prefs.getString("upload_group_id", null)
        ouraEnabledForSession = prefs.getBoolean("session_oura_enabled", false)
        polarEnabledForSession = prefs.getBoolean("session_polar_enabled", false)
        capturePurpose = CapturePurpose.fromWireValue(prefs.getString("capture_purpose", null))
        activityCode = DailyActivity.fromWireValue(prefs.getString("activity_code", null))
        ringPlacement = RingPlacement.fromWireValue(prefs.getString("ring_placement", null))
        subjectiveFeedbackRequired = prefs.getBoolean("subjective_feedback_required", false)
        subjectiveFeedbackTarget = SubjectiveFeedbackTarget.fromWireValue(
            prefs.getString("subjective_feedback_target", null),
        )
        subjectiveFeedbackSessionId = prefs.getString("subjective_feedback_session_id", null)
        healthFlashFinalizeReadyAtMs = prefs.getLong("health_flash_finalize_ready_at_ms", 0L)
            .takeIf { it > 0L }
        imuCapture = restoreCapture("imu")
        ppgCapture = restoreCapture("ppg")
        activePpgMode = prefs.getString("ppg_mode", null)?.let { runCatching { PpgMode.valueOf(it) }.getOrNull() }
        healthStopDisposition = prefs.getString("health_stop_disposition", null)?.let {
            runCatching { HealthStopDisposition.valueOf(it) }.getOrNull()
        }
        polarDeviceId = prefs.getString("polar_device_id", null)
        polarCaptureStartedAtMs = prefs.getLong("polar_started", 0L).takeIf { it >= MIN_PLAUSIBLE_UNIX_MS }
        val polarUri = prefs.getString("polar_live_uri", null)?.let(Uri::parse)
        val polarName = prefs.getString("polar_live_name", null)
        val polarCount = prefs.getLong("polar_live_count", 0L)
        if (polarUri != null && polarName != null) {
            polarCapture = runCatching {
                PolarHrRrCapture.reopen(this, polarUri, polarName, polarCount)
            }.onFailure { Log.e(TAG, "Unable to restore Polar live capture", it) }.getOrNull()
        }
        if (prefs.getBoolean("health_active", false)) {
            val restoredPhase = prefs.getString("health_phase", null)?.let {
                runCatching { HealthPhase.valueOf(it) }.getOrNull()
            } ?: HealthPhase.COLLECTING
            healthPhase = when (restoredPhase) {
                HealthPhase.STOPPING,
                HealthPhase.FINALIZING,
                HealthPhase.LISTING,
                HealthPhase.DOWNLOADING,
                HealthPhase.PROCESSING,
                -> HealthPhase.FINALIZING
                else -> restoredPhase
            }
            healthStartedAtMs = prefs.getLong("health_started", 0L).takeIf { it >= MIN_PLAUSIBLE_UNIX_MS }
            healthSessionId = prefs.getInt("health_session_id", 0)
            healthTotalBytes = prefs.getLong("health_total_bytes", 0L)
            healthDownloadedBytes = prefs.getLong("health_downloaded_bytes", 0L)
            healthNextOffset = prefs.getLong("health_next_offset", healthDownloadedBytes)
            activeUploadGroupId?.let { groupId ->
                val payload = runCatching { uploadStore.read(groupId) }.getOrNull()
                healthResumeItem = payload?.takeIf {
                    it.has("ring_anchor_uptime_ms") && it.has("ring_anchor_unix_ms") &&
                        healthSessionId != 0 && healthTotalBytes > 0
                }?.let {
                    HealthMessage.ListItem(
                        sessionId = healthSessionId,
                        bytes = healthTotalBytes,
                        records = it.optLong("ring_record_count", 0L),
                        uptimeMs = it.optLong("ring_anchor_uptime_ms", 0L),
                        unixMs = it.optLong("ring_anchor_unix_ms", 0L),
                    )
                }
            }
        }
        if (!hasActiveCapture()) reconnectStartedAtMs = null
    }

    private fun clearPolarState() {
        polarCaptureStartedAtMs = null
        polarDeviceId = null
        prefs.edit()
            .remove("polar_device_id")
            .remove("polar_exercise_id")
            .remove("polar_internal_recording_type")
            .remove("polar_started")
            .remove("polar_finalizing")
            .remove("polar_discard")
            .remove("polar_live_uri")
            .remove("polar_live_name")
            .remove("polar_live_count")
            .apply()
    }

    private fun restoreCapture(prefix: String): ActiveCapture? {
        val id = prefs.getString("${prefix}_id", null) ?: return null
        val groupId = prefs.getString("${prefix}_group", null)
            ?: activeUploadGroupId
            ?: return null
        val kind = prefs.getString("${prefix}_kind", null)?.let {
            runCatching { CaptureKind.valueOf(it) }.getOrNull()
        } ?: return null
        val uri = prefs.getString("${prefix}_uri", null)?.let(Uri::parse) ?: return null
        val name = prefs.getString("${prefix}_name", null) ?: return null
        val started = prefs.getLong("${prefix}_started", 0L).takeIf { it > 0 } ?: return null
        val count = prefs.getLong("${prefix}_count", 0L)
        val file = runCatching { CaptureFile.reopen(this, kind, uri, name, count) }
            .onFailure { Log.e(TAG, "Unable to restore $prefix capture", it) }
            .getOrNull() ?: return null
        return ActiveCapture(id, groupId, kind, file, started)
    }

    private fun motionLabel(kind: CaptureKind): String = when (kind) {
        CaptureKind.IMU -> "IMU 50 Hz"
        CaptureKind.IMU_25 -> "IMU 25 Hz"
        CaptureKind.IMU_LP_25 -> "低功耗 ACC 25 Hz"
        CaptureKind.IMU_LP_50 -> "低功耗 ACC 50 Hz"
        else -> "传感器"
    }

    companion object {
        private const val TAG = "RingCaptureService"
        private const val PREFS_NAME = "active_capture"
        private const val CHANNEL_ID = "overnight_capture"
        private const val NOTIFICATION_ID = 7201
        private const val SUMMARY_UPDATE_MS = 60_000L
        private const val PPG_SWITCH_DELAY_MS = 300L
        private const val MOTION_SWITCH_DELAY_MS = 300L
        private const val LOW_POWER_START_TIMEOUT_MS = 5_000L
        private const val BATTERY_DISPLAY_MS = 3_000L
        private const val BATTERY_RESPONSE_TIMEOUT_MS = 5_000L
        private const val BATTERY_RETRY_LIMIT = 1
        private const val BATTERY_PROBE_SETTLE_MS = 150L
        private const val BATTERY_PROBE_TIMEOUT_MS = 10_000L
        private const val FIRMWARE_QUERY_SETTLE_MS = 600L
        private const val FIRMWARE_QUERY_TIMEOUT_MS = 3_000L
        private const val HEALTH_STATUS_DELAY_MS = 500L
        private const val HEALTH_STOP_STATUS_POLL_MS = 1_000L
        private const val HEALTH_START_SETTLE_DELAY_MS = 500L
        private const val HEALTH_START_FIRST_POLL_DELAY_MS = 1_000L
        private const val HEALTH_START_POLL_INTERVAL_MS = 1_500L
        private const val HEALTH_START_POLLS_PER_ATTEMPT = 3
        private const val HEALTH_START_MAX_ATTEMPTS = 2
        private const val HEALTH_START_RESPONSE_TIMEOUT_MS = 4_000L
        private const val HEALTH_SILENT_RETRY_LIMIT = 2
        private const val HEALTH_TRANSPORT_RECONNECT_LIMIT = 1
        private const val HEALTH_POST_STOP_RECONNECT_LIMIT = 1
        private const val HEALTH_STATUS_RECONCILE_DELAY_MS = 250L
        private const val RECOVERY_COMMAND_SETTLE_MS = 800L
        private const val MIN_PLAUSIBLE_UNIX_MS = 946_684_800_000L
        private const val HEALTH_COMMAND_TIMEOUT_MS = 10_000L
        // Data-rescue build: use an 8 KiB transaction while retaining strict
        // application-level flow control (wait for READ_END before the next request).
        private const val HEALTH_READ_WINDOW_BYTES = 8 * 1_024
        private const val HEALTH_PROGRESS_PUBLISH_BYTES = 4 * 1_024L
        private const val HEALTH_DOWNLOAD_RETRY_LIMIT = 3
        private const val HEALTH_FLASH_FINALIZE_DELAY_MS = 5_000L
        private const val HEALTH_LIST_RETRY_INTERVAL_MS = 2_000L
        private const val HEALTH_LIST_RETRY_LIMIT = 30
        private const val BATTERY_DEFAULT_TEXT = "检测戒指剩余电量"
    }
}
