package com.retq.solitairebot.core

enum class Suit(val symbol: Char, val red: Boolean) {
    CLUBS('C', false), DIAMONDS('D', true), HEARTS('H', true), SPADES('S', false);
    companion object {
        fun fromChar(c: Char): Suit? = when (c.uppercaseChar()) {
            'C', '♣' -> CLUBS
            'D', '♦' -> DIAMONDS
            'H', '♥' -> HEARTS
            'S', '♠' -> SPADES
            else -> null
        }
    }
}

data class Card(val rank: Int, val suit: Suit) {
    init { require(rank in 1..13) { "Card rank must be 1..13" } }
    val red: Boolean get() = suit.red
    val code: String get() = "${RANKS[rank - 1]}${suit.symbol}"
    override fun toString(): String = code

    companion object {
        val RANKS = listOf("A", "2", "3", "4", "5", "6", "7", "8", "9", "T", "J", "Q", "K")
        fun parse(text: String): Card? {
            val normalized = text.trim().uppercase().replace("10", "T")
            if (normalized.length != 2) return null
            val suit = Suit.fromChar(normalized[1]) ?: return null
            val rank = RANKS.indexOf(normalized.substring(0, 1)) + 1
            return if (rank > 0) Card(rank, suit) else null
        }
    }
}
