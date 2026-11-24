#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
COMPILER=${KOTLINC:-kotlinc}
"$COMPILER" "$ROOT"/core/src/main/kotlin/com/retq/solitairebot/core/*.kt \
 "$ROOT"/app/src/main/java/com/retq/solitairebot/capture/NormalizedBoardLayout.kt \
 "$ROOT"/app/src/main/java/com/retq/solitairebot/control/RoundDiagnostics.kt \
 "$ROOT"/app/src/main/java/com/retq/solitairebot/control/ExecutionPacing.kt \
 "$ROOT"/app/src/main/java/com/retq/solitairebot/control/LivePipeline.kt \
 "$ROOT"/app/src/main/java/com/retq/solitairebot/control/LiveSession.kt \
 "$ROOT"/app/src/main/java/com/retq/solitairebot/control/MoveExecutor.kt \
 "$ROOT"/app/src/main/java/com/retq/solitairebot/control/GestureAccessibilityService.kt \
 "$ROOT"/tests/control-regression/stubs/*.kt "$ROOT"/tests/control-regression/ControlRegression.kt \
 -nowarn -include-runtime -d "$OUT/control-regression.jar"
java -jar "$OUT/control-regression.jar"
