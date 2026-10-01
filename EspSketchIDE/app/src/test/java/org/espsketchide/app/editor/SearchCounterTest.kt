package org.espsketchide.app.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchCounterTest {

    @Test
    fun `no matches shows the supplied text`() {
        assertEquals("0 results", SearchCounter.text(-1, 0, "0 results"))
    }

    @Test
    fun `selected match is shown one-based`() {
        assertEquals("3/12", SearchCounter.text(2, 12, "0 results"))
    }

    @Test
    fun `matches but none selected shows a dash`() {
        assertEquals("-/12", SearchCounter.text(-1, 12, "0 results"))
    }
}
