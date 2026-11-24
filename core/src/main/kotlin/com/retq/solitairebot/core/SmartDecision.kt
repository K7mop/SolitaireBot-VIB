package com.retq.solitairebot.core

/** Additional ranking over the existing tested SolitaireSolver; no touch changes. */
object SmartDecision {
    fun choose(board: BoardState, candidates: List<Suggestion>, memory: StockPassMemory,
        secondsLeft: Int? = null, knowledge: DeckKnowledge? = null): List<Suggestion> {
        if (candidates.isEmpty()) return candidates
        // A forced action needs no speculative tree. Check a stock pass once,
        // not once per candidate (which repeatedly built the entire fingerprint).
        val recycleAllowed = candidates.none { it.move == Move.RecycleStock } || memory.canRecycle()
        val eligible = candidates.filter { recycleAllowed || it.move != Move.RecycleStock }
        if (eligible.size <= 1) return eligible.map { proposal ->
            if (proposal.move == Move.RecycleStock)
                proposal.copy(score = proposal.score - memory.costOf(proposal.move)) else proposal
        }
        val endgame = EndgamePlanner.plan(board, if ((secondsLeft ?: 300) < 30) 400 else 1200,
            if ((secondsLeft ?: 300) < 30) 8L else 18L)
        val firstFinishMove = endgame?.moves?.firstOrNull()
        val beam = if (firstFinishMove == null)
            TacticalBeamPlanner.bonuses(board, eligible, secondsLeft)
            else emptyMap()
        val mobilityChance by lazy { knowledge?.immediateMobilityChance(board) ?: 0.0 }
        return eligible
            .map { proposal ->
                val informationBonus = if (knowledge != null && proposal.move is Move.TableauToTableau &&
                    proposal.move.startIndex == 0 &&
                    (board.columns[proposal.move.from].hiddenCount ?: 0) > 0)
                    (mobilityChance * 35).toInt() else 0
                val bonus = when {
                    firstFinishMove != null && firstFinishMove.signature == proposal.move.signature -> 10000
                    proposal.move == Move.RecycleStock -> -memory.costOf(proposal.move)
                    else -> 0
                }
                proposal.copy(score = proposal.score + bonus + informationBonus +
                    (beam[proposal.move.signature] ?: 0))
            }.sortedWith(compareByDescending<Suggestion> { it.score }.thenBy { it.move.signature })
    }
}
