package com.nexthci.ringfitness

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.PriorityQueue

class DemoFlowControllerTest {
    @get:Rule val temporary = TemporaryFolder()
    private data class Task(val time: Long, val order: Long, val action: () -> Unit)

    @Test fun normalFlowPersistsOneReferenceBeforeSyntheticTransferAndRecoversAfterRestart() {
        val f = Fixture()
        f.begin()
        val id = f.flow.state.session!!.sessionId
        f.stop()
        f.flow.saveReference("562")
        assertEquals(CollectionPage.SAVING, f.flow.state.page)
        assertNull(f.store.read()!!.reference)
        f.advance(350)
        assertEquals(CollectionPage.DOWNLOADING, f.flow.state.page)
        assertEquals(562L, f.store.read()!!.reference!!.steps)
        assertNull(f.store.read()!!.localData)
        f.drain()
        assertEquals(CollectionPage.COMPLETE, f.flow.state.page)
        val saved = f.store.read()!!
        assertEquals(id, saved.sessionId)
        assertEquals(SessionTransferStatus.COMPLETE, saved.transfer.status)
        assertTrue(saved.localData!!.files.single().simulated)
        assertTrue(saved.transfer.receipt!!.simulated)
        f.restart()
        assertEquals(CollectionPage.HOME, f.flow.state.page)
        assertEquals(562L, f.flow.state.savedSteps)
        assertEquals(id, f.flow.state.session!!.sessionId)
        assertTrue(f.flow.state.canStart)
        assertEquals(id, f.flow.state.records.single().sessionId)
    }

    @Test fun zeroMissingAndUnreliableRemainDistinctAcrossSeparateSessions() {
        val f = Fixture()
        f.complete("0")
        val zero = f.store.read()!!
        f.complete("", "missing", "无法读数")
        val missing = f.store.read()!!
        f.complete("125", "unreliable", "忘记清零")
        val unreliable = f.store.read()!!
        assertEquals(ReferenceStatus.VALID, zero.reference!!.status)
        assertEquals(0L, zero.reference.steps)
        assertEquals(ReferenceStatus.MISSING, missing.reference!!.status)
        assertNull(missing.reference.steps)
        assertEquals(ReferenceStatus.UNRELIABLE, unreliable.reference!!.status)
        assertEquals(125L, unreliable.reference.steps)
        assertEquals(3, f.store.listSessions().map { it.sessionId }.toSet().size)
    }

    @Test fun invalidInputDoesNotSaveOrStartTransfer() {
        val f = Fixture()
        f.begin(); f.stop()
        for (value in listOf("", "-1", "3.5", "9223372036854775808")) {
            f.flow.saveReference(value)
            assertEquals(CollectionPage.REFERENCE, f.flow.state.page)
            assertNotNull(f.flow.state.error)
            assertNull(f.store.read()!!.reference)
            assertNull(f.store.read()!!.localData)
        }
        f.flow.saveReference("", "missing", "")
        assertNull(f.store.read()!!.reference)
    }

    @Test fun repeatedSaveKeepsTheFirstConfirmedReferenceAndSingleReceipt() {
        val f = Fixture()
        f.begin(); f.stop()
        f.flow.saveReference("562")
        f.flow.saveReference("999")
        f.drain()
        assertEquals(562L, f.store.read()!!.reference!!.steps)
        assertEquals(1, f.directory.listFiles()!!.count { it.name.endsWith("simulated-receipt.txt") })
        f.flow.retryUpload(f.store.read()!!.sessionId)
        f.drain()
        assertEquals(1, f.store.read()!!.transfer.attempts)
    }

    @Test fun referenceSaveFailureKeepsSameSessionAndNeverStartsDownload() {
        val f = Fixture()
        f.begin(); f.stop()
        val id = f.store.read()!!.sessionId
        f.flow.setFault(FlowTestFault.SAVE_FAILURE)
        f.flow.saveReference("562")
        f.drain()
        assertEquals(CollectionPage.REFERENCE, f.flow.state.page)
        assertNull(f.store.read()!!.reference)
        assertNull(f.store.read()!!.localData)
        f.flow.saveReference("562")
        f.drain()
        assertEquals(id, f.store.read()!!.sessionId)
        assertEquals(562L, f.store.read()!!.reference!!.steps)
    }

