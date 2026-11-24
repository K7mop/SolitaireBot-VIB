package com.retq.solitairebot.core

/** Session clock; caller supplies monotonic milliseconds (Android elapsedRealtime()). */
data class SessionTimer(val startedAtMs: Long, val durationSeconds: Int = 300) {
    init { require(startedAtMs >= 0); require(durationSeconds > 0) }
    fun remainingSeconds(nowMs: Long): Int =
        ((durationSeconds * 1000L - (nowMs - startedAtMs).coerceAtLeast(0L)).coerceAtLeast(0L) / 1000L).toInt()
    fun expired(nowMs: Long): Boolean = remainingSeconds(nowMs) <= 0
}
