package org.espsketchide.app

import android.content.res.Configuration
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.espsketchide.app.databinding.ActivityEditorBinding
import org.espsketchide.app.editor.EditorEvent
import org.espsketchide.app.editor.EditorUiState
import org.espsketchide.app.editor.EditorViewModel
import org.espsketchide.app.editor.Highlighting
import org.espsketchide.app.editor.SourceKind
import org.espsketchide.app.editor.sourceKindFor
import org.espsketchide.app.model.Sketch
import org.espsketchide.app.ui.Insets
import org.espsketchide.app.model.SketchFile

class EditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditorBinding
    private lateinit var sketch: Sketch

    private val viewModel: EditorViewModel by viewModels {
        viewModelFactory {
            initializer {
                val app = application as EspSketchApp
                EditorViewModel(app.repository, sketch, persistScope = app.appScope)
            }
        }
    }

    private var renderedFiles: List<SketchFile> = emptyList()
    private var appliedVersion = 0

    private var highlightingReady = false
    private var appliedKind: SourceKind? = null
    private var currentKind = SourceKind.CPP

    /** True while tabs are changed programmatically, so selection callbacks are ignored. */
    private var renderingTabs = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Insets.applyToAppBar(binding.appBar)
        Insets.applyBottomIncludingKeyboard(binding.root)

        val sketchName = intent.getStringExtra(EXTRA_SKETCH_NAME)
        val folderId = intent.getStringExtra(EXTRA_SKETCH_FOLDER_ID)
        if (sketchName == null || folderId == null) {
            finish()
            return
        }
        sketch = Sketch(sketchName, folderId)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = sketch.name

        setupEditor()
        attachTabListener()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch { viewModel.events.collect(::handleEvent) }
            }
        }

        if (savedInstanceState == null) {
            Snackbar.make(binding.root, R.string.roadmap_notice, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun setupEditor() {
        binding.codeEditor.setTextSize(14f)
        val isDarkMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.Default) { Highlighting.load(applicationContext) }
                binding.codeEditor.colorScheme = Highlighting.colorScheme(isDarkMode)
                highlightingReady = true
                applyLanguage()
            } catch (e: Exception) {
                // Highlighting is a nicety; editing must keep working without it.
                Snackbar.make(binding.root, R.string.error_highlighting_failed, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun applyLanguage() {
        if (!highlightingReady || appliedKind == currentKind) return
        binding.codeEditor.setEditorLanguage(Highlighting.language(currentKind))
        appliedKind = currentKind
    }

    /** Attached once; tab rebuilds are guarded by [renderingTabs]. */
    private fun attachTabListener() {
        binding.fileTabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (renderingTabs) return
                val file = renderedFiles.getOrNull(tab.position) ?: return
                pushEditorText()
                viewModel.selectFile(file)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
    }

    private fun render(state: EditorUiState) {
        if (state.files != renderedFiles) {
            renderingTabs = true
            binding.fileTabLayout.removeAllTabs()
            state.files.forEach { file ->
                binding.fileTabLayout.addTab(binding.fileTabLayout.newTab().setText(file.name), false)
            }
            renderedFiles = state.files
            renderingTabs = false
        }

        val document = state.document ?: return
        val index = renderedFiles.indexOfFirst { it.id == document.file.id }
        if (index >= 0 && binding.fileTabLayout.selectedTabPosition != index) {
            renderingTabs = true
            binding.fileTabLayout.getTabAt(index)?.select()
            renderingTabs = false
        }
        if (document.version != appliedVersion) {
            currentKind = sourceKindFor(document.file.name)
            applyLanguage()
            binding.codeEditor.setText(document.text)
            appliedVersion = document.version
        }
    }

    private fun handleEvent(event: EditorEvent) {
        val message = when (event) {
            is EditorEvent.Saved -> getString(R.string.saved_toast)
            is EditorEvent.Error -> getString(event.message, event.arg)
        }
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    /** Hands the editor's text to the view model; skipped until a document has been shown. */
    private fun pushEditorText() {
        if (appliedVersion != 0) viewModel.updateText(binding.codeEditor.text.toString())
    }

    override fun onPause() {
        super.onPause()
        if (::sketch.isInitialized) {
            pushEditorText()
            viewModel.saveInBackground()
        }
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
                pushEditorText()
                viewModel.save()
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
        val input = EditText(this).apply { hint = getString(R.string.hint_file_name) }
        AlertDialog.Builder(this)
            .setTitle(R.string.action_add_file)
            .setView(input)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                pushEditorText()
                viewModel.addFile(input.text.toString().trim())
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    companion object {
        const val EXTRA_SKETCH_NAME = "extra_sketch_name"
        const val EXTRA_SKETCH_FOLDER_ID = "extra_sketch_folder_id"
    }
}
