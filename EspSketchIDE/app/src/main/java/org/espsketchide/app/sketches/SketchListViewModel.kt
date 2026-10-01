package org.espsketchide.app.sketches

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.espsketchide.app.R
import org.espsketchide.app.data.SketchAlreadyExistsException
import org.espsketchide.app.data.SketchDeleteFailedException
import org.espsketchide.app.data.SketchNameInvalidException
import org.espsketchide.app.data.SketchRenameFailedException
import org.espsketchide.app.data.SketchRepository
import org.espsketchide.app.model.Sketch

data class SketchListUiState(
    val hasRoot: Boolean = true,
    val sketches: List<Sketch> = emptyList(),
    val loading: Boolean = true
)

data class SketchListError(@StringRes val message: Int, val arg: String? = null)

class SketchListViewModel(
    private val repository: SketchRepository,
    private val io: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _state = MutableStateFlow(SketchListUiState())
    val state: StateFlow<SketchListUiState> = _state.asStateFlow()

    private val _errors = Channel<SketchListError>(Channel.BUFFERED)
    val errors = _errors.receiveAsFlow()

    /** Sketches to open right away, e.g. a freshly copied example. */
    private val _open = Channel<Sketch>(Channel.BUFFERED)
    val open = _open.receiveAsFlow()

    /**
     * Copies [files] (name to content, e.g. a bundled example) into a new sketch named [baseName],
     * or `baseName_2`… if taken, and opens it.
     */
    fun createFrom(baseName: String, files: Map<String, String>) {
        viewModelScope.launch {
            try {
                val sketch = withContext(io) { repository.createFromFiles(baseName, files) }
                _open.send(sketch)
            } catch (e: Exception) {
                _errors.send(SketchListError(R.string.error_example_copy_failed, baseName))
            }
            reload()
        }
    }

    fun refresh() {
        viewModelScope.launch { reload() }
    }

    /** Creates a sketch with [content] (e.g. a template), or the repository's default sketch if null. */
    fun create(name: String, content: String? = null) = mutate(name) {
        if (content == null) repository.createSketch(name) else repository.createSketch(name, content)
    }

    fun rename(sketch: Sketch, newName: String) = mutate(newName, original = sketch.name) {
        repository.renameSketch(sketch, newName)
    }

    fun delete(sketch: Sketch) = mutate(sketch.name) { repository.deleteSketch(sketch) }

    private fun mutate(name: String, original: String = name, action: () -> Unit) {
        viewModelScope.launch {
            val error = try {
                withContext(io) { action() }
                null
            } catch (e: SketchNameInvalidException) {
                SketchListError(R.string.error_invalid_sketch_name)
            } catch (e: SketchAlreadyExistsException) {
                SketchListError(R.string.error_sketch_exists, name)
            } catch (e: SketchRenameFailedException) {
                SketchListError(R.string.error_rename_failed, original)
            } catch (e: SketchDeleteFailedException) {
                SketchListError(R.string.error_delete_failed, name)
            } catch (e: Exception) {
                SketchListError(R.string.error_storage_failed)
            }
            error?.let { _errors.send(it) }
            reload()
        }
    }

    private suspend fun reload() {
        val result = try {
            withContext(io) {
                if (!repository.hasRoot()) null else repository.listSketches()
            }
        } catch (e: Exception) {
            _errors.send(SketchListError(R.string.error_storage_failed))
            emptyList()
        }
        _state.value = SketchListUiState(
            hasRoot = result != null,
            sketches = result.orEmpty(),
            loading = false
        )
    }
}
