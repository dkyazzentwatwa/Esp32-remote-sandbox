# IDE editor overhaul — design

Date: 2026-09-27
Status: approved in brainstorming, pending spec review

## Goal

Make EspSketchIDE look and feel like a real mobile Arduino IDE (the bar set by
ArduinoDroid) without adding any UI for capabilities the app doesn't have yet.
Three gaps are in scope: IDE look & feel, editor ergonomics, and built-in content.

The existing flow (sketch list → editor) stays. A single-screen workspace with a
drawer is explicitly not part of this work.

## Hard constraints

- No compile, upload, board-port, or console UI, not even disabled. The
  `roadmap_notice` toast stays as it is (CLAUDE.md rule).
- The board setting is described in the UI as "Used for examples, templates and
  autocomplete" so it doesn't imply building.
- All file access stays on the Storage Access Framework (`DocumentFile` /
  `ContentResolver`), never on `java.io.File`.
- Architecture stays plain Activities + ViewBinding. No DI, no ViewModels.
- Every third-party asset is MIT, Apache-2.0, or OFL-1.1 and listed in
  `THIRD_PARTY_NOTICES.md`.

## Delivery

One spec, three phases, implemented and committed in order on
`claude/espsketchide-public-plan`. Each phase leaves the app shippable. Before
phase 1, the pending bug-fix work (rename order, `.txt` suffix on added files,
tab-listener leak, tab contrast, etc.) is committed on its own.

---

## Phase 1: Look & feel

### New components

| Unit | Responsibility | Depends on |
|---|---|---|
| `EspSketchApp : Application` | Applies the saved theme via `AppCompatDelegate.setDefaultNightMode` in `onCreate`. Starts `EditorLanguages.init()` on a background thread. Registered in the manifest. | `AppSettings`, `EditorLanguages` |
| `settings/AppSettings` | Typed getters over SharedPreferences, with no UI. The keys and defaults are listed under **Settings keys** below. | `Context` |
| `settings/SettingsActivity` + `SettingsFragment : PreferenceFragmentCompat` | Settings screen driven by `res/xml/preferences.xml`. When the theme changes, the new night mode is applied immediately. Includes a **Licenses** entry that shows `assets/THIRD_PARTY_NOTICES.txt` in a scrollable dialog. | `androidx.preference:preference-ktx` |
| `editor/EditorLanguages` | One-time TextMate setup (see **TextMate setup** below). Also exposes `languageFor(fileName, board)` and `colorScheme(isDark)`. | sora `language-textmate` |

Both toolbars get a ⋮ → **Settings** entry.

**Settings keys.** They live in the existing `esp_sketch_ide` prefs file (the
fragment sets `preferenceManager.sharedPreferencesName`):

| Key | Values | Default |
|---|---|---|
| `theme` | `dark` / `light` / `system` | `dark` |
| `board` | `esp32` / `esp8266` | `esp32` |
| `editor_font_size` | 10–24 sp | 14 |
| `word_wrap` | on / off | off |
| `show_symbol_bar` | on / off | on |

**TextMate setup.** `EditorLanguages.init()` does the following:

1. Registers `AssetsFileResolver`.
2. Loads grammars from `textmate/languages.json`.
3. Loads `ide-dark` and `ide-light` into `ThemeRegistry`.

It is idempotent and thread-safe: a single `Future` that callers block on with
`get()`. `languageFor` returns:

- `TextMateLanguage.create("source.arduino", true)` for `ino, h, hpp, c, cpp, cc`
- `EmptyLanguage` for `txt`

### Syntax highlighting

- The `language-java` dependency is replaced by
  `io.github.Rosemoe.sora-editor:language-textmate:0.23.6`.
