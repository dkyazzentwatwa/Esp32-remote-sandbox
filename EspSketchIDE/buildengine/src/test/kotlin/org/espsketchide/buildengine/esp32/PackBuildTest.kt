package org.espsketchide.buildengine.esp32

import com.google.common.truth.Truth.assertWithMessage
import org.espsketchide.buildengine.BuildRequest
import org.espsketchide.buildengine.Builder
import org.espsketchide.buildengine.GoldenEnv
import org.espsketchide.buildengine.LocalProcessRunner
import org.espsketchide.buildengine.Platform
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * The board pack (toolchain/make-pack.py) must be complete: every bundled example builds from
 * the pack alone (plus host programs, here Espressif's desktop ones in the phone layout).
 * Sources now live under the pack's path, so embedded path strings (__FILE__ in asserts/logs)
 * differ from arduino-cli's build and shift rodata addresses inside the code; byte equality is
 * checked elsewhere with identical paths (BuilderGoldenTest). Here code and data sections must
 * have exactly arduino-cli's sizes. Needs ESP32_PACK_DIR and ESP32_REFERENCE_DIR.
 */
class PackBuildTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun everyExampleBuildsFromThePack() {
        val packDir = System.getenv("ESP32_PACK_DIR")?.let(::File)
        assumeTrue("ESP32_PACK_DIR not set", packDir?.isDirectory == true)
        val ref = GoldenEnv.require()
        val pack = PackLayout.read(packDir!!)
        val desktopTools = GoldenEnv.platform().tools.getValue("esp-x32")

        // Phone-style tree: host programs (desktop builds here) + the pack's target files.
        val tree = tmp.newFolder("tc")
        fun link(at: String, target: File) {
            val f = File(tree, at).apply { parentFile.mkdirs() }
            Files.createSymbolicLink(f.toPath(), target.toPath())
        }
        val v = pack.gccVersion
        for (t in listOf("gcc", "g++", "ar", "size")) link("bin/xtensa-esp-elf-$t", File(desktopTools, "bin/xtensa-esp-elf-$t"))
        for (p in listOf("cc1", "cc1plus", "collect2", "liblto_plugin.so")) link("libexec/gcc/xtensa-esp-elf/$v/$p", File(desktopTools, "libexec/gcc/xtensa-esp-elf/$v/$p"))
        for (p in listOf("as", "ld", "ar")) link("xtensa-esp-elf/bin/$p", File(desktopTools, "bin/xtensa-esp-elf-$p"))
        link("lib/xtensa_esp32.so", File(desktopTools, "lib/xtensa_esp32.so"))
        pack.linkTargetFiles(tree)

        val platform = pack.platform(toolchainTree = tree)
        val runner = XtensaToolchainRunner(LocalProcessRunner(XtensaToolchainRunner(LocalProcessRunner(), tree).environment), tree)
        val objcopy = File(desktopTools, "bin/xtensa-esp-elf-objcopy").path
        fun section(elf: File, name: String): ByteArray {
            val out = tmp.newFile()
            check(ProcessBuilder(objcopy, "-O", "binary", "-j", name, elf.path, out.path).start().waitFor() == 0)
            return out.readBytes()
        }

        for (name in listOf("Blink", "HelloSerial", "AnalogReadSerial", "Button", "Fade", "WiFiScan", "WiFiAccessPointLed", "BLEScan")) {
            val request = BuildRequest(platform, pack.defaultBoard, sketchDir = File(ref, "sketches/$name"), buildDir = tmp.newFolder("build-$name"))
            val result = Builder(request, Esp32BuildProfile, runner).build()
            for (sec in listOf(".flash.text", ".iram0.text", ".dram0.data")) {
                assertWithMessage("$name $sec size").that(section(result.elf, sec).size)
                    .isEqualTo(section(File(ref, "builds/$name/$name.ino.elf"), sec).size)
            }
        }
    }
}
