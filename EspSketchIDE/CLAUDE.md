# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

EspSketchIDE is an MIT-licensed Android app for editing ESP32/ESP8266 Arduino sketches on a phone or tablet. It is an independent project inspired by ArduinoDroid, not a fork or republish of it. It lives in a subdirectory of the `Esp32-remote-sandbox` git repo; the git root is the parent directory, and `../Esp32WhatsappServer` is an unrelated sibling sketch.

Status is alpha: sketch management, a multi-file editor, bundled examples, and **experimental** on-device Verify/Upload/Serial monitor for the ESP32. The public-release design is `../docs/superpowers/specs/2026-09-25-public-release-design.md`, the task plan is `../docs/superpowers/plans/2026-09-26-public-release-plan.md` (phases P0–P6) and what was proven how is in `../docs/superpowers/spikes/p0-results.md`. On-device compile is verified on a real phone (Pixel XL, Android 10, arm64: Blink builds in ~70 s cold / ~27 s cached, same size as arduino-cli); upload is verified only against Espressif's QEMU ESP32, not yet a real board, so the UI labels Verify/Upload experimental and shows a one-time notice. They appear only when the APK bundles the compiler (`BuildConfig.TOOLCHAIN_BUNDLED` and the files are really in `nativeLibraryDir`); otherwise the editor shows the `roadmap_notice` snackbar. Keep it that way: never add UI that claims a capability the app doesn't have. When you finish a milestone, update the README roadmap checkboxes.

## Modules

- `:app` (Android) — below. Bundled third-party assets are listed in `app/src/main/assets/licenses/NOTICES.md` (sora-editor is LGPL-2.1, used unmodified).
- `:buildengine` (pure JVM, `org.espsketchide.buildengine`) — an Arduino build written from scratch: platform.txt/boards.txt properties, `.ino` merge and prototypes, library discovery, recipes, object cache; `esp32/` has the partition table, elf2image/merge-bin, the relocated-toolchain runner and the pack layout. Golden tests compare against arduino-cli output (needs `ESP32_REFERENCE_DIR`, made by `buildengine/golden/make-reference.sh`; skipped otherwise).
- `:esptool` (pure JVM, `org.espsketchide.esptool`) — ESP32 ROM loader protocol (SLIP, sync, flash, MD5 verify) and DTR/RTS auto-reset. `QemuFlashTest` runs against Espressif's QEMU when `ESP_QEMU` is set.
- `toolchain/` — `build-android.sh` builds the xtensa GCC for Android with the NDK; `make-pack.py` builds the ESP32 board pack (`pack.json` + core, SDK and gcc target files).

## Build

