#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
kotlinc "$ROOT"/app/src/main/java/com/retq/solitairebot/control/RoundDiagnostics.kt \
  "$ROOT"/tests/capture-speed/DiagnosticsRegression.kt -include-runtime -d "$OUT/diagnostics.jar"
java -jar "$OUT/diagnostics.jar"
