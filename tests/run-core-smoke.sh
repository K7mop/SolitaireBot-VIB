#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
kotlinc "$ROOT"/core/src/main/kotlin/com/retq/solitairebot/core/*.kt \
  "$ROOT"/app/src/main/java/com/retq/solitairebot/capture/NormalizedBoardLayout.kt \
  "$ROOT"/app/src/main/java/com/retq/solitairebot/capture/BattleArenaPortraitProfile.kt \
  "$ROOT"/app/src/main/java/com/retq/solitairebot/capture/CaptureConfig.kt \
  "$ROOT"/tests/smoke/SmokeTests.kt -include-runtime -d "$OUT/solitaire-core.jar"
java -jar "$OUT/solitaire-core.jar"
