package com.nexthci.ringfitness

import java.util.Collections
import java.util.IdentityHashMap

/** Main-thread protocol; durable queue reads and writes remain on background executors. */
internal class UploadJobCoordination {
    enum class Scheduling { KEEP_ACTIVE, IF_ABSENT, REPLACE_FINISHED }
    private data class Run(val owner: Any, val enqueueVersion: Long)
    private var enqueueVersion = 0L
    private var active: Run? = null
    private var finishedWithoutRetry = false

    fun enqueued(): Scheduling {
        enqueueVersion++
        return when {
            active != null -> Scheduling.KEEP_ACTIVE
            finishedWithoutRetry -> Scheduling.REPLACE_FINISHED
            else -> Scheduling.IF_ABSENT
        }
    }

    fun scheduled() { finishedWithoutRetry = false }

    fun started(owner: Any) {
        active = Run(owner, enqueueVersion)
        finishedWithoutRetry = false
    }

    /** Null means a retired worker, which cannot finish or cancel its replacement's job. */
    fun finished(owner: Any, retry: Boolean): Boolean? {
        val run = active?.takeIf { it.owner === owner } ?: return null
        val needsRetry = retry || run.enqueueVersion != enqueueVersion
        active = null
        // JobScheduler can still report the old pending job after jobFinished(false).
        // A later enqueue must replace that finished identity even before system cleanup.
        finishedWithoutRetry = !needsRetry
        return needsRetry
    }

    fun stopped(owner: Any) {
        if (active?.owner !== owner) return
        active = null
        finishedWithoutRetry = false // onStopJob(true) asks Android to retain the job.
    }
}

/** Multiple stopped/replacement workers may briefly overlap while waiting for the delivery lock. */
internal class UploadFlightOwners {
    private val owners = mutableMapOf<String, MutableSet<Any>>()

    @Synchronized fun begin(sessionId: String, owner: Any) {
        owners.getOrPut(sessionId) { Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()) }.add(owner)
    }

    @Synchronized fun end(sessionId: String, owner: Any) {
        val current = owners[sessionId] ?: return
        current.remove(owner)
        if (current.isEmpty()) owners.remove(sessionId)
    }

    @Synchronized fun isInFlight(sessionId: String) = owners[sessionId]?.isNotEmpty() == true
}
