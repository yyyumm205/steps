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

    @Test fun confirmedStopRestoresTheFinishChoiceWithoutInventingAReferenceOrTransfer() {
        val f = Fixture()
        f.begin()
        f.stopAtFinish()
        val stopped = f.store.read()!!
        assertNotNull(stopped.stopConfirmedAtMs)
        assertNull(stopped.completionPolicy)
        assertNull(stopped.reference)
        assertNull(stopped.localData)
        f.restart(); f.drain()
        assertEquals(CollectionPage.FINISH, f.flow.state.page)
        assertEquals(stopped, f.store.read())
        f.flow.chooseFinish(false)
        assertEquals(CollectionPage.REFERENCE, f.flow.state.page)
        assertEquals(CompletionPolicy.SAVE_LATER, f.store.read()!!.completionPolicy)
        f.restart()
        assertEquals(CollectionPage.REFERENCE, f.flow.state.page)
        assertNull(f.store.read()!!.reference)
    }

    @Test fun deferredZeroSurvivesDownloadAndProcessRestartUntilExplicitUpload() {
        val f = Fixture()
        f.begin(SessionActivity.RUNNING); f.stopAtFinish()
        f.flow.chooseFinish(false)
        f.flow.saveReference("0"); f.advance(350)
        val id = f.store.read()!!.sessionId
        assertEquals(CompletionPolicy.SAVE_LATER, f.store.read()!!.completionPolicy)
        assertEquals(0L, f.store.read()!!.reference!!.steps)
        f.restart(); f.drain()
        val saved = f.store.read()!!
        assertEquals(id, saved.sessionId)
        assertEquals(SessionActivity.RUNNING, saved.activity)
        assertNotNull(saved.localData)
        assertEquals(0, saved.transfer.attempts)
        assertNull(saved.transfer.receipt)
        assertEquals(CollectionPage.COMPLETE, f.flow.state.page)
        assertTrue(f.flow.state.canStart)
        f.flow.home(); f.flow.reconnect(); f.restart(); f.drain()
        assertEquals(saved, f.store.read())
        assertTrue(f.flow.state.records.single().uploadDeferred)
        f.flow.retryUpload(id); f.drain()
        assertEquals(CompletionPolicy.SAVE_UPLOAD, f.store.read()!!.completionPolicy)
        assertEquals(SessionTransferStatus.COMPLETE, f.store.read()!!.transfer.status)
        assertEquals(1, f.store.read()!!.transfer.attempts)
        assertEquals(saved.reference, f.store.read()!!.reference)
        assertEquals(saved.localData, f.store.read()!!.localData)
    }

    @Test fun atomicFinalizationFailureLeavesNoHalfStateAndRestartCanRetry() {
        val f = Fixture()
        f.begin()
        f.stopAtFinish()
        f.flow.setFault(FlowTestFault.SAVE_FAILURE)
        f.flow.finalizeSession(false, "0", "valid", "")
        f.advance(350)
        assertNull(f.store.read()!!.completionPolicy)
        assertNull(f.store.read()!!.reference)
        assertEquals(CollectionPage.FINISH, f.flow.state.page)

        f.restart()
        assertEquals(CollectionPage.FINISH, f.flow.state.page)
        f.flow.finalizeSession(false, "0", "valid", "")
        f.drain()
        val restored = f.store.read()!!
        assertEquals(CompletionPolicy.SAVE_LATER, restored.completionPolicy)
        assertEquals(0L, restored.reference!!.steps)
        assertNotNull(restored.localData)
        assertEquals(0, restored.transfer.attempts)
    }

    @Test fun archivedDeferredSessionStaysLocalWhileTheNextSessionUploadsAndRestarts() {
        val f = Fixture()
        f.begin(); f.stopAtFinish(); f.flow.chooseFinish(false)
        f.flow.saveReference("8"); f.drain()
        val first = f.store.read()!!
        val firstRaw = File(f.directory, first.localData!!.files.single().fileName).readBytes()
        f.complete("23")
        val second = f.store.read()!!
        f.restart(); f.drain()
        assertEquals(first, f.store.read(first.sessionId))
        assertEquals(SessionTransferStatus.COMPLETE, second.transfer.status)
        assertEquals(second, f.store.read())
        f.flow.retryUpload(first.sessionId); f.drain()
        assertEquals(second, f.store.read())
        assertEquals(SessionTransferStatus.COMPLETE, f.store.read(first.sessionId)!!.transfer.status)
        assertArrayEquals(firstRaw, File(f.directory, first.localData.files.single().fileName).readBytes())
    }

    @Test fun everyNewSessionRequiresAFreshActivityChoiceAndFreezesItsOwnSelection() {
        val f = Fixture()
        assertNull(f.flow.state.selectedActivity)
        f.flow.start(); f.drain()
        assertNull(f.store.read())
        f.begin(SessionActivity.WALKING)
        assertNull(f.flow.state.selectedActivity)
        f.flow.selectActivity(SessionActivity.RUNNING)
        assertEquals(SessionActivity.WALKING, f.store.read()!!.activity)
        assertNull(f.flow.state.selectedActivity)
        f.stop(); f.flow.saveReference("12"); f.drain()
        val first = f.store.read()!!
        f.flow.home(); f.flow.start(); f.drain()
        assertEquals(first, f.store.read())
        assertEquals(1, f.store.listSessions().size)
        f.begin(SessionActivity.RUNNING)
        assertEquals(SessionActivity.RUNNING, f.store.read()!!.activity)
        assertEquals(SessionActivity.WALKING, f.store.read(first.sessionId)!!.activity)
        assertNull(f.flow.state.selectedActivity)
    }

    @Test fun discardNeedsNoReadingAndKeepsOtherSavedFilesAndReceiptAcrossRestart() {
        val f = Fixture()
        f.complete("73")
        val first = f.store.read()!!
        val preserved = f.directory.listFiles()!!.filter { it.name.startsWith(first.sessionId) }
            .associate { it.name to it.readBytes() }
        f.begin(SessionActivity.RUNNING); f.stopAtFinish()
        val discardedId = f.store.read()!!.sessionId
        f.flow.discardSession(); f.drain()
        assertEquals(CollectionPage.HOME, f.flow.state.page)
        assertTrue(f.flow.state.canStart)
        assertTrue(f.store.read(discardedId)!!.isDiscarded)
        assertNull(f.store.read(discardedId)!!.reference)
        assertNull(f.store.read(discardedId)!!.localData)
        assertNull(f.store.read(discardedId)!!.transfer.receipt)
        assertEquals(listOf(first.sessionId), f.flow.state.records.map { it.sessionId })
        f.restart(); f.flow.reconnect(); f.drain()
        assertEquals(CollectionPage.HOME, f.flow.state.page)
        assertTrue(f.flow.state.canStart)
        assertEquals(first, f.store.read(first.sessionId))
        preserved.forEach { (name, bytes) -> assertArrayEquals(name, bytes, File(f.directory, name).readBytes()) }
        f.begin()
        assertNotEquals(discardedId, f.store.read()!!.sessionId)
        assertTrue(f.store.read(discardedId)!!.isDiscarded)
    }

    @Test fun finishChoiceCommitFailureKeepsTheUnchosenSessionAndCanRetryAfterRestart() {
        val f = Fixture()
        f.begin(); f.stopAtFinish()
        val stopped = f.store.read()!!
        f.failNextSessionCommit = true
        f.flow.chooseFinish(false)
        assertEquals(CollectionPage.FINISH, f.flow.state.page)
        assertNotNull(f.flow.state.error)
        assertEquals(stopped, f.store.read())
        f.restart()
        assertEquals(CollectionPage.FINISH, f.flow.state.page)
        f.flow.chooseFinish(false)
        assertEquals(CollectionPage.REFERENCE, f.flow.state.page)
        assertEquals(CompletionPolicy.SAVE_LATER, f.store.read()!!.completionPolicy)
        assertEquals(stopped.sessionId, f.store.read()!!.sessionId)
    }

    @Test fun discardCommitFailurePreservesStoppedEvidenceAndRetriesTheSameSession() {
        val f = Fixture()
        f.begin(); f.stopAtFinish()
        val stopped = f.store.read()!!
        val device = File(f.directory, "device.json").readBytes()
        f.failNextSessionCommit = true
        f.flow.discardSession()
        assertEquals(CollectionPage.FINISH, f.flow.state.page)
        assertEquals(stopped, f.store.read())
        assertArrayEquals(device, File(f.directory, "device.json").readBytes())
        assertFalse(f.flow.state.canStart)
        f.restart(); f.flow.discardSession(); f.drain()
        assertTrue(f.store.read(stopped.sessionId)!!.isDiscarded)
        assertTrue(f.flow.state.canStart)
        assertTrue(f.flow.state.records.isEmpty())
        assertArrayEquals(device, File(f.directory, "device.json").readBytes())
    }

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
        f.startWalking(); f.drain()
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
        assertEquals(CollectionPage.FINISH, f.flow.state.page)
        assertNull(f.store.read()!!.completionPolicy)
        assertNull(f.store.read()!!.localData)
        assertEquals(0, f.store.read()!!.transfer.attempts)
        val reference = f.store.read()!!.reference
        f.restart()
        assertEquals(CollectionPage.FINISH, f.flow.state.page)
        f.flow.chooseFinish(false); f.drain()
        assertEquals(CollectionPage.COMPLETE, f.flow.state.page)
        assertEquals(reference, f.store.read()!!.reference)
        assertEquals(CompletionPolicy.SAVE_LATER, f.store.read()!!.completionPolicy)
        assertEquals(0, f.store.read()!!.transfer.attempts)
        assertNull(f.store.read()!!.transfer.receipt)
        assertEquals(ReferenceStatus.UNRELIABLE, f.store.read()!!.reference!!.status)
        assertFalse(f.store.read()!!.deviceRecordEvidence!!.status.collecting)
        assertNull(f.store.read()!!.endedAtMs)
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
        assertFalse(f.store.read()!!.deviceRecordEvidence!!.status.collecting)
        assertNull(f.store.read()!!.endedAtMs)
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
        f.startWalking(); f.advance(1100) // Complete preflight plus the 500 ms start settling window.
        assertEquals(FreeLivingSessionPhase.START_REQUESTED, f.store.read()!!.phase)
        val id = f.store.read()!!.sessionId
        val startedDevice = File(f.directory, "device.json").readBytes()
        f.restart()
        assertEquals(CollectionPage.COLLECTING, f.flow.state.page)
        assertArrayEquals(startedDevice, File(f.directory, "device.json").readBytes())
        assertTrue(f.store.read()!!.deviceRecordEvidence!!.status.collecting)
        f.flow.setFault(FlowTestFault.STOP_TIMEOUT)
        f.flow.stop()
        assertEquals(FreeLivingSessionPhase.STOP_REQUESTED, f.store.read()!!.phase)
        val stoppedDevice = File(f.directory, "device.json").readBytes()
        f.restart()
        assertEquals(CollectionPage.FINISH, f.flow.state.page)
        assertEquals(id, f.store.read()!!.sessionId)
        assertArrayEquals(stoppedDevice, File(f.directory, "device.json").readBytes())
    }

    @Test fun tamperedLocalFilesBlockNewCaptureAndPreserveTheDeviceRecord() {
        val f = Fixture()
        f.complete("562")
        val original = f.store.read()!!
        val before = File(f.directory, "device.json").readBytes()
        File(f.directory, original.localData!!.files.single().fileName).appendText("corrupt")
        f.startWalking()
        assertEquals(CollectionPage.ERROR, f.flow.state.page)
        assertEquals(original.sessionId, f.store.read()!!.sessionId)
        assertArrayEquals(before, File(f.directory, "device.json").readBytes())
    }

    @Test fun homeDuringPendingConfirmationDoesNotIssueASecondStart() {
        val f = Fixture()
        f.startWalking()
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
        val id = f.store.read()!!.sessionId
        f.flow.home()
        assertEquals(CollectionPage.HOME, f.flow.state.page)
        assertEquals(CollectionPage.ERROR, f.flow.state.taskPage)
        assertNotNull(f.flow.state.error)
        assertTrue(f.flow.state.canStart)
        assertEquals("transferring", f.flow.state.records.single().transferStatus)
        assertFalse(f.flow.state.records.single().transferInFlight)
        f.flow.retryUpload(id)
        assertEquals(id, f.flow.state.records.single().sessionId)
        assertTrue(f.flow.state.records.single().transferInFlight)
        assertEquals(CollectionPage.HOME, f.flow.state.page)
        assertEquals(CollectionPage.UPLOADING, f.flow.state.taskPage)
        f.drain()
        assertEquals(id, f.store.read()!!.sessionId)
        assertEquals(CollectionPage.HOME, f.flow.state.page)
        assertEquals(CollectionPage.COMPLETE, f.flow.state.taskPage)
        assertEquals("complete", f.flow.state.records.single().transferStatus)
        assertFalse(f.flow.state.records.single().transferInFlight)
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
        f.flow.home(); f.startWalking(); f.advance(2700) // Preflight, start/poll waits, then a complete STATUS/LIST.
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

    @Test fun homeDuringPreflightShowsStartingAndCannotOfferAnotherStartBeforeASessionExists() {
        val f = Fixture()
        f.startWalking()
        assertNull(f.store.read())
        f.flow.home()
        assertEquals(CollectionPage.HOME, f.flow.state.page)
        assertEquals(CollectionPage.STARTING, f.flow.state.taskPage)
        assertFalse(f.flow.state.canStart)
        assertTrue(f.flow.state.canRetry)
        val scheduled = f.scheduledCount
        f.flow.retry()
        assertEquals(CollectionPage.STARTING, f.flow.state.page)
        assertEquals(scheduled, f.scheduledCount)
        f.drain()
        assertEquals(CollectionPage.COLLECTING, f.flow.state.page)
        assertEquals(1, f.store.listSessions().size)
    }

    @Test fun homeDoesNotHideAsynchronousTaskProgressOrNavigateAwayFromHome() {
        val f = Fixture()
        f.startWalking()
        f.flow.home()
        f.drain()
        assertEquals(CollectionPage.HOME, f.flow.state.page)
        assertEquals(CollectionPage.COLLECTING, f.flow.state.taskPage)
        assertFalse(f.flow.state.canStart)
        val id = f.store.read()!!.sessionId
        val before = File(f.directory, "device.json").readBytes()
        f.flow.retry()
        assertEquals(CollectionPage.COLLECTING, f.flow.state.page)
        assertEquals(id, f.store.read()!!.sessionId)
        assertArrayEquals(before, File(f.directory, "device.json").readBytes())
    }

    @Test fun returningToPendingStopShowsTheSameOperationWithoutAnotherCommand() {
        val f = Fixture()
        f.begin(); f.flow.stop()
        val deviceBefore = File(f.directory, "device.json").readBytes()
        f.flow.home()
        assertEquals(CollectionPage.STOPPING, f.flow.state.taskPage)
        val scheduled = f.scheduledCount
        f.flow.retry()
        assertEquals(CollectionPage.STOPPING, f.flow.state.page)
        assertEquals(scheduled, f.scheduledCount)
        assertArrayEquals(deviceBefore, File(f.directory, "device.json").readBytes())
        f.drain()
        assertEquals(CollectionPage.FINISH, f.flow.state.page)
    }

    @Test fun returningHomeWhileSavingRetainsTheInFlightReferenceAndDoesNotReopenItsForm() {
        val f = Fixture()
        f.begin(); f.stop(); f.flow.saveReference("562")
        f.flow.home()
        assertEquals(CollectionPage.SAVING, f.flow.state.taskPage)
        assertNull(f.store.read()!!.reference)
        assertFalse(f.flow.state.canStart)
        val scheduled = f.scheduledCount
        f.flow.retry()
        assertEquals(CollectionPage.SAVING, f.flow.state.page)
        assertEquals(scheduled, f.scheduledCount)
        f.flow.saveReference("999")
        f.drain()
        assertEquals(562L, f.store.read()!!.reference!!.steps)
        assertEquals(1, f.store.read()!!.transfer.attempts)
    }

    @Test fun returningToDownloadOrUploadShowsProgressWithoutSchedulingAnotherTask() {
        val f = Fixture()
        f.begin(); f.stop(); f.flow.saveReference("8"); f.advance(350)
        f.flow.home()
        assertEquals(CollectionPage.DOWNLOADING, f.flow.state.taskPage)
        assertFalse(f.flow.state.canStart)
        val downloadScheduled = f.scheduledCount
        f.flow.retry()
        assertEquals(CollectionPage.DOWNLOADING, f.flow.state.page)
        assertEquals(downloadScheduled, f.scheduledCount)
        f.advance(1200)
        f.flow.home()
        assertEquals(CollectionPage.UPLOADING, f.flow.state.taskPage)
        assertTrue(f.flow.state.canStart)
        val uploadScheduled = f.scheduledCount
        f.flow.retry()
        assertEquals(CollectionPage.UPLOADING, f.flow.state.page)
        assertEquals(uploadScheduled, f.scheduledCount)
        f.flow.home(); f.drain()
        assertEquals(CollectionPage.HOME, f.flow.state.page)
        assertEquals(CollectionPage.COMPLETE, f.flow.state.taskPage)
        assertEquals(1, f.store.read()!!.transfer.attempts)
    }

    @Test fun homeShowsConfirmationTimeoutAndReconnectRecoveryWithoutResendingStart() {
        val f = Fixture()
        f.flow.setFault(FlowTestFault.START_TIMEOUT)
        f.startWalking(); f.flow.home(); f.drain()
        assertEquals(CollectionPage.HOME, f.flow.state.page)
        assertEquals(CollectionPage.RECOVERY, f.flow.state.taskPage)
        val before = File(f.directory, "device.json").readBytes()
        f.flow.retry()
        assertEquals(CollectionPage.COLLECTING, f.flow.state.page)
        assertArrayEquals(before, File(f.directory, "device.json").readBytes())
        f.flow.home(); f.flow.disconnect()
        assertEquals(CollectionPage.HOME, f.flow.state.page)
        assertEquals(CollectionPage.RECOVERY, f.flow.state.taskPage)
        assertFalse(f.flow.state.canStart)
        f.flow.reconnect()
        assertEquals(CollectionPage.COLLECTING, f.flow.state.page)
    }

    @Test fun previousReceiptDuringNewPreflightDoesNotReplaceStartingTaskOnHome() {
        val f = Fixture()
        f.begin(); f.stop(); f.flow.saveReference("41"); f.advance(2450)
        val firstId = f.store.read()!!.sessionId
        assertEquals(CollectionPage.UPLOADING, f.flow.state.page)
        f.flow.home(); f.startWalking(); f.flow.home()
        f.advance(300)
        assertEquals(SessionTransferStatus.COMPLETE, f.store.read(firstId)!!.transfer.status)
        assertEquals(CollectionPage.HOME, f.flow.state.page)
        assertEquals(CollectionPage.STARTING, f.flow.state.taskPage)
        assertFalse(f.flow.state.canStart)
        f.drain()
        assertNotEquals(firstId, f.store.read()!!.sessionId)
        assertEquals(CollectionPage.COLLECTING, f.flow.state.taskPage)
    }

    @Test fun reconnectBeforeAnySessionClearsTheRecoveryTaskAndRestoresTheStartAction() {
        val f = Fixture()
        f.flow.home(); f.flow.disconnect()
        assertEquals(CollectionPage.RECOVERY, f.flow.state.taskPage)
        assertFalse(f.flow.state.canStart)
        f.flow.reconnect()
        assertEquals(CollectionPage.HOME, f.flow.state.page)
        assertNull(f.flow.state.taskPage)
        assertTrue(f.flow.state.canStart)
        assertNull(f.store.read())
    }

    private inner class Fixture(parent: File = temporary.newFolder()) {
        val directory = File(parent, "collection-demo")
        var now = 1_789_804_800_000L
        var failNextUploadCommit = false
        var failNextSessionCommit = false
        var rejectNextDelay: Long? = null
        var rejectUploadScheduling = false
        var uploadExtraDelayMs = 0L
        private var order = 0L
        val scheduledCount get() = order
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
                if (failNextSessionCommit) {
                    failNextSessionCommit = false
                    throw IOException("test session journal failure")
                }
                if (failNextUploadCommit && source.readText().contains("transferring")) {
                    failNextUploadCommit = false
                    throw IOException("test upload journal failure")
                }
            })
        fun startWalking() { flow.selectActivity(SessionActivity.WALKING); flow.start() }
        fun begin(activity: SessionActivity = SessionActivity.WALKING) {
            flow.selectActivity(activity); flow.start(); drain()
            assertEquals(CollectionPage.COLLECTING, flow.state.page)
        }
        fun stopAtFinish() { flow.stop(); drain(); assertEquals(CollectionPage.FINISH, flow.state.page) }
        fun stop() {
            stopAtFinish(); flow.chooseFinish(true)
            assertEquals(CollectionPage.REFERENCE, flow.state.page)
        }
        fun complete(steps: String, status: String = "valid", reason: String = "") {
            begin(); stop(); flow.saveReference(steps, status, reason); drain()
            assertEquals(CollectionPage.COMPLETE, flow.state.page)
        }
        fun restart() { tasks.clear(); flow = create(); flow.initialize() }
        fun advance(ms: Long) {
            val end = now + ms
            while (tasks.peek()?.time?.let { it <= end } == true) {
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
