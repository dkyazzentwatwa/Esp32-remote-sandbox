# P0 spike results (in progress)

Go/no-go record for the on-device toolchain. Filled in as each P0 task finishes.

## Findings so far

### 2026-09-27: Espressif's toolchain needs `dlopen`; static musl is out

- `xtensa-esp-elf-gcc -print-multi-lib` (esp-x32 2601, gcc 14.2.0) shows every multilib is
  selected with `-mdynconfig=xtensa_<chip>.so`. The `xtensa-esp32-elf-*` programs are
  wrappers that add that flag and exec the unified tools, which `dlopen` the plugin from
  `lib/`. The crosstool-NG sample confirms `CT_XTENSA_DYNCONFIG=y`.
- A fully static musl binary can't `dlopen`. Primary route is now the NDK (bionic) build,
  whose binaries use `/system/bin/linker64` and can load `libxtensa_esp32.so` from the
  app's native library directory.
- Arduino-ESP32 3.3.12 calls `xtensa-esp32-elf-gcc`/`g++` (`compiler.prefix =
  {build.tarch}-{build.target}-elf-`); the SDK flag files (`flags/c_flags`) add
  `-mlongcalls`, `-mdisable-hardware-atomics` and the PSRAM fixes but not `-mdynconfig`.
  So our build engine must add `-mdynconfig=xtensa_esp32.so` itself.

### 2026-09-27: Desktop reference

- arduino-cli (1.5.2-rc.1, from downloads.arduino.cc) with esp32 core 3.3.12 compiles all
  eight bundled examples for `esp32:esp32:esp32` (Blink 257,628 bytes / 19% ... BLEScan
  1,080,375 bytes / 82% of the default app partition).

### 2026-09-27: Relocated layout proven on the desktop (P0.4 dry run)

Simulated the Android install on x86 with Espressif's Linux toolchain: binaries copied into
one flat directory with `lib<name>.so` names (as `nativeLibraryDir` looks), a link tree like
the app will create in `filesDir/tc`, and target files in the tree. Replaying all 122
compile/archive/link commands from arduino-cli's verbose Blink build through it produced an
ELF whose `.flash.text`, `.flash.rodata`, `.iram0.text` and `.dram0.data` are
**byte-identical** to arduino-cli's (20 s sequential on the 4-core container).

Rules the build engine must follow (each one failed when broken):

1. gcc resolves links in its own path (`make_relative_prefix` uses `lrealpath`), so launched
   via a link it can't find `cc1`. Set `GCC_EXEC_PREFIX=<tc>/lib/gcc/` and pass
   `-B<tc>/libexec/gcc/xtensa-esp-elf/14.2.0/ -B<tc>/xtensa-esp-elf/bin/ --sysroot=<tc>/xtensa-esp-elf`.
2. The multilib is chosen by matching the option text `-mdynconfig=xtensa_esp32.so`
   literally. A full path silently selects the default multilib and the link fails with
   "cross-endian linking not supported". Pass the bare name and set
   `XTENSA_GNU_CONFIG=<tc>/lib/` containing a `xtensa_esp32.so` link to the plugin.
3. Espressif's desktop gcc defaults to the LTO linker plugin at link time; our Android build
   uses `--disable-lto`, so this doesn't apply there (desktop replay needs
   `-fno-use-linker-plugin`).

### 2026-09-27: Android builds (arm64-v8a, NDK r29, API 26)

- `xtensa_esp32.so` dynconfig plugin: 400 KB, needs only `libc.so`/`libdl.so`.
- binutils 2.43.1 (Espressif fork): builds in ~2 min on 4 cores. `as`/`ld` are PIE
  executables with interpreter `/system/bin/linker64`, needing only `libc.so`/`libdl.so`.
- gcc 14.2.0 (`all-gcc`): ~4 min on 4 cores. Four build fixes were needed, each now in
  `toolchain/build-android.sh`:
  1. `--enable-host-pie`: gcc 14 builds its programs `-fno-PIE` by default; lld then fails
     with `R_AARCH64_LDST64_ABS_LO12_NC` alignment errors against bionic, and Android wouldn't
     run non-PIE executables anyway.
  2. A build-machine `xtensa-esp-elf-gcc` of the same version on `PATH` (canadian cross needs
     it to generate `specs`); Espressif's desktop release is used.
  3. `LDFLAGS=-static-libstdc++` so programs need only `libc/libm/libdl`, not `libc++_shared.so`.
  4. `--enable-plugin` (gcc) and `--enable-plugins` (binutils): both refuse
     `XTENSA_GNU_CONFIG` ("plugin support is disabled") without them.
- Host set (gcc, g++, cc1, cc1plus, collect2, as, ld, ar, objcopy, size, plugin):
  **58 MB uncompressed, 15 MB `.tar.xz`, ~23 MB zip-compressed**; the probe APK with it is
  29.7 MB. Under the 35 MB target.

### 2026-09-27: Android binaries compile Blink (under emulation) — P0.2 PASS

Android arm64 binaries were run in the x86 container with `qemu-aarch64-static` via
binfmt_misc, using `linker64`, `libc`, `libm`, `libdl` and `ld-android` extracted from the
official Android 11 (API 30) arm64 emulator system image (runtime APEX). `spike/rehearse.py`
lays everything out exactly like the probe app and replays the pack's 122 commands.

- The Android-built toolchain compiled and linked Blink (237 s under emulation; not a phone
  timing).
