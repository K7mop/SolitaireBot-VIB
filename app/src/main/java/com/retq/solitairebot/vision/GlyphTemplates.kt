package com.retq.solitairebot.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import com.retq.solitairebot.capture.PixelBox
import com.retq.solitairebot.core.Card
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Immutable recognizer snapshots: training/reloading never mutates a reader's pattern collection. */
class GlyphTemplates(context: Context) {
    private val automaticContext = context.applicationContext
    private val automatic by lazy { BundledCardRecognizer(automaticContext) }
    private val prefs = context.applicationContext.getSharedPreferences("card_templates", Context.MODE_PRIVATE)
    private data class Pattern(val kind: String, val label: String, val pixels: ByteArray)
    private data class RegionPattern(val label: String, val aspect: Float, val pixels: ByteArray)
    private data class Snapshot(
        val patterns: List<Pattern>, val regions: List<RegionPattern>, val examples: Int, val revision: Long
    ) {
        val ranks = patterns.filter { it.kind == "R" }.groupBy { it.label }
        val suits = patterns.filter { it.kind == "S" }.groupBy { it.label }
    }
    data class Match(val label: String, val confidence: Float)
    @Volatile private var snapshot = Snapshot(emptyList(), emptyList(), 0, -1L)

    companion object {
        const val EMPTY_COLUMN = "EMPTY_COLUMN"
        const val EMPTY_FOUNDATION = "EMPTY_FOUNDATION"
        const val EMPTY_WASTE = "EMPTY_WASTE"
        const val STOCK_BACK = "STOCK_BACK"
        const val EMPTY_STOCK = "EMPTY_STOCK"
        val REGION_LABELS = setOf(EMPTY_COLUMN, EMPTY_FOUNDATION, EMPTY_WASTE, STOCK_BACK, EMPTY_STOCK)
        private const val PATCH = 32
        /** All instances address the same preference store; writers serialize across activities. */
        private val STORE_LOCK = Any()
    }

    init { reloadIfChanged() }

    @Synchronized fun reloadIfChanged() {
        synchronized(STORE_LOCK) {
        val revision = prefs.getLong("revision", 0L)
        if (snapshot.revision == revision) return
        val patterns = mutableListOf<Pattern>()
        val regions = mutableListOf<RegionPattern>()
        try {
            val entries = JSONArray(prefs.getString("patterns", "[]"))
            for (index in 0 until entries.length()) {
                val o = entries.getJSONObject(index)
                val bytes = Base64.decode(o.getString("pixels"), Base64.NO_WRAP)
                val kind = o.getString("kind")
                val label = o.getString("label")
                if (bytes.size == 24 * 24 && ((kind == "R" && label in Card.RANKS) ||
                    (kind == "S" && label in setOf("C", "D", "H", "S")))) patterns += Pattern(kind, label, bytes)
            }
            val stored = JSONArray(prefs.getString("region_patterns", "[]"))
            for (index in 0 until stored.length()) {
                val o = stored.getJSONObject(index)
                val bytes = Base64.decode(o.getString("pixels"), Base64.NO_WRAP)
                if (bytes.size == PATCH * PATCH * 3 && o.getString("label") in REGION_LABELS)
                    regions += RegionPattern(o.getString("label"), o.getDouble("aspect").toFloat(), bytes)
            }
        } catch (_: Exception) {
            // Corrupt input cannot become an accepted template. A fresh training restores the store.
            patterns.clear(); regions.clear()
        }
        snapshot = Snapshot(patterns.toList(), regions.toList(), prefs.getInt("card_examples", 0), revision)
    
        }
    }

