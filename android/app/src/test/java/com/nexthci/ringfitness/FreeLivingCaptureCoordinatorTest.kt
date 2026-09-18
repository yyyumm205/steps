package com.nexthci.ringfitness

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class FreeLivingCaptureCoordinatorTest {
    @get:Rule val temporary = TemporaryFolder()
    private val epoch = 1_789_804_800_000L
    private val preparation = PreparationSnapshot("p001", "83e56afb-a74c-4825-af0b-b226cfe9c630",
        RingPlacement.LEFT_INDEX, PreparedRing("AA:BB:CC:DD:EE:01", "Ringo coordinator fixture"))
    private val address = preparation.ring!!.address

    @Test fun anEmptyJournalIsIdleAndRestoreNeverSendsACommand() {
        val f = Fixture()
        f.coordinator.restore()
        assertEquals(CaptureControlPhase.IDLE, f.coordinator.state.phase)
        assertNull(f.coordinator.state.session)
        assertTrue(f.port.calls.isEmpty())
    }

    @Test fun startRequiresThePreparedRingAndACompleteEmptyBaseline() {
        val f = Fixture()
        f.coordinator.requestStart(preparation)
        assertEquals(CaptureControlIssue.NOT_CONNECTED, f.coordinator.state.issue)
        f.coordinator.onConnected("AA:BB:CC:DD:EE:99", 1)
        f.coordinator.requestStart(preparation)
        assertEquals(CaptureControlIssue.WRONG_RING, f.coordinator.state.issue)
        assertTrue(f.port.calls.isEmpty())
        assertNull(f.store.read())
        f.connect(2)
        f.coordinator.requestStart(preparation)
        f.health(idle(), connection = 2)
        assertEquals(listOf("status", "list"), f.port.calls)
        assertNull(f.store.read())
        f.health(HealthMessage.ListEnd(0), connection = 2)
        assertEquals(listOf("status", "list", "start", "status"), f.port.calls)
        assertEquals(FreeLivingSessionPhase.START_REQUESTED, f.store.read()!!.phase)
    }

    @Test fun theDurableStartRequestExistsBeforeTheTransportReceivesStart() {
        val f = Fixture()
        f.port.accept = { command ->
            if (command == "start") {
                val persisted = requireNotNull(f.store.read())
                assertEquals(FreeLivingSessionPhase.START_REQUESTED, persisted.phase)
                assertEquals(preparation, persisted.preparation)
                assertEquals(CaptureControlPhase.STARTING, f.coordinator.state.phase)
            }
            true
        }
        f.beginStart()
        assertEquals(1, f.port.count("start"))
    }

    @Test fun failedInitialPersistenceDoesNotSendStartOrCreateAPartialSession() {
        val f = Fixture()
        f.failCommits = true
        f.beginStart()
        assertEquals(CaptureControlPhase.STORAGE_ERROR, f.coordinator.state.phase)
        assertEquals(0, f.port.count("start"))
        assertNull(f.store.read())
        f.failCommits = false
        f.coordinator.requestStart(preparation)
        f.observe(idle())
        assertEquals(1, f.port.count("start"))
    }

    @Test fun aRenamedStartWhoseDirectorySyncFailedIsNeverDispatchedOrReplaced() {
        val f = Fixture()
        f.failSyncAfterCommit = true
        f.beginStart()
        assertEquals(CaptureControlPhase.STORAGE_ERROR, f.coordinator.state.phase)
        assertEquals(0, f.port.count("start"))
        f.failSyncAfterCommit = false
        val saved = requireNotNull(f.store.read())
        val original = f.file.readBytes()
        assertEquals(FreeLivingSessionPhase.START_REQUESTED, saved.phase)
        f.coordinator.requestStart(preparation)
        f.observe(idle())
        assertEquals(CaptureControlIssue.RECOVERY_REQUIRES_REVIEW, f.coordinator.state.issue)
        assertEquals(saved, f.coordinator.state.session)
        assertEquals(0, f.port.count("start"))
        assertArrayEquals(original, f.file.readBytes())
    }

    @Test fun duplicateClicksBeforeAndAfterStartConfirmationKeepOneIdentityAndCommand() {
        val f = Fixture()
        f.connect()
        f.coordinator.requestStart(preparation)
        f.coordinator.requestStart(preparation)
        assertEquals(1, f.port.count("status"))
        f.observe(idle())
        val requested = requireNotNull(f.store.read())
        f.coordinator.requestStart(preparation)
        f.observe(collecting(), listOf(record()))
        f.coordinator.requestStart(preparation)
        assertEquals(requested.sessionId, f.store.read()!!.sessionId)
        assertEquals(1, f.port.count("start"))
        assertEquals(CaptureControlPhase.COLLECTING, f.coordinator.state.phase)
    }

    @Test fun queueAcceptanceAndStatusAloneCannotConfirmCollection() {
        val f = Fixture()
        f.beginStart()
        assertEquals(CaptureControlPhase.STARTING, f.coordinator.state.phase)
        f.health(collecting())
        assertEquals(CaptureControlPhase.STARTING, f.coordinator.state.phase)
        assertNull(f.store.read()!!.startConfirmedAtMs)
        f.health(record())
        assertNull(f.store.read()!!.startConfirmedAtMs)
        f.health(HealthMessage.ListEnd(1))
        assertEquals(CaptureControlPhase.COLLECTING, f.coordinator.state.phase)
        assertEquals(f.store.read(), f.coordinator.state.session)
    }

    @Test fun aNormalStartStopRetainsUnknownBoundariesAndPendingDataProtection() {
        val f = Fixture()
        f.beginCollecting()
        val id = f.store.read()!!.sessionId
        f.clock.now += 60_000
        f.coordinator.requestStop()
        f.observe(stopped(), listOf(record(bytes = 64, records = 4)))
        val saved = requireNotNull(f.store.read())
        assertEquals(id, saved.sessionId)
        assertEquals(FreeLivingSessionPhase.AWAITING_REFERENCE, saved.phase)
        assertEquals(CaptureControlPhase.AWAITING_REFERENCE, f.coordinator.state.phase)
        assertNull(saved.startedAtMs)
        assertNull(saved.endedAtMs)
        assertEquals("uncertain", saved.captureBoundaryStatus)
        val commands = f.port.calls.toList()
        f.coordinator.requestStart(preparation)
        f.coordinator.requestStop()
        assertEquals(commands, f.port.calls)
        assertEquals(saved, f.store.read())
    }

    @Test fun existingDeviceRecordsAndExistingCollectionBlockStart() {
        for (status in listOf(idle().copy(bytes = 32), idle().copy(records = 1), idle().copy(collecting = true))) {
            val f = Fixture()
            f.connect()
            f.coordinator.requestStart(preparation)
            f.observe(status)
            assertEquals(CaptureControlIssue.EXISTING_RECORDS, f.coordinator.state.issue)
            assertEquals(0, f.port.count("start"))
            assertNull(f.store.read())
        }
        val f = Fixture()
        f.connect()
        f.coordinator.requestStart(preparation)
        f.observe(idle(), listOf(record(bytes = 0, records = 0)))
        assertEquals(CaptureControlIssue.EXISTING_RECORDS, f.coordinator.state.issue)
        assertEquals(0, f.port.count("start"))
    }

    @Test fun deviceErrorsNeverBecomeSuccessfulCaptureEvidence() {
        val f = Fixture()
        f.connect()
        f.coordinator.requestStart(preparation)
        f.observe(idle().copy(errorCode = 3))
        assertEquals(CaptureControlIssue.DEVICE_ERROR, f.coordinator.state.issue)
        assertEquals(0, f.port.count("start"))
    }

    @Test fun incompleteDuplicateAndInvalidListsRequireFreshConnection() {
        val f = Fixture()
        f.beginStart()
        f.health(collecting())
        f.health(record())
        f.health(HealthMessage.ListEnd(2))
        assertEquals(CaptureControlIssue.INVALID_OBSERVATION, f.coordinator.state.issue)
        val calls = f.port.calls.toList()
        f.coordinator.reconcile()
        assertEquals(CaptureControlIssue.RECONNECT_REQUIRED, f.coordinator.state.issue)
        assertEquals(calls, f.port.calls)
        assertNull(f.store.read()!!.startConfirmedAtMs)

        val duplicate = Fixture()
        duplicate.beginStart()
        duplicate.health(collecting())
        duplicate.health(record())
        duplicate.health(record())
        duplicate.health(HealthMessage.ListEnd(2))
        assertEquals(CaptureControlIssue.INVALID_OBSERVATION, duplicate.coordinator.state.issue)

        val invalid = Fixture()
        invalid.beginStart()
        invalid.health(collecting())
        invalid.health(record().copy(uptimeMs = -1))
        assertEquals(CaptureControlIssue.INVALID_OBSERVATION, invalid.coordinator.state.issue)
    }

    @Test fun oldOrAmbiguousIdsAndUnknownAnchorsNeverConfirmStart() {
        for (candidate in listOf(record(id = 9), record(id = 0), record().copy(unixMs = 0))) {
            val f = Fixture()
            f.beginStart()
            f.observe(collecting(id = candidate.sessionId), listOf(candidate))
            assertEquals(CaptureControlIssue.RECORD_ORIGIN_UNCERTAIN, f.coordinator.state.issue)
            assertNull(f.store.read()!!.startConfirmedAtMs)
        }
        val f = Fixture()
        f.beginStart()
        f.observe(collecting(), listOf(record(id = 43)))
        assertEquals(CaptureControlIssue.RECORD_ORIGIN_UNCERTAIN, f.coordinator.state.issue)
        assertNull(f.store.read()!!.startConfirmedAtMs)
    }

    @Test fun oldConnectionAndPrematureListRepliesCannotAdvanceTheCurrentRound() {
        val f = Fixture()
        f.beginStart()
        f.health(record())
        f.health(HealthMessage.ListEnd(1))
        f.health(collecting(), connection = 0)
        f.health(record(), connection = 0)
        f.health(HealthMessage.ListEnd(1), connection = 0)
        assertNull(f.store.read()!!.startConfirmedAtMs)
        assertEquals(CaptureControlPhase.STARTING, f.coordinator.state.phase)
        f.observe(collecting(), listOf(record()))
        assertEquals(CaptureControlPhase.COLLECTING, f.coordinator.state.phase)
    }

    @Test fun timeoutDiscardsUntaggedRepliesAndNeverReplaysStart() {
        val f = Fixture()
        f.beginStart()
        val token = requireNotNull(f.coordinator.state.timeoutOperationId)
        f.coordinator.onTimeout(token - 1)
        assertEquals(CaptureControlPhase.STARTING, f.coordinator.state.phase)
        f.coordinator.onTimeout(token)
        assertEquals(CaptureControlIssue.QUERY_TIMEOUT, f.coordinator.state.issue)
        f.observe(collecting(), listOf(record()))
        assertNull(f.store.read()!!.startConfirmedAtMs)
        f.coordinator.requestStart(preparation)
        f.coordinator.reconcile()
        assertEquals(1, f.port.count("start"))
        assertEquals(CaptureControlIssue.RECONNECT_REQUIRED, f.coordinator.state.issue)
    }

    @Test fun aRejectedStartRemainsJournaledAndIsNeverAutomaticallyRetried() {
        val f = Fixture()
        f.port.accept = { it != "start" }
        f.beginStart()
        val saved = requireNotNull(f.store.read())
        assertEquals(FreeLivingSessionPhase.START_REQUESTED, saved.phase)
        assertEquals(CaptureControlIssue.COMMAND_NOT_ACCEPTED, f.coordinator.state.issue)
        f.coordinator.requestStart(preparation)
        f.connect(2)
        f.coordinator.reconcile()
        f.observe(collecting(), listOf(record()), connection = 2)
        assertEquals(CaptureControlIssue.RECOVERY_REQUIRES_REVIEW, f.coordinator.state.issue)
        assertEquals(saved, f.store.read())
        assertEquals(1, f.port.count("start"))
    }

    @Test fun disconnectedStartCannotBeAdoptedAfterReconnectEvenWithTheSameRing() {
        val f = Fixture()
        f.beginStart()
        val original = f.file.readBytes()
        f.coordinator.onDisconnected(1)
        f.connect(2)
        f.coordinator.reconcile()
        f.observe(collecting(), listOf(record()), connection = 1)
        assertEquals(CaptureControlPhase.CHECKING, f.coordinator.state.phase)
        f.observe(collecting(), listOf(record()), connection = 2)
        assertEquals(CaptureControlIssue.RECOVERY_REQUIRES_REVIEW, f.coordinator.state.issue)
        assertArrayEquals(original, f.file.readBytes())
        assertEquals(1, f.port.count("start"))
    }

    @Test fun restartedCoordinatorPreservesCollectingJournalButCannotStopAnUnprovenRecord() {
        val f = Fixture()
        f.beginCollecting()
        val original = f.file.readBytes()
        val reopened = f.newCoordinator()
        reopened.restore()
        assertEquals(CaptureControlIssue.RECOVERY_REQUIRES_REVIEW, reopened.state.issue)
        reopened.onConnected(address, 5)
        reopened.requestStop()
        assertEquals(0, f.port.count("stop"))
        assertEquals(CaptureControlIssue.RECOVERY_REQUIRES_REVIEW, reopened.state.issue)
        reopened.reconcile()
        reopened.onHealth(5, SensorPacket.Health(collecting(), epoch + 100))
        reopened.onHealth(5, SensorPacket.Health(record(), epoch + 101))
        reopened.onHealth(5, SensorPacket.Health(HealthMessage.ListEnd(1), epoch + 102))
        assertEquals(CaptureControlIssue.RECOVERY_REQUIRES_REVIEW, reopened.state.issue)
        assertArrayEquals(original, f.file.readBytes())
    }

    @Test fun aPersistedStoppedSessionRestoresWithoutInventingDeviceReadiness() {
        val f = Fixture()
        f.beginCollecting()
        f.coordinator.requestStop()
        f.observe(stopped(), listOf(record(bytes = 64, records = 4)))
        val calls = f.port.calls.toList()
        val reopened = f.newCoordinator()
        reopened.restore()
        assertEquals(CaptureControlPhase.AWAITING_REFERENCE, reopened.state.phase)
        assertEquals(f.store.read(), reopened.state.session)
        assertNull(reopened.state.observation)
        assertEquals(calls, f.port.calls)
    }

    @Test fun recoveryWhileNormallyCollectingPreservesTheStopAction() {
        val f = Fixture()
        f.beginCollecting()
        val saved = f.store.read()
        val calls = f.port.calls.toList()
        f.coordinator.reconcile()
        assertEquals(CaptureControlPhase.COLLECTING, f.coordinator.state.phase)
        assertEquals(saved, f.coordinator.state.session)
        assertEquals(calls, f.port.calls)
        f.coordinator.requestStop()
        assertEquals(1, f.port.count("stop"))
        assertEquals(CaptureControlPhase.STOPPING, f.coordinator.state.phase)
    }

    @Test fun recoveryAfterConfirmedStopDoesNotDowngradeTheReferenceStep() {
        val f = Fixture()
        f.beginCollecting()
        f.coordinator.requestStop()
        f.observe(stopped(), listOf(record(bytes = 64, records = 4)))
        val saved = f.store.read()
        val calls = f.port.calls.toList()
        f.coordinator.reconcile()
        assertEquals(CaptureControlPhase.AWAITING_REFERENCE, f.coordinator.state.phase)
        assertEquals(saved, f.coordinator.state.session)
        assertEquals(calls, f.port.calls)
    }

    @Test fun repeatedRestoreCannotReplaceAnInFlightResponseRound() {
        val f = Fixture()
        f.beginStart()
        val token = f.coordinator.state.timeoutOperationId
        f.health(collecting())
        val calls = f.port.calls.toList()
        f.coordinator.restore()
        assertEquals(token, f.coordinator.state.timeoutOperationId)
        assertEquals(calls, f.port.calls)
        f.health(record())
        f.health(HealthMessage.ListEnd(1))
        assertEquals(CaptureControlPhase.COLLECTING, f.coordinator.state.phase)
        assertEquals(1, f.port.count("start"))
    }

    @Test fun failedStartConfirmationDoesNotReportCollectingAndReadOnlyRetryCanSave() {
        val f = Fixture()
        f.beginStart()
        f.failCommits = true
        f.observe(collecting(), listOf(record()))
        assertEquals(CaptureControlPhase.STORAGE_ERROR, f.coordinator.state.phase)
        assertNull(f.store.read()!!.startConfirmedAtMs)
        f.failCommits = false
        f.coordinator.reconcile()
        f.observe(collecting(), listOf(record()))
        assertEquals(CaptureControlPhase.COLLECTING, f.coordinator.state.phase)
        assertEquals(1, f.port.count("start"))
    }

    @Test fun stopIsPersistedBeforeSendingAndFailedPersistenceCanRetryWithoutDuplicateStop() {
        val f = Fixture()
        f.beginCollecting()
        f.failCommits = true
        f.coordinator.requestStop()
        assertEquals(CaptureControlPhase.STORAGE_ERROR, f.coordinator.state.phase)
        assertEquals(0, f.port.count("stop"))
        assertNull(f.store.read()!!.stopRequestedAtMs)
        f.failCommits = false
        f.port.accept = { command ->
            if (command == "stop") {
                assertEquals(FreeLivingSessionPhase.STOP_REQUESTED, f.store.read()!!.phase)
                assertEquals(CaptureControlPhase.STOPPING, f.coordinator.state.phase)
            }
            true
        }
        f.coordinator.requestStop()
        f.coordinator.requestStop()
        assertEquals(1, f.port.count("stop"))
    }

    @Test fun stopStatusAloneAndDifferentRecordAnchorsCannotCompleteTheSession() {
        val f = Fixture()
        f.beginCollecting()
        f.coordinator.requestStop()
        f.health(stopped())
        assertNull(f.store.read()!!.stopConfirmedAtMs)
        f.health(record(bytes = 64, records = 4).copy(uptimeMs = 2000))
        f.health(HealthMessage.ListEnd(1))
        assertEquals(CaptureControlIssue.RECORD_CHANGED, f.coordinator.state.issue)
        assertNull(f.store.read()!!.stopConfirmedAtMs)
        f.coordinator.reconcile()
        f.observe(stopped(), listOf(record(bytes = 64, records = 4)))
        assertEquals(CaptureControlIssue.RECOVERY_REQUIRES_REVIEW, f.coordinator.state.issue)
        assertNull(f.store.read()!!.stopConfirmedAtMs)
    }

    @Test fun aStopStillCollectingIsOnlyQueriedAgainWithoutReplayingStop() {
        val f = Fixture()
        f.beginCollecting()
        f.coordinator.requestStop()
        f.observe(collecting(), listOf(record()))
        assertEquals(CaptureControlIssue.UNEXPECTED_DEVICE_STATE, f.coordinator.state.issue)
        f.coordinator.requestStop()
        assertEquals(1, f.port.count("stop"))
        f.coordinator.reconcile()
        f.observe(stopped(), listOf(record(bytes = 64, records = 4)))
        assertEquals(CaptureControlPhase.AWAITING_REFERENCE, f.coordinator.state.phase)
        assertEquals(1, f.port.count("stop"))
    }

    @Test fun failedStopConfirmationKeepsItsRequestAndRetriesOnlyTheSave() {
        val f = Fixture()
        f.beginCollecting()
        f.coordinator.requestStop()
        val request = f.store.read()!!
        f.failCommits = true
        f.observe(stopped(), listOf(record(bytes = 64, records = 4)))
        assertEquals(CaptureControlPhase.STORAGE_ERROR, f.coordinator.state.phase)
        assertEquals(request, f.store.read())
        f.failCommits = false
        f.coordinator.reconcile()
        f.observe(stopped(), listOf(record(bytes = 64, records = 4)))
        assertEquals(CaptureControlPhase.AWAITING_REFERENCE, f.coordinator.state.phase)
        assertEquals(1, f.port.count("stop"))
    }

    @Test fun stopConfirmationDirectorySyncFailureDoesNotReportSuccessOrRewriteFirstEvidence() {
        val f = Fixture()
        f.beginCollecting()
        f.coordinator.requestStop()
        f.failSyncAfterCommit = true
        f.observe(stopped(), listOf(record(bytes = 64, records = 4)))
        assertEquals(CaptureControlPhase.STORAGE_ERROR, f.coordinator.state.phase)
        f.failSyncAfterCommit = false
        val firstSaved = requireNotNull(f.store.read())
        assertEquals(FreeLivingSessionPhase.AWAITING_REFERENCE, firstSaved.phase)
        f.clock.now += 5_000
        f.coordinator.reconcile()
        f.observe(stopped(), listOf(record(bytes = 64, records = 4)))
        assertEquals(CaptureControlPhase.AWAITING_REFERENCE, f.coordinator.state.phase)
        assertEquals(firstSaved, f.coordinator.state.session)
        assertEquals(firstSaved, f.store.read())
        assertEquals(1, f.port.count("stop"))
    }

    @Test fun anUnexpectedDeviceStateCannotLeaveAuthorityToStopAnotherRecord() {
        val f = Fixture()
        f.beginCollecting()
        f.health(collecting(id = 43))
        assertEquals(CaptureControlIssue.UNEXPECTED_DEVICE_STATE, f.coordinator.state.issue)
        f.coordinator.requestStop()
        assertEquals(0, f.port.count("stop"))
        assertNull(f.store.read()!!.stopRequestedAtMs)
    }

    @Test fun sameIdCounterRegressionRevokesAuthorityBeforeAnotherStop() {
        for (regressed in listOf(collecting().copy(bytes = 0), collecting().copy(records = 0))) {
            val f = Fixture()
            f.beginCollecting()
            f.health(regressed)
            assertEquals(CaptureControlIssue.UNEXPECTED_DEVICE_STATE, f.coordinator.state.issue)
            f.coordinator.requestStop()
            assertEquals(0, f.port.count("stop"))
            assertNull(f.store.read()!!.stopRequestedAtMs)
        }
    }

    @Test fun aStatusBelowTheLaterListCountDoesNotInventADeviceReset() {
        val f = Fixture()
        f.beginCollecting() // STATUS=16/1, later LIST=32/2.
        f.health(collecting().copy(bytes = 24))
        assertEquals(CaptureControlPhase.COLLECTING, f.coordinator.state.phase)
        f.coordinator.requestStop()
        assertEquals(1, f.port.count("stop"))
    }

    @Test fun wallClockRollbackDoesNotTimeoutOrInventCaptureBoundaries() {
        val f = Fixture()
        f.beginStart()
        f.clock.now -= 1000
        f.observe(collecting(), listOf(record()))
        val saved = f.store.read()!!
        assertEquals(CaptureControlPhase.COLLECTING, f.coordinator.state.phase)
        assertTrue("phone_clock_order_uncertain" in saved.timingWarnings)
        assertNull(saved.startedAtMs)
        assertNull(saved.endedAtMs)
    }

    @Test fun synchronousPortRepliesAndReentrantClicksAreSerializedAfterTheCurrentTransition() {
        val f = Fixture()
        var started = false
        var stopped = false
        f.port.accept = { command ->
            when (command) {
                "start" -> { started = true; f.coordinator.requestStart(preparation) }
                "stop" -> { stopped = true; f.coordinator.requestStop() }
                "status" -> f.health(when { stopped -> stopped(); started -> collecting(); else -> idle() })
                "list" -> {
                    if (started) f.health(if (stopped) record(bytes = 64, records = 4) else record())
                    f.health(HealthMessage.ListEnd(if (started) 1 else 0))
                }
            }
            true
        }
        f.observer = { current ->
            if (current.phase == CaptureControlPhase.STARTING) f.coordinator.requestStart(preparation)
        }
        f.connect()
        f.coordinator.requestStart(preparation)
        assertEquals(CaptureControlPhase.COLLECTING, f.coordinator.state.phase)
        f.coordinator.requestStop()
        assertEquals(CaptureControlPhase.AWAITING_REFERENCE, f.coordinator.state.phase)
        assertEquals(1, f.port.count("start"))
        assertEquals(1, f.port.count("stop"))
    }

    @Test fun corruptJournalIsPreservedAndBlocksAllMutatingCommands() {
        val f = Fixture()
        val original = "corrupt session fixture".toByteArray()
        f.file.writeBytes(original)
        f.connect()
        f.coordinator.requestStart(preparation)
        f.coordinator.requestStop()
        f.coordinator.reconcile()
        assertEquals(CaptureControlPhase.STORAGE_ERROR, f.coordinator.state.phase)
        assertTrue(f.port.calls.isEmpty())
        assertArrayEquals(original, f.file.readBytes())
    }

    private fun idle() = HealthMessage.Status(false, 0, 0, 0, 9)
    private fun collecting(id: Int = 42) = HealthMessage.Status(true, 16, 1, 0, id)
    private fun stopped() = HealthMessage.Status(false, 64, 4, 0, 42)
    private fun record(id: Int = 42, bytes: Long = 32, records: Long = 2) =
        HealthMessage.ListItem(id, bytes, records, 1000, epoch)

    private inner class Fixture {
        val file = File(temporary.newFolder(), "session.json")
        var failCommits = false
        var failSyncAfterCommit = false
        private var syncFailurePending = false
        val store = FreeLivingSessionStore(file, { source, target ->
            if (failCommits) throw IOException("simulated commit failure")
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            syncFailurePending = failSyncAfterCommit
        }, {
            if (syncFailurePending) {
                syncFailurePending = false
                throw IOException("simulated directory sync failure after rename")
            }
        })
        val clock = TestClock(epoch)
        val port = RecordingPort()
        var observer: (CaptureControlState) -> Unit = {}
        val coordinator = newCoordinator()

        fun newCoordinator() = FreeLivingCaptureCoordinator(store, port, clock) { observer(it) }
        fun connect(connection: Long = 1) = coordinator.onConnected(address, connection)
        fun health(message: HealthMessage, connection: Long = 1) =
            coordinator.onHealth(connection, SensorPacket.Health(message, clock.now))
        fun observe(status: HealthMessage.Status, records: List<HealthMessage.ListItem> = emptyList(), connection: Long = 1) {
            health(status, connection)
            records.forEach { health(it, connection) }
            health(HealthMessage.ListEnd(records.size), connection)
        }
        fun beginStart() {
            connect()
            coordinator.requestStart(preparation)
            observe(idle())
        }
        fun beginCollecting() {
            beginStart()
            observe(collecting(), listOf(record()))
            assertEquals(CaptureControlPhase.COLLECTING, coordinator.state.phase)
        }
    }

    private class TestClock(var now: Long) : CaptureClock {
        override fun nowEpochMs() = now
        override fun timeZoneId() = "Asia/Shanghai"
    }

    private class RecordingPort : HealthControlPort {
        val calls = mutableListOf<String>()
        var accept: (String) -> Boolean = { true }
        override fun queryStatus() = send("status")
        override fun queryRecords() = send("list")
        override fun start() = send("start")
        override fun stop() = send("stop")
        fun count(command: String) = calls.count { it == command }
        private fun send(command: String): Boolean { calls += command; return accept(command) }
    }
}
