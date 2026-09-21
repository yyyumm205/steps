package com.nexthci.ringfitness

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class ChargingStartCompatibilityTest {
    @get:Rule val temporary = TemporaryFolder()
    private val epoch = 1_789_804_800_000L
    private val profile = PreparationSnapshot("compat001", "83e56afb-a74c-4825-af0b-b226cfe9c630",
        RingPlacement.LEFT_INDEX, PreparedRing("AA:BB:CC:DD:EE:01", "Ringo test"))
    private val idle = HealthMessage.Status(false, 0, 0, -16, 0)
    private val record = HealthMessage.ListItem(7, 16, 1, 1000, epoch)
    private val active = HealthMessage.Status(true, 16, 1, 0, 7)

    @Test fun freshIdleBatteryAllowsOneDurableStartAndSuccessfulStop() {
        val f = Fixture()
        f.begin()
        assertEquals(listOf("status", "list", "battery"), f.calls)
        assertNull(f.store.read())
        f.battery()
        val saved = f.store.readPending()!!
        assertEquals(-16, saved.startBaseline!!.status.errorCode)
        assertNotNull(saved.startBaseline.chargingRecoveryEvidence)
        assertEquals(saved, FreeLivingSessionStore(f.file, { source, target ->
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }, {}).readPending())
        assertEquals(0, f.count("start"))
        repeat(3) { f.coordinator.requestStart(profile) }
        f.advance(499)
        assertEquals(0, f.count("start"))
        f.advance(500)
        assertEquals(1, f.count("start"))
        assertNull(f.store.readPending()!!.startConfirmedAtMs)
        f.advance(1500)
        f.observe(active, listOf(record), reason = 0)
        assertEquals(CaptureControlPhase.COLLECTING, f.coordinator.state.phase)
        f.coordinator.requestStop()
        f.advance(2_000)
        f.observe(active.copy(collecting = false), listOf(record), reason = 0)
        assertEquals(CaptureControlPhase.STOPPING, f.coordinator.state.phase)
        f.advance(7_000) // The five-second Flash window starts at the first stopped reply.
        f.observe(active.copy(collecting = false), listOf(record), reason = 0)
        assertEquals(CaptureControlPhase.AWAITING_REFERENCE, f.coordinator.state.phase)
        assertNotNull(f.store.readPending()!!.stopConfirmedAtMs)
        assertEquals(1, f.count("start"))
        assertEquals(1, f.count("stop"))
    }

    @Test fun chargingUnknownAndOldConnectionBatteryCannotStart() {
        for (charge in listOf(null, 1, 2, 127)) {
            val f = Fixture(); f.begin(); f.battery(charge)
            f.advance(10000)
            assertNull(f.store.read())
            assertEquals(0, f.count("start"))
        }
        val f = Fixture(); f.begin(); f.battery(connection = 0)
        f.advance(10000)
        assertNull(f.store.read())
        assertEquals(0, f.count("start"))
    }

    @Test fun legacyUnknownReasonAndOtherErrorsCannotUseCompatibility() {
        for ((code, reason) in listOf(-16 to null, -16 to 2, -16 to 0, -16 to 255, -5 to 1)) {
            val f = Fixture(); f.begin(idle.copy(errorCode = code), reason = reason)
            f.battery(); f.advance(10000)
            assertEquals(0, f.count("battery"))
            assertEquals(0, f.count("start"))
            assertNull(f.store.read())
        }
        val f = Fixture(); f.begin(active.copy(errorCode = -16), listOf(record))
        assertEquals(0, f.count("battery"))
        assertEquals(0, f.count("start"))
    }

    @Test fun expiredBatteryAndReversedWallClockFailClosed() {
        for (shift in listOf(5001L, -1L)) {
            val f = Fixture(); f.begin(); f.wallShift = shift
            f.battery(); f.advance(10000)
            assertEquals(0, f.count("start")); assertNull(f.store.read())
        }
        val f = Fixture(); f.begin(); f.advance(5001); f.wallShift = -5001
        f.battery(); f.advance(10000)
        assertEquals(0, f.count("start")); assertNull(f.store.read())
    }

    @Test fun freshnessMustStillHoldWhenStartIsSent() {
        val f = Fixture(); f.begin(); f.advance(4800); f.battery()
        assertNotNull(f.store.readPending())
        f.advance(5300)
        assertEquals(0, f.count("start"))
        assertEquals(CaptureControlPhase.NEEDS_REVIEW, f.coordinator.state.phase)
    }

    @Test fun chargingOrChangedReasonDuringSettlingCancelsStart() {
        for (batteryChange in listOf(true, false)) {
            val f = Fixture(); f.begin(); f.battery(); f.advance(100)
            if (batteryChange) f.battery(1) else f.observe(idle, reason = 2)
            f.advance(10000)
            assertEquals(0, f.count("start"))
            assertNotNull(f.store.readPending())
        }
    }

    @Test fun unpreservedRecordsAndFailedWritesNeverSendStart() {
        val f = Fixture(); f.begin(active.copy(collecting = false, errorCode = -16), listOf(record)); f.battery()
        f.advance(10000)
        assertEquals(CaptureControlIssue.EXISTING_RECORDS, f.coordinator.state.issue)
        assertEquals(0, f.count("start")); assertNull(f.store.read())
        val failed = Fixture(); failed.begin(); failed.failWrite = true; failed.battery(); failed.advance(10000)
        assertEquals(CaptureControlPhase.STORAGE_ERROR, failed.coordinator.state.phase)
        assertEquals(0, failed.count("start")); assertNull(failed.store.read())
    }

    @Test fun restartOrDisconnectDoesNotReplayPendingStart() {
        for (afterSend in listOf(false, true)) {
            val f = Fixture(); f.begin(); f.battery()
            if (afterSend) f.advance(500)
            val saved = f.store.readPending()
            f.coordinator.close(); f.coordinator = f.createCoordinator()
            f.coordinator.onConnected(profile.ring!!.address, 2); f.coordinator.reconcile()
            f.observe(idle, connection = 2); f.advance(10000)
            assertEquals(if (afterSend) 1 else 0, f.count("start"))
            assertEquals(saved, f.store.readPending())
        }
    }

    @Test fun persistentChargingErrorOnlyRepollsAndNeverConfirms() {
        val f = Fixture(); f.begin(); f.battery(); f.advance(1500)
        f.observe(idle); f.advance(3000)
        f.observe(idle); f.advance(4500)
        f.observe(idle); f.advance(10000)
        assertEquals(1, f.count("start")); assertEquals(4, f.count("status"))
        assertNull(f.store.readPending()!!.startConfirmedAtMs)
        assertEquals(CaptureControlIssue.DEVICE_ERROR, f.coordinator.state.issue)
    }

    @Test fun newCollectingRecordStillRequiresErrorFreeConfirmation() {
        val f = Fixture(); f.begin(); f.battery(); f.advance(1500)
        f.observe(active.copy(errorCode = -16), listOf(record))
        assertEquals(1, f.count("start"))
        assertNull(f.store.readPending()!!.startConfirmedAtMs)
        assertFalse(f.coordinator.state.phase == CaptureControlPhase.COLLECTING)
    }

    @Test fun changingStatusWhileBatteryIsPendingRejectsTheRound() {
        val f = Fixture(); f.begin(); f.observe(idle.copy(bytes = 1)); f.battery(); f.advance(10000)
        assertEquals(0, f.count("start")); assertNull(f.store.read())
    }

    @Test fun contradictoryStatusDuringListCannotAuthorizeCompatibility() {
        for (next in listOf(idle to 2, idle.copy(collecting = true) to 1, idle.copy(bytes = 1) to 1)) {
            val f = Fixture()
            f.coordinator.onConnected(profile.ring!!.address, 1); f.coordinator.requestStart(profile)
            f.coordinator.onHealth(1, SensorPacket.Health(idle, epoch, 1))
            f.observe(next.first, reason = next.second)
            f.battery(); f.advance(10000)
            assertNull(f.store.read()); assertEquals(0, f.count("start"))
        }
    }

    private inner class Fixture {
        val file = File(temporary.newFolder(), "session.json")
        var now = 0L
        var wallShift = 0L
        var failWrite = false
        val store = FreeLivingSessionStore(file, { source, target ->
            if (failWrite) throw IOException("Injected commit failure")
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }, {})
        val calls = mutableListOf<String>()
        val tasks = mutableListOf<Pair<Long, () -> Unit>>()
        val clock = object : CaptureClock {
            override fun nowEpochMs() = epoch + now + wallShift
            override fun nowElapsedMs() = now
            override fun timeZoneId() = "Asia/Shanghai"
        }
        val port = object : HealthControlPort {
            override fun queryStatus() = send("status")
            override fun queryRecords() = send("list")
            override fun queryBattery() = send("battery")
            override fun start(): Boolean { assertNotNull(store.readPending()); return send("start") }
            override fun stop() = send("stop")
        }
        var coordinator = createCoordinator()
        fun createCoordinator() = FreeLivingCaptureCoordinator(store, port, clock,
            { delay, action -> tasks += now + delay to action }, {})
        fun begin(status: HealthMessage.Status = idle, records: List<HealthMessage.ListItem> = emptyList(), reason: Int? = 1) {
            coordinator.onConnected(profile.ring!!.address, 1); coordinator.requestStart(profile)
            observe(status, records, reason)
        }
        fun observe(status: HealthMessage.Status, records: List<HealthMessage.ListItem> = emptyList(), reason: Int? = 1, connection: Long = 1) {
            coordinator.onHealth(connection, SensorPacket.Health(status, clock.nowEpochMs(), reason))
            records.forEach { coordinator.onHealth(connection, SensorPacket.Health(it, clock.nowEpochMs())) }
            coordinator.onHealth(connection, SensorPacket.Health(HealthMessage.ListEnd(records.size), clock.nowEpochMs()))
        }
        fun battery(charge: Int? = 0, connection: Long = 1) =
            coordinator.onBattery(connection, SensorPacket.Battery(4100, 100, charge, clock.nowEpochMs()))
        fun send(name: String): Boolean { calls += name; return true }
        fun count(name: String) = calls.count { it == name }
        fun advance(target: Long) {
            while (true) {
                val task = tasks.minByOrNull { it.first }?.takeIf { it.first <= target } ?: break
                tasks.remove(task); now = task.first; task.second()
            }
            now = target
        }
    }
}
