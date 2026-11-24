package com.retq.solitairebot.core

/**
 * Conservative deterministic finish planner. Runs ONLY when every remaining
 * card is individually identified and there is provably no stock or waste.
 * Returns null rather than assuming any unknown card or an inaccessible deal.
 */
object EndgamePlanner {
    data class Solution(val moves: List<Move>, val explored: Int)
    private data class Node(val board: BoardState, val path: List<Move>, val rating: Int)

    fun allRemainingCardsKnown(board: BoardState): Boolean =
        board.problems().isEmpty() && board.stockStatus == StockStatus.EMPTY &&
            board.waste == null && board.columns.all { it.hiddenCount == 0 && !it.exposedBack } &&
            board.foundations.values.sum() + board.columns.sumOf { it.faceUp.size } == 52

    fun plan(board: BoardState, maxNodes: Int = 1500, maxMilliseconds: Long = 24L): Solution? {
        if (!allRemainingCardsKnown(board)) return null
        if (board.foundations.values.sum() == 52) return Solution(emptyList(), 0)
        val started = System.nanoTime()
        val queue = java.util.PriorityQueue<Node>(compareByDescending<Node> { it.rating }
            .thenBy { it.path.size })
        queue.add(Node(board, emptyList(), 0))
        val seen = mutableSetOf<String>()
        var explored = 0
        val cap = maxNodes.coerceIn(1, 6_000)
        while (queue.isNotEmpty() && explored < cap &&
            System.nanoTime() - started < maxMilliseconds.coerceIn(1L, 100L) * 1_000_000L) {
            val node = queue.poll()
            val key = key(node.board)
            if (!seen.add(key)) continue
            explored++
            if (node.board.foundations.values.sum() == 52) return Solution(node.path, explored)
            // Most complete-information finishes do not require a risky foundation rollback.
            val actions = MoveGenerator.generate(node.board).filter {
                it is Move.TableauToFoundation || it is Move.TableauToTableau
            }
            for (move in actions) {
                val next = MoveSimulator.applyKnownLegal(node.board, move)
                if (next.problems().isNotEmpty() || key(next) in seen) continue
                val foundation = next.foundations.values.sum()
                val rating = foundation * 100 - (node.path.size + 1) * 2 +
                    next.columns.count { it.definitelyEmpty } * 2
                queue.add(Node(next, node.path + move, rating))
            }
        }
        return null
    }

    private fun key(board: BoardState): String = buildString {
        Suit.entries.forEach { append(it.symbol).append(board.foundations.getValue(it)) }
        board.columns.forEach { column -> append('|'); column.faceUp.forEach { append(it.code).append(',') } }
    }
}
