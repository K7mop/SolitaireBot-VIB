package com.retq.solitairebot.capture

import android.graphics.Bitmap
import android.graphics.Color
import com.retq.solitairebot.core.Suit
import com.retq.solitairebot.vision.GlyphTemplates
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Image-derived setup for a portrait Klondike table with four foundation cells
 * on the left and stock at the far right. No preset coordinates or card labels
 * become evidence. A first deal must show seven visible card faces. Overlapping
 * blue backs may connect to a face's white border: component bottoms, not a
 * rigid full-height white band, determine the exposed face in each column.
 * Column validation deliberately does not assume monotonically increasing
 * bottom edges; real supported layouts can contain multi-card face-up runs.
 * Unsupported layouts remain uncalibrated.
 */
object AutoBoardSetup {
    data class Detection(val layout: NormalizedBoardLayout?, val message: String,
        val columnsFound: Int = 0, val foundationSuitsFound: Int = 0)
    private data class Run(val left: Int, val right: Int, val y: Int) {
        val width get() = right - left
        val center get() = (left + right) / 2f
    }
    private data class Cluster(val center: Float, val width: Float, val count: Int)
    private data class FaceBand(val top: Int, val bottom: Int) { val height get() = bottom - top }
    data class Geometry(val columns: List<PixelBox>, val width: Int, val height: Int,
        val tableauTop: Int, val gap: Int)

    fun detect(bitmap: Bitmap, templates: GlyphTemplates? = null): Detection {
        val geometry = findGeometry(bitmap)
            ?: return Detection(null, "تعذّر تثبيت الأعمدة السبعة. أخفِ نافذة المساعد وأظهر صف الأوراق كاملًا ثم أعد اللقطة")
        if (templates == null || !templates.automaticRecognitionReady)
            return Detection(null, "تعذّر تحميل قوالب القراءة المحلية", 7)
        val w = bitmap.width; val h = bitmap.height
        val cw = geometry.width; val ch = geometry.height
        val canonical = listOf(Suit.CLUBS, Suit.SPADES, Suit.HEARTS, Suit.DIAMONDS)
        // The first row is searched in the image, never assumed to be at a fixed screen Y.
        val start = max(0, geometry.tableauTop - (ch * 1.85f).toInt())
        val end = geometry.tableauTop - ch - max(2, ch / 16)
        if (end <= start) return Detection(null, "لا توجد مساحة صف علوي واضحة فوق الأعمدة", 7)
        var best: Pair<Float, List<PixelBox>>? = null
        var bestY = 0
        var maxSuits = 0
        for (y in start..end) {
            val edgeStrength = rectangleEdgeStrength(bitmap, geometry.columns[6].left, y, cw, ch)
            // On this deck the stock back is blue with a pale outline, while
            // the card's bottom edge fades into dark felt. Requiring both top
            // and bottom horizontal edges to be bright rejected the real card.
            // A distinct occupied stock center is still positive image evidence.
            val stockContrast = stockInteriorContrast(bitmap, geometry.columns[6].left, y, cw, ch)
            if (edgeStrength < 18f && stockContrast < 46f) continue
            val mapped = mutableMapOf<Suit, PixelBox>()
            var confidence = 1f
            var valid = true
            for (i in 0..3) {
                val col = geometry.columns[i]
                val box = PixelBox(col.left, y, col.left + cw, y + ch)
                val card = templates.recognizeAutomatic(bitmap, box.left, box.top, cw, ch)
                val suit = card?.let { it.first.suit to it.second }
                    ?: templates.recognizeSuitAutomatic(bitmap, box.left, box.top, cw, ch)
                if (suit == null || suit.first in mapped) { valid = false; continue }
                mapped[suit.first] = box
                confidence = min(confidence, suit.second)
            }
            maxSuits = max(maxSuits, mapped.size)
            val score = confidence + min(edgeStrength, 180f) / 1800f +
                min(stockContrast, 120f) / 12000f
            if (valid && mapped.size == 4 && (best == null || score > best!!.first)) {
                best = score to canonical.map { mapped.getValue(it) }; bestY = y
            }
        }
        if (best == null) return Detection(null,
            "قُرئت الأعمدة لكن لم تُعرف رموز خانات الأساس الأربع بوضوح؛ لم تُعتمد معايرة تخمينية", 7, maxSuits)
        val stock = PixelBox(geometry.columns[6].left, bestY, geometry.columns[6].left + cw, bestY + ch)
        // Waste fan spans the free space between foundation four and the stock.
        val waste = PixelBox(geometry.columns[4].left, bestY, stock.left - geometry.gap, bestY + ch)
        if (waste.right - waste.left < cw) return Detection(null, "منطقة السحب أضيق من ورقة واحدة", 7, 4)
        fun normalized(b: PixelBox) = NormalizedBox(b.left.toFloat() / w, b.top.toFloat() / h,
            b.right.toFloat() / w, b.bottom.toFloat() / h)
        val boardBottom = findBoardBottom(bitmap, geometry)
        if (geometry.columns.any { it.bottom > boardBottom })
            return Detection(null, "الأوراق السفلية قريبة من حافة الصورة؛ التقط اللوحة كاملة", 7, 4)
        return Detection(NormalizedBoardLayout(normalized(stock), normalized(waste),
            best!!.second.map(::normalized), geometry.columns.map {
                normalized(PixelBox(it.left, geometry.tableauTop, it.left + cw, boardBottom))
            }), "حُددت المناطق الـ 13 من الصورة وعُرف ترتيب رموز الأساسات تلقائيًا", 7, 4)
    }

