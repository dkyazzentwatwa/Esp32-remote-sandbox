# P0 spike tools (throwaway)

Everything here exists to answer the P0 go/no-go question in
`docs/superpowers/spikes/p0-results.md`. None of it ships in the app.

| File | What it does |
|---|---|
| `../toolchain/build-android.sh` | Builds Espressif's xtensa-esp-elf gcc/binutils + esp32 dynconfig plugin for Android (NDK). Not throwaway; promoted in P2. |
| `make-probe-pack.py` | Replays a desktop `arduino-cli compile -v` Blink build under strace and packs exactly the files it read, plus `commands.jsonl` with `@TC@/@PACK@/@BUILD@` placeholders. |
| `rehearse.py` | Rehearses the phone build on Linux: lays out the Android toolchain like the probe app does and replays `commands.jsonl`. Android arm64 binaries run via qemu-user + binfmt_misc with an Android root (`QEMU_LD_PREFIX`). |
| `execprobe/` | Probe app: exec/dlopen checks, toolchain start-up check, and "Compile Blink" with timings and peak memory. Built only with `-Pspike`. |

## Running the probe on a phone

```bash
# 1. Build the Android toolchain (arm64) and the probe APK with it bundled
ANDROID_NDK_HOME=... BUILD_XTENSA_BIN=~/.arduino15/packages/esp32/tools/esp-x32/2601/bin \
  toolchain/build-android.sh arm64-v8a
ANDROID_NDK_HOME=... ./gradlew -Pspike \
  -PtoolchainDirs=arm64-v8a=toolchain-work/out/xtensa-esp-elf-host-esp-14.2.0_20260121-arm64-v8a \
  :spike-execprobe:assembleDebug

# 2. Make the Blink pack from a desktop reference build
arduino-cli compile -v --clean --fqbn esp32:esp32:esp32 --build-path /tmp/ref app/src/main/assets/examples/01.Basics/Blink > blink-verbose.log
python3 spike/make-probe-pack.py --log blink-verbose.log --build /tmp/ref --out probe-pack

# 3. Install, push the pack, run
adb install spike/execprobe/build/outputs/apk/debug/spike-execprobe-debug.apk
adb push probe-pack /sdcard/Android/data/org.espsketchide.spike.execprobe/files/
# open "EspSketch P0 probe": tap Probes, then Compile Blink, then Share the log
```

The app folder under `/sdcard/Android/data/` exists after the app has been opened once.
