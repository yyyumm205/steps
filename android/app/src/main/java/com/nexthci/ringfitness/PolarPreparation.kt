package com.nexthci.ringfitness

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeoutOrNull

/** Bounded preparation, with explicit disconnect acknowledgements fencing same-device retries. */
internal class PolarPreparationGate {
    data class Attempt internal constructor(val deviceId: String, private val generation: Long)
    data class Disconnection(val expected: Boolean, val retry: Attempt?)

    private var generation = 0L
    private var pending: Attempt? = null
    private var connectionIssued = false
    private val disconnecting = mutableSetOf<String>()
    var clientGeneration = 0L
        private set
    var clientReady = true
        private set

    fun begin(deviceId: String): Attempt = Attempt(deviceId, ++generation).also {
        pending = it
        connectionIssued = false
    }

    fun takeConnection(attempt: Attempt): Boolean {
        if (!clientReady || pending != attempt || connectionIssued || isDisconnecting(attempt.deviceId)) return false
        connectionIssued = true
        return true
    }

    fun beginDisconnection(deviceId: String): Boolean = disconnecting.add(deviceId)

    fun isDisconnecting(deviceId: String): Boolean = deviceId in disconnecting

    fun isCurrent(attempt: Attempt): Boolean = pending == attempt

    fun acceptsClient(generation: Long): Boolean = clientReady && clientGeneration == generation

    fun needsClientReplacement(deviceId: String): Boolean = !clientReady || isDisconnecting(deviceId)

    /** The SDK is a singleton: retire it successfully before constructing its replacement. */
    fun <T> replaceClient(shutdown: () -> Unit, create: (Long) -> T): T {
        val nextGeneration = ++clientGeneration
        clientReady = false
        shutdown()
        val replacement = create(nextGeneration)
        disconnecting.clear()
        connectionIssued = false
        clientReady = true
        return replacement
    }

    fun disconnected(deviceId: String): Disconnection {
        val expected = disconnecting.remove(deviceId)
        val retry = pending?.takeIf { expected && !connectionIssued && it.deviceId == deviceId }
        return Disconnection(expected, retry)
    }

    fun finish(attempt: Attempt): Boolean {
        if (pending != attempt) return false
        clear()
        return true
    }

    /** Retire the attempt while preserving old disconnects until SDK acknowledgement. */
    fun clear() {
        pending = null
        connectionIssued = false
    }
}

/** Includes Flow construction so synchronous permission failures also end the search. */
internal suspend fun <T> collectPolarScan(
    durationMs: Long,
    source: () -> Flow<T>,
    onDevice: (T) -> Unit,
): Exception? = try {
    withTimeoutOrNull(durationMs) { source().collect { onDevice(it) } }
    null
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    error
}
