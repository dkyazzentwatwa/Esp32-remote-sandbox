package org.espsketchide.app.libraries

import com.google.common.truth.Truth.assertThat
import org.espsketchide.app.settings.Board
import org.junit.Test
import java.io.File

class LibraryCatalogTest {

    private val catalog = LibraryCatalog.parse(File("src/main/assets/libraries/catalog.json").readText())

    @Test
    fun bundledCatalogParses() {
        val dht = catalog.find("DHT sensor library")!!
        assertThat(dht.url).startsWith("https://")
        assertThat(dht.sha256).hasLength(64)
        assertThat(dht.dependencies).containsExactly("Adafruit Unified Sensor")
    }

    @Test
    fun everyDependencyIsInTheCatalog() {
        catalog.all.flatMap { it.dependencies }.forEach { assertThat(catalog.find(it)).isNotNull() }
    }

    @Test
    fun visibleListHidesDependenciesAndFiltersByBoard() {
        val esp32 = catalog.visible(Board.ESP32).map { it.name }
        assertThat(esp32).contains("ESP32Servo")
        assertThat(esp32).doesNotContain("Adafruit BusIO")
        assertThat(catalog.visible(Board.ESP8266).map { it.name }).doesNotContain("ESP32Servo")
    }

    @Test
    fun installPlanPutsMissingDependenciesFirst() {
        val plan = catalog.installPlan("Adafruit SSD1306", installed = emptySet())
        assertThat(plan.map { it.name }).containsExactly("Adafruit BusIO", "Adafruit GFX Library", "Adafruit SSD1306").inOrder()
        val partial = catalog.installPlan("Adafruit SSD1306", installed = setOf("Adafruit BusIO"))
        assertThat(partial.map { it.name }).containsExactly("Adafruit GFX Library", "Adafruit SSD1306").inOrder()
    }

    @Test
    fun suggestionForAMissingHeader() {
        assertThat(catalog.suggestFor("DHT.h")?.name).isEqualTo("DHT sensor library")
        assertThat(catalog.suggestFor("Adafruit_NeoPixel.h")?.name).isEqualTo("Adafruit NeoPixel")
        assertThat(catalog.suggestFor("PubSubClient.h")?.name).isEqualTo("PubSubClient")
        assertThat(catalog.suggestFor("Nonexistent.h")).isNull()
    }
}
