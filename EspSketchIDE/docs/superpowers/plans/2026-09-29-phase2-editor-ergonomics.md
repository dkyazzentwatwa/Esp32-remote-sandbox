# Phase 2: Editor Ergonomics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the editor pleasant to type in on a phone: a symbol bar,
undo/redo, find/replace, Arduino/ESP autocomplete, unsaved-change tracking with
`name •` tab labels, and a reorganized toolbar. Also adds the `board` and
`show_symbol_bar` settings deferred from Phase 1.

**Architecture:** Plain Activities and ViewBinding, as today. New units:

- **`settings/AppSettings` additions:** `Board` enum, `board`, `showSymbolBar`.
- **`editor/ArduinoApi`:** pure keyword lists per board.
- **`editor/DirtyTracker<K>`:** pure set-with-change-reporting for unsaved files.
- **`editor/SymbolBarSymbols`:** the symbol bar's display/insert arrays.
- **`editor/SearchController` + `SearchCounter`:** find/replace panel logic over
  sora's `EditorSearcher`; `SearchCounter` is the pure counter formatter.
- **`EditorActivity`:** wires all of the above in.

**Tech Stack:** as Phase 1; sora-editor 0.23.6.

**Spec:** `docs/superpowers/specs/2026-09-27-ide-editor-overhaul-design.md`

**sora 0.23.6 API, verified with `javap` against the jars (not guessed):**

| Need | Actual API |
|---|---|
| Symbol bar | `SymbolInputView(Context, AttributeSet)`; `bindEditor(CodeEditor)`; `addSymbols(String[] display, String[] insert)`; `setTextColor(int)`; `forEachButton(ButtonConsumer)`. A symbol whose insert text is exactly `"\t"` calls `CodeEditor.indentOrCommitTab()`, which inserts spaces when the language has `useTab(false)`. |
| Undo/redo | `CodeEditor.undo()/redo()/canUndo()/canRedo()`. `setText` builds a new `Content` (fresh `UndoManager`), so loading a file resets history with no extra call. |
| Content events | `subscribeEvent(Class<T>, EventReceiver<T>)`, where `EventReceiver.onReceive(event, unsubscribe)`. `ContentChangeEvent` has `getAction()` (`ACTION_SET_NEW_TEXT/INSERT/DELETE`) and is dispatched synchronously from `setText` with `ACTION_SET_NEW_TEXT`. |
| Search | `CodeEditor.getSearcher(): EditorSearcher` with `search(String, SearchOptions)`, `stopSearch()`, `hasQuery()`, `gotoNext()/gotoPrevious()`, `getCurrentMatchedPositionIndex()` (−1 = none selected), `getMatchedPositionCount()`, `isMatchedPositionSelected()`, `replaceThis(String)` (= `replaceCurrentMatch`: replaces if a match is selected, otherwise jumps to the next one), `replaceAll(String)`. `SearchOptions(int type, boolean caseInsensitive)` with `TYPE_NORMAL`. Search runs on a background thread and announces results with `PublishSearchResultEvent`. |
| Autocomplete | `TextMateLanguage.setCompleterKeywords(String[])` (calls `IdentifierAutoComplete.setKeywords(arr, false)`, so keywords are case-sensitive). |
| Selection text | `CodeEditor.getCursor().isSelected/getLeft/getRight`, `getText().substring(int,int)` |

**Deviations from the spec (decided while planning):**
1. `dirtyFiles` is a small `DirtyTracker<Uri>` instead of a bare
   `MutableSet<Uri>`, so the logic is JVM-testable (generic, tested with
   strings).
2. A `ContentChangeEvent` marks the file dirty unless it is the load
   (`ACTION_SET_NEW_TEXT`) *or* the `loading` flag is set. The flag alone would
   be enough; the action check is a second guard.
3. The `Aa` match-case toggle is a `MaterialButton` (checkable), not a
   ToggleButton.
4. Back closes the search panel first (an `OnBackPressedCallback` enabled only
   while the panel is open).

**Every Gradle command** needs JDK 17–21:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64   # or /opt/homebrew/opt/openjdk@21
```

All paths are relative to `EspSketchIDE/`. The git root is its parent.

**Commit trailer** (every commit):

```
Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01KLrUbTsFZqUuFtfLxHVfJr
```

---

## File map

**New:**

| Path | Contents |
|---|---|
| `app/src/main/java/org/espsketchide/app/editor/ArduinoApi.kt` | keyword lists |
| `app/src/main/java/org/espsketchide/app/editor/DirtyTracker.kt` | unsaved-file set |
| `app/src/main/java/org/espsketchide/app/editor/SymbolBarSymbols.kt` | symbol arrays |
| `app/src/main/java/org/espsketchide/app/editor/SearchCounter.kt` | counter text |
| `app/src/main/java/org/espsketchide/app/editor/SearchController.kt` | find/replace panel logic |
| `app/src/main/res/layout/view_search_panel.xml` | find/replace panel |
| `app/src/test/java/org/espsketchide/app/editor/ArduinoApiTest.kt` | JVM |
| `app/src/test/java/org/espsketchide/app/editor/DirtyTrackerTest.kt` | JVM |
| `app/src/test/java/org/espsketchide/app/editor/SymbolBarSymbolsTest.kt` | JVM |
| `app/src/test/java/org/espsketchide/app/editor/SearchCounterTest.kt` | JVM |
| `app/src/androidTest/java/org/espsketchide/app/editor/AutocompleteKeywordsTest.kt` | instrumented |

**Modified:** `settings/AppSettings.kt`, `AppSettingsTest.kt`,
`editor/EditorLanguages.kt`, `EditorActivity.kt`, `activity_editor.xml`,
`menu/editor_toolbar.xml`, `res/xml/preferences.xml`, `values/arrays.xml`,
`values/strings.xml`, `README.md`, `CLAUDE.md`.

---

### Task 1: `board` and `show_symbol_bar` settings (TDD)

**Files:** `settings/AppSettings.kt`, `AppSettingsTest.kt`, `preferences.xml`,
`arrays.xml`, `strings.xml`, `settings/SettingsActivity.kt`.

- [ ] **Step 1: Add failing tests** to `AppSettingsTest`:

```kotlin
    @Test
    fun `board keys map to boards and default to esp32`() {
        assertEquals(Board.ESP32, Board.fromKey("esp32"))
        assertEquals(Board.ESP8266, Board.fromKey("esp8266"))
        assertEquals(Board.ESP32, Board.fromKey(null))
        assertEquals(Board.ESP32, Board.fromKey("uno"))
    }
