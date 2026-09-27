package org.espsketchide.buildengine

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LibrariesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun lib(root: File, folder: String, props: String?, vararg files: String): File {
        val dir = File(root, folder).apply { mkdirs() }
        props?.let { File(dir, "library.properties").writeText(it) }
        files.forEach { File(dir, it).apply { parentFile.mkdirs(); writeText("//") } }
        return dir
    }

    @Test
    fun readsRecursiveAndLegacyLayouts() {
        val root = tmp.newFolder("libraries")
        lib(root, "Modern", "name=Modern Lib\nversion=1.2.0\narchitectures=esp32, avr", "src/Modern.h", "src/Modern.cpp", "src/impl/deep.cpp", "examples/x/x.ino")
        lib(root, "Old", null, "Old.h", "Old.cpp", "utility/helper.c", "extras/skip.cpp")
        lib(root, "NotALib", null, "readme.txt")

        val libs = Library.scan(root, LibraryLocation.USER)

        assertThat(libs.map { it.name }).containsExactly("Modern Lib", "Old").inOrder()
        val modern = libs[0]
        assertThat(modern.recursive).isTrue()
        assertThat(modern.includeDir).isEqualTo(File(root, "Modern/src"))
        assertThat(modern.headers).containsExactly("Modern.h")
        assertThat(modern.sources().map { it.name }).containsExactly("Modern.cpp", "deep.cpp")
        assertThat(modern.supports("ESP32")).isTrue()
        assertThat(modern.supports("rp2040")).isFalse()
        val old = libs[1]
        assertThat(old.recursive).isFalse()
        assertThat(old.includeDir).isEqualTo(File(root, "Old"))
        assertThat(old.sources().map { it.name }).containsExactly("Old.cpp", "helper.c")
        assertThat(old.supports("anything")).isTrue()
    }

    @Test
    fun resolverPrefersCompatibleThenNameMatchThenLocation() {
        val root = tmp.newFolder("r")
        val servoAvr = Library.load(lib(root, "ServoAvr", "name=Servo\narchitectures=avr", "src/Servo.h"), LibraryLocation.PLATFORM)!!
        val servoEsp = Library.load(lib(root, "ESP32Servo", "name=ESP32Servo\narchitectures=esp32", "src/Servo.h"), LibraryLocation.USER)!!
        val cfgA = Library.load(lib(root, "Config", "name=Config\narchitectures=*", "src/Config.h"), LibraryLocation.PLATFORM)!!
        val cfgB = Library.load(lib(root, "Config2", "name=Config2\narchitectures=*", "src/Config.h"), LibraryLocation.SKETCH)!!
        val userCfg = Library.load(lib(root, "u/Config", "name=Config\narchitectures=*", "src/Config.h"), LibraryLocation.USER)!!

        val resolver = LibraryResolver(listOf(servoAvr, servoEsp, cfgA, cfgB, userCfg), "esp32")

        assertThat(resolver.find("Servo.h")).isEqualTo(servoEsp)       // architecture wins
        assertThat(resolver.find("Config.h")).isEqualTo(userCfg)       // exact name, then user over platform
        assertThat(resolver.find("Nope.h")).isNull()
    }

    @Test
    fun discoveryAddsLibrariesUntilTheFileCompiles() {
        val root = tmp.newFolder("libs")
        val wifi = Library.load(lib(root, "WiFi", "name=WiFi", "src/WiFi.h", "src/WiFi.cpp"), LibraryLocation.PLATFORM)!!
        val net = Library.load(lib(root, "Network", "name=Networking", "src/Network.h", "src/Net.cpp"), LibraryLocation.PLATFORM)!!
        val sketch = tmp.newFile("sketch.cpp")
        // Fake preprocessor: sketch needs WiFi.h; WiFi.cpp needs Network.h.
        val needs = mapOf(sketch.path to "WiFi.h", File(root, "WiFi/src/WiFi.cpp").path to "Network.h")
        val runner = ProcessRunner { argv, _ ->
            val source = argv.first { it.endsWith(".cpp") }
            val includes = argv.filter { it.startsWith("-I") }.map { it.removePrefix("-I") }
            val need = needs[source]
            val provider = mapOf("WiFi.h" to wifi, "Network.h" to net)[need]
            if (need != null && provider!!.includeDir.path !in includes) {
                ProcessResult(1, "$source:1:10: fatal error: $need: No such file or directory")
            } else ProcessResult(0, "")
        }
        val props = Properties.of("recipe.cpp.o.pattern" to "g++ {includes} {source_file} -o {object_file}")

        val discovery = IncludeDiscovery(props, LibraryResolver(listOf(wifi, net), "esp32"), runner, tmp.root)
        val result = discovery.discover(listOf(sketch), baseIncludes = listOf(File("/core")))

        assertThat(result.libraries.map { it.name }).containsExactly("WiFi", "Networking").inOrder()
        assertThat(result.includeDirs).containsExactly(File("/core"), wifi.includeDir, net.includeDir).inOrder()
    }

    @Test
    fun unknownHeaderAndRealErrorsAreReported() {
        val sketch = tmp.newFile("s.cpp")
        val props = Properties.of("recipe.cpp.o.pattern" to "g++ {includes} {source_file} -o {object_file}")
        val resolver = LibraryResolver(emptyList(), "esp32")

        val missing = IncludeDiscovery(props, resolver, { _, _ -> ProcessResult(1, "s.cpp:1: fatal error: Adafruit_GFX.h: No such file or directory") }, tmp.root)
        val e = assertThrows(MissingLibraryException::class.java) { missing.discover(listOf(sketch), emptyList()) }
        assertThat(e.header).isEqualTo("Adafruit_GFX.h")

        val broken = IncludeDiscovery(props, resolver, { _, _ -> ProcessResult(1, "s.cpp:3:1: error: expected ';'") }, tmp.root)
        assertThrows(CompileException::class.java) { broken.discover(listOf(sketch), emptyList()) }
    }
}
