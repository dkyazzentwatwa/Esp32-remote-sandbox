package org.espsketchide.buildengine

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.espsketchide.buildengine.esp32.Esp32BuildProfile
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Full builds with our engine (and the desktop toolchain) compared with arduino-cli's builds of
 * the same sketches. Only __DATE__/__TIME__ strings may differ, so code sections must be equal.
 */
class BuilderGoldenTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val sketchUses = Regex("""Sketch uses (\d+) bytes""")
    private val globalsUse = Regex("""Global variables use (\d+) bytes""")

    private fun section(objcopy: String, elf: File, name: String): ByteArray {
        val out = tmp.newFile()
        val p = ProcessBuilder(objcopy, "-O", "binary", "-j", name, elf.path, out.path).redirectErrorStream(true).start()
        check(p.waitFor() == 0) { p.inputStream.bufferedReader().readText() }
        return out.readBytes()
    }

    private fun check(name: String) {
        val ref = GoldenEnv.require()
        val platform = GoldenEnv.platform()
        val refBuild = File(ref, "builds/$name")
        val buildDir = tmp.newFolder("build-$name")
        val request = BuildRequest(platform, "esp32", sketchDir = File(ref, "sketches/$name"), buildDir = buildDir)
        val events = mutableListOf<BuildEvent>()

        val result = Builder(request, Esp32BuildProfile, LocalProcessRunner(), listener = { synchronized(events) { events += it } }).build()

        val refLog = File(ref, "builds/$name.verbose.txt").readText()
        assertWithMessage("program size").that(result.programSize).isEqualTo(sketchUses.find(refLog)!!.groupValues[1].toLong())
        assertWithMessage("data size").that(result.dataSize).isEqualTo(globalsUse.find(refLog)!!.groupValues[1].toLong())
        for (file in listOf("sketch/$name.ino.cpp", "$name.ino.partitions.bin", "$name.ino.bootloader.bin", "flash_args", "sdkconfig")) {
            assertWithMessage(file).that(File(buildDir, file).readBytes()).isEqualTo(File(refBuild, file).readBytes())
        }
        val objcopy = File(platform.tools.getValue("esp-x32"), "bin/xtensa-esp-elf-objcopy").path
        for (sec in listOf(".flash.text", ".iram0.text", ".dram0.data", ".flash.appdesc")) {
            assertWithMessage("$name $sec").that(section(objcopy, result.elf, sec)).isEqualTo(section(objcopy, File(refBuild, "$name.ino.elf"), sec))
        }
        assertThat(section(objcopy, result.elf, ".flash.rodata").size).isEqualTo(section(objcopy, File(refBuild, "$name.ino.elf"), ".flash.rodata").size)
        assertThat(result.flashImages.map { it.first }).containsExactly(0x1000, 0x8000, 0xE000, 0x10000).inOrder()
        assertThat(File(buildDir, "$name.ino.merged.bin").length()).isEqualTo(4L * 1024 * 1024)

        // A second build with nothing changed recompiles nothing.
        val again = Builder(request, Esp32BuildProfile, LocalProcessRunner()).build()
        assertWithMessage("recompiled on unchanged rebuild").that(again.compiled).isEqualTo(0)
        assertThat(again.reused).isEqualTo(result.compiled + result.reused)
    }

    @Test fun blink() = check("Blink")
    @Test fun wifiAccessPointLed() = check("WiFiAccessPointLed")
}
