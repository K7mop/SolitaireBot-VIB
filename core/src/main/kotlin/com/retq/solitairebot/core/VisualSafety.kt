package com.retq.solitairebot.core

/** Transition checks over positively observed piles; an unknown source is not proof of removal. */
object VisualSafety {
    fun sameVisibleBoard(a: BoardState, b: BoardState): Boolean =
        a.problems().isEmpty() && b.problems().isEmpty() &&
            a.columns == b.columns && a.waste == b.waste && a.foundations == b.foundations &&
            a.stockStatus == b.stockStatus && a.drawCount == b.drawCount &&
            a.recycleAvailable == b.recycleAvailable &&
            (a.columns.any { it.top != null || it.exposedBack } || a.waste != null || a.foundations.values.any { it > 0 })

    /** Compatibility API: derives only evidence that the supplied board can positively express. */
    fun verifiesMove(before: BoardState, after: BoardState, move: Move, afterFoundationSeen: Set<Suit>): Boolean {
        fun evidence(board: BoardState, foundations: Set<Suit>) = ObservationEvidence(
            observedColumns = board.columns.indices.filter { board.columns[it].top != null }.toSet(),
            emptyColumns = board.columns.indices.filter { board.columns[it].definitelyEmpty }.toSet(),
            completeColumns = board.columns.indices.filter { board.columns[it].hiddenCount != null }.toSet(),
            observedFoundations = foundations.filter { board.foundations.getValue(it) > 0 }.toSet(),
            emptyFoundations = foundations.filter { board.foundations.getValue(it) == 0 }.toSet(),
            wasteObserved = board.waste != null, wasteEmpty = false,
            observedStock = board.stockStatus != StockStatus.UNKNOWN,
            exposedTableauBacks = board.columns.indices.filter { board.columns[it].exposedBack }.toSet(),
            recycleAvailable = board.recycleAvailable)
        return verifiesMove(before, after, move, evidence(after, afterFoundationSeen), evidence(before, afterFoundationSeen))
    }

