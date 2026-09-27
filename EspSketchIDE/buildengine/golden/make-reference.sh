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
mkdir -p "$OUT/sketches"
rm -rf "$SKETCH" && cp -r "$HERE/app/src/main/assets/examples/01.Basics/Blink" "$SKETCH"

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
echo "reference written to $OUT"
