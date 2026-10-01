package org.espsketchide.app.reference

import org.espsketchide.app.settings.Board
import org.json.JSONArray
import org.json.JSONObject

/** One reference card: what a function/keyword/constant does, with an example. */
data class ReferenceEntry(
    val name: String,
    val category: String,
    val syntax: String,
    val summary: String,
    val params: List<Pair<String, String>> = emptyList(),
    val returns: String? = null,
    val example: String? = null,
    val note: String? = null,
    val aliases: List<String> = emptyList(),
    /** Board families the entry applies to; empty means all. */
    val boards: Set<String> = emptySet(),
) {
    fun appliesTo(board: Board): Boolean = boards.isEmpty() || board.key in boards
}

/** The offline Arduino/ESP reference in assets/reference/reference.json. */
class ArduinoReference(val entries: List<ReferenceEntry>) {

    private val byName: Map<String, ReferenceEntry> =
        entries.flatMap { entry -> (listOf(entry.name) + entry.aliases).map { it to entry } }.toMap()

    fun lookup(name: String): ReferenceEntry? = byName[name]

    /** The entry for the word at [column] in [line], e.g. `Serial.println` or `digitalWrite`. */
    fun lookupAt(line: String, column: Int): ReferenceEntry? = wordAt(line, column)?.let(::lookup)

    /** Categories in file order, each with its entries. */
    fun byCategory(): List<Pair<String, List<ReferenceEntry>>> =
        entries.groupBy { it.category }.toList()

    companion object {

        fun parse(json: String): ArduinoReference {
            val array = JSONObject(json).getJSONArray("entries")
            return ArduinoReference((0 until array.length()).map { entry(array.getJSONObject(it)) })
        }

        private fun entry(o: JSONObject) = ReferenceEntry(
            name = o.getString("name"),
            category = o.getString("category"),
            syntax = o.getString("syntax"),
            summary = o.getString("summary"),
            params = o.optJSONArray("params")?.let { params ->
                (0 until params.length()).map { params.getJSONArray(it).let { p -> p.getString(0) to p.getString(1) } }
            }.orEmpty(),
            returns = o.optString("returns").ifEmpty { null },
            example = o.optString("example").ifEmpty { null },
            note = o.optString("note").ifEmpty { null },
            aliases = o.optJSONArray("aliases").strings(),
            boards = o.optJSONArray("boards").strings().toSet(),
        )

        private fun JSONArray?.strings(): List<String> =
            if (this == null) emptyList() else (0 until length()).map { getString(it) }

        private fun Char.isIdentifierPart() = isLetterOrDigit() || this == '_'

        /**
         * The identifier at [column], joined with a member access around it: on `Serial.println(`
         * both `Serial` and `println` give "Serial.println". Null if the column isn't on a word.
         */
        fun wordAt(line: String, column: Int): String? {
            if (column !in line.indices || !line[column].isIdentifierPart()) return null
            var start = column
            while (start > 0 && (line[start - 1].isIdentifierPart() || line[start - 1] == '.')) start--
            var end = column
            while (end < line.length && line[end].isIdentifierPart()) end++
            // Include one ".member" after the word, e.g. tapping "Serial" in "Serial.println".
            if (end < line.length - 1 && line[end] == '.' && line[end + 1].isIdentifierPart()) {
                end++
                while (end < line.length && line[end].isIdentifierPart()) end++
            }
            return line.substring(start, end).trim('.').takeIf { it.isNotEmpty() && it.first().isLetter() || it.startsWith('_') }
        }
    }
}
