package com.retq.solitairebot.core

/**
 * Time-capped beam search over positively observed cards only.
 *
 * Every candidate is evaluated by the existing solver first. This planner adds
 * a bounded look-ahead bonus for lines that improve the position, while keeping
 * observation barriers (draw/recycle/flip) out of simulated future states.
 */
object TacticalBeamPlanner {
    private data class Node(
        val board: BoardState,
        val first: String,
        val cumulative: Int,
        val depth: Int
    )

    fun bonuses(
        board: BoardState,
        proposals: List<Suggestion>,
        remaining: Int? = null,
        maxNodes: Int = 220,
        beamWidth: Int = 10,
        depth: Int = 4,
        millis: Long = 15L
    ): Map<String, Int> {
        if (board.problems().isNotEmpty() || proposals.size <= 1 || remaining in 0..25) {
            return emptyMap()
        }

        val started = System.nanoTime()
        val limitNanos = millis.coerceIn(1L, 30L) * 1_000_000L
        val nodeLimit = maxNodes.coerceIn(1, 1000)
        var visited = 0
        val best = HashMap<String, Int>()
        // Keep transpositions separate for each root. A state reached from one
        // first move must not suppress the same state reached from another root.
        val seen = HashMap<String, Int>()

        val roots = proposals.take(8).filterNot { requiresNewObservation(it.move) }
        var frontier = roots.mapNotNull { option ->
            val next = MoveSimulator.applyKnownLegal(board, option.move)
            if (next.problems().isNotEmpty()) null
            else Node(next, option.move.signature, 0, 1)
        }

        val levels = depth.coerceIn(1, 5) - 1
        for (level in 0 until levels) {
            if (frontier.isEmpty() || visited >= nodeLimit || timedOut(started, limitNanos)) break
            val expanded = ArrayList<Node>()

            for (node in frontier) {
                if (visited >= nodeLimit || timedOut(started, limitNanos)) break

                val actions = MoveGenerator.generate(node.board)
                    .filterNot(::requiresNewObservation)
                    .sortedByDescending { stepReward(node.board, it) }
                    .take(8)

                for (move in actions) {
                    if (visited >= nodeLimit || timedOut(started, limitNanos)) break
                    visited++

                    val next = MoveSimulator.applyKnownLegal(node.board, move)
                    if (next.problems().isNotEmpty()) continue

                    val tactical = stepReward(node.board, move)
                    val positional = ((positionValue(next) - positionValue(node.board)) / 4)
                        .coerceIn(-90, 90)
                    val discount = when (node.depth) {
                        1 -> 78
                        2 -> 58
                        else -> 42
                    }
                    val newValue = node.cumulative + (tactical + positional) * discount / 100

                    val signature = node.first + "|" + stateSignature(next)
                    val previous = seen[signature]
                    if (previous != null && previous >= newValue) continue
                    seen[signature] = newValue

                    best[node.first] = maxOf(best[node.first] ?: 0, newValue)
                    expanded.add(Node(next, node.first, newValue, node.depth + 1))
                }
            }

            frontier = expanded
                .sortedByDescending { it.cumulative }
                .take(beamWidth.coerceIn(1, 20))
        }

        // This is only a ranking bonus; physical verification and the original
        // safety filters still decide whether a move may actually be dispatched.
        return best.mapValues { (_, value) -> value.coerceIn(0, 220) * 32 / 100 }
    }

    private fun timedOut(started: Long, limitNanos: Long): Boolean =
        System.nanoTime() - started >= limitNanos

    private fun requiresNewObservation(move: Move): Boolean =
        move is Move.FlipTableau ||
            move == Move.DrawOne ||
            move == Move.DrawThree ||
            move == Move.RecycleStock

    private fun stepReward(board: BoardState, move: Move): Int = when (move) {
        is Move.TableauToFoundation -> 110
        is Move.WasteToFoundation -> 110
        is Move.TableauToTableau -> {
            val source = board.columns[move.from]
            when {
                source.faceUp.size == move.cards.size && (source.hiddenCount ?: 0) > 0 -> 190
                source.faceUp.size == move.cards.size && source.hiddenCount == 0 -> 5
                else -> -60
            }
        }
        is Move.WasteToTableau -> 30
        is Move.FoundationToTableau -> -185
        else -> 0
    }

    /** Cheap positional estimate: foundation progress, revealed backs and mobility. */
    private fun positionValue(board: BoardState): Int {
        val foundationProgress = board.foundations.values.sum() * 24
        val revealedBacks = board.columns.count { it.exposedBack } * 90
        val emptyColumns = board.columns.count { it.definitelyEmpty }
        val movableKings = board.columns.count { column ->
            column.faceUp.firstOrNull()?.rank == 13 && column.validRun(0)
        }
        val usefulSpaces = minOf(emptyColumns, movableKings) * 24
        val mobility = MoveGenerator.generate(board)
            .count { !requiresNewObservation(it) }
            .coerceAtMost(18) * 5
        return foundationProgress + revealedBacks + usefulSpaces + mobility
    }

    private fun stateSignature(board: BoardState): String = buildString {
        Suit.entries.forEach { append(board.foundations.getValue(it)).append(',') }
        board.columns.forEach { column ->
            append('|').append(column.hiddenCount).append(':').append(column.exposedBack)
            column.faceUp.forEach { append(it.code) }
        }
        append('|').append(board.waste)
            .append('|').append(board.stockStatus)
            .append('|').append(board.redealsRemaining)
            .append('|').append(board.drawCount)
            .append('|').append(board.recycleAvailable)
    }
}
