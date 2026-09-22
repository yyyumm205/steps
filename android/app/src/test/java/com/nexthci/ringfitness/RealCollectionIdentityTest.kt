package com.nexthci.ringfitness

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Exercises the same persisted username replacement and controller recreation as settings. */
class RealCollectionIdentityTest {
    @get:Rule val temporary = TemporaryFolder()
    private val ring = PreparedRing("AA:BB:CC:DD:EE:07", "Identity fixture")

    @Test fun newUsernameHasEmptyHistoryWhilePreviousUsersUploadsKeepTheirOwner() = Fixture().use { f ->
        val first = f.saveLocal(17)
        val second = f.saveLocal(23)
        f.open()
        assertEquals(setOf(first.sessionId, second.sessionId), f.owner!!.state.records.map { it.sessionId }.toSet())
        val firstManifest = f.store.manifestSnapshot(first.sessionId)

        f.switchTo("Bob02")
        f.open()

        assertEquals("bob02", f.owner!!.state.participantId)
        assertTrue(f.owner!!.state.records.isEmpty())
        assertNull(f.owner!!.state.session)
        assertNull(f.owner!!.state.savedSteps)
        assertEquals(setOf(first.sessionId, second.sessionId), f.uploads.requests.toSet())
        assertEquals(listOf("alice01", "alice01"), f.store.listSessions().map { it.preparation.participantId })

        f.store.markTransferStarted(first.sessionId)
        f.store.completeTransfer(first.sessionId,
            SessionTransferReceipt("identity-receipt", ++f.now, false, first.sessionId))
        f.owner!!.refreshUploads()

        assertTrue(f.owner!!.state.records.isEmpty())
        assertNull(f.owner!!.state.session)
        assertEquals(firstManifest, f.store.manifestSnapshot(first.sessionId))
        assertTrue(first.localData!!.files.all { File(f.directory, it.fileName).isFile })
        assertTrue(f.errors.isEmpty())
    }

    @Test fun switchingBackRestoresOnlyThatUserAndNewSessionsExportTheirSelectedUsername() = Fixture().use { f ->
        val alice = f.saveLocal(17)
        val aliceManifest = f.store.manifestSnapshot(alice.sessionId)
        f.open()
        f.switchTo("Bob02")
        val bob = f.saveLocal(42)
        f.open()

        assertEquals(listOf(bob.sessionId), f.owner!!.state.records.map { it.sessionId })
        assertEquals(42L, f.owner!!.state.savedSteps)
        assertEquals("bob02", f.store.manifestSnapshot(bob.sessionId).get("participant_id").asString)
        assertEquals("bob02", f.store.manifestSnapshot(bob.sessionId).get("participant_name").asString)
        assertEquals(aliceManifest, f.store.manifestSnapshot(alice.sessionId))

        f.switchTo("ALICE01")
        f.open()

        assertEquals("alice01", f.owner!!.state.participantId)
        assertEquals(listOf(alice.sessionId), f.owner!!.state.records.map { it.sessionId })
        assertNull(f.owner!!.state.session)
        assertNull(f.owner!!.state.savedSteps)
        assertEquals(setOf(alice.sessionId, bob.sessionId), f.uploads.requests.toSet())
        assertEquals("alice01", f.store.manifestSnapshot(alice.sessionId).get("participant_id").asString)
        assertEquals("bob02", f.store.read(bob.sessionId)!!.preparation.participantId)
        assertTrue(f.errors.isEmpty())
    }

    @Test fun controllerStartsTheNextCaptureWithTheNewUsername() = Fixture().use { f ->
        val alice = f.saveLocal(17)
        f.open()
        f.switchTo("Bob02")
        f.open()
        val owner = f.owner!!
        owner.onConnected(f.connectionGeneration)
        val idle = HealthMessage.Status(false, 0, 0, 0, 6)
        f.observe(idle)
        assertTrue(owner.state.canStart)

        owner.selectActivity(SessionActivity.WALKING)
        owner.start()
        f.observe(idle)
        f.observe(HealthMessage.Status(true, 13, 1, 0, 7),
            listOf(HealthMessage.ListItem(7, 13, 1, 900, f.now)))

        assertEquals(CollectionPage.COLLECTING, owner.state.page)
        assertEquals("bob02", f.store.readPending()!!.preparation.participantId)
        assertEquals("bob02", owner.state.participantId)
        assertFalse(owner.state.records.any { it.sessionId == alice.sessionId })
        assertEquals("alice01", f.store.read(alice.sessionId)!!.preparation.participantId)
        assertTrue(f.errors.isEmpty())
    }

