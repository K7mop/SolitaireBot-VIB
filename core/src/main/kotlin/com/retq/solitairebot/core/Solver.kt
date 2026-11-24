package com.retq.solitairebot.core

/** Ranking is deliberately limited to OBSERVED cards; an unseen card is never predicted. */
data class Suggestion(val move: Move, val score: Int, val reason: String)

object SolitaireSolver {
    /** Bounded depth-three search over known cards with a per-decision node budget. */
    fun candidates(
        board: BoardState, recent: List<String> = emptyList(), remainingSeconds: Int? = null,
        searchDepth: Int = 3, maxNodes: Int = 220,
        observedCoveredBacks: Set<Int> = emptySet()
    ): List<Suggestion> {
        if (board.problems().isNotEmpty()) return emptyList()
        val moves = MoveGenerator.generate(board)
        if (moves.isEmpty()) return emptyList()
        val left = remainingSeconds?.coerceIn(0, 300)
        val history = recent.takeLast(10).toSet()
        val adaptiveNodes = when {
            left != null && left <= 30 -> 75
            left != null && left <= 65 -> 125
            else -> 220
        }
        val budget = Budget(minOf(maxNodes.coerceIn(1, 2000), adaptiveNodes),
            System.nanoTime() + when {
                left != null && left <= 30 -> 8_000_000L
                left != null && left <= 65 -> 12_000_000L
                else -> 19_000_000L
            })
        val transpositions = HashMap<String, Int>()
        // Evaluate every root option, but prefer allocating search work to promising moves.
        val ordered = moves.sortedWith(compareByDescending<Move> { immediate(board, it, left, observedCoveredBacks) }
            .thenBy { it.signature })
        val rootKey = key(board)
        val scores = ordered.map { move ->
            val immediate = immediate(board, move, left, observedCoveredBacks)
            val future = if (searchDepth <= 1 || requiresObservation(move) || budget.exhausted()) 0 else {
                val next = MoveSimulator.applyKnownLegal(board, move)
                val gain = (positionalValue(next) - positionalValue(board)).coerceIn(-140, 140)
                val forward = lookAhead(next, searchDepth.coerceIn(1, 4) - 1,
                    budget, setOf(rootKey), left, transpositions)
                forward * 43 / 100 + gain
            }
            val recentPenalty = if (move.signature in history) 230 else 0
            Suggestion(move, immediate + future - recentPenalty, reason(move))
        }
        return scores.filter { suggestion ->
            when (suggestion.move) {
                is Move.TableauToTableau, is Move.FoundationToTableau -> suggestion.score > 0
                else -> true // Known-safe stock traversal remains possible.
            }
        }.sortedWith(compareByDescending<Suggestion> { it.score }.thenBy { it.move.signature })
    }

    fun suggest(board: BoardState, recent: List<String> = emptyList(), remainingSeconds: Int? = null): Suggestion? =
        candidates(board, recent, remainingSeconds).firstOrNull()

    private class Budget(var left: Int, private val deadlineNanos: Long) {
        fun exhausted(): Boolean = left <= 0 || System.nanoTime() >= deadlineNanos
    }

    private fun lookAhead(board: BoardState, depth: Int, budget: Budget,
        seen: Set<String>, remaining: Int?, memo: MutableMap<String, Int>): Int {
        if (depth <= 0 || budget.exhausted()) return 0
        val currentKey = key(board)
        if (currentKey in seen) return 0
        val memoKey = "$depth:$currentKey"
        memo[memoKey]?.let { return it }
        val path = seen + currentKey
        val moves = MoveGenerator.generate(board).filterNot(::requiresObservation)
            .sortedWith(compareByDescending<Move> { immediate(board, it, remaining) }
                .thenBy { it.signature }).take(9)
        var best = 0
        for (move in moves) {
            if (budget.exhausted()) break
            budget.left--
            val next = MoveSimulator.applyKnownLegal(board, move)
            if (key(next) in path || next.problems().isNotEmpty()) continue
            val reward = immediate(board, move, remaining) +
                (positionalValue(next) - positionalValue(board)).coerceIn(-140, 140) +
                if (depth > 1) lookAhead(next, depth - 1, budget, path, remaining, memo) * 42 / 100 else 0
            best = maxOf(best, reward)
        }
        // Cache only fully explored nodes; a timed-out partial score is a lower bound.
        if (!budget.exhausted()) memo[memoKey] = best
        return best
    }

