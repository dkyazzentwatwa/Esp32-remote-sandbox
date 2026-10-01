package org.espsketchide.buildengine

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/** Our generated `<sketch>.ino.cpp` must equal arduino-cli's for the bundled examples. */
class SketchPreprocessorGoldenTest {

    private fun check(name: String) {
        val ref = GoldenEnv.require()
        val platform = GoldenEnv.platform()
        val sketchDir = File(ref, "sketches/$name")
        val buildDir = File(ref, "builds/$name").canonicalFile
        val props = BuildProperties.assemble(BuildRequest(platform, "esp32", sketchDir = sketchDir, buildDir = buildDir))

        val inoFiles = SketchPreprocessor.inoFiles(sketchDir)
        val merged = SketchPreprocessor.merge(inoFiles)
        val mergedFile = File.createTempFile("merged", ".cpp").apply { writeText(merged); deleteOnExit() }
        val output = File.createTempFile("preprocessed", ".cpp").apply { deleteOnExit() }
        val base = listOf(File(props.expanded("build.core.path")), File(props.expanded("build.variant.path")))
        // Preprocessing needs the libraries' include folders, as in arduino-cli (discovery first).
        val libraries = Library.scan(File(platform.dir, "libraries"), LibraryLocation.PLATFORM)
        val includes = IncludeDiscovery(props, LibraryResolver(libraries, platform.arch), LocalProcessRunner(), buildDir)
            .discover(listOf(mergedFile), base).includeDirs
        val argv = Recipes.preprocess(props, mergedFile, output, includes)
        val process = ProcessBuilder(argv).redirectErrorStream(true).start()
        val log = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "preprocessor failed: $log" }

        val ours = SketchPreprocessor.addPrototypes(merged, output.readText(), inoFiles.map { it.path }.toSet())
        val theirs = File(buildDir, "sketch/$name.ino.cpp").readText()
        assertWithMessage("$name.ino.cpp").that(ours).isEqualTo(theirs)
    }

    @Test fun blink() = check("Blink")
    @Test fun helloSerial() = check("HelloSerial")
    @Test fun analogRead() = check("AnalogRead")
    @Test fun button() = check("Button")
    @Test fun fade() = check("Fade")
    @Test fun wifiScan() = check("WiFiScan")
    @Test fun wifiAccessPointLed() = check("WiFiAccessPointLed")
    @Test fun bleScan() = check("BLEScan")
}
