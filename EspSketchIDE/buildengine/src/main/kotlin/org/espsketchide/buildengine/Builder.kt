package org.espsketchide.buildengine

import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * Platform-specific steps that platform.txt expresses as shell hooks or host tools (which don't
 * exist on Android). A profile declares every hook and objcopy recipe it replaces; the builder
 * refuses platforms with hooks nobody implements, so a core update can't silently break builds.
 */
interface BuildProfile {
    val handledHooks: Set<String>
    val handledObjcopyRecipes: Set<String>

    fun prebuild(ctx: BuildContext)
    fun corePrebuild(ctx: BuildContext) {}
    fun corePostbuild(ctx: BuildContext) {}

    /** Turns the linked ELF into flashable files; returns them with their flash offsets. */
    fun objcopy(ctx: BuildContext, elf: File): List<Pair<Int, File>>
}

class BuildContext(val request: BuildRequest, val props: Properties) {
    val buildDir: File get() = request.buildDir
    val projectName: String get() = request.projectName
    fun expanded(key: String) = props.expanded(key)
    fun file(name: String) = File(buildDir, name)
}

class BuildResult(
    val elf: File,
    /** Files to flash, with their offsets. */
    val flashImages: List<Pair<Int, File>>,
    val libraries: List<Library>,
    val programSize: Long,
    val programMax: Long?,
    val dataSize: Long,
    val dataMax: Long?,
    val compiled: Int,
    val reused: Int,
)

sealed interface BuildEvent {
    data class Step(val name: String) : BuildEvent
    data class Compiled(val source: File, val reused: Boolean, val done: Int, val total: Int) : BuildEvent
    data class Command(val argv: List<String>) : BuildEvent
}

/**
 * Builds a sketch the way the Arduino IDE does: platform hooks, `.ino` merging, library
 * discovery, prototypes, compiling sketch/libraries/core (in parallel, reusing up-to-date
 * objects), archiving the core, linking, objcopy and the size check.
 */