    private inner class Fixture : AutoCloseable {
        val directory = temporary.newFolder()
        var now = 1_789_804_800_000L
        val preparation = PreparationStore(File(directory, "profile"), ::replace).apply {
            registerUsername("Alice01", RingPlacement.LEFT_INDEX)
            selectRing(ring)
        }
        val store = FreeLivingSessionStore(File(directory, "session.json"), ::replace, {})
        val uploads = RecordingUploads()
        val errors = mutableListOf<Exception>()
        var owner: RealCollectionController? = null
        var connectionGeneration = 0L

        fun open() {
            owner = RealCollectionController(directory, preparation, store, object : RealCollectionPort {
                override fun connect(ring: PreparedRing, generation: Long): Boolean {
                    connectionGeneration = generation
                    return true
                }
                override fun disconnect() = Unit
                override fun queryStatus() = true
                override fun queryBattery() = true
                override fun queryRecords() = true
                override fun start() = true
                override fun stop() = true
                override fun read(sessionId: Int, offset: Long, length: Int) = true
            }, CollectionScheduler { delay, action ->
                if (delay in setOf(500L, 1_000L, 5_000L)) action()
            }, object : CaptureClock {
                override fun nowEpochMs() = now
                override fun timeZoneId() = "Asia/Shanghai"
            }, uploads = uploads, reportError = { errors += it }).also { it.initialize() }
        }

        fun observe(status: HealthMessage.Status, records: List<HealthMessage.ListItem> = emptyList()) {
            owner!!.onHealth(connectionGeneration, SensorPacket.Health(status, ++now))
            records.forEach { owner!!.onHealth(connectionGeneration, SensorPacket.Health(it, ++now)) }
            owner!!.onHealth(connectionGeneration, SensorPacket.Health(HealthMessage.ListEnd(records.size), ++now))
        }

        fun switchTo(username: String) {
            assertNull(store.readPending())
            owner?.close()
            owner = null
            preparation.replaceCurrentUsername(username, collectionIsIdle = true)
            preparation.savePlacement(RingPlacement.LEFT_INDEX)
            preparation.selectRing(ring)
        }

        fun saveLocal(steps: Long): FreeLivingSession {
            val session = store.requestStart(preparation.read()!!, ++now, "Asia/Shanghai")
            store.confirmStart(session.sessionId, ring.address, HealthMessage.Status(true, 13, 1, 0, 7), ++now)
            store.requestStop(session.sessionId, ++now)
            store.confirmStop(session.sessionId, ring.address, HealthMessage.Status(false, 13, 1, 0, 7), ++now)
            store.saveReference(session.sessionId, SessionReference(ReferenceStatus.VALID, steps, ++now))
            val payload = byteArrayOf(0x32, 0x12, 1, 0, 0, 0, 0, 1, 2, 3, 4, 5, 6)
            val raw = File(directory, "${session.sessionId}-identity-fixture.rfbin").apply { writeBytes(payload) }
            val hash = MessageDigest.getInstance("SHA-256").digest(payload)
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            return store.completeLocalData(session.sessionId,
                listOf(SessionRawFile(raw.name, 7, raw.length(), hash, false)), ++now)
        }

        override fun close() { owner?.close() }
    }

    private class RecordingUploads : RealUploadPort {
        val requests = mutableListOf<String>()
        override fun enqueue(sessionId: String, retry: Boolean) { requests += sessionId }
        override fun isInFlight(sessionId: String) = sessionId in requests
    }

    private fun replace(source: File, target: File) {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
