package com.nexthci.ringfitness

import android.app.ActivityManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.ZoneId
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Opt-in device experiment. Uses production ownership and storage, with no upload owner. */
@RunWith(AndroidJUnit4::class)
class ChargingRecoveryDeviceInstrumentedTest {
    @Test fun oneChargingRecoveryAttemptStopsAndPreservesItsRealRawData() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Requires reviewed protected originals and explicit device opt-in",
            InstrumentationRegistry.getArguments().getString("verifyChargingRecovery") == "true")
        val context = instrumentation.targetContext
        val expectedStartError = InstrumentationRegistry.getArguments().getString("expectedStartError", "-16").toInt()
        require(expectedStartError in setOf(0, -16))
        check(BuildConfig.DEBUG && !RealCollectionBridge.isRunning()) { "Close the real collection owner before this experiment" }
        @Suppress("DEPRECATION")
        check(context.getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE)
            .none { it.service.className == RealCollectionService::class.java.name }) { "Real collection service is still running" }

        val root = File(context.filesDir, "charging-recovery-qa")
        val protected = File(root, "protected").canonicalFile
        require(protected.isDirectory) { "Provision and review the protected original collection directory first" }
        val protectedHashes = hashes(protected)
        val protectedStore = FreeLivingSessionStore(File(protected, "session.json"))
        val protectedObservation = Gson().fromJson(File(protected, "device-observation.json").readText(),
            HealthRecordObservation::class.java)
        val originalProfileFile = File(context.filesDir, "preparation/profile.properties")
        val originalProfileHash = sha256(originalProfileFile)
        val originalProfile = requireNotNull(PreparationStore(originalProfileFile).read())
        val ring = requireNotNull(originalProfile.ring)
        val placement = requireNotNull(originalProfile.placement)
        require(protectedObservation.address == ring.address && !protectedObservation.status.collecting)
        require(protectedObservation.status.errorCode in setOf(0, -16) && protectedObservation.records.isNotEmpty())

        fun verifyProtected() {
            check(protectedStore.readPending() == null) { "Protected originals still contain an unresolved request" }
            val sessions = protectedStore.listSessions()
            require(sessions.isNotEmpty())
            sessions.filter { it.localData != null }.forEach { session ->
                protectedStore.manifestSnapshot(session.sessionId)
                val record = requireNotNull(session.deviceRecordEvidence).record
                val raw = requireNotNull(session.localData).files.single()
                RealSessionDownload.verifyPreservedContainer(File(protected, raw.fileName), record,
                    session.startedAtMs ?: 0L, session.endedAtMs ?: 0L)
            }
            require(protectedStore.hasPreservedDeviceRecords(ring.address, protectedObservation.records))
            assertEquals(protectedHashes, hashes(protected))
            assertEquals(originalProfileHash, sha256(originalProfileFile))
        }
        verifyProtected()

        val run = File(root, "run")
        require(!File(run, "session.json").exists() && !File(run, "start-issued.json").exists()) {
            "An earlier experiment exists; inspect and recover it without another START"
        }
        if (!run.exists()) { check(run.mkdir()); syncDirectory(root) }
        require(run.isDirectory && run.canonicalFile.parentFile == root.canonicalFile)
        val preparation = PreparationStore(File(run, "profile.properties"))
        val registered = preparation.register("diagnostic001", placement)
        require(registered.participantId == "diagnostic001" && registered.installationId != originalProfile.installationId)
        preparation.savePlacement(placement)
        preparation.selectRing(ring)
        syncDirectory(run)
        val store = FreeLivingSessionStore(File(run, "session.json"))
        val events = LinkedBlockingQueue<() -> Unit>()
        val recoveryEvents = LinkedBlockingQueue<Any>()
        val main = Handler(Looper.getMainLooper())
        var client: RingBleClient? = null
        var activeGeneration = 0L
        var ready = false
        var closed = false
        var recoveryMode = false
        var issuingGeneration = 0L
        var startCount = 0
        var stopCount = 0
        var latestObservation: HealthRecordObservation? = null
        lateinit var owner: RealCollectionController
        var previousState = ""

        fun progress(message: String) {
            runCatching { instrumentation.sendStatus(0, Bundle().apply { putString("charging_recovery_progress", message) }) }
            runCatching {
                FileOutputStream(File(run, "progress.txt"), true).use {
                    it.write("${System.currentTimeMillis()} $message\n".toByteArray(Charsets.UTF_8)); it.fd.sync()
                }
            }
        }
        fun command(action: (RingBleClient) -> Boolean): Boolean {
            var accepted = false
            instrumentation.runOnMainSync { accepted = client?.let(action) ?: false }
            return accepted
        }
        fun closeClient() {
            instrumentation.runOnMainSync { activeGeneration = 0; ready = false; client?.stop(); client = null }
        }
        fun awaitState(label: String, timeoutMs: Long, done: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            while (!done()) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                check(remaining > 0) { "$label timed out; phase=${store.read()?.phase}, page=${owner.state.page}, error=${owner.state.error}" }
                events.poll(minOf(remaining, 250L), TimeUnit.MILLISECONDS)?.invoke()
            }
            progress(label)
        }
        val port = object : RealCollectionPort {
            override fun connect(ring: PreparedRing, generation: Long): Boolean {
                var accepted = false
                instrumentation.runOnMainSync {
                    activeGeneration = generation
                    ready = false
                    val next = RingBleClient(context, object : RingBleClient.Listener {
                        private fun active() = !closed && activeGeneration == generation
                        override fun onBleState(message: String, isReady: Boolean) {
                            if (!active()) return
                            if (isReady && !ready) {
                                ready = true
                                events.offer { owner.onConnected(generation) }
                            } else if (!isReady && ready) {
                                ready = false
                                events.offer { owner.onDisconnected(generation, message) }
                            }
                        }
                        override fun onBleError(message: String) {
                            if (active()) {
                                if (recoveryMode) recoveryEvents.offer(IllegalStateException(message))
                                else events.offer { owner.onDisconnected(generation, message) }
                            }
                        }
                        override fun onSensorPacket(packet: SensorPacket) {
                            if (!active()) return
                            if (recoveryMode) {
                                if (packet is SensorPacket.Health) recoveryEvents.offer(packet)
                                return
                            }
                            when (packet) {
                                is SensorPacket.Health -> events.offer { owner.onHealth(generation, packet) }
                                is SensorPacket.Battery -> events.offer { owner.onBattery(generation, packet) }
                                else -> Unit
                            }
                        }
                        override fun onRingsFound(rings: List<ScannedRing>) = Unit
                    })
                    client = next
                    accepted = next.connectKnownAddress(ring.address, ring.name)
                }
                return accepted
            }
            override fun disconnect() = closeClient()
            override fun queryBattery() = command { it.requestBattery() }
            override fun queryStatus() = command { it.requestHealthStatus() }
            override fun queryRecords() = command { it.requestHealthSessions() }
            override fun read(sessionId: Int, offset: Long, length: Int) = command { it.readHealth(sessionId, offset, length) }
            override fun start(): Boolean {
                val checkStarted = SystemClock.elapsedRealtime()
                require(startCount == 0) { "The experiment permits exactly one START" }
                verifyProtected()
                val current = requireNotNull(store.readPending())
                require(current.phase == FreeLivingSessionPhase.START_REQUESTED)
                val baseline = requireNotNull(current.startBaseline)
                require(baseline.status.errorCode == expectedStartError)
                if (expectedStartError == -16) requireNotNull(baseline.chargingRecoveryEvidence).validate(baseline.status, baseline.observedAtMs)
                require(store.hasPreservedDeviceRecords(ring.address, baseline.records))
                val marker = File(run, "start-issued.json")
                check(marker.createNewFile()) { "A START was already attempted; retain the existing run" }
                FileOutputStream(marker).use {
                    it.write(Gson().toJson(mapOf("session_id" to current.sessionId,
                        "issued_at_ms" to System.currentTimeMillis())).toByteArray(Charsets.UTF_8)); it.fd.sync()
                }
                syncDirectory(run)
                startCount++
                instrumentation.runOnMainSync { issuingGeneration = activeGeneration }
                require(issuingGeneration > 0)
                progress("START one-shot marker saved")
                return command {
                    check(ready && activeGeneration == issuingGeneration)
                    require(SystemClock.elapsedRealtime() - checkStarted < 1_000) { "Pre-send backup verification took too long" }
                    baseline.chargingRecoveryEvidence?.copy(checkedAtMs = System.currentTimeMillis())?.validate(baseline.status, baseline.observedAtMs)
                    it.startHealth()
                }
            }
            override fun stop(): Boolean {
                require(startCount == 1 && stopCount == 0)
                val current = requireNotNull(store.readPending())
                require(current.phase == FreeLivingSessionPhase.STOP_REQUESTED && current.startConfirmedAtMs != null)
                stopCount++
                val accepted = command { it.stopHealth() }
                progress("STOP after confirmed collection: queued=$accepted")
                return accepted
            }
        }
        owner = RealCollectionController(run, preparation, store, port,
            CollectionScheduler { delay, action -> main.postDelayed({ if (!closed && !recoveryMode) events.offer(action) }, delay) },
            object : CaptureClock {
                override fun nowEpochMs() = System.currentTimeMillis()
                override fun nowElapsedMs() = SystemClock.elapsedRealtime()
                override fun timeZoneId() = ZoneId.systemDefault().id
            }, recordObservation = { observation ->
                latestObservation = observation
                progress("STATUS collecting=${observation.status.collecting} error=${observation.status.errorCode} records=${observation.status.records}")
                val evidence = File(run, "observation-${System.nanoTime()}.json")
                check(evidence.createNewFile())
                FileOutputStream(evidence).use { it.write(Gson().toJson(observation).toByteArray(Charsets.UTF_8)); it.fd.sync() }
            }, reportError = { progress("owner error: ${it.javaClass.simpleName}: ${it.message}") })
        val subscription = owner.observe { state ->
            val snapshot = "page=${state.page} phase=${state.session?.phase} connected=${state.connected} canStart=${state.canStart} canStop=${state.canStop} error=${state.error}"
            if (snapshot != previousState) { previousState = snapshot; progress(snapshot) }
        }
        fun stopAndSave(timeoutMs: Long) {
            if (store.read()?.stopRequestedAtMs == null && !owner.state.canStop) {
                awaitState("Collection connection ready for STOP", timeoutMs) { owner.state.canStop }
            }
            if (owner.state.canStop) owner.stop()
            awaitState("STOP confirmed", timeoutMs) { store.read()?.stopConfirmedAtMs != null }
            if (store.read()?.reference == null) owner.saveReference("", "missing", "兼容恢复测试，未同步计步器")
            awaitState("Raw data saved", timeoutMs) { store.read()?.localData != null }
        }
        // An anomalous collecting reply must retain its raw error. This diagnostic-only exit
        // proves the new fingerprint on the issuing connection before issuing a single STOP.
        fun preserveAnomalousStart() {
            val pending = requireNotNull(store.readPending())
            require(pending.phase == FreeLivingSessionPhase.START_REQUESTED && startCount == 1 && stopCount == 0)
            val baseline = requireNotNull(pending.startBaseline)
            val recoveryDeadline = SystemClock.elapsedRealtime() + 120_000
            fun requireIssuingConnection() {
                check(SystemClock.elapsedRealtime() < recoveryDeadline) { "Diagnostic preservation deadline reached; retain partial files" }
                var valid = false
                instrumentation.runOnMainSync { valid = ready && activeGeneration == issuingGeneration && issuingGeneration > 0 }
                require(valid) { "Issuing connection was lost; retain unconfirmed request for explicit recovery" }
            }
            requireIssuingConnection()
            instrumentation.runOnMainSync { recoveryMode = true }
            events.clear()
            fun nextPacket(): SensorPacket.Health {
                requireIssuingConnection()
                val remaining = recoveryDeadline - SystemClock.elapsedRealtime()
                val value = checkNotNull(recoveryEvents.poll(minOf(remaining, 10_000L).coerceAtLeast(1), TimeUnit.MILLISECONDS)) { "Recovery response timed out" }
                if (value is Exception) throw value
                return value as SensorPacket.Health
            }
            fun inspect(): HealthRecordObservation {
                requireIssuingConnection()
                recoveryEvents.clear()
                val requested = System.currentTimeMillis()
                check(command { it.requestHealthStatus() })
                var status: SensorPacket.Health? = null
                val records = mutableListOf<HealthMessage.ListItem>()
                while (true) {
                    val packet = nextPacket()
                    if (packet.receivedEpochMs < requested) continue
                    when (val message = packet.message) {
                        is HealthMessage.Status -> if (status == null) {
                            status = packet
                            check(command { it.requestHealthSessions() })
                        } else require(status.message == message)
                        is HealthMessage.ListItem -> if (status != null) {
                            require(records.size < 255 && records.none { it.sessionId == message.sessionId } &&
                                message.sessionId in 1..65535 && message.bytes in 0..0xFFFF_FFFFL && message.records in 0..0xFFFF_FFFFL &&
                                message.uptimeMs in 0..0xFFFF_FFFFL && message.unixMs >= 0)
                            records += message
                        }
                        is HealthMessage.ListEnd -> if (status != null) {
                            require(message.count == records.size)
                            val result = HealthRecordObservation(ring.address, issuingGeneration,
                                status.message as HealthMessage.Status, status.receivedEpochMs, records.toList())
                            val evidence = File(run, "recovery-observation-${System.nanoTime()}.json")
                            check(evidence.createNewFile())
                            FileOutputStream(evidence).use {
                                it.write(Gson().toJson(result).toByteArray(Charsets.UTF_8)); it.fd.sync()
                            }
                            return result
                        }
                        else -> Unit
                    }
                }
            }
            val initial = inspect()
            val candidate = initial.records.single { it.sessionId == initial.status.sessionId }
            require(initial.status.collecting && FreeLivingSessionStore.isDistinctStartRecord(baseline, candidate) &&
                initial.records.all { it == candidate || it in baseline.records } &&
                candidate.bytes >= initial.status.bytes && candidate.records >= initial.status.records) {
                "Recovery cannot uniquely associate a new collecting record; no STOP sent"
            }
            val stopMarker = File(run, "recovery-stop-issued.json")
            check(stopMarker.createNewFile())
            FileOutputStream(stopMarker).use {
                it.write(Gson().toJson(initial).toByteArray(Charsets.UTF_8)); it.fd.sync()
            }
            syncDirectory(run)
            requireIssuingConnection()
            stopCount++
            check(command { it.stopHealth() })
            progress("Diagnostic STOP for proved new record; original anomalous status retained")
            var stopped: HealthRecordObservation? = null
            repeat(10) {
                if (stopped == null) {
                    SystemClock.sleep(1000)
                    val observed = inspect()
                    val record = observed.records.single { it.sessionId == candidate.sessionId }
                    require(observed.status.sessionId == candidate.sessionId &&
                        FreeLivingSessionStore.sameDeviceRecord(candidate, record) &&
                        record.bytes >= candidate.bytes && record.records >= candidate.records &&
                        observed.records.all { it == record || it in baseline.records })
                    if (!observed.status.collecting && observed.status.bytes == record.bytes && observed.status.records == record.records) {
                        stopped = observed
                    }
                }
            }
            val final = checkNotNull(stopped) { "Diagnostic STOP remains unconfirmed; retain ring data and journal" }
            val record = final.records.single { it.sessionId == candidate.sessionId }
            val recoveryDirectory = File(run, "recovery")
            check(recoveryDirectory.mkdir()); syncDirectory(run)
            RealSessionDownload(recoveryDirectory, pending.sessionId, ring.address, record).use { download ->
                var complete = false
                while (!complete) {
                    val checked = inspect()
                    require(!checked.status.collecting && checked.status == final.status && checked.records == final.records)
                    requireIssuingConnection()
                    check(command { it.readHealth(record.sessionId, download.nextOffset, 16_384) })
                    var windowDone = false
                    while (!windowDone) when (val message = nextPacket().message) {
                        is HealthMessage.DataChunk -> download.append(message)
                        is HealthMessage.ReadEnd -> {
                            complete = download.checkpoint(message)
                            if (complete) {
                                val result = download.finish(message)
                                val raw = File(recoveryDirectory, result.file.fileName)
                                assertEquals(result.file.sha256, sha256(raw))
                                RealSessionDownload.verifyPreservedContainer(raw, record, 0, 0)
                            }
                            windowDone = true
                        }
                        else -> Unit
                    }
                }
            }
            progress("Anomalous record stopped and raw backup preserved; normal flow remains FAILED and journal unconfirmed")
        }
        var failure: Throwable? = null
        try {
            owner.initialize()
            awaitState("Fresh charging recovery readiness", 120_000) { owner.state.canStart }
            verifyProtected()
            owner.start()
            awaitState("START result received", 90_000) {
                owner.state.canStop || (startCount == 1 && !owner.state.busy &&
                    owner.state.taskPage in setOf(CollectionPage.RECOVERY, CollectionPage.ERROR))
            }
            check(owner.state.canStop && store.read()?.phase == FreeLivingSessionPhase.COLLECTING) {
                "Ring did not confirm a successful START; inspect the diagnostic preservation result"
            }
            progress("START confirmed by ring")
            val stopAt = SystemClock.elapsedRealtime() + 3_000
            awaitState("Three-second diagnostic capture complete", 5_000) { SystemClock.elapsedRealtime() >= stopAt }
            stopAndSave(90_000)
            val saved = requireNotNull(FreeLivingSessionStore(File(run, "session.json")).read())
            assertFalse(saved.isPending)
            assertEquals("diagnostic001", saved.preparation.participantId)
            assertEquals(ReferenceStatus.MISSING, saved.reference!!.status)
            assertNull(saved.reference.steps)
            assertEquals(SessionTransferStatus.PENDING, saved.transfer.status)
            assertEquals(0, saved.transfer.attempts)
            assertEquals(1, startCount)
            assertEquals(1, stopCount)
            val raw = saved.localData!!.files.single()
            assertFalse(raw.simulated)
            assertEquals(raw.sha256, sha256(File(run, raw.fileName)))
            RealSessionDownload.verifyPreservedContainer(File(run, raw.fileName), saved.deviceRecordEvidence!!.record,
                saved.startedAtMs ?: 0L, saved.endedAtMs ?: 0L)
            assertFalse(owner.state.uploadAvailable)
            progress("PASS: real raw data and missing reference preserved; no upload scheduled")
        } catch (error: Throwable) {
            failure = error
            progress("Experiment failed: ${error.message}")
        } finally {
            try {
                val current = store.readPending()
                if (current != null && current.startConfirmedAtMs != null && current.localData == null) {
                    stopAndSave(45_000)
                } else if (current != null) {
                    if (startCount == 1 && stopCount == 0) preserveAnomalousStart()
                    else progress("Recovery required: START remains unconfirmed; keep ring and journal unchanged")
                }
            } catch (recoveryError: Throwable) {
                progress("Recovery incomplete: ${recoveryError.message}")
                if (failure == null) failure = recoveryError else failure!!.addSuppressed(recoveryError)
            }
            try { verifyProtected() } catch (integrityError: Throwable) {
                if (failure == null) failure = integrityError else failure!!.addSuppressed(integrityError)
            }
            subscription.close()
            owner.close()
            instrumentation.runOnMainSync { closed = true }
            progress("Final: phase=${store.read()?.phase}, stopConfirmed=${store.read()?.stopConfirmedAtMs != null}, localComplete=${store.read()?.localData != null}; files retained")
        }
        failure?.let { throw it }
    }

    private fun hashes(directory: File): Map<String, String> = directory.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(directory).invariantSeparatorsPath to sha256(it) }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").let { digest ->
        file.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun syncDirectory(directory: File) = FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
}
