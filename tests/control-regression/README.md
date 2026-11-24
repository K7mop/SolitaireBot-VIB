# Control regression scenarios

Run with a Kotlin 2.x command-line compiler and a JDK:

```sh
sh tests/run-control-regression.sh
# Or: KOTLINC=/absolute/path/to/kotlinc sh tests/run-control-regression.sh
```

The harness compiles the real production `LivePipeline`, `LiveSession`,
`MoveExecutor`, `GestureAccessibilityService`, and all `core` sources. Controlled
stubs supply the Android main queue, clock, permission/storage boundary, frame
recognition result, and physical gesture dispatcher. The stubs are outside `app`
and are not included in the APK.

Scenarios cover continuation after a verified move/cooldown, STOP before queued
input, old callbacks, missing dispatch callbacks, visual-verification deadlines,
overlay cleanup on guard rejection, failure-history persistence, single manual
move, same-package window replacement, migration of legacy glyph templates, positive/confident waste evidence before stock actions, last-good-observation watchdog timing, and stale vision publication after stop/capture replacement.

These are application-state and scheduling tests. They do not establish actual
Android capture, accessibility gesture delivery, OCR accuracy, or game theme
compatibility. The ordinary JUnit tests under `app/src/test` additionally cover
fresh frame IDs, capture generations, positive endpoint evidence and exactly-once
terminal timeout results. Full build/instrumentation/device verification remains
a separate check.
