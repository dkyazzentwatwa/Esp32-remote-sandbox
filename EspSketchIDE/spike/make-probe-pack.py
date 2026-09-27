#!/usr/bin/env python3
"""P0 spike (throwaway): turn a desktop arduino-cli Blink build into a pack for the probe app.

Replays the compile/archive/link commands from `arduino-cli compile -v` through a relocated
copy of Espressif's desktop toolchain under strace, then copies exactly the files the
toolchain opened (core, SDK libs, gcc target files) into probe-pack/, and writes
commands.jsonl with @TC@ / @PACK@ / @BUILD@ placeholders for the phone.

Usage: make-probe-pack.py --log blink-verbose.log --build <arduino-cli build dir> --out probe-pack
"""
import argparse
import json
import os
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

GCC_VERSION = "14.2.0"
TOOLS = {  # arduino-cli's chip wrapper -> unified tool in the link tree
    "xtensa-esp32-elf-g++": "bin/xtensa-esp-elf-g++",
    "xtensa-esp32-elf-gcc": "bin/xtensa-esp-elf-gcc",
    "xtensa-esp32-elf-gcc-ar": "bin/xtensa-esp-elf-ar",
}


def reloc_flags(tc: str) -> list:
    return [f"-B{tc}/libexec/gcc/xtensa-esp-elf/{GCC_VERSION}/", f"-B{tc}/xtensa-esp-elf/bin/",
            f"--sysroot={tc}/xtensa-esp-elf", "-mdynconfig=xtensa_esp32.so"]


def parse_commands(log: Path, esp_bin: str):
    for line in log.read_text().splitlines():
        line = line.strip()
        if not line.startswith(esp_bin):
            continue
        argv = shlex.split(line)
        name = os.path.basename(argv[0])
        if name in TOOLS and "-E" not in argv[1:]:  # skip arduino-cli's own preprocessing
            yield name, argv[1:]


def make_tree(tc: Path, esp: Path):
    """Desktop stand-in for the phone's filesDir/tc (x86 binaries instead of lib*.so)."""
    v = GCC_VERSION
    links = {
        "bin/xtensa-esp-elf-gcc": "bin/xtensa-esp-elf-gcc", "bin/xtensa-esp-elf-g++": "bin/xtensa-esp-elf-g++",
        "bin/xtensa-esp-elf-ar": "bin/xtensa-esp-elf-ar",
        f"libexec/gcc/xtensa-esp-elf/{v}/cc1": f"libexec/gcc/xtensa-esp-elf/{v}/cc1",
        f"libexec/gcc/xtensa-esp-elf/{v}/cc1plus": f"libexec/gcc/xtensa-esp-elf/{v}/cc1plus",
        f"libexec/gcc/xtensa-esp-elf/{v}/collect2": f"libexec/gcc/xtensa-esp-elf/{v}/collect2",
        "xtensa-esp-elf/bin/as": "bin/xtensa-esp-elf-as", "xtensa-esp-elf/bin/ld": "bin/xtensa-esp-elf-ld",
        "xtensa-esp-elf/bin/ar": "bin/xtensa-esp-elf-ar", "lib/xtensa_esp32.so": "lib/xtensa_esp32.so",
        f"lib/gcc/xtensa-esp-elf/{v}": f"lib/gcc/xtensa-esp-elf/{v}",
        "xtensa-esp-elf/include": "xtensa-esp-elf/include", "xtensa-esp-elf/lib": "xtensa-esp-elf/lib",
    }
    for at, target in links.items():
        (tc / at).parent.mkdir(parents=True, exist_ok=True)
        os.symlink(esp / target, tc / at)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--log", required=True, type=Path)
    ap.add_argument("--build", required=True, type=Path, help="arduino-cli --build-path of that log")
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--arduino15", type=Path, default=Path.home() / ".arduino15")
    args = ap.parse_args()

    packages = args.arduino15 / "packages"
    esp = next((packages / "esp32/tools/esp-x32").iterdir())
    esp_bin = str(esp / "bin") + "/"
    build = str(args.build.resolve())
    commands = list(parse_commands(args.log, esp_bin))
    print(f"{len(commands)} toolchain commands")

    work = Path(tempfile.mkdtemp(prefix="probe-pack-"))
    tc, replay_build = work / "tc", work / "build"
    make_tree(tc, esp)
    shutil.copytree(args.build, replay_build, symlinks=True,
                    ignore=shutil.ignore_patterns("*.o", "*.a", "*.elf", "*.d", "*.map", "*.bin"))
    seed = sorted(p.relative_to(replay_build) for p in replay_build.rglob("*") if p.is_file())

    # Replay under strace to learn which files the toolchain reads.
    env = dict(os.environ, GCC_EXEC_PREFIX=f"{tc}/lib/gcc/", XTENSA_GNU_CONFIG=f"{tc}/lib/")
    trace = work / "trace"
    for i, (name, rest) in enumerate(commands):
        rest = [a.replace(build, str(replay_build)) for a in rest]
        link = name.endswith("g++") and "-c" not in rest
        cmd = [str(tc / TOOLS[name])] + ([] if name.endswith("-ar") else reloc_flags(str(tc)))
        cmd += (["-fno-use-linker-plugin"] if link else []) + rest  # desktop gcc has LTO on
        r = subprocess.run(["strace", "-f", "-qq", "-e", "trace=openat,open,stat,newfstatat,access",
                            "-o", f"{trace}.{i}", *cmd], env=env, capture_output=True, text=True)
        if r.returncode:
            sys.exit(f"replay failed at {i}: {r.stderr[-1500:]}")
    opened = set()
    pat = re.compile(r'"([^"]+)"')
    for t in work.glob("trace.*"):
        for line in t.read_text(errors="replace").splitlines():
            if "ENOENT" in line or "ENOTDIR" in line:
                continue
            m = pat.search(line)
            if m:
                opened.add(os.path.realpath(m.group(1)))

    # Copy what was read: toolchain target files -> tc-data/, Arduino packages -> a15/.
    out = args.out
    shutil.rmtree(out, ignore_errors=True)
    copied = 0
    esp_real = os.path.realpath(esp) + "/"
    pkg_real = os.path.realpath(packages) + "/"
    host_bins = ("/bin/", "/libexec/", "/lib/xtensa_", "/lib64/")
    for f in sorted(opened):
        if not os.path.isfile(f):
            continue
        if f.startswith(esp_real):
            rel = f[len(esp_real):]
            if any(("/" + rel).startswith(h) for h in host_bins):
                continue  # host programs ship in the APK
            dest = out / "tc-data" / rel
        elif f.startswith(pkg_real):
            dest = out / "a15" / f[len(pkg_real):]
        else:
            continue
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(f, dest)
        copied += 1
    for rel in seed:
        dest = out / "build-seed" / rel
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(replay_build / rel, dest, follow_symlinks=True)

    with open(out / "commands.jsonl", "w") as fh:
        for name, rest in commands:
            argv = ["@TC@/" + TOOLS[name]] + ([] if name.endswith("-ar") else reloc_flags("@TC@"))
            argv += [a.replace(build, "@BUILD@").replace(str(packages), "@PACK@/a15") for a in rest]
            fh.write(json.dumps(argv) + "\n")
    shutil.rmtree(work)
    size = sum(p.stat().st_size for p in out.rglob("*") if p.is_file())
    print(f"copied {copied} files; pack {size / 2**20:.1f} MB -> {out}")


if __name__ == "__main__":
    main()
