package regression

import com.retq.solitairebot.core.*
import kotlin.random.Random

private fun card(code: String) = requireNotNull(Card.parse(code))
private fun board(vararg columns: TableauColumn): BoardState = BoardState(
    columns.toList() + List(7 - columns.size) { TableauColumn(0, emptyList()) },
    Suit.entries.associateWith { 0 }, null, StockStatus.UNKNOWN
)

/** Exact hidden deck belongs to the offline simulator only, never to the phone's solver. */
private class Deal(seed: Int) {
    val columns = List(7) { mutableListOf<Card>() }
    val hidden = List(7) { mutableListOf<Card>() }
    val foundation = Suit.entries.associateWith { 0 }.toMutableMap()
    val waste = mutableListOf<Card>()
    var stock: MutableList<Card>
    var redeals = 2
    init {
        val cards = Suit.entries.flatMap { suit -> (1..13).map { Card(it, suit) } }
            .shuffled(Random(seed)).toMutableList()
        for (col in 0..6) for (i in 0..col) {
            val c = cards.removeAt(0)
            if (i == col) columns[col].add(c) else hidden[col].add(c)
        }
        stock = cards
    }
    fun observed(): BoardState = BoardState(columns.indices.map { c ->
        TableauColumn(hidden[c].size, columns[c].toList(), hidden[c].isNotEmpty() && columns[c].isEmpty())
    },foundation.toMap(),waste.lastOrNull(),if(stock.isEmpty())StockStatus.EMPTY else StockStatus.AVAILABLE,
        redeals,3,stock.isEmpty()&&waste.isNotEmpty()&&redeals>0)
    fun apply(move: Move) {
        val b=observed()
        check(MoveGenerator.generate(b).any { it.signature==move.signature })
        when(move) {
            is Move.TableauToFoundation -> { columns[move.column].removeAt(columns[move.column].lastIndex);foundation[move.card.suit]=move.card.rank }
            is Move.WasteToFoundation -> { check(waste.removeAt(waste.lastIndex)==move.card);foundation[move.card.suit]=move.card.rank }
            is Move.TableauToTableau -> {
                val source=columns[move.from];val run=source.subList(move.startIndex,source.size).toList()
                check(run==move.cards);source.subList(move.startIndex,source.size).clear();columns[move.to].addAll(run)
            }
            is Move.WasteToTableau -> { check(waste.removeAt(waste.lastIndex)==move.card);columns[move.to].add(move.card) }
            is Move.FoundationToTableau -> { foundation[move.fromSuit]=move.card.rank-1;columns[move.to].add(move.card) }
            is Move.FlipTableau -> columns[move.column].add(hidden[move.column].removeAt(hidden[move.column].lastIndex))
            Move.DrawOne -> error("This simulator is Draw 3")
            Move.DrawThree -> { val count=minOf(3,stock.size);repeat(count){waste.add(stock.removeAt(0))} }
            Move.RecycleStock -> { stock=waste.toMutableList();waste.clear();redeals-- }
        }
        check(observed().problems().isEmpty()) { "invalid simulated deal after $move: ${observed().problems()}" }
    }
    fun points(): Int = foundation.values.sum() * 10 + (28 - hidden.sumOf { it.size }) * 5
}