```

- [ ] **Step 2: Run, expect a compile failure** (`Unresolved reference 'Board'`):
  `./gradlew :app:testDebugUnitTest --tests "org.espsketchide.app.settings.AppSettingsTest"`

- [ ] **Step 3: Implement.** In `AppSettings.kt` add the keys, the enum, and two
  getters:

```kotlin
const val KEY_BOARD = "board"
const val KEY_SHOW_SYMBOL_BAR = "show_symbol_bar"

/** Only selects examples, templates and autocomplete lists. The app cannot build for a board yet. */
enum class Board(val key: String) {
    ESP32("esp32"),
    ESP8266("esp8266");

    companion object {
        val DEFAULT = ESP32

        fun fromKey(key: String?): Board = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}
```

```kotlin
    val board: Board
        get() = Board.fromKey(prefs.getString(KEY_BOARD, null))

    val showSymbolBar: Boolean
        get() = prefs.getBoolean(KEY_SHOW_SYMBOL_BAR, true)
```

  `values/arrays.xml`:

```xml
    <string-array name="board_entries">
        <item>@string/board_esp32</item>
        <item>@string/board_esp8266</item>
    </string-array>
    <!-- Must match Board keys in settings/AppSettings.kt -->
    <string-array name="board_values" translatable="false">
        <item>esp32</item>
        <item>esp8266</item>
    </string-array>
```

  `strings.xml`:

```xml
    <string name="settings_category_board">Board</string>
    <string name="settings_board">Board</string>
    <string name="settings_board_summary">%1$s · used for examples, templates and autocomplete only</string>
    <string name="settings_show_symbol_bar">Symbol bar</string>
    <string name="settings_show_symbol_bar_summary">Show a row of code symbols above the keyboard</string>
```

  `preferences.xml`: a new category before Editor, and a switch in Editor:

```xml
    <PreferenceCategory
        app:iconSpaceReserved="false"
        app:title="@string/settings_category_board">
        <ListPreference
            app:defaultValue="esp32"
            app:entries="@array/board_entries"
            app:entryValues="@array/board_values"
            app:iconSpaceReserved="false"
            app:key="board"
            app:title="@string/settings_board" />
    </PreferenceCategory>
```

```xml
        <SwitchPreferenceCompat
            app:defaultValue="true"
            app:iconSpaceReserved="false"
            app:key="show_symbol_bar"
            app:summary="@string/settings_show_symbol_bar_summary"
            app:title="@string/settings_show_symbol_bar" />
```

  `SettingsFragment.onCreatePreferences`: the summary shows the current board
  plus the "not for building" note (`useSimpleSummaryProvider` would drop the
  note):

```kotlin
        findPreference<ListPreference>(KEY_BOARD)?.setSummaryProvider {
            val pref = it as ListPreference
            getString(R.string.settings_board_summary, pref.entry ?: getString(R.string.board_esp32))
        }
```

- [ ] **Step 4: Run tests, expect PASS.** Then commit:

```bash
git add app/src/main app/src/test
git commit -m "Add board and symbol-bar settings

Co-Authored-By: ...trailer..."
```

---

### Task 2: `ArduinoApi` keyword lists (TDD)

**Files:** `editor/ArduinoApi.kt`, `ArduinoApiTest.kt`.

Design rule: `core` holds every name both boards share; `esp32` and `esp8266`
hold only names specific to that board, so the three lists are pairwise
disjoint. `keywordsFor` returns core + the board's list, distinct and sorted.
Every entry must be a plain identifier (sora's completer matches identifiers).

- [ ] **Step 1: Write the failing test**

```kotlin
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
```

- [ ] **Step 2: Run, expect FAIL** (`Unresolved reference 'ArduinoApi'`).

- [ ] **Step 3: Implement** `editor/ArduinoApi.kt`. The lists are: `core` (Arduino
  language functions/constants/types, `Serial`/`Wire`/`SPI`/`String` members,
  WiFi/HTTP/EEPROM/LittleFS/mDNS/OTA names shared by both cores), `esp32`
  (LEDC, touch, DAC, FreeRTOS, sleep, `Preferences`, `WebServer`, `D`-less
  pins), `esp8266` (`ESP8266*` classes, `A0`/`D0`–`D8`, `ESP` sleep/reset
  members, `ICACHE_RAM_ATTR`). The full source is in
  `app/src/main/java/org/espsketchide/app/editor/ArduinoApi.kt`.

- [ ] **Step 4: Run tests, expect PASS.** Commit: `Add Arduino/ESP autocomplete keyword lists`.

---

### Task 3: Feed the keywords to the language

**Files:** `editor/EditorLanguages.kt`, `EditorActivity.kt` (call sites),
`androidTest/.../AutocompleteKeywordsTest.kt`.

- [ ] **Step 1: `languageFor` takes the board.**

```kotlin
    /** Highlighted Arduino/C++ language for sketch sources, plain text otherwise. Call after [awaitReady] returned true. */
    fun languageFor(fileName: String, board: Board): Language {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        if (extension !in HIGHLIGHTED_EXTENSIONS) return EmptyLanguage()
        return TextMateLanguage.create(ARDUINO_SCOPE, true).apply {
            tabSize = TAB_SIZE
            useTab(false)
            setCompleterKeywords(ArduinoApi.keywordsFor(board).toTypedArray())
        }
    }
```

  Import `org.espsketchide.app.settings.Board`. Update the two call sites in
  `EditorActivity` (Task 5 rewrites them).

- [ ] **Step 2: Instrumented test** (`AutocompleteKeywordsTest`): after
  `EditorLanguages.awaitReady`, `languageFor("a.ino", ESP32)` is a
  `TextMateLanguage` whose `autoCompleter.keywords` contains `ledcWrite` and
  not `D1`; the ESP8266 language the reverse; `languageFor("notes.txt", ...)` is
  an `EmptyLanguage`. Compile it with `./gradlew :app:compileDebugAndroidTestKotlin`.

- [ ] **Step 3: Commit**: `Wire board keyword lists into the editor language`.

---

### Task 4: Pure helpers (TDD): `DirtyTracker`, `SymbolBarSymbols`, `SearchCounter`

**Files:** the three `editor/*.kt` sources and their JVM tests.

- [ ] **Step 1: Tests first.**

```kotlin
class DirtyTrackerTest {
    @Test
    fun `mark and clean report whether state changed`() {
        val t = DirtyTracker<String>()
        assertFalse(t.isDirty("a"))
        assertTrue(t.markDirty("a"))
        assertFalse(t.markDirty("a"))
        assertTrue(t.isDirty("a"))
        assertTrue(t.markClean("a"))
        assertFalse(t.markClean("a"))
        assertFalse(t.isDirty("a"))
    }
}

class SymbolBarSymbolsTest {
    @Test
    fun `display and insert arrays line up`() {
        assertEquals(SymbolBarSymbols.display.size, SymbolBarSymbols.insert.size)
    }

    @Test
    fun `first symbol is the tab key and the rest insert themselves`() {
        assertEquals("\t", SymbolBarSymbols.insert[0])
        for (i in 1 until SymbolBarSymbols.display.size) {
            assertEquals(SymbolBarSymbols.display[i], SymbolBarSymbols.insert[i])
        }
    }

    @Test
    fun `contains the symbols sketches need most`() {
        listOf("{", "}", "(", ")", ";", "#", "\"", "[", "]").forEach {
            assertTrue("$it missing", it in SymbolBarSymbols.insert)
        }
    }
}

class SearchCounterTest {
    @Test
    fun `no matches shows the supplied text`() {
        assertEquals("0 results", SearchCounter.text(-1, 0, "0 results"))
    }

    @Test
    fun `selected match is shown one-based`() {
        assertEquals("3/12", SearchCounter.text(2, 12, "0 results"))
    }

    @Test
    fun `matches but none selected shows a dash`() {
        assertEquals("-/12", SearchCounter.text(-1, 12, "0 results"))
    }
}
```

- [ ] **Step 2: Run, expect compile failures.**

- [ ] **Step 3: Implement.**

```kotlin
/** Which files have edits that are not written to disk yet. Reports whether a call changed anything. */
class DirtyTracker<K> {
    private val dirty = mutableSetOf<K>()

