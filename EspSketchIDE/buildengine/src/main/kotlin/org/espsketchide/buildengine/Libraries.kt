package org.espsketchide.buildengine

import java.io.File

/** Where a library was found; lower [priority] wins when two libraries offer the same header. */
enum class LibraryLocation(val priority: Int) {
    /** In the sketch's own `libraries/` folder. */
    SKETCH(0),
    /** Installed by the user (library manager or .zip). */
    USER(1),
    /** Bundled with the board platform (`hardware/<vendor>/<arch>/<version>/libraries`). */
    PLATFORM(2),
}

/**
 * An Arduino library. 1.5-format libraries (with `library.properties` and `src/`) compile
 * everything under `src/` recursively; legacy libraries compile their root folder and `utility/`.
 */
data class Library(
    val name: String,
    val version: String,
    val dir: File,
    val location: LibraryLocation,
    val recursive: Boolean,
    val architectures: List<String>,
    /** Header files directly in the include folder; what `#include <X.h>` can find. */
    val headers: Set<String>,
) {
    /** The folder added with -I. */
    val includeDir: File get() = if (recursive) File(dir, "src") else dir

    fun supports(arch: String): Boolean = architectures.any { it == "*" || it.equals(arch, ignoreCase = true) }

    /** Source files to compile, in a stable order. */
    fun sources(): List<File> {
        val roots = if (recursive) listOf(File(dir, "src")) else listOf(dir, File(dir, "utility"))
        return roots.flatMap { root ->
            if (recursive) root.walkTopDown().filter { it.isFile && SourceKind.of(it) != null }.toList()
            else root.listFiles().orEmpty().filter { it.isFile && SourceKind.of(it) != null }
        }.sortedBy { it.path }
    }

    companion object {
        private val HEADER_EXTENSIONS = setOf("h", "hpp", "hh")

        /** Reads the library in [dir], or null if it isn't one. */
        fun load(dir: File, location: LibraryLocation): Library? {
            if (!dir.isDirectory) return null
            val propsFile = File(dir, "library.properties")
            val props = if (propsFile.isFile) Properties.load(propsFile) else null
            val recursive = props != null && File(dir, "src").isDirectory
            val includeDir = if (recursive) File(dir, "src") else dir
            val headers = includeDir.listFiles().orEmpty()
                .filter { it.isFile && it.extension.lowercase() in HEADER_EXTENSIONS }
                .map { it.name }.toSet()
            if (headers.isEmpty() && props == null) return null
            return Library(
                name = props?.get("name")?.takeIf { it.isNotBlank() } ?: dir.name,
                version = props?.get("version").orEmpty(),
                dir = dir,
                location = location,
                recursive = recursive,
                architectures = props?.get("architectures")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                    ?.ifEmpty { null } ?: listOf("*"),
                headers = headers,
            )
        }

        /** Every library directly inside [root] (e.g. a platform's `libraries/` folder). */
        fun scan(root: File, location: LibraryLocation): List<Library> =
            root.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }.mapNotNull { load(it, location) }
    }
}

/** Picks the library that provides a header, the way the Arduino IDE prioritises them. */
class LibraryResolver(libraries: List<Library>, private val arch: String) {

    private val byHeader: Map<String, List<Library>> =
        libraries.flatMap { lib -> lib.headers.map { it to lib } }.groupBy({ it.first }, { it.second })

    fun find(header: String): Library? {
        val candidates = byHeader[header] ?: return null
        val base = header.substringBeforeLast('.')
        return candidates.minWithOrNull(
            compareBy<Library> { if (it.supports(arch)) 0 else 1 }
                .thenBy { nameMatch(it, base) }
                .thenBy { it.location.priority }
                .thenBy { it.name }
        )
    }

    /** 0 = folder or name equals the header name, then prefix/suffix matches, then anything. */
    private fun nameMatch(lib: Library, base: String): Int {
        val names = listOf(lib.dir.name, lib.name).map { it.lowercase() }
        val b = base.lowercase()
        return when {
            b in names -> 0
            names.any { it.startsWith(b) || it.endsWith(b) } -> 1
            names.any { b in it } -> 2
            else -> 3
        }
    }
}
