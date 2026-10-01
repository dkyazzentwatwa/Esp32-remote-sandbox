package org.espsketchide.app.sketches

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.espsketchide.app.R
import org.espsketchide.app.data.InMemoryStorage
import org.espsketchide.app.data.SketchRepository
import org.espsketchide.app.data.SketchStorage
import org.espsketchide.app.data.StorageNode
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SketchListViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val storage = InMemoryStorage()
    private val blinkFiles = mapOf("Blink.ino" to "void setup() {}\nvoid loop() {}\n")
    private val vm = SketchListViewModel(SketchRepository(storage), dispatcher)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun createThenListsSketch() = runTest(dispatcher) {
        vm.create("Blink")
        advanceUntilIdle()

        assertThat(vm.state.value.sketches.map { it.name }).containsExactly("Blink")
        assertThat(vm.state.value.loading).isFalse()
    }

    @Test
    fun invalidNameReportsError() = runTest(dispatcher) {
        vm.create("9lives")
        advanceUntilIdle()

        assertThat(vm.errors.first()).isEqualTo(SketchListError(R.string.error_invalid_sketch_name))
    }

    @Test
    fun renameFailureReportsOriginalName() = runTest(dispatcher) {
        vm.create("Blink")
        advanceUntilIdle()
        storage.failRenamesTo += "Blinky"

        vm.rename(vm.state.value.sketches.single(), "Blinky")
        advanceUntilIdle()

        assertThat(vm.errors.first()).isEqualTo(SketchListError(R.string.error_rename_failed, "Blink"))
        assertThat(vm.state.value.sketches.map { it.name }).containsExactly("Blink")
    }

    @Test
    fun noRootIsReported() = runTest(dispatcher) {
        val noRoot = SketchListViewModel(SketchRepository(InMemoryStorage(hasRoot = false)), dispatcher)
        noRoot.refresh()
        advanceUntilIdle()

        assertThat(noRoot.state.value.hasRoot).isFalse()
    }

    @Test
    fun storageCrashBecomesError() = runTest(dispatcher) {
        val broken = object : SketchStorage by storage {
            override fun children(dir: StorageNode): List<StorageNode> = throw SecurityException("revoked")
        }
        val brokenVm = SketchListViewModel(SketchRepository(broken), dispatcher)
        brokenVm.refresh()
        advanceUntilIdle()

        assertThat(brokenVm.errors.first()).isEqualTo(SketchListError(R.string.error_storage_failed))
        assertThat(brokenVm.state.value.sketches).isEmpty()
    }

    @Test
    fun exampleIsCopiedAndOpened() = runTest(dispatcher) {
        vm.createFrom("Blink", blinkFiles)
        advanceUntilIdle()

        assertThat(vm.open.first().name).isEqualTo("Blink")
        assertThat(storage.contentOf("Blink/Blink.ino")).isEqualTo("void setup() {}\nvoid loop() {}\n")
        assertThat(vm.state.value.sketches.map { it.name }).containsExactly("Blink")
    }

    @Test
    fun exampleCopyFailureIsReported() = runTest(dispatcher) {
        val noRoot = SketchListViewModel(SketchRepository(InMemoryStorage(hasRoot = false)), dispatcher)
        noRoot.createFrom("Blink", blinkFiles)
        advanceUntilIdle()

        assertThat(noRoot.errors.first()).isEqualTo(SketchListError(R.string.error_example_copy_failed, "Blink"))
    }
}
