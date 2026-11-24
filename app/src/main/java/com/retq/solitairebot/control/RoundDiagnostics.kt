package com.retq.solitairebot.control

/** Bounded, privacy-conscious in-memory log for verifying a five-minute session. */
object RoundDiagnostics {
    // Keep the shared Intent comfortably below Android's transaction size limit.
    // Whole-round counters survive even when the bounded event tail rolls over.
    private const val MAX_ENTRIES = 800
    private val items = java.util.ArrayDeque<String>()
    private val counts = linkedMapOf<String, Int>()
    private data class Timing(var count: Int = 0, var total: Long = 0,
        var minimum: Long = Long.MAX_VALUE, var maximum: Long = 0) {
        fun add(ms: Long) { count++; total += ms; minimum = minOf(minimum, ms); maximum = maxOf(maximum, ms) }
    }
    private val timings = linkedMapOf<String, Timing>()
    private val timingPattern = Regex("\\b(cycle|verify|ocr|age|delivered|stroke)=([0-9]+)ms")
    private var startMs = System.nanoTime()
    private var round = "unspecified"

    @Synchronized fun reset(roundId: String?) {
        items.clear(); counts.clear(); timings.clear(); startMs = System.nanoTime()
        round = roundId?.take(36) ?: "unspecified"
        record("ROUND", "session reset")
    }
    @Synchronized fun record(kind: String, details: String) {
        val elapsedMs = ((System.nanoTime() - startMs) / 1_000_000L).coerceAtLeast(0L)
        val clean = details.replace('\n', ' ').take(260)
        val key = kind.take(24)
        // Production emits a fixed set of kinds. Bound even unexpected callers.
        if (key in counts || counts.size < 24) counts[key] = (counts[key] ?: 0) + 1
        timingPattern.findAll(clean).forEach { match ->
            val duration = match.groupValues[2].toLongOrNull()?.coerceIn(0L, 300_000L) ?: return@forEach
            val timingKey = "$key.${match.groupValues[1]}"
            if (timingKey in timings || timings.size < 24)
                timings.getOrPut(timingKey) { Timing() }.add(duration)
        }
        items.addLast("${elapsedMs / 1000}.${(elapsedMs % 1000).toString().padStart(3, '0')}s | $key | $clean")
        while (items.size > MAX_ENTRIES) items.removeFirst()
    }
    @Synchronized fun report(extra: String = ""): String = buildString {
        appendLine("Solitaire AI Bot - 0.8.10 round diagnostics")
        appendLine("Round ID: $round")
        appendLine("Device screenshots and account identifiers are not included.")
        appendLine("Whole-round events: " + counts.entries.joinToString { "${it.key}=${it.value}" })
        timings.forEach { (name, value) ->
            appendLine("$name: n=${value.count}, mean=${value.total / value.count}ms, min=${value.minimum}ms, max=${value.maximum}ms")
        }
        if (extra.isNotEmpty()) appendLine(extra.take(4_096))
        appendLine("Events (${items.size} most recent):")
        items.forEach { appendLine(it) }
    }
}
