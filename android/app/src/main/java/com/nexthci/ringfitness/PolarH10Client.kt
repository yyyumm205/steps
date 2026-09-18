package com.nexthci.ringfitness

import android.content.Context
import com.polar.androidcommunications.api.ble.model.DisInfo
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApiCallback
import com.polar.sdk.api.PolarBleApiDefaultImpl
import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.sdk.api.model.PolarHealthThermometerData
import com.polar.sdk.api.model.PolarHrData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
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
    private val api = PolarBleApiDefaultImpl.defaultImplementation(
        context.applicationContext,
        setOf(
            PolarBleApi.PolarBleSdkFeature.FEATURE_HR,
            PolarBleApi.PolarBleSdkFeature.FEATURE_DEVICE_INFO,
            PolarBleApi.PolarBleSdkFeature.FEATURE_BATTERY_INFO,
        ),
    )
    private var state = PolarH10State()
    private var scanJob: Job? = null
    private var scanGeneration = 0L
    private var streamJob: Job? = null
    private var reconnectJob: Job? = null
    private var captureRequested = false
    private var sampleConsumer: ((PolarHrData.PolarHrSample, Long) -> Unit)? = null

    init {
        api.setAutomaticReconnection(true)
        api.setApiCallback(object : PolarBleApiCallback() {
            override fun deviceConnecting(polarDeviceInfo: PolarDeviceInfo) {
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

            override fun deviceConnected(polarDeviceInfo: PolarDeviceInfo) {
                update {
                    it.copy(
                        devices = listOf(polarDeviceInfo),
                        selectedDeviceId = polarDeviceInfo.deviceId,
                        connected = true,
                        connecting = true,
                        connectionFailed = false,
                        message = "${polarDeviceInfo.name} 已连接，等待实时 HR/RR 服务",
                    )
                }
            }

            override fun deviceDisconnected(polarDeviceInfo: PolarDeviceInfo) {
                streamJob?.cancel()
                streamJob = null
                update {
                    it.copy(
                        connected = false,
                        hrReady = false,
                        connecting = captureRequested,
                        connectionFailed = !captureRequested,
                        recording = false,
                        message = if (captureRequested) {
                            "Polar H10 已断开，正在自动重连；断连区间保持为空"
                        } else {
                            "Polar H10 已断开"
                        },
                    )
                }
                if (captureRequested) scheduleReconnect(polarDeviceInfo.deviceId)
            }

            override fun bleSdkFeatureReady(identifier: String, feature: PolarBleApi.PolarBleSdkFeature) {
                if (feature == PolarBleApi.PolarBleSdkFeature.FEATURE_HR) markHrReady(identifier)
            }

            override fun bleSdkFeaturesReadiness(
                identifier: String,
                ready: List<PolarBleApi.PolarBleSdkFeature>,
                unavailable: List<PolarBleApi.PolarBleSdkFeature>,
            ) {
                if (ready.contains(PolarBleApi.PolarBleSdkFeature.FEATURE_HR)) markHrReady(identifier)
            }

            override fun disInformationReceived(identifier: String, disInfo: DisInfo) = Unit

            override fun htsNotificationReceived(identifier: String, data: PolarHealthThermometerData) = Unit
        })
    }

    fun snapshot(): PolarH10State = state

    fun beginFreshSelection() {
        if (captureRequested) return
        scanJob?.cancel()
        scanJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        scanGeneration += 1
        state.selectedDeviceId?.let { runCatching { api.disconnectFromDevice(it) } }
        update {
            PolarH10State(message = "")
        }
    }

    fun search() {
        if (captureRequested) return
        scanJob?.cancel()
        reconnectJob?.cancel()
        reconnectJob = null
        scanGeneration += 1
        val generation = scanGeneration
        state.selectedDeviceId?.let { runCatching { api.disconnectFromDevice(it) } }
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
            )
        }
        scanJob = scope.launch {
            delay(300)
            if (generation != scanGeneration) return@launch
            val found = linkedMapOf<String, PolarDeviceInfo>()
            val collector = launch {
                api.searchForDevice("Polar H10")
                    .catch { error ->
                        if (generation == scanGeneration) {
                            update { it.copy(message = "搜索 H10 失败：${error.message}") }
                        }
                    }
                    .collect { device ->
                        if (generation == scanGeneration &&
                            (device.hasHeartRateService || device.name.contains("H10", true))) {
                            found[device.deviceId] = device
                            update { it.copy(devices = found.values.sortedByDescending(PolarDeviceInfo::rssi)) }
                        }
                    }
            }
            delay(10_000)
            collector.cancel()
            if (generation == scanGeneration) {
                update {
                    it.copy(
                        scanning = false,
                        message = if (found.isEmpty()) "没有发现 Polar H10" else "请选择 Polar H10",
                    )
                }
            }
        }
    }

    fun connect(deviceId: String) {
        if (captureRequested) return
        scanJob?.cancel()
        reconnectJob?.cancel()
        reconnectJob = null
        scanGeneration += 1
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
            )
        }
        runCatching { api.connectToDevice(deviceId) }
            .onFailure { error ->
                update {
                    it.copy(
                        connecting = false,
                        connectionFailed = true,
                        message = "连接 H10 失败：${error.message}",
                    )
                }
            }
    }

    fun startRecording(consumer: (PolarHrData.PolarHrSample, Long) -> Unit): Boolean {
        val deviceId = state.selectedDeviceId ?: return false
        if (!state.connected || !state.hrReady || streamJob?.isActive == true) return false
        sampleConsumer = consumer
        captureRequested = true
        startLiveStream(deviceId)
        return true
    }

    fun resumeLiveRecording(consumer: (PolarHrData.PolarHrSample, Long) -> Unit): Boolean {
        val deviceId = state.selectedDeviceId ?: return false
        sampleConsumer = consumer
        captureRequested = true
        return if (state.connected && state.hrReady) {
            startLiveStream(deviceId)
            true
        } else {
            scheduleReconnect(deviceId)
            false
        }
    }

    private fun startLiveStream(deviceId: String) {
        if (!captureRequested || !state.connected || !state.hrReady || streamJob?.isActive == true) return
        reconnectJob?.cancel()
        reconnectJob = null
        streamJob = scope.launch {
            update { it.copy(recording = true, message = "Polar H10 正在实时采集 HR/RR") }
            try {
                api.startHrStreaming(deviceId)
                    .catch { error ->
                        update {
                            it.copy(
                                recording = false,
                                message = "H10 实时流中断，正在自动重连；断连区间保持为空：${error.message}",
                            )
                        }
                    }
                    .collect { data ->
                        val now = System.currentTimeMillis()
                        data.samples.forEach { sample ->
                            sampleConsumer?.invoke(sample, now)
                            update { it.copy(lastHeartRate = sample.hr) }
                        }
                    }
            } finally {
                streamJob = null
                update { it.copy(recording = false) }
                if (captureRequested) scheduleReconnect(deviceId)
            }
        }
    }

    private fun scheduleReconnect(deviceId: String) {
        if (!captureRequested || reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            var attempt = 0
            while (captureRequested && state.selectedDeviceId == deviceId) {
                if (state.connected && state.hrReady) {
                    startLiveStream(deviceId)
                    break
                }
                update {
                    it.copy(message = "Polar H10 已断开，正在自动重连（第 ${attempt + 1} 次）…")
                }
                runCatching { api.connectToDevice(deviceId) }
                val waitMs = minOf(30_000L, 2_000L shl minOf(attempt, 3))
                var elapsed = 0L
                while (captureRequested && elapsed < waitMs && !(state.connected && state.hrReady)) {
                    delay(500)
                    elapsed += 500
                }
                if (state.connected && state.hrReady) {
                    startLiveStream(deviceId)
                    break
                }
                attempt += 1
            }
            reconnectJob = null
        }
    }

    fun stopRecording() {
        captureRequested = false
        reconnectJob?.cancel()
        reconnectJob = null
        val deviceId = state.selectedDeviceId
        streamJob?.cancel()
        streamJob = null
        sampleConsumer = null
        if (deviceId != null) scope.launch { runCatching { api.stopHrStreaming(deviceId) } }
        update {
            it.copy(
                recording = false,
                connecting = false,
                message = if (it.connected && it.hrReady) "Polar H10 已就绪（实时 HR/RR）" else it.message,
            )
        }
    }

    fun markCaptureRestored(deviceId: String) {
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
            )
        }
        scheduleReconnect(deviceId)
    }

    private fun markHrReady(identifier: String) {
        if (state.selectedDeviceId != identifier) return
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

    fun shutdown() {
        captureRequested = false
        scanJob?.cancel()
        streamJob?.cancel()
        reconnectJob?.cancel()
        runCatching { state.selectedDeviceId?.let(api::disconnectFromDevice) }
        api.shutDown()
        scope.cancel()
    }

    private fun update(change: (PolarH10State) -> PolarH10State) {
        state = change(state)
        listener(state)
    }
}
