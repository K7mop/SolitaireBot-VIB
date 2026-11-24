#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
kotlinc "$ROOT"/core/src/main/kotlin/com/retq/solitairebot/core/*.kt \
  "$ROOT"/tests/strategy/SpeedStrategy0810.kt -include-runtime -d "$OUT/strategy.jar"
java -Xmx512m -jar "$OUT/strategy.jar"
