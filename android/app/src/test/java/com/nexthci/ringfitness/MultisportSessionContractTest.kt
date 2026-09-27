package com.nexthci.ringfitness

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.CRC32
import java.util.zip.ZipFile

class MultisportSessionContractTest {
    @get:Rule val temporary = TemporaryFolder()
    private val t = 1_789_804_800_000L
    private val preparation = PreparationSnapshot("p001", "83e56afb-a74c-4825-af0b-b226cfe9c630",
        RingPlacement.LEFT_INDEX, PreparedRing("AA:BB:CC:DD:EE:01", "Ringo"))

    @Test fun nineSelectableSportsHaveAnExplicitStepApplicability() {
        assertEquals(9, SessionActivity.selectable.size)
        assertFalse(SessionActivity.selectable.contains(SessionActivity.FREE_LIVING))
        assertEquals(listOf(SessionActivity.WALKING, SessionActivity.RUNNING), SessionActivity.selectable.filter { it.requiresReferenceSteps })
        assertTrue(SessionActivity.FREE_LIVING.requiresReferenceSteps)
        SessionActivity.selectable.forEach { activity ->
            assertEquals(activity, SessionActivity.fromWireValue(activity.wireValue))
            val f = fixture(activity)
            val reference = f.completeReference()
            assertEquals(activity, f.reopen().read()!!.activity)
            val frozen = f.packager().freeze(f.completeRaw())
            val manifest = manifest(frozen)
            assertEquals(8, manifest["version"].asInt)
            assertEquals("ringfitness-session-${activity.wireValue}-${reference.sessionId}.zip", frozen.file.name)
            assertFalse(manifest.getAsJsonObject("heart_rate")["enabled"].asBoolean)
            if (activity.requiresReferenceSteps) {
                assertEquals("external_pedometer", manifest["ground_truth_source"].asString)
                assertEquals("valid", manifest["ground_truth_status"].asString)
                assertEquals(0L, manifest["ground_truth_steps"].asLong)
            } else {
                assertEquals("none", manifest["ground_truth_source"].asString)
                assertEquals("not_applicable", manifest["ground_truth_status"].asString)
                listOf("ground_truth_steps", "ground_truth_recorded_at_ms", "ground_truth_reason").forEach {
                    assertTrue(manifest[it].isJsonNull)
                }
            }
        }
    }

    @Test fun stepAndNonstepReferenceKindsCannotBeConfused() {
        SessionActivity.selectable.forEach { activity ->
            val f = fixture(activity)
            val wrong = if (activity.requiresReferenceSteps) SessionReference(ReferenceStatus.NOT_APPLICABLE, null, t + 3_000)
                else SessionReference(ReferenceStatus.VALID, 0, t + 3_000)
            assertThrows(IllegalArgumentException::class.java) { f.store.finalizeStoppedSession(f.session.sessionId, CompletionPolicy.SAVE_UPLOAD, wrong) }
            assertNull(f.reopen().read()!!.reference)
        }
    }

    @Test fun nonstepDeferredChoiceSurvivesReopenAndDoesNotAuthorizeAutomaticTransfer() {
        val f = fixture(SessionActivity.BADMINTON)
        val saved = f.completeReference(CompletionPolicy.DEFER_ON_RING)
        assertTrue(f.reopen().read()!!.isRingDeferred)
        assertFalse(saved.uploadAllowed)
        assertEquals(ReferenceStatus.NOT_APPLICABLE, saved.reference!!.status)
        assertThrows(IllegalArgumentException::class.java) { f.completeRaw() }
        f.reopen().resumeRingTransfer(saved.sessionId)
        assertNotNull(f.completeRaw().localData)
    }

