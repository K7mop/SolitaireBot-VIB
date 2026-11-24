package com.retq.solitairebot.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real rule/transition assertions. The screen reader must separately prove these observations. */
class AutomaticStockAndFlipTest {
    private fun c(code: String) = Card.parse(code)!!
    private fun board(first: TableauColumn = TableauColumn(0, listOf(c("8H"))),
                      stock: StockStatus = StockStatus.AVAILABLE, waste: Card? = null,
                      drawCount: Int = 3, redeals: Int? = null, recycle: Boolean = false) =
        BoardState(listOf(first) + List(6) { TableauColumn(0, emptyList()) },
            Suit.entries.associateWith { 0 }, waste, stock, redeals, drawCount, recycle)
    private fun seen(board: BoardState) = ObservationEvidence(
        observedColumns = board.columns.indices.filter { board.columns[it].top != null }.toSet(),
        emptyColumns = board.columns.indices.filter { board.columns[it].definitelyEmpty }.toSet(),
        completeColumns = board.columns.indices.filter { board.columns[it].hiddenCount != null }.toSet(),
        observedFoundations = Suit.entries.filter { board.foundations.getValue(it) > 0 }.toSet(),
        emptyFoundations = Suit.entries.filter { board.foundations.getValue(it) == 0 }.toSet(),
        wasteObserved = board.waste != null, wasteEmpty = board.waste == null,
        observedStock = board.stockStatus != StockStatus.UNKNOWN,
        exposedTableauBacks = board.columns.indices.filter { board.columns[it].exposedBack }.toSet(),
        recycleAvailable = board.recycleAvailable)

    @Test fun drawOneChangesOnlyOneStockCardAndPreservesRecyclingOrder() {
        val start = StockTracker(listOf(c("AC"), c("2C"), c("3C")), emptyList(), 1, 1)
        val first = start.draw()
        assertEquals(c("AC"), first.visibleWaste)
        assertEquals(listOf(c("2C"), c("3C")), first.stockTopFirst)
        val last = first.drawOne().playWasteTop().second.drawOne()
        assertEquals(listOf(c("AC"), c("3C")), last.recycle().stockTopFirst)
        assertEquals(0, last.recycle().remainingRedeals)
        assertFailsWith<IllegalArgumentException> { start.drawThree() }
    }

    @Test fun drawThreeLastPacketCanContainFewerThanThree() {
        val start = StockTracker(listOf(c("AC"), c("2C")), emptyList(), 0, 3)
        val after = start.draw()
        assertEquals(c("2C"), after.visibleWaste)
        assertFalse(after.canDraw)
        assertFalse(after.canRecycle)
        assertFailsWith<IllegalArgumentException> { after.draw() }
    }

    @Test fun legalDrawUsesConfiguredVariantAndRejectsUnsupportedCount() {
        assertTrue(Move.DrawOne in MoveGenerator.generate(board(drawCount = 1)))
        assertFalse(Move.DrawThree in MoveGenerator.generate(board(drawCount = 1)))
        assertTrue(Move.DrawThree in MoveGenerator.generate(board(drawCount = 3)))
        assertFalse(Move.DrawOne in MoveGenerator.generate(board(drawCount = 3)))
        assertFailsWith<IllegalArgumentException> { board(drawCount = 2) }
    }

    @Test fun drawOneRequiresFreshRecognizedWasteAndStockProof() {
        val before = board(drawCount = 1)
        val after = before.copy(waste = c("5S"))
        assertTrue(VisualSafety.verifiesMove(before, after, Move.DrawOne, seen(after), seen(before)))
        assertFalse(VisualSafety.verifiesMove(before, before, Move.DrawOne, seen(before), seen(before)))
        assertFalse(VisualSafety.verifiesMove(before, after, Move.DrawOne,
            seen(after).copy(wasteObserved = false), seen(before)))
        assertFalse(VisualSafety.verifiesMove(before, after, Move.DrawThree, seen(after), seen(before)))
    }

