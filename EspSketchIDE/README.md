# EspSketchIDE

An open-source, from-scratch Android IDE for writing ESP32 / ESP8266 Arduino
sketches on your phone or tablet — inspired by
[ArduinoDroid](https://play.google.com/store/apps/details?id=name.antonsmirnov.android.arduinodroid2),
but independently written and MIT-licensed. It is **not** affiliated with or
a republish of ArduinoDroid.

## Status: alpha; compile and upload are experimental (v0.1.0-alpha)

EspSketchIDE is being built so that students (starting with the Circuit Hub
community) can write, compile and upload Arduino sketches **entirely from an
Android phone, offline**. The design and plan are in
[`../docs/superpowers/`](../docs/superpowers/). What works today:

- ✅ Sketch management: create, rename, delete and copy-from-example sketches
  in a folder you choose (Storage Access Framework, Arduino folder rules).
- ✅ Multi-file editor: a tab per file, Arduino/C++ highlighting, find and
  replace, Arduino/ESP autocomplete, undo/redo, a symbol bar for
  `{ } ( ) ; < > # "` and friends, unsaved-file markers (`name •`), pinch or
  Settings text size, word wrap, and autosave.
- ✅ Learning aids: compiler errors explained in plain language, an offline
  Arduino reference (long-press a function), autocomplete that fills in
  parameters, a pin guide for ESP32/ESP8266 boards, a lesson with every example,
  Auto format and comment/uncomment, and a serial plotter.
- ✅ Dark-by-default IDE theme (Light / Dark / Follow system) and a Settings
  screen (theme, font size, word wrap, symbol bar, board family, licenses).
- ✅ 17 example sketches in an Examples screen, with separate ESP32 and ESP8266
  versions where the APIs differ, and New sketch templates (Bare minimum,
  Serial, WiFi station). CI compiles every example for its board.
- 🧪 **Verify, Upload and Serial monitor (experimental)** for ESP32 boards, fully
  offline once the ESP32 board pack is installed. The compiler (Espressif's GCC
  14.2, built for Android) ships inside the APK; the build engine, image tools
  and USB flasher are written from scratch for this app. They match
  arduino-cli and esptool byte for byte in our emulated tests (Android
  binaries under qemu, the ESP32 ROM in Espressif's QEMU), **but haven't been
  tried on many real phones and boards yet**, so the app labels them
  experimental. Update (Oct 2026): compiling now runs on a real phone (Pixel XL,
  Android 10): Blink builds in about 70 s the first time and 27 s after that.
  Upload still needs testing on a real board.
- Only the libraries that come with the board pack (WiFi, BLE, Preferences,
  …) can be used for now; the library manager is next.
- Builds without the compiler (the plain `assembleDebug` below) hide these
  actions instead of showing buttons that can't work.

### Supported devices

| | |
|---|---|
| Phones | Android 8.0 (API 26) or newer, 64-bit or 32-bit ARM. Compiling needs a one-time ESP32 board pack: 36 MB download, 260 MB installed. The APK with the compiler is about 28 MB larger per CPU type (68 MB for both). |
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
such as `uint8_t`, function definitions and calls are all coloured. A small
Arduino grammar (`app/src/main/assets/textmate/arduino.tmLanguage.json`) is
injected on top to colour the Arduino/ESP API: functions such as `pinMode`,
constants such as `HIGH` and `LED_BUILTIN`, and classes such as `Serial` and
`WiFi`. `.txt` files are shown without highlighting.

## Roadmap

Done:

- [x] M0 — Project scaffold, MIT license
- [x] M1 — Sketch management (create/open/rename/delete)
- [x] M2 — Multi-file editor with syntax highlighting

Toward v0.1 (see the [plan](../docs/superpowers/plans/2026-09-26-public-release-plan.md)):

- [ ] P0 — Feasibility spike: run an Android build of the ESP32 GCC toolchain
      from the APK, compile Blink on a phone, flash it over USB — done under
      emulation; real phones and boards still to do
- [x] P1 — Editor fixes and student UX (symbol bar, examples, undo/redo, text
      size, C++ highlighting, targetSdk 36, CI, signed releases) — device
      testing still to do
- [x] P2 — Toolchain and ESP32 board-pack build pipelines
- [x] P3 — On-device build engine (`.ino` preprocessing, libraries, recipes)
- [x] P4 — Upload over USB (esptool ROM protocol) and serial monitor —
      device testing still to do
- [ ] P5 — Library manager (Arduino library index, `.zip` install)
- [ ] P6 — v0.1 beta with a Circuit Hub class

After v0.1: ESP32-S3/C3/C6, ESP8266, AVR Uno/Nano; Play Store and F-Droid.

## Building

Requires JDK 17+ and the Android SDK (compileSdk 37, targetSdk 36, minSdk 26).

```bash
./gradlew :app:testDebugUnitTest   # unit tests
./gradlew :app:assembleDebug       # editor-only APK at app/build/outputs/apk/debug/app-debug.apk
```

To include the compiler, build it with `toolchain/build-android.sh` (or take
it from a `espsketchide-toolchain-*` release) and pass one folder per CPU type:

```bash
./gradlew :app:assembleDebug \
  -PtoolchainDirs=arm64-v8a=/path/to/xtensa-esp-elf-host-…-arm64-v8a,armeabi-v7a=/path/to/…-armeabi-v7a
```

The board pack comes from `toolchain/make-pack.py`; install it in the app with
**Board pack → Import file**. `-PpackUrl=… -PpackSha256=…` adds a Download
button that fetches it instead.

See [CONTRIBUTING.md](CONTRIBUTING.md) for more, and [PRIVACY.md](PRIVACY.md)
for what the app does with your data (nothing leaves your phone).

## License

MIT — see [LICENSE](LICENSE).
