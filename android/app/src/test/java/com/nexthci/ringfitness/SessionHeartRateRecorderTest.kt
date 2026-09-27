package com.nexthci.ringfitness

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class SessionHeartRateRecorderTest {
    @get:Rule val temporary = TemporaryFolder()
    private val t = 1_790_000_000_000L
    private val sample = HeartRateSample(75, 75, 0, true, true, true, listOf(800), listOf(819))

    @Test fun originalCsvColumnsAndFirstOneBasedIndexArePreserved() {
        val f = Fixture()
        f.recorder.open(f.session, false)
        f.recorder.write(sample, t + 10)
        f.recorder.write(sample.copy(correctedHr = -1, ppgQuality = -1), t + 10)
        f.finish()
        val hr = f.store.read()!!.heartRate!!
        assertEquals(2L, hr.sampleCount)
        assertEquals(t + 10, hr.firstSampleAtMs)
        assertEquals(t + 10, hr.lastSampleAtMs)
        assertEquals("recorded", hr.status)
        assertEquals(SessionHeartRate.CSV_HEADER, f.csv.readLines().first())
        SessionHeartRate.verifyFile(f.directory, f.session.sessionId, hr)
    }

    @Test fun disconnectClosesOnlyOnARealSampleAndContinuesSameFile() {
        val f = Fixture()
        f.recorder.open(f.session, false)
        f.recorder.write(sample, t + 100)
        f.clock.now = t + 200
        f.recorder.gap("disconnected")
        f.recorder.gap("disconnected")
        assertNull(f.store.read()!!.heartRate!!.gaps.single().endedAtMs)
        f.recorder.write(sample, t + 800)
        f.finish()
        val hr = f.store.read()!!.heartRate!!
        assertEquals("partial", hr.status)
        assertEquals(listOf(HeartRateGap(t + 200, t + 800, "disconnected")), hr.gaps)
        assertEquals(2L, hr.sampleCount)
        SessionHeartRate.verifyFile(f.directory, f.session.sessionId, hr)
    }

    @Test fun reopenCountsActualCompleteRowsAndMarksProcessGap() {
        val f = Fixture()
        f.recorder.open(f.session, false)
        f.recorder.write(sample, t + 10)
        assertEquals(0L, f.store.read()!!.heartRate!!.sampleCount) // Before periodic checkpoint.
        f.recorder.close()
        f.csv.appendText("unfinished,partial")
        f.clock.now = t + 5_000
        f.recorder = f.newRecorder()
        f.recorder.open(f.store.read()!!, true)
        assertFalse(f.csv.readText().contains("unfinished"))
        assertEquals(1L, f.store.read()!!.heartRate!!.sampleCount)
        f.recorder.write(sample, t + 6_000)
        f.finish()
        val hr = f.store.read()!!.heartRate!!
        assertEquals(2L, hr.sampleCount)
        assertEquals("process_restart", hr.gaps.single().reason)
        SessionHeartRate.verifyFile(f.directory, f.session.sessionId, hr)
    }

    @Test fun missingRecoveredFileReportsStorageLossWithoutInventedSampleCount() {
        val f = Fixture()
        f.recorder.open(f.session, false)
        f.recorder.write(sample, t + 10)
        f.recorder.close()
        assertTrue(f.csv.delete())
        f.recorder = f.newRecorder()
        f.recorder.open(f.store.read()!!, true)
        f.finish()
        val hr = f.store.read()!!.heartRate!!
        assertEquals("no_samples", hr.status)
        assertEquals(0L, hr.sampleCount)
        assertTrue(hr.gaps.any { it.reason == "storage_error" })
        SessionHeartRate.verifyFile(f.directory, f.session.sessionId, hr)
    }

    @Test fun noSamplesAndSilentStreamGapAreExplicit() {
        for (hasSample in listOf(false, true)) {
            val f = Fixture()
            f.recorder.open(f.session, false)
            if (hasSample) f.recorder.write(sample, t + 10)
            f.clock.now = t + 20_000
            f.recorder.finish(f.store.read()!!)
            val hr = f.store.read()!!.heartRate!!
            assertEquals(if (hasSample) "partial" else "no_samples", hr.status)
            assertEquals("no_data", hr.gaps.single().reason)
            SessionHeartRate.verifyFile(f.directory, f.session.sessionId, hr)
        }
    }

    @Test fun backwardPhoneClockKeepsReceivedTimesForResearchReview() {
        val f = Fixture()
        f.recorder.open(f.session, false)
        f.recorder.write(sample, t + 100)
        f.clock.now = t + 200
        f.recorder.gap("disconnected")
        f.recorder.write(sample, t - 10_000)
        f.clock.now = t - 9_000
        f.recorder.finish(f.store.read()!!)
        val hr = f.store.read()!!.heartRate!!
        assertEquals(t - 10_000, hr.lastSampleAtMs)
        assertEquals(t - 10_000, hr.gaps.single().endedAtMs)
        SessionHeartRate.verifyFile(f.directory, f.session.sessionId, hr)
    }

    @Test fun earlierResolvedGapDoesNotHideLaterSilentTail() {
        val f = Fixture()
        f.recorder.open(f.session, false)
        f.recorder.write(sample, t + 100)
        f.clock.now = t + 200
        f.recorder.gap("disconnected")
        f.recorder.write(sample, t + 500)
        f.clock.now = t + 20_000
        f.recorder.finish(f.store.read()!!)
        assertEquals(listOf(HeartRateGap(t + 200, t + 500, "disconnected"),
            HeartRateGap(t + 500, t + 20_000, "no_data")), f.store.read()!!.heartRate!!.gaps)
    }

    @Test fun openTerminalGapIsClosedWithoutDuplicateSilenceGap() {
        val f = Fixture()
        f.recorder.open(f.session, false)
        f.recorder.write(sample, t + 100)
        f.clock.now = t + 200
        f.recorder.gap("disconnected")
        f.clock.now = t + 20_000
        f.recorder.finish(f.store.read()!!)
        assertEquals(listOf(HeartRateGap(t + 200, t + 20_000, "disconnected")), f.store.read()!!.heartRate!!.gaps)
    }

    @Test fun closedStreamAndForeignDiscardCannotWriteIntoNextSession() {
        val f = Fixture()
        f.recorder.open(f.session, false)
        f.recorder.discard(f.session.copy(sessionId = "another"))
        f.recorder.write(sample, t + 10)
        f.finish()
        val bytes = f.csv.readBytes()
        f.recorder.write(sample, t + 20)
        f.recorder.finish(f.store.read()!!)
        assertArrayEquals(bytes, f.csv.readBytes())
    }

    @Test fun completeMalformedRowsArePreservedForInspection() {
        val f = Fixture()
        f.recorder.open(f.session, false)
        f.recorder.close()
        f.csv.appendText("bad,complete,row\n")
        val original = f.csv.readBytes()
        f.recorder = f.newRecorder()
        assertThrows(IllegalArgumentException::class.java) { f.recorder.open(f.store.read()!!, true) }
        assertArrayEquals(original, f.csv.readBytes())
    }

    @Test fun interruptedAppendWithFailedRollbackIsRecoveredBeforeSealing() {
        val f = Fixture()
        var fail = false
        f.recorder = SessionHeartRateRecorder(f.directory, f.store, f.clock, {}, { output, bytes ->
            if (fail) {
                output.write(bytes, 0, bytes.size / 2)
                output.close() // Also makes the immediate rollback fail.
                throw java.io.IOException("injected interrupted append")
            }
            output.write(bytes)
        })
        f.recorder.open(f.session, false)
        f.recorder.write(sample, t + 100)
        fail = true
        assertThrows(java.io.IOException::class.java) { f.recorder.write(sample, t + 200) }
        f.recorder.gap("storage_error")
        f.recorder.write(sample, t + 300) // Retired stream cannot append behind its broken tail.
        assertNull(f.store.read()!!.heartRate!!.endedAtMs)
        f.finish()
        val hr = f.store.read()!!.heartRate!!
        assertEquals(1L, hr.sampleCount)
        assertTrue(hr.gaps.any { it.reason == "storage_error" })
        SessionHeartRate.verifyFile(f.directory, f.session.sessionId, hr)
    }

    private inner class Fixture {
        val directory = temporary.newFolder()
        val store = FreeLivingSessionStore(File(directory, "session.json"), { source, target ->
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }, {})
        val clock = TestClock(t)
        val session = store.requestStart(PreparationSnapshot("p001", "83e56afb-a74c-4825-af0b-b226cfe9c630",
            RingPlacement.LEFT_INDEX, PreparedRing("AA:BB:CC:DD:EE:01", "Ringo")), t, "UTC",
            activity = SessionActivity.BADMINTON, heartRate = SessionHeartRate("H1001", "Polar H10", t))
        val csv get() = File(directory, SessionHeartRate.fileName(session.sessionId))
        var recorder = newRecorder()
        fun newRecorder() = SessionHeartRateRecorder(directory, store, clock, {})
        fun finish() { clock.now = t + 1_000; recorder.finish(store.read()!!) }
    }

    private class TestClock(var now: Long) : CaptureClock {
        private val started = now
        override fun nowEpochMs() = now
        override fun nowElapsedMs() = now - started
        override fun timeZoneId() = "UTC"
    }
}
