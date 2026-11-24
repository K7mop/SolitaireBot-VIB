#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
kotlinc "$ROOT"/core/src/main/kotlin/com/retq/solitairebot/core/*.kt \
  "$ROOT"/tests/strategy/SmartStrategy089.kt -include-runtime -d "$OUT/smart.jar"
java -Xmx512m -jar "$OUT/smart.jar"