- Assets under `app/src/main/assets/textmate/`:

  | File | Source |
  |---|---|
  | `cpp.tmLanguage.json` | VS Code `extensions/cpp`, MIT; downloaded only with the user's OK |
  | `cpp.language-configuration.json` | same |
  | `arduino.tmLanguage.json` | ours; `scopeName: source.arduino` |
  | `languages.json` | ours; the grammar registry |

  `cpp.tmLanguage.json` pulls in any other grammar files it includes, such as
  the embedded-macro grammar.
- `arduino.tmLanguage.json` lists its own word-bounded patterns first, then
  `{ "include": "source.cpp" }`:

  | Scope | Examples |
  |---|---|
  | `support.function.arduino` | `pinMode`, `digitalWrite`, `digitalRead`, `analogRead`, `analogWrite`, `delay`, `millis`, `micros`, `attachInterrupt`, `ledcAttach`, `ledcWrite`, `touchRead`, `configTime` |
  | `support.constant.arduino` | `HIGH`, `LOW`, `INPUT`, `OUTPUT`, `INPUT_PULLUP`, `LED_BUILTIN`, `WL_CONNECTED` |
  | `support.class.arduino` | `Serial`, `WiFi`, `String`, `Preferences`, `EEPROM`, `ESP`, `WebServer`, `ESP8266WebServer`, `HTTPClient` |

  The TextMate engine picks the earliest match and breaks ties by pattern order,
  so these win over generic C++ identifiers. They don't fire inside strings or
  comments, because those regions only run their own inner patterns.
- Two themes we write ourselves, `textmate/themes/ide-dark.json` and
  `ide-light.json`, which cover the standard scopes plus the three Arduino
  scopes. The editor uses `TextMateColorScheme.create(ThemeRegistry.getInstance())`
  and switches themes by night mode.
- **Fallback:** if sora's engine mishandles the VS Code C++ grammar (wrong
  scopes or a hang on the test sketch), include `source.c` from VS Code's
  `c.tmLanguage.json` instead. The Arduino layer stays unchanged.

### Visual overhaul

- **Palette** (`values/colors.xml` plus `values-night`):
  - Dark: charcoal surfaces (`#1E1F22` background, `#2B2D30` toolbar/tabs) with
    teal `#00838F` as the accent.
  - Light: `#F7F8FA` / `#FFFFFF` with the same accent.
  - Both: orange `#FF6F00` tab indicator.
  - Both themes keep `Theme.Material3.DayNight.NoActionBar` as the parent.
- **Toolbar and tabs:** `tabTextColor` and `tabSelectedTextColor` come from
  theme attributes, not hard-coded white, so they work on both surfaces.
- **Icons:** vector drawables from Material Symbols (Apache-2.0) replace
  `@android:drawable/*`: save, undo, redo, search, add, settings, examples (book),
  close, arrow up/down, expand. They are tinted with `?attr/colorOnSurface`.
- **Editor font:** JetBrains Mono Regular in `assets/fonts/JetBrainsMono-Regular.ttf`,
  with `assets/fonts/OFL.txt`. Applied via `codeEditor.typefaceText`.
- The editor also applies `editor_font_size` and `word_wrap` in `onResume`, so
  changes take effect when you come back from Settings.
- **Sketch list:** denser rows with a sketch icon and the name. There is no
  secondary line: it would cost extra SAF reads per row, or claim a primary
  `.ino` exists without checking. The overflow button keeps its fixed "More
  options" label. Empty state: text plus a **Browse examples** button, wired up
  in phase 3.

---

## Phase 2: Editor ergonomics

### Symbol bar

- A sora `SymbolInputView` at the bottom of `activity_editor.xml`, in a
  horizontal scroller, bound to `codeEditor`.
- Symbols: `⇥ { } ( ) ; # < > " ' = + - * / & | ! [ ] , . _ :`
- `⇥` inserts two spaces.
- Visibility follows `show_symbol_bar`.
- `windowSoftInputMode="adjustResize"` (already set) keeps it above the
  keyboard.

### Undo / redo

- Toolbar icons call `codeEditor.undo()` / `redo()`.
- Enabled state is refreshed from `canUndo()` / `canRedo()` on every
  `ContentChangeEvent` and after each load.
