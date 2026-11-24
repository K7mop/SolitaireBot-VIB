#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
"${KOTLINC:-kotlinc}" "$ROOT"/core/src/main/kotlin/com/retq/solitairebot/core/*.kt \
  "$ROOT"/tests/strategy/StrategyRegression.kt -include-runtime -d "$OUT/strategy.jar"
java -Xmx512m -jar "$OUT/strategy.jar"
