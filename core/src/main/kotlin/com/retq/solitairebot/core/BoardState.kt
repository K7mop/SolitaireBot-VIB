package com.retq.solitairebot.core

/** Face-up cards ordered top-to-bottom; last card is the exposed movable card. */
data class TableauColumn(
    val hiddenCount: Int?, // null = not yet observed, never assume a column is empty
    val faceUp: List<Card>,
    /** True only when the top is positively seen as a face-down card, never an OCR failure. */
    val exposedBack: Boolean = false
) {
    init {
        require(hiddenCount == null || hiddenCount in 0..52) { "Hidden card count must be between 0 and 52" }
        require(!exposedBack || (faceUp.isEmpty() && hiddenCount != 0)) {
            "An exposed face-down card cannot also be empty or covered by face-up cards"
        }
    }
    val top: Card? get() = faceUp.lastOrNull()
    val definitelyEmpty: Boolean get() = !exposedBack && faceUp.isEmpty() && hiddenCount == 0
    fun validRun(start: Int): Boolean {
        if (start !in faceUp.indices) return false
        for (index in start until faceUp.lastIndex) {
            val upper = faceUp[index]
            val lower = faceUp[index + 1]
            if (upper.rank != lower.rank + 1 || upper.red == lower.red) return false
        }
        return true
    }
}

enum class StockStatus { AVAILABLE, EMPTY, UNKNOWN }

data class BoardState(
    val columns: List<TableauColumn>,
    val foundations: Map<Suit, Int>, // highest rank on each suit foundation, 0 if empty
    val waste: Card?, // top of the waste pile only
    val stockStatus: StockStatus,
    val redealsRemaining: Int? = null, // null = unknown/unlimited; no speculative recycle
    val drawCount: Int = 3,
    /** Positively observed recycle control. This does not imply unlimited redeals. */
    val recycleAvailable: Boolean = false
) {
    init {
        require(columns.size == 7) { "Exactly seven tableau columns required" }
        require(foundations.keys.containsAll(Suit.entries)) { "All four foundations required" }
        require(foundations.values.all { it in 0..13 })
        require(redealsRemaining == null || redealsRemaining >= 0)
        require(drawCount == 1 || drawCount == 3) { "Klondike draw count must be one or three" }
    }

    /** Incomplete observations must NEVER be silently interpreted as a full solvable deck. */
    fun problems(): List<String> {
        val errors = mutableListOf<String>()
        val observed = mutableListOf<Card>()
        columns.forEachIndexed { c, col ->
            if (!col.faceUp.indices.all { col.validRun(it) }) {
                errors += "Column ${c + 1} has a non-legal face-up run"
            }
            observed.addAll(col.faceUp)
        }
        waste?.let { observed += it }
        val dupes = observed.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (dupes.isNotEmpty()) errors += "Duplicate visible cards: ${dupes.joinToString()}"
        val knownCardCount = columns.sumOf { (it.hiddenCount ?: 0) + it.faceUp.size } +
            foundations.values.sum() + if (waste == null) 0 else 1
        if (knownCardCount > 52) errors += "Known card counts exceed a single 52-card deck: $knownCardCount"
        val alreadyInFoundations = observed.filter { it.rank <= foundations.getValue(it.suit) }
        if (alreadyInFoundations.isNotEmpty()) {
            errors += "Card already present in its foundation: ${alreadyInFoundations.joinToString()}"
        }
        return errors
    }

    val incomplete: Boolean get() = columns.any { it.hiddenCount == null }
    companion object {
        fun empty(): BoardState = BoardState(
            columns = List(7) { TableauColumn(null, emptyList()) },
            foundations = Suit.entries.associateWith { 0 },
            waste = null, stockStatus = StockStatus.UNKNOWN
        )
    }
}
