package org.espsketchide.buildengine.esp32

import org.espsketchide.buildengine.BuildException
import org.espsketchide.buildengine.Platform
import java.io.File
import java.nio.file.Files

/**
 * An installed ESP32 board pack (made by toolchain/make-pack.py): where its platform, SDK and
 * gcc target files are, which boards it supports, and how to plug it into a toolchain tree.
 * Reads only the small header of pack.json (the file list is for install-time verification).
 */
class PackLayout private constructor(
    val dir: File,
    val id: String,
    val core: String,
    val chip: String,
    val gccVersion: String,
    val defaultBoard: String,
    val boards: List<Pair<String, String>>,
    private val platformDir: String,
    private val tools: Map<String, String>,
) {
    /** The toolchain tool folder in the pack (holds only gcc target files). */
    val targetFilesDir: File get() = File(dir, tools.getValue("esp-x32"))

    /**
     * A [Platform] for building from this pack. [toolchainTree] is the gcc-shaped folder with the
     * host programs (see [XtensaToolchainRunner]); recipes find the compiler through it.
     */
    fun platform(toolchainTree: File): Platform {
        val toolDirs = tools.mapValues { File(dir, it.value) } + ("esp-x32" to toolchainTree)
        return Platform(File(dir, platformDir), vendor = "esp32", arch = "esp32", tools = toolDirs)
    }

    /** Links the pack's gcc target files into [tree] where gcc expects them. */
    fun linkTargetFiles(tree: File) {
        val v = gccVersion
        link(File(targetFilesDir, "lib/gcc/xtensa-esp-elf/$v"), File(tree, "lib/gcc/xtensa-esp-elf/$v"))
        link(File(targetFilesDir, "xtensa-esp-elf/include"), File(tree, "xtensa-esp-elf/include"))
        link(File(targetFilesDir, "xtensa-esp-elf/lib"), File(tree, "xtensa-esp-elf/lib"))
    }

    companion object {
        fun read(dir: File): PackLayout {
            val manifest = File(dir, "pack.json")
            if (!manifest.isFile) throw BuildException("${dir.name} is not a board pack (no pack.json)")
            val json = org.espsketchide.buildengine.MiniJson.parse(manifest.readText()) as Map<*, *>
            fun str(key: String) = json[key] as? String ?: throw BuildException("pack.json has no '$key'")
            @Suppress("UNCHECKED_CAST")
            val tools = (json["tools"] as Map<String, String>)
            val gcc = json["gcc"] as Map<*, *>
            val boards = (json["boards"] as List<*>).map { b -> (b as Map<*, *>).let { it["id"] as String to it["name"] as String } }
            return PackLayout(dir, str("id"), str("core"), str("chip"), gcc["version"] as String, str("defaultBoard"), boards, str("platformDir"), tools)
        }

        private fun link(target: File, at: File) {
            at.parentFile.mkdirs()
            if (Files.isSymbolicLink(at.toPath()) || at.exists()) at.delete()
            Files.createSymbolicLink(at.toPath(), target.toPath())
        }
    }
}
