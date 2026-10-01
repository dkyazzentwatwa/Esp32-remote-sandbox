package org.espsketchide.app.reference

import com.google.common.truth.Truth.assertThat
import org.espsketchide.app.settings.Board
import org.junit.Test
import java.io.File

class ArduinoReferenceTest {

    private val json = File("src/main/assets/reference/reference.json").readText()
    private val reference = ArduinoReference.parse(json)

    @Test
    fun bundledReferenceParsesWithFullEntries() {
        assertThat(reference.entries.size).isAtLeast(40)
        val digitalWrite = reference.lookup("digitalWrite")!!
        assertThat(digitalWrite.syntax).isEqualTo("digitalWrite(pin, value)")
        assertThat(digitalWrite.params.map { it.first }).containsExactly("pin", "value").inOrder()
        assertThat(digitalWrite.example).isNotEmpty()
    }

    @Test
    fun everyEntryHasASummaryAndUniqueName() {
        reference.entries.forEach { assertThat(it.summary).isNotEmpty() }
        val names = reference.entries.flatMap { listOf(it.name) + it.aliases }
        assertThat(names).containsNoDuplicates()
    }

    @Test
    fun aliasesFindTheirEntry() {
        assertThat(reference.lookup("LOW")!!.name).isEqualTo("HIGH")
        assertThat(reference.lookup("INPUT_PULLUP")!!.name).isEqualTo("OUTPUT")
    }

    @Test
    fun boardSpecificEntries() {
        assertThat(reference.lookup("ledcWrite")!!.appliesTo(Board.ESP32)).isTrue()
        assertThat(reference.lookup("ledcWrite")!!.appliesTo(Board.ESP8266)).isFalse()
        assertThat(reference.lookup("digitalWrite")!!.appliesTo(Board.ESP8266)).isTrue()
    }

    @Test
    fun wordAtFindsIdentifiersAndMemberCalls() {
        val line = "  Serial.println(digitalRead(BUTTON));"
        assertThat(ArduinoReference.wordAt(line, line.indexOf("println") + 2)).isEqualTo("Serial.println")
        assertThat(ArduinoReference.wordAt(line, line.indexOf("Serial") + 1)).isEqualTo("Serial.println")
        assertThat(ArduinoReference.wordAt(line, line.indexOf("digitalRead") + 3)).isEqualTo("digitalRead")
        assertThat(ArduinoReference.wordAt(line, 0)).isNull()
    }

    @Test
    fun lookupAtPrefersTheMemberCallThenTheBareName() {
        val line = "  WiFi.begin(ssid, pass);"
        assertThat(reference.lookupAt(line, line.indexOf("begin"))!!.name).isEqualTo("WiFi.begin")
        val other = "  Serial.flush();"
        assertThat(reference.lookupAt(other, other.indexOf("flush"))).isNull()
    }

    @Test
    fun categoriesKeepFileOrder() {
        assertThat(reference.byCategory().first().first).isEqualTo("Structure")
    }
}