    private fun requiresObservation(move: Move): Boolean =
        move == Move.DrawOne || move == Move.DrawThree || move == Move.RecycleStock || move is Move.FlipTableau

    private fun knownCoveredBack(board: BoardState, column: Int, observed: Set<Int>): Boolean =
        (board.columns[column].hiddenCount ?: 0) > 0 ||
            (board.columns[column].hiddenCount == null && column in observed)

    private fun reveals(board: BoardState, move: Move, observed: Set<Int>): Boolean = when (move) {
        is Move.TableauToFoundation -> board.columns[move.column].faceUp.size == 1 &&
            knownCoveredBack(board, move.column, observed)
        is Move.TableauToTableau -> move.startIndex == 0 &&
            knownCoveredBack(board, move.from, observed)
        else -> false
    }

    private fun safeFoundation(board: BoardState, card: Card): Boolean = card.rank <= 2 ||
        Suit.entries.filter { it.red != card.red }.all { board.foundations.getValue(it) >= card.rank - 1 }

    private fun immediate(board: BoardState, move: Move, remaining: Int?,
        observedCoveredBacks: Set<Int> = emptySet()): Int {
        val finalHalfMinute = remaining != null && remaining <= 30
        val nearEnd = remaining != null && remaining <= 65
        val base = when (move) {
            is Move.TableauToFoundation -> {
                val safe = safeFoundation(board, move.card)
                95 + (if (move.card.rank == 1) 95 else if (move.card.rank == 2) 65 else 0) -
                    (if (!safe && !nearEnd) 115 else 0)
            }
            is Move.WasteToFoundation -> 100 + (if (move.card.rank <= 2) 70 else 0) -
                (if (!safeFoundation(board, move.card) && !nearEnd) 105 else 0)
            is Move.TableauToTableau -> {
                val source = board.columns[move.from]
                val target = board.columns[move.to]
                when {
                    // Moving a complete king stack from one empty column to another
                    // makes no new card playable and wastes valuable seconds.
                    target.definitelyEmpty && source.hiddenCount == 0 && move.startIndex == 0 -> -350
                    // Physically uncovering a back is handled by the reveal bonus below.
                    source.faceUp.size == move.cards.size && knownCoveredBack(board, move.from, observedCoveredBacks) -> 35
                    // A genuinely new empty workspace can be tactically useful.
                    source.faceUp.size == move.cards.size && source.hiddenCount == 0 ->
                        if (target.definitelyEmpty) -230 else -55
                    // Revealing an already face-up card may enable a second move;
                    // let lookahead prove it rather than rewarding shuffling itself.
                    else -> -85 + if (target.definitelyEmpty) 35 else 0
                }
            }
            is Move.WasteToTableau -> 77 + if (move.card.rank == 13 && board.columns[move.to].definitelyEmpty) 35 else 0
            is Move.FoundationToTableau -> {
                // Do not suppress a concrete recovery route just because the
                // first step temporarily lowers a foundation. The returned card
                // must support an observed legal run that uncovers a KNOWN back.
                val opensBack = board.columns.withIndex().any { (index, column) ->
                    index != move.to && knownCoveredBack(board, index, observedCoveredBacks) && column.validRun(0) &&
                        column.faceUp.first().let { it.rank + 1 == move.card.rank && it.red != move.card.red }
                }
                -165 + if (opensBack) 110 else 0
            }
            is Move.FlipTableau -> 335 + (board.columns[move.column].hiddenCount ?: 0).coerceAtMost(8) * 10
            Move.DrawOne -> -22
            Move.DrawThree -> -26
            Move.RecycleStock -> -52
        }
        // This frame's positive back evidence affects only root evaluation.
        // Simulated future states keep unknown counts/identities unknown.
        val revealBonus = if (reveals(board, move, observedCoveredBacks)) 245 + when (move) {
            is Move.TableauToTableau -> (board.columns[move.from].hiddenCount ?: 0).coerceAtMost(8) * 12
            is Move.TableauToFoundation -> (board.columns[move.column].hiddenCount ?: 0).coerceAtMost(8) * 12
            else -> 0
        } else 0
        val lateBonus = if (nearEnd && (move is Move.TableauToFoundation || move is Move.WasteToFoundation))
            if (finalHalfMinute) 220 else 80 else 0
        // A slow sequence with no provable value should not outscore quick point-making late in a round.
        val gestureCost = when (move) {
            is Move.TableauToTableau -> 17 + if (move.cards.size > 1) 6 else 0
            is Move.WasteToTableau, is Move.FoundationToTableau -> 14
            else -> 7
        }
        return base + revealBonus + lateBonus - if (finalHalfMinute) gestureCost * 2 else gestureCost
    }

