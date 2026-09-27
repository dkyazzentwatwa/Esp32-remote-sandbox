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

## Still to measure

Exec paths on real phones (P0.1), host toolchain size (P0.2), pack size (P0.3), on-phone
compile time and cc1plus memory (P0.4), flash time (P0.5).
