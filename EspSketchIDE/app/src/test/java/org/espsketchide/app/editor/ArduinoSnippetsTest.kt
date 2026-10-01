package org.espsketchide.app.editor

import com.google.common.truth.Truth.assertThat
import org.espsketchide.app.reference.ArduinoReference
import org.espsketchide.app.settings.Board
import org.junit.Test
import java.io.File

class ArduinoSnippetsTest {

    @Test
    fun syntaxBecomesASnippetWithPlaceholders() {
        assertThat(ArduinoSnippets.fromSyntax("digitalWrite(pin, value)")).isEqualTo("digitalWrite(\${1:pin}, \${2:value})")
        assertThat(ArduinoSnippets.fromSyntax("millis()")).isEqualTo("millis()")
        assertThat(ArduinoSnippets.fromSyntax("random(max)  or  random(min, max)")).isEqualTo("random(\${1:max})")
        assertThat(ArduinoSnippets.fromSyntax("Serial.begin(speed)")).isEqualTo("Serial.begin(\${1:speed})")
        assertThat(ArduinoSnippets.fromSyntax("attachInterrupt(digitalPinToInterrupt(pin), function, mode)"))
            .isEqualTo("attachInterrupt(\${1:digitalPinToInterrupt(pin)}, \${2:function}, \${3:mode})")
    }

    @Test
    fun nonCallSyntaxHasNoSnippet() {
        assertThat(ArduinoSnippets.fromSyntax("HIGH, LOW")).isNull()
        assertThat(ArduinoSnippets.fromSyntax("String text = \"hello\";")).isNull()
        assertThat(ArduinoSnippets.fromSyntax("Serial.printf(format, values…)")).isEqualTo("Serial.printf(\${1:format}, \${2:values})")
    }

    private val all = ArduinoSnippets.from(ArduinoReference.parse(File("src/main/assets/reference/reference.json").readText()), Board.ESP32)

    @Test
    fun keywordsGetBlockTemplates() {
        val forLoop = all.first { it.label == "for" }
        assertThat(forLoop.body).isEqualTo("for (int \${1:i} = 0; \${1:i} < \${2:10}; \${1:i}++) {\n  \$0\n}")
        assertThat(all.first { it.label == "if" }.body).startsWith("if (\${1:condition}) {")
    }

    @Test
    fun plainPrefixMatchesNamesCaseInsensitively() {
        val matches = ArduinoSnippets.match(all, objectName = null, prefix = "digitalw")
        assertThat(matches.map { it.snippet.label }).containsExactly("digitalWrite")
        assertThat(matches.single().insert).isEqualTo("digitalWrite(\${1:pin}, \${2:value})")
    }

    @Test
    fun afterADotOnlyThatObjectsMembersMatchAndOnlyTheMemberIsInserted() {
        val matches = ArduinoSnippets.match(all, objectName = "Serial", prefix = "pr")
        assertThat(matches.map { it.snippet.label }).containsExactly("Serial.print", "Serial.println", "Serial.printf")
        assertThat(matches.first { it.snippet.label == "Serial.println" }.insert).isEqualTo("println(\${1:value})")
        assertThat(ArduinoSnippets.match(all, objectName = "WiFi", prefix = "pr")).isEmpty()
    }

    @Test
    fun typingTheObjectNameOffersItsMembers() {
        val labels = ArduinoSnippets.match(all, objectName = null, prefix = "Seri").map { it.snippet.label }
        assertThat(labels).contains("Serial.begin")
    }

    @Test
    fun boardSpecificSnippetsFollowTheBoard() {
        val esp8266 = ArduinoSnippets.from(ArduinoReference.parse(File("src/main/assets/reference/reference.json").readText()), Board.ESP8266)
        assertThat(esp8266.map { it.label }).doesNotContain("ledcWrite")
        assertThat(all.map { it.label }).contains("ledcWrite")
    }
}
