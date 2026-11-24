package com.retq.solitairebot.core

/** Editor grammar: 3;8H 7S 6D means 3 face-down cards and face-up 8H,7S,6D. */
object BoardText {
    fun parseColumn(text: String): TableauColumn {
        val parts = text.trim().split(";", limit = 2)
        require(parts.size == 2) { "Expected hidden count;cards (e.g., 3;8H 7S). Use ? if unknown." }
        val count = if (parts[0].trim() == "?") null else parts[0].trim().toIntOrNull()
            ?: throw IllegalArgumentException("Invalid hidden card count: ${parts[0]}")
        val tokens = parts[1].trim().split(Regex("[\\s,]+")) .filter { it.isNotEmpty() }
        if (tokens.size == 1 && tokens.single().equals("BACK", ignoreCase = true))
            return TableauColumn(count, emptyList(), exposedBack = true)
        val cards = tokens.map { token ->
            Card.parse(token) ?: throw IllegalArgumentException("Invalid card: $token")
        }
        return TableauColumn(count, cards)
    }
    fun formatColumn(column: TableauColumn) = "${column.hiddenCount?.toString() ?: "?"};${if (column.exposedBack) "BACK" else column.faceUp.joinToString(" ") { it.code }}"

    /** Foundation grammar: C=0,D=2,H=1,S=0 (one top rank per suit). */
    fun parseFoundations(text: String): Map<Suit, Int> {
        val result = Suit.entries.associateWith { 0 }.toMutableMap()
        val seen = mutableSetOf<Suit>()
        text.uppercase().replace(" ", "").split(",").filter { it.isNotBlank() }.forEach { item ->
            val pair = item.split("=", limit = 2)
            require(pair.size == 2) { "Expected C=0,D=2,H=1,S=0" }
            val suit = pair[0].singleOrNull()?.let { Suit.fromChar(it) }
                ?: throw IllegalArgumentException("Unknown foundation: ${pair[0]}")
            require(seen.add(suit)) { "Foundation entered more than once: ${suit.symbol}" }
            val value = if (pair[1] in Card.RANKS) Card.RANKS.indexOf(pair[1]) + 1 else pair[1].toIntOrNull()
            require(value != null && value in 0..13) { "Foundation rank must be 0..13" }
            result[suit] = value
        }
        return result
    }
    fun formatFoundations(foundations: Map<Suit, Int>) = Suit.entries.joinToString(",") {
        "${it.symbol}=${foundations.getValue(it)}"
    }
}
