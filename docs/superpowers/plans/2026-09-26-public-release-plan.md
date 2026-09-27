# EspSketchIDE Public Release — Implementation Plan

Spec: `docs/superpowers/specs/2026-09-25-public-release-design.md` (read it first).
Branch: `claude/espsketchide-public-plan-wco4nq`. App root: `EspSketchIDE/` (git root is its parent).

**Goal:** v0.1 — ESP32 classic sketches (examples + user libraries) compile fully offline on an
Android phone and flash over USB-OTG; editor bugs fixed; signed APKs on GitHub Releases.

**Architecture in one paragraph:** Host executables (a static-musl build of Espressif's
`xtensa-esp-elf` gcc 14.2.0) ship in the APK as `lib*.so`; everything else (core, SDK libs,
target sysroot, prebuilt `core.a`) is a downloadable board pack. A pure-Kotlin/JVM build engine
reads `platform.txt`/`boards.txt`, preprocesses `.ino`, resolves libraries by running the
preprocessor, runs gcc via `ProcessBuilder`, and reimplements the bash/esptool hooks
(`gen_esp32part`, `elf2image`, `merge-bin`). A pure-Kotlin esptool ROM-loader client flashes over
`usb-serial-for-android`.

---

## Conventions (apply to every task)

- **TDD:** write the failing test, run it and see it fail, implement, see it pass, commit.
  One task = one commit (or a few). Message style: imperative, e.g. `Fix addFile MIME type`.
- **Pure JVM first.** Anything that doesn't need Android APIs goes in a `kotlin("jvm")` module
  so it's testable in CI and in the cloud container without a device.
- **Clean-room rule.** arduino-cli (GPL-3.0) and esptool (GPL-2.0) are *behavioural references
  only*. Do not copy their code or test data into this MIT repo. Write our own tests from
  documented behaviour and from outputs we generate ourselves.
- **Honest UI rule (CLAUDE.md).** Never show a Compile/Upload button before it works end to end
  on a real device. Gate unfinished features behind a `BuildConfig.EXPERIMENTAL` flag.
- **Commands** (run from `EspSketchIDE/`):
  - `./gradlew test` — all JVM unit tests
  - `./gradlew :app:testDebugUnitTest --tests "org.espsketchide.app.X"` — one test
  - `./gradlew :app:assembleDebug :app:lint`
  - Cloud sessions: `.claude/hooks/session-start.sh` installs the Android SDK under
    `~/android-sdk`, writes `local.properties`, adds a Gradle init script that tries Google's
    Maven Central mirror first (Maven Central often answers `429` from cloud IPs), and warms
    the dependency cache. No KVM, so no emulator; `qemu-user-static` can be apt-installed to
    smoke-test ARM binaries.

## What needs a human (can't be done from the cloud container)

| When | What | Why |
|---|---|---|
| P0.3, P0.6, P4 | 2 Android phones: one **Android 8/9**, one **Android 14–16**; ideally one 32-bit ARM phone | Exec-from-`nativeLibraryDir`, speed, OTG power vary by OEM/version. No KVM here, so no emulator. |
| P0.6, P4 | ESP32 DevKitC (CP2102), a CH340 clone, a CH9102 board; USB-OTG adapter; data cables | Upload hardware matrix |
| P1.10 | Create release keystore; add `RELEASE_KEYSTORE_B64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` as repo Actions secrets | Signing; never commit the key |
| P2.1 | Decide: keep toolchain build in-tree (`toolchain/`) or create repo `espsketchide-toolchains` | Spec says separate repo; plan starts in-tree to move fast, split later |
| P6 | Pick the Circuit Hub beta class; collect feedback | Beta |

## Phase overview and order

```
P1 editor/hygiene ─────────────────────────────┐   (parallel, all doable in cloud)
P0 go/no-go spike ── P2 toolchain+pack CI ── P3 build engine ── P4 upload ── P5 libs ── P6 beta
                     (P3 JVM work can start as soon as P0.2 gives a desktop pack)
```

P0 is the gate. If P0 fails, stop and revisit the spec's section 1 — do not start P2.

---

# P0 — Go/no-go spike

Output: `docs/superpowers/spikes/p0-results.md` with measured numbers and a GO/NO-GO.
Code from P0 is throwaway unless a later task says "promote".

