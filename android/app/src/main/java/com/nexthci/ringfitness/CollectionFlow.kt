package com.nexthci.ringfitness

/** UI contract shared by the native collection pages and their environment-specific owner. */
enum class CollectionPage {
    HOME, STARTING, COLLECTING, STOPPING, REFERENCE, SAVING, DOWNLOADING, UPLOADING, COMPLETE, RECOVERY, ERROR,
}

enum class FlowTestFault { NONE, START_TIMEOUT, STOP_TIMEOUT, SAVE_FAILURE, DOWNLOAD_FAILURE, UPLOAD_FAILURE }

data class FlowRecordSummary(
    val sessionId: String,
    val steps: Long?,
    val referenceStatus: String?,
    val transferStatus: String,
    val localComplete: Boolean,
)

data class CollectionFlowState(
    val page: CollectionPage = CollectionPage.HOME,
    val isSimulation: Boolean,
    val hasProfile: Boolean = false,
    val participantId: String = "",
    val placement: RingPlacement? = null,
    val session: FreeLivingSession? = null,
    val connected: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val savedSteps: Long? = null,
    val referenceStatus: String? = null,
    val canStart: Boolean = false,
    val canStop: Boolean = false,
    val canRetry: Boolean = false,
    val records: List<FlowRecordSummary> = emptyList(),
    val fault: FlowTestFault = FlowTestFault.NONE,
)

interface CollectionFlow {
    val state: CollectionFlowState
    fun observe(observer: (CollectionFlowState) -> Unit): AutoCloseable
    fun register(participantId: String, placement: RingPlacement)
    fun start()
    fun stop()
    fun enterReference()
    fun saveReference(stepsText: String, status: String = "valid", reason: String = "")
    fun retry()
    fun retryUpload(sessionId: String)
    fun home()
    fun setFault(fault: FlowTestFault)
    fun disconnect()
    fun reconnect()
}
