# SolitaireBot 0.8.6 — Detect overlapping card rows in the user's portrait game

21 November 2026 — Source-code update for Android Studio. This is NOT a prebuilt APK.

## Reproduced problems

- The old `AutoBoardSetup.findGeometry` used a white scanline at both sides of each card. The real deck joins some blue back borders to the pale face, producing misleading face heights. The earlier detector either rejected the seven columns or inferred incorrect boundaries.
- Empty foundation suit marks on the photographed game are subtle green-on-green silhouettes. The original hard RGB-contrast threshold and stock upper/lower-edge test rejected them even after the columns were found.
- The game's corner design puts the rank at the upper left and its small suit at the upper right, rather than using the older rank-above-suit design. The older recognizer consequently returned unknown cards.
- Gradients on green felt made the initial reference color too different from the upper foundation/waste row, so visibly empty slots were reported as unknown.

## Source fixes

1. `AutoBoardSetup.kt`: image-derived pale connected components; seven-column pitch/size checks; face top derived from the bottom; thin-outline stock card used only to correct *inflated* face height; strict rejection of incomplete/masked boards; stock interior-vs-gutter evidence replaces the impossible two-edge contrast requirement.
2. `BundledCardRecognizer.kt`: low-contrast centered foundation silhouettes get image-relative thresholding, and a separate independent-rank/independent-suit path handles the screenshot's two top corners without weakening the card's required confidence and red/black consistency checks.
3. `CardVision.kt`: ignores pale blue-back border stripes instead of treating them as white face-up card tops; a candidate face must be pale in several close rows.
4. `AutomaticRegionReader.kt`: recognizes empty spaces against the *same-row* local gutter (for shaded felt) and checks green foundation placeholders by a recognized suit and absence of an overlying light face.
5. `offline_card_glyphs.txt`: compact 24x24 binary patterns derived from the user-supplied game screenshot, including simple rescaled variants. The screenshot itself and its balance are NOT included in the source archive.
6. `tests/automatic-layout`: synthetic regression case for white card borders connected to blue back stripes.

## Verified in the editing environment

- Source-level Kotlin/JVM regression with production geometry, glyph recognition, and RGB image pixels: **17/17** synthetic automatic-board scenarios passed, including the new connected-back case, incomplete board rejection, and Draw1 / Draw3 stock/recycle states already covered by regression tests.
- Regression: **22/22** production control scenarios passed, and **41/41** core smoke assertions passed.
- The screenshot the user provided earlier (716 x 1536) now yields all **13 board regions** and correctly reports seven exposed cards: 9S, 3S, 4H, 6H, 3H, 8D, 8S, four empty foundations, and an empty waste. An enlarged 1080 x 2316 image of that screenshot yields the same seven labels.
- These readouts use **glyph examples extracted from that SAME image**. They do NOT establish accuracy on a fresh random deal or prove that all 52 card illustrations will be read.
- A screenshot with the floating assistant covering the cards is rejected by automatic layout detection.
- All direct source-level checks used JVM Android/JSON test doubles. **No full Android Gradle build, install, finger gesture or device test was performed here.** The user must build the APK in Android Studio and check a new deal on the phone.

## Safe behavior

If any card, foundation, stock state or layout is unknown, the program must refrain from an unsupported move; it must not substitute guessed identities for unseen cards. Keep the floating Stop control available. The software is for a virtual-money practice game as described by the user.
