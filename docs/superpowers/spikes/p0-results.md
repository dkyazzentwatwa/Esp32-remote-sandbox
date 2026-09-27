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

## Still to measure

On real phones (needs the maintainer, see `EspSketchIDE/spike/README.md`): exec/dlopen paths
(P0.1), Blink compile time and peak toolchain memory (P0.4), flash time (P0.5, flasher not
written yet). Also: the armeabi-v7a toolchain build.
