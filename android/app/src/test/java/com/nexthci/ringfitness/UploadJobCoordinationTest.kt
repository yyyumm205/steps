package com.nexthci.ringfitness

import org.junit.Assert.*
import org.junit.Test

class UploadJobCoordinationTest {
    @Test fun enqueueAfterWorkersLastEmptyCheckKeepsARescheduledJob() {
        val coordination = UploadJobCoordination()
        val owner = Any()
        assertEquals(UploadJobCoordination.Scheduling.IF_ABSENT, coordination.enqueued())
        coordination.scheduled()
        coordination.started(owner)
        // Worker already found no queued IDs. The request arrives before jobFinished on main.
        val workerSawMoreTasks = false
        assertEquals(UploadJobCoordination.Scheduling.KEEP_ACTIVE, coordination.enqueued())
        assertEquals(true, coordination.finished(owner, workerSawMoreTasks))
    }

    @Test fun enqueueAfterFinishedReplacesTheStaleSystemPendingIdentity() {
        val coordination = UploadJobCoordination()
        val owner = Any()
        coordination.started(owner)
        assertEquals(false, coordination.finished(owner, false))
        // Android has not removed the old pending entry yet; it must not mask this new task.
        val systemStillReportsOldPendingJob = true
        val request = coordination.enqueued()
        val shouldSchedule = request == UploadJobCoordination.Scheduling.REPLACE_FINISHED ||
            request == UploadJobCoordination.Scheduling.IF_ABSENT && !systemStillReportsOldPendingJob
        assertTrue(shouldSchedule)
        coordination.scheduled()
        assertEquals(UploadJobCoordination.Scheduling.IF_ABSENT, coordination.enqueued())
    }

    @Test fun activeJobIsNotReplacedWhenAnotherRecordIsQueued() {
        val coordination = UploadJobCoordination()
        val owner = Any()
        coordination.started(owner)
        repeat(3) { assertEquals(UploadJobCoordination.Scheduling.KEEP_ACTIVE, coordination.enqueued()) }
        assertEquals(true, coordination.finished(owner, false))
        val replacement = Any()
        coordination.started(replacement)
        assertEquals(false, coordination.finished(replacement, false))
    }

    @Test fun cancelledOldWorkerCannotFinishOrDetachItsReplacement() {
        val coordination = UploadJobCoordination()
        val old = Any()
        val current = Any()
        coordination.started(old)
        coordination.stopped(old)
        coordination.started(current)
        coordination.stopped(old)
        assertNull(coordination.finished(old, false))
        assertEquals(UploadJobCoordination.Scheduling.KEEP_ACTIVE, coordination.enqueued())
        assertEquals(true, coordination.finished(current, false))
    }

    @Test fun workerRequestedRetryKeepsAndroidsPendingJobWithoutForcedReplacement() {
        val coordination = UploadJobCoordination()
        val owner = Any()
        coordination.started(owner)
        assertEquals(true, coordination.finished(owner, true))
        assertEquals(UploadJobCoordination.Scheduling.IF_ABSENT, coordination.enqueued())
        assertNull(coordination.finished(owner, false))
    }

    @Test fun unsuccessfulReplacementSchedulingKeepsTheNextRequestEligibleToReplace() {
        val coordination = UploadJobCoordination()
        val owner = Any()
        coordination.started(owner)
        coordination.finished(owner, false)
        assertEquals(UploadJobCoordination.Scheduling.REPLACE_FINISHED, coordination.enqueued())
        // schedule() failed; no scheduled() acknowledgment is sent.
        assertEquals(UploadJobCoordination.Scheduling.REPLACE_FINISHED, coordination.enqueued())
    }

    @Test fun finishingOldUploadCannotClearTheReplacementOwnersInFlightState() {
        val owners = UploadFlightOwners()
        val old = Any()
        val current = Any()
        owners.begin("session", old)
        owners.begin("session", current)
        owners.end("session", old)
        assertTrue(owners.isInFlight("session"))
        owners.end("session", old)
        assertTrue(owners.isInFlight("session"))
        owners.end("session", current)
        assertFalse(owners.isInFlight("session"))
    }

    @Test fun cancelledReplacementDoesNotClearAnOldUploadThatHasNotReturnedYet() {
        val owners = UploadFlightOwners()
        val old = Any()
        val current = Any()
        owners.begin("session", old)
        owners.begin("session", current)
        owners.end("session", current)
        assertTrue(owners.isInFlight("session"))
        owners.end("session", Any())
        assertTrue(owners.isInFlight("session"))
        owners.end("session", old)
        assertFalse(owners.isInFlight("session"))
    }

    @Test fun ownerRegistrationIsIdempotentAndDifferentRecordsRemainIndependent() {
        val owners = UploadFlightOwners()
        val owner = Any()
        owners.begin("first", owner)
        owners.begin("first", owner)
        owners.begin("second", owner)
        owners.end("first", owner)
        assertFalse(owners.isInFlight("first"))
        assertTrue(owners.isInFlight("second"))
        owners.end("second", owner)
        assertFalse(owners.isInFlight("second"))
    }
}
