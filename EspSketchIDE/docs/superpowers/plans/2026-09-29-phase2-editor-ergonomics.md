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
  off in Settings; light and dark; rotate while the search panel is open.
- [ ] Commit `Document Phase 2`.