    @Test fun failedDownloadRetainsReferenceAndBlocksNewCaptureUntilRetried() {
        val f = Fixture()
        f.begin(); f.stop()
        f.flow.setFault(FlowTestFault.DOWNLOAD_FAILURE)
        f.flow.saveReference("42")
        f.drain()
        val id = f.store.read()!!.sessionId
        assertEquals(CollectionPage.ERROR, f.flow.state.page)
        assertEquals(42L, f.store.read()!!.reference!!.steps)
        assertFalse(f.flow.state.canStart)
        f.flow.start()
        assertEquals(id, f.store.read()!!.sessionId)
        f.flow.retry(); f.drain()
        assertEquals(CollectionPage.COMPLETE, f.flow.state.page)
    }

    @Test fun failedUploadAllowsAnotherSessionAndRetryUsesTheArchivedIdentity() {
        val f = Fixture()
        f.begin(); f.stop()
        f.flow.setFault(FlowTestFault.UPLOAD_FAILURE)
        f.flow.saveReference("1"); f.drain()
        val first = f.store.read()!!
        assertEquals(SessionTransferStatus.FAILED, first.transfer.status)
        assertTrue(f.flow.state.canStart)
        f.begin()
        val secondId = f.store.read()!!.sessionId
        assertNotEquals(first.sessionId, secondId)
        f.flow.retryUpload(first.sessionId)
        f.drain()
        assertEquals(CollectionPage.COLLECTING, f.flow.state.page)
        assertEquals(secondId, f.store.read()!!.sessionId)
        assertEquals(SessionTransferStatus.COMPLETE, f.store.read(first.sessionId)!!.transfer.status)
        assertNull(f.store.read()!!.reference)
    }

    @Test fun startTimeoutRecoveryQueriesDurableDemoEvidenceWithoutRestartingCapture() {
        val f = Fixture()
        f.flow.setFault(FlowTestFault.START_TIMEOUT)
        f.flow.start(); f.drain()
        assertEquals(CollectionPage.RECOVERY, f.flow.state.page)
        val deviceBefore = File(f.directory, "device.json").readBytes()
        val id = f.store.read()!!.sessionId
        f.flow.retry(); f.drain()
        assertEquals(CollectionPage.COLLECTING, f.flow.state.page)
        assertEquals(id, f.store.read()!!.sessionId)
        assertArrayEquals(deviceBefore, File(f.directory, "device.json").readBytes())
        f.stop()
    }

    @Test fun uncertainStopCanPreserveAnUnreliableReadingBeforeRecovery() {
        val f = Fixture()
        f.begin()
        f.flow.setFault(FlowTestFault.STOP_TIMEOUT)
        f.flow.stop(); f.drain()
        assertEquals(FreeLivingSessionPhase.STOP_REQUESTED, f.store.read()!!.phase)
        val stoppedDevice = File(f.directory, "device.json").readBytes()
        f.flow.enterReference()
        f.flow.saveReference("32", "valid")
        assertNull(f.store.read()!!.reference)
        f.flow.saveReference("32", "unreliable", "结束时未收到确认")
        f.drain()
        assertEquals(32L, f.store.read()!!.reference!!.steps)
        assertNull(f.store.read()!!.stopConfirmedAtMs)
        assertNull(f.store.read()!!.endedAtMs)
        f.flow.retry(); f.drain()
        assertArrayEquals(stoppedDevice, File(f.directory, "device.json").readBytes())
        assertEquals(CollectionPage.COMPLETE, f.flow.state.page)
        assertEquals(ReferenceStatus.UNRELIABLE, f.store.read()!!.reference!!.status)
    }

