package org.espsketchide.app.compile

import org.espsketchide.buildengine.esp32.PackLayout
import java.io.File
import java.nio.file.Files

/**
 * The xtensa toolchain shipped in the APK as lib*.so files, arranged into the gcc-shaped folder
 * the build engine expects (see XtensaToolchainRunner and docs/superpowers/spikes/p0-results.md):
 *
 *   tree/bin/xtensa-esp-elf-{gcc,g++,ar,objcopy,size}      -> nativeLibraryDir/libxt*.so
 *   tree/libexec/gcc/xtensa-esp-elf/<v>/{cc1,cc1plus,collect2}
 *   tree/xtensa-esp-elf/bin/{as,ld,ar}
 *   tree/lib/xtensa_esp32.so                               (gcc/as/ld load it via XTENSA_GNU_CONFIG)
 *   tree/lib/gcc/..., tree/xtensa-esp-elf/{include,lib}     -> the installed board pack
 *
 * Links (not copies): Android only lets apps execute files installed with the APK.
 */
class Toolchain(private val nativeLibraryDir: File, val treeDir: File, private val gccVersion: String = "14.2.0") {

    private val links: Map<String, String> = buildMap {
        put("bin/xtensa-esp-elf-gcc", "libxtgcc.so")
        put("bin/xtensa-esp-elf-g++", "libxtgxx.so")
        put("bin/xtensa-esp-elf-ar", "libxtar.so")
        put("bin/xtensa-esp-elf-objcopy", "libxtobjcopy.so")
        put("bin/xtensa-esp-elf-size", "libxtsize.so")
        for (p in listOf("cc1", "cc1plus", "collect2")) put("libexec/gcc/xtensa-esp-elf/$gccVersion/$p", "libxt$p.so")
        for (p in listOf("as", "ld", "ar")) put("xtensa-esp-elf/bin/$p", "libxt$p.so")
        put("lib/xtensa_esp32.so", "libxtensa_esp32.so")
    }

    /** True if this APK contains the compiler. */
    val isBundled: Boolean get() = links.values.all { File(nativeLibraryDir, it).isFile }

    /**
     * Creates or refreshes the tree for [pack]. Cheap enough to run before every build; links
     * are rewritten because nativeLibraryDir changes when the app is updated.
     */
    fun prepare(pack: PackLayout) {
        check(isBundled) { "This version of the app has no compiler" }
        links.forEach { (at, lib) -> link(File(nativeLibraryDir, lib), File(treeDir, at)) }
        pack.linkTargetFiles(treeDir)
    }

    private fun link(target: File, at: File) {
        at.parentFile?.mkdirs()
        val path = at.toPath()
        if (Files.isSymbolicLink(path) && Files.readSymbolicLink(path) == target.toPath()) return
        Files.deleteIfExists(path)
        Files.createSymbolicLink(path, target.toPath())
    }
}
