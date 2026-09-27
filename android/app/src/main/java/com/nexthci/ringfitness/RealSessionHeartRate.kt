package com.nexthci.ringfitness

import android.content.Context
import com.polar.sdk.api.model.PolarHrData
import java.io.File

/** Bridges the original H10 client to the current serial, durable session owner. */
class RealSessionHeartRate(
    private val context: Context,
    directory: File,
    private val store: FreeLivingSessionStore,
    private val clock: CaptureClock,
    private val dispatch: (() -> Unit) -> Unit,
    private val onMain: (() -> Unit) -> Unit,
    private val changed: () -> Unit,
    private val reportError: (Exception) -> Unit,
) : SessionHeartRatePort {
    private val recorder = SessionHeartRateRecorder(directory, store, clock)
    private var client: PolarH10Client? = null
    private var captureToken = 0L
    private var closed = false
    override var state = PolarUiState(message = "可选连接 Polar H10")
        private set

    private fun useClient(action: (PolarH10Client) -> Unit) {
        check(!closed)
        onMain {
            val active = client ?: PolarH10Client(context) { next ->
                dispatch {
                    if (!closed) {
                        val before = state
                        state = PolarUiState(
                            devices = next.devices.map { PolarUiDevice(it.deviceId, it.name, it.rssi) },
                            selectedDeviceId = next.selectedDeviceId, connected = next.connected,
                            hrReady = next.hrReady, connecting = next.connecting, scanning = next.scanning,
                            recording = next.recording, lastHeartRate = next.lastHeartRate, message = next.message,
                        )
                        if (recorder.activeSessionId != null &&
                            ((before.connected && !next.connected) || (before.recording && !next.recording))) {
                            runCatching { recorder.gap(if (!next.connected) "disconnected" else "stream_error") }
                                .onFailure { reportError(it as? Exception ?: Exception(it)) }
                        }
                        changed()
                    }
                }
            }.also { client = it }
            action(active)
        }
    }

    override fun resetSelection() = useClient { it.beginFreshSelection() }
    override fun search() = useClient { it.search() }
    override fun connect(deviceId: String) = useClient { it.connect(deviceId) }

    override fun prepare(session: FreeLivingSession): Boolean {
        val configured = session.heartRate ?: return true
        if (recorder.activeSessionId == session.sessionId) return true
        if (!state.ready || state.selectedDeviceId != configured.deviceId) return false
        recorder.open(session, recovering = false)
        val token = ++captureToken
        var accepted = false
        useClient { accepted = it.startRecording(consumer(session.sessionId, token)) }
        if (!accepted) {
            recorder.gap("stream_error")
            recorder.finish(requireNotNull(store.read(session.sessionId)))
        }
        return accepted
    }

    override fun restore(session: FreeLivingSession) {
        val configured = session.heartRate ?: return
        if (configured.endedAtMs != null) return
        recorder.open(session, recovering = true)
        val token = ++captureToken
        useClient {
            it.markCaptureRestored(configured.deviceId)
            it.resumeLiveRecording(consumer(session.sessionId, token))
        }
    }

    private fun consumer(sessionId: String, token: Long): (PolarHrData.PolarHrSample, Long) -> Unit = { sample, at ->
        val value = HeartRateSample(sample.hr, sample.correctedHr, sample.ppgQuality, sample.rrAvailable,
            sample.contactStatusSupported, sample.contactStatus, sample.rrsMs.toList(), sample.rrs.toList())
        dispatch {
            if (!closed && token == captureToken && recorder.activeSessionId == sessionId) {
                runCatching { recorder.write(value, at) }.onFailure {
                    reportError(it as? Exception ?: Exception(it))
                    runCatching { recorder.gap("storage_error") }
                    state = state.copy(message = "心率数据保存中断，戒指采集继续；请检查手机空间")
                    changed()
                }
            }
        }
    }

    override fun finish(session: FreeLivingSession) {
        if (session.heartRate == null) return
        ++captureToken
        client?.let { onMain { it.stopRecording() } }
        recorder.finish(session)
    }

    override fun discard(session: FreeLivingSession) {
        ++captureToken
        client?.let { onMain { it.stopRecording() } }
        recorder.discard(session)
    }

    override fun close() {
        if (closed) return
        closed = true
        ++captureToken
        try { recorder.close() } finally {
            client?.let { onMain { it.shutdown() } }
            client = null
        }
    }
}
