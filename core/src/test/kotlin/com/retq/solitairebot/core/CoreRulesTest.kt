package com.retq.solitairebot.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class CoreRulesTest {
    private fun b(vararg columns: String, foundations: String = "C=0,D=0,H=0,S=0", waste: String = "",
                  stock: StockStatus = StockStatus.UNKNOWN): BoardState = BoardState(
        columns.map(BoardText::parseColumn), BoardText.parseFoundations(foundations), Card.parse(waste), stock)

    @Test fun parseCardsAndRejectBadInput() {
        assertEquals("TH", Card.parse("10h")?.code)
        assertEquals("AS", Card.parse("A♠")?.code)
        assertEquals(null, Card.parse("ZZ"))
        assertFailsWith<IllegalArgumentException> { BoardText.parseColumn("x;AS") }
    }

    @Test fun findAcesAndDrawThree() {
        val deal = b("1;AS", "2;AH", "3;KS", "4;2S", "5;QH", "6;JH", "?;QS", stock = StockStatus.AVAILABLE)
        val results = MoveGenerator.generate(deal)
        assertEquals(2, results.filterIsInstance<Move.TableauToFoundation>().size)
        assertTrue(results.contains(Move.DrawThree))
    }

    @Test fun moveAlternatingDescendingRunAndSimulate() {
        val deal = b("2;8H 7S", "0;9C", "0;", "0;", "0;", "0;", "0;")
        val transfer = MoveGenerator.generate(deal).filterIsInstance<Move.TableauToTableau>()
            .first { it.from == 0 && it.to == 1 && it.startIndex == 0 }
        val after = MoveSimulator.apply(deal, transfer)
        assertEquals(listOf("9C", "8H", "7S"), after.columns[1].faceUp.map { it.code })
        assertEquals(2, after.columns[0].hiddenCount)
        assertTrue(after.columns[0].faceUp.isEmpty())
    }

    @Test fun neverTreatUnobservedAsEmpty() {
        val deal = b("0;KS", "?;", "0;", "0;", "0;", "0;", "0;")
        assertTrue(MoveGenerator.generate(deal).none { it is Move.TableauToTableau && it.to == 1 })
        assertTrue(MoveGenerator.generate(deal).any { it is Move.TableauToTableau && it.to == 2 })
    }

    @Test fun wasteAndFoundationRules() {
        val deal = b("0;", "0;", "0;", "0;", "0;", "0;", "0;",
            foundations = "C=1,D=0,H=0,S=0", waste = "2C")
        val step = MoveGenerator.generate(deal).filterIsInstance<Move.WasteToFoundation>().single()
        val after = MoveSimulator.apply(deal, step)
        assertEquals(2, after.foundations[Suit.CLUBS])
        assertEquals(null, after.waste)
    }

    @Test fun duplicateVisibleCardsRefuseMoves() {
        val deal = b("0;AS", "0;AS", "0;", "0;", "0;", "0;", "0;")
        assertTrue(deal.problems().isNotEmpty())
        assertTrue(MoveGenerator.generate(deal).isEmpty())
    }

    @Test fun foundationsCannotContainVisibleDuplicates() {
        val deal = b("0;AC", "0;", "0;", "0;", "0;", "0;", "0;", foundations = "C=5")
        assertTrue(deal.problems().isNotEmpty())
    }

    @Test fun drawThreeStockAndRecyclePreserveOrder() {
        val cards = listOf("AC", "2C", "3C", "4C", "5C").map { Card.parse(it)!! }
        val stock = StockTracker(cards, emptyList(), 1)
        val afterFirst = stock.drawThree()
        assertEquals("3C", afterFirst.visibleWaste?.code)
        val (played, afterPlay) = afterFirst.playWasteTop()
        assertEquals("3C", played.code)
        val afterSecond = afterPlay.drawThree()
        assertEquals("5C", afterSecond.visibleWaste?.code)
        assertTrue(afterSecond.canRecycle)
        val recycled = afterSecond.recycle()
        assertEquals(listOf("AC", "2C", "4C", "5C"), recycled.stockTopFirst.map { it.code })
        assertEquals(0, recycled.remainingRedeals)
        assertFalse(recycled.canRecycle)
    }

    @Test fun unknownRedealsNeverAutoRecycle() {
        val deal = b("0;", "0;", "0;", "0;", "0;", "0;", "0;", stock = StockStatus.EMPTY, waste = "5S")
        assertTrue(MoveGenerator.generate(deal).none { it == Move.RecycleStock })
        assertTrue(MoveGenerator.generate(deal.copy(redealsRemaining = 1)).contains(Move.RecycleStock))
    }

    @Test fun scorePrefersUncoverAndAvoidsLoop() {
        val deal = b("2;AS", "0;", "0;", "0;", "0;", "0;", "0;", stock = StockStatus.AVAILABLE)
        val next = assertNotNull(SolitaireSolver.suggest(deal))
        assertTrue(next.move is Move.TableauToFoundation)
        val repeat = SolitaireSolver.candidates(deal, listOf(next.move.signature))
        // Ranking history is a penalty, not proof that a valuable reveal is illegal.
        // The controller's state/edge guard, rather than arbitrary rank order,
        // guarantees that a previously attempted action is not clicked again.
        assertTrue(repeat.first { it.move == next.move }.score < next.score)
        val guard = AutoPlayGuard()
        guard.start()
        assertEquals(null, guard.authorize(deal, next.move))
        guard.markConfirmed(MoveSimulator.apply(deal, next.move))
        assertTrue(guard.blocked(deal, next.move))
    }

    @Test fun clockIsMonotonicAndStopsAtFiveMinutes() {
        val timer = SessionTimer(1000, 300)
        assertEquals(300, timer.remainingSeconds(1000))
        assertEquals(299, timer.remainingSeconds(2000))
        assertEquals(0, timer.remainingSeconds(301_000))
        assertTrue(timer.expired(301_000))
    }
}
