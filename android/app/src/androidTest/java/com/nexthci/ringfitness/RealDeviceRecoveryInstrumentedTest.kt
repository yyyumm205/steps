package com.nexthci.ringfitness

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Explicit research repair of a verified START observation; never sends START or erases data. */
@RunWith(AndroidJUnit4::class)
class RealDeviceRecoveryInstrumentedTest {
    data class Authorization(val sessionId: String, val firstObservation: HealthRecordObservation)

    @Test fun stopOnlyTheExplicitlyVerifiedPendingRecord() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Requires a locally reviewed observation and explicit device opt-in",
            InstrumentationRegistry.getArguments().getString("verifyRealDeviceRecovery") == "true")
        val context = instrumentation.targetContext
        check(BuildConfig.DEBUG && !RealCollectionBridge.isRunning())
        val directory = File(context.filesDir, "collection-real")
        val authorization = Gson().fromJson(File(directory, "research-recovery.json").readText(), Authorization::class.java)
        val store = FreeLivingSessionStore(File(directory, "session.json"))
        val pending = requireNotNull(store.readPending())
        val first = authorization.firstObservation
        val firstRecord = first.records.single()
        require(authorization.sessionId == pending.sessionId && pending.phase == FreeLivingSessionPhase.START_REQUESTED)
        require(first.address == pending.preparation.ring?.address && first.status.collecting && first.status.errorCode == 0)
        require(first.statusReceivedAtMs >= pending.startRequestedAtMs && firstRecord.sessionId == first.status.sessionId)
        require(firstRecord.unixMs > 0 && firstRecord.uptimeMs > 0)
        require(requireNotNull(pending.startBaseline).records.none { FreeLivingSessionStore.sameDeviceRecord(it, firstRecord) })
        val events = LinkedBlockingQueue<Any>()
        lateinit var client: RingBleClient
        instrumentation.runOnMainSync {
            client = RingBleClient(context, object : RingBleClient.Listener {
                override fun onBleState(message: String, ready: Boolean) { if (ready) events.offer("ready") }
                override fun onBleError(message: String) { events.offer(IllegalStateException(message)) }
                override fun onSensorPacket(packet: SensorPacket) { if (packet is SensorPacket.Health) events.offer(packet) }
                override fun onRingsFound(rings: List<ScannedRing>) = Unit
            })
            check(client.connectKnownAddress(first.address, pending.preparation.ring!!.name))
        }
        fun next(): Any {
            val result = checkNotNull(events.poll(30, TimeUnit.SECONDS)) { "Device response timed out; retain all records" }
            if (result is Exception) throw result
            return result
        }
        fun inspect(): HealthRecordObservation {
            events.clear()
            instrumentation.runOnMainSync { check(client.requestHealthStatus()) }
            var status: SensorPacket.Health? = null
            val records = mutableListOf<HealthMessage.ListItem>()
            while (true) {
                val packet = next() as? SensorPacket.Health ?: continue
                when (val message = packet.message) {
                    is HealthMessage.Status -> if (status == null) {
                        status = packet
                        instrumentation.runOnMainSync { check(client.requestHealthSessions()) }
                    }
                    is HealthMessage.ListItem -> if (status != null) records += message
                    is HealthMessage.ListEnd -> if (status != null) {
                        check(message.count == records.size)
                        return HealthRecordObservation(first.address, 1, status.message as HealthMessage.Status,
                            status.receivedEpochMs, records)
                    }
                    else -> Unit
                }
            }
        }
        try {
            while (next() != "ready") { /* Await the control channel. */ }
            val observed = inspect()
            val record = observed.records.single()
            require(observed.status.collecting && observed.status.errorCode == 0 &&
                observed.status.sessionId == firstRecord.sessionId && FreeLivingSessionStore.sameDeviceRecord(record, firstRecord))
            require(record.bytes >= firstRecord.bytes && record.records >= firstRecord.records)
            require(observed.status.bytes >= first.status.bytes && observed.status.records >= first.status.records &&
                record.bytes >= observed.status.bytes && record.records >= observed.status.records)
            // Preserve the actual earlier observation; wall-clock boundaries remain unknown.
            store.confirmStart(pending.sessionId, first.address, first.status, first.statusReceivedAtMs,
                recordEvidence = DeviceRecordEvidence(firstRecord, first.status, first.statusReceivedAtMs))
            store.requestStop(pending.sessionId, System.currentTimeMillis())
            instrumentation.runOnMainSync { check(client.stopHealth()) }
            var stopped: HealthRecordObservation? = null
            repeat(10) {
                if (stopped == null) {
                    SystemClock.sleep(1000)
                    val result = inspect()
                    val candidate = result.records.single()
                    require(FreeLivingSessionStore.sameDeviceRecord(candidate, firstRecord))
                    if (!result.status.collecting && result.status.errorCode == 0 &&
                        result.status.sessionId == candidate.sessionId && result.status.bytes == candidate.bytes &&
                        result.status.records == candidate.records) stopped = result
                }
            }
            val final = checkNotNull(stopped) { "Stop finalization still pending; preserve the journal and ring" }
            require(final.status.bytes >= record.bytes && final.status.records >= record.records)
            val saved = store.confirmStop(pending.sessionId, final.address, final.status, final.statusReceivedAtMs,
                recordEvidence = DeviceRecordEvidence(final.records.single(), final.status, final.statusReceivedAtMs))
            assertEquals(FreeLivingSessionPhase.AWAITING_REFERENCE, saved.phase)
            assertNull(saved.startedAtMs)
            assertNull(saved.endedAtMs)
            assertNull(saved.reference)
        } finally {
            instrumentation.runOnMainSync { client.stop() }
        }
    }
}
