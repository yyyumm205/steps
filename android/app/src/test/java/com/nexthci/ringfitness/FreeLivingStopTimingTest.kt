package com.nexthci.ringfitness

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class FreeLivingStopTimingTest {
    @get:Rule val temporary = TemporaryFolder()
    private val epoch = 1_789_804_800_000L
    private val address = "AA:BB:CC:DD:EE:01"
    private val profile = PreparationSnapshot("stop001", "83e56afb-a74c-4825-af0b-b226cfe9c630",
        RingPlacement.LEFT_INDEX, PreparedRing(address, "Ringo timing fixture"))
    private val record = HealthMessage.ListItem(7, 32, 2, 1_000, epoch)
    private val active = HealthMessage.Status(true, 16, 1, 0, 7)
    private val finalRecord = record.copy(bytes = 64, records = 4)
    private val stopped = HealthMessage.Status(false, 64, 4, 0, 7)

    @Test fun stopIsPersistedAndSentOnceThenWaitsBeforeTheFirstConfirmationQuery() {
        val f = Fixture()
        f.begin()
        val before = f.count("status")
        f.coordinator.requestStop()
        assertEquals(FreeLivingSessionPhase.STOP_REQUESTED, f.store.readPending()!!.phase)
        assertEquals(1, f.count("stop"))
        assertTrue(f.coordinator.state.settling)
        assertNull(f.coordinator.state.timeoutOperationId)
        f.advance(999)
        assertEquals(before, f.count("status"))
        f.wallTime -= 86_400_000L
        f.advance(1)
        assertEquals(before + 1, f.count("status"))
        assertFalse(f.coordinator.state.settling)
        assertNotNull(f.coordinator.state.timeoutOperationId)
        f.observe(stopped, finalRecord)
        assertEquals(CaptureControlPhase.AWAITING_REFERENCE, f.coordinator.state.phase)
        assertEquals(1, f.count("stop"))
    }

    @Test fun sameRecordStillCollectingGetsAnotherReadOnlyRoundBeforeTheReferencePage() {
        val f = Fixture()
        f.begin()
        f.coordinator.requestStop()
        f.advance(1_000)
        f.observe(active.copy(bytes = 40, records = 3), record.copy(bytes = 48, records = 3))
        assertTrue(f.coordinator.state.settling)
        assertEquals(CaptureControlPhase.STOPPING, f.coordinator.state.phase)
        assertNull(f.store.readPending()!!.stopConfirmedAtMs)
        assertEquals(48L, f.store.readPending()!!.deviceRecordEvidence!!.record.bytes)
        val before = f.calls.toList()
        repeat(2) { f.coordinator.requestStop(); f.coordinator.reconcile(); f.coordinator.requestStart(profile) }
        assertEquals(before, f.calls)
        f.advance(1_499)
        assertEquals(before, f.calls)
        f.advance(1)
        f.observe(stopped, finalRecord)
        assertEquals(CaptureControlPhase.AWAITING_REFERENCE, f.coordinator.state.phase)
        assertEquals(1, f.count("stop"))
    }

    @Test fun threeStillCollectingRoundsEndInRecoveryWithTheOriginalStopRequest() {
        val f = Fixture()
        f.begin()
        val initialQueries = f.count("status")
        f.coordinator.requestStop()
        val request = f.store.readPending()!!.stopRequestedAtMs
        repeat(3) { index ->
            f.advance(if (index == 0) 1_000 else 1_500)
            f.observe(active, record)
        }
        f.advance(30_000)
        assertEquals(initialQueries + 3, f.count("status"))
        assertEquals(1, f.count("stop"))
        assertEquals(CaptureControlIssue.UNEXPECTED_DEVICE_STATE, f.coordinator.state.issue)
        assertEquals(request, f.store.readPending()!!.stopRequestedAtMs)
        assertNull(f.store.readPending()!!.stopConfirmedAtMs)
        assertFalse(f.coordinator.state.settling)
    }

    @Test fun identityChangesAndCounterRegressionStopPollingAndRetainTheJournal() {
        val bad = listOf(
            active.copy(sessionId = 8) to record.copy(sessionId = 8),
            active to record.copy(uptimeMs = 2_000),
            active to record.copy(unixMs = epoch + 1),
            active.copy(bytes = 15) to record,
            active to record.copy(records = 1),
            active.copy(errorCode = -16) to record,
        )
        bad.forEach { (status, item) ->
            val f = Fixture()
            f.begin()
            f.coordinator.requestStop()
            f.advance(1_000)
            f.observe(status, item)
            val before = f.calls.toList()
            f.advance(30_000)
            assertEquals(before, f.calls)
            assertEquals(CaptureControlPhase.NEEDS_REVIEW, f.coordinator.state.phase)
            assertEquals(1, f.count("stop"))
            assertNotNull(f.store.readPending()!!.stopRequestedAtMs)
            assertNull(f.store.readPending()!!.stopConfirmedAtMs)
        }
    }

    @Test fun secondPollCannotRegressBelowThePersistedFirstPoll() {
        val f = Fixture()
        f.begin()
        f.coordinator.requestStop()
        f.advance(1_000)
        f.observe(active.copy(bytes = 80, records = 5), record.copy(bytes = 96, records = 6))
        f.advance(1_500)
        f.observe(stopped, finalRecord)
        assertEquals(CaptureControlIssue.RECORD_CHANGED, f.coordinator.state.issue)
        assertTrue(f.store.readPending()!!.deviceAssociationInvalidated)
        assertNull(f.store.readPending()!!.stopConfirmedAtMs)
    }

    @Test fun spontaneousStopRepliesCannotSkipTheConfirmationQuery() {
        val f = Fixture()
        f.begin()
        f.coordinator.requestStop()
        val before = f.calls.toList()
        f.observe(active.copy(collecting = false), record)
        assertEquals(before, f.calls)
        assertNull(f.store.readPending()!!.stopConfirmedAtMs)
        f.advance(1_000)
        f.observe(stopped, finalRecord)
        assertEquals(CaptureControlPhase.AWAITING_REFERENCE, f.coordinator.state.phase)
    }

    @Test fun harmfulUnsolicitedRepliesDuringEitherWaitCancelTheNextQuery() {
        for (afterFirstPoll in listOf(false, true)) {
            for (message in listOf(active.copy(errorCode = -16), active.copy(sessionId = 99),
                    record.copy(uptimeMs = 2_000), active.copy(bytes = 1))) {
                val f = Fixture()
                f.begin()
                f.coordinator.requestStop()
                if (afterFirstPoll) { f.advance(1_000); f.observe(active, record) }
                f.health(message)
                val before = f.calls.toList()
                f.advance(30_000)
                assertEquals(before, f.calls)
                assertEquals(CaptureControlPhase.NEEDS_REVIEW, f.coordinator.state.phase)
                assertNull(f.store.readPending()!!.stopConfirmedAtMs)
                assertEquals(1, f.count("stop"))
            }
        }
    }

    @Test fun unsolicitedCollectingStatusHighWaterMarkSurvivesWaitingAndReopening() {
        for (reopen in listOf(false, true)) {
            val f = Fixture()
            f.begin()
            f.coordinator.requestStop()
            val higher = active.copy(bytes = 128, records = 8)
            f.health(higher)
            val evidence = f.store.readPending()!!.deviceRecordEvidence!!
            assertEquals(higher, evidence.status)
            assertEquals(record, evidence.record) // Keep the actual earlier LIST; no synthesized bytes.
            val coordinator = if (reopen) {
                f.coordinator.close()
                f.newCoordinator().also { it.onConnected(address, 2); it.reconcile() }
            } else { f.advance(1_000); f.coordinator }
            val connection = if (reopen) 2L else 1L
            coordinator.onHealth(connection, SensorPacket.Health(stopped, epoch + 5_000))
            coordinator.onHealth(connection, SensorPacket.Health(finalRecord, epoch + 5_001))
            coordinator.onHealth(connection, SensorPacket.Health(HealthMessage.ListEnd(1), epoch + 5_002))
            assertEquals(CaptureControlIssue.RECORD_CHANGED, coordinator.state.issue)
            assertTrue(f.store.readPending()!!.deviceAssociationInvalidated)
            assertNull(f.store.readPending()!!.stopConfirmedAtMs)
            assertEquals(1, f.count("stop"))
        }
    }

    @Test fun unsolicitedListHighWaterMarkSurvivesWaitingAndReopening() {
        for (reopen in listOf(false, true)) {
            val f = Fixture()
            f.begin()
            f.coordinator.requestStop()
            val higher = record.copy(bytes = 128, records = 8)
            f.health(higher)
            val evidence = f.store.readPending()!!.deviceRecordEvidence!!
            assertEquals(higher, evidence.record)
            assertEquals(active, evidence.status) // Retain the real STATUS separately from LIST.
            val coordinator = if (reopen) {
                f.coordinator.close()
                f.newCoordinator().also { it.onConnected(address, 2); it.reconcile() }
            } else { f.advance(1_000); f.coordinator }
            val connection = if (reopen) 2L else 1L
            coordinator.onHealth(connection, SensorPacket.Health(stopped, epoch + 5_000))
            coordinator.onHealth(connection, SensorPacket.Health(finalRecord, epoch + 5_001))
            coordinator.onHealth(connection, SensorPacket.Health(HealthMessage.ListEnd(1), epoch + 5_002))
            assertEquals(CaptureControlIssue.RECORD_CHANGED, coordinator.state.issue)
            assertTrue(f.store.readPending()!!.deviceAssociationInvalidated)
            assertNull(f.store.readPending()!!.stopConfirmedAtMs)
            assertEquals(1, f.count("stop"))
        }
    }

    @Test fun unsolicitedStoppedStatusGrowthRejectsLowerConfirmationAcrossReopening() {
        val f = Fixture()
        f.begin()
        f.coordinator.requestStop()
        f.health(stopped.copy(bytes = 128, records = 8))
        assertFalse(f.store.readPending()!!.deviceAssociationInvalidated)
        assertEquals(stopped.copy(bytes = 128, records = 8), f.store.readPending()!!.deviceRecordEvidence!!.status)
        assertNull(f.store.readPending()!!.stopConfirmedAtMs)
        f.coordinator.close()
        val reopened = f.newCoordinator()
        reopened.onConnected(address, 2)
        reopened.reconcile()
        reopened.onHealth(2, SensorPacket.Health(stopped, epoch + 5_000))
        reopened.onHealth(2, SensorPacket.Health(finalRecord, epoch + 5_001))
        reopened.onHealth(2, SensorPacket.Health(HealthMessage.ListEnd(1), epoch + 5_002))
        assertEquals(CaptureControlIssue.RECORD_CHANGED, reopened.state.issue)
        assertTrue(f.store.readPending()!!.deviceAssociationInvalidated)
        assertNull(f.store.readPending()!!.stopConfirmedAtMs)
        assertEquals(1, f.count("stop"))
    }

    @Test fun disconnectReconnectRefreshAndCloseCancelBothStopWaits() {
        val interruptions: List<(Fixture) -> Unit> = listOf(
            { it.coordinator.onDisconnected(1) },
            { it.coordinator.onConnected(address, 2) },
            { it.coordinator.refresh() },
            { it.coordinator.close() },
        )
        for (afterFirstPoll in listOf(false, true)) for (interrupt in interruptions) {
            val f = Fixture()
            f.begin()
            f.coordinator.requestStop()
            if (afterFirstPoll) { f.advance(1_000); f.observe(active, record) }
            val saved = f.store.readPending()
            interrupt(f)
            val before = f.calls.toList()
            f.advance(30_000)
            assertEquals(before, f.calls)
            assertEquals(saved, f.store.readPending())
            assertEquals(1, f.count("stop"))
        }
    }

    @Test fun damagedJournalDuringTheWaitCannotSendAnotherQuery() {
        val f = Fixture()
        f.begin()
        f.coordinator.requestStop()
        val before = f.calls.toList()
        f.journal.writeText("corrupt stop fixture")
        f.advance(1_000)
        assertEquals(before, f.calls)
        assertEquals(CaptureControlPhase.STORAGE_ERROR, f.coordinator.state.phase)
        assertFalse(f.coordinator.state.settling)
    }

    @Test fun failingToPersistAStillCollectingObservationStopsPolling() {
        val f = Fixture()
        f.begin()
        f.coordinator.requestStop()
        f.advance(1_000)
        f.failCommit = true
        f.observe(active.copy(bytes = 40, records = 3), record.copy(bytes = 48, records = 3))
        val before = f.calls.toList()
        f.advance(30_000)
        assertEquals(before, f.calls)
        assertEquals(CaptureControlPhase.STORAGE_ERROR, f.coordinator.state.phase)
        assertNull(f.store.readPending()!!.stopConfirmedAtMs)
    }

    @Test fun reconnectRechecksTheSameRecordAndResumesQueriesWithoutResendingStop() {
        val f = Fixture()
        f.begin()
        f.coordinator.requestStop()
        f.advance(1_000)
        f.observe(active.copy(bytes = 40, records = 3), record.copy(bytes = 48, records = 3))
        f.coordinator.onDisconnected(1)
        f.coordinator.onConnected(address, 2)
        f.coordinator.reconcile()
        f.observe(active.copy(bytes = 48, records = 3), record.copy(bytes = 48, records = 3), 2)
        f.advance(1_500)
        f.observe(stopped, finalRecord, 2)
        assertEquals(CaptureControlPhase.AWAITING_REFERENCE, f.coordinator.state.phase)
        assertEquals(1, f.count("stop"))
    }

    private inner class Fixture {
        val journal = File(temporary.newFolder(), "session.json")
        var failCommit = false
        var elapsed = 0L
        var wallTime = epoch
        private val pending = mutableListOf<Pair<Long, () -> Unit>>()
        val calls = mutableListOf<Pair<String, Long>>()
        val store = FreeLivingSessionStore(journal, { source, target ->
            if (failCommit) throw IOException("Injected persistence failure")
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }, {})
        val coordinator = newCoordinator()
        fun newCoordinator() = FreeLivingCaptureCoordinator(store, object : HealthControlPort {
            private fun send(command: String): Boolean {
                if (command == "stop") assertEquals(FreeLivingSessionPhase.STOP_REQUESTED, store.readPending()!!.phase)
                calls += command to elapsed
                return true
            }
            override fun queryStatus() = send("status")
            override fun queryRecords() = send("list")
            override fun start() = send("start")
            override fun stop() = send("stop")
        }, object : CaptureClock {
            override fun nowEpochMs() = wallTime + elapsed
            override fun nowElapsedMs() = elapsed
            override fun timeZoneId() = "Asia/Shanghai"
        }, { delay, action -> pending += elapsed + delay to action }, {})

        fun advance(delta: Long) {
            val target = elapsed + delta
            while (true) {
                val next = pending.minByOrNull { it.first }?.takeIf { it.first <= target } ?: break
                pending.remove(next)
                elapsed = next.first
                next.second()
            }
            elapsed = target
        }
        fun count(command: String) = calls.count { it.first == command }
        fun health(message: HealthMessage, connection: Long = 1) =
            coordinator.onHealth(connection, SensorPacket.Health(message, wallTime + elapsed))
        fun observe(status: HealthMessage.Status, item: HealthMessage.ListItem, connection: Long = 1) {
            health(status, connection); health(item, connection); health(HealthMessage.ListEnd(1), connection)
        }
        fun begin() {
            coordinator.onConnected(address, 1)
            coordinator.requestStart(profile)
            health(HealthMessage.Status(false, 0, 0, 0, 0))
            health(HealthMessage.ListEnd(0))
            advance(1_500)
            observe(active, record)
            assertEquals(CaptureControlPhase.COLLECTING, coordinator.state.phase)
        }
    }
}