    /** Actual successfully labelled card teaching events; glyph/region counts are reported separately. */
    val numberOfSamples: Int get() = snapshot.examples
    val glyphPatternCount: Int get() = snapshot.patterns.size
    val ranksLearned: Int get() = snapshot.ranks.size
    val suitsLearned: Int get() = snapshot.suits.size
    val regionSamples: Int get() = snapshot.regions.size
    val currentRevision: Long get() = snapshot.revision
    /** Bundled knowledge is independent of optional user-taught templates. */
    val automaticRecognitionReady: Boolean get() = automatic.ready
    fun recognizeAutomatic(bitmap: Bitmap, x: Int, y: Int, width: Int, height: Int,
        fullFaceVisible: Boolean = false): Pair<Card, Float>? =
        automatic.recognize(bitmap, x, y, width, height, fullFaceVisible)
    fun recognizeSuitAutomatic(bitmap: Bitmap, x: Int, y: Int, width: Int, height: Int): Pair<com.retq.solitairebot.core.Suit, Float>? =
        automatic.recognizeSuit(bitmap, x, y, width, height)
    fun recognizeRecycleAutomatic(bitmap: Bitmap, x: Int, y: Int, width: Int, height: Int): Float? =
        automatic.recognizeRecycle(bitmap, x, y, width, height)
    fun stockBusyAutomatic(bitmap: Bitmap, x: Int, y: Int, width: Int, height: Int): Boolean =
        automatic.isBusyStock(bitmap, x, y, width, height)
    val coverageSummary: String get() {
        val s = snapshot
        return "تعليم أوراق في هذه النسخة: ${s.examples} | قوالب الرموز: ${s.patterns.size} | رتب: ${s.ranks.size}/13 | أنواع: ${s.suits.size}/4 | حالات مناطق: ${s.regions.size}"
    }

    @Synchronized fun train(bitmap: Bitmap, anchorX: Int, anchorY: Int, cardWidth: Int, cardHeight: Int, card: Card): Boolean {
        synchronized(STORE_LOCK) {
        val rank = extract(bitmap, anchorX, anchorY, cardWidth, cardHeight, "R") ?: return false
        val suit = extract(bitmap, anchorX, anchorY, cardWidth, cardHeight, "S") ?: return false
        reloadIfChanged()
        val s = snapshot
        val updated = s.patterns.toMutableList()
        fun add(kind: String, label: String, pixels: ByteArray) {
            val existing = updated.filter { it.kind == kind && it.label == label }
            if (existing.any { distance(it.pixels, pixels) < .025f }) return
            if (existing.size >= 6) updated.remove(existing.first())
            updated += Pattern(kind, label, pixels)
        }
        add("R", Card.RANKS[card.rank - 1], rank)
        add("S", card.suit.symbol.toString(), suit)
        save(Snapshot(updated.toList(), s.regions, s.examples + 1, s.revision + 1))
        return true
    
        }
    }

    @Synchronized fun trainRegion(bitmap: Bitmap, box: PixelBox, label: String): Boolean {
        synchronized(STORE_LOCK) {
        if (label !in REGION_LABELS) return false
        val pixels = regionSignature(bitmap, box) ?: return false
        reloadIfChanged()
        val s = snapshot
        val updated = s.regions.toMutableList()
        val aspect = (box.right - box.left).toFloat() / (box.bottom - box.top)
        if (updated.none { it.label == label && abs(it.aspect - aspect) < .02f && regionSimilarity(it.pixels, pixels) > .995f }) {
            val same = updated.filter { it.label == label }
            if (same.size >= 16) updated.remove(same.first())
            updated += RegionPattern(label, aspect, pixels)
        }
        save(Snapshot(s.patterns, updated.toList(), s.examples, s.revision + 1))
        return true
    
        }
    }

    fun classifyRegion(bitmap: Bitmap, box: PixelBox, labels: Set<String>): Match? {
        val s = snapshot
        // Automatic operation normally has no taught regions. Do not sample
        // 1024 RGB pixels for each of 13 zones when there is nothing to match.
        if (s.regions.none { it.label in labels }) return null
        val pixels = regionSignature(bitmap, box) ?: return null
        val aspect = (box.right - box.left).toFloat() / (box.bottom - box.top)
        val matches = s.regions.filter { it.label in labels && abs(it.aspect - aspect) <= max(.025f, aspect * .06f) }
            .groupBy { it.label }.map { (label, entries) -> Match(label, entries.maxOf { regionSimilarity(it.pixels, pixels) }) }
            .sortedByDescending { it.confidence }
        val best = matches.firstOrNull() ?: return null
        val runnerUp = matches.getOrNull(1)?.confidence ?: 0f
        return best.takeIf { it.confidence >= .94f && it.confidence - runnerUp >= .025f }
    }