### P0.1 Exec mechanism with a trivial binary (cloud + device)
- [ ] In a throwaway module `spike/execprobe/` (Android app, targetSdk 36), ship NDK-built
      `hello` and `hellodl` (which `dlopen`s a `libplugin.so` from `nativeLibraryDir`, as gcc
      does with `xtensa_esp32.so`) as `jniLibs/<abi>/libhello.so` etc.
- [ ] `android { packaging { jniLibs { useLegacyPackaging = true } } }` so libs are extracted.
- [ ] At startup: create `filesDir/tc/bin/hello` → symlink to `nativeLibraryDir/libhello.so`;
      run both the direct path and the symlink via `ProcessBuilder`; also run a child that
      itself `execve`s another symlinked binary (`busybox sh -c "hello"`), which is exactly
      what the gcc driver does. Show stdout/exit codes on screen.
- [ ] Set `TMPDIR=cacheDir` (Android has no `/tmp`; gcc needs it).
- **Pass:** all three exec paths work on both test phones.

### P0.2 Build the Android-hosted xtensa toolchain (cloud / CI)
*Revised 2026-09-27:* Espressif's toolchain loads the chip config as a `dlopen` plugin
(`-mdynconfig=xtensa_esp32.so`), so fully static musl binaries can't work. Build with the NDK.
- [ ] Sources pinned to Espressif crosstool-NG `esp-14.2.0_20260121`: `espressif/gcc`
      `esp-14.2.0_20260121`, `espressif/binutils-gdb` `esp-2.43.1_20260121`,
      `xtensa-dynconfig` `c545876`, `xtensa-overlays` `dd1cf19`.
- [ ] `toolchain/build-android.sh <abi>`: gmp/mpfr/mpc for the Android host; binutils
      (`all-binutils all-gas all-ld`) and gcc (`all-gcc`) with
      `--build=x86_64-linux-gnu --host=<ndk triple>26 --target=xtensa-esp-elf` and Espressif's
      gcc configure options (from `xtensa-esp-elf-gcc -v`); the `xtensa_esp32.so` plugin from
      xtensa-dynconfig + overlays with the NDK compiler. No LTO plugin, no gdb, no NLS.
- [ ] Output: `bin/` + `libexec/` + plugins, stripped. Target files are reused from
      Espressif's release (same sources and options).
- [ ] Smoke test: the arm64 binaries can't run in the x86 container (bionic), so check with
      `file`/`readelf` here (interpreter `/system/bin/linker64`, no missing `NEEDED` libs
      beyond libc/libm/libdl/liblog) and run for real in P0.4 or on a CI arm64 Android emulator.
- [ ] Record sizes: per-binary, total uncompressed, total compressed.
- **Pass:** complete set builds for arm64-v8a; compressed host set ≤ 35 MB per ABI.

### P0.3 Hand-made ESP32 pack + desktop reference build (cloud)
- [ ] `tools/pack/make_pack.py` v0: download arduino-esp32 3.3.12 core zip + `esp32-libs`
      from the package index (verify sha256 from index), copy upstream xtensa target files,
      emit a directory tree + `pack.json`. Keep it scriptable; it's promoted in P2.
- [ ] Install arduino-cli 1.x in the container; `arduino-cli compile -v --fqbn
      esp32:esp32:esp32 Blink` and save the full verbose log to
      `spike/reference/blink-verbose.log` (our ground truth of commands and flags).
- [ ] Measure: which `esp32-libs` subfolders and multilib dirs were actually referenced
      (`-v` log + `strace -f -e trace=openat` on the compile). Prune pack to those. Record size.

### P0.4 Compile Blink on the phone with the hand-written command list (device)
- [ ] In `spike/execprobe`, add "Compile Blink": copy the pack (adb push to app-specific
      external dir is fine for the spike), rewrite the reference log's commands to device
      paths, run them in sequence with the gcc tree from P0.1's symlink approach.
      Skip `core` compile by using a `core.a` built on desktop from the same pack.
- [ ] Measure wall time of each step; peak RSS of `cc1plus` (`/proc/<pid>/status` poll).
- **Pass:** `Blink.ino.elf` produced; `xtensa-esp-elf-size` output matches desktop within a
  few bytes; total time noted for both phones.

### P0.5 Minimal Kotlin ROM flasher (cloud → device)
- [ ] Throwaway: SLIP + SYNC + FLASH_BEGIN/DATA/END (uncompressed is fine) + reset, using
      `usb-serial-for-android`. Flash the desktop-produced `merged.bin` at 0x0.
