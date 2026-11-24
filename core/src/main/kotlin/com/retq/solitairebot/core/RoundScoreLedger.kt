package com.retq.solitairebot.core

/**
 * Verified-move estimate, NOT the game's actual score or win/time bonus.
 * Actual HUD scoring may differ; only recycle's 15-point cost is user confirmed.
 */
class RoundScoreLedger(val recyclePenalty: Int = 15) {
    data class Stats(val verifiedMoves: Int, val estimatedBaseDelta: Int,
        val recycled: Int, val raisedToFoundation: Int, val newlyExposedBacks: Int)
    private var moves = 0
    private var estimate = 0
    private var recycled = 0
    private var raised = 0
    private var exposed = 0
    @Synchronized fun reset() { moves = 0; estimate = 0; recycled = 0; raised = 0; exposed = 0 }
    @Synchronized fun verified(before: BoardState, move: Move, after: BoardState) {
        val advanced = after.foundations.values.sum() - before.foundations.values.sum()
        if (advanced > 0) { estimate += advanced * 10; raised += advanced }
        for (i in 0 until 7) {
            val prev = before.columns[i]
            val next = after.columns[i]
            if ((!prev.exposedBack && next.exposedBack) ||
                (prev.hiddenCount != null && next.hiddenCount != null && next.hiddenCount < prev.hiddenCount)) {
                exposed++; estimate += 5
            }
        }
        if (move == Move.RecycleStock) { recycled++; estimate -= recyclePenalty }
        moves++
    }
    @Synchronized fun stats(): Stats = Stats(moves, estimate, recycled, raised, exposed)
}