    fun isDirty(key: K): Boolean = key in dirty
    fun markDirty(key: K): Boolean = dirty.add(key)
    fun markClean(key: K): Boolean = dirty.remove(key)
}

/** Symbols on the bar above the keyboard. Index 0 is Tab: sora turns "\t" into indentOrCommitTab(). */
object SymbolBarSymbols {
    val display = arrayOf(
        "⇥", "{", "}", "(", ")", ";", "#", "<", ">", "\"", "'", "=", "+", "-", "*", "/",
        "&", "|", "!", "[", "]", ",", ".", "_", ":",
    )
    val insert = arrayOf("\t") + display.drop(1)
}

object SearchCounter {
    /** [index] is EditorSearcher's zero-based current match, -1 when none is selected. */
    fun text(index: Int, count: Int, noResults: String): String = when {
        count <= 0 -> noResults
        index < 0 -> "-/$count"
        else -> "${index + 1}/$count"
    }
}
```

- [ ] **Step 4: Run tests, expect PASS.** Commit: `Add pure helpers for dirty tracking, symbol bar and search counter`.

---

### Task 5: Editor UI: layout, menu, strings

**Files:** `activity_editor.xml`, `view_search_panel.xml`, `editor_toolbar.xml`,
`strings.xml`.

- [ ] **Step 1: Strings.**

```xml
    <string name="action_undo">Undo</string>
    <string name="action_redo">Redo</string>
    <string name="action_search">Find and replace</string>
    <string name="nothing_to_save">No changes</string>
    <string name="search_hint">Find</string>
    <string name="replace_hint">Replace with</string>
    <string name="search_no_results">0 results</string>
    <string name="search_match_case">Match case</string>
    <string name="search_previous">Previous match</string>
    <string name="search_next">Next match</string>
    <string name="search_close">Close search</string>
    <string name="search_toggle_replace">Show replace</string>
    <string name="action_replace">Replace</string>
    <string name="action_replace_all">Replace all</string>
```

- [ ] **Step 2: Toolbar menu** (order = overflow priority): Undo, Redo, Search,
  Save (all `ifRoom`), then Add file and Settings (`never`).

- [ ] **Step 3: `view_search_panel.xml`**: a vertical `LinearLayout`
  (`esp_bar` background, `GONE`) with the search row (field, counter, previous,
  next, `Aa` toggle, expand, close) and a `GONE` replace row (field, Replace,
  Replace all), plus a 1 dp divider.

- [ ] **Step 4: `activity_editor.xml`:** `<include layout="@layout/view_search_panel"
  android:id="@+id/searchPanel"/>` after the AppBarLayout; below the editor a
  `HorizontalScrollView` (`symbolBarScroll`, `esp_bar` background) holding a
  `SymbolInputView` (`symbolBar`).

- [ ] **Step 5: Build** (`:app:assembleDebug`); commit
  `Editor layout: search panel, symbol bar, reorganized toolbar`.

---

### Task 6: `SearchController`

**File:** `editor/SearchController.kt`. Owns the panel's behavior:

- Typing or toggling `Aa` runs `searcher.search(query, SearchOptions(TYPE_NORMAL, !matchCase))`;
  an empty query calls `stopSearch()`.
- `PublishSearchResultEvent` updates the counter via `SearchCounter`, and on the
  first result after a query change jumps to the first match (`gotoNext`).
- Previous/next call `gotoPrevious()/gotoNext()`; Replace calls
  `replaceThis(text)`; Replace all calls `replaceAll(text)`.
- `show()` reveals the panel, prefills a single-line selection, focuses the
  field and opens the keyboard; `hide()` stops the search (which clears the
  highlights) and closes the keyboard. `refresh()` re-runs the search (after a
  different file loads).
- Regex search is out of scope: type is always `TYPE_NORMAL`.

- [ ] Write it, build, commit `Add find/replace controller`.

---

### Task 7: Wire it into `EditorActivity`

- [ ] `dirtyFiles = DirtyTracker<Uri>()`, `loading` flag, `subscribeEvent(ContentChangeEvent)`
  marking dirty and refreshing the undo/redo state.
- [ ] `saveCurrentFile(): Boolean` writes only when dirty, then clears the flag
  and label. The Save action shows `saved_toast` or `nothing_to_save`.
- [ ] Tab labels use `editor_unsaved_indicator` for dirty files.
- [ ] Undo/Redo menu items call `undo()/redo()`; enabled state (icon alpha too)
  follows `canUndo()/canRedo()` after every change and each load.
- [ ] Search menu item toggles `SearchController`; back closes it first.
- [ ] Symbol bar: `bindEditor`, `addSymbols`, themed colors and mono font;
  visibility follows `settings.showSymbolBar` in `onResume`.
- [ ] `languageFor(file.name, settings.board)` at both call sites.
- [ ] Build, unit tests, commit `Editor: undo/redo, find/replace, symbol bar, unsaved tracking`.

---

### Task 8: Docs and full verification

- [ ] README "What works today" and CLAUDE.md architecture notes.
- [ ] `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lint :app:compileDebugAndroidTestKotlin`
- [ ] With an emulator: `./gradlew :app:connectedDebugAndroidTest` (uninstalls the app afterwards).
- [ ] Manual pass on a device (not automatable here): type and see the `•`
  marker appear/clear on Save; undo/redo enable states; find next/prev, case
  toggle, replace, replace all; symbol bar taps incl. `⇥` (two spaces); board
  switch to ESP8266 then reopen a file and complete `D` / `ESP8266`; symbol bar
  off in Settings; light and dark; rotate while the search panel is open (the panel closes; unsaved edits are saved in `onPause`).
- [ ] Commit `Document Phase 2`.

---

## Appendix: final sources as committed

The tasks above describe the work; these are the exact files that were
implemented and verified (unit tests, `assembleDebug`, `lint`,
`compileDebugAndroidTestKotlin`). Where a task above only summarizes a file, this
is its complete code.

### `app/src/main/java/org/espsketchide/app/editor/ArduinoApi.kt`

```kotlin
package org.espsketchide.app.editor

import org.espsketchide.app.settings.Board

/**
 * Names offered by autocomplete on top of the identifiers already in the file. [core] holds what
 * both cores share; [esp32] and [esp8266] hold only what is specific to that board, so the three
 * lists never overlap (pinned by ArduinoApiTest). Every entry must be a plain identifier because
 * sora's completer matches identifiers. Targets ESP32 Arduino core 3.x and ESP8266 core 3.x.
 */
object ArduinoApi {