- **Pass:** Blink blinks; serial shows boot log at 115200.

### P0.6 Results + decision
- [ ] Write `docs/superpowers/spikes/p0-results.md`: phones, Android versions, pass/fail per
      exec path, APK size, pack size, compile times, cc1plus RSS, flash time. GO/NO-GO.
- [ ] Update the spec's estimates with measured numbers.

---

# P1 — Editor fixes, UX, and release hygiene (parallel with P0)

All of P1 is doable in the cloud. Real-device checks at the end of the phase.

### P1.1 Test infrastructure + storage abstraction
Files: `app/build.gradle.kts`, new `app/src/test/...`, `data/SketchStorage.kt`,
`data/DocumentFileStorage.kt`, `data/SketchRepository.kt`.
- [ ] Add `org.jetbrains.kotlinx:kotlinx-coroutines-android` and `-test`, `com.google.truth:truth`.
- [ ] Introduce a tiny interface over SAF so repository logic is JVM-testable:
  ```kotlin
  interface SketchStorage {
      fun children(dir: Node): List<Node>
      fun find(dir: Node, name: String): Node?
      fun createDir(parent: Node, name: String): Node?
      fun createFile(parent: Node, mime: String, name: String): Node?
      fun rename(node: Node, newName: String): Node?   // returns node with fresh URI
      fun delete(node: Node): Boolean
      fun read(node: Node): String
      fun write(node: Node, text: String)
  }
  data class Node(val name: String, val id: String, val isDir: Boolean)
  ```
  `DocumentFileStorage` implements it with `DocumentFile`; `InMemoryStorage` (test source set)
  implements it with a map and **mimics ExternalStorageProvider's behaviour of appending the
  MIME's extension** (`text/plain` + `foo.h` → `foo.h.txt`) so bug 1 is reproducible in tests.
- [ ] Refactor `SketchRepository` to take `SketchStorage` (no behaviour change yet). Existing
      callers construct it with `DocumentFileStorage(context)`.
- [ ] Tests: create/list/read/write round trip on `InMemoryStorage`.

### P1.2 Bug 1 — `addFile` MIME
- [ ] Test: `addFile(sketch, "pins.h")` then `listFiles` contains `pins.h`, not `pins.h.txt`.
      Fails with current `text/plain`.
- [ ] Fix: `mimeFor(name)` → `application/octet-stream` for everything except `.ino`
      (keep `text/x-arduino`). Use it in `createSketch` and `addFile`.

### P1.3 Bug 2 — rename order
- [ ] Test: rename `Blink` → `Blinky`; expect folder `Blinky` containing `Blinky.ino` and no
      `Blink.ino`. Test rollback: fake storage fails the folder rename → `.ino` renamed back.
- [ ] Fix: rename primary `.ino` first, then folder; on folder failure rename `.ino` back and
      throw `SketchRenameFailedException`. Use the node returned by `rename` (fresh URI).

### P1.4 Bug 5 — list only real sketches; report delete failure
- [ ] Tests: dir `libraries/` and dir `notes/` (no matching `.ino`) are not listed; `Blink/`
      with `Blink.ino` is. `deleteSketch` returns/throws on failure.
- [ ] Fix: `listSketches` filters to dirs containing `<dirName>.ino`. `deleteSketch` throws
      `SketchDeleteFailedException` when `delete` returns false; activity shows a snackbar.

### P1.5 Bug 3 — main-thread I/O and crash-safety
Files: `EditorActivity.kt`, `SketchListActivity.kt`, new `EditorViewModel.kt`,
`SketchListViewModel.kt`.
- [ ] Move repository calls into ViewModels using `viewModelScope` + `Dispatchers.IO`,
      injected dispatcher for tests.
- [ ] All I/O wrapped: failures become a `UiEvent.Error(messageRes)` shown as a snackbar.
- [ ] `onPause` save: launch on an application-scoped `CoroutineScope` so it completes after
      the activity pauses; never throws. Add dirty tracking (content hash) so unchanged files
      aren't rewritten.
- [ ] Tests (ViewModel, `StandardTestDispatcher`): read failure → error event, no crash;
      save of unchanged file performs no write.

