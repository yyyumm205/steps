package com.nexthci.ringfitness

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.ArrayDeque

class RealSessionHeartRateTest {
    @get:Rule val temporary = TemporaryFolder()
    private val t = 1_790_000_000_000L
    private val sample = HeartRateSample(75, 75, 0, true, true, true, listOf(800), listOf(819))

    @Test fun retiredRecorderStorageErrorSurvivesFollowingSdkTelemetry() {
        val f = Fixture()
        f.prepare()
        f.emitSample()
        f.drain()
        f.failAppend = true
        f.retireOnAppendFailure = true
        f.emitSample()
        f.drain()

        val warning = f.bridge.state.storageError
        assertNotNull(warning)
        assertEquals(warning, f.bridge.state.message)
        assertFalse(f.bridge.state.recording)
        assertEquals(1L, f.store.read()!!.heartRate!!.sampleCount)
        assertTrue(f.errors.single() is IOException)

        f.emitSample(82)
        f.client.publish(f.client.state.copy(message = "Polar H10 已就绪（实时 HR/RR）"))
        f.drain()
        assertEquals(warning, f.bridge.state.storageError)
        assertEquals(warning, f.bridge.state.message)
        assertFalse(f.bridge.state.recording)
        assertEquals(82, f.bridge.state.lastHeartRate)
        assertEquals(1L, f.store.read()!!.heartRate!!.sampleCount)

        f.bridge.finish(f.store.read()!!)
        f.drain()
        assertEquals(warning, f.bridge.state.storageError)
        SessionHeartRate.verifyFile(f.directory, f.session.sessionId, f.store.read()!!.heartRate!!)
    }

    @Test fun storageWarningPersistsForCurrentOwnerAndClearsOnFreshSelection() {
        val f = Fixture()
        f.prepare()
        f.failAppend = true
        f.emitSample()
        f.drain()
        val warning = f.bridge.state.storageError
        assertNotNull(warning)

        assertTrue(f.bridge.prepare(f.store.read()!!))
        f.bridge.resetSelection()
        f.drain()
        assertEquals(warning, f.bridge.state.storageError)

        f.bridge.discard(f.session)
        f.drain()
        f.bridge.resetSelection()
        f.drain()
        assertNull(f.bridge.state.storageError)
        assertEquals("", f.bridge.state.message)
    }

    @Test fun mainThreadStopFailureStillSealsNoSampleFileAndRejectsLateSamples() {
        val f = Fixture()
        f.prepare()
        val failure = IOException("injected main-thread timeout")
        f.mainFailure = failure
        f.clock.now = t + 20_000
        assertSame(failure, assertThrows(IOException::class.java) { f.bridge.finish(f.store.read()!!) })

        val sealed = f.store.read()!!.heartRate!!
        assertNotNull(sealed.endedAtMs)
        assertNull(f.recorder.activeSessionId)
        assertEquals("no_samples", sealed.status)
        assertEquals("no_data", sealed.gaps.single().reason)
        f.emitSample()
        f.drain()
        assertEquals(sealed, f.store.read()!!.heartRate)
        SessionHeartRate.verifyFile(f.directory, f.session.sessionId, sealed)
    }

    @Test fun clientStopFailureStillReleasesDiscardedRecorder() {
        val f = Fixture()
        f.prepare()
        val failure = IOException("injected transport STOP failure")
        f.client.stopFailure = failure
        f.emitSample() // This callback is queued behind the discard operation.
        assertSame(failure, assertThrows(IOException::class.java) { f.bridge.discard(f.session) })
        assertNull(f.recorder.activeSessionId)
        f.drain()
        assertEquals(listOf(SessionHeartRate.CSV_HEADER), f.csv.readLines())
    }

