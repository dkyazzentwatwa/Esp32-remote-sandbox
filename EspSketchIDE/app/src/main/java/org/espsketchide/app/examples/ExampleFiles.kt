package org.espsketchide.app.examples

import android.content.res.AssetManager
import org.espsketchide.app.data.SketchNames
import org.espsketchide.app.settings.Board

object ExampleFiles {

    private const val ROOT = "examples"

    /** Reads every file of a variant folder (a path from the index, e.g. `basics/Blink`) as name to text. */
    fun read(assets: AssetManager, variantPath: String): Map<String, String> {
        val dir = "$ROOT/$variantPath"
        return assets.list(dir).orEmpty().sorted().associateWith { name ->
            assets.open("$dir/$name").bufferedReader().use { it.readText() }
        }
    }

    fun readIndex(assets: AssetManager): String =
        assets.open("$ROOT/index.json").bufferedReader().use { it.readText() }
}

/** A bundled example ready to copy: a valid sketch name and its files, primary `.ino` renamed to match. */
data class ExampleSketch(val name: String, val files: Map<String, String>)

/** Reads the variant of example [id] for [board], or null if the example or that variant doesn't exist. */
fun ExampleFiles.load(assets: AssetManager, id: String, board: Board): ExampleSketch? {
    val example = ExampleCatalog.parse(readIndex(assets)).firstOrNull { it.id == id } ?: return null
    val path = ExampleCatalog.variantPath(example, board) ?: return null
    val name = SketchNames.toSketchName(example.name)
    return ExampleSketch(name, SketchNames.renamePrimary(read(assets, path), name))
}
