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

## Still to measure

Exec paths on real phones (P0.1), host toolchain size (P0.2), pack size (P0.3), on-phone
compile time and cc1plus memory (P0.4), flash time (P0.5).
