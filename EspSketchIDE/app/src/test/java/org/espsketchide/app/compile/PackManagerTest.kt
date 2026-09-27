package org.espsketchide.app.compile

import com.google.common.truth.Truth.assertThat
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class PackManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** A minimal pack as make-pack.py lays it out, as .tar.xz bytes. */
    private fun pack(
        id: String = "esp32-3.3.12",
        files: Map<String, ByteArray> = mapOf("hardware/esp32/3.3.12/platform.txt" to "name=ESP32\n".toByteArray()),
        lie: Boolean = false,
        extraEntry: String? = null,
    ): ByteArray {
        val listed = files.entries.joinToString(",") { (p, b) -> """{"path":"$p","size":${b.size + if (lie) 1 else 0},"sha256":"${sha(b)}"}""" }
        val manifest = """{"format":1,"id":"$id","vendor":"esp32","arch":"esp32","chip":"esp32","core":"3.3.12",
            "platformDir":"hardware/esp32/3.3.12","tools":{"esp32-libs":"tools/esp32-libs/3.3.12","esp-x32":"tools/esp-x32/2601"},
            "gcc":{"version":"14.2.0"},"defaultBoard":"esp32","boards":[{"id":"esp32","name":"ESP32 Dev Module"}],
            "unpackedSize":1,"files":[$listed]}"""
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(XZOutputStream(bytes, LZMA2Options())).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            fun put(name: String, data: ByteArray) {
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = data.size.toLong() })
                tar.write(data)
                tar.closeArchiveEntry()
            }
            put("$id/pack.json", manifest.toByteArray())
            files.forEach { (p, b) -> put("$id/$p", b) }
            extraEntry?.let { put(it, ByteArray(1)) }
        }
        return bytes.toByteArray()
    }

    private fun manager() = PackManager(tmp.newFolder("packs"), tmp.newFolder("cache"))

    @Test
    fun installsVerifiesAndLists() {
        val m = manager()

        val pack = m.install(ByteArrayInputStream(pack()), verifyHashes = true)

        assertThat(pack.id).isEqualTo("esp32-3.3.12")
        assertThat(pack.defaultBoard).isEqualTo("esp32")
        assertThat(m.installed().map { it.id }).containsExactly("esp32-3.3.12")
        assertThat(pack.dir.resolve("hardware/esp32/3.3.12/platform.txt").readText()).isEqualTo("name=ESP32\n")
    }

    @Test
    fun reinstallReplacesAtomically() {
        val m = manager()
        m.install(ByteArrayInputStream(pack()))

        m.install(ByteArrayInputStream(pack(files = mapOf("hardware/esp32/3.3.12/platform.txt" to "name=v2\n".toByteArray()))))

        assertThat(m.installed()).hasSize(1)
        assertThat(m.find("esp32-3.3.12")!!.dir.resolve("hardware/esp32/3.3.12/platform.txt").readText()).isEqualTo("name=v2\n")
    }

    @Test
    fun incompletePackIsRejectedAndLeavesNothingBehind() {
        val m = manager()

        assertThrows(PackInstallException::class.java) { m.install(ByteArrayInputStream(pack(lie = true))) }
        assertThat(m.installed()).isEmpty()
    }

    @Test
    fun pathTraversalIsRejected() {
        val m = manager()

        assertThrows(PackInstallException::class.java) { m.install(ByteArrayInputStream(pack(extraEntry = "../../evil.txt"))) }
    }

    @Test
    fun nonPackArchiveIsRejected() {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(XZOutputStream(bytes, LZMA2Options())).use { tar ->
            tar.putArchiveEntry(TarArchiveEntry("random/file.txt").apply { size = 1 }); tar.write(1); tar.closeArchiveEntry()
        }

        assertThrows(PackInstallException::class.java) { manager().install(ByteArrayInputStream(bytes.toByteArray())) }
    }
}
