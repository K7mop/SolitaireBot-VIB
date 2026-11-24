package com.retq.solitairebot.core

/** Positive visual facts. An unreadable region is never the same thing as an empty one. */
data class ObservationEvidence(
    val observedColumns: Set<Int> = emptySet(),
    val emptyColumns: Set<Int> = emptySet(),
    val completeColumns: Set<Int> = emptySet(),
    val observedFoundations: Set<Suit> = emptySet(),
    val emptyFoundations: Set<Suit> = emptySet(),
    val wasteObserved: Boolean = false,
    val wasteEmpty: Boolean = false,
    val observedStock: Boolean = false,
    val stockSignature: String = "",
    val regionConfidences: Map<String, Float> = emptyMap(),
    val unknownRegions: Set<String> = emptySet(),
    val exposedTableauBacks: Set<Int> = emptySet(),
    val recycleAvailable: Boolean = false,
    /** A light-faced waste card is visible even when its rank/suit OCR is inconclusive. */
    val wasteOccupiedUnidentified: Boolean = false,
    /** Captured waste-fan pixels, bound to this frame; NEVER a guessed card identity. */
    val wasteSignature: String = ""
) {
    fun confidence(key: String): Float = regionConfidences[key] ?: 0f
    fun hasColumn(index: Int): Boolean = index in observedColumns || index in emptyColumns || index in exposedTableauBacks
    fun hasFoundation(suit: Suit): Boolean = suit in observedFoundations || suit in emptyFoundations
}
