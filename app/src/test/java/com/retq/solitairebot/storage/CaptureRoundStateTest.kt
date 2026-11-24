package com.retq.solitairebot.storage

import org.junit.Assert.*
import org.junit.Test

class CaptureRoundStateTest {
    @Test fun captureAndProcessRecreationNeverStartTheRound() {
        val state = CaptureRoundState()
        state.beginSession("first")
        assertNull(state.roundId)
        assertNull(state.remainingSeconds(10_000))
        state.startRound("round", 10_000)
        assertEquals(300, state.remainingSeconds(10_000))
        assertNull(CaptureRoundState().remainingSeconds(10_000))
        state.endSession()
        assertNull(state.remainingSeconds(10_000))
    }

    @Test fun resizePreservesTheOriginalDeadlineButNewCaptureDoesNot() {
        val state = CaptureRoundState()
        state.beginSession("portrait")
        state.startRound("round", 1_000)
        state.beginSession("landscape", preserveRound = true)
        assertEquals("landscape", state.sessionId)
        assertEquals("round", state.roundId)
        assertEquals(60, state.remainingSeconds(241_000))
        assertEquals(0, state.remainingSeconds(301_000))
        state.beginSession("new projection")
        assertNull(state.roundId)
        assertNull(state.remainingSeconds(301_000))
    }

    @Test fun anOldFrameCannotBeBoundToANewRound() {
        val state = CaptureRoundState()
        state.beginSession("capture")
        state.startRound("first", 1_000)
        assertNull(state.roundForFrame(999))
        assertEquals("first", state.roundForFrame(1_000))
        state.startRound("second", 20_000)
        assertNull(state.roundForFrame(19_999))
        assertEquals("second", state.roundForFrame(20_000))
    }

    @Test fun roundCannotStartWithoutAnActiveCapture() {
        val state = CaptureRoundState()
        try { state.startRound("round", 1_000); fail("Expected missing capture rejection") }
        catch (_: IllegalStateException) { }
        state.beginSession("first", preserveRound = true)
        assertNull(state.roundId)
    }
}
