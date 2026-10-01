package org.espsketchide.app.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SymbolBarSymbolsTest {

    @Test
    fun `display and insert arrays line up`() {
        assertEquals(SymbolBarSymbols.display.size, SymbolBarSymbols.insert.size)
    }

    @Test
    fun `first symbol is the tab key and the rest insert themselves`() {
        assertEquals("\t", SymbolBarSymbols.insert[0])
        for (i in 1 until SymbolBarSymbols.display.size) {
            assertEquals(SymbolBarSymbols.display[i], SymbolBarSymbols.insert[i])
        }
    }

    @Test
    fun `contains the symbols sketches need most`() {
        listOf("{", "}", "(", ")", ";", "#", "\"", "[", "]").forEach {
            assertTrue("$it missing", it in SymbolBarSymbols.insert)
        }
    }
}
