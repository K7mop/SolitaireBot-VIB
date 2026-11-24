# SolitaireBot VIB — source update

## Changes in this delivery
- Added `DeckCatalog`, a canonical, unique 52-card Klondike deck with all four suits and ranks A–K.
- Added reusable rule checks for alternating-color descending tableau placement, King-only empty-column placement, next-rank foundation placement, valid face-up runs, and remaining-card calculation.
- Connected `DeckKnowledge.unseen()` to the canonical deck catalog. The existing knowledge policy remains conservative: only observed cards and positively implied foundation cards are identified; hidden cards are never guessed.
- Added focused JUnit coverage for deck completeness, parsing, tableau/foundation rules, and unseen-card accounting.
- Unified Android version metadata as `0.9.2-VIB` / versionCode `902`; changed the app label to `SolitaireBot VIB`.
- Preserved the original application ID and existing Android permissions.

## Inherited behavior retained
The source already contains the 0.9.1 seven-column geometry recovery, calibration checks, automatic capture/control loop, stock-pass memory, scoring ledger, and strategy modules. These were retained rather than replaced. The seven-column geometry fix is documented in `CHANGES_0.9.1.md`.

## Validation limits
The input ZIP passed its archive integrity check and contained 1,298 entries. Source edits and the output ZIP were checked for presence and archive integrity. A Gradle/JUnit run and Android build were not possible in this environment because Gradle and the Kotlin compiler are not installed and the wrapper distribution cannot be assumed available offline. This delivery is a modified source ZIP, not a tested APK. Please run the project tests and Gradle build in Android Studio before installing.
