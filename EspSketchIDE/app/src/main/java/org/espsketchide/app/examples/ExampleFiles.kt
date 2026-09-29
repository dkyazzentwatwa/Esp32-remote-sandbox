package org.espsketchide.app.examples

import android.content.res.AssetManager

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
