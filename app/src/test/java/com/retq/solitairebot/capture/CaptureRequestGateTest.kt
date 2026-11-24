package com.retq.solitairebot.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureRequestGateTest {
    @Test fun notificationWaitsThreeSecondsAndRequiresNewFrame() {
        val gate = CaptureRequestGate()
        val id = gate.start(1_000L, fromNotification = true)
        assertTrue(gate.markOverlayHidden(1_000L, id))
        assertFalse(gate.ready(3_999L, 4_100L, true, id))
        assertTrue(gate.ready(4_000L, 4_100L, true, id))
    }

    @Test fun overlayMustBeHiddenAndItsFramesDrainedFirst() {
        val gate = CaptureRequestGate()
        val id = gate.start(1_000L, fromNotification = false)
        assertFalse(gate.ready(2_000L, 2_000L, true, id))
        assertTrue(gate.markOverlayHidden(2_000L, id))
        assertFalse(gate.ready(2_149L, 2_500L, true, id))
        assertTrue(gate.ready(2_150L, 2_500L, true, id))
    }

    @Test fun lateOverlayHideExtendsDelayBeyondNotificationCountdown() {
        val gate = CaptureRequestGate()
        val id = gate.start(1_000L, fromNotification = true)
        gate.markOverlayHidden(4_500L, id)
        assertFalse(gate.ready(4_600L, 4_700L, true, id))
        assertTrue(gate.ready(4_650L, 4_700L, true, id))
    }

    @Test fun obscuredContentAndFutureFrameTimestampsAreRejected() {
        val gate = CaptureRequestGate()
        val id = gate.start(1_000L, fromNotification = false)
        gate.markOverlayHidden(1_000L, id)
        assertFalse(gate.ready(2_000L, 2_000L, false, id))
        assertFalse(gate.ready(2_001L, 2_000L, true, id))
        assertTrue(gate.ready(2_000L, 2_000L, true, id))
    }

    @Test fun missingFramesExpireOnceAfterFifteenSeconds() {
        val gate = CaptureRequestGate()
        val id = gate.start(1_000L, fromNotification = true)
        gate.markOverlayHidden(1_000L, id)
        assertFalse(gate.expire(15_999L, id))
        assertFalse(gate.ready(16_000L, 16_000L, true, id))
        assertTrue(gate.expire(16_000L, id))
        assertFalse(gate.expire(16_001L, id))
        assertFalse(gate.hasPendingRequest)
    }

    @Test fun staleCallbacksCannotAffectReplacementRequest() {
        val gate = CaptureRequestGate()
        val oldId = gate.start(1_000L, fromNotification = true)
        val newId = gate.start(2_000L, fromNotification = false)
        assertNotEquals(oldId, newId)
        assertFalse(gate.markOverlayHidden(2_100L, oldId))
        assertFalse(gate.cancel(oldId))
        assertFalse(gate.expire(17_000L, oldId))
        assertTrue(gate.isCurrent(newId))
        assertFalse(gate.ready(3_000L, 3_000L, true, newId))
        assertTrue(gate.markOverlayHidden(3_000L, newId))
        assertTrue(gate.ready(3_150L, 3_150L, true, newId))
    }

    @Test fun cancelledRequestCannotSaveOrBeResurrected() {
        val gate = CaptureRequestGate()
        val id = gate.start(1_000L, fromNotification = false)
        gate.markOverlayHidden(1_000L, id)
        assertTrue(gate.cancel(id))
        assertFalse(gate.markOverlayHidden(2_000L, id))
        assertFalse(gate.ready(2_000L, 2_000L, true, id))
        assertFalse(gate.isCurrent(id))
        val next = gate.start(3_000L, fromNotification = false)
        assertNotEquals(id, next)
    }

    @Test fun clockRollbackAndPreRequestHideAreRejected() {
        val gate = CaptureRequestGate()
        val id = gate.start(1_000L, fromNotification = false)
        assertFalse(gate.markOverlayHidden(999L, id))
        gate.markOverlayHidden(1_000L, id)
        assertFalse(gate.ready(900L, 900L, true, id))
        assertFalse(gate.expire(900L, id))
    }
}
