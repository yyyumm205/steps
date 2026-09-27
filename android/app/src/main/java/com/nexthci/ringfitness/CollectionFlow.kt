package com.nexthci.ringfitness

/** UI contract shared by the native collection pages and their environment-specific owner. */
enum class CollectionPage {
    HOME, STARTING, COLLECTING, STOPPING, FINISH, REFERENCE, SAVING, RING_PENDING, DOWNLOADING, UPLOADING, COMPLETE, RECOVERY, ERROR,
}

enum class FlowTestFault { NONE, START_TIMEOUT, STOP_TIMEOUT, SAVE_FAILURE, DOWNLOAD_FAILURE, UPLOAD_FAILURE }

data class PolarUiDevice(val deviceId: String, val name: String, val rssi: Int)

/** Bluetooth details presented without exposing SDK objects to the flow or its simulations. */
data class PolarUiState(
    val devices: List<PolarUiDevice> = emptyList(),
    val selectedDeviceId: String? = null,
    val connected: Boolean = false,
    val hrReady: Boolean = false,
    val connecting: Boolean = false,
    val scanning: Boolean = false,
    val recording: Boolean = false,
    val lastHeartRate: Int? = null,
    val message: String = "",
) {
    val ready: Boolean get() = connected && hrReady && selectedDeviceId != null
}

internal fun heartRateStatusLabel(enabled: Boolean, polar: PolarUiState, captureActive: Boolean = false): String = when {
    !enabled -> "未启用"
    polar.scanning -> "正在搜索 Polar H10…"
    polar.connecting -> if (captureActive) "心率带正在重连…" else "正在连接 Polar H10…"
    !polar.connected && polar.selectedDeviceId != null ->
        if (captureActive) "心率带连接已中断，等待恢复" else "连接已中断，请重新连接"
    polar.connected && !polar.hrReady -> "已连接，等待 HR/RR 就绪"
    polar.ready && polar.recording -> polar.lastHeartRate?.let { "$it bpm · 正在采集" } ?: "正在采集 HR/RR"
    polar.ready && captureActive -> "已连接，等待 HR/RR 数据"
    polar.ready -> "Polar H10 已就绪"
    else -> "请搜索并连接 Polar H10"
}

data class FlowRecordSummary(
    val sessionId: String,
    val steps: Long?,
    val referenceStatus: String?,
    val transferStatus: String,
    val localComplete: Boolean,
    val transferInFlight: Boolean = false,
    val localReviewRequired: Boolean = false,
    val activity: SessionActivity = SessionActivity.FREE_LIVING,
    val uploadDeferred: Boolean = false,
    val startedAtMs: Long? = null,
    val timeZoneId: String? = null,
    val referenceEditable: Boolean = false,
    val referenceReason: String? = null,
    val ringDeferred: Boolean = false,
    /** A requested upload has no active or persisted system job and can be queued again. */
    val uploadRequeueAvailable: Boolean = false,
    /** Phone-side anchor used only when rendering history without a verified sample boundary. */
    val phoneStartAtMs: Long? = null,
)

internal val FlowRecordSummary.displayStartedAtMs: Long?
    get() = startedAtMs ?: phoneStartAtMs

internal val FlowRecordSummary.referenceLabel: String
    get() = if (!activity.requiresReferenceSteps) "无需计步" else steps?.let { "$it 步" } ?: "未提供读数"

internal fun FreeLivingSession.phoneStartAnchorMs(): Long? =
    startConfirmedAtMs ?: startRequestedAtMs.takeIf { it > 0 }

