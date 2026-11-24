# Offline automatic glyph recognition, 0.8.4

The production `BundledCardRecognizer` reads RGB pixels. It supports upright,
pale-faced cards whose upper-left corner contains a rank above a suit. A complete,
separate rank and suit must be visible. Rank/color agreement, match similarity and
runner-up separation are mandatory. Ambiguous/unreadable input returns `null`.

Bundled knowledge covers A, 2–10, J, Q, K and ♣ ♦ ♥ ♠. The package has 10,591
normalized glyph templates rendered from DejaVu, Liberation, Noto and Open Sans
fonts, including distractors such as O and 0 that must not become Q. These are
local font/shape templates, **not a neural model trained on the user's game**.
Nothing is sent to a server; no model download, account or API key is used.

The model loads once into an immutable process cache. Existing user-trained
`GlyphTemplates.recognize()` and region templates remain independent. Clearing
optional user teaching does not remove the bundled knowledge. Automatic methods
are explicitly selected by the board reader; bundling does not silently turn
manual evidence into automatic proof.

`recognizeSuitAutomatic` recognizes a centered filled/hollow icon on a uniform
slot background. Hollow outlines are filled only inside closed boundaries; the
same strict shape score and ambiguity gates still apply.
It proves the icon identity only, not that a foundation is empty. The caller must
separately verify whether the region contains a card. `recognizeRecycleAutomatic`
likewise proves only a circular-arrow affordance; the caller separately checks
stock emptiness and limits stock recycling.

## Reproducible checks

Run `tests/run-automatic-glyph-regression.sh` with `JSON_JAR` pointing at the
org.json dependency and `KOTLIN_BIN` at Kotlin 2.2.21. PNG inputs are included;
Android Bitmap/Context are small boundary doubles, but the production recognizer
and its packaged assets are used unchanged.

780 input images are checked: 728 positive card fixtures (624 across all 52 cards
at three held-out font sizes in four typefaces, plus 104 with shifted corner
positions), 28 negative card images, 16 centered suit icons and eight recycle
affordance cases. The positive fixtures are generated separately from the asset
samples; their sizes 19, 25 and 33 are absent from the asset generation sizes.
Results in this environment: **622 correct accepted cards, 106 unknown, zero
incorrect accepted cards; all 28 negative card cases rejected, all 16 suit icons
correct, and all eight recycle-affordance cases correct**. At DejaVu Sans sizes 25/33 and Noto Sans Bold sizes 25/33, every
one of the 52 cards is recognized. Small/serif shapes have more abstentions.

Negative card cases: 20 held-out-size non-rank glyphs plus blank face, patterned blue back, missing rank, incorrect suit
color, inconsistent rank/suit colors, unsupported O, cropped rank and random
noise. Slot tests cover filled and hollow symbols on pale and green backgrounds.
Recycle tests distinguish clockwise/anticlockwise circular arrows from blank
slots and ordinary circles. These checks cover concrete regressions; they do not prove accuracy on
all visual themes or all possible bad images.

These are synthetic raster tests, **not a live phone or actual-game benchmark**.
No clear full screenshot from the target game was available as a validated
fixture. Artwork, orientation, covered corners and unsupported fonts can require
additional samples. The application must stop/leave the card unknown rather
than relax the gates or fill a sample board.

`generate_glyphs.py` reproduces the asset and manifest using Pillow, fontTools and
the listed open fonts. `generate_fixtures.py` reproduces the RGB test inputs using
Pillow. The font notices are in `app/src/main/assets/OFFLINE_GLYPH_LICENSES.txt`.
Match scores describe visual similarity and separation; they are not calibrated
probabilities of correct recognition.

## Measured desktop JVM runtime

The final recorded run loaded the immutable asset in 547.51 ms. Seven full card
recognitions took a median 3.68 ms and maximum 3.87 ms over 25 batches after five
warmup batches. These times exclude screen capture, layout detection and the
rest of the live controller, and are not Android/device measurements. The full
font/size breakdown and benchmark output are in `RESULTS.txt`.

## Corner placement

The reader searches row bands in the upper-left 3.5–46% of card width and
2.5–54% of card height. The rank must appear above a separated suit, with
plausible size, gap and horizontal alignment. It does not depend on the older
manual recognizer's fixed rank/suit rectangles. All 104 high/low shifted-corner
fixtures were accepted correctly. Side-by-side corner marks, rotated cards,
covered corners, non-pale faces and unsupported artwork remain unknown.
