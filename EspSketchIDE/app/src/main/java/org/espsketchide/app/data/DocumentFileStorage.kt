package org.espsketchide.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import java.io.IOException

private const val PREFS_NAME = "esp_sketch_ide"
private const val KEY_ROOT_URI = "sketches_root_uri"

/**
 * [SketchStorage] backed by the Storage Access Framework. Node ids are document URIs
 * built from the persisted tree URI, so they can be resolved with [DocumentFile.fromTreeUri].
 */
class DocumentFileStorage(context: Context) : SketchStorage {

    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun rootUri(): Uri? {
        val stored = prefs.getString(KEY_ROOT_URI, null) ?: return null
        val uri = stored.toUri()
        val stillGranted = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
        return if (stillGranted) uri else null
    }

    fun setRoot(treeUri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        prefs.edit { putString(KEY_ROOT_URI, treeUri.toString()) }
    }

    override fun root(): StorageNode? {
        val uri = rootUri() ?: return null
        val doc = DocumentFile.fromTreeUri(context, uri) ?: return null
        return doc.toNode()
    }

    override fun children(dir: StorageNode): List<StorageNode> =
        document(dir)?.listFiles()?.mapNotNull { it.toNode() }.orEmpty()

    override fun createDirectory(parent: StorageNode, name: String): StorageNode? =
        document(parent)?.createDirectory(name)?.toNode()

    override fun createFile(parent: StorageNode, mimeType: String, name: String): StorageNode? =
        document(parent)?.createFile(mimeType, name)?.toNode()

    override fun rename(node: StorageNode, newName: String): StorageNode? {
        val doc = document(node) ?: return null
        // TreeDocumentFile.renameTo updates the document's URI in place on success.
        return if (doc.renameTo(newName)) doc.toNode() else null
    }

    override fun delete(node: StorageNode): Boolean = document(node)?.delete() ?: false

    override fun read(node: StorageNode): String {
        val input = context.contentResolver.openInputStream(node.id.toUri())
            ?: throw IOException("Could not open ${node.name} for reading")
        return input.bufferedReader().use { it.readText() }
    }

    override fun write(node: StorageNode, content: String) {
        val output = context.contentResolver.openOutputStream(node.id.toUri(), "wt")
            ?: throw IOException("Could not open ${node.name} for writing")
        output.bufferedWriter().use { it.write(content) }
    }

    private fun document(node: StorageNode): DocumentFile? =
        DocumentFile.fromTreeUri(context, node.id.toUri())

    private fun DocumentFile.toNode(): StorageNode? {
        val name = name ?: return null
        return StorageNode(uri.toString(), name, isDirectory)
    }
}
