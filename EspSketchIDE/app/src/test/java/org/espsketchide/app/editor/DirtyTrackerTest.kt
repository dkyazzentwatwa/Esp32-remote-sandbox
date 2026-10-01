package org.espsketchide.app.editor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirtyTrackerTest {

    @Test
    fun `mark and clean report whether state changed`() {
        val tracker = DirtyTracker<String>()
        assertFalse(tracker.isDirty("a"))
        assertTrue(tracker.markDirty("a"))
        assertFalse(tracker.markDirty("a"))
        assertTrue(tracker.isDirty("a"))
        assertTrue(tracker.markClean("a"))
        assertFalse(tracker.markClean("a"))
        assertFalse(tracker.isDirty("a"))
    }

    @Test
    fun `files are tracked independently`() {
        val tracker = DirtyTracker<String>()
        tracker.markDirty("a.ino")
        assertFalse(tracker.isDirty("b.h"))
    }
}
