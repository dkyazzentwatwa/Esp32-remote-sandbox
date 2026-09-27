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
import kotlinx.coroutines.launch
import org.espsketchide.app.databinding.ActivitySketchListBinding
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
        viewModelFactory { initializer { SketchListViewModel((application as EspSketchApp).repository) } }
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

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_change_folder) {
            pickRootFolder.launch(null)
            return true
        }
        return super.onOptionsItemSelected(item)
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
}
