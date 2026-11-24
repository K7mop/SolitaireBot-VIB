#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
"${KOTLINC:-kotlinc}" "$ROOT/tests/vision/platform/AndroidGraphics.kt" \
 "$ROOT/tests/vision/platform/AndroidOs.kt" \
 "$ROOT/core/src/main/kotlin/com/retq/solitairebot/core/"*.kt \
 "$ROOT/app/src/main/java/com/retq/solitairebot/capture/NormalizedBoardLayout.kt" \
 "$ROOT/app/src/main/java/com/retq/solitairebot/vision/AutomaticRegionReader.kt" \
 "$ROOT/app/src/main/java/com/retq/solitairebot/vision/FramePixels.kt" \
 "$ROOT/app/src/main/java/com/retq/solitairebot/vision/CardVision.kt" \
 "$ROOT/tests/waste-occupancy/FakeGlyphTemplates.kt" \
 -nowarn -d "$OUT/vision.jar"
printf '%s\n' 'PASS: production CardVision + AutomaticRegionReader Kotlin source compiles with Android boundary stubs'
