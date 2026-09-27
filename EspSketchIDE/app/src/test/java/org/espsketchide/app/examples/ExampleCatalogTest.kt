package org.espsketchide.app.examples

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

class ExampleCatalogTest {

    private val root = File("src/main/assets/examples")

    @Test
    fun categoryPrefixIsHidden() {
        assertThat(AssetExampleSource.displayCategory("01.Basics")).isEqualTo("Basics")
        assertThat(AssetExampleSource.displayCategory("Misc")).isEqualTo("Misc")
    }

    /** Every bundled example must be a valid sketch: folder name == primary .ino name. */
    @Test
    fun bundledExamplesAreValidSketches() {
        val examples = root.listFiles().orEmpty().filter { it.isDirectory }.flatMap { it.listFiles().orEmpty().toList() }
        assertThat(examples).isNotEmpty()
        examples.forEach { dir ->
            assertThat(File(dir, "${dir.name}.ino").isFile).isTrue()
            assertThat(dir.name).matches("[A-Za-z][A-Za-z0-9_]*")
        }
    }
}
