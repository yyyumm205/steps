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
import java.util.zip.CRC32

class StartAttemptArchiveTest {
    @get:Rule val temporary = TemporaryFolder()
    private val epoch = 1_789_804_800_000L
    private val profile = PreparationSnapshot("archive001", "83e56afb-a74c-4825-af0b-b226cfe9c630",
        RingPlacement.LEFT_INDEX, PreparedRing("AA:BB:CC:DD:EE:01", "Ringo archive fixture"))
    private val reason = "体验点击，未进行正式采集"
    private val idle = HealthMessage.Status(false, 0, 0, 0, 0)
    private val payload = byteArrayOf(0x32, 0x12, 1, 1, 0, 0, 0, 1, 0, 2, 0, 3, 0)
    private val record = HealthMessage.ListItem(1, payload.size.toLong(), 1, 1_000, epoch)
    private val stopped = HealthMessage.Status(false, record.bytes, record.records, 0, 1)

    @Test fun archivePreservesAuditAndOldManifestWhileReleasingOnlyTheUnconfirmedRequest() {
        val f = Fixture()
        val oldManifest = f.store.manifestSnapshot(f.saved.sessionId)
        val oldBytes = f.raw.readBytes()
        val archived = f.archive()
        assertEquals(reason, archived.startAttemptArchive!!.reason)
        assertEquals(-16, archived.startAttemptArchive.observation.status.errorCode)
        assertNull(archived.startConfirmedAtMs)
        assertNull(archived.stopRequestedAtMs)
        assertNull(archived.stopConfirmedAtMs)
        assertNull(archived.reference)
        assertNull(archived.localData)
        assertNull(f.open().readPending())
        assertEquals(archived, f.open().read(f.pending.sessionId))
        assertArrayEquals(oldBytes, f.raw.readBytes())
        assertEquals(oldManifest, f.store.manifestSnapshot(f.saved.sessionId))
        assertFalse(oldManifest.has("start_attempt_archive"))
        assertThrows(IllegalArgumentException::class.java) { f.store.manifestSnapshot(archived.sessionId) }
        val next = f.store.requestStart(profile, epoch + 200, "Asia/Shanghai", f.baseline)
        assertNotEquals(f.pending.sessionId, next.sessionId)
        assertEquals(listOf(f.saved.sessionId, f.pending.sessionId, next.sessionId), f.open().listSessions().map { it.sessionId })
        assertEquals(next, f.open().readPending())
    }

    @Test fun duplicateArchiveIsIdempotentAndCannotTurnIntoCaptureConfirmation() {
        val f = Fixture()
        val archived = f.archive()
        val bytes = f.journal.readBytes()
        assertEquals(archived, f.store.archiveStartAttempt(archived.sessionId, f.observation, epoch + 999, reason))
        assertArrayEquals(bytes, f.journal.readBytes())
        assertThrows(IllegalArgumentException::class.java) {
            f.store.confirmStart(archived.sessionId, profile.ring!!.address, stopped.copy(collecting = true), epoch + 999)
        }
        assertEquals(archived, f.open().read())
    }

    @Test fun anyChangedOrUncertainDeviceEvidenceKeepsTheRequestPending() {
        val changes: List<(HealthRecordObservation) -> HealthRecordObservation> = listOf(
            { it.copy(status = it.status.copy(collecting = true)) },
            { it.copy(status = it.status.copy(bytes = it.status.bytes + 1)) },
            { it.copy(status = it.status.copy(records = 2)) },
            { it.copy(status = it.status.copy(sessionId = 2)) },
            { it.copy(status = it.status.copy(errorCode = -99)) },
            { it.copy(address = "AA:BB:CC:DD:EE:02") },
            { it.copy(connectionGeneration = 0) },
            { it.copy(records = emptyList()) },
            { it.copy(records = it.records + record.copy(sessionId = 2)) },
            { it.copy(records = listOf(record.copy(bytes = record.bytes + 1))) },
            { it.copy(records = listOf(record.copy(unixMs = record.unixMs + 1))) },
            { it.copy(records = listOf(record.copy(uptimeMs = record.uptimeMs + 1))) },
        )
        changes.forEach { change ->
            val f = Fixture()
            val before = f.journal.readBytes()
            assertThrows(IllegalArgumentException::class.java) {
                f.store.archiveStartAttempt(f.pending.sessionId, change(f.observation), epoch + 100, reason)
            }
            assertEquals(f.pending, f.open().readPending())
            assertArrayEquals(before, f.journal.readBytes())
        }
    }

