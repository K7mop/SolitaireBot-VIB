package com.retq.solitairebot.capture

import org.junit.Assert.*
import org.junit.Test

class FrameStampGateTest {
    @Test fun duplicatedAndOutOfOrderFramesCannotCountAsFreshEvidence() {
        val gate = FrameStampGate()
        assertFalse(gate.accept(0))
        assertTrue(gate.accept(1_000_000))
        assertFalse(gate.accept(1_000_000))
        assertFalse(gate.accept(999_999))
        assertTrue(gate.accept(1_000_001))
    }
    @Test fun newReaderHasIndependentClockSequence() {
        val old = FrameStampGate()
        assertTrue(old.accept(Long.MAX_VALUE - 1))
        assertTrue(FrameStampGate().accept(1))
    }
}
