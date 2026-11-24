# SolitaireBot 0.8.9 - verified stock tracking and bounded planning

This release MODIFIES the existing 0.8.8 Android Studio source, rather than
replacing its capture/gesture architecture. App id, modules, overlay, accessibility
and existing click/drag code are preserved. Source package, NOT a prebuilt APK.

## What is new

1. StockPassMemory remembers ONLY confirmed waste tops, physical image signatures
   and completed draw/recycle events, along with the number of successful recycle
   operations and their user-confirmed **15-point penalty**. The app no longer
   treats an unseen draw-three middle card as a known card. If an entire further
   pass repeats identically with no verified progress, another recycle is blocked
   before paying the same penalty again. A genuinely changed board can unlock
   further legal stock actions through the existing AutoPlayGuard.
2. DeckKnowledge retains all positively observed card identities and all foundation
   ranks entailed by a positively observed foundation top. It does NOT place an
   unseen card into a particular column or stock position. A uniform unseen-card
   mobility estimate is used only as a small heuristic bonus when revealing cards.
3. SolitaireSolver now enforces per-decision CPU deadlines (~8/12/19 ms based on
   time left) and caches completed tactical subproblems; TacticalBeamPlanner adds a
   bounded, observation-safe beam for near-future foundation opportunities.
4. EndgamePlanner uses bounded best-first search ONLY when every remaining card is
   known, no waste/stock exists and the visible+foundation count equals 52.
   Otherwise it abstains. It cannot solve arbitrary unknown stock distributions.
5. SmartDecision merges verified stock cost, limited tactical beam and a complete
   endgame path with existing proposal safety checks. It never overrides the
   visibility, confidence or physical-coordinate validations in MoveExecutor.
6. A lightweight optional GreenHudReader reads the score and time HUD on the
   supported portrait layout. Its glyph masks are derived from user-provided
   gameplay screenshots; digits 5 and 9 are preliminary synthetic fallbacks.
   HudClockTracker demands consecutive monotonic timer samples. Unsupported fonts
   and ambiguous digits yield UNKNOWN, not fabricated values. Estimated point
   ledger is labeled as an estimate; the game-winning time bonus is still unknown.
7. Adaptive timing changes the post-move settling minimum 500ms -> 390ms and
   sample intervals (250ms -> 210ms active, 760ms -> 650ms while parked). The
   actual gesture duration and the TWO-new-frame visual confirmation are unchanged.
   These timing changes need real-phone validation for animation stability.
8. RoundDiagnostics records bounded in-memory move attempts, confirmations,
   failures, unknown-regions and accepted HUD score changes. The main app has an
   'Export round diagnostics' sharing control; it exports text, not screenshots or
   wallet/account data. The data is process-local, and can be lost on force-close.

## Intended next device test

Build via Android Studio (JDK17, SDK35), install, enable permissions, start a
fresh virtual-practice round, tap 'Capture and start automatic' once, and test
five minutes. At the end return to the app and share the diagnostics report.
Compare game score and win/time bonus against the HUD ledger, verify recycling
and the -15 cost, and inspect any stability or gesture failures before tuning.

## Important limitations

- No guarantee of 12,000-14,000 points or full completion. The score structure
  and winning-time bonus of the specific game have not been validated.
- No APK assembleDebug was executed here: Android SDK/Gradle not available.
- No automated Android/on-device five-minute rounds were performed here.
- Fully unknown stock/hidden-card identities remain unobserved until revealed.
- Undo remains disabled without a positively identified in-game Undo control.
- Most ROI/fonts remain calibrated to the demonstrated game layout. The HUD OCR
  can refuse to classify 5/9 or other unfamiliar numeral shapes.
- Recycle can be withheld after no-progress repetition to avoid wasting 15 points;
  the app then safely watches for new information instead of blindly tapping.
