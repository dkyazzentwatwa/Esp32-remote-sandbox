package org.espsketchide.app.editor

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import org.espsketchide.app.settings.Board
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutocompleteKeywordsTest {

    @Before
    fun loadLanguages() {
        assertTrue("TextMate setup failed", EditorLanguages.awaitReady(ApplicationProvider.getApplicationContext()))
    }

    private fun keywords(file: String, board: Board): List<String> {
        val language = EditorLanguages.languageFor(file, board) as TextMateLanguage
        return language.autoCompleter.keywords.toList()
    }

    @Test
    fun esp32SketchGetsEsp32Names() {
        val words = keywords("blink.ino", Board.ESP32)
        assertTrue("ledcWrite" in words)
        assertTrue("digitalWrite" in words)
        assertFalse("D1" in words)
    }

    @Test
    fun esp8266SketchGetsEsp8266Names() {
        val words = keywords("blink.ino", Board.ESP8266)
        assertTrue("D1" in words)
        assertTrue("digitalWrite" in words)
        assertFalse("ledcWrite" in words)
    }

    @Test
    fun plainTextGetsNoLanguage() {
        assertTrue(EditorLanguages.languageFor("notes.txt", Board.ESP32) is EmptyLanguage)
    }
}
