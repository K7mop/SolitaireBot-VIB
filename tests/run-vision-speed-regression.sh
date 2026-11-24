#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
: "${JSON_JAR:?Set JSON_JAR to org.json dependency}"
KOTLIN_BIN=${KOTLIN_BIN:-kotlinc}
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
"$KOTLIN_BIN" "$ROOT"/core/src/main/kotlin/com/retq/solitairebot/core/*.kt \
 "$ROOT/app/src/main/java/com/retq/solitairebot/capture/NormalizedBoardLayout.kt" \
 "$ROOT/app/src/main/java/com/retq/solitairebot/capture/AutoBoardSetup.kt" \
 "$ROOT"/app/src/main/java/com/retq/solitairebot/vision/*.kt \
 "$ROOT"/tests/vision/platform/*.kt "$ROOT/tests/vision-speed/VisionSpeedRegression.kt" \
 -classpath "$JSON_JAR" -include-runtime -d "$OUT/vision.jar"
java -Dsolitaire.assets="$ROOT/app/src/main/assets" -cp "$OUT/vision.jar:$JSON_JAR" \
 regression.VisionSpeedRegressionKt "$ROOT/tests/vision-speed/fixtures" "$ROOT/tests/automatic-layout/fixtures" "$@"
