#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
# Optional external baseline checkout allows an apples-to-apples run of this
# exact event-loop fixture against the previous production controller.
SOURCE=${1:-$ROOT}
CAPTURE_MS=${2:-33}
BUDGET_MS=${3:-450}
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
COMPILER=${KOTLINC:-kotlinc}
set -- "$SOURCE"/core/src/main/kotlin/com/retq/solitairebot/core/*.kt \
 "$SOURCE"/app/src/main/java/com/retq/solitairebot/capture/NormalizedBoardLayout.kt \
 "$SOURCE"/app/src/main/java/com/retq/solitairebot/control/RoundDiagnostics.kt \
 "$SOURCE"/app/src/main/java/com/retq/solitairebot/control/LivePipeline.kt \
 "$SOURCE"/app/src/main/java/com/retq/solitairebot/control/LiveSession.kt \
 "$SOURCE"/app/src/main/java/com/retq/solitairebot/control/MoveExecutor.kt \
 "$SOURCE"/app/src/main/java/com/retq/solitairebot/control/GestureAccessibilityService.kt \
 "$ROOT"/tests/control-regression/stubs/*.kt \
 "$ROOT"/tests/control-regression/LatencyBenchmark.kt
if [ -f "$SOURCE/app/src/main/java/com/retq/solitairebot/control/ExecutionPacing.kt" ]; then
 set -- "$@" "$SOURCE/app/src/main/java/com/retq/solitairebot/control/ExecutionPacing.kt"
fi
"$COMPILER" "$@" -nowarn -include-runtime -d "$OUT/latency.jar"
java -jar "$OUT/latency.jar" "$CAPTURE_MS" "$BUDGET_MS"
