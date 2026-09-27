package org.espsketchide.app.compile

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.espsketchide.app.model.Sketch
import org.espsketchide.buildengine.BuildEvent
import org.espsketchide.esptool.AutoReset
import org.espsketchide.esptool.FlashImage
import org.espsketchide.esptool.FlashProgress
import org.espsketchide.esptool.Flasher
import org.espsketchide.esptool.SerialLink
import java.io.File

/** Compiles sketches. Separate from [BuildController] so tests can fake the toolchain. */
fun interface CompileBackend {
    fun compile(sketchDir: File, buildDir: File, boardId: String, listener: (BuildEvent) -> Unit): CompileOutcome
}

/** Why a build can't start (no compiler in this APK, no pack installed); shown as is. */
class NotReadyException(message: String) : Exception(message)

sealed interface BuildState {
    data object Idle : BuildState

    data class Running(val sketch: String, val uploading: Boolean, val step: String, val fraction: Float? = null) : BuildState

    data class Succeeded(val sketch: String, val uploaded: Boolean, val summary: String) : BuildState

    data class Failed(
        val sketch: String,
        val message: String,
        val output: String = "",
        val diagnostics: List<Diagnostic> = emptyList(),
    ) : BuildState
}

/**
 * Runs Verify and Upload for the whole app, one at a time, and publishes progress in [state]
 * so the screen can be recreated (or left and reopened) while a build runs.
 */
class BuildController(
    private val scope: CoroutineScope,
    private val stager: SketchStager,
    private val stageDir: File,
    private val buildsDir: File,
    private val backend: () -> CompileBackend,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Upload speed after connecting; the ROM starts at 115200. */
    private val uploadBaud: Int = 460_800,
) {
    private val _state = MutableStateFlow<BuildState>(BuildState.Idle)
    val state: StateFlow<BuildState> = _state.asStateFlow()

    val isBusy: Boolean get() = _state.value is BuildState.Running

    fun verify(sketch: Sketch, boardId: String) = start(sketch, boardId, link = null)

    /**
     * Compiles, then flashes through [link] (already opened; closed afterwards) and resets the
     * board so the new sketch runs.
     */
    fun upload(sketch: Sketch, boardId: String, link: SerialLink) = start(sketch, boardId, link)

    fun dismiss() {
        if (!isBusy) _state.value = BuildState.Idle
    }

    private fun start(sketch: Sketch, boardId: String, link: SerialLink?): Boolean {
        if (isBusy) {
            link?.close()
            return false
        }
        val uploading = link != null
        _state.value = BuildState.Running(sketch.name, uploading, "Preparing")
        scope.launch {
            _state.value = try {
                withContext(io) { run(sketch, boardId, link) }
            } catch (e: NotReadyException) {
                BuildState.Failed(sketch.name, e.message ?: "Not ready")
            } catch (e: Exception) {
                BuildState.Failed(sketch.name, e.message ?: e.javaClass.simpleName)
            } finally {
                runCatching { link?.close() }
            }
        }
        return true
    }

    private fun run(sketch: Sketch, boardId: String, link: SerialLink?): BuildState {
        val uploading = link != null
        fun progress(step: String, fraction: Float? = null) {
            _state.value = BuildState.Running(sketch.name, uploading, step, fraction)
        }

        val compiler = backend()
        progress("Copying sketch")
        val sketchDir = stager.stage(sketch, stageDir)
        val buildDir = File(buildsDir, "${sketch.name}-$boardId")
        var step = "Compiling"
        val outcome = compiler.compile(sketchDir, buildDir, boardId) { event ->
            when (event) {
                is BuildEvent.Step -> { step = event.name; progress(step) }
                is BuildEvent.Compiled -> progress(step, event.done.toFloat() / event.total)
                is BuildEvent.Command -> Unit
            }
        }
        val result = when (outcome) {
            is CompileOutcome.Failure -> return BuildState.Failed(sketch.name, outcome.message, outcome.output, outcome.diagnostics)
            is CompileOutcome.Success -> outcome
        }
        val size = sizeSummary(result)
        if (link == null) return BuildState.Succeeded(sketch.name, uploaded = false, summary = size)

        progress("Connecting to the board")
        val images = result.result.flashImages.map { (offset, file) -> FlashImage(offset, file.readBytes(), file.name) }
        val total = images.sumOf { it.data.size }.toFloat()
        var before = 0
        link.setBaudRate(ROM_BAUD)
        AutoReset.enterDownloadMode(link)
        Flasher(link).flash(images, result.flashSizeBytes, baud = uploadBaud, currentBaud = ROM_BAUD) { p ->
            when (p) {
                is FlashProgress.Writing -> progress("Uploading ${p.image}", (before + p.bytesDone) / total)
                is FlashProgress.Verified -> before += images.first { it.name == p.image }.data.size
                else -> Unit
            }
        }
        AutoReset.hardReset(link)
        return BuildState.Succeeded(sketch.name, uploaded = true, summary = size)
    }

    private fun sizeSummary(s: CompileOutcome.Success): String {
        val r = s.result
        fun pct(used: Long, max: Long?) = if (max == null || max == 0L) "" else " (${used * 100 / max}%)"
        return "Program: ${r.programSize} bytes${pct(r.programSize, r.programMax)}. " +
            "Global variables: ${r.dataSize} bytes${pct(r.dataSize, r.dataMax)}."
    }

    companion object {
        const val ROM_BAUD = 115_200
    }
}
