# Vision speed and evidence regressions — 0.8.10

Run with the same Kotlin compiler and org.json JAR as the existing vision tests:

```sh
KOTLIN_BIN=/absolute/path/to/kotlinc JSON_JAR=/absolute/path/to/json.jar \
  sh tests/run-vision-speed-regression.sh
KOTLIN_BIN=/absolute/path/to/kotlinc JSON_JAR=/absolute/path/to/json.jar \
  sh tests/run-vision-speed-benchmark.sh
```

An optional final argument points to a directory containing the user-supplied
`137242.jpg` and `137246.jpg` files. These full phone screenshots are deliberately
not included in this source package. When they are present, the regression also
checks the actual pixels → covered-back evidence → QD-to-KC move priority path.
The benchmark reports their original 716×1536 readouts and same-source 1080×2316
resampling. Resampling is a scale compatibility check, not another independent
phone observation.

For a controlled baseline, use the unchanged 0.8.9 source directory:

```sh
VISION_SOURCE_ROOT=/absolute/path/to/unmodified-0.8.9 \
KOTLIN_BIN=/absolute/path/to/kotlinc JSON_JAR=/absolute/path/to/json.jar \
  sh tests/run-vision-speed-benchmark.sh
```

## What the tests cover

- A new frame overwrites the reused RGB buffer, and exceptions revoke its source
  binding. The test double counts individual `getPixel` and bulk `getPixels`
  boundaries. These counts describe production method calls; JVM timings are
  not Android timings, camera frame rates, physical drag speeds, or game scores.
- The pure glyph classifier memoizes exact normalized mask bits and classifier
  group, never a previously known board position or an assumed card identity.
  The changing-board benchmark verifies draw, foundation, exposed-back and flip
  results after each actual image change.
- The gold rotating ring observed at video 137529 seconds 135, 175 and 220 is a
  **busy/loading indicator**, on both blue and green stock backgrounds. It must
  never become a positive recycle button. Plain blue stock is neither busy nor
  a recycle icon.
- The true reset affordance is a faint, static green counterclockwise arrow.
  Two stock-only captures (219.0 and 225.5 seconds) are calibration examples.
  Twelve other captures from that supplied video are held out. On this filmed,
  blurred material, four held-outs are positively recognized and eight remain
  unknown. This is not a claim that all reset images or the user's phone have
  been recognized successfully. The same confidence and runner-up thresholds
  remain in force.
- Nine card-only crops are labelled calibration examples from supplied
  screenshots. Seven card crops from a later screenshot are held out. All seven
  are recognized in explicitly full-face mode with no confidently wrong labels
  in this small set. The upper ranks of 5C and 6H remain ambiguous by themselves:
  without complete-face permission they are still rejected. When the whole
  physical card is exposed, a strict upright lower-right rank can corroborate
  the upper rank's best candidate. Both labels, the upper suit and all ink
  colors must agree; the normal .90 similarity and .055 ambiguity thresholds
  remain unchanged. No held-out pixels were added to the template asset.
- Lower-rank tests reject partially exposed permission, conflicting indices,
  a rotated lower6 that resembles9, blank cards, card backs and invalid bounds.
  Of four Java-generated 131×190 crop resamples, three are recognized and one
  bilinear5C crop remains unknown. This intentional abstention is retained.
  The full board reader also checks bounded anchor offsets; both supplied full
  screenshots are read with all seven top cards, waste and foundation states
  at the original716×1536 and reconstructed1080×2316 sizes. These resizes are
  same-source checks, not native phone captures or proof of all hidden cards.
- Calibration corner variants resized from 87×126 to 131×190 with bilinear and
  Lanczos interpolation are included in the asset. They are explicitly
  **same-source augmentation**, not held-out accuracy evidence.
- A `coveredTableauBacks` flag requires a positively learned back palette and
  a contiguous textured strip directly above the first identified card of a
  complete visible run. Pale face strips, blank gaps, an unseeded reader and
  a column-origin face do not qualify. Exact hidden counts stay unknown.

The independent icon negatives in `tests/independent-icons` additionally check
ordinary circles/arcs, crosses, disks, triangles and squares. The original
synthetic glyph and complete-board suites remain required; the small actual
image set alone is not adequate recognition validation.
