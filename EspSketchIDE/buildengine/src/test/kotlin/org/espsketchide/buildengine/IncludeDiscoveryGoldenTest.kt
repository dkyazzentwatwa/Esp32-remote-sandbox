package org.espsketchide.buildengine

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Library discovery against the real core and compiler: the libraries found, in order, must be
 * the ones arduino-cli reports ("Using library X at version V in folder: D").
 */
class IncludeDiscoveryGoldenTest {

    private val usingLibrary = Regex("""^Using library (.+) at version (\S*) in folder: (\S+)\s*$""")

    private fun check(name: String) {
        val ref = GoldenEnv.require()
        val platform = GoldenEnv.platform()
        val sketchDir = File(ref, "sketches/$name")
        val buildDir = File(ref, "builds/$name").canonicalFile
        val props = BuildProperties.assemble(BuildRequest(platform, "esp32", sketchDir = sketchDir, buildDir = buildDir))

        val merged = File.createTempFile("$name.ino.cpp", ".merged").apply {
            writeText(SketchPreprocessor.merge(SketchPreprocessor.inoFiles(sketchDir))); deleteOnExit()
        }
        val libraries = Library.scan(File(platform.dir, "libraries"), LibraryLocation.PLATFORM)
        val discovery = IncludeDiscovery(props, LibraryResolver(libraries, platform.arch), LocalProcessRunner(), buildDir)
        val base = listOf(File(props.expanded("build.core.path")), File(props.expanded("build.variant.path")))
        val result = discovery.discover(listOf(merged), base)

        val expected = File(ref, "builds/$name.verbose.txt").readLines().mapNotNull { usingLibrary.matchEntire(it) }
            .map { "${it.groupValues[1]} ${it.groupValues[2]} ${it.groupValues[3]}" }
        val actual = result.libraries.map { "${it.name} ${it.version} ${it.dir.path}" }
        assertWithMessage("libraries for $name (scanned ${discovery.scanned.size} files)").that(actual).isEqualTo(expected)
    }

    @Test fun blinkNeedsNoLibraries() = check("Blink")
    @Test fun wifiScan() = check("WiFiScan")
    @Test fun wifiAccessPointLed() = check("WiFiAccessPointLed")
    @Test fun bleScan() = check("BLEScan")
}
