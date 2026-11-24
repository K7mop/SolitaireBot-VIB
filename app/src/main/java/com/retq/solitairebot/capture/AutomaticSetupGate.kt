package com.retq.solitairebot.capture

/** A late OCR result can never restart a stopped/replaced capture. No Android dependency. */
class AutomaticSetupGate {
    data class Token(val sequence: Long, val generation: Long, val request: Long)
    private var sequence = 0L
    private var pending: Token? = null

    @Synchronized fun begin(generation: Long, request: Long): Token =
        Token(++sequence, generation, request).also { pending = it }

    @Synchronized fun isCurrent(token: Token, generation: Long): Boolean =
        pending == token && token.generation == generation

    @Synchronized fun complete(token: Token, generation: Long): Boolean {
        if (!isCurrent(token, generation)) return false
        pending = null
        return true
    }

    @Synchronized fun cancel() { sequence++; pending = null }
    @Synchronized fun pendingFor(request: Long): Token? = pending?.takeIf { it.request == request }
}
