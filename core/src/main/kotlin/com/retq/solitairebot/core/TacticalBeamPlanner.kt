package com.retq.solitairebot.core

/**
 * Small, time-capped beam over positively observed cards only. It finds future
 * foundation/space opportunities missed by a greedy one-move score. Observation
 * barriers (draw, recycle, flip) are NEVER simulated as known future cards.
 */
object TacticalBeamPlanner {
    private data class Node(val board: BoardState, val first: String,
        val cumulative: Int, val depth: Int)

    fun bonuses(board: BoardState, proposals: List<Suggestion>, remaining: Int? = null,
        maxNodes: Int = 150, beamWidth: Int = 8, depth: Int = 4,
        millis: Long = 11L): Map<String, Int> {
        if (board.problems().isNotEmpty() || proposals.size <= 1 || remaining in 0..25) return emptyMap()
        val started = System.nanoTime()
        val limitNanos = millis.coerceIn(1L, 30L) * 1_000_000L
        var visited=0
        val best=HashMap<String, Int>()
        val seen=HashMap<String, Int>()
        val roots=proposals.take(8).filter { !requiresNewObservation(it.move) }
        var frontier=roots.mapNotNull { option ->
            val next=MoveSimulator.applyKnownLegal(board,option.move)
            if (next.problems().isNotEmpty()) null else Node(next,option.move.signature,0,1)
        }
        repeat(depth.coerceIn(1,5)-1) {
            if (frontier.isEmpty() || visited>=maxNodes || System.nanoTime()-started>=limitNanos) return@repeat
            val expanded=ArrayList<Node>()
            for (node in frontier) {
                if (visited>=maxNodes || System.nanoTime()-started>=limitNanos) break
                val actions=MoveGenerator.generate(node.board)
                    .filterNot(::requiresNewObservation)
                    .sortedByDescending { stepReward(node.board,it) }.take(7)
                for (move in actions) {
                    if (visited++>=maxNodes || System.nanoTime()-started>=limitNanos) break
                    val next=MoveSimulator.applyKnownLegal(node.board,move)
                    if (next.problems().isNotEmpty()) continue
                    val reward=stepReward(node.board,move)
                    val newValue=node.cumulative + reward * when(node.depth) {1 -> 75; 2 -> 55; else -> 40}/100
                    val signature=stateSignature(next)
                    val previous=seen[signature]
                    if (previous != null && previous>=newValue) continue
                    seen[signature]=newValue
                    best[node.first]=maxOf(best[node.first]?:0,newValue)
                    expanded.add(Node(next,node.first,newValue,node.depth+1))
                }
            }
            frontier=expanded.sortedByDescending { it.cumulative }.take(beamWidth.coerceIn(1,20))
        }
        // A bonus cannot compensate for a physically unverified or otherwise
        // unprofitable action: the original safety filters still apply.
        return best.mapValues { (_,value) -> value.coerceIn(0,180) * 35 / 100 }
    }

    private fun requiresNewObservation(move: Move): Boolean = move is Move.FlipTableau ||
        move == Move.DrawOne || move == Move.DrawThree || move == Move.RecycleStock

    private fun stepReward(board: BoardState, move: Move): Int = when(move) {
        is Move.TableauToFoundation -> 105
        is Move.WasteToFoundation -> 105
        is Move.TableauToTableau -> {
            val source=board.columns[move.from]
            if (source.faceUp.size==move.cards.size && (source.hiddenCount?:0)>0) 180
            else if (source.faceUp.size==move.cards.size && source.hiddenCount==0) 10
            else -55
        }
        is Move.WasteToTableau -> 32
        is Move.FoundationToTableau -> -170
        else -> 0
    }

    private fun stateSignature(board: BoardState): String = buildString {
        Suit.entries.forEach { append(board.foundations.getValue(it)).append(',') }
        board.columns.forEach { col ->
            append('|').append(col.hiddenCount).append(':').append(col.exposedBack)
            col.faceUp.forEach { append(it.code) }
        }
        append('|').append(board.waste).append('|').append(board.stockStatus)
    }
}
