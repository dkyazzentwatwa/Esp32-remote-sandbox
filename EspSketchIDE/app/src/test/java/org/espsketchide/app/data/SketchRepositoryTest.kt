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
}
