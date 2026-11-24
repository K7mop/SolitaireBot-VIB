package com.retq.solitairebot.core

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith

class BoardValidationAndStrategyTest {
    @Test fun hiddenCountCannotExceedSingleDeck() { assertFailsWith<IllegalArgumentException> { TableauColumn(100,emptyList()) } }
    @Test fun combinedKnownCardTotalCannotExceedDeck() {
        val b=BoardState.empty().copy(columns=List(7) { TableauColumn(8,emptyList()) })
        assertTrue(b.problems().any { "52-card" in it })
        assertTrue(MoveGenerator.generate(b).isEmpty())
    }
    @Test fun duplicateFoundationEntryIsRejected() { assertFailsWith<IllegalArgumentException> { BoardText.parseFoundations("C=1,C=0") } }
    @Test fun pointlessFoundationRollbackIsNotSelected() {
        val b=BoardState.empty().copy(columns=listOf(TableauColumn(0,listOf(Card.parse("KH")!!)))+List(6){TableauColumn(null,emptyList())},
            foundations=Suit.entries.associateWith { if(it==Suit.SPADES)12 else 0 })
        assertTrue(MoveGenerator.generate(b).any { it is Move.FoundationToTableau })
        assertTrue(SolitaireSolver.candidates(b).none { it.move is Move.FoundationToTableau })
    }
    @Test fun usefulFoundationRollbackUnlocksARealHiddenPile() {
        // QS -> KH makes a landing place for JH, exposing the three backs below it.
        // Without that known tactical benefit the penalty above still applies.
        val b=BoardState.empty().copy(columns=listOf(
            TableauColumn(0,listOf(Card.parse("KH")!!)),
            TableauColumn(3,listOf(Card.parse("JH")!!)))+List(5){TableauColumn(null,emptyList())},
            foundations=Suit.entries.associateWith { if(it==Suit.SPADES)12 else 0 })
        val best=assertNotNull(SolitaireSolver.suggest(b))
        assertTrue(best.move == Move.FoundationToTableau(Suit.SPADES, 0, Card.parse("QS")!!))
        val next=MoveSimulator.apply(b,best.move)
        val reveal=Move.TableauToTableau(1,0,0,listOf(Card.parse("JH")!!))
        assertTrue(reveal in MoveGenerator.generate(next))
        assertTrue(MoveSimulator.apply(next,reveal).columns[1].exposedBack)
    }
    @Test fun boundedStrategyPrefersExposingKnownHiddenCard() {
        val b=BoardState.empty().copy(columns=listOf(TableauColumn(2,listOf(Card.parse("AS")!!)))+List(6){TableauColumn(null,emptyList())},stockStatus=StockStatus.AVAILABLE)
        assertTrue(SolitaireSolver.suggest(b)?.move is Move.TableauToFoundation)
    }
    @Test fun timeUrgencyIsUsedWithoutChangingMoveLegality() {
        val b=BoardState.empty().copy(columns=listOf(TableauColumn(0,listOf(Card.parse("5S")!!)))+List(6){TableauColumn(null,emptyList())},
            foundations=Suit.entries.associateWith{if(it==Suit.SPADES)4 else 0})
        val normal=SolitaireSolver.candidates(b,remainingSeconds=240).first {it.move is Move.TableauToFoundation}
        val urgent=SolitaireSolver.candidates(b,remainingSeconds=20).first {it.move is Move.TableauToFoundation}
        assertTrue(urgent.score>normal.score)
    }
}
