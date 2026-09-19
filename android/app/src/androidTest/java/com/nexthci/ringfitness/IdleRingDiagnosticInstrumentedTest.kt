package com.nexthci.ringfitness

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Opt-in observations of one backed-up idle ring. No collection or download commands. */
@RunWith(AndroidJUnit4::class)
class IdleRingDiagnosticInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val events = LinkedBlockingQueue<Any>()
    private data class Ready(val elapsedMs: Long)

    @Test fun compareSettlingAndListWithStatusOnlyControls() {
        assumeTrue("Requires a reviewed idle device and retained original files",
            InstrumentationRegistry.getArguments().getString("verifyIdleRingDiagnostic") == "true")
        check(BuildConfig.DEBUG && !RealCollectionBridge.isRunning())
        val root = instrumentation.targetContext.filesDir
        val filesBefore = protectedHashes(root)
        val journal = File(root, "collection-real/session.json")
        check(journal.isFile) { "A verified retained journal is required; do not reconstruct it from device counters" }
        val store = FreeLivingSessionStore(journal)
        check(store.readPending() == null) { "A pending capture must be recovered first" }
        val ring = requireNotNull(PreparationStore(File(root, "preparation/profile.properties")).read()?.ring)
        val baseline = Gson().fromJson(File(root, "collection-real/device-observation.json").readText(),
            HealthRecordObservation::class.java)
        require(baseline.address == ring.address && !baseline.status.collecting)
        val saved = store.listSessions().filter { it.localData != null }
        check(saved.isNotEmpty()) { "At least one retained raw record must be verified before diagnostics" }
        saved.forEach { store.manifestSnapshot(it.sessionId) } // Rechecks the retained raw file checksums.
        require(baseline.records.all { record -> saved.any { session ->
            session.preparation.ring?.address == ring.address && session.deviceRecordEvidence?.record == record
        } }) { "Every ring record must match a verified local copy" }
        try {
            for (delay in listOf(250L, 1_000L, 5_000L)) {
                for (readList in listOf(false, true)) {
                    runCase(ring, baseline, delay, readList)
                    assertEquals("Protected files changed", filesBefore, protectedHashes(root))
                }
            }
        } finally {
            assertEquals("Protected files changed", filesBefore, protectedHashes(root))
        }
    }

    private fun runCase(ring: PreparedRing, baseline: HealthRecordObservation, delay: Long, readList: Boolean) {
        events.clear()
        var client: RingBleClient? = null
        var connected = false
        val caseName = "delay_${delay}_${if (readList) "with_list" else "status_only"}"
        emit(caseName, "begin", mapOf("delay_ms" to delay, "with_list" to readList))
        fun validate(status: HealthMessage.Status) {
            require(!status.collecting && status.copy(errorCode = 0) == baseline.status.copy(errorCode = 0)) {
                "Device state changed; all remaining observations cancelled"
            }
        }
        fun status(): HealthMessage.Status {
            instrumentation.runOnMainSync { check(requireNotNull(client).requestHealthStatus()) }
            val result = (next() as? SensorPacket.Health)?.message as? HealthMessage.Status
                ?: error("Unexpected reply; reconnect before further diagnostics")
            emit(caseName, "status", mapOf("error" to result.errorCode, "collecting" to result.collecting,
                "bytes" to result.bytes, "records" to result.records, "device_session_id" to result.sessionId))
            validate(result)
            check(result.errorCode == baseline.status.errorCode) {
                "Error field changed; inspect evidence before sending further queries"
            }
            return result
        }
        try {
            instrumentation.runOnMainSync {
                client = RingBleClient(instrumentation.targetContext, object : RingBleClient.Listener {
                    override fun onBleState(message: String, ready: Boolean) {
                        if (ready) { connected = true; events.offer(Ready(SystemClock.elapsedRealtime())) }
                        else if (connected) { events.offer(IllegalStateException("Connection lost; no automatic retry")) }
                    }
                    override fun onBleError(message: String) { events.offer(IllegalStateException(message)) }
                    override fun onSensorPacket(packet: SensorPacket) { if (packet is SensorPacket.Health) events.offer(packet) }
                    override fun onRingsFound(rings: List<ScannedRing>) = Unit
                })
                requireNotNull(client).setAutoReconnect(false)
                check(requireNotNull(client).connectKnownAddress(ring.address, ring.name))
            }
            val ready = next() as? Ready ?: error("Unexpected event before readiness")
            waitUntil(ready.elapsedMs + delay)
            val first = status()
            // Equal observation deadline in the control and LIST cases avoids changing wait and command together.
            val finalQueryAt = SystemClock.elapsedRealtime() + 2_000L
            if (readList) {
                instrumentation.runOnMainSync { check(requireNotNull(client).requestHealthSessions()) }
                val records = mutableListOf<HealthMessage.ListItem>()
                val listDeadline = SystemClock.elapsedRealtime() + 1_500L
                while (true) {
                    val message = (next(listDeadline) as? SensorPacket.Health)?.message
                    when (message) {
                        is HealthMessage.ListItem -> {
                            require(records.size < 255 && records.none { it.sessionId == message.sessionId })
                            records += message
                        }
                        is HealthMessage.ListEnd -> {
                            require(message.count == records.size && records.size == baseline.records.size &&
                                records.toSet() == baseline.records.toSet()) { "Record fingerprint changed; stop diagnostics" }
                            emit(caseName, "list_verified", mapOf("count" to records.size))
                            break
                        }
                        else -> error("Unexpected LIST response; stop diagnostics")
                    }
                }
            }
            waitUntil(finalQueryAt)
            val last = status()
            emit(caseName, "complete", mapOf("first_error" to first.errorCode, "last_error" to last.errorCode))
            check(first.errorCode == baseline.status.errorCode && last.errorCode == baseline.status.errorCode) {
                "Error field changed; inspect evidence before continuing"
            }
        } finally {
            instrumentation.runOnMainSync { client?.stop() }
        }
    }

    private fun next(deadline: Long = SystemClock.elapsedRealtime() + 20_000L): Any {
        val remaining = deadline - SystemClock.elapsedRealtime()
        check(remaining > 0) { "Observation deadline elapsed" }
        val result = checkNotNull(events.poll(remaining, TimeUnit.MILLISECONDS)) { "Device response timed out" }
        if (result is Exception) throw result
        return result
    }

    private fun waitUntil(deadline: Long) {
        val remaining = deadline - SystemClock.elapsedRealtime()
        if (remaining > 0) SystemClock.sleep(remaining)
    }

    private fun emit(caseName: String, event: String, fields: Map<String, Any>) {
        val value = mapOf("case" to caseName, "event" to event, "elapsed_ms" to SystemClock.elapsedRealtime()) + fields
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", Gson().toJson(value) + "\n") })
    }

    private fun protectedHashes(root: File): Map<String, String> =
        listOf("collection-real", "preparation", "collection-demo").flatMap { name ->
            File(root, name).walkTopDown().filter { it.isFile }.map { file ->
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { stream ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
                }
                file.relativeTo(root).path to digest.digest().joinToString("") { "%02x".format(it) }
            }.toList()
        }.toMap()
}
