package org.espsketchide.app.compile

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.espsketchide.app.data.InMemoryStorage
import org.espsketchide.app.model.Sketch
import org.espsketchide.buildengine.BuildEvent
import org.espsketchide.buildengine.BuildResult
import org.espsketchide.esptool.SerialLink
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class BuildControllerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val storage = InMemoryStorage().apply { put("Blink/Blink.ino", "void setup() {}\nvoid loop() {}\n") }
    private val sketch = Sketch("Blink", storage.root()!!.id + "/Blink")

    private fun success(dir: File) = CompileOutcome.Success(
        BuildResult(
            elf = File(dir, "Blink.ino.elf"),
            flashImages = listOf(0x10000 to File(dir, "Blink.ino.bin").apply { parentFile.mkdirs(); writeBytes(ByteArray(16)) }),
            libraries = emptyList(), programSize = 250, programMax = 1000, dataSize = 30, dataMax = 300, compiled = 1, reused = 0,
        ),
        flashSizeBytes = 4 * 1024 * 1024,
    )

    private fun controller(backend: () -> CompileBackend) = UnconfinedTestDispatcher().let { d ->
        BuildController(kotlinx.coroutines.CoroutineScope(d), SketchStager(storage), tmp.newFolder("stage"), tmp.newFolder("builds"), backend, io = d)
    }

    @Test
    fun verifyStagesCompilesAndSummarisesSize() = runTest {
        var seen: Triple<File, File, String>? = null
        val c = controller {
            CompileBackend { sketchDir, buildDir, board, listener ->
                seen = Triple(sketchDir, buildDir, board)
                listener(BuildEvent.Step("Compiling sketch"))
                success(buildDir)
            }
        }

        c.verify(sketch, "esp32")

        assertThat(c.state.value).isEqualTo(BuildState.Succeeded("Blink", uploaded = false, summary = "Program: 250 bytes (25%). Global variables: 30 bytes (10%)."))
        assertThat(seen!!.first.resolve("Blink.ino").isFile).isTrue()
        assertThat(seen!!.second.name).isEqualTo("Blink-esp32")
        assertThat(seen!!.third).isEqualTo("esp32")
    }

    @Test
    fun compileFailureCarriesDiagnostics() = runTest {
        val d = Diagnostic(Diagnostic.Severity.ERROR, "Blink.ino", 2, 1, "oops", inSketch = true)
        val c = controller { CompileBackend { _, _, _, _ -> CompileOutcome.Failure("Blink.ino:2: oops", "out", listOf(d)) } }

        c.verify(sketch, "esp32")

        assertThat(c.state.value).isEqualTo(BuildState.Failed("Blink", "Blink.ino:2: oops", "out", listOf(d)))
    }

    @Test
    fun notReadyIsReported() = runTest {
        val c = controller { throw NotReadyException("Install a board pack first") }

        c.verify(sketch, "esp32")

        assertThat(c.state.value).isEqualTo(BuildState.Failed("Blink", "Install a board pack first"))
    }

    @Test
    fun uploadWithoutAnswerFromTheBoardFailsAndClosesTheLink() = runTest {
        val link = SilentLink()
        val c = controller { CompileBackend { _, buildDir, _, _ -> success(buildDir) } }

        c.upload(sketch, "esp32", link)

        val state = c.state.value as BuildState.Failed
        assertThat(state.message).contains("download mode")
        assertThat(link.closed).isTrue()
        assertThat(link.lines).contains("dtr=true rts=false")
    }

    private class SilentLink : SerialLink {
        var closed = false
        val lines = mutableListOf<String>()
        private var dtr = false
        private var rts = false
        override fun write(data: ByteArray) = Unit
        override fun read(buffer: ByteArray, timeoutMs: Int): Int { Thread.sleep(minOf(timeoutMs, 5).toLong()); return 0 }
        override fun setBaudRate(baud: Int) = Unit
        override fun setDtr(value: Boolean) { dtr = value; lines += "dtr=$dtr rts=$rts" }
        override fun setRts(value: Boolean) { rts = value; lines += "dtr=$dtr rts=$rts" }
        override fun discardInput() = Unit
        override fun close() { closed = true }
    }
}
