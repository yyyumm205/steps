package com.nexthci.ringfitness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParticipantProfileStoreTest {
    @Test
    fun acceptsLettersNumbersAndMixedUsernames() {
        assertTrue(ParticipantProfileStore.isValidUsername("hezhe"))
        assertTrue(ParticipantProfileStore.isValidUsername("20260823"))
        assertTrue(ParticipantProfileStore.isValidUsername("user2026"))
    }

    @Test
    fun rejectsPunctuationUnicodeAndWrongLength() {
        assertFalse(ParticipantProfileStore.isValidUsername("ab"))
        assertFalse(ParticipantProfileStore.isValidUsername("user_name"))
        assertFalse(ParticipantProfileStore.isValidUsername("刘佳琪"))
        assertFalse(ParticipantProfileStore.isValidUsername("a".repeat(25)))
    }

    @Test
    fun normalizesCaseAndUploadSlash() {
        assertEquals("user2026", ParticipantProfileStore.normalizeUsername(" User2026 "))
        assertEquals("https://example.test/path/", ParticipantProfileStore.normalizeUploadLink("https://example.test/path"))
    }
}