- Loading a file resets the undo history.

### Find / replace

- **Layout:** `layout/view_search_panel.xml`, included in `activity_editor.xml`
  between the tabs and the editor and `GONE` by default, driven by a small
  controller class `editor/SearchController`. The Search icon toggles it.
- **Search row:** query field, match counter ("3/12" or "0 results"),
  previous/next, a Match case toggle, an expand arrow, and close.
- **Replace row** (shown via the expand arrow): replace field, **Replace**,
  **Replace all**.
- **Engine:** `codeEditor.searcher` (sora `EditorSearcher`) handles:
  - plain-text search with a case-sensitivity flag
  - next/previous match
  - replace the current match / replace all
  - the match count

  Exact method and `SearchOptions` signatures get checked against sora 0.23.6
  during implementation.
- Closing the panel stops the search, which clears the highlights. Regex search
  is out of scope.

### Autocomplete

- **`editor/ArduinoApi`:** a pure Kotlin object with `core`, `esp32`, and
  `esp8266` keyword lists, plus `keywordsFor(board): List<String>` returning
  core + board list, deduplicated and sorted.
- **Wiring:** `EditorLanguages.languageFor` passes
  `ArduinoApi.keywordsFor(board)` to the TextMate language's completer keywords
  (`setCompleterKeywords`; verify the name in 0.23.6). sora's TextMate language
  already suggests identifiers from the open file.
- **Board changes** apply the next time a file is opened.

### Unsaved-change tracking

- **State:** `EditorActivity` keeps `dirtyFiles: MutableSet<Uri>`.
- **Marking dirty:** a `ContentChangeEvent` marks the current file dirty unless
  the event came from loading the file (guarded by a `loading` flag set around
  `setText`).
- **Tab label:** dirty tabs are labeled with `editor_unsaved_indicator`
  (`name •`).
- **Saving:** `saveCurrentFile()` only writes when the file is dirty, and clears
  the flag and label afterwards. The save points (tab switch, `onPause`, Save)
  stay the same.
- **Save feedback:** shows `saved_toast`, or a new `nothing_to_save` string
  ("No changes").

### Toolbar

← back · sketch name · Undo · Redo · Search · Save · ⋮ (**Add file**,
**Settings**). All actions use `showAsAction="ifRoom"`, and the order above sets
which ones drop into the overflow first on narrow screens (Add file and Settings
are always in it).

---

## Phase 3: Built-in content

### Examples

- **Location:** `app/src/main/assets/examples/`, one folder per variant, each
  containing `<Name>.ino` and optionally other files.
- **Catalog:** `examples/index.json`:

  ```json
  { "examples": [
    { "id": "blink", "name": "Blink", "category": "Basics",
      "description": "Blink the on-board LED once per second.",
      "variants": { "esp32": "basics/Blink", "esp8266": "basics/Blink" } }
  ] }
  ```

  An example is listed for a board only if `variants` has that board.
- **Target cores:** ESP32 Arduino core 3.x and ESP8266 Arduino core 3.x. Where a
  pin differs, the sketch uses `#ifndef LED_BUILTIN` / a clearly commented
  `const int` pin.
- **Set:**

  | Category | Examples (**bold** = separate ESP32 / ESP8266 versions) |
  |---|---|
  | Basics | Blink, BlinkWithoutDelay, Button (`INPUT_PULLUP`), **AnalogRead**, Fade (`analogWrite`) |
  | Communication | SerialEcho |
  | WiFi | **WiFiScan**, **WiFiConnect**, **HttpGet**, **WebServerHello**, **NtpClock** |
  | ESP-specific | **DeepSleepTimer**, TouchSensor (ESP32 only), Preferences counter (ESP32) / EEPROM counter (ESP8266) |

- **License:** all written for this project, MIT.

### Components

