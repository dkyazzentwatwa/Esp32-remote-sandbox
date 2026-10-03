package org.espsketchide.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.espsketchide.app.compile.BoardPrefs
import org.espsketchide.app.compile.BuildController
import org.espsketchide.app.compile.CompileBackend
import org.espsketchide.app.compile.NotReadyException
import org.espsketchide.app.compile.PackInstaller
import org.espsketchide.app.compile.PackManager
import org.espsketchide.app.compile.SketchCompiler
import org.espsketchide.app.compile.SketchStager
import org.espsketchide.app.compile.Toolchain
import org.espsketchide.app.data.DocumentFileStorage
import org.espsketchide.app.data.SketchRepository
import org.espsketchide.app.libraries.LibraryManager
import org.espsketchide.app.editor.EditorLanguages
import org.espsketchide.app.settings.AppSettings
import org.espsketchide.app.upload.SerialMonitor
import org.espsketchide.buildengine.esp32.PackLayout
import java.io.File

class EspSketchApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppSettings(this).themeMode.nightMode)
        EditorLanguages.init(this)
    }

    /** For work that must finish even after the screen that started it is gone (e.g. autosave). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val storage by lazy { DocumentFileStorage(this) }

    val repository by lazy { SketchRepository(storage) }

    val toolchain by lazy { Toolchain(File(applicationInfo.nativeLibraryDir), File(filesDir, "tc")) }

    val packs by lazy { PackManager(File(filesDir, "packs"), cacheDir) }

    val packInstaller by lazy { PackInstaller(packs, appScope) }

    val boardPrefs by lazy { BoardPrefs(this) }

    /** Library catalog, installer and downloads for the Libraries screen and the build. */
    val libraries by lazy { LibraryManager(this, appScope) }

    val builds by lazy {
        BuildController(appScope, SketchStager(storage), File(cacheDir, "stage"), File(cacheDir, "builds"), ::compileBackend)
    }

    /** The open serial monitor, kept here so it survives screen rotation. */
    var serialMonitor: SerialMonitor? = null

    /** Compile and upload need the compiler inside this APK; builds without it hide those actions. */
    val canCompile: Boolean get() = BuildConfig.TOOLCHAIN_BUNDLED && toolchain.isBundled

    /** The installed pack for the ESP32 (the only chip this version supports), or null. */
    fun esp32Pack(): PackLayout? = packs.installed().firstOrNull { it.chip == "esp32" }

    private fun compileBackend(): CompileBackend {
        if (!canCompile) throw NotReadyException(getString(R.string.error_no_compiler))
        val pack = esp32Pack() ?: throw NotReadyException(getString(R.string.error_no_pack))
        synchronized(toolchain) { toolchain.prepare(pack) }
        val tmp = File(cacheDir, "tmp").apply { mkdirs() }
        val compiler = SketchCompiler(
            toolchain.treeDir, pack, tmp,
            userLibraries = { libraries.installer.installed() },
            suggestLibrary = { header -> libraries.catalog.suggestFor(header)?.name },
        )
        return CompileBackend(compiler::compile)
    }
}
