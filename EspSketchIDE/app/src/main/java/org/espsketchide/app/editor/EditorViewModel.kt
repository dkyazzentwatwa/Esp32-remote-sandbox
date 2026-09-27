package org.espsketchide.app.editor

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.espsketchide.app.R
import org.espsketchide.app.data.SketchAlreadyExistsException
import org.espsketchide.app.data.SketchNameInvalidException
import org.espsketchide.app.data.SketchRepository
import org.espsketchide.app.model.Sketch
import org.espsketchide.app.model.SketchFile

/** Text to show in the editor. [version] increases whenever the activity must replace its text. */
data class OpenDocument(val file: SketchFile, val text: String, val version: Int)

data class EditorUiState(
    val files: List<SketchFile> = emptyList(),
    val document: OpenDocument? = null
)

sealed interface EditorEvent {
    data class Error(@StringRes val message: Int, val arg: String? = null) : EditorEvent
    data object Saved : EditorEvent
}

/**
 * Owns the open sketch's files and their unsaved text. All storage access runs on [io];
 * saves that must outlive the screen (onPause) run on [persistScope].
 */
class EditorViewModel(
    private val repository: SketchRepository,
    private val sketch: Sketch,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val persistScope: CoroutineScope
) : ViewModel() {

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private val _events = Channel<EditorEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** Latest known text per file id, including unsaved edits. */
    private val buffers = mutableMapOf<String, String>()

    /** Text last read from or written to storage, per file id. */
    private val persisted = mutableMapOf<String, String>()

    private val saveMutex = Mutex()
    private var version = 0

    init {
        viewModelScope.launch { reloadFiles(select = null) }
    }

    /** Records the editor's current text for the open file. Call before any save or switch. */
    fun updateText(text: String) {
        val document = _state.value.document ?: return
        buffers[document.file.id] = text
        // Same version: the current screen already shows this text, but a recreated one needs it.
        _state.update { it.copy(document = document.copy(text = text)) }
    }

    fun selectFile(file: SketchFile) {
        if (_state.value.document?.file?.id == file.id) return
        viewModelScope.launch {
            saveDirty(reportErrors = true)
            open(file)
        }
    }

    fun save() {
        viewModelScope.launch {
            if (saveDirty(reportErrors = true)) _events.send(EditorEvent.Saved)
        }
    }

    /** Saves before a build so the compiler sees what's on screen. False if a file couldn't be saved. */
    suspend fun saveBeforeBuild(): Boolean = saveDirty(reportErrors = true)

    /** Saves unsaved edits even if the screen is going away. */
    fun saveInBackground() {
        persistScope.launch { saveDirty(reportErrors = false) }
    }

    fun addFile(fileName: String) {
        viewModelScope.launch {
            saveDirty(reportErrors = true)
            try {
                val created = withContext(io) { repository.addFile(sketch, fileName) }
                reloadFiles(select = created.name)
            } catch (e: SketchNameInvalidException) {
                _events.send(EditorEvent.Error(R.string.error_invalid_file_name))
            } catch (e: SketchAlreadyExistsException) {
                _events.send(EditorEvent.Error(R.string.error_file_exists, fileName))
            } catch (e: Exception) {
                _events.send(EditorEvent.Error(R.string.error_create_file_failed, fileName))
            }
        }
    }

    private suspend fun reloadFiles(select: String?) {
        val files = try {
            withContext(io) { repository.listFiles(sketch) }
        } catch (e: Exception) {
            _events.send(EditorEvent.Error(R.string.error_list_files_failed))
            return
        }
        _state.update { it.copy(files = files) }
        val target = files.firstOrNull { it.name == select }
            ?: _state.value.document?.file?.let { open -> files.firstOrNull { it.id == open.id } }
            ?: files.firstOrNull()
        if (target != null && target.id != _state.value.document?.file?.id) open(target)
    }

    private suspend fun open(file: SketchFile) {
        val text = buffers[file.id] ?: try {
            withContext(io) { repository.readFile(file) }.also {
                persisted[file.id] = it
                buffers[file.id] = it
            }
        } catch (e: Exception) {
            _events.send(EditorEvent.Error(R.string.error_read_failed, file.name))
            return
        }
        _state.update { it.copy(document = OpenDocument(file, text, ++version)) }
    }

    /** Writes every buffer that differs from storage. Returns false if any write failed. */
    private suspend fun saveDirty(reportErrors: Boolean): Boolean = saveMutex.withLock {
        val files = _state.value.files.associateBy { it.id }
        var ok = true
        for ((id, text) in buffers.toMap()) {
            if (persisted[id] == text) continue
            val file = files[id] ?: continue
            try {
                withContext(io) { repository.writeFile(file, text) }
                persisted[id] = text
            } catch (e: Exception) {
                ok = false
                if (reportErrors) _events.send(EditorEvent.Error(R.string.error_save_failed, file.name))
            }
        }
        ok
    }
}
