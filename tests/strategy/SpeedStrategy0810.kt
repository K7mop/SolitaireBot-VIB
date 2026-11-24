package regression

import com.retq.solitairebot.core.*

private fun card(code: String) = Card.parse(code) ?: error(code)

fun main() {
    var checks = 0
    fun ok(condition: Boolean, description: String) { check(condition) { description }; checks++; println("PASS $checks: $description") }
    val memory = StockPassMemory(15)
    val initial = BoardState.empty().copy(stockStatus = StockStatus.AVAILABLE)
    val exhausted = initial.copy(waste = card("4S"), stockStatus = StockStatus.EMPTY, recycleAvailable = true)
    fun draw() { memory.verified(initial, Move.DrawThree, exhausted, "same-card") }
    fun recycle() { memory.verified(exhausted, Move.RecycleStock, initial, "empty") }
    draw(); recycle(); draw()
    ok(!memory.canRecycle(), "Identical no-progress pass blocked BEFORE paying a second fee")
    // An externally completed/manual recycle can be reported while it was not
    // proposed. This historically latched the repeat flag forever.
    recycle(); draw()
    ok(!memory.canRecycle(), "Remembered identical cycle remains blocked")
    repeat(20) { memory.observe(initial, "varying-pixels-$it") }
    ok(!memory.canRecycle(), "Unverified image changes do not manufacture progress")
    val before = initial.copy(columns = listOf(TableauColumn(1, listOf(card("AH")))) + initial.columns.drop(1))
    val after = before.copy(columns = listOf(TableauColumn(1, emptyList(), true)) + before.columns.drop(1),
        foundations = before.foundations + (Suit.HEARTS to 1))
    memory.verified(before, Move.TableauToFoundation(0, card("AH")), after)
    ok(memory.canRecycle(), "Confirmed newly uncovered back reopens previously blocked stock opportunity")
    ok(memory.summary().costPaid == 30, "Re-evaluation neither spends nor invents another recycle fee")

    val rollbackBase = BoardState.empty().copy(
        columns = listOf(TableauColumn(0, listOf(card("KH")))) + List(6) { TableauColumn(null, emptyList()) },
        foundations = Suit.entries.associateWith { if (it == Suit.SPADES) 12 else 0 })
    val rollback = Move.FoundationToTableau(Suit.SPADES, 0, card("QS"))
    ok(SolitaireSolver.candidates(rollbackBase).none { it.move == rollback }, "Pointless foundation rollback stays excluded")
    val unlocks = rollbackBase.copy(columns = rollbackBase.columns.toMutableList().apply {
        this[1] = TableauColumn(3, listOf(card("JH")))
    })
    val candidates = SolitaireSolver.candidates(unlocks, remainingSeconds = 180)
    ok(candidates.firstOrNull()?.move == rollback, "Known QS rollback enables JH transfer and reveals a back")
    val moved = MoveSimulator.apply(unlocks, rollback)
    ok(SolitaireSolver.candidates(moved, remainingSeconds = 180).firstOrNull()?.move is Move.TableauToTableau,
        "Recovery continuation transfers the observed run instead of immediately reversing")
    val unknownBack = unlocks.copy(columns = unlocks.columns.toMutableList().apply {
        this[1] = TableauColumn(null, listOf(card("JH")))
    })
    ok(SolitaireSolver.candidates(unknownBack).none { it.move == rollback }, "Unknown hidden count does not invent a reveal bonus")
    val forced = Suggestion(Move.DrawThree, -26, "draw")
    ok(SmartDecision.choose(initial, listOf(forced), memory).single() == forced,
        "Forced legal draw bypasses extra tree search without changing its meaning")
    val fee = SmartDecision.choose(exhausted, listOf(Suggestion(Move.RecycleStock, 20, "reset")), memory)
    ok(fee.single().score == 5, "Forced stock reset still respects confirmed 15-point cost")
    val physicalBack = initial.copy(columns = listOf(TableauColumn(null, listOf(card("8S"))),
        TableauColumn(0, listOf(card("9H")))) + initial.columns.drop(2))
    val uncover = Move.TableauToTableau(0, 0, 1, listOf(card("8S")))
    ok(SolitaireSolver.candidates(physicalBack).none { it.move == uncover },
        "Unknown hidden count by itself is not a claimed reveal")
    ok(SolitaireSolver.candidates(physicalBack, observedCoveredBacks = setOf(0)).firstOrNull()?.move == uncover,
        "A positively seen covered back gets reveal priority without inventing its count")
    ok(physicalBack.columns[0].hiddenCount == null &&
        MoveSimulator.apply(physicalBack, uncover).columns[0].hiddenCount == null,
        "Root visual evidence never fills hidden counts in simulated future states")
    val autoFlipped = physicalBack.copy(columns = listOf(TableauColumn(null, listOf(card("5D"))),
        TableauColumn(0, listOf(card("9H"), card("8S")))) + physicalBack.columns.drop(2))
    val proofMemory = StockPassMemory()
    proofMemory.verified(physicalBack, uncover, autoFlipped)
    ok(!proofMemory.summary().progressSinceRecycle, "Unproven unknown-column change alone does not reopen stock")
    proofMemory.verified(physicalBack, uncover, autoFlipped, observedCoveredBacks = setOf(0))
    ok(proofMemory.summary().progressSinceRecycle,
        "Confirmed game auto-flip counts as progress when its covered back was positively seen")
    println("ALL $checks speed/stock strategy checks passed")
}
