package com.nexthci.ringfitness

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.UUID

class PhoneClockSyncTest {
    @get:Rule val temporary = TemporaryFolder()
    private val address = "AA:BB:CC:DD:EE:FF"
    private val epoch = 1_800_000_000_000L
    private val sessionId = "00000000-0000-0000-0000-000000000001"
    private fun accept(
        synced: Boolean = true,
        requested: Long = epoch,
        received: Long = epoch + 100,
        sentElapsed: Long = 5000,
        receivedElapsed: Long = 5100,
        device: Long = epoch + 50,
        uptime: Long = 1000,
        ring: String = address,
        generation: Long = 3,
    ) = PhoneClockSync.accept(ring, generation, requested, sentElapsed, receivedElapsed,
        SensorPacket.TimeStatus(synced, device, uptime, received))

    @Test fun acceptedReplyPreservesTheObservedTimesAndConnection() {
        val result = accept()
        assertEquals(UUID.fromString(result.attemptId).toString(), result.attemptId)
        assertEquals(address, result.ringAddress)
        assertEquals(3L, result.connectionGeneration)
        assertEquals(epoch, result.requestedAtMs)
        assertEquals(epoch + 100, result.receivedAtMs)
        assertEquals(5000L, result.requestedElapsedMs)
        assertEquals(5100L, result.receivedElapsedMs)
        assertEquals(epoch + 50, result.deviceUnixMs)
        assertEquals(1000L, result.deviceUptimeMs)
        assertNotEquals(result.attemptId, accept().attemptId)
    }

    @Test fun zeroRoundTripAndTimeoutBoundaryAreAllowed() {
        accept(received = epoch, receivedElapsed = 5000, device = epoch, uptime = 0)
        accept(received = epoch + 3000, receivedElapsed = 8000)
        assertThrows(IllegalArgumentException::class.java) {
            accept(received = epoch + 3001, receivedElapsed = 8001)
        }
    }

