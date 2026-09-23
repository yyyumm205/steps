package com.nexthci.ringfitness

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReferenceDraftStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private fun file() = File(temporary.root, "collection/reference-draft.json")
    private fun id() = UUID.randomUUID().toString()
    private fun store(file: File = file()) = ReferenceDraftStore(file, { source, target ->
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING)
    }, {})

    @Test
    fun zeroAndDigitsSurviveReopenAndClearOnlyForTheirSession() {
        val file = file()
        val sessionId = id()
        val other = id()
        val store = store(file)

        assertEquals(ReferenceDraft(sessionId, "0"), store.save(sessionId, "0"))
        assertEquals(ReferenceDraft(sessionId, "0"), store(file).read(sessionId))
        assertNull(store(file).read(other))

        store.clear(other)
        assertEquals("0", store.read(sessionId)?.stepsText)
        store.clear(sessionId)
        assertNull(store.read(sessionId))
        assertNull(store.save(sessionId, ""))
    }

    @Test
    fun corruptOrOversizedFilesAreIgnoredAndCanBeReplaced() {
        val file = file()
        file.parentFile!!.mkdirs()
        file.writeText("{broken")
        val sessionId = id()
        val store = store(file)
        assertNull(store.read(sessionId))
        assertEquals("123", store.save(sessionId, "123")?.stepsText)
        assertEquals("123", store.read(sessionId)?.stepsText)

        file.writeBytes(ByteArray(4 * 1024 + 1))
        assertNull(store.read(sessionId))
    }

    @Test
    fun invalidInputNeverReplacesTheLastGoodDraft() {
        val file = file()
        val sessionId = id()
        val store = store(file)
        store.save(sessionId, "73")
        assertThrows(IllegalArgumentException::class.java) { store.save(sessionId, "7a") }
        assertThrows(IllegalArgumentException::class.java) { store.save(sessionId, "1".repeat(21)) }
        assertEquals("73", store.read(sessionId)?.stepsText)
    }

    @Test
    fun failedAtomicReplacementKeepsThePreviousDraft() {
        val file = file()
        val sessionId = id()
        val working = store(file)
        working.save(sessionId, "17")
        val failing = ReferenceDraftStore(file, { _, _ -> throw IOException("simulated move failure") }, {})

        assertThrows(IOException::class.java) { failing.save(sessionId, "18") }
        assertEquals("17", store(file).read(sessionId)?.stepsText)
    }

    @Test
    fun clearAllRemovesAStaleDraft() {
        val store = store()
        val sessionId = id()
        store.save(sessionId, "9")
        store.clearAll()
        assertNull(store.read(sessionId))
    }
}
