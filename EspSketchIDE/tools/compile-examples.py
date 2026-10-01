#!/usr/bin/env python3
"""Compile every bundled example variant with arduino-cli, for the board it is listed under.

Variant folders (e.g. wifi/WiFiConnect_esp32/WiFiConnect.ino) don't match their .ino name, which
arduino-cli requires, so each one is copied to <tmp>/<Name>/ first. Needs arduino-cli on PATH with
the esp32:esp32 and esp8266:esp8266 cores installed. Usage: tools/compile-examples.py [board ...]
"""
import json
import pathlib
import shutil
import subprocess
import sys
import tempfile

FQBN = {"esp32": "esp32:esp32:esp32", "esp8266": "esp8266:esp8266:nodemcuv2"}
EXAMPLES = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/assets/examples"


def main() -> int:
    boards = sys.argv[1:] or list(FQBN)
    examples = json.loads((EXAMPLES / "index.json").read_text())["examples"]
    failures = []
    with tempfile.TemporaryDirectory() as tmp:
        for example in examples:
            for board, path in example["variants"].items():
                if board not in boards:
                    continue
                source = EXAMPLES / path
                ino = next(source.glob("*.ino"))
                sketch = pathlib.Path(tmp, board, ino.stem)
                shutil.copytree(source, sketch)
                label = f"{example['name']} ({board})"
                print(f"::group::{label}", flush=True)
                result = subprocess.run(["arduino-cli", "compile", "--fqbn", FQBN[board], str(sketch)])
                print("::endgroup::", flush=True)
                if result.returncode != 0:
                    failures.append(label)
    for label in failures:
        print(f"FAILED: {label}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
