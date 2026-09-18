package com.nexthci.ringfitness

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PreparationStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private fun profileFile(): File = File(temporary.root, "private/preparation.properties")
    private val ring = PreparedRing("A1:B2:C3:D4:E5:F6", "Ringo 戒指")

    @Test
    fun firstReadDoesNotCreateIdentityOrFiles() {
        val file = profileFile()
        assertNull(PreparationStore(file).read())
        assertFalse(file.exists())
    }

    @Test
    fun firstRegistrationCommitsParticipantAndPlacementTogether() {
        val file = profileFile()
        var commits = 0
        val store = PreparationStore(file) { source, target ->
            assertNull(PreparationStore(target).read())
            val pending = requireNotNull(PreparationStore(source).read())
            assertEquals("p001", pending.participantId)
            assertEquals(RingPlacement.RIGHT_MIDDLE, pending.placement)
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            commits++
        }

        val saved = store.register(" P001 ", RingPlacement.RIGHT_MIDDLE)

        assertEquals(1, commits)
        assertEquals(saved, PreparationStore(file).read())
        assertEquals("p001", saved.participantId)
        assertEquals(RingPlacement.RIGHT_MIDDLE, saved.placement)
        assertEquals(saved.installationId, UUID.fromString(saved.installationId).toString())
    }

    @Test
    fun failedCombinedRegistrationLeavesNoPartialProfileAndAllowsEditedRetry() {
        val file = profileFile()
        var failCommit = true
        val store = PreparationStore(file) { source, target ->
            if (failCommit) throw IOException("simulated rename failure")
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }

        assertThrows(IOException::class.java) { store.register("P001", RingPlacement.LEFT_INDEX) }
        assertNull(PreparationStore(file).read())
        assertFalse(file.exists())
        assertEquals(0, requireNotNull(file.parentFile).listFiles()!!.size)

        failCommit = false
        val saved = store.register("P002", RingPlacement.RIGHT_RING)
        assertEquals("p002", saved.participantId)
        assertEquals(RingPlacement.RIGHT_RING, saved.placement)
        assertEquals(saved, PreparationStore(file).read())
    }

    @Test
    fun repeatedRegistrationKeepsExistingIncompleteProfileUnchanged() {
        val file = profileFile()
        val original = PreparationStore(file).register("P001")
        val bytes = file.readBytes()

        assertEquals(original, PreparationStore(file).register("P001", RingPlacement.LEFT_INDEX))
        assertNull(PreparationStore(file).read()!!.placement)
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test
    fun reopeningRestoresConfirmedParticipantPlacementAndRing() {
        val file = profileFile()
        val store = PreparationStore(file)
        val registered = store.register(" P001 ")
        assertEquals("p001", registered.participantId)
        assertEquals(registered.installationId, UUID.fromString(registered.installationId).toString())
        store.savePlacement(RingPlacement.RIGHT_MIDDLE)
        val saved = store.selectRing(ring)

        val restored = PreparationStore(file).read()
        assertEquals(saved, restored)
        assertEquals(registered.installationId, restored!!.installationId)
        assertEquals(RingPlacement.RIGHT_MIDDLE, restored.placement)
        assertEquals(ring, restored.ring)
    }

    @Test
    fun repeatedRegistrationPreservesAllInformationAndBytes() {
        val file = profileFile()
        val store = PreparationStore(file)
        store.register("P001")
        store.savePlacement(RingPlacement.LEFT_INDEX)
        val saved = store.selectRing(ring)
        val bytes = file.readBytes()
        assertEquals(saved, PreparationStore(file).register(" p001 ", RingPlacement.RIGHT_RING))
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test
    fun differentParticipantCannotOverwriteExistingIdentity() {
        val file = profileFile()
        val store = PreparationStore(file)
        val original = store.register("P001")
        val bytes = file.readBytes()
        assertThrows(IllegalArgumentException::class.java) { store.register("P002", RingPlacement.RIGHT_RING) }
        assertEquals(original, store.read())
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test
    fun rejectsInvalidParticipantWithoutCreatingFile() {
        val file = profileFile()
        val store = PreparationStore(file)
        for (invalid in listOf("", "ab", "被试001", "p_001", "a".repeat(25), "p 001")) {
            assertThrows(IllegalArgumentException::class.java) { store.register(invalid) }
        }
        assertFalse(file.exists())
    }

    @Test
    fun participantNormalizationIsIndependentOfTurkishLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("i001", PreparationStore(profileFile()).register("I001").participantId)
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun independentInstallationsGetDifferentIds() {
        val first = PreparationStore(File(temporary.root, "first.properties")).register("P001")
        val second = PreparationStore(File(temporary.root, "second.properties")).register("P001")
        assertNotEquals(first.installationId, second.installationId)
    }

    @Test
    fun placementAndDeviceRequireRegisteredParticipant() {
        val file = profileFile()
        val store = PreparationStore(file)
        assertThrows(IllegalStateException::class.java) { store.savePlacement(RingPlacement.LEFT_RING) }
        assertThrows(IllegalStateException::class.java) { store.selectRing(ring) }
        assertFalse(file.exists())
    }

    @Test
    fun failedReplacementPreservesLastConfirmedSnapshotAndCleansTemporaryFile() {
        val file = profileFile()
        val store = PreparationStore(file)
        store.register("P001")
        val original = store.savePlacement(RingPlacement.LEFT_INDEX)
        val bytes = file.readBytes()
        val failingStore = PreparationStore(file) { _, _ -> throw IOException("simulated full disk") }

        assertThrows(IOException::class.java) { failingStore.selectRing(ring) }
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(original, PreparationStore(file).read())
        assertEquals(listOf(file.name), requireNotNull(file.parentFile).listFiles()!!.map { it.name })
    }

    @Test
    fun failedFirstSaveLeavesRegistrationIncomplete() {
        val file = profileFile()
        val store = PreparationStore(file) { _, _ -> throw IOException("simulated rename failure") }
        assertThrows(IOException::class.java) { store.register("P001") }
        assertNull(store.read())
        assertFalse(file.exists())
        assertEquals(0, requireNotNull(file.parentFile).listFiles()!!.size)
    }

    @Test
    fun truncatedRecordIsReportedAndNeverOverwritten() {
        val file = profileFile()
        PreparationStore(file).register("P001")
        file.writeText("version=1\nparticipant_id=p001\n", Charsets.UTF_8)
        val damaged = file.readBytes()
        val store = PreparationStore(file)
        assertThrows(IOException::class.java) { store.read() }
        assertThrows(IOException::class.java) { store.register("P001") }
        assertThrows(IOException::class.java) { store.savePlacement(RingPlacement.LEFT_INDEX) }
        assertThrows(IOException::class.java) { store.selectRing(ring) }
        assertArrayEquals(damaged, file.readBytes())
    }

    @Test
    fun changedRecordWithOtherwiseValidFieldsFailsIntegrityCheck() {
        val file = profileFile()
        val store = PreparationStore(file)
        store.register("P001")
        file.writeText(file.readText(Charsets.UTF_8).replace("participant_id=p001", "participant_id=p002"), Charsets.UTF_8)
        val damaged = file.readBytes()
        assertThrows(IOException::class.java) { store.read() }
        assertThrows(IOException::class.java) { store.register("P002") }
        assertArrayEquals(damaged, file.readBytes())
    }

    @Test
    fun malformedPropertiesIsReportedWithoutDeletingFile() {
        val file = profileFile()
        requireNotNull(file.parentFile).mkdirs()
        file.writeText("participant_id=\\uZZZZ\n", Charsets.UTF_8)
        val damaged = file.readBytes()
        assertThrows(IOException::class.java) { PreparationStore(file).read() }
        assertArrayEquals(damaged, file.readBytes())
    }

    @Test
    fun invalidAddressDoesNotReplaceLastSelectedRing() {
        val store = PreparationStore(profileFile())
        store.register("P001")
        val original = store.selectRing(ring)
        for (invalid in listOf("", "A1:B2:C3:D4:E5", "GG:B2:C3:D4:E5:F6", "A1-B2-C3-D4-E5-F6")) {
            assertThrows(IllegalArgumentException::class.java) { store.selectRing(ring.copy(address = invalid)) }
        }
        assertEquals(original, store.read())
    }

    @Test
    fun ringAddressNormalizesAndBlankNamesAreRejected() {
        val store = PreparationStore(profileFile())
        store.register("P001")
        assertEquals(ring, store.selectRing(PreparedRing(" a1:b2:c3:d4:e5:f6 ", " Ringo 戒指 ")).ring)
        assertThrows(IllegalArgumentException::class.java) { store.selectRing(ring.copy(name = "  ")) }
        assertEquals(ring, store.read()!!.ring)
    }

    @Test
    fun separatelyCreatedStoresNeverOverwriteUnrelatedLatestFields() {
        val file = profileFile()
        val first = PreparationStore(file)
        val second = PreparationStore(file)
        val identity = first.register("P001")
        second.savePlacement(RingPlacement.LEFT_MIDDLE)
        first.selectRing(ring)
        second.savePlacement(RingPlacement.RIGHT_RING)
        assertEquals(identity.copy(placement = RingPlacement.RIGHT_RING, ring = ring), first.read())
    }

    @Test
    fun concurrentStoresKeepBothConfirmedUpdates() {
        val file = profileFile()
        val identity = PreparationStore(file).register("P001")
        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val placement = executor.submit {
                start.await()
                PreparationStore(file).savePlacement(RingPlacement.RIGHT_INDEX)
            }
            val device = executor.submit {
                start.await()
                PreparationStore(file).selectRing(ring)
            }
            start.countDown()
            placement.get(5, TimeUnit.SECONDS)
            device.get(5, TimeUnit.SECONDS)
            assertEquals(identity.copy(placement = RingPlacement.RIGHT_INDEX, ring = ring), PreparationStore(file).read())
        } finally {
            executor.shutdownNow()
        }
    }
}
