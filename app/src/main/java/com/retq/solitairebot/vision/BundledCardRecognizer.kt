package com.retq.solitairebot.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import com.retq.solitairebot.core.Card
import com.retq.solitairebot.core.Suit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Offline, font-template recognizer for upright cards with a pale face and a
 * rank above a suit in the upper-left corner. It never supplies a card merely
 * because a label is missing. Similarities are NOT calibrated probabilities.
 *
 * Loading happens once, on the caller's worker thread. The immutable template
 * table is shared between capture readers. No screenshot is sent off device.
 */
class BundledCardRecognizer(context: Context) {
    private data class Pattern(val label: String, val bits: LongArray, val count: Int, val dilated: LongArray)
    private data class Library(val ranks: List<Pattern>, val suits: List<Pattern>, val recycle: List<Pattern>) {
        val ready = ranks.map { it.label }.toSet().containsAll(Card.RANKS) && suits.map { it.label }.toSet().containsAll(listOf("C", "D", "H", "S"))
    }
    private data class Mask(val width: Int, val height: Int, val pixels: ByteArray,
                            val red: Int = 0, val dark: Int = 0)
    private data class Band(val top: Int, val bottom: Int)
    private data class Match(val label: String, val score: Float)
    private data class RankedMatch(val best: Match, val runnerUp: Float) {
        fun strict(): Match? = best.takeIf { it.score >= .90f && it.score - runnerUp >= .055f }
    }
    private val library = load(context.applicationContext)
    val ready: Boolean get() = library.ready

