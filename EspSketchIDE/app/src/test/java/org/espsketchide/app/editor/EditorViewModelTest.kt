package org.espsketchide.app.editor

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.espsketchide.app.R
import org.espsketchide.app.data.InMemoryStorage
import org.espsketchide.app.data.SketchRepository
import org.espsketchide.app.data.StorageNode
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val storage = InMemoryStorage()
    private val repository = SketchRepository(storage)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(): EditorViewModel {
        storage.put("Blink/Blink.ino", "void setup() {}\n")
        storage.put("Blink/pins.h", "#define LED 2\n")
        val sketch = repository.listSketches().single()
        return EditorViewModel(repository, sketch, dispatcher, persistScope = this)
    }

    @Test
    fun opensPrimaryInoFirst() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        val state = vm.state.value
        assertThat(state.files.map { it.name }).containsExactly("Blink.ino", "pins.h").inOrder()
        assertThat(state.document?.file?.name).isEqualTo("Blink.ino")
        assertThat(state.document?.text).isEqualTo("void setup() {}\n")
    }

    @Test
    fun switchingFilesSavesEditsAndKeepsThemInMemory() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.updateText("void setup() { edited(); }\n")
        vm.selectFile(vm.state.value.files[1])
        advanceUntilIdle()

        assertThat(storage.contentOf("Blink/Blink.ino")).isEqualTo("void setup() { edited(); }\n")
        assertThat(vm.state.value.document?.text).isEqualTo("#define LED 2\n")

        vm.selectFile(vm.state.value.files[0])
        advanceUntilIdle()
        assertThat(vm.state.value.document?.text).isEqualTo("void setup() { edited(); }\n")
    }

    @Test
    fun saveSkipsUnchangedFiles() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        val writesBefore = storage.writeCount

        vm.updateText("void setup() {}\n")
        vm.save()
        vm.saveInBackground()
        advanceUntilIdle()

        assertThat(storage.writeCount).isEqualTo(writesBefore)
    }

    @Test
    fun backgroundSaveWritesDirtyFile() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.updateText("changed\n")
        vm.saveInBackground()
        advanceUntilIdle()

        assertThat(storage.contentOf("Blink/Blink.ino")).isEqualTo("changed\n")
    }

    @Test
    fun readFailureBecomesErrorEventNotCrash() = runTest(dispatcher) {
        val failing = object : org.espsketchide.app.data.SketchStorage by storage {
            override fun read(node: StorageNode): String = throw IOException("provider died")
        }
        storage.put("Blink/Blink.ino", "")
        val sketch = SketchRepository(failing).listSketches().single()
        val vm = EditorViewModel(SketchRepository(failing), sketch, dispatcher, persistScope = this)
        advanceUntilIdle()

        val event = vm.events.first()
        assertThat(event).isEqualTo(EditorEvent.Error(R.string.error_read_failed, "Blink.ino"))
        assertThat(vm.state.value.document).isNull()
    }

    @Test
    fun writeFailureIsReportedOnExplicitSave() = runTest(dispatcher) {
        val failing = object : org.espsketchide.app.data.SketchStorage by storage {
            override fun write(node: StorageNode, content: String) = throw IOException("read-only")
        }
        storage.put("Blink/Blink.ino", "")
        val sketch = SketchRepository(failing).listSketches().single()
        val vm = EditorViewModel(SketchRepository(failing), sketch, dispatcher, persistScope = this)
        advanceUntilIdle()

        vm.updateText("x")
        vm.save()
        advanceUntilIdle()

        assertThat(vm.events.first()).isEqualTo(EditorEvent.Error(R.string.error_save_failed, "Blink.ino"))
    }

    @Test
    fun addFileCreatesAndOpensIt() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.addFile("util.cpp")
        advanceUntilIdle()

        assertThat(storage.exists("Blink/util.cpp")).isTrue()
        assertThat(vm.state.value.files.map { it.name }).contains("util.cpp")
        assertThat(vm.state.value.document?.file?.name).isEqualTo("util.cpp")
    }

    @Test
    fun addFileWithBadNameReportsError() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.addFile("bad name.sh")
        advanceUntilIdle()

        assertThat(vm.events.first()).isEqualTo(EditorEvent.Error(R.string.error_invalid_file_name))
    }

    // A recreated activity (rotation) applies state.document; it must include unsaved edits.
    @Test
    fun documentTextTracksEditsWithoutNewVersion() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        val version = vm.state.value.document!!.version

        vm.updateText("unsaved edit\n")

        assertThat(vm.state.value.document!!.text).isEqualTo("unsaved edit\n")
        assertThat(vm.state.value.document!!.version).isEqualTo(version)
    }

    @Test
    fun documentVersionIncreasesOnEachOpen() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        val first = vm.state.value.document!!.version

        vm.selectFile(vm.state.value.files[1])
        advanceUntilIdle()

        assertThat(vm.state.value.document!!.version).isGreaterThan(first)
    }
}
