package org.espsketchide.app.editor

import android.content.Context
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.widget.doAfterTextChanged
import io.github.rosemoe.sora.event.PublishSearchResultEvent
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import org.espsketchide.app.R
import org.espsketchide.app.databinding.ViewSearchPanelBinding

/**
 * Behavior of the find/replace panel over sora's [EditorSearcher]. Plain-text search only
 * (regex is out of scope). The searcher works on a background thread and announces results with
 * [PublishSearchResultEvent], which is where the match counter is updated.
 */
class SearchController(
    private val panel: ViewSearchPanelBinding,
    private val editor: CodeEditor,
    private val onVisibilityChanged: (Boolean) -> Unit,
) {
    private var jumpToFirstResult = false

    val isVisible: Boolean get() = panel.root.visibility == View.VISIBLE

    init {
        panel.searchField.doAfterTextChanged { runSearch() }
        panel.searchField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                step { editor.searcher.gotoNext() }
                true
            } else {
                false
            }
        }
        panel.searchMatchCase.addOnCheckedChangeListener { _, _ -> runSearch() }
        panel.searchNext.setOnClickListener { step { editor.searcher.gotoNext() } }
        panel.searchPrevious.setOnClickListener { step { editor.searcher.gotoPrevious() } }
        panel.searchToggleReplace.setOnClickListener {
            panel.replaceRow.visibility = if (panel.replaceRow.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        panel.replaceOne.setOnClickListener {
            if (editor.searcher.hasQuery()) {
                // Re-arm the first-result jump so the next match is selected when the refreshed
                // results arrive; pressing Replace again then replaces it, as in desktop editors.
                jumpToFirstResult = true
                keepPanelFocus { editor.searcher.replaceThis(panel.replaceField.text.toString()) }
                updateCounter()
            }
        }
        panel.replaceAll.setOnClickListener {
            if (editor.searcher.hasQuery()) {
                editor.searcher.replaceAll(panel.replaceField.text.toString()) { updateCounter() }
            }
        }
        panel.searchClose.setOnClickListener { hide() }
        editor.subscribeEvent(PublishSearchResultEvent::class.java) { _, _ ->
            if (jumpToFirstResult && editor.searcher.matchedPositionCount > 0) {
                jumpToFirstResult = false
                keepPanelFocus { editor.searcher.gotoNext() }
            }
            updateCounter()
        }
    }

    /** Shows the panel, prefilled with a single-line selection, and opens the keyboard. */
    fun show() {
        panel.root.visibility = View.VISIBLE
        val cursor = editor.cursor
        if (cursor.isSelected) {
            val selected = editor.text.substring(cursor.left, cursor.right)
            if ('\n' !in selected && selected.length <= MAX_PREFILL) {
                panel.searchField.setText(selected)
            }
        }
        panel.searchField.requestFocus()
        panel.searchField.setSelection(panel.searchField.text.length)
        inputMethods().showSoftInput(panel.searchField, InputMethodManager.SHOW_IMPLICIT)
        onVisibilityChanged(true)
        runSearch()
    }

    /** Hides the panel and stops the search, which clears the highlights. */
    fun hide() {
        editor.searcher.stopSearch()
        jumpToFirstResult = false
        inputMethods().hideSoftInputFromWindow(panel.root.windowToken, 0)
        panel.root.visibility = View.GONE
        editor.requestFocus()
        onVisibilityChanged(false)
    }

    fun toggle() = if (isVisible) hide() else show()

    /** Re-runs the current query, e.g. after another file was loaded into the editor. */
    fun refresh() {
        if (isVisible) runSearch()
    }

    private fun runSearch() {
        val query = panel.searchField.text.toString()
        if (query.isEmpty()) {
            editor.searcher.stopSearch()
            jumpToFirstResult = false
            panel.searchCount.text = ""
            return
        }
        jumpToFirstResult = true
        val caseInsensitive = !panel.searchMatchCase.isChecked
        editor.searcher.search(query, EditorSearcher.SearchOptions(EditorSearcher.SearchOptions.TYPE_NORMAL, caseInsensitive))
        updateCounter()
    }

    private fun step(move: () -> Boolean) {
        if (editor.searcher.hasQuery()) {
            keepPanelFocus { move() }
            updateCounter()
        }
    }

    /**
     * Runs a searcher move and gives focus back to the panel field that had it. sora selects the
     * match with CodeEditor.setSelectionRegion, which calls requestFocus(); with the keyboard
     * still open, the next keystrokes would then replace the selected match in the sketch.
     */
    private fun keepPanelFocus(move: () -> Unit) {
        val field = if (panel.replaceField.hasFocus()) panel.replaceField else panel.searchField
        move()
        field.requestFocus()
    }

    private fun updateCounter() {
        val searcher = editor.searcher
        panel.searchCount.text = if (searcher.hasQuery()) {
            SearchCounter.text(
                searcher.currentMatchedPositionIndex,
                searcher.matchedPositionCount,
                panel.root.context.getString(R.string.search_no_results),
            )
        } else {
            ""
        }
    }

    private fun inputMethods() = panel.root.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    private companion object {
        const val MAX_PREFILL = 100
    }
}
