package com.nexthci.ringfitness

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

class UnconfirmedStartAbortStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun abortRequestSurvivesRestartWithoutInventingSuccessfulStartOrReference() {
        val f = Fixture()
        f.request()
        val reopened = f.open().read()!!
        assertNotNull(reopened.startAbort)
        assertTrue(reopened.isPending)
        assertFalse(reopened.uploadAllowed)
        assertNull(reopened.startConfirmedAtMs)
        assertNull(reopened.stopConfirmedAtMs)
        assertNull(reopened.reference)
        assertThrows(IllegalArgumentException::class.java) {
            f.store.confirmStart(f.id, f.address, f.active.status, f.time + 200)
        }
        assertThrows(IllegalArgumentException::class.java) { f.store.manifestSnapshot(f.id) }
        assertEquals(reopened, f.store.requestUnconfirmedStartAbort(f.id, f.active, f.time + 210, f.owner))
    }

    @Test fun foreignConnectionStaleClockAndExistingFingerprintCannotGainStopAuthorization() {
        val f = Fixture()
        listOf(
            f.active.copy(connectionGeneration = 2),
            f.active.copy(address = "AA:BB:CC:DD:EE:02"),
            f.active.copy(status = f.active.status.copy(errorCode = -16)),
            f.active.copy(records = listOf(f.newRecord.copy(uptimeMs = 999))),
            f.active.copy(records = listOf(f.newRecord.copy(uptimeMs = 50_000))),
            f.active.copy(status = f.active.status.copy(sessionId = 7), records = listOf(f.oldRecord)),
        ).forEach { observed ->
            assertThrows(IllegalArgumentException::class.java) {
                f.store.requestUnconfirmedStartAbort(f.id, observed, f.time + 201, f.owner)
            }
            assertNull(f.store.read()!!.startAbort)
        }
        assertThrows(IllegalArgumentException::class.java) {
            f.store.requestUnconfirmedStartAbort(f.id, f.active, f.time + 201, UUID.randomUUID().toString())
        }
    }

    @Test fun stopConfirmationRequiresOriginalConnectionAndMonotonicStoppedIdentity() {
        val f = Fixture(); f.request()
        listOf(
            f.stopped.copy(connectionGeneration = 2),
            f.stopped.copy(status = f.stopped.status.copy(collecting = true)),
            f.stopped.copy(status = f.stopped.status.copy(sessionId = f.oldRecord.sessionId)),
            f.stopped.copy(status = f.stopped.status.copy(bytes = 1), records = listOf(f.newRecord.copy(bytes = 1))),
            f.stopped.copy(records = listOf(f.newRecord.copy(uptimeMs = 1501))),
        ).forEach { observed ->
            assertThrows(IllegalArgumentException::class.java) { f.store.confirmUnconfirmedStartAbortStop(f.id, observed) }
        }
        f.store.confirmUnconfirmedStartAbortStop(f.id, f.stopped)
        assertTrue(f.open().read()!!.isPending)
        assertEquals(f.stopped, f.open().read()!!.startAbort!!.stoppedObservation)
    }

    @Test fun onlyANewChannelsCompleteUnassignedBackupCanReleaseTheAttempt() {
        val f = Fixture(); f.request()
        f.store.confirmUnconfirmedStartAbortStop(f.id, f.stopped)
        val proof = f.preserve()
        assertThrows(IllegalArgumentException::class.java) {
            f.store.completeUnconfirmedStartAbort(f.id, proof.copy(connectionGeneration = 1),
                f.fresh.copy(connectionGeneration = 1), f.time + 302)
        }
        assertThrows(IllegalArgumentException::class.java) {
            f.store.completeUnconfirmedStartAbort(f.id, proof.copy(rawSha256 = "b".repeat(64)), f.fresh, f.time + 302)
        }
        val completed = f.store.completeUnconfirmedStartAbort(f.id, proof, f.fresh, f.time + 302)
        assertNull(f.open().readPending())
        assertFalse(completed.uploadAllowed)
        assertNull(completed.reference)
        assertNull(completed.localData)
        assertNull(completed.startConfirmedAtMs)
        assertNull(completed.stopConfirmedAtMs)
        assertEquals(proof, f.open().read()!!.startAbort!!.preservation)
        assertThrows(IllegalArgumentException::class.java) { f.store.manifestSnapshot(f.id) }
        assertNotEquals(f.id, f.store.requestStart(f.profile, f.time + 500, "UTC").sessionId)
        assertTrue(f.backupDirectory.walkTopDown().any { it.extension == "rfbin" })
    }

    @Test fun aNewProcessMayPreserveStoppedEvidenceWithoutClaimingConnectionContinuity() {
        val f = Fixture(); f.request()
        f.store.confirmUnconfirmedStartAbortStop(f.id, f.stopped)
        val proof = f.preserve().copy(ownerId = UUID.randomUUID().toString(), connectionGeneration = 1)
        f.store.completeUnconfirmedStartAbort(f.id, proof, f.fresh.copy(connectionGeneration = 1), f.time + 302)
        assertNull(f.open().readPending())
        assertNotEquals(f.owner, f.open().read()!!.startAbort!!.preservation!!.ownerId)
    }

    @Test fun commitFailurePreservesProtectedAttemptAndCompletedOriginal() {
        val f = Fixture(); f.request()
        f.store.confirmUnconfirmedStartAbortStop(f.id, f.stopped)
        val proof = f.preserve()
        f.failCommit = true
        assertThrows(IOException::class.java) { f.store.completeUnconfirmedStartAbort(f.id, proof, f.fresh, f.time + 302) }
        assertTrue(f.open().read()!!.isPending)
        assertTrue(f.backupDirectory.walkTopDown().any { it.extension == "rfbin" })
        f.failCommit = false
        f.store.completeUnconfirmedStartAbort(f.id, proof, f.fresh, f.time + 302)
        assertNull(f.store.readPending())
    }

    @Test fun emptyStoppedRecordCompletesWithFreshSnapshotAndNoInventedFile() {
        val f = Fixture()
        val record = f.newRecord.copy(bytes = 0, records = 0)
        val active = f.active.copy(status = f.active.status.copy(bytes = 0, records = 0), records = listOf(f.oldRecord, record))
        val stopped = active.copy(status = active.status.copy(collecting = false))
        f.store.requestUnconfirmedStartAbort(f.id, active, f.time + 201, f.owner)
        f.store.confirmUnconfirmedStartAbortStop(f.id, stopped)
        assertThrows(IllegalArgumentException::class.java) {
            f.store.completeUnconfirmedStartAbort(f.id, null, stopped, f.time + 302)
        }
        f.store.completeUnconfirmedStartAbort(f.id, null, stopped.copy(connectionGeneration = 2), f.time + 302)
        assertNull(f.open().readPending())
        assertNull(f.store.read()!!.startAbort!!.preservation)
        assertFalse(f.backupDirectory.exists())
        assertNull(f.store.read()!!.localData)
    }

    @Test fun aNonemptyStoppedRecordCannotReleaseWithoutItsOriginal() {
        val f = Fixture(); f.request()
        f.store.confirmUnconfirmedStartAbortStop(f.id, f.stopped)
        assertThrows(IllegalArgumentException::class.java) {
            f.store.completeUnconfirmedStartAbort(f.id, null, f.fresh, f.time + 302)
        }
        assertTrue(f.store.read()!!.isPending)
    }

    private inner class Fixture {
        val directory = temporary.newFolder().canonicalFile
        val backupDirectory = File(directory, "device-backups")
        val time = 1_800_000_000_000L
        val owner = UUID.randomUUID().toString()
        val address = "AA:BB:CC:DD:EE:01"
        val profile = PreparationSnapshot("abort001", UUID.randomUUID().toString(), RingPlacement.RIGHT_INDEX,
            PreparedRing(address, "Abort fixture"))
        val payload = ByteArrayOutputStream().apply {
            write(0x32); write(0x12); write(2)
            repeat(4) { write((1500L ushr (8 * it)).toInt()) }; repeat(12) { write(it) }
        }.toByteArray()
        val oldRecord = HealthMessage.ListItem(7, payload.size.toLong(), 1, 900_000, 0)
        val newRecord = oldRecord.copy(sessionId = 8, uptimeMs = 1_500)
        val proof = UnknownTimeStartEvidence(oldRecord, UUID.randomUUID().toString(), "a".repeat(64), owner, 1, time - 100,
            PhoneClockSyncEvidence(UUID.randomUUID().toString(), address, 1, time, time + 10, 100, 110, time + 5, 1_000))
        val baseline = DeviceStartBaseline(HealthMessage.Status(false, oldRecord.bytes, 1, 0, 7), listOf(oldRecord),
            time + 20, unknownTimeStartEvidence = proof)
        val active = HealthRecordObservation(address, 1, HealthMessage.Status(true, newRecord.bytes, 1, 0, 8),
            time + 200, listOf(oldRecord, newRecord))
        val stopped = active.copy(status = active.status.copy(collecting = false), statusReceivedAtMs = time + 250)
        val fresh = stopped.copy(connectionGeneration = 2, statusReceivedAtMs = time + 301)
        var failCommit = false
        fun open() = FreeLivingSessionStore(File(directory, "session.json"), { source, destination ->
            if (failCommit) throw IOException("Injected commit failure")
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }, {})
        val store = open()
        val id = store.requestStart(profile, time + 30, "UTC", baseline).sessionId
        fun request() = store.requestUnconfirmedStartAbort(id, active, time + 201, owner)
        fun preserve(): UnknownTimeRecordProof {
            val backupId = UUID.randomUUID().toString()
            val backups = DeviceRecordBackupStore(backupDirectory) {}
            val completed = backups.open(address, newRecord, backupId).use { download ->
                download.append(HealthMessage.DataChunk(0, payload))
                download.finish(HealthMessage.ReadEnd(payload.size.toLong(), true))
            }
            backups.accept(address, newRecord, completed, time + 300, backupId)
            return UnknownTimeRecordProof(newRecord, backupId, completed.file.sha256, owner, 2, time + 300)
        }
    }
}