    fun recognize(bitmap: Bitmap, anchorX: Int, anchorY: Int, cardWidth: Int, cardHeight: Int): Pair<Card, Float>? {
        val s = snapshot
        if (s.ranks.isEmpty() || s.suits.isEmpty()) return null
        val rank = recognizeKind(bitmap, anchorX, anchorY, cardWidth, cardHeight, "R", s.ranks) ?: return null
        val suit = recognizeKind(bitmap, anchorX, anchorY, cardWidth, cardHeight, "S", s.suits) ?: return null
        val card = Card.parse(rank.label + suit.label) ?: return null
        return card to min(rank.confidence, suit.confidence)
    }

    private fun recognizeKind(bitmap: Bitmap, x: Int, y: Int, w: Int, h: Int, kind: String, grouped: Map<String, List<Pattern>>): Match? {
        val target = extract(bitmap, x, y, w, h, kind) ?: return null
        var best: Match? = null
        var runnerUp = 0f
        for ((label, entries) in grouped) {
            val confidence = entries.maxOf { 1f - distance(target, it.pixels) }
            if (best == null || confidence > best.confidence) {
                runnerUp = best?.confidence ?: runnerUp
                best = Match(label, confidence)
            } else if (confidence > runnerUp) runnerUp = confidence
        }
        return best?.takeIf { it.confidence >= .90f && it.confidence - runnerUp >= .045f }
    }

    @Synchronized fun clear() {
        synchronized(STORE_LOCK) {
        reloadIfChanged()
        save(Snapshot(emptyList(), emptyList(), 0, snapshot.revision + 1))
    
        }
    }

    private fun save(value: Snapshot) {
        val glyphs = JSONArray()
        value.patterns.forEach { p -> glyphs.put(JSONObject().put("kind", p.kind).put("label", p.label)
            .put("pixels", Base64.encodeToString(p.pixels, Base64.NO_WRAP))) }
        val regions = JSONArray()
        value.regions.forEach { p -> regions.put(JSONObject().put("label", p.label).put("aspect", p.aspect.toDouble())
            .put("pixels", Base64.encodeToString(p.pixels, Base64.NO_WRAP))) }
        prefs.edit().putString("patterns", glyphs.toString()).putString("region_patterns", regions.toString())
            .putInt("card_examples", value.examples).putLong("revision", value.revision).apply()
        snapshot = value
    }

    /** Fixed-size RGB evidence. A local difference cannot be hidden by averaging an entire tall column. */
    fun regionSignature(bitmap: Bitmap, box: PixelBox): ByteArray? {
        if (box.left < 0 || box.top < 0 || box.right > bitmap.width || box.bottom > bitmap.height ||
            box.right - box.left < 3 || box.bottom - box.top < 3) return null
        val out = ByteArray(PATCH * PATCH * 3)
        for (y in 0 until PATCH) for (x in 0 until PATCH) {
            val px = box.left + ((x + .5f) * (box.right - box.left) / PATCH).toInt().coerceAtMost(box.right - box.left - 1)
            val py = box.top + ((y + .5f) * (box.bottom - box.top) / PATCH).toInt().coerceAtMost(box.bottom - box.top - 1)
            val color = FramePixels.get(bitmap, px, py)
            val i = (y * PATCH + x) * 3
            out[i] = Color.red(color).toByte(); out[i + 1] = Color.green(color).toByte(); out[i + 2] = Color.blue(color).toByte()
        }
        return out
    }

