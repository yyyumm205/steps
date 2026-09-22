package com.nexthci.ringfitness

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

class RealSessionDownloadTest {
    @get:Rule val temporary = TemporaryFolder()
    private val session = "5f13d497-57a5-458d-83c7-426f603c28be"
    private val ring = "AA:BB:CC:DD:EE:FF"
    private val payload get() = imu(2, 1000) + imu(3, 1060)
    private val record get() = HealthMessage.ListItem(7, payload.size.toLong(), 2, 900, 1_800_000_000_000)
    private fun adapter(directory: File, item: HealthMessage.ListItem = record,
        sync: (File) -> Unit = {}): RealSessionDownload =
        RealSessionDownload(directory, session, ring, item, 0L, 0L, sync)
    private fun part(directory: File) = File(directory, "$session-ring-7.part")
    private fun final(directory: File) = File(directory, "$session-ring-7.rfbin")
    private fun end(size: Int = payload.size) = HealthMessage.ReadEnd(size.toLong(), true)

    @Test fun completeFilePreservesRawBytesAndUnknownBoundariesWithCrcAndShaEvidence() {
        val directory = temporary.newFolder()
        adapter(directory).use { download ->
            download.append(HealthMessage.DataChunk(0, payload))
            val result = download.finish(end())
            val bytes = final(directory).readBytes()
            assertArrayEquals(payload, bytes.copyOfRange(64, bytes.size))
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(7, header.getShort(12).toInt())
            assertEquals(2, header.getInt(16))
            assertEquals(0L, header.getLong(32))
            assertEquals(0L, header.getLong(40))
            assertEquals(payload.size.toLong(), header.getLong(48))
            assertEquals(CRC32().apply { update(payload) }.value, header.getInt(56).toLong() and 0xFFFF_FFFFL)
            assertEquals(5, result.evidence.imuSamples)
            assertEquals(1000L, result.evidence.firstImuUptimeMs)
            assertEquals(1060L, result.evidence.lastImuUptimeMs)
            assertEquals(bytes.size.toLong(), result.file.bytes)
            assertFalse(result.file.simulated)
            assertEquals(64, result.file.sha256.length)
            val evidence = JsonParser.parseString(File(directory, result.evidenceFileName).readText()).asJsonObject
            assertEquals("not_assessed", evidence["sample_coverage_status"].asString)
            assertEquals("phone_payload_and_container_reread", evidence["crc_source"].asString)
            assertTrue(part(directory).isFile)
            assertEquals(result, download.finish(end()))
            download.releaseTemporary()
            assertFalse(part(directory).exists())
            assertTrue(final(directory).isFile)
        }
    }

    @Test fun reopenedDownloadResumesExactOffsetAndDoesNotDuplicateOverlap() {
        val directory = temporary.newFolder()
        adapter(directory).use { download ->
            download.append(HealthMessage.DataChunk(0, payload.copyOfRange(0, 13)))
            assertFalse(download.checkpoint(HealthMessage.ReadEnd(13, false)))
        }
        adapter(directory).use { resumed ->
            assertEquals(13L, resumed.nextOffset)
            resumed.append(HealthMessage.DataChunk(9, payload.copyOfRange(9, payload.size)))
            val result = resumed.finish(end())
            assertEquals(2L, result.evidence.records)
            assertArrayEquals(payload, part(directory).readBytes())
        }
    }

    @Test fun threeReadWindowsWithoutDataStopAndKeepTheEmptyCheckpoint() {
        val directory = temporary.newFolder()
        adapter(directory).use { download ->
            repeat(2) {
                download.beginRead()
                assertFalse(download.checkpoint(HealthMessage.ReadEnd(0, false)))
            }
            download.beginRead()
            assertThrows(RealSessionDownload.DownloadNoProgressException::class.java) {
                download.checkpoint(HealthMessage.ReadEnd(0, false))
            }
            assertEquals(0L, download.nextOffset)
            assertTrue(part(directory).isFile)
            assertFalse(final(directory).exists())
        }
        adapter(directory).use { assertEquals(0L, it.nextOffset) }
    }

