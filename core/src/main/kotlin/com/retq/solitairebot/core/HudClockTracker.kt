package com.retq.solitairebot.core

/** Only stable, monotonically decreasing visual clock samples can steer strategy. */
class HudClockTracker {
    private var previous: Int? = null
    private var trusted: Int? = null
    private var missingTicks = 0
    private var previousScore: Int? = null
    private var trustedScore: Int? = null
    private var missingScoreTicks = 0
    fun reset() { previous=null; trusted=null; previousScore=null; trustedScore=null; missingTicks=0; missingScoreTicks=0 }
    fun update(seconds: Int?, score: Int?): Pair<Int?, Int?> {
        if (seconds != null && seconds in 0..300) {
            missingTicks=0
            val before=previous
            if (before != null && seconds in (before - 3).coerceAtLeast(0)..before) trusted=seconds
            else if (trusted != null && seconds > trusted!! + 2) trusted=null
            previous=seconds
        } else {
            previous=null
            missingTicks++
            if (missingTicks>=2) trusted=null
        }
        if (score != null && score in 0..150_000) {
            missingScoreTicks=0
            if (previousScore != null && kotlin.math.abs(previousScore!! - score) <= 300)
                trustedScore=score
            else if (previousScore != null) trustedScore=null
            previousScore=score
        } else {
            previousScore=null
            missingScoreTicks++
            if (missingScoreTicks>=2) trustedScore=null
        }
        return trusted to trustedScore
    }
}
