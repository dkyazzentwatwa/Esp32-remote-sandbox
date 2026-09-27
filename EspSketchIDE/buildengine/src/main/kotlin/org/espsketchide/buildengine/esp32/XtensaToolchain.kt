package org.espsketchide.buildengine.esp32

import org.espsketchide.buildengine.ProcessResult
import org.espsketchide.buildengine.ProcessRunner
import java.io.File

/**
 * Runs Arduino-ESP32 recipes against a relocated xtensa toolchain, the way the app lays it out
 * on a phone: [treeDir] is a gcc-shaped folder (bin/, libexec/gcc/..., xtensa-esp-elf/,
 * lib/gcc/..., lib/xtensa_<chip>.so) whose programs may be links to files elsewhere.
 *
 * Recipes call the chip wrappers (`xtensa-esp32-elf-g++`); this maps them to the unified tools
 * and adds what the wrappers and the normal install layout would have provided:
 *  - `GCC_EXEC_PREFIX` and `-B` so gcc finds cc1/as/ld (it resolves links in its own path);
 *  - `--sysroot` for the target headers and libraries;
 *  - exactly `-mdynconfig=xtensa_<chip>.so` (gcc picks the multilib by that literal text) with
 *    `XTENSA_GNU_CONFIG=<tree>/lib/` so gcc/as/ld load the chip plugin from there.
 * See docs/superpowers/spikes/p0-results.md for how each rule was established.
 */
class XtensaToolchainRunner(
    private val delegate: ProcessRunner,
    private val treeDir: File,
    private val chip: String = "esp32",
    private val gccVersion: String = "14.2.0",
    private val tmpDir: File? = null,
) : ProcessRunner {

    val environment: Map<String, String> = buildMap {
        put("GCC_EXEC_PREFIX", "${treeDir.path}/lib/gcc/")
        put("XTENSA_GNU_CONFIG", "${treeDir.path}/lib/")
        tmpDir?.let { put("TMPDIR", it.path) }
    }

    override fun run(argv: List<String>, workDir: File?): ProcessResult = delegate.run(rewrite(argv), workDir)

    fun rewrite(argv: List<String>): List<String> {
        val program = File(argv.first()).name
        val prefix = "xtensa-$chip-elf-"
        if (!program.startsWith(prefix) && !program.startsWith("xtensa-esp-elf-")) return argv
        val tool = program.removePrefix(prefix).removePrefix("xtensa-esp-elf-")
        val rest = argv.drop(1)
        return when (tool) {
            "gcc", "g++", "c++", "cpp" -> listOf(bin(tool)) + driverFlags() + rest
            // gcc-ar only adds the LTO plugin, which this toolchain doesn't have.
            "gcc-ar", "ar" -> listOf(bin("ar")) + rest
            else -> listOf(bin(tool)) + rest
        }
    }

    private fun bin(tool: String) = File(treeDir, "bin/xtensa-esp-elf-$tool").path

    private fun driverFlags() = listOf(
        "-B${treeDir.path}/libexec/gcc/xtensa-esp-elf/$gccVersion/",
        "-B${treeDir.path}/xtensa-esp-elf/bin/",
        "--sysroot=${treeDir.path}/xtensa-esp-elf",
        "-mdynconfig=xtensa_$chip.so",
    )
}