fun main() {
    var checks=0
    fun pass(message: String) { checks++;println("PASS $checks $message") }
    val reveal=board(TableauColumn(2,listOf(card("AS"))), TableauColumn(0,listOf(card("8H"))))
    val sim=MoveSimulator.apply(reveal,Move.TableauToFoundation(0,card("AS")))
    check(sim.columns[0].exposedBack && MoveGenerator.generate(sim).contains(Move.FlipTableau(0)))
    pass("lookahead notices the real exposed back after removing final visible card")

    val boardWithAce=board(TableauColumn(0,listOf(card("AS"))), TableauColumn(0,listOf(card("9H"))))
    check(SolitaireSolver.suggest(boardWithAce)?.move == Move.TableauToFoundation(0,card("AS")))
    pass("ace is promoted ahead of unproductive choices")

    val revealed=board(TableauColumn(4,listOf(card("8H"))),TableauColumn(0,listOf(card("9S"))),
        TableauColumn(0,listOf(card("AC"))))
    val ranked=SolitaireSolver.candidates(revealed)
    check(ranked.first().move is Move.TableauToTableau && (ranked.first().move as Move.TableauToTableau).from==0)
    pass("a known hidden-card reveal outranks an ordinary foundation ascent")

    val late=board(TableauColumn(0,listOf(card("AS"))),TableauColumn(0,listOf(card("5H"))))
    check(SolitaireSolver.suggest(late,remainingSeconds=24)?.move==Move.TableauToFoundation(0,card("AS")))
    pass("last thirty seconds favor verified immediate foundation points")

    val pointlessShuffle=board(TableauColumn(0,listOf(card("9S"),card("8H"))),
        TableauColumn(0,listOf(card("9C")))).copy(stockStatus=StockStatus.AVAILABLE)
    check(SolitaireSolver.candidates(pointlessShuffle).none {
        it.move is Move.TableauToTableau && it.move.from==0
    })
    pass("unproductive face-up shuffling is pruned rather than repeated until the timer expires")

    val guard=AutoPlayGuard(50);guard.start()
    val stockBoard=board(TableauColumn(0,listOf(card("AS")))).copy(stockStatus=StockStatus.AVAILABLE)
    check(guard.authorize(stockBoard,Move.DrawThree)==null)
    check(guard.blocked(stockBoard,Move.DrawThree))
    val next=stockBoard.copy(foundations=stockBoard.foundations+(Suit.SPADES to 1),columns=
        listOf(TableauColumn(0,emptyList()))+stockBoard.columns.drop(1))
    guard.noteVerifiedProgress(stockBoard,Move.TableauToFoundation(0,card("AS")),next)
    check(!guard.blocked(stockBoard,Move.DrawThree))
    check(guard.authorize(stockBoard,Move.DrawThree)==null)
    check(guard.blocked(stockBoard,Move.DrawThree))
    val shuffle=pointlessShuffle
    val altered=MoveSimulator.apply(shuffle,Move.TableauToTableau(0,1,1,listOf(card("8H"))))
    guard.noteVerifiedProgress(shuffle,Move.TableauToTableau(0,1,1,listOf(card("8H"))),altered)
    check(guard.blocked(stockBoard,Move.DrawThree))
    pass("verified genuine progress opens a stock pass; plain tableau rearrangement cannot")

    // Every simulated round has a separate real 52-card hidden deck; the solver sees
    // ONLY its currently exposed observation and must not invent the hidden cards.
    var totalMoves=0;var maxSteps=0;var solved=0;var totalScore=0
    for (seed in 1..12) {
        val deal=Deal(12_000+seed)
        val session=AutoPlayGuard(300);session.start()
        var steps=0
        while (steps<110) {
            val old=deal.observed()
            val choices=SolitaireSolver.candidates(old,remainingSeconds=300-steps, maxNodes=120)
            val move=choices.firstOrNull { !session.blocked(old,it.move) }?.move?:break
            check(session.authorize(old,move)==null)
            deal.apply(move)
            session.noteVerifiedProgress(old,move,deal.observed())
            val loop=session.markConfirmed(deal.observed())
            steps++
            if(loop!=null){session.start();continue}
        }
        check(steps<=110)
        totalMoves+=steps;maxSteps=maxOf(maxSteps,steps)
        totalScore+=deal.points()
        if(deal.foundation.values.sum()==52)solved++
    }
    check(totalMoves>40)
    pass("12 separate 52-card offline rounds: $totalMoves legal verified-model steps, max $maxSteps, solved $solved, aggregate simulated score $totalScore")
    println("ALL $checks strategy regression groups passed; simulated deals are not Android/on-device results")
}
