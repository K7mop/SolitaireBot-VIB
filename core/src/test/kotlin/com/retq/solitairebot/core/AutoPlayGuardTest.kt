package com.retq.solitairebot.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AutoPlayGuardTest {
    private val first = Card.parse("AS")!!
    private val board = BoardState.empty().copy(columns = listOf(
        TableauColumn(1, listOf(first))
    ) + List(6) { TableauColumn(null, emptyList()) })
    private val move = Move.TableauToFoundation(0, first)

    @Test fun `requires an explicit start`() {
        val gate = AutoPlayGuard()
        assertNotNull(gate.authorize(board, move))
        assertEquals(0, gate.attempts)
    }
    @Test fun `will not repeat the same move against unchanged board`() {
        val gate = AutoPlayGuard(); gate.start()
        assertNull(gate.authorize(board, move))
        assertNotNull(gate.authorize(board, move))
        assertFalse(gate.enabled)
        assertEquals(1, gate.attempts)
    }
    @Test fun `failure immediately stops autoplay`() {
        val gate = AutoPlayGuard(); gate.start()
        assertNull(gate.authorize(board, move))
        gate.failed("gesture cancelled")
        assertFalse(gate.enabled)
        assertNotNull(gate.authorize(board, move))
    }
    @Test fun `hard cap prevents endless sessions`() {
        val gate = AutoPlayGuard(maxActions = 1); gate.start()
        assertNull(gate.authorize(board, move))
        val alternative = Move.DrawThree
        assertNotNull(gate.authorize(board, alternative))
        assertFalse(gate.enabled)
    }
    @Test fun `restart preserves attempted and failed edges`() {
        val gate = AutoPlayGuard(); gate.start()
        assertNull(gate.authorize(board, move))
        gate.markFailed(board, move, "not verified")
        gate.start()
        assertTrue(gate.blocked(board, move))
        assertNotNull(gate.authorize(board, move))
        assertEquals(1, gate.attempts)
    }
    @Test fun `new capture explicitly clears history`() {
        val gate = AutoPlayGuard(); gate.start()
        assertNull(gate.authorize(board, move))
        gate.resetSession(); gate.start()
        assertFalse(gate.blocked(board, move))
        assertNull(gate.authorize(board, move))
    }
    @Test fun `manual dispatch shares failed move memory`() {
        val gate = AutoPlayGuard()
        assertNull(gate.authorize(board, move, requireAuto = false))
        gate.markFailed(board, move, "no visual proof")
        assertNotNull(gate.authorize(board, move, requireAuto = false))
    }
    @Test fun `unknown hidden counts cannot bypass attempted state key`() {
        val gate = AutoPlayGuard(); gate.start()
        assertNull(gate.authorize(board, move))
        val changedUnobservedCount = board.copy(columns = board.columns.map { it.copy(hiddenCount = null) })
        assertTrue(gate.blocked(changedUnobservedCount, move))
    }

}
