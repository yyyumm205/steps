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

    @Test fun maximumSupportedRrArraysSealWithinSharedLineQuota() {
        val f = Fixture()
        f.recorder.open(f.session, false)
        val intervals = List(4096) { 65535 }
        f.recorder.write(sample.copy(rrMs = intervals, rrRaw = intervals), t + 10)
        f.finish()
        val row = f.csv.readLines()[1]
        assertTrue((row + "\n").toByteArray(Charsets.US_ASCII).size <= 64 * 1024)
        assertEquals(4096, row.split(',')[9].split('|').size)
        assertEquals(4096, row.split(',')[10].split('|').size)
        val hr = f.store.read()!!.heartRate!!
        assertEquals(1L, hr.sampleCount)
        SessionHeartRate.verifyFile(f.directory, f.session.sessionId, hr)
    }

    @Test fun excessiveRrArraysLeaveCsvAndCheckpointIntactBeforeValidRetry() {
        val f = Fixture()
        f.recorder.open(f.session, false)
        f.recorder.write(sample, t + 10)
        f.recorder.checkpoint()
        val before = f.csv.readBytes()
        val checkpoint = f.store.read()!!.heartRate!!
        val intervals = List(4097) { 65535 }
        assertThrows(IllegalArgumentException::class.java) {
            f.recorder.write(sample.copy(rrMs = intervals, rrRaw = intervals), t + 20_000)
        }
        assertArrayEquals(before, f.csv.readBytes())
        assertEquals(checkpoint, f.store.read()!!.heartRate)
        f.recorder.write(sample, t + 20)
        f.finish()
        val hr = f.store.read()!!.heartRate!!
        assertEquals(2L, hr.sampleCount)
        assertEquals(t + 20, hr.lastSampleAtMs)
        assertEquals("recorded", hr.status)
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

    @Test fun missingFileRecoveryRetriesAfterJournalCommitFailure() {
        val f = Fixture()
        f.recorder.open(f.session, false)
        f.recorder.write(sample, t + 10)
        f.recorder.close()
        val checkpoint = f.store.read()!!.heartRate!!
        assertTrue(f.csv.delete())
        f.clock.now = t + 500
        f.recorder = f.newRecorder()
        f.failNextJournalCommit = true
        assertThrows(java.io.IOException::class.java) { f.recorder.open(f.store.read()!!, true) }
        assertEquals(checkpoint, f.store.read()!!.heartRate)
        assertEquals(SessionHeartRate.CSV_HEADER + "\n", f.csv.readText())
        assertNull(f.recorder.activeSessionId)

        f.recorder.open(f.store.read()!!, true)
        f.finish()
        val hr = f.store.read()!!.heartRate!!
        assertEquals("no_samples", hr.status)
        assertEquals(0L, hr.sampleCount)
        assertNull(hr.firstSampleAtMs)
        assertNull(hr.lastSampleAtMs)
        assertEquals(listOf(HeartRateGap(t, t + 500, "storage_error")),
            hr.gaps.filter { it.reason == "storage_error" })
        SessionHeartRate.verifyFile(f.directory, f.session.sessionId, hr)
    }

    @Test fun recoveryPreservesCompleteRowsAfterCheckpointedTailIsLost() {
        val f = Fixture()
        f.recorder.open(f.session, false)
        f.recorder.write(sample, t + 10)
        f.recorder.write(sample, t + 20)
        f.recorder.close()
        assertEquals(2L, f.store.read()!!.heartRate!!.sampleCount)
        val survivingPrefix = f.csv.readLines().take(2).joinToString("\n", postfix = "\n")
        f.csv.writeText(survivingPrefix)
        f.clock.now = t + 500
        f.recorder = f.newRecorder()

        f.recorder.open(f.store.read()!!, true)
        val recovered = f.store.read()!!.heartRate!!
        assertEquals(survivingPrefix, f.csv.readText())
        assertEquals(1L, recovered.sampleCount)
        assertEquals(t + 10, recovered.firstSampleAtMs)
        assertEquals(t + 10, recovered.lastSampleAtMs)
        assertEquals(listOf(HeartRateGap(t + 10, t + 500, "storage_error")),
            recovered.gaps.filter { it.reason == "storage_error" })
        f.recorder.write(sample, t + 600)
        f.finish()
        val hr = f.store.read()!!.heartRate!!
        assertEquals("partial", hr.status)
        assertEquals(2L, hr.sampleCount)
        assertEquals(t + 10, hr.firstSampleAtMs)
        assertEquals(t + 600, hr.lastSampleAtMs)
        assertTrue(f.csv.readText().startsWith(survivingPrefix))
        SessionHeartRate.verifyFile(f.directory, f.session.sessionId, hr)
    }

    @Test fun repeatedStorageLossAtSamePhoneTimeKeepsEachRecoveryEvidence() {
        val f = Fixture()
        f.recorder.open(f.session, false)
        f.recorder.write(sample, t + 10)
        f.recorder.write(sample, t + 20)
        f.recorder.close()
        val survivingPrefix = f.csv.readLines().take(2).joinToString("\n", postfix = "\n")
        val loss = HeartRateGap(t + 10, t + 500, "storage_error")

        repeat(2) { recovery ->
            f.csv.writeText(survivingPrefix)
            f.clock.now = t + 500 // Repeated wall-clock readings still describe separate losses.
            f.recorder = f.newRecorder()
            f.recorder.open(f.store.read()!!, true)
            val recovered = f.store.read()!!.heartRate!!
            assertEquals(survivingPrefix, f.csv.readText())
            assertEquals(1L, recovered.sampleCount)
            assertEquals(t + 10, recovered.firstSampleAtMs)
            assertEquals(t + 10, recovered.lastSampleAtMs)
            assertEquals(List(recovery + 1) { loss }, recovered.gaps.filter { it.reason == "storage_error" })
            f.clock.now = t + 600 + recovery * 100
            f.recorder.write(sample, f.clock.now)
            f.recorder.close()
            assertEquals(2L, f.store.read()!!.heartRate!!.sampleCount)
        }

        f.finish()
        val hr = f.store.read()!!.heartRate!!
        assertEquals("partial", hr.status)
        assertEquals(2L, hr.sampleCount)
        assertEquals(t + 10, hr.firstSampleAtMs)
        assertEquals(t + 700, hr.lastSampleAtMs)
        assertEquals(listOf(loss, loss), hr.gaps.filter { it.reason == "storage_error" })
        assertTrue(f.csv.readText().startsWith(survivingPrefix))
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
        var failNextJournalCommit = false
        val store = FreeLivingSessionStore(File(directory, "session.json"), { source, target ->
            if (failNextJournalCommit) {
                failNextJournalCommit = false
                throw java.io.IOException("injected journal commit failure")
            }
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
