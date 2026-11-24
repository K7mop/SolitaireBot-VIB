# Local vision regressions (0.8.3)

This harness compiles the real production `GlyphTemplates`, `CardVision`, `NormalizedBoardLayout` and core classes. It renders deterministic RGB card shapes and slot images, then runs the actual template extractor, training persistence, matching and board reader over those pixels. The recognizer is **not mocked**.

The small `platform` classes replace Android Context/SharedPreferences, Bitmap pixel storage, Color, Base64 and the monotonic clock with JVM equivalents. JSON encoding/decoding uses the real `org.json` dependency. The tests do **not** exercise Android's compositor, MediaProjection, AccessibilityService, OEM overlay behavior, or the real game's font and artwork. Synthetic image success is not a claim of real-device OCR or drag accuracy.

## Run

Requirements: JDK17+ and Kotlin2.2.21 (or compatible newer compiler), plus `org.json:json:20240303`. Obtain the dependency from its primary Maven repository:

https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar

From the project root:

```sh
JSON_JAR=/absolute/path/json-20240303.jar KOTLIN_BIN=/absolute/path/kotlinc sh tests/run-vision-regression.sh
```

The script writes its compiled fixture only in a temporary directory and removes it on exit. It never changes project sources or packages platform doubles into the Android app.

## Covered regressions

- Actual taught-card event counts are separate from rank/suit pattern coverage.
- Confirmed empty columns remain usable, including king destinations.
- Session-bound empty-patch evidence survives omission of manual data, but is cleared for a new session.
- A previously reviewed empty foundation cannot be certified empty from a newer occupied live image.
- No manual emptiness evidence is created without the exact reviewed reference bitmap.
- Geometry selects the lowest physical card, with distinct anchor positions for a run and touch points inside exposed strips.
- Previously identified cards may remain known only while their still-visible pixel strip matches uniquely.
- An unknown lowest card blocks the column rather than promoting a higher known card.
- The playable waste card follows the rightmost full card when the fan shrinks.
- Stock available/empty states require positive taught region patches.
- Cross-instance concurrent training does not lose example counts or revisions.
- Concurrent recognition sees immutable template snapshots.

Runtime requires calibration and teaching from the actual game theme. Empty-column, empty-foundation, empty-waste, stock-back and empty-stock samples should each be taught explicitly where those states occur. A missing visual proof pauses execution; it is not replaced by an invented card or empty slot.
