package com.nexthci.ringfitness

import android.content.Context
import com.polar.androidcommunications.api.ble.model.DisInfo
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApiCallback
import com.polar.sdk.api.PolarBleApiDefaultImpl
import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.sdk.api.model.PolarHealthThermometerData
import com.polar.sdk.api.model.PolarHrData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class PolarH10State(
    val devices: List<PolarDeviceInfo> = emptyList(),
    val selectedDeviceId: String? = null,
    val connected: Boolean = false,
    val hrReady: Boolean = false,
    val connecting: Boolean = false,
    val connectionFailed: Boolean = false,
    val scanning: Boolean = false,
    val recording: Boolean = false,
    val lastHeartRate: Int? = null,
    val message: String = "尚未连接 Polar H10",
)

class PolarH10Client(context: Context, private val listener: (PolarH10State) -> Unit) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val applicationContext = context.applicationContext
    private var api = createApi()
    private var state = PolarH10State()
    // SDK callbacks, UI actions and Flow collectors can run on different threads.
    private val stateLock = Any()
    private var closed = false
    private var scanJob: Job? = null
    private var scanGeneration = 0L
    private val preparationGate = PolarPreparationGate()
    private var preparationJob: Job? = null
    private var preparationRebuildJob: Job? = null
    private var streamJob: Job? = null
    private var reconnectJob: Job? = null
    private var captureRequested = false
    private var captureGeneration = 0L
    private var streamGeneration = 0L
    private var reconnectGeneration = 0L
    private var captureDeviceId: String? = null
    // A new stream waits for cancellation and SDK STOP of the preceding stream.
    private var streamBarrier: Job? = null
    private var sampleConsumer: ((PolarHrData.PolarHrSample, Long) -> Unit)? = null

    init {
        installApiCallbacks(api, preparationGate.clientGeneration)
    }

    private fun createApi(): PolarBleApi = PolarBleApiDefaultImpl.defaultImplementation(
        applicationContext,
        setOf(
            PolarBleApi.PolarBleSdkFeature.FEATURE_HR,
            PolarBleApi.PolarBleSdkFeature.FEATURE_DEVICE_INFO,
            PolarBleApi.PolarBleSdkFeature.FEATURE_BATTERY_INFO,
        ),
    )

    private fun installApiCallbacks(activeApi: PolarBleApi, generation: Long) {
        activeApi.setAutomaticReconnection(true)
        activeApi.setApiCallback(object : PolarBleApiCallback() {
            override fun deviceConnecting(polarDeviceInfo: PolarDeviceInfo) {
                synchronized(stateLock) {
                    if (closed || !preparationGate.acceptsClient(generation)) return
                    if (!acceptDevice(polarDeviceInfo.deviceId)) return
                    update {
                        it.copy(
                            selectedDeviceId = polarDeviceInfo.deviceId,
                            connecting = true,
                            connectionFailed = false,
                            message = if (captureRequested) {
                                "正在自动重连 ${polarDeviceInfo.name}…"
                            } else {
                                "正在连接 ${polarDeviceInfo.name}…"
                            },
                        )
                    }
                }
            }

            override fun deviceConnected(polarDeviceInfo: PolarDeviceInfo) {
                synchronized(stateLock) {
                    if (closed || !preparationGate.acceptsClient(generation)) return
                    if (!acceptDevice(polarDeviceInfo.deviceId)) return
                    update {
                        it.copy(
                            devices = listOf(polarDeviceInfo),
                            selectedDeviceId = polarDeviceInfo.deviceId,
                            connected = true,
                            connecting = !it.hrReady,
                            connectionFailed = false,
                            message = "${polarDeviceInfo.name} 已连接，等待实时 HR/RR 服务",
                        )
                    }
                }
            }

            override fun deviceDisconnected(polarDeviceInfo: PolarDeviceInfo) {
                synchronized(stateLock) {
                    if (closed || !preparationGate.acceptsClient(generation)) return
                    val retirement = preparationGate.disconnected(polarDeviceInfo.deviceId)
                    if (retirement.expected && !captureRequested) {
                        retirement.retry?.let(::issuePreparationConnection)
                        return
                    }
                    if (!acceptDevice(polarDeviceInfo.deviceId)) return
                    cancelPreparationTimeout()
                    retireStream()
                    update {
                        it.copy(
                            connected = false,
                            hrReady = false,
                            connecting = captureRequested,
                            connectionFailed = !captureRequested,
                            recording = false,
                            lastHeartRate = null,
                            message = if (captureRequested) {
                                "Polar H10 已断开，正在自动重连；断连区间保持为空"
                            } else {
                                "Polar H10 已断开"
                            },
                        )
                    }
                    if (captureRequested) scheduleReconnect(polarDeviceInfo.deviceId)
                }
            }

            override fun bleSdkFeatureReady(identifier: String, feature: PolarBleApi.PolarBleSdkFeature) {
                synchronized(stateLock) {
                    if (closed || !preparationGate.acceptsClient(generation)) return
                    if (feature == PolarBleApi.PolarBleSdkFeature.FEATURE_HR) markHrReady(identifier)
                }
            }

            override fun bleSdkFeaturesReadiness(
                identifier: String,
                ready: List<PolarBleApi.PolarBleSdkFeature>,
                unavailable: List<PolarBleApi.PolarBleSdkFeature>,
            ) {
                synchronized(stateLock) {
                    if (closed || !preparationGate.acceptsClient(generation)) return
                    when {
                        ready.contains(PolarBleApi.PolarBleSdkFeature.FEATURE_HR) -> markHrReady(identifier)
                        unavailable.contains(PolarBleApi.PolarBleSdkFeature.FEATURE_HR) -> {
                            if (!captureRequested && acceptDevice(identifier)) {
                                failPreparation(identifier, "连接 H10 失败：心率服务不可用")
                            }
                        }
                    }
                }
            }

            override fun disInformationReceived(identifier: String, disInfo: DisInfo) = Unit

            override fun htsNotificationReceived(identifier: String, data: PolarHealthThermometerData) = Unit
        })
    }

    fun snapshot(): PolarH10State = synchronized(stateLock) { state }

    fun beginFreshSelection(): Unit = synchronized(stateLock) {
        if (closed || captureRequested) return@synchronized
        scanJob?.cancel()
        scanJob = null
        cancelPreparationTimeout()
        cancelReconnect()
        scanGeneration += 1
        val previousDevice = state.selectedDeviceId
        val disconnectPrevious = state.connected || state.connecting
        update {
            PolarH10State(message = "")
        }
        if (disconnectPrevious) previousDevice?.let(::requestPreparationDisconnection)
    }

    fun search(): Unit = synchronized(stateLock) {
        if (closed || captureRequested) return@synchronized
        scanJob?.cancel()
        cancelPreparationTimeout()
        cancelReconnect()
        scanGeneration += 1
        val generation = scanGeneration
        if (!preparationGate.clientReady) replacePreparationClient()
        val previousDevice = state.selectedDeviceId
        val disconnectPrevious = state.connected || state.connecting
        update {
            it.copy(
                devices = emptyList(),
                selectedDeviceId = null,
                connected = false,
                hrReady = false,
                scanning = true,
                connecting = false,
                connectionFailed = false,
                message = "正在搜索 Polar H10…",
                lastHeartRate = null,
            )
        }
        if (disconnectPrevious) previousDevice?.let(::requestPreparationDisconnection)
        scanJob = scope.launch {
            delay(300)
            if (!synchronized(stateLock) { !closed && generation == scanGeneration }) return@launch
            val found = linkedMapOf<String, PolarDeviceInfo>()
            val failure = collectPolarScan(10_000, { api.searchForDevice("Polar H10") }) { device ->
                synchronized(stateLock) {
                    if (!closed && generation == scanGeneration &&
                        (device.hasHeartRateService || device.name.contains("H10", true))) {
                        found[device.deviceId] = device
                        update { it.copy(devices = found.values.sortedByDescending(PolarDeviceInfo::rssi)) }
                    }
                }
            }
            synchronized(stateLock) {
                if (!closed && generation == scanGeneration) {
                    scanJob = null
                    update {
                        it.copy(
                            scanning = false,
                            message = when {
                                failure != null -> "搜索 H10 失败：请检查蓝牙和权限后重试"
                                found.isEmpty() -> "没有发现 Polar H10"
                                else -> "请选择 Polar H10"
                            },
                        )
                    }
                }
            }
        }
    }

    fun connect(deviceId: String): Unit = synchronized(stateLock) {
        if (closed || captureRequested) return@synchronized
        scanJob?.cancel()
        cancelPreparationTimeout()
        cancelReconnect()
        scanGeneration += 1
        val previousDevice = state.selectedDeviceId
        val disconnectPrevious = state.connected || state.connecting
        update {
            it.copy(
                devices = it.devices.filter { device -> device.deviceId == deviceId },
                selectedDeviceId = deviceId,
                connected = false,
                hrReady = false,
                scanning = false,
                connecting = true,
                connectionFailed = false,
                message = "正在连接 Polar H10…",
                lastHeartRate = null,
            )
        }
        if (disconnectPrevious) previousDevice?.takeIf { it != deviceId }?.let(::requestPreparationDisconnection)
        issuePreparationConnection(startPreparationTimeout(deviceId))
    }

    fun startRecording(consumer: (PolarHrData.PolarHrSample, Long) -> Unit): Boolean = synchronized(stateLock) {
        val deviceId = state.selectedDeviceId ?: return@synchronized false
        if (closed || captureRequested || !state.connected || !state.hrReady || streamJob?.isActive == true) return@synchronized false
        cancelPreparationTimeout()
        captureGeneration += 1
        captureDeviceId = deviceId
        sampleConsumer = consumer
        captureRequested = true
        startLiveStream(deviceId)
        true
    }

    fun resumeLiveRecording(consumer: (PolarHrData.PolarHrSample, Long) -> Unit): Boolean = synchronized(stateLock) {
        val deviceId = state.selectedDeviceId ?: return@synchronized false
        if (closed || (captureRequested && captureDeviceId != deviceId)) return@synchronized false
        cancelPreparationTimeout()
        if (!captureRequested) {
            captureGeneration += 1
            captureDeviceId = deviceId
        }
        // Repeated readiness notifications must not replace an active capture's consumer.
        if (sampleConsumer == null) sampleConsumer = consumer
        captureRequested = true
        if (state.connected && state.hrReady) {
            startLiveStream(deviceId)
            true
        } else {
            scheduleReconnect(deviceId)
            false
        }
    }

    private fun startLiveStream(deviceId: String) {
        if (!captureRequested || captureDeviceId != deviceId || !acceptDevice(deviceId) ||
            !state.connected || !state.hrReady || streamJob?.isActive == true) return
        val consumer = sampleConsumer ?: return
        cancelReconnect()
        val capture = captureGeneration
        val stream = ++streamGeneration
        val barrier = streamBarrier
        val next = scope.launch(start = CoroutineStart.LAZY) {
            try {
                barrier?.join()
                val flow = synchronized(stateLock) {
                    if (!ownsStream(deviceId, capture, stream)) return@launch
                    update { it.copy(recording = true, lastHeartRate = null, message = "Polar H10 正在实时采集 HR/RR") }
                    if (!ownsStream(deviceId, capture, stream)) return@launch
                    api.startHrStreaming(deviceId)
                }
                flow
                    .collect { data ->
                        val now = System.currentTimeMillis()
                        data.samples.forEach { sample ->
                            synchronized(stateLock) {
                                if (ownsStream(deviceId, capture, stream)) {
                                    consumer(sample, now)
                                    if (ownsStream(deviceId, capture, stream)) update { it.copy(lastHeartRate = sample.hr) }
                                }
                            }
                        }
                    }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                synchronized(stateLock) {
                    if (ownsStream(deviceId, capture, stream)) {
                        update {
                            it.copy(
                                recording = false,
                                message = "H10 实时流中断，正在自动重连；断连区间保持为空：${error.message}",
                            )
                        }
                    }
                }
            } finally {
                synchronized(stateLock) {
                    if (ownsStream(deviceId, capture, stream)) {
                        streamJob = null
                        update { it.copy(recording = false) }
                        // A failed HR stream can still report connected/ready. Back off before retry.
                        scheduleReconnect(deviceId, initialDelayMs = 2_000)
                    }
                }
            }
        }
        streamJob = next
        next.start()
    }

    private fun scheduleReconnect(deviceId: String, initialDelayMs: Long = 0) {
        if (closed || !captureRequested || captureDeviceId != deviceId || !acceptDevice(deviceId) ||
            reconnectJob?.isActive == true) return
        val capture = captureGeneration
        val reconnect = ++reconnectGeneration
        val next = scope.launch(start = CoroutineStart.LAZY) {
            try {
                if (initialDelayMs > 0) delay(initialDelayMs)
                var attempt = 0
                while (true) {
                    val waiting = synchronized(stateLock) {
                        if (!ownsReconnect(deviceId, capture, reconnect)) return@synchronized false
                        if (state.connected && state.hrReady) {
                            startLiveStream(deviceId)
                            false
                        } else {
                            update {
                                it.copy(message = "Polar H10 已断开，正在自动重连（第 ${attempt + 1} 次）…")
                            }
                            runCatching { api.connectToDevice(deviceId) }
                            true
                        }
                    }
                    if (!waiting) break
                    val waitMs = minOf(30_000L, 2_000L shl minOf(attempt, 3))
                    var elapsed = 0L
                    while (elapsed < waitMs && synchronized(stateLock) {
                            ownsReconnect(deviceId, capture, reconnect) && !(state.connected && state.hrReady)
                        }) {
                        delay(500)
                        elapsed += 500
                    }
                    attempt += 1
                }
            } finally {
                synchronized(stateLock) {
                    if (reconnectGeneration == reconnect && captureGeneration == capture) reconnectJob = null
                }
            }
        }
        reconnectJob = next
        next.start()
    }

    fun stopRecording(): Unit = synchronized(stateLock) {
        if (closed) return@synchronized
        captureRequested = false
        captureGeneration += 1
        cancelReconnect()
        val deviceId = captureDeviceId ?: state.selectedDeviceId
        captureDeviceId = null
        sampleConsumer = null
        retireStream(stopDeviceId = deviceId)
        update {
            it.copy(
                recording = false,
                connecting = false,
                message = if (it.connected && it.hrReady) "Polar H10 已就绪（实时 HR/RR）" else it.message,
            )
        }
    }

    fun markCaptureRestored(deviceId: String): Unit = synchronized(stateLock) {
        if (closed) return@synchronized
        cancelPreparationTimeout()
        cancelReconnect()
        scanJob?.cancel()
        scanGeneration += 1
        retireStream(stopDeviceId = captureDeviceId)
        val previousDevice = state.selectedDeviceId
        captureGeneration += 1
        captureDeviceId = deviceId
        sampleConsumer = null
        captureRequested = true
        update {
            it.copy(
                selectedDeviceId = deviceId,
                connected = false,
                hrReady = false,
                recording = false,
                connecting = true,
                connectionFailed = false,
                message = "正在恢复 Polar H10 实时采集连接…",
                lastHeartRate = null,
            )
        }
        previousDevice?.takeIf { it != deviceId }?.let { runCatching { api.disconnectFromDevice(it) } }
        scheduleReconnect(deviceId)
    }

    private fun markHrReady(identifier: String): Unit = synchronized(stateLock) {
        if (!acceptDevice(identifier)) return@synchronized
        cancelPreparationTimeout()
        update {
            it.copy(
                connected = true,
                hrReady = true,
                connecting = false,
                connectionFailed = false,
                message = "Polar H10 已就绪（实时 HR/RR）",
            )
        }
        if (captureRequested) startLiveStream(identifier)
    }

    fun shutdown(): Unit = synchronized(stateLock) {
        if (closed) return@synchronized
        closed = true
        captureRequested = false
        captureGeneration += 1
        streamGeneration += 1
        scanGeneration += 1
        captureDeviceId = null
        sampleConsumer = null
        scanJob?.cancel()
        cancelPreparationTimeout()
        streamJob?.cancel()
        streamJob = null
        cancelReconnect()
        runCatching { state.selectedDeviceId?.let(api::disconnectFromDevice) }
        try { api.shutDown() } finally { scope.cancel() }
    }

    /** Call only while holding stateLock. Old device callbacks cannot adopt a new selection. */
    private fun acceptDevice(deviceId: String): Boolean = !closed && state.selectedDeviceId == deviceId &&
        (if (captureRequested) captureDeviceId == deviceId else
            !state.connectionFailed && !preparationGate.isDisconnecting(deviceId))

    /** Preparation waits for both BLE connection and HR readiness within the same deadline. */
    private fun startPreparationTimeout(deviceId: String): PolarPreparationGate.Attempt {
        val attempt = preparationGate.begin(deviceId)
        val next = scope.launch(start = CoroutineStart.LAZY) {
            delay(20_000)
            synchronized(stateLock) {
                if (!closed && !captureRequested && preparationGate.finish(attempt)) {
                    failPreparation(deviceId, "连接 H10 失败：等待心率服务超时")
                }
            }
        }
        preparationJob = next
        next.start()
        return attempt
    }

    private fun issuePreparationConnection(attempt: PolarPreparationGate.Attempt) {
        if (closed || captureRequested || state.selectedDeviceId != attempt.deviceId ||
            !preparationGate.isCurrent(attempt)) return
        if (preparationGate.needsClientReplacement(attempt.deviceId)) {
            schedulePreparationRebuild(attempt)
            return
        }
        if (!preparationGate.takeConnection(attempt)) return
        preparationRebuildJob?.cancel()
        preparationRebuildJob = null
        runCatching { api.connectToDevice(attempt.deviceId) }.onFailure {
            failPreparation(attempt.deviceId, "连接 H10 失败：请重试或重新搜索", disconnect = false)
        }
    }

    /** Some SDK disconnect paths are already closed and do not emit an acknowledgement. */
    private fun schedulePreparationRebuild(attempt: PolarPreparationGate.Attempt) {
        preparationRebuildJob?.cancel()
        val next = scope.launch(Dispatchers.Main, start = CoroutineStart.LAZY) {
            delay(1_000)
            synchronized(stateLock) {
                if (closed || captureRequested || !preparationGate.isCurrent(attempt) ||
                    !preparationGate.needsClientReplacement(attempt.deviceId)) return@synchronized
                try {
                    replacePreparationClient()
                    issuePreparationConnection(attempt)
                } catch (error: Exception) {
                    failPreparation(attempt.deviceId, "连接 H10 失败：请重试或重新搜索", disconnect = false)
                }
            }
        }
        preparationRebuildJob = next
        next.start()
    }

    /** Called on the main thread; old callbacks are fenced before shutting down the singleton. */
    private fun replacePreparationClient() {
        check(!captureRequested)
        preparationGate.replaceClient(
            shutdown = { api.shutDown() },
            create = { generation ->
                createApi().also { replacement ->
                    api = replacement
                    installApiCallbacks(replacement, generation)
                }
            },
        )
    }

    private fun cancelPreparationTimeout() {
        preparationGate.clear()
        preparationJob?.cancel()
        preparationJob = null
        preparationRebuildJob?.cancel()
        preparationRebuildJob = null
    }

    /** A terminal preparation failure leaves retry controls available despite late SDK callbacks. */
    private fun failPreparation(deviceId: String, message: String, disconnect: Boolean = true) {
        if (closed || captureRequested || state.selectedDeviceId != deviceId || state.connectionFailed) return
        cancelPreparationTimeout()
        val requestDisconnection = disconnect && preparationGate.beginDisconnection(deviceId)
        update {
            it.copy(
                connected = false,
                hrReady = false,
                connecting = false,
                connectionFailed = true,
                recording = false,
                lastHeartRate = null,
                message = message,
            )
        }
        if (requestDisconnection) runCatching { api.disconnectFromDevice(deviceId) }
    }

    private fun requestPreparationDisconnection(deviceId: String) {
        if (preparationGate.beginDisconnection(deviceId)) runCatching { api.disconnectFromDevice(deviceId) }
    }

    private fun ownsStream(deviceId: String, capture: Long, stream: Long): Boolean =
        captureRequested && captureGeneration == capture && streamGeneration == stream && acceptDevice(deviceId)

    private fun ownsReconnect(deviceId: String, capture: Long, reconnect: Long): Boolean =
        captureRequested && captureGeneration == capture && reconnectGeneration == reconnect && acceptDevice(deviceId)

    private fun cancelReconnect() {
        reconnectGeneration += 1
        reconnectJob?.cancel()
        reconnectJob = null
    }

    private fun retireStream(stopDeviceId: String? = null) {
        streamGeneration += 1
        val previous = streamJob
        streamJob = null
        previous?.cancel()
        val preceding = streamBarrier
        val retiringApi = api
        if (previous != null || stopDeviceId != null) {
            val cleanup = scope.launch(start = CoroutineStart.LAZY) {
                preceding?.join()
                previous?.join()
                if (stopDeviceId != null) runCatching { retiringApi.stopHrStreaming(stopDeviceId) }
            }
            streamBarrier = cleanup
            cleanup.start()
        }
    }

    private fun update(change: (PolarH10State) -> PolarH10State) {
        state = change(state)
        listener(state)
    }
}
