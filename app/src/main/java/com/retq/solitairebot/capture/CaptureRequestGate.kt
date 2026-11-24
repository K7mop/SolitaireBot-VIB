package com.retq.solitairebot.capture

/**
 * Timing and cancellation for one explicitly requested screenshot.
 *
 * All times must use the same monotonic clock. The caller must drain queued images
 * after hiding its overlay, then call [markOverlayHidden]. [ready] accepts only a
 * subsequently acquired frame after the settling delay. This class cannot inspect
 * pixels or detect another app's notification shade: [unoccluded] represents only
 * visibility conditions which the caller actually knows.
 */
class CaptureRequestGate(
    private val notificationDelayMs: Long = 3_000L,
    private val overlaySettleMs: Long = 150L,
    private val timeoutMs: Long = 15_000L
) {
    init {
        require(notificationDelayMs >= 0L)
        require(overlaySettleMs > 0L)
        require(timeoutMs > notificationDelayMs && timeoutMs > overlaySettleMs)
    }

    private data class Request(
        val id: Long,
        val requestedAtMs: Long,
        val notBeforeMs: Long,
        var overlayHiddenAtMs: Long? = null
    )

    private var nextId = 0L
    private var pending: Request? = null

    val pendingRequestId: Long?
        @Synchronized get() = pending?.id

    val hasPendingRequest: Boolean
        @Synchronized get() = pending != null

    /** A new request supersedes the old one; IDs are never reused after cancellation. */
    @Synchronized
    fun start(nowMs: Long, fromNotification: Boolean): Long {
        require(nowMs >= 0L)
        val id = ++nextId
        pending = Request(id, nowMs, nowMs + if (fromNotification) notificationDelayMs else 0L)
        return id
    }

    /**
     * Invoke on the UI/worker handoff after the overlay is hidden and old images
     * have been drained. Pass the ID returned by [start] in delayed callbacks.
     */
    @Synchronized
    fun markOverlayHidden(nowMs: Long, requestId: Long? = pendingRequestId): Boolean {
        val request = pending ?: return false
        if (request.id != requestId || nowMs < request.requestedAtMs || isExpired(request, nowMs)) return false
        if (request.overlayHiddenAtMs == null) request.overlayHiddenAtMs = nowMs
        return true
    }

    /** No side effects: the caller can report expiry separately with [expire]. */
    @Synchronized
    fun ready(
        frameAtMs: Long,
        nowMs: Long,
        unoccluded: Boolean,
        requestId: Long? = pendingRequestId
    ): Boolean {
        val request = pending ?: return false
        val hiddenAt = request.overlayHiddenAtMs ?: return false
        if (request.id != requestId || !unoccluded || isExpired(request, nowMs)) return false
        if (nowMs < request.requestedAtMs || frameAtMs > nowMs) return false
        val earliestFrame = maxOf(request.notBeforeMs, hiddenAt + overlaySettleMs)
        return frameAtMs >= earliestFrame
    }

    /** Clear only an expired request; return true once so the caller can report it. */
    @Synchronized
    fun expire(nowMs: Long, requestId: Long? = pendingRequestId): Boolean {
        val request = pending ?: return false
        if (request.id != requestId || !isExpired(request, nowMs)) return false
        pending = null
        return true
    }

    /** A stale save/completion callback cannot cancel a newer screenshot request. */
    @Synchronized
    fun cancel(requestId: Long? = pendingRequestId): Boolean {
        if (pending == null || pending?.id != requestId) return false
        pending = null
        return true
    }

    @Synchronized
    fun isCurrent(requestId: Long): Boolean = pending?.id == requestId

    private fun isExpired(request: Request, nowMs: Long): Boolean =
        nowMs >= request.requestedAtMs && nowMs - request.requestedAtMs >= timeoutMs
}
