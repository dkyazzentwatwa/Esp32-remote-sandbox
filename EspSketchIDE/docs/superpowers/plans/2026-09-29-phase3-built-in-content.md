# Phase 3: Built-in Content Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give a new user something to start from: a catalog of built-in
example sketches (per board), an **Examples** screen that opens one as a new
sketch, and sketch templates in the New sketch dialog.

**Architecture:** Plain Activities and ViewBinding. New units:

- **`data/SketchNames`** (pure): `nextFreeName`, `toSketchName`, `renamePrimary`.
- **`examples/ExampleCatalog`** (pure): parses `assets/examples/index.json`,
  filters by board, groups by category, flattens to header/item rows.
- **`examples/ExampleFiles`**: reads a variant folder from assets.
- **`examples/ExamplesActivity` + `ExampleAdapter`**: the sectioned list and the
  "Open as new sketch" flow.
- **`templates/SketchTemplates`** (pure): three templates, `contentFor(id, board)`.
- **`SketchRepository`**: `createSketch(name, files)`, `uniqueName(base)`.
- **`SketchListActivity`**: Examples menu entry, empty-state button, template
  dropdown in the New sketch dialog.

**Tech Stack:** as Phases 1 and 2, plus `testImplementation("org.json:json:20240303")`
(Android's `org.json` is a stub on the JVM).

**Spec:** `docs/superpowers/specs/2026-09-27-ide-editor-overhaul-design.md`

**Deviations from the spec (decided while planning):**
1. **Template bodies are Kotlin string constants**, not files in
   `assets/templates/`. That makes "each template/board pair returns content
   and the WiFi header matches the board" a JVM test that runs anywhere, and it
   drops the "every template asset exists" instrumented check, which no longer
   has anything to check.
2. **`ExampleAssetsTest` is a JVM test**, not an instrumented one. Gradle runs
   unit tests with the module directory as the working directory, so the test
   reads `src/main/assets/examples` directly. It checks that every variant path
   exists and holds a `.ino`, every folder is referenced, every file has an
   editable extension, every sketch has `setup()` and `loop()`, balanced braces,
   and that a board's variant only includes its own WiFi headers.
3. **One "Saved counter" example** with an ESP32 (`Preferences`) and an ESP8266
   (`EEPROM`) variant, instead of two separate entries.
4. `uniqueName` checks every entry in the sketchbook root (files as well as
   folders), because `createDirectory` would collide with either.
5. All files of a new sketch are created with MIME `application/octet-stream`
   (as `addFile` already does) so providers never append an extension.
6. Nothing here adds UI for compile, upload or serial.

**Every Gradle command** needs JDK 17–21
(`export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`). Paths are relative to
`EspSketchIDE/`.

**Commit trailer:**

```
Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01KLrUbTsFZqUuFtfLxHVfJr
```

---

## File map

**New:** `data/SketchNames.kt`, `examples/{ExampleCatalog,ExampleFiles,ExamplesActivity,ExampleAdapter}.kt`,
`templates/SketchTemplates.kt`, `assets/examples/index.json` plus 22 variant
folders, layouts `activity_examples`, `item_example`, `item_example_header`,
`dialog_new_sketch`, and JVM tests `SketchNamesTest`, `ExampleCatalogTest`,
`SketchTemplatesTest`, `ExampleAssetsTest`.

**Modified:** `app/build.gradle.kts`, `SketchRepository.kt`,
`SketchListActivity.kt`, `activity_sketch_list.xml`, `sketch_list_toolbar.xml`,
`AndroidManifest.xml`, `strings.xml`, `assets/licenses/NOTICES.md`, `README.md`,
`CLAUDE.md`.

---

### Task 1: Pure logic with tests (TDD)

- [ ] **Step 1: Add the JVM dependency** `testImplementation("org.json:json:20240303")`.
- [ ] **Step 2: Tests first:** `SketchNamesTest` (free-name search `base`, `base_2`,
  `base_3`, gaps; `toSketchName` strips symbols and prefixes a non-letter start;
  `renamePrimary` renames the `.ino` and leaves other files), `ExampleCatalogTest`
  (parse, board filter, category grouping in index order, unknown boards ignored,
  flatten to rows), `SketchTemplatesTest` (every id × board returns content with
  `setup()`/`loop()`, the WiFi template includes `WiFi.h` on ESP32 and
  `ESP8266WiFi.h` on ESP8266, unknown ids fall back to bare minimum).
- [ ] **Step 3: Run, expect compile failures.**
- [ ] **Step 4: Implement** the three classes (sources in the appendix).
- [ ] **Step 5: Run, expect PASS. Commit** `Add pure logic for examples, templates and sketch names`.

### Task 2: Example sketches and the asset test

- [ ] **Step 1: Write `ExampleAssetsTest`** (see deviation 2).
- [ ] **Step 2: Write `assets/examples/index.json`** and the 22 variant folders:

  | Category | Example | Variants |
  |---|---|---|
  | Basics | Blink, BlinkWithoutDelay, Button, Fade | shared `basics/<Name>` |
  | Basics | AnalogRead | `basics/AnalogRead_esp32`, `basics/AnalogRead_esp8266` |
  | Communication | SerialEcho | shared |
  | WiFi | WiFiScan, WiFiConnect, HttpGet, WebServerHello, NtpClock | `wifi/<Name>_esp32`, `wifi/<Name>_esp8266` |
  | ESP-specific | DeepSleepTimer | `esp/DeepSleepTimer_esp32`, `_esp8266` |
  | ESP-specific | TouchSensor | ESP32 only |
  | ESP-specific | SavedCounter | Preferences (ESP32) / EEPROM (ESP8266) |

  Targets: ESP32 Arduino core 3.x and ESP8266 core 3.x. All sketches are written
  for this project (MIT). Where a pin differs it is a commented `const int`.
- [ ] **Step 3: Run the JVM tests, expect PASS. Commit** `Add built-in example sketches`.

### Task 3: Repository support

- [ ] `createSketch(name, files: Map<String, String>)`, with the existing
  single-argument call kept working (default content: the bare-minimum
  template). A failed write deletes the half-made folder.
- [ ] `uniqueName(base)` via `SketchNames.nextFreeName` over every entry of the
  root.
- [ ] Build; commit `SketchRepository: create sketches from file maps, unique names`.

### Task 4: UI

- [ ] `ExamplesActivity`: toolbar subtitle `<Board> · change in Settings`,
  sectioned RecyclerView, tap → root picker if there is no sketchbook yet, then
  the **Open as new sketch** dialog with a unique name prefilled, then copy the
  variant, open `EditorActivity`, and finish.
- [ ] Entry points: sketch-list ⋮ → **Examples**, and the empty-state
  **Browse examples** button.
- [ ] New sketch dialog gets a template dropdown (Material exposed dropdown,
  default Bare minimum); the chosen template is rendered for the current board.
- [ ] Manifest entry; strings.
- [ ] `assembleDebug`, `lint`; commit `Examples screen, template dropdown and entry points`.

### Task 5: Docs and verification

- [ ] `NOTICES.md` (examples are project-written, MIT), README, CLAUDE.md.
- [ ] `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lint :app:compileDebugAndroidTestKotlin`.
- [ ] Manual (needs a device/emulator, not run in the cloud session): empty
  state → Browse examples → open Blink → editor shows it and the sketch appears
  in the list; open it again and see `Blink_2`; switch the board to ESP8266 and
  check the list and the WiFi variants; New sketch → each template; light and
  dark.
- [ ] Optional (large download, needs the user's OK): compile every variant with
  `arduino-cli` and the `esp32:esp32` / `esp8266:esp8266` cores.
- [ ] Commit `Document Phase 3`.
