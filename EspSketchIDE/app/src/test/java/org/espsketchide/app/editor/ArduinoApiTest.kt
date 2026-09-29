package org.espsketchide.app.editor

import org.espsketchide.app.settings.Board
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArduinoApiTest {

    private val identifier = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    @Test
    fun `core keywords are offered for every board`() {
        for (board in Board.entries) {
            assertTrue(ArduinoApi.keywordsFor(board).containsAll(ArduinoApi.core))
        }
    }

    @Test
    fun `board specific names stay out of the other board`() {
        assertTrue("ledcWrite" in ArduinoApi.keywordsFor(Board.ESP32))
        assertFalse("ledcWrite" in ArduinoApi.keywordsFor(Board.ESP8266))
        assertTrue("ESP8266WebServer" in ArduinoApi.keywordsFor(Board.ESP8266))
        assertFalse("ESP8266WebServer" in ArduinoApi.keywordsFor(Board.ESP32))
        assertTrue("D1" in ArduinoApi.keywordsFor(Board.ESP8266))
        assertFalse("D1" in ArduinoApi.keywordsFor(Board.ESP32))
    }

    @Test
    fun `core and board lists do not overlap`() {
        assertEquals(emptySet<String>(), ArduinoApi.core.intersect(ArduinoApi.esp32.toSet()))
        assertEquals(emptySet<String>(), ArduinoApi.core.intersect(ArduinoApi.esp8266.toSet()))
        assertEquals(emptySet<String>(), ArduinoApi.esp32.intersect(ArduinoApi.esp8266.toSet()))
    }

    @Test
    fun `keywords are unique sorted identifiers`() {
        for (board in Board.entries) {
            val words = ArduinoApi.keywordsFor(board)
            assertEquals(words.distinct(), words)
            assertEquals(words.sorted(), words)
            words.forEach { assertTrue("not an identifier: '$it'", identifier.matches(it)) }
        }
    }

    @Test
    fun `common Arduino API is present`() {
        val words = ArduinoApi.keywordsFor(Board.ESP32)
        listOf("pinMode", "digitalWrite", "Serial", "millis", "HIGH", "LED_BUILTIN", "WiFi").forEach {
            assertTrue("$it missing", it in words)
        }
    }
}
