package org.espsketchide.buildengine

/** One option of a board menu, e.g. PartitionScheme=huge_app. */
data class MenuOption(val id: String, val label: String)

/** A board menu (Tools > Partition Scheme ...) with its options in file order; the first is the default. */
data class BoardMenu(val id: String, val label: String, val options: List<MenuOption>) {
    val default: MenuOption get() = options.first()
}

/** A board from boards.txt. [properties] are the board's own keys without its id prefix. */
class Board(val id: String, val name: String, val menus: List<BoardMenu>, private val properties: Properties) {

    /**
     * The board's build properties with menu choices applied. Options not in [selection] use
     * the menu's first option, as the Arduino IDE does.
     */
    fun resolve(selection: Map<String, String> = emptyMap()): Properties {
        selection.forEach { (menu, option) ->
            val m = menus.firstOrNull { it.id == menu } ?: throw BuildException("Board $id has no menu '$menu'")
            if (m.options.none { it.id == option }) throw BuildException("Menu '$menu' has no option '$option'")
        }
        // Like arduino-cli, the raw menu.* keys stay in the result (harmless, and keeps output comparable).
        var result = properties.with("_id" to id)
        for (menu in menus) {
            val option = selection[menu.id] ?: menu.default.id
            result += properties.subtree("menu.${menu.id}.$option")
        }
        return result
    }
}

class BoardCatalog(val boards: List<Board>) {

    operator fun get(id: String): Board = boards.firstOrNull { it.id == id } ?: throw BuildException("Unknown board '$id'")

    companion object {
        fun parse(boardsTxt: Properties): BoardCatalog {
            val menuLabels = boardsTxt.subtree("menu").toMap()
            val boardIds = boardsTxt.keys.filter { it.endsWith(".name") && it.count { c -> c == '.' } == 1 }
                .map { it.removeSuffix(".name") }
            val boards = boardIds.map { id ->
                val props = boardsTxt.subtree(id)
                val menus = menuLabels.keys.mapNotNull { menuId ->
                    // Options are the keys "menu.<menu>.<option>" (labels); deeper keys are settings.
                    val options = props.keys
                        .filter { it.startsWith("menu.$menuId.") && it.count { c -> c == '.' } == 2 }
                        .map { MenuOption(it.substringAfterLast('.'), props.getValue(it)) }
                    if (options.isEmpty()) null else BoardMenu(menuId, menuLabels.getValue(menuId), options)
                }
                Board(id, props.getValue("name"), menus, props)
            }
            return BoardCatalog(boards)
        }
    }
}
