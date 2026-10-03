package org.espsketchide.app.libraries

import org.espsketchide.app.settings.Board
import org.json.JSONArray
import org.json.JSONObject

/** One installable library from assets/libraries/catalog.json (made by tools/make-library-catalog.py). */
data class CatalogLibrary(
    val name: String,
    val version: String,
    val category: String,
    val description: String,
    val url: String,
    val sha256: String,
    val size: Long,
    val architectures: List<String>,
    val headers: List<String>,
    val dependencies: List<String>,
    /** Only installed because another library needs it; not listed on its own. */
    val isDependency: Boolean,
) {
    fun supports(board: Board) = architectures.any { it == "*" || it.equals(board.key, ignoreCase = true) }
}

/** The curated library list the Libraries screen offers. */
class LibraryCatalog(val all: List<CatalogLibrary>) {

    private val byName = all.associateBy { it.name }

    fun find(name: String): CatalogLibrary? = byName[name]

    /** What the Libraries screen lists for [board]: not dependencies, in catalog order. */
    fun visible(board: Board): List<CatalogLibrary> = all.filter { !it.isDependency && it.supports(board) }

    /** [name] plus its missing dependencies, dependencies first; already [installed] names are skipped. */
    fun installPlan(name: String, installed: Set<String>): List<CatalogLibrary> {
        val plan = LinkedHashSet<CatalogLibrary>()
        fun visit(library: CatalogLibrary, path: Set<String>) {
            if (library.name in installed || library in plan || library.name in path) return
            library.dependencies.mapNotNull(::find).forEach { visit(it, path + library.name) }
            plan += library
        }
        find(name)?.let { visit(it, emptySet()) }
        return plan.toList()
    }

    /** The catalog library whose headers include [header], e.g. "DHT.h", to suggest installing it. */
    fun suggestFor(header: String): CatalogLibrary? = all.firstOrNull { header in it.headers }

    companion object {
        fun parse(json: String): LibraryCatalog {
            val array = JSONObject(json).getJSONArray("libraries")
            return LibraryCatalog((0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                CatalogLibrary(
                    name = o.getString("name"),
                    version = o.getString("version"),
                    category = o.getString("category"),
                    description = o.optString("description"),
                    url = o.getString("url"),
                    sha256 = o.getString("sha256"),
                    size = o.getLong("size"),
                    architectures = o.getJSONArray("architectures").strings(),
                    headers = o.optJSONArray("headers")?.strings().orEmpty(),
                    dependencies = o.getJSONArray("dependencies").strings(),
                    isDependency = o.optBoolean("dependency"),
                )
            })
        }

        private fun JSONArray.strings() = (0 until length()).map { getString(it) }
    }
}
