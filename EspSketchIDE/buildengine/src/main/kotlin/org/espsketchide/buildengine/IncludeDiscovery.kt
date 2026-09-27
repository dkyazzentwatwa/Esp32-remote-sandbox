package org.espsketchide.buildengine

import java.io.File

/** Runs a command; the engine never spawns processes directly so tests and Android can swap this. */
fun interface ProcessRunner {
    fun run(argv: List<String>, workDir: File?): ProcessResult
}

class ProcessResult(val exitCode: Int, val output: String)

class LocalProcessRunner(private val env: Map<String, String> = emptyMap()) : ProcessRunner {
    override fun run(argv: List<String>, workDir: File?): ProcessResult {
        val pb = ProcessBuilder(argv).redirectErrorStream(true)
        pb.environment().putAll(env)
        workDir?.let { pb.directory(it) }
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        return ProcessResult(p.waitFor(), out)
    }
}

/**
 * Finds the libraries a sketch needs by running the C preprocessor over each source file: on
 * "fatal error: X.h: No such file", the library providing X.h is added (its include folder and
 * its sources, which are then scanned too) and the file is tried again.
 */
class IncludeDiscovery(
    private val props: Properties,
    private val resolver: LibraryResolver,
    private val runner: ProcessRunner,
    private val scratch: File,
) {
    class Result(val libraries: List<Library>, val includeDirs: List<File>)

    /** Paths of every file preprocessed, in order (for tests and diagnostics). */
    val scanned = mutableListOf<String>()

    fun discover(sketchSources: List<File>, baseIncludes: List<File>): Result {
        val libraries = mutableListOf<Library>()
        val includes = baseIncludes.toMutableList()
        val queue = ArrayDeque(sketchSources)
        val seen = mutableSetOf<String>()
        while (queue.isNotEmpty()) {
            val file = queue.first()
            if (!seen.add(file.path + "#" + includes.size)) {
                // Same file, same include set, still failing: nothing more we can add.
                throw BuildException("Include discovery made no progress on ${file.path}")
            }
            scanned += file.path
            val argv = Recipes.preprocess(props, file, File("/dev/null"), includes)
            val result = runner.run(argv, scratch)
            if (result.exitCode == 0) {
                queue.removeFirst()
                continue
            }
            val header = MISSING_HEADER.find(result.output)?.groupValues?.get(1)
                ?: throw CompileException(file, result.output)
            val lib = resolver.find(header)
                ?: throw MissingLibraryException(header, file, result.output)
            if (libraries.any { it.dir == lib.dir }) {
                throw BuildException("$header is in library ${lib.name} but still not found while compiling ${file.name}")
            }
            libraries += lib
            includes += lib.includeDir
            queue.addAll(lib.sources())
        }
        return Result(libraries, includes)
    }

    companion object {
        private val MISSING_HEADER = Regex("""fatal error: ([^\s:]+): No such file or directory""")
    }
}

/** The compiler rejected a file for a reason other than a missing library header. */
class CompileException(val file: File, val output: String) : Exception("Compiling ${file.name} failed:\n$output")

/** `#include <header>` names a header no installed library provides. */
class MissingLibraryException(val header: String, val file: File, val output: String) :
    Exception("No installed library provides $header (included from ${file.name})")
