package com.nexthci.ringfitness

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class FreeLivingRepeatedStopStatusTest {
    @get:Rule val temporary = TemporaryFolder()
    private val epoch = 1_789_804_800_000L
    private val address = "AA:BB:CC:DD:EE:01"
    private val profile = PreparationSnapshot("stop001", "83e56afb-a74c-4825-af0b-b226cfe9c630",
        RingPlacement.LEFT_INDEX, PreparedRing(address, "Ringo repeated STATUS fixture"))
    private val initialRecord = HealthMessage.ListItem(7, 32, 2, 1_000, epoch)
    private val collecting = HealthMessage.Status(true, 16, 1, 0, 7)
    private val stopped = collecting.copy(collecting = false, bytes = 64, records = 4)
    private val higher = stopped.copy(bytes = 128, records = 8)

    @Test fun repeatedGrowthDrainsTheListThenRequiresAnotherCompleteRoundWithinTheLimit() {
        val f = Fixture()
        f.beginStopQuery()
        val before = f.count("status")
        f.health(stopped)
        f.health(higher)
        assertEquals(higher, f.store.readPending()!!.deviceRecordEvidence!!.status)
        assertEquals(initialRecord, f.store.readPending()!!.deviceRecordEvidence!!.record)
        assertNull(f.store.readPending()!!.stopConfirmedAtMs)
        assertEquals(before, f.count("status"))
        f.list(64, 4)
        assertTrue(f.coordinator.state.settling)
        assertEquals(before, f.count("status"))
        f.nextDelay()
        f.health(higher)
        f.list(128, 8)
        assertEquals(CaptureControlPhase.AWAITING_REFERENCE, f.coordinator.state.phase)
        assertEquals(1, f.count("stop"))

        val bounded = Fixture()
        bounded.beginStopQuery()
        val initialQueries = bounded.count("status")
        repeat(3) { index ->
            val low = stopped.copy(bytes = 64L + index * 64L, records = 4L + index * 4L)
            bounded.health(low)
            bounded.health(low.copy(bytes = low.bytes + 64, records = low.records + 4))
            bounded.list(low.bytes, low.records)
            if (index < 2) bounded.nextDelay()
        }
        assertEquals(initialQueries + 2, bounded.count("status"))
        assertEquals(CaptureControlPhase.NEEDS_REVIEW, bounded.coordinator.state.phase)
        assertFalse(bounded.coordinator.state.settling)
        assertEquals(0, bounded.pendingCount())
        assertEquals(1, bounded.count("stop"))
        assertNull(bounded.store.readPending()!!.stopConfirmedAtMs)
    }

    @Test fun repeatedOwnedStatusHighWaterMarkSurvivesReopeningBeforeListEnd() {
        val f = Fixture()
        f.beginStopQuery()
        f.health(stopped)
        f.health(higher)
        assertEquals(higher, f.openStore().readPending()!!.deviceRecordEvidence!!.status)
        f.coordinator.close()
        f.coordinator = f.newCoordinator(f.openStore())
        f.connection = 2
        f.coordinator.onConnected(address, 2)
        f.coordinator.reconcile()
        f.health(stopped)
        f.list(64, 4)
        assertEquals(CaptureControlIssue.RECORD_CHANGED, f.coordinator.state.issue)
        assertTrue(f.store.readPending()!!.deviceAssociationInvalidated)
        assertNull(f.store.readPending()!!.stopConfirmedAtMs)
        assertEquals(1, f.count("stop"))
    }

    @Test fun aRepeatedStatusCannotHideTheFirstStatusRegression() {
        for (inspect in listOf(false, true)) {
            val f = Fixture()
            f.beginStopQuery()
            f.health(stopped)
            f.health(higher)
            f.list(64, 4)
            val sessionId = f.openStore().readPending()!!.sessionId
            if (inspect) {
                f.coordinator.close()
                f.coordinator = f.newCoordinator(f.openStore())
                f.connection = 2
                f.coordinator.onConnected(address, 2)
                f.coordinator.reconcile()
            } else {
                f.nextDelay()
            }
            f.health(stopped)
            f.health(higher)
            f.list(128, 8)
            assertEquals(CaptureControlPhase.NEEDS_REVIEW, f.coordinator.state.phase)
            assertEquals(CaptureControlIssue.RECORD_CHANGED, f.coordinator.state.issue)
            val saved = f.openStore().readPending()!!
            assertEquals(sessionId, saved.sessionId)
            assertTrue(saved.deviceAssociationInvalidated)
            assertNull(saved.stopConfirmedAtMs)
            assertEquals(higher, saved.deviceRecordEvidence!!.status)
            assertEquals(1, f.count("stop"))
        }
    }

    @Test fun firstOwnedStatusHighWaterMarkSurvivesReopeningBeforeListEnd() {
        val f = Fixture()
        f.beginStopQuery()
        f.health(higher)
        val saved = f.openStore().readPending()!!
        assertEquals(higher, saved.deviceRecordEvidence!!.status)
        assertEquals(initialRecord, saved.deviceRecordEvidence.record)
        assertNull(saved.stopConfirmedAtMs)
        f.coordinator.close()
        f.coordinator = f.newCoordinator(f.openStore())
        f.connection = 2
        f.coordinator.onConnected(address, 2)
        f.coordinator.reconcile()
        f.health(stopped)
        f.list(64, 4)
        assertEquals(CaptureControlIssue.RECORD_CHANGED, f.coordinator.state.issue)
        val reopened = f.openStore().readPending()!!
        assertEquals(saved.sessionId, reopened.sessionId)
        assertTrue(reopened.deviceAssociationInvalidated)
        assertEquals(higher, reopened.deviceRecordEvidence!!.status)
        assertNull(reopened.stopConfirmedAtMs)
        assertEquals(1, f.count("stop"))
    }

    @Test fun inspectProvesTheFingerprintBeforeSavingRepeatedStatusAndRepolling() {
        for (sameRecord in listOf(false, true)) {
            val f = Fixture()
            f.beginStopQuery()
            f.coordinator.onDisconnected(1)
            f.connection = 2
            f.coordinator.onConnected(address, 2)
            f.coordinator.reconcile()
            val before = f.store.readPending()!!.deviceRecordEvidence
            f.health(stopped)
            f.health(higher)
            assertEquals(before, f.openStore().readPending()!!.deviceRecordEvidence)
            f.health(initialRecord.copy(bytes = 64, records = 4, uptimeMs = if (sameRecord) 1_000 else 2_000))
            f.health(HealthMessage.ListEnd(1))
            if (sameRecord) {
                assertEquals(higher, f.openStore().readPending()!!.deviceRecordEvidence!!.status)
                assertNull(f.store.readPending()!!.stopConfirmedAtMs)
                f.nextDelay()
                f.health(higher)
                f.list(128, 8)
                assertEquals(CaptureControlPhase.AWAITING_REFERENCE, f.coordinator.state.phase)
            } else {
                assertEquals(CaptureControlIssue.RECORD_CHANGED, f.coordinator.state.issue)
                assertEquals(before, f.openStore().readPending()!!.deviceRecordEvidence)
                assertNull(f.store.readPending()!!.stopConfirmedAtMs)
            }
            assertEquals(1, f.count("stop"))
        }
    }

    private inner class Fixture {
        val journal = File(temporary.newFolder(), "session.json")
        val store = openStore()
        var connection = 1L
        val calls = mutableListOf<String>()
        private val pending = mutableListOf<() -> Unit>()
        var coordinator = newCoordinator(store)
        fun openStore() = FreeLivingSessionStore(journal, { source, target ->
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }, {})
        fun newCoordinator(journalStore: FreeLivingSessionStore) = FreeLivingCaptureCoordinator(journalStore,
            object : HealthControlPort {
                private fun send(command: String): Boolean { calls += command; return true }
                override fun queryStatus() = send("status")
                override fun queryRecords() = send("list")
                override fun start() = send("start")
                override fun stop() = send("stop")
            }, object : CaptureClock {
                override fun nowEpochMs() = epoch + 1_000
                override fun nowElapsedMs() = 1_000L
                override fun timeZoneId() = "UTC"
            }, { _, action -> pending += action }, {})
        fun nextDelay() = pending.removeAt(0).invoke()
        fun pendingCount() = pending.size
        fun count(command: String) = calls.count { it == command }
        fun health(message: HealthMessage) = coordinator.onHealth(connection, SensorPacket.Health(message, epoch + 1_000))
        fun list(bytes: Long, records: Long) {
            health(initialRecord.copy(bytes = bytes, records = records))
            health(HealthMessage.ListEnd(1))
        }
        fun beginStopQuery() {
            coordinator.onConnected(address, 1)
            coordinator.requestStart(profile)
            health(collecting.copy(collecting = false, bytes = 0, records = 0, sessionId = 0))
            health(HealthMessage.ListEnd(0))
            nextDelay(); nextDelay()
            health(collecting)
            list(32, 2)
            assertEquals(CaptureControlPhase.COLLECTING, coordinator.state.phase)
            coordinator.requestStop()
            nextDelay()
        }
    }
}
