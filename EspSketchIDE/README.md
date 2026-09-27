# EspSketchIDE

An open-source, from-scratch Android IDE for writing ESP32 / ESP8266 Arduino
sketches on your phone or tablet — inspired by
[ArduinoDroid](https://play.google.com/store/apps/details?id=name.antonsmirnov.android.arduinodroid2),
but independently written and MIT-licensed. It is **not** affiliated with or
a republish of ArduinoDroid.

## Status: editor alpha (v0.1.0-alpha)

EspSketchIDE is being built so that students (starting with the Circuit Hub
community) can write, compile and upload Arduino sketches **entirely from an
Android phone, offline**. The design and plan are in
[`../docs/superpowers/`](../docs/superpowers/). What works today:

- ✅ Sketch management: create, rename, delete and copy-from-example sketches
  in a folder you choose (Storage Access Framework, Arduino folder rules).
- ✅ Multi-file editor: a tab per file, C/C++ highlighting, undo/redo, a
  symbol bar for `{ } ( ) ; < > # "` and friends, adjustable text size, and
  autosave.
- ✅ Eight example sketches (Blink through WiFi and Bluetooth) that are checked
  in CI to compile for the ESP32.
- 🚧 **Compiling and uploading are not implemented yet.** They are not stubbed
  in as fake buttons, so the app only claims what it actually does.

### Supported devices

| | |
|---|---|
| Phones | Android 8.0 (API 26) or newer, 64-bit or 32-bit ARM. Compiling will need a one-time ESP32 board-pack download (estimated 80–150 MB; to be measured). |
| Boards (first) | ESP32 "classic" dev boards (ESP32 DevKitC and clones) with a CP210x, CH340/CH9102 or FTDI USB chip |
| Boards (later) | ESP32-S3/C3/C6, ESP8266, Arduino Uno/Nano |
| Not supported | **iPhone/iPad**: iOS doesn't allow apps to run a compiler or talk to USB-serial boards. |

For uploading you'll need a **USB-OTG adapter** and a **data** cable (many
cheap cables only carry power).

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

Done:

- [x] M0 — Project scaffold, MIT license
- [x] M1 — Sketch management (create/open/rename/delete)
- [x] M2 — Multi-file editor with syntax highlighting

Toward v0.1 (see the [plan](../docs/superpowers/plans/2026-09-26-public-release-plan.md)):

- [ ] P0 — Feasibility spike: run an Android build of the ESP32 GCC toolchain
      from the APK, compile Blink on a phone, flash it over USB
- [x] P1 — Editor fixes and student UX (symbol bar, examples, undo/redo, text
      size, C++ highlighting, targetSdk 36, CI, signed releases) — device
      testing still to do
- [ ] P2 — Toolchain and ESP32 board-pack build pipelines
- [ ] P3 — On-device build engine (`.ino` preprocessing, libraries, recipes)
- [ ] P4 — Upload over USB (esptool ROM protocol) and serial monitor
- [ ] P5 — Library manager (Arduino library index, `.zip` install)
- [ ] P6 — v0.1 beta with a Circuit Hub class

After v0.1: ESP32-S3/C3/C6, ESP8266, AVR Uno/Nano; Play Store and F-Droid.

## Building

Requires JDK 17+ and the Android SDK (compileSdk 37, targetSdk 36, minSdk 26).

```bash
./gradlew :app:testDebugUnitTest   # unit tests
./gradlew :app:assembleDebug       # APK at app/build/outputs/apk/debug/app-debug.apk
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for more, and [PRIVACY.md](PRIVACY.md)
for what the app does with your data (nothing leaves your phone).

## License

MIT — see [LICENSE](LICENSE).
