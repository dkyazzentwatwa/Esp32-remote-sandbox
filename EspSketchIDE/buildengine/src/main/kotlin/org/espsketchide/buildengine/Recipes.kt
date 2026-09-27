package org.espsketchide.buildengine

import java.io.File

/** Turns platform.txt recipe patterns into argv for one file. */
object Recipes {

    /** argv for `recipe.<kind>.o.pattern` compiling [source] to [objectFile]. */
    fun compile(props: Properties, kind: SourceKind, source: File, objectFile: File, includes: List<File>): List<String> =
        expandRecipe(
            props, "recipe.${kind.recipe}.o.pattern",
            "source_file" to source.path,
            "object_file" to objectFile.path,
            "includes" to includeFlags(includes),
        )

    /**
     * argv that runs only the C preprocessor over [source] into [output]: the C++ compile recipe
     * without dependency output, plus `-w -x c++ -E -CC` (keep comments), like the Arduino IDE.
     */
    fun preprocess(props: Properties, source: File, output: File, includes: List<File>): List<String> {
        val argv = compile(props, SourceKind.CPP, source, output, includes).toMutableList()
        argv.removeAll { it == "-MMD" }
        argv.addAll(1, listOf("-w", "-x", "c++", "-E", "-CC"))
        return argv
    }

    fun expandRecipe(props: Properties, key: String, vararg values: Pair<String, String>): List<String> {
        val pattern = props[key] ?: throw BuildException("Platform has no $key")
        val expanded = props.with(*values).expand(pattern)
        LEFTOVER.find(expanded)?.let { throw BuildException("Unresolved ${it.value} in $key") }
        return CommandLine.split(expanded)
    }

    fun includeFlags(dirs: List<File>): String = dirs.joinToString(" ") { "\"-I${it.path}\"" }

    private val LEFTOVER = Regex("""\{[a-z_.\-]+\}""")
}

enum class SourceKind(val recipe: String) {
    C("c"), CPP("cpp"), ASM("S");

    companion object {
        fun of(file: File): SourceKind? = when (file.extension) {
            "c" -> C
            "cpp", "cc", "cxx" -> CPP
            "S" -> ASM
            else -> null
        }
    }
}
