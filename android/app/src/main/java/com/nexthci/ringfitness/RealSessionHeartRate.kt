package com.nexthci.ringfitness

import android.content.Context
import com.polar.sdk.api.model.PolarHrData
import java.io.File

/** Bridges the original H10 client to the current serial, durable session owner. */
class RealSessionHeartRate internal constructor(
    private val store: FreeLivingSessionStore,
    private val recorder: SessionHeartRateRecorder,
    private val dispatch: (() -> Unit) -> Unit,
    private val onMain: (() -> Unit) -> Unit,
    private val changed: () -> Unit,
    private val reportError: (Exception) -> Unit,
    private val clientFactory: ((PolarH10State) -> Unit) -> SessionHeartRateClient,
) : SessionHeartRatePort {
    constructor(
        context: Context,
        directory: File,
        store: FreeLivingSessionStore,
        clock: CaptureClock,
        dispatch: (() -> Unit) -> Unit,
        onMain: (() -> Unit) -> Unit,
        changed: () -> Unit,
        reportError: (Exception) -> Unit,
    ) : this(store, SessionHeartRateRecorder(directory, store, clock), dispatch, onMain, changed,
        reportError, { listener -> PolarSessionHeartRateClient(PolarH10Client(context, listener)) })

    private var client: SessionHeartRateClient? = null
    private var captureToken = 0L
    private var closed = false
    private var storageError: String? = null
    @Volatile private var pendingStop = false
    override var state = PolarUiState(message = "可选连接 Polar H10")
        private set

    private fun useClient(action: (SessionHeartRateClient) -> Unit) {
        check(!closed)
        onMain {
            val active = client ?: clientFactory { next ->
                val intentionalStop = pendingStop
                dispatch {
                    if (!closed) {
                        val before = state
                        state = PolarUiState(
                            devices = next.devices.map { PolarUiDevice(it.deviceId, it.name, it.rssi) },
                            selectedDeviceId = next.selectedDeviceId, connected = next.connected,
                            hrReady = next.hrReady, connecting = next.connecting, scanning = next.scanning,
                            recording = next.recording && storageError == null,
                            lastHeartRate = next.lastHeartRate, message = storageError ?: next.message,
                            storageError = storageError,
                        )
                        if (!intentionalStop && recorder.activeSessionId != null &&
                            ((before.connected && !next.connected) || (before.recording && !next.recording))) {
                            runCatching { recorder.gap(if (!next.connected) "disconnected" else "stream_error") }
                                .onFailure { reportError(it as? Exception ?: Exception(it)) }
                        }
                        changed()
                    }
                }
            }.also { client = it }
            if (pendingStop) {
                active.stopRecording()
                pendingStop = false
            }
            action(active)
        }
    }

    override fun resetSelection() {
        if (recorder.activeSessionId == null) clearStorageError()
        useClient { it.beginFreshSelection() }
    }
    override fun search() = useClient { it.search() }
    override fun connect(deviceId: String) = useClient { it.connect(deviceId) }

    override fun prepare(session: FreeLivingSession): Boolean {
        val configured = session.heartRate ?: return true
        if (recorder.activeSessionId == session.sessionId) return true
        if (!state.ready || state.selectedDeviceId != configured.deviceId) return false
        recorder.open(session, recovering = false)
        clearStorageError()
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
        clearStorageError()
        val token = ++captureToken
        useClient {
            it.markCaptureRestored(configured.deviceId)
            it.resumeLiveRecording(consumer(session.sessionId, token))
        }
    }

    private fun consumer(sessionId: String, token: Long): (HeartRateSample, Long) -> Unit = { sample, at ->
        val value = sample.copy(rrMs = sample.rrMs.toList(), rrRaw = sample.rrRaw.toList())
        dispatch {
            if (!closed && token == captureToken && recorder.activeSessionId == sessionId) {
                runCatching { recorder.write(value, at) }.onFailure {
                    reportError(it as? Exception ?: Exception(it))
                    runCatching { recorder.gap("storage_error") }
                    // A BLE notification confirms reception, not durable storage. Keep the
                    // session's storage warning until an explicit new capture or recovery.
                    storageError = "心率数据保存中断，戒指采集继续；请检查手机空间"
                    state = state.copy(recording = false, message = storageError!!, storageError = storageError)
                    changed()
                }
            }
        }
    }

    override fun finish(session: FreeLivingSession) {
        if (session.heartRate == null) return
        ++captureToken
        withCleanup({ stopClient() }) { recorder.finish(session) }
    }

    override fun discard(session: FreeLivingSession) {
        ++captureToken
        withCleanup({ stopClient() }) { recorder.discard(session) }
    }

    override fun close() {
        if (closed) return
        closed = true
        ++captureToken
        withCleanup({ recorder.close() }) {
            val retiring = client
            client = null
            retiring?.let { onMain { it.shutdown() } }
        }
    }

    private fun clearStorageError() {
        storageError = null
        state = state.copy(storageError = null)
    }

    private fun stopClient() {
        val active = client ?: return
        pendingStop = true
        onMain {
            active.stopRecording()
            pendingStop = false
        }
    }

    /** Release both owners while preserving the original error if each operation fails. */
    private fun withCleanup(action: () -> Unit, cleanup: () -> Unit) {
        var failure: Throwable? = null
        try { action() } catch (error: Throwable) { failure = error }
        try { cleanup() } catch (error: Throwable) {
            val first = failure
            if (first == null) failure = error else if (first !== error) first.addSuppressed(error)
        }
        failure?.let { throw it }
    }
}

/** The bridge only needs these operations; the adapter keeps SDK ownership in PolarH10Client. */
internal interface SessionHeartRateClient {
    fun beginFreshSelection()
    fun search()
    fun connect(deviceId: String)
    fun startRecording(consumer: (HeartRateSample, Long) -> Unit): Boolean
    fun markCaptureRestored(deviceId: String)
    fun resumeLiveRecording(consumer: (HeartRateSample, Long) -> Unit): Boolean
    fun stopRecording()
    fun shutdown()
}

private class PolarSessionHeartRateClient(private val client: PolarH10Client) : SessionHeartRateClient {
    override fun beginFreshSelection() = client.beginFreshSelection()
    override fun search() = client.search()
    override fun connect(deviceId: String) = client.connect(deviceId)
    override fun startRecording(consumer: (HeartRateSample, Long) -> Unit) =
        client.startRecording(adapt(consumer))
    override fun markCaptureRestored(deviceId: String) = client.markCaptureRestored(deviceId)
    override fun resumeLiveRecording(consumer: (HeartRateSample, Long) -> Unit) =
        client.resumeLiveRecording(adapt(consumer))
    override fun stopRecording() = client.stopRecording()
    override fun shutdown() = client.shutdown()

    private fun adapt(consumer: (HeartRateSample, Long) -> Unit): (PolarHrData.PolarHrSample, Long) -> Unit =
        { sample, at -> consumer(HeartRateSample(sample.hr, sample.correctedHr, sample.ppgQuality,
            sample.rrAvailable, sample.contactStatusSupported, sample.contactStatus, sample.rrsMs, sample.rrs), at) }
}