    @Test fun failedStopRetriesBeforeNextCaptureAndOldConsumerStaysInvalid() {
        for (failOnMain in listOf(true, false)) {
            val f = Fixture()
            f.prepare()
            val oldConsumer = requireNotNull(f.client.consumer)
            val address = requireNotNull(f.session.preparation.ring).address
            f.store.confirmStart(f.session.sessionId, address, HealthMessage.Status(true, 1024, 12, 0, 41), t + 100)
            f.store.requestStop(f.session.sessionId, t + 200)
            f.store.confirmStop(f.session.sessionId, address, HealthMessage.Status(false, 1024, 12, 0, 41), t + 300)
            f.store.discardStoppedSession(f.session.sessionId, t + 400)
            val failure = IOException("injected STOP failure")
            if (failOnMain) f.mainFailure = failure else f.client.stopFailure = failure
            assertSame(failure, assertThrows(IOException::class.java) { f.bridge.discard(f.session) })
            assertNull(f.recorder.activeSessionId)

            // An unsuccessful retry retains the pending STOP and does not start another action.
            assertSame(failure, assertThrows(IOException::class.java) { f.bridge.search() })
            assertEquals(0, f.client.searchCalls)
            f.mainFailure = null
            f.client.stopFailure = null
            f.clock.now = t + 1_000
            val next = f.store.requestStart(f.session.preparation, f.clock.now, "UTC",
                activity = SessionActivity.BADMINTON, heartRate = SessionHeartRate("H1001", "Polar H10", f.clock.now))
            assertNotEquals(f.session.sessionId, next.sessionId)
            assertTrue(f.bridge.prepare(next))
            f.drain()
            assertEquals(2, f.client.acceptedStarts)
            assertTrue(f.bridge.state.recording)

            oldConsumer(sample.copy(hr = 99), t + 1_100)
            f.emitSample(81)
            f.drain()
            f.bridge.finish(f.store.read()!!)
            val sealed = f.store.read()!!.heartRate!!
            assertEquals(1L, sealed.sampleCount)
            assertTrue(sealed.gaps.isEmpty())
            val csv = File(f.directory, SessionHeartRate.fileName(next.sessionId)).readText()
            assertTrue(csv.contains(",81,75,"))
            assertFalse(csv.contains(",99,75,"))
            SessionHeartRate.verifyFile(f.directory, next.sessionId, sealed)
        }
    }

    @Test fun transportAndSealFailuresAreBothRetainedAndSealCanRetry() {
        val f = Fixture()
        f.prepare()
        val transportFailure = IOException("injected main-thread failure")
        f.mainFailure = transportFailure
        f.failJournal = true
        val thrown = assertThrows(IOException::class.java) { f.bridge.finish(f.store.read()!!) }
        assertSame(transportFailure, thrown)
        assertEquals(listOf(f.journalFailure), thrown.suppressed.toList())
        assertNull(f.store.read()!!.heartRate!!.endedAtMs)

        f.mainFailure = null
        f.failJournal = false
        f.bridge.finish(f.store.read()!!)
        f.drain()
        assertNotNull(f.store.read()!!.heartRate!!.endedAtMs)
        assertNull(f.recorder.activeSessionId)
        SessionHeartRate.verifyFile(f.directory, f.session.sessionId, f.store.read()!!.heartRate!!)
    }

    @Test fun shutdownFailureLeavesRecorderReleasedAndCloseIsIdempotent() {
        val f = Fixture()
        f.prepare()
        val failure = IOException("injected shutdown failure")
        f.client.shutdownFailure = failure
        assertSame(failure, assertThrows(IOException::class.java) { f.bridge.close() })
        assertNull(f.recorder.activeSessionId)
        f.bridge.close()
        assertEquals(1, f.client.shutdownCalls)
        f.emitSample()
        f.drain()
        assertEquals(listOf(SessionHeartRate.CSV_HEADER), f.csv.readLines())
    }

    @Test fun checkpointFailureStillShutsDownClientAndRetainsBothFailures() {
        val f = Fixture()
        f.prepare()
        f.emitSample()
        f.drain()
        assertEquals(0L, f.store.read()!!.heartRate!!.sampleCount) // The sample still needs its close checkpoint.
        val shutdownFailure = IOException("injected shutdown failure")
        f.client.shutdownFailure = shutdownFailure
        f.failJournal = true
        val thrown = assertThrows(IOException::class.java) { f.bridge.close() }
        assertSame(f.journalFailure, thrown)
        assertEquals(listOf(shutdownFailure), thrown.suppressed.toList())
        assertNull(f.recorder.activeSessionId)
        assertEquals(1, f.client.shutdownCalls)
        f.bridge.close()
        assertEquals(1, f.client.shutdownCalls)
    }

    @Test fun queuedSampleUsesCopiedArraysAndCannotWriteAfterFinish() {
        val f = Fixture()
        f.prepare()
        val intervals = mutableListOf(800)
        f.client.emit(sample.copy(rrMs = intervals), t + 100)
        intervals[0] = 900
        f.drain()
        assertTrue(f.csv.readText().contains(",800,819\n"))

        f.emitSample()
        f.bridge.finish(f.store.read()!!)
        val bytes = f.csv.readBytes()
        f.drain()
        assertArrayEquals(bytes, f.csv.readBytes())
        assertEquals(1L, f.store.read()!!.heartRate!!.sampleCount)
    }

