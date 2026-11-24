#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
kotlinc "$ROOT"/app/src/main/java/com/retq/solitairebot/capture/RgbaPlanePacker.kt \
  "$ROOT"/tests/capture-speed/FramePackingRegression.kt -include-runtime -d "$OUT/packing.jar"
java -Xmx512m -jar "$OUT/packing.jar"
