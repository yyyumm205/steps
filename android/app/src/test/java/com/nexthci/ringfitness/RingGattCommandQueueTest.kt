package com.nexthci.ringfitness

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RingGattCommandQueueTest {
    private val battery = byteArrayOf(0x29, 0x01)
    private val info = byteArrayOf(0x2A, 0x01)
    private val status = byteArrayOf(0x32, 0x02)
    private val start = byteArrayOf(0x32, 0x00)

    @Test fun commandsWaitForMtuCallback() {
        val f = Fixture()
        f.queue.beginMtu { true }
        assertFalse(f.queue.enqueue(battery))
        assertEquals(0, f.readyCount)
        f.queue.onMtuChanged(247, 0)
        assertEquals(1, f.readyCount)
        assertTrue(f.queue.enqueue(battery))
        assertEquals(1, f.writes.size)
    }

    @Test fun rejectedMtuFailsWithoutPublishingReady() {
        val f = Fixture()
        f.queue.beginMtu { false }
        f.queue.onMtuChanged(247, 0)
        assertEquals(0, f.readyCount)
        assertEquals(1, f.failures.size)
        assertTrue(f.failures.single().contains("未受理"))
        assertFalse(f.queue.enqueue(start))
    }

    @Test fun mtuFailureAndInvalidMtuCannotBecomeReady() {
        for ((mtu, status) in listOf(247 to 133, 22 to 0)) {
            val f = Fixture()
            f.queue.beginMtu { true }
            f.queue.onMtuChanged(mtu, status)
            assertEquals(0, f.readyCount)
            assertEquals(1, f.failures.size)
        }
    }

    @Test fun mtuTimeoutDoesNotPretendThatConnectionIsReady() {
        val f = Fixture()
        f.queue.beginMtu { true }
        f.advance(5_000)
        f.queue.onMtuChanged(247, 0)
        assertEquals(0, f.readyCount)
        assertTrue(f.failures.single().contains("MTU 确认"))
    }

    @Test fun revokedPermissionDuringMtuProducesARecoverableFailure() {
        val f = Fixture()
        f.queue.beginMtu { throw SecurityException("permission revoked") }
        assertEquals(0, f.readyCount)
        assertEquals(1, f.failures.size)
        assertFalse(f.queue.enqueue(status))
    }

    @Test fun successfulMtuCancelsItsTimeoutAndDuplicateReplyDoesNotRequery() {
        val f = readyFixture()
        f.queue.onMtuChanged(247, 0)
        f.advance(5_000)
        assertEquals(1, f.readyCount)
        assertTrue(f.failures.isEmpty())
    }

    @Test fun bothWriteModesWaitForCallbackInsteadOfAFortyMillisecondGuess() {
        // The queue intentionally has no write-mode branch; Android reports local completion for both.
        val f = readyFixture()
        f.queue.enqueue(battery)
        f.queue.enqueue(info)
        f.advance(400)
        assertEquals(1, f.writes.size)
        f.queue.onWriteCompleted(0)
        f.advance(39)
        assertEquals(1, f.writes.size)
        f.advance(1)
        assertEquals(2, f.writes.size)
        assertArrayEquals(info, f.writes.last())
    }

    @Test fun enqueueDuringBackoffCannotBypassDelayOrConsumeRetryBudget() {
        val f = readyFixture()
        f.acceptWrites = false
        f.queue.enqueue(battery)
        f.queue.enqueue(info)
        f.queue.enqueue(status)
        assertEquals(listOf(0L), f.writeTimes)
        f.advance(119)
        assertEquals(1, f.writes.size)
        f.acceptWrites = true
        f.advance(1)
        assertEquals(listOf(0L, 120L), f.writeTimes)
        assertArrayEquals(battery, f.writes.last())
        f.queue.onWriteCompleted(0)
        f.advance(40)
        assertArrayEquals(info, f.writes.last())
        f.queue.onWriteCompleted(0)
        f.advance(40)
        assertArrayEquals(status, f.writes.last())
        assertTrue(f.failures.isEmpty())
    }

    @Test fun rejectedSubmissionsHaveBoundedSpacedRetriesAndDiscardQueuedCommands() {
        val f = readyFixture()
        f.acceptWrites = false
        f.queue.enqueue(battery)
        f.queue.enqueue(start)
        f.advance(1_000)
        assertEquals(listOf(0L, 120L, 240L, 360L), f.writeTimes)
        assertTrue(f.writes.all { it.contentEquals(battery) })
        assertEquals(1, f.failures.size)
        assertFalse(f.queue.enqueue(status))
    }

    @Test fun acceptedStartWithFailedCallbackIsNeverResubmittedOrFollowedByQueuedStop() {
        val f = readyFixture()
        f.queue.enqueue(start)
        f.queue.enqueue(byteArrayOf(0x32, 0x01))
        f.queue.onWriteCompleted(133)
        f.advance(10_000)
        assertEquals(1, f.writes.size)
        assertArrayEquals(start, f.writes.single())
        assertEquals(1, f.failures.size)
    }

    @Test fun acceptedStartWithMissingCallbackIsNeverResubmitted() {
        val f = readyFixture()
        f.queue.enqueue(start)
        f.queue.enqueue(status)
        f.advance(5_000)
        f.queue.onWriteCompleted(0)
        f.advance(5_000)
        assertEquals(1, f.writes.size)
        assertTrue(f.failures.single().contains("确认超时"))
    }

    @Test fun writeExceptionClearsQueueAndDoesNotRetryUnknownCommand() {
        val f = readyFixture()
        f.writeException = true
        f.queue.enqueue(start)
        f.queue.enqueue(status)
        f.advance(10_000)
        assertEquals(1, f.writes.size)
        assertEquals(1, f.failures.size)
    }

    @Test fun oldConnectionRetryCannotReleaseTheNewConnectionsInFlightWrite() {
        val f = readyFixture()
        f.acceptWrites = false
        f.queue.enqueue(battery)
        f.advance(10)
        f.queue.reset()
        f.queue.beginMtu { true }
        f.queue.onMtuChanged(247, 0)
        f.acceptWrites = true
        f.queue.enqueue(info)
        f.queue.enqueue(status)
        f.advance(200)
        assertEquals(2, f.writes.size)
        f.queue.onWriteCompleted(0)
        f.advance(40)
        assertArrayEquals(status, f.writes.last())
    }

    @Test fun oldConnectionTimeoutCannotFailAReconnectedDevice() {
        val f = readyFixture()
        f.queue.enqueue(battery)
        f.advance(1_000)
        f.queue.reset()
        f.queue.beginMtu { true }
        f.queue.onMtuChanged(247, 0)
        f.queue.enqueue(info)
        f.advance(4_000)
        assertTrue(f.failures.isEmpty())
        f.queue.onWriteCompleted(0)
        f.advance(1_000)
        assertTrue(f.failures.isEmpty())
    }

    @Test fun oldMtuTimeoutCannotFailNewConnectionInitialization() {
        val f = Fixture()
        f.queue.beginMtu { true }
        f.advance(1_000)
        f.queue.reset()
        f.queue.beginMtu { true }
        f.advance(4_000)
        assertTrue(f.failures.isEmpty())
        f.queue.onMtuChanged(247, 0)
        f.advance(1_000)
        assertEquals(1, f.readyCount)
        assertTrue(f.failures.isEmpty())
    }

    @Test fun completedCommandsKeepFifoAndCopyCallerBytes() {
        val f = readyFixture()
        f.queue.enqueue(battery)
        val callerPacket = info.copyOf()
        f.queue.enqueue(callerPacket)
        callerPacket[0] = 0
        f.queue.enqueue(status)
        repeat(3) {
            f.queue.onWriteCompleted(0)
            f.advance(40)
        }
        assertEquals(3, f.writes.size)
        assertArrayEquals(battery, f.writes[0])
        assertArrayEquals(info, f.writes[1])
        assertArrayEquals(status, f.writes[2])
        f.advance(5_000)
        assertTrue(f.failures.isEmpty())
    }

    @Test fun resetDuringGapPreventsAnOldTimerFromReleasingAnotherWrite() {
        val f = readyFixture()
        f.queue.enqueue(battery)
        f.queue.onWriteCompleted(0)
        f.queue.reset()
        f.queue.beginMtu { true }
        f.queue.onMtuChanged(247, 0)
        f.queue.enqueue(info)
        f.queue.enqueue(status)
        f.advance(40)
        assertEquals(2, f.writes.size)
    }

    private fun readyFixture() = Fixture().also {
        it.queue.beginMtu { true }
        it.queue.onMtuChanged(247, 0)
    }

    private class Fixture {
        var now = 0L
        var acceptWrites = true
        var writeException = false
        var readyCount = 0
        val writes = mutableListOf<ByteArray>()
        val writeTimes = mutableListOf<Long>()
        val failures = mutableListOf<String>()
        private val tasks = mutableListOf<Pair<Long, () -> Unit>>()
        val queue = RingGattCommandQueue(
            schedule = { delay, action -> tasks.add((now + delay) to action) },
            write = {
                writes.add(it.copyOf())
                writeTimes.add(now)
                if (writeException) throw SecurityException("permission revoked")
                acceptWrites
            },
            onReady = { readyCount++ },
            onFailure = { failures.add(it) },
        )

        fun advance(duration: Long) {
            val end = now + duration
            while (true) {
                val next = tasks.withIndex().filter { it.value.first <= end }
                    .minByOrNull { it.value.first } ?: break
                tasks.removeAt(next.index)
                now = next.value.first
                next.value.second()
            }
            now = end
        }
    }
}
