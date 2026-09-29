package org.espsketchide.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SketchNamesTest {

    @Test
    fun `free base name is returned unchanged`() {
        assertEquals("Blink", SketchNames.nextFreeName("Blink", emptySet()))
        assertEquals("Blink", SketchNames.nextFreeName("Blink", setOf("Other")))
    }

    @Test
    fun `taken names get a numeric suffix starting at 2`() {
        assertEquals("Blink_2", SketchNames.nextFreeName("Blink", setOf("Blink")))
        assertEquals("Blink_3", SketchNames.nextFreeName("Blink", setOf("Blink", "Blink_2")))
    }

    @Test
    fun `first gap in the numbering is used`() {
        assertEquals("Blink_2", SketchNames.nextFreeName("Blink", setOf("Blink", "Blink_3")))
    }

    @Test
    fun `sketch names drop symbols and always start with a letter`() {
        assertEquals("BlinkWithoutDelay", SketchNames.toSketchName("Blink Without Delay"))
        assertEquals("My_sketch1", SketchNames.toSketchName("My_sketch-1!"))
        assertEquals("sketch_3D", SketchNames.toSketchName("3D"))
        assertEquals("sketch", SketchNames.toSketchName("!!!"))
        assertTrue(Regex("^[A-Za-z][A-Za-z0-9_]*$").matches(SketchNames.toSketchName("_x")))
    }

    @Test
    fun `primary ino is renamed and other files are kept`() {
        val files = linkedMapOf("Blink.ino" to "a", "config.h" to "b")
        val renamed = SketchNames.renamePrimary(files, "Blink_2")
        assertEquals(setOf("Blink_2.ino", "config.h"), renamed.keys)
        assertEquals("a", renamed["Blink_2.ino"])
        assertEquals("b", renamed["config.h"])
    }

    @Test
    fun `files without an ino are returned as they are`() {
        val files = mapOf("notes.txt" to "x")
        assertEquals(files, SketchNames.renamePrimary(files, "Foo"))
    }
}
