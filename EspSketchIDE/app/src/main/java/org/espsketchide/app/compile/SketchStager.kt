package org.espsketchide.app.compile

import org.espsketchide.app.data.SketchStorage
import org.espsketchide.app.data.StorageNode
import org.espsketchide.app.model.Sketch
import java.io.File

/**
 * Copies a sketch from SAF storage into a real folder for the compiler, which needs file paths.
 * Takes what Arduino compiles: source files in the sketch folder and everything under `src/`
 * (recursively). Files are rewritten only when their content changed and files that disappeared
 * are removed, so the build engine's object cache stays valid between builds.
 */
class SketchStager(private val storage: SketchStorage) {

    /** Stages [sketch] into [dest]/<name>/ and returns that folder. */
    fun stage(sketch: Sketch, dest: File): File {
        val out = File(dest, sketch.name)
        val wanted = mutableSetOf<File>()
        val folder = StorageNode(sketch.folderId, sketch.name, isDirectory = true)
        for (node in storage.children(folder)) {
            when {
                node.isDirectory && node.name == "src" -> copyTree(node, File(out, "src"), wanted)
                !node.isDirectory && isSource(node.name) -> copy(node, File(out, node.name), wanted)
            }
        }
        if (File(out, "${sketch.name}.ino") !in wanted) throw IllegalStateException("${sketch.name}.ino is missing")
        out.walkBottomUp().forEach { f ->
            if (f.isFile && f !in wanted) f.delete()
            else if (f.isDirectory && f != out && f.listFiles().isNullOrEmpty()) f.delete()
        }
        return out
    }

    private fun copyTree(dir: StorageNode, out: File, wanted: MutableSet<File>) {
        for (node in storage.children(dir)) {
            if (node.isDirectory) copyTree(node, File(out, node.name), wanted)
            else if (isSource(node.name)) copy(node, File(out, node.name), wanted)
        }
    }

    private fun copy(node: StorageNode, to: File, wanted: MutableSet<File>) {
        val text = storage.read(node)
        wanted += to
        if (to.isFile && to.readText() == text) return
        to.parentFile?.mkdirs()
        to.writeText(text)
    }

    companion object {
        private val SOURCE_EXTENSIONS = setOf("ino", "pde", "h", "hh", "hpp", "c", "cpp", "cc", "cxx", "s", "tpp", "ipp")

        fun isSource(name: String) = name.substringAfterLast('.', "").lowercase() in SOURCE_EXTENSIONS &&
            !name.startsWith(".")
    }
}
