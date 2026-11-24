package com.retq.solitairebot.capture

import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class AutomaticSetupGateTest {
    @Test fun stoppedRecognitionCannotRestartAutomaticPlay() {
        val gate = AutomaticSetupGate()
        val inFlight = gate.begin(7, 1)
        gate.cancel()
        assertFalse(gate.complete(inFlight, 7))
    }
    @Test fun newScreenshotSupersedesOldRecognition() {
        val gate = AutomaticSetupGate()
        val old = gate.begin(7, 1)
        val fresh = gate.begin(7, 2)
        assertFalse(gate.complete(old, 7))
        assertTrue(gate.complete(fresh, 7))
    }
    @Test fun captureResizeInvalidatesOldCoordinates() {
        val gate = AutomaticSetupGate()
        val inFlight = gate.begin(7, 1)
        assertFalse(gate.isCurrent(inFlight, 8))
        assertFalse(gate.complete(inFlight, 8))
    }
    @Test fun automaticStartIsDeliveredOnlyOnce() {
        val gate = AutomaticSetupGate()
        val inFlight = gate.begin(7, 1)
        assertTrue(gate.complete(inFlight, 7))
        assertFalse(gate.complete(inFlight, 7))
        assertNull(gate.pendingFor(1))
    }
    @Test fun oldSaveCannotAcquireNewSetupRequest() {
        val gate = AutomaticSetupGate()
        gate.begin(7, 2)
        assertNull(gate.pendingFor(1))
        assertTrue(gate.pendingFor(2) != null)
    }
    @Test fun cancelledTokenDoesNotReviveWhenRequestNumberIsReused() {
        val gate = AutomaticSetupGate()
        val old = gate.begin(7, 1)
        gate.cancel()
        val fresh = gate.begin(7, 1)
        assertFalse(gate.complete(old, 7))
        assertTrue(gate.complete(fresh, 7))
    }
}
