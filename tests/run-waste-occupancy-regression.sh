#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
"${KOTLINC:-kotlinc}" "$ROOT/tests/vision/platform/AndroidGraphics.kt" \
 "$ROOT/core/src/main/kotlin/com/retq/solitairebot/core/Card.kt" \
 "$ROOT/app/src/main/java/com/retq/solitairebot/capture/NormalizedBoardLayout.kt" \
 "$ROOT/app/src/main/java/com/retq/solitairebot/vision/AutomaticRegionReader.kt" \
 "$ROOT/app/src/main/java/com/retq/solitairebot/vision/FramePixels.kt" \
 "$ROOT/tests/waste-occupancy/FakeGlyphTemplates.kt" \
 "$ROOT/tests/waste-occupancy/WasteOccupancyRegression.kt" \
 -nowarn -include-runtime -d "$OUT/waste.jar"
java -jar "$OUT/waste.jar"