    /** Geometric prerequisite exposed to regression tests; it never authorizes touches. */
    fun findGeometry(bitmap: Bitmap): Geometry? =
        componentGeometry(bitmap) ?: legacyGeometry(bitmap)

    /** Retain the established fast path for older supported decks. */
    private fun legacyGeometry(bitmap: Bitmap): Geometry? {
        val w = bitmap.width; val h = bitmap.height
        if (w < 280 || h < 480 || h <= w || h.toFloat() / w !in 1.25f..2.6f) return null
        val sx = max(1, w / 700); val sy = max(1, h / 1000)
        val minWidth = (w * .088f).toInt(); val maxWidth = (w * .145f).toInt()
        val runs = mutableListOf<Run>()
        for (y in (h * .12f).toInt() until (h * .90f).toInt() step sy) {
            var left = -1
            for (x in 0 until w step sx) {
                if (pale(bitmap.getPixel(x, y))) { if (left < 0) left = x }
                else if (left >= 0) {
                    if (x - left in minWidth..maxWidth) runs += Run(left, x, y)
                    left = -1
                }
            }
            if (left >= 0 && w - left in minWidth..maxWidth) runs += Run(left, w, y)
        }
        if (runs.size < 21) return null
        val bucket = max(2, w / 180)
        val clusters = runs.groupBy { (it.center / bucket).toInt() }.values
            .filter { it.size >= 3 }.map {
                Cluster(it.map(Run::center).sorted()[it.size / 2],
                    it.map(Run::width).sorted()[it.size / 2].toFloat(), it.size)
            }.sortedBy { it.center }
        var selected: List<Cluster>? = null; var bestScore = -1
        for (first in clusters.filter { it.center < w * .17f }) {
            for (last in clusters.filter { it.center > w * .83f }) {
                val pitch = (last.center - first.center) / 6f
                if (pitch !in w * .12f..w * .155f) continue
                val grid = (0..6).map { index ->
                    val center = first.center + index * pitch
                    clusters.minByOrNull { abs(it.center - center) }!!
                }
                if (grid.toSet().size != 7 || grid.withIndex().any { (i, c) -> abs(c.center - first.center - i * pitch) > pitch * .055f }) continue
                val cw = grid.map { it.width }.sorted()[3]
                if (cw / pitch !in .76f.. .985f || grid.any { abs(it.width - cw) > cw * .10f }) continue
                val score = grid.sumOf { min(it.count, 160) }
                if (score > bestScore) { selected = grid; bestScore = score }
            }
        }
        val grid = selected ?: return null
        val cardWidth = grid.map { it.width.toInt() }.sorted()[3] + sx
        val faces = grid.map { c ->
            val left = (c.center - cardWidth / 2f).toInt().coerceAtLeast(0)
            edgeBands(bitmap, left, cardWidth).filter { band ->
                band.height.toFloat() / cardWidth in 1.12f..1.65f && band.top > h * .20f
            }.lastOrNull() ?: return null
        }
        val cardHeight = faces.map { it.height }.sorted()[3]
        if (faces.any { abs(it.height - cardHeight) > cardHeight * .08f }) return null
        val top = faces.minOf { it.top }
        if (faces.maxOf { it.top } - top > cardHeight * 1.6f) return null
        // A modal white rectangle spanning several columns is not a dealt board.
        val columns = grid.mapIndexed { i, c ->
            val left = (c.center - cardWidth / 2f).toInt()
            if (left < 0 || left + cardWidth > w) return null
            PixelBox(left, faces[i].top, left + cardWidth, faces[i].bottom)
        }
        val pitch = (grid.last().center - grid.first().center) / 6f
        val gap = (pitch - cardWidth).toInt().coerceAtLeast(1)
        for (i in 0..5) {
            val mid = (columns[i].right + columns[i + 1].left) / 2
            var paleGap = 0; var count = 0
            for (y in top until min(h, faces.maxOf { it.bottom }) step max(1, cardHeight / 25)) {
                count++; if (pale(bitmap.getPixel(mid, y))) paleGap++
            }
            if (paleGap > count * .35f) return null
        }
        return Geometry(columns, cardWidth, cardHeight, top, gap)
    }

