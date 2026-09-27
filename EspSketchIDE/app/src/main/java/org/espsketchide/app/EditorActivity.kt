package org.espsketchide.app

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
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
import io.github.rosemoe.sora.event.ContentChangeEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.espsketchide.app.compile.BuildState
import org.espsketchide.app.compile.Diagnostic
import org.espsketchide.app.databinding.ActivityEditorBinding
import org.espsketchide.app.editor.EditorEvent
import org.espsketchide.app.editor.EditorPrefs
import org.espsketchide.app.editor.FontSize
import org.espsketchide.app.editor.EditorUiState
import org.espsketchide.app.editor.EditorViewModel
import org.espsketchide.app.editor.Highlighting
import org.espsketchide.app.editor.SourceKind
import org.espsketchide.app.editor.sourceKindFor
import org.espsketchide.app.model.Sketch
import org.espsketchide.app.ui.Insets
import org.espsketchide.app.model.SketchFile
import org.espsketchide.app.upload.UsbConnect

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

    private val app get() = application as EspSketchApp

    /** A diagnostic's position to show once its file is open in the editor. */
    private var pendingJump: Diagnostic? = null

    /** The build state last drawn, so a new state that's equal isn't redrawn. */
    private var renderedBuild: BuildState? = null

    private var renderedFiles: List<SketchFile> = emptyList()
    private var appliedVersion = 0

    private lateinit var prefs: EditorPrefs

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
                if (app.canCompile) launch { app.builds.state.collect(::renderBuild) }
            }
        }
        setupOutputPanel()

        if (savedInstanceState == null && !app.canCompile) {
            Snackbar.make(binding.root, R.string.roadmap_notice, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun setupEditor() {
        prefs = EditorPrefs(this)
        val editor = binding.codeEditor
        editor.setTextSize(prefs.fontSizeSp)
        editor.setScalable(true)
        editor.setScaleTextSizes(spToPx(FontSize.MIN), spToPx(FontSize.MAX))
        editor.subscribeEvent(ContentChangeEvent::class.java) { _, _ -> invalidateOptionsMenu() }

        binding.symbolBar.bindEditor(editor)
        binding.symbolBar.addSymbols(SYMBOLS.map { if (it == "\t") "⇥" else it }.toTypedArray(), SYMBOLS)

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
        pendingJump?.takeIf { it.file == document.file.name }?.let { jump ->
            pendingJump = null
            binding.codeEditor.post { moveCursor(jump) }
        }
    }

    // --- Verify / Upload -------------------------------------------------------------------

    private fun setupOutputPanel() {
        binding.outputClose.setOnClickListener { app.builds.dismiss() }
        binding.outputShowLog.setOnClickListener { showFullOutput() }
    }

    private fun renderBuild(state: BuildState) {
        if (state == renderedBuild) return
        // Only announce an upload this screen watched finish, not again after rotation.
        val justFinished = renderedBuild is BuildState.Running
        renderedBuild = state
        val panel = binding
        panel.outputPanel.visibility = if (state is BuildState.Idle || !state.isFor(sketch)) View.GONE else View.VISIBLE
        val running = state is BuildState.Running
        if (running) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        panel.outputProgress.visibility = if (running) View.VISIBLE else View.GONE
        panel.outputClose.visibility = if (running) View.GONE else View.VISIBLE
        panel.outputShowLog.visibility = if (state is BuildState.Failed && state.output.isNotEmpty()) View.VISIBLE else View.GONE
        panel.diagnosticsList.removeAllViews()
        when (state) {
            is BuildState.Idle -> Unit
            is BuildState.Running -> {
                val action = getString(if (state.uploading) R.string.build_uploading else R.string.build_verifying, state.sketch)
                panel.outputStatus.text = getString(R.string.build_step, action, state.step)
                val progress = panel.outputProgress
                if (state.fraction == null) {
                    progress.isIndeterminate = true
                } else {
                    progress.isIndeterminate = false
                    progress.setProgressCompat((state.fraction * 100).toInt(), true)
                }
            }
            is BuildState.Succeeded -> {
                panel.outputStatus.text = getString(if (state.uploaded) R.string.build_uploaded else R.string.build_verified, state.summary)
                if (state.uploaded && justFinished) {
                    Snackbar.make(binding.root, R.string.build_uploaded_short, Snackbar.LENGTH_LONG)
                        .setAction(R.string.action_open_monitor) { openSerialMonitor() }
                        .show()
                }
            }
            is BuildState.Failed -> {
                panel.outputStatus.text = getString(R.string.build_failed, state.message)
                state.diagnostics.filter { it.severity != Diagnostic.Severity.NOTE }.take(MAX_DIAGNOSTICS).forEach { d ->
                    panel.diagnosticsList.addView(diagnosticView(d))
                }
            }
        }
        val rows = panel.diagnosticsList.childCount
        panel.diagnosticsScroll.layoutParams = panel.diagnosticsScroll.layoutParams.apply {
            height = if (rows > 4) (160 * resources.displayMetrics.density).toInt() else android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        }
    }

    private fun BuildState.isFor(sketch: Sketch) = when (this) {
        is BuildState.Idle -> false
        is BuildState.Running -> this.sketch == sketch.name
        is BuildState.Succeeded -> this.sketch == sketch.name
        is BuildState.Failed -> this.sketch == sketch.name
    }

    private fun diagnosticView(d: Diagnostic): View = TextView(this).apply {
        val kind = if (d.severity == Diagnostic.Severity.WARNING) "warning" else "error"
        text = "${d.file}:${d.line}: $kind: ${d.message}"
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
        typeface = android.graphics.Typeface.MONOSPACE
        setPadding(0, 6, 0, 6)
        if (d.inSketch) {
            setTextColor(getColor(if (d.severity == Diagnostic.Severity.WARNING) R.color.esp_warning else R.color.esp_error))
            setOnClickListener { jumpTo(d) }
        }
    }

    private fun jumpTo(d: Diagnostic) {
        val file = renderedFiles.firstOrNull { it.name == d.file } ?: return
        if (viewModel.state.value.document?.file?.id == file.id) {
            moveCursor(d)
        } else {
            pendingJump = d
            pushEditorText()
            viewModel.selectFile(file)
        }
    }

    private fun moveCursor(d: Diagnostic) {
        val editor = binding.codeEditor
        val line = (d.line - 1).coerceIn(0, editor.text.lineCount - 1)
        val column = (d.column - 1).coerceIn(0, editor.text.getColumnCount(line))
        editor.setSelection(line, column)
        editor.ensureSelectionVisible()
        editor.requestFocus()
    }

    private fun showFullOutput() {
        val state = app.builds.state.value as? BuildState.Failed ?: return
        val text = TextView(this).apply {
            text = state.output
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_output_title)
            .setView(ScrollView(this).apply { addView(text) })
            .setPositiveButton(R.string.dialog_ok, null)
            .show()
    }

    /** Explains once that compile/upload are experimental, then runs [action]. */
    private fun afterExperimentalNotice(action: () -> Unit) {
        val prefs = getSharedPreferences("esp_sketch_ide", MODE_PRIVATE)
        if (prefs.getBoolean(KEY_EXPERIMENTAL_SEEN, false)) return action()
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_experimental_title)
            .setMessage(R.string.dialog_experimental_message)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                prefs.edit().putBoolean(KEY_EXPERIMENTAL_SEEN, true).apply()
                action()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun startBuild(upload: Boolean) = afterExperimentalNotice {
        if (app.builds.isBusy) {
            Snackbar.make(binding.root, R.string.error_busy, Snackbar.LENGTH_SHORT).show()
            return@afterExperimentalNotice
        }
        pushEditorText()
        lifecycleScope.launch {
            if (!viewModel.saveBeforeBuild()) return@launch
            val pack = withContext(Dispatchers.IO) { app.esp32Pack() }
            if (pack == null) {
                Snackbar.make(binding.root, R.string.error_no_pack, Snackbar.LENGTH_LONG).show()
                return@launch
            }
            val board = app.boardPrefs.board(pack)
            if (!upload) {
                app.builds.verify(sketch, board)
                return@launch
            }
            // The serial monitor holds the port; close it so the upload can use it.
            app.serialMonitor?.let { m -> withContext(Dispatchers.IO) { m.close() } }
            app.serialMonitor = null
            val link = UsbConnect.pickAndOpen(this@EditorActivity, 115_200) { message ->
                Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
            } ?: return@launch
            app.builds.upload(sketch, board, link)
        }
    }

    private fun chooseBoard() {
        lifecycleScope.launch {
            val pack = withContext(Dispatchers.IO) { app.esp32Pack() }
            if (pack == null) {
                Snackbar.make(binding.root, R.string.error_no_pack, Snackbar.LENGTH_LONG).show()
                return@launch
            }
            val boards = pack.boards.sortedBy { it.second.lowercase() }
            val current = app.boardPrefs.board(pack)
            AlertDialog.Builder(this@EditorActivity)
                .setTitle(R.string.dialog_board_title)
                .setSingleChoiceItems(boards.map { it.second }.toTypedArray(), boards.indexOfFirst { it.first == current }) { dialog, which ->
                    app.boardPrefs.setBoard(boards[which].first)
                    boardName = boards[which].second
                    invalidateOptionsMenu()
                    dialog.dismiss()
                }
                .show()
        }
    }

    private fun openSerialMonitor() = startActivity(Intent(this, SerialMonitorActivity::class.java))

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
        if (::prefs.isInitialized) prefs.fontSizeSp = pxToSp(binding.codeEditor.textSizePx)
        if (::sketch.isInitialized) {
            pushEditorText()
            viewModel.saveInBackground()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.editor_toolbar, menu)
        return true
    }

    /** The chosen board's name for the menu; loaded off the main thread. */
    private var boardName: String? = null

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val compile = app.canCompile
        for (id in listOf(R.id.action_verify, R.id.action_upload, R.id.action_serial_monitor, R.id.action_board)) {
            menu.findItem(id)?.isVisible = compile
        }
        if (compile) {
            menu.findItem(R.id.action_board)?.title = boardName?.let { getString(R.string.action_board, it) } ?: getString(R.string.action_board_none)
            if (boardName == null) {
                lifecycleScope.launch {
                    val name = withContext(Dispatchers.IO) {
                        app.esp32Pack()?.let { pack -> app.boardPrefs.board(pack).let { id -> pack.boards.firstOrNull { it.first == id }?.second } }
                    }
                    if (name != null) { boardName = name; invalidateOptionsMenu() }
                }
            }
        }
        menu.findItem(R.id.action_undo)?.isEnabled = binding.codeEditor.canUndo()
        menu.findItem(R.id.action_redo)?.isEnabled = binding.codeEditor.canRedo()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_undo -> {
                binding.codeEditor.undo()
                invalidateOptionsMenu()
                true
            }
            R.id.action_redo -> {
                binding.codeEditor.redo()
                invalidateOptionsMenu()
                true
            }
            R.id.action_text_larger -> {
                setFontSize(FontSize.larger(pxToSp(binding.codeEditor.textSizePx)))
                true
            }
            R.id.action_text_smaller -> {
                setFontSize(FontSize.smaller(pxToSp(binding.codeEditor.textSizePx)))
                true
            }
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_save -> {
                pushEditorText()
                viewModel.save()
                true
            }
            R.id.action_verify -> {
                startBuild(upload = false)
                true
            }
            R.id.action_upload -> {
                startBuild(upload = true)
                true
            }
            R.id.action_serial_monitor -> {
                openSerialMonitor()
                true
            }
            R.id.action_board -> {
                chooseBoard()
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

    private fun setFontSize(sp: Float) {
        binding.codeEditor.setTextSize(sp)
        prefs.fontSizeSp = sp
    }

    private fun spToPx(sp: Float): Float = sp * resources.displayMetrics.density * resources.configuration.fontScale

    private fun pxToSp(px: Float): Float = px / (resources.displayMetrics.density * resources.configuration.fontScale)

    companion object {
        /** Symbol bar keys; "\t" is shown as ⇥ and inserts indentation. */
        private val SYMBOLS = arrayOf(
            "\t", "{", "}", "(", ")", ";", "<", ">", "=", "\"", "'", "#", "&", "|",
            "[", "]", "/", "*", "+", "-", "!", "_", ",", "."
        )

        private const val KEY_EXPERIMENTAL_SEEN = "compile_experimental_seen"
        private const val MAX_DIAGNOSTICS = 50

        const val EXTRA_SKETCH_NAME = "extra_sketch_name"
        const val EXTRA_SKETCH_FOLDER_ID = "extra_sketch_folder_id"
    }
}
