package org.espsketchide.app.compile

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

sealed interface PackInstallState {
    data object Idle : PackInstallState
    data class Working(val stage: String, val fraction: Float?) : PackInstallState
    data class Installed(val id: String) : PackInstallState
    data class Failed(val message: String) : PackInstallState
}

/** Runs one pack install at a time in [scope] and reports progress, surviving screen changes. */
class PackInstaller(
    private val packs: PackManager,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow<PackInstallState>(PackInstallState.Idle)
    val state: StateFlow<PackInstallState> = _state.asStateFlow()

    val isBusy: Boolean get() = _state.value is PackInstallState.Working

    fun import(open: () -> InputStream) = run { progress ->
        // A file from elsewhere hasn't been checked yet, so check every file's hash.
        open().use { packs.install(it, verifyHashes = true, progress = progress) }.id
    }

    fun download(url: String, sha256: String?) = run { progress -> packs.download(url, sha256, progress).id }

    fun acknowledge() {
        if (!isBusy) _state.value = PackInstallState.Idle
    }

    private fun run(work: (PackManager.Progress) -> String): Boolean {
        if (isBusy) return false
        _state.value = PackInstallState.Working("Starting", null)
        scope.launch {
            _state.value = try {
                PackInstallState.Installed(withContext(io) {
                    work { stage, done, total ->
                        _state.value = PackInstallState.Working(stage, if (total > 0) done.toFloat() / total else null)
                    }
                })
            } catch (e: Exception) {
                PackInstallState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
        return true
    }
}
