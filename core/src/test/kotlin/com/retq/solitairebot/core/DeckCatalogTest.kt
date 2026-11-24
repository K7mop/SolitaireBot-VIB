package com.retq.solitairebot.core

import org.junit.Assert.*
import org.junit.Test

class DeckCatalogTest {
    @Test fun canonicalDeckContainsAll52UniqueCards() {
        assertEquals(52, DeckCatalog.allCards.size)
        assertEquals(52, DeckCatalog.allCards.toSet().size)
        Suit.entries.forEach { suit ->
            assertEquals((1..13).toSet(), DeckCatalog.allCards.filter { it.suit == suit }.map { it.rank }.toSet())
        }
    }

    @Test fun parsesTenAndSuitGlyphs() {
        assertEquals(Card(10, Suit.HEARTS), DeckCatalog.card("10♥"))
        assertEquals(Card(1, Suit.SPADES), DeckCatalog.card("AS"))
    }

    @Test fun tableauPlacementUsesDescendingRankAndAlternatingColor() {
        assertTrue(DeckCatalog.canPlaceOnTableau(Card(6, Suit.HEARTS), Card(7, Suit.SPADES)))
        assertFalse(DeckCatalog.canPlaceOnTableau(Card(6, Suit.CLUBS), Card(7, Suit.SPADES)))
        assertFalse(DeckCatalog.canPlaceOnTableau(Card(8, Suit.HEARTS), Card(7, Suit.SPADES)))
        assertTrue(DeckCatalog.canPlaceOnTableau(Card(13, Suit.DIAMONDS), null))
        assertFalse(DeckCatalog.canPlaceOnTableau(Card(12, Suit.DIAMONDS), null))
    }

    @Test fun foundationPlacementRequiresNextRankOfSameSuitAtCaller() {
        assertTrue(DeckCatalog.canPlaceOnFoundation(Card(4, Suit.CLUBS), 3))
        assertFalse(DeckCatalog.canPlaceOnFoundation(Card(5, Suit.CLUBS), 3))
        assertFalse(DeckCatalog.canPlaceOnFoundation(Card(1, Suit.CLUBS), 13))
    }

    @Test fun unseenCardsAreExactlyDeckMinusObservedCards() {
        val seen = setOf(Card(1, Suit.SPADES), Card(13, Suit.HEARTS))
        val remaining = DeckCatalog.remainingAfter(seen)
        assertEquals(50, remaining.size)
        assertFalse(Card(1, Suit.SPADES) in remaining)
        assertTrue(Card(2, Suit.SPADES) in remaining)
    }
}
