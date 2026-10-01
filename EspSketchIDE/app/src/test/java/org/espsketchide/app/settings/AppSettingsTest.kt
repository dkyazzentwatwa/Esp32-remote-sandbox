package org.espsketchide.app.settings

import androidx.appcompat.app.AppCompatDelegate
import org.junit.Assert.assertEquals
import org.junit.Test

class AppSettingsTest {

    @Test
    fun `theme keys map to AppCompat night modes`() {
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, ThemeMode.fromKey("dark").nightMode)
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, ThemeMode.fromKey("light").nightMode)
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, ThemeMode.fromKey("system").nightMode)
    }

    @Test
    fun `missing or unknown theme key falls back to dark`() {
        assertEquals(ThemeMode.DARK, ThemeMode.fromKey(null))
        assertEquals(ThemeMode.DARK, ThemeMode.fromKey("sepia"))
    }

    @Test
    fun `font size is clamped to the supported range`() {
        assertEquals(10, EditorFontSize.clamp(4))
        assertEquals(14, EditorFontSize.clamp(14))
        assertEquals(28, EditorFontSize.clamp(99))
    }

    @Test
    fun `board keys map to boards and default to esp32`() {
        assertEquals(Board.ESP32, Board.fromKey("esp32"))
        assertEquals(Board.ESP8266, Board.fromKey("esp8266"))
        assertEquals(Board.ESP32, Board.fromKey(null))
        assertEquals(Board.ESP32, Board.fromKey("uno"))
    }
}
