package com.retq.solitairebot.control

import org.junit.Assert.*
import org.junit.Test

class OverlayPlacementTest {
    @Test fun stopButtonRemainsOutsideBoardAndSystemInsets() {
        val board = OverlayPlacement.Rect(20, 740, 1040, 1800)
        val p = OverlayPlacement.find(1080, 2340, 116, 56, 96, 12, listOf(board))
        assertNotNull(p)
        assertFalse(p!!.intersects(board))
        assertTrue(p.top >= 56 && p.bottom <= 2340 - 96)
    }
    @Test fun impossibleSpaceIsRejectedInsteadOfCoveringCards() {
        assertNull(OverlayPlacement.find(360, 800, 58, 28, 48, 6,
            listOf(OverlayPlacement.Rect(0, 0, 360, 800))))
    }
    @Test fun bottomSpaceUsedWhenTopIsOccupied() {
        val p = OverlayPlacement.find(360, 800, 58, 28, 48, 6,
            listOf(OverlayPlacement.Rect(0, 0, 360, 600)))
        assertNotNull(p)
        assertTrue(p!!.top > 600)
    }
}
