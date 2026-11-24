# Independent reset-control negatives (0.8.10)

These 116 synthetic geometry controls were created independently of the trained
reset-arrow examples. They exercise the production glyph recognizer through the
same pixel-backed JVM Android boundary doubles as the other vision tests.

Each of two shaded backgrounds (green empty tile and blue card back) has one blank
case plus three colors (green, gold, white) of the following shapes:

- Plain rings at three stroke widths.
- A 290-degree open ring at twelve rotations.
- A cross, triangle, square outline, and filled disk.

No case is a reset arrow. None may be reported as `RECYCLE`. Gold rings and arcs
must instead be recognized as a busy/loading indicator. The `busy: null` cases
do not impose a busy classification; their critical assertion is that no reset
tap is authorized from a non-arrow shape.

The images were rendered at five times the delivered 70 × 98 dimensions and
downsampled with Lanczos. They are synthetic controls, not held-out game screenshots
and not evidence of live-device recognition accuracy. Actual supplied-video reset
and spinner examples are checked separately by the vision-speed suite.

Run from the project root:

```sh
JSON_JAR=/path/to/json.jar KOTLIN_BIN=/path/to/kotlinc \
  sh tests/run-independent-icon-regression.sh
```
