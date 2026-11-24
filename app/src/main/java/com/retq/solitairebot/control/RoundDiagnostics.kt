package com.retq.solitairebot.control

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/** Bounded, privacy-conscious diagnostic log persisted across Activity/service process restarts. */
object RoundDiagnostics {
    private const val MAX_ENTRIES = 800
    private const val PREFS = "round_diagnostics"
    private val items = java.util.ArrayDeque<String>()
    private val counts = linkedMapOf<String, Int>()
    private data class Timing(var count: Int = 0, var total: Long = 0,
        var minimum: Long = Long.MAX_VALUE, var maximum: Long = 0) {
        fun add(ms: Long) { count++; total += ms; minimum = minOf(minimum, ms); maximum = maxOf(maximum, ms) }
    }
    private val timings = linkedMapOf<String, Timing>()
    private val timingPattern = Regex("\\b(cycle|verify|ocr|age|delivered|stroke)=([0-9]+)ms")
    private var preferences: SharedPreferences? = null
    private var appVersion = "unknown"
    private var startMs = System.nanoTime()
    private var round = "unspecified"

    @Synchronized fun attach(context: Context) {
        if (preferences != null) return
        val app = context.applicationContext
        preferences = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        appVersion = try {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "unknown"
        } catch (_: Exception) { "unknown" }
        val prefs = preferences ?: return
        round = prefs.getString("round", "unspecified") ?: "unspecified"
        val saved = prefs.getString("events", null) ?: return
        try {
            val array = JSONArray(saved)
            for (i in 0 until array.length()) {
                val line = array.optString(i).takeIf { it.isNotBlank() } ?: continue
                items.addLast(line)
                val parts = line.split("|", limit = 3)
                if (parts.size == 3) {
                    val key = parts[1].trim()
                    counts[key] = (counts[key] ?: 0) + 1
                    timingPattern.findAll(parts[2]).forEach { match ->
                        val ms = match.groupValues[2].toLongOrNull() ?: return@forEach
                        timings.getOrPut("$key.${match.groupValues[1]}") { Timing() }.add(ms)
                    }
                }
            }
            while (items.size > MAX_ENTRIES) items.removeFirst()
        } catch (_: Exception) {
            items.clear(); counts.clear(); timings.clear()
        }
    }

    @Synchronized fun reset(roundId: String?) {
        items.clear(); counts.clear(); timings.clear(); startMs = System.nanoTime()
        round = roundId?.take(36) ?: "unspecified"
        record("ROUND", "diagnostic session reset")
    }

    @Synchronized fun record(kind: String, details: String) {
        val elapsedMs = ((System.nanoTime() - startMs) / 1_000_000L).coerceAtLeast(0L)
        val clean = details.replace('\n', ' ').take(260)
        val key = kind.take(24)
        if (key in counts || counts.size < 32) counts[key] = (counts[key] ?: 0) + 1
        timingPattern.findAll(clean).forEach { match ->
            val duration = match.groupValues[2].toLongOrNull()?.coerceIn(0L, 300_000L) ?: return@forEach
            val timingKey = "$key.${match.groupValues[1]}"
            if (timingKey in timings || timings.size < 32)
                timings.getOrPut(timingKey) { Timing() }.add(duration)
        }
        items.addLast("${elapsedMs / 1000}.${(elapsedMs % 1000).toString().padStart(3, '0')}s | $key | $clean")
        while (items.size > MAX_ENTRIES) items.removeFirst()
        persistLocked()
    }

    private fun persistLocked() {
        val prefs = preferences ?: return
        val array = JSONArray()
        items.forEach { array.put(it) }
        prefs.edit().putString("round", round).putString("events", array.toString()).apply()
    }

    @Synchronized fun report(extra: String = ""): String = buildString {
        appendLine("Solitaire AI Bot - $appVersion round diagnostics")
        appendLine("Round ID: $round")
        appendLine("Device screenshots and account identifiers are not included.")
        appendLine("Diagnostics storage: persistent local event log")
        appendLine("Whole-round events: " + counts.entries.joinToString { "${it.key}=${it.value}" })
        timings.forEach { (name, value) ->
            if (value.count > 0) appendLine("$name: n=${value.count}, mean=${value.total / value.count}ms, min=${value.minimum}ms, max=${value.maximum}ms")
        }
        if (extra.isNotEmpty()) appendLine(extra.take(4_096))
        appendLine("Events (${items.size} most recent):")
        items.forEach { appendLine(it) }
    }
}
