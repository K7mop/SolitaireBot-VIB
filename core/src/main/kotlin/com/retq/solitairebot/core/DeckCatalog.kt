package com.retq.solitairebot.core

/**
 * Canonical Klondike deck and rule reference. This catalog describes card identity
 * and legal destinations; it does not invent identities for face-down cards.
 */
object DeckCatalog {
    val allCards: List<Card> = Suit.entries.flatMap { suit ->
        (1..13).map { rank -> Card(rank, suit) }
    }

    init { check(allCards.size == 52 && allCards.toSet().size == 52) }

    fun card(code: String): Card? = Card.parse(code)

    fun canPlaceOnTableau(moving: Card, destinationTop: Card?): Boolean =
        if (destinationTop == null) moving.rank == 13
        else moving.red != destinationTop.red && moving.rank == destinationTop.rank - 1

    fun canPlaceOnFoundation(moving: Card, foundationRank: Int): Boolean =
        foundationRank in 0..13 && moving.rank == foundationRank + 1

    fun canMoveRun(column: TableauColumn, startIndex: Int): Boolean =
        column.validRun(startIndex)

    fun nextFoundationCard(suit: Suit, foundationRank: Int): Card? =
        if (foundationRank in 0..12) Card(foundationRank + 1, suit) else null

    fun remainingAfter(seen: Set<Card>): Set<Card> = allCards.toSet() - seen
}