    val core: List<String> = words(
        """
        setup loop yield
        pinMode digitalWrite digitalRead analogRead analogWrite
        delay delayMicroseconds millis micros
        attachInterrupt detachInterrupt digitalPinToInterrupt interrupts noInterrupts
        tone noTone pulseIn shiftIn shiftOut
        map constrain min max abs pow sqrt sq sin cos tan random randomSeed
        lowByte highByte bitRead bitWrite bitSet bitClear bit
        isDigit isAlpha isAlphaNumeric isSpace isUpperCase isLowerCase isPunct isHexadecimalDigit
        HIGH LOW INPUT OUTPUT INPUT_PULLUP LED_BUILTIN LSBFIRST MSBFIRST CHANGE RISING FALLING
        PI HALF_PI TWO_PI DEG_TO_RAD RAD_TO_DEG EULER
        String boolean byte word size_t
        uint8_t uint16_t uint32_t uint64_t int8_t int16_t int32_t int64_t
        Serial begin end available read peek write flush print println printf
        setTimeout readString readStringUntil parseInt parseFloat
        Wire beginTransmission endTransmission requestFrom SPI
        length substring indexOf toInt toFloat toCharArray c_str trim replace
        startsWith endsWith equals concat toUpperCase toLowerCase
        WiFi WiFiClient WiFiClientSecure WiFiServer WiFiUDP IPAddress
        status localIP softAP softAPIP scanNetworks SSID RSSI disconnect macAddress mode
        setHostname gatewayIP subnetMask dnsIP
        WL_CONNECTED WL_IDLE_STATUS WL_NO_SSID_AVAIL WL_CONNECT_FAILED WL_DISCONNECTED
        WIFI_STA WIFI_AP WIFI_AP_STA WIFI_OFF
        HTTPClient GET POST addHeader getString getSize HTTP_CODE_OK
        configTime MDNS ArduinoOTA LittleFS EEPROM commit
        ESP restart getFreeHeap
        """
    )