    @Test fun duplicateOnlyWindowsStopAndResumeFromThePreservedPartial() {
        val directory = temporary.newFolder()
        adapter(directory).use { it.append(HealthMessage.DataChunk(0, payload.copyOf(13))) }
        adapter(directory).use { download ->
            repeat(2) {
                download.beginRead()
                download.append(HealthMessage.DataChunk(0, payload.copyOf(13)))
                assertFalse(download.checkpoint(HealthMessage.ReadEnd(13, false)))
            }
            download.beginRead()
            download.append(HealthMessage.DataChunk(0, payload.copyOf(13)))
            assertThrows(RealSessionDownload.DownloadNoProgressException::class.java) {
                download.checkpoint(HealthMessage.ReadEnd(13, false))
            }
            assertArrayEquals(payload.copyOf(13), part(directory).readBytes())
            assertFalse(final(directory).exists())
        }
        adapter(directory).use { resumed ->
            assertEquals(13L, resumed.nextOffset)
            resumed.beginRead()
            resumed.append(HealthMessage.DataChunk(13, payload.copyOfRange(13, payload.size)))
            assertEquals(2L, resumed.finish(end()).evidence.records)
        }
    }

    @Test fun newBytesResetTheWindowBudgetWhileRepeatedBytesKeepCounting() {
        val directory = temporary.newFolder()
        adapter(directory).use { download ->
            repeat(2) {
                download.beginRead()
                assertFalse(download.checkpoint(HealthMessage.ReadEnd(0, false)))
            }
            download.beginRead()
            download.append(HealthMessage.DataChunk(0, payload.copyOf(12)))
            assertFalse(download.checkpoint(HealthMessage.ReadEnd(12, false)))
            repeat(2) {
                download.beginRead()
                download.append(HealthMessage.DataChunk(0, payload.copyOf(12)))
                assertFalse(download.checkpoint(HealthMessage.ReadEnd(12, false)))
            }
            download.beginRead()
            download.append(HealthMessage.DataChunk(0, payload.copyOf(12)))
            assertThrows(RealSessionDownload.DownloadNoProgressException::class.java) {
                download.checkpoint(HealthMessage.ReadEnd(12, false))
            }
            assertArrayEquals(payload.copyOf(12), part(directory).readBytes())
        }
    }

    @Test fun doneAtAnAlreadyDownloadedEndCanCompleteWithoutAnotherDataChunk() {
        val directory = temporary.newFolder()
        adapter(directory).use { it.append(HealthMessage.DataChunk(0, payload)) }
        adapter(directory).use { resumed ->
            repeat(2) {
                resumed.beginRead()
                assertFalse(resumed.checkpoint(HealthMessage.ReadEnd(record.bytes, false)))
            }
            resumed.beginRead()
            assertTrue(resumed.checkpoint(end()))
            val completed = resumed.finish(end())
            assertEquals(completed, resumed.finish(end()))
            assertArrayEquals(payload, part(directory).readBytes())
        }
    }

    @Test fun replayProgressResetsTheBudgetWhileTheDownloadedPrefixStaysFixed() {
        val directory = temporary.newFolder()
        adapter(directory).use { it.append(HealthMessage.DataChunk(0, payload.copyOf(18))) }
        adapter(directory).use { download ->
            for (offset in listOf(0L, 6L, 12L)) {
                repeat(2) {
                    download.beginRead(offset)
                    download.checkpointReplay(HealthMessage.ReadEnd(offset, false), offset)
                }
                download.beginRead(offset)
                val endOffset = offset + 6
                download.append(HealthMessage.DataChunk(offset, payload.copyOfRange(offset.toInt(), endOffset.toInt())))
                download.checkpointReplay(HealthMessage.ReadEnd(endOffset, false), endOffset)
                assertEquals(18L, download.nextOffset)
            }
            download.beginRead()
            download.append(HealthMessage.DataChunk(18, payload.copyOfRange(18, payload.size)))
            assertEquals(2L, download.finish(end()).evidence.records)
        }
    }

    @Test fun replayWithOnlyAlreadyVerifiedBytesStopsAndPreservesTheWholePartial() {
        val directory = temporary.newFolder()
        adapter(directory).use { it.append(HealthMessage.DataChunk(0, payload.copyOf(18))) }
        adapter(directory).use { download ->
            download.beginRead(0)
            download.append(HealthMessage.DataChunk(0, payload.copyOf(6)))
            download.checkpointReplay(HealthMessage.ReadEnd(6, false), 6)
            repeat(2) {
                download.beginRead(6)
                download.append(HealthMessage.DataChunk(0, payload.copyOf(6)))
                download.checkpointReplay(HealthMessage.ReadEnd(6, false), 6)
            }
            download.beginRead(6)
            download.append(HealthMessage.DataChunk(0, payload.copyOf(6)))
            assertThrows(RealSessionDownload.DownloadNoProgressException::class.java) {
                download.checkpointReplay(HealthMessage.ReadEnd(6, false), 6)
            }
            assertEquals(18L, download.nextOffset)
            assertArrayEquals(payload.copyOf(18), part(directory).readBytes())
        }
        adapter(directory).use { assertEquals(18L, it.nextOffset) }
    }

