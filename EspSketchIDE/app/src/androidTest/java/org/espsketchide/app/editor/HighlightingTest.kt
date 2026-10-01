package org.espsketchide.app.editor

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import org.eclipse.tm4e.core.grammar.IGrammar
import org.eclipse.tm4e.core.grammar.IStateStack
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HighlightingTest {

    private val sketch = listOf(
        "#include <WiFi.h>",
        "uint8_t counter = 0; // count loops",
        "void loop() {",
        "  digitalWrite(LED_BUILTIN, HIGH);",
        "  Serial.println(\"hi\");",
        "}",
    )

    private lateinit var grammar: IGrammar

    @Before
    fun setUp() {
        assertTrue(EditorLanguages.awaitReady(ApplicationProvider.getApplicationContext()))
        grammar = requireNotNull(GrammarRegistry.getInstance().findGrammar(EditorLanguages.ARDUINO_SCOPE))
    }

    /** Scopes of the token covering the first occurrence of [text] on line [lineIndex]. */
    private fun scopesAt(lineIndex: Int, text: String): List<String> {
        var state: IStateStack? = null
        sketch.forEachIndexed { i, line ->
            val result = grammar.tokenizeLine(line, state, null)
            if (i == lineIndex) {
                val column = line.indexOf(text)
                require(column >= 0) { "'$text' not on line $lineIndex" }
                return result.tokens.first { column >= it.startIndex && column < it.endIndex }.scopes
            }
            state = result.ruleStack
        }
        error("line $lineIndex out of range")
    }

    private fun assertScope(lineIndex: Int, text: String, prefix: String) {
        val scopes = scopesAt(lineIndex, text)
        assertTrue("'$text' scopes $scopes lack $prefix", scopes.any { it.startsWith(prefix) })
    }

    @Test
    fun preprocessorDirectiveIsHighlighted() = assertScope(0, "#include", "keyword.control.directive")

    @Test
    fun includePathIsStringNotArduinoClass() {
        assertScope(0, "WiFi.h", "string")
        assertTrue(scopesAt(0, "WiFi.h").none { it.startsWith("support.class.arduino") })
    }

    @Test
    fun fixedWidthTypeIsHighlighted() {
        val scopes = scopesAt(1, "uint8_t")
        assertTrue("uint8_t scopes $scopes", scopes.any { it.startsWith("support.type") || it.startsWith("storage.type") })
    }

    @Test
    fun lineCommentIsHighlighted() = assertScope(1, "// count", "comment")

    @Test
    fun arduinoFunctionInsideFunctionBody() = assertScope(3, "digitalWrite", "support.function.arduino")

    @Test
    fun arduinoConstants() {
        assertScope(3, "LED_BUILTIN", "support.constant.arduino")
        assertScope(3, "HIGH", "support.constant.arduino")
    }

    @Test
    fun arduinoClassAndString() {
        assertScope(4, "Serial", "support.class.arduino")
        assertScope(4, "hi", "string")
    }

    @Test
    fun bothThemesAreRegistered() {
        assertNotNull(ThemeRegistry.getInstance().findThemeByThemeName(EditorLanguages.THEME_DARK))
        assertNotNull(ThemeRegistry.getInstance().findThemeByThemeName(EditorLanguages.THEME_LIGHT))
    }
}
