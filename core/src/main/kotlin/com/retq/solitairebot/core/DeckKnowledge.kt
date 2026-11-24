package com.retq.solitairebot.core

/**
 * Remembers identities actually seen in this round, including foundation cards
 * implied by a positively observed rank. It never assigns an unseen identity to
 * a particular face-down card or hidden stock position.
 */
class DeckKnowledge {
    private val seen = linkedSetOf<Card>()
    @Synchronized fun reset() = seen.clear()
    @Synchronized fun observe(board: BoardState) {
        if (board.problems().isNotEmpty()) return
        board.columns.forEach { column -> seen.addAll(column.faceUp) }
        board.waste?.let { seen.add(it) }
        for ((suit, rank) in board.foundations) {
            for (r in 1..rank) seen.add(Card(r,suit))
        }
    }
    @Synchronized fun knownCount(): Int = seen.size
    @Synchronized fun unseen(): Set<Card> = DeckCatalog.remainingAfter(seen)
    /** Uniform unknown-card prior; value is only a heuristic, NOT a location prediction. */
    @Synchronized fun immediateMobilityChance(board: BoardState): Double {
        val remaining=unseen()
        if (remaining.isEmpty()) return 0.0
        val opportunities=remaining.count { card ->
            card.rank == board.foundations.getValue(card.suit) + 1 ||
                board.columns.any { column ->
                    val top = column.top
                    if (top != null) top.rank==card.rank+1 && top.red!=card.red
                    else column.definitelyEmpty && card.rank==13
                }
        }
        return opportunities.toDouble()/remaining.size
    }
}