### P1.6 Bug 4 — duplicate tab listener
- [ ] Split `setupFileTabs` into `attachTabListener()` (called once in `onCreate`) and
      `renderTabs(files)`. Guard `onTabSelected` against re-entrancy during `removeAllTabs`.
- [ ] Test: Robolectric test (add `org.robolectric:robolectric`) — after 3 `addFile` calls,
      selecting a tab triggers exactly one load. If Robolectric + sora-editor is too heavy,
      test the ViewModel's `selectFile` call count instead and note it.

### P1.7 targetSdk 36 + toolchain upgrade
- [ ] Gradle wrapper → 8.14.x (or newest compatible), AGP → newest stable supporting
      compileSdk 36, Kotlin → matching. `compileSdk = 36`, `targetSdk = 36`, `minSdk` stays 26.
- [ ] Edge-to-edge is enforced at targetSdk ≥ 35: apply `WindowInsets` padding to toolbars,
      FAB, tab bar, and the editor (IME insets for the symbol bar later).
- [ ] Predictive back: `android:enableOnBackInvokedCallback="true"`; replace any
      `onBackPressed` override.
- [ ] `./gradlew :app:assembleDebug :app:lint` → 0 errors. Check whether the JDK-25 Android
      Studio issue from CLAUDE.md is fixed by the new Gradle; update CLAUDE.md either way.

### P1.8 C++ highlighting (TextMate)
- [ ] Add `io.github.Rosemoe.sora-editor:language-textmate` (same version as `editor`; bump both
      if needed). Bundle `cpp.tmLanguage.json` + `language-configuration.json` from
      microsoft/vscode (MIT; keep license header + NOTICE entry) under `app/src/main/assets/textmate/`.
- [ ] Map `.ino/.h/.hpp/.c/.cpp/.cc` to the C++ grammar; `.txt` plain.
- [ ] Remove `language-java`; update README's "About the syntax highlighting" section.
- [ ] Manual check: `#include`, `#define`, `uint8_t`, `constexpr`, raw strings highlight.

### P1.9 Student UX: symbol bar, undo/redo, font size, examples
- [ ] Symbol bar: horizontally scrolling row above the keyboard (use sora's
      `SymbolInputView`), keys `Tab { } ( ) ; < > = " ' # & | [ ] / * + - ! _ ,`. Follows IME insets.
- [ ] Undo/redo toolbar actions (`codeEditor.undo()/redo()`, enabled state from `canUndo/canRedo`).
- [ ] Font size: pinch (sora built-in, clamp 10–28sp) + Settings; persist in DataStore.
- [ ] Examples: `app/src/main/assets/examples/<Category>/<Name>/<Name>.ino` for Blink, Serial
      (AnalogReadSerial), Button, Fade, WiFiScan, WiFiWebServer (simple), BLEScan. Write
      them ourselves (MIT) — don't copy upstream examples without their licence.
      "New from example" copies into the SAF root as a new sketch (reuses `createSketch`).
- [ ] Tests: example asset listing; "new from example" uniqueness (`Blink` exists → `Blink_2`).

### P1.10 CI + release signing
- [ ] `.github/workflows/android.yml` (repo root, `working-directory: EspSketchIDE`):
      on PR/push — JDK 17, Gradle cache, `./gradlew test :app:lint :app:assembleDebug`,
      upload lint report and debug APK as artifacts.
- [ ] `.github/workflows/release.yml`: on tag `espsketchide-v*` — decode keystore from secrets,
      `assembleRelease` with ABI splits (`arm64-v8a`, `armeabi-v7a`, universal), attach APKs +
      sha256 to a GitHub Release (draft).
- [ ] `app/build.gradle.kts`: `signingConfigs.release` read from env vars; absent → unsigned
      (so forks and PRs still build). `splits { abi { … isUniversalApk = true } }`.
      `versionCode` scheme per ABI (e.g. `base*10 + abiIndex`).

### P1.11 Docs
- [ ] `PRIVACY.md` (no data collected; network only for packs/library index; no analytics).
- [ ] `CONTRIBUTING.md` (build, tests, clean-room rule, commit style).
- [ ] README: rewrite roadmap to match spec phases (keep M0–M2 as done history), supported
      phones/boards table, "no iPhone" note, first-upload hardware checklist.
- [ ] CLAUDE.md: new modules, test commands, conventions above.

### P1.12 Device check + tag `espsketchide-v0.0.2`
- [ ] On both phones: create/rename/delete, add `.h`, rename with open tabs, rotate while
      editing, background app mid-edit, revoke folder permission. Record in PR description.
