package org.espsketchide.app.data

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class SketchRepositoryTest {

    private val storage = InMemoryStorage()
    private val repository = SketchRepository(storage)

    @Test
    fun noRootMeansNoSketches() {
        val repo = SketchRepository(InMemoryStorage(hasRoot = false))
        assertThat(repo.hasRoot()).isFalse()
        assertThat(repo.listSketches()).isEmpty()
    }

    @Test
    fun createSketchWritesPrimaryInoWithTemplate() {
        val sketch = repository.createSketch("Blink")

        assertThat(sketch.name).isEqualTo("Blink")
        assertThat(storage.contentOf("Blink/Blink.ino")).contains("void setup()")
        assertThat(repository.listFiles(sketch).map { it.name }).containsExactly("Blink.ino")
    }

    @Test
    fun createSketchRejectsInvalidAndDuplicateNames() {
        repository.createSketch("Blink")
        assertThrows(SketchNameInvalidException::class.java) { repository.createSketch("1Blink") }
        assertThrows(SketchNameInvalidException::class.java) { repository.createSketch("my sketch") }
        assertThrows(SketchAlreadyExistsException::class.java) { repository.createSketch("Blink") }
    }

    @Test
    fun readAndWriteRoundTrip() {
        val sketch = repository.createSketch("Blink")
        val file = repository.listFiles(sketch).single()

        repository.writeFile(file, "void loop() {}\n")

        assertThat(repository.readFile(file)).isEqualTo("void loop() {}\n")
    }

    @Test
    fun listFilesPutsPrimaryInoFirstThenAlphabetical() {
        storage.put("Blink/zeta.h", "")
        storage.put("Blink/Alpha.cpp", "")
        storage.put("Blink/Blink.ino", "")
        storage.put("Blink/notes.md", "")

        val names = repository.listFiles(repository.listSketches().single()).map { it.name }

        assertThat(names).containsExactly("Blink.ino", "Alpha.cpp", "zeta.h").inOrder()
    }

    // Bug 1: text/plain made SAF save pins.h as pins.h.txt.
    @Test
    fun addFileKeepsHeaderExtension() {
        val sketch = repository.createSketch("Blink")

        val added = repository.addFile(sketch, "pins.h")

        assertThat(added.name).isEqualTo("pins.h")
        assertThat(storage.exists("Blink/pins.h")).isTrue()
        assertThat(storage.exists("Blink/pins.h.txt")).isFalse()
        assertThat(repository.listFiles(sketch).map { it.name }).contains("pins.h")
    }

    @Test
    fun addFileRejectsBadNames() {
        val sketch = repository.createSketch("Blink")
        assertThrows(SketchNameInvalidException::class.java) { repository.addFile(sketch, "a.b.h") }
        assertThrows(SketchNameInvalidException::class.java) { repository.addFile(sketch, "run.sh") }
        assertThrows(SketchNameInvalidException::class.java) { repository.addFile(sketch, " ") }
    }

    // Bug 2: renaming the folder first left the primary .ino with its old name.
    @Test
    fun renameSketchRenamesFolderAndPrimaryIno() {
        val sketch = repository.createSketch("Blink")
        repository.addFile(sketch, "pins.h")

        repository.renameSketch(sketch, "Blinky")

        assertThat(storage.exists("Blinky/Blinky.ino")).isTrue()
        assertThat(storage.exists("Blinky/pins.h")).isTrue()
        assertThat(storage.exists("Blinky/Blink.ino")).isFalse()
        assertThat(storage.exists("Blink")).isFalse()
        assertThat(repository.listSketches().map { it.name }).containsExactly("Blinky")
    }

    @Test
    fun renameSketchRollsBackInoWhenFolderRenameFails() {
        val sketch = repository.createSketch("Blink")
        storage.failRenamesTo += "Blinky"

        assertThrows(SketchRenameFailedException::class.java) { repository.renameSketch(sketch, "Blinky") }

        assertThat(storage.exists("Blink/Blink.ino")).isTrue()
        assertThat(storage.exists("Blink/Blinky.ino")).isFalse()
    }

    @Test
    fun renameSketchFailsCleanlyWhenInoRenameFails() {
        val sketch = repository.createSketch("Blink")
        storage.failRenamesTo += "Blinky.ino"

        assertThrows(SketchRenameFailedException::class.java) { repository.renameSketch(sketch, "Blinky") }

        assertThat(storage.exists("Blink/Blink.ino")).isTrue()
    }

    // Bug 5: folders without a matching .ino (libraries/, notes/) were listed as sketches.
    @Test
    fun listSketchesOnlyIncludesFoldersWithMatchingIno() {
        storage.put("Blink/Blink.ino", "")
        storage.put("libraries/Servo/Servo.h", "")
        storage.put("notes/todo.txt", "")
        storage.put("Mismatch/Other.ino", "")
        storage.put("stray.ino", "")

        assertThat(repository.listSketches().map { it.name }).containsExactly("Blink")
    }

    // Bug 5: delete ignored failures.
    @Test
    fun deleteSketchReportsFailure() {
        val sketch = repository.createSketch("Blink")
        storage.failDeletesOf += sketch.folderId

        assertThrows(SketchDeleteFailedException::class.java) { repository.deleteSketch(sketch) }
        assertThat(storage.exists("Blink/Blink.ino")).isTrue()
    }

    @Test
    fun deleteSketchRemovesFolder() {
        val sketch = repository.createSketch("Blink")

        repository.deleteSketch(sketch)

        assertThat(storage.paths()).isEmpty()
    }

    @Test
    fun copyFromExampleUsesExampleName() {
        val sketch = repository.createFromFiles("Blink", mapOf("Blink.ino" to "blink();", "pins.h" to "#define P 2"))

        assertThat(sketch.name).isEqualTo("Blink")
        assertThat(storage.contentOf("Blink/Blink.ino")).isEqualTo("blink();")
        assertThat(storage.contentOf("Blink/pins.h")).isEqualTo("#define P 2")
    }

    @Test
    fun copyFromExamplePicksFreeNameAndRenamesPrimaryIno() {
        repository.createSketch("Blink")
        repository.createSketch("Blink_2")

        val sketch = repository.createFromFiles("Blink", mapOf("Blink.ino" to "blink();"))

        assertThat(sketch.name).isEqualTo("Blink_3")
        assertThat(storage.contentOf("Blink_3/Blink_3.ino")).isEqualTo("blink();")
        assertThat(storage.exists("Blink_3/Blink.ino")).isFalse()
    }

    @Test
    fun copyFromExampleSkipsUnsupportedFiles() {
        val sketch = repository.createFromFiles("Web", mapOf("Web.ino" to "x", "data.bin" to "y", "a.b.h" to "z"))

        assertThat(repository.listFiles(sketch).map { it.name }).containsExactly("Web.ino")
    }
}
