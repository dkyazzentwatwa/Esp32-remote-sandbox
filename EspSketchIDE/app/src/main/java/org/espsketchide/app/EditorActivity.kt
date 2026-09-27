package org.espsketchide.app

import android.content.res.Configuration
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.tabs.TabLayout
import io.github.rosemoe.sora.langs.java.JavaLanguage
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula
import io.github.rosemoe.sora.widget.schemes.SchemeGitHub
import org.espsketchide.app.data.SketchAlreadyExistsException
import org.espsketchide.app.data.DocumentFileStorage
import org.espsketchide.app.data.SketchNameInvalidException
import org.espsketchide.app.data.SketchRepository
import org.espsketchide.app.databinding.ActivityEditorBinding
import org.espsketchide.app.model.Sketch
import org.espsketchide.app.model.SketchFile

class EditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditorBinding
    private lateinit var repository: SketchRepository
    private lateinit var sketch: Sketch

    private var openFiles: List<SketchFile> = emptyList()
    private var currentFile: SketchFile? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val sketchName = intent.getStringExtra(EXTRA_SKETCH_NAME)
        val folderId = intent.getStringExtra(EXTRA_SKETCH_FOLDER_ID)
        if (sketchName == null || folderId == null) {
            finish()
            return
        }
        sketch = Sketch(sketchName, folderId)
        repository = SketchRepository(DocumentFileStorage(this))

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = sketch.name

        setupEditor()
        setupFileTabs()

        if (savedInstanceState == null) {
            Toast.makeText(this, R.string.roadmap_notice, Toast.LENGTH_LONG).show()
        }
    }

    private fun setupEditor() {
        binding.codeEditor.setEditorLanguage(JavaLanguage())
        val isDarkMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        binding.codeEditor.colorScheme = if (isDarkMode) SchemeDarcula() else SchemeGitHub()
        binding.codeEditor.setTextSize(14f)
    }

    private fun setupFileTabs() {
        openFiles = repository.listFiles(sketch)
        binding.fileTabLayout.removeAllTabs()
        openFiles.forEach { file ->
            binding.fileTabLayout.addTab(binding.fileTabLayout.newTab().setText(file.name))
        }
        binding.fileTabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                saveCurrentFile()
                loadFile(openFiles[tab.position])
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        if (openFiles.isNotEmpty()) {
            loadFile(openFiles.first())
        }
    }

    private fun loadFile(file: SketchFile) {
        currentFile = file
        val content = repository.readFile(file)
        binding.codeEditor.setText(content)
    }

    private fun saveCurrentFile() {
        val file = currentFile ?: return
        repository.writeFile(file, binding.codeEditor.text.toString())
    }

    override fun onPause() {
        super.onPause()
        saveCurrentFile()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.editor_toolbar, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_save -> {
                saveCurrentFile()
                Toast.makeText(this, R.string.saved_toast, Toast.LENGTH_SHORT).show()
                true
            }
            R.id.action_add_file -> {
                promptAddFile()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun promptAddFile() {
        val input = EditText(this)
        AlertDialog.Builder(this)
            .setTitle(R.string.action_add_file)
            .setView(input)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                addFile(input.text.toString().trim())
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun addFile(fileName: String) {
        try {
            saveCurrentFile()
            repository.addFile(sketch, fileName)
            setupFileTabs()
            val newIndex = openFiles.indexOfFirst { it.name == fileName }
            if (newIndex >= 0) {
                binding.fileTabLayout.getTabAt(newIndex)?.select()
            }
        } catch (e: SketchNameInvalidException) {
            Toast.makeText(this, R.string.error_invalid_sketch_name, Toast.LENGTH_SHORT).show()
        } catch (e: SketchAlreadyExistsException) {
            Toast.makeText(this, getString(R.string.error_sketch_exists, fileName), Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val EXTRA_SKETCH_NAME = "extra_sketch_name"
        const val EXTRA_SKETCH_FOLDER_ID = "extra_sketch_folder_id"
    }
}