- [ ] Tag a pre-release APK (editor only — honest scope in the release notes).

---

# P2 — Toolchain and pack pipelines (after P0 = GO)

### P2.1 Promote the toolchain build
- [ ] Move `toolchain/` build to CI (`.github/workflows/toolchain.yml`, manual + tag trigger,
      `ubuntu-24.04` for the build, `ubuntu-24.04-arm` to run a smoke compile natively).
      Publish `xtensa-esp-elf-host-<ver>-<abi>.tar.xz` + GPL source tarball + build scripts to a
      GitHub Release. Pin versions and sha256 in `toolchain/versions.properties`.
- [ ] Optional: `x86_64` host build for emulator tests.

### P2.2 App packaging of the toolchain
- [ ] Gradle task `fetchToolchain` downloads the pinned host tarballs, verifies sha256,
      renames `cc1plus` → `libcc1plus.so` etc. into `app/src/main/jniLibs/<abi>/` (git-ignored).
      `preBuild` depends on it.
- [ ] `ToolchainInstaller` (app): creates the gcc directory tree of symlinks on first run and
      after every app update (keyed on `versionCode`); returns a `ToolchainPaths` object.
- [ ] Instrumented test on device: `g++ --version` exits 0.

### P2.3 Pack builder
- [ ] Promote `tools/pack/make_pack.py`: inputs = platform version; outputs
      `esp32-<ver>-pack.tar.zst` + `pack.json` (per-file sha256, unpacked size, board menus
      extracted from `boards.txt`), pruned per P0.3 measurements; prebuilt bootloader bins
      (run upstream esptool `elf2image` in CI); prebuilt `core.a` for default menus
      (built with the upstream x86_64 toolchain using our build engine's CLI from P3.9 —
      until then, with arduino-cli).
- [ ] `packs-index.json` published alongside; workflow `.github/workflows/pack.yml`.
- [ ] Unit tests for pruning rules and manifest generation (pytest, runs in CI).

### P2.4 Pack install in the app
- [ ] `PackRepository`: fetch index, show size, resumable download (HTTP Range) to
      `cacheDir`, verify sha256, extract `.tar.zst` (add `com.github.luben:zstd-jni` +
      commons-compress) into `filesDir/packs/<id>-<ver>/` atomically (extract to temp, rename).
- [ ] Sideload: pick a pack file via SAF → same verify/extract path.
- [ ] Metered-network warning; storage-space check before download.
- [ ] Tests: interrupted download resumes; corrupted file rejected; extraction is atomic.

---

# P3 — Build engine (`:buildengine`, pure JVM)

New Gradle module `EspSketchIDE/buildengine` (`kotlin("jvm")`), no Android deps. The app uses it
through a thin Android service. A `:cli` module wraps it for desktop/CI golden tests.

### P3.1 Properties + expansion
- [ ] `PropertiesFile` parser for Arduino `.txt` (comments, `key=value`, OS suffixes
      `.linux/.windows/.macosx` → pick `.linux`), `boards.txt` menu resolution
      (`<board>.menu.<menu>.<option>.*`), `{var}` expansion with recursion + cycle detection.
- [ ] Tests from hand-written fixtures (not copied from arduino-cli).

### P3.2 `.ino` preprocessing
- [ ] Merge `.ino` files (primary first, rest alphabetical), inject `#include <Arduino.h>`
      and `#line` directives.
