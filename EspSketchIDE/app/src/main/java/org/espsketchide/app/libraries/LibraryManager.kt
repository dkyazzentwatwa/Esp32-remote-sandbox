package org.espsketchide.app.libraries

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class LibrariesState(
    val installed: Set<String> = emptySet(),
    /** The library being downloaded or installed right now, if any. */
    val working: String? = null,
)

sealed interface LibraryEvent {
    data class Installed(val names: List<String>) : LibraryEvent
    data class Removed(val name: String) : LibraryEvent
    data class Failed(val message: String) : LibraryEvent
}

/** Catalog, downloads and installs for the Libraries screen; installs outlive the screen (appScope). */
class LibraryManager(private val context: Context, private val scope: CoroutineScope) {

    val catalog: LibraryCatalog by lazy {
        LibraryCatalog.parse(context.assets.open("libraries/catalog.json").bufferedReader().use { it.readText() })
    }

    val installer = LibraryInstaller(File(context.filesDir, "libraries"), File(context.cacheDir, "libwork"))

    private val _state = MutableStateFlow(LibrariesState())
    val state: StateFlow<LibrariesState> = _state.asStateFlow()

    private val _events = Channel<LibraryEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun refresh() {
        scope.launch { _state.update { it.copy(installed = installer.installedNames()) } }
    }

    /** Downloads and installs [name] and any dependencies it's missing. */
    fun install(name: String) = work(name) {
        val plan = catalog.installPlan(name, installer.installedNames())
        for (library in plan) {
            _state.update { it.copy(working = library.name) }
            download(library.url).use { input -> installer.install(library.name, input, library.sha256) }
        }
        LibraryEvent.Installed(plan.map { it.name })
    }

    /** Installs a library from a .zip the user picked (Install from .zip). */
    fun installZip(uri: Uri) = work("zip") {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Can't read the file")
        val library = input.use { installer.install(name = null, it, sha256 = null) }
        LibraryEvent.Installed(listOf(library.name))
    }

    fun remove(name: String) = work(name) {
        installer.remove(name)
        LibraryEvent.Removed(name)
    }

    private fun work(label: String, action: suspend () -> LibraryEvent) {
        if (_state.value.working != null) return
        _state.update { it.copy(working = label) }
        scope.launch {
            val event = try {
                action()
            } catch (e: LibraryInstallException) {
                LibraryEvent.Failed(e.message ?: "Install failed")
            } catch (e: java.net.UnknownHostException) {
                LibraryEvent.Failed(NO_INTERNET)
            } catch (e: java.net.SocketTimeoutException) {
                LibraryEvent.Failed(NO_INTERNET)
            } catch (e: IOException) {
                LibraryEvent.Failed("Download failed: ${e.message ?: "check the internet connection"}")
            }
            _state.update { LibrariesState(installed = installer.installedNames(), working = null) }
            _events.send(event)
        }
    }

    private fun download(url: String): java.io.InputStream {
        var current = URL(url)
        repeat(MAX_REDIRECTS) {
            if (current.protocol != "https") throw IOException("refusing a non-HTTPS download")
            val connection = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
            }
            when (val code = connection.responseCode) {
                in 200..299 -> return connection.inputStream
                in 300..399 -> current = URL(current, connection.getHeaderField("Location") ?: throw IOException("Redirect without a location"))
                else -> throw IOException("server answered $code")
            }
        }
        throw IOException("Too many redirects")
    }

    private companion object {
        const val MAX_REDIRECTS = 5
        const val NO_INTERNET = "No internet connection. Connect to Wi-Fi and try again, or use Install from .zip."
    }
}