    @Test fun unreadableColumnDoesNotBecomeAFlipCandidate() {
        val unknown = board(TableauColumn(null, emptyList()))
        assertTrue(MoveGenerator.generate(unknown).none { it is Move.FlipTableau })
        val back = board(TableauColumn(null, emptyList(), exposedBack = true))
        assertTrue(Move.FlipTableau(0) in MoveGenerator.generate(back))
        assertFalse(back.columns[0].definitelyEmpty)
        assertFailsWith<IllegalArgumentException> { TableauColumn(0, emptyList(), true) }
        assertFailsWith<IllegalArgumentException> { TableauColumn(1, listOf(c("AS")), true) }
    }

    @Test fun flipRequiresBothPositiveBackAndNewlyRecognizedCard() {
        val before = board(TableauColumn(2, emptyList(), true))
        val after = board(TableauColumn(1, listOf(c("8H"))))
        assertTrue(VisualSafety.verifiesMove(before, after, Move.FlipTableau(0), seen(after), seen(before)))
        assertFalse(VisualSafety.verifiesMove(before, before, Move.FlipTableau(0), seen(before), seen(before)))
        assertFalse(VisualSafety.verifiesMove(before, after, Move.FlipTableau(0), seen(after),
            seen(before).copy(exposedTableauBacks = emptySet())))
        val wrongCount = board(TableauColumn(2, listOf(c("8H"))))
        assertFalse(VisualSafety.verifiesMove(before, wrongCount, Move.FlipTableau(0), seen(wrongCount), seen(before)))
    }

    @Test fun movingLastFaceUpCardMayRevealBackThenFlipIt() {
        val before = board(TableauColumn(1, listOf(c("AC"))))
        val back = before.copy(columns = listOf(TableauColumn(1, emptyList(), true)) + before.columns.drop(1),
            foundations = before.foundations + (Suit.CLUBS to 1))
        val move = Move.TableauToFoundation(0, c("AC"))
        assertTrue(VisualSafety.verifiesMove(before, back, move, seen(back), seen(before)))
        assertEquals(Move.FlipTableau(0), SolitaireSolver.suggest(back)?.move)
        val face = back.copy(columns = listOf(TableauColumn(0, listOf(c("8H")))) + back.columns.drop(1))
        assertTrue(VisualSafety.verifiesMove(back, face, Move.FlipTableau(0), seen(face), seen(back)))
    }

    @Test fun simulatedFlipNeverInventsHiddenCardOrAnEmptyColumn() {
        val before = board(TableauColumn(1, emptyList(), true))
        val after = MoveSimulator.apply(before, Move.FlipTableau(0))
        assertTrue(after.columns[0].faceUp.isEmpty())
        assertFalse(after.columns[0].definitelyEmpty)
        assertFalse(after.columns[0].exposedBack)
        assertNull(after.columns[0].hiddenCount)
    }

    @Test fun hiddenPileCannotDisappearDuringOneCardTransfer() {
        val before = board(TableauColumn(2, listOf(c("AC"))))
        val empty = before.copy(columns = listOf(TableauColumn(0, emptyList())) + before.columns.drop(1),
            foundations = before.foundations + (Suit.CLUBS to 1))
        val move = Move.TableauToFoundation(0, c("AC"))
        assertFalse(VisualSafety.verifiesMove(before, empty, move, seen(empty), seen(before)))
        val extraBacks = empty.copy(columns = listOf(TableauColumn(3, emptyList(), true)) + empty.columns.drop(1))
        assertFalse(VisualSafety.verifiesMove(before, extraBacks, move, seen(extraBacks), seen(before)))
        val validBack = empty.copy(columns = listOf(TableauColumn(2, emptyList(), true)) + empty.columns.drop(1))
        assertTrue(VisualSafety.verifiesMove(before, validBack, move, seen(validBack), seen(before)))
    }

