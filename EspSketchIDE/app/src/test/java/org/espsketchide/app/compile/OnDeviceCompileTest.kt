package org.espsketchide.app.compile

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.espsketchide.app.data.InMemoryStorage
import org.espsketchide.app.model.Sketch
import org.espsketchide.buildengine.LocalProcessRunner
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.time.Duration.Companion.minutes
import java.io.File
import java.nio.file.Files

/**
 * The app's whole compile path as it runs on a phone: install the released pack archive,
 * lay out the toolchain from lib*.so files, stage a sketch from storage and build it.
 * Runs the Android-hosted compiler under qemu-user (binfmt_misc), so it needs:
 *  - ESP32_PACK_ARCHIVE: the pack .tar.xz from toolchain/make-pack.py;
 *  - XTENSA_ANDROID_TOOLCHAIN: an Android build from toolchain/build-android.sh;
 *  - QEMU_LD_PREFIX: an Android root (system/, apex/) for the dynamic linker and bionic.
 * Skipped otherwise (CI runs the same engine against desktop tools in :buildengine).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnDeviceCompileTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun buildsExamplesThroughTheAppPipeline() = runTest(timeout = 60.minutes) {
        val archive = System.getenv("ESP32_PACK_ARCHIVE")?.let(::File)
        val androidTools = System.getenv("XTENSA_ANDROID_TOOLCHAIN")?.let(::File)
        val androidRoot = System.getenv("QEMU_LD_PREFIX")
        assumeTrue("ESP32_PACK_ARCHIVE, XTENSA_ANDROID_TOOLCHAIN and QEMU_LD_PREFIX are needed",
            archive?.isFile == true && androidTools?.isDirectory == true && androidRoot != null)

        // 1. Install the pack exactly as "Import file" does.
        val packs = PackManager(tmp.newFolder("files", "packs"), tmp.newFolder("cache"))
        val pack = archive!!.inputStream().use { packs.install(it, verifyHashes = true) }
        assertThat(packs.installed().map { it.id }).containsExactly(pack.id)

        // 2. What the APK's nativeLibraryDir holds (see app/build.gradle.kts toolchainNames).
        val nativeLibs = tmp.newFolder("nativeLibs")
        mapOf(
            "bin/xtensa-esp-elf-gcc" to "libxtgcc.so", "bin/xtensa-esp-elf-g++" to "libxtgxx.so",
            "bin/xtensa-esp-elf-as" to "libxtas.so", "bin/xtensa-esp-elf-ld" to "libxtld.so",
            "bin/xtensa-esp-elf-ar" to "libxtar.so", "bin/xtensa-esp-elf-objcopy" to "libxtobjcopy.so",
            "bin/xtensa-esp-elf-size" to "libxtsize.so",
            "libexec/gcc/xtensa-esp-elf/14.2.0/cc1" to "libxtcc1.so",
            "libexec/gcc/xtensa-esp-elf/14.2.0/cc1plus" to "libxtcc1plus.so",
            "libexec/gcc/xtensa-esp-elf/14.2.0/collect2" to "libxtcollect2.so",
            "lib/xtensa_esp32.so" to "libxtensa_esp32.so",
        ).forEach { (from, to) -> File(androidTools, from).copyTo(File(nativeLibs, to)).setExecutable(true) }

        val toolchain = Toolchain(nativeLibs, File(tmp.root, "files/tc"))
        assertThat(toolchain.isBundled).isTrue()
        toolchain.prepare(pack)
        toolchain.prepare(pack) // idempotent, as before every build

        // 3. Sketches come from SAF storage.
        val storage = InMemoryStorage()
        val examples = File("src/main/assets/examples")
        val names = listOf("Blink", "WiFiScan")
        for (name in names) {
            val ino = examples.walk().first { it.name == "$name.ino" }
            storage.put("$name/$name.ino", ino.readText())
        }

        val qemuEnv = mapOf("QEMU_LD_PREFIX" to androidRoot!!, "ANDROID_ROOT" to "/system", "ANDROID_DATA" to "/data")
        val compiler = SketchCompiler(toolchain.treeDir, pack, tmp.newFolder("cache", "tmp")) { env -> LocalProcessRunner(env + qemuEnv) }
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val builds = BuildController(
            CoroutineScope(dispatcher), SketchStager(storage), File(tmp.root, "cache/stage"), File(tmp.root, "cache/builds"),
            backend = { CompileBackend(compiler::compile) }, io = dispatcher,
        )

        for (name in names) {
            builds.verify(Sketch(name, storage.root()!!.id + "/$name"), pack.defaultBoard)
            val state = builds.state.value
            assertWithMessage("$name: $state").that(state).isInstanceOf(BuildState.Succeeded::class.java)
            println("$name: ${(state as BuildState.Succeeded).summary}")
            val app = File(tmp.root, "cache/builds/$name-${pack.defaultBoard}/$name.ino.bin")
            assertThat(app.readBytes()[0]).isEqualTo(0xE9.toByte())
        }

        // A mistake comes back as a diagnostic pointing into the sketch.
        storage.put("Blink/Blink.ino", "void setup() {\n  pinMod(2, OUTPUT);\n}\nvoid loop() {}\n")
        builds.verify(Sketch("Blink", storage.root()!!.id + "/Blink"), pack.defaultBoard)
        val failed = builds.state.value as BuildState.Failed
        assertThat(failed.message).startsWith("Blink.ino:2:")
        assertThat(failed.diagnostics.first { it.inSketch }.let { it.file to it.line }).isEqualTo("Blink.ino" to 2)
    }
}