    @Test fun restartWhileCollectingKeepsSessionAndRequiresAnExplicitStop() {
        val f = Fixture()
        f.begin()
        val id = f.store.read()!!.sessionId
        val before = File(f.directory, "device.json").readBytes()
        f.restart()
        assertEquals(CollectionPage.COLLECTING, f.flow.state.page)
        assertArrayEquals(before, File(f.directory, "device.json").readBytes())
        assertEquals(id, f.store.read()!!.sessionId)
        f.stop()
        assertNotNull(f.store.read()!!.stopConfirmedAtMs)
    }

    @Test fun restartDuringDownloadContinuesFromTheSavedReference() {
        val f = Fixture()
        f.begin(); f.stop(); f.flow.saveReference("73"); f.advance(350)
        val id = f.store.read()!!.sessionId
        f.restart(); f.drain()
        assertEquals(id, f.store.read()!!.sessionId)
        assertEquals(73L, f.flow.state.savedSteps)
        assertEquals(CollectionPage.COMPLETE, f.flow.state.page)
    }

    @Test fun restartDuringUploadProducesOneReceiptForTheSameRecord() {
        val f = Fixture()
        f.begin(); f.stop(); f.flow.saveReference("73"); f.advance(1550)
        assertEquals(CollectionPage.UPLOADING, f.flow.state.page)
        val id = f.store.read()!!.sessionId
        f.restart(); f.drain()
        assertEquals(id, f.store.read()!!.sessionId)
        assertEquals(CollectionPage.COMPLETE, f.flow.state.page)
        assertEquals(1, f.directory.listFiles()!!.count { it.name.endsWith("simulated-receipt.txt") })
    }

    @Test fun disconnectDuringDownloadKeepsReferenceAndReconnectResumes() {
        val f = Fixture()
        f.begin(); f.stop(); f.flow.saveReference("15"); f.advance(350)
        f.flow.disconnect(); f.drain()
        assertEquals(15L, f.store.read()!!.reference!!.steps)
        assertNull(f.store.read()!!.localData)
        f.flow.reconnect(); f.drain()
        assertEquals(CollectionPage.COMPLETE, f.flow.state.page)
    }

    @Test fun leavingPagesAndRemovingObserversDoesNotCancelWork() {
        val f = Fixture()
        var events = 0
        val subscription = f.flow.observe { events++ }
        f.begin(); f.stop(); f.flow.saveReference("8")
        subscription.close()
        val previousEvents = events
        f.flow.home(); f.drain()
        assertEquals(previousEvents, events)
        assertEquals(SessionTransferStatus.COMPLETE, f.store.read()!!.transfer.status)
        assertEquals(8L, f.store.read()!!.reference!!.steps)
    }

    @Test fun deviceRecordFromAnotherSessionIsRetainedWithoutCommands() {
        val f = Fixture()
        f.begin()
        val other = DemoDeviceRecord("another-session", 12, f.now)
        DemoDeviceStore(File(f.directory, "device.json")) {}.save(other)
        val before = File(f.directory, "device.json").readBytes()
        f.restart()
        assertEquals(CollectionPage.RECOVERY, f.flow.state.page)
        f.flow.stop(); f.drain()
        assertArrayEquals(before, File(f.directory, "device.json").readBytes())
        assertNull(f.store.read()!!.stopRequestedAtMs)
    }

    @Test fun testSpaceLeavesSiblingProductionFilesUntouched() {
        val parent = temporary.newFolder()
        val formal = File(parent, "preparation-profile").apply { writeText("production sentinel") }
        val f = Fixture(parent)
        f.complete("562")
        assertEquals("production sentinel", formal.readText())
        assertTrue(f.directory.walkTopDown().filter { it.isFile }.all { it.canonicalFile.parentFile == f.directory })
    }