    @Test fun oldRecordBackupRetainsItsPartialAfterThreeEmptyWindows() {
        val directory = File(temporary.newFolder(), "device-backups")
        val backups = DeviceRecordBackupStore(directory) {}
        backups.open(ring, record).use { download ->
            download.beginRead()
            download.append(HealthMessage.DataChunk(0, payload.copyOf(13)))
            assertFalse(download.checkpoint(HealthMessage.ReadEnd(13, false)))
            repeat(2) {
                download.beginRead()
                assertFalse(download.checkpoint(HealthMessage.ReadEnd(13, false)))
            }
            download.beginRead()
            assertThrows(RealSessionDownload.DownloadNoProgressException::class.java) {
                download.checkpoint(HealthMessage.ReadEnd(13, false))
            }
        }
        assertFalse(backups.isPreserved(ring, record))
        backups.open(ring, record).use { resumed ->
            assertEquals(13L, resumed.nextOffset)
            resumed.beginRead()
            resumed.append(HealthMessage.DataChunk(13, payload.copyOfRange(13, payload.size)))
            val completed = resumed.finish(end())
            backups.accept(ring, record, completed, record.unixMs + 1000)
        }
        assertTrue(backups.isPreserved(ring, record))
    }

    @Test fun matchingDuplicateChunkIsIdempotentButConflictingOverlapPreservesOriginal() {
        val directory = temporary.newFolder()
        adapter(directory).use { download ->
            download.append(HealthMessage.DataChunk(0, payload.copyOf(15)))
            assertEquals(15L, download.append(HealthMessage.DataChunk(0, payload.copyOf(15))))
            assertThrows(IllegalArgumentException::class.java) {
                download.append(HealthMessage.DataChunk(5, byteArrayOf(100)))
            }
            assertEquals(15L, download.nextOffset)
            assertArrayEquals(payload.copyOf(15), part(directory).readBytes())
        }
    }

    @Test fun gapAndOutOfRangeChunksCannotCreateSparseOrExtraData() {
        val directory = temporary.newFolder()
        adapter(directory).use { download ->
            assertThrows(IllegalArgumentException::class.java) {
                download.append(HealthMessage.DataChunk(2, payload))
            }
            assertThrows(IllegalArgumentException::class.java) {
                download.append(HealthMessage.DataChunk(0, payload + 0))
            }
            assertEquals(0L, download.nextOffset)
            assertEquals(0L, part(directory).length())
        }
    }

    @Test fun prematureDoneAndMismatchedReadEndNeverPublishACompleteFile() {
        val directory = temporary.newFolder()
        adapter(directory).use { download ->
            download.append(HealthMessage.DataChunk(0, payload.copyOf(10)))
            assertThrows(IllegalArgumentException::class.java) { download.finish(end(10)) }
            assertThrows(IllegalArgumentException::class.java) {
                download.checkpoint(HealthMessage.ReadEnd(11, false))
            }
            assertFalse(final(directory).exists())
            assertEquals(10L, part(directory).length())
        }
    }

    @Test fun changedDeviceRecordOnRetryKeepsOldPayloadAndRefusesNewAssociation() {
        val directory = temporary.newFolder()
        adapter(directory).use { it.append(HealthMessage.DataChunk(0, payload.copyOf(12))) }
        val original = part(directory).readBytes()
        assertThrows(IllegalArgumentException::class.java) {
            adapter(directory, record.copy(uptimeMs = record.uptimeMs + 1))
        }
        assertArrayEquals(original, part(directory).readBytes())
    }

    @Test fun damagedCommittedPrefixIsDetectedBeforeResuming() {
        val directory = temporary.newFolder()
        adapter(directory).use { it.append(HealthMessage.DataChunk(0, payload.copyOf(12))) }
        RandomAccessFile(part(directory), "rw").use { it.seek(4); it.write(100) }
        assertThrows(IllegalArgumentException::class.java) { adapter(directory) }
        assertTrue(part(directory).isFile)
        assertFalse(final(directory).exists())
    }

