package com.retq.solitairebot.storage

import org.junit.Assert.*
import org.junit.Test

class SnapshotProofPolicyTest {
    private fun current(id:String="image1", requested:String="image1", session:String="capture1",
        active:String?="capture1", at:Long=1_000L, now:Long=2_000L, limit:Long=120_000L) =
        SnapshotProofPolicy.current(id, requested, session, active, at, now, limit)

    @Test fun exactSnapshotAndActiveCaptureAreRequired() {
        assertTrue(current())
        assertFalse(current(requested="other-image"))
        assertFalse(current(active="new-capture"))
        assertFalse(current(active=null)) // process death cannot restore a proof from preferences
    }
    @Test fun expiredAndFutureImagesCannotArmCalibration() {
        assertFalse(current(now=121_001L))
        assertFalse(current(at=3_000L, now=2_000L))
        assertTrue(current(now=121_000L))
    }
    @Test fun malformedOrOverflowingTimesAndBlankIdentityAreRejected() {
        assertFalse(current(id="",requested=""))
        assertFalse(current(at=-1))
        assertFalse(current(limit=-1))
        assertFalse(current(at=Long.MAX_VALUE,now=0L))
    }
}