    @Test fun restartAtStartAndStopRequestsAdoptsOnlyTheMatchingSyntheticEvidence() {
        val f = Fixture()
        f.flow.setFault(FlowTestFault.START_TIMEOUT)
        f.flow.start(); f.advance(600)
        assertEquals(FreeLivingSessionPhase.START_REQUESTED, f.store.read()!!.phase)
        val id = f.store.read()!!.sessionId
        val startedDevice = File(f.directory, "device.json").readBytes()
        f.restart()
        assertEquals(CollectionPage.COLLECTING, f.flow.state.page)
        assertArrayEquals(startedDevice, File(f.directory, "device.json").readBytes())
        f.flow.setFault(FlowTestFault.STOP_TIMEOUT)
        f.flow.stop()
        assertEquals(FreeLivingSessionPhase.STOP_REQUESTED, f.store.read()!!.phase)
        val stoppedDevice = File(f.directory, "device.json").readBytes()
        f.restart()
        assertEquals(CollectionPage.REFERENCE, f.flow.state.page)
        assertEquals(id, f.store.read()!!.sessionId)
        assertArrayEquals(stoppedDevice, File(f.directory, "device.json").readBytes())
    }

    @Test fun tamperedLocalFilesBlockNewCaptureAndPreserveTheDeviceRecord() {
        val f = Fixture()
        f.complete("562")
        val original = f.store.read()!!
        val before = File(f.directory, "device.json").readBytes()
        File(f.directory, original.localData!!.files.single().fileName).appendText("corrupt")
        f.flow.start()
        assertEquals(CollectionPage.ERROR, f.flow.state.page)
        assertEquals(original.sessionId, f.store.read()!!.sessionId)
        assertArrayEquals(before, File(f.directory, "device.json").readBytes())
    }

    @Test fun homeDuringPendingConfirmationDoesNotIssueASecondStart() {
        val f = Fixture()
        f.flow.start()
        f.flow.home()
        f.flow.start()
        f.drain()
        assertEquals(1, f.store.listSessions().size)
        assertEquals(CollectionPage.COLLECTING, f.flow.state.page)
    }

    @Test fun uploadJournalFailureCanRetryInTheSameProcessWithoutAnInMemoryLock() {
        val f = Fixture()
        f.begin(); f.stop()
        f.failNextUploadCommit = true
        f.flow.saveReference("81"); f.drain()
        assertEquals(CollectionPage.ERROR, f.flow.state.page)
        assertEquals(SessionTransferStatus.PENDING, f.store.read()!!.transfer.status)
        assertNotNull(f.store.read()!!.localData)
        f.flow.retryUpload(f.store.read()!!.sessionId); f.drain()
        assertEquals(CollectionPage.COMPLETE, f.flow.state.page)
        assertEquals(SessionTransferStatus.COMPLETE, f.store.read()!!.transfer.status)
    }

    @Test fun schedulerRejectionsLeaveSaveDownloadAndUploadRetriable() {
        val f = Fixture()
        f.begin(); f.stop()
        f.rejectNextDelay = 350L
        f.flow.saveReference("12")
        assertNull(f.store.read()!!.reference)
        f.rejectNextDelay = 1200L
        f.flow.saveReference("12"); f.drain()
        assertNotNull(f.store.read()!!.reference)
        assertNull(f.store.read()!!.localData)
        f.rejectUploadScheduling = true
        f.flow.retry(); f.drain()
        assertNotNull(f.store.read()!!.localData)
        assertEquals(SessionTransferStatus.TRANSFERRING, f.store.read()!!.transfer.status)
        f.flow.retry(); f.drain()
        assertEquals(CollectionPage.COMPLETE, f.flow.state.page)
    }

    @Test fun switchingBackToValidReadingDoesNotSaveTheHiddenReason() {
        val f = Fixture()
        f.begin(); f.stop()
        f.flow.saveReference("13", "valid", "不再适用的异常草稿")
        f.drain()
        assertEquals(CollectionPage.COMPLETE, f.flow.state.page)
        assertEquals(13L, f.store.read()!!.reference!!.steps)
        assertNull(f.store.read()!!.reference!!.reason)
    }

