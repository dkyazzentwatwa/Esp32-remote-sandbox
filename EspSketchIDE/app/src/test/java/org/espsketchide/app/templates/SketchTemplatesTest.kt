package org.espsketchide.app.templates

import org.espsketchide.app.settings.Board
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SketchTemplatesTest {

    @Test
    fun `every template and board has a sketch with setup and loop`() {
        for (id in SketchTemplates.ids) {
            for (board in Board.entries) {
                val text = SketchTemplates.contentFor(id, board)
                assertTrue("$id/$board: setup", "void setup()" in text)
                assertTrue("$id/$board: loop", "void loop()" in text)
                assertTrue("$id/$board: ends with newline", text.endsWith("\n"))
            }
        }
    }

    @Test
    fun `wifi station template includes the header of its board`() {
        val esp32 = SketchTemplates.contentFor(SketchTemplates.WIFI_STATION, Board.ESP32)
        assertTrue("#include <WiFi.h>" in esp32)
        assertFalse("ESP8266WiFi" in esp32)
        val esp8266 = SketchTemplates.contentFor(SketchTemplates.WIFI_STATION, Board.ESP8266)
        assertTrue("#include <ESP8266WiFi.h>" in esp8266)
        assertFalse("#include <WiFi.h>" in esp8266)
    }

    @Test
    fun `unknown ids fall back to the bare minimum template`() {
        assertEquals(
            SketchTemplates.contentFor(SketchTemplates.BARE, Board.ESP32),
            SketchTemplates.contentFor("nope", Board.ESP32),
        )
    }

    @Test
    fun `bare minimum is the default and does not use the serial port`() {
        assertEquals(SketchTemplates.BARE, SketchTemplates.ids.first())
        assertFalse("Serial" in SketchTemplates.contentFor(SketchTemplates.BARE, Board.ESP32))
    }
}
