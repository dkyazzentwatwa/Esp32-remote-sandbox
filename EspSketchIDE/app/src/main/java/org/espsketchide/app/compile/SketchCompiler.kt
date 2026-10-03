package org.espsketchide.app.compile

import org.espsketchide.buildengine.BuildEvent
import org.espsketchide.buildengine.BuildException
import org.espsketchide.buildengine.BuildProperties
import org.espsketchide.buildengine.BuildRequest
import org.espsketchide.buildengine.BuildResult
import org.espsketchide.buildengine.Builder
import org.espsketchide.buildengine.Library
import org.espsketchide.buildengine.CompileException
import org.espsketchide.buildengine.LocalProcessRunner
import org.espsketchide.buildengine.MissingLibraryException
import org.espsketchide.buildengine.ProcessRunner
import org.espsketchide.buildengine.esp32.Esp32BuildProfile
import org.espsketchide.buildengine.esp32.PackLayout
import org.espsketchide.buildengine.esp32.XtensaToolchainRunner
import java.io.File

sealed interface CompileOutcome {
    class Success(val result: BuildResult, val flashSizeBytes: Int) : CompileOutcome

    /** [message] is for people; [output] is the compiler's text; [diagnostics] are parsed from it. */
    class Failure(val message: String, val output: String = "", val diagnostics: List<Diagnostic> = emptyList()) : CompileOutcome
}

/**
 * Builds a staged sketch with the phone toolchain and an installed pack, and turns engine
 * exceptions into messages a student can act on.
 */
class SketchCompiler(
    private val toolchainTree: File,
    private val pack: PackLayout,
    private val tmpDir: File,
    private val processRunner: (Map<String, String>) -> ProcessRunner = { env -> LocalProcessRunner(env) },
    /** Libraries the user installed (Libraries screen), searched after the sketch's own. */
    private val userLibraries: () -> List<Library> = { emptyList() },
    /** A catalog library to suggest when a header is missing, e.g. "DHT.h" -> "DHT sensor library". */
    private val suggestLibrary: (String) -> String? = { null },
) {

    fun compile(sketchDir: File, buildDir: File, boardId: String, listener: (BuildEvent) -> Unit = {}): CompileOutcome {
        val request = BuildRequest(pack.platform(toolchainTree), boardId, sketchDir = sketchDir, buildDir = buildDir)
        val env = XtensaToolchainRunner(LocalProcessRunner(), toolchainTree, chip = pack.chip, gccVersion = pack.gccVersion, tmpDir = tmpDir).environment
        val runner = XtensaToolchainRunner(processRunner(env), toolchainTree, chip = pack.chip, gccVersion = pack.gccVersion, tmpDir = tmpDir)
        return try {
            val result = Builder(request, Esp32BuildProfile, runner, userLibraries = userLibraries(), listener = listener).build()
            val flashSize = Esp32BuildProfile.flashSizeBytes(BuildProperties.assemble(request).expanded("build.flash_size"))
            CompileOutcome.Success(result, flashSize)
        } catch (e: MissingLibraryException) {
            val suggestion = suggestLibrary(e.header)
            CompileOutcome.Failure(
                if (suggestion != null) {
                    "${e.header} comes from the library \"$suggestion\". Install it in Libraries (sketch list menu), then verify again."
                } else {
                    "No installed library provides ${e.header}. Install it in Libraries (sketch list menu), or with Install from .zip."
                },
                e.output, Diagnostics.parse(e.output, sketchDir),
            )
        } catch (e: CompileException) {
            val diagnostics = Diagnostics.parse(e.output, sketchDir)
            val first = diagnostics.firstOrNull { it.severity == Diagnostic.Severity.ERROR }
            val message = when {
                first != null -> "${first.file}:${first.line}: ${first.message}"
                e.output.contains("undefined reference") -> "Linking failed: something is used but never defined."
                else -> "Compiling ${e.file.name} failed."
            }
            CompileOutcome.Failure(message, e.output, diagnostics)
        } catch (e: BuildException) {
            CompileOutcome.Failure(e.message ?: "Build failed")
        } catch (e: java.io.IOException) {
            CompileOutcome.Failure("Couldn't run the compiler: ${e.message}")
        }
    }
}