    val esp32: List<String> = words(
        """
        ledcAttach ledcAttachChannel ledcDetach ledcWrite ledcRead ledcReadFreq ledcWriteTone ledcWriteNote
        touchRead touchAttachInterrupt dacWrite temperatureRead
        analogReadResolution analogSetAttenuation analogReadMilliVolts
        ADC_0db ADC_2_5db ADC_6db ADC_11db
        INPUT_PULLDOWN OUTPUT_OPEN_DRAIN IRAM_ATTR
        xTaskCreate xTaskCreatePinnedToCore vTaskDelay vTaskDelete pdMS_TO_TICKS portTICK_PERIOD_MS
        xSemaphoreCreateMutex xSemaphoreTake xSemaphoreGive xQueueCreate xQueueSend xQueueReceive
        esp_sleep_enable_timer_wakeup esp_sleep_enable_ext0_wakeup esp_sleep_enable_ext1_wakeup
        esp_sleep_get_wakeup_cause esp_deep_sleep_start esp_light_sleep_start esp_restart esp_random
        esp_err_t ESP_OK
        WebServer WiFiMulti Preferences getLocalTime SPIFFS
        putInt getInt putUInt getUInt putBool getBool putFloat getFloat putString isKey remove clear
        getChipModel getCpuFreqMHz getFlashChipSize getEfuseMac
        """
    )

    val esp8266: List<String> = words(
        """
        ESP8266WiFi ESP8266WebServer ESP8266WiFiMulti ESP8266mDNS
        A0 D0 D1 D2 D3 D4 D5 D6 D7 D8
        analogWriteRange analogWriteFreq ICACHE_RAM_ATTR
        deepSleep deepSleepInstant getChipId getResetReason reset wdtFeed wdtEnable getCoreVersion
        setSleepMode WIFI_NONE_SLEEP WIFI_LIGHT_SLEEP WIFI_MODEM_SLEEP
        """
    )

    /** [core] plus the board's own names, unique and sorted. */
    fun keywordsFor(board: Board): List<String> {
        val specific = when (board) {
            Board.ESP32 -> esp32
            Board.ESP8266 -> esp8266
        }
        return (core + specific).distinct().sorted()
    }

    private fun words(block: String): List<String> = block.split(Regex("\\s+")).filter { it.isNotEmpty() }
}
```

### `app/src/main/java/org/espsketchide/app/editor/SearchController.kt`

```kotlin
package org.espsketchide.app.editor

import android.content.Context
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.widget.doAfterTextChanged
import io.github.rosemoe.sora.event.PublishSearchResultEvent
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import org.espsketchide.app.R
import org.espsketchide.app.databinding.ViewSearchPanelBinding

/**
 * Behavior of the find/replace panel over sora's [EditorSearcher]. Plain-text search only
 * (regex is out of scope). The searcher works on a background thread and announces results with
 * [PublishSearchResultEvent], which is where the match counter is updated.
 */
class SearchController(
    private val panel: ViewSearchPanelBinding,
    private val editor: CodeEditor,
    private val onVisibilityChanged: (Boolean) -> Unit,
) {
    private var jumpToFirstResult = false

    val isVisible: Boolean get() = panel.root.visibility == View.VISIBLE

    init {
        panel.searchField.doAfterTextChanged { runSearch() }
        panel.searchField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                editor.searcher.gotoNext()
                updateCounter()
                true
            } else {
                false
            }
        }
        panel.searchMatchCase.addOnCheckedChangeListener { _, _ -> runSearch() }
        panel.searchNext.setOnClickListener { step { editor.searcher.gotoNext() } }
        panel.searchPrevious.setOnClickListener { step { editor.searcher.gotoPrevious() } }
        panel.searchToggleReplace.setOnClickListener {
            panel.replaceRow.visibility = if (panel.replaceRow.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        panel.replaceOne.setOnClickListener {
            if (editor.searcher.hasQuery()) {
                editor.searcher.replaceThis(panel.replaceField.text.toString())
                updateCounter()
            }
        }
        panel.replaceAll.setOnClickListener {
            if (editor.searcher.hasQuery()) {
                editor.searcher.replaceAll(panel.replaceField.text.toString()) { updateCounter() }
            }
        }
        panel.searchClose.setOnClickListener { hide() }
        editor.subscribeEvent(PublishSearchResultEvent::class.java) { _, _ ->
            if (jumpToFirstResult && editor.searcher.matchedPositionCount > 0) {
                jumpToFirstResult = false
                editor.searcher.gotoNext()
            }
            updateCounter()
        }
    }

    /** Shows the panel, prefilled with a single-line selection, and opens the keyboard. */
    fun show() {
        panel.root.visibility = View.VISIBLE
        val cursor = editor.cursor
        if (cursor.isSelected) {
            val selected = editor.text.substring(cursor.left, cursor.right)
            if ('\n' !in selected && selected.length <= MAX_PREFILL) {
                panel.searchField.setText(selected)
            }
        }
        panel.searchField.requestFocus()
        panel.searchField.setSelection(panel.searchField.text.length)
        inputMethods().showSoftInput(panel.searchField, InputMethodManager.SHOW_IMPLICIT)
        onVisibilityChanged(true)
        runSearch()
    }

    /** Hides the panel and stops the search, which clears the highlights. */
    fun hide() {
        editor.searcher.stopSearch()
        jumpToFirstResult = false
        inputMethods().hideSoftInputFromWindow(panel.root.windowToken, 0)
        panel.root.visibility = View.GONE
        editor.requestFocus()
        onVisibilityChanged(false)
    }

    fun toggle() = if (isVisible) hide() else show()

    /** Re-runs the current query, e.g. after another file was loaded into the editor. */
    fun refresh() {
        if (isVisible) runSearch()
    }

    private fun runSearch() {
        val query = panel.searchField.text.toString()
        if (query.isEmpty()) {
            editor.searcher.stopSearch()
            jumpToFirstResult = false
            panel.searchCount.text = ""
            return
        }
        jumpToFirstResult = true
        val caseInsensitive = !panel.searchMatchCase.isChecked
        editor.searcher.search(query, EditorSearcher.SearchOptions(EditorSearcher.SearchOptions.TYPE_NORMAL, caseInsensitive))
        updateCounter()
    }

    private fun step(move: () -> Boolean) {
        if (editor.searcher.hasQuery()) {
            move()
            updateCounter()
        }
    }

    private fun updateCounter() {
        val searcher = editor.searcher
        panel.searchCount.text = if (searcher.hasQuery()) {
            SearchCounter.text(
                searcher.currentMatchedPositionIndex,
                searcher.matchedPositionCount,
                panel.root.context.getString(R.string.search_no_results),
            )
        } else {
            ""
        }
    }

    private fun inputMethods() = panel.root.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    private companion object {
        const val MAX_PREFILL = 100
    }
}
```

### `app/src/main/res/layout/view_search_panel.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- Find/replace panel; driven by editor/SearchController. Hidden until the Search action is used. -->
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:background="@color/esp_bar"
    android:orientation="vertical"
    android:theme="@style/ThemeOverlay.EspSketchIDE.Bar"
    android:visibility="gone">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:gravity="center_vertical"
        android:orientation="horizontal"
        android:paddingStart="12dp"
        android:paddingEnd="4dp">

