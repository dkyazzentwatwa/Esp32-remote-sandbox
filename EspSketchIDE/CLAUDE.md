# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

EspSketchIDE is an MIT-licensed Android app for editing ESP32/ESP8266 Arduino sketches on a phone or tablet. It is an independent project inspired by ArduinoDroid, not a fork or republish of it. It lives in a subdirectory of the `Esp32-remote-sandbox` git repo; the git root is the parent directory, and `../Esp32WhatsappServer` is an unrelated sibling sketch.

Status is editor alpha: sketch management, a multi-file editor and bundled examples. The public-release design is `../docs/superpowers/specs/2026-09-25-public-release-design.md` and the task plan is `../docs/superpowers/plans/2026-09-26-public-release-plan.md` (phases P0–P6; P1 is done apart from device testing). **Compile and upload are not implemented.** On purpose, the app has no stub or fake buttons for them; it shows a `roadmap_notice` snackbar instead. Keep it that way: never add UI that claims a capability the app doesn't have. When you finish a milestone, update the README roadmap checkboxes.

## Build

Needs JDK 17+ (the cloud container has 21) and the Android SDK: compileSdk 37 (required by current AndroidX), targetSdk 36 (Google Play's requirement since 2026-08-31), minSdk 26. Uses Gradle 9.8.0 through the wrapper and AGP 9.4.1 with AGP's built-in Kotlin support (Kotlin 2.2.10). Do not apply `org.jetbrains.kotlin.android` or add a `kotlinOptions` block; AGP 9 rejects both. In cloud sessions `.claude/hooks/session-start.sh` installs the SDK and points Gradle at Google's Maven Central mirror.

```bash
./gradlew :app:assembleDebug        # APK -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug         # install on a connected device/emulator
./gradlew :app:lint
```

JVM unit tests live in `app/src/test` (JUnit4 + Truth + kotlinx-coroutines-test). Run them all with `./gradlew :app:testDebugUnitTest`, or one class with `./gradlew :app:testDebugUnitTest --tests "org.espsketchide.app.data.SketchRepositoryTest"`. There are no instrumented tests yet.

## Architecture

Single module (`:app`, package `org.espsketchide.app`): Activities with ViewBinding, ViewModels with coroutines, no DI framework. `EspSketchApp` (the `Application`) owns the shared `DocumentFileStorage`, `SketchRepository` and an `appScope` for work that must outlive a screen.

- **Storage goes through the `SketchStorage` interface** (`data/`). `DocumentFileStorage` implements it with the Storage Access Framework: on first launch the user picks a root folder with `OpenDocumentTree`; the tree URI is persisted in SharedPreferences (`esp_sketch_ide` / `sketches_root_uri`) with a persistable read/write grant, and `root()` returns null once the grant is revoked. Node ids are document URI strings; models (`Sketch.folderId`, `SketchFile.id`) carry them as `String`, not `Uri`, so repository logic is JVM-testable. Never use `java.io.File` paths for sketches.
- **SAF quirks the code relies on** (and `InMemoryStorage` in tests mimics): ids are path-based, so renaming a folder invalidates ids of its children (hence `renameSketch` renames the `.ino` first, then the folder, with rollback); and providers append the MIME type's extension, so non-`.ino` files are created as `application/octet-stream` (`mimeTypeFor`).
- **Sketch rules follow Arduino.** A sketch is a folder under the root that contains `<folder>.ino`; other folders (e.g. `libraries/`) are not listed. Names match `^[A-Za-z][A-Za-z0-9_]*$`. Editable files: `ino, h, hpp, c, cpp, cc, txt`, one dot, primary `.ino` first. `createFromFiles` copies an example, picking `Name_2`, `Name_3`… if taken.
- **Screens:** `SketchListActivity` + `sketches/SketchListViewModel` (list/create/rename/delete, change root, "New from example") → `EditorActivity` + `editor/EditorViewModel`, passed `EXTRA_SKETCH_NAME` and `EXTRA_SKETCH_FOLDER_ID`. Errors surface as snackbars via the ViewModels' event channels; storage never runs on the main thread.
- **Editor:** one tab per file and a shared sora-editor `CodeEditor`. `EditorViewModel` keeps per-file buffers and what was last persisted, writes only changed files (on tab switch, Save, and `onPause` via `appScope`), and exposes `OpenDocument(file, text, version)`; the activity replaces editor text only when `version` changes, and `updateText` keeps `text` current so rotation keeps unsaved edits. The tab listener is attached once and ignored while tabs are rebuilt.
- **Highlighting:** `editor/Highlighting` loads VS Code's TextMate C++ grammar (`assets/textmate/`, MIT, see `NOTICE.md`) off the main thread with our own light/dark themes; `.txt` is plain. If loading fails, editing continues without colours.
- **Student UX:** `SymbolInputView` symbol bar above the keyboard, undo/redo menu actions, pinch and menu text size (10–28 sp, `EditorPrefs`).
- **Examples:** `assets/examples/<NN.Category>/<Name>/<Name>.ino`, read by `examples/AssetExampleSource`. CI compiles each with arduino-cli for `esp32:esp32:esp32`.
- **Edge-to-edge** is enforced at targetSdk 35+: `ui/Insets` pads the app bar under the status bar, makes the editor follow the keyboard (adjustResize no longer does), and keeps the list/FAB clear of the navigation bar.

## CI and releases

`../.github/workflows/espsketchide.yml` runs unit tests, lint, a debug build, and the examples compile job. `espsketchide-release.yml` builds a signed APK into a draft GitHub Release on tags `espsketchide-v*`; signing reads `RELEASE_KEYSTORE_PATH`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` from the environment (unsigned without them).

## Conventions

- Test first; keep logic out of Activities. Honest UI (see above).
- Clean-room: arduino-cli (GPL-3.0) and esptool (GPL-2.0) are behaviour references only; don't copy their code or test data.
- GitHub repos outside this session's scope are blocked from the cloud container; arduino-cli comes from `downloads.arduino.cc`.
