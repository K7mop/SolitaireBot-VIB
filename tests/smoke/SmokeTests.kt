package com.retq.solitairebot.core

import com.retq.solitairebot.capture.BattleArenaPortraitProfile
import com.retq.solitairebot.capture.CaptureConfig

/** Runs without Android SDK or Gradle: kotlinc <core sources> -include-runtime -d ... && java -jar ... */
fun main() {
    fun board(vararg columns: String, foundation: String = "C=0,D=0,H=0,S=0", waste: String = "", stock: StockStatus = StockStatus.UNKNOWN): BoardState {
        require(columns.size == 7)
        return BoardState(columns.map(BoardText::parseColumn), BoardText.parseFoundations(foundation),
            Card.parse(waste), stock)
    }
    check(Card.parse("10h") == Card(10, Suit.HEARTS))
    check(Card.parse("A♠") == Card(1, Suit.SPADES))
    check(Card.parse("0Q") == null)
    val sample = board("1;AS", "2;AH", "3;KS", "4;2S", "5;QH", "6;JH", "?;QS", stock = StockStatus.AVAILABLE)
    // Sample profile's seven visible cards are a source fixture, NOT a solved deal.
    check(sample.problems().isEmpty())
    val moves = MoveGenerator.generate(sample)
    check(moves.any { it is Move.TableauToFoundation && it.card == Card.parse("AS") })
    check(moves.any { it is Move.TableauToFoundation && it.card == Card.parse("AH") })
    check(moves.any { it == Move.DrawThree })
    check(SolitaireSolver.suggest(sample)?.move is Move.TableauToFoundation)
    val revealed = board("1;8H 7S", "0;9C", "0;", "0;", "0;", "0;", "0;")
    check(MoveGenerator.generate(revealed).any { it is Move.TableauToTableau && it.from == 0 && it.to == 1 && it.startIndex == 0 })
    val revealMove = MoveGenerator.generate(revealed).filterIsInstance<Move.TableauToTableau>().first { it.to == 1 }
    val moved = MoveSimulator.apply(revealed, revealMove)
    check(moved.columns[1].faceUp == listOf(Card.parse("9C"), Card.parse("8H"), Card.parse("7S")))
    check(moved.columns[0].faceUp.isEmpty())
    val junk = board("0;AS", "0;AS", "?;", "?;", "?;", "?;", "?;")
    check(junk.problems().isNotEmpty())
    check(MoveGenerator.generate(junk).isEmpty())
    // Unknown emptiness cannot accept a king: prevents accidental moves into occupied slots.
    val king = board("0;KH", "?;", "0;", "0;", "0;", "0;", "0;")
    check(MoveGenerator.generate(king).none { it is Move.TableauToTableau && it.to == 1 })
    check(MoveGenerator.generate(king).any { it is Move.TableauToTableau && it.to == 2 })
    val recycled = board("0;", "0;", "0;", "0;", "0;", "0;", "0;", stock = StockStatus.EMPTY, waste = "5S")
    check(MoveGenerator.generate(recycled).none { it == Move.RecycleStock })
    check(MoveGenerator.generate(recycled.copy(redealsRemaining = 2)).contains(Move.RecycleStock))
    val ace = board("0;AC", "0;", "0;", "0;", "0;", "0;", "0;")
    val aceMoved = board("0;", "0;", "0;", "0;", "0;", "0;", "0;", foundation = "C=1")
    val aceMove = Move.TableauToFoundation(0, Card.parse("AC")!!)
    check(VisualSafety.sameVisibleBoard(ace, ace.copy()))
    check(!VisualSafety.sameVisibleBoard(ace, aceMoved))
    check(!VisualSafety.verifiesMove(ace, aceMoved, aceMove, emptySet()))
    check(VisualSafety.verifiesMove(ace, aceMoved, aceMove, setOf(Suit.CLUBS)))
    check(!VisualSafety.verifiesMove(ace, ace, aceMove, setOf(Suit.CLUBS)))
    val shiftedWaste = board("0;AC", "0;", "0;", "0;", "0;", "0;", "0;", waste = "3D")
    check(VisualSafety.verifiesMove(ace.copy(stockStatus = StockStatus.AVAILABLE),
        shiftedWaste.copy(stockStatus = StockStatus.AVAILABLE), Move.DrawThree,
        ObservationEvidence(observedStock = true, wasteObserved = true),
        ObservationEvidence(observedStock = true, wasteEmpty = true)))
    check(!VisualSafety.verifiesMove(ace, ace, Move.DrawThree, emptySet()))
    check(!VisualSafety.verifiesMove(ace, aceMoved, Move.RecycleStock, emptySet()))
    check(BoardState.empty().incomplete)
    val layout = BattleArenaPortraitProfile.boardLayout
    check(layout.foundations.size == 4 && layout.tableauColumns.size == 7)
    check(layout.stock.toPixels(540, 1170).left in 450..455)
    check(layout.waste.toPixels(540, 1170).right in 445..450)
    check(CaptureConfig().maxFramesPerSecond == 2)
    val gate = AutoPlayGuard(maxActions = 2)
    check(gate.authorize(ace, aceMove) != null) // no implicit play
    gate.resetSession(); gate.start()
    check(gate.authorize(ace, aceMove) == null)
    check(gate.authorize(ace, aceMove) != null) // same board + action must never repeat
    check(!gate.enabled)
    gate.resetSession(); gate.start()
    check(gate.authorize(ace, aceMove) == null)
    gate.failed("gesture cancelled")
    check(!gate.enabled)
    gate.resetSession(); gate.start()
    check(gate.authorize(ace, aceMove) == null)
    check(gate.authorize(aceMoved, Move.DrawThree) == null)
    check(gate.authorize(aceMoved, Move.RecycleStock) != null) // hard bound
    check(!gate.enabled)
    gate.start()
    check(gate.authorize(ace, aceMove) != null) // stop/start cannot erase attempted action history
    println("ALL 41 core/calibration/auto-guard assertions passed")
}
