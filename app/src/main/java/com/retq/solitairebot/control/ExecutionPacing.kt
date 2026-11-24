package com.retq.solitairebot.control

import com.retq.solitairebot.core.Move

/**
 * Fast initial physical timings with bounded, evidence-driven recovery. A timer
 * never proves a move succeeded: LiveSession still requires two fresh, matching
 * post-gesture observations and the exact expected board transition.
 */
class ExecutionPacing {
    companion object {
        const val ACTIVE_SAMPLE_MS = 50L
        const val PARKED_SAMPLE_MS = 450L
        const val STABLE_FRAME_SEPARATION_MS = 40L
        const val INITIAL_TAP_MS = 42L
        const val INITIAL_DRAG_MS = 145L
        const val INITIAL_VERIFY_DELAY_MS = 55L
    }
    private var slowdown = 0
    private var consecutiveSuccesses = 0
    private var observedVerificationMs = 0L

    fun reset() { slowdown = 0; consecutiveSuccesses = 0; observedVerificationMs = 0L }

    fun durationFor(move: Move): Long = when (move) {
        Move.DrawOne, Move.DrawThree, Move.RecycleStock, is Move.FlipTableau -> INITIAL_TAP_MS + slowdown * 8L
        else -> INITIAL_DRAG_MS + slowdown * 35L
    }

    fun verificationDelayMs(): Long = INITIAL_VERIFY_DELAY_MS + slowdown * 20L

    /** A positively unchanged board can finish a no-op earlier than the hard OCR timeout. */
    fun unchangedDeadlineMs(): Long = maxOf(1_100L + slowdown * 160L,
        observedVerificationMs * 3L).coerceAtMost(2_400L)

    fun confirmed(verificationMs: Long) {
        val measured = verificationMs.coerceIn(0L, 2_000L)
        observedVerificationMs = if (observedVerificationMs == 0L) measured
            else (observedVerificationMs * 3L + measured) / 4L
        consecutiveSuccesses++
        // A slow observation alone need not mean an unreliable gesture. Shorten
        // the stroke again only after six separately verified successful moves.
        if (consecutiveSuccesses >= 6) { slowdown = (slowdown - 1).coerceAtLeast(0); consecutiveSuccesses = 0 }
    }

    fun failed() { slowdown = (slowdown + 1).coerceAtMost(5); consecutiveSuccesses = 0 }
    fun description(): String = "tap=${INITIAL_TAP_MS + slowdown * 8L}ms, drag=${INITIAL_DRAG_MS + slowdown * 35L}ms, " +
        "verifyFence=${verificationDelayMs()}ms, backoff=$slowdown"
}
