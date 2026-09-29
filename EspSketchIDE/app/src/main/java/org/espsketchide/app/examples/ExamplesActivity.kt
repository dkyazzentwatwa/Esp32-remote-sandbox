package org.espsketchide.app.examples

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import org.espsketchide.app.EditorActivity
import org.espsketchide.app.R
import org.espsketchide.app.data.SketchAlreadyExistsException
import org.espsketchide.app.data.SketchNameInvalidException
import org.espsketchide.app.data.SketchNames
import org.espsketchide.app.data.SketchRepository
import org.espsketchide.app.databinding.ActivityExamplesBinding
import org.espsketchide.app.settings.AppSettings
import org.espsketchide.app.settings.Board
import java.io.IOException

/** Lists the built-in examples for the selected board and opens one as a new sketch. */
class ExamplesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityExamplesBinding
    private lateinit var repository: SketchRepository
    private lateinit var board: Board

    /** The example the user tapped while there was no sketchbook folder yet. */
    private var pendingExample: Example? = null

    private val pickRootFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        val example = pendingExample
        pendingExample = null
        if (uri != null) {
            repository.setRoot(uri)
            if (example != null) promptOpenAsSketch(example)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExamplesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        repository = SketchRepository(this)
        board = AppSettings(this).board
        supportActionBar?.subtitle = getString(R.string.examples_subtitle, getString(boardLabel(board)))

        val catalog = ExampleCatalog.parse(ExampleFiles.readIndex(assets))
        val rows = ExampleCatalog.rows(ExampleCatalog.forBoard(catalog, board))
        binding.exampleRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.exampleRecyclerView.adapter = ExampleAdapter(rows) { example -> onExampleClicked(example) }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun boardLabel(board: Board): Int = when (board) {
        Board.ESP32 -> R.string.board_esp32
        Board.ESP8266 -> R.string.board_esp8266
    }

    private fun onExampleClicked(example: Example) {
        if (!repository.hasRoot()) {
            pendingExample = example
            pickRootFolder.launch(null)
            return
        }
        promptOpenAsSketch(example)
    }

    private fun promptOpenAsSketch(example: Example) {
        val input = EditText(this).apply {
            setText(repository.uniqueName(SketchNames.toSketchName(example.name)))
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_open_example_title)
            .setView(input)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                openAsSketch(example, input.text.toString().trim())
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun openAsSketch(example: Example, name: String) {
        val path = ExampleCatalog.variantPath(example, board) ?: return
        try {
            val files = SketchNames.renamePrimary(ExampleFiles.read(assets, path), name)
            val sketch = repository.createSketch(name, files)
            startActivity(
                Intent(this, EditorActivity::class.java)
                    .putExtra(EditorActivity.EXTRA_SKETCH_NAME, sketch.name)
                    .putExtra(EditorActivity.EXTRA_SKETCH_FOLDER_URI, sketch.folderUri)
            )
            finish()
        } catch (e: SketchNameInvalidException) {
            toast(getString(R.string.error_invalid_sketch_name))
        } catch (e: SketchAlreadyExistsException) {
            toast(getString(R.string.error_sketch_exists, name))
        } catch (e: IOException) {
            toast(getString(R.string.error_create_failed))
        } catch (e: IllegalStateException) {
            toast(getString(R.string.error_create_failed))
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