- [ ] `PrototypeGenerator`: tokenizer aware of comments/strings/raw strings/char literals/
      preprocessor lines; finds top-level function definitions outside class/struct/namespace
      /extern blocks; skips templates, `static inline` already-declared, and functions already
      prototyped; inserts prototypes before the first function definition (after includes
      and type declarations used by the signatures — mimic Arduino's "before first function" rule).
- [ ] Tests: 25+ cases incl. default args, `#ifdef`-wrapped functions, structs as params,
      lambdas, `ISR`-style attributes (`void IRAM_ATTR isr()`), multiline signatures.

### P3.3 Diagnostics mapping
- [ ] Parse gcc `file:line:col: error|warning|note:` lines; map back through `#line` to the
      user's `.ino`/`.h`. Tests with captured gcc output.

### P3.4 Library model + header index
- [ ] Parse `library.properties` (1.5 layout: `src/`, recursive) and legacy layout (flat,
      `utility/`); `architectures` filter; build header → [libraries] index; priority rules
      (sketch-local > user-installed > pack-bundled; exact header-name/folder-name match first).

### P3.5 Include discovery loop
- [ ] Run `g++ -E` with `-o /dev/null` over each source (sketch, then each added library's
      sources); on `fatal error: X: No such file or directory`, look up `X`, add library,
      repeat; cache per (file hash, include paths). `ProcessRunner` interface → fake in tests.

### P3.6 Recipe execution
- [ ] Build steps from `recipe.c.o.pattern`, `recipe.cpp.o.pattern`, `recipe.S.o.pattern`,
      `recipe.ar.pattern`, `recipe.c.combine.pattern`, `recipe.size.pattern` (+ regex for size
      report). Tokenise command lines like arduino-cli (quote handling).
- [ ] `Esp32BuildProfile`: native implementations of every `recipe.hooks.*` in 3.3.12
      platform.txt (partitions CSV selection order, `build_opt.h`, `file_opts`, `sdkconfig`
      copy, bootloader selection/prebuilt bin, `flash_args`, `boot_app0.bin` copy). Unknown
      hooks → fail loudly with the hook name (so a core update can't silently break builds).
- [ ] Parallel compile (N = cores, capped by RAM: 1 job per 700 MB free, min 1).
- [ ] Object cache: key = sha256(source bytes + expanded command line); stored under build dir.

### P3.7 `gen_esp32part` (Kotlin)
- [ ] CSV → binary: 32-byte entries (magic `0xAA50`, type, subtype, offset, size, 16-byte name,
      flags), offsets auto-assigned with alignment rules, MD5 entry (`0xEBEB` + md5), `0xFF` pad
      to 0xC00. Golden tests: our output == upstream tool output for every CSV in the pack
      (generate expected bins once with upstream tool in CI, store as test resources).

### P3.8 `elf2image` + `merge-bin` (Kotlin)
- [ ] ELF32 parser (program headers + sections). ESP32 image: header `0xE9`, segment count,
      flash mode/size/freq, entry, 16-byte extended header (chip id 0, min rev), segments with
      flash-mapped (IROM/DROM) segments placed so their load address ≡ file offset (mod 64 KiB)
      with padding segments inserted, checksum (`0xEF` XOR), pad to 16, appended SHA-256;
      `--elf-sha256-offset 0xb0` patching of the app description. `merge-bin` with padding.
- [ ] Golden tests: for 5 ELF fixtures built in CI, our `.bin` == upstream esptool `.bin`
      byte for byte.

### P3.9 `:cli` + golden builds in CI
- [ ] `espsketch-cli compile --pack <dir> --fqbn esp32:esp32:esp32 [--menu k=v] <sketchDir>`.
- [ ] CI job (x86_64, upstream Linux toolchain): compile the example set with our CLI and with
      arduino-cli; compare object files (same flags ⇒ identical), ELF sections, and final
      image sizes. Also run the same job on `ubuntu-24.04-arm` with *our* musl toolchain.

### P3.10 Android integration
- [x] Build runner: `BuildController` in the app scope, staging via `SketchStager` into
      `cacheDir/stage`, env from `XtensaToolchainRunner` plus `TMPDIR`; screen kept on while
      it runs. **Deferred:** a foreground service (`specialUse`) with a progress
      notification and cancel. Builds survive leaving the screen but not the app being
      killed in the background; revisit after device timings (and the Play FGS review).
- [x] UI (labelled experimental, one-time notice): board picker (board list from
      `pack.json`; menu options use the defaults for now), Verify/Upload actions, output
      panel with clickable diagnostics and the full compiler output.
- [ ] Device test: Blink first build (with pack `core.a`) and rebuild times vs spec targets.

---

# P4 — Upload + serial monitor (`:esptool`, pure JVM + app glue)

### P4.1 Transport
- [ ] `interface SerialLink { write; read(timeout); setBaud; setDtr; setRts; purge }`.
      App implementation over `com.github.mik3y:usb-serial-for-android` (Jitpack, pinned).
      Test implementation: scripted fake that replays traces we record ourselves.

### P4.2 SLIP + commands
- [ ] SLIP encode/decode; command packet (dir, op, len, checksum, data); response parsing
      incl. ROM status bytes. Ops: SYNC 0x08, READ_REG 0x0A, WRITE_REG, CHANGE_BAUDRATE 0x0F,
      SPI_ATTACH 0x0D, SPI_SET_PARAMS 0x0B, FLASH_DEFL_BEGIN 0x10 / DATA 0x11 / END 0x12,
      SPI_FLASH_MD5 0x13. Checksum seed `0xEF`.
- [ ] Deflate with `java.util.zip.Deflater` (zlib stream, as ROM expects).

### P4.3 Reset + chip detect
- [ ] Classic reset (DTR/RTS sequence with 100 ms / 50 ms timings), retry with alternate
      timings; USB-JTAG-serial reset kept as a stub for P-next (S3/C3).
- [ ] Detect ESP32 via magic register `0x40001000`; refuse other chips with a clear message.

### P4.4 Flash flow
- [ ] SYNC (up to 7 tries) → detect → SPI_ATTACH → SPI_SET_PARAMS (flash size) →
      CHANGE_BAUDRATE 460800 (fallback 115200) → for each `flash_args` entry:
      DEFL_BEGIN/DATA (block size 0x4000)/…/MD5 verify → DEFL_END(reboot) → hard reset.
- [ ] Progress callbacks; cancellation; error taxonomy (no sync → "hold BOOT" help screen).

### P4.5 App glue + serial monitor
- [ ] USB device filter XML (CP210x 10C4:EA60, CH340 1A86:7523, CH9102 1A86:55D4, FTDI 0403:6001,
      generic CDC). Permission request with an **explicit** `PendingIntent`
      (`setPackage`, `FLAG_MUTABLE`) as required for targetSdk ≥ 34.
- [ ] Serial monitor screen: baud, autoscroll, send line, line endings; releases port during
      upload and reopens after.
- [ ] Upload button enabled (remove `EXPERIMENTAL` gate for Verify+Upload once P4.6 passes).

### P4.6 Hardware matrix (device)
- [ ] 3 boards × 2 phones: Blink, WiFiScan, BLEScan upload + run. Record flash times.
- [ ] Optional CI: Espressif QEMU (esp32) in download mode over TCP as a `SerialLink` —
      verify it's supported before investing.

---

# P5 — Library manager (`:libmanager`, pure JVM + UI)

- [ ] P5.1 Index: download `library_index.json.gz` (ETag cache), stream-parse
      (kotlinx-serialization `JsonReader`-style streaming or Moshi), keep latest N versions per
      library, filter `architectures` ∋ `esp32|*`, store in SQLite (Room) with FTS for search.
- [ ] P5.2 Resolver: `depends` with version constraints (`(>=1.2.0)` etc.), latest-compatible
      choice, conflict report. Unit tests with synthetic indexes.
- [ ] P5.3 Installer: download, checksum (`SHA-256:` prefix in index), unzip with path
      traversal protection, install atomically into `filesDir/libraries/<name>/`.
- [ ] P5.4 `.zip` install via SAF; validation (`library.properties` or header at root).
- [ ] P5.5 Import from SAF `libraries/` folder of the sketchbook (copy in; show source).
- [ ] P5.6 UI: search, details, install/remove/update; examples appear in Examples browser.
- [ ] P5.7 Build integration: P3.4 index includes installed libs; "missing header X — install
      library Y?" quick action from a build error.

---

# P6 — v0.1 beta

- [ ] P6.1 "First upload" guide screen; troubleshooting page (charge-only cable, OTG power, BOOT).
- [ ] P6.2 Crash-free check: StrictMode in debug; fix any main-thread I/O left.
- [ ] P6.3 Release notes, supported-hardware table, GPL source links for toolchain.
- [ ] P6.4 Tag `espsketchide-v0.1.0`; signed APKs + pack on GitHub Releases.
- [ ] P6.5 Circuit Hub class beta; feedback issue template; triage into v0.1.x / v0.2.

---

## After v0.1 (not planned in detail)
- v0.2: ESP32-S3/C3/C6 — `riscv32-esp-elf` host build, per-chip packs, USB-JTAG-serial reset, stub flasher (licence review).
- v0.3: ESP8266 — `xtensa-lx106-elf` gcc 10.3, `Esp8266BuildProfile`, stub required.
- v0.4: AVR Uno/Nano — avr-gcc, STK500v1 over CH340/16U2.
- Play Store: `specialUse` FGS justification, data-safety form, AAB with ABI splits.
