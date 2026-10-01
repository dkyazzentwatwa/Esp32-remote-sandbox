package org.espsketchide.app.compile

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ErrorHelpTest {

    private fun explain(message: String) = ErrorHelp.explain(message)

    @Test
    fun missingSemicolon() {
        assertThat(explain("expected ';' before '}' token")).contains("semicolon")
        assertThat(explain("expected ';' before 'digitalWrite'")).contains("line above")
    }

    @Test
    fun misspelledNameWithSuggestion() {
        val help = explain("'digitalWrit' was not declared in this scope; did you mean 'digitalWrite'?")
        assertThat(help).contains("digitalWrit")
        assertThat(help).contains("digitalWrite")
    }

    @Test
    fun undeclaredName() {
        val help = explain("'ledPin' was not declared in this scope")
        assertThat(help).contains("ledPin")
        assertThat(help).contains("capital")
    }

    @Test
    fun undeclaredNameThatNeedsAnInclude() {
        val help = explain("'WiFi' was not declared in this scope; 'WiFi' is defined in header '<WiFi.h>'; this is probably fixable by adding '#include <WiFi.h>'")
        assertThat(help).contains("#include <WiFi.h>")
    }

    @Test
    fun missingHeader() {
        assertThat(explain("Adafruit_NeoPixel.h: No such file or directory")).contains("Adafruit_NeoPixel.h")
    }

    @Test
    fun braces() {
        assertThat(explain("expected '}' at end of input")).contains("}")
        assertThat(explain("expected declaration before '}' token")).contains("extra")
        assertThat(explain("expected ')' before ';' token")).contains(")")
    }

    @Test
    fun curlyQuotesFromTheWeb() {
        assertThat(explain("stray '\\342' in program")).contains("quote")
    }

    @Test
    fun unterminatedString() {
        assertThat(explain("missing terminating \" character")).contains("\"")
    }

    @Test
    fun typeAndDefinitionMistakes() {
        assertThat(explain("'Strng' does not name a type; did you mean 'String'?")).contains("String")
        assertThat(explain("redefinition of 'void setup()'")).contains("twice")
        assertThat(explain("too few arguments to function 'void digitalWrite(uint8_t, uint8_t)'")).contains("digitalWrite")
        assertThat(explain("lvalue required as left operand of assignment")).contains("==")
    }

    @Test
    fun assignmentInsideIf() {
        assertThat(explain("suggest parentheses around assignment used as truth value [-Wparentheses]")).contains("==")
    }

    @Test
    fun unknownMessagesHaveNoHelp() {
        assertThat(explain("some message nobody has seen before")).isNull()
    }
}
