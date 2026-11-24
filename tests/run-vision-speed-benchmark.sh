#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
: "${JSON_JAR:?Set JSON_JAR to org.json dependency}"
KOTLIN_BIN=${KOTLIN_BIN:-kotlinc}
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
SOURCE_ROOT=${VISION_SOURCE_ROOT:-$ROOT}
"$KOTLIN_BIN" "$SOURCE_ROOT"/core/src/main/kotlin/com/retq/solitairebot/core/*.kt \
 "$SOURCE_ROOT/app/src/main/java/com/retq/solitairebot/capture/NormalizedBoardLayout.kt" \
 "$SOURCE_ROOT/app/src/main/java/com/retq/solitairebot/capture/AutoBoardSetup.kt" \
 "$SOURCE_ROOT"/app/src/main/java/com/retq/solitairebot/vision/*.kt \
 "$ROOT"/tests/vision/platform/*.kt "$ROOT/tests/vision-speed/BoardLatencyBenchmark.kt" \
 -classpath "$JSON_JAR" -include-runtime -d "$OUT/vision.jar"
java -Dsolitaire.assets="$SOURCE_ROOT/app/src/main/assets" -cp "$OUT/vision.jar:$JSON_JAR" \
 regression.BoardLatencyBenchmarkKt "$ROOT/tests/automatic-layout/fixtures" "$@"
