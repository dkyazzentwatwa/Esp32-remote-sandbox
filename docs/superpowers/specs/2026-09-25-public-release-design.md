# EspSketchIDE — Public Release Design

Status: approved design (brainstorm complete), 2026-09-26
Scope: `EspSketchIDE/` (Android app). Git root is the parent repo.

## Goal

Make EspSketchIDE a public, open-source educational tool so Circuit Hub students
can write, **compile on the phone, fully offline**, and upload Arduino sketches
over USB-OTG from Android phones.

## Decisions

| Topic | Decision |
|---|---|
| Compilation | On-device only, fully offline. No cloud compile server. |
| Board families | ESP32 classic first, end to end. Then ESP32-S3/C3/C6, ESP8266, AVR Uno/Nano. |
| Core version | Arduino-ESP32 3.x (3.3.12 at time of writing, toolchain `esp-x32 2601` = `xtensa-esp-elf` gcc 14.2.0). |
| Toolchain approach | **A**: Android-runnable GCC shipped inside the APK + Kotlin build engine. |
| Phone ABIs | `arm64-v8a` and `armeabi-v7a` (ABI-split APKs + universal APK). |
| Min Android | API 26 (Android 8), unchanged. |
| Target SDK | 36 (Google Play requirement since 2026-08-31). |
| iPhone | Out of scope; documented. |
| Distribution | Signed APKs on GitHub Releases first; Play / F-Droid later. |
| v0.1 "done" | ESP32 classic: examples + user libraries compile offline and flash over USB-OTG; editor bugs fixed. |

Rejected: **B** (Espressif LLVM/clang, untested with the GCC-built core, no lx106) — possible later
size optimisation. **C** (targetSdk 28 + glibc shim + stock arduino-cli) — permanently excludes Play.

## Measured facts (package index, 2026-09-26)

- Espressif ships ARM host toolchains only for glibc Linux (`aarch64-linux-gnu`, `arm-linux-gnueabi`); they do not run on Android as-is.
- `xtensa-esp-elf-14.2.0_20260121-aarch64-linux-gnu`: 314 MB compressed, 4,183 files; host `bin/`+`libexec/` = 172 MB, target data = 912 MB (ESP32 multilibs alone 377 MB, headers 37 MB).
- Host binaries actually needed ≈ 70 MB uncompressed (cc1plus 27.9, cc1 25.7, driver/as/ld/ar/collect2/objcopy/size ≈ 15). `lto1` (24.5 MB) not needed — Arduino-ESP32 does not use LTO.
- Per-chip precompiled SDK libs: `esp32-libs-3.3.12.zip` = 45 MB.
- `platform.txt` uses `/usr/bin/env bash -c` hooks (partitions, bootloader, `build_opt.h`, `file_opts`, `sdkconfig`, `flash_args`) and a 72 MB PyInstaller `esptool` for `elf2image`/`merge-bin`. These cannot be executed on Android and must be reimplemented.

## Platform constraint

On Android 10+ with targetSdk ≥ 29, apps may only execute files installed with the APK
(the native library directory). Downloaded files cannot be executed or `mmap`-ed `PROT_EXEC`.
Therefore: **host executables ship in the APK; everything else is downloadable data.**

---

## 1. Toolchain (CI-built, shipped in APK)

- Separate repo `espsketchide-toolchains`: crosstool-NG canadian-cross build of Espressif's
  `xtensa-esp-elf` gcc 14.2.0 for hosts `aarch64` and `armv7a`.
- **Primary: Android NDK (bionic) dynamic build**, `--host=aarch64-linux-android26` /
  `armv7a-linux-androideabi26`. Binaries use `/system/bin/linker64` (present on every device)
  as their ELF interpreter, so they run from `nativeLibraryDir` like Termux's tools.
  *Changed 2026-09-27 (P0 finding):* Espressif's unified `xtensa-esp-elf` toolchain has no
  chip configuration compiled in; gcc, as and ld load it at run time from a plugin
  (`-mdynconfig=xtensa_esp32.so` / `XTENSA_GNU_CONFIG`) with `dlopen`. Fully static musl
  binaries cannot `dlopen`, so static musl is only possible by diverging from Espressif's
  build (static per-chip config), which we reject. The plugins (`xtensa_esp32.so`, later
  `_esp32s3.so`) ship as `libxtensa_esp32.so` in `jniLibs`.
- The `xtensa-esp32-elf-*` names are thin wrappers that add `-mdynconfig=xtensa_esp32.so`;
  the build engine passes that flag (and sets `XTENSA_GNU_CONFIG` for `as`/`ld`) itself
  instead of shipping the wrappers.
