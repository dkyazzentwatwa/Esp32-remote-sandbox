package org.espsketchide.app.compile

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.espsketchide.buildengine.MiniJson
import org.espsketchide.buildengine.esp32.PackLayout
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class PackInstallException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Board packs (toolchain/make-pack.py) installed under [packsDir]/<id>/. Installing extracts
 * into a temporary folder, checks every file against pack.json (size, and sha256 when asked),
 * then renames it into place, so a half-extracted pack is never used.
 */
class PackManager(private val packsDir: File, private val cacheDir: File) {

    fun interface Progress {
        /** [done]/[total] in bytes; total is -1 when unknown. */
        fun update(stage: String, done: Long, total: Long)
    }

    fun installed(): List<PackLayout> =
        packsDir.listFiles().orEmpty().filter { File(it, "pack.json").isFile && !it.name.startsWith(".") }
            .sortedBy { it.name }.mapNotNull { runCatching { PackLayout.read(it) }.getOrNull() }

    fun find(id: String): PackLayout? = installed().firstOrNull { it.id == id }

    /** Installs a `.tar.xz` pack from [input] (a download or a file the user picked). */
    fun install(input: InputStream, verifyHashes: Boolean = false, progress: Progress = Progress { _, _, _ -> }): PackLayout {
        packsDir.mkdirs()
        val staging = File(packsDir, ".staging-${System.nanoTime()}")
        try {
            val root = extract(input, staging, progress)
            verify(root, verifyHashes, progress)
            val pack = PackLayout.read(root)
            val dest = File(packsDir, pack.id)
            val old = File(packsDir, ".old-${pack.id}-${System.nanoTime()}")
            if (dest.exists() && !dest.renameTo(old)) throw PackInstallException("Couldn't replace the installed ${pack.id}")
            if (!root.renameTo(dest)) throw PackInstallException("Couldn't move the pack into place")
            old.deleteRecursively()
            return PackLayout.read(dest)
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * Downloads [url] into the cache (resuming a partial download), checks [sha256] if given,
     * and installs it.
     */
    fun download(url: String, sha256: String?, progress: Progress = Progress { _, _, _ -> }): PackLayout {
        cacheDir.mkdirs()
        val part = File(cacheDir, "pack-download-" + url.hashCode().toUInt() + ".part")
        var connection = (URL(url).openConnection() as HttpURLConnection)
        if (part.length() > 0) connection.setRequestProperty("Range", "bytes=${part.length()}-")
        connection.connect()
        val resumed = connection.responseCode == HttpURLConnection.HTTP_PARTIAL
        if (!resumed) {
            if (connection.responseCode !in 200..299) throw PackInstallException("Download failed: HTTP ${connection.responseCode}")
            part.delete()
        }
        val total = connection.contentLengthLong.let { if (it < 0) -1 else it + (if (resumed) part.length() else 0) }
        connection.inputStream.use { input ->
            java.io.FileOutputStream(part, resumed).use { out ->
                val buf = ByteArray(64 * 1024)
                var done = part.length()
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    progress.update("Downloading", done, total)
                }
            }
        }
        connection.disconnect()
        if (sha256 != null) {
            progress.update("Checking download", 0, part.length())
            val actual = sha256(part)
            if (!actual.equals(sha256, ignoreCase = true)) {
                part.delete()
                throw PackInstallException("The download is corrupted (checksum mismatch). Please try again.")
            }
        }
        return try {
            part.inputStream().use { install(it, verifyHashes = false, progress = progress) }
        } finally {
            part.delete()
        }
    }

    fun remove(id: String) {
        File(packsDir, id).deleteRecursively()
    }

    /** Extracts into [staging]; returns the pack's top folder. Rejects unsafe paths. */
    private fun extract(input: InputStream, staging: File, progress: Progress): File {
        staging.mkdirs()
        val base = staging.canonicalFile
        var bytes = 0L
        TarArchiveInputStream(XZInputStream(BufferedInputStream(input, 1 shl 16))).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val out = File(staging, entry.name).canonicalFile
                if (!out.path.startsWith(base.path + File.separator)) throw PackInstallException("Unsafe path in pack: ${entry.name}")
                when {
                    entry.isDirectory -> out.mkdirs()
                    entry.isSymbolicLink || entry.isLink -> throw PackInstallException("Links aren't allowed in packs: ${entry.name}")
                    else -> {
                        out.parentFile.mkdirs()
                        out.outputStream().use { tar.copyTo(it, 1 shl 16) }
                        bytes += entry.size
                        progress.update("Unpacking", bytes, -1)
                    }
                }
            }
        }
        val tops = staging.listFiles().orEmpty()
        if (tops.size != 1 || !File(tops[0], "pack.json").isFile) throw PackInstallException("This file isn't a board pack")
        return tops[0]
    }

    private fun verify(root: File, hashes: Boolean, progress: Progress) {
        val manifest = MiniJson.parse(File(root, "pack.json").readText()) as Map<*, *>
        val format = (manifest["format"] as? Long)?.toInt() ?: 0
        if (format != SUPPORTED_FORMAT) throw PackInstallException("This pack needs a newer version of the app (format $format)")
        val files = manifest["files"] as List<*>
        val total = files.sumOf { ((it as Map<*, *>)["size"] as Long) }
        var done = 0L
        for (f in files) {
            f as Map<*, *>
            val file = File(root, f["path"] as String)
            val size = f["size"] as Long
            if (!file.isFile || file.length() != size) throw PackInstallException("The pack is incomplete: ${f["path"]}")
            if (hashes && sha256(file) != f["sha256"]) throw PackInstallException("The pack is damaged: ${f["path"]}")
            done += size
            progress.update("Checking", done, total)
        }
    }

    companion object {
        const val SUPPORTED_FORMAT = 1

        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
