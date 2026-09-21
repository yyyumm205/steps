package com.nexthci.ringfitness

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class FreeLivingDeviceEvidenceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val t = 1_789_804_800_000L
    private val preparation = PreparationSnapshot("p001", "83e56afb-a74c-4825-af0b-b226cfe9c630",
        RingPlacement.LEFT_INDEX, PreparedRing("AA:BB:CC:DD:EE:01", "Ringo fixture"))
    private val idle = HealthMessage.Status(false, 0, 0, 0, 9)
    private val active = HealthMessage.Status(true, 32, 2, 0, 42)
    private val record = HealthMessage.ListItem(42, 32, 2, 1000, t)
    private val stopped = active.copy(collecting = false, bytes = 64, records = 4)
    private val finalRecord = record.copy(bytes = 64, records = 4)

    @Test fun versionTwoJournalMigratesOnWriteWithoutInventingAnAssociation() {
        val file = file()
        val store = open(file)
        val request = store.requestStart(preparation, t, "UTC")
        val original = store.confirmStart(request.sessionId, preparation.ring!!.address, active, t + 1)
        val envelope = JsonParser.parseString(file.readText()).asJsonObject
        val payload = envelope.getAsJsonObject("session")
        listOf("start_baseline", "device_record_evidence", "device_association_invalidated",
            "start_attempt_archive", "completion_policy", "discarded", "start_abort",
            "reference_revisions", "start_command_dispatch", "stop_observed_at_ms", "stop_origin",
            "stop_command_dispatch").forEach(payload::remove)
        envelope.addProperty("journal_version", 2)
        updateChecksum(envelope)
        file.writeText(envelope.toString())
        assertEquals(original, open(file).read())
        val bytes = file.readBytes()
        assertNull(open(file).read()!!.deviceRecordEvidence)
        assertNull(open(file).read()!!.startAttemptArchive)
        assertArrayEquals(bytes, file.readBytes())
        open(file).requestStop(original.sessionId, t + 2)
        assertEquals(13, JsonParser.parseString(file.readText()).asJsonObject["journal_version"].asInt)
        assertNull(open(file).read()!!.deviceRecordEvidence)
        assertNull(open(file).read()!!.startAttemptArchive)
    }

    @Test fun associationAndUncertaintySurviveReferenceAndLocalCompletion() {
        val file = file()
        val store = open(file)
        val session = stop(store)
        val saved = store.saveReference(session.sessionId, SessionReference(ReferenceStatus.VALID, 0, t + 3))
        store.completeLocalData(saved.sessionId, listOf(raw(file, saved)), t + 4)
        val reopened = open(file).read()!!
        assertEquals(DeviceStartBaseline(idle, emptyList(), t), reopened.startBaseline)
        assertEquals(DeviceRecordEvidence(finalRecord, stopped, t + 2), reopened.deviceRecordEvidence)
        assertEquals(0L, reopened.reference!!.steps)
        assertNull(reopened.startedAtMs)
        assertNull(reopened.endedAtMs)
        assertEquals("uncertain", reopened.captureBoundaryStatus)
    }

    @Test fun preservedRecordAuthorizationRequiresExactRecordAddressRealFileAndCurrentHash() {
        val file = file()
        val store = open(file)
        val session = stop(store)
        store.saveReference(session.sessionId, SessionReference(ReferenceStatus.MISSING, null, t + 3, "无法读取"))
        assertFalse(store.hasPreservedDeviceRecords(preparation.ring!!.address, listOf(finalRecord)))
        val entry = raw(file, session)
        store.completeLocalData(session.sessionId, listOf(entry), t + 4)
        assertTrue(store.hasPreservedDeviceRecords(preparation.ring.address, listOf(finalRecord)))
        assertFalse(store.hasPreservedDeviceRecords("AA:BB:CC:DD:EE:99", listOf(finalRecord)))
        assertFalse(store.hasPreservedDeviceRecords(preparation.ring.address, listOf(finalRecord.copy(bytes = 65))))
        assertFalse(store.hasPreservedDeviceRecords(preparation.ring.address, listOf(finalRecord, record.copy(sessionId = 99))))
        File(file.parentFile, entry.fileName).appendText("changed")
        assertThrows(IOException::class.java) { store.hasPreservedDeviceRecords(preparation.ring.address, listOf(finalRecord)) }
    }

    @Test fun simulatedFilesNeverAuthorizeReplacingARealDeviceRecord() {
        val file = file()
        val store = open(file)
        val session = stop(store)
        store.saveReference(session.sessionId, SessionReference(ReferenceStatus.VALID, 2, t + 3))
        store.completeLocalData(session.sessionId, listOf(raw(file, session).copy(simulated = true)), t + 4)
        assertFalse(store.hasPreservedDeviceRecords(preparation.ring!!.address, listOf(finalRecord)))
    }

    @Test fun anUnknownClockRecordCannotAuthorizeReplacementAfterItWasSaved() {
        val file = file()
        val store = open(file)
        val unknownClockRecord = record.copy(unixMs = 0)
        val unknownClockFinal = finalRecord.copy(unixMs = 0)
        val session = store.requestStart(preparation, t, "UTC", DeviceStartBaseline(idle, emptyList(), t))
        store.confirmStart(session.sessionId, preparation.ring!!.address, active, t + 1,
            recordEvidence = DeviceRecordEvidence(unknownClockRecord, active, t + 1))
        store.requestStop(session.sessionId, t + 2)
        val stoppedSession = store.confirmStop(session.sessionId, preparation.ring.address, stopped, t + 3,
            recordEvidence = DeviceRecordEvidence(unknownClockFinal, stopped, t + 3))
        store.saveReference(session.sessionId, SessionReference(ReferenceStatus.VALID, 2, t + 4))
        store.completeLocalData(session.sessionId, listOf(raw(file, stoppedSession)), t + 5)
        assertFalse(open(file).hasPreservedDeviceRecords(preparation.ring.address, listOf(unknownClockFinal)))
    }

    @Test fun invalidatedAssociationStaysRevokedAcrossProcessRestart() {
        val file = file()
        val store = open(file)
        val session = start(store)
        store.invalidateDeviceAssociation(session.sessionId)
        assertTrue(open(file).read()!!.deviceAssociationInvalidated)
        assertThrows(IllegalArgumentException::class.java) {
            open(file).updateDeviceEvidence(session.sessionId, DeviceRecordEvidence(record, active, t + 10))
        }
        store.requestStop(session.sessionId, t + 11)
        assertThrows(IllegalArgumentException::class.java) {
            store.confirmStop(session.sessionId, preparation.ring!!.address, stopped, t + 12,
                recordEvidence = DeviceRecordEvidence(finalRecord, stopped, t + 12))
        }
        assertNull(store.read()!!.stopConfirmedAtMs)
    }

    @Test fun aStopCannotReplaceThePreviouslyProvedFingerprint() {
        val file = file()
        val store = open(file)
        val session = start(store)
        store.requestStop(session.sessionId, t + 2)
        assertThrows(IllegalArgumentException::class.java) {
            store.confirmStop(session.sessionId, preparation.ring!!.address, stopped, t + 3,
                recordEvidence = DeviceRecordEvidence(finalRecord.copy(unixMs = t + 1), stopped, t + 3))
        }
        assertNull(store.read()!!.stopConfirmedAtMs)
        assertEquals(record, store.read()!!.deviceRecordEvidence!!.record)
    }

    @Test fun evidenceIntegrityIsCheckedBeforeRestoringDeviceAuthority() {
        val file = file()
        start(open(file))
        val envelope = JsonParser.parseString(file.readText()).asJsonObject
        envelope.getAsJsonObject("session").getAsJsonObject("device_record_evidence")
            .getAsJsonObject("record").addProperty("unix_ms", t + 99)
        file.writeText(envelope.toString())
        val corrupt = file.readBytes()
        assertThrows(IOException::class.java) { open(file).read() }
        assertArrayEquals(corrupt, file.readBytes())
    }

    @Test fun startEvidenceCannotReuseAnUnchangedFingerprintFromTheIdleBaseline() {
        val file = file()
        val store = open(file)
        val baselineStatus = active.copy(collecting = false)
        val session = store.requestStart(preparation, t, "UTC", DeviceStartBaseline(baselineStatus, listOf(record), t))
        assertThrows(IllegalArgumentException::class.java) {
            store.confirmStart(session.sessionId, preparation.ring!!.address, active, t + 1,
                recordEvidence = DeviceRecordEvidence(record, active, t + 1))
        }
        assertNull(store.read()!!.startConfirmedAtMs)
    }

    private fun start(store: FreeLivingSessionStore): FreeLivingSession {
        val session = store.requestStart(preparation, t, "UTC", DeviceStartBaseline(idle, emptyList(), t))
        return store.confirmStart(session.sessionId, preparation.ring!!.address, active, t + 1,
            recordEvidence = DeviceRecordEvidence(record, active, t + 1))
    }

    private fun stop(store: FreeLivingSessionStore): FreeLivingSession {
        val session = start(store)
        store.requestStop(session.sessionId, t + 2)
        return store.confirmStop(session.sessionId, preparation.ring!!.address, stopped, t + 2,
            recordEvidence = DeviceRecordEvidence(finalRecord, stopped, t + 2))
    }

    private fun file() = File(temporary.newFolder(), "session.json")
    private fun open(file: File) = FreeLivingSessionStore(file, { source, target ->
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }, {})

    private fun raw(journal: File, session: FreeLivingSession): SessionRawFile {
        val file = File(journal.parentFile, "${session.sessionId}-42.rfbin")
        file.writeBytes(ByteArray(128) { it.toByte() })
        return SessionRawFile(file.name, 42, file.length(), digest(file.readBytes()))
    }

    private fun updateChecksum(envelope: JsonObject) {
        val payload = JsonObject().apply {
            add("session", envelope["session"])
            add("archived_sessions", envelope["archived_sessions"])
        }
        envelope.addProperty("sha256", digest(payload.toString().toByteArray()))
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
