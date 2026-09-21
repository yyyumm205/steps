package com.nexthci.ringfitness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class AtomicSessionFinalizationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun zeroStepsAndUploadChoiceCommitTogetherAndSurviveReopen() {
        val fixture = Fixture()
        val reference = sessionReferenceFromInput("0", "valid", "", fixture.time + 4)

        fixture.store.finalizeStoppedSession(fixture.id, CompletionPolicy.SAVE_UPLOAD, reference)

        val restored = fixture.reopen().read()!!
        assertEquals(CompletionPolicy.SAVE_UPLOAD, restored.completionPolicy)
        assertEquals(ReferenceStatus.VALID, restored.reference!!.status)
        assertEquals(0L, restored.reference.steps)
        assertEquals(fixture.time + 4, restored.reference.recordedAtMs)
    }

    @Test fun missingAndUnreliableObservationsKeepTheirDistinctMeaning() {
        val missing = sessionReferenceFromInput("999", "missing", " 计步器意外归零 ", 101)
        assertEquals(ReferenceStatus.MISSING, missing.status)
        assertNull(missing.steps)
        assertEquals("计步器意外归零", missing.reason)

        assertThrows(IllegalArgumentException::class.java) {
            sessionReferenceFromInput("", "unreliable", "数字看不清", 102)
        }

        val estimated = sessionReferenceFromInput("73", "unreliable", "计步器中途松动", 103)
        assertEquals(73L, estimated.steps)
        assertEquals("计步器中途松动", estimated.reason)
    }

    @Test fun failedJournalCommitLeavesNeitherChoiceNorReference() {
        val fixture = Fixture()
        fixture.failCommit = true

        assertThrows(IOException::class.java) {
            fixture.store.finalizeStoppedSession(fixture.id, CompletionPolicy.SAVE_LATER,
                SessionReference(ReferenceStatus.VALID, 0, fixture.time + 4))
        }

        fixture.failCommit = false
        val restored = fixture.reopen().read()!!
        assertNull(restored.completionPolicy)
        assertNull(restored.reference)
    }

    @Test fun reopenCanRetryAtomicFinalizationWithoutReplacingFirstTimestamp() {
        val fixture = Fixture()
        val first = SessionReference(ReferenceStatus.VALID, 42, fixture.time + 4)
        fixture.store.finalizeStoppedSession(fixture.id, CompletionPolicy.SAVE_LATER, first)

        val retried = fixture.reopen().finalizeStoppedSession(fixture.id, CompletionPolicy.SAVE_LATER,
            first.copy(recordedAtMs = fixture.time + 50))

        assertEquals(first, retried.reference)
        assertEquals(CompletionPolicy.SAVE_LATER, retried.completionPolicy)
        assertEquals(first, fixture.reopen().read()!!.reference)
    }

    @Test fun legacyHalfStatesCanBeCompletedWithoutChangingTheirSavedValue() {
        val policyFirst = Fixture()
        policyFirst.store.setCompletionPolicy(policyFirst.id, CompletionPolicy.SAVE_LATER)
        val completedPolicyFirst = policyFirst.reopen().finalizeStoppedSession(policyFirst.id,
            CompletionPolicy.SAVE_LATER, SessionReference(ReferenceStatus.VALID, 5, policyFirst.time + 4))
        assertEquals(CompletionPolicy.SAVE_LATER, completedPolicyFirst.completionPolicy)
        assertEquals(5L, completedPolicyFirst.reference!!.steps)

        val referenceFirst = Fixture()
        val original = SessionReference(ReferenceStatus.MISSING, null, referenceFirst.time + 4, "无法读取")
        referenceFirst.store.saveReference(referenceFirst.id, original)
        val completedReferenceFirst = referenceFirst.reopen().finalizeStoppedSession(referenceFirst.id,
            CompletionPolicy.SAVE_UPLOAD, original.copy(recordedAtMs = referenceFirst.time + 50))
        assertEquals(CompletionPolicy.SAVE_UPLOAD, completedReferenceFirst.completionPolicy)
        assertEquals(original, completedReferenceFirst.reference)
    }

    private inner class Fixture {
        val directory = temporary.newFolder().canonicalFile
        val journal = File(directory, "session.json")
        val time = 1_789_804_800_000L
        var failCommit = false
        fun reopen() = FreeLivingSessionStore(journal, { source, target ->
            if (failCommit) throw IOException("Injected commit failure")
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
        }, {})
        val store = reopen()
        val id: String

        init {
            val profile = PreparationSnapshot("atomic001", "11111111-1111-4111-8111-111111111111",
                RingPlacement.RIGHT_INDEX, PreparedRing("AA:BB:CC:DD:EE:17", "Atomic fixture"))
            val baseline = DeviceStartBaseline(HealthMessage.Status(false, 0, 0, 0, 0), emptyList(), time)
            id = store.requestStart(profile, time, "Asia/Shanghai", baseline).sessionId
            val record = HealthMessage.ListItem(17, 20, 1, 100, time)
            val started = HealthMessage.Status(true, 20, 1, 0, 17)
            store.confirmStart(id, profile.ring!!.address, started, time + 1,
                recordEvidence = DeviceRecordEvidence(record, started, time + 1))
            store.requestStop(id, time + 2)
            val stopped = started.copy(collecting = false)
            store.confirmStop(id, profile.ring.address, stopped, time + 3,
                recordEvidence = DeviceRecordEvidence(record, stopped, time + 3))
        }
    }
}