Needs JDK 17+ (the cloud container has 21) and the Android SDK: compileSdk 37 (required by current AndroidX), targetSdk 36 (Google Play's requirement since 2026-08-31), minSdk 26. Uses Gradle 9.8.0 through the wrapper and AGP 9.4.1 with AGP's built-in Kotlin support (Kotlin 2.2.10). Do not apply `org.jetbrains.kotlin.android` or add a `kotlinOptions` block; AGP 9 rejects both. In cloud sessions `.claude/hooks/session-start.sh` installs the SDK and points Gradle at Google's Maven Central mirror.

```bash
./gradlew :app:assembleDebug        # APK -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug         # install on a connected device/emulator
./gradlew :app:lint
```

`-PtoolchainDirs=arm64-v8a=<dir>,armeabi-v7a=<dir>` bundles the Android toolchain as `lib*.so` files (see `toolchainNames` in `app/build.gradle.kts`) and turns compile/upload on; `-PpackUrl`/`-PpackSha256` add the board pack Download button. Module tests: `./gradlew :buildengine:test :esptool:test`.

JVM unit tests live in `app/src/test` (JUnit4 + Truth + kotlinx-coroutines-test). Run them all with `./gradlew :app:testDebugUnitTest`, or one class with `./gradlew :app:testDebugUnitTest --tests "org.espsketchide.app.data.SketchRepositoryTest"`. Instrumented tests (`app/src/androidTest`: highlighting scopes, autocomplete lists, find/replace) need a device or emulator: `./gradlew :app:connectedDebugAndroidTest`. It uninstalls the app afterwards, which wipes its prefs and folder grant. `tools/compile-examples.py` compiles every bundled example for its board with arduino-cli (CI runs it).

## Architecture

`:app` (package `org.espsketchide.app`): Activities with ViewBinding, ViewModels with coroutines, no DI framework. `EspSketchApp` (the `Application`) owns the shared `DocumentFileStorage`, `SketchRepository`, the compile/upload objects below and an `appScope` for work that must outlive a screen.

- **Storage goes through the `SketchStorage` interface** (`data/`). `DocumentFileStorage` implements it with the Storage Access Framework: on first launch the user picks a root folder with `OpenDocumentTree`; the tree URI is persisted in SharedPreferences (`esp_sketch_ide` / `sketches_root_uri`) with a persistable read/write grant, and `root()` returns null once the grant is revoked. Node ids are document URI strings; models (`Sketch.folderId`, `SketchFile.id`) carry them as `String`, not `Uri`, so repository logic is JVM-testable. Never use `java.io.File` paths for sketches.
- **SAF quirks the code relies on** (and `InMemoryStorage` in tests mimics): ids are path-based, so renaming a folder invalidates ids of its children (hence `renameSketch` renames the `.ino` first, then the folder, with rollback); and providers append the MIME type's extension, so non-`.ino` files are created as `application/octet-stream` (`mimeTypeFor`).
- **Sketch rules follow Arduino.** A sketch is a folder under the root that contains `<folder>.ino`; other folders (e.g. `libraries/`) are not listed. Names match `^[A-Za-z][A-Za-z0-9_]*$`. Editable files: `ino, h, hpp, c, cpp, cc, txt`, one dot, primary `.ino` first. `createFromFiles` copies an example, picking `Name_2`, `Name_3`… if taken.
- **Screens:** `SketchListActivity` + `sketches/SketchListViewModel` (list/create/rename/delete, change root, "New from example") → `EditorActivity` + `editor/EditorViewModel`, passed `EXTRA_SKETCH_NAME` and `EXTRA_SKETCH_FOLDER_ID`. Errors surface as snackbars via the ViewModels' event channels; storage never runs on the main thread.
- **Editor:** one tab per file and a shared sora-editor `CodeEditor`. `EditorViewModel` keeps per-file buffers and what was last persisted, writes only changed files (on tab switch, Save, and `onPause` via `appScope`), and exposes `OpenDocument(file, text, version)`; the activity replaces editor text only when `version` changes, and `updateText` keeps `text` current so rotation keeps unsaved edits. The tab listener is attached once and ignored while tabs are rebuilt.
- **Highlighting:** `editor/EditorLanguages` loads VS Code's C++ grammar (`assets/textmate/cpp/`, MIT) plus our `assets/textmate/arduino.tmLanguage.json` and `ide-{dark,light}` themes once, on a background thread started in `EspSketchApp`, and tokenizes a warm-up sketch there (tm4e compiles rules lazily; doing that on sora's analyzer thread held a lock `setText` needs and caused an ANR). The editor never blocks: it shows plain text and switches on via `whenReady()`. Two grammar rules pinned by `HighlightingTest`: Arduino names are an *injection* (`L:source.arduino -string -comment`) because the C++ grammar re-enters itself via `$self` inside blocks, and the function pattern consumes leading whitespace because C++'s function-call rule starts its match there. `.txt` is plain.
- **Learning aids:** `compile/ErrorHelp` explains common gcc errors under each diagnostic; `reference/ArduinoReference` (+ `assets/reference/reference.json`) backs long-press help, Help → Arduino reference and the parameter-filling snippets (`editor/ArduinoSnippets`, wrapped around the TextMate language by `editor/SnippetLanguage`); `reference/PinGuide` (+ `pins.json`) is Help → Pin guide; examples carry lessons in `index.json` (`examples/LessonCard`). Edit menu: `editor/CodeTools` (comment toggle, Auto format) and go to line. Serial monitor has a plotter (`upload/SerialPlot`, `PlotView`).
- **Editor tools:** symbol bar (`SymbolBarSymbols`, hidden via Settings), undo/redo, find/replace (`SearchController`: every searcher move returns focus to the panel because sora's `setSelectionRegion` calls `requestFocus()`; disarm the first-result jump before `stopSearch()`), autocomplete from `ArduinoApi` for the Settings board family, unsaved tabs from `EditorUiState.unsaved`.
- **Settings:** `settings/AppSettings` over the `esp_sketch_ide` prefs (theme, font size 10–28 sp shared with pinch zoom, word wrap, symbol bar, board family) and `SettingsActivity`. The board family (ESP32/ESP8266) only picks examples, templates and autocomplete; `compile/BoardPrefs` picks the ESP32 variant to build for, and Verify/Upload refuse to run for the ESP8266 family.
- **Examples:** `assets/examples/index.json` lists each example's variants per board (`examples/ExampleCatalog`); `ExamplesActivity` returns the picked id and `SketchListViewModel.createFrom` copies it. Templates: `templates/SketchTemplates`.
- **Compile** (`compile/`): `Toolchain` links the bundled `libxt*.so` programs and the pack's gcc target files into `filesDir/tc` (Android only executes files from `nativeLibraryDir`); `PackManager` installs `.tar.xz` packs into `filesDir/packs/<id>` (safe paths, no links, checked against `pack.json`, atomic rename); `PackInstaller` runs that with progress. `SketchStager` copies the sketch from SAF into `cacheDir/stage` (only changed files, so objects are reused), `SketchCompiler` runs the build engine and turns failures into `Diagnostic`s, and `BuildController` (one per app, in `EspSketchApp`) runs Verify/Upload and publishes `BuildState` for the editor's output panel. `OnDeviceCompileTest` runs this whole path with the Android compiler under qemu when its env vars are set.
- **Upload and monitor** (`upload/`): `UsbSerial`/`UsbSerialLink` implement `SerialLink` over usb-serial-for-android (package-scoped `PendingIntent` for the permission); upload = auto-reset into the ROM loader, `Flasher` at 460800 baud with MD5 verify, hard reset. `SerialMonitor` decodes output on a thread; the open one lives in `EspSketchApp.serialMonitor` and is closed before an upload.
- **Edge-to-edge** is enforced at targetSdk 35+: `ui/Insets` pads the app bar under the status bar, makes the editor follow the keyboard (adjustResize no longer does), and keeps the list/FAB clear of the navigation bar.

## CI and releases

`../.github/workflows/espsketchide.yml` runs unit tests, lint, a debug build, and the examples compile job. `espsketchide-release.yml` builds a signed APK into a draft GitHub Release on tags `espsketchide-v*`; signing reads `RELEASE_KEYSTORE_PATH`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` from the environment (unsigned without them).

## Conventions

- Test first; keep logic out of Activities. Honest UI (see above).
- Clean-room: arduino-cli (GPL-3.0) and esptool (GPL-2.0) are behaviour references only; don't copy their code or test data.
- GitHub repos outside this session's scope are blocked from the cloud container; arduino-cli comes from `downloads.arduino.cc`.
