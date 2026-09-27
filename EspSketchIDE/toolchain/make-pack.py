#!/usr/bin/env python3
"""Builds the ESP32 board pack the app downloads (spec section 2).

The pack holds everything a build needs except host programs (those ship in the APK):
  hardware/esp32/<core>/   platform.txt, boards.txt, cores/, libraries/, variants/ of boards
                           whose chip is the pack's chip, tools/partitions/
  tools/<chip>-libs/<v>/   the precompiled ESP-IDF SDK for the chip (headers, libs, ld scripts)
  tools/esp-x32/<v>/       gcc target files: C/C++ headers, gcc's include dirs, crt files and
                           only the multilib folders the platform's flags select
  pack.json                manifest: versions, tool folders, supported boards, every file's
                           size and sha256

Input is an arduino-cli install of the core (`arduino-cli core install esp32:esp32@<v>`).
Usage: make-pack.py --arduino15 ~/.arduino15 --core 3.3.12 --chip esp32 --out <dir> [--archive]
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import tarfile
from pathlib import Path

GCC_VERSION = "14.2.0"
PACK_FORMAT = 1


def props(path: Path) -> dict:
    out = {}
    for line in path.read_text(errors="replace").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            out[k.strip()] = v.strip()
    return out


def copy_tree(src: Path, dst: Path, keep=lambda rel: True):
    for root, dirs, files in os.walk(src):
        dirs.sort()
        for name in sorted(files):
            f = Path(root) / name
            rel = f.relative_to(src)
            if keep(rel):
                target = dst / rel
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(f, target)


def only_dir(path: Path) -> Path:
    entries = [p for p in path.iterdir() if p.is_dir()]
    if len(entries) != 1:
        raise SystemExit(f"expected exactly one version under {path}, found {[p.name for p in entries]}")
    return entries[0]


def multilib(gcc: Path, flags_file: Path, driver: str) -> str:
    flags = flags_file.read_text().split()
    out = subprocess.run([str(gcc.parent / driver), *flags, "-print-multi-directory"],
                         capture_output=True, text=True, check=True).stdout.strip()
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--arduino15", type=Path, default=Path.home() / ".arduino15")
    ap.add_argument("--core", required=True)
    ap.add_argument("--chip", default="esp32")
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--archive", action="store_true", help="also write <out>.tar.xz")
    args = ap.parse_args()

    pkg = args.arduino15 / "packages" / "esp32"
    platform = pkg / "hardware" / "esp32" / args.core
    sdk = only_dir(pkg / "tools" / f"{args.chip}-libs")
    toolchain = only_dir(pkg / "tools" / "esp-x32")
    out = args.out
    shutil.rmtree(out, ignore_errors=True)
    out.mkdir(parents=True)

    # --- core: boards of this chip, their variants, no host tools ---
    boards_txt = props(platform / "boards.txt")
    board_ids = sorted({k.split(".")[0] for k in boards_txt if k.endswith(".name") and k.count(".") == 1})
    boards = [b for b in board_ids if boards_txt.get(f"{b}.build.mcu") == args.chip]
    variants = {boards_txt.get(f"{b}.build.variant") for b in boards} - {None}
    core_out = out / "hardware" / "esp32" / args.core
    core_out.mkdir(parents=True)
    for name in ("platform.txt", "boards.txt", "programmers.txt"):
        if (platform / name).exists():
            shutil.copy2(platform / name, core_out / name)
    copy_tree(platform / "cores", core_out / "cores")
    copy_tree(platform / "libraries", core_out / "libraries",
              keep=lambda rel: "examples" not in rel.parts and "extras" not in rel.parts)
    for v in sorted(variants):
        if (platform / "variants" / v).is_dir():
            copy_tree(platform / "variants" / v, core_out / "variants" / v)
    copy_tree(platform / "tools" / "partitions", core_out / "tools" / "partitions")

    # --- SDK for the chip, whole ---
    sdk_out = out / "tools" / f"{args.chip}-libs" / sdk.name
    copy_tree(sdk, sdk_out)

    # --- gcc target files: headers + selected multilibs ---
    gcc = toolchain / "bin" / "xtensa-esp-elf-gcc"
    wrapper = f"xtensa-{args.chip}-elf-"
    # Headers are needed for every multilib the compile flags select; libraries only for the
    # one the link selects.
    link_dir = multilib(gcc, sdk / "flags" / "ld_flags", wrapper + "g++")
    dirs = {
        multilib(gcc, sdk / "flags" / "c_flags", wrapper + "gcc"),
        multilib(gcc, sdk / "flags" / "cpp_flags", wrapper + "g++"),
        link_dir,
    }
    tc_out = out / "tools" / "esp-x32" / toolchain.name
    target = toolchain / "xtensa-esp-elf"
    gcclib = toolchain / "lib" / "gcc" / "xtensa-esp-elf" / GCC_VERSION
    cxx_multi = Path("include") / "c++" / GCC_VERSION / "xtensa-esp-elf"

    def keep_target(rel: Path) -> bool:
        s = rel.as_posix()
        if s.startswith("bin/"):
            return False  # host programs; the APK has Android builds of them
        if s.startswith(cxx_multi.as_posix() + "/"):
            # Multilib-specific C++ headers: the default set plus the selected multilibs' own
            # bits/ and ext/ (e.g. esp32/psram/no-rtti/bits), not other chips' or variants'.
            sub = rel.relative_to(cxx_multi).as_posix()
            if sub.startswith(("bits/", "ext/")):
                return True
            return any(sub.startswith((d + "/bits/", d + "/ext/")) for d in dirs)
        if s.startswith("include/"):
            return True
        if s.startswith("lib/"):
            return rel.parent.relative_to("lib").as_posix() == link_dir
        return False

    copy_tree(target, tc_out / "xtensa-esp-elf", keep_target)

    def keep_gcclib(rel: Path) -> bool:
        s = rel.as_posix()
        if s.startswith(("include/", "include-fixed/")):
            return True
        if "/" not in s:
            return s.endswith(".o") or s.endswith(".a")  # crt*.o, libgcc for the default multilib
        return rel.parent.as_posix() == link_dir

    copy_tree(gcclib, tc_out / "lib" / "gcc" / "xtensa-esp-elf" / GCC_VERSION, keep_gcclib)

    # --- manifest ---
    files = []
    total = 0
    for root, _, names in os.walk(out):
        for n in sorted(names):
            p = Path(root) / n
            data = p.read_bytes()
            files.append({"path": p.relative_to(out).as_posix(), "size": len(data),
                          "sha256": hashlib.sha256(data).hexdigest()})
            total += len(data)
    files.sort(key=lambda f: f["path"])
    board_names = [{"id": b, "name": boards_txt[f"{b}.name"]} for b in boards]
    manifest = {
        "format": PACK_FORMAT,
        "id": f"{args.chip}-{args.core}",
        "vendor": "esp32", "arch": "esp32", "chip": args.chip,
        "core": args.core,
        "platformDir": f"hardware/esp32/{args.core}",
        "tools": {
            f"{args.chip}-libs": f"tools/{args.chip}-libs/{sdk.name}",
            "esp-x32": f"tools/esp-x32/{toolchain.name}",
        },
        "gcc": {"version": GCC_VERSION, "multilibs": sorted(dirs), "linkMultilib": link_dir},
        "defaultBoard": "esp32" if "esp32" in boards else boards[0],
        "boards": board_names,
        "unpackedSize": total,
        "files": files,
    }
    (out / "pack.json").write_text(json.dumps(manifest, indent=1))
    print(f"{manifest['id']}: {len(files)} files, {total / 2**20:.1f} MB, {len(boards)} boards, multilibs {sorted(dirs)}")

    if args.archive:
        archive = out.parent / (out.name + ".tar.xz")
        with tarfile.open(archive, "w:xz", preset=6) as tar:
            tar.add(out, arcname=manifest["id"])
        print(f"archive {archive} {archive.stat().st_size / 2**20:.1f} MB")


if __name__ == "__main__":
    main()
