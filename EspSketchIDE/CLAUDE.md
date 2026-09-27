# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

EspSketchIDE is an MIT-licensed Android app for editing ESP32/ESP8266 Arduino sketches on a phone or tablet. It is an independent project inspired by ArduinoDroid, not a fork or republish of it. It lives in a subdirectory of the `Esp32-remote-sandbox` git repo; the git root is the parent directory, and `../Esp32WhatsappServer` is an unrelated sibling sketch.

Status is alpha: sketch management, a multi-file editor, bundled examples, and **experimental** on-device Verify/Upload/Serial monitor for the ESP32. The public-release design is `../docs/superpowers/specs/2026-09-25-public-release-design.md`, the task plan is `../docs/superpowers/plans/2026-09-26-public-release-plan.md` (phases P0–P6) and what was proven how is in `../docs/superpowers/spikes/p0-results.md`. Compile and upload are verified under emulation only (Android binaries under qemu-user, the ESP32 ROM in Espressif's QEMU), not yet on real phones and boards, so the UI labels them experimental and shows a one-time notice. They appear only when the APK bundles the compiler (`BuildConfig.TOOLCHAIN_BUNDLED` and the files are really in `nativeLibraryDir`); otherwise the editor shows the `roadmap_notice` snackbar. Keep it that way: never add UI that claims a capability the app doesn't have. When you finish a milestone, update the README roadmap checkboxes.

## Modules

- `:app` (Android) — below.
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

JVM unit tests live in `app/src/test` (JUnit4 + Truth + kotlinx-coroutines-test). Run them all with `./gradlew :app:testDebugUnitTest`, or one class with `./gradlew :app:testDebugUnitTest --tests "org.espsketchide.app.data.SketchRepositoryTest"`. There are no instrumented tests yet.

## Architecture

`:app` (package `org.espsketchide.app`): Activities with ViewBinding, ViewModels with coroutines, no DI framework. `EspSketchApp` (the `Application`) owns the shared `DocumentFileStorage`, `SketchRepository`, the compile/upload objects below and an `appScope` for work that must outlive a screen.

- **Storage goes through the `SketchStorage` interface** (`data/`). `DocumentFileStorage` implements it with the Storage Access Framework: on first launch the user picks a root folder with `OpenDocumentTree`; the tree URI is persisted in SharedPreferences (`esp_sketch_ide` / `sketches_root_uri`) with a persistable read/write grant, and `root()` returns null once the grant is revoked. Node ids are document URI strings; models (`Sketch.folderId`, `SketchFile.id`) carry them as `String`, not `Uri`, so repository logic is JVM-testable. Never use `java.io.File` paths for sketches.
- **SAF quirks the code relies on** (and `InMemoryStorage` in tests mimics): ids are path-based, so renaming a folder invalidates ids of its children (hence `renameSketch` renames the `.ino` first, then the folder, with rollback); and providers append the MIME type's extension, so non-`.ino` files are created as `application/octet-stream` (`mimeTypeFor`).
- **Sketch rules follow Arduino.** A sketch is a folder under the root that contains `<folder>.ino`; other folders (e.g. `libraries/`) are not listed. Names match `^[A-Za-z][A-Za-z0-9_]*$`. Editable files: `ino, h, hpp, c, cpp, cc, txt`, one dot, primary `.ino` first. `createFromFiles` copies an example, picking `Name_2`, `Name_3`… if taken.
- **Screens:** `SketchListActivity` + `sketches/SketchListViewModel` (list/create/rename/delete, change root, "New from example") → `EditorActivity` + `editor/EditorViewModel`, passed `EXTRA_SKETCH_NAME` and `EXTRA_SKETCH_FOLDER_ID`. Errors surface as snackbars via the ViewModels' event channels; storage never runs on the main thread.
- **Editor:** one tab per file and a shared sora-editor `CodeEditor`. `EditorViewModel` keeps per-file buffers and what was last persisted, writes only changed files (on tab switch, Save, and `onPause` via `appScope`), and exposes `OpenDocument(file, text, version)`; the activity replaces editor text only when `version` changes, and `updateText` keeps `text` current so rotation keeps unsaved edits. The tab listener is attached once and ignored while tabs are rebuilt.
- **Highlighting:** `editor/Highlighting` loads VS Code's TextMate C++ grammar (`assets/textmate/`, MIT, see `NOTICE.md`) off the main thread with our own light/dark themes; `.txt` is plain. If loading fails, editing continues without colours.
- **Student UX:** `SymbolInputView` symbol bar above the keyboard, undo/redo menu actions, pinch and menu text size (10–28 sp, `EditorPrefs`).
- **Examples:** `assets/examples/<NN.Category>/<Name>/<Name>.ino`, read by `examples/AssetExampleSource`. CI compiles each with arduino-cli for `esp32:esp32:esp32`.
- **Compile** (`compile/`): `Toolchain` links the bundled `libxt*.so` programs and the pack's gcc target files into `filesDir/tc` (Android only executes files from `nativeLibraryDir`); `PackManager` installs `.tar.xz` packs into `filesDir/packs/<id>` (safe paths, no links, checked against `pack.json`, atomic rename); `PackInstaller` runs that with progress. `SketchStager` copies the sketch from SAF into `cacheDir/stage` (only changed files, so objects are reused), `SketchCompiler` runs the build engine and turns failures into `Diagnostic`s, and `BuildController` (one per app, in `EspSketchApp`) runs Verify/Upload and publishes `BuildState` for the editor's output panel. `OnDeviceCompileTest` runs this whole path with the Android compiler under qemu when its env vars are set.
- **Upload and monitor** (`upload/`): `UsbSerial`/`UsbSerialLink` implement `SerialLink` over usb-serial-for-android (package-scoped `PendingIntent` for the permission); upload = auto-reset into the ROM loader, `Flasher` at 460800 baud with MD5 verify, hard reset. `SerialMonitor` decodes output on a thread; the open one lives in `EspSketchApp.serialMonitor` and is closed before an upload.
- **Edge-to-edge** is enforced at targetSdk 35+: `ui/Insets` pads the app bar under the status bar, makes the editor follow the keyboard (adjustResize no longer does), and keeps the list/FAB clear of the navigation bar.

## CI and releases

`../.github/workflows/espsketchide.yml` runs unit tests, lint, a debug build, and the examples compile job. `espsketchide-release.yml` builds a signed APK into a draft GitHub Release on tags `espsketchide-v*`; signing reads `RELEASE_KEYSTORE_PATH`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` from the environment (unsigned without them).

## Conventions

- Test first; keep logic out of Activities. Honest UI (see above).
- Clean-room: arduino-cli (GPL-3.0) and esptool (GPL-2.0) are behaviour references only; don't copy their code or test data.
- GitHub repos outside this session's scope are blocked from the cloud container; arduino-cli comes from `downloads.arduino.cc`.
