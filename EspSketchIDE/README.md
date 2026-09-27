# EspSketchIDE

An open-source, from-scratch Android IDE for writing ESP32 / ESP8266 Arduino
sketches on your phone or tablet — inspired by
[ArduinoDroid](https://play.google.com/store/apps/details?id=name.antonsmirnov.android.arduinodroid2),
but independently written and MIT-licensed. It is **not** affiliated with or
a republish of ArduinoDroid.

## Status: early scaffold (v0.1.0-alpha)

This is the first milestone of an incremental build-out. What works today:

- ✅ Sketch management: create, rename, delete sketches (folders backed by
  the Storage Access Framework, following the Arduino convention that a
  sketch folder's name matches its primary `.ino` file).
- ✅ Multi-file editor with tabs per sketch, syntax highlighting, and
  save-on-switch/save-on-exit.
- 🚧 **Compiling and uploading sketches is not implemented yet.** See
  Roadmap below — this is deliberately not stubbed in as a fake button,
  so the app only claims to do what it actually does.

### About the syntax highlighting

`.ino`, `.h`, `.hpp`, `.c`, `.cpp` and `.cc` files are highlighted with the
TextMate C++ grammar that VS Code ships (from
[jeff-hykin/better-cpp-syntax](https://github.com/jeff-hykin/better-cpp-syntax),
MIT; see `app/src/main/assets/textmate/cpp/NOTICE.md`), rendered by
[sora-editor](https://github.com/Rosemoe/sora-editor)'s TextMate module with
the app's own light and dark themes. Preprocessor lines, fixed-width types
such as `uint8_t`, function definitions and calls are all coloured. Arduino
names like `pinMode` or `OUTPUT` are ordinary identifiers to a C++ grammar, so
they are not specially coloured. `.txt` files are shown without highlighting.

## Roadmap

- [x] M0 — Project scaffold, MIT license
- [x] M1 — Sketch management (create/open/rename/delete)
- [x] M2 — Multi-file editor with syntax highlighting
- [ ] M3 — USB serial monitor/plotter (via
      [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android))
- [ ] M4 — Toolchain research spike: how to package an Android-hosted
      `xtensa-esp32-elf`/`xtensa-esp8266-elf` GCC toolchain (Espressif does
      not publish one; this needs either a crosstool-NG cross-build or a
      located prebuilt), shipped as executables under `jniLibs/` so
      Android will let the app execute them
- [ ] M5 — On-device compilation
- [ ] M6 — Upload over USB (esptool protocol) and WiFi OTA
- [ ] M7 — Library/board manager compatible with Arduino's package index
      format
- [ ] M8 — Themes, example sketches, settings, F-Droid/GitHub Releases

## Building

Requires JDK 17+ and the Android SDK (compileSdk 34, minSdk 26).

```bash
./gradlew :app:assembleDebug
```

The resulting APK is at `app/build/outputs/apk/debug/app-debug.apk`.

## License

MIT — see [LICENSE](LICENSE).
