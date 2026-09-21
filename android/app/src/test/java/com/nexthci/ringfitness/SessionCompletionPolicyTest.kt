package com.nexthci.ringfitness

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SessionCompletionPolicyTest {
    @get:Rule val temporary = TemporaryFolder()
    private val link = "https://cloud.tsinghua.edu.cn/u/d/fixture/"

    @Test fun newStoppedSessionRetainsUnselectedChoiceUntilItIsSaved() {
        val f = Fixture()
        assertNull(f.store.read()!!.completionPolicy)
        f.store.setCompletionPolicy(f.id, CompletionPolicy.SAVE_LATER)
        assertEquals(CompletionPolicy.SAVE_LATER, f.reopen().read()!!.completionPolicy)
        assertNull(f.store.read()!!.reference)
    }

    @Test fun choiceAndDiscardRequireConfirmedStop() {
        val f = Fixture(stopped = false)
        assertThrows(IllegalArgumentException::class.java) { f.store.setCompletionPolicy(f.id, CompletionPolicy.SAVE_LATER) }
        assertThrows(IllegalArgumentException::class.java) { f.store.discardStoppedSession(f.id, f.time + 9) }
        assertTrue(f.reopen().read()!!.isPending)
    }

    @Test fun saveLaterSurvivesQueueReconstructionAndAllAutomaticEntrypoints() {
        val f = Fixture()
        f.store.setCompletionPolicy(f.id, CompletionPolicy.SAVE_LATER)
        f.complete()
        assertFalse(f.queue().restore(link))
        assertFalse(f.queue().enqueue(f.id, link, false))
        assertTrue(f.queue().queuedIds().isEmpty())
        assertFalse(f.queue().run(f.id))
        assertNull(f.queue().task(f.id))
        assertEquals(0, f.requests)
        assertEquals(0L, f.reopen().read()!!.reference!!.steps)
        assertThrows(IllegalArgumentException::class.java) { f.store.markTransferStarted(f.id) }
    }

    @Test fun saveLaterAlsoSuppressesAlreadyQueuedWork() {
        val f = Fixture(); f.complete()
        assertTrue(f.queue().enqueue(f.id, link, false))
        f.store.setCompletionPolicy(f.id, CompletionPolicy.SAVE_LATER)
        assertFalse(f.queue().restore(link))
        assertFalse(f.queue().run(f.id))
        assertTrue(f.queue().queuedIds().isEmpty())
        assertEquals(0, f.requests)
    }

    @Test fun explicitUploadPersistsAcrossExitAndRetainsTheFrozenManifest() {
        val f = Fixture(); f.complete()
        val original = f.store.manifestSnapshot(f.id)
        f.store.setCompletionPolicy(f.id, CompletionPolicy.SAVE_LATER)
        f.store.allowUpload(f.id)
        assertEquals(original, f.store.manifestSnapshot(f.id))
        assertTrue(f.queue().restore(link))
        assertFalse(f.queue().run(f.id))
        assertEquals(1, f.requests)
        assertEquals(SessionTransferStatus.COMPLETE, f.reopen().read()!!.transfer.status)
    }

    @Test fun archivedSaveLaterCanBeExplicitlyUploadedWithoutChangingCurrentSession() {
        val f = Fixture(); f.complete()
        f.store.setCompletionPolicy(f.id, CompletionPolicy.SAVE_LATER)
        val next = f.store.requestStart(f.profile, f.time + 20, "Asia/Shanghai")
        f.store.allowUpload(f.id)
        assertTrue(f.queue().restore(link))
        f.queue().run(f.id)
        assertEquals(next.sessionId, f.store.read()!!.sessionId)
        assertEquals(SessionTransferStatus.COMPLETE, f.store.read(f.id)!!.transfer.status)
    }

    @Test fun discardBeforeDownloadReleasesPendingAndRejectsLateCallbacks() {
        val f = Fixture()
        val cancelled = f.store.discardStoppedSession(f.id, f.time + 9)
        assertTrue(cancelled.isDiscarded)
        assertNull(f.reopen().readPending())
        assertThrows(IllegalArgumentException::class.java) {
            f.store.saveReference(f.id, SessionReference(ReferenceStatus.VALID, 0, f.time + 10))
        }
        assertThrows(IllegalArgumentException::class.java) { f.store.allowUpload(f.id) }
        assertThrows(IllegalArgumentException::class.java) { f.store.manifestSnapshot(f.id) }
        assertFalse(f.queue().restore(link))
        assertNotEquals(f.id, f.store.requestStart(f.profile, f.time + 20, "Asia/Shanghai").sessionId)
    }

    @Test fun discardDeletesOnlyTheTargetSessionWhitelistAndIsRepeatable() {
        val f = Fixture(); f.complete()
        f.queue().enqueue(f.id, link, false)
        val prefix = "${f.id}-ring-7"
        val related = listOf("$prefix.part", "$prefix.download.json", "$prefix.raw-evidence.json", "${f.id}.clock-sync.json")
            .map { File(f.directory, it).apply { writeText("fixture") } }
        val other = File(f.directory, "other-record.rfbin").apply { writeText("preserve") }
        val backups = File(f.directory, "device-backups").apply { mkdir() }
        val backup = File(backups, "fixture.json").apply { writeText("preserve") }
        val packageDir = File(f.directory, "packages/${f.id}").apply { mkdirs() }
        listOf(
            "ringfitness-session-${f.id}.zip",
            "ringfitness-session-walking-${f.id}.zip",
            "ringfitness-session-running-${f.id}.zip",
            "manifest.snapshot.json",
            "package.json",
        )
            .forEach { File(packageDir, it).writeText("fixture") }
        val obsoleteDir = File(packageDir.parentFile, ".obsolete-${f.id}-11111111-1111-4111-8111-111111111111")
            .apply { mkdirs() }
        listOf("ringfitness-session-walking-${f.id}.zip", "manifest.snapshot.json", "package.json")
            .forEach { File(obsoleteDir, it).writeText("fixture") }
        f.queue().discardSession(f.id, f.time + 9)
        f.store.cleanupDiscardedSession(f.id)
        assertTrue(related.none { it.exists() })
        assertFalse(f.raw.exists())
        assertFalse(packageDir.exists())
        assertFalse(obsoleteDir.exists())
        assertNull(f.queue().task(f.id))
        assertEquals("preserve", other.readText())
        assertEquals("preserve", backup.readText())
        assertTrue(f.store.read()!!.isDiscarded)
        assertNotEquals(f.id, f.store.requestStart(f.profile, f.time + 20, "Asia/Shanghai").sessionId)
        f.queue().discardSession(f.id, f.time + 9)
    }

    @Test fun failedCleanupKeepsDurableTombstoneAndRestorationRetriesIt() {
        val f = Fixture(); f.complete()
        val blocked = File(f.directory, "${f.id}-ring-7.part").apply { mkdir() }
        assertThrows(IllegalArgumentException::class.java) { f.queue().discardSession(f.id, f.time + 9) }
        assertTrue(f.reopen().read()!!.isDiscarded)
        assertNull(f.reopen().readPending())
        assertTrue(blocked.delete())
        assertFalse(f.queue().restore(link))
        assertFalse(f.raw.exists())
        assertEquals(0, f.requests)
    }

    @Test fun failedTombstoneCommitKeepsEveryRawByte() {
        val f = Fixture(); f.complete()
        val before = f.raw.readBytes()
        f.failJournal = true
        assertThrows(IOException::class.java) { f.queue().discardSession(f.id, f.time + 9) }
        assertArrayEquals(before, f.raw.readBytes())
        assertFalse(f.store.read()!!.isDiscarded)
    }

    @Test fun anAttemptedUploadCannotBeDiscardedOrPausedAsUnsent() {
        val f = Fixture(); f.complete()
        f.store.markTransferStarted(f.id)
        f.store.markTransferFailed(f.id)
        assertThrows(IllegalArgumentException::class.java) { f.queue().discardSession(f.id, f.time + 9) }
        assertThrows(IllegalArgumentException::class.java) { f.store.setCompletionPolicy(f.id, CompletionPolicy.SAVE_LATER) }
        assertTrue(f.raw.isFile)
    }

    @Test fun activeHttpPreventsDiscardAndRetainsTheReceipt() {
        val f = Fixture(); f.complete()
        val entered = CountDownLatch(1); val finish = CountDownLatch(1)
        val queue = f.queue { entered.countDown(); check(finish.await(5, TimeUnit.SECONDS)) }
        queue.enqueue(f.id, link, false)
        val worker = Thread { queue.run(f.id) }.apply { start() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            assertThrows(IllegalArgumentException::class.java) { f.store.discardStoppedSession(f.id, f.time + 9) }
            assertThrows(IllegalArgumentException::class.java) { queue.discardSession(f.id, f.time + 9) }
        } finally { finish.countDown(); worker.join(5_000) }
        assertFalse(worker.isAlive)
        assertEquals(SessionTransferStatus.COMPLETE, f.store.read()!!.transfer.status)
        assertTrue(f.raw.isFile)
    }

    @Test fun unknownTimeTombstoneOnlyMatchesTheOriginalConnection() {
        val f = Fixture(unknownClock = true)
        val owner = "22222222-2222-4222-8222-222222222222"
        f.store.discardStoppedSession(f.id, f.time + 9, owner, 4)
        assertTrue(f.store.isDiscardedDeviceRecord(f.profile.ring!!.address, f.record, owner, 4))
        assertFalse(f.store.isDiscardedDeviceRecord(f.profile.ring.address, f.record, owner, 5))
        assertFalse(f.store.isDiscardedDeviceRecord(f.profile.ring.address, f.record))
        assertTrue(f.store.hasUnresolvedDiscardedRecord(f.profile.ring.address, f.record))
    }

    @Test fun calibratedTombstoneRequiresTheCompleteRecordFingerprint() {
        val f = Fixture()
        f.store.discardStoppedSession(f.id, f.time + 9)
        assertTrue(f.store.isDiscardedDeviceRecord(f.profile.ring!!.address, f.record))
        assertFalse(f.store.isDiscardedDeviceRecord(f.profile.ring.address, f.record.copy(bytes = 21)))
        assertFalse(f.store.isDiscardedDeviceRecord(f.profile.ring.address, f.record.copy(uptimeMs = 101)))
        assertTrue(f.store.hasPreservedDeviceRecords(f.profile.ring.address, listOf(f.record)))
    }

    @Test fun legacyCompletedReadMigratesWithoutChangingManifest() {
        val f = Fixture(); f.complete()
        val before = f.store.manifestSnapshot(f.id)
        f.downgradeJournal()
        assertEquals(CompletionPolicy.SAVE_UPLOAD, f.reopen().read()!!.completionPolicy)
        val expected = before.deepCopy().apply {
            addProperty("stop_origin", StopOrigin.LEGACY_UNSPECIFIED.wireValue)
        }
        assertEquals(expected, f.reopen().manifestSnapshot(f.id))
    }

    @Test fun legacyPendingStopWithMissingReferenceRemainsRecoverable() {
        val f = Fixture(stopped = false)
        f.store.saveReference(f.id, SessionReference(ReferenceStatus.MISSING, null, f.time + 4, "读数丢失"))
        f.downgradeJournal()
        val restored = f.reopen().read()!!
        assertNull(restored.completionPolicy)
        assertEquals(ReferenceStatus.MISSING, restored.reference!!.status)
        assertTrue(restored.isPending)
    }

    private inner class Fixture(stopped: Boolean = true, unknownClock: Boolean = false) {
        val directory = temporary.newFolder().canonicalFile
        val time = 1_789_804_800_000L
        val profile = PreparationSnapshot("finish001", "11111111-1111-4111-8111-111111111111",
            RingPlacement.RIGHT_INDEX, PreparedRing("AA:BB:CC:DD:EE:07", "Completion fixture"))
        var failJournal = false
        fun reopen() = FreeLivingSessionStore(File(directory, "session.json"), { source, target ->
            if (failJournal) throw IOException("Injected commit failure")
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }, {})
        val store = reopen()
        val record = HealthMessage.ListItem(7, 20, 1, 100, if (unknownClock) 0 else time)
        val id: String
        lateinit var raw: File
        var requests = 0
        init {
            val baseline = DeviceStartBaseline(HealthMessage.Status(false, 0, 0, 0, 0), emptyList(), time)
            id = store.requestStart(profile, time, "Asia/Shanghai", baseline).sessionId
            val started = HealthMessage.Status(true, 20, 1, 0, 7)
            store.confirmStart(id, profile.ring!!.address, started, time + 1,
                recordEvidence = DeviceRecordEvidence(record, started, time + 1))
            store.requestStop(id, time + 2)
            if (stopped) {
                val stop = started.copy(collecting = false)
                store.confirmStop(id, profile.ring.address, stop, time + 3,
                    recordEvidence = DeviceRecordEvidence(record, stop, time + 3))
            }
        }
        fun complete() {
            store.saveReference(id, SessionReference(ReferenceStatus.VALID, 0, time + 4))
            raw = File(directory, "$id-ring-7.rfbin").apply { writeText("Completion fixture") }
            store.completeLocalData(id, listOf(SessionRawFile(raw.name, 7, raw.length(), hash(raw.readBytes()))), time + 5)
        }
        fun queue(onUpload: () -> Unit = {}) = RealUploadQueue(directory, reopen(), freeze = { session ->
            val archive = File(directory, "fixture-${session.sessionId}.zip").apply { writeText("fixture archive") }
            FrozenSessionPackage(archive, hash(archive.readBytes()), archive.length(), session.sessionId)
        }, transport = SessionUploadTransport { _, archive, _, onDispatch ->
            onDispatch()
            requests++; onUpload()
            RemoteSessionReceipt(archive.name, "a".repeat(40), archive.length())
        }, now = { time + 10 }, sync = {})
        fun downgradeJournal() {
            val journal = File(directory, "session.json")
            val envelope = JsonParser.parseString(journal.readText()).asJsonObject
            val session = envelope.getAsJsonObject("session")
            session.remove("completion_policy"); session.remove("discarded"); session.remove("start_abort")
            session.remove("reference_revisions"); session.remove("start_command_dispatch")
            session.remove("stop_observed_at_ms"); session.remove("stop_origin"); session.remove("stop_command_dispatch")
            session.getAsJsonObject("start_baseline")?.remove("unknown_time_start_evidence")
            envelope.addProperty("journal_version", 6)
            val payload = JsonObject().apply {
                add("session", session); add("archived_sessions", envelope.getAsJsonArray("archived_sessions"))
            }
            envelope.addProperty("sha256", hash(payload.toString().toByteArray()))
            journal.writeText(envelope.toString())
        }
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
