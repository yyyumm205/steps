package com.nexthci.ringfitness

/** UI contract shared by the native collection pages and their environment-specific owner. */
enum class CollectionPage {
    HOME, STARTING, COLLECTING, STOPPING, FINISH, REFERENCE, SAVING, RING_PENDING, DOWNLOADING, UPLOADING, COMPLETE, RECOVERY, ERROR,
}

enum class FlowTestFault { NONE, START_TIMEOUT, STOP_TIMEOUT, SAVE_FAILURE, DOWNLOAD_FAILURE, UPLOAD_FAILURE }

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
): SessionReference {
    val kind = ReferenceStatus.entries.singleOrNull { it.wireValue == status }
        ?: throw IllegalArgumentException("请选择读数状态")
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
