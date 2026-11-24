package com.retq.solitairebot.control

import android.content.Context
import android.content.SharedPreferences
import android.os.Process
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Bounded local diagnostics. An append-only private file is the source of truth:
 * unlike a process-local singleton/SharedPreferences cache, it can be read again
 * by the Activity after the capture service has written events.
 */
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
    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "solitaire-diagnostics-writer").apply { isDaemon = true }
    }
    private var preferences: SharedPreferences? = null
    private var logFile: File? = null
    private var appVersion = "unknown"
    private var startMs = System.nanoTime()
    private var round = "unspecified"

    @Synchronized fun attach(context: Context) {
        if (preferences != null) return
        val app = context.applicationContext
        preferences = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        logFile = File(app.filesDir, "round-diagnostics.log")
        appVersion = try {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "unknown"
        } catch (_: Exception) { "unknown" }
        val prefs = preferences ?: return
        round = prefs.getString("round", "unspecified") ?: "unspecified"
        val file = logFile
        val savedLines = try {
            if (file != null && file.exists() && file.length() > 0L) file.readLines(Charsets.UTF_8)
            else {
                val array = JSONArray(prefs.getString("events", "[]") ?: "[]")
                (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
            }
        } catch (_: Exception) { emptyList() }
        rebuildLocked(savedLines)
        // Migrate the previous SharedPreferences-only log on first launch of this build.
        if (file != null && !file.exists() && savedLines.isNotEmpty()) {
            writer.execute { writeSnapshot(file, savedLines.takeLast(MAX_ENTRIES)) }
        }
    }

    @Synchronized fun reset(roundId: String?) {
        items.clear(); counts.clear(); timings.clear(); startMs = System.nanoTime()
        round = roundId?.take(36) ?: "unspecified"
        val file = logFile
        if (file != null) writer.execute {
            try { FileOutputStream(file, false).use { it.fd.sync() } } catch (_: Exception) { }
        }
        record("ROUND", "diagnostic session reset; pid=${Process.myPid()}")
    }

    @Synchronized fun record(kind: String, details: String) {
        val elapsedMs = ((System.nanoTime() - startMs) / 1_000_000L).coerceAtLeast(0L)
        val clean = details.replace('\n', ' ').replace('|', '/').take(260)
        val key = kind.take(24)
        if (key in counts || counts.size < 32) counts[key] = (counts[key] ?: 0) + 1
        timingPattern.findAll(clean).forEach { match ->
            val duration = match.groupValues[2].toLongOrNull()?.coerceIn(0L, 300_000L) ?: return@forEach
            val timingKey = "$key.${match.groupValues[1]}"
            if (timingKey in timings || timings.size < 32)
                timings.getOrPut(timingKey) { Timing() }.add(duration)
        }
        val line = "${elapsedMs / 1000}.${(elapsedMs % 1000).toString().padStart(3, '0')}s | $key | $clean"
        items.addLast(line)
        while (items.size > MAX_ENTRIES) items.removeFirst()
        persistLocked()
        logFile?.let { file ->
            writer.execute {
                appendLineLocked(file, line)
                trimFileLocked(file)
            }
        }
    }

    private fun appendLineLocked(file: File, line: String) {
        try {
            FileOutputStream(file, true).use { stream ->
                val bytes = (line + "\n").toByteArray(StandardCharsets.UTF_8)
                val channel: FileChannel = stream.channel
                channel.lock().use {
                    stream.write(bytes)
                    stream.flush()
                    stream.fd.sync()
                }
            }
        } catch (_: Exception) { }
    }

    private fun trimFileLocked(file: File) {
        try {
            val lines = file.readLines(Charsets.UTF_8)
            if (lines.size > MAX_ENTRIES) writeSnapshot(file, lines.takeLast(MAX_ENTRIES))
        } catch (_: Exception) { }
    }

    private fun writeSnapshot(file: File, lines: List<String>) {
        try {
            FileOutputStream(file, false).use { stream ->
                stream.write((lines.joinToString("\n") + if (lines.isEmpty()) "" else "\n")
                    .toByteArray(StandardCharsets.UTF_8))
                stream.flush()
                stream.fd.sync()
            }
        } catch (_: Exception) { }
    }

    private fun persistLocked() {
        val prefs = preferences ?: return
        val array = JSONArray()
        items.forEach { array.put(it) }
        prefs.edit().putString("round", round).putString("events", array.toString()).apply()
    }

    private fun rebuildLocked(lines: List<String>) {
        items.clear(); counts.clear(); timings.clear()
        lines.takeLast(MAX_ENTRIES).forEach { line ->
            if (line.isBlank()) return@forEach
            items.addLast(line)
            val parts = line.split("|", limit = 3)
            if (parts.size == 3) {
                val key = parts[1].trim()
                if (key in counts || counts.size < 32) counts[key] = (counts[key] ?: 0) + 1
                timingPattern.findAll(parts[2]).forEach { match ->
                    val ms = match.groupValues[2].toLongOrNull() ?: return@forEach
                    val timingKey = "$key.${match.groupValues[1]}"
                    if (timingKey in timings || timings.size < 32)
                        timings.getOrPut(timingKey) { Timing() }.add(ms)
                }
            }
        }
    }

    @Synchronized fun report(extra: String = ""): String {
        // Drain queued service writes, then re-read the shared file before exporting.
        try { writer.submit { Unit }.get(3, TimeUnit.SECONDS) } catch (_: Exception) { }
        logFile?.let { file ->
            try { if (file.exists()) rebuildLocked(file.readLines(Charsets.UTF_8)) } catch (_: Exception) { }
        }
        return buildString {
            appendLine("Solitaire AI Bot - $appVersion round diagnostics")
            appendLine("Round ID: $round")
            appendLine("Diagnostic process: pid=${Process.myPid()}")
            appendLine("Device screenshots and account identifiers are not included.")
            appendLine("Diagnostics storage: private append-only file + SharedPreferences fallback")
            appendLine("Whole-round events: " + counts.entries.joinToString { "${it.key}=${it.value}" })
            timings.forEach { (name, value) ->
                if (value.count > 0) appendLine("$name: n=${value.count}, mean=${value.total / value.count}ms, min=${value.minimum}ms, max=${value.maximum}ms")
            }
            if (extra.isNotEmpty()) appendLine(extra.take(4_096))
            appendLine("Events (${items.size} most recent):")
            items.forEach { appendLine(it) }
        }
    }
}