        <EditText
            android:id="@+id/searchField"
            android:layout_width="0dp"
            android:layout_height="48dp"
            android:layout_weight="1"
            android:autofillHints="none"
            android:background="@null"
            android:hint="@string/search_hint"
            android:imeOptions="actionSearch"
            android:importantForAutofill="no"
            android:inputType="text"
            android:maxLines="1"
            android:textColor="@color/esp_on_bar"
            android:textColorHint="@color/esp_on_bar_muted"
            android:textSize="14sp" />

        <TextView
            android:id="@+id/searchCount"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginEnd="4dp"
            android:textColor="@color/esp_on_bar_muted"
            android:textSize="12sp" />

        <ImageButton
            android:id="@+id/searchPrevious"
            android:layout_width="40dp"
            android:layout_height="48dp"
            android:background="?attr/selectableItemBackgroundBorderless"
            android:contentDescription="@string/search_previous"
            android:src="@drawable/ic_keyboard_arrow_up"
            app:tint="@color/esp_on_bar" />

        <ImageButton
            android:id="@+id/searchNext"
            android:layout_width="40dp"
            android:layout_height="48dp"
            android:background="?attr/selectableItemBackgroundBorderless"
            android:contentDescription="@string/search_next"
            android:src="@drawable/ic_keyboard_arrow_down"
            app:tint="@color/esp_on_bar" />

        <com.google.android.material.button.MaterialButton
            android:id="@+id/searchMatchCase"
            style="@style/Widget.Material3.Button.TextButton"
            android:layout_width="40dp"
            android:layout_height="36dp"
            android:contentDescription="@string/search_match_case"
            android:insetLeft="0dp"
            android:insetRight="0dp"
            android:minWidth="0dp"
            android:padding="0dp"
            android:text="@string/search_match_case_label"
            android:textAllCaps="false"
            android:textColor="@color/search_toggle_text"
            android:textSize="13sp"
            app:backgroundTint="@color/search_toggle_bg"
            app:cornerRadius="6dp" />

        <ImageButton
            android:id="@+id/searchToggleReplace"
            android:layout_width="40dp"
            android:layout_height="48dp"
            android:background="?attr/selectableItemBackgroundBorderless"
            android:contentDescription="@string/search_toggle_replace"
            android:src="@drawable/ic_expand_more"
            app:tint="@color/esp_on_bar" />

        <ImageButton
            android:id="@+id/searchClose"
            android:layout_width="40dp"
            android:layout_height="48dp"
            android:background="?attr/selectableItemBackgroundBorderless"
            android:contentDescription="@string/search_close"
            android:src="@drawable/ic_close"
            app:tint="@color/esp_on_bar" />
    </LinearLayout>

    <LinearLayout
        android:id="@+id/replaceRow"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:gravity="center_vertical"
        android:orientation="horizontal"
        android:paddingStart="12dp"
        android:paddingEnd="4dp"
        android:visibility="gone">

        <EditText
            android:id="@+id/replaceField"
            android:layout_width="0dp"
            android:layout_height="48dp"
            android:layout_weight="1"
            android:autofillHints="none"
            android:background="@null"
            android:hint="@string/replace_hint"
            android:importantForAutofill="no"
            android:inputType="text"
            android:maxLines="1"
            android:textColor="@color/esp_on_bar"
            android:textColorHint="@color/esp_on_bar_muted"
            android:textSize="14sp" />

        <Button
            android:id="@+id/replaceOne"
            style="@style/Widget.Material3.Button.TextButton"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="@string/action_replace"
            android:textAllCaps="false" />

        <Button
            android:id="@+id/replaceAll"
            style="@style/Widget.Material3.Button.TextButton"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="@string/action_replace_all"
            android:textAllCaps="false" />
    </LinearLayout>

    <View
        android:layout_width="match_parent"
        android:layout_height="1dp"
        android:background="@color/esp_divider" />
</LinearLayout>
```

### `app/src/main/res/layout/activity_editor.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical">

    <com.google.android.material.appbar.AppBarLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:background="@color/esp_bar"
        android:theme="@style/ThemeOverlay.EspSketchIDE.Bar"
        app:elevation="0dp"
        app:liftOnScroll="false">

        <com.google.android.material.appbar.MaterialToolbar
            android:id="@+id/toolbar"
            android:layout_width="match_parent"
            android:layout_height="?attr/actionBarSize" />

        <com.google.android.material.tabs.TabLayout
            android:id="@+id/fileTabLayout"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:background="@color/esp_bar"
            app:tabGravity="start"
            app:tabIndicatorColor="@color/esp_accent"
            app:tabIndicatorFullWidth="true"
            app:tabMinWidth="72dp"
            app:tabMode="scrollable"
            app:tabSelectedTextColor="@color/esp_on_bar"
            app:tabTextAppearance="@style/TextAppearance.EspSketchIDE.Tab"
            app:tabTextColor="@color/esp_on_bar_muted" />

        <View
            android:layout_width="match_parent"
            android:layout_height="1dp"
            android:background="@color/esp_divider" />