    fun regionSimilarity(a: ByteArray, b: ByteArray): Float {
        if (a.size != PATCH * PATCH * 3 || b.size != a.size) return 0f
        var maximumTileDifference = 0f
        for (ty in 0 until 8) for (tx in 0 until 8) {
            var total = 0
            for (y in ty * 4 until ty * 4 + 4) for (x in tx * 4 until tx * 4 + 4) for (c in 0..2) {
                val i = (y * PATCH + x) * 3 + c
                total += abs((a[i].toInt() and 255) - (b[i].toInt() and 255))
            }
            maximumTileDifference = max(maximumTileDifference, total / (4f * 4f * 3f * 255f))
        }
        return (1f - maximumTileDifference).coerceIn(0f, 1f)
    }

    /** Compare only a previously identified card's still-exposed top strip. */
    fun prefixSimilarity(a: ByteArray, b: ByteArray, visibleFraction: Float): Float {
        if (a.size != PATCH * PATCH * 3 || b.size != a.size) return 0f
        val rows = (PATCH * visibleFraction.coerceIn(0f, 1f)).toInt()
        if (rows < 6) return 0f
        var worst = 0f
        for (y in 0 until rows step 3) for (x in 0 until PATCH step 4) {
            var sum = 0; var samples = 0
            for (yy in y until min(rows, y + 3)) for (xx in x until min(PATCH, x + 4)) for (c in 0..2) {
                val i = (yy * PATCH + xx) * 3 + c
                sum += abs((a[i].toInt() and 255) - (b[i].toInt() and 255)); samples++
            }
            worst = max(worst, sum / (samples * 255f))
        }
        return (1f - worst).coerceIn(0f, 1f)
    }

    fun signatureId(bitmap: Bitmap, box: PixelBox): String = regionSignature(bitmap, box)?.let {
        MessageDigest.getInstance("SHA-256").digest(it).take(8).joinToString("") { b -> "%02x".format(b.toInt() and 255) }
    } ?: ""

    private fun extract(bitmap: Bitmap, x: Int, y: Int, w: Int, h: Int, kind: String): ByteArray? {
        if (w < 20 || h < 30) return null
        val left = x + (w * .055f).roundToInt()
        val right = x + (w * .37f).roundToInt()
        val top = y + (h * if (kind == "R") .045f else .26f).roundToInt()
        val bottom = y + (h * if (kind == "R") .255f else .49f).roundToInt()
        if (left < 0 || top < 0 || right > bitmap.width || bottom > bitmap.height || right <= left || bottom <= top) return null
        val rawW = right - left; val rawH = bottom - top
        val bin = ByteArray(rawW * rawH)
        var filled = 0; var minX = rawW; var minY = rawH; var maxX = -1; var maxY = -1
        for (yy in 0 until rawH) for (xx in 0 until rawW) {
            val color = FramePixels.get(bitmap, left + xx, top + yy)
            val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
            if ((r + g + b) / 3 < 155 || (r > 120 && r > g * 1.35f && r > b * 1.35f)) {
                bin[yy * rawW + xx] = 1; filled++
                minX = min(minX, xx); maxX = max(maxX, xx); minY = min(minY, yy); maxY = max(maxY, yy)
            }
        }
        if (filled < 3 || filled > rawW * rawH * .86f || maxX - minX < 2 || maxY - minY < 2) return null
        val glyphWidth = maxX - minX + 1; val glyphHeight = maxY - minY + 1
        val output = ByteArray(24 * 24)
        val ratio = min(20f / glyphWidth, 20f / glyphHeight)
        val scaledW = max(1, (glyphWidth * ratio).roundToInt()); val scaledH = max(1, (glyphHeight * ratio).roundToInt())
        val padX = (24 - scaledW) / 2; val padY = (24 - scaledH) / 2
        for (dy in 0 until scaledH) for (dx in 0 until scaledW) {
            output[(padY + dy) * 24 + padX + dx] = bin[(minY + dy * glyphHeight / scaledH) * rawW + minX + dx * glyphWidth / scaledW]
        }
        return output
    }

    private fun distance(a: ByteArray, b: ByteArray): Float {
        var difference = 0; var union = 0
        for (i in a.indices) {
            if (a[i] != b[i]) difference++
            if (a[i].toInt() != 0 || b[i].toInt() != 0) union++
        }
        return if (union == 0) 1f else difference.toFloat() / union
    }
}