    private fun positionalValue(board: BoardState): Int {
        if (board.problems().isNotEmpty()) return -1000
        val foundations = board.foundations.values.sum() * 10
        val openBacks = board.columns.count { it.exposedBack } * 90
        val spaces = board.columns.count { it.definitelyEmpty }
        val availableKings = board.columns.count { col -> col.faceUp.firstOrNull()?.rank == 13 }
        val usefulSpace = minOf(spaces, availableKings) * 30
        // Small, constant-time mobility estimate: no extra recursive move generation.
        val mobility = board.columns.sumOf { source ->
            val card = source.top
            if (card == null) 0 else {
                val toTableau = board.columns.count { destination ->
                    destination !== source && (destination.top?.let {
                        it.rank == card.rank + 1 && it.red != card.red
                    } ?: (destination.definitelyEmpty && card.rank == 13))
                }
                toTableau + if (board.foundations.getValue(card.suit) + 1 == card.rank) 1 else 0
            }
        }.coerceAtMost(18) * 8
        return foundations + openBacks + usefulSpace + mobility
    }

    private fun key(board: BoardState): String = buildString {
        board.columns.forEach { col ->
            append('[').append(col.hiddenCount).append(':').append(col.exposedBack)
            col.faceUp.forEach { append(',').append(it.code) }
            append(']')
        }
        Suit.entries.forEach { append(it.symbol).append(board.foundations.getValue(it)) }
        append('|').append(board.waste?.code).append('|').append(board.stockStatus)
    }

    private fun reason(move: Move): String = when (move) {
        is Move.TableauToFoundation -> "رفع ${move.card.code} من العمود ${move.column + 1} إلى الأساس"
        is Move.WasteToFoundation -> "رفع ${move.card.code} من السحب إلى الأساس"
        is Move.TableauToTableau -> "نقل سلسلة ${move.cards.size} من العمود ${move.from + 1} إلى ${move.to + 1}"
        is Move.WasteToTableau -> "نقل ${move.card.code} من السحب إلى العمود ${move.to + 1}"
        is Move.FoundationToTableau -> "إعادة ${move.card.code} إلى عمود ${move.to + 1} لتحرير مسار"
        is Move.FlipTableau -> "كشف ظهر ورقة في العمود ${move.column + 1}"
        Move.DrawOne -> "قراءة ورقة جديدة من المخزون"
        Move.DrawThree -> "قراءة ثلاث أوراق جديدة من المخزون"
        Move.RecycleStock -> "إعادة المخزون المسموح ثم التحقق بصريًا"
    }
}
