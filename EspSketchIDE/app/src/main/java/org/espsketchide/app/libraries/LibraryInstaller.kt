package org.espsketchide.app.libraries

import org.espsketchide.buildengine.Library
import org.espsketchide.buildengine.LibraryLocation
import java.io.File
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

class LibraryInstallException(message: String) : Exception(message)

/**
 * Installs Arduino libraries from .zip archives into [librariesDir] (one folder per library),
 * which the build passes to the build engine as user libraries. Archives are checked against
 * their SHA-256 when known, extracted into [workDir] with path checks, and moved into place only
 * once complete, so a failed install leaves nothing behind.
 */
class LibraryInstaller(val librariesDir: File, private val workDir: File) {

    /** Every installed library, as the build engine sees them. */
    fun installed(): List<Library> = Library.scan(librariesDir, LibraryLocation.USER)

    fun installedNames(): Set<String> = installed().map { it.name }.toSet()

    /**
     * Installs the library in [zip]. [name] is the catalog name (null for a user's own .zip: the
     * name comes from library.properties or the folder). Replaces an existing install.
     */
    fun install(name: String?, zip: InputStream, sha256: String?): Library {
        workDir.mkdirs()
        val staging = File(workDir, "lib-" + System.nanoTime()).apply { mkdirs() }
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            DigestInputStream(zip, digest).use { input -> extract(input, staging) }
            if (sha256 != null) {
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(sha256, ignoreCase = true)) throw LibraryInstallException("The download is damaged (checksum mismatch). Try again.")
            }
            val root = libraryRoot(staging) ?: throw LibraryInstallException("This .zip doesn't contain an Arduino library.")
            val library = Library.load(root, LibraryLocation.USER) ?: throw LibraryInstallException("This .zip doesn't contain an Arduino library.")
            val finalName = folderName(name ?: library.name)
            librariesDir.mkdirs()
            val target = File(librariesDir, finalName)
            target.deleteRecursively()
            if (!root.renameTo(target)) {
                root.copyRecursively(target, overwrite = true)
            }
            return Library.load(target, LibraryLocation.USER) ?: throw LibraryInstallException("Install failed.")
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Removes the installed library named [name] (its library.properties name or folder). */
    fun remove(name: String) {
        installed().filter { it.name == name || it.dir.name == folderName(name) }.forEach { it.dir.deleteRecursively() }
    }

    private fun extract(input: InputStream, into: File) {
        val base = into.canonicalFile
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = File(into, entry.name).canonicalFile
                if (!target.path.startsWith(base.path + File.separator)) {
                    throw LibraryInstallException("This .zip has a file outside its folder (${entry.name}); not installing it.")
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zip.copyTo(it) }
                }
            }
            // Drain anything after the last entry so the digest covers the whole file.
            val buffer = ByteArray(8192)
            while (input.read(buffer) >= 0) Unit
        }
    }

    /** The extracted folder that holds the library: the root, or a single top-level folder (GitHub archives). */
    private fun libraryRoot(staging: File): File? {
        if (Library.load(staging, LibraryLocation.USER) != null) return staging
        val children = staging.listFiles().orEmpty().filter { it.name != "__MACOSX" }
        return children.singleOrNull { it.isDirectory }
    }

    private fun folderName(name: String) = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
