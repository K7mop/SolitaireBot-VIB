# Independent validation notes for 0.8.10

## Exact baseline

Baseline extracted from `SolitaireBot-Ready-0.8.9-Smart-Stock-Fast-Solver-Source.zip` into `baseline/SolitaireBot-Ready`; delivered source ZIP SHA-256 was `1be4b5eef05866612e5004a3326cc53cc2f344ad4011e395d0ef01df2fdaa101`.

`validation-baseline/checks.json` and corresponding logs preserve actual results before production edits. All 51 production Kotlin sources compiled with Kotlin 2.2.21 and Android API 35. AAPT2 resource compilation/link and D8 dex conversion passed. These are manual toolchain checks, not a Gradle build or a phone test.

The ten standalone shell suites passed. Original JUnit results were 81/84 passing; this exposes three assertions left behind by previous releases rather than hiding them behind the newer shell suites.

## JUnit contract reconciliation

1. `scorePrefersUncoverAndAvoidsLoop` previously required any recently considered ace/reveal to rank below a stock draw. Current ranking deliberately retains useful moves with a history penalty, while the execution guard blocks an already attempted `(board, move)` edge. Test now proves the score decreases and the guard rejects the repeated physical edge. It does not lower or bypass the guard.
2. `unknownRedealsNeedPositiveRecycleControlAndVisibleWaste` previously prohibited generating a recycle candidate with an unidentified waste top. Since 0.8.7, a positively recognized reset icon may generate such a candidate, but execution/verification must independently prove waste occupancy. Updated test retains the no-redeal rejection and tests both rejection without occupancy and acceptance with positive occupied-waste evidence.
3. `recoveryFromFoundationRemainsSelectable` expected a purposeless QS-to-KH rollback with no known follow-up. Since 0.8.8, non-beneficial rollback is filtered. Updated test proves legal-but-pointless rollback is not selected. Added separate useful recovery fixture: KH, JH above three hidden cards, foundation S=Q, other foundations empty. QS-to-KH permits JH-to-QS to expose a back; 0.8.9 incorrectly filtered this too. Root owns the production tactical correction; the new test must pass after that correction.

## Scope of performance evidence

Desktop JVM timing, simulated controller timing, and synthetic screenshot regression measure different parts of the application. None establishes phone frame rate, completed game score, live five-minute reliability, or an expected 14,000-point result. Report each metric with its scope.

## Toolchain

Kotlin 2.2.21; Java 17 bytecode target; Android API 35 stub library; AAPT2 Android 15; R8/D8 8.10.21; org.json; JUnit platform standalone 1.11.4. Installed Gradle executable and connected Android device were not available. The final verifier compiles core and app as distinct Kotlin modules, so cross-module internal-access errors are not hidden by a combined compile.
