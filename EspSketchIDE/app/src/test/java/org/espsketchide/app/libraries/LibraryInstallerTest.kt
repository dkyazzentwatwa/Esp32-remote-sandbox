package org.espsketchide.app.libraries

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LibraryInstallerTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun zip(vararg files: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> files.forEach { (name, text) -> z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() } }
        return out.toByteArray()
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private val dhtZip = zip(
        "DHT-sensor-library-1.4.7/library.properties" to "name=DHT sensor library\nversion=1.4.7\narchitectures=*\n",
        "DHT-sensor-library-1.4.7/src/DHT.h" to "#pragma once",
        "DHT-sensor-library-1.4.7/src/DHT.cpp" to "",
    )

    @Test
    fun installsUnderTheLibraryNameWithoutTheArchiveFolder() {
        val installer = LibraryInstaller(tmp.newFolder("libraries"), tmp.newFolder("work"))
        installer.install("DHT sensor library", dhtZip.inputStream(), sha256 = sha(dhtZip))
        val dir = File(installer.librariesDir, "DHT_sensor_library")
        assertThat(File(dir, "src/DHT.h").isFile).isTrue()
        assertThat(installer.installed().map { it.name }).containsExactly("DHT sensor library")
    }

    @Test
    fun checksumMismatchInstallsNothing() {
        val installer = LibraryInstaller(tmp.newFolder("libraries"), tmp.newFolder("work"))
        val failure = runCatching { installer.install("DHT sensor library", dhtZip.inputStream(), sha256 = "0".repeat(64)) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(LibraryInstallException::class.java)
        assertThat(installer.librariesDir.listFiles()).isEmpty()
    }

    @Test
    fun pathsEscapingTheFolderAreRejected() {
        val evil = zip("lib/library.properties" to "name=x\n", "lib/../../evil.txt" to "boom")
        val installer = LibraryInstaller(tmp.newFolder("libraries"), tmp.newFolder("work"))
        val failure = runCatching { installer.install("x", evil.inputStream(), sha256 = null) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(LibraryInstallException::class.java)
        assertThat(File(tmp.root, "evil.txt").exists()).isFalse()
    }

    @Test
    fun zipWithoutALibraryIsRejected() {
        val installer = LibraryInstaller(tmp.newFolder("libraries"), tmp.newFolder("work"))
        val failure = runCatching { installer.install("x", zip("readme.txt" to "hi").inputStream(), sha256 = null) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(LibraryInstallException::class.java)
    }

    @Test
    fun zipWithoutATopFolderAndNameFromPropertiesWhenUnnamed() {
        val flat = zip("library.properties" to "name=My Lib\nversion=1.0\n", "MyLib.h" to "")
        val installer = LibraryInstaller(tmp.newFolder("libraries"), tmp.newFolder("work"))
        val installed = installer.install(name = null, flat.inputStream(), sha256 = null)
        assertThat(installed.name).isEqualTo("My Lib")
        assertThat(File(installer.librariesDir, "My_Lib/MyLib.h").isFile).isTrue()
    }

    @Test
    fun reinstallReplacesAndRemoveDeletes() {
        val installer = LibraryInstaller(tmp.newFolder("libraries"), tmp.newFolder("work"))
        installer.install("DHT sensor library", dhtZip.inputStream(), sha256 = null)
        installer.install("DHT sensor library", dhtZip.inputStream(), sha256 = null)
        assertThat(installer.installed()).hasSize(1)
        installer.remove("DHT sensor library")
        assertThat(installer.installed()).isEmpty()
    }
}