    </com.google.android.material.appbar.AppBarLayout>

    <include
        android:id="@+id/searchPanel"
        layout="@layout/view_search_panel" />

    <io.github.rosemoe.sora.widget.CodeEditor
        android:id="@+id/codeEditor"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1" />

    <View
        android:layout_width="match_parent"
        android:layout_height="1dp"
        android:background="@color/esp_divider" />

    <HorizontalScrollView
        android:id="@+id/symbolBarScroll"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:background="@color/esp_bar"
        android:scrollbars="none">

        <io.github.rosemoe.sora.widget.SymbolInputView
            android:id="@+id/symbolBar"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content" />
    </HorizontalScrollView>

</LinearLayout>
```

### `app/src/main/res/menu/editor_toolbar.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- Order is overflow priority: with little room, later ifRoom items fall into the ⋮ menu first. -->
<menu xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto">
    <item
        android:id="@+id/action_undo"
        android:icon="@drawable/ic_undo"
        android:title="@string/action_undo"
        app:showAsAction="ifRoom" />
    <item
        android:id="@+id/action_redo"
        android:icon="@drawable/ic_redo"
        android:title="@string/action_redo"
        app:showAsAction="ifRoom" />
    <item
        android:id="@+id/action_search"
        android:icon="@drawable/ic_search"
        android:title="@string/action_search"
        app:showAsAction="ifRoom" />
    <item
        android:id="@+id/action_save"
        android:icon="@drawable/ic_save"
        android:title="@string/action_save"
        app:showAsAction="ifRoom" />
    <item
        android:id="@+id/action_add_file"
        android:title="@string/action_add_file"
        app:showAsAction="never" />
    <item
        android:id="@+id/action_settings"
        android:title="@string/action_settings"
        app:showAsAction="never" />
</menu>
```

### `app/src/main/java/org/espsketchide/app/EditorActivity.kt`

```kotlin
package org.espsketchide.app

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.tabs.TabLayout
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula
import io.github.rosemoe.sora.widget.schemes.SchemeGitHub
import org.espsketchide.app.data.SketchAlreadyExistsException
import org.espsketchide.app.data.SketchNameInvalidException
import org.espsketchide.app.data.SketchRepository
import org.espsketchide.app.databinding.ActivityEditorBinding
import org.espsketchide.app.editor.DirtyTracker
import org.espsketchide.app.editor.EditorLanguages
import org.espsketchide.app.editor.SearchController
import org.espsketchide.app.editor.SymbolBarSymbols
import org.espsketchide.app.model.Sketch
import org.espsketchide.app.model.SketchFile
import org.espsketchide.app.settings.AppSettings
import org.espsketchide.app.settings.SettingsActivity

class EditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditorBinding
    private lateinit var repository: SketchRepository
    private lateinit var settings: AppSettings
    private lateinit var sketch: Sketch

    private var openFiles: List<SketchFile> = emptyList()
    private var currentFile: SketchFile? = null
    private var highlightingAvailable = false

    private lateinit var searchController: SearchController
    private var undoItem: MenuItem? = null
    private var redoItem: MenuItem? = null

    /** Files whose editor text is newer than what is on disk. Shown as "name •" in the tab. */
    private val dirtyFiles = DirtyTracker<Uri>()

    /** True while [loadFile] replaces the editor text, so that is not counted as an edit. */
    private var loading = false

