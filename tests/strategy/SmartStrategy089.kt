package regression

import com.retq.solitairebot.core.*

private fun card(code: String) = Card.parse(code) ?: error("Invalid $code")
private fun board(columns: List<TableauColumn> = List(7) { TableauColumn(0, emptyList()) },
                  foundation: Map<Suit,Int> = Suit.entries.associateWith { 0 },
                  waste: Card? = null, stock: StockStatus = StockStatus.UNKNOWN,
                  recycling: Boolean = false): BoardState =
    BoardState(columns, foundation, waste, stock, null, 3, recycling)

fun main() {
    var checks = 0
    fun ok(condition: Boolean, description: String) { check(condition) { description }; checks++; println("PASS $checks: $description") }
    val memory = StockPassMemory(15)
    ok(memory.summary().costPaid == 0, "Starts without fictitious stock fees")
    val empty = board(stock = StockStatus.EMPTY, waste=card("8H"), recycling=true)
    val available = empty.copy(waste = null, stockStatus = StockStatus.AVAILABLE, recycleAvailable=false)
    memory.observe(empty, "signature1")
    memory.verified(empty, Move.RecycleStock, available, "")
    ok(memory.summary().confirmedRecycles == 1 && memory.summary().costPaid == 15,
       "Confirmed recycle costs 15 points")
    val drawing = available.copy(waste = card("3C"))
    memory.verified(available, Move.DrawThree, drawing, "rgb1")
    memory.observe(drawing, "rgb1")
    ok(memory.summary().confirmedDraws == 1 && memory.knownCards() == setOf(card("8H"), card("3C")),
       "Only positively observed waste cards are remembered")
    ok(memory.summary().unknownDraws == 0 && memory.summary().currentPassSamples == 1,
       "Repeated frames do not create fake additional draws")
    val unknown = drawing.copy(waste = null)
    memory.verified(drawing, Move.DrawThree, unknown, "rgb2")
    ok(memory.summary().unknownDraws == 1 && memory.knownCards().size == 2,
       "Unknown draw-three cards never receive invented identities")
    val beforeProgress = board(columns=listOf(TableauColumn(1, listOf(card("AS"))))+
        List(6){TableauColumn(0,emptyList())})
    val afterProgress = beforeProgress.copy(columns = listOf(TableauColumn(1,emptyList(),true)) + beforeProgress.columns.drop(1),
        foundations = beforeProgress.foundations+(Suit.SPADES to 1))
    memory.verified(beforeProgress,Move.TableauToFoundation(0,card("AS")),afterProgress)
    ok(memory.summary().progressSinceRecycle, "A revealed back is verified progress")
    val score=RoundScoreLedger()
    score.verified(empty,Move.RecycleStock,available)
    score.verified(beforeProgress,Move.TableauToFoundation(0,card("AS")),afterProgress)
    ok(score.stats().estimatedBaseDelta == 0 && score.stats().raisedToFoundation == 1 &&
        score.stats().newlyExposedBacks == 1,
        "Score ledger counts verified foundation and reveal, and recycle fee")
    ok(!EndgamePlanner.allRemainingCardsKnown(available), "Unknown stock cannot trigger full-board endgame")
    val allSuits=Suit.entries.associateWith { if (it == Suit.SPADES) 12 else 13 }
    val finish=board(columns=listOf(TableauColumn(0,listOf(card("KS"))))+
        List(6){TableauColumn(0,emptyList())}, foundation=allSuits, stock=StockStatus.EMPTY)
    ok(EndgamePlanner.allRemainingCardsKnown(finish), "Fully known 52-card finish is detected")
    val solved=EndgamePlanner.plan(finish)
    ok(solved?.moves?.firstOrNull()==Move.TableauToFoundation(0,card("KS")),
        "Endgame planner finds the final foundation move")
    val ordinary=SolitaireSolver.candidates(finish)
    ok(SmartDecision.choose(finish,ordinary,memory).firstOrNull()?.move == Move.TableauToFoundation(0,card("KS")),
        "Finish route outranks heuristic suggestions")
    ok(SmartDecision.choose(empty,listOf(Suggestion(Move.RecycleStock, 20,"test")), memory).single().score == 5,
        "The 15-point recycle fee is applied to decision ranking")
    ok(StockPassMemory().canRecycle(), "First positively observed recycle remains available")
    val beamBoard=board(columns=listOf(
        TableauColumn(0, listOf(card("9S"),card("8H"))),
        TableauColumn(0, listOf(card("9C")))) + List(5) { TableauColumn(0,emptyList()) },
        foundation=Suit.entries.associateWith { if(it==Suit.SPADES)8 else 0 })
    val beamMove=Move.TableauToTableau(0,1,1,listOf(card("8H")))
    val beamBonus=TacticalBeamPlanner.bonuses(beamBoard,
        listOf(Suggestion(beamMove,15,"free card"),Suggestion(Move.DrawThree,0,"draw")),
        remaining=150, maxNodes=140, millis=20)
    ok((beamBonus[beamMove.signature]?:0)>0, "Beam sees the newly freed foundation card")
    val deck=DeckKnowledge()
    val reading=board(columns=listOf(TableauColumn(0,listOf(card("9S"),card("8H"))))+
        List(6){TableauColumn(0,emptyList())},waste=card("2C"))
    deck.observe(reading)
    ok(deck.knownCount()==3 && card("2C") !in deck.unseen(),
        "Card memory keeps only actually seen identities")
    val moreFoundation=reading.copy(foundations=reading.foundations+(Suit.HEARTS to 3),
        columns=listOf(TableauColumn(0,listOf(card("9S"))))+reading.columns.drop(1))
    deck.observe(moreFoundation)
    ok(deck.knownCount()==6 && deck.unseen().size==46,
        "Observed foundation implies lower ranks but does not locate unseen cards")
    ok(deck.immediateMobilityChance(moreFoundation) in 0.0..1.0,
        "Unknown-card opportunity is a probability, not a hidden-card identity")
    val another=StockPassMemory(15)
    val initial=board(stock=StockStatus.AVAILABLE)
    val wasteTop=initial.copy(stockStatus=StockStatus.EMPTY,waste=card("4S"),recycleAvailable=true)
    another.verified(initial,Move.DrawThree,wasteTop,"rgb")
    another.verified(wasteTop,Move.RecycleStock,initial,"empty")
    another.verified(initial,Move.DrawThree,wasteTop,"rgb")
    ok(!another.canRecycle(), "Second identical stock pass is rejected before another -15 fee")
    ok(HudClockTracker().let { tracker ->
        tracker.update(200,500).first == null && tracker.update(199,500).first == 199 &&
            tracker.update(null,null).first == 199 && tracker.update(null,null).first == null
    }, "HUD timer requires stable monotonic samples and expires on missing data")
    println("ALL $checks smart strategy tests passed")
}
