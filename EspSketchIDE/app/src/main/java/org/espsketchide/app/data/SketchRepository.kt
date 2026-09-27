package org.espsketchide.app.data

import org.espsketchide.app.model.Sketch
import org.espsketchide.app.model.SketchFile

private val VALID_SKETCH_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*$")
private val EDITABLE_EXTENSIONS = setOf("ino", "h", "hpp", "c", "cpp", "cc", "txt")

class SketchNameInvalidException : Exception()
class SketchAlreadyExistsException : Exception()

/**
 * Reads and writes sketches as folders under the user-chosen sketchbook root, following the
 * Arduino convention that a sketch folder's name matches its primary .ino file's name.
 */
class SketchRepository(private val storage: SketchStorage) {

    fun hasRoot(): Boolean = storage.root() != null

    fun isValidSketchName(name: String): Boolean = VALID_SKETCH_NAME.matches(name)

    fun listSketches(): List<Sketch> {
        val root = storage.root() ?: return emptyList()
        return storage.children(root)
            .filter { it.isDirectory }
            .sortedBy { it.name.lowercase() }
            .map { Sketch(it.name, it.id) }
    }

    @Throws(SketchNameInvalidException::class, SketchAlreadyExistsException::class)
    fun createSketch(name: String, content: String = defaultSketchTemplate()): Sketch {
        if (!isValidSketchName(name)) throw SketchNameInvalidException()
        val root = storage.root() ?: throw IllegalStateException("No sketches root set")
        if (storage.find(root, name) != null) throw SketchAlreadyExistsException()

        val folder = storage.createDirectory(root, name)
            ?: throw IllegalStateException("Could not create sketch folder")
        val inoFile = storage.createFile(folder, "text/x-arduino", "$name.ino")
            ?: throw IllegalStateException("Could not create $name.ino")
        storage.write(inoFile, content)
        return Sketch(name, folder.id)
    }

    @Throws(SketchNameInvalidException::class, SketchAlreadyExistsException::class)
    fun renameSketch(sketch: Sketch, newName: String) {
        if (!isValidSketchName(newName)) throw SketchNameInvalidException()
        val root = storage.root() ?: throw IllegalStateException("No sketches root set")
        if (storage.find(root, newName) != null) throw SketchAlreadyExistsException()

        val folder = folderNode(sketch)
        val primaryIno = storage.find(folder, sketch.primaryFileName)
        storage.rename(folder, newName)
        primaryIno?.let { storage.rename(it, "$newName.ino") }
    }

    fun deleteSketch(sketch: Sketch) {
        storage.delete(folderNode(sketch))
    }

    fun listFiles(sketch: Sketch): List<SketchFile> {
        val files = storage.children(folderNode(sketch))
            .filter { !it.isDirectory && extensionOf(it.name) in EDITABLE_EXTENSIONS }
            .map { SketchFile(it.name, it.id) }

        // Primary .ino file first, then the rest alphabetically.
        return files.sortedWith(
            compareBy({ it.name != sketch.primaryFileName }, { it.name.lowercase() })
        )
    }

    @Throws(SketchNameInvalidException::class, SketchAlreadyExistsException::class)
    fun addFile(sketch: Sketch, fileName: String): SketchFile {
        val folder = folderNode(sketch)
        val ext = extensionOf(fileName)
        if (fileName.isBlank() || ext !in EDITABLE_EXTENSIONS || fileName.count { it == '.' } != 1) {
            throw SketchNameInvalidException()
        }
        if (storage.find(folder, fileName) != null) throw SketchAlreadyExistsException()

        val file = storage.createFile(folder, "text/plain", fileName)
            ?: throw IllegalStateException("Could not create $fileName")
        return SketchFile(fileName, file.id)
    }

    fun readFile(file: SketchFile): String = storage.read(fileNode(file))

    fun writeFile(file: SketchFile, content: String) = storage.write(fileNode(file), content)

    private fun folderNode(sketch: Sketch) = StorageNode(sketch.folderId, sketch.name, isDirectory = true)

    private fun fileNode(file: SketchFile) = StorageNode(file.id, file.name, isDirectory = false)

    private fun extensionOf(fileName: String): String = fileName.substringAfterLast('.', "")

    private fun defaultSketchTemplate(): String = """
        void setup() {
          Serial.begin(115200);

        }

        void loop() {

        }
    """.trimIndent() + "\n"
}
