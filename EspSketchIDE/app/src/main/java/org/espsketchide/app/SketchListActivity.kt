package org.espsketchide.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.PopupMenu
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.espsketchide.app.compile.PackInstallState
import org.espsketchide.app.databinding.ActivitySketchListBinding
import org.espsketchide.app.examples.AssetExampleSource
import org.espsketchide.app.model.Sketch
import org.espsketchide.app.sketches.SketchListUiState
import org.espsketchide.app.sketches.SketchListViewModel
import org.espsketchide.app.ui.Insets
import org.espsketchide.app.ui.SketchAdapter

class SketchListActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySketchListBinding
    private lateinit var adapter: SketchAdapter

    private val app get() = application as EspSketchApp

    private val viewModel: SketchListViewModel by viewModels {
        viewModelFactory {
            initializer {
                SketchListViewModel((application as EspSketchApp).repository, AssetExampleSource(assets))
            }
        }
    }

    private val pickRootFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                app.storage.setRoot(uri)
            } catch (e: SecurityException) {
                Snackbar.make(binding.root, R.string.error_storage_failed, Snackbar.LENGTH_LONG).show()
            }
            viewModel.refresh()
        }
    }

    private val importPack = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null && hasSpaceForPack()) {
            app.packInstaller.import { contentResolver.openInputStream(uri) ?: throw java.io.IOException("Can't read the file") }
        }
    }

    private var packDialog: AlertDialog? = null
    private var packProgress: LinearProgressIndicator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivitySketchListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Insets.applyToAppBar(binding.appBar)
        Insets.applyBottomPadding(binding.sketchRecyclerView)
        Insets.applyBottomMargin(binding.newSketchFab)
        setSupportActionBar(binding.toolbar)

        adapter = SketchAdapter(
            onClick = { sketch -> openSketch(sketch) },
            onOverflowClick = { sketch, anchor -> showSketchOverflowMenu(sketch, anchor) }
        )
        binding.sketchRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.sketchRecyclerView.adapter = adapter

        binding.newSketchFab.setOnClickListener { promptNewSketch() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch { viewModel.open.collect(::openSketch) }
                if (app.canCompile) launch { app.packInstaller.state.collect(::renderPackInstall) }
                launch {
                    viewModel.errors.collect { error ->
                        Snackbar.make(binding.root, getString(error.message, error.arg), Snackbar.LENGTH_LONG).show()
                    }
                }
            }
        }

        if (savedInstanceState == null && app.storage.rootUri() == null) {
            pickRootFolder.launch(null)
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.sketch_list_toolbar, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_board_pack)?.isVisible = app.canCompile
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_board_pack -> {
                showBoardPack()
                true
            }
            R.id.action_change_folder -> {
                pickRootFolder.launch(null)
                true
            }
            R.id.action_new_from_example -> {
                pickExample()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showBoardPack() {
        lifecycleScope.launch {
            val pack = withContext(Dispatchers.IO) { app.esp32Pack() }
            val message = if (pack != null) getString(R.string.pack_installed, pack.core, pack.boards.size) else getString(R.string.pack_missing)
            val dialog = AlertDialog.Builder(this@SketchListActivity)
                .setTitle(R.string.pack_title)
                .setMessage(message)
                .setPositiveButton(R.string.pack_import) { _, _ -> importPack.launch(arrayOf("application/x-xz", "application/octet-stream", "*/*")) }
            // Download only when this build knows where the pack is published.
            if (BuildConfig.PACK_URL.isNotEmpty() && pack == null) {
                dialog.setNeutralButton(R.string.pack_download) { _, _ ->
                    if (hasSpaceForPack()) app.packInstaller.download(BuildConfig.PACK_URL, BuildConfig.PACK_SHA256.ifEmpty { null })
                }
            }
            if (pack != null) {
                dialog.setNegativeButton(R.string.pack_remove) { _, _ ->
                    app.appScope.launch {
                        app.packs.remove(pack.id)
                        withContext(Dispatchers.Main) { Snackbar.make(binding.root, R.string.pack_removed, Snackbar.LENGTH_SHORT).show() }
                    }
                }
            }
            dialog.show()
        }
    }

    private fun hasSpaceForPack(): Boolean {
        if (filesDir.usableSpace >= PACK_SPACE_BYTES) return true
        Snackbar.make(binding.root, R.string.pack_no_space, Snackbar.LENGTH_LONG).show()
        return false
    }

    private fun renderPackInstall(state: PackInstallState) {
        when (state) {
            is PackInstallState.Working -> {
                val progress = packProgress ?: LinearProgressIndicator(this).also { packProgress = it }
                val dialog = packDialog ?: AlertDialog.Builder(this)
                    .setTitle(R.string.pack_title)
                    .setMessage(" ")
                    .setView(progress.apply {
                        val pad = (24 * resources.displayMetrics.density).toInt()
                        setPadding(pad, 0, pad, 0)
                    })
                    .setCancelable(false)
                    .show()
                    .also { packDialog = it }
                dialog.setMessage(getString(R.string.pack_progress, state.stage))
                if (state.fraction == null) progress.isIndeterminate = true
                else {
                    progress.isIndeterminate = false
                    progress.setProgressCompat((state.fraction * 100).toInt(), false)
                }
            }
            else -> {
                packDialog?.dismiss()
                packDialog = null
                packProgress = null
                when (state) {
                    is PackInstallState.Installed ->
                        Snackbar.make(binding.root, getString(R.string.pack_done, state.id), Snackbar.LENGTH_LONG).show()
                    is PackInstallState.Failed ->
                        Snackbar.make(binding.root, getString(R.string.pack_failed, state.message), Snackbar.LENGTH_INDEFINITE)
                            .setAction(R.string.dialog_ok) {}
                            .show()
                    else -> Unit
                }
                if (state !is PackInstallState.Idle) app.packInstaller.acknowledge()
            }
        }
    }

    override fun onDestroy() {
        packDialog?.dismiss()
        packDialog = null
        packProgress = null
        super.onDestroy()
    }

    private fun pickExample() {
        if (!viewModel.state.value.hasRoot) {
            pickRootFolder.launch(null)
            return
        }
        lifecycleScope.launch {
            val examples = viewModel.listExamples()
            val labels = examples.map { "${it.category} › ${it.name}" }.toTypedArray()
            AlertDialog.Builder(this@SketchListActivity)
                .setTitle(R.string.dialog_examples_title)
                .setItems(labels) { _, which -> viewModel.createFromExample(examples[which]) }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        }
    }

    private fun render(state: SketchListUiState) {
        adapter.submitList(state.sketches)
        val emptyText = when {
            state.loading -> null
            !state.hasRoot -> getString(R.string.error_no_storage_access)
            state.sketches.isEmpty() -> getString(R.string.sketch_list_empty)
            else -> null
        }
        binding.emptyStateText.visibility = if (emptyText == null) View.GONE else View.VISIBLE
        binding.emptyStateText.text = emptyText
    }

    private fun openSketch(sketch: Sketch) {
        val intent = Intent(this, EditorActivity::class.java).apply {
            putExtra(EditorActivity.EXTRA_SKETCH_NAME, sketch.name)
            putExtra(EditorActivity.EXTRA_SKETCH_FOLDER_ID, sketch.folderId)
        }
        startActivity(intent)
    }

    private fun showSketchOverflowMenu(sketch: Sketch, anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.sketch_item_menu, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_rename -> {
                    promptRenameSketch(sketch)
                    true
                }
                R.id.action_delete -> {
                    confirmDeleteSketch(sketch)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun promptNewSketch() {
        if (!viewModel.state.value.hasRoot) {
            pickRootFolder.launch(null)
            return
        }
        val input = EditText(this)
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_new_sketch_title)
            .setView(input)
            .setPositiveButton(R.string.dialog_ok) { _, _ -> viewModel.create(input.text.toString().trim()) }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun promptRenameSketch(sketch: Sketch) {
        val input = EditText(this).apply { setText(sketch.name) }
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_rename_title)
            .setView(input)
            .setPositiveButton(R.string.dialog_ok) { _, _ -> viewModel.rename(sketch, input.text.toString().trim()) }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun confirmDeleteSketch(sketch: Sketch) {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_delete_title)
            .setMessage(getString(R.string.dialog_delete_message, sketch.name))
            .setPositiveButton(R.string.dialog_delete_confirm) { _, _ -> viewModel.delete(sketch) }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private companion object {
        /** An installed pack takes about 260 MB; leave room for the extraction and builds. */
        const val PACK_SPACE_BYTES = 300L * 1024 * 1024
    }
}