    private class MaskKey(val group: Int, val bits: LongArray) {
        override fun hashCode(): Int = 31 * group + bits.contentHashCode()
        override fun equals(other: Any?): Boolean = other is MaskKey &&
            group == other.group && bits.contentEquals(other.bits)
    }
    // A cache of this pure matching function, not a cache of board identities.
    // Every fresh frame still supplies and validates its actual RGB mask.
    private val maskCache = object : java.util.LinkedHashMap<MaskKey, RankedMatch?>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MaskKey, RankedMatch?>?): Boolean = size > 768
    }

    fun recognize(bitmap: Bitmap, x: Int, y: Int, width: Int, height: Int,
        fullFaceVisible: Boolean = false): Pair<Card, Float>? =
        recognizeStackedCorner(bitmap, x, y, width, height)
            ?: recognizeSeparatedCorners(bitmap, x, y, width, height, fullFaceVisible)

    /** Original upper-left rank-above-suit artwork. */
    private fun recognizeStackedCorner(bitmap: Bitmap, x: Int, y: Int, width: Int, height: Int): Pair<Card, Float>? {
        if (!ready || width < 32 || height < 44 || x < 0 || y < 0 ||
            x + width > bitmap.width || y + height > bitmap.height) return null
        // A dark back, modal dimming, artwork or a partially covered crop must
        // not be promoted to an exposed face by finding an incidental glyph.
        val left = x + max(2, (width * .035f).roundToInt())
        val top = y + max(2, (height * .025f).roundToInt())
        val right = x + (width * .46f).roundToInt()
        val bottom = y + (height * .54f).roundToInt()
        val w = right - left; val h = bottom - top
        if (w < 10 || h < 18) return null
        val pixels = ByteArray(w * h)
        var pale = 0; var saturatedOther = 0
        for (yy in 0 until h) for (xx in 0 until w) {
            val c = FramePixels.get(bitmap, left + xx, top + yy)
            val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
            val red = r > 95 && r > g * 1.4f && r > b * 1.4f
            val neutralDark = maxOf(r, g, b) < 175 && maxOf(r, g, b) - minOf(r, g, b) < 75
            if (minOf(r, g, b) >= 175 && maxOf(r, g, b) - minOf(r, g, b) < 60) pale++
            if (red || neutralDark) pixels[yy * w + xx] = 1
            else if (maxOf(r, g, b) - minOf(r, g, b) > 90) saturatedOther++
        }
        if (pale < w * h * .56f || saturatedOther > w * h * .05f) return null
        val bands = bands(Mask(w, h, pixels), max(1, height / 120))
            .filter { it.bottom - it.top >= max(5, height / 25) }
        // Ignore later artwork; the first two separate bands must be the rank
        // and its suit, with plausible vertical alignment and compact spacing.
        if (bands.size < 2) return null
        val rankBand = bands[0]; val suitBand = bands[1]
        if (rankBand.top > height * .13f || rankBand.bottom - rankBand.top > height * .28f ||
            suitBand.top - rankBand.bottom > height * .18f ||
            suitBand.bottom - suitBand.top > height * .27f || suitBand.bottom > height * .52f) return null
        val rankMask = cropMask(bitmap, left, top, w, pixels, rankBand)
        val suitMask = cropMask(bitmap, left, top, w, pixels, suitBand)
        fun center(mask: Mask): Float {
            var low = mask.width; var high = -1
            for (yy in 0 until mask.height) for (xx in 0 until mask.width) if (mask.pixels[yy * mask.width + xx].toInt() != 0) {
                low = min(low, xx); high = max(high, xx)
            }
            return (low + high) * .5f
        }
        if (abs(center(rankMask) - center(suitMask)) > width * .18f) return null
        val rank = classify(rankMask, library.ranks) ?: return null
        val suit = classifySuit(suitMask) ?: return null
        val card = Card.parse(rank.label + suit.label) ?: return null
        // Both corner marks must agree with the standard red/black suit. A
        // black heart/diamond or red club/spade is not accepted as this deck.
        fun colorAgrees(mask: Mask): Boolean {
            val total = mask.red + mask.dark
            if (total < 8) return false
            return if (card.red) mask.red >= total * .88f else mask.dark >= total * .94f
        }
        if (!colorAgrees(rankMask) || !colorAgrees(suitMask)) return null
        return card to min(rank.score, suit.score)
    }

    /**
     * Alternative supported artwork: the rank is at the upper-left and a
     * small suit is at the upper-right, with a large decorative suit below.
     * Only isolated index glyphs become evidence; the large art is ignored.
     * On a positively complete face, an upright lower-right index may resolve
     * an ambiguous upper rank only when both independently agree on its label.
     */
    private fun recognizeSeparatedCorners(bitmap: Bitmap, x: Int, y: Int, width: Int, height: Int,
        fullFaceVisible: Boolean): Pair<Card, Float>? {
        if (!ready || width < 32 || height < 44 || x < 0 || y < 0 ||
            x + width > bitmap.width || y + height > bitmap.height) return null
        fun corner(leftFraction: Float, rightFraction: Float,
            topFraction: Float = .02f, bottomFraction: Float = .28f): Mask? {
            val left = x + (width * leftFraction).roundToInt()
            val right = x + (width * rightFraction).roundToInt()
            val top = y + (height * topFraction).roundToInt()
            val bottom = y + (height * bottomFraction).roundToInt()
            val w = right - left; val h = bottom - top
            if (w < 9 || h < 12 || left < 0 || top < 0 || right > bitmap.width || bottom > bitmap.height) return null
            val pixels = ByteArray(w * h)
            var pale = 0; var red = 0; var dark = 0; var other = 0
            for (yy in 0 until h) for (xx in 0 until w) {
                val color = FramePixels.get(bitmap, left + xx, top + yy)
                val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
                val isRed = r > 95 && r > g * 1.4f && r > b * 1.4f
                val isDark = maxOf(r,g,b) < 175 && maxOf(r,g,b) - minOf(r,g,b) < 75
                if (minOf(r,g,b) >= 175 && maxOf(r,g,b) - minOf(r,g,b) < 60) pale++
                if (isRed || isDark) {
                    pixels[yy * w + xx] = 1
                    if (isRed) red++ else dark++
                } else if (maxOf(r,g,b) - minOf(r,g,b) > 90) other++
            }
            if (pale < w * h * .62f || other > w * h * .06f || red + dark < 10) return null
            return Mask(w, h, pixels, red, dark)
        }
        val rankMask = corner(.045f, .38f) ?: return null
        val suitMask = corner(.63f, .965f) ?: return null
        val upper = ranked(rankMask, library.ranks) ?: return null
        val directRank = upper.strict()
        if (directRank == null && (!fullFaceVisible || upper.best.score < .90f || upper.best.label !in Card.RANKS)) return null
        val suit = classifySuit(suitMask) ?: return null
        var lowerMask: Mask? = null
        val rank = directRank ?: run {
            // This deck prints the lower-right rank upright. Never rotate it
            // speculatively: rotating its visible 6 would manufacture a 9.
            val bottom = corner(.60f, .95f, .70f, .96f) ?: return null
            val lower = classify(bottom, library.ranks) ?: return null
            if (lower.label != upper.best.label) return null
            lowerMask = bottom
            lower
        }
        val card = Card.parse(rank.label + suit.label) ?: return null
        fun agrees(mask: Mask): Boolean {
            val ink = mask.red + mask.dark
            if (ink < 10) return false
            return if (card.red) mask.red >= ink * .88f else mask.dark >= ink * .94f
        }
        if (!agrees(rankMask) || !agrees(suitMask) || lowerMask?.let { !agrees(it) } == true) return null
        return card to minOf(rank.score, suit.score, upper.best.score)
    }

    /** A centered suit icon, including an icon on a colored empty-slot fill. */
    fun recognizeSuit(bitmap: Bitmap, x: Int, y: Int, width: Int, height: Int): Pair<Suit, Float>? {
        if (!ready) return null
        val mask = symbolMask(bitmap, x, y, width, height) ?: return null
        val match = classifySuit(mask) ?: return null
        val suit = match.label.singleOrNull()?.let { Suit.fromChar(it) } ?: return null
        if (mask.red > (mask.red + mask.dark) * .8f && !suit.red) return null
        return suit to match.score
    }

    /** Positive circular-arrow affordance; a blank or ordinary circle is unknown. */
    fun recognizeRecycle(bitmap: Bitmap, x: Int, y: Int, width: Int, height: Int): Float? {
        if (!ready) return null
        if (isBusyStock(bitmap, x, y, width, height)) return null
        val standard = symbolMask(bitmap, x, y, width, height)?.let { classify(it, library.recycle) }
        if (standard?.label == "RECYCLE") return standard.score
        // The real reset arrow is low-contrast green on a vertically shaded
        // green tile. Subtract the same row's background, rather than mixing
        // the tile gradient with the icon or imposing a pale-corner rule.
        for (mask in greenResetMasks(bitmap, x, y, width, height)) {
            val match = classify(mask, library.recycle)
            if (match?.label == "RECYCLE") return match.score
        }
        return null
    }

    /** Gold rotating rings in the supplied game are network/animation BUSY
     * indicators. They appear on both full and empty stock and are NEVER a
     * recycle button. A ring stays busy regardless of its rotation angle. */
    fun isBusyStock(bitmap: Bitmap, x: Int, y: Int, width: Int, height: Int): Boolean {
        val mask = goldSpinnerMask(bitmap, x, y, width, height) ?: return false
        var left = width; var right = -1; var top = height; var bottom = -1
        for (yy in 0 until height) for (xx in 0 until width) if (mask.pixels[yy * width + xx].toInt() != 0) {
            left = min(left, xx); right = max(right, xx); top = min(top, yy); bottom = max(bottom, yy)
        }
        val w = right - left + 1; val h = bottom - top + 1
        if (w < width * .38f || h < height * .22f || w > width * .93f || h > height * .82f ||
            w < h * .55f || w > h * 1.8f) return false
        val cx = (left + right) * .5f; val cy = (top + bottom) * .5f
        val occupied = BooleanArray(12)
        var center = 0; var ink = 0; var ring = 0
        for (yy in top..bottom) for (xx in left..right) if (mask.pixels[yy * width + xx].toInt() != 0) {
            val dx = (xx - cx) / w; val dy = (yy - cy) / h
            val radius = dx * dx + dy * dy
            ink++
            if (radius < .045f) center++
            if (radius in .07f.. .42f) ring++
            val angle = Math.atan2(dy.toDouble(), dx.toDouble()) + Math.PI
            occupied[(angle / (2.0 * Math.PI) * 12).toInt().coerceIn(0, 11)] = true
        }
        return ink >= 16 && center <= ink * .08f && ring >= ink * .78f && occupied.count { it } >= 8
    }

    private fun goldSpinnerMask(bitmap: Bitmap, x: Int, y: Int, width: Int, height: Int): Mask? {
        if (width < 24 || height < 34 || x < 0 || y < 0 ||
            x + width > bitmap.width || y + height > bitmap.height) return null
        val pixels = ByteArray(width * height)
        var ink = 0
        val insetX = max(2, width / 25); val insetY = max(2, height / 25)
        for (yy in insetY until height - insetY) for (xx in insetX until width - insetX) {
            val color = FramePixels.get(bitmap, x + xx, y + yy)
            val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
            if (r >= 110 && g >= 90 && r >= g * .88f && g - b >= 25 && r - b >= 25) {
                pixels[yy * width + xx] = 1; ink++
            }
        }
        if (ink < max(16, width * height / 60) || ink > width * height * .28f) return null
        return Mask(width, height, pixels)
    }

    private fun greenResetMasks(bitmap: Bitmap, x: Int, y: Int, width: Int, height: Int): List<Mask> {
        if (width < 24 || height < 34 || x < 0 || y < 0 ||
            x + width > bitmap.width || y + height > bitmap.height) return emptyList()
        val insetX = max(2, (width * .14f).roundToInt())
        val insetY = max(2, (height * .14f).roundToInt())
        val w = width - 2 * insetX; val h = height - 2 * insetY
        val difference = FloatArray(w * h)
        var greenRows = 0; var peak = 0f
        for (yy in 0 until h) {
            val left = FramePixels.get(bitmap, x + insetX, y + insetY + yy)
            val right = FramePixels.get(bitmap, x + insetX + w - 1, y + insetY + yy)
            if (Color.green(left) >= Color.blue(left) + 12 && Color.green(left) >= Color.red(left) + 20 &&
                Color.green(right) >= Color.blue(right) + 12 && Color.green(right) >= Color.red(right) + 20) greenRows++
            for (xx in 0 until w) {
                val fraction = xx.toFloat() / (w - 1)
                val pixel = FramePixels.get(bitmap, x + insetX + xx, y + insetY + yy)
                val dr = Color.red(pixel) - (Color.red(left) + (Color.red(right) - Color.red(left)) * fraction)
                val dg = Color.green(pixel) - (Color.green(left) + (Color.green(right) - Color.green(left)) * fraction)
                val db = Color.blue(pixel) - (Color.blue(left) + (Color.blue(right) - Color.blue(left)) * fraction)
                val d = dr + dg + db
                difference[yy * w + xx] = d
                peak = max(peak, d)
            }
        }
        if (greenRows < h * .55f || peak < 28) return emptyList()
        return listOf(.22f, .30f, .40f).mapNotNull { fraction ->
            val threshold = max(12f, peak * fraction)
            val pixels = ByteArray(w * h) { if (difference[it] >= threshold) 1 else 0 }
            val ink = pixels.count { it.toInt() != 0 }
            if (ink < 12 || ink > w * h * .45f) null else Mask(w, h, pixels)
        }
    }

    private fun symbolMask(bitmap: Bitmap, x: Int, y: Int, width: Int, height: Int): Mask? {
        if (width < 16 || height < 16 || x < 0 || y < 0 ||
            x + width > bitmap.width || y + height > bitmap.height) return null
        val insetX = max(2, (width * .16f).roundToInt())
        val insetY = max(2, (height * .16f).roundToInt())
        val w = width - insetX * 2; val h = height - insetY * 2
        val left = x + insetX; val top = y + insetY
        val samples = mutableListOf<Int>()
        for (yy in listOf(0, 1, h - 2, h - 1)) for (xx in listOf(0, 1, w - 2, w - 1))
            samples += FramePixels.get(bitmap, left + xx, top + yy)
        fun median(channel: (Int) -> Int) = samples.map(channel).sorted()[samples.size / 2]
        val r0 = median(Color::red); val g0 = median(Color::green); val b0 = median(Color::blue)
        if (samples.count { abs(Color.red(it) - r0) + abs(Color.green(it) - g0) + abs(Color.blue(it) - b0) > 100 } > 3)
            return null // The presumed background itself is occluded/nonuniform.
        var peakDifference = 0
        for (yy in 0 until h) for (xx in 0 until w) {
            val c = FramePixels.get(bitmap, left + xx, top + yy)
            peakDifference = max(peakDifference, abs(Color.red(c) - r0) + abs(Color.green(c) - g0) + abs(Color.blue(c) - b0))
        }
        // Some supported games draw foundation placeholders as translucent
        // green silhouettes on green felt. Their contrast peaks around 150
        // RGB-sum units, so the former hard 160 gate rejected all four suits.
        // Keep the original high-contrast threshold for ordinary symbols, and
        // use a stricter *shape* match for low-contrast silhouettes below.
        if (peakDifference < 42) return null
        val inkThreshold = if (peakDifference < 220)
            max(52f, peakDifference * .34f)
        else max(100f, peakDifference * .36f)
        val raw = ByteArray(w * h)
        var ink = 0; var red = 0; var dark = 0
        for (yy in 0 until h) for (xx in 0 until w) {
            val c = FramePixels.get(bitmap, left + xx, top + yy)
            val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
            if (abs(r - r0) + abs(g - g0) + abs(b - b0) >= inkThreshold) {
                raw[yy * w + xx] = 1; ink++
                if (r > 95 && r > g * 1.4f && r > b * 1.4f) red++ else dark++
            }
        }
        if (ink < 10 || ink > w * h * .58f) return null
        return Mask(w, h, raw, red, dark)
    }

    private fun cropMask(bitmap: Bitmap, left: Int, top: Int, width: Int, source: ByteArray, band: Band): Mask {
        val height = band.bottom - band.top
        val pixels = ByteArray(width * height)
        var red = 0; var dark = 0
        for (yy in 0 until height) for (xx in 0 until width) {
            val v = source[(yy + band.top) * width + xx]
            pixels[yy * width + xx] = v
            if (v.toInt() == 0) continue
            val c = FramePixels.get(bitmap, left + xx, top + band.top + yy)
            val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
            if (r > 95 && r > g * 1.4f && r > b * 1.4f) red++ else dark++
        }
        return Mask(width, height, pixels, red, dark)
    }

    private fun bands(mask: Mask, mergeGap: Int): List<Band> {
        val rows = BooleanArray(mask.height) { y ->
            (0 until mask.width).count { x -> mask.pixels[y * mask.width + x].toInt() != 0 } >= 1
        }
        val result = mutableListOf<Band>()
        var start = -1; var last = -1
        for (y in rows.indices) {
            if (rows[y]) {
                if (start < 0) start = y
                last = y
            } else if (start >= 0 && y - last > mergeGap) {
                result += Band(start, last + 1); start = -1
            }
        }
        if (start >= 0) result += Band(start, last + 1)
        return result
    }

    private fun classifySuit(mask: Mask): Match? = classify(mask, library.suits)
        ?: fillEnclosedOutline(mask)?.let { classify(it, library.suits) }

    /** Fill only fully enclosed holes, preserving the observed outer silhouette.
     * This supports hollow foundation icons without relaxing a similarity gate.
     * Plain circles/triangles remain explicit non-suit distractors after filling.
     */
    private fun fillEnclosedOutline(mask: Mask): Mask? {
        val outside = BooleanArray(mask.pixels.size)
        val queue = IntArray(mask.pixels.size)
        var head = 0; var tail = 0
        fun visit(x: Int, y: Int) {
            if (x !in 0 until mask.width || y !in 0 until mask.height) return
            val i = y * mask.width + x
            if (!outside[i] && mask.pixels[i].toInt() == 0) {
                outside[i] = true; queue[tail++] = i
            }
        }
        for (x in 0 until mask.width) { visit(x, 0); visit(x, mask.height - 1) }
        for (y in 0 until mask.height) { visit(0, y); visit(mask.width - 1, y) }
        while (head < tail) {
            val i = queue[head++]; val x = i % mask.width; val y = i / mask.width
            visit(x - 1, y); visit(x + 1, y); visit(x, y - 1); visit(x, y + 1)
        }
        val filled = mask.pixels.copyOf()
        var added = 0; var original = 0
        for (i in filled.indices) {
            if (filled[i].toInt() != 0) original++
            else if (!outside[i]) { filled[i] = 1; added++ }
        }
        return if (added >= max(5, original / 5)) mask.copy(pixels = filled) else null
    }

    private fun classify(mask: Mask, patterns: List<Pattern>): Match? = ranked(mask, patterns)?.strict()

    @Synchronized private fun ranked(mask: Mask, patterns: List<Pattern>): RankedMatch? {
        val normalized = normalize(mask) ?: return null
        val target = pack(normalized)
        val group = when {
            patterns === library.ranks -> 0
            patterns === library.suits -> 1
            else -> 2
        }
        val key = MaskKey(group, target)
        if (maskCache.containsKey(key)) return maskCache[key]
        val count = target.sumOf { java.lang.Long.bitCount(it) }
        val dilated = pack(dilate(normalized))
        val scores = mutableMapOf<String, Float>()
        for (pattern in patterns) {
            var overlap = 0; var near = 0; var reverseNear = 0
            for (i in target.indices) {
                overlap += java.lang.Long.bitCount(target[i] and pattern.bits[i])
                near += java.lang.Long.bitCount(dilated[i] and pattern.bits[i])
                reverseNear += java.lang.Long.bitCount(target[i] and pattern.dilated[i])
            }
            val exact = overlap.toFloat() / (count + pattern.count - overlap).coerceAtLeast(1)
            // A one-normalized-pixel tolerance helps antialiasing, but the
            // actual overlap dominates and cannot be replaced by proximity.
            val proximity = .5f * (near.toFloat() / pattern.count + reverseNear.toFloat() / count)
            val score = .55f * exact + .45f * proximity
            if (score > (scores[pattern.label] ?: 0f)) scores[pattern.label] = score
        }
        val ranked = scores.entries.sortedByDescending { it.value }
        val best = ranked.firstOrNull() ?: return null
        val runnerUp = ranked.getOrNull(1)?.value ?: 0f
        val match = RankedMatch(Match(best.key, best.value), runnerUp)
        maskCache[key] = match
        return match
    }

    private fun normalize(mask: Mask): ByteArray? {
        var minX = mask.width; var minY = mask.height; var maxX = -1; var maxY = -1; var ink = 0
        for (y in 0 until mask.height) for (x in 0 until mask.width) if (mask.pixels[y * mask.width + x].toInt() != 0) {
            minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y); ink++
        }
        val w = maxX - minX + 1; val h = maxY - minY + 1
        if (w < 3 || h < 5 || ink < 8 || ink > w * h * .94f || w > h * 2.4f) return null
        // Ink touching a lateral crop boundary can be a truncated symbol.
        if (minX == 0 || maxX == mask.width - 1) return null
        val scale = min(20f / w, 20f / h)
        val sw = max(1, (w * scale).roundToInt()); val sh = max(1, (h * scale).roundToInt())
        val out = ByteArray(24 * 24)
        val padX = (24 - sw) / 2; val padY = (24 - sh) / 2
        for (yy in 0 until sh) for (xx in 0 until sw)
            out[(yy + padY) * 24 + xx + padX] = mask.pixels[(minY + yy * h / sh) * mask.width + minX + xx * w / sw]
        return out
    }

    private fun dilate(pixels: ByteArray): ByteArray {
        val out = ByteArray(pixels.size)
        for (y in 1 until 23) for (x in 1 until 23) if (pixels[y * 24 + x].toInt() != 0)
            for (dy in -1..1) for (dx in -1..1) if (abs(dx) + abs(dy) <= 1) out[(y + dy) * 24 + x + dx] = 1
        return out
    }

    private fun pack(pixels: ByteArray): LongArray {
        val out = LongArray(9)
        for (i in pixels.indices) if (pixels[i].toInt() != 0) out[i / 64] = out[i / 64] or (1L shl (i % 64))
        return out
    }

    companion object {
        @Volatile private var cached: Library? = null
        private fun load(context: Context): Library = cached ?: synchronized(this) {
            cached ?: run {
                val ranks = mutableListOf<Pattern>(); val suits = mutableListOf<Pattern>(); val recycle = mutableListOf<Pattern>()
                try {
                    context.assets.open("offline_card_glyphs.txt").bufferedReader().useLines { lines ->
                        lines.filter { !it.startsWith("#") && it.isNotBlank() }.forEach { line ->
                            val parts = line.split('|')
                            require(parts.size == 3)
                            val bytes = Base64.decode(parts[2], Base64.NO_WRAP)
                            require(bytes.size == 72)
                            val bits = LongArray(9)
                            for (i in 0 until 576) if ((bytes[i / 8].toInt() and (1 shl (7 - i % 8))) != 0)
                                bits[i / 64] = bits[i / 64] or (1L shl (i % 64))
                            val expanded = LongArray(9)
                            for (i in 0 until 576) if ((bits[i / 64] and (1L shl (i % 64))) != 0L) {
                                val y = i / 24; val x = i % 24
                                for (dy in -1..1) for (dx in -1..1) if (abs(dx) + abs(dy) <= 1 &&
                                    x + dx in 0..23 && y + dy in 0..23) {
                                    val j = (y + dy) * 24 + x + dx
                                    expanded[j / 64] = expanded[j / 64] or (1L shl (j % 64))
                                }
                            }
                            val pattern = Pattern(parts[1], bits, bits.sumOf { java.lang.Long.bitCount(it) }, expanded)
                            require(pattern.count in 8..550)
                            if (parts[0] == "R" && parts[1] in Card.RANKS) ranks += pattern
                            else if (parts[0] == "S" && parts[1] in setOf("C", "D", "H", "S")) suits += pattern
                            else if (parts[0] == "X" && parts[1].startsWith("?")) ranks += pattern
                            else if (parts[0] == "Y" && parts[1].startsWith("?")) { suits += pattern; recycle += pattern }
                            else if (parts[0] == "U" && parts[1] == "RECYCLE") recycle += pattern
                            else error("Invalid local glyph label")
                        }
                    }
                } catch (_: Exception) {
                    ranks.clear(); suits.clear(); recycle.clear()
                }
                Library(ranks.toList(), suits.toList(), recycle.toList()).also { cached = it }
            }
        }
    }
}