    @Test fun unsuccessfulOrMissingDeviceClockIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { accept(synced = false) }
        assertThrows(IllegalArgumentException::class.java) { accept(device = 0) }
        assertThrows(IllegalArgumentException::class.java) { accept(device = -1) }
        assertThrows(IllegalArgumentException::class.java) { accept(uptime = -1) }
    }

    @Test fun invalidConnectionOrPhoneTimeIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { accept(generation = 0) }
        assertThrows(IllegalArgumentException::class.java) { accept(generation = -1) }
        assertThrows(IllegalArgumentException::class.java) { accept(ring = "other ring") }
        assertThrows(IllegalArgumentException::class.java) { accept(requested = 0) }
        assertThrows(IllegalArgumentException::class.java) { accept(received = epoch - 1) }
        assertThrows(IllegalArgumentException::class.java) { accept(sentElapsed = -1) }
        assertThrows(IllegalArgumentException::class.java) { accept(receivedElapsed = 4999) }
    }

    @Test fun wallClockSkewToleranceIsInclusiveAndDoesNotHideJumps() {
        accept(received = epoch + 350)
        accept(received = epoch + 50, receivedElapsed = 5300)
        assertThrows(IllegalArgumentException::class.java) { accept(received = epoch + 351) }
        assertThrows(IllegalArgumentException::class.java) { accept(received = epoch + 49, receivedElapsed = 5300) }
    }

    @Test fun deviceClockMustFallWithinTheToleratedRequestReplyWindow() {
        assertEquals(epoch - 250, accept(device = epoch - 250).deviceUnixMs)
        assertEquals(epoch + 350, accept(device = epoch + 350).deviceUnixMs)
        assertThrows(IllegalArgumentException::class.java) { accept(device = epoch - 251) }
        assertThrows(IllegalArgumentException::class.java) { accept(device = epoch + 351) }
    }

    @Test fun extremeLongValuesCannotWrapIntoAcceptedTiming() {
        assertThrows(IllegalArgumentException::class.java) { accept(received = Long.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { accept(receivedElapsed = Long.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { accept(device = Long.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { accept(requested = Long.MIN_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { accept(sentElapsed = Long.MIN_VALUE) }
        val result = accept(requested = Long.MAX_VALUE - 100, received = Long.MAX_VALUE,
            sentElapsed = Long.MAX_VALUE - 100, receivedElapsed = Long.MAX_VALUE,
            device = Long.MAX_VALUE, uptime = Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, result.deviceUnixMs)
    }

    @Test fun attemptAndSessionBindingReopenWithTheSameEvidence() {
        val directory = temporary.newFolder()
        val evidence = accept()
        var syncs = 0
        PhoneClockSync.save(directory, evidence, syncDirectory = { syncs++ })
        PhoneClockSync.save(directory, evidence, sessionId, syncDirectory = { syncs++ })
        val attempt = File(directory, "clock-sync-${evidence.attemptId}.json")
        val binding = File(directory, "$sessionId.clock-sync.json")
        val original = binding.readBytes()
        val json = JsonParser.parseString(binding.readText()).asJsonObject
        assertEquals(sessionId, json["session_id"].asString)
        assertEquals(evidence.attemptId, json["attempt_id"].asString)
        assertEquals(address, json["ring_address"].asString)
        assertEquals(epoch + 50, json["device_unix_ms"].asLong)
        assertEquals(250L, json["wall_clock_tolerance_ms"].asLong)
        assertTrue(JsonParser.parseString(attempt.readText()).asJsonObject["session_id"].isJsonNull)
        assertFalse(json.has("upload_link"))
        PhoneClockSync.save(File(directory.path), evidence.copy(), sessionId, syncDirectory = { syncs++ })
        assertEquals(3, syncs)
        assertArrayEquals(original, binding.readBytes())
        assertEquals(2, directory.listFiles()!!.size)
    }

    @Test fun differentEvidenceCannotReplaceAnAttemptOrSessionBinding() {
        val directory = temporary.newFolder()
        val evidence = accept()
        PhoneClockSync.save(directory, evidence, syncDirectory = {})
        PhoneClockSync.save(directory, evidence, sessionId, syncDirectory = {})
        val before = directory.listFiles()!!.associate { it.name to it.readText() }
        assertThrows(IllegalArgumentException::class.java) {
            PhoneClockSync.save(directory, evidence.copy(deviceUnixMs = epoch + 51), syncDirectory = {})
        }
        assertThrows(IllegalArgumentException::class.java) {
            PhoneClockSync.save(directory, accept(), sessionId, syncDirectory = {})
        }
        assertEquals(before, directory.listFiles()!!.associate { it.name to it.readText() })
    }

    @Test fun failedDirectorySyncIsReportedAndAnIdenticalRetryMustSyncAgain() {
        val directory = temporary.newFolder()
        val evidence = accept()
        var calls = 0
        val sync: (File) -> Unit = { calls++; throw IOException("injected directory sync failure") }
        assertThrows(IOException::class.java) { PhoneClockSync.save(directory, evidence, syncDirectory = sync) }
        val saved = directory.listFiles()!!.single()
        val original = saved.readBytes()
        assertThrows(IOException::class.java) { PhoneClockSync.save(directory, evidence, syncDirectory = sync) }
        assertEquals(2, calls)
        PhoneClockSync.save(directory, evidence, syncDirectory = { calls++ })
        assertEquals(3, calls)
        assertArrayEquals(original, saved.readBytes())
        assertFalse(directory.listFiles()!!.any { it.name.endsWith(".tmp") })
    }

    @Test fun malformedIdentifiersOrEvidenceCannotCreateOrReplaceFiles() {
        val directory = temporary.newFolder()
        val evidence = accept()
        assertThrows(IllegalArgumentException::class.java) {
            PhoneClockSync.save(directory, evidence.copy(attemptId = "../escape"), syncDirectory = {})
        }
        assertThrows(IllegalArgumentException::class.java) {
            PhoneClockSync.save(directory, evidence, "../escape", syncDirectory = {})
        }
        assertThrows(IllegalArgumentException::class.java) {
            PhoneClockSync.save(directory, evidence.copy(deviceUnixMs = 0), syncDirectory = {})
        }
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun missingDirectoryIsNotCreatedOrReportedSaved() {
        val directory = File(temporary.newFolder(), "missing")
        assertThrows(IOException::class.java) { PhoneClockSync.save(directory, accept(), syncDirectory = {}) }
        assertFalse(directory.exists())
    }

    @Test fun corruptedExistingEvidenceIsRetainedAndRejected() {
        val directory = temporary.newFolder()
        val evidence = accept()
        val target = File(directory, "clock-sync-${evidence.attemptId}.json")
        target.writeText("incomplete evidence")
        assertThrows(IllegalArgumentException::class.java) {
            PhoneClockSync.save(directory, evidence, syncDirectory = {})
        }
        assertEquals("incomplete evidence", target.readText())
    }
}