    @Test fun selectedHeartRateDeviceAndProgressSurviveReopenAndFreezeInsideSameZip() {
        val f = fixture(SessionActivity.TENNIS, heartRate = heart())
        f.completeReference()
        val captured = f.writeHeart(listOf(t + 100, t + 800))
        val finished = f.store.updateHeartRate(f.session.sessionId, captured)
        assertEquals(captured, f.reopen().read()!!.heartRate)
        val complete = f.completeRaw()
        val frozen = f.packager().freeze(complete)
        val before = frozen.file.readBytes()
        val m = manifest(frozen)
        assertEquals("recorded", m.getAsJsonObject("heart_rate")["status"].asString)
        assertEquals("phone_receipt", m.getAsJsonObject("heart_rate")["timestamp_source"].asString)
        val hr = m.getAsJsonArray("files").map { it.asJsonObject }.single { it["role"].asString == "polar_hr_rr" }
        assertEquals(SessionHeartRate.fileName(f.session.sessionId), hr["file_name"].asString)
        assertTrue(hr["device_session_id"].isJsonNull)
        ZipFile(frozen.file).use { zip -> assertNotNull(zip.getEntry(hr["file_name"].asString)) }
        assertArrayEquals(before, f.packager().freeze(finished).file.readBytes())
        assertThrows(IllegalArgumentException::class.java) { f.store.updateHeartRate(f.session.sessionId, captured.copy(sampleCount = 3)) }
    }

    @Test fun heartRateMustFinishBeforeLocalDataCanBeMarkedComplete() {
        val f = fixture(SessionActivity.RUNNING, heartRate = heart())
        f.completeReference()
        assertThrows(IllegalArgumentException::class.java) { f.completeRaw() }
        assertNull(f.reopen().read()!!.localData)
        f.store.updateHeartRate(f.session.sessionId, f.writeHeart(emptyList()))
        assertNotNull(f.completeRaw().localData)
    }

    @Test fun emptyHeaderIsValidAndAbsentFileRequiresRecordedStorageError() {
        val f = fixture(SessionActivity.FOOTBALL, heartRate = heart())
        f.completeReference()
        f.store.updateHeartRate(f.session.sessionId, f.writeHeart(emptyList()))
        val m = manifest(f.packager().freeze(f.completeRaw()))
        assertEquals("no_samples", m.getAsJsonObject("heart_rate")["status"].asString)
        val missing = fixture(SessionActivity.VOLLEYBALL, heartRate = heart())
        missing.completeReference()
        missing.store.updateHeartRate(missing.session.sessionId, heart().copy(endedAtMs = t + 1_001))
        assertThrows(IllegalArgumentException::class.java) { missing.completeRaw() }

        val failure = fixture(SessionActivity.BASKETBALL, heartRate = heart())
        failure.completeReference()
        failure.store.updateHeartRate(failure.session.sessionId, heart().copy(endedAtMs = t + 1_001,
            gaps = listOf(HeartRateGap(t, t + 1_001, "storage_error"))))
        val failureManifest = manifest(failure.packager().freeze(failure.completeRaw()))
        assertEquals("no_samples", failureManifest.getAsJsonObject("heart_rate")["status"].asString)
        assertTrue(failureManifest.getAsJsonArray("files").none { it.asJsonObject["role"].asString == "polar_hr_rr" })
    }

    @Test fun realGapsAndPhoneClockChangesPreserveActualSamples() {
        val f = fixture(SessionActivity.STRENGTH_TRAINING, heartRate = heart())
        f.completeReference()
        val captured = f.writeHeart(listOf(t + 600, t - 1_000), end = t - 500).copy(
            gaps = listOf(HeartRateGap(t + 700, t - 900, "process_restart")))
        f.store.updateHeartRate(f.session.sessionId, captured)
        val m = manifest(f.packager().freeze(f.completeRaw()))
        assertEquals("partial", m.getAsJsonObject("heart_rate")["status"].asString)
        assertEquals(t - 1_000, m.getAsJsonObject("heart_rate")["last_sample_at_ms"].asLong)
    }

