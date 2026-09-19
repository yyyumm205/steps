package com.nexthci.ringfitness

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipFile

class FreeLivingSessionPackageTest {
    @get:Rule val temporary = TemporaryFolder()
    private val t = 1_789_804_800_000L
    private val preparation = PreparationSnapshot("pkg001", "83e56afb-a74c-4825-af0b-b226cfe9c630",
        RingPlacement.LEFT_INDEX, PreparedRing("AA:BB:CC:DD:EE:07", "Ringo package fixture"))

    @Test fun zeroAndUnknownTimesArePreservedInTheFrozenManifest() {
        val f = fixture(SessionReference(ReferenceStatus.VALID, 0, t + 3000))
        val frozen = f.packager.freeze(f.session)
        val manifest = manifest(frozen)
        assertEquals(2, manifest["version"].asInt)
        assertEquals(1, manifest["step_schema_version"].asInt)
        assertEquals(2, manifest["rfbin_version"].asInt)
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

    @Test fun missingAndUnreliableReadingsRemainDistinctAndLongMaxIsExact() {
        val values = listOf(
            SessionReference(ReferenceStatus.MISSING, null, t + 3000, "无法读取"),
            SessionReference(ReferenceStatus.UNRELIABLE, null, t + 3000, "计步器归零"),
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
        val reopened = packager(f.directory, openStore(f.directory)).freeze(f.session)
        assertEquals(first, reopened)
        assertArrayEquals(original, reopened.file.readBytes())
        assertEquals(manifestBefore, manifest(reopened))
        assertEquals(2, f.store.read()!!.transfer.attempts)
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
        transform: (ByteArray) -> ByteArray = { it }): Fixture {
        val directory = temporary.newFolder()
        val store = openStore(directory)
        val payloads = (0 until rawCount).map { imu(it * 40L + 1000) }
        val record = HealthMessage.ListItem(7, payloads.sumOf { it.size }.toLong(), rawCount.toLong(), 900, t)
        val started = store.requestStart(preparation, t, "Asia/Shanghai",
            DeviceStartBaseline(HealthMessage.Status(false, 0, 0, 0, 6), emptyList(), t))
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
        commit: (File, File) -> Unit = { source, target -> Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE) }) =
        FreeLivingSessionPackage(directory, store, sync, commit)

    private fun manifest(value: FrozenSessionPackage): JsonObject = ZipFile(value.file).use { zip ->
        JsonParser.parseString(zip.getInputStream(zip.getEntry("manifest.json")).reader(Charsets.UTF_8).readText()).asJsonObject
    }

    private fun imu(uptime: Long) = ByteArrayOutputStream().apply {
        write(0x32); write(0x12); write(2)
        repeat(4) { write((uptime ushr (it * 8)).toInt()) }
        repeat(12) { write(it + 1) }
    }.toByteArray()

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
