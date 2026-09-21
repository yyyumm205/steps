package com.nexthci.ringfitness

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class FreeLivingStartTimingTest {
    @get:Rule val temporary = TemporaryFolder()
    private val epoch = 1_789_804_800_000L
    private val address = "AA:BB:CC:DD:EE:01"
    private val profile = PreparationSnapshot("timing001", "83e56afb-a74c-4825-af0b-b226cfe9c630",
        RingPlacement.LEFT_INDEX, PreparedRing(address, "Ringo timing fixture"))
    private val idle = HealthMessage.Status(false, 0, 0, 0, 0)
    private val record = HealthMessage.ListItem(7, 16, 1, 1_000, epoch)
    private val active = HealthMessage.Status(true, record.bytes, record.records, 0, record.sessionId)

    @Test fun durableRequestWaitsFiveHundredBeforeEnqueueAndOneThousandBeforeStatus() {
        val f = Fixture()
        f.begin()
        val pending = requireNotNull(f.store.readPending())
        assertEquals(FreeLivingSessionPhase.START_REQUESTED, pending.phase)
        assertTrue(f.coordinator.state.settling)
        assertNull(f.coordinator.state.timeoutOperationId)
        assertEquals(listOf("status" to 0L, "list" to 0L), f.calls)
        f.time.advanceTo(499)
        assertEquals(0, f.count("start"))
        f.time.advanceTo(500)
        assertEquals(listOf(500L), f.times("start"))
        assertTrue(f.coordinator.state.settling)
        val queued = requireNotNull(f.store.readPending())
        assertEquals(pending.sessionId, queued.sessionId)
        assertNotNull(queued.startCommandDispatch?.acceptedAtMs)
        f.time.advanceTo(1_499)
        assertEquals(1, f.count("status"))
        // Scheduling uses elapsed time; a wall-clock jump cannot shorten the settling window.
        f.wallTime -= 86_400_000L
        f.time.advanceTo(1_500)
        assertEquals(listOf(0L, 1_500L), f.times("status"))
        assertFalse(f.coordinator.state.settling)
        assertNotNull(f.coordinator.state.timeoutOperationId)
        f.observe(active, listOf(record))
        assertEquals(CaptureControlPhase.COLLECTING, f.coordinator.state.phase)
        assertEquals(pending.sessionId, f.store.readPending()!!.sessionId)
        assertEquals(1, f.count("start"))
    }

    @Test fun duplicateActionsDuringBothWaitsCannotSendAnotherCommand() {
        val f = Fixture()
        f.begin()
        for (at in listOf(100L, 600L)) {
            f.time.advanceTo(at)
            val before = f.calls.toList()
            repeat(2) {
                f.coordinator.requestStart(profile)
                f.coordinator.requestStop()
                f.coordinator.reconcile()
                f.coordinator.archiveUnconfirmedStart("test")
            }
            assertEquals(before, f.calls)
            assertTrue(f.coordinator.state.settling)
        }
        f.time.advanceTo(1_500)
        assertEquals(1, f.count("start"))
        assertEquals(0, f.count("stop"))
        assertNull(f.store.readPending()!!.startAttemptArchive)
    }

    @Test fun unsolicitedStartResponsesDuringTheWaitCannotConfirmTheRequest() {
        val f = Fixture()
        f.begin()
        f.time.advanceTo(500)
        f.observe(active, listOf(record))
        assertEquals(CaptureControlPhase.STARTING, f.coordinator.state.phase)
        assertNull(f.store.readPending()!!.startConfirmedAtMs)
        assertEquals(1, f.count("list"))
        f.time.advanceTo(1_499)
        f.health(idle.copy(errorCode = -16))
        assertNull(f.store.readPending()!!.startConfirmedAtMs)
        f.time.advanceTo(1_500)
        f.health(record)
        f.health(HealthMessage.ListEnd(1))
        assertNull(f.store.readPending()!!.startConfirmedAtMs)
        // Only a complete response to the newly issued STATUS/LIST round advances the task.
        f.observe(active, listOf(record))
        assertEquals(CaptureControlPhase.COLLECTING, f.coordinator.state.phase)
        assertEquals(1, f.count("start"))
    }

    @Test fun changedOrAbnormalMessagesBeforeStartCancelTheCommand() {
        val messages: List<HealthMessage> = listOf(active, idle.copy(errorCode = -16),
            idle.copy(bytes = 1), idle.copy(sessionId = 7), record, HealthMessage.ListEnd(1),
            HealthMessage.DataChunk(0, byteArrayOf(1)))
        messages.forEach { message ->
            val f = Fixture()
            f.begin()
            val request = f.store.readPending()
            f.time.advanceTo(499)
            f.health(message)
            f.time.advanceTo(10_000)
            assertEquals(0, f.count("start"))
            assertEquals(request, f.store.readPending())
            assertEquals(CaptureControlIssue.UNEXPECTED_DEVICE_STATE, f.coordinator.state.issue)
            assertFalse(f.coordinator.state.settling)
        }
    }

    @Test fun unchangedDuplicatesAndOldConnectionMessagesDoNotShortenTheWait() {
        val f = Fixture()
        f.begin()
        f.time.advanceTo(200)
        f.health(idle)
        f.health(HealthMessage.ListEnd(0))
        f.health(active, connection = 0)
        assertTrue(f.coordinator.state.settling)
        f.time.advanceTo(499)
        assertEquals(0, f.count("start"))
        f.time.advanceTo(500)
        assertEquals(listOf(500L), f.times("start"))
    }

    @Test fun disconnectReconnectRefreshAndCloseCancelAStartThatHasNotBeenSent() {
        interruptions().forEach { interrupt ->
            val f = Fixture()
            f.begin()
            val request = f.store.readPending()
            f.time.advanceTo(499)
            interrupt(f)
            f.time.advanceTo(10_000)
            assertEquals(0, f.count("start"))
            assertEquals(1, f.count("status"))
            assertEquals(request, f.store.readPending())
        }
    }

    @Test fun disconnectReconnectRefreshAndCloseCancelTheFirstPostStartQuery() {
        interruptions().forEach { interrupt ->
            val f = Fixture()
            f.begin()
            f.time.advanceTo(1_499)
            val request = f.store.readPending()
            interrupt(f)
            f.time.advanceTo(10_000)
            assertEquals(1, f.count("start"))
            assertEquals(1, f.count("status"))
            assertEquals(request, f.store.readPending())
            assertNull(request!!.startConfirmedAtMs)
        }
    }

    @Test fun persistenceFailureBeforeSettlingNeverEnqueuesStart() {
        val f = Fixture()
        f.failCommit = true
        f.begin()
        f.time.advanceTo(10_000)
        assertEquals(CaptureControlPhase.STORAGE_ERROR, f.coordinator.state.phase)
        assertEquals(0, f.count("start"))
        assertNull(f.store.readPending())
    }

    @Test fun damagedJournalDuringEitherWaitStopsTheNextCommand() {
        for (at in listOf(100L, 600L)) {
            val f = Fixture()
            f.begin()
            f.time.advanceTo(at)
            val before = f.calls.toList()
            f.journal.writeText("corrupted fixture")
            f.time.advanceTo(10_000)
            assertEquals(before, f.calls)
            assertEquals(CaptureControlPhase.STORAGE_ERROR, f.coordinator.state.phase)
            assertFalse(f.coordinator.state.settling)
        }
    }

    @Test fun schedulingFailureAndRejectedStartRetainTheRequestWithoutRetryingStart() {
        for (failDelay in listOf(500L, 1_000L)) {
            val f = Fixture()
            f.failSchedulingDelay = failDelay
            f.begin()
            f.time.advanceTo(10_000)
            assertEquals(if (failDelay == 500L) 0 else 1, f.count("start"))
            assertEquals(1, f.count("status"))
            assertNotNull(f.store.readPending())
            assertEquals(CaptureControlIssue.COMMAND_NOT_ACCEPTED, f.coordinator.state.issue)
        }
        val f = Fixture()
        f.acceptStart = false
        f.begin()
        f.time.advanceTo(10_000)
        assertEquals(1, f.count("start"))
        assertEquals(1, f.count("status"))
        assertNull(f.store.readPending()!!.startConfirmedAtMs)
        assertFalse(f.coordinator.state.settling)
    }

    @Test fun unreadyAfterTheFirstPollOnlyAllowsQueriesAndKeepsOneStart() {
        val f = Fixture()
        f.begin()
        f.time.advanceTo(1_500)
        f.observe(idle)
        assertTrue(f.coordinator.state.settling)
        assertNull(f.store.readPending()!!.startConfirmedAtMs)
        f.time.advanceTo(2_999)
        assertEquals(2, f.count("status"))
        f.observe(active, listOf(record)) // Unrequested replies during the wait are not confirmations.
        assertNull(f.store.readPending()!!.startConfirmedAtMs)
        f.time.advanceTo(3_000)
        assertEquals(3, f.count("status"))
        f.observe(active, listOf(record))
        assertEquals(CaptureControlPhase.COLLECTING, f.coordinator.state.phase)
        assertEquals(1, f.count("start"))
    }

    @Test fun unchangedIdleGetsAtMostThreeCompletePollsAndPreservesEachObservation() {
        for (errorCode in listOf(0, -16, 27)) {
            val f = Fixture()
            f.begin()
            for (at in listOf(1_500L, 3_000L, 4_500L)) {
                f.time.advanceTo(at)
                val before = f.observations.size
                f.observe(idle.copy(errorCode = errorCode))
                assertEquals(before + 1, f.observations.size)
                assertEquals(errorCode, f.observations.last().status.errorCode)
                assertNull(f.store.readPending()!!.startConfirmedAtMs)
            }
            f.time.advanceTo(30_000)
            assertEquals(listOf(0L, 1_500L, 3_000L, 4_500L), f.times("status"))
            assertEquals(4, f.count("list")) // Includes the preflight query.
            assertEquals(1, f.count("start"))
            assertFalse(f.coordinator.state.settling)
            assertEquals(CaptureControlPhase.NEEDS_REVIEW, f.coordinator.state.phase)
            assertEquals(if (errorCode == 0) CaptureControlIssue.RECORD_ORIGIN_UNCERTAIN else CaptureControlIssue.DEVICE_ERROR,
                f.coordinator.state.issue)
        }
    }

    @Test fun newOrAmbiguousRecordsAndCollectingErrorsDoNotEnterIdleRepolling() {
        val observations = listOf(
            idle.copy(bytes = 1) to emptyList(),
            idle to listOf(record),
            idle.copy(errorCode = -16, sessionId = 7) to emptyList(),
            active.copy(errorCode = -16) to listOf(record),
        )
        observations.forEach { (status, records) ->
            val f = Fixture()
            f.begin()
            f.time.advanceTo(1_500)
            f.observe(status, records)
            f.time.advanceTo(30_000)
            assertEquals(2, f.count("status"))
            assertEquals(1, f.count("start"))
            assertEquals(CaptureControlPhase.NEEDS_REVIEW, f.coordinator.state.phase)
            assertNull(f.store.readPending()!!.deviceRecordEvidence)
        }
    }

    @Test fun disconnectionDuringAnIdleRepollWaitInvalidatesItsDelayedQuery() {
        val f = Fixture()
        f.begin()
        f.time.advanceTo(1_500)
        f.observe(idle.copy(errorCode = -16))
        f.time.advanceTo(2_999)
        f.coordinator.onDisconnected(1)
        f.coordinator.onConnected(address, 2)
        f.time.advanceTo(30_000)
        assertEquals(2, f.count("status"))
        assertEquals(1, f.count("start"))
        assertNotNull(f.store.readPending())
        assertNull(f.store.readPending()!!.startConfirmedAtMs)
    }

    private fun interruptions(): List<(Fixture) -> Unit> = listOf(
        { it.coordinator.onDisconnected(1) },
        { it.coordinator.onConnected(address, 2) },
        { it.coordinator.refresh() },
        { it.coordinator.close() },
    )

    private inner class Fixture {
        val journal = File(temporary.newFolder(), "session.json")
        var failCommit = false
        var failSchedulingDelay: Long? = null
        var acceptStart = true
        var wallTime = epoch
        val store = FreeLivingSessionStore(journal, { source, target ->
            if (failCommit) throw IOException("Injected persistence failure")
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }, {})
        val time = ElapsedScheduler()
        val calls = mutableListOf<Pair<String, Long>>()
        val observations = mutableListOf<HealthRecordObservation>()
        val port = object : HealthControlPort {
            override fun queryStatus() = recordCall("status")
            override fun queryRecords() = recordCall("list")
            override fun start(): Boolean {
                assertEquals(FreeLivingSessionPhase.START_REQUESTED, store.readPending()!!.phase)
                recordCall("start")
                return acceptStart
            }
            override fun stop() = recordCall("stop")
        }
        val coordinator = FreeLivingCaptureCoordinator(store, port, object : CaptureClock {
            override fun nowEpochMs() = wallTime
            override fun timeZoneId() = "Asia/Shanghai"
        }, { delay, action ->
            if (delay == failSchedulingDelay) throw IllegalStateException("Injected scheduling failure")
            time.schedule(delay, action)
        }, { state -> state.observation?.let(observations::add) })
        fun begin() {
            coordinator.onConnected(address, 1)
            coordinator.requestStart(profile)
            observe(idle)
        }
        fun health(message: HealthMessage, connection: Long = 1) =
            coordinator.onHealth(connection, SensorPacket.Health(message, wallTime))
        fun observe(status: HealthMessage.Status, records: List<HealthMessage.ListItem> = emptyList()) {
            health(status)
            records.forEach { health(it) }
            health(HealthMessage.ListEnd(records.size))
        }
        fun count(command: String) = calls.count { it.first == command }
        fun times(command: String) = calls.filter { it.first == command }.map { it.second }
        private fun recordCall(command: String): Boolean { calls += command to time.now; return true }
    }

    private class ElapsedScheduler {
        var now = 0L
            private set
        private data class Task(val due: Long, val action: () -> Unit)
        private val tasks = mutableListOf<Task>()
        fun schedule(delay: Long, action: () -> Unit) { tasks += Task(now + delay, action) }
        fun advanceTo(target: Long) {
            require(target >= now)
            while (true) {
                val next = tasks.minByOrNull { it.due }?.takeIf { it.due <= target } ?: break
                tasks.remove(next)
                now = next.due
                next.action()
            }
            now = target
        }
    }
}
