#!/usr/bin/env bash
# Compile Esp32WhatsappServer with arduino-cli
# Usage: ./compile.sh [fqbn]
# Default FQBN targets the generic ESP32 module. Pass a different FQBN as $1
# to target another variant (e.g. esp32:esp32:esp32s3).

set -euo pipefail

FQBN="${1:-esp32:esp32:esp32}"
SKETCH="$(dirname "$0")/Esp32WhatsappServer"
OUTPUT="$(dirname "$0")/build"

echo "[compile] FQBN   : $FQBN"
echo "[compile] Sketch : $SKETCH"
echo "[compile] Output : $OUTPUT"

mkdir -p "$OUTPUT"

arduino-cli compile \
  --fqbn "$FQBN" \
  --output-dir "$OUTPUT" \
  "$SKETCH"

echo "[compile] Done. Artifacts in $OUTPUT/"
ls -lh "$OUTPUT/"
