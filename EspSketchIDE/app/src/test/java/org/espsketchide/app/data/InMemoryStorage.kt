package org.espsketchide.app.data

import java.io.IOException

/**
 * In-memory [SketchStorage] that mimics two behaviours of Android's ExternalStorageProvider
 * that matter for sketch handling:
 *  - ids are paths, so renaming a folder invalidates ids previously handed out for its children;
 *  - creating a file with a MIME type whose extension doesn't match the name appends that
 *    extension (e.g. `text/plain` + `pins.h` becomes `pins.h.txt`).
 */
class InMemoryStorage(hasRoot: Boolean = true) : SketchStorage {

    private class Entry(val isDirectory: Boolean, var content: String = "")

    private val entries = sortedMapOf<String, Entry>()

    /** Names for which [rename] fails, to test rollback paths. */
    val failRenamesTo = mutableSetOf<String>()

    /** Ids for which [delete] fails. */
    val failDeletesOf = mutableSetOf<String>()

    /** Number of successful [write] calls, to verify unchanged files aren't rewritten. */
    var writeCount = 0
        private set

    init {
        if (hasRoot) entries[ROOT] = Entry(isDirectory = true)
    }

    fun mkdirs(path: String) {
        var current = ROOT
        path.split('/').forEach { part ->
            current = "$current/$part"
            entries.getOrPut(current) { Entry(isDirectory = true) }
        }
    }

    fun put(path: String, content: String) {
        mkdirs(path.substringBeforeLast('/'))
        entries["$ROOT/$path"] = Entry(isDirectory = false, content = content)
    }

    fun exists(path: String): Boolean = entries.containsKey("$ROOT/$path")

    fun contentOf(path: String): String? = entries["$ROOT/$path"]?.content

    fun paths(): List<String> = entries.keys.filter { it != ROOT }.map { it.removePrefix("$ROOT/") }

    override fun root(): StorageNode? = entries[ROOT]?.let { node(ROOT) }

    override fun children(dir: StorageNode): List<StorageNode> {
        val prefix = dir.id + "/"
        return entries.keys
            .filter { it.startsWith(prefix) && !it.removePrefix(prefix).contains('/') }
            .map { node(it) }
    }

    override fun createDirectory(parent: StorageNode, name: String): StorageNode? {
        if (entries[parent.id]?.isDirectory != true) return null
        val id = "${parent.id}/$name"
        if (entries.containsKey(id)) return null
        entries[id] = Entry(isDirectory = true)
        return node(id)
    }

    override fun createFile(parent: StorageNode, mimeType: String, name: String): StorageNode? {
        if (entries[parent.id]?.isDirectory != true) return null
        val finalName = if (mimeType == "text/plain" && !name.endsWith(".txt")) "$name.txt" else name
        val id = "${parent.id}/$finalName"
        if (entries.containsKey(id)) return null
        entries[id] = Entry(isDirectory = false)
        return node(id)
    }

    override fun rename(node: StorageNode, newName: String): StorageNode? {
        if (!entries.containsKey(node.id) || newName in failRenamesTo) return null
        val newId = node.id.substringBeforeLast('/') + "/" + newName
        if (entries.containsKey(newId)) return null
        val moved = entries.filterKeys { it == node.id || it.startsWith(node.id + "/") }
        moved.keys.forEach { entries.remove(it) }
        moved.forEach { (oldId, entry) -> entries[newId + oldId.removePrefix(node.id)] = entry }
        return node(newId)
    }

    override fun delete(node: StorageNode): Boolean {
        if (!entries.containsKey(node.id) || node.id in failDeletesOf) return false
        entries.keys.removeAll { it == node.id || it.startsWith(node.id + "/") }
        return true
    }

    override fun read(node: StorageNode): String {
        val entry = entries[node.id] ?: throw IOException("No such file: ${node.id}")
        return entry.content
    }

    override fun write(node: StorageNode, content: String) {
        val entry = entries[node.id] ?: throw IOException("No such file: ${node.id}")
        entry.content = content
        writeCount++
    }

    private fun node(id: String) = StorageNode(id, id.substringAfterLast('/'), entries.getValue(id).isDirectory)

    companion object {
        const val ROOT = "root"
    }
}
