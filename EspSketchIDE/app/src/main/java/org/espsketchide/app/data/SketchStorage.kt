package org.espsketchide.app.data

import java.io.IOException

/** A file or folder in sketch storage. [id] is opaque to callers. */
data class StorageNode(
    val id: String,
    val name: String,
    val isDirectory: Boolean
)

/**
 * The small set of file operations [SketchRepository] needs. The app implements it with the
 * Storage Access Framework ([DocumentFileStorage]); tests use an in-memory fake.
 */
interface SketchStorage {
    /** The user-chosen sketchbook folder, or null if none is set or access was revoked. */
    fun root(): StorageNode?

    fun children(dir: StorageNode): List<StorageNode>

    fun createDirectory(parent: StorageNode, name: String): StorageNode?

    fun createFile(parent: StorageNode, mimeType: String, name: String): StorageNode?

    /** Returns the renamed node (its id may change), or null on failure. */
    fun rename(node: StorageNode, newName: String): StorageNode?

    fun delete(node: StorageNode): Boolean

    @Throws(IOException::class)
    fun read(node: StorageNode): String

    @Throws(IOException::class)
    fun write(node: StorageNode, content: String)
}

fun SketchStorage.find(dir: StorageNode, name: String): StorageNode? =
    children(dir).firstOrNull { it.name == name }
