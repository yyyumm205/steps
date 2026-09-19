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

    @Test fun aCompleteInspectionIsRequiredBeforeStartAndStartRequiresDeviceConfirmation() = Fixture().use { f ->
        f.owner.initialize()
        assertTrue(f.owner.state.connecting)
        assertFalse(f.owner.state.canStart)
        f.owner.start()
        assertEquals(0, f.port.count("start"))
        f.owner.onConnected(f.port.generation)
        f.health(idle())
        assertFalse(f.owner.state.canStart)
        f.health(HealthMessage.ListEnd(0))
        assertTrue(f.owner.state.canStart)
        f.owner.start()
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
        f.owner.saveReference("0", "valid", "")
        assertEquals(0L, f.store.read()!!.reference!!.steps)
        assertTrue(f.port.reads.isEmpty())
        f.observe(stopped(), listOf(finalRecord))
        assertEquals(listOf(Read(7, 0, 16_384)), f.port.reads)
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
        f.owner.saveReference("999", "missing", "计步器意外清零")
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
        f.owner.saveReference("562", "valid", "")
        assertEquals(before, f.store.read())
        assertEquals(CollectionPage.REFERENCE, f.owner.state.page)
        assertNotNull(f.owner.state.error)
        assertTrue(f.port.reads.isEmpty())
        assertFalse(f.owner.state.canStart)
        f.failCommit = false
        f.owner.saveReference("562", "valid", "")
        assertEquals(before.sessionId, f.store.read()!!.sessionId)
        assertEquals(562L, f.store.read()!!.reference!!.steps)
        f.observe(stopped(), listOf(finalRecord))
        assertEquals(1, f.port.reads.size)
    }

    @Test fun rejectedStopPreservesAnUnreliableReadingWithoutInventingAnEndOrDownload() = Fixture().use { f ->
        f.beginCollecting()
        val id = f.store.read()!!.sessionId
        f.port.accept = { it != "stop" }
        f.owner.stop()
        assertEquals(1, f.port.count("stop"))
        assertEquals(FreeLivingSessionPhase.STOP_REQUESTED, f.store.read()!!.phase)
        assertNull(f.store.read()!!.stopConfirmedAtMs)
        f.owner.enterReference()
        f.owner.saveReference("73", "unreliable", "尚未确认结束")
        val saved = requireNotNull(f.store.read())
        assertEquals(id, saved.sessionId)
        assertEquals(73L, saved.reference!!.steps)
        assertEquals(ReferenceStatus.UNRELIABLE, saved.reference!!.status)
        assertNull(saved.stopConfirmedAtMs)
        assertNull(saved.endedAtMs)
        assertNull(saved.localData)
        assertTrue(f.port.reads.isEmpty())
        assertFalse(f.owner.state.canStart)
    }

    @Test fun homeAndRetryOnlyShowTheCurrentTaskWithoutRepeatingStartOrStop() = Fixture().use { f ->
        f.beginCollecting()
        val id = f.store.read()!!.sessionId
        f.owner.home()
        assertEquals(CollectionPage.HOME, f.owner.state.page)
        assertEquals(CollectionPage.COLLECTING, f.owner.state.taskPage)
        f.owner.retry()
        f.owner.start()
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
        f.owner.saveReference("562", "valid", "")
        f.observe(stopped(), listOf(finalRecord))
        val before = requireNotNull(f.store.read())
        f.health(HealthMessage.DataChunk(0, payload.copyOf(13)))
        f.owner.onDisconnected(f.port.generation, "连接中断")
        assertNull(f.store.read()!!.localData)
        assertFalse(f.owner.state.canStart)
        f.reopen()
        f.owner.onConnected(f.port.generation)
        f.observe(stopped(), listOf(finalRecord))
        assertEquals(Read(7, 13, 16_384), f.port.reads.last())
        assertEquals(before.sessionId, f.store.read()!!.sessionId)
        assertEquals(before.reference, f.store.read()!!.reference)
        f.health(HealthMessage.DataChunk(9, payload.copyOfRange(9, payload.size)))
        f.health(HealthMessage.ReadEnd(payload.size.toLong(), true))
        assertEquals(CollectionPage.COMPLETE, f.owner.state.page)
        assertEquals(1, f.directory.listFiles()!!.count { it.extension == "rfbin" })
        val final = f.store.read()!!.localData!!.files.single()
        assertArrayEquals(payload, File(f.directory, final.fileName).readBytes().drop(64).toByteArray())
        assertEquals(1, f.port.count("start"))
        assertEquals(1, f.port.count("stop"))
    }

    @Test fun changedRecordCannotBeDownloadedUnderTheOriginalReference() = Fixture().use { f ->
        f.reachReference()
        f.owner.saveReference("71", "valid", "")
        val before = requireNotNull(f.store.read())
        f.observe(stopped(), listOf(finalRecord.copy(unixMs = epoch + 1)))
        assertTrue(f.port.reads.isEmpty())
        assertEquals(before, f.store.read())
        assertEquals(CollectionPage.ERROR, f.owner.state.page)
        assertFalse(f.owner.state.canStart)
    }

    @Test fun prematureDownloadEndPreservesPartialAndCannotBecomeComplete() = Fixture().use { f ->
        f.reachReference()
        f.owner.saveReference("0", "valid", "")
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
        f.owner.saveReference("22", "valid", "")
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
        assertEquals(CollectionPage.COMPLETE, f.owner.state.page)
        assertEquals(22L, f.store.read()!!.reference!!.steps)
    }

    @Test fun savedRecordReopensAsLocalAndPendingUploadWithoutAnotherDownload() = Fixture().use { f ->
        f.reachReference()
        f.owner.saveReference("124", "valid", "")
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

    @Test fun unknownDeviceClockCanStopAndDownloadOnItsOriginalConnection() = Fixture().use { f ->
        f.reachReference(unixMs = 0)
        val original = requireNotNull(f.store.read())
        assertEquals(1, f.port.count("stop"))
        assertNotNull(original.stopConfirmedAtMs)
        assertNull(original.startedAtMs)
        assertNull(original.endedAtMs)
        f.owner.saveReference("12", "valid", "")
        f.observe(stopped(), listOf(finalRecord.copy(unixMs = 0)))
        assertEquals(listOf(Read(7, 0, 16_384)), f.port.reads)
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
        f.owner.saveReference("29", "valid", "")
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
        f.owner.saveReference("31", "valid", "")
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
        f.owner.saveReference("33", "valid", "")
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

    @Test fun cleanupSyncFailureAfterLocalCommitKeepsCompleteStateAndSavedFiles() = Fixture().use { f ->
        f.reachReference()
        f.owner.saveReference("47", "valid", "")
        f.observe(stopped(), listOf(finalRecord))
        val before = requireNotNull(f.store.read())
        f.failDownloadSyncAfterLocalCommit = true
        f.health(HealthMessage.DataChunk(0, payload))
        f.health(HealthMessage.ReadEnd(payload.size.toLong(), true))
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
        f.owner.saveReference("51", "valid", "")
        f.observe(stopped(), listOf(finalRecord))
        val reads = f.port.reads.toList()
        assertFalse(f.owner.releaseIfIdle())
        assertEquals(reads, f.port.reads)
        assertNull(f.store.read()!!.localData)
    }

    @Test fun completedReleasedOwnerIgnoresStalePageIntentsAndCallbacks() = Fixture().use { f ->
        f.reachReference()
        f.owner.saveReference("58", "valid", "")
        f.observe(stopped(), listOf(finalRecord))
        f.finishDownload()
        val saved = requireNotNull(f.store.read())
        val oldGeneration = f.port.generation
        assertTrue(f.owner.releaseIfIdle())
        val commands = f.port.calls.toList()
        f.owner.start()
        f.owner.stop()
        f.owner.retry()
        f.owner.reconnect()
        f.owner.enterReference()
        f.owner.saveReference("999", "valid", "")
        f.owner.home()
        f.owner.onConnected(oldGeneration)
        f.owner.onHealth(oldGeneration, SensorPacket.Health(collecting(), ++f.clock.now))
        assertEquals(commands, f.port.calls)
        assertEquals(saved, f.store.read())
    }

    private inner class Fixture : AutoCloseable {
        val directory = temporary.newFolder()
        var failCommit = false
        var failObservation = false
        var failDownloadSyncAfterLocalCommit = false
        val preparation = PreparationStore(File(directory, "profile")) { source, target -> replace(source, target) }.apply {
            register("owner001", RingPlacement.LEFT_INDEX)
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
        var owner = createOwner()

        private fun createOwner() = RealCollectionController(directory, preparation, store, port,
            CollectionScheduler { delay, action -> scheduled += delay to action }, clock,
            recordObservation = { if (failObservation) throw IOException("注入诊断记录失败") },
            reportError = { errors += it },
            downloadFactory = { location, session, record -> RealSessionDownload(location, session.sessionId,
                session.preparation.ring!!.address, record, session.startedAtMs ?: 0L, session.endedAtMs ?: 0L, {
                    if (failDownloadSyncAfterLocalCommit && store.read()?.localData != null) {
                        failDownloadSyncAfterLocalCommit = false
                        throw IOException("注入已保存后的清理同步失败")
                    }
                }) })

        fun reopen() { owner.close(); owner = createOwner(); owner.initialize() }

        fun connectReady() {
            owner.initialize()
            owner.onConnected(port.generation)
            observe(idle())
            assertTrue("Idle inspection should permit start; errors=$errors", owner.state.canStart)
        }

        fun beginCollecting(record: HealthMessage.ListItem = initialRecord) {
            connectReady()
            owner.start()
            observe(idle())
            observe(collecting(), listOf(record))
            assertEquals("errors=$errors", CollectionPage.COLLECTING, owner.state.page)
        }

        fun reachReference(unixMs: Long = epoch) {
            beginCollecting(initialRecord.copy(unixMs = unixMs))
            clock.now += 60_000
            owner.stop()
            observe(stopped(), listOf(finalRecord.copy(unixMs = unixMs)))
            assertEquals("errors=$errors", CollectionPage.REFERENCE, owner.state.page)
        }

        fun observe(status: HealthMessage.Status, records: List<HealthMessage.ListItem> = emptyList()) {
            health(status)
            records.forEach(::health)
            health(HealthMessage.ListEnd(records.size))
        }

        fun health(message: HealthMessage) {
            owner.onHealth(port.generation, SensorPacket.Health(message, ++clock.now))
        }

        fun finishDownload() {
            health(HealthMessage.DataChunk(0, payload))
            health(HealthMessage.ReadEnd(payload.size.toLong(), true))
            assertTrue("No processing errors expected: $errors", errors.isEmpty())
        }

        override fun close() = owner.close()
    }

    private data class Read(val sessionId: Int, val offset: Long, val length: Int)

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
