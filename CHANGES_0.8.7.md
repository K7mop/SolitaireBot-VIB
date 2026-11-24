# SolitaireBot 0.8.7 — Correct premature stops and constrained stock follow-through

21 November 2026. Source update following user-supplied 300-second filmed round and two screenshots; no APK built here.

## Evidence and diagnosed failure

- Actual video shows a few real automatic movements, then assistant controls repeatedly consulted; user finishes most of round manually. This confirms that touch dispatch works initially; it does **not** prove that the recognizer sees every later card.
- The in-app screenshot reports «لا توجد حركة مؤكدة جديدة بعد قراءات مستقلة؛ توقف التنفيذ دون تخمين» despite the visibly present top-right stock pile / face-up waste fan.
- In 0.8.6 `LivePipeline.accept()` unconditionally stopped after three no-candidate frames/three seconds. `MoveExecutor.pointsFor(DrawThree)` also required the waste card's **OCR identity** (or positively blank slot), so a physically visible but unreadable fan could block a legal stock tap; `VisualSafety` required OCR of the new waste card for draw verification.
- After stock exhaustion, `MoveGenerator` required an OCR-visible waste card even when a recycle control was recognized. Thus an unreadable top card blocked recycling.
- Captured video is externally filmed; timing and exact in-game callback traces cannot be measured reliably from it. This is code-grounded diagnosis with controlled regressions, not an on-device performance benchmark.

## Fixes

1. **Do not end timed monitoring just because 3 stable reads offer no supported edge.** Park safely and sample more slowly while waiting for a new state or an observable action. Continue 300-second session unless time, permission, foreground, or real failure conditions require stopping. Guard still refuses tried edges and loops.
2. **Separate waste occupancy from rank recognition.** `AutomaticRegionReader.unidentifiedWasteFace` requires a predominantly pale full-face rectangle; it is not a card identity. `CardVision` captures a per-frame waste-region visual signature and confidence. A blue back, blank felt, or a narrow border strip does not count as a light face.
3. **Stock actions with unreadable waste** are permitted only if stock is positively classified AND the waste is identified/empty or visually confirmed occupied; proof of a changed waste fingerprint or newly identified waste card is required from two stable frames.
4. **Recycle stock with unreadable waste** is allowed only with a positively recognized recycle affordance and an occupied waste fan, then requires evidence that stock became available and waste empty. No unlimited or speculative redeals.
5. **Per-physical-state loop guard:** an OCR-null fan's fingerprint differentiates distinct observed stock-draw states; attempted actions against the same state remain blocked. Revisiting one state after three confirmed events parks monitoring rather than blindly repeating.
6. **Adaptive timing**: acquisition up to 3 FPS during active reading/moves; roughly 1 FPS when parked; OCR throttle 290ms active and 1050ms parked; 620ms between gesture results and next dispatch; touch/drag 75/340ms. Actual on-device gains not measured; two independent stable frames still required.
7. **Action diagnostic line**: legal / not-yet-attempted / coordinate-verified candidates, stock state and unknown regions. Increased hard action budget from 120 to 360 within 300-second maximum; no repeated same-state edges.

## Safety invariant

- Card identity from uncertain OCR is never promoted to a known rank/suit.
- Source/destination card moves require their original individual confidence checks.
- Stock click/recycle requires positive visual evidence, two fresh stable frames and a verified postcondition; no blind taps.
- Stop button, accessibility checks, session epochs and foreground checks remain active.
- Lack of moves is not a reason to execute an invented move; unsolvable/uncertain positions can only be monitored.

## Verification

- 41 production core/calibration smoke assertions pass.
- 26 actual `LivePipeline`/`LiveSession`/`MoveExecutor` control regressions pass with mocked Android boundaries, including 4 added checks for unreadable visible waste, matching fingerprint and parking/recycle.
- Real `AutomaticRegionReader` pixel logic passes synthetic positive/negative occupancy test; user screenshot waste-fan sample produced 24/25 pale probes in photo 137242 and 137246, though this is not a full Android OCR test.
- Production `CardVision` and `AutomaticRegionReader` compile using Kotlin plus Android boundary stubs.
- Existing full Gradle/Android SDK build and installation were NOT available in this environment. No measured move speed on a physical phone; final APK requires Android Studio on user's Windows laptop.
