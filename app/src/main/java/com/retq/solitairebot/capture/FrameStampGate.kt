package com.retq.solitairebot.capture

/** Producer timestamps identify independent images; they are not assumed to share a clock epoch. */
class FrameStampGate {
    private var last = Long.MIN_VALUE
    fun accept(timestampNanos: Long): Boolean {
        if (timestampNanos <= 0L || timestampNanos <= last) return false
        last = timestampNanos
        return true
    }
}
