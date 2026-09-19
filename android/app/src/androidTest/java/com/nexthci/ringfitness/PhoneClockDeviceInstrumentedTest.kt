package com.nexthci.ringfitness

import android.app.ActivityManager
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
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Explicitly enabled clock experiment on a fully preserved idle ring. Never collects or downloads. */
@RunWith(AndroidJUnit4::class)
class PhoneClockDeviceInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val events = LinkedBlockingQueue<Any>()
    private data class Ready(val elapsedMs: Long)
    private data class Received(val packet: SensorPacket, val elapsedMs: Long)
    private data class Request(val epochMs: Long, val elapsedMs: Long)

    @Test fun oneClockSetPreservesIdleRecordsAndMatchesASubsequentPhoneWindow() {
        assumeTrue("Requires explicit opt-in and verified local originals",
            InstrumentationRegistry.getArguments().getString("verifyPhoneClock") == "true")
        check(BuildConfig.DEBUG) { "Clock diagnostics require a development build" }
        val context = instrumentation.targetContext
        val root = context.filesDir
        val beforeHashes = protectedHashes(root)
        val journal = File(root, "collection-real/session.json")
        check(journal.isFile) { "A retained collection journal is required before clock diagnostics" }
        val store = FreeLivingSessionStore(journal)
        val ring = requireNotNull(PreparationStore(File(root, "preparation/profile.properties")).read()?.ring) {
            "Register and select the test ring before clock diagnostics"
        }
        var client: RingBleClient? = null
        val ready = AtomicBoolean(false)
        val closing = AtomicBoolean(false)
        var run: File? = null
        var setCount = 0

        fun idleOwner() {
            check(!RealCollectionBridge.isRunning()) { "Close the real collection owner before clock diagnostics" }
            @Suppress("DEPRECATION")
            check(context.getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE)
                .none { it.service.className in setOf(RealCollectionService::class.java.name, RingCaptureService::class.java.name) }) {
                "A collection service is still running; clock diagnostics cannot share its ring connection"
            }
            check(store.readPending() == null) { "Recover the pending capture before clock diagnostics" }
        }

        fun verifySavedFiles() {
            idleOwner()
            store.listSessions().filter { it.localData != null }.forEach { session ->
                store.manifestSnapshot(session.sessionId)
                val record = requireNotNull(session.deviceRecordEvidence) { "Retained raw files need device identity evidence" }.record
                requireNotNull(session.localData).files.forEach { file ->
                    check(!file.simulated) { "A simulated file cannot protect a real device record" }
                    RealSessionDownload.verifyPreservedContainer(File(journal.parentFile, file.fileName), record,
                        session.startedAtMs ?: 0L, session.endedAtMs ?: 0L)
                }
            }
            assertEquals("Protected app files changed", beforeHashes, protectedHashes(root))
        }

        fun command(name: String, action: (RingBleClient) -> Boolean): Request {
            check(ready.get()) { "The original connection was lost; no retry or clock SET will be sent" }
            emit("command", mapOf("name" to name))
            var request: Request? = null
            instrumentation.runOnMainSync {
                check(ready.get()) { "The original connection was lost" }
                request = Request(System.currentTimeMillis(), SystemClock.elapsedRealtime())
                check(action(requireNotNull(client))) { "$name was not accepted; no automatic retry" }
            }
            return requireNotNull(request)
        }

        fun observation(): HealthRecordObservation {
            val request = command("HEALTH_STATUS_GET") { it.requestHealthStatus() }
            val received = next(request.elapsedMs + 10_000) as? Received
                ?: error("Unexpected event while awaiting HEALTH STATUS")
            val status = (received.packet as? SensorPacket.Health)?.message as? HealthMessage.Status
                ?: error("Unexpected reply while awaiting HEALTH STATUS")
            require(received.packet.receivedEpochMs > 0 && !status.collecting && status.errorCode == 0 && status.sessionId in 0..65535 &&
                status.bytes in 0..0xFFFF_FFFFL && status.records in 0..0xFFFF_FFFFL) {
                "The ring must be idle and error-free before or after clock SET"
            }
            val listRequest = command("HEALTH_LIST") { it.requestHealthSessions() }
            val records = mutableListOf<HealthMessage.ListItem>()
            val deadline = listRequest.elapsedMs + 10_000
            while (true) {
                val item = next(deadline) as? Received ?: error("Unexpected event during HEALTH LIST")
                when (val message = (item.packet as? SensorPacket.Health)?.message) {
                    is HealthMessage.ListItem -> {
                        require(message.sessionId in 1..65535 && message.bytes in 0..0xFFFF_FFFFL &&
                            message.records in 0..0xFFFF_FFFFL && message.uptimeMs in 0..0xFFFF_FFFFL && message.unixMs >= 0)
                        require(records.size < 255 && records.none { it.sessionId == message.sessionId }) {
                            "Duplicate or excessive device records; cancel clock diagnostics"
                        }
                        records += message
                    }
                    is HealthMessage.ListEnd -> {
                        require(message.count == records.size) { "HEALTH LIST is incomplete" }
                        require(if (records.isEmpty()) status.bytes == 0L && status.records == 0L
                            else records.any { it.sessionId == status.sessionId && it.bytes == status.bytes && it.records == status.records }) {
                            "HEALTH STATUS and LIST disagree; cancel clock diagnostics"
                        }
                        return HealthRecordObservation(ring.address, 1, status, received.packet.receivedEpochMs, records)
                    }
                    else -> error("Unexpected HEALTH LIST response; cancel clock diagnostics")
                }
            }
        }

        fun readTime(): Pair<Request, Received> {
            val request = command("TIME_GET") { it.requestTime() }
            val received = next(request.elapsedMs + PhoneClockSync.TIMEOUT_MS) as? Received
                ?: error("Unexpected event while awaiting TIME STATUS")
            check(received.packet is SensorPacket.TimeStatus) { "Expected TIME STATUS; received another packet" }
            return request to received
        }

        try {
            verifySavedFiles()
            events.clear()
            instrumentation.runOnMainSync {
                client = RingBleClient(context, object : RingBleClient.Listener {
                    override fun onBleState(message: String, connected: Boolean) {
                        if (closing.get()) return
                        if (connected) {
                            if (ready.compareAndSet(false, true)) events.offer(Ready(SystemClock.elapsedRealtime()))
                        } else if (ready.getAndSet(false)) {
                            events.offer(IllegalStateException("Connection lost; cancel clock diagnostics without retry"))
                        }
                    }
                    override fun onBleError(message: String) {
                        if (!closing.get()) events.offer(IllegalStateException(message))
                    }
                    override fun onSensorPacket(packet: SensorPacket) {
                        if (!closing.get() && (packet is SensorPacket.Health || packet is SensorPacket.TimeStatus)) {
                            events.offer(Received(packet, SystemClock.elapsedRealtime()))
                        }
                    }
                    override fun onRingsFound(rings: List<ScannedRing>) = Unit
                })
                requireNotNull(client).setAutoReconnect(false)
                check(requireNotNull(client).connectKnownAddress(ring.address, ring.name))
            }
            check(next(SystemClock.elapsedRealtime() + 20_000) is Ready) { "Expected connection readiness" }
            val baseline = observation()
            check(baseline.records.isEmpty() || store.hasPreservedDeviceRecords(ring.address, baseline.records)) {
                "Every nonempty ring record must have a verified local copy before TIME SET"
            }
            val (_, beforeTime) = readTime()
            val beforePacket = beforeTime.packet as SensorPacket.TimeStatus
            check(beforePacket.unixMs >= 0 && beforePacket.uptimeMs >= 0) { "Invalid pre-SET device time" }
            verifySavedFiles()

            val qa = File(root, "clock-sync-qa")
            if (!qa.exists()) { check(qa.mkdir()); syncDirectory(root) }
            check(qa.isDirectory)
            run = File(qa, UUID.randomUUID().toString()).also { check(it.mkdir()); syncDirectory(qa) }
            writeEvidence(requireNotNull(run), "before-device-observation.json", baseline)
            writeEvidence(requireNotNull(run), "before-time-status.json", beforePacket)
            // Capture the phone time in the main-thread enqueue action, after all protection checks.
            var setRequest: Request? = null
            command("TIME_SET") {
                check(setCount == 0) { "A second clock SET is forbidden" }
                val request = Request(System.currentTimeMillis(), SystemClock.elapsedRealtime())
                setRequest = request
                setCount++
                it.syncTime(request.epochMs)
            }
            val issued = requireNotNull(setRequest)
            writeEvidence(requireNotNull(run), "time-set-request.json", issued)
            val reply = next(issued.elapsedMs + PhoneClockSync.TIMEOUT_MS) as? Received
                ?: error("Unexpected event after TIME SET")
            val status = reply.packet as? SensorPacket.TimeStatus ?: error("TIME SET did not return TIME STATUS")
            val evidence = PhoneClockSync.accept(ring.address, 1, issued.epochMs, issued.elapsedMs, reply.elapsedMs, status)
            PhoneClockSync.save(requireNotNull(run), evidence)
            writeEvidence(requireNotNull(run), "time-set-status.json", status)

            val after = observation()
            writeEvidence(requireNotNull(run), "after-device-observation.json", after)
            assertEquals("TIME SET changed the idle HEALTH status", baseline.status, after.status)
            assertEquals("TIME SET changed the retained record fingerprints", baseline.records.toSet(), after.records.toSet())
            val (finalRequest, finalReply) = readTime()
            val finalStatus = finalReply.packet as SensorPacket.TimeStatus
            val finalEvidence = PhoneClockSync.accept(ring.address, 1, finalRequest.epochMs, finalRequest.elapsedMs,
                finalReply.elapsedMs, finalStatus)
            PhoneClockSync.save(requireNotNull(run), finalEvidence)
            writeEvidence(requireNotNull(run), "final-time-status.json", finalStatus)
            verifySavedFiles()
            assertEquals("Only one TIME SET may be issued", 1, setCount)
            emit("complete", mapOf("before_synced" to beforePacket.synced, "after_synced" to status.synced,
                "final_synced" to finalStatus.synced, "retained_record_count" to baseline.records.size,
                "set_round_trip_ms" to (reply.elapsedMs - issued.elapsedMs),
                "final_get_round_trip_ms" to (finalReply.elapsedMs - finalRequest.elapsedMs), "time_set_count" to setCount))
        } catch (failure: Throwable) {
            run?.let { folder -> runCatching {
                writeEvidence(folder, "failure.json", mapOf("message" to failure.message, "time_set_count" to setCount))
            } }
            throw failure
        } finally {
            closing.set(true)
            instrumentation.runOnMainSync { client?.stop() }
            assertEquals("Protected app files changed during clock diagnostics", beforeHashes, protectedHashes(root))
        }
    }

    private fun next(deadline: Long): Any {
        while (true) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            check(remaining > 0) { "Device observation deadline elapsed; no automatic retry" }
            val event = events.poll(minOf(remaining, 250L), TimeUnit.MILLISECONDS) ?: continue
            if (event is Exception) throw event
            return event
        }
    }

    private fun syncDirectory(directory: File) {
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }

    private fun writeEvidence(directory: File, name: String, value: Any) {
        val target = File(directory, name)
        check(!target.exists()) { "Clock diagnostic evidence already exists" }
        val temporary = File.createTempFile("clock-qa-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(Gson().toJson(value).toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            syncDirectory(directory)
        } finally {
            temporary.delete()
        }
    }

    private fun emit(event: String, fields: Map<String, Any>) {
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", Gson().toJson(mapOf("event" to event, "elapsed_ms" to SystemClock.elapsedRealtime()) + fields) + "\n")
        })
    }

    private fun protectedHashes(root: File): Map<String, String> =
        listOf("collection-real", "preparation", "collection-demo").flatMap { name ->
            File(root, name).walkTopDown().filter { it.isFile }.map { file ->
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { stream ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                file.relativeTo(root).path to digest.digest().joinToString("") { "%02x".format(it) }
            }.toList()
        }.toMap()
}
