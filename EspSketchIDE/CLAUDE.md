# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

EspSketchIDE is an MIT-licensed Android app for editing ESP32/ESP8266 Arduino sketches on a phone or tablet. It is an independent project inspired by ArduinoDroid, not a fork or republish of it. It lives in a subdirectory of the `Esp32-remote-sandbox` git repo; the git root is the parent directory, and `../Esp32WhatsappServer` is an unrelated sibling sketch.

Status is early alpha (M0–M2 of the roadmap in `README.md`): sketch management and a multi-file editor. **Compile and upload are not implemented.** On purpose, the app has no stub or fake buttons for them; it shows a `roadmap_notice` toast instead. Keep it that way: never add UI that claims a capability the app doesn't have. When you finish a milestone, update the README roadmap checkboxes.

## Build

Needs JDK 17–21 (Gradle 8.7 can't run on JDK 22+; Android Studio's bundled JBR is 25, so set `JAVA_HOME` to a JDK 17/21, e.g. `/opt/homebrew/opt/openjdk@21`) and the Android SDK (compileSdk/targetSdk 34, minSdk 26). Uses Gradle 8.7 through the wrapper, AGP 8.5.2, and Kotlin 2.1.21.

```bash
./gradlew :app:assembleDebug        # APK -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug         # install on a connected device/emulator
./gradlew :app:lint
```

JVM unit tests live in `app/src/test` and instrumented tests in `app/src/androidTest` (they need a device or emulator; locally there's an `EspSketchIDE_API34` AVD, and it needs `-memory 4096` or timings get unreliable). Run one JVM test with `./gradlew :app:testDebugUnitTest --tests "org.espsketchide.app.settings.AppSettingsTest"` and one instrumented class with `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.espsketchide.app.editor.HighlightingTest`. `connectedDebugAndroidTest` uninstalls the app afterwards, which wipes its prefs and folder grant, so reinstall and re-pick the sketches folder before manual testing.

## Architecture

It is a single-module app (`:app`, package `org.espsketchide.app`) built on plain Activities with ViewBinding. There are no ViewModels, DI, or coroutines yet, although the lifecycle/viewmodel dependencies are present.

- **Storage runs entirely through the Storage Access Framework.** On first launch the user picks a root folder with `OpenDocumentTree`. `SketchRepository` persists the tree URI in SharedPreferences (`esp_sketch_ide` / `sketches_root_uri`) and takes a persistable read/write grant. `rootUri()` returns null once that grant is revoked. All file access goes through `DocumentFile` and `ContentResolver` streams. Never use `java.io.File` paths.
- **Sketch model follows Arduino conventions.** A sketch is a directory under the root whose name matches its primary `<name>.ino`. Sketch names must match `^[A-Za-z][A-Za-z0-9_]*$`. Renaming a sketch renames both the folder and the primary `.ino`. Only the extensions in `EDITABLE_EXTENSIONS` (`ino, h, hpp, c, cpp, cc, txt`) are listed or editable, and the primary `.ino` always sorts first.
- **Screen flow:** `SketchListActivity` (launcher; list/create/rename/delete, change root) → `EditorActivity`. The sketch is passed as two extras, `EXTRA_SKETCH_NAME` and `EXTRA_SKETCH_FOLDER_URI`, rather than a Parcelable.
- **Editor:** `EditorActivity` shows one TabLayout tab per sketch file and a single shared sora-editor `CodeEditor` in JetBrains Mono. It saves the current file when you switch tabs, in `onPause`, and from the Save menu action. There is no dirty tracking. Font size and word wrap are re-applied in `onResume` so changes made in Settings take effect when you come back.
- **Syntax highlighting:** sora-editor's `language-textmate` with VS Code's C++ grammar (`assets/textmate/cpp/`, vendored, MIT) plus our `assets/textmate/arduino.tmLanguage.json`. Two non-obvious things, both pinned by `HighlightingTest`: (1) the Arduino names must be an *injection* (`L:source.arduino -string -comment`), because the C++ grammar re-enters itself via `$self` inside blocks, so top-level patterns never reach function bodies; (2) the function pattern consumes leading whitespace, because C++'s function-call rule starts its match at that whitespace and would otherwise win. Themes are our own `assets/textmate/themes/ide-{dark,light}.json`. Keep the README's "About the syntax highlighting" section accurate if this changes.
- **Highlighting startup:** `editor/EditorLanguages` loads grammars and themes once on a background thread (started in `EspSketchApp`) and tokenizes a warm-up sketch there. tm4e compiles rules lazily on first tokenization, and if that happens on sora's analyzer thread it holds a lock `CodeEditor.setText` needs, which caused an ANR. `EditorActivity` never blocks on this: it shows plain text and switches highlighting on via `EditorLanguages.whenReady()`, which runs its callback immediately when loading is already done.
- **Settings and theme:** `settings/AppSettings` gives typed reads over the same `esp_sketch_ide` prefs file; `SettingsActivity` (androidx Preference, `res/xml/preferences.xml`) writes them. `EspSketchApp` applies the theme with `AppCompatDelegate.setDefaultNightMode` (default **dark**). Colors come from `values/colors.xml` plus `values-night/colors.xml`, and app bars use `ThemeOverlay.EspSketchIDE.Bar`.
- **Third-party assets:** everything vendored is listed in `app/src/main/assets/licenses/NOTICES.md` with its license text alongside it; add to it whenever you vendor something. sora-editor is LGPL-2.1 (used unmodified).
- File I/O currently runs on the main thread.

## Roadmap context for future work

The next milestones are M3 (USB serial monitor via `usb-serial-for-android`; the manifest already declares the optional `android.hardware.usb.host` feature), M4 (a spike on packaging an Android-hosted xtensa GCC toolchain as executables under `jniLibs/` so Android allows them to run), M5 (on-device compile), M6 (esptool USB upload + OTA), and M7 (library/board manager compatible with Arduino's package index). Jitpack is already listed in `settings.gradle.kts` repositories.
