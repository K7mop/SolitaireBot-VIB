#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
kotlinc "$ROOT"/tests/vision/platform/AndroidGraphics.kt \
  "$ROOT"/tests/vision/platform/AndroidAssets.kt \
  "$ROOT"/app/src/main/java/com/retq/solitairebot/vision/GreenHudReader.kt \
  "$ROOT"/tests/hud/HudRegression.kt -include-runtime -d "$OUT/hud.jar"
cd "$ROOT"
java -Dsolitaire.assets="$ROOT/app/src/main/assets" -jar "$OUT/hud.jar"
