package org.espsketchide.app

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import com.google.android.material.tabs.TabLayout
import io.github.rosemoe.sora.langs.java.JavaLanguage
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula
import io.github.rosemoe.sora.widget.schemes.SchemeGitHub
import org.espsketchide.app.data.SketchAlreadyExistsException
import org.espsketchide.app.data.SketchNameInvalidException
import org.espsketchide.app.data.SketchRepository
import org.espsketchide.app.databinding.ActivityEditorBinding
import org.espsketchide.app.model.Sketch
import org.espsketchide.app.model.SketchFile
import org.espsketchide.app.settings.SettingsActivity

class EditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditorBinding
    private lateinit var repository: SketchRepository
    private lateinit var sketch: Sketch

    private var openFiles: List<SketchFile> = emptyList()
    private var currentFile: SketchFile? = null

    private val fileTabListener = object : TabLayout.OnTabSelectedListener {
        override fun onTabSelected(tab: TabLayout.Tab) {
            saveCurrentFile()
            loadFile(openFiles[tab.position])
        }

        override fun onTabUnselected(tab: TabLayout.Tab) = Unit
        override fun onTabReselected(tab: TabLayout.Tab) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val sketchName = intent.getStringExtra(EXTRA_SKETCH_NAME)
        val folderUri = IntentCompat.getParcelableExtra(intent, EXTRA_SKETCH_FOLDER_URI, Uri::class.java)
        if (sketchName == null || folderUri == null) {
            finish()
            return
        }
        sketch = Sketch(sketchName, folderUri)
        repository = SketchRepository(this)

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
        // Detach while rebuilding so the auto-selection of the first added tab doesn't
        // trigger a save/load, and so the listener is never registered more than once.
        binding.fileTabLayout.removeOnTabSelectedListener(fileTabListener)
        binding.fileTabLayout.removeAllTabs()
        openFiles.forEach { file ->
            binding.fileTabLayout.addTab(binding.fileTabLayout.newTab().setText(file.name))
        }
        binding.fileTabLayout.addOnTabSelectedListener(fileTabListener)
        if (openFiles.isNotEmpty()) {
            loadFile(openFiles.first())
        }
    }

    private fun loadFile(file: SketchFile) {
        currentFile = file
        val content = repository.readFile(file.uri)
        binding.codeEditor.setText(content)
    }

    private fun saveCurrentFile() {
        val file = currentFile ?: return
        repository.writeFile(file.uri, binding.codeEditor.text.toString())
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
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
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
            Toast.makeText(this, R.string.error_invalid_file_name, Toast.LENGTH_LONG).show()
        } catch (e: SketchAlreadyExistsException) {
            Toast.makeText(this, getString(R.string.error_file_exists, fileName), Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val EXTRA_SKETCH_NAME = "extra_sketch_name"
        const val EXTRA_SKETCH_FOLDER_URI = "extra_sketch_folder_uri"
    }
}
