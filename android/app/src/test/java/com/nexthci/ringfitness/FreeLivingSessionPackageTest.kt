package com.nexthci.ringfitness

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class FreeLivingSessionPackageTest {
    @get:Rule val temporary = TemporaryFolder()
    private val t = 1_789_804_800_000L
    private val packagedAt = Instant.ofEpochMilli(t + 5_000)
    private val preparation = PreparationSnapshot("pkg001", "83e56afb-a74c-4825-af0b-b226cfe9c630",
        RingPlacement.LEFT_INDEX, PreparedRing("AA:BB:CC:DD:EE:07", "Ringo package fixture"))

    @Test fun zeroAndUnknownTimesArePreservedInTheFrozenManifest() {
        val f = fixture(SessionReference(ReferenceStatus.VALID, 0, t + 3000))
        val frozen = f.packager.freeze(f.session)
        val manifest = manifest(frozen)
        assertEquals(7, manifest["version"].asInt)
        assertEquals(1, manifest["step_schema_version"].asInt)
        assertEquals(2, manifest["rfbin_version"].asInt)
        assertEquals("hand_finger_v1", manifest["ring_placement_schema"].asString)
        assertEquals("left", manifest["ring_hand"].asString)
        assertEquals("index", manifest["ring_finger"].asString)
        assertEquals(BuildConfig.VERSION_NAME, manifest["app_version"].asString)
        assertEquals(packagedAt.toString(), manifest["created_at"].asString)
        assertEquals("user_request", manifest["stop_origin"].asString)
        assertTrue(manifest["stop_observed_at_ms"].isJsonNull)
        assertNotEquals(Instant.ofEpochMilli(t).toString(), manifest["created_at"].asString)
        assertEquals("daily_activity_v2", manifest["activity_schema"].asString)
        assertEquals("free_living", manifest["activity_code"].asString)
        assertEquals("unlabelled", manifest["activity_label_status"].asString)
        assertEquals("none", manifest["activity_label_source"].asString)
        assertEquals(0L, manifest["ground_truth_steps"].asLong)
        assertEquals("valid", manifest["ground_truth_status"].asString)
        assertTrue(manifest["started_at_ms"].isJsonNull)
        assertTrue(manifest["ended_at_ms"].isJsonNull)
        assertEquals("uncertain", manifest["capture_boundary_status"].asString)
        assertEquals(t, manifest["start_requested_at_ms"].asLong)
        assertEquals(t + 3000, manifest["reference_saved_at_ms"].asLong)
        assertEquals(t + 4000, manifest["download_completed_at_ms"].asLong)
        listOf("phase", "transfer", "raw_files", "upload_link", "token").forEach { assertFalse(manifest.has(it)) }
        assertFalse(manifest["simulated"].asBoolean)
        assertEquals("ringfitness-session-${f.session.sessionId}.zip", frozen.file.name)
        assertEquals(sha(frozen.file.readBytes()), frozen.sha256)
        assertEquals(frozen.file.length(), frozen.bytes)
        val item = manifest.getAsJsonArray("files").single().asJsonObject
        assertEquals("raw", item["role"].asString)
        ZipFile(frozen.file).use { zip ->
            assertArrayEquals(f.rawFiles.single().readBytes(), zip.getInputStream(zip.getEntry(f.rawFiles.single().name)).readBytes())
        }
    }

    @Test fun walkingAndRunningFreezeAsTwoActivityQualifiedArchives() {
        listOf(SessionActivity.WALKING, SessionActivity.RUNNING).forEach { activity ->
            val f = fixture(activity = activity)
            val frozen = f.packager.freeze(f.session)
            assertEquals(
                "ringfitness-session-${activity.wireValue}-${f.session.sessionId}.zip",
                frozen.file.name,
            )
            assertEquals(activity.wireValue, manifest(frozen)["activity_code"].asString)
            val metadata = JsonParser.parseString(File(frozen.file.parentFile, "package.json").readText()).asJsonObject
            assertEquals(2, metadata["package_version"].asInt)
            assertEquals(frozen.file.name, metadata["file_name"].asString)
        }
    }

    @Test fun missingAndUnreliableReadingsRemainDistinctAndLongMaxIsExact() {
        val values = listOf(
            SessionReference(ReferenceStatus.MISSING, null, t + 3000, "无法读取"),
            SessionReference(ReferenceStatus.UNRELIABLE, 73, t + 3000, "忘记清零"),
            SessionReference(ReferenceStatus.VALID, Long.MAX_VALUE, t + 3000),
        )
        values.forEach { reference ->
            val f = fixture(reference)
            val m = manifest(f.packager.freeze(f.session))
            assertEquals(reference.status.wireValue, m["ground_truth_status"].asString)
            if (reference.steps == null) {
                assertTrue(m["ground_truth_steps"].isJsonNull)
                assertTrue(m["ground_truth_recorded_at_ms"].isJsonNull)
            } else {
                assertEquals(reference.steps, m["ground_truth_steps"].asLong)
                assertEquals(reference.recordedAtMs, m["ground_truth_recorded_at_ms"].asLong)
            }
            if (reference.reason != null) assertEquals(reference.reason, m["ground_truth_reason"].asString)
        }
    }

    @Test fun retriesAfterOwnerReopenOrReceiptChangesUseTheSameFrozenBytes() {
        val f = fixture()
        val first = f.packager.freeze(f.session)
        val original = first.file.readBytes()
        val manifestBefore = manifest(first)
        f.store.markTransferStarted(f.session.sessionId)
        f.store.markTransferFailed(f.session.sessionId)
        f.store.markTransferStarted(f.session.sessionId)
        f.store.completeTransfer(f.session.sessionId,
            SessionTransferReceipt("real-server-file", t + 7000, false, f.session.sessionId))
        val reopened = packager(f.directory, openStore(f.directory),
            now = { Instant.ofEpochMilli(t + 90_000) }).freeze(f.session)
        assertEquals(first, reopened)
        assertArrayEquals(original, reopened.file.readBytes())
        assertEquals(manifestBefore, manifest(reopened))
        assertEquals(2, f.store.read()!!.transfer.attempts)
    }

    @Test fun deferredReferenceCorrectionInvalidatesAnUnpublishedZipAndFreezesOnlyTheCorrectedValue() {
        val f = fixture(SessionReference(ReferenceStatus.VALID, 12, t + 3_000))
        f.store.setCompletionPolicy(f.session.sessionId, CompletionPolicy.SAVE_LATER)
        val first = f.packager.freeze(requireNotNull(f.store.read()))
        assertEquals(12L, manifest(first)["ground_truth_steps"].asLong)

        val corrected = SessionReference(ReferenceStatus.VALID, 21, t + 6_000)
        val revised = f.packager.reviseUnpublishedReference(f.session.sessionId, corrected)
        assertFalse(first.file.exists())
        assertEquals(corrected, revised.reference)
        assertEquals(listOf(SessionReferenceRevision(
            SessionReference(ReferenceStatus.VALID, 12, t + 3_000), t + 6_000)), revised.referenceRevisions)
        assertEquals(revised, openStore(f.directory).read())

        val second = f.packager.freeze(revised)
        assertEquals(21L, manifest(second)["ground_truth_steps"].asLong)
        assertFalse(manifest(second).has("reference_revisions"))
        f.store.allowUpload(f.session.sessionId)
        val uploadConfirmedCorrection = SessionReference(ReferenceStatus.VALID, 22, t + 7_000)
        val uploadConfirmed = f.packager.reviseUnpublishedReference(f.session.sessionId, uploadConfirmedCorrection)
        assertEquals(uploadConfirmedCorrection, uploadConfirmed.reference)
        assertEquals(2, uploadConfirmed.referenceRevisions.size)
        f.store.markTransferStarted(f.session.sessionId)
        assertThrows(IllegalArgumentException::class.java) {
            f.store.reviseReferenceBeforeUpload(f.session.sessionId,
                SessionReference(ReferenceStatus.VALID, 23, t + 8_000))
        }
    }

    @Test fun recoveryPackageVersionsOnlyTheNewEvidenceAndKeepsSuccessfulRepliesStrict() {
        val evidence = ChargingRecoveryEvidence(statusErrorReason = 1, batteryChargeStatus = 0,
            batteryReceivedAtMs = t - 100, statusReceivedAtMs = t, checkedAtMs = t,
            statusConnectionGeneration = 1, batteryConnectionGeneration = 1)
        val f = fixture(recoveryEvidence = evidence)
        val frozen = f.packager.freeze(f.session)
        val before = frozen.file.readBytes()
        val m = manifest(frozen)
        assertEquals(7, m["version"].asInt)
        assertEquals(1, m["step_schema_version"].asInt)
        assertEquals("daily_activity_v2", m["activity_schema"].asString)
        assertEquals(-16, m.getAsJsonObject("start_baseline").getAsJsonObject("status")["error_code"].asInt)
        val recovery = m.getAsJsonObject("start_baseline").getAsJsonObject("charging_recovery_evidence")
        assertEquals(1, recovery["status_error_reason"].asInt)
        assertEquals(0, recovery["battery_charge_status"].asInt)
        assertEquals(t - 100, recovery["battery_received_at_ms"].asLong)
        assertEquals(t, recovery["status_received_at_ms"].asLong)
        assertEquals(t, recovery["checked_at_ms"].asLong)
        assertEquals(1, recovery["status_connection_generation"].asInt)
        assertEquals(1, recovery["battery_connection_generation"].asInt)
        assertEquals(0, m.getAsJsonObject("start_status_evidence")["error_code"].asInt)
        assertEquals(0, m.getAsJsonObject("stop_status_evidence")["error_code"].asInt)
        assertFalse(m.has("start_attempt_archive"))
        assertEquals(evidence, openStore(f.directory).read()!!.startBaseline!!.chargingRecoveryEvidence)
        assertArrayEquals(before, packager(f.directory, openStore(f.directory)).freeze(f.session).file.readBytes())
    }

    @Test fun upgradingVersionFourJournalPreservesExistingFrozenPackageBytes() {
        val f = fixture()
        val journal = File(f.directory, "session.json")
        val envelope = JsonParser.parseString(journal.readText()).asJsonObject
        envelope.addProperty("journal_version", 4)
        val payload = envelope.getAsJsonObject("session")
        payload.getAsJsonObject("start_baseline").remove("charging_recovery_evidence")
        payload.getAsJsonObject("start_baseline").remove("unknown_time_start_evidence")
        payload.remove("completion_policy")
        payload.remove("discarded")
        payload.remove("start_abort")
        payload.remove("reference_revisions")
        payload.remove("start_command_dispatch")
        payload.remove("stop_observed_at_ms")
        payload.remove("stop_origin")
        payload.remove("stop_command_dispatch")
        val hashed = JsonObject().apply {
            add("session", payload)
            add("archived_sessions", envelope["archived_sessions"])
        }
        envelope.addProperty("sha256", sha(hashed.toString().toByteArray(Charsets.UTF_8)))
        journal.writeText(envelope.toString())
        val frozen = f.packager.freeze(f.session)
        val before = frozen.file.readBytes()
        val m = manifest(frozen)
        assertEquals(7, m["version"].asInt)
        assertFalse(m.getAsJsonObject("start_baseline").has("charging_recovery_evidence"))
        assertFalse(m.has("start_attempt_archive"))
        f.store.markTransferStarted(f.session.sessionId)
        assertEquals(13, JsonParser.parseString(journal.readText()).asJsonObject["journal_version"].asInt)
        assertArrayEquals(before, packager(f.directory, openStore(f.directory)).freeze(f.session).file.readBytes())
        assertEquals(m, manifest(f.packager.freeze(f.session)))
    }

    @Test fun existingLegacyFrozenPackagesRemainByteIdenticalAfterManifestUpgrade() {
        val recovery = ChargingRecoveryEvidence(statusErrorReason = 1, batteryChargeStatus = 0,
            batteryReceivedAtMs = t - 100, statusReceivedAtMs = t, checkedAtMs = t,
            statusConnectionGeneration = 1, batteryConnectionGeneration = 1)
        data class LegacyCase(val version: Int, val recovery: ChargingRecoveryEvidence? = null,
            val activity: SessionActivity = SessionActivity.FREE_LIVING, val unknownTime: Boolean = false)
        listOf(
            LegacyCase(2),
            LegacyCase(3, recovery = recovery),
            LegacyCase(4, activity = SessionActivity.WALKING),
            LegacyCase(5, recovery = recovery, activity = SessionActivity.RUNNING, unknownTime = true),
        ).forEach { legacy ->
            val f = fixture(recoveryEvidence = legacy.recovery, activity = legacy.activity,
                unknownTime = legacy.unknownTime)
            val frozen = f.packager.freeze(f.session)
            val legacyArchive = rewriteAsLegacy(frozen, legacy.version)
            val original = legacyArchive.readBytes()

            val reopened = packager(f.directory, openStore(f.directory)).freeze(f.session)

            assertArrayEquals(original, reopened.file.readBytes())
            assertEquals(legacyArchive.name, reopened.file.name)
            assertEquals(legacy.version, manifest(reopened)["version"].asInt)
        }
    }

    @Test fun versionSevenActivityPackagesFromVersion081KeepTheirLegacyNameAndBytes() {
        listOf(SessionActivity.WALKING, SessionActivity.RUNNING).forEach { activity ->
            val f = fixture(activity = activity)
            val current = f.packager.freeze(f.session)
            val legacyArchive = rewriteAsPackageVersionOne(current)
            val original = legacyArchive.readBytes()
            val originalHash = sha(original)

            val reopened = packager(f.directory, openStore(f.directory)).freeze(f.session)

            assertEquals("ringfitness-session-${f.session.sessionId}.zip", reopened.file.name)
            assertEquals(originalHash, reopened.sha256)
            assertArrayEquals(original, reopened.file.readBytes())
            assertEquals(7, manifest(reopened)["version"].asInt)
            assertEquals(activity.wireValue, manifest(reopened)["activity_code"].asString)
            assertFalse(File(reopened.file.parentFile,
                "ringfitness-session-${activity.wireValue}-${f.session.sessionId}.zip").exists())
            val packageDirectory = requireNotNull(reopened.file.parentFile)
            assertEquals(listOf(legacyArchive.name),
                packageDirectory.listFiles()!!.filter { it.extension == "zip" }.map { it.name })
        }
    }

    @Test fun multipleRawFilesAndEvidenceShareOneReferenceAtTheRoot() {
        val f = fixture(rawCount = 2, withEvidence = true)
        val frozen = f.packager.freeze(f.session)
        val m = manifest(frozen)
        assertEquals(73L, m["ground_truth_steps"].asLong)
        val files = m.getAsJsonArray("files").map { it.asJsonObject }
        assertEquals(4, files.size)
        assertEquals(2, files.count { it["role"].asString == "raw" })
        assertEquals(2, files.count { it["role"].asString == "evidence" })
        assertTrue(files.all { it["file_name"].asString.startsWith(f.session.sessionId) && !it.has("ground_truth_steps") })
        ZipFile(frozen.file).use { zip ->
            assertEquals(files.map { it["file_name"].asString }.toSet() + "manifest.json",
                zip.entries().asSequence().map { it.name }.toSet())
            files.forEach { item ->
                val bytes = zip.getInputStream(zip.getEntry(item["file_name"].asString)).readBytes()
                assertEquals(item["bytes"].asLong, bytes.size.toLong())
                assertEquals(item["sha256"].asString, sha(bytes))
            }
        }
    }

    @Test fun damagedRawFileRejectsRetryWithoutReplacingFrozenPackage() {
        val f = fixture()
        val frozen = f.packager.freeze(f.session)
        val original = frozen.file.readBytes()
        f.rawFiles.single().appendBytes(byteArrayOf(1))
        assertThrows(Exception::class.java) { f.packager.freeze(f.session) }
        assertArrayEquals(original, frozen.file.readBytes())
    }

    @Test fun damagedZipIsRejectedInsteadOfBeingRebuilt() {
        val f = fixture()
        val frozen = f.packager.freeze(f.session)
        frozen.file.appendBytes(byteArrayOf(1))
        val damaged = frozen.file.readBytes()
        assertThrows(IllegalArgumentException::class.java) { f.packager.freeze(f.session) }
        assertArrayEquals(damaged, frozen.file.readBytes())
    }

    @Test fun changedResearchMetadataRejectsRetryButKeepsTheFrozenSnapshot() {
        val f = fixture()
        val frozen = f.packager.freeze(f.session)
        val original = frozen.file.readBytes()
        f.store.invalidateDeviceAssociation(f.session.sessionId)
        assertThrows(IllegalArgumentException::class.java) { f.packager.freeze(f.session) }
        assertArrayEquals(original, frozen.file.readBytes())
        assertFalse(manifest(frozen)["device_association_invalidated"].asBoolean)
    }

    @Test fun matchingFileShaDoesNotHideAnInvalidPayloadCrcOrRecordCount() {
        val invalidCrc = fixture(transform = { bytes -> bytes.also { it[56] = (it[56].toInt() xor 1).toByte() } })
        assertThrows(IllegalArgumentException::class.java) { invalidCrc.packager.freeze(invalidCrc.session) }
        val invalidCount = fixture(transform = { bytes -> bytes.also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(16, 17)
        } })
        assertThrows(IllegalArgumentException::class.java) { invalidCount.packager.freeze(invalidCount.session) }
    }

    @Test fun evidenceForAnotherSessionIsPreservedAndRejected() {
        val f = fixture(withEvidence = true)
        val evidence = f.directory.listFiles()!!.single { it.name.endsWith(".raw-evidence.json") }
        val wrong = JsonParser.parseString(evidence.readText()).asJsonObject.apply {
            addProperty("session_id", UUID.randomUUID().toString())
        }
        evidence.writeText(wrong.toString())
        assertThrows(IllegalArgumentException::class.java) { f.packager.freeze(f.session) }
        assertEquals(wrong, JsonParser.parseString(evidence.readText()))
        assertFalse(File(f.directory, "packages/${f.session.sessionId}").exists())
    }

    @Test fun simulatedFilesAndIncompleteSessionsCannotBecomeExperimentPackages() {
        val simulation = fixture(simulated = true)
        assertThrows(IllegalArgumentException::class.java) { simulation.packager.freeze(simulation.session) }
        val directory = temporary.newFolder()
        val store = openStore(directory)
        val started = store.requestStart(preparation, t, "Asia/Shanghai")
        assertThrows(IllegalArgumentException::class.java) { packager(directory, store).freeze(started) }
    }

    @Test fun failedAtomicCommitKeepsRawDataAndRetryBuildsOneCompletePackage() {
        val f = fixture()
        val originalRaw = f.rawFiles.single().readBytes()
        val failure = packager(f.directory, f.store, commit = { _, _ -> throw IOException("injected move failure") })
        assertThrows(IOException::class.java) { failure.freeze(f.session) }
        assertArrayEquals(originalRaw, f.rawFiles.single().readBytes())
        assertFalse(File(f.directory, "packages/${f.session.sessionId}").exists())
        val successful = f.packager.freeze(f.session)
        assertTrue(successful.file.isFile)
        assertEquals(1, File(f.directory, "packages").listFiles()!!.size)
    }

    @Test fun directorySyncFailureAfterRenameRetainsThePackageForIdenticalRetry() {
        val f = fixture()
        val target = File(f.directory, "packages/${f.session.sessionId}")
        var injected = true
        val failure = packager(f.directory, f.store, sync = { path ->
            if (injected && path.name == "packages" && target.exists()) throw IOException("injected directory sync failure")
        })
        assertThrows(IOException::class.java) { failure.freeze(f.session) }
        val committed = target.listFiles()!!.single { it.extension == "zip" }
        val bytes = committed.readBytes()
        injected = false
        val result = failure.freeze(f.session)
        assertArrayEquals(bytes, result.file.readBytes())
        assertEquals(sha(bytes), result.sha256)
    }

    @Test fun damagedFrozenManifestIsRejectedWithoutTouchingTheZip() {
        val f = fixture()
        val frozen = f.packager.freeze(f.session)
        val bytes = frozen.file.readBytes()
        File(frozen.file.parentFile, "manifest.snapshot.json").writeText("{}")
        assertThrows(IllegalArgumentException::class.java) { f.packager.freeze(f.session) }
        assertArrayEquals(bytes, frozen.file.readBytes())
    }

    private data class Fixture(val directory: File, val store: FreeLivingSessionStore,
        val session: FreeLivingSession, val packager: FreeLivingSessionPackage, val rawFiles: List<File>)

    private fun fixture(reference: SessionReference = SessionReference(ReferenceStatus.VALID, 73, t + 3000),
        rawCount: Int = 1, withEvidence: Boolean = false, simulated: Boolean = false,
        recoveryEvidence: ChargingRecoveryEvidence? = null,
        activity: SessionActivity = SessionActivity.FREE_LIVING, unknownTime: Boolean = false,
        transform: (ByteArray) -> ByteArray = { it }): Fixture {
        val directory = temporary.newFolder()
        val store = openStore(directory)
        val payloads = (0 until rawCount).map { imu(it * 40L + 1000) }
        val record = HealthMessage.ListItem(7, payloads.sumOf { it.size }.toLong(), rawCount.toLong(),
            if (unknownTime) 1_100 else 900, t)
        val previous = HealthMessage.ListItem(6, 20, 1, 900, 0)
        val unknownEvidence = if (!unknownTime) null else UnknownTimeStartEvidence(
            previous, UUID.randomUUID().toString(), "a".repeat(64), UUID.randomUUID().toString(), 1, t - 300,
            PhoneClockSyncEvidence(UUID.randomUUID().toString(), preparation.ring!!.address, 1,
                t - 110, t - 100, 100, 110, t - 105, 1_000),
        )
        val baselineRecords = if (unknownTime) listOf(previous) else emptyList()
        val baselineStatus = if (unknownTime) HealthMessage.Status(false, previous.bytes, previous.records,
            if (recoveryEvidence == null) 0 else -16, previous.sessionId)
        else HealthMessage.Status(false, 0, 0, if (recoveryEvidence == null) 0 else -16, 6)
        val started = store.requestStart(preparation, t, "Asia/Shanghai",
            DeviceStartBaseline(baselineStatus, baselineRecords, t, recoveryEvidence,
                unknownTimeStartEvidence = unknownEvidence), activity)
        val collecting = HealthMessage.Status(true, record.bytes, record.records, 0, 7)
        store.confirmStart(started.sessionId, preparation.ring!!.address, collecting, t + 1,
            recordEvidence = DeviceRecordEvidence(record, collecting, t + 1))
        store.requestStop(started.sessionId, t + 1000)
        val stopped = collecting.copy(collecting = false)
        store.confirmStop(started.sessionId, preparation.ring.address, stopped, t + 1001,
            recordEvidence = DeviceRecordEvidence(record, stopped, t + 1001))
        store.saveReference(started.sessionId, reference)
        val files = payloads.mapIndexed { index, payload ->
            val file = File(directory, "${started.sessionId}-ring-7-$index.rfbin")
            val crc = CRC32().apply { update(payload) }.value
            val partRecord = record.copy(bytes = payload.size.toLong(), records = 1)
            file.writeBytes(transform(HealthRawV2.header(partRecord, 0, 0, payload.size.toLong(), crc) + payload))
            if (withEvidence) File(directory, file.name.removeSuffix(".rfbin") + ".raw-evidence.json").writeText(
                JsonObject().apply {
                    addProperty("session_id", started.sessionId)
                    addProperty("device_session_id", 7)
                    addProperty("file_sha256", sha(file.readBytes()))
                    addProperty("bytes", payload.size)
                    addProperty("payload_crc32", crc)
                }.toString(),
            )
            file
        }
        val complete = store.completeLocalData(started.sessionId,
            files.map { SessionRawFile(it.name, 7, it.length(), sha(it.readBytes()), simulated) }, t + 4000)
        return Fixture(directory, store, complete, packager(directory, store), files)
    }

    private fun openStore(directory: File) = FreeLivingSessionStore(File(directory, "session.json"),
        { source, target -> Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }, {})

    private fun packager(directory: File, store: FreeLivingSessionStore, sync: (File) -> Unit = {},
        commit: (File, File) -> Unit = { source, target -> Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE) },
        now: () -> Instant = { packagedAt }) =
        FreeLivingSessionPackage(directory, store, sync, commit, now)

    private fun manifest(value: FrozenSessionPackage): JsonObject = ZipFile(value.file).use { zip ->
        JsonParser.parseString(zip.getInputStream(zip.getEntry("manifest.json")).reader(Charsets.UTF_8).readText()).asJsonObject
    }

    private fun rewriteAsLegacy(frozen: FrozenSessionPackage, version: Int): File {
        val target = frozen.file.parentFile
        val legacy = manifest(frozen).apply {
            listOf("ring_placement_schema", "ring_hand", "ring_finger", "app_version", "created_at",
                "stop_origin", "stop_observed_at_ms").forEach(::remove)
            addProperty("version", version)
        }
        val contents = ZipFile(frozen.file).use { zip ->
            zip.entries().asSequence().associate { entry ->
                entry.name to if (entry.name == "manifest.json") legacy.toString().toByteArray(Charsets.UTF_8)
                else zip.getInputStream(entry).readBytes()
            }
        }
        val replacement = File(target, "legacy.zip.tmp")
        ZipOutputStream(FileOutputStream(replacement)).use { zip ->
            contents.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        val legacyArchive = File(target, "ringfitness-session-${frozen.sessionId}.zip")
        Files.move(replacement.toPath(), legacyArchive.toPath(), StandardCopyOption.REPLACE_EXISTING)
        if (frozen.file != legacyArchive) assertTrue(frozen.file.delete())
        File(target, "manifest.snapshot.json").writeText(legacy.toString(), Charsets.UTF_8)
        File(target, "package.json").writeText(JsonObject().apply {
            addProperty("package_version", 1)
            addProperty("session_id", frozen.sessionId)
            addProperty("file_name", legacyArchive.name)
            addProperty("bytes", legacyArchive.length())
            addProperty("sha256", sha(legacyArchive.readBytes()))
            addProperty("manifest_sha256", sha(legacy.toString().toByteArray(Charsets.UTF_8)))
        }.toString(), Charsets.UTF_8)
        return legacyArchive
    }

    private fun rewriteAsPackageVersionOne(frozen: FrozenSessionPackage): File {
        val target = frozen.file.parentFile
        val legacyArchive = File(target, "ringfitness-session-${frozen.sessionId}.zip")
        Files.move(frozen.file.toPath(), legacyArchive.toPath(), StandardCopyOption.REPLACE_EXISTING)
        File(target, "package.json").writeText(JsonObject().apply {
            addProperty("package_version", 1)
            addProperty("session_id", frozen.sessionId)
            addProperty("file_name", legacyArchive.name)
            addProperty("bytes", legacyArchive.length())
            addProperty("sha256", sha(legacyArchive.readBytes()))
            val snapshot = File(target, "manifest.snapshot.json").readBytes()
            addProperty("manifest_sha256", sha(snapshot))
        }.toString(), Charsets.UTF_8)
        return legacyArchive
    }

    private fun imu(uptime: Long) = ByteArrayOutputStream().apply {
        write(0x32); write(0x12); write(2)
        repeat(4) { write((uptime ushr (it * 8)).toInt()) }
        repeat(12) { write(it + 1) }
    }.toByteArray()

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