    @Test fun restoreReconnectsRecordedIdentityAndWritesIntoItsOriginalFile() {
        val f = Fixture()
        f.recorder.open(f.session, false)
        f.recorder.write(sample, t + 100)
        f.recorder.close()
        f.clock.now = t + 5_000
        f.bridge.restore(f.store.read()!!)
        f.drain()
        assertEquals("H1001", f.client.restoredDevice)
        f.emitSample()
        f.drain()
        f.bridge.finish(f.store.read()!!)
        val sealed = f.store.read()!!.heartRate!!
        assertEquals(2L, sealed.sampleCount)
        assertEquals("process_restart", sealed.gaps.single().reason)
        SessionHeartRate.verifyFile(f.directory, f.session.sessionId, sealed)
    }

    private inner class Fixture {
        val directory = temporary.newFolder()
        var failAppend = false
        var retireOnAppendFailure = false
        var failJournal = false
        var mainFailure: Exception? = null
        val journalFailure = IOException("injected journal failure")
        val store = FreeLivingSessionStore(File(directory, "session.json"), { source, target ->
            if (failJournal) throw journalFailure
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }, {})
        val clock = TestClock(t)
        val session = store.requestStart(PreparationSnapshot("p001", "83e56afb-a74c-4825-af0b-b226cfe9c630",
            RingPlacement.LEFT_INDEX, PreparedRing("AA:BB:CC:DD:EE:01", "Ringo")), t, "UTC",
            activity = SessionActivity.BADMINTON, heartRate = SessionHeartRate("H1001", "Polar H10", t))
        val csv get() = File(directory, SessionHeartRate.fileName(session.sessionId))
        val recorder = SessionHeartRateRecorder(directory, store, clock, {}, { output, bytes ->
            if (failAppend) {
                if (retireOnAppendFailure) {
                    output.write(bytes, 0, bytes.size / 2)
                    output.close()
                }
                throw IOException("injected append failure")
            }
            output.write(bytes)
        })
        val client = FakeClient()
        val errors = mutableListOf<Exception>()
        private val queued = ArrayDeque<() -> Unit>()
        val bridge = RealSessionHeartRate(store, recorder,
            dispatch = { queued.addLast(it) },
            onMain = { action -> mainFailure?.let { throw it }; action() },
            changed = {}, reportError = { errors += it },
            clientFactory = { listener -> client.also { it.listener = listener } })

        fun prepare() {
            bridge.connect("H1001")
            drain()
            assertTrue(bridge.prepare(session))
            drain()
        }

        fun emitSample(hr: Int = sample.hr) {
            clock.now += 100
            client.emit(sample.copy(hr = hr), clock.now)
        }

        fun drain() {
            while (queued.isNotEmpty()) queued.removeFirst().invoke()
        }
    }

    private class FakeClient : SessionHeartRateClient {
        var listener: (PolarH10State) -> Unit = {}
        var state = PolarH10State()
        var consumer: ((HeartRateSample, Long) -> Unit)? = null
        var stopFailure: Exception? = null
        var shutdownFailure: Exception? = null
        var shutdownCalls = 0
        var searchCalls = 0
        var acceptedStarts = 0
        var restoredDevice: String? = null

        fun publish(next: PolarH10State) { state = next; listener(next) }
        fun emit(sample: HeartRateSample, at: Long) {
            consumer?.invoke(sample, at)
            publish(state.copy(lastHeartRate = sample.hr))
        }
        override fun beginFreshSelection() {
            if (consumer == null) publish(PolarH10State(message = ""))
        }
        override fun search() { searchCalls++; publish(state.copy(scanning = true)) }
        override fun connect(deviceId: String) = publish(PolarH10State(selectedDeviceId = deviceId,
            connected = true, hrReady = true, message = "Polar H10 已就绪（实时 HR/RR）"))
        override fun startRecording(consumer: (HeartRateSample, Long) -> Unit): Boolean {
            if (this.consumer != null) return false
            this.consumer = consumer
            acceptedStarts++
            publish(state.copy(recording = true, message = "Polar H10 正在实时采集 HR/RR"))
            return true
        }
        override fun markCaptureRestored(deviceId: String) {
            consumer = null
            restoredDevice = deviceId
            connect(deviceId)
        }
        override fun resumeLiveRecording(consumer: (HeartRateSample, Long) -> Unit) = startRecording(consumer)
        override fun stopRecording() {
            stopFailure?.let { throw it }
            consumer = null
            publish(state.copy(recording = false, message = "Polar H10 已就绪（实时 HR/RR）"))
        }
        override fun shutdown() { shutdownCalls++; shutdownFailure?.let { throw it } }
    }

    private class TestClock(var now: Long) : CaptureClock {
        private val started = now
        override fun nowEpochMs() = now
        override fun nowElapsedMs() = now - started
        override fun timeZoneId() = "UTC"
    }
}