- Built in the same directory path as Espressif's desktop toolchain, `.flash.text`,
  `.iram0.text` and `.dram0.data` are byte-identical; `.flash.rodata` differs in 4 bytes, the
  `__TIME__` in Arduino's "Software Info" banner (07:09:48 vs 07:12:01). **The Android build
  produces the same firmware as Espressif's.**
- Correction: an earlier "0 of 61 objects differ" check was invalid (objcopy to /dev/stdout
  through a pipe yields nothing); it is superseded by the section comparison above.

### 2026-09-27: Pack for Blink (P0.3 measurement)

`spike/make-probe-pack.py` copies only files the toolchain opened (strace) during the Blink
build: **796 files, 167.5 MB unpacked (120 MB Arduino core + SDK, 51 MB gcc target files),
27.9 MB `.tar.xz`**. A pack-only replay reproduces the desktop build. WiFi/BLE sketches will
need more SDK libraries; measure them before fixing the pack format.

### 2026-09-27: Kotlin ROM flasher matches esptool (P0.5 / P4 core, emulated)

New pure-JVM module `EspSketchIDE/esptool` (SLIP, ROM commands, flash flow, auto-reset lines),
written from Espressif's protocol documentation. Tested with 15 unit tests against our own
model of the ROM, and against **Espressif's QEMU ESP32 (esp_develop_9.2.2_20260417) in
download mode**: flashing Blink's bootloader, partition table, boot_app0 and app gives a 4 MB
flash image **byte-identical** to `esptool --no-stub write-flash` of the same files.

Two corrections found this way (esptool read as a behaviour reference):
- esptool writes **uncompressed** (`FLASH_BEGIN`/`FLASH_DATA`) with the ROM loader; its
  compressed path is for the stub. On the emulated ROM our compressed writes left the bytes
  after each image unerased, so compressed mode is now opt-in.
- The ROM rejects/acts on `FLASH_END` by leaving the loader; esptool doesn't send it. We
  don't either and reset over RTS instead.

The emulated QEMU image also boots Blink up to flash init, where it stops because QEMU's flash
model lacks QIO mode (`qio_mode: Failed to set QIE bit`); a DIO build would boot fully.

### 2026-09-27: armeabi-v7a toolchain works too (emulated)

- Built with `toolchain/build-android.sh armeabi-v7a` after one fix: GMP/MPFR/MPC need
  `--with-pic`, and GMP's 32-bit ARM assembly isn't PIC (`--disable-assembly` there).
  57 MB unpacked, 16 MB `.tar.xz`.
- No Android 8+ emulator image ships 32-bit bionic, so the test used Android 7.1 (API 25)
  armeabi-v7a bionic with three test-only accommodations: `DF_1_PIE` cleared in a copy of the
  binaries (the 7.1 linker rejects it; 8.0+ accepts it), an `LD_PRELOAD` shim for
  `nl_langinfo` (the only import missing from API 25 of 220), and `ANDROID_ROOT=/system`
  (bionic crashes in its tzdata lookup for `__TIME__` without it; Android always sets it).
- Blink through the phone layout: `.flash.text`, `.iram0.text`, `.dram0.data` identical to
  Espressif's desktop toolchain; `.flash.rodata` same size (differs only in `__TIME__`).
  227 s under qemu-arm.

### 2026-09-27: Build engine status

`EspSketchIDE/buildengine` builds sketches end to end with the same results as arduino-cli
(properties, `.ino.cpp`, libraries, partition tables, bootloader, app/merged images byte for
byte; full Blink/WiFi builds with identical code sections and sizes), and drives the relocated
phone toolchain through `XtensaToolchainRunner`. Library discovery preprocesses every library
source: ~16 s per WiFi/BLE sketch on the desktop, likely minutes on a phone, so bundled-library
dependencies should be precomputed into the board pack (P2).

## Still to measure

On real phones (needs the maintainer, see `EspSketchIDE/spike/README.md`): exec/dlopen paths
(P0.1), Blink compile time and peak toolchain memory (P0.4), flash time (P0.5, flasher not
written yet). Also: the armeabi-v7a toolchain build.

## App pipeline under emulation (2026-09-27)

`app/src/test/.../compile/OnDeviceCompileTest` runs the app's real compile path: `PackManager`
installs `esp32-3.3.12.tar.xz` (7743 files, all sha256-checked), `Toolchain` links the
arm64 Android binaries (named as in the APK, `libxt*.so`) into a tree, `SketchStager` copies
sketches from (fake) SAF storage, and `BuildController` + `SketchCompiler` build them, with
the Android binaries running under qemu-aarch64 + bionic from the emulator image.

| Sketch | Program (app) | Program (arduino-cli) | Globals (both) |
|---|---|---|---|
| Blink | 257 676 | 257 628 | 22 100 |
| WiFiScan | 877 108 | 877 060 | 45 680 |

Globals match. Program is 48 bytes larger in both, which fits longer embedded `__FILE__`
strings (pack path under the test's temp folder vs `~/.arduino15`), the same effect
`PackBuildTest` documents; not proven byte by byte. A deliberate typo comes back as a
`Blink.ino:2` diagnostic. About 5 minutes for both builds under emulation, which says
nothing about phone speed.

A debug APK bundling both ABIs is 67.7 MB (editor-only: 10.9 MB).
Still unverified: real phones (W^X exec from nativeLibraryDir on each Android version,
build time, memory), USB permission and upload on real CP210x/CH340 boards, 460800 baud.
