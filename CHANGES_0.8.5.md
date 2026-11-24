# 0.8.5: Correct foreground-app tracking with floating controls

## Verified root cause from the 0.8.4 source

The user supplied a saved capture showing seven visible tableau columns, four
foundation cells, and a stock. The error matched the `require()` in
`ScreenCaptureService.prepareAutomaticBoard` (`target == null` or equal to the
assistant package). This check runs BEFORE `AutoBoardSetup.detect`, so the error
does not mean the 13-region recognizer processed and rejected that screenshot.

## Fix

`GestureAccessibilityService.onAccessibilityEvent` now ignores window-state events
from its OWN non-focusable TYPE_APPLICATION_OVERLAY while `assistantUiVisible` is
false. This prevents the overlay control window from replacing the currently
tracked game package and invalidating snapshot ownership. When a real assistant
activity is visible, own-package events DO invalidate the game. Events for any
other application also invalidate it. No changes to permissions or gesture
authorization and cancellation checks. `CaptureMonitorStore`'s existing
application lifecycle state is used as the source of truth.

Additional change: use `java.util.ArrayDeque` for the bounded 24-move history;
functionality is unchanged and the control regression suite also compiles on
Kotlin 1.9 on the inspection machine.

Version code 805, version name 0.8.5, package remains `com.retq.solitairebot`.

## Test results in this turn

- 22/22 control regression scenarios passed with the PRODUCTION
  `GestureAccessibilityService`, `LivePipeline`, `MoveExecutor`, and `LiveSession`
  against Android boundary stubs.
- Three new regression assertions cover overlay event, assistant activity
  event, and unrelated-app event.
- 41/41 core/calibration/guard smoke assertions passed.
- Previous 0.8.4 visual and instrument-free tests remain in the package as
  historical results; they were NOT all rerun for this patch.
- Gradle `assembleDebug` and live-device installation were NOT run here.

This patch addresses the **specific first failure**. The user's game's distinctive
card graphics and geometry could still require another fix; do not infer successful
OCR or gameplay from foreground-event tests alone.
