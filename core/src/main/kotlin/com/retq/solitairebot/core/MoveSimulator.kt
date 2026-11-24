package com.retq.solitairebot.core

/** Models card movement for tests/offline practice. Physical actions still need vision confirmation. */
object MoveSimulator {
    fun apply(board: BoardState, move: Move): BoardState {
        require(MoveGenerator.generate(board).any { it.signature == move.signature }) {
            "Illegal move: ${move.signature}"
        }
        return applyKnownLegal(board, move)
    }

    /** Used only for a move JUST generated as legal from this exact immutable board. */
    internal fun applyKnownLegal(board: BoardState, move: Move): BoardState {
        val columns = board.columns.toMutableList()
        // Only known positive hidden counts authorize a simulated exposed back.
        // The card identity remains unknown until a subsequent visual observation.
        fun afterRemoving(column: TableauColumn, remaining: List<Card>): TableauColumn =
            column.copy(faceUp = remaining, exposedBack = remaining.isEmpty() && (column.hiddenCount ?: 0) > 0)
        val foundations = board.foundations.toMutableMap()
        var waste = board.waste
        when (move) {
            is Move.TableauToFoundation -> {
                val col = columns[move.column]
                columns[move.column] = afterRemoving(col, col.faceUp.dropLast(1))
                foundations[move.card.suit] = move.card.rank
            }
            is Move.WasteToFoundation -> {
                foundations[move.card.suit] = move.card.rank
                waste = null // lower waste card not known from current observation
            }
            is Move.TableauToTableau -> {
                val source = columns[move.from]
                columns[move.from] = afterRemoving(source, source.faceUp.take(move.startIndex))
                val target = columns[move.to]
                columns[move.to] = target.copy(faceUp = target.faceUp + move.cards)
            }
            is Move.WasteToTableau -> {
                val target = columns[move.to]
                columns[move.to] = target.copy(faceUp = target.faceUp + move.card)
                waste = null
            }
            is Move.FoundationToTableau -> {
                foundations[move.fromSuit] = move.card.rank - 1
                val target = columns[move.to]
                columns[move.to] = target.copy(faceUp = target.faceUp + move.card)
            }
            is Move.FlipTableau -> {
                // The identity is unknowable until the next real observation. No invented face-up card.
                val source = columns[move.column]
                columns[move.column] = source.copy(hiddenCount = null, exposedBack = false)
            }
            Move.DrawOne, Move.DrawThree -> return board.copy(waste = null, stockStatus = StockStatus.UNKNOWN,
                recycleAvailable = false)
            Move.RecycleStock -> return board.copy(stockStatus = StockStatus.AVAILABLE,
                redealsRemaining = board.redealsRemaining?.minus(1), waste = null, recycleAvailable = false)
        }
        return board.copy(columns = columns, foundations = foundations, waste = waste)
    }
}
