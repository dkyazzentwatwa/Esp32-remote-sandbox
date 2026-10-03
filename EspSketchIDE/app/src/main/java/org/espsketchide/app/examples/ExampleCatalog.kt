package org.espsketchide.app.examples

import org.espsketchide.app.settings.Board
import org.json.JSONObject

/** One entry of `assets/examples/index.json`. [variants] maps a board key to a folder under `assets/examples/`. */
data class Example(
    val id: String,
    val name: String,
    val category: String,
    val description: String,
    val variants: Map<String, String>,
    val lesson: Lesson? = null,
)

/** What an example teaches, shown before the sketch opens. */
data class Lesson(val learn: String, val wiring: String, val how: String, val tryThis: List<String>)

/** A row of the Examples list: a category header or an example. */
sealed interface ExampleRow {
    data class Header(val title: String) : ExampleRow
    data class Item(val example: Example) : ExampleRow
}

object ExampleCatalog {

    fun parse(json: String): List<Example> {
        val array = JSONObject(json).getJSONArray("examples")
        return (0 until array.length()).map { i ->
            val entry = array.getJSONObject(i)
            val variants = entry.getJSONObject("variants")
            Example(
                id = entry.getString("id"),
                name = entry.getString("name"),
                category = entry.getString("category"),
                description = entry.optString("description"),
                variants = variants.keys().asSequence().associateWith { variants.getString(it) },
                lesson = entry.optJSONObject("lesson")?.let { l ->
                    val tries = l.optJSONArray("tryThis")
                    Lesson(
                        learn = l.optString("learn"),
                        wiring = l.optString("wiring"),
                        how = l.optString("how"),
                        tryThis = if (tries == null) emptyList() else (0 until tries.length()).map { tries.getString(it) },
                    )
                },
            )
        }
    }

    /** Examples that have a variant for [board], grouped by category in order of first appearance. */
    fun forBoard(examples: List<Example>, board: Board): List<Pair<String, List<Example>>> =
        examples.filter { variantPath(it, board) != null }
            .groupBy { it.category }
            .toList()

    fun variantPath(example: Example, board: Board): String? = example.variants[board.key]

    fun rows(groups: List<Pair<String, List<Example>>>): List<ExampleRow> =
        groups.flatMap { (category, items) -> listOf<ExampleRow>(ExampleRow.Header(category)) + items.map { ExampleRow.Item(it) } }
}
