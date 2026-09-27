package org.espsketchide.app.examples

import android.content.res.AssetManager

/** A bundled example sketch, e.g. category "Basics", name "Blink". */
data class Example(val category: String, val name: String, val path: String)

interface ExampleSource {
    fun list(): List<Example>

    /** File name to content for every file in the example's folder. */
    fun files(example: Example): Map<String, String>
}

/**
 * Examples under `assets/examples/<NN.Category>/<Name>/<Name>.ino`. The `NN.` prefix only
 * orders categories and is not shown.
 */
class AssetExampleSource(private val assets: AssetManager) : ExampleSource {

    override fun list(): List<Example> =
        assets.list(ROOT).orEmpty().sorted().flatMap { category ->
            assets.list("$ROOT/$category").orEmpty().sorted().map { name ->
                Example(displayCategory(category), name, "$ROOT/$category/$name")
            }
        }

    override fun files(example: Example): Map<String, String> =
        assets.list(example.path).orEmpty().associateWith { file ->
            assets.open("${example.path}/$file").bufferedReader().use { it.readText() }
        }

    companion object {
        private const val ROOT = "examples"

        fun displayCategory(folder: String): String = folder.substringAfter('.', folder)
    }
}
