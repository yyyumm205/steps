package com.nexthci.ringfitness

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.Properties
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
import org.junit.Assert.assertTrue
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
            val pending = Properties().apply { source.reader(Charsets.UTF_8).use { load(it) } }
            assertEquals("p001", pending.getProperty("participant_id"))
            assertEquals(RingPlacement.RIGHT_MIDDLE.wireValue, pending.getProperty("placement"))
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            commits++
        }

        val saved = store.register(" P001 ", RingPlacement.RIGHT_MIDDLE)

        assertEquals(1, commits)
        assertEquals(saved, PreparationStore(file).read())
        assertEquals("p001", saved.participantId)
        assertEquals("P001", saved.displayLabel)
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
    fun localNamesNeverBecomeResearchIdsEvenWhenTheyAreOnlyLettersOrDigits() {
        val file = profileFile()
        for ((index, label) in listOf("鱼淼淼", "Alice", "123").withIndex()) {
            val current = File(file.parentFile, "local-$index.properties")
            val saved = PreparationStore(current).register(
                " $label ",
                identityType = PreparationIdentityType.LOCAL_NAME,
            )
            assertEquals(label, saved.displayLabel)
            assertEquals(PreparationIdentityType.LOCAL_NAME, saved.identityType)
            assertTrue(saved.participantId.matches(Regex("^local[a-f0-9]{16}$")))
            assertFalse(saved.participantId.equals(label, ignoreCase = true))
            assertEquals(saved, PreparationStore(current).read())
        }
    }

    @Test
    fun localNameRejectsOnlyEmptyOverlongOrControlCharacterLabels() {
        for (invalid in listOf("", "   ", "a".repeat(41), "张\n三")) {
            val file = File(temporary.root, "invalid-${invalid.length}.properties")
            assertThrows(IllegalArgumentException::class.java) {
                PreparationStore(file).register(invalid, identityType = PreparationIdentityType.LOCAL_NAME)
            }
            assertFalse(file.exists())
        }
    }

    @Test
    fun researchIdKeepsOriginalAsciiRuleAndCanonicalizesCase() {
        val saved = PreparationStore(profileFile()).register(
            " AbC123 ",
            identityType = PreparationIdentityType.RESEARCH_ID,
        )
        assertEquals("abc123", saved.participantId)
        assertEquals("AbC123", saved.displayLabel)
        assertEquals(PreparationIdentityType.RESEARCH_ID, saved.identityType)

        for (invalid in listOf("", "ab", "被试001", "p_001", "a".repeat(25), "p 001")) {
            val file = File(temporary.root, "invalid-research-${invalid.length}-${invalid.hashCode()}.properties")
            assertThrows(IllegalArgumentException::class.java) {
                PreparationStore(file).register(invalid, identityType = PreparationIdentityType.RESEARCH_ID)
            }
            assertFalse(file.exists())
        }
    }

    @Test
    fun theSameTextHasDifferentMeaningOnlyWhenTheUserSelectsIt() {
        val store = PreparationStore(profileFile())
        val research = store.register(
            "Alice",
            identityType = PreparationIdentityType.RESEARCH_ID,
        )
        val local = store.replaceCurrentProfile(
            "Alice",
            collectionIsIdle = true,
            identityType = PreparationIdentityType.LOCAL_NAME,
        )

        assertEquals("alice", research.participantId)
        assertTrue(local.participantId.matches(Regex("^local[a-f0-9]{16}$")))
        assertNotEquals(research.participantId, local.participantId)
        assertEquals(research.installationId, local.installationId)
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
        assertFalse(requireNotNull(file.parentFile).listFiles()!!.any { it.extension == "tmp" })
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
    fun truncatedPrimaryIsRestoredFromLastKnownGoodBackup() {
        val file = profileFile()
        val expected = PreparationStore(file).register("P001")
        file.writeText("version=1\nparticipant_id=p001\n", Charsets.UTF_8)
        val damaged = file.readBytes()
        val store = PreparationStore(file)
        assertEquals(expected, store.read())
        assertEquals(expected, PreparationStore(file).read())
        assertArrayEquals(damaged, File(file.parentFile, "${file.name}.damaged").readBytes())
    }

    @Test
    fun missingPrimaryIsRestoredFromLastKnownGoodBackup() {
        val file = profileFile()
        val expected = PreparationStore(file).register(
            "张三",
            RingPlacement.RIGHT_INDEX,
            PreparationIdentityType.LOCAL_NAME,
        )
        val backup = File(file.parentFile, "${file.name}.last-good")
        assertTrue(backup.isFile)
        assertTrue(file.delete())

        assertEquals(expected, PreparationStore(file).read())
        assertTrue(file.isFile)
        assertEquals(expected, PreparationStore(file).read())
    }

    @Test
    fun changedRecordWithOtherwiseValidFieldsRecoversWithoutAcceptingTampering() {
        val file = profileFile()
        val store = PreparationStore(file)
        val expected = store.register("P001")
        file.writeText(file.readText(Charsets.UTF_8).replace("participant_id=p001", "participant_id=p002"), Charsets.UTF_8)
        assertEquals(expected, store.read())
        assertEquals("p001", store.read()!!.participantId)
    }

    @Test
    fun malformedPropertiesIsReportedWithoutDeletingFile() {
        val file = profileFile()
        requireNotNull(file.parentFile).mkdirs()
        file.writeText("participant_id=\\uZZZZ\n", Charsets.UTF_8)
        val damaged = file.readBytes()
        assertThrows(PreparationProfileUnreadableException::class.java) { PreparationStore(file).read() }
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

    @Test
    fun versionOneProfileReadsWithParticipantIdAsDisplayLabelAndUpgradesOnWrite() {
        val file = profileFile()
        val installationId = "83e56afb-a74c-4825-af0b-b226cfe9c630"
        writeVersionOne(file, "p001", installationId, RingPlacement.RIGHT_INDEX, ring)

        val restored = PreparationStore(file).read()!!
        assertEquals("p001", restored.displayLabel)
        assertEquals(PreparationIdentityType.RESEARCH_ID, restored.identityType)
        assertEquals(RingPlacement.RIGHT_INDEX, restored.placement)
        assertEquals(ring, restored.ring)
        assertTrue(file.readText(Charsets.UTF_8).contains("version=1"))

        PreparationStore(file).savePlacement(RingPlacement.LEFT_RING)
        val upgraded = Properties().apply { file.reader(Charsets.UTF_8).use { load(it) } }
        assertEquals("3", upgraded.getProperty("version"))
        assertEquals("p001", upgraded.getProperty("display_label"))
        assertEquals("research_id", upgraded.getProperty("identity_type"))
        assertEquals(installationId, PreparationStore(file).read()!!.installationId)
    }

    @Test
    fun versionTwoProfilesKeepTheirLegacyMeaningAndUpgradeToVersionThree() {
        val installationId = "83e56afb-a74c-4825-af0b-b226cfe9c630"
        val researchFile = File(temporary.root, "legacy-research.properties")
        writeVersionTwo(researchFile, "alice", installationId, "Alice")
        val research = PreparationStore(researchFile).read()!!
        assertEquals(PreparationIdentityType.RESEARCH_ID, research.identityType)
        assertEquals("alice", research.participantId)

        val seed = PreparationStore(File(temporary.root, "local-seed.properties")).register(
            "张三",
            identityType = PreparationIdentityType.LOCAL_NAME,
        )
        val localFile = File(temporary.root, "legacy-local.properties")
        writeVersionTwo(localFile, seed.participantId, seed.installationId, "张三")
        val local = PreparationStore(localFile).read()!!
        assertEquals(PreparationIdentityType.LOCAL_NAME, local.identityType)
        assertEquals(seed.participantId, local.participantId)

        PreparationStore(researchFile).savePlacement(RingPlacement.LEFT_INDEX)
        PreparationStore(localFile).savePlacement(RingPlacement.RIGHT_INDEX)
        for (upgraded in listOf(researchFile, localFile)) {
            val properties = Properties().apply { upgraded.reader(Charsets.UTF_8).use { load(it) } }
            assertEquals("3", properties.getProperty("version"))
            assertTrue(properties.getProperty("identity_type") in setOf("research_id", "local_name"))
        }
    }

    @Test
    fun replacingCurrentProfileKeepsInstallationButClearsDeviceAndPlacement() {
        val store = PreparationStore(profileFile())
        val original = store.register(
            "张三",
            RingPlacement.RIGHT_INDEX,
            PreparationIdentityType.LOCAL_NAME,
        )
        store.selectRing(ring)

        val replacement = store.replaceCurrentProfile(
            "李四",
            collectionIsIdle = true,
            identityType = PreparationIdentityType.LOCAL_NAME,
        )

        assertEquals(original.installationId, replacement.installationId)
        assertNotEquals(original.participantId, replacement.participantId)
        assertEquals("李四", replacement.displayLabel)
        assertNull(replacement.placement)
        assertNull(replacement.ring)
    }

    @Test
    fun localNameGetsSamePseudonymousIdWhenSelectedAgainOnSameInstallation() {
        val store = PreparationStore(profileFile())
        val first = store.register("张三", identityType = PreparationIdentityType.LOCAL_NAME)
        store.replaceCurrentProfile(
            "李四",
            collectionIsIdle = true,
            identityType = PreparationIdentityType.LOCAL_NAME,
        )
        val selectedAgain = store.replaceCurrentProfile(
            "张三",
            collectionIsIdle = true,
            identityType = PreparationIdentityType.LOCAL_NAME,
        )

        assertEquals(first.participantId, selectedAgain.participantId)
        assertEquals(first.installationId, selectedAgain.installationId)
    }

    @Test
    fun activeCollectionGuardPreventsReplaceAndClear() {
        val store = PreparationStore(profileFile())
        val original = store.register("P001", RingPlacement.RIGHT_INDEX)
        store.selectRing(ring)

        assertThrows(IllegalArgumentException::class.java) {
            store.replaceCurrentProfile("P002", collectionIsIdle = false)
        }
        assertThrows(IllegalArgumentException::class.java) { store.clearCurrentProfile(collectionIsIdle = false) }
        assertEquals(original.copy(ring = ring), store.read())
    }

    @Test
    fun clearKeepsInstallationAndLeavesHistoricalSessionFilesUntouched() {
        val file = profileFile()
        requireNotNull(file.parentFile).mkdirs()
        val session = File(file.parentFile, "session-history.json").apply { writeText("kept", Charsets.UTF_8) }
        val store = PreparationStore(file)
        val original = store.register("张三", identityType = PreparationIdentityType.LOCAL_NAME)

        store.clearCurrentProfile(collectionIsIdle = true)
        assertNull(store.read())
        assertEquals("kept", session.readText(Charsets.UTF_8))

        val next = store.register("李四", identityType = PreparationIdentityType.LOCAL_NAME)
        assertEquals(original.installationId, next.installationId)
    }

    @Test
    fun unreadablePrimaryAndBackupExposeRecoverableProfileException() {
        val file = profileFile()
        val original = PreparationStore(file).register("P001")
        file.writeText("damaged primary", Charsets.UTF_8)
        File(file.parentFile, "${file.name}.last-good").writeText("damaged backup", Charsets.UTF_8)

        val store = PreparationStore(file)
        val error = assertThrows(PreparationProfileUnreadableException::class.java) {
            store.read()
        }
        assertTrue(error.message!!.contains("重新登记"))
        assertTrue(file.exists())

        store.clearCurrentProfile(collectionIsIdle = true)
        val registeredAgain = store.register("新用户", identityType = PreparationIdentityType.LOCAL_NAME)
        assertEquals(original.installationId, registeredAgain.installationId)
    }

    private fun writeVersionOne(
        file: File,
        participantId: String,
        installationId: String,
        placement: RingPlacement?,
        preparedRing: PreparedRing?,
    ) {
        val fields = listOf("version", "participant_id", "installation_id", "placement", "ring_address", "ring_name")
        val properties = Properties().apply {
            setProperty("version", "1")
            setProperty("participant_id", participantId)
            setProperty("installation_id", installationId)
            setProperty("placement", placement?.wireValue.orEmpty())
            setProperty("ring_address", preparedRing?.address.orEmpty())
            setProperty("ring_name", preparedRing?.name.orEmpty())
        }
        val digest = MessageDigest.getInstance("SHA-256")
        fields.forEach { field ->
            val bytes = properties.getProperty(field).toByteArray(Charsets.UTF_8)
            digest.update("${bytes.size}:".toByteArray(Charsets.US_ASCII))
            digest.update(bytes)
        }
        properties.setProperty(
            "checksum_sha256",
            digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) },
        )
        requireNotNull(file.parentFile).mkdirs()
        FileOutputStream(file).use { stream ->
            stream.writer(Charsets.UTF_8).use { properties.store(it, "v1") }
        }
    }

    private fun writeVersionTwo(
        file: File,
        participantId: String,
        installationId: String,
        displayLabel: String,
    ) {
        val fields = listOf(
            "version",
            "participant_id",
            "installation_id",
            "placement",
            "ring_address",
            "ring_name",
            "display_label",
        )
        val properties = Properties().apply {
            setProperty("version", "2")
            setProperty("participant_id", participantId)
            setProperty("installation_id", installationId)
            setProperty("placement", "")
            setProperty("ring_address", "")
            setProperty("ring_name", "")
            setProperty("display_label", displayLabel)
        }
        val digest = MessageDigest.getInstance("SHA-256")
        fields.forEach { field ->
            val bytes = properties.getProperty(field).toByteArray(Charsets.UTF_8)
            digest.update("${bytes.size}:".toByteArray(Charsets.US_ASCII))
            digest.update(bytes)
        }
        properties.setProperty(
            "checksum_sha256",
            digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) },
        )
        requireNotNull(file.parentFile).mkdirs()
        FileOutputStream(file).use { stream ->
            stream.writer(Charsets.UTF_8).use { properties.store(it, "v2") }
        }
    }
}
