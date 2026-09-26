package org.espsketchide.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import org.espsketchide.app.data.SketchAlreadyExistsException
import org.espsketchide.app.data.SketchNameInvalidException
import org.espsketchide.app.data.SketchRepository
import org.espsketchide.app.databinding.ActivitySketchListBinding
import org.espsketchide.app.model.Sketch
import org.espsketchide.app.ui.SketchAdapter

class SketchListActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySketchListBinding
    private lateinit var repository: SketchRepository
    private lateinit var adapter: SketchAdapter

    private val pickRootFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            repository.setRoot(uri)
            refreshSketchList()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySketchListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        repository = SketchRepository(this)
        adapter = SketchAdapter(
            onClick = { sketch -> openSketch(sketch) },
            onOverflowClick = { sketch, anchor -> showSketchOverflowMenu(sketch, anchor) }
        )
        binding.sketchRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.sketchRecyclerView.adapter = adapter

        binding.newSketchFab.setOnClickListener { promptNewSketch() }

        if (!repository.hasRoot()) {
            pickRootFolder.launch(null)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshSketchList()
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

    private fun refreshSketchList() {
        if (!repository.hasRoot()) {
            binding.emptyStateText.visibility = View.VISIBLE
            binding.emptyStateText.text = getString(R.string.error_no_storage_access)
            adapter.submitList(emptyList())
            return
        }
        val sketches = repository.listSketches()
        adapter.submitList(sketches)
        binding.emptyStateText.visibility = if (sketches.isEmpty()) View.VISIBLE else View.GONE
        if (sketches.isEmpty()) {
            binding.emptyStateText.text = getString(R.string.sketch_list_empty)
        }
    }

    private fun openSketch(sketch: Sketch) {
        val intent = Intent(this, EditorActivity::class.java).apply {
            putExtra(EditorActivity.EXTRA_SKETCH_NAME, sketch.name)
            putExtra(EditorActivity.EXTRA_SKETCH_FOLDER_URI, sketch.folderUri)
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
        if (!repository.hasRoot()) {
            pickRootFolder.launch(null)
            return
        }
        val input = EditText(this)
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_new_sketch_title)
            .setView(input)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                createSketch(input.text.toString().trim())
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun createSketch(name: String) {
        try {
            repository.createSketch(name)
            refreshSketchList()
        } catch (e: SketchNameInvalidException) {
            toast(getString(R.string.error_invalid_sketch_name))
        } catch (e: SketchAlreadyExistsException) {
            toast(getString(R.string.error_sketch_exists, name))
        }
    }

    private fun promptRenameSketch(sketch: Sketch) {
        val input = EditText(this).apply { setText(sketch.name) }
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_rename_title)
            .setView(input)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                renameSketch(sketch, input.text.toString().trim())
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun renameSketch(sketch: Sketch, newName: String) {
        try {
            repository.renameSketch(sketch, newName)
            refreshSketchList()
        } catch (e: SketchNameInvalidException) {
            toast(getString(R.string.error_invalid_sketch_name))
        } catch (e: SketchAlreadyExistsException) {
            toast(getString(R.string.error_sketch_exists, newName))
        }
    }

    private fun confirmDeleteSketch(sketch: Sketch) {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_delete_title)
            .setMessage(getString(R.string.dialog_delete_message, sketch.name))
            .setPositiveButton(R.string.dialog_delete_confirm) { _, _ ->
                repository.deleteSketch(sketch)
                refreshSketchList()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