    @Test fun uncheckpointedTailAfterCrashIsReplayedAndComparedBeforeExtending() {
        val directory = temporary.newFolder()
        adapter(directory).use { it.append(HealthMessage.DataChunk(0, payload.copyOf(8))) }
        part(directory).appendBytes(payload.copyOfRange(8, 17))
        adapter(directory).use { download ->
            assertEquals(8L, download.nextOffset)
            download.append(HealthMessage.DataChunk(8, payload.copyOfRange(8, payload.size)))
            download.finish(end())
            assertArrayEquals(payload, part(directory).readBytes())
        }
    }

    @Test fun incompletePacketIsRetainedWhenParsingFails() {
        val directory = temporary.newFolder()
        val damaged = payload.copyOf(payload.size - 1)
        adapter(directory, record.copy(bytes = damaged.size.toLong())).use { download ->
            download.append(HealthMessage.DataChunk(0, damaged))
            assertThrows(IOException::class.java) { download.finish(end(damaged.size)) }
            assertArrayEquals(damaged, part(directory).readBytes())
            assertFalse(final(directory).exists())
        }
    }

    @Test fun payloadChangedAfterAppendIsNotWrappedAsAnApparentlyValidContainer() {
        val directory = temporary.newFolder()
        adapter(directory).use { download ->
            download.append(HealthMessage.DataChunk(0, payload))
            RandomAccessFile(part(directory), "rw").use { it.seek(9); it.write(99) }
            assertThrows(IllegalArgumentException::class.java) { download.finish(end()) }
            assertTrue(part(directory).isFile)
            assertFalse(final(directory).exists())
        }
    }

    @Test fun recordCountMismatchKeepsPartialAndNeverReportsComplete() {
        val directory = temporary.newFolder()
        adapter(directory, record.copy(records = 3)).use { download ->
            download.append(HealthMessage.DataChunk(0, payload))
            assertThrows(IllegalArgumentException::class.java) { download.finish(end()) }
            assertArrayEquals(payload, part(directory).readBytes())
            assertFalse(final(directory).exists())
        }
    }

    @Test fun mixedOriginalRecordKindsAreCountedWithoutRewritingUptimeWrap() {
        val directory = temporary.newFolder()
        val mixed = imu(2, 0xFFFF_FFFFL) + byteArrayOf(0x32, 0x10) + ByteArray(15) +
            ppg(3, 7, 5) + imu(1, 10)
        val item = record.copy(bytes = mixed.size.toLong(), records = 4)
        adapter(directory, item).use { download ->
            download.append(HealthMessage.DataChunk(0, mixed))
            val result = download.finish(end(mixed.size))
            assertEquals(4L, result.evidence.records)
            assertEquals(3L, result.evidence.imuSamples)
            assertEquals(3L, result.evidence.ppgSamples)
            assertEquals(0xFFFF_FFFFL, result.evidence.firstImuUptimeMs)
            assertEquals(10L, result.evidence.lastImuUptimeMs)
            assertArrayEquals(mixed, part(directory).readBytes())
        }
    }

    @Test fun corruptFinalFileIsNotOverwrittenOnRetry() {
        val directory = temporary.newFolder()
        adapter(directory).use { download ->
            download.append(HealthMessage.DataChunk(0, payload))
            download.finish(end())
        }
        RandomAccessFile(final(directory), "rw").use { it.seek(65); it.write(0) }
        val damaged = final(directory).readBytes()
        adapter(directory).use { resumed ->
            assertThrows(IllegalArgumentException::class.java) { resumed.finish(end()) }
            assertArrayEquals(damaged, final(directory).readBytes())
            assertArrayEquals(payload, part(directory).readBytes())
        }
    }

    @Test fun directorySyncFailureAfterFinalRenameRetainsRawAndReusesSameFinalFile() {
        val directory = temporary.newFolder()
        var fail = true
        adapter(directory, sync = {
            if (fail && final(directory).exists()) throw IOException("injected directory sync failure")
        }).use { download ->
            download.append(HealthMessage.DataChunk(0, payload))
            assertThrows(IOException::class.java) { download.finish(end()) }
            assertTrue(final(directory).isFile)
            assertArrayEquals(payload, part(directory).readBytes())
            fail = false
        }
        val existing = final(directory).readBytes()
        adapter(directory).use { resumed ->
            resumed.finish(end())
            assertArrayEquals(existing, final(directory).readBytes())
            assertEquals(1, directory.listFiles()!!.count { it.extension == "rfbin" })
        }
    }

