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

The project has no unit or instrumented tests yet (there is no `app/src/test` or `app/src/androidTest`). JUnit4 and Espresso are declared as dependencies. When you add tests, run a single one like this: `./gradlew :app:testDebugUnitTest --tests "org.espsketchide.app.SomeTest"`.

## Architecture

It is a single-module app (`:app`, package `org.espsketchide.app`) built on plain Activities with ViewBinding. There are no ViewModels, DI, or coroutines yet, although the lifecycle/viewmodel dependencies are present.

- **Storage runs entirely through the Storage Access Framework.** On first launch the user picks a root folder with `OpenDocumentTree`. `SketchRepository` persists the tree URI in SharedPreferences (`esp_sketch_ide` / `sketches_root_uri`) and takes a persistable read/write grant. `rootUri()` returns null once that grant is revoked. All file access goes through `DocumentFile` and `ContentResolver` streams. Never use `java.io.File` paths.
- **Sketch model follows Arduino conventions.** A sketch is a directory under the root whose name matches its primary `<name>.ino`. Sketch names must match `^[A-Za-z][A-Za-z0-9_]*$`. Renaming a sketch renames both the folder and the primary `.ino`. Only the extensions in `EDITABLE_EXTENSIONS` (`ino, h, hpp, c, cpp, cc, txt`) are listed or editable, and the primary `.ino` always sorts first.
- **Screen flow:** `SketchListActivity` (launcher; list/create/rename/delete, change root) → `EditorActivity`. The sketch is passed as two extras, `EXTRA_SKETCH_NAME` and `EXTRA_SKETCH_FOLDER_URI`, rather than a Parcelable.
- **Editor:** `EditorActivity` shows one TabLayout tab per sketch file and a single shared sora-editor `CodeEditor`. It saves the current file when you switch tabs, in `onPause`, and from the Save menu action. There is no dirty tracking.
- **Syntax highlighting:** sora-editor's `language-java` (`JavaLanguage`) stands in for C/C++. The editor uses the Darcula or GitHub scheme depending on night mode. Preprocessor lines and `uint8_t`-style types are not highlighted. A real C/Arduino grammar is a roadmap item, so keep the README's "About the syntax highlighting" section accurate if this changes.
- File I/O currently runs on the main thread.

## Roadmap context for future work

The next milestones are M3 (USB serial monitor via `usb-serial-for-android`; the manifest already declares the optional `android.hardware.usb.host` feature), M4 (a spike on packaging an Android-hosted xtensa GCC toolchain as executables under `jniLibs/` so Android allows them to run), M5 (on-device compile), M6 (esptool USB upload + OTA), and M7 (library/board manager compatible with Arduino's package index). Jitpack is already listed in `settings.gradle.kts` repositories.
