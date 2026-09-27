package com.nexthci.ringfitness

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolarPreparationTest {
    @Test fun pendingPreparationCanExpireOnlyOnce() {
        val gate = PolarPreparationGate()
        val attempt = gate.begin("H10-A")
        assertEquals("H10-A", attempt.deviceId)
        assertTrue(gate.finish(attempt))
        assertFalse(gate.finish(attempt))
    }

    @Test fun readyOrCancelledPreparationRetiresItsDeadline() {
        val gate = PolarPreparationGate()
        val attempt = gate.begin("H10-A")
        gate.clear()
        assertFalse(gate.finish(attempt))
    }

    @Test fun retryingTheSameDeviceRejectsTheOldDeadline() {
        val gate = PolarPreparationGate()
        val previous = gate.begin("H10-A")
        val retry = gate.begin("H10-A")
        assertFalse(gate.finish(previous))
        assertTrue(gate.finish(retry))
    }

    @Test fun switchingDeviceRejectsTheOldDeadline() {
        val gate = PolarPreparationGate()
        val previous = gate.begin("H10-A")
        val selected = gate.begin("H10-B")
        assertFalse(gate.finish(previous))
        assertTrue(gate.finish(selected))
    }

    @Test fun sameDeviceRetryWaitsForRetiredConnectionThenAcceptsAGenuineDisconnection() {
        val gate = PolarPreparationGate()
        val failed = gate.begin("H10-A")
        assertTrue(gate.takeConnection(failed))
        assertTrue(gate.finish(failed))
        assertTrue(gate.beginDisconnection("H10-A"))

        val retry = gate.begin("H10-A")
        assertFalse(gate.takeConnection(retry))
        assertTrue(gate.isDisconnecting("H10-A"))
        val retired = gate.disconnected("H10-A")
        assertTrue(retired.expected)
        assertEquals(retry, retired.retry)
        assertTrue(gate.takeConnection(requireNotNull(retired.retry)))
        assertFalse(gate.takeConnection(retry))
        assertFalse(gate.isDisconnecting("H10-A"))

        val genuine = gate.disconnected("H10-A")
        assertFalse(genuine.expected)
        assertNull(genuine.retry)
        assertTrue(gate.finish(retry))
    }

    @Test fun queuedRetryCanExpireBeforeOldDisconnectAcknowledgement() {
        val gate = PolarPreparationGate()
        assertTrue(gate.beginDisconnection("H10-A"))
        val queued = gate.begin("H10-A")
        assertFalse(gate.takeConnection(queued))
        assertTrue(gate.finish(queued))
        val retired = gate.disconnected("H10-A")
        assertTrue(retired.expected)
        assertNull(retired.retry)
        assertFalse(gate.takeConnection(queued))
        assertTrue(gate.takeConnection(gate.begin("H10-A")))
    }

    @Test fun resettingSelectionKeepsTheRetiredDeviceBarrier() {
        val gate = PolarPreparationGate()
        assertTrue(gate.beginDisconnection("H10-A"))
        gate.clear()
        val selected = gate.begin("H10-A")
        assertFalse(gate.takeConnection(selected))
        assertFalse(gate.beginDisconnection("H10-A"))
        assertEquals(selected, gate.disconnected("H10-A").retry)
        assertTrue(gate.takeConnection(selected))
    }

    @Test fun anotherDevicesRetirementCannotRestartTheCurrentConnection() {
        val gate = PolarPreparationGate()
        assertTrue(gate.beginDisconnection("H10-A"))
        val current = gate.begin("H10-B")
        assertTrue(gate.takeConnection(current))
        val retired = gate.disconnected("H10-A")
        assertTrue(retired.expected)
        assertNull(retired.retry)
        assertTrue(gate.finish(current))
    }

    @Test fun missingOrFailedDisconnectCanRecoverWithoutAnAcknowledgement() {
        // No-session and already-closed SDK paths return without a callback; permission
        // failures may throw instead. Each path must still permit a fresh SDK connection.
        val disconnectActions = listOf<() -> Unit>({}, {}, { throw SecurityException("permission denied") })
        disconnectActions.forEach { disconnect ->
            val gate = PolarPreparationGate()
            val oldGeneration = gate.clientGeneration
            assertTrue(gate.beginDisconnection("H10-A"))
            runCatching(disconnect)
            val retry = gate.begin("H10-A")
            assertFalse(gate.takeConnection(retry))
            val events = mutableListOf<String>()
            val replacement = gate.replaceClient(
                shutdown = {
                    events += "shutdown-old"
                    assertFalse(gate.acceptsClient(oldGeneration))
                },
                create = { generation ->
                    events += "create-new"
                    assertFalse(gate.acceptsClient(generation))
                    "fresh-sdk"
                },
            )
            assertEquals(listOf("shutdown-old", "create-new"), events)
            assertEquals("fresh-sdk", replacement)
            assertTrue(gate.clientReady)
            assertFalse(gate.acceptsClient(oldGeneration))
            assertTrue(gate.acceptsClient(gate.clientGeneration))
            assertTrue(gate.takeConnection(retry))
            // The current SDK's real disconnection still takes the ordinary failure path.
            assertFalse(gate.disconnected("H10-A").expected)
        }
    }

    @Test fun failedShutdownKeepsFactoryBlockedAndAllowsAnotherCleanupAttempt() {
        val gate = PolarPreparationGate()
        val oldGeneration = gate.clientGeneration
        val first = gate.begin("H10-A")
        val events = mutableListOf<String>()
        val failure = runCatching {
            gate.replaceClient(
                shutdown = { events += "shutdown-failed"; throw SecurityException("cleanup denied") },
                create = { events += "unexpected-create" },
            )
        }.exceptionOrNull()
        assertTrue(failure is SecurityException)
        assertEquals(listOf("shutdown-failed"), events)
        assertFalse(gate.clientReady)
        assertFalse(gate.acceptsClient(oldGeneration))
        assertFalse(gate.takeConnection(first))
        gate.clear()
        val retry = gate.begin("H10-A")
        gate.replaceClient(
            shutdown = { events += "shutdown-retried" },
            create = { events += "create-new" },
        )
        assertEquals(listOf("shutdown-failed", "shutdown-retried", "create-new"), events)
        assertFalse(gate.isCurrent(first))
        assertTrue(gate.takeConnection(retry))
    }

    @Test fun failedFactoryCanBeRetriedWhileOldCallbacksRemainFenced() {
        val gate = PolarPreparationGate()
        val oldGeneration = gate.clientGeneration
        val failure = runCatching {
            gate.replaceClient(shutdown = {}, create = { throw IllegalStateException("Bluetooth unavailable") })
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertFalse(gate.clientReady)
        assertFalse(gate.acceptsClient(oldGeneration))
        val failedGeneration = gate.clientGeneration
        gate.clear()
        val retry = gate.begin("H10-A")
        gate.replaceClient(shutdown = {}, create = { "fresh-sdk" })
        assertFalse(gate.acceptsClient(failedGeneration))
        assertTrue(gate.acceptsClient(gate.clientGeneration))
        assertTrue(gate.takeConnection(retry))
    }

    @Test fun failedConfigurationRetainsTheActualSingletonForTheNextCleanup() {
        val gate = PolarPreparationGate()
        var owner = "old-sdk"
        val events = mutableListOf<String>()
        val failure = runCatching {
            gate.replaceClient(
                shutdown = { events += "shutdown-$owner" },
                create = {
                    owner = "partly-configured-sdk"
                    events += "create-$owner"
                    throw IllegalStateException("callback setup failed")
                },
            )
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertFalse(gate.clientReady)
        val failedGeneration = gate.clientGeneration
        gate.replaceClient(
            shutdown = { events += "shutdown-$owner" },
            create = { owner = "fresh-sdk"; events += "create-$owner" },
        )
        assertEquals(listOf("shutdown-old-sdk", "create-partly-configured-sdk",
            "shutdown-partly-configured-sdk", "create-fresh-sdk"), events)
        assertFalse(gate.acceptsClient(failedGeneration))
        assertTrue(gate.acceptsClient(gate.clientGeneration))
    }

    @Test fun scanConstructionFailureReturnsImmediately() = runBlocking {
        val failure = SecurityException("permission denied")
        val actual = collectPolarScan<Int>(10_000, { throw failure }) { error("No devices expected") }
        assertScanFailure(failure, actual)
    }

    @Test fun scanFlowFailurePreservesDevicesAndItsFailure() = runBlocking {
        val error = IllegalStateException("scanner unavailable")
        val devices = mutableListOf<String>()
        val actual = collectPolarScan(10_000, {
            flow {
                emit("H10-A")
                throw error
            }
        }) { devices += it }
        assertEquals(listOf("H10-A"), devices)
        assertScanFailure(error, actual)
    }

    @Test fun completedEmptyScanHasNoFailure() = runBlocking {
        assertNull(collectPolarScan(10_000, { flowOf<String>() }) { error("No devices expected") })
    }

    @Test fun scanWindowCancelsTheCollectorWithoutReportingFailure() = runBlocking {
        val devices = mutableListOf<String>()
        var released = false
        val actual = collectPolarScan(100, {
            flow {
                try {
                    emit("H10-A")
                    awaitCancellation()
                } finally {
                    released = true
                }
            }
        }) { devices += it }
        assertNull(actual)
        assertEquals(listOf("H10-A"), devices)
        assertTrue(released)
    }

    @Test fun changingSelectionCancelsScanWithoutPublishingCompletion() = runBlocking {
        var returned = false
        var released = false
        val scan = launch(start = CoroutineStart.UNDISPATCHED) {
            collectPolarScan<String>(10_000, {
                flow {
                    try { awaitCancellation() } finally { released = true }
                }
            }) { error("No devices expected") }
            returned = true
        }
        scan.cancelAndJoin()
        assertTrue(released)
        assertFalse(returned)
    }

    private fun assertScanFailure(expected: Exception, actual: Exception?) {
        assertEquals(expected.javaClass, actual?.javaClass)
        assertEquals(expected.message, actual?.message)
        // Coroutine stack-trace recovery may wrap an exception while retaining its cause.
        assertTrue(generateSequence<Throwable>(actual) { it.cause }.any { it === expected })
    }
}
