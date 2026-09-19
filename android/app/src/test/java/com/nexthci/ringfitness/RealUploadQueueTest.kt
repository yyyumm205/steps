package com.nexthci.ringfitness

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class RealUploadQueueTest {
    @get:Rule val temporary = TemporaryFolder()
    private val link = "https://cloud.tsinghua.edu.cn/u/d/fixture/"

    @Test fun reopeningCreatesMissingTasksWithoutUploadingOrRebindingAnExistingDestination() {
        val f = Fixture()
        assertTrue(f.openQueue().restore(link))
        assertTrue(f.openQueue().restore("https://cloud.tsinghua.edu.cn/u/d/another/"))
        assertEquals(listOf(f.id), f.queue.queuedIds())
        assertEquals(link, f.queue.task(f.id)!!.targetLink)
        assertEquals(SessionTransferStatus.PENDING, f.store.read()!!.transfer.status)
        assertEquals(0, f.requests)
        f.queue.run(f.id)
        assertFalse(f.openQueue().restore(link))
        assertEquals(1, f.requests)
    }

    @Test fun reopeningKeepsFailureUntilManualRetryAndRestoresThatRetryAfterAnotherExit() {
        val f = Fixture()
        f.queue.enqueue(f.id, link, false)
        f.failUpload = true
        f.queue.run(f.id)
        val failed = f.queue.task(f.id)
        assertFalse(f.openQueue().restore(link))
        assertEquals(failed, f.queue.task(f.id))
        assertEquals(1, f.requests)
        assertTrue(f.queue.enqueue(f.id, link, true))
        // The journal still says failed until a worker starts this already requested retry.
        assertEquals(SessionTransferStatus.FAILED, f.store.read()!!.transfer.status)
        assertTrue(f.openQueue().restore(""))
        assertEquals(listOf(f.id), f.queue.queuedIds())
        assertEquals(failed!!.archiveSha256, f.queue.task(f.id)!!.archiveSha256)
        assertEquals(1, f.requests)
    }

    @Test fun reopeningIsolatesDamagedTaskWhileRecoveringAnotherRecord() {
        val f = Fixture()
        f.queue.enqueue(f.id, link, false)
        val damaged = File(f.directory, "upload-tasks/${f.id}.json").apply { writeText("broken") }
        val other = f.createSavedSession()
        assertTrue(f.openQueue().restore(link))
        assertEquals(listOf(other), f.queue.queuedIds())
        assertEquals("broken", damaged.readText())
        assertEquals(SessionTransferStatus.FAILED, f.store.read(f.id)!!.transfer.status)
        assertEquals(0, f.requests)
    }

    @Test fun reopeningWithoutAnUploadConfigurationKeepsUnboundDataLocal() {
        val f = Fixture()
        assertFalse(f.openQueue().restore(""))
        assertNull(f.queue.task(f.id))
        assertEquals(SessionTransferStatus.PENDING, f.store.read()!!.transfer.status)
        assertEquals(0, f.requests)
    }

    @Test fun missingAttemptedTaskNeverRebindsEvenOnManualRetryAndDoesNotBlockAnotherSession() {
        for (failed in listOf(false, true)) {
            val f = Fixture()
            f.queue.enqueue(f.id, link, false)
            f.store.markTransferStarted(f.id)
            if (failed) f.store.markTransferFailed(f.id)
            check(File(f.directory, "upload-tasks/${f.id}.json").delete())
            val other = f.createSavedSession()
            val changedLink = "https://cloud.tsinghua.edu.cn/u/d/another/"

            assertTrue(f.openQueue().restore(changedLink))
            assertTrue(f.queue.needsLocalReview(f.id))
            assertNull(f.queue.task(f.id))
            assertEquals(listOf(other), f.queue.queuedIds())
            assertThrows(IllegalStateException::class.java) { f.queue.enqueue(f.id, changedLink, true) }
            assertEquals(1, f.store.read(f.id)!!.transfer.attempts)
            assertEquals(SessionTransferStatus.FAILED, f.store.read(f.id)!!.transfer.status)
            assertEquals(0L, f.store.read(f.id)!!.reference!!.steps)
            assertEquals(0, f.requests)
            assertNull(f.queue.task(f.id))
        }
    }

    @Test fun missingConfigurationPreservesPendingAndSendsNothing() {
        val f = Fixture()
        assertFalse(f.queue.enqueue(f.id, "", false))
        assertTrue(f.queue.queuedIds().isEmpty())
        assertEquals(0, f.requests)
        assertEquals(SessionTransferStatus.PENDING, f.store.read()!!.transfer.status)
    }

    @Test fun failureRequiresManualRetryAndKeepsOriginalDestinationAndPackage() {
        val f = Fixture()
        assertTrue(f.queue.enqueue(f.id, link, false))
        f.failUpload = true
        assertFalse(f.queue.run(f.id))
        assertEquals(SessionTransferStatus.FAILED, f.store.read()!!.transfer.status)
        assertEquals("transport", f.queue.task(f.id)!!.failureStage)
        assertFalse(f.queue.needsLocalReview(f.id))
        assertFalse(f.queue.enqueue(f.id, link, false))
        val original = f.queue.task(f.id)!!
        f.failUpload = false
        assertTrue(f.queue.enqueue(f.id, "https://cloud.tsinghua.edu.cn/u/d/another/", true))
        assertFalse(f.queue.run(f.id))
        assertEquals(listOf(link, link), f.destinations)
        assertEquals(original.archiveSha256, f.queue.task(f.id)!!.archiveSha256)
        assertEquals(2, f.store.read()!!.transfer.attempts)
        assertEquals(SessionTransferStatus.COMPLETE, f.store.read()!!.transfer.status)
        assertFalse(f.queue.enqueue(f.id, link, true))
        assertFalse(f.queue.run(f.id))
        assertEquals(2, f.requests)
    }

    @Test fun cancelledSendingResumesSameBytesAfterQueueReconstruction() {
        val f = Fixture()
        f.queue.enqueue(f.id, link, false)
        f.interruptUpload = true
        assertTrue(f.queue.run(f.id))
        assertEquals(listOf(f.id), f.queue.queuedIds())
        assertFalse(f.queue.needsLocalReview(f.id))
        f.interruptUpload = false
        val reopened = f.openQueue()
        assertFalse(reopened.run(f.id))
        assertEquals(1, f.uploadedHashes.distinct().size)
        assertEquals(SessionTransferStatus.COMPLETE, f.store.read()!!.transfer.status)
    }

    @Test fun persistedReceiptRecoversWithoutASecondHttpUpload() {
        val f = Fixture()
        f.queue.enqueue(f.id, link, false)
        // Fail only receipt commit: transport starts successfully before injecting the disk error.
        f.failAfterResponse = true
        assertTrue(f.queue.run(f.id))
        assertNotNull(f.queue.task(f.id)!!.receipt)
        assertEquals("receipt", f.queue.task(f.id)!!.failureStage)
        assertFalse(f.queue.needsLocalReview(f.id))
        assertEquals(SessionTransferStatus.TRANSFERRING, f.store.read()!!.transfer.status)
        f.failJournal = false
        f.failAfterResponse = false
        assertFalse(f.openQueue().run(f.id))
        assertEquals(1, f.requests)
        assertEquals(SessionTransferStatus.COMPLETE, f.store.read()!!.transfer.status)
    }

    @Test fun changedFrozenPackageIsRejectedBeforeHttpRetry() {
        val f = Fixture()
        f.queue.enqueue(f.id, link, false)
        f.failUpload = true
        f.queue.run(f.id)
        f.archive.appendText("different")
        f.queue.enqueue(f.id, link, true)
        f.queue.run(f.id)
        assertEquals(1, f.requests)
        assertEquals("failed", f.queue.task(f.id)!!.state)
        assertTrue(f.queue.needsLocalReview(f.id))
    }

    @Test fun malformedBoundTaskIsPreservedAndNeverRebound() {
        val f = Fixture()
        f.queue.enqueue(f.id, link, false)
        val file = File(f.directory, "upload-tasks/${f.id}.json")
        file.writeText("broken")
        assertThrows(RuntimeException::class.java) { f.queue.enqueue(f.id, link, true) }
        assertTrue(f.queue.needsLocalReview(f.id))
        assertEquals("broken", file.readText())
        assertEquals(0, f.requests)
    }

    @Test fun preparationFailureIsVisibleAndCanRetryWithoutLosingReference() {
        val f = Fixture()
        f.queue.enqueue(f.id, link, false)
        f.failFreeze = true
        assertFalse(f.queue.run(f.id))
        assertEquals(SessionTransferStatus.FAILED, f.store.read(f.id)!!.transfer.status)
        assertEquals("preparation", f.queue.task(f.id)!!.failureStage)
        assertTrue(f.queue.needsLocalReview(f.id))
        assertEquals(0, f.requests)
        assertEquals(0L, f.store.read(f.id)!!.reference!!.steps)
        f.failFreeze = false
        assertTrue(f.queue.enqueue(f.id, link, true))
        assertTrue(f.queue.needsLocalReview(f.id))
        assertFalse(f.queue.run(f.id))
        assertFalse(f.queue.needsLocalReview(f.id))
        assertEquals(SessionTransferStatus.COMPLETE, f.store.read(f.id)!!.transfer.status)
        assertEquals(1, f.requests)
    }

    @Test fun missingRawIsFlaggedBeforeHttpAndRetryKeepsTheOriginalBindingAndArchive() {
        val f = Fixture()
        f.queue.enqueue(f.id, link, false)
        val raw = File(f.directory, "${f.id}-fixture.rfbin")
        val originalRaw = raw.readBytes()
        val originalArchive = f.archive.readBytes()
        val second = f.createSavedSession()
        f.queue.enqueue(second, link, false)
        check(raw.delete())

        assertFalse(f.queue.run(f.id))
        assertEquals(0, f.requests)
        assertTrue(f.openQueue().needsLocalReview(f.id))
        assertEquals(SessionTransferStatus.PENDING, f.store.read(f.id)!!.transfer.status)
        assertFalse(raw.exists())
        assertEquals(listOf(second), f.queue.queuedIds())
        assertFalse(f.queue.run(second))
        assertEquals(SessionTransferStatus.COMPLETE, f.store.read(second)!!.transfer.status)

        assertTrue(f.queue.enqueue(f.id, "https://cloud.tsinghua.edu.cn/u/d/another/", true))
        assertTrue(f.queue.needsLocalReview(f.id))
        raw.writeBytes(originalRaw)
        assertTrue(f.queue.needsLocalReview(f.id))
        assertFalse(f.openQueue().run(f.id))
        assertFalse(f.queue.needsLocalReview(f.id))
        assertEquals(listOf(link, link), f.destinations)
        assertArrayEquals(originalArchive, f.archive.readBytes())
        assertArrayEquals(originalRaw, raw.readBytes())
    }

    @Test fun changedRawIsPreservedAndFlaggedWhileAnotherSessionCanUpload() {
        val f = Fixture()
        f.queue.enqueue(f.id, link, false)
        val originalArchive = f.archive.readBytes()
        val expectedRawHash = f.store.read(f.id)!!.localData!!.files.single().sha256
        val second = f.createSavedSession()
        f.queue.enqueue(second, link, false)
        val raw = File(f.directory, "${f.id}-fixture.rfbin")
        val changedRaw = raw.readBytes().also { it[0] = (it[0].toInt() xor 1).toByte() }
        raw.writeBytes(changedRaw)

        assertFalse(f.queue.run(f.id))
        assertEquals(0, f.requests)
        assertEquals("preparation", f.queue.task(f.id)!!.failureStage)
        assertTrue(f.openQueue().needsLocalReview(f.id))
        assertEquals(listOf(second), f.queue.queuedIds())
        assertFalse(f.queue.run(second))
        assertEquals(SessionTransferStatus.COMPLETE, f.store.read(second)!!.transfer.status)
        assertArrayEquals(changedRaw, raw.readBytes())
        assertArrayEquals(originalArchive, f.archive.readBytes())
        assertEquals(expectedRawHash, f.store.read(f.id)!!.localData!!.files.single().sha256)
        assertEquals(link, f.queue.task(f.id)!!.targetLink)
    }

    @Test fun successfulLocalRecheckClearsReviewEvenWhenTheNetworkAttemptFails() {
        val f = Fixture()
        f.queue.enqueue(f.id, link, false)
        f.failFreeze = true
        f.queue.run(f.id)
        assertTrue(f.queue.needsLocalReview(f.id))
        f.failFreeze = false
        f.failUpload = true
        f.queue.enqueue(f.id, link, true)
        assertTrue(f.queue.needsLocalReview(f.id))

        assertFalse(f.queue.run(f.id))
        assertFalse(f.queue.needsLocalReview(f.id))
        assertEquals("transport", f.queue.task(f.id)!!.failureStage)
        assertEquals(1, f.requests)
    }

    @Test fun damagedTaskDoesNotBlockAnotherSavedSessionOrRebindItsDestination() {
        val f = Fixture()
        f.queue.enqueue(f.id, link, false)
        val damaged = File(f.directory, "upload-tasks/${f.id}.json").apply { writeText("broken") }
        val second = f.createSavedSession()
        f.queue.enqueue(second, link, false)
        assertEquals(listOf(second), f.queue.queuedIds())
        assertEquals(SessionTransferStatus.FAILED, f.store.read(f.id)!!.transfer.status)
        assertFalse(f.queue.run(second))
        assertEquals(SessionTransferStatus.COMPLETE, f.store.read(second)!!.transfer.status)
        assertEquals("broken", damaged.readText())
        assertEquals(1, f.requests)
    }

    @Test fun damagedTaskAndMissingRawRemainIsolatedFromOtherSessions() {
        val f = Fixture()
        f.queue.enqueue(f.id, link, false)
        val damaged = File(f.directory, "upload-tasks/${f.id}.json").apply { writeText("broken") }
        val second = f.createSavedSession()
        f.queue.enqueue(second, link, false)
        check(File(f.directory, "${f.id}-fixture.rfbin").delete())
        assertEquals(listOf(second), f.queue.queuedIds())
        assertFalse(f.queue.run(second))
        assertEquals(SessionTransferStatus.COMPLETE, f.store.read(second)!!.transfer.status)
        assertEquals("broken", damaged.readText())
        assertEquals(1, f.requests)
    }

    private inner class Fixture {
        val directory = temporary.newFolder().canonicalFile
        var failJournal = false
        val store = FreeLivingSessionStore(File(directory, "session.json"), { source, destination ->
            if (failJournal) throw IOException("Injected journal commit failure")
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }, {})
        val time = 1_789_804_800_000L
        val profile = PreparationSnapshot("upload001", "11111111-1111-4111-8111-111111111111",
            RingPlacement.RIGHT_INDEX, PreparedRing("AA:BB:CC:DD:EE:07", "Upload fixture"))
        val id: String
        val archive: File
        var requests = 0
        var failUpload = false
        var interruptUpload = false
        var failAfterResponse = false
        var failFreeze = false
        val destinations = mutableListOf<String>()
        val uploadedHashes = mutableListOf<String>()
        init {
            id = createSavedSession()
            archive = archiveFor(id)
        }
        fun createSavedSession(): String {
            val s = store.requestStart(profile, time, "Asia/Shanghai")
            val id = s.sessionId
            store.confirmStart(id, profile.ring!!.address, HealthMessage.Status(true, 20, 1, 0, 7), time + 1)
            store.requestStop(id, time + 2)
            store.confirmStop(id, profile.ring.address, HealthMessage.Status(false, 20, 1, 0, 7), time + 3)
            store.saveReference(id, SessionReference(ReferenceStatus.VALID, 0, time + 4))
            val raw = File(directory, "$id-fixture.rfbin").apply { writeText("Transport boundary fixture") }
            store.completeLocalData(id, listOf(SessionRawFile(raw.name, 7, raw.length(), sha(raw))), time + 5)
            return id
        }
        private fun archiveFor(id: String) = File(directory, "ringfitness-session-$id.zip").apply {
            if (!exists()) writeText("Frozen package transport fixture")
        }
        val queue get() = openQueue()
        fun openQueue() = RealUploadQueue(directory, store, freeze = { session ->
            if (failFreeze) throw IOException("Injected package preparation failure")
            val file = archiveFor(session.sessionId)
            FrozenSessionPackage(file, sha(file), file.length(), session.sessionId)
        }, transport = SessionUploadTransport { destination, file, _ ->
            requests++; destinations += destination; uploadedHashes += sha(file)
            if (interruptUpload) throw InterruptedException()
            if (failUpload) throw IOException("Injected lost response")
            if (failAfterResponse) failJournal = true
            RemoteSessionReceipt(file.name, "a".repeat(40), file.length())
        }, now = { time + 10 }, sync = {})
    }
    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
}