- Only host programs are built (`make all-gcc`, `all-binutils all-gas all-ld`); target
  libraries come from Espressif's release, which was built from the same sources and
  configure options (read from `xtensa-esp-elf-gcc -v`).
- APK contents: `gcc`, `g++`, `cc1`, `cc1plus`, `collect2`, `as`, `ld`, `ar`, `objcopy`, `size`,
  renamed `lib<name>.so` under `jniLibs/<abi>/`. `extractNativeLibs=true` / `useLegacyPackaging`.
  Estimated 20–25 MB compressed per ABI (to verify in CI).
- On first run the app builds a gcc-shaped directory tree in app storage
  (e.g. `libexec/gcc/xtensa-esp-elf/14.2.0/cc1plus`) of symlinks into `nativeLibraryDir`.
- Target sysroot (newlib, libstdc++, libgcc, ldscripts) is pruned to the ESP32 multilib(s)
  in use and goes in the board pack.
- Licensing: app stays MIT; each release links the exact GCC/binutils source and build scripts (GPL compliance).
- App build fetches toolchain artifacts at a pinned version with pinned sha256.

## 2. Board pack (own format, CI-built from upstream)

- Artifact: `esp32-3.3.12-pack.tar.zst` containing:
  - `cores/esp32`, `variants/esp32`, bundled `libraries/`, `boards.txt`, `platform.txt`
  - `esp32-libs` (precompiled SDK) and the pruned toolchain sysroot
  - prebuilt bootloader `.bin`s (elf2image done in CI), `boot_app0.bin`, partition CSVs
  - precompiled `core.a` for default ESP32 Dev Module options
  - `pack.json`: version, per-file sha256, board menu options, unpacked size
- Hosted on GitHub Releases; discovered via `packs-index.json`.
- Install: hash-verified, resumable download, extract to app-private storage;
  sideload from local file for offline classrooms. Show size first; warn on metered network.
- Estimated 80–150 MB download (unverified; depends on pruning).

## 3. Build engine (Kotlin, foreground service)

1. **Stage**: copy sketch from SAF into `files/build/<sketch-hash>/src` (gcc needs real paths).
2. **`.ino` preprocessing** (arduino-cli semantics, no ctags): concatenate tabs (main `.ino`
   first, rest alphabetical), inject `#include <Arduino.h>`, emit `#line` directives, generate
   prototypes via a Kotlin C++ scanner for top-level function definitions (skip templates and
   already-declared functions).
3. **Library discovery**: loop `cc1plus -E`; on `fatal error: Foo.h: No such file`, resolve
   `Foo.h` from the header→library index, add include path, repeat. Cache results.
4. **Recipes**: flags, include paths and link line expanded from `platform.txt`/`boards.txt`
   (`{build.*}` and menu options). Bash/esptool hooks reimplemented in `Esp32BuildProfile`:
   partition CSV selection, `build_opt.h`, `file_opts`, `sdkconfig` copy, bootloader selection,
   `gen_esp32part`, `elf2image` (incl. `--elf-sha256-offset 0xb0`), `merge-bin`, `flash_args`.
   ESP8266/AVR get their own profiles later.
5. **Incremental**: object cache keyed by hash(source + flags); prebuilt `core.a` reused unless
   board options differ from defaults.
6. **UX**: progress, cancel, clickable diagnostics mapped via `#line` to the user's file/line.
- Targets to validate in P0: Blink first build < 60 s (prebuilt core) on a mid-range arm64
  phone; edit-rebuild < 15 s.
- Verification: golden tests vs desktop arduino-cli — byte-identical objects where possible,
  section-level comparison for the final image (timestamps differ).

## 4. Upload + serial monitor

- `usb-serial-for-android` (MIT): CP210x, CH340/CH9102, FTDI, CDC; USB device filter XML for auto-launch.
- Auto-reset: esptool classic DTR/RTS sequence, retry with alternate timings; fallback UI
  "hold BOOT, tap EN" with diagram.
- v0.1 uses the **ESP32 ROM loader only** (no stub): SLIP, `SYNC`, `READ_REG` chip detect,
  `CHANGE_BAUDRATE` 460800, `SPI_ATTACH`, `SPI_SET_PARAMS`, then per image `FLASH_BEGIN` /
  `FLASH_DATA` (1 KiB blocks, last one padded with 0xFF) and `SPI_FLASH_MD5` verify, then a
  hard reset over RTS. *Changed 2026-09-27:* uncompressed writes by default, as esptool does
  with the ROM loader; no `FLASH_END` (in the ROM it exits the loader). Verified against
  Espressif's emulated ESP32 ROM (QEMU): the flash image is byte-identical to esptool's.
  Compressed `FLASH_DEFL_*` is implemented but off until checked on real chips (on the emulated
  ROM it leaves the bytes after each image unerased).
