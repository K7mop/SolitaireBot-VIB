package com.retq.solitairebot.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VisualSafetyTest {
    private fun board(t1: String, t2: String = "0;", foundation: String = "C=0,D=0,H=0,S=0", waste: String = "", stock: StockStatus = StockStatus.UNKNOWN, redeals: Int? = null) =
        BoardState(listOf(t1,t2,"0;","0;","0;","0;","0;").map(BoardText::parseColumn),
            BoardText.parseFoundations(foundation),Card.parse(waste),stock,redeals)
    /** These fixtures explicitly declare inspected empty piles; null alone supplies no such fact. */
    private fun seen(b: BoardState) = ObservationEvidence(
        observedColumns=b.columns.indices.filter { b.columns[it].top != null }.toSet(),
        emptyColumns=b.columns.indices.filter { b.columns[it].definitelyEmpty }.toSet(),
        completeColumns=b.columns.indices.filter { b.columns[it].hiddenCount != null }.toSet(),
        observedFoundations=Suit.entries.filter { b.foundations.getValue(it)>0 }.toSet(),
        emptyFoundations=Suit.entries.filter { b.foundations.getValue(it)==0 }.toSet(),
        wasteObserved=b.waste!=null,wasteEmpty=b.waste==null,observedStock=b.stockStatus!=StockStatus.UNKNOWN)
    private fun verify(a: BoardState,b: BoardState,m: Move) = VisualSafety.verifiesMove(a,b,m,seen(b),seen(a))

    @Test fun repeatedObservationIsStable() {
        val a=board("1;AS")
        assertTrue(VisualSafety.sameVisibleBoard(a,a.copy()))
        assertFalse(VisualSafety.sameVisibleBoard(a,board("1;2S")))
        assertFalse(VisualSafety.sameVisibleBoard(board("?;"),board("?;")))
    }
    @Test fun changedDeeperRunIsNotStable() {
        assertFalse(VisualSafety.sameVisibleBoard(board("0;9C 8H"),board("0;8H")))
    }
    @Test fun requiresObservedFoundationAndSourceChange() {
        val before=board("0;AC")
        val after=board("0;",foundation="C=1")
        val move=Move.TableauToFoundation(0,Card.parse("AC")!!)
        assertFalse(VisualSafety.verifiesMove(before,after,move,emptySet()))
        assertTrue(VisualSafety.verifiesMove(before,after,move,setOf(Suit.CLUBS)))
        assertFalse(verify(before,before,move))
    }
    @Test fun unreadableSourceIsNotProofOfRemoval() {
        val a=board("1;AC")
        val b=board("?;",foundation="C=1")
        assertFalse(verify(a,b,Move.TableauToFoundation(0,Card.parse("AC")!!)))
    }
    @Test fun positiveObservedRevealCanConfirmFoundationMove() {
        val a=board("1;AC")
        val b=board("0;8H",foundation="C=1")
        assertTrue(verify(a,b,Move.TableauToFoundation(0,Card.parse("AC")!!)))
    }
    @Test fun wasteMoveRequiresNewDestinationAndChangedWaste() {
        val a=board("0;9S",waste="8H")
        val b=board("0;9S 8H")
        val move=Move.WasteToTableau(0,Card.parse("8H")!!)
        assertTrue(verify(a,b,move))
        assertFalse(verify(a,a,move))
        assertFalse(VisualSafety.verifiesMove(a,b,move,seen(b).copy(wasteEmpty=false),seen(a)))
    }
    @Test fun onlyVisuallyVerifiableStockChanges() {
        val a=board("1;AC",waste="5S",stock=StockStatus.AVAILABLE)
        val b=board("1;AC",waste="2D",stock=StockStatus.AVAILABLE)
        assertTrue(verify(a,b,Move.DrawThree))
        assertFalse(verify(a,a,Move.DrawThree))
        assertFalse(VisualSafety.verifiesMove(a,b,Move.DrawThree,seen(b).copy(observedStock=false),seen(a)))
        assertFalse(verify(a,board("1;AC"),Move.RecycleStock))
    }
    @Test fun recycleRequiresPositiveStockAndWasteEmptyProof() {
        val a=board("1;AC",waste="5S",stock=StockStatus.EMPTY,redeals=1)
        val b=board("1;AC",stock=StockStatus.AVAILABLE,redeals=0)
        assertTrue(verify(a,b,Move.RecycleStock))
        assertFalse(VisualSafety.verifiesMove(a,b,Move.RecycleStock,seen(b).copy(wasteEmpty=false),seen(a)))
    }
    @Test fun verifiesEveryCardInAMultiCardRun() {
        val a=board("0;8H 7S","0;9C")
        val m=Move.TableauToTableau(0,0,1,listOf(Card.parse("8H")!!,Card.parse("7S")!!))
        val b=board("0;","0;9C 8H 7S")
        assertTrue(verify(a,b,m))
        assertFalse(verify(a,board("0;","0;7S"),m))
    }
    @Test fun unexplainedOtherColumnChangeRejectsSuccess() {
        val a=board("0;8H","0;9S")
        val b=board("0;","0;9S 8H").let { it.copy(columns=it.columns.toMutableList().also { cols -> cols[2]=TableauColumn(0,listOf(Card.parse("KC")!!)) }) }
        assertFalse(verify(a,b,Move.TableauToTableau(0,0,1,listOf(Card.parse("8H")!!))))
    }
    @Test fun rollbackOfAnAceCanBeVerifiedWithAnEmptyFoundation() {
        val a=board("0;2H",foundation="C=1")
        val b=board("0;2H AC",foundation="C=0")
        assertTrue(verify(a,b,Move.FoundationToTableau(Suit.CLUBS,0,Card.parse("AC")!!)))
    }
}
