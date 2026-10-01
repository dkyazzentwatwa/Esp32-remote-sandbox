package org.espsketchide.app.editor

/** Which files have edits that are not written to disk yet. Each call reports whether it changed anything. */
class DirtyTracker<K> {
    private val dirty = mutableSetOf<K>()

    fun isDirty(key: K): Boolean = key in dirty
    fun markDirty(key: K): Boolean = dirty.add(key)
    fun markClean(key: K): Boolean = dirty.remove(key)
}
