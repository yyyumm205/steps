package com.nexthci.ringfitness

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class DeviceRecordBackupStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val address = "AA:BB:CC:DD:EE:FF"
    private val payload = ByteArrayOutputStream().apply {
        write(0x32); write(0x12); write(2)
        repeat(4) { write((1000L ushr (8 * it)).toInt()) }
        repeat(12) { write(it) }
    }.toByteArray()
    private val record = HealthMessage.ListItem(7, payload.size.toLong(), 1, 900, 1_800_000_000_000)
    private val now = 1_800_000_001_000L
    private fun directory() = File(temporary.newFolder(), "device-backups")
    private fun store(directory: File, sync: (File) -> Unit = {}) = DeviceRecordBackupStore(directory, sync)
    private fun end() = HealthMessage.ReadEnd(payload.size.toLong(), true)
    private fun folder(directory: File) = directory.listFiles()!!.single()
    private fun complete(directory: File): RealSessionDownload.Completed {
        val store = store(directory)
        return store.open(address, record).use { download ->
            download.append(HealthMessage.DataChunk(0, payload))
            val completed = download.finish(end())
            store.accept(address, record, completed, now)
            completed
        }
    }

    @Test fun missingBackupReadDoesNotCreateDirectoriesOrClaimPreservation() {
        val directory = directory()
        assertFalse(store(directory).isPreserved(address, record))
        assertFalse(directory.exists())
    }

    @Test fun interruptedDownloadReopensTheSameIdentityAndDurableOffset() {
        val directory = directory()
        store(directory).open(address, record).use {
            it.append(HealthMessage.DataChunk(0, payload.copyOf(10)))
            assertFalse(it.checkpoint(HealthMessage.ReadEnd(10, false)))
        }
        val metadata = File(folder(directory), "metadata.json").readBytes()
        assertFalse(store(directory).isPreserved(address, record))
        store(directory).open(address, record).use {
            assertEquals(10L, it.nextOffset)
            it.append(HealthMessage.DataChunk(10, payload.copyOfRange(10, payload.size)))
            val completed = it.finish(end())
            store(directory).accept(address, record, completed, now)
            it.releaseTemporary()
        }
        assertTrue(store(directory).isPreserved(address, record))
        assertArrayEquals(metadata, File(folder(directory), "metadata.json").readBytes())
        assertFalse(folder(directory).listFiles()!!.any { it.name.endsWith(".part") })
    }

    @Test fun receiptIsIdempotentAndContainsNoParticipantOrReference() {
        val directory = directory()
        val completed = complete(directory)
        val receipt = File(folder(directory), "receipt.json")
        val original = receipt.readBytes()
        store(directory).accept(address, record, completed, now + 5000)
        assertArrayEquals(original, receipt.readBytes())
        assertTrue(store(directory).isPreserved(address, record))
        val json = JsonParser.parseString(receipt.readText()).asJsonObject
        assertEquals("unassigned_device_preservation", json["purpose"].asString)
        assertEquals(address, json["ring_address"].asString)
        assertEquals(record.unixMs, json.getAsJsonObject("record")["unix_ms"].asLong)
        assertFalse(json.has("participant_id"))
        assertFalse(json.has("ground_truth_steps"))
        assertFalse(json.has("upload"))
        assertThrows(IllegalArgumentException::class.java) { store(directory).open(address, record) }
    }

    @Test fun ringAndEveryRecordFingerprintFieldSeparateBackupIdentity() {
        val directory = directory()
        complete(directory)
        assertFalse(store(directory).isPreserved("AA:BB:CC:DD:EE:01", record))
        listOf(record.copy(sessionId = 8), record.copy(bytes = record.bytes + 1), record.copy(records = 2),
            record.copy(uptimeMs = 901), record.copy(unixMs = record.unixMs + 1)).forEach {
            assertFalse(store(directory).isPreserved(address, it))
        }
        store(directory).open("AA:BB:CC:DD:EE:01", record).close()
        assertEquals(2, directory.listFiles()!!.size)
        assertTrue(store(directory).isPreserved(address, record))
    }

    @Test fun corruptedMetadataCannotOverwriteOrReassignAnExistingPartial() {
        val directory = directory()
        store(directory).open(address, record).use {
            it.append(HealthMessage.DataChunk(0, payload.copyOf(10)))
            it.checkpoint(HealthMessage.ReadEnd(10, false))
        }
        val metadata = File(folder(directory), "metadata.json")
        metadata.writeText("{bad-json")
        val before = folder(directory).listFiles()!!.associate { it.name to sha(it.readBytes()) }
        assertThrows(Exception::class.java) { store(directory).isPreserved(address, record) }
        assertThrows(Exception::class.java) { store(directory).open(address, record) }
        assertEquals(before, folder(directory).listFiles()!!.associate { it.name to sha(it.readBytes()) })
    }

    @Test fun receiptRejectsPathTraversalWrongRingAndUnknownFields() {
        val changes: List<(com.google.gson.JsonObject) -> Unit> = listOf(
            { it.getAsJsonObject("raw_file").addProperty("file_name", "../other.rfbin") },
            { it.addProperty("ring_address", "AA:BB:CC:DD:EE:01") },
            { it.getAsJsonObject("record").addProperty("uptime_ms", 901) },
            { it.addProperty("extra", true) },
            { it.addProperty("saved_at_ms", now.toString()) },
            { it.getAsJsonObject("raw_file").addProperty("simulated", true) },
        )
        changes.forEach { change ->
            val directory = directory()
            complete(directory)
            val receipt = File(folder(directory), "receipt.json")
            val json = JsonParser.parseString(receipt.readText()).asJsonObject
            change(json)
            receipt.writeText(json.toString())
            val before = receipt.readBytes()
            assertThrows(Exception::class.java) { store(directory).isPreserved(address, record) }
            assertThrows(Exception::class.java) { store(directory).open(address, record) }
            assertArrayEquals(before, receipt.readBytes())
        }
    }

    @Test fun missingOrDamagedRawFileNeverCountsAsPreserved() {
        val directory = directory()
        val completed = complete(directory)
        val raw = File(folder(directory), completed.file.fileName)
        val original = raw.readBytes()
        raw.appendBytes(byteArrayOf(1))
        assertThrows(Exception::class.java) { store(directory).isPreserved(address, record) }
        raw.writeBytes(original)
        assertTrue(store(directory).isPreserved(address, record))
        check(raw.renameTo(File(folder(directory), "preserved-outside-receipt.rfbin")))
        assertThrows(Exception::class.java) { store(directory).isPreserved(address, record) }
    }

    @Test fun validHashCannotHideAChangedContainerFingerprint() {
        val directory = directory()
        val completed = complete(directory)
        val raw = File(folder(directory), completed.file.fileName)
        val altered = raw.readBytes().also { it[20] = (it[20].toInt() xor 1).toByte() }
        raw.writeBytes(altered)
        val receipt = File(folder(directory), "receipt.json")
        val json = JsonParser.parseString(receipt.readText()).asJsonObject
        json.getAsJsonObject("raw_file").addProperty("sha256", sha(altered))
        receipt.writeText(json.toString())
        assertThrows(Exception::class.java) { store(directory).isPreserved(address, record) }
    }

    @Test fun failedReceiptDirectorySyncKeepsOriginalAndBlocksSuccessUntilRetrySyncs() {
        val directory = directory()
        val stable = store(directory)
        stable.open(address, record).use { download ->
            download.append(HealthMessage.DataChunk(0, payload))
            val completed = download.finish(end())
            val failing = store(directory) { throw IOException("injected directory sync failure") }
            assertThrows(IOException::class.java) { failing.accept(address, record, completed, now) }
            assertTrue(File(folder(directory), completed.file.fileName).isFile)
            assertTrue(folder(directory).listFiles()!!.any { it.name.endsWith(".part") })
            assertThrows(IOException::class.java) { failing.isPreserved(address, record) }
            stable.accept(address, record, completed, now + 1000)
            assertTrue(stable.isPreserved(address, record))
        }
    }

    @Test fun unknownAnchorsAndInvalidAddressCannotAuthorizeReplacement() {
        val directory = directory()
        assertThrows(IllegalArgumentException::class.java) { store(directory).isPreserved(address, record.copy(unixMs = 0)) }
        assertThrows(IllegalArgumentException::class.java) { store(directory).open(address, record.copy(uptimeMs = 0)) }
        assertThrows(IllegalArgumentException::class.java) { store(directory).open("../ring", record) }
        assertFalse(directory.exists())
    }

    @Test fun unknownClockRereadsUseIndependentOriginalsEvenWithTheSameDescriptor() {
        val directory = directory()
        val store = store(directory)
        val unknown = record.copy(unixMs = 0)
        val attempts = listOf(java.util.UUID.randomUUID().toString(), java.util.UUID.randomUUID().toString())
        val variants = listOf(payload, payload.copyOf().apply { this[lastIndex] = (this[lastIndex] + 1).toByte() })
        val hashes = attempts.zip(variants).map { (id, bytes) ->
            store.open(address, unknown, id).use { download ->
                assertEquals(0L, download.nextOffset)
                download.append(HealthMessage.DataChunk(0, bytes))
                val completed = download.finish(end())
                store.accept(address, unknown, completed, now, id)
                download.releaseTemporary()
                store.verifyUnknown(address, unknown, id).sha256
            }
        }
        assertEquals(2, hashes.toSet().size)
        assertEquals(hashes, attempts.map { store.verifyUnknown(address, unknown, it).sha256 })
        assertThrows(IllegalArgumentException::class.java) { store.isPreserved(address, unknown) }
        assertEquals(2, directory.listFiles()!!.size)
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
