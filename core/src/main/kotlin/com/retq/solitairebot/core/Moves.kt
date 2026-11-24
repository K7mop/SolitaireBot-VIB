package com.retq.solitairebot.core

/** Only legality over the cards we KNOW is considered. No hidden-card guesses. */
sealed class Move {
    abstract val signature: String

    data class TableauToFoundation(val column: Int, val card: Card) : Move() {
        override val signature = "T${column + 1}->F${card.suit.symbol}:${card.code}"
    }
    data class WasteToFoundation(val card: Card) : Move() {
        override val signature = "W->F${card.suit.symbol}:${card.code}"
    }
    data class TableauToTableau(val from: Int, val startIndex: Int, val to: Int, val cards: List<Card>) : Move() {
        override val signature = "T${from + 1}[$startIndex]->T${to + 1}:${cards.joinToString(":") { it.code }}"
    }
    data class WasteToTableau(val to: Int, val card: Card) : Move() {
        override val signature = "W->T${to + 1}:${card.code}"
    }
    data class FoundationToTableau(val fromSuit: Suit, val to: Int, val card: Card) : Move() {
        override val signature = "F${fromSuit.symbol}->T${to + 1}:${card.code}"
    }
    data class FlipTableau(val column: Int) : Move() {
        override val signature = "FLIP:T${column + 1}"
    }
    data object DrawOne : Move() { override val signature = "DRAW1" }
    data object DrawThree : Move() { override val signature = "DRAW3" }
    data object RecycleStock : Move() { override val signature = "RECYCLE" }
}

object MoveGenerator {
    private fun canPutOnTableau(card: Card, destination: TableauColumn): Boolean =
        destination.top?.let { top -> top.rank == card.rank + 1 && top.red != card.red }
            ?: (destination.definitelyEmpty && card.rank == 13)

    fun generate(board: BoardState): List<Move> {
        if (board.problems().isNotEmpty()) return emptyList()
        val moves = mutableListOf<Move>()
        board.columns.forEachIndexed { from, col ->
            if (col.exposedBack) moves += Move.FlipTableau(from)
            val top = col.top
            if (top != null && board.foundations.getValue(top.suit) + 1 == top.rank) {
                moves += Move.TableauToFoundation(from, top)
            }
            for (start in col.faceUp.indices) {
                if (!col.validRun(start)) continue
                val run = col.faceUp.drop(start)
                val first = run.first()
                for (to in board.columns.indices) {
                    if (to == from) continue
                    val target = board.columns[to]
                    if (canPutOnTableau(first, target)) {
                        moves += Move.TableauToTableau(from, start, to, run)
                    }
                }
            }
        }
        board.waste?.let { card ->
            if (board.foundations.getValue(card.suit) + 1 == card.rank) moves += Move.WasteToFoundation(card)
            board.columns.forEachIndexed { index, target ->
                if (canPutOnTableau(card, target)) moves += Move.WasteToTableau(index, card)
            }
        }
        // Foundation rollback is legal, but almost always undesirable. The policy ranks it last.
        board.foundations.forEach { (suit, rank) ->
            if (rank == 0) return@forEach
            val card = Card(rank, suit)
            board.columns.forEachIndexed { to, target ->
                if (canPutOnTableau(card, target)) moves += Move.FoundationToTableau(suit, to, card)
            }
        }
        when (board.stockStatus) {
            StockStatus.AVAILABLE -> moves += if (board.drawCount == 1) Move.DrawOne else Move.DrawThree
            // An observed recycle icon can coexist with an OCCUPIED waste fan
            // whose glyph is unreadable. Physical occupancy is checked at dispatch.
            StockStatus.EMPTY -> if ((board.waste != null || board.recycleAvailable) &&
                ((board.redealsRemaining != null && board.redealsRemaining > 0) ||
                    (board.redealsRemaining == null && board.recycleAvailable))) {
                moves += Move.RecycleStock
            }
            StockStatus.UNKNOWN -> Unit // Do not guess whether stock is available.
        }
        return moves.distinctBy { it.signature }
    }
}
