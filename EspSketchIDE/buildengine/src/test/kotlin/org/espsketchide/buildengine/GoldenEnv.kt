package org.espsketchide.buildengine

import org.junit.Assume.assumeTrue
import java.io.File

/**
 * Golden tests compare the engine with arduino-cli on a real ESP32 core install. They are
 * skipped unless ESP32_REFERENCE_DIR points at output of `buildengine/golden/make-reference.sh`,
 * whose `env` file records where the core, sketches and build dirs were.
 */
object GoldenEnv {
    val referenceDir: File? = System.getenv("ESP32_REFERENCE_DIR")?.let(::File)

    fun require(): File {
        assumeTrue("golden reference not configured (ESP32_REFERENCE_DIR)", referenceDir?.isDirectory == true)
        return referenceDir!!
    }

    fun env(): Map<String, String> =
        File(require(), "env").readLines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }

    /** The platform as arduino-cli installed it, with every tool under packages/<vendor>/tools. */
    fun platform(): Platform {
        val e = env()
        val arduino15 = File(e.getValue("ARDUINO15"))
        val tools = LinkedHashMap<String, File>()
        File(arduino15, "packages").listFiles().orEmpty().sortedBy { it.name }.forEach { vendor ->
            File(vendor, "tools").listFiles().orEmpty().sortedBy { it.name }.forEach { tool ->
                tool.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }.forEach { version ->
                    tools[tool.name] = version
                    tools["${tool.name}-${version.name}"] = version
                }
            }
        }
        return Platform(File(e.getValue("PLATFORM_DIR")), "esp32", "esp32", tools)
    }

    /** `key=value` dump from `arduino-cli compile --show-properties`. */
    fun shownProperties(name: String): Map<String, String> =
        File(require(), name).readLines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
}
