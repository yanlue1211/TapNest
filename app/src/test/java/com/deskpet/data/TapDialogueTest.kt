package com.deskpet.data

import com.deskpet.data.model.TapDialogue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TapDialogueTest {
    @Test fun sanitizesAndCaps() {
        val text = (0 until 26).map { "  " + "a".repeat(70) + it }
        val result = TapDialogue.sanitize(text)
        assertEquals(20, result.size)
        assertEquals(60, result.first().length)
        assertEquals(listOf("hi", "there"), TapDialogue.sanitize(listOf("", " hi ", "  ", "there")))
    }

    @Test fun cyclesAndHandlesSilence() {
        val lines = listOf("one", "two")
        assertEquals("one", TapDialogue.at(lines, 0))
        assertEquals("two", TapDialogue.at(lines, 1))
        assertEquals("one", TapDialogue.at(lines, 2))
        assertNull(TapDialogue.at(emptyList(), 5))
    }
}