data class CollectionFlowState(
    val page: CollectionPage = CollectionPage.HOME,
    val taskPage: CollectionPage? = null,
    val isSimulation: Boolean,
    val uploadAvailable: Boolean = true,
    val hasProfile: Boolean = false,
    val participantId: String = "",
    /** Local-only label; research files continue to use [participantId]. */
    val participantLabel: String = "",
    val ringName: String = "",
    val placement: RingPlacement? = null,
    val session: FreeLivingSession? = null,
    val connected: Boolean = true,
    val connecting: Boolean = false,
    val checkingDevice: Boolean = false,
    val preservingExisting: Boolean = false,
    /** Durable raw bytes already saved for the current ring transfer. */
    val downloadSavedBytes: Long? = null,
    val downloadTotalBytes: Long? = null,
    val downloadFinalizing: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val savedSteps: Long? = null,
    val referenceStatus: String? = null,
    val canStart: Boolean = false,
    val canStop: Boolean = false,
    val canStopUnconfirmedStart: Boolean = false,
    val canRetry: Boolean = false,
    val canEndStartAttempt: Boolean = false,
    val records: List<FlowRecordSummary> = emptyList(),
    val fault: FlowTestFault = FlowTestFault.NONE,
    val selectedActivity: SessionActivity? = null,
    val heartRateEnabled: Boolean = false,
    val heartRateState: PolarUiState = PolarUiState(),
    /** Recoverable UI input. It is not research reference data until stop confirmation. */
    val referenceDraft: String? = null,
    /** Set when the visible input has not yet replaced the last durable draft. */
    val referenceDraftError: String? = null,
) {
    val canRecordReferenceLocally: Boolean get() = session?.let {
        it.isPending && it.stopConfirmedAtMs != null && it.reference == null && it.startAbort == null
    } == true
}

interface CollectionFlow {
    val state: CollectionFlowState
    fun observe(observer: (CollectionFlowState) -> Unit): AutoCloseable
    fun register(participantId: String, placement: RingPlacement)
    fun start()
    fun selectActivity(activity: SessionActivity) = Unit
    fun setHeartRateEnabled(enabled: Boolean) = Unit
    fun scanHeartRate() = Unit
    fun connectHeartRate(deviceId: String) = Unit
    fun stop()
    fun chooseFinish(uploadNow: Boolean) = Unit
    /** Commits the completion choice and reference observation as one durable operation. */
    fun finalizeSession(uploadNow: Boolean, stepsText: String, status: String = "valid", reason: String = "")
    fun enterFinish() = Unit
    fun discardSession() = Unit
    fun enterReference()
    fun updateReferenceDraft(stepsText: String) = Unit
    fun saveReference(stepsText: String, status: String = "valid", reason: String = "")
    fun retry()
    fun endStartAttempt(reason: String) = Unit
    fun retryUpload(sessionId: String)
    /** Explicit consent to download a session parked on its original ring and then upload it. */
    fun resumeRingTransfer() = Unit
    /** Replaces a locally saved reference before the participant confirms its first upload. */
    fun reviseReference(sessionId: String, stepsText: String, status: String = "valid", reason: String = "") = Unit
    fun home()
    fun setFault(fault: FlowTestFault)
    fun disconnect()
    fun reconnect()
}

internal fun sessionReferenceFromInput(
    stepsText: String,
    status: String,
    reason: String,
    recordedAtMs: Long,
    activity: SessionActivity = SessionActivity.FREE_LIVING,
): SessionReference {
    if (!activity.requiresReferenceSteps) {
        require(stepsText.isBlank() && reason.isBlank() && status == ReferenceStatus.NOT_APPLICABLE.wireValue) {
            "本项运动无需填写步数"
        }
        return SessionReference(ReferenceStatus.NOT_APPLICABLE, null, recordedAtMs)
    }
    val kind = ReferenceStatus.entries.singleOrNull { it.wireValue == status }
        ?: throw IllegalArgumentException("请选择读数状态")
    require(kind != ReferenceStatus.NOT_APPLICABLE) { "请填写本次参考步数" }
    val steps = when {
        kind == ReferenceStatus.MISSING -> null
        stepsText.trim().matches(Regex("[0-9]+")) -> stepsText.trim().toLongOrNull()
            ?: throw IllegalArgumentException("步数太大，请核对读数")
        else -> throw IllegalArgumentException("请输入计步器上的整数")
    }
    val note = reason.trim().ifEmpty { null }
    require(kind == ReferenceStatus.VALID || note != null) { "请填写简短原因" }
    return SessionReference(kind, steps, recordedAtMs, if (kind == ReferenceStatus.VALID) null else note)
}
