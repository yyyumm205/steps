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
import java.util.UUID

class FreeLivingSessionCompletionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val preparation = PreparationSnapshot("p001", "83e56afb-a74c-4825-af0b-b226cfe9c630",
        RingPlacement.LEFT_INDEX, PreparedRing("AA:BB:CC:DD:EE:01", "Ringo"))
    private val t = 1_789_804_800_000L

    @Test fun savedZeroMissingAndUnreliableStayDistinctAfterReopen() {
        val references = listOf(
            SessionReference(ReferenceStatus.VALID, 0, t + 3_000),
            SessionReference(ReferenceStatus.MISSING, null, t + 3_000, "无法读取"),
            SessionReference(ReferenceStatus.UNRELIABLE, 562, t + 3_000, "忘记清零"),
            SessionReference(ReferenceStatus.UNRELIABLE, null, t + 3_000, "读数不清"),
        )
        for (reference in references) {
            val file = file()
            val store = open(file)
            val stopped = stop(store)
            assertNull(stopped.reference)
            val saved = store.saveReference(stopped.sessionId, reference)
            assertEquals(reference, open(file).read()!!.reference)
            assertEquals(stopped.stopConfirmedAtMs, saved.stopConfirmedAtMs)
            assertNull(saved.startedAtMs)
            assertNull(saved.endedAtMs)
        }
    }

    @Test fun retrySameReferenceKeepsFirstTimestampButDifferentValueCannotReplaceIt() {
        val file = file()
        val store = open(file)
        val stopped = stop(store)
        val reference = SessionReference(ReferenceStatus.VALID, 562, t + 3_000)
        val saved = store.saveReference(stopped.sessionId, reference)
        val failingWriter = open(file) { _, _ -> throw IOException("must not write") }
        assertEquals(saved, failingWriter.saveReference(saved.sessionId, reference.copy(recordedAtMs = t + 4_000)))
        assertThrows(IllegalArgumentException::class.java) {
            store.saveReference(saved.sessionId, reference.copy(steps = 563))
        }
        assertEquals(saved, open(file).read())
    }

    @Test fun invalidReferencesNeverWriteOrAdvance() {
        val file = file()
        val store = open(file)
        val stopped = stop(store)
        val invalid = listOf(
            SessionReference(ReferenceStatus.VALID, null, t),
            SessionReference(ReferenceStatus.VALID, -1, t),
            SessionReference(ReferenceStatus.VALID, 0, t, "异常"),
            SessionReference(ReferenceStatus.MISSING, 0, t, "无法读取"),
            SessionReference(ReferenceStatus.MISSING, null, t),
            SessionReference(ReferenceStatus.UNRELIABLE, 3, t, " "),
            SessionReference(ReferenceStatus.VALID, 3, 0),
        )
        invalid.forEach { assertThrows(IllegalArgumentException::class.java) { store.saveReference(stopped.sessionId, it) } }
        assertEquals(stopped, open(file).read())
    }

    @Test fun unconfirmedStopCanPreserveAnomalousReferenceButDoesNotReleaseProtection() {
        val file = file()
        val store = open(file)
        val collecting = start(store)
        store.requestStop(collecting.sessionId, t + 1_000)
        assertThrows(IllegalArgumentException::class.java) {
            store.saveReference(collecting.sessionId, SessionReference(ReferenceStatus.VALID, 8, t + 2_000))
        }
        val saved = store.saveReference(collecting.sessionId,
            SessionReference(ReferenceStatus.UNRELIABLE, 8, t + 2_000, "戒指停止未确认"))
        assertEquals(FreeLivingSessionPhase.STOP_REQUESTED, saved.phase)
        assertNull(saved.endedAtMs)
        assertNull(saved.stopConfirmedAtMs)
        assertThrows(IllegalArgumentException::class.java) { store.requestStart(preparation, t + 3_000, "UTC") }
        assertThrows(IllegalArgumentException::class.java) {
            store.completeLocalData(saved.sessionId, listOf(raw(file, saved)), t + 3_000)
        }
        assertEquals(saved, open(file).readPending())
    }

    @Test fun failedReferenceSavePreservesPreviousJournalAndCanRetryAfterReopen() {
        val file = file()
        val store = open(file)
        val stopped = stop(store)
        val before = file.readBytes()
        val reference = SessionReference(ReferenceStatus.VALID, 12, t + 3_000)
        assertThrows(IOException::class.java) {
            open(file) { _, _ -> throw IOException("disk full") }.saveReference(stopped.sessionId, reference)
        }
        assertArrayEquals(before, file.readBytes())
        assertNull(open(file).read()!!.reference)
        assertEquals(reference, open(file).saveReference(stopped.sessionId, reference).reference)
    }

    @Test fun wrongSessionCannotSaveReferenceOrCompleteFiles() {
        val file = file()
        val store = open(file)
        val stopped = withReference(store)
        val other = UUID.randomUUID().toString()
        assertThrows(IllegalArgumentException::class.java) {
            store.saveReference(other, SessionReference(ReferenceStatus.VALID, 12, t + 3_000))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.completeLocalData(other, listOf(raw(file, stopped)), t + 4_000)
        }
        assertEquals(stopped, store.read())
    }

    @Test fun missingReferenceBlocksDownloadCompletionAndTransfer() {
        val file = file()
        val store = open(file)
        val stopped = stop(store)
        assertThrows(IllegalArgumentException::class.java) {
            store.completeLocalData(stopped.sessionId, listOf(raw(file, stopped)), t + 4_000)
        }
        assertThrows(IllegalArgumentException::class.java) { store.markTransferStarted(stopped.sessionId) }
        assertEquals(stopped, store.read())
    }

    @Test fun localCompletionChecksBytesHashOwnershipAndPathsBeforeUnlocking() {
        val file = file()
        val store = open(file)
        val stopped = withReference(store)
        val entry = raw(file, stopped)
        val invalid = listOf(
            entry.copy(fileName = "../${entry.fileName}"),
            entry.copy(fileName = "${UUID.randomUUID()}-raw.bin"),
            entry.copy(deviceSessionId = 42),
            entry.copy(bytes = -1),
        )
        invalid.forEach {
            assertThrows(IllegalArgumentException::class.java) { store.completeLocalData(stopped.sessionId, listOf(it), t + 4_000) }
        }
        assertThrows(IOException::class.java) {
            store.completeLocalData(stopped.sessionId, listOf(entry.copy(bytes = entry.bytes + 1)), t + 4_000)
        }
        assertThrows(IOException::class.java) {
            store.completeLocalData(stopped.sessionId, listOf(entry.copy(sha256 = "0".repeat(64))), t + 4_000)
        }
        assertThrows(IllegalArgumentException::class.java) { store.completeLocalData(stopped.sessionId, listOf(entry, entry), t + 4_000) }
        assertNotNull(store.readPending())
        val saved = store.completeLocalData(stopped.sessionId, listOf(entry), t + 4_000)
        assertNull(store.readPending())
        assertEquals(saved, open(file).read())
        assertEquals(t + 4_000, saved.localData!!.completedAtMs)
        assertEquals(t + 2_000, saved.stopConfirmedAtMs)
    }

    @Test fun completionRetryKeepsFirstDownloadTimeAndFiles() {
        val file = file()
        val store = open(file)
        val stopped = withReference(store)
        val entry = raw(file, stopped)
        val saved = store.completeLocalData(stopped.sessionId, listOf(entry), t + 4_000)
        assertEquals(saved, store.completeLocalData(stopped.sessionId, listOf(entry), t + 5_000))
        assertThrows(IllegalArgumentException::class.java) {
            store.completeLocalData(stopped.sessionId, listOf(entry.copy(simulated = false)), t + 5_000)
        }
    }

    @Test fun diskFailureAfterRawFilePreservesPendingStateAndRetriesOriginalFile() {
        val file = file()
        val store = open(file)
        val stopped = withReference(store)
        val entry = raw(file, stopped)
        assertThrows(IOException::class.java) {
            open(file) { _, _ -> throw IOException("disk full") }.completeLocalData(stopped.sessionId, listOf(entry), t + 4_000)
        }
        assertNotNull(open(file).readPending())
        assertTrue(File(file.parentFile, entry.fileName).exists())
        assertNotNull(open(file).completeLocalData(stopped.sessionId, listOf(entry), t + 5_000).localData)
    }

    @Test fun twoSessionsKeepTheirOwnReferenceFilesAndUploadRetry() {
        val file = file()
        val store = open(file)
        val first = localComplete(file, store)
        store.markTransferStarted(first.sessionId)
        val failed = store.markTransferFailed(first.sessionId)
        val second = store.requestStart(preparation, t + 9_000, "Asia/Shanghai")
        assertNotEquals(first.sessionId, second.sessionId)
        assertEquals(failed, open(file).read(first.sessionId))
        assertEquals(second, open(file).read())
        store.markTransferStarted(first.sessionId)
        val receipt = SessionTransferReceipt("simulated:${first.sessionId}", t + 10_000, true, first.sessionId)
        val uploaded = store.completeTransfer(first.sessionId, receipt)
        assertEquals(2, uploaded.transfer.attempts)
        assertEquals(SessionTransferStatus.COMPLETE, open(file).read(first.sessionId)!!.transfer.status)
        assertEquals(second, open(file).read())
        assertEquals(first.reference, uploaded.reference)
        assertEquals(first.localData, uploaded.localData)
        assertEquals(2, open(file).listSessions().size)
    }

    @Test fun archivingIsAtomicAndFailedNewStartRetainsCompletePriorSession() {
        val file = file()
        val store = open(file)
        val first = localComplete(file, store)
        assertThrows(IOException::class.java) {
            open(file) { _, _ -> throw IOException("disk full") }.requestStart(preparation, t + 9_000, "UTC")
        }
        assertEquals(listOf(first), open(file).listSessions())
        store.requestStart(preparation, t + 10_000, "UTC")
        assertEquals(2, open(file).listSessions().size)
    }

    @Test fun missingOrChangedRawFilePreventsNewStartAndUploadSuccess() {
        val file = file()
        val store = open(file)
        val saved = localComplete(file, store)
        store.markTransferStarted(saved.sessionId)
        val raw = File(file.parentFile, saved.localData!!.files.single().fileName)
        raw.writeText("changed")
        assertThrows(IOException::class.java) { store.requestStart(preparation, t + 9_000, "UTC") }
        assertThrows(IOException::class.java) {
            store.completeTransfer(saved.sessionId, SessionTransferReceipt("receipt", t + 8_000, true, saved.sessionId))
        }
        assertEquals(SessionTransferStatus.TRANSFERRING, store.read()!!.transfer.status)
        assertTrue(raw.delete())
        assertThrows(IOException::class.java) { store.markTransferStarted(saved.sessionId) }
    }

    @Test fun transmissionCannotSucceedWithoutStartedAttemptOrMixSimulationWithRealFiles() {
        val file = file()
        val store = open(file)
        val saved = localComplete(file, store)
        val receipt = SessionTransferReceipt("simulated", t + 8_000, true, saved.sessionId)
        assertThrows(IllegalArgumentException::class.java) { store.completeTransfer(saved.sessionId, receipt) }
        assertThrows(IllegalArgumentException::class.java) { store.markTransferFailed(saved.sessionId) }
        store.markTransferStarted(saved.sessionId)
        assertThrows(IllegalArgumentException::class.java) {
            store.completeTransfer(saved.sessionId, receipt.copy(sessionId = UUID.randomUUID().toString()))
        }
        assertThrows(IllegalArgumentException::class.java) { store.completeTransfer(saved.sessionId, receipt.copy(simulated = false)) }
        val complete = store.completeTransfer(saved.sessionId, receipt)
        assertEquals(complete, store.completeTransfer(saved.sessionId, receipt))
        assertEquals(complete, store.markTransferStarted(saved.sessionId))
        assertThrows(IllegalArgumentException::class.java) {
            store.completeTransfer(saved.sessionId, receipt.copy(receiptId = "another"))
        }
    }

    @Test fun legacyJournalCanBeReadAndUpgradedWithoutInventingReferences() {
        val file = file()
        val stopped = stop(open(file))
        val envelope = JsonParser.parseString(file.readText()).asJsonObject
        val session = envelope.getAsJsonObject("session")
        listOf("ground_truth_reason", "reference_saved_at_ms", "raw_files", "transfer",
            "start_baseline", "device_record_evidence", "device_association_invalidated",
            "start_attempt_archive", "completion_policy", "discarded", "start_abort").forEach(session::remove)
        envelope.addProperty("journal_version", 1)
        envelope.remove("archived_sessions")
        envelope.addProperty("sha256", digest(session.toString().toByteArray()))
        file.writeText(envelope.toString())
        val originalBytes = file.readBytes()
        assertEquals(stopped, open(file).read())
        assertNull(open(file).read()!!.reference)
        assertNull(open(file).read()!!.startAttemptArchive)
        assertArrayEquals(originalBytes, file.readBytes())
        open(file).saveReference(stopped.sessionId, SessionReference(ReferenceStatus.VALID, 0, t + 3_000))
        assertEquals(9, JsonParser.parseString(file.readText()).asJsonObject.get("journal_version").asInt)
        assertEquals(0L, open(file).read()!!.reference!!.steps)
        assertNull(open(file).read()!!.startAttemptArchive)
    }

    @Test fun archivedContentIsCoveredByChecksum() {
        val file = file()
        val store = open(file)
        val first = localComplete(file, store)
        store.requestStart(preparation, t + 9_000, "UTC")
        val envelope = JsonParser.parseString(file.readText()).asJsonObject
        envelope.getAsJsonArray("archived_sessions")[0].asJsonObject.addProperty("ground_truth_steps", 99)
        file.writeText(envelope.toString())
        assertThrows(IOException::class.java) { open(file).read(first.sessionId) }
        assertThrows(IOException::class.java) { open(file).requestStart(preparation, t + 10_000, "UTC") }
    }

    @Test fun failedReceiptSaveCanRetrySameReceiptWithoutTouchingReferenceOrRawFiles() {
        val file = file()
        val store = open(file)
        val saved = localComplete(file, store)
        val sending = store.markTransferStarted(saved.sessionId)
        val receipt = SessionTransferReceipt("simulated:${saved.sessionId}", t + 6_000, true, saved.sessionId)
        assertThrows(IOException::class.java) {
            open(file) { _, _ -> throw IOException("disk full") }.completeTransfer(saved.sessionId, receipt)
        }
        assertEquals(sending, open(file).read())
        val completed = open(file).completeTransfer(saved.sessionId, receipt)
        assertEquals(receipt, completed.transfer.receipt)
        assertEquals(saved.reference, completed.reference)
        assertEquals(saved.localData, completed.localData)
        assertEquals(1, completed.transfer.attempts)
    }

    @Test fun successfulRenameWithFailedDirectorySyncDoesNotReportSavedReference() {
        val file = file()
        val stopped = stop(open(file))
        var syncCalls = 0
        val store = FreeLivingSessionStore(file, { source, target ->
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }, { if (++syncCalls >= 2) throw IOException("directory sync failed") })
        val reference = SessionReference(ReferenceStatus.VALID, 0, t + 3_000)
        assertThrows(IOException::class.java) { store.saveReference(stopped.sessionId, reference) }
        assertThrows(IOException::class.java) { store.read() }
        val reopened = open(file)
        assertEquals(reference, reopened.read()!!.reference)
        assertEquals(reference, reopened.saveReference(stopped.sessionId, reference.copy(recordedAtMs = t + 5_000)).reference)
    }

    @Test fun multipleRawFilesAndMidnightRetainOneReferenceAndSession() {
        val file = file()
        val store = open(file)
        val beforeMidnight = java.time.Instant.parse("2026-09-18T15:50:00Z").toEpochMilli()
        val started = store.requestStart(preparation, beforeMidnight, "Asia/Shanghai")
        store.confirmStart(started.sessionId, preparation.ring!!.address,
            HealthMessage.Status(true, 32, 2, 0, 41), beforeMidnight + 100)
        store.requestStop(started.sessionId, beforeMidnight + 1_800_000)
        store.confirmStop(started.sessionId, preparation.ring.address,
            HealthMessage.Status(false, 32, 2, 0, 41), beforeMidnight + 1_800_100)
        val saved = store.saveReference(started.sessionId,
            SessionReference(ReferenceStatus.VALID, 1_002, beforeMidnight + 1_801_000))
        val first = raw(file, saved)
        val secondName = "${saved.sessionId}-raw-2.bin"
        File(file.parentFile, secondName).writeBytes(File(file.parentFile, first.fileName).readBytes())
        val completed = store.completeLocalData(saved.sessionId,
            listOf(first, first.copy(fileName = secondName)), beforeMidnight + 1_802_000)
        assertEquals(2, completed.localData!!.files.size)
        assertEquals(1_002L, completed.reference!!.steps)
        assertEquals(1, open(file).listSessions().size)
        assertEquals("Asia/Shanghai", completed.timeZoneId)
        assertNull(completed.startedAtMs)
        assertNull(completed.endedAtMs)
    }

    @Test fun simulatedAndRealFilesCannotBeClaimedAsOneCompleteDownload() {
        val file = file()
        val store = open(file)
        val saved = withReference(store)
        val first = raw(file, saved)
        val secondName = "${saved.sessionId}-real.bin"
        File(file.parentFile, secondName).writeBytes(File(file.parentFile, first.fileName).readBytes())
        assertThrows(IllegalArgumentException::class.java) {
            store.completeLocalData(saved.sessionId, listOf(first, first.copy(fileName = secondName, simulated = false)), t + 4_000)
        }
        assertNotNull(store.readPending())
    }

    @Test fun backwardReferenceClockIsPreservedAndFlaggedWithoutReplacingDeviceBoundaries() {
        val file = file()
        val store = open(file)
        val stopped = stop(store)
        val saved = store.saveReference(stopped.sessionId, SessionReference(ReferenceStatus.VALID, 0, t - 1_000))
        assertEquals(t - 1_000, saved.reference!!.recordedAtMs)
        assertEquals(listOf("phone_clock_order_uncertain"), saved.timingWarnings)
        assertNull(saved.startedAtMs)
        assertNull(saved.endedAtMs)
    }

    @Test fun confirmedMissingKeepsNumericTimestampNullAndPersistsSeparateObservationTime() {
        for (status in listOf(ReferenceStatus.MISSING, ReferenceStatus.UNRELIABLE)) {
            val file = file()
            val store = open(file)
            val stopped = stop(store)
            val saved = store.saveReference(stopped.sessionId, SessionReference(status, null, t + 3_000, "无法读取"))
            val payload = JsonParser.parseString(file.readText()).asJsonObject.getAsJsonObject("session")
            assertTrue(payload.get("ground_truth_steps").isJsonNull)
            assertTrue(payload.get("ground_truth_recorded_at_ms").isJsonNull)
            assertEquals(t + 3_000, payload.get("reference_saved_at_ms").asLong)
            assertNotNull(open(file).read()!!.reference)
            assertNull(saved.reference!!.groundTruthRecordedAtMs)
            assertEquals(saved, open(file).read())
        }
        val file = file()
        val store = open(file)
        val stopped = stop(store)
        store.saveReference(stopped.sessionId, SessionReference(ReferenceStatus.VALID, 0, t + 3_000))
        val payload = JsonParser.parseString(file.readText()).asJsonObject.getAsJsonObject("session")
        assertEquals(0L, payload.get("ground_truth_steps").asLong)
        assertEquals(t + 3_000, payload.get("ground_truth_recorded_at_ms").asLong)
    }

    @Test fun preservingAnomalousReadingBeforeDelayedStopConfirmationDoesNotImplyClockRollback() {
        val file = file()
        val store = open(file)
        val collecting = start(store)
        store.requestStop(collecting.sessionId, t + 1_000)
        store.saveReference(collecting.sessionId,
            SessionReference(ReferenceStatus.UNRELIABLE, 562, t + 2_000, "等待戒指确认结束"))
        val stopped = store.confirmStop(collecting.sessionId, preparation.ring!!.address,
            HealthMessage.Status(false, 32, 2, 0, 41), t + 3_000)
        assertTrue(stopped.timingWarnings.isEmpty())
        val completed = store.completeLocalData(stopped.sessionId, listOf(raw(file, stopped)), t + 4_000)
        assertTrue(completed.timingWarnings.isEmpty())
        assertEquals(t + 2_000, completed.reference!!.recordedAtMs)
        assertEquals(t + 3_000, completed.stopConfirmedAtMs)
        assertNull(completed.endedAtMs)
    }

    private fun file() = File(temporary.newFolder(), "session.json")
    private fun open(file: File, commit: (File, File) -> Unit = { source, target ->
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }) = FreeLivingSessionStore(file, commit, {})
    private fun start(store: FreeLivingSessionStore): FreeLivingSession {
        val session = store.requestStart(preparation, t, "Asia/Shanghai")
        return store.confirmStart(session.sessionId, preparation.ring!!.address,
            HealthMessage.Status(true, 32, 2, 0, 41), t + 100)
    }
    private fun stop(store: FreeLivingSessionStore): FreeLivingSession {
        val session = start(store)
        store.requestStop(session.sessionId, t + 1_000)
        return store.confirmStop(session.sessionId, preparation.ring!!.address,
            HealthMessage.Status(false, 32, 2, 0, 41), t + 2_000)
    }
    private fun withReference(store: FreeLivingSessionStore): FreeLivingSession {
        val session = stop(store)
        return store.saveReference(session.sessionId, SessionReference(ReferenceStatus.VALID, 12, t + 3_000))
    }
    private fun raw(journal: File, session: FreeLivingSession): SessionRawFile {
        val bytes = "SIMULATED SIGNAL ${session.sessionId}".toByteArray()
        val name = "${session.sessionId}-raw.bin"
        File(journal.parentFile, name).writeBytes(bytes)
        return SessionRawFile(name, session.deviceSessionId!!, bytes.size.toLong(), digest(bytes), true)
    }
    private fun localComplete(file: File, store: FreeLivingSessionStore): FreeLivingSession {
        val session = withReference(store)
        return store.completeLocalData(session.sessionId, listOf(raw(file, session)), t + 4_000)
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