    /**
     * Fallback for real decks where the white border of an exposed face joins
     * the pale outlines of overlapping face-down cards above it. Detect white
     * connected components, require seven regularly spaced cards with matching
     * widths, and recover the visible face's top from its bottom edge. No card
     * identity or empty slot is inferred from the geometric fit alone.
     *
     * Sampling at most ~600 x 1300 positions keeps the first-frame scan bounded.
     */
    private data class LightIsland(val left: Int, val top: Int, val right: Int,
        val bottom: Int, val area: Int) {
        val width get() = right - left
        val height get() = bottom - top
        val center get() = (left + right) / 2f
    }

    private fun componentGeometry(bitmap: Bitmap): Geometry? {
        val w = bitmap.width; val h = bitmap.height
        if (w < 280 || h < 480 || h <= w || h.toFloat() / w !in 1.25f..2.6f) return null
        val step = max(1, max(w / 520, h / 1200))
        val sw = (w + step - 1) / step; val sh = (h + step - 1) / step
        val mask = ByteArray(sw * sh)
        val startY = (h * .19f).toInt() / step
        val endY = min(sh, (h * .90f).toInt() / step)
        for (y in startY until endY) for (x in 0 until sw) {
            if (pale(bitmap.getPixel(min(w - 1, x * step), min(h - 1, y * step))))
                mask[y * sw + x] = 1
        }
        val queue = IntArray(mask.size)
        val islands = mutableListOf<LightIsland>()
        val outlinedStock = mutableListOf<LightIsland>()
        for (seedY in startY until endY) for (seedX in 0 until sw) {
            val seed = seedY * sw + seedX
            if (mask[seed].toInt() == 0) continue
            var head = 0; var tail = 1
            queue[0] = seed; mask[seed] = 0
            var minX = seedX; var maxX = seedX; var minY = seedY; var maxY = seedY
            while (head < tail) {
                val position = queue[head++]
                val x = position % sw; val y = position / sw
                minX = min(minX, x); maxX = max(maxX, x)
                minY = min(minY, y); maxY = max(maxY, y)
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val xx = x + dx; val yy = y + dy
                    if (xx !in 0 until sw || yy !in startY until endY) continue
                    val next = yy * sw + xx
                    if (mask[next].toInt() == 0) continue
                    mask[next] = 0; queue[tail++] = next
                }
            }
            val left = minX * step; val right = min(w, (maxX + 1) * step)
            val top = minY * step; val bottom = min(h, (maxY + 1) * step)
            val cw = right - left; val ch = bottom - top
            if (cw.toFloat() !in w * .085f..w * .15f ||
                ch.toFloat() / cw !in 1.10f..2.65f) continue
            val island = LightIsland(left, top, right, bottom, tail * step * step)
            val density = tail * step * step.toFloat() / (cw * ch)
            // A patterned stock back often has only a thin pale border. Its
            // aspect gives an independent card-height check even when blue
            // back outlines join the white faces in multiple columns.
            if (density in .025f.. .24f && ch.toFloat() / cw in 1.15f..1.75f)
                outlinedStock += island
            if (density < .28f || bottom < h * .39f || top > h * .78f) continue
            islands += island
        }
        if (islands.size < 7) return null
        val sorted = islands.sortedBy { it.center }
        var best: List<LightIsland>? = null
        var bestScore = Float.NEGATIVE_INFINITY
        for (first in sorted.filter { it.center < w * .18f }) {
            for (last in sorted.filter { it.center > w * .82f }) {
                val pitch = (last.center - first.center) / 6f
                if (pitch !in w * .12f..w * .155f) continue
                val cards = (0..6).map { index ->
                    val expected = first.center + index * pitch
                    sorted.filter { abs(it.center - expected) <= pitch * .105f &&
                        abs(it.width - first.width) <= first.width * .17f }
                        .maxByOrNull { it.bottom }
                }
                if (cards.any { it == null }) continue
                val grid = cards.filterNotNull()
                if (grid.toSet().size != 7) continue
                val widths = grid.map { it.width }.sorted()
                val cardWidth = widths[3]
                if (cardWidth / pitch !in .76f.. .99f ||
                    grid.any { abs(it.width - cardWidth) > cardWidth * .17f }) continue
                val heights = grid.map { it.height }.sorted()
                val cardHeight = heights[3]
                if (grid.any { abs(it.height - cardHeight) > cardHeight * .68f }) continue
                // Do not assume exposed-face bottoms increase monotonically
                // from column 1 to column 7. Some supported Klondike skins show
                // multi-card face-up runs, uneven overlap, or a partially covered
                // first column. Validate the seven-card grid by shared pitch,
                // width, height, and bounded vertical spread instead. The former
                // monotonic-bottom test rejected valid Battle Arena boards where
                // column 1 extends below column 2.
                if (grid.maxOf { it.bottom } - grid.minOf { it.bottom } > cardHeight * 1.5f) continue
                val score = grid.sumOf { it.area.toDouble() }.toFloat() -
                    grid.sumOf { abs(it.center - (first.center + grid.indexOf(it) * pitch)).toDouble() }.toFloat() * cardWidth
                if (score > bestScore) { best = grid; bestScore = score }
            }
        }
        val grid = best ?: return null
        val cardWidth = grid.map { it.width }.sorted()[3] + 2 * step
        val apparentHeight = grid.map { it.height }.sorted()[3] + 2 * step
        val firstFaceTop = grid.minOf { it.top }
        val stockHeight = outlinedStock.filter {
            abs(it.center - grid.last().center) <= cardWidth * .10f &&
                abs(it.width - cardWidth) <= cardWidth * .13f &&
                it.bottom < firstFaceTop - cardWidth * .35f
        }.maxByOrNull { it.area }?.height
        val cardHeight = if (stockHeight != null &&
            apparentHeight > stockHeight * 1.18f) stockHeight else apparentHeight
        if (cardHeight.toFloat() / cardWidth !in 1.17f..1.75f) return null
        val columns = grid.map { island ->
            val left = (island.center - cardWidth / 2f).toInt()
            val bottom = min(h, island.bottom + step)
            val top = bottom - cardHeight
            if (left < 0 || left + cardWidth > w || top < 0) return null
            PixelBox(left, top, left + cardWidth, bottom)
        }
        val top = columns.minOf { it.top }
        val pitch = (grid.last().center - grid.first().center) / 6f
        val gap = (pitch - cardWidth).toInt().coerceAtLeast(1)
        if (columns.maxOf { it.top } - top > cardHeight * 1.5f) return null
        // White modals bridging card gutters must not be treated as a game.
        for (i in 0..5) {
            val x = (columns[i].right + columns[i + 1].left) / 2
            var paleCount = 0; var samples = 0
            for (y in top until columns.maxOf { it.bottom } step max(1, cardHeight / 25)) {
                if (pale(bitmap.getPixel(x, y))) paleCount++
                samples++
            }
            if (paleCount > samples * .35f) return null
        }
        return Geometry(columns, cardWidth, cardHeight, top, gap)
    }

    private fun edgeBands(bitmap: Bitmap, left: Int, width: Int): List<FaceBand> {
        val result = mutableListOf<FaceBand>()
        var start = -1; var last = -1
        val tolerance = max(2, width / 30)
        fun row(y: Int): Boolean = floatArrayOf(.06f, .92f).all { fraction ->
            val x = (left + width * fraction).toInt().coerceIn(0, bitmap.width - 1)
            pale(bitmap.getPixel(x, y))
        }
        for (y in (bitmap.height * .12f).toInt() until (bitmap.height * .92f).toInt()) {
            if (row(y)) { if (start < 0) start = y; last = y }
            else if (start >= 0 && y - last > tolerance) {
                result += FaceBand((start - 1).coerceAtLeast(0), last + 2); start = -1
            }
        }
        if (start >= 0) result += FaceBand((start - 1).coerceAtLeast(0), last + 2)
        return result
    }

    private fun findBoardBottom(bitmap: Bitmap, geometry: Geometry): Int {
        val limit = min(bitmap.height - max(8, geometry.height / 5), (bitmap.height * .94f).toInt())
        val scanStart = geometry.columns.maxOf { it.bottom } + geometry.height / 8
        if (scanStart >= limit) return limit
        val samples = (0..5).map { i ->
            val x = (geometry.columns[i].right + geometry.columns[i + 1].left) / 2
            bitmap.getPixel(x, scanStart)
        }
        fun median(channel: (Int) -> Int) = samples.map(channel).sorted()[samples.size / 2]
        val r = median(Color::red); val g = median(Color::green); val b = median(Color::blue)
        var streakStart = -1
        val step = max(1, geometry.height / 50)
        for (y in scanStart until limit step step) {
            var obstructed = 0
            for (col in geometry.columns) for (fraction in floatArrayOf(.20f, .40f, .60f, .80f)) {
                val c = bitmap.getPixel((col.left + geometry.width * fraction).toInt(), y)
                if (maxOf(abs(Color.red(c) - r), abs(Color.green(c) - g), abs(Color.blue(c) - b)) > 48) obstructed++
            }
            if (obstructed >= 14) {
                if (streakStart < 0) streakStart = y
                if (y - streakStart >= max(6, geometry.height / 15)) return (streakStart - step).coerceAtLeast(scanStart)
            } else streakStart = -1
        }
        return limit
    }

    /** Contrast between an occupied stock card and the neighboring green gutter. */
    private fun stockInteriorContrast(bitmap: Bitmap, x: Int, y: Int, width: Int, height: Int): Float {
        if (x - width / 7 < 0 || y < 0 || y + height > bitmap.height) return 0f
        val gutterX = x - max(3, width / 7)
        val centerX = x + width / 2
        if (centerX >= bitmap.width) return 0f
        val samples = floatArrayOf(.24f, .35f, .50f, .65f, .76f)
        var score = 0f
        for (position in samples) {
            val sampleY = (y + position * height).toInt()
            val background = bitmap.getPixel(gutterX, sampleY)
            val center = bitmap.getPixel(centerX, sampleY)
            score += maxOf(abs(Color.red(center) - Color.red(background)),
                abs(Color.green(center) - Color.green(background)),
                abs(Color.blue(center) - Color.blue(background)))
        }
        return score / samples.size
    }

    private fun rectangleEdgeStrength(bitmap: Bitmap, x: Int, y: Int, width: Int, height: Int): Float {
        if (y < 3 || y + height + 3 >= bitmap.height) return 0f
        fun difference(a: Int, b: Int): Float = (abs(Color.red(a) - Color.red(b)) +
            abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))) / 3f
        var top = 0f; var bottom = 0f
        for (i in 1..7) {
            val px = (x + width * (.1f + i * .1f)).toInt().coerceIn(0, bitmap.width - 1)
            top += difference(bitmap.getPixel(px, y - 1), bitmap.getPixel(px, y))
            bottom += difference(bitmap.getPixel(px, y + height - 1), bitmap.getPixel(px, y + height))
        }
        return min(top, bottom) / 7f
    }

    private fun pale(color: Int): Boolean {
        val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
        return minOf(r, g, b) >= 174 && maxOf(r, g, b) - minOf(r, g, b) <= 66
    }
}