class Builder(
    private val request: BuildRequest,
    private val profile: BuildProfile,
    private val runner: ProcessRunner,
    private val userLibraries: List<Library> = emptyList(),
    private val jobs: Int = Runtime.getRuntime().availableProcessors(),
    private val listener: (BuildEvent) -> Unit = {},
) {
    private var compiled = 0
    private var reused = 0

    fun build(): BuildResult {
        val props = BuildProperties.assemble(request)
        checkSupported(props)
        val ctx = BuildContext(request, props)
        val buildDir = request.buildDir.apply { mkdirs() }
        val sketchOut = File(buildDir, "sketch").apply { mkdirs() }
        val name = request.sketchName

        step("Preparing")
        profile.prebuild(ctx)

        // Sketch: merged .ino plus the sketch's other sources, copied so relative includes work.
        val inoFiles = SketchPreprocessor.inoFiles(request.sketchDir)
        val merged = SketchPreprocessor.merge(inoFiles)
        val mergedFile = File(sketchOut, "$name.ino.cpp.merged").apply { writeText(merged) }
        val extraSources = copySketchFiles(request.sketchDir, sketchOut)

        step("Finding libraries")
        val platformLibraries = Library.scan(File(request.platform.dir, "libraries"), LibraryLocation.PLATFORM)
        val sketchLibraries = Library.scan(File(request.sketchDir, "libraries"), LibraryLocation.SKETCH)
        val resolver = LibraryResolver(sketchLibraries + userLibraries + platformLibraries, request.platform.arch)
        val base = listOf(File(ctx.expanded("build.core.path"))) +
            (props["build.variant.path"]?.let { listOf(File(props.expand(it))) } ?: emptyList())
        val discovery = IncludeDiscovery(props, resolver, runner, buildDir)
            .discover(listOf(mergedFile) + extraSources.filter { SourceKind.of(it) != null }, base)
        val includes = discovery.includeDirs

        step("Generating prototypes")
        val preprocessed = File(buildDir, "preproc/sketch_merged.cpp").apply { parentFile.mkdirs() }
        run(Recipes.preprocess(props, mergedFile, preprocessed, includes), mergedFile)
        val sketchCpp = File(sketchOut, "$name.ino.cpp")
        writeIfChanged(sketchCpp, SketchPreprocessor.addPrototypes(merged, preprocessed.readText(), inoFiles.map { it.path }.toSet()))

        step("Compiling sketch")
        val sketchSources = listOf(sketchCpp) + extraSources.filter { SourceKind.of(it) != null }
        val sketchObjects = compileAll(props, sketchSources.map { it to File(sketchOut, it.relativeTo(sketchOut).path + ".o") }, includes)

        step("Compiling libraries")
        val libraryObjects = discovery.libraries.flatMap { lib ->
            val out = File(buildDir, "libraries/${lib.dir.name}")
            val libIncludes = if (!lib.recursive && File(lib.dir, "utility").isDirectory) includes + File(lib.dir, "utility") else includes
            compileAll(props, lib.sources().map { it to File(out, it.relativeTo(lib.includeDir).path + ".o") }, libIncludes)
        }

        step("Compiling core")
        val coreArchive = buildCore(ctx, includes)

        step("Linking")
        val elf = ctx.file("$name.ino.elf")
        val objects = sketchObjects + libraryObjects
        run(
            Recipes.expandRecipe(
                props, "recipe.c.combine.pattern",
                "object_files" to objects.joinToString(" ") { "\"${it.path}\"" },
                "archive_file" to coreArchive.name,
                "archive_file_path" to coreArchive.path,
            ),
            elf,
        )

        step("Creating firmware image")
        val images = profile.objcopy(ctx, elf)

        val (program, data) = size(props)
        val programMax = props["upload.maximum_size"]?.toLongOrNull()
        val dataMax = props["upload.maximum_data_size"]?.toLongOrNull()
        if (programMax != null && program > programMax) {
            throw BuildException("Sketch too big: uses $program bytes, the maximum is $programMax. Try a partition scheme with a larger app.")
        }
        if (dataMax != null && data > dataMax) {
            throw BuildException("Not enough memory: global variables use $data bytes, the maximum is $dataMax.")
        }
        return BuildResult(elf, images, discovery.libraries, program, programMax, data, dataMax, compiled, reused)
    }

    private fun checkSupported(props: Properties) {
        val hooks = props.keys.filter { it.startsWith("recipe.hooks.") && it.endsWith(".pattern") }
            .map { it.removeSuffix(".pattern") }
        val unknown = hooks.filter { h -> h !in profile.handledHooks && !h.startsWith("recipe.hooks.savehex.") }
        if (unknown.isNotEmpty()) throw BuildException("This board package needs build steps the app doesn't support yet: $unknown")
        val objcopy = props.keys.filter { it.startsWith("recipe.objcopy.") && it.endsWith(".pattern") }
        val unknownObjcopy = objcopy.filter { it !in profile.handledObjcopyRecipes }
        if (unknownObjcopy.isNotEmpty()) throw BuildException("Unsupported objcopy recipes: $unknownObjcopy")
    }

    private fun buildCore(ctx: BuildContext, includes: List<File>): File {
        val props = ctx.props
        val coreDir = File(ctx.expanded("build.core.path"))
        val out = File(ctx.buildDir, "core").apply { mkdirs() }
        val sources = coreDir.walkTopDown().filter { it.isFile && SourceKind.of(it) != null }.sortedBy { it.path }.toList()
        val variantDir = props["build.variant.path"]?.let { File(props.expand(it)) }
        val variantSources = variantDir?.listFiles().orEmpty().filter { it.isFile && SourceKind.of(it) != null }.sortedBy { it.name }

        profile.corePrebuild(ctx)
        val objects = try {
            compileAll(props, (sources.map { it to File(out, it.relativeTo(coreDir).path + ".o") } +
                variantSources.map { it to File(out, it.name + ".o") }), includes)
        } finally {
            profile.corePostbuild(ctx)
        }

        val archive = File(out, "core.a")
        val stamp = File(out, "core.a.inputs")
        val inputs = objects.joinToString("\n") { "${it.path} ${it.lastModified()}" }
        if (archive.isFile && stamp.isFile && stamp.readText() == inputs) return archive
        archive.delete()
        // One `ar` call for all objects when the recipe allows it (each process start is costly
        // on a phone); otherwise one call per object like the IDE.
        val probe = Recipes.expandRecipe(props, "recipe.ar.pattern",
            "archive_file_path" to archive.path, "archive_file" to archive.name, "object_file" to objects.first().path)
        if (probe.last() == objects.first().path) {
            run(probe + objects.drop(1).map { it.path }, archive)
        } else {
            for (obj in objects) {
                run(Recipes.expandRecipe(props, "recipe.ar.pattern",
                    "archive_file_path" to archive.path, "archive_file" to archive.name, "object_file" to obj.path), archive)
            }
        }
        stamp.writeText(inputs)
        return archive
    }

    /** Compiles each (source, object) pair that isn't up to date; returns all objects. */
    private fun compileAll(props: Properties, pairs: List<Pair<File, File>>, includes: List<File>): List<File> {
        if (pairs.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(jobs.coerceAtLeast(1))
        try {
            var done = 0
            val futures = pairs.map { (source, obj) ->
                pool.submit(Callable {
                    val kind = SourceKind.of(source)!!
                    val argv = Recipes.compile(props, kind, source, obj, includes)
                    obj.parentFile.mkdirs()
                    val upToDate = ObjectCache.isUpToDate(obj, argv)
                    if (!upToDate) {
                        run(argv, source)
                        ObjectCache.record(obj, argv)
                    }
                    synchronized(this) {
                        done++
                        if (upToDate) reused++ else compiled++
                        listener(BuildEvent.Compiled(source, upToDate, done, pairs.size))
                    }
                    obj
                })
            }
            return futures.map {
                try { it.get() } catch (e: ExecutionException) { throw e.cause ?: e }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun run(argv: List<String>, subject: File) {
        listener(BuildEvent.Command(argv))
        val result = runner.run(argv, request.buildDir)
        if (result.exitCode != 0) throw CompileException(subject, result.output)
    }

    private fun size(props: Properties): Pair<Long, Long> {
        val argv = Recipes.expandRecipe(props, "recipe.size.pattern")
        listener(BuildEvent.Command(argv))
        val result = runner.run(argv, request.buildDir)
        if (result.exitCode != 0) throw BuildException("Size check failed:\n${result.output}")
        fun sum(key: String): Long {
            val regex = props[key]?.let { Regex(it, RegexOption.MULTILINE) } ?: return 0
            return regex.findAll(result.output).sumOf { it.groupValues[1].toLongOrNull() ?: 0 }
        }
        return sum("recipe.size.regex") to sum("recipe.size.regex.data")
    }

    private fun copySketchFiles(sketchDir: File, out: File): List<File> {
        val exts = setOf("c", "cpp", "cc", "S", "h", "hpp", "hh")
        val files = sketchDir.listFiles().orEmpty().filter { it.isFile && it.extension in exts } +
            File(sketchDir, "src").walkTopDown().filter { it.isFile && it.extension in exts }
        return files.sortedBy { it.path }.map { src ->
            val dest = File(out, src.relativeTo(sketchDir).path)
            dest.parentFile.mkdirs()
            writeIfChanged(dest, src.readBytes())
            dest
        }
    }

    private fun step(name: String) = listener(BuildEvent.Step(name))

    companion object {
        /** Writes only if different, so unchanged files keep their mtime and objects stay valid. */
        fun writeIfChanged(file: File, text: String) = writeIfChanged(file, text.toByteArray())

        fun writeIfChanged(file: File, bytes: ByteArray) {
            if (file.isFile && file.length() == bytes.size.toLong() && file.readBytes().contentEquals(bytes)) return
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
        }
    }
}

/**
 * Decides whether an object file can be reused: same command as last time (stored next to it),
 * and every dependency gcc listed in its `.d` file is older than the object.
 */
object ObjectCache {
    private fun commandFile(obj: File) = File(obj.path + ".cmd")

    fun isUpToDate(obj: File, argv: List<String>): Boolean {
        if (!obj.isFile) return false
        val cmd = commandFile(obj)
        if (!cmd.isFile || cmd.readText() != argv.joinToString("\n")) return false
        val deps = File(obj.path.removeSuffix(".o") + ".d")
        if (!deps.isFile) return false
        val built = obj.lastModified()
        return dependencies(deps.readText()).all { File(it).let { f -> f.isFile && f.lastModified() <= built } }
    }

    fun record(obj: File, argv: List<String>) = commandFile(obj).writeText(argv.joinToString("\n"))

    /** Paths listed in a make-style dependency file ("target: dep dep \\\n dep"). */
    fun dependencies(text: String): List<String> {
        val body = text.replace("\\\n", " ").substringAfter(":")
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            when {
                c == '\\' && i + 1 < body.length && body[i + 1] == ' ' -> { current.append(' '); i++ }
                c.isWhitespace() -> if (current.isNotEmpty()) { out += current.toString(); current.clear() }
                else -> current.append(c)
            }
            i++
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }
}
