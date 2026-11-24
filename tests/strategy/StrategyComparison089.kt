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
    for (smart in listOf(false,true)) {
        var actions=0;var totalScore=0;var wins=0;var recycle=0
        val timings=mutableListOf<Long>()
        for (seed in 1..12) {
            val deal=Deal(12000+seed)
            val guard=AutoPlayGuard(180); guard.start()
            val memory=StockPassMemory(15)
            val knowledge=DeckKnowledge()
            var steps=0
            while(steps<95) {
                val before=deal.observed()
                knowledge.observe(before)
                val start=System.nanoTime()
                val proposals=SolitaireSolver.candidates(before,remainingSeconds=300-steps,maxNodes=125)
                val options=if(smart) SmartDecision.choose(before,proposals,memory,300-steps,knowledge) else proposals
                timings.add((System.nanoTime()-start)/1_000_000L)
                val move=options.firstOrNull{ !guard.blocked(before,it.move) }?.move ?: break
                val auth=guard.authorize(before,move)
                if(auth!=null)break
                deal.apply(move)
                val after=deal.observed()
                guard.noteVerifiedProgress(before,move,after)
                guard.markConfirmed(after)?.let{ guard.start() }
                memory.verified(before,move,after)
                if(move==Move.RecycleStock)recycle++
                steps++; actions++
            }
            totalScore+=deal.points()
            if(deal.foundation.values.sum()==52)wins++
        }
        val sorted=timings.sorted()
        val p50=if(sorted.isEmpty())0 else sorted[sorted.size/2]
        val p95=if(sorted.isEmpty())0 else sorted[(sorted.size*95/100).coerceAtMost(sorted.lastIndex)]
        println("SIM smart=$smart deals=12 steps=$actions simulatedScore=$totalScore wins=$wins recycles=$recycle decisions=${timings.size} p50=${p50}ms p95=${p95}ms")
    }
}
