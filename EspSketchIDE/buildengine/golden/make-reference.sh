#!/bin/bash
# Generates golden reference data for the build engine tests with arduino-cli.
# Usage: buildengine/golden/make-reference.sh <out-dir>
# Needs arduino-cli with esp32:esp32@$ESP32_CORE installed. Run the tests with
# ESP32_REFERENCE_DIR=<out-dir> ./gradlew :buildengine:test
set -euo pipefail
OUT="$(mkdir -p "$1" && cd "$1" && pwd)"
CLI="${ARDUINO_CLI:-arduino-cli}"
ESP32_CORE="${ESP32_CORE:-3.3.12}"
ARDUINO15="$("$CLI" config get directories.data)"
HERE="$(cd "$(dirname "$0")/../.." && pwd)"
SKETCH="$OUT/sketches/Blink"
mkdir -p "$OUT/sketches" "$OUT/builds"
# Stage every ESP32 example variant under its sketch name (folder = .ino name, as arduino-cli
# requires); variant folders in assets are named like WiFiScan_esp32.
python3 - "$HERE/app/src/main/assets/examples" "$OUT/sketches" <<'PY2'
import json, pathlib, shutil, sys
src, dst = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
for example in json.loads((src / "index.json").read_text())["examples"]:
    path = example["variants"].get("esp32")
    if not path:
        continue
    ino = next((src / path).glob("*.ino"))
    target = dst / ino.stem
    shutil.rmtree(target, ignore_errors=True)
    shutil.copytree(src / path, target)
PY2

{
  echo "ARDUINO15=$ARDUINO15"
  echo "PLATFORM_DIR=$ARDUINO15/packages/esp32/hardware/esp32/$ESP32_CORE"
  echo "SKETCH_DIR=$SKETCH"
  echo "BUILD_DIR=$OUT/build"
} > "$OUT/env"

"$CLI" compile --fqbn esp32:esp32:esp32 --show-properties --build-path "$OUT/build" "$SKETCH" > "$OUT/show-properties-default.txt"
"$CLI" compile --fqbn esp32:esp32:esp32:PartitionScheme=huge_app,DebugLevel=info --show-properties \
  --build-path "$OUT/build" "$SKETCH" > "$OUT/show-properties-menus.txt"
"$CLI" compile -v --clean --fqbn esp32:esp32:esp32 --build-path "$OUT/build" "$SKETCH" > "$OUT/compile-verbose.txt"

# Every bundled ESP32 example, each with its own build dir and verbose log.
for dir in "$OUT"/sketches/*/; do
  name="$(basename "$dir")"
  [ "$name" = Blink ] && continue
  "$CLI" compile -v --clean --fqbn esp32:esp32:esp32 --build-path "$OUT/builds/$name" "$OUT/sketches/$name" \
    > "$OUT/builds/$name.verbose.txt"
done
mkdir -p "$OUT/builds" && rm -rf "$OUT/builds/Blink" && ln -s "$OUT/build" "$OUT/builds/Blink"
cp "$OUT/compile-verbose.txt" "$OUT/builds/Blink.verbose.txt"
# Partition tables for every CSV the core ships, made by the core's own gen_esp32part.py.
PLATFORM_DIR="$ARDUINO15/packages/esp32/hardware/esp32/$ESP32_CORE"
mkdir -p "$OUT/partitions"
for csv in "$PLATFORM_DIR"/tools/partitions/*.csv; do
  python3 "$PLATFORM_DIR/tools/gen_esp32part.py" -q "$csv" "$OUT/partitions/$(basename "$csv" .csv).bin" \
    || echo "gen_esp32part rejected $(basename "$csv")" >> "$OUT/partitions/rejected.txt"
done
echo "reference written to $OUT"
