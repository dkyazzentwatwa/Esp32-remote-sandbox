package org.espsketchide.app.compile

import com.google.common.truth.Truth.assertThat
import org.espsketchide.app.data.InMemoryStorage
import org.espsketchide.app.model.Sketch
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SketchStagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val storage = InMemoryStorage().apply {
        put("Blink/Blink.ino", "void setup() {}\nvoid loop() {}\n")
        put("Blink/pins.h", "#define LED 2\n")
        put("Blink/notes.txt", "not compiled")
        put("Blink/src/util/helper.cpp", "int helper() { return 1; }\n")
        put("Blink/data/image.bin", "ignored")
    }
    private val sketch = Sketch("Blink", storage.root()!!.id + "/Blink")

    private fun files(dir: java.io.File) = dir.walk().filter { it.isFile }.map { it.relativeTo(dir).path }.toList().sorted()

    @Test
    fun copiesSourcesAndSrcTree() {
        val out = SketchStager(storage).stage(sketch, tmp.root)

        assertThat(out.name).isEqualTo("Blink")
        assertThat(files(out)).containsExactly("Blink.ino", "pins.h", "src/util/helper.cpp").inOrder()
        assertThat(out.resolve("pins.h").readText()).isEqualTo("#define LED 2\n")
    }

    @Test
    fun unchangedFilesKeepTheirTimestampAndRemovedFilesGo() {
        val stager = SketchStager(storage)
        val out = stager.stage(sketch, tmp.root)
        val ino = out.resolve("Blink.ino").apply { setLastModified(1_000_000) }

        storage.put("Blink/pins.h", "#define LED 5\n")
        stager.stage(sketch, tmp.root)
        assertThat(ino.lastModified()).isEqualTo(1_000_000)
        assertThat(out.resolve("pins.h").readText()).isEqualTo("#define LED 5\n")

        val withoutSrc = InMemoryStorage().apply { put("Blink/Blink.ino", "void setup() {}\nvoid loop() {}\n") }
        SketchStager(withoutSrc).stage(Sketch("Blink", withoutSrc.root()!!.id + "/Blink"), tmp.root)
        assertThat(files(out)).containsExactly("Blink.ino")
        assertThat(out.resolve("src").exists()).isFalse()
    }

    @Test
    fun missingPrimaryFileIsAnError() {
        val broken = InMemoryStorage().apply { put("Blink/other.ino", "") }

        assertThrows(IllegalStateException::class.java) {
            SketchStager(broken).stage(Sketch("Blink", broken.root()!!.id + "/Blink"), tmp.root)
        }
    }
}