    @Test fun incompleteGapCorruptContentOrDifferentFilenameCannotBePublished() {
        val f = fixture(SessionActivity.TABLE_TENNIS, heartRate = heart())
        f.completeReference()
        val captured = f.writeHeart(listOf(t + 100))
        assertThrows(IllegalArgumentException::class.java) { f.store.updateHeartRate(f.session.sessionId,
            captured.copy(file = captured.file!!.copy(fileName = "someone_else.csv"))) }
        f.store.updateHeartRate(f.session.sessionId, captured.copy(gaps = listOf(HeartRateGap(t + 200, null, "disconnected"))))
        assertThrows(IllegalArgumentException::class.java) { f.completeRaw() }

        val corrupt = fixture(SessionActivity.TENNIS, heartRate = heart())
        corrupt.completeReference()
        val row = corrupt.writeHeart(listOf(t + 100))
        val source = File(corrupt.directory, row.file!!.fileName)
        source.appendText("broken,row\n")
        corrupt.store.updateHeartRate(corrupt.session.sessionId, row.copy(file = SessionHeartRateFile(source.name, source.length(), sha(source.readBytes()))))
        assertThrows(IllegalArgumentException::class.java) { corrupt.completeRaw() }
    }

    @Test fun heartRateCannotAttachLateOrSwitchDeviceMidSession() {
        val f = fixture(SessionActivity.BADMINTON, heartRate = heart())
        assertThrows(IllegalArgumentException::class.java) { f.store.updateHeartRate(f.session.sessionId, heart().copy(deviceId = "OTHER")) }
        val off = fixture(SessionActivity.RUNNING)
        assertThrows(IllegalArgumentException::class.java) { off.store.updateHeartRate(off.session.sessionId, heart()) }
    }

    @Test fun discardDeletesOnlyOwnedHeartRateAndSportPackage() {
        val f = fixture(SessionActivity.FOOTBALL, heartRate = heart())
        f.completeReference()
        f.store.updateHeartRate(f.session.sessionId, f.writeHeart(listOf(t + 100)))
        val frozen = f.packager().freeze(f.completeRaw())
        val unrelated = File(f.directory, "other_polar_hr_rr.csv").apply { writeText("retain") }
        f.store.discardStoppedSession(f.session.sessionId, t + 6_000)
        f.store.cleanupDiscardedSession(f.session.sessionId)
        f.store.cleanupDiscardedSession(f.session.sessionId)
        assertFalse(File(f.directory, SessionHeartRate.fileName(f.session.sessionId)).exists())
        assertFalse(frozen.file.exists())
        assertEquals("retain", unrelated.readText())
    }

    @Test fun exportFrozenNineSportHeartRateContractFixturesWhenRequested() {
        val export = System.getenv("RINGFITNESS_CONTRACT_EXPORT_DIR")?.let(::File)
        export?.mkdirs()
        export?.resolve("TEST-FIXTURES-ONLY.txt")?.writeText(
            "Synthetic contract fixtures from JVM tests, participant contractqa. No human or device data. " +
                "The production packager rejects simulated=true, so these exercise its real-shaped contract. " +
                "Keep this directory isolated; never upload these ZIPs to the study cloud.\n")
        for (activity in SessionActivity.selectable) for (enabled in listOf(false, true)) {
            val f = fixture(activity, heartRate = if (enabled) heart() else null, participantId = "contractqa")
            f.completeReference()
            if (enabled) f.store.updateHeartRate(f.session.sessionId, f.writeHeart(listOf(t + 100, t + 600)))
            val frozen = f.packager().freeze(f.completeRaw())
            val m = manifest(frozen)
            assertEquals(activity.wireValue, m["activity_code"].asString)
            assertEquals(enabled, m.getAsJsonObject("heart_rate")["enabled"].asBoolean)
            assertEquals("contractqa", m["participant_id"].asString)
            export?.let { Files.copy(frozen.file.toPath(), File(it, frozen.file.name).toPath(), StandardCopyOption.REPLACE_EXISTING) }
        }
    }

