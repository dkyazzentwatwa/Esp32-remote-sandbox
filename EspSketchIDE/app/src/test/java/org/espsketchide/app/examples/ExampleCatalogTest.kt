package org.espsketchide.app.examples

import org.espsketchide.app.settings.Board
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleCatalogTest {

    private val json = """
        { "examples": [
          { "id": "blink", "name": "Blink", "category": "Basics", "description": "Blink the LED.",
            "variants": { "esp32": "basics/Blink", "esp8266": "basics/Blink" } },
          { "id": "scan", "name": "WiFiScan", "category": "WiFi", "description": "Scan.",
            "variants": { "esp32": "wifi/Scan_esp32", "esp8266": "wifi/Scan_esp8266" } },
          { "id": "touch", "name": "TouchSensor", "category": "ESP-specific", "description": "Touch.",
            "variants": { "esp32": "esp/Touch" } },
          { "id": "fade", "name": "Fade", "category": "Basics", "description": "Fade.",
            "variants": { "esp32": "basics/Fade", "esp8266": "basics/Fade" } },
          { "id": "ghost", "name": "Ghost", "category": "Other", "description": "Unknown board only.",
            "variants": { "esp99": "x/Ghost" } }
        ] }
    """.trimIndent()

    private val examples = ExampleCatalog.parse(json)

    @Test
    fun `parses every entry with its fields`() {
        assertEquals(5, examples.size)
        val blink = examples.first()
        assertEquals("blink", blink.id)
        assertEquals("Blink", blink.name)
        assertEquals("Basics", blink.category)
        assertEquals("Blink the LED.", blink.description)
        assertEquals(mapOf("esp32" to "basics/Blink", "esp8266" to "basics/Blink"), blink.variants)
    }

    @Test
    fun `an example is listed for a board only if it has a variant for it`() {
        val esp8266 = ExampleCatalog.forBoard(examples, Board.ESP8266).flatMap { it.second }.map { it.id }
        assertTrue("touch" !in esp8266)
        assertTrue("blink" in esp8266)
        val esp32 = ExampleCatalog.forBoard(examples, Board.ESP32).flatMap { it.second }.map { it.id }
        assertTrue("touch" in esp32)
    }

    @Test
    fun `unknown boards are ignored and produce no empty groups`() {
        val groups = ExampleCatalog.forBoard(examples, Board.ESP32)
        assertTrue(groups.none { it.first == "Other" })
        assertTrue(groups.all { it.second.isNotEmpty() })
    }

    @Test
    fun `groups follow index order of first appearance`() {
        val groups = ExampleCatalog.forBoard(examples, Board.ESP32)
        assertEquals(listOf("Basics", "WiFi", "ESP-specific"), groups.map { it.first })
        assertEquals(listOf("blink", "fade"), groups[0].second.map { it.id })
    }

    @Test
    fun `variant path depends on the board`() {
        val scan = examples.first { it.id == "scan" }
        assertEquals("wifi/Scan_esp32", ExampleCatalog.variantPath(scan, Board.ESP32))
        assertEquals("wifi/Scan_esp8266", ExampleCatalog.variantPath(scan, Board.ESP8266))
        assertNull(ExampleCatalog.variantPath(examples.first { it.id == "touch" }, Board.ESP8266))
    }

    @Test
    fun `rows put a header before each group`() {
        val rows = ExampleCatalog.rows(ExampleCatalog.forBoard(examples, Board.ESP32))
        assertEquals(ExampleRow.Header("Basics"), rows[0])
        assertEquals("blink", (rows[1] as ExampleRow.Item).example.id)
        assertEquals("fade", (rows[2] as ExampleRow.Item).example.id)
        assertEquals(ExampleRow.Header("WiFi"), rows[3])
        assertEquals(7, rows.size)
    }
}