| Unit | Responsibility |
|---|---|
| `examples/ExampleCatalog` | Pure Kotlin. Parses the index JSON string into `Example(id, name, category, description, variants: Map<String,String>)`. `forBoard(board)` returns examples grouped by category in index order. |
| `examples/ExamplesActivity` | Loads the catalog from assets and shows a sectioned RecyclerView (category headers plus rows with name and description). Toolbar subtitle: board name + "· change in Settings". Tapping a row opens the **Open as new sketch** dialog with a unique name prefilled, then copies the variant folder's files into the sketchbook and opens `EditorActivity`. |
| `SketchRepository` changes | `createSketch(name, files: Map<String, String>)`, where the primary `<name>.ino` is renamed from the example's `.ino`. `uniqueName(base)` returns `base`, then `base_2`, `base_3`, … using the existing `findFile`. Existing callers keep working through a template-based overload. |

- **Entry points:** sketch-list ⋮ → **Examples**, and the empty-state **Browse
  examples** button.
- If no sketchbook root is set, the root picker comes first (same as New sketch
  today).

### Templates

- **`templates/SketchTemplates`:** a pure Kotlin object with three templates,
  **Bare minimum** (default), **Serial**, and **WiFi station**.
  `contentFor(templateId, board)` returns the `.ino` text (the WiFi station
  template picks `WiFi.h` or `ESP8266WiFi.h`). Bodies are stored in
  `assets/templates/`.
- **New sketch dialog:** gains a template dropdown (Material exposed dropdown)
  under the name field.

---

## Testing

- **JVM unit tests** (new `app/src/test`), which also need
  `testImplementation("org.json:json:20240303")`:
  - `ExampleCatalogTest`: parsing, board filtering, category grouping, and
    ignoring unknown boards.
  - `ArduinoApiTest`: core ⊂ each board list, board-specific names excluded from
    the other board, no duplicates.
  - `UniqueNameTest`: pure helper `nextFreeName(base, existing: Set<String>)`,
    which `uniqueName` uses.
  - `SketchTemplatesTest`: each template/board pair returns content, and the
    WiFi header matches the board.
- **Instrumented tests** (new `app/src/androidTest`, run on the
  `EspSketchIDE_API34` emulator):
  - `HighlightingTest`: after `EditorLanguages.init()`, tokenizing a sample
    sketch gives the expected scopes for `#include <WiFi.h>`, `uint8_t`,
    `digitalWrite`, `HIGH`, `// comment`, and `"string"`.
  - `ExampleAssetsTest`: every variant path in `index.json` exists in assets and
    contains a `.ino`; every template asset exists.
- **Manual emulator pass after each phase:** light and dark screenshots, plus
  the existing create/rename/add-file/delete flow re-checked.
- **Optional, needs the user's OK for the download:** compile every example and
  template variant on the Mac with `arduino-cli` and the `esp32:esp32` and
  `esp8266:esp8266` cores.

## Docs

- README:
  - "What works today" gains the new features.
  - "About the syntax highlighting" is rewritten for TextMate.
  - The roadmap checkboxes stay unchanged (no milestone is completed by this
    work).
- CLAUDE.md: architecture bullets for Application, settings, `EditorLanguages`,
  examples/templates, and the tests now existing.
- New `THIRD_PARTY_NOTICES.md` at the project root, mirrored to
  `assets/THIRD_PARTY_NOTICES.txt` for the in-app Licenses screen.

## Risks

- APK grows by about 3–4 MB (regex engine, grammar, font). Accepted.
- Grammar load and first tokenization cost. Grammars load in the background
  from `Application`; `EditorActivity` blocks on the `Future` only if it opens
  before loading finishes.
- VS Code C++ grammar compatibility with sora's engine. Mitigated by the C
  grammar fallback above.
- File I/O stays on the main thread. This is a known issue, out of scope here.

## Out of scope

Drawer/single-screen workspace, serial monitor, compile/upload/console, regex
search, clangd/LSP completion, per-sketch board setting.
