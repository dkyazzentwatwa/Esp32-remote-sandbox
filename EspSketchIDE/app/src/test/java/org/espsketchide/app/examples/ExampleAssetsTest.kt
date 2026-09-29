package org.espsketchide.app.examples

import org.espsketchide.app.settings.Board
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Static checks on the bundled example sketches. Gradle runs unit tests with the module directory
 * as the working directory, so the assets are read straight from src/main/assets.
 */
class ExampleAssetsTest {

    private val root = File("src/main/assets/examples")
    private val examples = ExampleCatalog.parse(File(root, "index.json").readText())
    private val editableExtensions = setOf("ino", "h", "hpp", "c", "cpp", "cc", "txt")

    private fun variants(): List<Triple<Example, String, String>> =
        examples.flatMap { ex -> ex.variants.map { (board, path) -> Triple(ex, board, path) } }

    @Test
    fun `index is not empty and ids are unique`() {
        assertTrue(examples.isNotEmpty())
        assertEquals(examples.size, examples.map { it.id }.toSet().size)
    }

    @Test
    fun `every example has a name that is a valid sketch name`() {
        val valid = Regex("^[A-Za-z][A-Za-z0-9_]*$")
        examples.forEach { assertTrue("${it.name} is not a valid sketch name", valid.matches(it.name)) }
    }

    @Test
    fun `every variant folder exists and holds exactly one ino named like the example`() {
        for ((ex, board, path) in variants()) {
            val dir = File(root, path)
            assertTrue("$path missing (${ex.id}/$board)", dir.isDirectory)
            val inos = dir.listFiles().orEmpty().filter { it.extension == "ino" }
            assertEquals("$path needs exactly one .ino", 1, inos.size)
            assertEquals("${ex.name}.ino", inos.single().name)
        }
    }

    @Test
    fun `every file in a variant folder has an editable extension`() {
        for ((_, _, path) in variants()) {
            File(root, path).listFiles().orEmpty().forEach {
                assertTrue("${it.path} is not an editable file type", it.extension in editableExtensions)
            }
        }
    }

    @Test
    fun `every folder under examples is referenced by the index`() {
        val referenced = variants().map { it.third }.toSet()
        val onDisk = root.walkTopDown()
            .filter { it != root && it.isDirectory && it.listFiles().orEmpty().any { f -> f.isFile } }
            .map { it.relativeTo(root).path }
            .toSet()
        assertEquals(referenced, onDisk)
    }

    @Test
    fun `every sketch has setup and loop and balanced braces`() {
        for ((_, _, path) in variants().distinctBy { it.third }) {
            val text = File(root, path).listFiles().orEmpty().first { it.extension == "ino" }.readText()
            assertTrue("$path: setup()", "void setup()" in text)
            assertTrue("$path: loop()", "void loop()" in text)
            assertEquals("$path: braces", text.count { it == '{' }, text.count { it == '}' })
            assertEquals("$path: parentheses", text.count { it == '(' }, text.count { it == ')' })
        }
    }

    @Test
    fun `a board's variants only include that board's wifi headers`() {
        for ((ex, board, path) in variants()) {
            val text = File(root, path).listFiles().orEmpty().first { it.extension == "ino" }.readText()
            when (board) {
                Board.ESP32.key -> {
                    assertFalse("$path (${ex.id}) includes an ESP8266 header", "#include <ESP8266" in text)
                }
                Board.ESP8266.key -> {
                    assertFalse("$path (${ex.id}) includes WiFi.h", "#include <WiFi.h>" in text)
                    assertFalse("$path (${ex.id}) includes WebServer.h", "#include <WebServer.h>" in text)
                    assertFalse("$path (${ex.id}) includes HTTPClient.h", "#include <HTTPClient.h>" in text)
                }
            }
        }
    }

    @Test
    fun `each board gets at least one example per category it has`() {
        for (board in Board.entries) {
            val groups = ExampleCatalog.forBoard(examples, board)
            assertTrue(groups.isNotEmpty())
            assertTrue(groups.all { it.second.isNotEmpty() })
        }
    }
}
