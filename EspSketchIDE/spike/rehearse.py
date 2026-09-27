#!/usr/bin/env python3
"""P0 spike: rehearse the phone's Blink build on a Linux machine.

Lays out the Android-built toolchain exactly as the probe app does on a phone (flat lib*.so
copies standing in for nativeLibraryDir, a link tree for filesDir/tc, target files from the
probe pack) and replays the pack's commands.jsonl. Android arm64 binaries run through qemu
user-mode emulation with Android's own linker64/bionic (QEMU_LD_PREFIX) and binfmt_misc, so
this exercises the real bionic build end to end without a phone.

Usage: rehearse.py --toolchain <package dir> --pack <probe-pack> --work <dir> [--reference Blink.ino.elf]
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

V = "14.2.0"
NATIVE = {  # package file -> lib*.so name in nativeLibraryDir (same as the probe app's build)
    "bin/xtensa-esp-elf-gcc": "libxtgcc.so", "bin/xtensa-esp-elf-g++": "libxtgxx.so",
    "bin/xtensa-esp-elf-as": "libxtas.so", "bin/xtensa-esp-elf-ld": "libxtld.so",
    "bin/xtensa-esp-elf-ar": "libxtar.so", "bin/xtensa-esp-elf-objcopy": "libxtobjcopy.so",
    "bin/xtensa-esp-elf-size": "libxtsize.so", f"libexec/gcc/xtensa-esp-elf/{V}/cc1": "libxtcc1.so",
    f"libexec/gcc/xtensa-esp-elf/{V}/cc1plus": "libxtcc1plus.so",
    f"libexec/gcc/xtensa-esp-elf/{V}/collect2": "libxtcollect2.so", "lib/xtensa_esp32.so": "libxtensa_esp32.so",
}
TREE = {  # link in filesDir/tc -> lib*.so (ProbeActivity.setUpToolchainTree)
    "bin/xtensa-esp-elf-gcc": "libxtgcc.so", "bin/xtensa-esp-elf-g++": "libxtgxx.so",
    "bin/xtensa-esp-elf-ar": "libxtar.so", "bin/xtensa-esp-elf-objcopy": "libxtobjcopy.so",
    "bin/xtensa-esp-elf-size": "libxtsize.so", "lib/xtensa_esp32.so": "libxtensa_esp32.so",
    **{f"libexec/gcc/xtensa-esp-elf/{V}/{p}": f"libxt{p}.so" for p in ("cc1", "cc1plus", "collect2")},
    **{f"xtensa-esp-elf/bin/{p}": f"libxt{p}.so" for p in ("as", "ld", "ar")},
}


def link(target, at: Path):
    at.parent.mkdir(parents=True, exist_ok=True)
    os.symlink(target, at)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--toolchain", required=True, type=Path)
    ap.add_argument("--pack", required=True, type=Path)
    ap.add_argument("--work", required=True, type=Path)
    ap.add_argument("--reference", type=Path, help="desktop Blink.ino.elf to compare sections with")
    ap.add_argument("--objcopy", default="xtensa-esp-elf-objcopy", help="desktop objcopy for the comparison")
    ap.add_argument("--desktop-gcc", action="store_true",
                    help="toolchain is Espressif's desktop build (LTO on): add -fno-use-linker-plugin at link")
    args = ap.parse_args()

    work = args.work.resolve()
    shutil.rmtree(work, ignore_errors=True)
    native, tc, pack, build = work / "nativelib", work / "tc", work / "pack", work / "build"
    native.mkdir(parents=True)
    for src, name in NATIVE.items():
        shutil.copy2(args.toolchain / src, native / name)
    shutil.copytree(args.pack, pack, symlinks=True)
    for at, name in TREE.items():
        link(native / name, tc / at)
    link(pack / f"tc-data/lib/gcc/xtensa-esp-elf/{V}", tc / f"lib/gcc/xtensa-esp-elf/{V}")
    link(pack / "tc-data/xtensa-esp-elf/include", tc / "xtensa-esp-elf/include")
    link(pack / "tc-data/xtensa-esp-elf/lib", tc / "xtensa-esp-elf/lib")
    shutil.copytree(pack / "build-seed", build)

    env = dict(os.environ, GCC_EXEC_PREFIX=f"{tc}/lib/gcc/", XTENSA_GNU_CONFIG=f"{tc}/lib/", TMPDIR="/tmp")
    lines = [l for l in (pack / "commands.jsonl").read_text().splitlines() if l.strip()]
    t0 = time.time()
    for i, line in enumerate(lines):
        argv = [a.replace("@TC@", str(tc)).replace("@PACK@", str(pack)).replace("@BUILD@", str(build))
                for a in json.loads(line)]
        if "-o" in argv:
            Path(argv[argv.index("-o") + 1]).parent.mkdir(parents=True, exist_ok=True)
        if args.desktop_gcc and argv[0].endswith("g++") and "-c" not in argv:
            argv.insert(1, "-fno-use-linker-plugin")
        r = subprocess.run(argv, env=env, cwd=build, capture_output=True, text=True)
        if r.returncode:
            sys.exit(f"FAIL at {i + 1}/{len(lines)}: {argv[0]}\n{r.stderr[-2000:]}")
        if (i + 1) % 20 == 0:
            print(f"{i + 1}/{len(lines)} {time.time() - t0:.0f}s", flush=True)
    elf = build / "Blink.ino.elf"
    print(f"OK: {len(lines)} commands in {time.time() - t0:.0f}s -> {elf} ({elf.stat().st_size} bytes)")

    if args.reference:
        same = True
        for sec in (".flash.text", ".flash.rodata", ".iram0.text", ".dram0.data"):
            dump = []
            for f in (args.reference, elf):
                out = work / f"section-{len(dump)}.bin"
                subprocess.run([args.objcopy, "-O", "binary", "-j", sec, str(f), str(out)], check=True)
                dump.append(out.read_bytes())
            ok = dump[0] == dump[1] and len(dump[0]) > 0
            same &= ok
            print(f"{sec}: {'identical' if ok else 'DIFFERS'} ({len(dump[0])} vs {len(dump[1])} bytes)")
        sys.exit(0 if same else 1)


if __name__ == "__main__":
    main()
