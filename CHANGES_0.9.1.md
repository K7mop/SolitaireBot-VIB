# SolitaireBot 0.9.1 – Tableau Geometry Recovery

## Fix in this revision
- Removed the invalid assumption that the bottom edge of the exposed card must move progressively downward from tableau column 1 through column 7.
- Seven-column geometry is now checked using the shared horizontal pitch, card widths, card-height consistency, and a bounded overall vertical spread. This accommodates supported boards with multi-card face-up runs and uneven overlaps, including the layout shown in the user's Battle Arena screenshot where column 1 extends below column 2.
- Updated the rejection message to identify the remaining visual prerequisite: the assistant overlay must be minimized and the full top row/board must be visible before automatic calibration can safely read the four foundations.
- Version: 0.9.1 (versionCode 901).

## Important validation note
This is a source revision, not a verified APK. The current build environment has no Android SDK and cannot download the Gradle distribution, so an installable APK could not be built or tested here. The screenshot also has the assistant panel covering the foundation row; card identities hidden behind an overlay cannot be safely inferred from pixels. Minimize the assistant panel before the first automatic capture.
