package org.espsketchide.buildengine.esp32

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.espsketchide.buildengine.BuildRequest
import org.espsketchide.buildengine.Builder
import org.espsketchide.buildengine.GoldenEnv
import org.espsketchide.buildengine.LocalProcessRunner
import org.espsketchide.buildengine.Platform
import org.espsketchide.buildengine.ProcessResult
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class XtensaToolchainTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun rewritesChipWrappersToRelocatedUnifiedTools() {
        val tree = File("/data/tc")
        val runner = XtensaToolchainRunner({ _, _ -> ProcessResult(0, "") }, tree)

        assertThat(runner.rewrite(listOf("/pack/tools/esp-x32/bin/xtensa-esp32-elf-g++", "-c", "a.cpp"))).containsExactly(
            "/data/tc/bin/xtensa-esp-elf-g++",
            "-B/data/tc/libexec/gcc/xtensa-esp-elf/14.2.0/", "-B/data/tc/xtensa-esp-elf/bin/",
            "--sysroot=/data/tc/xtensa-esp-elf", "-mdynconfig=xtensa_esp32.so",
            "-c", "a.cpp",
        ).inOrder()
        assertThat(runner.rewrite(listOf("/x/xtensa-esp32-elf-gcc-ar", "cr", "core.a", "a.o")))
            .containsExactly("/data/tc/bin/xtensa-esp-elf-ar", "cr", "core.a", "a.o").inOrder()
        assertThat(runner.rewrite(listOf("/x/xtensa-esp32-elf-size", "-A", "b.elf")))
            .containsExactly("/data/tc/bin/xtensa-esp-elf-size", "-A", "b.elf").inOrder()
        assertThat(runner.rewrite(listOf("/usr/bin/python3", "x.py"))).containsExactly("/usr/bin/python3", "x.py").inOrder()
        assertThat(runner.environment).containsExactly("GCC_EXEC_PREFIX", "/data/tc/lib/gcc/", "XTENSA_GNU_CONFIG", "/data/tc/lib/")
    }

    /**
     * The phone layout, rehearsed on the desktop: Espressif's binaries copied flat under
     * lib*.so names (like nativeLibraryDir), a link tree for filesDir/tc, and the Builder driving
     * it through [XtensaToolchainRunner]. Code must equal arduino-cli's build.
     */
    @Test
    fun builderWorksThroughTheRelocatedLayout() {
        val ref = GoldenEnv.require()
        val installed = GoldenEnv.platform()
        val esp = installed.tools.getValue("esp-x32")
        val v = "14.2.0"
        val native = tmp.newFolder("nativelib")
        val tree = tmp.newFolder("tc")
        fun ship(from: String, name: String) = File(native, name).also { if (!it.exists()) { File(esp, from).copyTo(it); it.setExecutable(true) } }
        fun link(at: String, target: File) {
            val f = File(tree, at).apply { parentFile.mkdirs() }
            Files.createSymbolicLink(f.toPath(), target.toPath())
        }
        link("bin/xtensa-esp-elf-gcc", ship("bin/xtensa-esp-elf-gcc", "libxtgcc.so"))
        link("bin/xtensa-esp-elf-g++", ship("bin/xtensa-esp-elf-g++", "libxtgxx.so"))
        link("bin/xtensa-esp-elf-ar", ship("bin/xtensa-esp-elf-ar", "libxtar.so"))
        link("bin/xtensa-esp-elf-size", ship("bin/xtensa-esp-elf-size", "libxtsize.so"))
        for (p in listOf("cc1", "cc1plus", "collect2")) link("libexec/gcc/xtensa-esp-elf/$v/$p", ship("libexec/gcc/xtensa-esp-elf/$v/$p", "libxt$p.so"))
        // Espressif's desktop gcc defaults to the LTO linker plugin; the Android build has no LTO.
        link("libexec/gcc/xtensa-esp-elf/$v/liblto_plugin.so", File(esp, "libexec/gcc/xtensa-esp-elf/$v/liblto_plugin.so"))
        for (p in listOf("as", "ld", "ar")) link("xtensa-esp-elf/bin/$p", ship("bin/xtensa-esp-elf-$p", "libxt$p.so"))
        link("lib/xtensa_esp32.so", ship("lib/xtensa_esp32.so", "libxtensa_esp32.so"))
        link("lib/gcc/xtensa-esp-elf/$v", File(esp, "lib/gcc/xtensa-esp-elf/$v"))
        link("xtensa-esp-elf/include", File(esp, "xtensa-esp-elf/include"))
        link("xtensa-esp-elf/lib", File(esp, "xtensa-esp-elf/lib"))

        // compiler.path now points into the tree, where no chip wrappers exist: the runner maps them.
        val platform = Platform(installed.dir, installed.vendor, installed.arch, installed.tools + ("esp-x32" to tree))
        val buildDir = tmp.newFolder("build")
        val adapter = XtensaToolchainRunner(LocalProcessRunner(), tree)
        val runner = XtensaToolchainRunner(LocalProcessRunner(adapter.environment), tree)
        val result = Builder(BuildRequest(platform, "esp32", sketchDir = File(ref, "sketches/Blink"), buildDir = buildDir), Esp32BuildProfile, runner).build()

        val objcopy = File(esp, "bin/xtensa-esp-elf-objcopy").path
        fun section(elf: File, name: String): ByteArray {
            val out = tmp.newFile()
            check(ProcessBuilder(objcopy, "-O", "binary", "-j", name, elf.path, out.path).start().waitFor() == 0)
            return out.readBytes()
        }
        for (sec in listOf(".flash.text", ".iram0.text", ".dram0.data")) {
            assertWithMessage(sec).that(section(result.elf, sec)).isEqualTo(section(File(ref, "builds/Blink/Blink.ino.elf"), sec))
        }
    }
}