    @Test fun missingOrDamagedLocalOriginalKeepsTheRequestPending() {
        for (missing in listOf(false, true)) {
            val f = Fixture()
            if (missing) assertTrue(f.raw.delete()) else f.raw.appendBytes(byteArrayOf(1))
            assertThrows(IOException::class.java) { f.archive() }
            assertEquals(f.pending, f.open().readPending())
        }
    }

    @Test fun crcIsRecheckedEvenWhenSavedShaMatchesTheOriginalFile() {
        val f = Fixture(invalidCrc = true)
        assertThrows(IllegalArgumentException::class.java) { f.archive() }
        assertEquals(f.pending, f.open().readPending())
    }

    @Test fun unchangedUnknownTimeBaselineCanBeAuditedButKnownUnpreservedDataStaysProtected() {
        fun scenario(unixMs: Long): Triple<FreeLivingSessionStore, FreeLivingSession, HealthRecordObservation> {
            val journal = File(temporary.newFolder(), "session.json")
            val store = open(journal)
            val baseline = DeviceStartBaseline(stopped, listOf(record.copy(unixMs = unixMs)), epoch)
            val pending = store.requestStart(profile, epoch + 1, "UTC", baseline)
            return Triple(store, pending,
                HealthRecordObservation(profile.ring!!.address, 2, stopped, epoch + 2, baseline.records))
        }
        val (unknownStore, unknownPending, unknownObservation) = scenario(0L)
        unknownStore.archiveStartAttempt(unknownPending.sessionId, unknownObservation, epoch + 3, reason)
        assertNull(unknownStore.readPending())

        val (knownStore, knownPending, knownObservation) = scenario(epoch)
        assertThrows(IllegalArgumentException::class.java) {
            knownStore.archiveStartAttempt(knownPending.sessionId, knownObservation, epoch + 3, reason)
        }
        assertEquals(knownPending, knownStore.readPending())
    }

    @Test fun failedAtomicCommitRetainsTheOriginalRequestAndCanBeRetried() {
        val f = Fixture()
        val before = f.journal.readBytes()
        f.failCommit = true
        assertThrows(IOException::class.java) { f.archive() }
        assertArrayEquals(before, f.journal.readBytes())
        assertEquals(f.pending, f.open().readPending())
        f.failCommit = false
        f.archive()
        assertNull(f.open().readPending())
    }

    @Test fun anEmptyBaselineNeedsNoSyntheticFilesAndStillRequiresExplicitReason() {
        val journal = File(temporary.newFolder(), "session.json")
        val store = open(journal)
        val pending = store.requestStart(profile, epoch, "UTC", DeviceStartBaseline(idle, emptyList(), epoch))
        val observation = HealthRecordObservation(profile.ring!!.address, 1, idle, epoch + 1, emptyList())
        assertThrows(IllegalArgumentException::class.java) {
            store.archiveStartAttempt(pending.sessionId, observation, epoch + 2, "")
        }
        store.archiveStartAttempt(pending.sessionId, observation, epoch + 2, reason)
        assertNull(store.readPending())
        assertNull(store.read()!!.localData)
    }