    @Test fun restartResumesArchivedPendingUploadWithoutReplacingTheNewCollectingPage() {
        val f = Fixture()
        f.uploadExtraDelayMs = 5000
        f.begin(); f.stop(); f.flow.saveReference("41"); f.advance(1550)
        val firstId = f.store.read()!!.sessionId
        assertEquals(SessionTransferStatus.TRANSFERRING, f.store.read()!!.transfer.status)
        f.flow.home(); f.flow.start(); f.advance(1200)
        val secondId = f.store.read()!!.sessionId
        assertNotEquals(firstId, secondId)
        assertEquals(CollectionPage.COLLECTING, f.flow.state.page)
        assertEquals(SessionTransferStatus.TRANSFERRING, f.store.read(firstId)!!.transfer.status)
        // Lose the process after the second capture starts, before the first receipt arrives.
        f.restart(); f.drain()
        assertEquals(CollectionPage.COLLECTING, f.flow.state.page)
        assertEquals(secondId, f.store.read()!!.sessionId)
        assertEquals(SessionTransferStatus.COMPLETE, f.store.read(firstId)!!.transfer.status)
    }

    private inner class Fixture(parent: File = temporary.newFolder()) {
        val directory = File(parent, "collection-demo")
        var now = 1_789_804_800_000L
        var failNextUploadCommit = false
        var rejectNextDelay: Long? = null
        var rejectUploadScheduling = false
        var uploadExtraDelayMs = 0L
        private var order = 0L
        private val tasks = PriorityQueue<Task>(compareBy<Task> { it.time }.thenBy { it.order })
        private val clock = object : CaptureClock {
            override fun nowEpochMs() = now
            override fun timeZoneId() = "Asia/Shanghai"
        }
        var flow = create()
        val store get() = FreeLivingSessionStore(File(directory, "session.json"), { a, b ->
            Files.move(a.toPath(), b.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }, {})
        init { flow.initialize(); flow.register("preview001", RingPlacement.LEFT_INDEX) }

        private fun create() = DemoFlowController(directory,
            DemoFlowScheduler { delay, action ->
                if (rejectNextDelay == delay) {
                    rejectNextDelay = null
                    throw java.util.concurrent.RejectedExecutionException("test scheduling failure")
                }
                if (rejectUploadScheduling && delay == 1200L && store.read()?.transfer?.status == SessionTransferStatus.TRANSFERRING) {
                    rejectUploadScheduling = false
                    throw java.util.concurrent.RejectedExecutionException("test upload scheduling failure")
                }
                val extra = if (delay == 1200L && store.read()?.transfer?.status == SessionTransferStatus.TRANSFERRING) uploadExtraDelayMs else 0L
                tasks += Task(now + delay + extra, order++, action)
            }, clock, syncDirectory = {}, beforeSessionCommit = { source ->
                if (failNextUploadCommit && source.readText().contains("transferring")) {
                    failNextUploadCommit = false
                    throw IOException("test upload journal failure")
                }
            })
        fun begin() { flow.start(); drain(); assertEquals(CollectionPage.COLLECTING, flow.state.page) }
        fun stop() { flow.stop(); drain(); assertEquals(CollectionPage.REFERENCE, flow.state.page) }
        fun complete(steps: String, status: String = "valid", reason: String = "") {
            begin(); stop(); flow.saveReference(steps, status, reason); drain()
            assertEquals(CollectionPage.COMPLETE, flow.state.page)
        }
        fun restart() { tasks.clear(); flow = create(); flow.initialize() }
        fun advance(ms: Long) {
            val end = now + ms
            while (tasks.isNotEmpty() && tasks.peek().time <= end) {
                val task = tasks.remove(); now = task.time; task.action()
            }
            now = end
        }
        fun drain() {
            var count = 0
            while (tasks.isNotEmpty()) {
                check(count++ < 100) { "unexpected scheduled loop" }
                val task = tasks.remove(); now = task.time; task.action()
            }
        }
    }
}