    private val closeSearchOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = searchController.hide()
    }

    private val fileTabListener = object : TabLayout.OnTabSelectedListener {
        override fun onTabSelected(tab: TabLayout.Tab) {
            saveCurrentFile()
            loadFile(openFiles[tab.position])
        }

        override fun onTabUnselected(tab: TabLayout.Tab) = Unit
        override fun onTabReselected(tab: TabLayout.Tab) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val sketchName = intent.getStringExtra(EXTRA_SKETCH_NAME)
        val folderUri = IntentCompat.getParcelableExtra(intent, EXTRA_SKETCH_FOLDER_URI, Uri::class.java)
        if (sketchName == null || folderUri == null) {
            finish()
            return
        }
        sketch = Sketch(sketchName, folderUri)
        repository = SketchRepository(this)
        settings = AppSettings(this)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = sketch.name

        onBackPressedDispatcher.addCallback(this, closeSearchOnBack)
        setupEditor()
        setupSymbolBar()
        searchController = SearchController(binding.searchPanel, binding.codeEditor) { visible ->
            closeSearchOnBack.isEnabled = visible
        }
        setupFileTabs()

        if (savedInstanceState == null) {
            Toast.makeText(this, R.string.roadmap_notice, Toast.LENGTH_LONG).show()
        }
    }

    private fun setupEditor() {
        val isDarkMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        // Plain colors until highlighting is ready. Usually it already is, and whenReady
        // replaces them before the first frame.
        binding.codeEditor.colorScheme = if (isDarkMode) SchemeDarcula() else SchemeGitHub()
        val mono = ResourcesCompat.getFont(this, R.font.jetbrains_mono_regular) ?: Typeface.MONOSPACE
        binding.codeEditor.setTypefaceText(mono)
        binding.codeEditor.setTypefaceLineNumber(mono)
        binding.codeEditor.setTabWidth(2)
        binding.codeEditor.subscribeEvent(ContentChangeEvent::class.java) { event, _ ->
            val file = currentFile
            val isEdit = !loading && event.action != ContentChangeEvent.ACTION_SET_NEW_TEXT
            if (file != null && isEdit && dirtyFiles.markDirty(file.uri)) {
                refreshTabLabel(file)
            }
            updateUndoRedo()
        }
        EditorLanguages.whenReady(this) { ok -> onHighlightingReady(ok, isDarkMode) }
    }

    private fun setupSymbolBar() {
        val bar = binding.symbolBar
        bar.bindEditor(binding.codeEditor)
        bar.addSymbols(SymbolBarSymbols.display, SymbolBarSymbols.insert)
        val mono = ResourcesCompat.getFont(this, R.font.jetbrains_mono_regular) ?: Typeface.MONOSPACE
        val color = ContextCompat.getColor(this, R.color.esp_on_bar)
        val ripple = TypedValue().also { theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true) }
        val minWidth = (SYMBOL_MIN_WIDTH_DP * resources.displayMetrics.density).toInt()
        bar.setTextColor(color)
        bar.forEachButton { button ->
            button.typeface = mono
            button.setTextColor(color)
            button.isAllCaps = false
            button.setBackgroundResource(ripple.resourceId)
            button.minWidth = 0
            button.minimumWidth = minWidth
        }
    }

    /** Switches on TextMate highlighting; runs on the main thread, possibly after the first file loaded. */
    private fun onHighlightingReady(ok: Boolean, isDarkMode: Boolean) {
        if (!ok || isDestroyed) return
        highlightingAvailable = true
        binding.codeEditor.colorScheme = EditorLanguages.colorScheme(isDarkMode)
        currentFile?.let { binding.codeEditor.setEditorLanguage(EditorLanguages.languageFor(it.name, settings.board)) }
    }

    /** Font size and word wrap can change in Settings while this screen is in the back stack. */
    private fun applyEditorSettings() {
        binding.codeEditor.setTextSize(settings.editorFontSize.toFloat())
        binding.codeEditor.setWordwrap(settings.wordWrap)
        binding.symbolBarScroll.visibility = if (settings.showSymbolBar) View.VISIBLE else View.GONE
    }

    private fun setupFileTabs() {
        openFiles = repository.listFiles(sketch)
        // Detach while rebuilding so the auto-selection of the first added tab doesn't
        // trigger a save/load, and so the listener is never registered more than once.
        binding.fileTabLayout.removeOnTabSelectedListener(fileTabListener)
        binding.fileTabLayout.removeAllTabs()
        openFiles.forEach { file ->
            binding.fileTabLayout.addTab(binding.fileTabLayout.newTab().setText(tabLabel(file)))
        }
        binding.fileTabLayout.addOnTabSelectedListener(fileTabListener)
        if (openFiles.isNotEmpty()) {
            loadFile(openFiles.first())
        }
    }

    private fun loadFile(file: SketchFile) {
        currentFile = file
        loading = true
        try {
            binding.codeEditor.setEditorLanguage(
                if (highlightingAvailable) EditorLanguages.languageFor(file.name, settings.board) else EmptyLanguage()
            )
            // A fresh Content also starts a fresh undo history.
            binding.codeEditor.setText(repository.readFile(file.uri))
        } finally {
            loading = false
        }
        updateUndoRedo()
        searchController.refresh()
    }

    /** Writes the current file only if it has unsaved edits. Returns whether it wrote anything. */
    private fun saveCurrentFile(): Boolean {
        val file = currentFile ?: return false
        if (!dirtyFiles.isDirty(file.uri)) return false
        repository.writeFile(file.uri, binding.codeEditor.text.toString())
        dirtyFiles.markClean(file.uri)
        refreshTabLabel(file)
        return true
    }

    private fun tabLabel(file: SketchFile): String =
        if (dirtyFiles.isDirty(file.uri)) getString(R.string.editor_unsaved_indicator, file.name) else file.name

    private fun refreshTabLabel(file: SketchFile) {
        val index = openFiles.indexOfFirst { it.uri == file.uri }
        if (index >= 0) binding.fileTabLayout.getTabAt(index)?.text = tabLabel(file)
    }

    private fun updateUndoRedo() {
        undoItem?.let { setEnabledDimmed(it, binding.codeEditor.canUndo()) }
        redoItem?.let { setEnabledDimmed(it, binding.codeEditor.canRedo()) }
    }

    /** Toolbar icons are tinted by the theme, so a disabled one has to be dimmed by hand. */
    private fun setEnabledDimmed(item: MenuItem, enabled: Boolean) {
        item.isEnabled = enabled
        item.icon?.mutate()?.alpha = if (enabled) ALPHA_ENABLED else ALPHA_DISABLED
    }

    override fun onResume() {
        super.onResume()
        applyEditorSettings()
    }

    override fun onPause() {
        super.onPause()
        saveCurrentFile()
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.codeEditor.release()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.editor_toolbar, menu)
        undoItem = menu.findItem(R.id.action_undo)
        redoItem = menu.findItem(R.id.action_redo)
        updateUndoRedo()
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_undo -> {
                binding.codeEditor.undo()
                true
            }
            R.id.action_redo -> {
                binding.codeEditor.redo()
                true
            }
            R.id.action_search -> {
                searchController.toggle()
                true
            }
            R.id.action_save -> {
                val message = if (saveCurrentFile()) R.string.saved_toast else R.string.nothing_to_save
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                true
            }
            R.id.action_add_file -> {
                promptAddFile()
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun promptAddFile() {
        val input = EditText(this)
        AlertDialog.Builder(this)
            .setTitle(R.string.action_add_file)
            .setView(input)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                addFile(input.text.toString().trim())
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun addFile(fileName: String) {
        try {
            saveCurrentFile()
            repository.addFile(sketch, fileName)
            setupFileTabs()
            val newIndex = openFiles.indexOfFirst { it.name == fileName }
            if (newIndex >= 0) {
                binding.fileTabLayout.getTabAt(newIndex)?.select()
            }
        } catch (e: SketchNameInvalidException) {
            Toast.makeText(this, R.string.error_invalid_file_name, Toast.LENGTH_LONG).show()
        } catch (e: SketchAlreadyExistsException) {
            Toast.makeText(this, getString(R.string.error_file_exists, fileName), Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val EXTRA_SKETCH_NAME = "extra_sketch_name"
        const val EXTRA_SKETCH_FOLDER_URI = "extra_sketch_folder_uri"
        private const val ALPHA_ENABLED = 255
        private const val ALPHA_DISABLED = 90
        private const val SYMBOL_MIN_WIDTH_DP = 40
    }
}
```
