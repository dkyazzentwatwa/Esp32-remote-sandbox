package org.espsketchide.buildengine

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/** Our assembled + expanded properties must equal arduino-cli's `--show-properties`. */
class BuildPropertiesGoldenTest {

    private fun check(propsFile: String, selection: Map<String, String>) {
        val env = GoldenEnv.env()
        val expected = GoldenEnv.shownProperties(propsFile)
        val request = BuildRequest(
            platform = GoldenEnv.platform(),
            boardId = "esp32",
            menuSelection = selection,
            sketchDir = File(env.getValue("SKETCH_DIR")),
            buildDir = File(env.getValue("BUILD_DIR")),
        )
        val ours = BuildProperties.assemble(request)

        // Values that legitimately differ run to run, or depend on arduino-cli's own install.
        // Also not build-related: serial monitor defaults and programmers.txt tools.
        val ignored = setOf(
            "extra.time.utc", "extra.time.local", "extra.time.zone", "extra.time.dst", "runtime.ide.path",
            "monitor_port.serial.dtr", "monitor_port.serial.rts", "tools.avrdude.path",
        )
        val mismatches = expected.filterKeys { it !in ignored }.mapNotNull { (key, value) ->
            val actual = ours[key]?.let(ours::expand)
            if (actual == value) null else "$key\n    expected: $value\n    actual:   $actual"
        }
        val extra = ours.keys.filter { it !in expected && it !in ignored }
        assertWithMessage("mismatched keys:\n" + mismatches.take(15).joinToString("\n")).that(mismatches).isEmpty()
        assertWithMessage("keys arduino-cli doesn't have").that(extra).isEmpty()
    }

    @Test
    fun defaultEsp32DevModule() = check("show-properties-default.txt", emptyMap())

    @Test
    fun withPartitionAndDebugMenus() =
        check("show-properties-menus.txt", mapOf("PartitionScheme" to "huge_app", "DebugLevel" to "info"))
}
