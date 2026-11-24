package com.retq.solitairebot.core

/**
 * Exact offline draw-one/draw-three model, usable ONLY when the full stock order is known.
 * stockTopFirst[0] is the next card drawn; wasteBottomToTop.last is playable.
 * It is intentionally NOT filled from guesses when reading screen pixels.
 */
data class StockTracker(
    val stockTopFirst: List<Card>,
    val wasteBottomToTop: List<Card>,
    val remainingRedeals: Int?, // null means unlimited, but only for explicitly configured games
    val drawCount: Int = 3
) {
    init {
        require(remainingRedeals == null || remainingRedeals >= 0)
        require(drawCount == 1 || drawCount == 3)
        require((stockTopFirst + wasteBottomToTop).distinct().size == stockTopFirst.size + wasteBottomToTop.size)
    }
    val visibleWaste: Card? get() = wasteBottomToTop.lastOrNull()
    val canDraw: Boolean get() = stockTopFirst.isNotEmpty()
    val canRecycle: Boolean get() = stockTopFirst.isEmpty() && wasteBottomToTop.isNotEmpty() &&
        (remainingRedeals == null || remainingRedeals > 0)

    fun draw(): StockTracker {
        require(canDraw) { "Stock is empty" }
        val drawn = stockTopFirst.take(drawCount)
        return copy(stockTopFirst = stockTopFirst.drop(drawCount), wasteBottomToTop = wasteBottomToTop + drawn)
    }

    fun drawThree(): StockTracker {
        require(drawCount == 3) { "This stock is configured for draw-one" }
        return draw()
    }

    fun drawOne(): StockTracker {
        require(drawCount == 1) { "This stock is configured for draw-three" }
        return draw()
    }

    fun playWasteTop(): Pair<Card, StockTracker> {
        require(wasteBottomToTop.isNotEmpty()) { "No waste card" }
        return wasteBottomToTop.last() to copy(wasteBottomToTop = wasteBottomToTop.dropLast(1))
    }

    fun recycle(): StockTracker {
        require(canRecycle) { "No available redeals or remaining waste" }
        // Flip the complete waste back face down without shuffling: bottom becomes stock top.
        return copy(stockTopFirst = wasteBottomToTop, wasteBottomToTop = emptyList(),
            remainingRedeals = remainingRedeals?.minus(1))
    }
}
