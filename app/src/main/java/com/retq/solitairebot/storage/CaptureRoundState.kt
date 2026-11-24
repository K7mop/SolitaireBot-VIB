package com.retq.solitairebot.storage

import com.retq.solitairebot.core.SessionTimer

/** Process-local round clock: geometry changes cannot create or extend a round. */
internal class CaptureRoundState {
    @Volatile var sessionId: String? = null
        private set
    @Volatile var roundId: String? = null
        private set
    @Volatile var roundStartedAt: Long = -1L
        private set

    @Synchronized fun beginSession(id: String, preserveRound: Boolean = false) {
        require(id.isNotBlank())
        val retainClock = preserveRound && sessionId != null
        sessionId = id
        if (!retainClock) clearRound()
    }

    @Synchronized fun endSession() { sessionId = null; clearRound() }

    @Synchronized fun startRound(id: String, atMs: Long) {
        check(sessionId != null) { "ابدأ مشاركة الشاشة قبل تحديد بدء الجولة" }
        require(id.isNotBlank() && atMs >= 0)
        roundId = id
        roundStartedAt = atMs
    }

    @Synchronized fun remainingSeconds(nowMs: Long): Int? {
        if (roundId == null || roundStartedAt < 0 || nowMs < roundStartedAt) return null
        return SessionTimer(roundStartedAt).remainingSeconds(nowMs)
    }

    @Synchronized fun roundForFrame(frameAtMs: Long): String? =
        roundId?.takeIf { frameAtMs >= roundStartedAt }

    private fun clearRound() { roundId = null; roundStartedAt = -1L }
}