    private fun heart() = SessionHeartRate("ABC123", "Polar H10 ABC123", t)
    private fun fixture(activity: SessionActivity, heartRate: SessionHeartRate? = null,
        participantId: String = preparation.participantId): Fixture {
        val directory = temporary.newFolder()
        val store = store(directory)
        val uptime = 1_000L + activity.ordinal * 10_000L + if (heartRate == null) 0L else 1_000L
        val payload = ByteArrayOutputStream().apply {
            write(0x32); write(0x12); write(2)
            repeat(4) { write((uptime ushr (it * 8)).toInt()) }
            repeat(12) { write(it + 1) }
        }.toByteArray()
        val record = HealthMessage.ListItem(7, payload.size.toLong(), 1, uptime - 100, t)
        val baseline = DeviceStartBaseline(HealthMessage.Status(false, 0, 0, 0, 6), emptyList(), t)
        val started = store.requestStart(preparation.copy(participantId = participantId), t, "UTC",
            baseline, activity = activity, heartRate = heartRate)
        val collecting = HealthMessage.Status(true, record.bytes, record.records, 0, 7)
        store.confirmStart(started.sessionId, preparation.ring!!.address, collecting, t + 1,
            recordEvidence = DeviceRecordEvidence(record, collecting, t + 1))
        store.requestStop(started.sessionId, t + 1_000)
        val stoppedStatus = collecting.copy(collecting = false)
        val stopped = store.confirmStop(started.sessionId, preparation.ring.address, stoppedStatus, t + 1_001,
            recordEvidence = DeviceRecordEvidence(record, stoppedStatus, t + 1_001))
        return Fixture(directory, store, stopped, record, payload)
    }

    private inner class Fixture(val directory: File, val store: FreeLivingSessionStore, val session: FreeLivingSession,
        val record: HealthMessage.ListItem, val payload: ByteArray) {
        fun reopen() = store(directory)
        fun completeReference(policy: CompletionPolicy = CompletionPolicy.SAVE_UPLOAD): FreeLivingSession =
            if (session.activity.requiresReferenceSteps) store.finalizeStoppedSession(session.sessionId, policy,
                SessionReference(ReferenceStatus.VALID, 0, t + 3_000))
            else store.finalizeStoppedSessionWithoutReference(session.sessionId, policy, t + 3_000)

        fun completeRaw(simulated: Boolean = false): FreeLivingSession {
            val raw = File(directory, "${session.sessionId}-ring-7.rfbin")
            raw.writeBytes(HealthRawV2.header(record, 0, 0, payload.size.toLong(), CRC32().apply { update(payload) }.value) + payload)
            return store.completeLocalData(session.sessionId, listOf(SessionRawFile(raw.name, 7, raw.length(), sha(raw.readBytes()), simulated)), t + 4_000)
        }

        fun writeHeart(times: List<Long>, end: Long = t + 1_001): SessionHeartRate {
            val raw = File(directory, SessionHeartRate.fileName(session.sessionId))
            raw.writeText(SessionHeartRate.CSV_HEADER + "\n" + times.mapIndexed { index, time ->
                "${Instant.ofEpochMilli(time)},$time,${index + 1},72,72,0,true,true,true,800|810,819|829\n"
            }.joinToString(""))
            return heart().copy(endedAtMs = end, sampleCount = times.size.toLong(), firstSampleAtMs = times.firstOrNull(),
                lastSampleAtMs = times.lastOrNull(), file = SessionHeartRateFile(raw.name, raw.length(), sha(raw.readBytes())))
        }

        fun packager() = FreeLivingSessionPackage(directory, reopen(), {},
            { from, to -> Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE) }, { Instant.ofEpochMilli(t + 5_000) })
    }

    private fun store(directory: File) = FreeLivingSessionStore(File(directory, "session.json"),
        { from, to -> Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }, {})
    private fun manifest(frozen: FrozenSessionPackage): JsonObject = ZipFile(frozen.file).use { zip ->
        JsonParser.parseString(zip.getInputStream(zip.getEntry("manifest.json")).reader().readText()).asJsonObject
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}
