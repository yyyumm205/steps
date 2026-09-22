package com.nexthci.ringfitness

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Real owner and durable files, with only the BLE port, scheduler and directory fsync replaced. */
class RealCollectionControllerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val epoch = 1_789_804_800_000L
    private val ring = PreparedRing("AA:BB:CC:DD:EE:07", "Ringo owner fixture")
    private val firstPacket = imu(2, 1_000)
    private val payload = firstPacket + imu(3, 1_060)
    private val initialRecord get() = HealthMessage.ListItem(7, firstPacket.size.toLong(), 1, 900, epoch)
    private val finalRecord get() = initialRecord.copy(bytes = payload.size.toLong(), records = 2)
    private fun idle() = HealthMessage.Status(false, 0, 0, 0, 6)
    private fun collecting() = HealthMessage.Status(true, initialRecord.bytes, initialRecord.records, 0, 7)
    private fun stopped() = HealthMessage.Status(false, finalRecord.bytes, finalRecord.records, 0, 7)

    @Test fun unknownIdleRecordIsBackedUpAutomaticallyWithoutCreatingResearchData() {
        val uploads = RecordingUploads()
        Fixture(uploads).use { f ->
            f.owner.initialize(); f.owner.onConnected(f.port.generation)
            f.observe(stopped(), listOf(finalRecord))
            assertTrue(f.owner.state.preservingExisting)
            assertTrue(f.owner.state.busy)
            assertFalse(f.owner.state.canStart)
            assertFalse(f.owner.canReleaseIfIdle())
            assertEquals(listOf(Read(7, 0, 8192)), f.port.reads)
            f.startSelected()
            assertEquals(0, f.port.count("start"))
            f.finishDownload()
            f.owner.onConnected(f.port.generation)
            f.observe(stopped(), listOf(finalRecord))
            f.observe(stopped(), listOf(finalRecord))
            assertTrue(f.owner.state.canStart)
            assertFalse(f.owner.state.preservingExisting)
            assertNull(f.store.read())
            assertTrue(f.owner.state.records.isEmpty())
            assertTrue(uploads.requests.isEmpty())
            assertTrue(f.store.hasPreservedDeviceRecords(ring.address, listOf(finalRecord)))
            f.reopen(); f.owner.onConnected(f.port.generation)
            f.observe(stopped(), listOf(finalRecord))
            assertTrue(f.owner.state.canStart)
            assertEquals(1, f.port.reads.size)
            f.startSelected(); f.observe(stopped(), listOf(finalRecord))
            val newRecord = initialRecord.copy(uptimeMs = 2000, unixMs = epoch + 60000)
            f.observe(collecting(), listOf(newRecord))
            assertEquals(CollectionPage.COLLECTING, f.owner.state.page)
            assertEquals(1, f.port.count("start"))
        }
    }

    @Test fun unassignedIdleRecordIsIgnoredForFreshParticipantFlow() {
        Fixture(preserveUnassignedExisting = false).use { f ->
            f.owner.initialize(); f.owner.onConnected(f.port.generation)
            f.observe(stopped(), listOf(finalRecord))
            assertTrue("A fresh participant must reach the activity chooser", f.owner.state.canStart)
            assertFalse(f.owner.state.preservingExisting)
            assertTrue(f.port.reads.isEmpty())
            f.startSelected()
            f.observe(stopped(), listOf(finalRecord))
            val newRecord = initialRecord.copy(sessionId = 8, uptimeMs = 2_000, unixMs = epoch + 60_000)
            f.observe(collecting().copy(sessionId = 8), listOf(finalRecord, newRecord))
            assertEquals(CollectionPage.COLLECTING, f.owner.state.page)
            assertEquals(1, f.port.count("start"))
        }
    }

    @Test fun zeroClockOriginalIsFullyRereadOnEachConnectionAndCanStartAfterClockSync() = Fixture(syncClock = true).use { f ->
        val old = finalRecord.copy(unixMs = 0)
        f.owner.initialize(); f.owner.onConnected(f.port.generation)
        f.observe(stopped(), listOf(old))
        val originalGeneration = f.port.generation
        assertTrue(f.owner.state.preservingExisting)
        f.finishDownload()
        assertEquals(originalGeneration, f.port.generation)
        f.observe(stopped(), listOf(old))
        assertTrue(f.owner.state.canStart)
        assertFalse(f.store.hasPreservedDeviceRecords(ring.address, listOf(old)))
        f.reopen(); f.owner.onConnected(f.port.generation)
        f.observe(stopped(), listOf(old))
        assertFalse(f.owner.state.canStart)
        assertEquals(0L, f.port.reads.last().offset)
        f.finishDownload(); f.observe(stopped(), listOf(old))
        assertTrue(f.owner.state.canStart)
        assertEquals(2, File(f.directory, "device-backups").listFiles()!!.size)
        f.startSelected(); f.observe(stopped(), listOf(old)); f.timeReply()
        f.observe(stopped(), listOf(old)); f.observe(stopped(), listOf(old))
        assertEquals(1, f.port.count("start"))
        val sync = f.clockEvidence.last().first
        val next = initialRecord.copy(uptimeMs = 1_000, unixMs = sync.deviceUnixMs + 500)
        f.observe(collecting(), listOf(next))
        assertEquals("${f.errors}", CollectionPage.COLLECTING, f.owner.state.page)
        assertNotNull(f.store.readPending()!!.startBaseline!!.unknownTimeStartEvidence)
    }

    @Test fun zeroClockInterruptedBackupKeepsPartialAndRestartsFromZeroWithoutAnUpload() = Fixture().use { f ->
        val old = finalRecord.copy(unixMs = 0)
        f.owner.initialize(); f.owner.onConnected(f.port.generation); f.observe(stopped(), listOf(old))
        f.health(HealthMessage.DataChunk(0, firstPacket)); f.health(HealthMessage.ReadEnd(firstPacket.size.toLong(), false))
        val oldConnection = f.port.generation
        val oldOwner = f.owner
        f.reopen(); f.owner.onConnected(f.port.generation); f.observe(stopped(), listOf(old))
        assertEquals(0L, f.port.reads.last().offset)
        oldOwner.onHealth(oldConnection, SensorPacket.Health(HealthMessage.ReadEnd(payload.size.toLong(), true), ++f.clock.now))
        assertFalse(f.owner.state.canStart)
        f.finishDownload(); f.observe(stopped(), listOf(old))
        assertTrue(f.owner.state.canStart)
        assertNull(f.store.read())
        assertTrue(File(f.directory, "device-backups").walkTopDown().any { it.extension == "part" && it.length() > 0 })
    }

    @Test fun aFailedZeroClockStartCanBeArchivedAfterAFreshFullBackup() = Fixture(syncClock = true).use { f ->
        val old = finalRecord.copy(unixMs = 0)
        f.owner.initialize(); f.owner.onConnected(f.port.generation); f.observe(stopped(), listOf(old))
        f.finishDownload(); f.observe(stopped(), listOf(old))
        f.startSelected(); f.observe(stopped(), listOf(old)); f.timeReply()
        f.observe(stopped(), listOf(old)); f.observe(stopped(), listOf(old))
        f.observeUnconfirmedStart(stopped(), listOf(old))
        assertTrue(f.owner.state.canEndStartAttempt)
        f.owner.endStartAttempt("设备未开始")
        f.owner.onConnected(f.port.generation); f.observe(stopped(), listOf(old))
        assertTrue(f.owner.state.preservingExisting)
        f.finishDownload(); f.observe(stopped(), listOf(old)); f.observe(stopped(), listOf(old))
        assertNull(f.store.readPending())
        assertNotNull(f.store.read()!!.startAttemptArchive)
        assertTrue("${f.errors}", f.owner.state.canStart)
        assertEquals(1, f.port.count("start"))
        assertEquals(0, f.port.count("stop"))
    }

    @Test fun aNewIdAfterUnknownClockPreservationStillNeedsClockProofAndDownloadsOnAFreshConnection() {
        for (knownClock in listOf(false, true)) Fixture(syncClock = true).use { f ->
            val old = finalRecord.copy(unixMs = 0)
            f.owner.initialize(); f.owner.onConnected(f.port.generation); f.observe(stopped(), listOf(old))
            f.finishDownload(); f.observe(stopped(), listOf(old))
            f.startSelected(); f.observe(stopped(), listOf(old)); f.timeReply()
            f.observe(stopped(), listOf(old)); f.observe(stopped(), listOf(old))
            val sync = f.clockEvidence.last().first
            val first = initialRecord.copy(sessionId = 8, uptimeMs = 1_000,
                unixMs = if (knownClock) sync.deviceUnixMs + 500 else 0)
            f.observe(collecting().copy(sessionId = 8), listOf(first))
            if (!knownClock) {
                assertNull(f.store.readPending()!!.startConfirmedAtMs)
                assertEquals(CollectionPage.RECOVERY, f.owner.state.page)
                assertTrue(f.owner.state.canStopUnconfirmedStart)
            } else {
                assertEquals(CollectionPage.COLLECTING, f.owner.state.page)
                val last = first.copy(bytes = finalRecord.bytes, records = finalRecord.records)
                f.owner.stop(); f.observe(stopped().copy(sessionId = 8), listOf(last))
                assertEquals(CollectionPage.FINISH, f.owner.state.page)
                f.owner.chooseFinish(true); f.owner.saveReference("0", "valid", "")
                val oldConnection = f.port.generation
                f.observe(stopped().copy(sessionId = 8), listOf(last))
                assertTrue(f.port.generation > oldConnection)
                assertEquals(1, f.port.reads.size) // Only the old record's backup before reconnection.
                f.owner.onConnected(f.port.generation); f.observe(stopped().copy(sessionId = 8), listOf(last))
                f.owner.onHealth(oldConnection, SensorPacket.Health(HealthMessage.DataChunk(0, payload), ++f.clock.now))
                assertNull(f.store.readPending()!!.localData)
                f.finishDownload()
                assertEquals(0L, f.store.read()!!.reference!!.steps)
                assertNotNull(f.store.read()!!.localData)
                assertEquals(7, f.store.manifestSnapshot(f.store.read()!!.sessionId)["version"].asInt)
                assertEquals(2, f.port.reads.size)
            }
        }
    }

    @Test fun unconfirmedZeroTimeStartStopsOnceAndPreservesOnlyItsTargetOnAFreshConnection() {
        for (keepOld in listOf(false, true)) {
            val uploads = RecordingUploads()
            Fixture(uploads, syncClock = true).use { f ->
                val initial = f.beginUnconfirmedZeroTimeStart(keepOld = keepOld)
                val old = finalRecord.copy(unixMs = 0)
                val final = initial.copy(bytes = payload.size.toLong(), records = 2)
                val initialList = if (keepOld) listOf(old, initial) else listOf(initial)
                val finalList = if (keepOld) listOf(old, final) else listOf(final)
                val collecting = collecting().copy(sessionId = initial.sessionId)
                val stopped = stopped().copy(sessionId = initial.sessionId)
                val id = f.store.readPending()!!.sessionId
                f.port.accept = { command ->
                    if (command == "stop") assertNotNull(f.store.read(id)!!.startAbort)
                    true
                }
                f.owner.stop(); f.owner.stop()
                assertEquals(0, f.port.count("stop")) // A fresh full snapshot precedes the write.
                f.observe(collecting, initialList)
                f.owner.stop()
                assertEquals(1, f.port.count("stop"))
                val issuingConnection = f.port.generation
                f.observe(stopped, finalList)
                assertTrue(f.port.generation > issuingConnection)
                assertNotNull(f.store.read(id)!!.startAbort!!.stoppedObservation)
                f.owner.onConnected(f.port.generation)
                f.observe(stopped, finalList)
                assertEquals(listOf(7, initial.sessionId), f.port.reads.map { it.sessionId })
                assertEquals(0L, f.port.reads.last().offset)
                f.owner.onHealth(issuingConnection, SensorPacket.Health(HealthMessage.DataChunk(0, byteArrayOf(9)), ++f.clock.now))
                f.finishDownload(); f.observe(stopped, finalList)
                assertNull(f.store.readPending())
                val audit = f.store.read(id)!!
                assertNotNull(audit.startAbort!!.completedAtMs)
                assertNotNull(audit.startAbort!!.preservation)
                assertNull(audit.startConfirmedAtMs); assertNull(audit.stopConfirmedAtMs)
                assertNull(audit.reference); assertNull(audit.localData)
                assertFalse(audit.uploadAllowed)
                assertTrue(f.owner.state.records.isEmpty())
                assertNull(f.owner.state.session)
                assertTrue(uploads.requests.isEmpty())
                assertEquals(2, File(f.directory, "device-backups").listFiles()!!.size)
                f.observe(stopped, finalList) // Reuse the regular readiness guard after closing the audit.
                assertTrue(f.owner.state.canStart)
                assertEquals(CollectionPage.HOME, f.owner.state.page)
                assertEquals(2, f.port.reads.size) // The old unknown record is never downloaded again here.
            }
        }
    }

    @Test fun emptyUnconfirmedStartIsStoppedAndAuditedWithoutInventingARawFile() = Fixture(syncClock = true).use { f ->
        val initial = f.beginUnconfirmedZeroTimeStart(empty = true)
        val collecting = HealthMessage.Status(true, 0, 0, 0, initial.sessionId)
        val stopped = collecting.copy(collecting = false)
        val id = f.store.readPending()!!.sessionId
        f.owner.stop(); f.observe(collecting, listOf(initial)); f.observe(stopped, listOf(initial))
        f.reopen(); f.owner.onConnected(f.port.generation); f.observe(stopped, listOf(initial))
        val audit = f.store.read(id)!!.startAbort!!
        assertNotNull(audit.completedAtMs)
        assertNull(audit.preservation)
        assertNull(f.store.readPending())
        assertEquals(1, f.port.reads.size) // Only the pre-START original existed.
        assertEquals(1, f.port.count("stop"))
        f.observe(stopped, listOf(initial))
        assertEquals(CollectionPage.HOME, f.owner.state.page)
        assertNull(f.owner.state.error)
        assertTrue(f.owner.state.canStart)
        f.owner.home()
        assertEquals(CollectionPage.HOME, f.owner.state.page)
        assertEquals(1, f.port.reads.size)
    }

    @Test fun changedOrRegressedAbortPrecheckNeverSendsStop() {
        for (change in listOf("identity", "stopped", "error", "counters", "foreign")) Fixture(syncClock = true).use { f ->
            val initial = f.beginUnconfirmedZeroTimeStart()
            f.owner.stop()
            val status = collecting().copy(sessionId = initial.sessionId,
                collecting = change != "stopped", errorCode = if (change == "error") -16 else 0,
                bytes = if (change == "counters") 0 else initial.bytes)
            val actual = if (change == "identity") initial.copy(uptimeMs = initial.uptimeMs + 1) else initial
            val records = if (change == "foreign") listOf(actual, finalRecord.copy(sessionId = 99)) else listOf(actual)
            f.observe(status, records)
            assertEquals(0, f.port.count("stop"))
            assertNull(f.store.readPending()!!.startAbort)
            assertNull(f.store.readPending()!!.startConfirmedAtMs)
            assertFalse(f.owner.state.canStopUnconfirmedStart)
        }
    }

    @Test fun freshStatusAfterAnomalousStartInvalidatesAStaleStopCandidate() = Fixture(syncClock = true).use { f ->
        f.beginUnconfirmedZeroTimeStart()
        f.health(collecting().copy(errorCode = -16))
        assertFalse(f.owner.state.canStopUnconfirmedStart)
        f.owner.stop()
        assertEquals(0, f.port.count("stop"))
        assertNotNull(f.store.readPending())
    }

    @Test fun abortIntentSaveFailureCannotSendStop() = Fixture(syncClock = true).use { f ->
        val initial = f.beginUnconfirmedZeroTimeStart()
        f.owner.stop(); f.failCommit = true
        f.observe(collecting().copy(sessionId = initial.sessionId), listOf(initial))
        assertEquals(0, f.port.count("stop"))
        assertNull(f.store.readPending()!!.startAbort)
    }

    @Test fun stopAbortWaitsForFlashAndRetriesOnlyReadChecksOnOriginalConnection() = Fixture(syncClock = true, deferCaptureWaits = true).use { f ->
        val initial = f.beginUnconfirmedZeroTimeStart()
        val collecting = collecting().copy(sessionId = initial.sessionId)
        f.owner.stop(); f.observe(collecting, listOf(initial))
        assertEquals(CollectionPage.STOPPING, f.owner.state.page)
        val queries = f.port.count("status")
        val connection = f.port.generation
        f.owner.stop(); f.owner.retry()
        assertEquals(queries, f.port.count("status"))
        assertEquals(1, f.port.count("stop"))
        f.runDelay(5_000)
        repeat(3) { attempt ->
            f.observe(collecting, listOf(initial))
            if (attempt < 2) f.runDelay(FreeLivingCaptureCoordinator.STOP_POLL_INTERVAL_MS)
        }
        assertEquals(CollectionPage.STOPPING, f.owner.state.page)
        assertNull(f.owner.state.error)
        f.runDelay(FreeLivingCaptureCoordinator.STOP_POLL_INTERVAL_MS)
        assertEquals(connection, f.port.generation)
        val final = initial.copy(bytes = payload.size.toLong(), records = 2)
        f.observe(stopped().copy(sessionId = initial.sessionId), listOf(final))
        assertNotNull(f.store.readPending()!!.startAbort!!.stoppedObservation)
        assertEquals(1, f.port.count("stop"))
    }

    @Test fun abortStopUnconfirmedAcrossDisconnectOrReopenStaysProtectedWithoutRepeatingCommand() {
        for (reopen in listOf(false, true)) Fixture(syncClock = true).use { f ->
            val initial = f.beginUnconfirmedZeroTimeStart()
            f.owner.stop(); f.observe(collecting().copy(sessionId = initial.sessionId), listOf(initial))
            if (reopen) f.reopen() else {
                f.owner.onDisconnected(f.port.generation, "测试中断")
                f.owner.retry()
            }
            f.owner.onConnected(f.port.generation)
            f.observe(stopped().copy(sessionId = initial.sessionId), listOf(initial.copy(bytes = payload.size.toLong(), records = 2)))
            assertEquals(1, f.port.count("stop"))
            assertNotNull(f.store.readPending())
            assertNull(f.store.readPending()!!.startAbort!!.stoppedObservation)
            assertFalse(f.owner.state.canStart)
        }
    }

    @Test fun abortBackupAfterConfirmedStopRestartsAtZeroAfterReopenAndPreservesPartial() = Fixture(syncClock = true).use { f ->
        val initial = f.beginUnconfirmedZeroTimeStart()
        val final = initial.copy(bytes = payload.size.toLong(), records = 2)
        val stopped = stopped().copy(sessionId = initial.sessionId)
        f.owner.stop(); f.observe(collecting().copy(sessionId = initial.sessionId), listOf(initial))
        f.observe(stopped, listOf(final)); f.owner.onConnected(f.port.generation); f.observe(stopped, listOf(final))
        f.health(HealthMessage.DataChunk(0, firstPacket)); f.health(HealthMessage.ReadEnd(firstPacket.size.toLong(), false))
        f.reopen(); f.owner.onConnected(f.port.generation); f.observe(stopped, listOf(final))
        assertEquals(0L, f.port.reads.last().offset)
        f.finishDownload(); f.observe(stopped, listOf(final))
        assertNull(f.store.readPending())
        assertNotNull(f.store.read()!!.startAbort!!.completedAtMs)
        assertTrue(File(f.directory, "device-backups").walkTopDown().any { it.extension == "part" && it.length() > 0 })
        assertEquals(1, f.port.count("stop"))
    }

    @Test fun changedAbortRecordAfterStopKeepsAuditPendingAndNeverReads() = Fixture(syncClock = true).use { f ->
        val initial = f.beginUnconfirmedZeroTimeStart()
        val final = initial.copy(bytes = payload.size.toLong(), records = 2)
        val stopped = stopped().copy(sessionId = initial.sessionId)
        f.owner.stop(); f.observe(collecting().copy(sessionId = initial.sessionId), listOf(initial))
        f.observe(stopped, listOf(final)); f.owner.onConnected(f.port.generation)
        f.observe(stopped, listOf(final.copy(uptimeMs = 2000)))
        assertNotNull(f.store.readPending())
        assertNull(f.store.readPending()!!.startAbort!!.completedAtMs)
        assertEquals(1, f.port.reads.size)
    }

    @Test fun failedAbortQueryRetainsEveryObservedCounterBeforeReadOnlyRetry() = Fixture(syncClock = true).use { f ->
        val initial = f.beginUnconfirmedZeroTimeStart()
        f.owner.stop(); f.observe(collecting().copy(sessionId = initial.sessionId), listOf(initial))
        val final = initial.copy(bytes = payload.size.toLong(), records = 2)
        f.observe(stopped().copy(sessionId = initial.sessionId, bytes = final.bytes + 100), listOf(final))
        assertNull(f.store.readPending()!!.startAbort!!.stoppedObservation)
        val generation = f.port.generation
        f.owner.retry()
        assertEquals(generation, f.port.generation)
        f.observe(stopped().copy(sessionId = initial.sessionId), listOf(final))
        assertNull(f.store.readPending()!!.startAbort!!.stoppedObservation)
        assertNull(f.store.readPending()!!.startAbort!!.completedAtMs)
        assertEquals(1, f.port.count("stop"))
        assertEquals(1, f.port.reads.size)
    }

    @Test fun severalZeroClockRecordsRemainIntactAndDoNotBlockANewDistinctSession() = Fixture(syncClock = true).use { f ->
        val old = listOf(finalRecord.copy(unixMs = 0), finalRecord.copy(sessionId = 8, unixMs = 0))
        f.owner.initialize(); f.owner.onConnected(f.port.generation)
        f.observe(stopped(), old)
        assertTrue(f.owner.state.canStart)
        assertNull(f.owner.state.error)
        assertEquals(CollectionPage.HOME, f.owner.state.page)
        f.startSelected(); f.observe(stopped(), old); f.timeReply()
        f.observe(stopped(), old); f.observe(stopped(), old)
        val fresh = initialRecord.copy(sessionId = 9, uptimeMs = 2_000, unixMs = epoch + 60_000)
        f.observe(collecting().copy(sessionId = 9), old + fresh)
        assertEquals(CollectionPage.COLLECTING, f.owner.state.page)
        assertEquals(old, f.store.readPending()!!.startBaseline!!.records)
        assertTrue(f.port.reads.isEmpty())
        assertEquals(1, f.port.count("start"))
    }

    @Test fun inconsistentIdleStatusAndListNeverExposeStart() {
        val cases = listOf(
            stopped() to emptyList(),
            stopped() to listOf(finalRecord.copy(sessionId = 8)),
        )
        cases.forEach { (status, records) -> Fixture().use { f ->
            f.owner.initialize(); f.owner.onConnected(f.port.generation)
            f.observe(status, records)
            assertEquals(CollectionPage.HOME, f.owner.state.page)
            assertEquals(CollectionPage.RECOVERY, f.owner.state.taskPage)
            assertFalse(f.owner.state.canStart)
            assertTrue(f.owner.state.canRetry)
            assertTrue(f.owner.state.error!!.contains("重新检查"))
            f.startSelected()
            assertEquals(0, f.port.count("start"))
            assertEquals(0, f.port.count("time"))
            assertNull(f.store.readPending())
        } }
    }

    @Test fun deferKeepsRawOnRingUntilExplicitResumeThenDownloadsAndUploadsTheSameSession() {
        val uploads = RecordingUploads()
        Fixture(uploads).use { f ->
            f.beginCollecting(); f.owner.stop(); f.observe(stopped(), listOf(finalRecord))
            assertEquals(CollectionPage.FINISH, f.owner.state.page)
            assertTrue(f.port.reads.isEmpty())
            f.owner.finalizeSession(false, "0", "valid", "")
            val deferred = requireNotNull(f.store.readPending())
            assertEquals(CompletionPolicy.DEFER_ON_RING, deferred.completionPolicy)
            assertEquals(0L, deferred.reference!!.steps)
            assertEquals(SessionActivity.WALKING, deferred.activity)
            assertNull(deferred.localData)
            assertEquals(CollectionPage.RING_PENDING, f.owner.state.page)
            assertTrue(uploads.requests.isEmpty())
            assertTrue(f.owner.state.records.single().ringDeferred)
            assertFalse(f.owner.state.records.single().uploadDeferred)
            assertTrue(f.owner.canReleaseIfIdle())

            val startCount = f.port.count("start")
            val stopCount = f.port.count("stop")
            f.owner.retry(); f.owner.reconnect()
            f.health(stopped()); f.health(finalRecord); f.health(HealthMessage.ListEnd(1))
            f.owner.onTime(f.port.generation, SensorPacket.TimeStatus(true, f.clock.now, 1_000, ++f.clock.now))
            assertEquals(CompletionPolicy.DEFER_ON_RING, f.store.readPending()!!.completionPolicy)
            assertTrue(f.port.reads.isEmpty())
            assertTrue(uploads.requests.isEmpty())
            f.owner.onDisconnected(f.port.generation, "测试断连")
            assertEquals(0, f.waitCount(3_000))

            f.reopen()
            assertEquals(CollectionPage.RING_PENDING, f.owner.state.page)
            assertEquals(deferred, f.store.readPending())
            assertTrue(f.port.reads.isEmpty())
            assertTrue(f.owner.canReleaseIfIdle())
            f.owner.retry(); f.owner.reconnect(); f.owner.onConnected(f.port.generation)
            assertEquals(CompletionPolicy.DEFER_ON_RING, f.store.readPending()!!.completionPolicy)
            assertTrue(f.port.reads.isEmpty())
            assertTrue(uploads.requests.isEmpty())

            f.owner.resumeRingTransfer()
            assertEquals(CompletionPolicy.SAVE_UPLOAD, f.store.readPending()!!.completionPolicy)
            f.owner.onConnected(f.port.generation)
            f.observe(stopped(), listOf(finalRecord))
            assertEquals(1, f.port.reads.size)
            f.finishDownload()
            val saved = requireNotNull(f.store.read(deferred.sessionId))
            assertEquals(deferred.sessionId, saved.sessionId)
            assertEquals(deferred.reference, saved.reference)
            assertNotNull(saved.localData)
            assertEquals(listOf(saved.sessionId to false), uploads.requests)
            assertEquals(startCount, f.port.count("start"))
            assertEquals(stopCount, f.port.count("stop"))
        }
    }

    @Test fun deferredSessionCanBeDiscardedWithoutChangingAnEarlierSavedRecord() = Fixture().use { f ->
        val previous = f.seedLocal()
        val previousRaw = File(f.directory, previous.localData!!.files.single().fileName)
        val previousBytes = previousRaw.readBytes()
        f.beginCollecting(); f.owner.stop(); f.observe(stopped(), listOf(finalRecord))
        f.owner.finalizeSession(false, "4", "valid", "")
        val deferred = requireNotNull(f.store.readPending())
        assertTrue(deferred.isRingDeferred)

        f.owner.discardSession()

        assertNull(f.store.readPending())
        assertTrue(f.store.read(deferred.sessionId)!!.isDiscarded)
        assertEquals(previous, f.store.read(previous.sessionId))
        assertArrayEquals(previousBytes, previousRaw.readBytes())
        assertTrue(f.port.reads.isEmpty())
    }

    @Test fun unavailableUploadConfigurationFallsBackToEditableLocalSaveWithoutQueuing() {
        val uploads = RecordingUploads().apply { configured = false }
        Fixture(uploads).use { f ->
            assertFalse(f.owner.state.uploadAvailable)
            f.beginCollecting(); f.owner.stop(); f.observe(stopped(), listOf(finalRecord))
            assertFalse(f.owner.state.uploadAvailable)

            f.owner.finalizeSession(true, "9", "valid", "")
            assertEquals(CompletionPolicy.SAVE_LATER, f.store.readPending()!!.completionPolicy)
            f.observe(stopped(), listOf(finalRecord)); f.finishDownload()
            val saved = f.store.read()!!
            assertTrue(uploads.requests.isEmpty())
            assertTrue(f.owner.state.records.single().referenceEditable)

            f.owner.reviseReference(saved.sessionId, "11", "unreliable", "计步器短暂松动")
            val revised = f.store.read(saved.sessionId)!!
            assertEquals(11L, revised.reference!!.steps)
            assertEquals(ReferenceStatus.UNRELIABLE, revised.reference!!.status)
            assertEquals(9L, revised.referenceRevisions.single().previous.steps)
            assertEquals(revised.reference!!.recordedAtMs, revised.referenceRevisions.single().replacedAtMs)
            assertTrue(f.owner.state.records.single().referenceEditable)

            f.owner.retryUpload(saved.sessionId)
            assertTrue(uploads.requests.isEmpty())
            assertEquals(CompletionPolicy.SAVE_LATER, f.store.read(saved.sessionId)!!.completionPolicy)
            f.reopen()
            assertEquals(revised.reference, f.store.read(saved.sessionId)!!.reference)
            assertEquals(revised.referenceRevisions, f.store.read(saved.sessionId)!!.referenceRevisions)
        }
    }

    @Test fun discardedStoppedRecordDoesNotRedownloadAndKeepsOtherRecordsAndFiles() = Fixture().use { f ->
        val previous = f.seedLocal()
        val previousRaw = File(f.directory, previous.localData!!.files.single().fileName)
        val previousBytes = previousRaw.readBytes()
        f.beginCollecting(); f.owner.stop(); f.observe(stopped(), listOf(finalRecord))
        val current = f.store.readPending()!!
        assertEquals(CollectionPage.FINISH, f.owner.state.page)
        f.owner.discardSession()
        assertNull(f.store.readPending())
        assertTrue(f.store.read(current.sessionId)!!.isDiscarded)
        assertArrayEquals(previousBytes, previousRaw.readBytes())
        assertEquals(previous, f.store.read(previous.sessionId))
        f.owner.onConnected(f.port.generation); f.observe(stopped(), listOf(finalRecord))
        assertTrue("${f.errors}", f.owner.state.canStart)
        assertTrue(f.port.reads.isEmpty())
        assertFalse(f.owner.state.records.any { it.sessionId == current.sessionId })
        f.reopen(); f.owner.onConnected(f.port.generation); f.observe(stopped(), listOf(finalRecord))
        assertTrue(f.owner.state.canStart)
        assertTrue(f.port.reads.isEmpty())
    }

    @Test fun discardedZeroTimeRecordStaysExcludedWithoutBlockingTheRing() = Fixture().use { f ->
        f.beginCollecting(initialRecord.copy(unixMs = 0))
        f.owner.stop(); f.observe(stopped(), listOf(finalRecord.copy(unixMs = 0)))
        f.owner.discardSession()
        f.owner.onConnected(f.port.generation); f.observe(stopped(), listOf(finalRecord.copy(unixMs = 0)))
        assertEquals(CollectionPage.HOME, f.owner.state.page)
        assertNull(f.owner.state.error)
        assertTrue(f.owner.state.canStart)
        assertTrue(f.port.reads.isEmpty())
        f.owner.home()
        assertEquals(CollectionPage.HOME, f.owner.state.page)
    }

    @Test fun anUnreliableReadingIsSavedAfterStopConfirmationAndFinishChoice() = Fixture().use { f ->
        f.beginCollecting()
        f.port.accept = { it != "stop" }
        f.owner.stop()
        assertEquals(FreeLivingSessionPhase.COLLECTING, f.store.readPending()!!.phase)
        assertNull(f.store.readPending()!!.reference)
        assertNull(f.store.readPending()!!.stopConfirmedAtMs)
        assertTrue(f.port.reads.isEmpty())
        f.port.accept = { true }
        f.owner.stop(); f.observe(stopped(), listOf(finalRecord))
        assertEquals(CollectionPage.FINISH, f.owner.state.page)
        assertTrue(f.port.reads.isEmpty())
        f.owner.chooseFinish(true)
        f.owner.saveReference("73", "unreliable", "计步器读数可能偏低")
        val savedReference = f.store.readPending()!!.reference
        f.observe(stopped(), listOf(finalRecord)); f.finishDownload()
        assertEquals(savedReference, f.store.read()!!.reference)
        assertEquals(CompletionPolicy.SAVE_LATER, f.store.read()!!.completionPolicy)
        assertEquals(2, f.port.count("stop"))
    }

    @Test fun interruptedUnknownRecordBackupResumesOnlyItsVerifiedPrefixAfterReopen() = Fixture().use { f ->
        f.owner.initialize(); f.owner.onConnected(f.port.generation)
        f.observe(stopped(), listOf(finalRecord))
        f.health(HealthMessage.DataChunk(0, firstPacket))
        f.health(HealthMessage.ReadEnd(firstPacket.size.toLong(), false))
        f.owner.onDisconnected(f.port.generation, "连接中断")
        assertFalse(f.owner.state.preservingExisting)
        assertNull(f.store.read())
        f.reopen(); f.owner.onConnected(f.port.generation)
        f.observe(stopped(), listOf(finalRecord))
        assertEquals(firstPacket.size.toLong(), f.port.reads.last().offset)
        f.health(HealthMessage.DataChunk(firstPacket.size.toLong(), payload.copyOfRange(firstPacket.size, payload.size)))
        f.health(HealthMessage.ReadEnd(payload.size.toLong(), true))
        f.owner.onConnected(f.port.generation)
        f.observe(stopped(), listOf(finalRecord)); f.observe(stopped(), listOf(finalRecord))
        assertTrue(f.owner.state.canStart)
        assertTrue(f.store.hasPreservedDeviceRecords(ring.address, listOf(finalRecord)))
        assertEquals(0, f.port.count("start"))
    }

    @Test fun existingRecordWindowsKeepOneStableSavingState() = Fixture().use { f ->
        val bytes = ByteArrayOutputStream().apply {
            repeat(1_400) { write(imu(3, 1_000L + it * 60)) }
        }.toByteArray()
        val record = finalRecord.copy(bytes = bytes.size.toLong(), records = 1_400)
        val status = stopped().copy(bytes = record.bytes, records = record.records)
        f.owner.initialize(); f.owner.onConnected(f.port.generation)
        f.observe(status, listOf(record))
        val savingState = f.owner.state
        assertTrue(savingState.preservingExisting)
        assertFalse(savingState.checkingDevice)
        assertTrue(savingState.busy)
        val observedStates = mutableListOf<CollectionFlowState>()
        f.owner.observe { observedStates += it }.use {
            repeat(2) { window ->
                val start = window * 8192
                val end = start + 8192
                f.health(HealthMessage.DataChunk(start.toLong(), bytes.copyOfRange(start, end)))
                f.health(HealthMessage.ReadEnd(end.toLong(), false))
                assertEquals(savingState, f.owner.state)
                f.observe(status, listOf(record))
                assertEquals(savingState, f.owner.state)
                assertEquals(end.toLong(), f.port.reads.last().offset)
            }
        }
        assertTrue(observedStates.isNotEmpty())
        assertTrue(observedStates.all { it == savingState })
        assertNull(f.store.read())
    }

    @Test fun stalledBackupStopsAfterThreeEmptyWindowsAndRetriesFromItsSavedPrefix() = Fixture().use { f ->
        f.owner.initialize(); f.owner.onConnected(f.port.generation)
        f.observe(stopped(), listOf(finalRecord))
        f.health(HealthMessage.DataChunk(0, firstPacket))
        f.owner.reconnect(); f.owner.onConnected(f.port.generation)
        f.observe(stopped(), listOf(finalRecord))
        val retired = f.port.generation
        repeat(3) { attempt ->
            f.health(HealthMessage.ReadEnd(firstPacket.size.toLong(), false))
            if (attempt < 2) f.observe(stopped(), listOf(finalRecord))
        }
        assertEquals(4, f.port.reads.size)
        assertFalse(f.owner.state.busy)
        assertTrue(f.owner.state.canRetry)
        assertFalse(f.owner.state.preservingExisting)
        assertFalse(f.owner.state.connected)
        val part = File(f.directory, "device-backups").walkTopDown().single { it.extension == "part" }
        assertArrayEquals(firstPacket, part.readBytes())
        assertNull(f.store.read())
        f.owner.onDisconnected(retired, "迟到的断开通知")
        repeat(6) { f.owner.onHealth(retired, SensorPacket.Health(HealthMessage.ReadEnd(firstPacket.size.toLong(), false), ++f.clock.now)) }
        f.runAllDelays(30_000)
        assertEquals(4, f.port.reads.size)
        assertEquals(0, f.waitCount(3_000))
        f.owner.retry(); f.owner.onConnected(f.port.generation)
        f.observe(stopped(), listOf(finalRecord))
        assertEquals(firstPacket.size.toLong(), f.port.reads.last().offset)
        f.health(HealthMessage.DataChunk(firstPacket.size.toLong(), payload.copyOfRange(firstPacket.size, payload.size)))
        f.health(HealthMessage.ReadEnd(payload.size.toLong(), true))
        assertTrue(f.store.hasPreservedDeviceRecords(ring.address, listOf(finalRecord)))
        assertEquals(0, f.port.count("start"))
    }

    @Test fun stalledSessionDownloadKeepsReferenceAndPausesUntilManualRetry() = Fixture().use { f ->
        f.reachReference(); f.saveReference("0", "valid", "")
        f.observe(stopped(), listOf(finalRecord))
        val saved = f.store.readPending()!!
        f.health(HealthMessage.DataChunk(0, firstPacket))
        f.owner.reconnect(); f.owner.onConnected(f.port.generation)
        f.observe(stopped(), listOf(finalRecord))
        repeat(3) { f.health(HealthMessage.ReadEnd(firstPacket.size.toLong(), false)) }
        assertEquals(CollectionPage.RECOVERY, f.owner.state.page)
        assertTrue(f.owner.state.canRetry)
        assertFalse(f.owner.state.busy)
        assertEquals(saved.sessionId, f.store.readPending()!!.sessionId)
        assertEquals(saved.reference, f.store.readPending()!!.reference)
        assertNull(f.store.readPending()!!.localData)
        assertArrayEquals(firstPacket, f.directory.listFiles()!!.single { it.extension == "part" }.readBytes())
        f.owner.retry(); f.owner.onConnected(f.port.generation)
        f.observe(stopped(), listOf(finalRecord))
        assertEquals(firstPacket.size.toLong(), f.port.reads.last().offset)
        f.health(HealthMessage.DataChunk(firstPacket.size.toLong(), payload.copyOfRange(firstPacket.size, payload.size)))
        f.health(HealthMessage.ReadEnd(payload.size.toLong(), true))
        f.observe(stopped(), listOf(finalRecord))
        assertNotNull(f.store.read()!!.localData)
        assertEquals(0L, f.store.read()!!.reference!!.steps)
        assertEquals(1, f.port.count("start"))
    }

    @Test fun duplicateBackupChunksDoNotPostponeTheNoDataTimeout() = Fixture().use { f ->
        f.owner.initialize(); f.owner.onConnected(f.port.generation)
        f.observe(stopped(), listOf(finalRecord))
        f.health(HealthMessage.DataChunk(0, firstPacket))
        val timers = f.waitCount(30_000)
        repeat(20) { f.health(HealthMessage.DataChunk(0, firstPacket)) }
        assertEquals(timers, f.waitCount(30_000))
        f.runAllDelays(30_000)
        assertTrue(f.owner.state.canRetry)
        assertFalse(f.owner.state.preservingExisting)
        assertArrayEquals(firstPacket, File(f.directory, "device-backups").walkTopDown().single { it.extension == "part" }.readBytes())
    }

    @Test fun duplicateCompletedBackupEndsDoNotStartAnotherReadOrInterruptTheNextWindow() = Fixture().use { f ->
        f.owner.initialize(); f.owner.onConnected(f.port.generation)
        f.observe(stopped(), listOf(finalRecord))
        f.health(HealthMessage.DataChunk(0, firstPacket))
        val previousEnd = HealthMessage.ReadEnd(firstPacket.size.toLong(), false)
        f.health(previousEnd)
        f.observe(stopped(), listOf(finalRecord))
        val reads = f.port.reads.toList()
        repeat(4) { f.health(previousEnd) }
        assertEquals(reads, f.port.reads)
        f.health(HealthMessage.DataChunk(firstPacket.size.toLong(), payload.copyOfRange(firstPacket.size, payload.size)))
        f.health(previousEnd)
        assertEquals(reads, f.port.reads)
        f.health(HealthMessage.ReadEnd(payload.size.toLong(), true))
        assertTrue(f.store.hasPreservedDeviceRecords(ring.address, listOf(finalRecord)))
        assertTrue(f.errors.isEmpty())
        assertNull(f.store.read())
    }

    @Test fun backupGapReconnectsBeforeContinuingItsPreservedPrefix() = Fixture().use { f ->
        f.owner.initialize(); f.owner.onConnected(f.port.generation)
        f.observe(stopped(), listOf(finalRecord))
        f.health(HealthMessage.DataChunk(0, payload.copyOf(13)))
        val previous = f.port.generation
        f.health(HealthMessage.DataChunk(20, payload.copyOfRange(20, 22)))
        assertFalse(f.owner.state.connected)
        f.runAllDelays(3_000)
        f.owner.onConnected(f.port.generation)
        f.observe(stopped(), listOf(finalRecord))
        assertEquals(Read(7, 13, 8192), f.port.reads.last())
        f.owner.onHealth(previous, SensorPacket.Health(HealthMessage.DataChunk(13, byteArrayOf(100)), ++f.clock.now))
        f.health(HealthMessage.DataChunk(13, payload.copyOfRange(13, payload.size)))
        f.health(HealthMessage.ReadEnd(payload.size.toLong(), true))
        assertTrue(f.store.hasPreservedDeviceRecords(ring.address, listOf(finalRecord)))
        assertTrue(f.errors.isEmpty())
        assertNull(f.store.read())
    }

    @Test fun backupGapRecoveryRejectsAChangedSnapshotBeforeReading() = Fixture().use { f ->
        f.owner.initialize(); f.owner.onConnected(f.port.generation)
        f.observe(stopped(), listOf(finalRecord))
        f.health(HealthMessage.DataChunk(0, payload.copyOf(13)))
        f.health(HealthMessage.ReadEnd(20, false))
        f.runAllDelays(3_000); f.owner.onConnected(f.port.generation)
        f.observe(stopped(), listOf(finalRecord.copy(uptimeMs = finalRecord.uptimeMs + 1)))
        assertEquals(1, f.port.reads.size)
        assertTrue(f.owner.state.canRetry)
        assertFalse(f.owner.state.busy)
        assertArrayEquals(payload.copyOf(13), File(f.directory, "device-backups").walkTopDown().single { it.extension == "part" }.readBytes())
        assertNull(f.store.read())
    }

    @Test fun unknownClockBackupGapRetriesUseIsolatedAttemptsAndStopWithoutNewProgress() = Fixture().use { f ->
        val record = finalRecord.copy(unixMs = 0)
        val prefix = payload.copyOf(13)
        f.owner.initialize(); f.owner.onConnected(f.port.generation)
        f.observe(stopped(), listOf(record))
        repeat(RealCollectionController.DOWNLOAD_RECOVERY_LIMIT) { attempt ->
            f.health(HealthMessage.DataChunk(0, prefix))
            f.health(HealthMessage.ReadEnd(20, false))
            if (attempt + 1 < RealCollectionController.DOWNLOAD_RECOVERY_LIMIT) {
                f.runAllDelays(3_000); f.owner.onConnected(f.port.generation)
                f.observe(stopped(), listOf(record))
                assertEquals(Read(7, 0, 8192), f.port.reads.last())
            }
        }
        assertTrue(f.owner.state.canRetry)
        assertFalse(f.owner.state.busy)
        assertFalse(f.owner.state.connected)
        assertEquals(0, f.waitCount(3_000))
        val partials = File(f.directory, "device-backups").walkTopDown().filter { it.extension == "part" }.toList()
        assertEquals(RealCollectionController.DOWNLOAD_RECOVERY_LIMIT, partials.size)
        partials.forEach { assertArrayEquals(prefix, it.readBytes()) }
        assertNull(f.store.read())
        assertEquals(0, f.port.count("start"))
    }

    @Test fun changedRecordDuringBackupCannotBeMixedIntoAnotherWindow() = Fixture().use { f ->
        f.owner.initialize(); f.owner.onConnected(f.port.generation)
        f.observe(stopped(), listOf(finalRecord))
        f.health(HealthMessage.DataChunk(0, firstPacket))
        f.health(HealthMessage.ReadEnd(firstPacket.size.toLong(), false))
        f.observe(stopped(), listOf(finalRecord.copy(unixMs = epoch + 1)))
        assertFalse(f.owner.state.canStart)
        assertFalse(f.owner.state.preservingExisting)
        assertEquals(1, f.port.reads.size)
        assertFalse(f.store.hasPreservedDeviceRecords(ring.address, listOf(finalRecord)))
        assertNull(f.store.read())
        assertTrue(File(f.directory, "device-backups").walkTopDown().any { it.extension == "part" && it.length() > 0 })
    }

    @Test fun differentDeviceRecordsUseNewConnectionsAndRejectLateBytesFromThePreviousRecord() = Fixture().use { f ->
        val second = finalRecord.copy(sessionId = 8, uptimeMs = 3000, unixMs = epoch + 1000)
        val secondBytes = payload.copyOf().apply { this[lastIndex] = (this[lastIndex] + 1).toByte() }
        val records = listOf(finalRecord, second)
        f.owner.initialize(); f.owner.onConnected(f.port.generation)
        f.observe(stopped(), records)
        val firstGeneration = f.port.generation
        f.finishDownload()
        assertTrue(f.port.generation > firstGeneration)
        f.owner.onConnected(f.port.generation); f.observe(stopped(), records)
        assertEquals(8, f.port.reads.last().sessionId)
        f.owner.onHealth(firstGeneration, SensorPacket.Health(HealthMessage.DataChunk(0, payload), ++f.clock.now))
        f.owner.onHealth(firstGeneration, SensorPacket.Health(HealthMessage.ReadEnd(payload.size.toLong(), true), ++f.clock.now))
        assertFalse(f.store.hasPreservedDeviceRecords(ring.address, listOf(second)))
        f.health(HealthMessage.DataChunk(0, secondBytes))
        f.health(HealthMessage.ReadEnd(secondBytes.size.toLong(), true))
        f.owner.onConnected(f.port.generation); f.observe(stopped(), records)
        assertTrue(f.owner.state.canStart)
        assertEquals(2, f.port.reads.size)
        assertTrue(f.store.hasPreservedDeviceRecords(ring.address, records))
        val rawBytes = File(f.directory, "device-backups").walkTopDown().filter { it.extension == "rfbin" }
            .map { it.readBytes().drop(HealthRawV2.HEADER_SIZE) }.toList()
        assertTrue(rawBytes.contains(payload.toList())); assertTrue(rawBytes.contains(secondBytes.toList()))
        assertNull(f.store.read())
    }

    @Test fun chargingRecoveryReadinessIsRecheckedOnStartAndCompletesTheNormalSavePath() = Fixture().use { f ->
        f.owner.initialize()
        f.owner.onConnected(f.port.generation)
        f.observe(idle().copy(errorCode = -16), reason = 1)
        assertFalse(f.owner.state.canStart)
        assertEquals(1, f.port.count("battery"))
        f.battery()
        assertTrue(f.owner.state.canStart)
        assertNull(f.store.read())
        f.startSelected()
        f.observe(idle().copy(errorCode = -16), reason = 1)
        assertEquals(2, f.port.count("battery"))
        assertEquals(0, f.port.count("start"))
        f.battery()
        assertEquals(1, f.port.count("start"))
        assertEquals(-16, f.store.readPending()!!.startBaseline!!.status.errorCode)
        f.observe(collecting(), listOf(initialRecord))
        assertEquals(CollectionPage.COLLECTING, f.owner.state.page)
        f.owner.stop()
        f.observe(stopped(), listOf(finalRecord))
        f.saveReference("0", "valid", "")
        f.observe(stopped(), listOf(finalRecord))
        f.finishDownload()
        val saved = f.store.read()!!
        assertEquals(CollectionPage.COMPLETE, f.owner.state.page)
        assertEquals(0L, saved.reference!!.steps)
        assertEquals(7, f.store.manifestSnapshot(saved.sessionId).get("version").asInt)
        assertNotNull(f.store.manifestSnapshot(saved.sessionId).getAsJsonObject("start_baseline")["charging_recovery_evidence"])
        assertEquals(1, f.port.count("start"))
        f.reopen()
        assertEquals(saved, f.store.read())
    }

    @Test fun chargingOrOldBatteryCannotUnlockReadinessAndNoCachedBatteryAuthorizesStart() = Fixture().use { f ->
        f.owner.initialize(); f.owner.onConnected(f.port.generation)
        f.battery() // Unsolicited/cached battery before the current STATUS round.
        f.observe(idle().copy(errorCode = -16), reason = 1)
        assertFalse(f.owner.state.canStart)
        f.owner.onBattery(f.port.generation - 1, SensorPacket.Battery(4100, 100, 0, ++f.clock.now))
        assertFalse(f.owner.state.canStart)
        f.battery(1)
        assertFalse(f.owner.state.canStart)
        assertEquals("请将戒指取出充电盒后重试", f.owner.state.error)
        assertEquals(0, f.port.count("start"))
        f.owner.retry(); f.owner.onConnected(f.port.generation)
        f.observe(idle().copy(errorCode = -16), reason = 1); f.battery()
        assertTrue(f.owner.state.canStart)
        f.startSelected()
        f.observe(idle().copy(errorCode = -16), reason = 1); f.battery(1)
        assertEquals(0, f.port.count("start")); assertNull(f.store.read())
    }

    @Test fun changedStatusReasonDuringListCannotUnlockStart() = Fixture().use { f ->
        f.owner.initialize(); f.owner.onConnected(f.port.generation)
        f.owner.onHealth(f.port.generation, SensorPacket.Health(idle().copy(errorCode = -16), ++f.clock.now, 1))
        f.owner.onHealth(f.port.generation, SensorPacket.Health(idle().copy(errorCode = -16), ++f.clock.now, 2))
        f.health(HealthMessage.ListEnd(0)); f.battery()
        assertFalse(f.owner.state.canStart); assertNull(f.store.read())
        assertEquals(0, f.port.count("start"))
    }

    @Test fun batteryNotificationsDoNotReplaceAnActiveDownloadPage() = Fixture().use { f ->
        f.reachReference(); f.saveReference("17", "valid", "")
        f.observe(stopped(), listOf(finalRecord))
        assertEquals(CollectionPage.DOWNLOADING, f.owner.state.page)
        for (charge in listOf(null, 1, 2)) {
            f.battery(charge)
            assertEquals(CollectionPage.DOWNLOADING, f.owner.state.page)
        }
        f.finishDownload()
        assertEquals(CollectionPage.COMPLETE, f.owner.state.page)
    }

    @Test fun aCompleteInspectionIsRequiredBeforeStartAndStartRequiresDeviceConfirmation() = Fixture().use { f ->
        f.owner.initialize()
        assertTrue(f.owner.state.connecting)
        assertFalse(f.owner.state.canStart)
        f.startSelected()
        assertEquals(0, f.port.count("start"))
        f.owner.onConnected(f.port.generation)
        f.health(idle())
        assertFalse(f.owner.state.canStart)
        f.health(HealthMessage.ListEnd(0))
        assertTrue(f.owner.state.canStart)
        f.startSelected()
        assertNull(f.store.read())
        f.observe(idle())
        val id = requireNotNull(f.store.read()).sessionId
        assertEquals(1, f.port.count("start"))
        assertEquals(CollectionPage.STARTING, f.owner.state.page)
        assertNull(f.store.read()!!.startConfirmedAtMs)
        f.health(collecting())
        f.health(initialRecord)
        assertEquals(CollectionPage.STARTING, f.owner.state.page)
        assertFalse(f.owner.state.canStop)
        f.health(HealthMessage.ListEnd(1))
        assertEquals(CollectionPage.COLLECTING, f.owner.state.page)
        assertTrue(f.owner.state.canStop)
        assertEquals(id, f.store.read()!!.sessionId)
        assertFalse(f.owner.state.isSimulation)
        assertFalse(f.owner.state.uploadAvailable)
    }

    @Test fun validZeroIsDurableBeforeReadAndFinalFileMatchesTheSameSession() = Fixture().use { f ->
        f.reachReference()
        val original = requireNotNull(f.store.read())
        f.port.beforeRead = {
            val reference = requireNotNull(f.store.read()!!.reference)
            assertEquals(ReferenceStatus.VALID, reference.status)
            assertEquals(0L, reference.steps)
            assertNotNull(reference.groundTruthRecordedAtMs)
        }
        f.saveReference("0", "valid", "")
        assertEquals(0L, f.store.read()!!.reference!!.steps)
        assertTrue(f.port.reads.isEmpty())
        f.observe(stopped(), listOf(finalRecord))
        assertEquals(listOf(Read(7, 0, 8192)), f.port.reads)
        f.finishDownload()
        val saved = requireNotNull(f.store.read())
        assertEquals(CollectionPage.COMPLETE, f.owner.state.page)
        assertEquals(original.sessionId, saved.sessionId)
        assertEquals(original.stopConfirmedAtMs, saved.stopConfirmedAtMs)
        assertNull(saved.startedAtMs)
        assertNull(saved.endedAtMs)
        assertEquals(0L, saved.reference!!.steps)
        val raw = requireNotNull(saved.localData).files.single()
        assertFalse(raw.simulated)
        val bytes = File(f.directory, raw.fileName).readBytes()
        assertArrayEquals(payload, bytes.copyOfRange(HealthRawV2.HEADER_SIZE, bytes.size))
        assertEquals(SessionTransferStatus.PENDING, saved.transfer.status)
        assertNull(saved.transfer.receipt)
        assertTrue(f.owner.state.canStart)
    }

    @Test fun missingReferencePersistsItsReasonAndNullBeforeAnyDownload() = Fixture().use { f ->
        f.reachReference()
        f.port.beforeRead = {
            val reference = requireNotNull(f.store.read()!!.reference)
            assertEquals(ReferenceStatus.MISSING, reference.status)
            assertNull(reference.steps)
            assertNull(reference.groundTruthRecordedAtMs)
            assertEquals("计步器意外清零", reference.reason)
        }
        f.saveReference("999", "missing", "计步器意外清零")
        assertEquals(ReferenceStatus.MISSING, f.store.read()!!.reference!!.status)
        f.observe(stopped(), listOf(finalRecord))
        assertEquals(1, f.port.reads.size)
        f.finishDownload()
        assertNull(f.store.read()!!.reference!!.steps)
        assertEquals("missing", f.owner.state.referenceStatus)
        assertEquals(CollectionPage.COMPLETE, f.owner.state.page)
    }

    @Test fun failedReferenceCommitKeepsTheSessionAndCannotStartDownload() = Fixture().use { f ->
        f.reachReference()
        val before = requireNotNull(f.store.read())
        f.failCommit = true
        f.saveReference("562", "valid", "")
        assertEquals(before, f.store.read())
        assertEquals(CollectionPage.REFERENCE, f.owner.state.page)
        assertNotNull(f.owner.state.error)
        assertTrue(f.port.reads.isEmpty())
        assertFalse(f.owner.state.canStart)
        f.failCommit = false
        f.saveReference("562", "valid", "")
        assertEquals(before.sessionId, f.store.read()!!.sessionId)
        assertEquals(562L, f.store.read()!!.reference!!.steps)
        f.observe(stopped(), listOf(finalRecord))
        assertEquals(1, f.port.reads.size)
    }

    @Test fun atomicFinalizationFailureKeepsBothChoiceAndZeroReferenceRetriable() = Fixture().use { f ->
        f.beginCollecting()
        f.owner.stop()
        f.observe(stopped(), listOf(finalRecord))
        val before = requireNotNull(f.store.read())
        assertEquals(CollectionPage.FINISH, f.owner.state.page)
        assertNull(before.completionPolicy)
        assertNull(before.reference)

        f.failCommit = true
        f.owner.finalizeSession(false, "0", "valid", "")
        assertEquals(before, f.store.read())
        assertEquals(CollectionPage.FINISH, f.owner.state.page)
        assertTrue(f.port.reads.isEmpty())

        f.failCommit = false
        f.owner.finalizeSession(false, "0", "valid", "")
        val saved = requireNotNull(f.store.read())
        assertEquals(CompletionPolicy.DEFER_ON_RING, saved.completionPolicy)
        assertEquals(0L, saved.reference!!.steps)
        assertEquals(before.sessionId, saved.sessionId)
        assertEquals(CollectionPage.RING_PENDING, f.owner.state.page)
        assertTrue(f.port.reads.isEmpty())
        f.observe(stopped(), listOf(finalRecord))
        assertTrue(f.port.reads.isEmpty())
        f.owner.resumeRingTransfer()
        f.observe(stopped(), listOf(finalRecord))
        assertEquals(1, f.port.reads.size)
    }

    @Test fun legacySeparateDeferChoiceCannotCommitBeforeTheReference() = Fixture().use { f ->
        f.beginCollecting(); f.owner.stop(); f.observe(stopped(), listOf(finalRecord))

        f.owner.chooseFinish(false)

        val pending = requireNotNull(f.store.readPending())
        assertNull(pending.completionPolicy)
        assertNull(pending.reference)
        assertEquals(CollectionPage.FINISH, f.owner.state.page)
        assertTrue(f.port.reads.isEmpty())
    }

    @Test fun rejectedStopReturnsToCollectingAndCanBeRetriedWithoutInventingAnEnd() = Fixture().use { f ->
        f.beginCollecting()
        val id = f.store.read()!!.sessionId
        f.port.accept = { it != "stop" }
        f.owner.stop()
        assertEquals(1, f.port.count("stop"))
        assertEquals(FreeLivingSessionPhase.COLLECTING, f.store.read()!!.phase)
        assertNull(f.store.read()!!.stopConfirmedAtMs)
        assertNull(f.store.read()!!.reference)
        assertTrue(f.port.reads.isEmpty())
        assertFalse(f.owner.state.canStart)
        assertTrue(f.owner.state.canStop)

        f.port.accept = { true }
        f.owner.stop(); f.observe(stopped(), listOf(finalRecord))
        val saved = requireNotNull(f.store.read())
        assertEquals(id, saved.sessionId)
        assertNotNull(saved.stopConfirmedAtMs)
        assertNull(saved.reference)
        assertNull(saved.localData)
        assertEquals(CollectionPage.FINISH, f.owner.state.page)
        assertEquals(2, f.port.count("stop"))
    }

    @Test fun homeAndRetryOnlyShowTheCurrentTaskWithoutRepeatingStartOrStop() = Fixture().use { f ->
        f.beginCollecting()
        val id = f.store.read()!!.sessionId
        f.owner.home()
        assertEquals(CollectionPage.HOME, f.owner.state.page)
        assertEquals(CollectionPage.COLLECTING, f.owner.state.taskPage)
        f.owner.retry()
        f.startSelected()
        assertEquals(CollectionPage.COLLECTING, f.owner.state.page)
        assertEquals(1, f.port.count("start"))
        assertEquals(0, f.port.count("stop"))
        f.owner.stop()
        f.owner.stop()
        f.owner.home()
        f.owner.retry()
        assertEquals(1, f.port.count("stop"))
        assertEquals(id, f.store.read()!!.sessionId)
        f.observe(stopped(), listOf(finalRecord))
        f.owner.home()
        f.owner.enterReference()
        assertEquals(CollectionPage.FINISH, f.owner.state.page)
        f.owner.chooseFinish(true)
        assertEquals(CollectionPage.REFERENCE, f.owner.state.page)
        assertEquals(1, f.port.count("start"))
        assertEquals(1, f.port.count("stop"))
    }

    @Test fun recreatedOwnerQueriesTheSameCollectingRecordAndRestoresTheStopAction() = Fixture().use { f ->
        f.beginCollecting()
        val id = f.store.read()!!.sessionId
        f.reopen()
        f.owner.onConnected(f.port.generation)
        assertFalse(f.owner.state.canStop)
        f.observe(collecting(), listOf(initialRecord))
        assertEquals(id, f.store.read()!!.sessionId)
        assertEquals(CollectionPage.COLLECTING, f.owner.state.page)
        assertTrue(f.owner.state.canStop)
        assertEquals(1, f.port.count("start"))
        f.owner.stop()
        assertEquals(1, f.port.count("stop"))
    }

    @Test fun interruptedDownloadAndOwnerReopenResumeTheSameOffsetAndReference() = Fixture().use { f ->
        f.reachReference()
        f.saveReference("562", "valid", "")
        f.observe(stopped(), listOf(finalRecord))
        val before = requireNotNull(f.store.read())
        f.health(HealthMessage.DataChunk(0, payload.copyOf(13)))
        f.owner.onDisconnected(f.port.generation, "连接中断")
        assertNull(f.store.read()!!.localData)
        assertFalse(f.owner.state.canStart)
        f.reopen()
        f.owner.onConnected(f.port.generation)
        f.observe(stopped(), listOf(finalRecord))
        assertEquals(Read(7, 13, 8192), f.port.reads.last())
        assertEquals(before.sessionId, f.store.read()!!.sessionId)
        assertEquals(before.reference, f.store.read()!!.reference)
        f.health(HealthMessage.DataChunk(9, payload.copyOfRange(9, payload.size)))
        f.health(HealthMessage.ReadEnd(payload.size.toLong(), true))
        f.observe(stopped(), listOf(finalRecord))
        assertEquals(CollectionPage.COMPLETE, f.owner.state.page)
        assertEquals(1, f.directory.listFiles()!!.count { it.extension == "rfbin" })
        val final = f.store.read()!!.localData!!.files.single()
        assertArrayEquals(payload, File(f.directory, final.fileName).readBytes().drop(64).toByteArray())
        assertEquals(1, f.port.count("start"))
        assertEquals(1, f.port.count("stop"))
    }

    @Test fun completePayloadWaitsForFinalDeviceInspectionAndReopensWithoutRedownload() = Fixture().use { f ->
        f.reachReference()
        f.saveReference("563", "valid", "")
        f.observe(stopped(), listOf(finalRecord))
        f.health(HealthMessage.DataChunk(0, payload))
        f.health(HealthMessage.ReadEnd(payload.size.toLong(), true))

        assertNull(f.store.read()!!.localData)
        assertEquals(CollectionPage.DOWNLOADING, f.owner.state.page)
        assertTrue(f.directory.listFiles()!!.any { it.extension == "rfbin" })
        assertTrue(f.directory.listFiles()!!.any { it.name.endsWith(".download.json") })
        val reads = f.port.reads.toList()

        f.reopen()
        f.owner.onConnected(f.port.generation)
        f.observe(stopped(), listOf(finalRecord))

        assertEquals(CollectionPage.COMPLETE, f.owner.state.page)
        assertNotNull(f.store.read()!!.localData)
        assertEquals(reads, f.port.reads)
        assertFalse(f.directory.listFiles()!!.any { it.name.endsWith(".download.json") || it.extension == "part" })
    }

    @Test fun finalInspectionContinuesAStableRecordTailAndLeavesOtherSavedRecordUntouched() = Fixture().use { f ->
        val previous = f.seedLocal()
        val previousFile = File(f.directory, previous.localData!!.files.single().fileName)
        val previousBytes = previousFile.readBytes()
        f.reachReference()
        f.saveReference("564", "valid", "")
        f.observe(stopped(), listOf(finalRecord))
        f.health(HealthMessage.DataChunk(0, payload))
        f.health(HealthMessage.ReadEnd(payload.size.toLong(), true))

        val tail = imu(1, 1_120)
        val grown = finalRecord.copy(bytes = payload.size + tail.size.toLong(), records = 3)
        val grownStatus = stopped().copy(bytes = grown.bytes, records = grown.records)
        f.observe(grownStatus, listOf(grown))

        assertNull(f.store.read()!!.localData)
        assertEquals(Read(7, payload.size.toLong(), 8192), f.port.reads.last())
        assertArrayEquals(previousBytes, previousFile.readBytes())
        f.health(HealthMessage.DataChunk(payload.size.toLong(), tail))
        f.health(HealthMessage.ReadEnd(grown.bytes, true))
        f.observe(grownStatus, listOf(grown))

        val saved = requireNotNull(f.store.read())
        assertEquals(CollectionPage.COMPLETE, f.owner.state.page)
        assertEquals(grown, saved.deviceRecordEvidence!!.record)
        assertArrayEquals(payload + tail,
            File(f.directory, saved.localData!!.files.single().fileName).readBytes().drop(HealthRawV2.HEADER_SIZE).toByteArray())
        assertArrayEquals(previousBytes, previousFile.readBytes())
        assertFalse(f.directory.listFiles()!!.any { it.name.contains(".prefix-") })
    }

    @Test fun growingTailCanGrowAgainAcrossOwnerReopenAndResumeFromTheDurablePrefix() = Fixture().use { f ->
        f.reachReference()
        f.saveReference("566", "valid", "")
        f.observe(stopped(), listOf(finalRecord))
        f.health(HealthMessage.DataChunk(0, payload))
        f.health(HealthMessage.ReadEnd(payload.size.toLong(), true))
        val tailOne = imu(1, 1_120)
        val grownOnce = finalRecord.copy(bytes = payload.size + tailOne.size.toLong(), records = 3)
        f.observe(stopped().copy(bytes = grownOnce.bytes, records = grownOnce.records), listOf(grownOnce))
        assertEquals(Read(7, payload.size.toLong(), 8192), f.port.reads.last())

        f.reopen()
        f.owner.onConnected(f.port.generation)
        val tailTwo = imu(2, 1_140)
        val grownTwice = finalRecord.copy(bytes = payload.size + tailOne.size + tailTwo.size.toLong(), records = 4)
        val finalStatus = stopped().copy(bytes = grownTwice.bytes, records = grownTwice.records)
        f.observe(finalStatus, listOf(grownTwice))
        assertEquals(Read(7, payload.size.toLong(), 8192), f.port.reads.last())
        f.health(HealthMessage.DataChunk(payload.size.toLong(), tailOne + tailTwo))
        f.health(HealthMessage.ReadEnd(grownTwice.bytes, true))
        f.observe(finalStatus, listOf(grownTwice))

        val saved = requireNotNull(f.store.read())
        assertEquals(CollectionPage.COMPLETE, f.owner.state.page)
        assertEquals(grownTwice, saved.deviceRecordEvidence!!.record)
        assertArrayEquals(payload + tailOne + tailTwo,
            File(f.directory, saved.localData!!.files.single().fileName).readBytes().drop(HealthRawV2.HEADER_SIZE).toByteArray())
    }

    @Test fun finalInspectionAllowsAProtectedBaselineToDisappearButRejectsANewUnrelatedRecord() {
        for (foreign in listOf(false, true)) Fixture().use { f ->
            val old = finalRecord.copy(sessionId = 6, uptimeMs = 400, unixMs = epoch - 60_000)
            val oldStatus = stopped().copy(sessionId = 6)
            f.owner.initialize(); f.owner.onConnected(f.port.generation)
            f.observe(oldStatus, listOf(old))
            f.finishDownload()
            f.owner.onConnected(f.port.generation)
            f.observe(oldStatus, listOf(old)); f.observe(oldStatus, listOf(old))
            assertTrue(f.owner.state.canStart)
            f.startSelected(); f.observe(oldStatus, listOf(old))
            f.observe(collecting(), listOf(old, initialRecord))
            f.owner.stop(); f.observe(stopped(), listOf(old, finalRecord))
            f.owner.chooseFinish(true); f.owner.saveReference("567", "valid", "")
            f.observe(stopped(), listOf(old, finalRecord))
            f.health(HealthMessage.DataChunk(0, payload))
            f.health(HealthMessage.ReadEnd(payload.size.toLong(), true))

            val records = if (foreign) listOf(finalRecord,
                old.copy(sessionId = 99, uptimeMs = old.uptimeMs + 1)) else listOf(finalRecord)
            f.observe(stopped(), records)

            if (foreign) {
                assertNull(f.store.readPending()!!.localData)
                assertTrue(f.owner.state.error!!.contains("其他记录变化"))
                assertTrue(f.directory.listFiles()!!.any { it.extension == "rfbin" })
            } else {
                assertNotNull(f.store.read()!!.localData)
                assertEquals(CollectionPage.COMPLETE, f.owner.state.page)
            }
        }
    }

    @Test fun delayedCompletedPrefixEndIsIgnoredButAnUnknownEndStillStopsTheTransfer() = Fixture().use { f ->
        f.reachReference()
        f.saveReference("568", "valid", "")
        f.observe(stopped(), listOf(finalRecord))
        f.health(HealthMessage.DataChunk(0, payload))
        f.health(HealthMessage.ReadEnd(payload.size.toLong(), true))
        val tail = imu(1, 1_120)
        val grown = finalRecord.copy(bytes = payload.size + tail.size.toLong(), records = 3)
        val grownStatus = stopped().copy(bytes = grown.bytes, records = grown.records)
        f.observe(grownStatus, listOf(grown))
        val reads = f.port.reads.size

        f.health(HealthMessage.ReadEnd(payload.size.toLong(), true))
        assertEquals(reads, f.port.reads.size)
        assertTrue(f.errors.isEmpty())
        f.health(HealthMessage.ReadEnd(payload.size - 1L, true))
        assertNull(f.store.readPending()!!.localData)
        assertTrue(f.errors.any { it.message == "下载结束位置与已保存片段不一致" })
    }

    @Test fun changedIdentityAfterReadEndKeepsRawAndCheckpointForRecovery() = Fixture().use { f ->
        f.reachReference()
        f.saveReference("565", "valid", "")
        f.observe(stopped(), listOf(finalRecord))
        f.health(HealthMessage.DataChunk(0, payload))
        f.health(HealthMessage.ReadEnd(payload.size.toLong(), true))

        val replacement = finalRecord.copy(uptimeMs = finalRecord.uptimeMs + 1)
        f.observe(stopped(), listOf(replacement))

        assertNull(f.store.read()!!.localData)
        assertTrue(f.owner.state.error!!.contains("身份发生变化"))
        assertTrue(f.owner.state.canRetry)
        assertTrue(f.directory.listFiles()!!.any { it.extension == "rfbin" })
        assertTrue(f.directory.listFiles()!!.any { it.extension == "part" })
        assertTrue(f.directory.listFiles()!!.any { it.name.endsWith(".download.json") })
    }

    @Test fun confirmedStopKeepsFinishAndReferenceAvailableWhileDisconnectedAndReconnecting() = Fixture().use { f ->
        f.beginCollecting()
        f.owner.stop()
        f.observe(stopped(), listOf(finalRecord))
        val stoppedSession = requireNotNull(f.store.readPending())
        f.owner.onDisconnected(f.port.generation, "连接中断")
        assertEquals(CollectionPage.FINISH, f.owner.state.page)
        assertFalse(f.owner.state.busy)
        f.runDelay(3_000)
        assertTrue(f.owner.state.connecting)
        assertEquals(CollectionPage.FINISH, f.owner.state.page)
        assertFalse(f.owner.state.busy)
        f.owner.finalizeSession(false, "73", "valid", "")
        val saved = requireNotNull(f.store.readPending())
        assertEquals(stoppedSession.sessionId, saved.sessionId)
        assertEquals(stoppedSession.stopConfirmedAtMs, saved.stopConfirmedAtMs)
        assertEquals(73L, saved.reference!!.steps)
        assertEquals(CompletionPolicy.DEFER_ON_RING, saved.completionPolicy)
        assertEquals(CollectionPage.RING_PENDING, f.owner.state.page)
        assertTrue(f.port.reads.isEmpty())
        f.reopen()
        assertEquals(saved.reference, f.store.readPending()!!.reference)
        assertEquals(CompletionPolicy.DEFER_ON_RING, f.store.readPending()!!.completionPolicy)
        assertEquals(CollectionPage.RING_PENDING, f.owner.state.page)
        f.owner.retry(); f.owner.reconnect()
        f.owner.onConnected(f.port.generation)
        assertTrue(f.port.reads.isEmpty())
        f.owner.resumeRingTransfer()
        f.owner.onConnected(f.port.generation); f.observe(stopped(), listOf(finalRecord))
        f.finishDownload()
        assertEquals(saved.reference, f.store.read()!!.reference)
        assertEquals(0, f.store.read()!!.transfer.attempts)
        assertEquals(1, f.port.count("start"))
        assertEquals(1, f.port.count("stop"))
    }

    @Test fun referenceFormRemainsAvailableThroughAReconnectTimeoutAndProcessRestart() = Fixture().use { f ->
        f.reachReference()
        f.owner.onDisconnected(f.port.generation, "连接中断")
        assertEquals(CollectionPage.REFERENCE, f.owner.state.page)
        f.reopen()
        assertEquals(CollectionPage.REFERENCE, f.owner.state.page)
        assertFalse(f.owner.state.busy)
        f.runAllDelays(30_000)
        assertFalse(f.owner.state.connecting)
        assertEquals(CollectionPage.REFERENCE, f.owner.state.page)
        f.owner.saveReference("0", "valid", "")
        assertEquals(0L, f.store.readPending()!!.reference!!.steps)
        assertFalse(f.owner.state.busy)
        assertTrue(f.owner.state.canRetry)
        assertTrue(f.port.reads.isEmpty())
    }

    @Test fun changedRecordCannotBeDownloadedUnderTheOriginalReference() = Fixture().use { f ->
        f.reachReference()
        f.saveReference("71", "valid", "")
        val before = requireNotNull(f.store.read())
        f.observe(stopped(), listOf(finalRecord.copy(unixMs = epoch + 1)))
        assertTrue(f.port.reads.isEmpty())
        assertEquals(before, f.store.read())
        assertEquals(CollectionPage.ERROR, f.owner.state.page)
        assertFalse(f.owner.state.canStart)
    }

    @Test fun prematureDownloadEndPreservesPartialAndCannotBecomeComplete() = Fixture().use { f ->
        f.reachReference()
        f.saveReference("0", "valid", "")
        f.observe(stopped(), listOf(finalRecord))
        f.health(HealthMessage.DataChunk(0, payload.copyOf(9)))
        f.health(HealthMessage.ReadEnd(9, true))
        assertEquals(CollectionPage.ERROR, f.owner.state.page)
        assertNull(f.store.read()!!.localData)
        assertFalse(f.owner.state.canStart)
        assertArrayEquals(payload.copyOf(9), f.directory.listFiles()!!.single { it.extension == "part" }.readBytes())
        assertFalse(f.directory.listFiles()!!.any { it.extension == "rfbin" })
    }

    @Test fun lateRepliesFromThePreviousConnectionCannotCompleteTheResumedDownload() = Fixture().use { f ->
        f.reachReference()
        f.saveReference("22", "valid", "")
        f.observe(stopped(), listOf(finalRecord))
        f.health(HealthMessage.DataChunk(0, payload.copyOf(13)))
        val previousGeneration = f.port.generation
        f.owner.onDisconnected(previousGeneration, "连接中断")
        f.owner.reconnect()
        assertTrue(f.port.generation > previousGeneration)
        f.owner.onConnected(f.port.generation)
        f.observe(stopped(), listOf(finalRecord))
        f.owner.onHealth(previousGeneration, SensorPacket.Health(
            HealthMessage.DataChunk(13, payload.copyOfRange(13, payload.size)), ++f.clock.now))
        f.owner.onHealth(previousGeneration, SensorPacket.Health(
            HealthMessage.ReadEnd(payload.size.toLong(), true), ++f.clock.now))
        assertNull(f.store.read()!!.localData)
        assertEquals(CollectionPage.DOWNLOADING, f.owner.state.page)
        f.health(HealthMessage.DataChunk(13, payload.copyOfRange(13, payload.size)))
        f.health(HealthMessage.ReadEnd(payload.size.toLong(), true))
        f.observe(stopped(), listOf(finalRecord))
        assertEquals(CollectionPage.COMPLETE, f.owner.state.page)
        assertEquals(22L, f.store.read()!!.reference!!.steps)
    }

    @Test fun completedWindowEndsAreIgnoredBeforeAndAfterTheNextWindowData() = Fixture().use { f ->
        f.reachReference(); f.saveReference("21", "valid", "")
        f.observe(stopped(), listOf(finalRecord))
        f.health(HealthMessage.DataChunk(0, firstPacket))
        val previousEnd = HealthMessage.ReadEnd(firstPacket.size.toLong(), false)
        f.health(previousEnd)
        val reads = f.port.reads.toList()
        repeat(4) { f.health(previousEnd) }
        assertEquals(reads, f.port.reads)
        f.health(HealthMessage.DataChunk(firstPacket.size.toLong(), payload.copyOfRange(firstPacket.size, payload.size)))
        f.health(previousEnd)
        assertEquals(reads, f.port.reads)
        f.health(HealthMessage.ReadEnd(payload.size.toLong(), true))
        f.observe(stopped(), listOf(finalRecord))
        assertEquals(CollectionPage.COMPLETE, f.owner.state.page)
        assertEquals(21L, f.store.read()!!.reference!!.steps)
        assertTrue(f.errors.isEmpty())
    }

    @Test fun missingChunkOrReadEndGapReconnectsAndCompletesFromTheContiguousPrefix() {
        for (gapInEnd in listOf(false, true)) Fixture().use { f ->
            f.reachReference(); f.saveReference("26", "valid", "")
            f.observe(stopped(), listOf(finalRecord))
            val saved = f.store.readPending()!!
            f.health(HealthMessage.DataChunk(0, payload.copyOf(13)))
            val previous = f.port.generation
            f.health(if (gapInEnd) HealthMessage.ReadEnd(20, false)
                else HealthMessage.DataChunk(20, payload.copyOfRange(20, 22)))
            assertFalse(f.owner.state.connected)
            assertArrayEquals(payload.copyOf(13), f.directory.listFiles()!!.single { it.extension == "part" }.readBytes())
            f.runAllDelays(3_000); f.owner.onConnected(f.port.generation)
            f.observe(stopped(), listOf(finalRecord))
            assertEquals(Read(7, 13, 8192), f.port.reads.last())
            f.owner.onHealth(previous, SensorPacket.Health(HealthMessage.DataChunk(13, byteArrayOf(100)), ++f.clock.now))
            f.health(HealthMessage.DataChunk(13, payload.copyOfRange(13, payload.size)))
            f.health(HealthMessage.ReadEnd(payload.size.toLong(), true))
            f.observe(stopped(), listOf(finalRecord))
            assertEquals(CollectionPage.COMPLETE, f.owner.state.page)
            assertEquals(saved.sessionId, f.store.read()!!.sessionId)
            assertEquals(saved.reference, f.store.read()!!.reference)
            assertTrue(f.errors.isEmpty())
        }
    }

    @Test fun repeatedMissingChunksStopAfterTheSharedRecoveryLimit() = Fixture().use { f ->
        f.reachReference(); f.saveReference("27", "valid", "")
        f.observe(stopped(), listOf(finalRecord))
        f.health(HealthMessage.DataChunk(0, payload.copyOf(13)))
        repeat(RealCollectionController.DOWNLOAD_RECOVERY_LIMIT) { attempt ->
            f.health(HealthMessage.DataChunk(20, payload.copyOfRange(20, 22)))
            if (attempt + 1 < RealCollectionController.DOWNLOAD_RECOVERY_LIMIT) {
                f.runAllDelays(3_000); f.owner.onConnected(f.port.generation)
                f.observe(stopped(), listOf(finalRecord))
            }
        }
        assertEquals(CollectionPage.RECOVERY, f.owner.state.page)
        assertTrue(f.owner.state.canRetry)
        assertFalse(f.owner.state.busy)
        assertEquals(0, f.waitCount(3_000))
        assertArrayEquals(payload.copyOf(13), f.directory.listFiles()!!.single { it.extension == "part" }.readBytes())
        assertEquals(27L, f.store.readPending()!!.reference!!.steps)
        assertNull(f.store.readPending()!!.localData)
    }

    @Test fun silentDownloadStopsAcrossConnectionsAndManualRetryKeepsTheReference() = Fixture().use { f ->
        f.reachReference(); f.saveReference("22", "valid", "")
        f.observe(stopped(), listOf(finalRecord))
        val saved = f.store.readPending()!!
        var retired = f.port.generation
        repeat(RealCollectionController.DOWNLOAD_RECOVERY_LIMIT) { attempt ->
            retired = f.port.generation
            f.runAllDelays(30_000)
            if (attempt + 1 < RealCollectionController.DOWNLOAD_RECOVERY_LIMIT) {
                f.runAllDelays(3_000)
                f.owner.onConnected(f.port.generation)
                f.observe(stopped(), listOf(finalRecord))
            }
        }
        assertEquals(CollectionPage.RECOVERY, f.owner.state.page)
        assertTrue(f.owner.state.canRetry)
        assertFalse(f.owner.state.busy)
        assertFalse(f.owner.state.connected)
        assertEquals(saved.reference, f.store.readPending()!!.reference)
        assertEquals(0L, f.directory.listFiles()!!.single { it.extension == "part" }.length())
        assertNull(f.store.readPending()!!.localData)
        val paused = f.owner.state
        val connections = f.port.count("connect")
        val reads = f.port.reads.size
        f.owner.onDisconnected(retired, "迟到断开")
        f.owner.onConnected(retired)
        f.owner.onHealth(retired, SensorPacket.Health(HealthMessage.DataChunk(0, payload), ++f.clock.now))
        assertEquals(paused, f.owner.state)
        assertEquals(connections, f.port.count("connect"))
        assertEquals(reads, f.port.reads.size)
        assertEquals(0, f.waitCount(3_000))

        f.errors.clear()
        f.owner.retry(); f.owner.onConnected(f.port.generation)
        f.observe(stopped(), listOf(finalRecord)); f.finishDownload()
        assertEquals(CollectionPage.COMPLETE, f.owner.state.page)
        assertEquals(saved.sessionId, f.store.read()!!.sessionId)
        assertEquals(saved.reference, f.store.read()!!.reference)
        assertEquals(1, f.port.count("start")); assertEquals(1, f.port.count("stop"))
    }

    @Test fun repeatedDataAcrossConnectionsCannotKeepAStalledDownloadAlive() = Fixture().use { f ->
        val prefix = payload.copyOf(13)
        f.reachReference(); f.saveReference("23", "valid", "")
        f.observe(stopped(), listOf(finalRecord))
        f.health(HealthMessage.DataChunk(0, prefix))
        repeat(RealCollectionController.DOWNLOAD_RECOVERY_LIMIT) { attempt ->
            repeat(5) { f.health(HealthMessage.DataChunk(0, prefix)) }
            f.runAllDelays(30_000)
            if (attempt + 1 < RealCollectionController.DOWNLOAD_RECOVERY_LIMIT) {
                f.runAllDelays(3_000)
                f.owner.onConnected(f.port.generation)
                f.observe(stopped(), listOf(finalRecord))
                assertEquals(13L, f.port.reads.last().offset)
            }
        }
        assertFalse(f.owner.state.busy)
        assertTrue(f.owner.state.canRetry)
        assertArrayEquals(prefix, f.directory.listFiles()!!.single { it.extension == "part" }.readBytes())
        assertEquals(23L, f.store.readPending()!!.reference!!.steps)
        assertNull(f.store.readPending()!!.localData)
    }

    @Test fun downloadRecoveryConnectionFailuresShareTheBudgetAndDuplicateDisconnectsDoNotSpendIt() = Fixture().use { f ->
        f.reachReference(); f.saveReference("24", "valid", "")
        f.observe(stopped(), listOf(finalRecord))
        val failed = f.port.generation
        f.runAllDelays(30_000)
        repeat(5) { f.owner.onDisconnected(failed, "重复断开") }
        assertEquals(1, f.waitCount(3_000))
        f.port.accept = { it != "connect" }
        repeat(RealCollectionController.DOWNLOAD_RECOVERY_LIMIT - 1) { f.runAllDelays(3_000) }
        assertEquals(CollectionPage.RECOVERY, f.owner.state.page)
        assertFalse(f.owner.state.busy)
        assertTrue(f.owner.state.canRetry)
        assertEquals(0, f.waitCount(3_000))
        val paused = f.owner.state
        if (f.waitCount(30_000) > 0) f.runAllDelays(30_000)
        assertEquals(paused, f.owner.state)
        assertEquals(24L, f.store.readPending()!!.reference!!.steps)
    }

    @Test fun newDownloadedBytesRestoreTheAutomaticRecoveryBudget() = Fixture().use { f ->
        f.reachReference(); f.saveReference("25", "valid", "")
        f.observe(stopped(), listOf(finalRecord))
        fun recover() {
            f.runAllDelays(30_000)
            assertFalse(f.owner.state.connected)
            f.runAllDelays(3_000)
            f.owner.onConnected(f.port.generation)
            f.observe(stopped(), listOf(finalRecord))
        }
        repeat(RealCollectionController.DOWNLOAD_RECOVERY_LIMIT - 1) { recover() }
        f.health(HealthMessage.DataChunk(0, payload.copyOf(13)))
        repeat(RealCollectionController.DOWNLOAD_RECOVERY_LIMIT - 1) { recover() }
        assertEquals(13L, f.port.reads.last().offset)
        f.health(HealthMessage.DataChunk(13, payload.copyOfRange(13, payload.size)))
        f.health(HealthMessage.ReadEnd(payload.size.toLong(), true))
        f.observe(stopped(), listOf(finalRecord))
        assertEquals(CollectionPage.COMPLETE, f.owner.state.page)
        assertEquals(25L, f.store.read()!!.reference!!.steps)
        assertTrue(f.errors.isEmpty())
    }

    @Test fun savedRecordReopensAsLocalAndPendingUploadWithoutAnotherDownload() = Fixture().use { f ->
        f.reachReference()
        f.saveReference("124", "valid", "")
        f.observe(stopped(), listOf(finalRecord))
        f.finishDownload()
        val saved = requireNotNull(f.store.read())
        f.reopen()
        f.owner.onConnected(f.port.generation)
        f.observe(stopped(), listOf(finalRecord))
        assertEquals(saved, f.store.read())
        assertEquals(1, f.port.reads.size)
        assertTrue(f.owner.state.canStart)
        assertEquals(124L, f.owner.state.records.single().steps)
        assertEquals("pending", f.owner.state.records.single().transferStatus)
        assertFalse(f.owner.state.uploadAvailable)
        f.owner.retryUpload(saved.sessionId)
        assertEquals(saved, f.store.read())
    }

    @Test fun completedSessionReopenCleansOnlyItsVerifiedDownloadResidue() = Fixture().use { f ->
        f.reachReference()
        f.saveReference("569", "valid", "")
        f.observe(stopped(), listOf(finalRecord))
        f.finishDownload()
        val saved = requireNotNull(f.store.read())
        val prefix = "${saved.sessionId}-ring-${finalRecord.sessionId}"
        val leftovers = listOf("$prefix.part", "$prefix.download.json",
            "$prefix.prefix-${payload.size}.rfbin", "$prefix.prefix-${payload.size}.raw-evidence.json")
        leftovers.forEach { File(f.directory, it).writeText("restart residue") }
        val otherId = "11111111-2222-4333-8444-555555555555"
        val other = File(f.directory, "$otherId-ring-7.prefix-${payload.size}.rfbin").apply { writeText("other") }

        f.reopen()

        leftovers.forEach { assertFalse(File(f.directory, it).exists()) }
        assertEquals("other", other.readText())
        assertTrue(File(f.directory, saved.localData!!.files.single().fileName).isFile)
    }

    @Test fun unknownDeviceClockCanStopAndDownloadOnItsOriginalConnection() = Fixture().use { f ->
        f.reachReference(unixMs = 0)
        val original = requireNotNull(f.store.read())
        assertEquals(1, f.port.count("stop"))
        assertNotNull(original.stopConfirmedAtMs)
        assertNull(original.startedAtMs)
        assertNull(original.endedAtMs)
        f.saveReference("12", "valid", "")
        f.observe(stopped(), listOf(finalRecord.copy(unixMs = 0)))
        assertEquals(listOf(Read(7, 0, 8192)), f.port.reads)
        f.finishDownload()
        assertEquals(CollectionPage.COMPLETE, f.owner.state.page)
        assertEquals(original.sessionId, f.store.read()!!.sessionId)
        assertEquals(12L, f.store.read()!!.reference!!.steps)
        assertNotNull(f.store.read()!!.localData)
        assertNull(f.store.read()!!.endedAtMs)
    }

    @Test fun unknownClockReferenceScreenReopenPreservesInputButCannotReadUnprovedRecord() = Fixture().use { f ->
        f.reachReference(unixMs = 0)
        val original = requireNotNull(f.store.read())
        f.reopen()
        f.owner.onConnected(f.port.generation)
        assertEquals(CollectionPage.REFERENCE, f.owner.state.page)
        f.saveReference("29", "valid", "")
        f.observe(stopped(), listOf(finalRecord.copy(unixMs = 0)))
        assertTrue(f.port.reads.isEmpty())
        assertEquals(CollectionPage.ERROR, f.owner.state.page)
        assertEquals(original.sessionId, f.store.read()!!.sessionId)
        assertEquals(original.stopConfirmedAtMs, f.store.read()!!.stopConfirmedAtMs)
        assertEquals(29L, f.store.read()!!.reference!!.steps)
        assertNull(f.store.read()!!.localData)
        assertFalse(f.owner.state.canStart)
        assertEquals(1, f.port.count("start"))
        assertEquals(1, f.port.count("stop"))
    }

    @Test fun unknownClockSavedReferenceReopenKeepsReferenceAndRefusesDownload() = Fixture().use { f ->
        f.reachReference(unixMs = 0)
        f.saveReference("31", "valid", "")
        val before = requireNotNull(f.store.read())
        f.reopen()
        f.owner.onConnected(f.port.generation)
        f.observe(stopped(), listOf(finalRecord.copy(unixMs = 0)))
        assertTrue(f.port.reads.isEmpty())
        assertEquals(before, f.store.read())
        assertEquals(CollectionPage.ERROR, f.owner.state.page)
        assertFalse(f.owner.state.canStart)
    }

    @Test fun unknownClockInterruptedDownloadRetainsPartialAndDoesNotReadOnNewConnection() = Fixture().use { f ->
        f.reachReference(unixMs = 0)
        f.saveReference("33", "valid", "")
        f.observe(stopped(), listOf(finalRecord.copy(unixMs = 0)))
        f.health(HealthMessage.DataChunk(0, payload.copyOf(13)))
        val before = requireNotNull(f.store.read())
        val previousGeneration = f.port.generation
        f.owner.onDisconnected(previousGeneration, "连接中断")
        f.owner.reconnect()
        assertTrue(f.port.generation > previousGeneration)
        f.owner.onConnected(f.port.generation)
        f.observe(stopped(), listOf(finalRecord.copy(unixMs = 0)))
        assertEquals(1, f.port.reads.size)
        assertEquals(before, f.store.read())
        assertEquals(CollectionPage.ERROR, f.owner.state.page)
        assertFalse(f.owner.state.canStart)
        assertArrayEquals(payload.copyOf(13), f.directory.listFiles()!!.single { it.extension == "part" }.readBytes())
    }

    @Test fun unknownClockSavedReferenceReopensWithReadOnlyClockEvidenceAndSameSession() = Fixture().use { f ->
        f.reachReference(unixMs = 0)
        f.saveReference("0", "valid", "")
        val before = f.store.readPending()!!
        val startClock = f.seedRecoveryClock()
        f.reopen(); f.owner.onConnected(f.port.generation)
        f.observe(stopped(), listOf(finalRecord.copy(unixMs = 0)))
        assertEquals(1, f.port.count("time_get"))
        assertTrue(f.port.reads.isEmpty())
        assertTrue(f.owner.state.busy)
        f.recoveryTimeReply(startClock)
        assertTrue(f.port.reads.isEmpty())
        f.observe(stopped(), listOf(finalRecord.copy(unixMs = 0)))
        f.finishDownload()
        assertEquals(CollectionPage.COMPLETE, f.owner.state.page)
        assertEquals(before.sessionId, f.store.read()!!.sessionId)
        assertEquals(before.reference, f.store.read()!!.reference)
        assertEquals(0L, f.store.read()!!.deviceRecordEvidence!!.record.unixMs)
        assertNull(f.store.read()!!.startedAtMs)
        assertEquals(0, f.port.count("time"))
        assertEquals(1, f.port.count("start")); assertEquals(1, f.port.count("stop"))
    }

    @Test fun unknownClockReconnectedDownloadComparesSavedPrefixBeforeContinuing() = Fixture().use { f ->
        f.reachReference(unixMs = 0); f.saveReference("73", "valid", "")
        val startClock = f.seedRecoveryClock()
        f.observe(stopped(), listOf(finalRecord.copy(unixMs = 0)))
        f.health(HealthMessage.DataChunk(0, payload.copyOf(13)))
        f.reopen(); f.owner.onConnected(f.port.generation)
        f.observe(stopped(), listOf(finalRecord.copy(unixMs = 0)))
        f.recoveryTimeReply(startClock)
        f.observe(stopped(), listOf(finalRecord.copy(unixMs = 0)))
        assertEquals(Read(7, 0, 13), f.port.reads.last())
        f.health(HealthMessage.DataChunk(0, payload.copyOf(13)))
        f.health(HealthMessage.ReadEnd(13, false))
        assertEquals(Read(7, 13, 8192), f.port.reads.last())
        f.health(HealthMessage.DataChunk(13, payload.copyOfRange(13, payload.size)))
        f.health(HealthMessage.ReadEnd(payload.size.toLong(), true))
        f.observe(stopped(), listOf(finalRecord.copy(unixMs = 0)))
        assertEquals(CollectionPage.COMPLETE, f.owner.state.page)
        assertEquals(73L, f.store.read()!!.reference!!.steps)
    }

    @Test fun stalledReplayStopsAfterThreeWindowsAndRechecksTheSavedPrefixOnManualRetry() = Fixture().use { f ->
        val record = finalRecord.copy(unixMs = 0)
        val prefix = payload.copyOf(13)
        f.reachReference(unixMs = 0); f.saveReference("73", "valid", "")
        val startClock = f.seedRecoveryClock()
        f.observe(stopped(), listOf(record))
        f.health(HealthMessage.DataChunk(0, prefix))
        val saved = f.store.readPending()!!
        f.reopen(); f.owner.onConnected(f.port.generation)
        f.observe(stopped(), listOf(record))
        f.recoveryTimeReply(startClock)
        f.observe(stopped(), listOf(record))
        assertEquals(Read(7, 0, 13), f.port.reads.last())
        val readsBeforeStall = f.port.reads.size
        val retired = f.port.generation
        repeat(3) { f.health(HealthMessage.ReadEnd(0, false)) }
        assertEquals(readsBeforeStall + 2, f.port.reads.size)
        assertEquals(CollectionPage.RECOVERY, f.owner.state.page)
        assertTrue(f.owner.state.canRetry)
        assertFalse(f.owner.state.busy)
        assertFalse(f.owner.state.connected)
        assertEquals(saved.sessionId, f.store.readPending()!!.sessionId)
        assertEquals(saved.reference, f.store.readPending()!!.reference)
        assertNull(f.store.readPending()!!.localData)
        assertArrayEquals(prefix, f.directory.listFiles()!!.single { it.extension == "part" }.readBytes())
        val connectionsAfterStall = f.port.count("connect")
        val pausedState = f.owner.state
        f.owner.onDisconnected(retired, "迟到的断开通知")
        f.owner.onHealth(retired, SensorPacket.Health(HealthMessage.ReadEnd(0, false), ++f.clock.now))
        f.runAllDelays(30_000)
        // The completed recovery clock query also leaves a retired three-second timer.
        f.runAllDelays(3_000)
        assertEquals(readsBeforeStall + 2, f.port.reads.size)
        assertEquals(connectionsAfterStall, f.port.count("connect"))
        assertEquals(pausedState, f.owner.state)

        f.owner.retry(); f.owner.onConnected(f.port.generation)
        f.observe(stopped(), listOf(record))
        f.recoveryTimeReply(startClock)
        f.observe(stopped(), listOf(record))
        assertEquals(Read(7, 0, 13), f.port.reads.last())
        f.health(HealthMessage.DataChunk(0, prefix))
        f.health(HealthMessage.ReadEnd(13, false))
        assertEquals(Read(7, 13, 8192), f.port.reads.last())
        f.health(HealthMessage.DataChunk(13, payload.copyOfRange(13, payload.size)))
        f.health(HealthMessage.ReadEnd(payload.size.toLong(), true))
        f.observe(stopped(), listOf(record))
        assertEquals(CollectionPage.COMPLETE, f.owner.state.page)
        assertEquals(saved.sessionId, f.store.read()!!.sessionId)
        assertEquals(saved.reference, f.store.read()!!.reference)
        assertEquals(1, f.port.count("start"))
    }

    @Test fun repeatingTheSameVerifiedPrefixAcrossReconnectsDoesNotResetTheRecoveryBudget() = Fixture().use { f ->
        val record = finalRecord.copy(unixMs = 0)
        val prefix = payload.copyOf(13)
        f.reachReference(unixMs = 0); f.saveReference("74", "valid", "")
        val startClock = f.seedRecoveryClock()
        f.observe(stopped(), listOf(record))
        f.health(HealthMessage.DataChunk(0, prefix))
        f.owner.onDisconnected(f.port.generation, "连接中断")
        repeat(RealCollectionController.DOWNLOAD_RECOVERY_LIMIT) {
            f.runAllDelays(3_000)
            f.owner.onConnected(f.port.generation)
            f.observe(stopped(), listOf(record))
            f.recoveryTimeReply(startClock)
            f.observe(stopped(), listOf(record))
            assertEquals(Read(7, 0, 13), f.port.reads.last())
            f.health(HealthMessage.DataChunk(0, prefix))
            f.health(HealthMessage.ReadEnd(13, false))
            assertEquals(13L, f.port.reads.last().offset)
            f.runAllDelays(30_000)
        }
        assertEquals(CollectionPage.RECOVERY, f.owner.state.page)
        assertTrue(f.owner.state.canRetry)
        assertFalse(f.owner.state.busy)
        assertArrayEquals(prefix, f.directory.listFiles()!!.single { it.extension == "part" }.readBytes())
        val connections = f.port.count("connect")
        val paused = f.owner.state
        // Completed TIME GET requests leave obsolete timers at this same delay.
        f.runAllDelays(3_000)
        assertEquals(connections, f.port.count("connect"))
        assertEquals(paused, f.owner.state)
        assertEquals(74L, f.store.readPending()!!.reference!!.steps)
        assertNull(f.store.readPending()!!.localData)
    }

    @Test fun unknownClockChangedPrefixCannotBeMergedOrOverwriteSavedBytes() = Fixture().use { f ->
        f.reachReference(unixMs = 0); f.saveReference("2", "valid", "")
        val startClock = f.seedRecoveryClock()
        f.observe(stopped(), listOf(finalRecord.copy(unixMs = 0)))
        f.health(HealthMessage.DataChunk(0, payload.copyOf(13)))
        f.reopen(); f.owner.onConnected(f.port.generation)
        f.observe(stopped(), listOf(finalRecord.copy(unixMs = 0)))
        f.recoveryTimeReply(startClock)
        f.observe(stopped(), listOf(finalRecord.copy(unixMs = 0)))
        f.health(HealthMessage.DataChunk(0, payload.copyOf(13).apply { this[0] = 0 }))
        assertEquals(CollectionPage.ERROR, f.owner.state.page)
        assertArrayEquals(payload.copyOf(13), f.directory.listFiles()!!.single { it.extension == "part" }.readBytes())
        assertNull(f.store.read()!!.localData)
        assertEquals(2L, f.store.read()!!.reference!!.steps)
    }

    @Test fun recoveredPrefixSpansMultipleWindowsAndAcceptsIdenticalDuplicateChunks() = Fixture().use { f ->
        val longPayload = ByteArrayOutputStream().apply {
            repeat(500) { write(imu(3, 1_000L + it * 60)) }
        }.toByteArray()
        val record = finalRecord.copy(unixMs = 0, bytes = longPayload.size.toLong(), records = 500)
        val status = stopped().copy(bytes = record.bytes, records = record.records)
        f.reachReference(unixMs = 0); f.saveReference("0", "valid", "")
        val start = f.seedRecoveryClock()
        f.observe(status, listOf(record))
        f.health(HealthMessage.DataChunk(0, longPayload.copyOf(10_000)))
        f.reopen(); f.owner.onConnected(f.port.generation)
        f.observe(status, listOf(record)); f.recoveryTimeReply(start); f.observe(status, listOf(record))
        assertEquals(Read(7, 0, 8192), f.port.reads.last())
        val first = HealthMessage.DataChunk(0, longPayload.copyOf(8192))
        f.health(first); f.health(first)
        f.health(HealthMessage.ReadEnd(8192, false))
        assertEquals(Read(7, 8192, 1808), f.port.reads.last())
        f.health(HealthMessage.DataChunk(8192, longPayload.copyOfRange(8192, 10_000)))
        f.health(HealthMessage.ReadEnd(10_000, false))
        assertEquals(Read(7, 10_000, 8192), f.port.reads.last())
        f.health(HealthMessage.DataChunk(10_000, longPayload.copyOfRange(10_000, longPayload.size)))
        f.health(HealthMessage.ReadEnd(record.bytes, true)); f.observe(status, listOf(record))
        assertEquals(CollectionPage.COMPLETE, f.owner.state.page)
        assertEquals(0L, f.store.read()!!.reference!!.steps)
        assertEquals(500L, f.store.read()!!.deviceRecordEvidence!!.record.records)
    }

    @Test fun unknownClockRecoveryRejectsResetClockTimeoutAndForeignRecords() {
        for (fault in listOf("reset", "unsynced", "timeout", "foreign", "save")) Fixture().use { f ->
            f.reachReference(unixMs = 0); f.saveReference("31", "valid", "")
            val startClock = f.seedRecoveryClock()
            f.reopen(); f.owner.onConnected(f.port.generation)
            val before = f.store.readPending()!!
            f.observe(stopped(), listOf(finalRecord.copy(unixMs = 0)))
            when (fault) {
                "timeout" -> f.runDelay(PhoneClockSync.TIMEOUT_MS)
                "reset" -> f.recoveryTimeReply(startClock, uptime = 1)
                "unsynced" -> f.recoveryTimeReply(startClock, synced = false)
                "save" -> { f.failRecoveryEvidence = true; f.recoveryTimeReply(startClock) }
                else -> {
                    f.recoveryTimeReply(startClock)
                    f.observe(stopped(), listOf(finalRecord.copy(unixMs = 0), finalRecord.copy(sessionId = 8)))
                }
            }
            assertTrue("fault=$fault", f.port.reads.isEmpty())
            assertEquals(before, f.store.readPending())
            assertTrue(f.owner.state.canRetry)
            assertEquals(1, f.port.count("start")); assertEquals(1, f.port.count("stop"))
            assertEquals(0, f.port.count("time"))
        }
    }

    @Test fun unknownClockRecoveryAllowsTenHoursAndPhoneElapsedResetButRejectsPhoneClockJump() {
        for (jump in listOf(false, true)) Fixture().use { f ->
            f.reachReference(unixMs = 0); f.saveReference("31", "valid", "")
            val startClock = f.seedRecoveryClock()
            f.clock.now += 10 * 60 * 60 * 1000L
            f.clock.elapsed = 50 // Another Android boot; only this GET uses the new monotonic clock.
            f.reopen(); f.owner.onConnected(f.port.generation)
            f.observe(stopped(), listOf(finalRecord.copy(unixMs = 0)))
            f.recoveryTimeReply(startClock, clockJump = if (jump) 10_000 else 0)
            if (!jump) {
                f.observe(stopped(), listOf(finalRecord.copy(unixMs = 0)))
                f.finishDownload()
                assertEquals(CollectionPage.COMPLETE, f.owner.state.page)
            } else {
                assertTrue(f.port.reads.isEmpty())
                assertNull(f.store.read()!!.localData)
            }
        }
    }

    @Test fun recoveryUptimeAnchorUsesHealthUint32WrappingWithoutChangingResearchTime() = Fixture().use { f ->
        f.reachReference(unixMs = 0); f.saveReference("0", "valid", "")
        val session = f.store.readPending()!!
        val start = f.seedRecoveryClock()
        StoppedRecordClockRecovery.validateStart(session, start.copy(deviceUptimeMs = (1L shl 32) + start.deviceUptimeMs))
        // The HEALTH record is 100 ms after this TIME anchor across the uint32 boundary.
        val record = session.deviceRecordEvidence!!.record.copy(uptimeMs = 50)
        StoppedRecordClockRecovery.validateStart(session.copy(deviceRecordEvidence =
            session.deviceRecordEvidence!!.copy(record = record)), start.copy(deviceUptimeMs = (1L shl 32) - 50))
        assertThrows(IllegalArgumentException::class.java) {
            StoppedRecordClockRecovery.validateStart(session, start.copy(deviceUptimeMs = initialRecord.uptimeMs + 1))
        }
        assertEquals(0L, f.store.readPending()!!.deviceRecordEvidence!!.record.unixMs)
    }

    @Test fun recoveryUsesElapsedContinuityForAccumulatedClockDriftAndRejectsLargerChanges() {
        for (lag in listOf(364L, 6_000L, 7_000L)) Fixture().use { f ->
            f.reachReference(unixMs = 0); f.saveReference("0", "valid", "")
            val start = f.seedRecoveryClock()
            f.reopen(); f.owner.onConnected(f.port.generation)
            f.observe(stopped(), listOf(finalRecord.copy(unixMs = 0)))
            f.recoveryTimeReply(start, deviceLag = lag)
            if (lag < 6_500) {
                f.observe(stopped(), listOf(finalRecord.copy(unixMs = 0)))
                f.finishDownload()
                assertEquals(CollectionPage.COMPLETE, f.owner.state.page)
            } else {
                assertEquals(CollectionPage.ERROR, f.owner.state.page)
                assertTrue(f.port.reads.isEmpty())
            }
        }
    }

    @Test fun timedOutRecoveryReplyAndOldConnectionCannotAuthorizeTheNextDownload() = Fixture().use { f ->
        f.reachReference(unixMs = 0); f.saveReference("73", "valid", "")
        val start = f.seedRecoveryClock()
        f.reopen(); f.owner.onConnected(f.port.generation)
        f.observe(stopped(), listOf(finalRecord.copy(unixMs = 0)))
        val oldGeneration = f.port.generation
        f.runDelay(PhoneClockSync.TIMEOUT_MS)
        f.recoveryTimeReply(start)
        assertTrue(f.port.reads.isEmpty())
        assertFalse(File(f.directory, "${f.store.readPending()!!.sessionId}.clock-recovery.json").exists())
        f.owner.retry(); f.owner.onConnected(f.port.generation)
        f.observe(stopped(), listOf(finalRecord.copy(unixMs = 0)))
        f.owner.onTime(oldGeneration, SensorPacket.TimeStatus(true, f.clock.now,
            start.deviceUptimeMs + f.clock.now - start.deviceUnixMs, f.clock.now))
        assertTrue(f.port.reads.isEmpty())
        assertTrue(f.owner.state.busy)
        f.recoveryTimeReply(start)
        f.observe(stopped(), listOf(finalRecord.copy(unixMs = 0)))
        f.finishDownload()
        assertEquals(CollectionPage.COMPLETE, f.owner.state.page)
        assertEquals(1, f.port.count("start")); assertEquals(1, f.port.count("stop"))
    }

    @Test fun missingOrDamagedClockEvidenceKeepsDataUntilExplicitDiscardThenReopensReady() {
        for (damaged in listOf(false, true)) Fixture().use { f ->
            f.reachReference(unixMs = 0); f.saveReference("73", "valid", "")
            val id = f.store.readPending()!!.sessionId
            f.observe(stopped(), listOf(finalRecord.copy(unixMs = 0)))
            f.health(HealthMessage.DataChunk(0, payload.copyOf(13)))
            if (damaged) File(f.directory, "$id.clock-sync.json").writeText("damaged")
            val other = File(f.directory, "another-session.rfbin").apply { writeText("retained") }
            f.reopen(); f.owner.onConnected(f.port.generation)
            f.observe(stopped(), listOf(finalRecord.copy(unixMs = 0)))
            assertEquals(CollectionPage.ERROR, f.owner.state.page)
            assertEquals(73L, f.store.readPending()!!.reference!!.steps)
            assertArrayEquals(payload.copyOf(13), f.directory.listFiles()!!.single { it.extension == "part" }.readBytes())
            val readCount = f.port.reads.size
            f.owner.discardSession()
            f.reopen(); f.owner.onConnected(f.port.generation)
            f.observe(stopped(), listOf(finalRecord.copy(unixMs = 0)))
            assertNull(f.store.readPending())
            assertTrue(f.store.read(id)!!.isDiscarded)
            assertEquals(73L, f.store.read(id)!!.reference!!.steps) // Tombstone retains the audit, excluded from research.
            assertEquals("retained", other.readText())
            assertFalse(f.directory.listFiles()!!.any { it.name.startsWith(id) })
            assertEquals(readCount, f.port.reads.size)
            assertEquals(1, f.port.count("start")); assertEquals(1, f.port.count("stop"))
            assertTrue(f.owner.state.canStart)
        }
    }

    @Test fun cleanupSyncFailureAfterLocalCommitKeepsCompleteStateAndSavedFiles() = Fixture().use { f ->
        f.reachReference()
        f.saveReference("47", "valid", "")
        f.observe(stopped(), listOf(finalRecord))
        val before = requireNotNull(f.store.read())
        f.failDownloadSyncAfterLocalCommit = true
        f.health(HealthMessage.DataChunk(0, payload))
        f.health(HealthMessage.ReadEnd(payload.size.toLong(), true))
        f.observe(stopped(), listOf(finalRecord))
        assertFalse(f.failDownloadSyncAfterLocalCommit)
        assertTrue(f.errors.any { it.message == "注入已保存后的清理同步失败" })
        assertEquals(CollectionPage.COMPLETE, f.owner.state.page)
        assertTrue(f.owner.state.canStart)
        val saved = requireNotNull(f.store.read())
        assertEquals(before.sessionId, saved.sessionId)
        assertEquals(before.reference, saved.reference)
        assertEquals(before.stopConfirmedAtMs, saved.stopConfirmedAtMs)
        val raw = saved.localData!!.files.single()
        assertArrayEquals(payload, File(f.directory, raw.fileName).readBytes().drop(HealthRawV2.HEADER_SIZE).toByteArray())
        f.reopen()
        f.owner.onConnected(f.port.generation)
        f.observe(stopped(), listOf(finalRecord))
        assertEquals(saved, f.store.read())
        assertEquals(1, f.port.reads.size)
        assertTrue(f.owner.state.canStart)
    }

    @Test fun auxiliaryObservationFailureDoesNotBlockStartStopOrTheirDurableEvidence() = Fixture().use { f ->
        f.failObservation = true
        f.reachReference()
        assertTrue(f.errors.any { it.message == "注入诊断记录失败" })
        assertEquals(1, f.port.count("start"))
        assertEquals(1, f.port.count("stop"))
        assertEquals(CollectionPage.REFERENCE, f.owner.state.page)
        assertNotNull(f.store.read()!!.startConfirmedAtMs)
        assertNotNull(f.store.read()!!.stopConfirmedAtMs)
        assertEquals(finalRecord, f.store.read()!!.deviceRecordEvidence!!.record)
    }

    @Test fun aPendingCaptureCannotReleaseItsTransportOwner() = Fixture().use { f ->
        f.beginCollecting()
        val calls = f.port.calls.toList()
        assertFalse(f.owner.releaseIfIdle())
        assertEquals(calls, f.port.calls)
        assertTrue(f.owner.state.canStop)
        f.owner.stop()
        f.observe(stopped(), listOf(finalRecord))
        assertFalse(f.owner.releaseIfIdle())
        f.saveReference("51", "valid", "")
        f.observe(stopped(), listOf(finalRecord))
        val reads = f.port.reads.toList()
        assertFalse(f.owner.releaseIfIdle())
        assertEquals(reads, f.port.reads)
        assertNull(f.store.read()!!.localData)
    }

    @Test fun completedReleasedOwnerIgnoresStalePageIntentsAndCallbacks() = Fixture().use { f ->
        f.reachReference()
        f.saveReference("58", "valid", "")
        f.observe(stopped(), listOf(finalRecord))
        f.finishDownload()
        val saved = requireNotNull(f.store.read())
        val oldGeneration = f.port.generation
        assertTrue(f.owner.releaseIfIdle())
        val commands = f.port.calls.toList()
        f.startSelected()
        f.owner.stop()
        f.owner.retry()
        f.owner.reconnect()
        f.owner.enterReference()
        f.saveReference("999", "valid", "")
        f.owner.home()
        f.owner.onConnected(oldGeneration)
        f.owner.onHealth(oldGeneration, SensorPacket.Health(collecting(), ++f.clock.now))
        assertEquals(commands, f.port.calls)
        assertEquals(saved, f.store.read())
    }

    @Test fun automaticUploadWaitsForVerifiedLocalCompletionAndKeepsTheRecordPendingUntilReceipt() {
        val uploads = RecordingUploads()
        Fixture(uploads).use { f ->
            f.reachReference()
            val id = f.store.read()!!.sessionId
            assertTrue(f.owner.state.uploadAvailable)
            assertTrue(uploads.requests.isEmpty())
            f.saveReference("0", "valid", "")
            f.observe(stopped(), listOf(finalRecord))
            assertTrue(uploads.requests.isEmpty())
            f.finishDownload()
            assertEquals(listOf(id to false), uploads.requests)
            assertEquals(CollectionPage.COMPLETE, f.owner.state.page)
            assertEquals(SessionTransferStatus.PENDING, f.owner.state.session!!.transfer.status)
            assertNull(f.owner.state.session!!.transfer.receipt)
            assertTrue(f.owner.state.records.single().transferInFlight)
            assertTrue(f.owner.state.canStart)
            f.owner.refreshUploads()
            assertEquals(listOf(id to false), uploads.requests)
        }
    }

    @Test fun initializationQueuesOnlyCompleteRealPendingAndInterruptedTransfersIncludingArchives() {
        val uploads = RecordingUploads()
        Fixture(uploads).use { f ->
            val pending = f.seedLocal()
            val interrupted = f.seedLocal().also { f.store.markTransferStarted(it.sessionId) }
            val failed = f.seedLocal().also {
                f.store.markTransferStarted(it.sessionId)
                f.store.markTransferFailed(it.sessionId)
            }
            val complete = f.seedLocal().also {
                f.store.markTransferStarted(it.sessionId)
                f.store.completeTransfer(it.sessionId,
                    SessionTransferReceipt("test-receipt", ++f.clock.now, false, it.sessionId))
            }
            val simulated = f.seedLocal(simulated = true)
            val incomplete = f.store.requestStart(f.preparation.read()!!, ++f.clock.now, "Asia/Shanghai")
            f.owner.initialize()
            assertEquals(setOf(pending.sessionId to false, interrupted.sessionId to false), uploads.requests.toSet())
            assertEquals(2, uploads.requests.size)
            assertEquals(SessionTransferStatus.FAILED, f.store.read(failed.sessionId)!!.transfer.status)
            assertEquals(SessionTransferStatus.COMPLETE, f.store.read(complete.sessionId)!!.transfer.status)
            assertNull(f.store.read(incomplete.sessionId)!!.localData)
            assertFalse(f.owner.state.records.any { it.sessionId == simulated.sessionId })
            f.owner.retryUpload(simulated.sessionId)
            f.owner.retryUpload(incomplete.sessionId)
            f.owner.retryUpload(complete.sessionId)
            f.owner.retryUpload("unknown-session")
            assertEquals(2, uploads.requests.size)
        }
    }

    @Test fun homeHistoryIsScopedToCurrentIdentityWhileBackgroundUploadStillQueuesAllIdentities() {
        val uploads = RecordingUploads()
        Fixture(uploads).use { f ->
            val owner = f.seedLocal()
            val ownerProfile = f.preparation.read()!!
            val otherProfile = ownerProfile.copy(participantId = "other001", displayLabel = "other001")
            val other = f.seedLocal(profile = otherProfile)

            f.owner.initialize()

            assertEquals(listOf(owner.sessionId), f.owner.state.records.map { it.sessionId })
            assertNull(f.owner.state.session)
            assertEquals(setOf(owner.sessionId to false, other.sessionId to false), uploads.requests.toSet())
            uploads.inFlight.clear()
            f.owner.retryUpload(other.sessionId)
            assertEquals(2, uploads.requests.size)
            f.owner.retryUpload(owner.sessionId)
            assertEquals(owner.sessionId to true, uploads.requests.last())
        }
    }

    @Test fun pendingSessionUsesCurrentLocalNameOnlyForDisplay() {
        Fixture(profileLabel = "张三", identityType = PreparationIdentityType.LOCAL_NAME).use { f ->
            val local = f.preparation.read()!!
            f.store.requestStart(local, ++f.clock.now, "Asia/Shanghai", activity = SessionActivity.WALKING)

            f.owner.initialize()

            val pending = f.store.readPending()!!
            assertEquals(local.participantId, f.owner.state.participantId)
            assertEquals("张三", f.owner.state.participantLabel)
            assertEquals(pending.preparation.participantId, pending.preparation.displayLabel)
            assertFalse(File(f.directory, "session.json").readText(Charsets.UTF_8).contains("张三"))
        }
    }

    @Test fun archivedRetryAndUploadRefreshKeepTheActiveCaptureAndHomeNavigationIntact() {
        val uploads = RecordingUploads()
        Fixture(uploads).use { f ->
            val archive = f.seedLocal()
            f.store.markTransferStarted(archive.sessionId)
            f.store.markTransferFailed(archive.sessionId)
            f.beginCollecting()
            val current = f.store.read()!!
            val commands = f.port.calls.toList()
            assertTrue(uploads.requests.isEmpty())
            f.owner.retryUpload(archive.sessionId)
            f.owner.retryUpload(archive.sessionId)
            assertEquals(listOf(archive.sessionId to true), uploads.requests)
            assertEquals(current, f.owner.state.session)
            assertEquals(CollectionPage.COLLECTING, f.owner.state.page)
            assertTrue(f.owner.state.canStop)
            assertTrue(f.owner.state.records.single { it.sessionId == archive.sessionId }.transferInFlight)
            f.store.markTransferStarted(archive.sessionId)
            f.owner.refreshUploads()
            assertEquals(CollectionPage.COLLECTING, f.owner.state.page)
            assertEquals(commands, f.port.calls)

            f.owner.home()
            f.store.completeTransfer(archive.sessionId,
                SessionTransferReceipt("test-archived-receipt", ++f.clock.now, false, archive.sessionId))
            uploads.inFlight.remove(archive.sessionId)
            f.owner.refreshUploads()
            assertEquals(CollectionPage.HOME, f.owner.state.page)
            assertEquals(CollectionPage.COLLECTING, f.owner.state.taskPage)
            assertTrue(f.owner.state.canStop)
            assertNull(f.owner.state.error)
            assertEquals(current, f.owner.state.session)
            val record = f.owner.state.records.single { it.sessionId == archive.sessionId }
            assertEquals("complete", record.transferStatus)
            assertFalse(record.transferInFlight)
            assertEquals(commands, f.port.calls)
            f.owner.retryUpload(archive.sessionId)
            assertEquals(1, uploads.requests.size)
        }
    }

    @Test fun rejectedUploadSchedulingPreservesTheCompletedFileAndDoesNotEnterBleRecovery() {
        val uploads = RecordingUploads().apply { reject = true }
        Fixture(uploads).use { f ->
            f.reachReference()
            f.saveReference("19", "valid", "")
            f.observe(stopped(), listOf(finalRecord))
            f.health(HealthMessage.DataChunk(0, payload))
            f.health(HealthMessage.ReadEnd(payload.size.toLong(), true))
            f.observe(stopped(), listOf(finalRecord))
            val commands = f.port.calls.toList()
            val completed = f.store.read()!!
            assertEquals(CollectionPage.COMPLETE, f.owner.state.page)
            assertTrue(f.owner.state.canStart)
            assertNull(f.owner.state.error)
            assertEquals(19L, completed.reference!!.steps)
            assertTrue(File(f.directory, completed.localData!!.files.single().fileName).isFile)
            assertEquals(SessionTransferStatus.PENDING, completed.transfer.status)
            assertEquals(commands, f.port.calls)
            assertTrue(f.errors.any { it.message == "Injected upload scheduling failure" })
            f.owner.home()
            f.owner.retryUpload(completed.sessionId)
            assertEquals(CollectionPage.HOME, f.owner.state.page)
            assertNull(f.owner.state.error)
            assertEquals(commands, f.port.calls)
        }
    }

    @Test fun localFileReviewRefreshKeepsReferenceAndNavigationWithoutBleCommands() {
        val uploads = RecordingUploads()
        Fixture(uploads).use { f ->
            val saved = f.seedLocal()
            f.owner.initialize()
            val before = f.owner.state.page
            val commands = f.port.calls.toList()
            uploads.localReview += saved.sessionId
            f.owner.refreshUploads()
            assertTrue(f.owner.state.records.single().localReviewRequired)
            assertEquals(17L, f.owner.state.records.single().steps)
            assertEquals(before, f.owner.state.page)
            assertEquals(commands, f.port.calls)
            assertEquals(saved.reference, f.store.read(saved.sessionId)!!.reference)
            uploads.localReview.clear()
            f.owner.refreshUploads()
            assertFalse(f.owner.state.records.single().localReviewRequired)
        }
    }

    @Test fun endingAFailedStartKeepsTheSavedRecordAndReturnsHomeWithoutPretendingTheDeviceIsReady() = Fixture().use { f ->
        f.reachReference()
        f.saveReference("73", "valid", "")
        f.observe(stopped(), listOf(finalRecord))
        f.finishDownload()
        val saved = requireNotNull(f.store.read())
        f.owner.home()
        f.startSelected()
        f.observe(stopped(), listOf(finalRecord))
        f.observeUnconfirmedStart(stopped().copy(errorCode = -16), listOf(finalRecord))
        val request = requireNotNull(f.store.readPending())
        assertEquals("戒指返回异常，请重新连接后再试", f.owner.state.error)
        assertFalse(f.owner.state.canStart)
        assertTrue(f.owner.state.canEndStartAttempt)
        val oldConnection = f.port.generation
        f.owner.endStartAttempt("体验点击，未进行正式采集")
        assertTrue(f.owner.state.connecting)
        assertFalse(f.owner.state.canEndStartAttempt)
        assertTrue(f.port.generation > oldConnection)
        f.owner.onHealth(oldConnection, SensorPacket.Health(stopped(), ++f.clock.now))
        f.owner.onHealth(oldConnection, SensorPacket.Health(HealthMessage.ListEnd(0), ++f.clock.now))
        assertEquals(request, f.store.readPending())
        f.owner.onConnected(f.port.generation)
        f.observe(stopped().copy(errorCode = -16), listOf(finalRecord))
        assertNull(f.store.readPending())
        assertEquals(CollectionPage.HOME, f.owner.state.page)
        assertNull(f.owner.state.session)
        assertFalse(f.owner.state.canStart)
        assertFalse(f.owner.state.canEndStartAttempt)
        assertEquals(listOf(saved.sessionId), f.owner.state.records.map { it.sessionId })
        assertEquals(73L, f.owner.state.records.single().steps)
        assertEquals(2, f.port.count("start"))
        assertEquals(1, f.port.count("stop"))
        f.owner.refreshUploads()
        assertNull(f.owner.state.session)
        f.reopen()
        f.owner.onConnected(f.port.generation)
        f.observe(stopped(), listOf(finalRecord))
        assertTrue(f.owner.state.canStart)
        assertNull(f.owner.state.session)
        f.startSelected()
        f.observe(stopped(), listOf(finalRecord))
        assertNotEquals(request.sessionId, f.store.readPending()!!.sessionId)
        assertEquals(3, f.store.listSessions().size)
    }

    @Test fun aNewRecordOrDisconnectDuringEndAttemptPreservesTheRequest() = Fixture().use { f ->
        f.connectReady()
        f.startSelected()
        f.observe(idle())
        f.observeUnconfirmedStart(idle().copy(errorCode = -16))
        val request = f.store.readPending()
        f.owner.endStartAttempt("仅检查操作")
        f.owner.onConnected(f.port.generation)
        f.observe(collecting(), listOf(initialRecord))
        assertEquals(request, f.store.readPending())
        assertEquals(CollectionPage.RECOVERY, f.owner.state.page)
        assertFalse(f.owner.state.canStart)
        f.owner.endStartAttempt("仅检查操作")
        f.owner.onDisconnected(f.port.generation, "测试连接中断")
        assertEquals(request, f.store.readPending())
        assertFalse(f.owner.state.canEndStartAttempt)
        assertEquals(0, f.port.count("stop"))
    }

    @Test fun idleDeviceErrorGetsOnlyThreeReadOnlyChecksAndNeverStartsOrChangesTheJournal() = Fixture().use { f ->
        val saved = f.seedLocal()
        val before = File(f.directory, "session.json").readBytes()
        f.owner.initialize()
        assertTrue("Legacy completed sessions must not fail restart cleanup: ${f.errors}", f.errors.isEmpty())
        f.owner.onConnected(f.port.generation)
        repeat(3) { index ->
            f.observe(stopped().copy(errorCode = -16), listOf(finalRecord))
            assertFalse(f.owner.state.canStart)
            if (index < 2) {
                assertTrue(f.owner.state.checkingDevice)
                assertTrue(f.owner.state.busy)
                assertFalse(f.owner.state.canRetry)
                val count = f.port.calls.size
                f.startSelected(); f.owner.retry()
                assertEquals(count, f.port.calls.size)
                f.runReadinessWait()
            }
        }
        assertFalse(f.owner.state.busy)
        assertFalse(f.owner.state.checkingDevice)
        assertTrue(f.owner.state.canRetry)
        assertEquals(3, f.port.count("status"))
        assertEquals(3, f.port.count("list"))
        assertEquals(0, f.port.count("start"))
        assertEquals(0, f.port.count("stop"))
        assertTrue(f.port.reads.isEmpty())
        assertEquals(listOf(-16, -16, -16), f.observations.map { it.status.errorCode })
        assertEquals(saved, f.store.read())
        assertArrayEquals(before, File(f.directory, "session.json").readBytes())
        assertFalse(f.hasReadinessWait())
        val oldConnection = f.port.generation
        f.owner.retry()
        assertTrue(f.owner.state.connecting)
        assertTrue(f.port.generation > oldConnection)
        f.owner.onConnected(f.port.generation)
        assertEquals(4, f.port.count("status"))
        f.observe(stopped(), listOf(finalRecord))
        // This legacy fixture has no durable device fingerprint; preserve the unassigned record first.
        assertTrue(f.owner.state.preservingExisting)
        f.finishDownload()
        f.owner.onConnected(f.port.generation)
        f.observe(stopped(), listOf(finalRecord)); f.observe(stopped(), listOf(finalRecord))
        assertTrue(f.owner.state.canStart)
        assertEquals(saved, f.store.read())
        assertArrayEquals(before, File(f.directory, "session.json").readBytes())
    }

    @Test fun aClearedErrorRequiresTheWholeUnchangedSnapshotBeforeReadiness() = Fixture().use { f ->
        f.owner.initialize(); f.owner.onConnected(f.port.generation)
        f.observe(idle().copy(errorCode = -16))
        f.runReadinessWait()
        f.health(idle())
        assertFalse(f.owner.state.canStart)
        f.health(HealthMessage.ListEnd(0))
        assertTrue(f.owner.state.canStart)
        assertFalse(f.owner.state.checkingDevice)
        assertNull(f.owner.state.error)
        assertEquals(listOf(-16, 0), f.observations.map { it.status.errorCode })
        assertNull(f.store.read())
        assertEquals(0, f.port.count("start"))
    }

    @Test fun changedRecordsOrCollectingDuringRecheckCannotBeAcceptedAsRecovery() {
        listOf(stopped(), stopped().copy(collecting = true)).forEach { next ->
            Fixture().use { f ->
                f.owner.initialize(); f.owner.onConnected(f.port.generation)
                f.observe(idle().copy(errorCode = -16))
                f.runReadinessWait()
                f.observe(next, listOf(finalRecord))
                assertFalse(f.owner.state.canStart)
                assertFalse(f.owner.state.checkingDevice)
                assertFalse(f.hasReadinessWait())
                assertEquals(0, f.port.count("start"))
                assertNull(f.store.read())
            }
        }
    }

    @Test fun disconnectedOrClosedOwnersCannotRunAnOldReadinessCheck() {
        for (close in listOf(false, true)) Fixture().use { f ->
            f.owner.initialize(); f.owner.onConnected(f.port.generation)
            f.observe(idle().copy(errorCode = -16))
            if (close) f.owner.close() else {
                f.owner.onDisconnected(f.port.generation, "测试断连")
                f.owner.reconnect()
                f.owner.onConnected(f.port.generation)
                f.observe(idle())
            }
            val before = f.port.calls.toList()
            f.runReadinessWait()
            assertEquals(before, f.port.calls)
            assertNull(f.store.read())
        }
    }

    @Test fun delayedStartPreventsRetryAndReleaseAndCloseCancelsTheCommand() = Fixture(deferCaptureWaits = true).use { f ->
        f.connectReady()
        f.startSelected(); f.observe(idle())
        assertTrue(f.owner.state.busy)
        assertFalse(f.owner.state.canRetry)
        assertFalse(f.owner.canReleaseIfIdle())
        val commands = f.port.calls.toList()
        f.owner.retry(); f.startSelected()
        assertEquals(commands, f.port.calls)
        f.owner.close()
        f.runDelay(500)
        assertEquals(0, f.port.count("start"))
        assertNotNull(f.store.readPending())
    }

    @Test fun phoneClockSyncCompletesBeforeStartAndBindsTheDurableSession() = Fixture(syncClock = true).use { f ->
        f.connectReady()
        assertEquals(0, f.port.count("time"))
        f.startSelected(); f.startSelected()
        assertEquals(0, f.port.count("time"))
        f.observe(idle()) // Fresh idle check before changing device time.
        assertEquals(1, f.port.count("time"))
        assertEquals(0, f.port.count("start"))
        assertNull(f.store.readPending())
        assertFalse(f.owner.canReleaseIfIdle())
        assertFalse(f.owner.state.canRetry)
        f.timeReply()
        assertEquals(1, f.clockEvidence.size)
        assertNull(f.clockEvidence.single().second)
        f.observe(idle()) // Post-sync unchanged record snapshot.
        f.observe(idle()) // Capture preflight is still required.
        assertEquals(1, f.port.count("start"))
        assertEquals(f.store.readPending()!!.sessionId, f.clockEvidence.last().second)
        assertEquals(f.clockEvidence.first().first, f.clockEvidence.last().first)
        f.observe(collecting(), listOf(initialRecord))
        f.owner.stop(); f.observe(stopped(), listOf(finalRecord))
        f.saveReference("0", "valid", ""); f.observe(stopped(), listOf(finalRecord)); f.finishDownload()
        assertEquals(0L, f.store.read()!!.reference!!.steps)
        f.reopen()
        assertEquals(1, f.port.count("time")) // Recovery/opening never writes the clock.
        assertNotNull(f.store.read()!!.localData)
    }

    @Test fun unsolicitedTimeDuringIdlePrecheckCannotReplaceTheClockWrite() = Fixture(syncClock = true).use { f ->
        f.connectReady(); f.startSelected()
        f.timeReply()
        assertTrue(f.clockEvidence.isEmpty())
        assertEquals(0, f.port.count("time"))
        assertEquals(0, f.port.count("start"))
        f.observe(idle())
        assertEquals(1, f.port.count("time"))
        assertTrue(f.clockEvidence.isEmpty())
        assertEquals(0, f.port.count("start"))
        f.timeReply()
        assertEquals(1, f.clockEvidence.size)
        f.observe(idle()); f.observe(idle())
        assertEquals(1, f.port.count("start"))
    }

    @Test fun clockTimeoutAndLateReplyNeverIssueStart() = Fixture(syncClock = true).use { f ->
        f.connectReady(); f.startSelected(); f.observe(idle())
        f.clock.elapsed += PhoneClockSync.TIMEOUT_MS
        f.runDelay(PhoneClockSync.TIMEOUT_MS)
        assertEquals(CollectionPage.RECOVERY, f.owner.state.page)
        assertTrue(f.owner.state.canRetry)
        f.timeReply()
        assertEquals(0, f.port.count("start"))
        assertNull(f.store.readPending())
        f.owner.retry()
        assertTrue(f.owner.state.connecting)
    }

    @Test fun rejectedClockReplyOrWriteFailureKeepsStartUnsent() {
        for (mode in listOf("unsynced", "clock-jump", "storage", "binding")) Fixture(syncClock = true).use { f ->
            f.connectReady(); f.startSelected(); f.observe(idle())
            if (mode == "clock-jump") f.clock.now += 5000
            if (mode == "storage") f.failClockEvidence = true
            f.timeReply(synced = mode != "unsynced")
            if (mode == "binding") {
                f.failClockEvidence = true
                f.observe(idle()); f.observe(idle())
            }
            assertEquals("mode=$mode", 0, f.port.count("start"))
            assertEquals(CollectionPage.ERROR, f.owner.state.page)
            assertFalse(f.owner.state.canStart)
            assertTrue("Failed clock persistence must leave a recovery action", f.owner.state.canRetry)
            if (mode == "binding") {
                f.failClockEvidence = false
                f.owner.retry(); f.owner.onConnected(f.port.generation)
                f.observe(idle())
                assertEquals(0, f.port.count("start"))
                assertEquals(CollectionPage.HOME, f.owner.state.page)
                assertTrue(f.owner.state.canStart)
                assertFalse(f.owner.state.canEndStartAttempt)
                assertNull(f.store.readPending())
                assertNotNull(f.store.read()!!.startAttemptArchive)
            }
        }
    }

    @Test fun changedRecordsAfterClockSyncCannotAuthorizeStart() = Fixture(syncClock = true).use { f ->
        f.connectReady(); f.startSelected(); f.observe(idle()); f.timeReply()
        f.observe(stopped(), listOf(finalRecord))
        assertEquals(CollectionPage.RECOVERY, f.owner.state.page)
        assertFalse(f.owner.state.canStart)
        assertEquals(0, f.port.count("start"))
        assertNull(f.store.readPending())
    }

    @Test fun disconnectOrCloseInvalidatesOutstandingClockReply() {
        for (close in listOf(false, true)) Fixture(syncClock = true).use { f ->
            f.connectReady(); f.startSelected(); f.observe(idle())
            if (close) f.owner.close() else f.owner.onDisconnected(f.port.generation, "断连")
            f.timeReply()
            assertEquals(0, f.port.count("start"))
            assertTrue(f.clockEvidence.isEmpty())
            assertNull(f.store.readPending())
        }
    }

    @Test fun clockWriteRequiresFreshIdleAndUnsolicitedCollectionInvalidatesReadiness() {
        for (unsolicited in listOf(false, true)) Fixture(syncClock = true).use { f ->
            f.connectReady()
            if (unsolicited) f.health(collecting())
            f.startSelected()
            if (!unsolicited) f.observe(collecting(), listOf(initialRecord))
            assertFalse(f.owner.state.canStart)
            assertEquals(0, f.port.count("time"))
            assertEquals(0, f.port.count("start"))
        }
    }

    @Test fun phoneClockChangeAfterSyncBlocksPreflightAndActualStart() {
        for (duringWait in listOf(false, true)) Fixture(syncClock = true, deferCaptureWaits = true).use { f ->
            f.connectReady(); f.startSelected(); f.observe(idle()); f.timeReply(); f.observe(idle())
            if (!duringWait) f.clock.now += 5_000
            f.observe(idle())
            if (duringWait) {
                f.clock.now += 5_000
                f.runDelay(500)
            }
            assertEquals(0, f.port.count("start"))
            assertTrue(f.owner.state.canRetry)
        }
    }

    @Test fun expiredClockEvidenceCannotStartAfterSlowPreflight() = Fixture(syncClock = true).use { f ->
        f.connectReady(); f.startSelected(); f.observe(idle()); f.timeReply(); f.observe(idle())
        f.clock.now += PhoneClockSync.MAX_START_AGE_MS + 1
        f.clock.elapsed += PhoneClockSync.MAX_START_AGE_MS + 1
        f.observe(idle())
        assertEquals(0, f.port.count("start"))
        assertTrue(f.owner.state.canRetry)
    }

    @Test fun pendingCaptureKeepsReconnectingAfterRepeatedBluetoothUnavailableResults() = Fixture().use { f ->
        f.beginCollecting()
        val original = f.store.readPending()!!
        f.owner.onDisconnected(f.port.generation, "手机蓝牙已关闭")
        repeat(6) { attempt ->
            f.port.accept = {
                if (it == "connect" && attempt % 2 == 1) throw SecurityException("Injected permission loss")
                it != "connect"
            }
            f.runDelay(3_000)
            assertFalse(f.owner.state.connecting)
            assertTrue(f.owner.state.canRetry)
            assertEquals(original.sessionId, f.store.readPending()!!.sessionId)
        }
        f.port.accept = { true }
        f.runDelay(3_000)
        f.owner.onConnected(f.port.generation)
        f.observe(collecting(), listOf(initialRecord))
        assertEquals(CollectionPage.COLLECTING, f.owner.state.page)
        assertEquals(original.sessionId, f.store.readPending()!!.sessionId)
        assertEquals(1, f.port.count("start"))
        assertEquals(0, f.port.count("stop"))
    }

    @Test fun pendingCaptureKeepsReconnectingBeyondThreeConnectionTimeouts() = Fixture().use { f ->
        f.beginCollecting()
        val id = f.store.readPending()!!.sessionId
        f.owner.onDisconnected(f.port.generation, "戒指暂时不在附近")
        repeat(6) {
            f.runDelay(3_000)
            assertTrue(f.owner.state.connecting)
            f.runAllDelays(30_000)
            assertFalse(f.owner.state.connected)
            assertFalse(f.owner.state.connecting)
            assertEquals(id, f.store.readPending()!!.sessionId)
        }
        f.runDelay(3_000)
        f.owner.onConnected(f.port.generation)
        f.observe(collecting(), listOf(initialRecord))
        assertEquals(CollectionPage.COLLECTING, f.owner.state.page)
        assertEquals(1, f.port.count("start"))
        assertEquals(0, f.port.count("stop"))
    }

    @Test fun interruptedDownloadPausesAfterRepeatedConnectionFailuresAndResumesSavedZeroOnRetry() = Fixture().use { f ->
        f.reachReference()
        f.saveReference("0", "valid", "")
        f.observe(stopped(), listOf(finalRecord))
        val id = f.store.readPending()!!.sessionId
        val originalReference = f.store.readPending()!!.reference
        f.health(HealthMessage.DataChunk(0, payload.copyOf(13)))
        f.owner.onDisconnected(f.port.generation, "手机蓝牙已关闭")
        f.port.accept = { it != "connect" }
        repeat(RealCollectionController.DOWNLOAD_RECOVERY_LIMIT - 1) { f.runDelay(3_000) }
        assertEquals(0, f.waitCount(3_000))
        assertTrue(f.owner.state.canRetry)
        assertFalse(f.owner.state.busy)
        assertArrayEquals(payload.copyOf(13), f.directory.listFiles()!!.single { it.extension == "part" }.readBytes())
        f.port.accept = { true }
        f.owner.retry()
        f.owner.onConnected(f.port.generation)
        f.observe(stopped(), listOf(finalRecord))
        assertEquals(13L, f.port.reads.last().offset)
        f.health(HealthMessage.DataChunk(13, payload.copyOfRange(13, payload.size)))
        f.health(HealthMessage.ReadEnd(payload.size.toLong(), true))
        f.observe(stopped(), listOf(finalRecord))
        assertEquals(CollectionPage.COMPLETE, f.owner.state.page)
        assertEquals(id, f.store.read()!!.sessionId)
        assertEquals(originalReference, f.store.read()!!.reference)
        assertEquals(0L, f.store.read()!!.reference!!.steps)
        assertEquals(1, f.port.count("start"))
        assertEquals(1, f.port.count("stop"))
        val connections = f.port.count("connect")
        f.owner.onDisconnected(f.port.generation, "完成后断开")
        assertEquals(0, f.waitCount(3_000))
        assertEquals(connections, f.port.count("connect"))
    }

    @Test fun duplicateDisconnectAndRetiredReadyCallbacksCannotCreateParallelRecovery() = Fixture().use { f ->
        f.beginCollecting()
        val previousGeneration = f.port.generation
        repeat(8) { f.owner.onDisconnected(previousGeneration, "连接中断") }
        assertEquals(1, f.waitCount(3_000))
        f.owner.onConnected(previousGeneration)
        f.owner.onHealth(previousGeneration, SensorPacket.Health(stopped(), ++f.clock.now))
        assertFalse(f.owner.state.connected)
        f.owner.reconnect()
        val connections = f.port.count("connect")
        f.runAllDelays(3_000)
        f.owner.onDisconnected(previousGeneration, "迟到的旧连接回调")
        assertEquals(connections, f.port.count("connect"))
        f.owner.onConnected(f.port.generation)
        f.observe(collecting(), listOf(initialRecord))
        assertEquals(CollectionPage.COLLECTING, f.owner.state.page)
        assertEquals(1, f.port.count("start"))
    }

    @Test fun idleOrClosedOwnerDoesNotContinueAutomaticConnections() = Fixture().use { f ->
        f.port.accept = { it != "connect" }
        f.owner.initialize()
        assertTrue(f.owner.state.canRetry)
        assertEquals(0, f.waitCount(3_000))
        f.port.accept = { true }
        f.owner.reconnect()
        f.owner.onConnected(f.port.generation)
        f.observe(idle())
        f.startSelected(); f.observe(idle()); f.observe(collecting(), listOf(initialRecord))
        f.owner.onDisconnected(f.port.generation, "连接中断")
        f.owner.close()
        val connections = f.port.count("connect")
        f.runAllDelays(3_000)
        assertEquals(connections, f.port.count("connect"))
    }

    private inner class Fixture(private val uploads: RealUploadPort? = null,
        private val deferCaptureWaits: Boolean = false, private val syncClock: Boolean = false,
        private val preserveUnassignedExisting: Boolean = true,
        profileLabel: String = "owner001",
        identityType: PreparationIdentityType = PreparationIdentityType.RESEARCH_ID) : AutoCloseable {
        val directory = temporary.newFolder()
        var failCommit = false
        var failObservation = false
        var failDownloadSyncAfterLocalCommit = false
        var failClockEvidence = false
        var failRecoveryEvidence = false
        val clockEvidence = mutableListOf<Pair<PhoneClockSyncEvidence, String?>>()
        val preparation = PreparationStore(File(directory, "profile")) { source, target -> replace(source, target) }.apply {
            register(profileLabel, RingPlacement.LEFT_INDEX, identityType)
            selectRing(ring)
        }
        val store = FreeLivingSessionStore(File(directory, "session.json"), { source, target ->
            if (failCommit) throw IOException("注入保存失败")
            replace(source, target)
        }, {})
        val clock = TestClock(epoch)
        val port = RecordingPort()
        private val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
        val errors = mutableListOf<Exception>()
        val observations = mutableListOf<HealthRecordObservation>()
        var owner = createOwner()

        private fun createOwner() = RealCollectionController(directory, preparation, store, port,
            // Timing is covered by dedicated virtual-time cases; existing workflow tests complete these waits immediately.
            CollectionScheduler { delay, action ->
                if (!deferCaptureWaits && delay in setOf(500L, 1_000L, 5_000L)) action() else scheduled += delay to action
            }, clock,
            recordObservation = { if (failObservation) throw IOException("注入诊断记录失败") else observations += it },
            reportError = { errors += it },
            downloadFactory = { location, session, record -> RealSessionDownload(location, session.sessionId,
                session.preparation.ring!!.address, record, session.startedAtMs ?: 0L, session.endedAtMs ?: 0L, {
                    if (failDownloadSyncAfterLocalCommit && store.read()?.localData != null) {
                        failDownloadSyncAfterLocalCommit = false
                        throw IOException("注入已保存后的清理同步失败")
                    }
                }) }, uploads = uploads,
            backups = DeviceRecordBackupStore(File(directory, "device-backups"), {}),
            preserveUnassignedExisting = preserveUnassignedExisting, syncClockBeforeStart = syncClock,
            saveClockEvidence = { evidence, sessionId ->
                if (failClockEvidence) throw IOException("校时证据保存失败")
                clockEvidence += evidence to sessionId
            }, saveRecoveryEvidence = { session, start, observed, requested, sent, received, reply ->
                if (failRecoveryEvidence) throw IOException("恢复依据保存失败")
                StoppedRecordClockRecovery.save(directory, session, start, observed, requested, sent, received, reply, {})
            })

        fun seedRecoveryClock(): PhoneClockSyncEvidence {
            val session = store.readPending()!!
            val received = session.startRequestedAtMs - 1
            val evidence = PhoneClockSyncEvidence(java.util.UUID.randomUUID().toString(), ring.address,
                session.startCommandDispatch!!.connectionGeneration!!, received - 10, received,
                1_000, 1_010, received - 5, initialRecord.uptimeMs - 100)
            PhoneClockSync.save(directory, evidence, session.sessionId, {})
            return evidence
        }

        fun recoveryTimeReply(start: PhoneClockSyncEvidence, synced: Boolean = true, uptime: Long? = null,
            clockJump: Long = 0, deviceLag: Long = 0) {
            clock.now += 10; clock.elapsed += 10
            val unix = clock.now - 5 - deviceLag
            clock.now += clockJump
            owner.onTime(port.generation, SensorPacket.TimeStatus(synced, unix,
                uptime ?: (start.deviceUptimeMs + unix - start.deviceUnixMs), clock.now))
        }

        fun timeReply(synced: Boolean = true) {
            clock.now += 10; clock.elapsed += 10
            owner.onTime(port.generation, SensorPacket.TimeStatus(synced, clock.now - 5, 500, clock.now))
        }

        fun seedLocal(
            simulated: Boolean = false,
            profile: PreparationSnapshot = preparation.read()!!,
        ): FreeLivingSession {
            val session = store.requestStart(profile, ++clock.now, "Asia/Shanghai")
            store.confirmStart(session.sessionId, ring.address, collecting(), ++clock.now)
            store.requestStop(session.sessionId, ++clock.now)
            store.confirmStop(session.sessionId, ring.address, stopped(), ++clock.now)
            store.saveReference(session.sessionId, SessionReference(ReferenceStatus.VALID, 17, ++clock.now))
            val file = File(directory, "${session.sessionId}-upload-fixture.rfbin")
            file.writeBytes(payload)
            val hash = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it.toInt() and 255) }
            return store.completeLocalData(session.sessionId,
                listOf(SessionRawFile(file.name, 7, file.length(), hash, simulated)), ++clock.now)
        }

        fun reopen() { owner.close(); owner = createOwner(); owner.initialize() }

        fun waitCount(delay: Long) = scheduled.count { it.first == delay }
        fun hasReadinessWait() = scheduled.any { it.first == RealCollectionController.READINESS_CHECK_INTERVAL_MS }
        fun runReadinessWait() = runDelay(RealCollectionController.READINESS_CHECK_INTERVAL_MS)
        fun runDelay(delay: Long) {
            val index = scheduled.indexOfFirst { it.first == delay }
            check(index >= 0) { "Expected a scheduled wait" }
            scheduled.removeAt(index).second()
        }

        fun runAllDelays(delay: Long) {
            val due = scheduled.filter { it.first == delay }
            check(due.isNotEmpty()) { "Expected scheduled waits" }
            scheduled.removeAll(due.toSet())
            due.forEach { it.second() }
        }

        fun connectReady() {
            owner.initialize()
            owner.onConnected(port.generation)
            observe(idle())
            assertTrue("Idle inspection should permit start; errors=$errors", owner.state.canStart)
        }

        fun beginCollecting(record: HealthMessage.ListItem = initialRecord) {
            connectReady()
            startSelected()
            observe(idle())
            observe(collecting(), listOf(record))
            assertEquals("errors=$errors", CollectionPage.COLLECTING, owner.state.page)
        }

        fun reachReference(unixMs: Long = epoch) {
            beginCollecting(initialRecord.copy(unixMs = unixMs))
            clock.now += 60_000
            owner.stop()
            observe(stopped(), listOf(finalRecord.copy(unixMs = unixMs)))
            assertEquals("errors=$errors", CollectionPage.FINISH, owner.state.page)
            owner.chooseFinish(true)
            assertEquals("errors=$errors", CollectionPage.REFERENCE, owner.state.page)
        }

        fun startSelected() { owner.selectActivity(SessionActivity.WALKING); owner.start() }

        fun beginUnconfirmedZeroTimeStart(keepOld: Boolean = false, empty: Boolean = false): HealthMessage.ListItem {
            val old = finalRecord.copy(unixMs = 0)
            owner.initialize(); owner.onConnected(port.generation); observe(stopped(), listOf(old))
            finishDownload(); observe(stopped(), listOf(old))
            startSelected(); observe(stopped(), listOf(old)); timeReply()
            observe(stopped(), listOf(old)); observe(stopped(), listOf(old))
            if (deferCaptureWaits) { runDelay(500); runDelay(1_000) }
            val initial = initialRecord.copy(sessionId = if (keepOld) 8 else 7, uptimeMs = 1_000, unixMs = 0,
                bytes = if (empty) 0 else initialRecord.bytes, records = if (empty) 0 else initialRecord.records)
            observe(collecting().copy(sessionId = initial.sessionId, bytes = initial.bytes, records = initial.records),
                if (keepOld) listOf(old, initial) else listOf(initial))
            assertTrue("errors=$errors; state=${owner.state}", owner.state.canStopUnconfirmedStart)
            assertNull(store.readPending()!!.startConfirmedAtMs)
            assertEquals(1, port.count("start"))
            return initial
        }

        fun saveReference(steps: String, status: String, reason: String) {
            if (owner.state.page == CollectionPage.FINISH) owner.chooseFinish(true)
            owner.saveReference(steps, status, reason)
        }

        fun observe(status: HealthMessage.Status, records: List<HealthMessage.ListItem> = emptyList(), reason: Int? = null) {
            owner.onHealth(port.generation, SensorPacket.Health(status, ++clock.now, reason))
            records.forEach(::health)
            health(HealthMessage.ListEnd(records.size))
            if (!deferCaptureWaits && !status.collecting &&
                store.readPending()?.phase == FreeLivingSessionPhase.STOP_REQUESTED &&
                owner.state.page == CollectionPage.STOPPING) {
                owner.onHealth(port.generation, SensorPacket.Health(status, ++clock.now, reason))
                records.forEach(::health)
                health(HealthMessage.ListEnd(records.size))
            }
        }

        fun battery(charge: Int? = 0) = owner.onBattery(port.generation, SensorPacket.Battery(4100, 100, charge, ++clock.now))

        fun observeUnconfirmedStart(status: HealthMessage.Status, records: List<HealthMessage.ListItem> = emptyList()) {
            repeat(3) { attempt ->
                observe(status, records)
                if (attempt < 2) runDelay(1_500)
            }
        }

        fun health(message: HealthMessage) {
            owner.onHealth(port.generation, SensorPacket.Health(message, ++clock.now))
        }

        fun finishDownload() {
            health(HealthMessage.DataChunk(0, payload))
            health(HealthMessage.ReadEnd(payload.size.toLong(), true))
            store.readPending()?.takeIf { it.reference != null && it.localData == null }?.deviceRecordEvidence?.let { evidence ->
                observe(evidence.status, listOf(evidence.record))
            }
            assertTrue("No processing errors expected: $errors", errors.isEmpty())
        }

        override fun close() = owner.close()
    }

    private data class Read(val sessionId: Int, val offset: Long, val length: Int)

    private class RecordingUploads : RealUploadPort {
        val requests = mutableListOf<Pair<String, Boolean>>()
        val inFlight = mutableSetOf<String>()
        val localReview = mutableSetOf<String>()
        var reject = false
        var configured = true
        override val available get() = configured
        override fun enqueue(sessionId: String, retry: Boolean) {
            check(!reject) { "Injected upload scheduling failure" }
            requests += sessionId to retry
            inFlight += sessionId
        }
        override fun isInFlight(sessionId: String) = sessionId in inFlight
        override fun needsLocalReview(sessionId: String) = sessionId in localReview
    }

    private class RecordingPort : RealCollectionPort {
        var generation = 0L
        val calls = mutableListOf<String>()
        val reads = mutableListOf<Read>()
        var beforeRead: () -> Unit = {}
        var accept: (String) -> Boolean = { true }
        override fun connect(ring: PreparedRing, generation: Long): Boolean {
            this.generation = generation
            return send("connect")
        }
        override fun disconnect() { calls += "disconnect" }
        override fun queryStatus() = send("status")
        override fun queryBattery() = send("battery")
        override fun syncTime(unixMs: Long) = send("time")
        override fun queryTime() = send("time_get")
        override fun queryRecords() = send("list")
        override fun start() = send("start")
        override fun stop() = send("stop")
        override fun read(sessionId: Int, offset: Long, length: Int): Boolean {
            beforeRead()
            reads += Read(sessionId, offset, length)
            return send("read")
        }
        fun count(command: String) = calls.count { it == command }
        private fun send(command: String): Boolean { calls += command; return accept(command) }
    }

    private class TestClock(var now: Long) : CaptureClock {
        var elapsed = 10_000L
        override fun nowElapsedMs() = elapsed
        override fun nowEpochMs() = now
        override fun timeZoneId() = "Asia/Shanghai"
    }

    private fun replace(source: File, target: File) {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun imu(count: Int, uptime: Long): ByteArray = ByteArrayOutputStream().apply {
        write(0x32); write(0x12); write(count)
        repeat(4) { write((uptime ushr (it * 8)).toInt()) }
        repeat(count * 6) { write(it + 1) }
    }.toByteArray()
}