    fun verifiesMove(
        before: BoardState, after: BoardState, move: Move,
        afterEvidence: ObservationEvidence, beforeEvidence: ObservationEvidence
    ): Boolean {
        if (before.problems().isNotEmpty() || after.problems().isNotEmpty()) return false
        if (before.drawCount != after.drawCount) return false
        if (MoveGenerator.generate(before).none { it == move }) return false
        val changedColumns = when (move) {
            is Move.TableauToFoundation -> setOf(move.column)
            is Move.TableauToTableau -> setOf(move.from, move.to)
            is Move.WasteToTableau -> setOf(move.to)
            is Move.FoundationToTableau -> setOf(move.to)
            is Move.FlipTableau -> setOf(move.column)
            else -> emptySet()
        }
        val changedFoundation = when (move) {
            is Move.TableauToFoundation -> move.card.suit
            is Move.WasteToFoundation -> move.card.suit
            is Move.FoundationToTableau -> move.fromSuit
            else -> null
        }
        for (i in before.columns.indices) if (i !in changedColumns) {
            // A known pile becoming unreadable or changing unexpectedly invalidates the transition.
            if (beforeEvidence.hasColumn(i) != afterEvidence.hasColumn(i)) return false
            if (beforeEvidence.hasColumn(i) && before.columns[i] != after.columns[i]) return false
        }
        for (suit in Suit.entries) if (suit != changedFoundation) {
            if (beforeEvidence.hasFoundation(suit) && !afterEvidence.hasFoundation(suit)) return false
            if (before.foundations.getValue(suit) != after.foundations.getValue(suit)) return false
        }
        val changesWaste = move is Move.WasteToFoundation || move is Move.WasteToTableau ||
            move == Move.DrawOne || move == Move.DrawThree || move == Move.RecycleStock
        if (!changesWaste) {
            if ((beforeEvidence.wasteObserved || beforeEvidence.wasteEmpty) &&
                !(afterEvidence.wasteObserved || afterEvidence.wasteEmpty)) return false
            if (before.waste != after.waste) return false
        }
        if (move != Move.DrawOne && move != Move.DrawThree && move != Move.RecycleStock &&
            beforeEvidence.observedStock && (!afterEvidence.observedStock || before.stockStatus != after.stockStatus)) return false

        fun sourceRemoved(index: Int, cards: List<Card>): Boolean {
            if (!beforeEvidence.hasColumn(index) || !afterEvidence.hasColumn(index)) return false
            val previous = before.columns[index]
            val current = after.columns[index]
            if (previous.faceUp.takeLast(cards.size) != cards || current.top in cards) return false
            val remains = previous.faceUp.dropLast(cards.size)
            return when {
                remains.isNotEmpty() -> current.faceUp.takeLast(remains.size) == remains &&
                    (index !in beforeEvidence.completeColumns || index !in afterEvidence.completeColumns ||
                        current.faceUp == remains) &&
                    (previous.hiddenCount == null || current.hiddenCount == null || previous.hiddenCount == current.hiddenCount)
                previous.hiddenCount == 0 && index in beforeEvidence.completeColumns ->
                    index in afterEvidence.emptyColumns && current.definitelyEmpty
                index in afterEvidence.exposedTableauBacks && current.exposedBack ->
                    previous.hiddenCount == null || current.hiddenCount == null || previous.hiddenCount == current.hiddenCount
                current.top != null -> previous.hiddenCount == null || current.hiddenCount == null ||
                    current.hiddenCount == previous.hiddenCount - 1
                else -> previous.hiddenCount == null && index in afterEvidence.emptyColumns && current.definitelyEmpty
            }
        }
        fun destinationReceived(index: Int, cards: List<Card>): Boolean {
            if (!beforeEvidence.hasColumn(index) || !afterEvidence.hasColumn(index)) return false
            val current = after.columns[index].faceUp
            if (current.takeLast(cards.size) != cards) return false
            val expected = before.columns[index].faceUp + cards
            // A reader may see an identified suffix, but must prove every card that was moved.
            return if (index in beforeEvidence.completeColumns && index in afterEvidence.completeColumns)
                current == expected else current.size >= cards.size
        }
        fun wasteRemoved(card: Card): Boolean = beforeEvidence.wasteObserved && before.waste == card &&
            (afterEvidence.wasteObserved || afterEvidence.wasteEmpty) && after.waste != card
        return when (move) {
            is Move.TableauToFoundation -> sourceRemoved(move.column, listOf(move.card)) &&
                move.card.suit in afterEvidence.observedFoundations &&
                after.foundations.getValue(move.card.suit) == move.card.rank
            is Move.WasteToFoundation -> wasteRemoved(move.card) &&
                move.card.suit in afterEvidence.observedFoundations &&
                after.foundations.getValue(move.card.suit) == move.card.rank
            is Move.TableauToTableau -> sourceRemoved(move.from, move.cards) && destinationReceived(move.to, move.cards)
            is Move.WasteToTableau -> wasteRemoved(move.card) && destinationReceived(move.to, listOf(move.card))
            is Move.FoundationToTableau -> beforeEvidence.hasFoundation(move.fromSuit) &&
                afterEvidence.hasFoundation(move.fromSuit) &&
                after.foundations.getValue(move.fromSuit) == move.card.rank - 1 &&
                destinationReceived(move.to, listOf(move.card))
            is Move.FlipTableau -> move.column in beforeEvidence.exposedTableauBacks &&
                before.columns[move.column].exposedBack && move.column in afterEvidence.observedColumns &&
                move.column !in afterEvidence.exposedTableauBacks && !after.columns[move.column].exposedBack &&
                after.columns[move.column].faceUp.size == 1 &&
                (before.columns[move.column].hiddenCount == null || after.columns[move.column].hiddenCount == null ||
                    after.columns[move.column].hiddenCount == before.columns[move.column].hiddenCount!! - 1)
            Move.DrawOne, Move.DrawThree -> {
                val beforeWasteKnown = beforeEvidence.wasteObserved || beforeEvidence.wasteEmpty ||
                    beforeEvidence.wasteOccupiedUnidentified
                val afterWasteKnown = afterEvidence.wasteObserved || afterEvidence.wasteOccupiedUnidentified
                val visualChange = beforeEvidence.wasteSignature.isNotEmpty() &&
                    afterEvidence.wasteSignature.isNotEmpty() &&
                    beforeEvidence.wasteSignature != afterEvidence.wasteSignature
                beforeEvidence.observedStock && before.stockStatus == StockStatus.AVAILABLE &&
                    afterEvidence.observedStock && beforeWasteKnown && afterWasteKnown &&
                    (visualChange || (afterEvidence.wasteObserved && after.waste != null &&
                        before.waste != after.waste))
            }
            Move.RecycleStock -> beforeEvidence.observedStock && before.stockStatus == StockStatus.EMPTY &&
                ((before.redealsRemaining ?: 0) > 0 ||
                    (before.redealsRemaining == null && before.recycleAvailable && beforeEvidence.recycleAvailable)) &&
                (beforeEvidence.wasteObserved && before.waste != null ||
                    beforeEvidence.wasteOccupiedUnidentified && beforeEvidence.wasteSignature.isNotBlank()) &&
                afterEvidence.observedStock && after.stockStatus == StockStatus.AVAILABLE &&
                afterEvidence.wasteEmpty && after.waste == null &&
                (beforeEvidence.wasteSignature.isEmpty() || afterEvidence.wasteSignature.isEmpty() ||
                    beforeEvidence.wasteSignature != afterEvidence.wasteSignature)
        }
    }
}
