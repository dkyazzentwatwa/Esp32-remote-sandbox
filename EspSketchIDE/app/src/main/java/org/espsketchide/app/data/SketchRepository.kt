package org.espsketchide.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.espsketchide.app.model.Sketch
import org.espsketchide.app.model.SketchFile
import org.espsketchide.app.settings.Board
import org.espsketchide.app.settings.PREFS_NAME
import org.espsketchide.app.templates.SketchTemplates
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

private const val KEY_ROOT_URI = "sketches_root_uri"

private val VALID_SKETCH_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*$")
private val EDITABLE_EXTENSIONS = setOf("ino", "h", "hpp", "c", "cpp", "cc", "txt")

class SketchNameInvalidException : Exception()
class SketchAlreadyExistsException : Exception()

/**
 * Reads and writes sketches as folders under a user-chosen Storage Access Framework
 * root, following the Arduino convention that a sketch folder's name matches its
 * primary .ino file's name.
 */
class SketchRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasRoot(): Boolean = rootUri() != null

    fun rootUri(): Uri? {
        val stored = prefs.getString(KEY_ROOT_URI, null) ?: return null
        val uri = Uri.parse(stored)
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
        prefs.edit().putString(KEY_ROOT_URI, treeUri.toString()).apply()
    }

    private fun rootDocument(): DocumentFile? {
        val uri = rootUri() ?: return null
        return DocumentFile.fromTreeUri(context, uri)
    }

    fun isValidSketchName(name: String): Boolean = VALID_SKETCH_NAME.matches(name)

    fun listSketches(): List<Sketch> {
        val root = rootDocument() ?: return emptyList()
        return root.listFiles()
            .filter { it.isDirectory && it.name != null }
            .sortedBy { it.name?.lowercase() }
            .map { Sketch(it.name!!, it.uri) }
    }

    /** [base] if no entry in the sketchbook has that name, otherwise `base_2`, `base_3`, … */
    fun uniqueName(base: String): String {
        val root = rootDocument() ?: return base
        val taken = root.listFiles().mapNotNull { it.name }.toSet()
        return SketchNames.nextFreeName(base, taken)
    }

    /** Creates a sketch whose only file is `<name>.ino` holding [primaryContent]. */
    @Throws(SketchNameInvalidException::class, SketchAlreadyExistsException::class)
    fun createSketch(name: String, primaryContent: String = SketchTemplates.contentFor(SketchTemplates.BARE, Board.DEFAULT)): Sketch =
        createSketch(name, mapOf("$name.ino" to primaryContent))

    /**
     * Creates a sketch from [files] (file name to content), e.g. an example. The sketch must contain
     * its primary `<name>.ino`. If writing any file fails the half-made folder is removed again.
     */
    @Throws(SketchNameInvalidException::class, SketchAlreadyExistsException::class)
    fun createSketch(name: String, files: Map<String, String>): Sketch {
        if (!isValidSketchName(name)) throw SketchNameInvalidException()
        require("$name.ino" in files) { "A sketch needs its primary $name.ino" }
        val root = rootDocument() ?: throw IllegalStateException("No sketches root set")
        if (root.findFile(name) != null) throw SketchAlreadyExistsException()

        val folder = root.createDirectory(name) ?: throw IllegalStateException("Could not create sketch folder")
        try {
            for ((fileName, content) in files) {
                // application/octet-stream keeps the display name as-is (see addFile).
                val file = folder.createFile("application/octet-stream", fileName)
                    ?: throw IllegalStateException("Could not create $fileName")
                writeFile(file.uri, content)
            }
        } catch (e: Exception) {
            folder.delete()
            throw e
        }
        return Sketch(name, folder.uri)
    }

    @Throws(SketchNameInvalidException::class, SketchAlreadyExistsException::class)
    fun renameSketch(sketch: Sketch, newName: String) {
        if (!isValidSketchName(newName)) throw SketchNameInvalidException()
        if (newName == sketch.name) return
        val root = rootDocument() ?: throw IllegalStateException("No sketches root set")
        if (root.findFile(newName) != null) throw SketchAlreadyExistsException()

        val folder = DocumentFile.fromTreeUri(context, sketch.folderUri)
            ?: throw IllegalStateException("Sketch folder not found")
        // Rename the .ino before its folder: on path-based providers (local storage)
        // renaming the folder changes every child's document URI, so a handle to the
        // .ino taken beforehand would be stale and its rename would silently fail.
        val primaryIno = folder.findFile("${sketch.name}.ino")
        if (primaryIno != null && !primaryIno.renameTo("$newName.ino")) {
            throw IllegalStateException("Could not rename ${sketch.name}.ino")
        }
        if (!folder.renameTo(newName)) {
            primaryIno?.renameTo("${sketch.name}.ino")
            throw IllegalStateException("Could not rename sketch folder")
        }
    }

    fun deleteSketch(sketch: Sketch) {
        DocumentFile.fromTreeUri(context, sketch.folderUri)?.delete()
    }

    fun listFiles(sketch: Sketch): List<SketchFile> {
        val folder = DocumentFile.fromTreeUri(context, sketch.folderUri) ?: return emptyList()
        val files = folder.listFiles()
            .filter { it.isFile && it.name != null && extensionOf(it.name!!) in EDITABLE_EXTENSIONS }
            .map { SketchFile(it.name!!, it.uri) }

        // Primary .ino file first, then the rest alphabetically.
        val primaryName = "${sketch.name}.ino"
        return files.sortedWith(
            compareBy({ it.name != primaryName }, { it.name.lowercase() })
        )
    }

    @Throws(SketchNameInvalidException::class, SketchAlreadyExistsException::class)
    fun addFile(sketch: Sketch, fileName: String): SketchFile {
        val folder = DocumentFile.fromTreeUri(context, sketch.folderUri)
            ?: throw IllegalStateException("Sketch folder not found")
        val ext = extensionOf(fileName)
        if (fileName.isBlank() || ext !in EDITABLE_EXTENSIONS || fileName.count { it == '.' } != 1) {
            throw SketchNameInvalidException()
        }
        if (folder.findFile(fileName) != null) throw SketchAlreadyExistsException()

        // application/octet-stream keeps the display name as-is; with a concrete MIME
        // type like text/plain, providers append a matching extension (config.h.txt).
        val file = folder.createFile("application/octet-stream", fileName)
            ?: throw IllegalStateException("Could not create $fileName")
        return SketchFile(fileName, file.uri)
    }

    fun readFile(uri: Uri): String {
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Could not open $uri for reading" }
            return BufferedReader(InputStreamReader(input)).readText()
        }
    }

    fun writeFile(uri: Uri, content: String) {
        context.contentResolver.openOutputStream(uri, "wt").use { output ->
            requireNotNull(output) { "Could not open $uri for writing" }
            OutputStreamWriter(output).use { it.write(content) }
        }
    }

    private fun extensionOf(fileName: String): String = fileName.substringAfterLast('.', "")
}
