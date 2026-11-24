# Battle Arena screenshot calibration — 0.6.1

## What was calibrated
Based on the user-provided 540x1170 portrait screenshot:
- Four foundation crop regions (left to right: clubs, spades, hearts, diamonds).
- Waste region and stock region in the upper-right area.
- Seven tableau column search regions.
- Sample's visible tableau top cards: AS, AH, KS, 2S, QH, JH, QS.

All regions are normalized against the full screenshot. This profile is suitable only
when the game's UI scale, aspect ratio, and orientation match the reference. For a
new device resolution, normalized coordinates scale automatically; for a different
aspect ratio or UI layout, recalibration is required.

## Important limits
- The screenshot contains a tutorial overlay covering the lower play area. The bottom
  of each tableau region is therefore conservative and is not a full-board calibration.
- The waste pile shows overlapping cards; only the exposed top card should be sent to
  card recognition. The screenshot alone does not establish the complete waste order.
- No OCR/card classifier is implemented by this profile. The sample card labels are a
  manually transcribed fixture, not an automatic recognition result.
- Coordinates are crop/search boxes, not tap targets. Do not execute gestures from
  these boxes without a separate interaction calibration and user-authorized testing.
- No Android build, APK generation, or physical-device test was performed.

## Next calibration input
Provide a screenshot after closing the tutorial popup, with the full tableau visible.
A second screenshot after drawing from Stock will help calibrate the waste pile and
card overlap spacing.
