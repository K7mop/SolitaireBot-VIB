package com.retq.solitairebot.core

/**
 * Sparse, observation-only stock memory. This never infers the identities or order
 * of cards that were not actually observed. A draw-three observation describes
 * the exposed waste top, NOT the other two hidden cards in that draw.
 */
class StockPassMemory(private val recycleCost: Int = 15) {
    data class Sample(val ordinal: Int, val top: Card?, val pixels: String)
    data class Summary(
        val pass: Int, val confirmedDraws: Int, val confirmedRecycles: Int,
        val distinctKnownWaste: Int, val unknownDraws: Int, val costPaid: Int,
        val currentPassSamples: Int, val progressSinceRecycle: Boolean
    )

    private val allKnown = linkedSetOf<Card>()
    private val passSamples = mutableListOf<Sample>()
    private var lastPassFingerprint: String? = null
    private var currentPassProgress = false
    private var previousPassProgress = false
    private var repeatedUnproductivePass = false
    private var draws = 0
    private var unknownDraws = 0
    private var recycles = 0
    private var lastSignature = ""
    private var lastCard: Card? = null
    private var roundEpoch = 0

    @Synchronized fun reset() {
        allKnown.clear(); passSamples.clear(); lastPassFingerprint = null
        currentPassProgress = false; previousPassProgress = false
        repeatedUnproductivePass = false; draws = 0; unknownDraws = 0
        recycles = 0; lastSignature = ""; lastCard = null; roundEpoch++
    }

    /** Multiple frames showing the same top card are ONE sighting. */
    @Synchronized fun observe(board: BoardState, wastePixels: String = "") {
        board.waste?.let { allKnown.add(it) }
        if (board.stockStatus == StockStatus.UNKNOWN) return
        // An unverified screenshot must not advance the draw counter or stock order.
        lastCard = board.waste
        lastSignature = wastePixels
    }

    /** Called only by the visual-transition-confirmed event path. */
    @Synchronized fun verified(before: BoardState, move: Move, after: BoardState,
        wastePixels: String = "", observedCoveredBacks: Set<Int> = emptySet()) {
        if (before.problems().isNotEmpty() || after.problems().isNotEmpty()) return
        when (move) {
            Move.DrawOne, Move.DrawThree -> {
                draws++
                val top = after.waste
                if (top == null) unknownDraws++ else allKnown.add(top)
                // Do not fill unknown draw-three intermediate cards by guessing.
                passSamples.add(Sample(draws, top, wastePixels.take(36)))
            }
            Move.RecycleStock -> {
                val fingerprint = passSamples.joinToString(";") { it.top?.code ?: "?${it.pixels}" }
                repeatedUnproductivePass = lastPassFingerprint != null &&
                    lastPassFingerprint == fingerprint && !currentPassProgress && !previousPassProgress
                previousPassProgress = currentPassProgress
                currentPassProgress = false
                lastPassFingerprint = fingerprint
                passSamples.clear()
                recycles++
            }
            is Move.TableauToFoundation, is Move.WasteToFoundation, is Move.FlipTableau -> noteProgress()
            is Move.TableauToTableau -> {
                val from = before.columns[move.from]
                val to = after.columns[move.from]
                if ((!from.exposedBack && to.exposedBack) ||
                    (from.hiddenCount != null && to.hiddenCount != null && to.hiddenCount < from.hiddenCount) ||
                    (from.hiddenCount == null && move.from in observedCoveredBacks && move.startIndex == 0 &&
                        to.top != null && to.top !in from.faceUp))
                    noteProgress()
            }
            is Move.WasteToTableau -> noteProgress()
            else -> Unit
        }
        observe(after, wastePixels)
    }

    /** A proven new opportunity invalidates the old "nothing useful in this pass" verdict. */
    private fun noteProgress() {
        currentPassProgress = true
        repeatedUnproductivePass = false
    }

    /** A repeat cycle with no verified progress does not justify another 15-point fee. */
    @Synchronized fun canRecycle(): Boolean {
        if (repeatedUnproductivePass) return false
        if (lastPassFingerprint == null || passSamples.isEmpty() || currentPassProgress || previousPassProgress)
            return true
        val current = passSamples.joinToString(";") { it.top?.code ?: "?${it.pixels}" }
        // A second identical pass without any verified progress is a known
        // negative-value loop; do not spend another 15 points on it.
        return current != lastPassFingerprint
    }

    @Synchronized fun costOf(move: Move): Int = if (move == Move.RecycleStock) recycleCost else 0

    @Synchronized fun summary(): Summary = Summary(recycles, draws, recycles, allKnown.size,
        unknownDraws, recycles * recycleCost, passSamples.size,
        currentPassProgress || previousPassProgress)

    @Synchronized fun knownCards(): Set<Card> = allKnown.toSet()
}
