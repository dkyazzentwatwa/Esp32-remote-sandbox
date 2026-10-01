package org.espsketchide.app.editor

import android.view.LayoutInflater
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.rosemoe.sora.event.PublishSearchResultEvent
import io.github.rosemoe.sora.widget.CodeEditor
import org.espsketchide.app.R
import org.espsketchide.app.databinding.ViewSearchPanelBinding
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SearchControllerTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun closingThePanelAfterASearchWithNoMatchesDoesNotCrash() {
        lateinit var editor: CodeEditor
        lateinit var controller: SearchController
        val published = CountDownLatch(1)
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_EspSketchIDE)
            editor = CodeEditor(context)
            editor.setText("void setup() {}\n")
            val panel = ViewSearchPanelBinding.inflate(LayoutInflater.from(context))
            controller = SearchController(panel, editor) {}
            editor.subscribeEvent(PublishSearchResultEvent::class.java) { _, _ -> published.countDown() }
            controller.show()
            panel.searchField.setText("nomatch")
        }
        assertTrue("search never published results", published.await(10, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()

        // Used to throw IllegalStateException("pattern not set") from the result handler,
        // because the first-result jump was still armed when stopSearch() published.
        instrumentation.runOnMainSync { controller.hide() }
    }
}
