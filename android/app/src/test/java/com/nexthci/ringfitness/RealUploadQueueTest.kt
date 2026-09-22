package com.nexthci.ringfitness

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
import java.util.concurrent.atomic.AtomicReference

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

    @Test fun reopeningKeepsAutomaticNetworkRetryAndStopsAfterFiveAttempts() {
        val f = Fixture()
        f.queue.enqueue(f.id, link, false)
        f.failUpload = true
        assertTrue(f.queue.run(f.id))
        val queued = f.queue.task(f.id)
        assertEquals("sending", queued!!.state)
        assertTrue(f.openQueue().restore(link))
        assertEquals(queued, f.queue.task(f.id))
        assertEquals(1, f.requests)
        repeat(3) { assertTrue(f.openQueue().run(f.id)) }
        assertFalse(f.openQueue().run(f.id))
        val failed = f.queue.task(f.id)
        assertEquals("failed", failed!!.state)
        assertEquals(RealUploadQueue.MAX_AUTOMATIC_ATTEMPTS, f.store.read()!!.transfer.attempts)
        assertFalse(f.openQueue().restore(link))
        assertTrue(f.queue.enqueue(f.id, link, true))
        // The journal still says failed until a worker starts this already requested retry.
        assertEquals(SessionTransferStatus.FAILED, f.store.read()!!.transfer.status)
        assertTrue(f.openQueue().restore(""))
        assertEquals(listOf(f.id), f.queue.queuedIds())
        assertEquals(failed.archiveSha256, f.queue.task(f.id)!!.archiveSha256)
        assertEquals(RealUploadQueue.MAX_AUTOMATIC_ATTEMPTS, f.requests)
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

    @Test fun networkFailureRetriesFiveTimesThenManualRetryKeepsOriginalDestinationAndPackage() {
        val f = Fixture()
        assertTrue(f.queue.enqueue(f.id, link, false))
        f.failUpload = true
        repeat(RealUploadQueue.MAX_AUTOMATIC_ATTEMPTS - 1) { assertTrue(f.queue.run(f.id)) }
        assertFalse(f.queue.run(f.id))
        assertEquals(SessionTransferStatus.FAILED, f.store.read()!!.transfer.status)
        assertEquals("transport", f.queue.task(f.id)!!.failureStage)
        assertFalse(f.queue.needsLocalReview(f.id))
        assertFalse(f.queue.enqueue(f.id, link, false))
        val original = f.queue.task(f.id)!!
        f.failUpload = false
        assertTrue(f.queue.enqueue(f.id, "https://cloud.tsinghua.edu.cn/u/d/another/", true))
        assertFalse(f.queue.run(f.id))
        assertEquals(List(RealUploadQueue.MAX_AUTOMATIC_ATTEMPTS + 1) { link }, f.destinations)
        assertEquals(original.archiveSha256, f.queue.task(f.id)!!.archiveSha256)
        assertEquals(RealUploadQueue.MAX_AUTOMATIC_ATTEMPTS + 1, f.store.read()!!.transfer.attempts)
        assertEquals(SessionTransferStatus.COMPLETE, f.store.read()!!.transfer.status)
        assertFalse(f.queue.enqueue(f.id, link, true))
        assertFalse(f.queue.run(f.id))
        assertEquals(RealUploadQueue.MAX_AUTOMATIC_ATTEMPTS + 1, f.requests)
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

    @Test fun repeatedCancellationBeforeHttpDoesNotConsumeNetworkAttempts() {
        val f = Fixture()
        f.queue.enqueue(f.id, link, false)

        repeat(RealUploadQueue.MAX_AUTOMATIC_ATTEMPTS + 2) {
            var cancellationChecks = 0
            assertTrue(f.openQueue().run(f.id) { ++cancellationChecks >= 2 })
            assertEquals(0, f.requests)
            assertEquals(0, f.store.read()!!.transfer.attempts)
            assertEquals(SessionTransferStatus.PENDING, f.store.read()!!.transfer.status)
            assertEquals("queued", f.queue.task(f.id)!!.state)
        }

        assertFalse(f.openQueue().run(f.id))
        assertEquals(1, f.requests)
        assertEquals(1, f.store.read()!!.transfer.attempts)
        assertEquals(SessionTransferStatus.COMPLETE, f.store.read()!!.transfer.status)
    }

    @Test fun cancellationInsideTransportBeforeFirstHttpDoesNotConsumeNetworkAttempts() {
        val f = Fixture()
        f.queue.enqueue(f.id, link, false)
        f.checkCancellationBeforeDispatch = true
        var cancellationChecks = 0

        assertTrue(f.queue.run(f.id) { ++cancellationChecks >= 3 })
        assertEquals(0, f.requests)
        assertEquals(0, f.store.read()!!.transfer.attempts)
        assertEquals(SessionTransferStatus.PENDING, f.store.read()!!.transfer.status)
        assertEquals("queued", f.queue.task(f.id)!!.state)

        f.checkCancellationBeforeDispatch = false
        assertFalse(f.openQueue().run(f.id))
        assertEquals(1, f.requests)
        assertEquals(1, f.store.read()!!.transfer.attempts)
        assertEquals(SessionTransferStatus.COMPLETE, f.store.read()!!.transfer.status)
    }

    @Test fun processExitDuringFreezeReleasesUndispatchedClaimOnReopen() {
        val f = Fixture()
        f.queue.enqueue(f.id, link, false)
        var exitDuringFreeze = true
        f.duringFreeze = { if (exitDuringFreeze) throw SimulatedProcessExit() }

        assertThrows(SimulatedProcessExit::class.java) { f.queue.run(f.id) }
        assertEquals(0, f.requests)
        assertEquals(1, f.store.read()!!.transfer.attempts)
        assertEquals(SessionTransferStatus.TRANSFERRING, f.store.read()!!.transfer.status)
        assertEquals("queued", f.queue.task(f.id)!!.state)

        exitDuringFreeze = false
        assertFalse(f.openQueue().run(f.id))
        assertEquals(1, f.requests)
        assertEquals(1, f.store.read()!!.transfer.attempts)
        assertEquals(SessionTransferStatus.COMPLETE, f.store.read()!!.transfer.status)
    }

    @Test fun legacyQueuedTaskWithUnknownDispatchStopsBeforeReplay() {
        val f = Fixture()
        f.queue.enqueue(f.id, link, false)
        f.failUpload = true
        assertTrue(f.queue.run(f.id))
        val taskFile = File(f.directory, "upload-tasks/${f.id}.json")
        val legacy = JsonParser.parseString(taskFile.readText()).asJsonObject
        legacy.addProperty("version", 1)
        legacy.addProperty("state", "queued")
        legacy.remove("dispatchStarted")
        legacy.remove("payloadStarted")
        taskFile.writeText(legacy.toString())

        f.failUpload = false
        assertFalse(f.openQueue().run(f.id))
        assertEquals(1, f.requests)
        assertEquals(1, f.store.read()!!.transfer.attempts)
        assertEquals(SessionTransferStatus.FAILED, f.store.read()!!.transfer.status)
        assertEquals(3, f.queue.task(f.id)!!.version)
        assertEquals(true, f.queue.task(f.id)!!.dispatchStarted)
        assertEquals(true, f.queue.task(f.id)!!.payloadStarted)
        assertEquals("outcome", f.queue.task(f.id)!!.failureStage)
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

    @Test fun firstReceiptTaskSyncFailureCompletesLocallyAfterReopenWithoutUploadingTwice() {
        val f = Fixture()
        f.queue.enqueue(f.id, link, false)
        val original = f.archive.readBytes()
        var failures = 0
        f.taskSync = { directory ->
            val file = File(directory, "${f.id}.json")
            if (directory.name == "upload-tasks" && file.isFile && failures == 0 &&
                JsonParser.parseString(file.readText()).asJsonObject.has("receipt")) {
                failures++
                throw IOException("Injected first receipt directory sync failure")
            }
        }

        assertTrue("A validated response can be completed locally", f.queue.run(f.id))
        assertEquals(1, failures)
        assertNotNull(f.queue.task(f.id)!!.receipt)
        assertFalse(f.queue.needsLocalReview(f.id))
        assertEquals(1, f.requests)
        assertEquals(SessionTransferStatus.TRANSFERRING, f.store.read()!!.transfer.status)
        assertTrue(f.openQueue().restore(link))
        assertFalse(f.openQueue().run(f.id))
        assertEquals(SessionTransferStatus.COMPLETE, f.store.read()!!.transfer.status)
        assertEquals(1, f.requests)
        assertArrayEquals(original, f.archive.readBytes())
    }

    @Test fun serverCommitWithLostReceiptStopsAutomaticReplayAndRequiresReview() {
        val f = Fixture()
        f.queue.enqueue(f.id, link, false)
        f.uncertainAfterServerCommit = true

        assertFalse(f.queue.run(f.id))
        assertEquals(1, f.requests)
        assertEquals(SessionTransferStatus.FAILED, f.store.read()!!.transfer.status)
        assertEquals("failed", f.queue.task(f.id)!!.state)
        assertEquals("outcome", f.queue.task(f.id)!!.failureStage)
        assertTrue(f.queue.needsLocalReview(f.id))

        assertFalse(f.openQueue().restore(link))
        assertFalse(f.openQueue().enqueue(f.id, link, true))
        assertFalse(f.openQueue().run(f.id))
        assertEquals(1, f.requests)
    }

    @Test fun processExitAfterPayloadStartsNeverReplaysThePost() {
        val f = Fixture()
        f.queue.enqueue(f.id, link, false)
        f.exitAfterPayloadStarted = true

        assertThrows(SimulatedProcessExit::class.java) { f.queue.run(f.id) }
        assertEquals(1, f.requests)
        assertEquals(true, f.queue.task(f.id)!!.payloadStarted)
        assertEquals(SessionTransferStatus.TRANSFERRING, f.store.read()!!.transfer.status)

        f.exitAfterPayloadStarted = false
        assertFalse(f.openQueue().run(f.id))
        assertEquals(1, f.requests)
        assertEquals("failed", f.queue.task(f.id)!!.state)
        assertEquals("outcome", f.queue.task(f.id)!!.failureStage)
        assertEquals(SessionTransferStatus.FAILED, f.store.read()!!.transfer.status)
        assertTrue(f.queue.needsLocalReview(f.id))
    }

    @Test fun processExitAfterDispatchButBeforePayloadCanResume() {
        val f = Fixture()
        f.queue.enqueue(f.id, link, false)
        f.exitAfterDispatchBeforePayload = true

        assertThrows(SimulatedProcessExit::class.java) { f.queue.run(f.id) }
        assertEquals(1, f.requests)
        assertEquals(false, f.queue.task(f.id)!!.payloadStarted)

        f.exitAfterDispatchBeforePayload = false
        assertFalse(f.openQueue().run(f.id))
        assertEquals(2, f.requests)
        assertEquals(SessionTransferStatus.COMPLETE, f.store.read()!!.transfer.status)
    }

    @Test fun explicitRetryableResponseClearsPayloadMarkerAndResumesAfterReopen() {
        val f = Fixture()
        f.queue.enqueue(f.id, link, false)
        f.retryableAfterPayload = true

        assertTrue(f.queue.run(f.id))
        assertEquals(1, f.requests)
        assertEquals(false, f.queue.task(f.id)!!.payloadStarted)
        assertEquals("transport", f.queue.task(f.id)!!.failureStage)

        f.retryableAfterPayload = false
        assertFalse(f.openQueue().run(f.id))
        assertEquals(2, f.requests)
        assertEquals(SessionTransferStatus.COMPLETE, f.store.read()!!.transfer.status)
    }

    @Test fun ambiguousVersionTwoTaskCannotBeReplayedByManualRetry() {
        val f = Fixture()
        f.queue.enqueue(f.id, link, false)
        val taskFile = File(f.directory, "upload-tasks/${f.id}.json")
        val legacy = JsonParser.parseString(taskFile.readText()).asJsonObject
        legacy.addProperty("version", 2)
        legacy.addProperty("state", "failed")
        legacy.addProperty("dispatchStarted", true)
        legacy.addProperty("failureStage", "transport")
        legacy.remove("payloadStarted")
        taskFile.writeText(legacy.toString())

        assertTrue(f.openQueue().needsLocalReview(f.id))
        assertFalse(f.openQueue().enqueue(f.id, link, true))
        assertEquals(0, f.requests)
        assertEquals("outcome", f.queue.task(f.id)!!.failureStage)
        assertEquals(SessionTransferStatus.FAILED, f.store.read()!!.transfer.status)
    }

    @Test fun restoreDuringAnActivePayloadDoesNotMisclassifyOrDuplicateIt() {
        val f = Fixture()
        f.queue.enqueue(f.id, link, false)
        val payloadStarted = CountDownLatch(1)
        val finishPayload = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        f.afterPayloadStart = {
            payloadStarted.countDown()
            check(finishPayload.await(10, TimeUnit.SECONDS))
        }
        val worker = Thread {
            try {
                f.queue.run(f.id)
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        worker.start()
        assertTrue(payloadStarted.await(10, TimeUnit.SECONDS))

        assertTrue(f.openQueue().restore(link))
        assertEquals("sending", f.queue.task(f.id)!!.state)
        assertEquals(true, f.queue.task(f.id)!!.payloadStarted)
        assertEquals(SessionTransferStatus.TRANSFERRING, f.store.read()!!.transfer.status)

        finishPayload.countDown()
        worker.join(10_000)
        assertFalse(worker.isAlive)
        assertNull(failure.get())
        assertEquals(1, f.requests)
        assertEquals(SessionTransferStatus.COMPLETE, f.store.read()!!.transfer.status)
    }

    @Test fun permanentlyRejectedDestinationDoesNotConsumeFiveAutomaticAttempts() {
        val f = Fixture()
        f.queue.enqueue(f.id, link, false)
        f.permanentDestinationFailure = true

        assertFalse(f.queue.run(f.id))
        assertEquals(1, f.requests)
        assertEquals(SessionTransferStatus.FAILED, f.store.read()!!.transfer.status)
        assertEquals("destination", f.queue.task(f.id)!!.failureStage)
        assertTrue(f.queue.needsLocalReview(f.id))
        assertFalse(f.openQueue().restore(link))
        assertFalse(f.openQueue().enqueue(f.id, link, true))
        assertEquals(1, f.requests)
    }

    @Test fun mismatchedPersistedReceiptFailsForReviewWithoutRepeatedUpload() {
        val f = Fixture()
        f.queue.enqueue(f.id, link, false)
        f.failAfterResponse = true
        assertTrue(f.queue.run(f.id))
        f.failJournal = false
        f.failAfterResponse = false
        val taskFile = File(f.directory, "upload-tasks/${f.id}.json")
        val task = JsonParser.parseString(taskFile.readText()).asJsonObject
        task.getAsJsonObject("receipt").addProperty("fileName", "ringfitness-session-${"0".repeat(36)}.zip")
        taskFile.writeText(task.toString())

        assertFalse(f.openQueue().run(f.id))
        assertEquals(1, f.requests)
        assertEquals("failed", f.queue.task(f.id)?.state)
        assertEquals("receipt", f.queue.task(f.id)?.failureStage)
        assertTrue(f.queue.needsLocalReview(f.id))
        assertEquals(SessionTransferStatus.FAILED, f.store.read(f.id)?.transfer?.status)
        assertFalse(f.openQueue().run(f.id))
        assertEquals(1, f.requests)
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

        assertTrue(f.queue.run(f.id))
        assertFalse(f.queue.needsLocalReview(f.id))
        assertEquals("transport", f.queue.task(f.id)!!.failureStage)
        assertEquals("sending", f.queue.task(f.id)!!.state)
        assertEquals(1, f.requests)
        f.failUpload = false
        assertFalse(f.openQueue().run(f.id))
        assertEquals(SessionTransferStatus.COMPLETE, f.store.read()!!.transfer.status)
    }

    @Test fun uploadClaimsTheReferenceBeforeFreezingTheResearchPackage() {
        val f = Fixture()
        f.queue.enqueue(f.id, link, false)
        f.duringFreeze = {
            assertEquals(1, f.store.read(f.id)!!.transfer.attempts)
            assertThrows(IllegalArgumentException::class.java) {
                FreeLivingSessionPackage(f.directory, f.store).reviseUnpublishedReference(f.id,
                    SessionReference(ReferenceStatus.VALID, 99, f.time + 20))
            }
        }

        assertFalse(f.queue.run(f.id))
        assertEquals(0L, f.store.read(f.id)!!.reference!!.steps)
        assertEquals(SessionTransferStatus.COMPLETE, f.store.read(f.id)!!.transfer.status)
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

    @Test fun walkingAndRunningUseIndependentArchivesAndUploadReceipts() {
        val f = Fixture()
        val walking = f.createSavedSession(SessionActivity.WALKING)
        val running = f.createSavedSession(SessionActivity.RUNNING)

        assertTrue(f.queue.enqueue(walking, link, false))
        assertTrue(f.queue.enqueue(running, link, false))
        assertFalse(f.queue.run(walking))
        assertFalse(f.queue.run(running))

        assertEquals(
            listOf(
                "ringfitness-session-walking-$walking.zip",
                "ringfitness-session-running-$running.zip",
            ),
            f.uploadedNames,
        )
        assertEquals(f.uploadedNames[0], f.queue.task(walking)?.receipt?.fileName)
        assertEquals(f.uploadedNames[1], f.queue.task(running)?.receipt?.fileName)
        assertEquals(SessionTransferStatus.COMPLETE, f.store.read(walking)?.transfer?.status)
        assertEquals(SessionTransferStatus.COMPLETE, f.store.read(running)?.transfer?.status)
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
        var checkCancellationBeforeDispatch = false
        var failAfterResponse = false
        var uncertainAfterServerCommit = false
        var permanentDestinationFailure = false
        var exitAfterPayloadStarted = false
        var exitAfterDispatchBeforePayload = false
        var retryableAfterPayload = false
        var afterPayloadStart: (() -> Unit)? = null
        var failFreeze = false
        var duringFreeze: (() -> Unit)? = null
        var taskSync: (File) -> Unit = {}
        val destinations = mutableListOf<String>()
        val uploadedHashes = mutableListOf<String>()
        val uploadedNames = mutableListOf<String>()
        init {
            id = createSavedSession()
            archive = archiveFor(id)
        }
        fun createSavedSession(activity: SessionActivity = SessionActivity.FREE_LIVING): String {
            val s = store.requestStart(profile, time, "Asia/Shanghai", activity = activity)
            val id = s.sessionId
            store.confirmStart(id, profile.ring!!.address, HealthMessage.Status(true, 20, 1, 0, 7), time + 1)
            store.requestStop(id, time + 2)
            store.confirmStop(id, profile.ring.address, HealthMessage.Status(false, 20, 1, 0, 7), time + 3)
            store.saveReference(id, SessionReference(ReferenceStatus.VALID, 0, time + 4))
            val raw = File(directory, "$id-fixture.rfbin").apply { writeText("Transport boundary fixture") }
            store.completeLocalData(id, listOf(SessionRawFile(raw.name, 7, raw.length(), sha(raw))), time + 5)
            return id
        }
        private fun archiveFor(id: String): File {
            val activity = requireNotNull(store.read(id)).activity
            val name = if (activity == SessionActivity.FREE_LIVING) {
                "ringfitness-session-$id.zip"
            } else {
                "ringfitness-session-${activity.wireValue}-$id.zip"
            }
            return File(directory, name).apply {
            if (!exists()) writeText("Frozen package transport fixture")
            }
        }
        val queue get() = openQueue()
        fun openQueue() = RealUploadQueue(directory, store, freeze = { session ->
            if (failFreeze) throw IOException("Injected package preparation failure")
            duringFreeze?.invoke()
            val file = archiveFor(session.sessionId)
            FrozenSessionPackage(file, sha(file), file.length(), session.sessionId)
        }, transport = SessionUploadTransport { destination, file, cancelled, onDispatch, onPayloadStart ->
            if (checkCancellationBeforeDispatch && cancelled()) throw InterruptedException()
            onDispatch()
            requests++; destinations += destination; uploadedHashes += sha(file); uploadedNames += file.name
            if (exitAfterDispatchBeforePayload) throw SimulatedProcessExit()
            if (interruptUpload) throw InterruptedException()
            if (permanentDestinationFailure) throw PermanentUploadException("Injected rejected destination")
            if (failUpload) throw IOException("Injected lost response")
            onPayloadStart()
            afterPayloadStart?.invoke()
            if (exitAfterPayloadStarted) throw SimulatedProcessExit()
            if (retryableAfterPayload) throw RetryableUploadException("Injected retryable response")
            if (uncertainAfterServerCommit) throw UploadOutcomeUncertainException("Injected lost receipt")
            if (failAfterResponse) failJournal = true
            RemoteSessionReceipt(file.name, "a".repeat(40), file.length())
        }, now = { time + 10 }, sync = { taskSync(it) })
    }
    private class SimulatedProcessExit : Error()
    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
}