    @Test fun completedPrefixCanReopenAgainstAStableGrowingRecordAndResumeItsTail() {
        val directory = temporary.newFolder()
        adapter(directory).use { download ->
            download.append(HealthMessage.DataChunk(0, payload))
            download.finish(end())
        }
        val prefixContainer = final(directory).readBytes()
        val tail = imu(1, 1_120)
        val grown = record.copy(bytes = payload.size + tail.size.toLong(), records = 3)

        adapter(directory, grown).use { resumed ->
            assertEquals(payload.size.toLong(), resumed.nextOffset)
            assertFalse(final(directory).exists())
            assertTrue(directory.listFiles()!!.any { it.name.endsWith(".prefix-${payload.size}.rfbin") })
            resumed.append(HealthMessage.DataChunk(payload.size.toLong(), tail))
            val result = resumed.finish(HealthMessage.ReadEnd(grown.bytes, true))
            assertEquals(3L, result.evidence.records)
            assertArrayEquals(payload + tail, part(directory).readBytes())
            resumed.releaseTemporary()
        }

        assertFalse(prefixContainer.contentEquals(final(directory).readBytes()))
        assertFalse(directory.listFiles()!!.any { it.name.contains(".prefix-") })
    }

    @Test fun completedPrefixCanExtendAfterTemporaryEmptyReadsAndIgnoreItsDelayedEnd() {
        val directory = temporary.newFolder()
        val tail = imu(1, 1_120)
        val grown = record.copy(bytes = payload.size + tail.size.toLong(), records = 3)
        adapter(directory).use { download ->
            download.beginRead()
            download.append(HealthMessage.DataChunk(0, payload))
            download.finish(end())
            download.close()
            download.extendTo(grown)
            assertTrue(download.isDelayedCompletedPrefix(end()))
            repeat(2) {
                download.beginRead()
                assertFalse(download.checkpoint(HealthMessage.ReadEnd(record.bytes, false)))
            }
            download.beginRead()
            download.append(HealthMessage.DataChunk(record.bytes, tail))
            assertEquals(3L, download.finish(HealthMessage.ReadEnd(grown.bytes, true)).evidence.records)
            assertArrayEquals(payload + tail, part(directory).readBytes())
        }
    }

    @Test fun invalidSessionOrUnassociatedFilesCannotBeOverwritten() {
        val directory = temporary.newFolder()
        part(directory).writeText("unassociated original")
        assertThrows(IllegalArgumentException::class.java) { adapter(directory) }
        assertEquals("unassociated original", part(directory).readText())
        assertThrows(IllegalArgumentException::class.java) {
            RealSessionDownload(directory, "../elsewhere", ring, record, 0, 0, {})
        }
    }

    @Test fun emptyPartLeftBeforeFirstCheckpointCanRetryAfterStorageRecovers() {
        val directory = temporary.newFolder()
        assertTrue(part(directory).createNewFile())
        adapter(directory).use { download ->
            assertEquals(0L, download.nextOffset)
            download.append(HealthMessage.DataChunk(0, payload))
            val result = download.finish(end())
            assertEquals(2L, result.evidence.records)
            assertArrayEquals(payload, part(directory).readBytes())
            assertTrue(final(directory).isFile)
        }
    }

    private fun imu(count: Int, uptime: Long): ByteArray = ByteArrayOutputStream().apply {
        write(0x32); write(0x12); write(count); u32(uptime)
        repeat(count * 6) { write(it + 1) }
    }.toByteArray()

    private fun ppg(count: Int, mask: Int, uptime: Long): ByteArray = ByteArrayOutputStream().apply {
        write(0x32); write(0x11); write(0); write(0); write(1); write(0)
        write(count); write(mask); u32(uptime)
        repeat(count * Integer.bitCount(mask) * 4) { write(it) }
    }.toByteArray()

    private fun ByteArrayOutputStream.u32(value: Long) = repeat(4) { write((value ushr (it * 8)).toInt()) }
}