- Stub flasher deferred to v0.2 (needed for ESP8266; GPL-2.0 licensing to review).
- Serial monitor on the same connection: baud, autoscroll, send box, line endings; auto
  release/reacquire the port around uploads.
- Tests: JVM protocol tests against a fake serial port replaying captured esptool traces;
  manual hardware matrix (ESP32 DevKitC/CP2102, CH340 clone, CH9102 board).

## 5. Library manager

- Download `library_index.json.gz`, stream-parse into a compact local SQLite index; filter
  `architectures` ∋ `esp32` or `*`.
- Install: version pick, `depends` resolution (latest compatible; conflict warnings), checksum
  verify, unzip into app-private storage. Library examples appear in the Examples browser.
- `.zip` install via SAF for offline classes.
- Import libraries from the user's SAF sketchbook `libraries/` folder (and stop listing it as a sketch).
- v0.1 must-have: build-time library resolution. Browse/search UI: in v0.1 but first to cut.

## 6. Editor fixes + UX

Bug fixes (each with a regression test first):
1. `addFile` uses `text/plain` → `foo.h.txt`. Use `application/octet-stream`.
2. `renameSketch` renames folder first, leaving `.ino` stale. Rename `.ino` first, then folder; roll back on failure.
3. Main-thread I/O without error handling (incl. `onPause`). Move to coroutines, snackbar errors, crash-safe autosave.
4. `setupFileTabs` adds a duplicate `OnTabSelectedListener` per `addFile`. Attach once.
5. `deleteSketch` ignores its result; non-sketch dirs listed. Surface failures; list only folders containing `<name>.ino`.

UX:
- Symbol bar above the keyboard: `{ } ( ) ; < > = " # & | [ ] /` and Tab.
- Examples: Blink, Serial, Button, AnalogRead, Fade (PWM), WiFi scan, WiFi web server, BLE scan, plus pack/library examples.
- Undo/redo buttons; font size (pinch + setting).
- C++ TextMate grammar (sora-editor) replacing Java highlighting.
- Board + port picker with ESP32 Dev Module menus (partition scheme, flash freq, upload speed).
- Build output panel with clickable errors.
- One-screen "first upload" guide (OTG adapter, data cable, BOOT button).

## 7. Release, CI, hygiene

- `compileSdk`/`targetSdk` 36; `minSdk` 26.
- ABI-split APKs (`arm64-v8a`, `armeabi-v7a`) + universal; release keystore in GitHub Actions secrets.
- CI on PR: lint, JVM unit tests (preprocessor, recipe expansion, `gen_esp32part`,
  `elf2image` golden vs esptool, SLIP/ROM traces, dependency resolver), debug build.
- CI on tag: release APKs + board pack → GitHub Releases. Toolchain repo has its own workflow.
- Optional: CI-only `x86_64` toolchain so an emulator test can compile end to end.
- Docs: privacy policy (no data collected; network only for packs/libraries), CONTRIBUTING,
  GPL source links, supported boards/phones matrix, "no iPhone" note.

## Phases

Named P0–P6 to avoid clashing with the README's existing M0–M8 roadmap.

| | Phase | Exit criterion |
|---|---|---|
| P0 | Spike (go/no-go) | CI-built static `cc1plus` runs from `nativeLibraryDir` on Android 8 and 15 devices; Blink compiles with a hand-made pack; minimal Kotlin ROM flasher uploads it. Failure → revisit design. |
| P1 | Editor fixes + UX + targetSdk/CI/signing | Parallel with P0. Bugs 1–5 fixed with tests. |
| P2 | Toolchain + pack pipelines | Tagged, reproducible artifacts with sha256. |
| P3 | Build engine | Example set compiles; golden tests pass. |
| P4 | Upload + serial monitor | Hardware matrix passes. |
| P5 | Library manager | Resolve/install from index and `.zip`. |
| P6 | v0.1 beta | One Circuit Hub class uses it. |

After v0.1: v0.2 ESP32-S3/C3/C6 (adds `riscv32-esp-elf`) + stub flasher; v0.3 ESP8266
(`xtensa-lx106-elf`); v0.4 AVR Uno/Nano (avr-gcc, STK500).

## Open risks

- Static musl gcc performance / allocator on phones; 32-bit devices with ≤ 2 GB RAM compiling WiFi/BLE sketches.
- OEM restrictions on executing from `nativeLibraryDir` or symlinks to it.
- Pack size after pruning; which ESP32 lib variants the default board needs.
- ROM-only flash speed for ~1 MB images.
- Prototype generator edge cases (templates, `#ifdef`-wrapped functions, default args).
- Hardware: charge-only cables, phones without OTG power, boards with broken auto-reset.