    @Test fun unknownRedealsNeedPositiveRecycleControlAndVisibleWaste() {
        val before = board(stock = StockStatus.EMPTY, waste = c("5S"), recycle = false)
        assertFalse(Move.RecycleStock in MoveGenerator.generate(before))
        assertTrue(Move.RecycleStock in MoveGenerator.generate(before.copy(recycleAvailable = true)))
        assertFalse(Move.RecycleStock in MoveGenerator.generate(before.copy(recycleAvailable = true, redealsRemaining = 0)))
        // An observed reset control may coexist with an occupied but unreadable
        // waste top. Generating a candidate must not declare it executable or
        // successful without the independent positive occupancy evidence.
        val unreadable = before.copy(recycleAvailable = true, waste = null)
        assertTrue(Move.RecycleStock in MoveGenerator.generate(unreadable))
        val reset = unreadable.copy(stockStatus = StockStatus.AVAILABLE, recycleAvailable = false)
        assertFalse(VisualSafety.verifiesMove(unreadable, reset, Move.RecycleStock,
            seen(reset), seen(unreadable).copy(wasteEmpty = false)))
        assertTrue(VisualSafety.verifiesMove(unreadable, reset, Move.RecycleStock,
            seen(reset), seen(unreadable).copy(wasteEmpty = false,
                wasteOccupiedUnidentified = true, wasteSignature = "observed-occupied-waste")))
        assertFalse(Move.RecycleStock in MoveGenerator.generate(before.copy(redealsRemaining = 2, waste = null)))
    }

    @Test fun visuallyAvailableRecycleStillNeedsConfirmedPhysicalTransition() {
        val before = board(stock = StockStatus.EMPTY, waste = c("5S"), recycle = true)
        val after = before.copy(stockStatus = StockStatus.AVAILABLE, waste = null, recycleAvailable = false)
        assertTrue(VisualSafety.verifiesMove(before, after, Move.RecycleStock, seen(after), seen(before)))
        assertFalse(VisualSafety.verifiesMove(before, after, Move.RecycleStock, seen(after),
            seen(before).copy(recycleAvailable = false)))
        assertFalse(VisualSafety.verifiesMove(before, after, Move.RecycleStock,
            seen(after).copy(wasteEmpty = false), seen(before)))
        assertNull(MoveSimulator.apply(before, Move.RecycleStock).redealsRemaining)
    }

    @Test fun repeatStockCycleAndFailedFlipCannotBeRetriedByRestarting() {
        val guard = AutoPlayGuard(120)
        val emptyStock = board(stock = StockStatus.EMPTY, waste = c("5S"), recycle = true)
        guard.start()
        assertNull(guard.authorize(emptyStock, Move.RecycleStock))
        guard.markConfirmed(emptyStock.copy(stockStatus = StockStatus.AVAILABLE, waste = null, recycleAvailable = false))
        guard.stop("pause")
        guard.start()
        assertTrue(guard.blocked(emptyStock, Move.RecycleStock))
        assertNotNull(guard.authorize(emptyStock, Move.RecycleStock))
        val back = board(TableauColumn(1, emptyList(), true))
        guard.start()
        assertNull(guard.authorize(back, Move.FlipTableau(0)))
        guard.markFailed(back, Move.FlipTableau(0), "unchanged")
        guard.start()
        assertTrue(guard.blocked(back, Move.FlipTableau(0)))
    }

    @Test fun solverPrefersRevealAndLargerHiddenPileBeforeDrawing() {
        val before = board(TableauColumn(1, listOf(c("AC")))).let {
            it.copy(columns = listOf(it.columns[0], TableauColumn(5, listOf(c("AS")))) + it.columns.drop(2))
        }
        val best = assertNotNull(SolitaireSolver.suggest(before))
        assertEquals(Move.TableauToFoundation(1, c("AS")), best.move)
        val onlyStock = board()
        assertEquals(Move.DrawThree, SolitaireSolver.suggest(onlyStock)?.move)
        val back = board(TableauColumn(null, emptyList(), true))
        assertEquals(Move.FlipTableau(0), SolitaireSolver.suggest(back)?.move)
    }

    @Test fun exposedBackCannotServeAsEmptyKingDestinationAndRoundTripsEditor() {
        val before = board(TableauColumn(null, emptyList(), true)).copy(waste = c("KS"))
        assertFalse(MoveGenerator.generate(before).any { it is Move.WasteToTableau && it.to == 0 })
        assertEquals(before.columns[0], BoardText.parseColumn(BoardText.formatColumn(before.columns[0])))
        assertTrue(VisualSafety.sameVisibleBoard(before, before.copy()))
        assertFalse(VisualSafety.sameVisibleBoard(before, before.copy(columns =
            listOf(TableauColumn(null, emptyList())) + before.columns.drop(1))))
    }
}
