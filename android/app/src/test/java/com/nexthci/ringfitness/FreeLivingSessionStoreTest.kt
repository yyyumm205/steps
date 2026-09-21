package com.nexthci.ringfitness

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FreeLivingSessionStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val preparation = PreparationSnapshot("p001", "83e56afb-a74c-4825-af0b-b226cfe9c630",
        RingPlacement.LEFT_INDEX, PreparedRing("AA:BB:CC:DD:EE:01", "Ringo 测试戒指"))
    private val t = 1_789_804_800_000L
    private val address = preparation.ring!!.address

    @Test fun emptyJournalHasNoSession() {
        assertNull(openStore(file()).read())
    }

    @Test fun requestCreatesOneUuidAndFreezesConfirmedPreparation() {
        val file = file()
        val s = openStore(file).requestStart(preparation, t, "Asia/Shanghai")
        assertEquals(s.sessionId, UUID.fromString(s.sessionId).toString())
        assertEquals(preparation, s.preparation)
        assertEquals(FreeLivingSessionPhase.START_REQUESTED, s.phase)
        assertEquals(28_800, s.utcOffsetSeconds)
        assertEquals(s, openStore(file).read())
        assertNull(s.deviceSessionId)
        assertNull(s.startedAtMs)
        assertNull(s.endedAtMs)
    }

    @Test fun localDisplayIdentityCannotChangeTheDurableStartRequest() {
        val file = file()
        val localProfile = preparation.copy(
            participantId = "local0123456789abcdef",
            displayLabel = "Alice",
            identityType = PreparationIdentityType.LOCAL_NAME,
        )

        val requested = openStore(file).requestStart(localProfile, t, "UTC")

        assertEquals(localProfile.participantId, requested.preparation.participantId)
        assertEquals(localProfile.participantId, requested.preparation.displayLabel)
        assertEquals(PreparationIdentityType.RESEARCH_ID, requested.preparation.identityType)
        assertEquals(requested, openStore(file).read())
        assertEquals(requested, openStore(file).requestStart(localProfile, t + 100, "UTC"))
    }

    @Test fun everyPhaseSurvivesOpeningANewStoreInstance() {
        val file = file()
        val requested = openStore(file).requestStart(preparation, t, "UTC")
        val collecting = openStore(file).confirmStart(requested.sessionId, address, status(true), t + 100)
        assertEquals(collecting, openStore(file).read())
        val stopping = openStore(file).requestStop(requested.sessionId, t + 1_000)
        assertEquals(stopping, openStore(file).read())
        val ended = openStore(file).confirmStop(requested.sessionId, address, status(false), t + 1_100)
        assertEquals(ended, openStore(file).read())
        assertEquals(FreeLivingSessionPhase.AWAITING_REFERENCE, ended.phase)
    }

    @Test fun phoneConfirmationsRemainSeparateFromUnknownCaptureBoundaries() {
        val s = finish(file())
        assertEquals(t, s.startRequestedAtMs)
        assertEquals(t + 100, s.startConfirmedAtMs)
        assertEquals(t + 1_000, s.stopRequestedAtMs)
        assertEquals(t + 1_100, s.stopConfirmedAtMs)
        assertNull(s.startedAtMs)
        assertNull(s.endedAtMs)
        assertEquals("uncertain", s.captureBoundaryStatus)
    }

    @Test fun boundaryTimesUseDeviceEvidenceAndRetainItsSource() {
        val file = file()
        val store = openStore(file)
        val requested = store.requestStart(preparation, t, "Asia/Shanghai")
        val begin = boundary(t + 20)
        val end = boundary(t + 1_020)
        store.confirmStart(requested.sessionId, address, status(true), t + 100, begin)
        store.requestStop(requested.sessionId, t + 1_000)
        val s = store.confirmStop(requested.sessionId, address, status(false), t + 1_100, end)
        assertEquals(t + 20, s.startedAtMs)
        assertEquals(t + 1_020, s.endedAtMs)
        assertEquals("confirmed", s.captureBoundaryStatus)
        assertEquals(begin, s.startBoundaryEvidence)
        assertEquals(end, openStore(file).read()!!.endBoundaryEvidence)
    }

    @Test fun partialDeviceEvidenceKeepsBoundaryUncertain() {
        val store = openStore(file())
        val s = store.requestStart(preparation, t, "UTC")
        store.confirmStart(s.sessionId, address, status(true), t + 100, boundary(t + 20))
        store.requestStop(s.sessionId, t + 1_000)
        val ended = store.confirmStop(s.sessionId, address, status(false), t + 1_100)
        assertEquals(t + 20, ended.startedAtMs)
        assertNull(ended.endedAtMs)
        assertEquals("uncertain", ended.captureBoundaryStatus)
    }

    @Test fun duplicateStartsBeforeAndAfterConfirmationKeepIdentityAndFirstRequest() {
        val file = file()
        val store = openStore(file)
        val first = store.requestStart(preparation, t, "Asia/Shanghai")
        assertEquals(first, store.requestStart(preparation, t + 300, "UTC"))
        val collecting = store.confirmStart(first.sessionId, address, status(true), t + 100)
        assertEquals(collecting, store.requestStart(preparation, t + 400, "UTC"))
        assertEquals(first.sessionId, openStore(file).read()!!.sessionId)
    }

    @Test fun duplicateConfirmationsAndStopRequestsNeverRewriteTimesOrEvidence() {
        val store = openStore(file())
        val first = store.requestStart(preparation, t, "UTC")
        val collecting = store.confirmStart(first.sessionId, address, status(true), t + 100, boundary(t + 20))
        assertEquals(collecting, store.confirmStart(first.sessionId, address, status(true), t + 200, boundary(t + 30)))
        val stopping = store.requestStop(first.sessionId, t + 1_000)
        assertEquals(stopping, store.requestStop(first.sessionId, t + 1_200))
        val ended = store.confirmStop(first.sessionId, address, status(false), t + 1_300, boundary(t + 1_100))
        assertEquals(ended, store.confirmStop(first.sessionId, address, status(false), t + 1_400, boundary(t + 1_200)))
        assertEquals(ended, store.requestStop(first.sessionId, t + 1_500))
        assertEquals(ended, store.confirmStart(first.sessionId, address, status(true), t + 1_600))
    }

    @Test fun cancellingALocallyRejectedStopRestoresTheExactCollectingSession() {
        val file = file()
        val store = openStore(file)
        val collecting = start(store)
        store.requestStop(collecting.sessionId, t + 1_000)

        val restored = store.cancelStopRequest(collecting.sessionId)

        assertEquals(collecting, restored)
        assertEquals(FreeLivingSessionPhase.COLLECTING, restored.phase)
        assertNull(restored.stopRequestedAtMs)
        assertEquals(restored, openStore(file).read())
    }

    @Test fun startCommandOutboxSurvivesReopenAndDistinguishesPreparedFromAccepted() {
        val file = file()
        val store = openStore(file)
        val baseline = DeviceStartBaseline(HealthMessage.Status(false, 0, 0, 0, 9), emptyList(), t)
        val requested = store.requestStart(preparation, t, "UTC", baseline)

        val prepared = store.prepareStartCommand(requested.sessionId, t + 10)
        assertEquals(StartCommandDispatch(t + 10,
            ownerId = "00000000-0000-0000-0000-000000000000", connectionGeneration = 1),
            prepared.startCommandDispatch)
        assertEquals(prepared, openStore(file).read())

        val accepted = store.confirmStartCommandAccepted(requested.sessionId, t + 20)
        assertEquals(StartCommandDispatch(t + 10, t + 20,
            "00000000-0000-0000-0000-000000000000", 1), accepted.startCommandDispatch)
        assertEquals(accepted, openStore(file).read())
        assertThrows(IllegalArgumentException::class.java) {
            store.cancelPreparedStartCommand(requested.sessionId)
        }
        assertEquals(accepted, openStore(file).read())
    }

    @Test fun observedDeviceStopAtomicallyEntersRecoverableFinalization() {
        val file = file()
        val store = openStore(file)
        val baseline = DeviceStartBaseline(HealthMessage.Status(false, 0, 0, 0, 9), emptyList(), t)
        val requested = store.requestStart(preparation, t, "UTC", baseline)
        val collectingStatus = status(true)
        val firstRecord = HealthMessage.ListItem(41, 1_024, 12, 12_345, t)
        val collecting = store.confirmStart(requested.sessionId, address, collectingStatus, t + 100,
            recordEvidence = DeviceRecordEvidence(firstRecord, collectingStatus, t + 100))
        val stoppedStatus = status(false).copy(bytes = 1_200, records = 14)
        val finalRecord = firstRecord.copy(bytes = 1_200, records = 14)

        val finalizing = store.beginObservedStopFinalization(collecting.sessionId, t + 200,
            DeviceRecordEvidence(finalRecord, stoppedStatus, t + 200))

        assertEquals(FreeLivingSessionPhase.STOP_REQUESTED, finalizing.phase)
        assertNull(finalizing.stopRequestedAtMs)
        assertEquals(t + 200, finalizing.stopObservedAtMs)
        assertEquals(StopOrigin.DEVICE_OBSERVED, finalizing.stopOrigin)
        assertNull(finalizing.stopConfirmedAtMs)
        assertEquals(finalRecord, finalizing.deviceRecordEvidence!!.record)
        assertEquals(finalizing, openStore(file).read())
    }

    @Test fun aRecoveredUnsentStopMovesItsOutboxToTheProvedConnection() {
        val file = file()
        val store = openStore(file)
        val collecting = start(store)
        val requested = store.requestStop(collecting.sessionId, t + 1_000)
        val original = requireNotNull(requested.stopCommandDispatch)
        val recoveringOwner = "11111111-1111-1111-1111-111111111111"

        val recovered = store.prepareRecoveredStopCommand(collecting.sessionId, t + 2_000,
            recoveringOwner, 9)

        assertEquals(t + 1_000, recovered.stopRequestedAtMs)
        assertEquals(StopCommandDispatch(t + 2_000, ownerId = recoveringOwner,
            connectionGeneration = 9), recovered.stopCommandDispatch)
        assertNotEquals(original, recovered.stopCommandDispatch)
        assertEquals(recovered, openStore(file).read())
    }

    @Test fun stoppedOrStoppingDataBlocksANewSessionUntilFutureSafeDownloadFlow() {
        val store = openStore(file())
        val s = start(store)
        store.requestStop(s.sessionId, t + 1_000)
        assertThrows(IllegalArgumentException::class.java) { store.requestStart(preparation, t + 2_000, "UTC") }
        val ended = store.confirmStop(s.sessionId, address, status(false), t + 1_100)
        assertThrows(IllegalArgumentException::class.java) { store.requestStart(preparation, t + 3_000, "UTC") }
        assertEquals(ended, store.read())
    }

    @Test fun changingParticipantPlacementOrRingCannotReplaceAnActiveSnapshot() {
        val store = openStore(file())
        val first = store.requestStart(preparation, t, "UTC")
        val candidates = listOf(
            preparation.copy(participantId = "p002"),
            preparation.copy(installationId = UUID.randomUUID().toString()),
            preparation.copy(placement = RingPlacement.RIGHT_RING),
            preparation.copy(ring = PreparedRing("AA:BB:CC:DD:EE:02", "另一戒指")),
        )
        candidates.forEach { changed ->
            assertThrows(IllegalArgumentException::class.java) { store.requestStart(changed, t + 100, "UTC") }
        }
        assertEquals(first, store.read())
    }

    @Test fun incompleteOrNoncanonicalPreparationCannotCreateASession() {
        listOf(preparation.copy(placement = null), preparation.copy(ring = null),
            preparation.copy(participantId = "P001"), preparation.copy(participantId = "../p1"),
            preparation.copy(installationId = "bad-id"),
            preparation.copy(ring = PreparedRing("bad-address", "Ringo"))).forEach { invalid ->
            val file = file()
            assertThrows(IllegalArgumentException::class.java) { openStore(file).requestStart(invalid, t, "UTC") }
            assertFalse(file.exists())
        }
    }

    @Test fun wrongLocalSessionAndRingRepliesCannotAdvanceOrOverwriteJournal() {
        val file = file()
        val store = openStore(file)
        val s = store.requestStart(preparation, t, "UTC")
        val before = file.readBytes()
        assertThrows(IllegalArgumentException::class.java) { store.confirmStart(UUID.randomUUID().toString(), address, status(true), t + 100) }
        assertThrows(IllegalArgumentException::class.java) { store.confirmStart(s.sessionId, "AA:BB:CC:DD:EE:02", status(true), t + 100) }
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun otherDeviceSessionCannotConfirmStopOrReplaceEarlierStart() {
        val store = openStore(file())
        val s = start(store)
        assertThrows(IllegalArgumentException::class.java) { store.confirmStart(s.sessionId, address, status(true, 42), t + 200) }
        store.requestStop(s.sessionId, t + 1_000)
        assertThrows(IllegalArgumentException::class.java) { store.confirmStop(s.sessionId, address, status(false, 42), t + 1_100) }
        assertEquals(FreeLivingSessionPhase.STOP_REQUESTED, store.read()!!.phase)
        assertEquals(41, store.read()!!.deviceSessionId)
    }

    @Test fun invalidStatusCannotBeSavedAsConfirmation() {
        val store = openStore(file())
        val s = store.requestStart(preparation, t, "UTC")
        listOf(status(false), status(true).copy(errorCode = 1), status(true).copy(bytes = -1),
            status(true).copy(records = -1), status(true).copy(bytes = 0x1_0000_0000L), status(true, 65536)).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { store.confirmStart(s.sessionId, address, invalid, t + 100) }
        }
        assertEquals(s, store.read())
    }

    @Test fun outOfOrderOperationsDoNotAdvanceTheSession() {
        val store = openStore(file())
        val s = store.requestStart(preparation, t, "UTC")
        assertThrows(IllegalArgumentException::class.java) { store.requestStop(s.sessionId, t + 100) }
        assertThrows(IllegalArgumentException::class.java) { store.confirmStop(s.sessionId, address, status(false), t + 100) }
        store.confirmStart(s.sessionId, address, status(true), t + 200)
        assertThrows(IllegalArgumentException::class.java) { store.confirmStop(s.sessionId, address, status(false), t + 300) }
        assertEquals(FreeLivingSessionPhase.COLLECTING, store.read()!!.phase)
    }

    @Test fun phoneClockRollbackIsRetainedAndFlaggedWithoutInventingBoundaries() {
        val file = file()
        val store = openStore(file)
        val s = store.requestStart(preparation, t, "Asia/Shanghai")
        store.confirmStart(s.sessionId, address, status(true), t - 100)
        store.requestStop(s.sessionId, t - 1_000)
        val ended = store.confirmStop(s.sessionId, address, status(false), t - 900)
        assertEquals(t - 1_000, ended.stopRequestedAtMs)
        assertEquals(t - 900, ended.stopConfirmedAtMs)
        assertEquals(listOf("phone_clock_order_uncertain"), ended.timingWarnings)
        assertNull(ended.startedAtMs)
        assertNull(ended.endedAtMs)
        assertEquals(ended, openStore(file).read())
    }

    @Test fun reversedDeviceBoundaryPreservesRawEvidenceAndKeepsEffectiveEndUnknown() {
        val store = openStore(file())
        val s = store.requestStart(preparation, t, "UTC")
        store.confirmStart(s.sessionId, address, status(true), t + 100, boundary(t + 20))
        store.requestStop(s.sessionId, t + 1_000)
        val ended = store.confirmStop(s.sessionId, address, status(false), t + 1_100, boundary(t - 20))
        assertEquals(t - 20, ended.endBoundaryEvidence!!.epochMs)
        assertNull(ended.endedAtMs)
        assertEquals("uncertain", ended.captureBoundaryStatus)
        assertEquals(listOf("device_boundary_order_uncertain"), ended.timingWarnings)
    }

    @Test fun midnightAndLongDurationsKeepOneSessionWithoutADaySplit() {
        val store = openStore(file())
        val midnight = java.time.Instant.parse("2026-09-18T15:50:00Z").toEpochMilli()
        val s = store.requestStart(preparation, midnight, "Asia/Shanghai")
        store.confirmStart(s.sessionId, address, status(true), midnight + 100)
        store.requestStop(s.sessionId, midnight + 23 * 60 * 60 * 1_000)
        val ended = store.confirmStop(s.sessionId, address, status(false), midnight + 23 * 60 * 60 * 1_000 + 100)
        assertEquals(s.sessionId, ended.sessionId)
        assertEquals("Asia/Shanghai", ended.timeZoneId)
        assertNull(ended.startedAtMs)
        assertNull(ended.endedAtMs)
    }

    @Test fun jsonKeepsExplicitNullsAndFreeLivingUnlabelledMeanings() {
        val file = file()
        finish(file)
        val p = JsonParser().parse(file.readText()).asJsonObject.getAsJsonObject("session")
        assertEquals("free_living", p.get("activity_code").asString)
        assertEquals("unlabelled", p.get("activity_label_status").asString)
        assertEquals("none", p.get("activity_label_source").asString)
        assertEquals("external_pedometer", p.get("ground_truth_source").asString)
        assertEquals("missing", p.get("ground_truth_status").asString)
        listOf("ground_truth_steps", "ground_truth_recorded_at_ms", "download_completed_at_ms", "started_at_ms", "ended_at_ms").forEach {
            assertNotNull(p.get(it))
            assertTrue(p.get(it).isJsonNull)
        }
    }

    @Test fun failedInitialSaveLeavesNoSessionAndCanBeRetried() {
        val file = file()
        val broken = openStore(file) { _, _ -> throw IOException("disk full") }
        assertThrows(IOException::class.java) { broken.requestStart(preparation, t, "UTC") }
        assertFalse(file.exists())
        assertEquals(0, file.parentFile.listFiles()!!.size)
        assertNotNull(openStore(file).requestStart(preparation, t, "UTC"))
    }

    @Test fun failedTransitionPreservesLastConfirmedFileAndRetryUsesOriginalSession() {
        val file = file()
        val store = openStore(file)
        val s = store.requestStart(preparation, t, "UTC")
        val before = file.readBytes()
        val broken = openStore(file) { _, _ -> throw IOException("replace failed") }
        assertThrows(IOException::class.java) { broken.confirmStart(s.sessionId, address, status(true), t + 100) }
        assertArrayEquals(before, file.readBytes())
        assertEquals(s, openStore(file).read())
        assertEquals(1, file.parentFile.listFiles()!!.size)
        val collecting = store.confirmStart(s.sessionId, address, status(true), t + 200)
        assertEquals(s.sessionId, collecting.sessionId)
    }

    @Test fun duplicateOperationDoesNotAttemptAnotherDiskWrite() {
        val file = file()
        val store = openStore(file)
        val s = start(store)
        val brokenWriter = openStore(file) { _, _ -> throw IOException("must not write") }
        assertEquals(s, brokenWriter.requestStart(preparation, t + 300, "UTC"))
        assertEquals(s, brokenWriter.confirmStart(s.sessionId, address, status(true), t + 400))
    }

    @Test fun corruptJsonIsPreservedAndCannotBeOverwrittenByAnotherStart() {
        val file = file()
        file.writeText("{\"journal_version\":1,\"session\":")
        val before = file.readBytes()
        val store = openStore(file)
        assertThrows(IOException::class.java) { store.read() }
        assertThrows(IOException::class.java) { store.requestStart(preparation, t, "UTC") }
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun validJsonWithAlteredPayloadFailsIntegrityCheck() {
        val file = file()
        finish(file)
        val envelope = JsonParser().parse(file.readText()).asJsonObject
        envelope.getAsJsonObject("session").addProperty("participant_id", "p999")
        file.writeText(envelope.toString())
        val before = file.readBytes()
        assertThrows(IOException::class.java) { openStore(file).read() }
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun schemaRejectsCoercedTimesMissingNullsAndFalseActivityLabels() {
        val changes: List<(JsonObject) -> Unit> = listOf(
            { it.addProperty("start_requested_at_ms", t.toString()) },
            { it.remove("ground_truth_steps") },
            { it.addProperty("ground_truth_steps", 0) },
            { it.addProperty("activity_code", "other") },
            { it.addProperty("started_at_ms", t) },
        )
        for (change in changes) {
            val file = file()
            finish(file)
            editPayloadWithUpdatedDigest(file, change)
            assertThrows(IOException::class.java) { openStore(file).read() }
        }
    }

    @Test fun concurrentDuplicateStartsAcrossInstancesCreateOneIdentity() {
        val file = file()
        val executor = Executors.newFixedThreadPool(2)
        val gate = CountDownLatch(1)
        try {
            val futures = (0..1).map { index -> executor.submit<FreeLivingSession> {
                gate.await()
                openStore(file).requestStart(preparation, t + index, "UTC")
            } }
            gate.countDown()
            val sessions = futures.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(sessions[0], sessions[1])
            assertEquals(sessions[0], openStore(file).read())
            assertEquals(1, file.parentFile.listFiles()!!.size)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun blankDeviceEvidenceIsRejectedAndDoesNotClaimAKnownBoundary() {
        val file = file()
        val store = openStore(file)
        val s = store.requestStart(preparation, t, "UTC")
        assertThrows(IllegalArgumentException::class.java) {
            store.confirmStart(s.sessionId, address, status(true), t + 100, boundary(t).copy(rawEvidence = " "))
        }
        assertEquals(s, store.read())
    }

    @Test fun directorySyncFailureCannotBeReportedAsASavedRequestOrAnIdempotentSuccess() {
        val file = file()
        var failSync = true
        val store = openStore(file, syncDirectory = { if (failSync) throw IOException("directory fsync failed") })
        assertThrows(IOException::class.java) { store.requestStart(preparation, t, "UTC") }
        assertTrue(file.exists()) // Rename succeeded; keep this recoverable journal rather than delete it.
        val bytes = file.readBytes()
        assertThrows(IOException::class.java) { store.read() }
        assertThrows(IOException::class.java) { store.requestStart(preparation, t + 100, "UTC") }
        assertArrayEquals(bytes, file.readBytes())
        failSync = false
        val recovered = store.read()!!
        assertEquals(t, recovered.startRequestedAtMs)
        assertEquals(recovered, store.requestStart(preparation, t + 200, "UTC"))
    }

    @Test fun missingParentDirectoryIsRejectedWithoutCreatingAnUndurableDirectoryTree() {
        val parent = File(temporary.newFolder(), "not-created")
        val file = File(parent, "active-session.json")
        assertThrows(IOException::class.java) { openStore(file).requestStart(preparation, t, "UTC") }
        assertFalse(parent.exists())
        assertFalse(file.exists())
    }

    @Test fun jsonParserRejectsCommentsUnquotedKeysAndExtraDocuments() {
        val file = file()
        finish(file)
        val valid = file.readText()
        val variants = listOf("/* comment */$valid", valid.replaceFirst("\"journal_version\"", "journal_version"),
            valid.replaceFirst("\"journal_version\"", "'journal_version'"), "$valid null")
        for (invalid in variants) {
            file.writeText(invalid)
            assertThrows(IOException::class.java) { openStore(file).read() }
            assertEquals(invalid, file.readText())
        }
    }

    @Test fun savedUtcOffsetRemainsASnapshotRatherThanBeingRecomputedByLaterTzdb() {
        val file = file()
        openStore(file).requestStart(preparation, t, "Africa/Casablanca")
        editPayloadWithUpdatedDigest(file) { it.addProperty("utc_offset_seconds", 0) }
        assertEquals(0, openStore(file).read()!!.utcOffsetSeconds)
        editPayloadWithUpdatedDigest(file) { it.addProperty("utc_offset_seconds", 100_000) }
        assertThrows(IOException::class.java) { openStore(file).read() }
    }

    @Test fun chargingRecoveryRetainsTheOriginalErrorAndEvidenceAfterReopen() {
        val file = file()
        val baseline = chargingBaseline()
        val requested = openStore(file).requestStart(preparation, t, "UTC", baseline)
        val reopened = openStore(file).read()!!
        assertEquals(requested, reopened)
        assertEquals(-16, reopened.startBaseline!!.status.errorCode)
        assertEquals(baseline.chargingRecoveryEvidence, reopened.startBaseline.chargingRecoveryEvidence)
        assertEquals(13, JsonParser.parseString(file.readText()).asJsonObject["journal_version"].asInt)

        assertThrows(IllegalArgumentException::class.java) {
            openStore(file).confirmStart(requested.sessionId, address, status(true).copy(errorCode = -16), t + 100)
        }
        assertEquals(requested, openStore(file).read())
        val confirmed = openStore(file).confirmStart(requested.sessionId, address, status(true), t + 100)
        openStore(file).requestStop(requested.sessionId, t + 200)
        assertThrows(IllegalArgumentException::class.java) {
            openStore(file).confirmStop(requested.sessionId, address, status(false).copy(errorCode = -16), t + 300)
        }
        assertEquals(confirmed.startBaseline, openStore(file).read()!!.startBaseline)
    }

    @Test fun recoveryBaselineRejectsMissingStaleOrContradictoryEvidenceBeforeWriting() {
        val baseline = chargingBaseline()
        val evidence = baseline.chargingRecoveryEvidence!!
        val invalid = listOf(
            baseline.copy(chargingRecoveryEvidence = null),
            baseline.copy(status = baseline.status.copy(collecting = true)),
            baseline.copy(status = baseline.status.copy(errorCode = 0)),
            baseline.copy(status = baseline.status.copy(errorCode = -15)),
            baseline.copy(chargingRecoveryEvidence = evidence.copy(statusErrorReason = 2)),
            baseline.copy(chargingRecoveryEvidence = evidence.copy(batteryChargeStatus = 1)),
            baseline.copy(chargingRecoveryEvidence = evidence.copy(batteryReceivedAtMs = t - 5001)),
            baseline.copy(chargingRecoveryEvidence = evidence.copy(checkedAtMs = t - 1)),
            baseline.copy(chargingRecoveryEvidence = evidence.copy(statusReceivedAtMs = t - 1)),
            baseline.copy(chargingRecoveryEvidence = evidence.copy(batteryConnectionGeneration = 2)),
            baseline.copy(chargingRecoveryEvidence = evidence.copy(statusConnectionGeneration = 0, batteryConnectionGeneration = 0)),
        )
        invalid.forEach { value ->
            val file = file()
            assertThrows(IllegalArgumentException::class.java) { openStore(file).requestStart(preparation, t, "UTC", value) }
            assertFalse(file.exists())
        }
    }

    @Test fun corruptedRecoveryEvidenceIsRejectedWithoutOverwritingTheJournal() {
        val changes: List<(JsonObject) -> Unit> = listOf(
            { it.remove("charging_recovery_evidence") },
            { it.add("charging_recovery_evidence", com.google.gson.JsonNull.INSTANCE) },
            { it.getAsJsonObject("charging_recovery_evidence").addProperty("status_error_reason", 2) },
            { it.getAsJsonObject("charging_recovery_evidence").addProperty("battery_received_at_ms", (t - 5001)) },
            { it.getAsJsonObject("charging_recovery_evidence").addProperty("battery_charge_status", "0") },
            { it.getAsJsonObject("charging_recovery_evidence").addProperty("unexpected", true) },
        )
        changes.forEach { change ->
            val file = file()
            openStore(file).requestStart(preparation, t, "UTC", chargingBaseline())
            editPayloadWithUpdatedDigest(file) { change(it.getAsJsonObject("start_baseline")) }
            val before = file.readBytes()
            assertThrows(IOException::class.java) { openStore(file).read() }
            assertArrayEquals(before, file.readBytes())
        }
    }

    @Test fun journalVersionFourRemainsReadableAndMigratesWithoutInventingRecoveryEvidence() {
        val file = file()
        val baseline = DeviceStartBaseline(HealthMessage.Status(false, 0, 0, 0, 0), emptyList(), t)
        val requested = openStore(file).requestStart(preparation, t, "UTC", baseline)
        val envelope = JsonParser.parseString(file.readText()).asJsonObject
        envelope.addProperty("journal_version", 4)
        file.writeText(envelope.toString())
        editPayloadWithUpdatedDigest(file) {
            it.remove("completion_policy"); it.remove("discarded"); it.remove("start_abort")
            it.remove("reference_revisions"); it.remove("start_command_dispatch")
            it.remove("stop_observed_at_ms"); it.remove("stop_origin"); it.remove("stop_command_dispatch")
            it.getAsJsonObject("start_baseline").remove("charging_recovery_evidence")
            it.getAsJsonObject("start_baseline").remove("unknown_time_start_evidence")
        }
        assertEquals(requested, openStore(file).read())
        openStore(file).confirmStart(requested.sessionId, address, status(true), t + 100)
        val migrated = JsonParser.parseString(file.readText()).asJsonObject
        assertEquals(13, migrated["journal_version"].asInt)
        assertTrue(migrated.getAsJsonObject("session").getAsJsonObject("start_baseline")["charging_recovery_evidence"].isJsonNull)
        assertNull(openStore(file).read()!!.startBaseline!!.chargingRecoveryEvidence)
    }

    @Test fun unconfirmedRecoveryAttemptCanArchiveOnlyTheUnchangedIdleBaseline() {
        val file = file()
        val store = openStore(file)
        val baseline = chargingBaseline()
        val requested = store.requestStart(preparation, t, "UTC", baseline)
        val observation = HealthRecordObservation(address, 1, baseline.status.copy(errorCode = 0), t + 100, emptyList())
        val archived = store.archiveStartAttempt(requested.sessionId, observation, t + 200, "连接检查")
        assertFalse(archived.isPending)
        assertEquals(-16, archived.startBaseline!!.status.errorCode)
        assertEquals(archived, openStore(file).read())
    }

    @Test fun selectedActivitySurvivesReopenAndConflictingRepeatCannotOverwriteIt() {
        listOf(SessionActivity.WALKING, SessionActivity.RUNNING).forEach { activity ->
            val file = file()
            val store = openStore(file)
            val requested = store.requestStart(preparation, t, "UTC", activity = activity)
            val before = file.readBytes()
            assertEquals(activity, openStore(file).read()!!.activity)
            assertEquals(requested, store.requestStart(preparation, t + 100, "UTC", activity = activity))
            SessionActivity.entries.filter { it != activity }.forEach { conflict ->
                assertThrows(IllegalArgumentException::class.java) {
                    store.requestStart(preparation, t + 200, "UTC", activity = conflict)
                }
            }
            assertArrayEquals(before, file.readBytes())
            store.confirmStart(requested.sessionId, address, status(true), t + 300)
            assertEquals(activity, openStore(file).readPending()!!.activity)
            assertThrows(IllegalArgumentException::class.java) { store.requestStart(preparation, t + 400, "UTC") }
        }
    }

    @Test fun versionsOneThroughFiveKeepHistoricalActivityDuringMigration() {
        for (version in 1..5) {
            val file = file()
            val store = openStore(file)
            val requested = store.requestStart(preparation, t, "UTC")
            downgradeJournal(file, version)
            val oldBytes = file.readBytes()
            assertEquals(requested, openStore(file).read())
            assertEquals(SessionActivity.FREE_LIVING, openStore(file).read()!!.activity)
            assertArrayEquals(oldBytes, file.readBytes())
            store.confirmStart(requested.sessionId, address, status(true), t + 100)
            assertEquals(13, JsonParser.parseString(file.readText()).asJsonObject["journal_version"].asInt)
            val migrated = openStore(file).read()!!
            assertEquals(SessionActivity.FREE_LIVING, migrated.activity)
            val payload = JsonParser.parseString(file.readText()).asJsonObject.getAsJsonObject("session")
            assertEquals("daily_activity_v2", payload["activity_schema"].asString)
            assertFalse(payload.has("activity_selection_source"))
        }
    }

    @Test fun historicalVersionsRejectSelectedActivityEvenWithAnUpdatedDigest() {
        for (version in 1..5) {
            val file = file()
            openStore(file).requestStart(preparation, t, "UTC")
            downgradeJournal(file, version)
            editPayloadWithUpdatedDigest(file) {
                it.addProperty("activity_code", "walking")
                it.addProperty("activity_schema", "daily_activity_v3")
                it.addProperty("activity_selection_source", "participant")
            }
            val before = file.readBytes()
            assertThrows(IOException::class.java) { openStore(file).read() }
            assertArrayEquals(before, file.readBytes())
        }
    }

    @Test fun selectedActivityRejectsMissingOrContradictoryFieldsAndSampleLabels() {
        val changes: List<(JsonObject) -> Unit> = listOf(
            { it.remove("activity_code") },
            { it.addProperty("activity_code", "other") },
            { it.addProperty("activity_code", true) },
            { it.addProperty("activity_code", "free_living") },
            { it.remove("activity_schema") },
            { it.addProperty("activity_schema", "daily_activity_v2") },
            { it.remove("activity_selection_source") },
            { it.addProperty("activity_selection_source", "researcher") },
            { it.add("activity_selection_source", com.google.gson.JsonNull.INSTANCE) },
            { it.addProperty("activity_label_status", "labelled") },
            { it.addProperty("activity_label_source", "participant") },
        )
        for (activity in listOf(SessionActivity.WALKING, SessionActivity.RUNNING)) {
            changes.forEach { change ->
                val file = file()
                openStore(file).requestStart(preparation, t, "UTC", activity = activity)
                editPayloadWithUpdatedDigest(file, change)
                val before = file.readBytes()
                assertThrows(IOException::class.java) { openStore(file).read() }
                assertArrayEquals(before, file.readBytes())
            }
        }
        val historical = file()
        openStore(historical).requestStart(preparation, t, "UTC")
        editPayloadWithUpdatedDigest(historical) { it.addProperty("activity_selection_source", "participant") }
        assertThrows(IOException::class.java) { openStore(historical).read() }
    }

    private fun downgradeJournal(file: File, version: Int) {
        val envelope = JsonParser.parseString(file.readText()).asJsonObject
        envelope.addProperty("journal_version", version)
        if (version == 1) envelope.remove("archived_sessions")
        file.writeText(envelope.toString())
        editPayloadWithUpdatedDigest(file) { payload ->
            payload.remove("completion_policy"); payload.remove("discarded"); payload.remove("start_abort")
            if (version < 10) payload.remove("reference_revisions")
            if (version < 11) payload.remove("start_command_dispatch")
            if (version < 12) {
                payload.remove("stop_observed_at_ms")
                payload.remove("stop_origin")
                payload.remove("stop_command_dispatch")
                payload.get("start_command_dispatch")?.takeIf { it.isJsonObject }?.asJsonObject?.let {
                    it.remove("owner_id")
                    it.remove("connection_generation")
                }
            }
            payload.get("start_baseline")?.takeIf { it.isJsonObject }?.asJsonObject?.remove("unknown_time_start_evidence")
            payload.get("start_attempt_archive")?.takeIf { it.isJsonObject }?.asJsonObject?.remove("unknown_preservation")
            if (version < 2) listOf("reference_saved_at_ms", "ground_truth_reason", "raw_files", "transfer").forEach(payload::remove)
            if (version < 3) listOf("start_baseline", "device_record_evidence", "device_association_invalidated").forEach(payload::remove)
            if (version < 4) payload.remove("start_attempt_archive")
            if (version < 5 && payload.get("start_baseline")?.isJsonObject == true) {
                payload.getAsJsonObject("start_baseline").remove("charging_recovery_evidence")
            }
        }
    }

    private fun chargingBaseline() = DeviceStartBaseline(HealthMessage.Status(false, 0, 0, -16, 0), emptyList(), t,
        ChargingRecoveryEvidence(statusErrorReason = 1, batteryChargeStatus = 0,
            batteryReceivedAtMs = t - 100, statusReceivedAtMs = t, checkedAtMs = t,
            statusConnectionGeneration = 1, batteryConnectionGeneration = 1))

    private fun file(): File = File(temporary.newFolder(), "active-session.json")
    // Windows cannot open a directory FileChannel. JVM tests exercise the journal contract;
    // the separate Android instrumented test uses the production directory-sync implementation.
    private fun openStore(file: File, syncDirectory: (File) -> Unit = {}, commit: (File, File) -> Unit = { source, target ->
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }): FreeLivingSessionStore = FreeLivingSessionStore(file, commit, syncDirectory)
    private fun status(collecting: Boolean, sessionId: Int = 41) =
        HealthMessage.Status(collecting, 1_024, 12, 0, sessionId)
    private fun boundary(epochMs: Long) = DeviceBoundaryEvidence(
        DeviceBoundarySource.RAW_SAMPLE, epochMs, 12_345L, "Synthetic test record offset=64; uptime=12345")
    private fun start(store: FreeLivingSessionStore): FreeLivingSession {
        val s = store.requestStart(preparation, t, "UTC")
        return store.confirmStart(s.sessionId, address, status(true), t + 100)
    }
    private fun finish(file: File): FreeLivingSession {
        val store = openStore(file)
        val s = start(store)
        store.requestStop(s.sessionId, t + 1_000)
        return store.confirmStop(s.sessionId, address, status(false), t + 1_100)
    }
    private fun editPayloadWithUpdatedDigest(file: File, change: (JsonObject) -> Unit) {
        val envelope = JsonParser().parse(file.readText()).asJsonObject
        val payload = envelope.getAsJsonObject("session")
        change(payload)
        val hashed = if (envelope.get("journal_version").asInt >= 2) JsonObject().apply {
            add("session", payload)
            add("archived_sessions", envelope.get("archived_sessions"))
        } else payload
        val digest = MessageDigest.getInstance("SHA-256").digest(hashed.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        envelope.addProperty("sha256", digest)
        file.writeText(envelope.toString())
    }
}
