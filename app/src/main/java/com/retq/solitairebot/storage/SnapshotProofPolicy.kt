package com.retq.solitairebot.storage

/** Pure proof lease used by Android storage and deterministic regression tests. */
object SnapshotProofPolicy {
    fun current(savedId: String, requestedId: String, savedSession: String, activeSession: String?,
        capturedAtMs: Long, nowMs: Long, maxAgeMs: Long): Boolean =
        activeSession != null && activeSession.isNotBlank() && savedSession == activeSession &&
            savedId.isNotBlank() && savedId == requestedId && capturedAtMs >= 0L &&
            maxAgeMs >= 0L && nowMs >= capturedAtMs && nowMs - capturedAtMs <= maxAgeMs
}
