# Changes 0.8.4 — first-screenshot automatic practice

## User-visible changes

- The first screenshot can detect all13 board regions, identify foundation suit order, read visible cards, and start the existing live controller automatically.
- Added bundled offline rank/suit and recycle-symbol recognition. Manual templates remain an optional fallback; no sample card labels are injected into a live board.
- New snapshot action label: لقطة وبدء تلقائي. A preparing/cancel state is visible before automatic play. Advanced manual calibration/training is grouped separately.
- Automatic observations populate the review interface. Review of the saved image re-reads that exact image; later live state is never silently approved against the initialPNG.
- Continuous MediaProjection frames drive subsequent moves without further screenshot requests. Reachable stop control remains outside the board.

## Rules and execution

- Added DrawOne while retaining DrawThree and supporting both configured stock packet sizes.
- Added FlipTableau only for positively detected exposed card backs and validated in-column touch points.
- Recycling requires visible waste and positive reset-control evidence or a known positive remaining count.
- Bounded recovery after ambiguous gestures uses two fresh frames, blocks the old board/move edge, and stops on the third failure. Single-move manual mode still stops on its first failure.
- Stops on completed foundations, sustained unreadability, changed window, lost permissions, screen lock, time/action/cycle limits.
- Unknown draws/flips terminate speculative lookahead; hidden card identities are never invented.

## Correctness protections

- AutomaticSetupGate binds asynchronous work to request and capture generation; cancelled or superseded OCR cannot start playback.
- Snapshot save and setup commit compare foreground package AND window revision acquired before image conversion.
- Final dispatch repeats card/back coordinate stability checks, not merely card identities.
- Initial screenshot reference is separate from human-reviewed proof and scoped to capture/layout identity.
- Unconfirmed actions invalidate provisional hidden-card counts/redeal evidence before recovery.
- Unknown foundation display uses ? rather than displaying an unverified0 as an empty foundation.
- Existing0.8.3 lifecycle, buffer ownership, stale-event, overlay and manual-evidence protections remain.

## Limits

This is a conservative shape/geometry recognizer for the portrait layout documented in README, not a universal trained neural card detector. Acceptance/rejection on generated fixtures is documented in TEST_REPORT; no real-game accuracy, installed-device test or guaranteed win is claimed. The engine selects ranked legal candidates with bounded lookahead, not a proof of globally optimal play with hidden cards.

The default automatic stock operation is one tap followed by observed effects. It does not infer Draw1 solely because only one waste card is visible; configured theoretical packet size defaults to3 unless the user specifies1. Touch operation follows the game itself in either mode.
