package com.nexthci.ringfitness

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class FreeLivingStoppedEvidenceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val epoch = 1_789_804_800_000L
    private val address = "AA:BB:CC:DD:EE:01"
    private val profile = PreparationSnapshot("stop001", "83e56afb-a74c-4825-af0b-b226cfe9c630",
        RingPlacement.LEFT_INDEX, PreparedRing(address, "Ringo stop fixture"))
    private val initialRecord = HealthMessage.ListItem(7, 32, 2, 1_000, epoch)
    private val collecting = HealthMessage.Status(true, 16, 1, 0, 7)
    private val finalRecord = initialRecord.copy(bytes = 128, records = 8)
    private val stopped = collecting.copy(collecting = false, bytes = 128, records = 8)

    @Test fun spontaneousStoppedStatusRemainsPendingUntilMatchingCompleteConfirmation() {
        for (reopen in listOf(false, true)) {
            val f = Fixture()
            f.begin()
            f.coordinator.requestStop()
            f.health(f.coordinator, stopped)
            val pending = f.store.readPending()!!
            assertEquals(FreeLivingSessionPhase.STOP_REQUESTED, pending.phase)
            assertNull(pending.stopConfirmedAtMs)
            assertNull(pending.stopStatusEvidence)
            assertEquals(stopped, pending.deviceRecordEvidence!!.status)
            assertEquals(initialRecord, pending.deviceRecordEvidence.record)
            assertFalse(pending.deviceAssociationInvalidated)
            assertEquals(pending, f.openStore().readPending())
            val coordinator = if (reopen) {
                f.coordinator.close()
                f.newCoordinator(f.openStore()).also { it.onConnected(address, 2); it.reconcile() }
            } else { f.nextDelay(); f.coordinator }
            val connection = if (reopen) 2L else 1L
            f.health(coordinator, stopped, connection)
            assertNull(f.store.readPending()!!.stopConfirmedAtMs)
            f.health(coordinator, finalRecord, connection)
            assertNull(f.store.readPending()!!.stopConfirmedAtMs)
            f.health(coordinator, HealthMessage.ListEnd(1), connection)
            assertEquals(CaptureControlPhase.STOPPING, coordinator.state.phase)
            assertNull(f.store.readPending()!!.stopConfirmedAtMs)
            // Reopening leaves the closed coordinator's cancelled wait in this simple FIFO fixture.
            repeat(if (reopen) 2 else 1) { f.nextDelay() }
            f.health(coordinator, stopped, connection)
            f.health(coordinator, finalRecord, connection)
            f.health(coordinator, HealthMessage.ListEnd(1), connection)
            assertEquals(CaptureControlPhase.AWAITING_REFERENCE, coordinator.state.phase)
            assertEquals(stopped, f.store.readPending()!!.stopStatusEvidence)
            assertEquals(finalRecord, f.store.readPending()!!.deviceRecordEvidence!!.record)
            assertEquals(1, f.calls.count { it == "stop" })
        }
    }

    @Test fun stoppedEvidenceCannotRevertToCollectingDuringWaitOrRecovery() {
        for (reopen in listOf(false, true)) {
            val f = Fixture()
            f.begin()
            f.coordinator.requestStop()
            f.health(f.coordinator, stopped)
            val coordinator = if (reopen) {
                f.coordinator.close()
                f.newCoordinator(f.openStore()).also { it.onConnected(address, 2); it.reconcile() }
            } else f.coordinator
            val connection = if (reopen) 2L else 1L
            f.health(coordinator, stopped.copy(collecting = true), connection)
            f.health(coordinator, finalRecord, connection)
            f.health(coordinator, HealthMessage.ListEnd(1), connection)
            assertEquals(CaptureControlIssue.RECORD_CHANGED, coordinator.state.issue)
            assertTrue(f.store.readPending()!!.deviceAssociationInvalidated)
            assertNull(f.store.readPending()!!.stopConfirmedAtMs)
            assertEquals(1, f.calls.count { it == "stop" })
        }
    }

    @Test fun aSessionWithoutAStopRequestCannotPersistStoppedEvidence() {
        val f = Fixture()
        f.begin()
        val current = f.store.readPending()!!
        val original = f.journal.readBytes()
        assertThrows(IllegalArgumentException::class.java) {
            f.store.updateDeviceEvidence(current.sessionId,
                current.deviceRecordEvidence!!.copy(status = stopped, observedAtMs = epoch + 2_000))
        }
        assertArrayEquals(original, f.journal.readBytes())
        assertEquals(current, f.openStore().readPending())
        assertEquals(0, f.calls.count { it == "stop" })
    }

    @Test fun aCollectingReplyBetweenStoppedStatusAndListCannotBeIgnored() {
        val f = Fixture()
        f.begin()
        f.coordinator.requestStop()
        f.health(f.coordinator, stopped)
        f.nextDelay()
        f.health(f.coordinator, stopped)
        f.health(f.coordinator, stopped.copy(collecting = true))
        f.health(f.coordinator, finalRecord)
        f.health(f.coordinator, HealthMessage.ListEnd(1))
        assertEquals(CaptureControlIssue.RECORD_CHANGED, f.coordinator.state.issue)
        assertTrue(f.store.readPending()!!.deviceAssociationInvalidated)
        assertNull(f.store.readPending()!!.stopConfirmedAtMs)
        assertEquals(1, f.calls.count { it == "stop" })
    }

    @Test fun persistedStoppedEvidenceRejectsDirectCollectingRollback() {
        val f = Fixture()
        f.begin()
        f.coordinator.requestStop()
        f.health(f.coordinator, stopped)
        val current = f.store.readPending()!!
        assertThrows(IllegalArgumentException::class.java) {
            f.store.updateDeviceEvidence(current.sessionId,
                current.deviceRecordEvidence!!.copy(status = stopped.copy(collecting = true), observedAtMs = epoch + 2_000))
        }
        assertEquals(current, f.openStore().readPending())
        assertNull(current.stopConfirmedAtMs)
    }

    private inner class Fixture {
        val journal = File(temporary.newFolder(), "session.json")
        val store = openStore()
        val calls = mutableListOf<String>()
        private val pending = mutableListOf<() -> Unit>()
        val coordinator = newCoordinator(store)
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
        fun health(owner: FreeLivingCaptureCoordinator, message: HealthMessage, connection: Long = 1) =
            owner.onHealth(connection, SensorPacket.Health(message, epoch + 1_000))
        fun begin() {
            coordinator.onConnected(address, 1)
            coordinator.requestStart(profile)
            health(coordinator, collecting.copy(collecting = false, bytes = 0, records = 0, sessionId = 0))
            health(coordinator, HealthMessage.ListEnd(0))
            nextDelay(); nextDelay()
            health(coordinator, collecting)
            health(coordinator, initialRecord)
            health(coordinator, HealthMessage.ListEnd(1))
            assertEquals(CaptureControlPhase.COLLECTING, coordinator.state.phase)
        }
    }
}