    @Test fun versionThreeJournalUpgradesWithoutChangingFrozenResearchMetadata() {
        val f = Fixture()
        val manifest = f.store.manifestSnapshot(f.saved.sessionId)
        val envelope = JsonParser.parseString(f.journal.readText()).asJsonObject
        envelope.addProperty("journal_version", 3)
        envelope.getAsJsonObject("session").remove("start_attempt_archive")
        envelope.getAsJsonArray("archived_sessions").forEach { it.asJsonObject.remove("start_attempt_archive") }
        (listOf(envelope.getAsJsonObject("session")) + envelope.getAsJsonArray("archived_sessions").map { it.asJsonObject })
            .forEach {
                it.remove("completion_policy"); it.remove("discarded"); it.remove("start_abort")
                it.remove("reference_revisions"); it.remove("start_command_dispatch")
                it.remove("stop_observed_at_ms"); it.remove("stop_origin"); it.remove("stop_command_dispatch")
                it.get("start_baseline").takeUnless { value -> value.isJsonNull }?.asJsonObject?.let { baseline ->
                    baseline.remove("charging_recovery_evidence"); baseline.remove("unknown_time_start_evidence")
                }
            }
        val data = JsonObject().apply {
            add("session", envelope.get("session")); add("archived_sessions", envelope.get("archived_sessions"))
        }
        envelope.addProperty("sha256", sha(data.toString().toByteArray(Charsets.UTF_8)))
        f.journal.writeText(envelope.toString())
        assertEquals(f.pending, f.open().readPending())
        f.archive()
        assertEquals(13, JsonParser.parseString(f.journal.readText()).asJsonObject.get("journal_version").asInt)
        val expected = manifest.deepCopy().apply {
            addProperty("stop_origin", StopOrigin.LEGACY_UNSPECIFIED.wireValue)
        }
        assertEquals(expected, f.open().manifestSnapshot(f.saved.sessionId))
    }

    private inner class Fixture(invalidCrc: Boolean = false) {
        val journal = File(temporary.newFolder(), "session.json")
        var failCommit = false
        val store = FreeLivingSessionStore(journal, { source, target ->
            if (failCommit) throw IOException("Injected commit failure")
            replace(source, target)
        }, {})
        val saved: FreeLivingSession
        val raw: File
        val baseline = DeviceStartBaseline(stopped, listOf(record), epoch + 20)
        val pending: FreeLivingSession
        val observation = HealthRecordObservation(profile.ring!!.address, 2, stopped.copy(errorCode = -16), epoch + 90, listOf(record))
        init {
            val session = store.requestStart(profile, epoch, "Asia/Shanghai", DeviceStartBaseline(idle, emptyList(), epoch))
            val active = stopped.copy(collecting = true)
            store.confirmStart(session.sessionId, profile.ring!!.address, active, epoch + 1,
                recordEvidence = DeviceRecordEvidence(record, active, epoch + 1))
            store.requestStop(session.sessionId, epoch + 2)
            store.confirmStop(session.sessionId, profile.ring!!.address, stopped, epoch + 3,
                recordEvidence = DeviceRecordEvidence(record, stopped, epoch + 3))
            store.saveReference(session.sessionId, SessionReference(ReferenceStatus.VALID, 73, epoch + 4))
            raw = File(journal.parentFile, "${session.sessionId}-raw.rfbin")
            val crc = CRC32().apply { update(payload) }.value
            raw.writeBytes(HealthRawV2.header(record, 0, 0, record.bytes, if (invalidCrc) crc + 1 else crc) + payload)
            saved = store.completeLocalData(session.sessionId,
                listOf(SessionRawFile(raw.name, 1, raw.length(), sha(raw.readBytes()))), epoch + 5)
            pending = store.requestStart(profile, epoch + 21, "Asia/Shanghai", baseline)
        }
        fun open() = open(journal)
        fun archive() = store.archiveStartAttempt(pending.sessionId, observation, epoch + 100, reason)
    }

    private fun open(journal: File) = FreeLivingSessionStore(journal, ::replace, {})
    private fun replace(source: File, target: File) {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}
