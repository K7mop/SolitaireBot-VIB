package com.retq.solitairebot.vision

import android.graphics.Bitmap
import android.graphics.Color
import com.retq.solitairebot.capture.NormalizedBoardLayout
import com.retq.solitairebot.capture.PixelBox
import com.retq.solitairebot.core.Suit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Session-local evidence for a supported light-faced card table. Background is
 * sampled in the actual gutters of the approved first image; an occupied stock
 * supplies its visible back pattern. Neither "OCR failed" nor a remembered card
 * identity is an empty/back classification.
 */
class AutomaticRegionReader(private val templates: GlyphTemplates) {
    private var felt: Int? = null
    private var tolerance = 24
    private var backSignature: ByteArray? = null
    private var backPalette: List<Int> = emptyList()
    private var referenceReady = false
    val hasReference: Boolean get() = referenceReady

    fun clear() { felt = null; tolerance = 24; backSignature = null; backPalette = emptyList(); referenceReady = false }

    fun seed(bitmap: Bitmap, layout: NormalizedBoardLayout): Boolean {
        clear()
        val columns = layout.tableauColumns.map { it.toPixels(bitmap.width, bitmap.height) }
        val samples = mutableListOf<Int>()
        for (i in 0..5) {
            val x = (columns[i].right + columns[i + 1].left) / 2
            if (columns[i + 1].left - columns[i].right < 2) continue
            val top = max(columns[i].top, columns[i + 1].top)
            val bottom = min(columns[i].bottom, columns[i + 1].bottom)
            for (n in 1..20) samples += FramePixels.get(bitmap, x, top + (bottom - top) * n / 21)
        }
        if (samples.size < 40) return false
        // A common surface must occupy most of the inter-column gutters.
        val dominant = samples.groupBy {
            (Color.red(it) / 24 shl 8) or (Color.green(it) / 24 shl 4) or (Color.blue(it) / 24)
        }.maxByOrNull { it.value.size }?.value ?: return false
        val representative = rgbMedian(dominant)
        val close = samples.filter { distance(it, representative) <= 30 }
        if (close.size < samples.size * .70f || isPale(representative)) return false
        felt = rgbMedian(close)
        tolerance = (close.maxOf { distance(it, felt!!) } + 10).coerceIn(18, 40)
        val stock = layout.stock.toPixels(bitmap.width, bitmap.height)
        if (backCandidate(bitmap, stock)) {
            backSignature = templates.regionSignature(bitmap, stock)
            val width = stock.right - stock.left; val height = stock.bottom - stock.top
            val colors = mutableListOf<Int>()
            for (yi in 4..40) for (xi in 3..25) {
                val color = FramePixels.get(bitmap, stock.left + width * xi / 28, stock.top + height * yi / 45)
                if (!isPale(color) && !onFelt(color)) colors += color
            }
            // The palette is learned only from a positively patterned stock
            // back in this exact session; a generic blue rectangle is not a
            // universal rule, and card identities/counts are never inferred.
            backPalette = colors.groupBy { (Color.red(it) / 16 shl 8) or
                (Color.green(it) / 16 shl 4) or (Color.blue(it) / 16) }.values
                .filter { it.size >= colors.size * .025f }.sortedByDescending { it.size }
                .take(12).map { rgbMedian(it) }
        }
        referenceReady = true
        return true
    }

    fun emptyConfidence(bitmap: Bitmap, box: PixelBox, label: String, suit: Suit? = null): Float? {
        if (!hasReference || !valid(bitmap, box)) return null
        val insetX = max(2, (box.right - box.left) / 10)
        val insetY = max(2, min(box.bottom - box.top, box.right - box.left) / 12)
        val left = box.left + insetX; val right = box.right - insetX
        val top = box.top + insetY; val bottom = box.bottom - insetY
        if (left >= right || top >= bottom) return null
        val samplesPerRow = 19
        var total = 0; var changed = 0; var maxChangedRow = 0; var palePixels = 0
        val rowStep = max(1, min(box.right - box.left, box.bottom - box.top) / 32)
        // The felt is vertically shaded in this deck. Compare each row to its
        // immediate left gutter rather than treating a global RGB median as
        // a constant background color across the entire 2300px screen.
        val gutterX = (box.left - 3).coerceAtLeast(0)
        for (y in top until bottom step rowStep) {
            var rowChanged = 0
            val gutter = FramePixels.get(bitmap, gutterX, y)
            for (i in 0 until samplesPerRow) {
                val x = left + (right - left - 1) * i / (samplesPerRow - 1)
                val pixel = FramePixels.get(bitmap, x, y)
                total++
                if (isPale(pixel)) palePixels++
                if (distance(pixel, gutter) > max(tolerance, 30) && !onFelt(pixel)) {
                    changed++; rowChanged++
                }
            }
            maxChangedRow = max(maxChangedRow, rowChanged)
        }
        if (total == 0) return null
        if (label == GlyphTemplates.EMPTY_FOUNDATION) {
            if (suit == null) return null
            // A dark-green placeholder with a translucent green suit can
            // differ from the distant tableau-felt reference over most of
            // its area. A positively classified suit plus no light-faced
            // card corners is stronger evidence than flat-color matching.
            if (palePixels > total * .38f && !clearSlotCorners(bitmap, box)) return null
            val symbol = templates.recognizeSuitAutomatic(bitmap, box.left, box.top,
                box.right - box.left, box.bottom - box.top) ?: return null
            return if (symbol.first == suit) min(.98f, symbol.second) else null
        }
        // Entire height is scanned: even a short exposed back/face strip blocks
        // an empty-column result. A blank text/OCR result plays no part here.
        if (label == GlyphTemplates.EMPTY_COLUMN || label == GlyphTemplates.EMPTY_WASTE) {
            if (changed > total * .025f || maxChangedRow > 2) return null
            return .98f
        }
        if (label == GlyphTemplates.EMPTY_STOCK) {
            // A central reset symbol on a pale placeholder is positive evidence
            // only when the card-index corners are clear as well.
            if (changed > total * .13f || maxChangedRow > 11) {
                if (!clearSlotCorners(bitmap, box)) return null
                val reset = templates.recognizeRecycleAutomatic(bitmap, box.left, box.top,
                    box.right - box.left, box.bottom - box.top) ?: return null
                return min(.97f, reset)
            }
            return .96f
        }
        return null
    }

    fun stockBackConfidence(bitmap: Bitmap, box: PixelBox): Float? {
        if (!hasReference) return null
        val expected = backSignature ?: return null
        val actual = templates.regionSignature(bitmap, box) ?: return null
        val score = templates.regionSimilarity(expected, actual)
        return score.takeIf { it >= .965f && backCandidate(bitmap, box) }
    }

    /** A covered back directly above a fully read face-up run. This says
     * "at least one back is visibly present", never how many or which card.
     * The caller must not use a gap above an unreadable face-up suffix. */
    fun coveredBackConfidence(bitmap: Bitmap, box: PixelBox, firstFaceY: Int): Float? {
        if (!hasReference || backSignature == null || backPalette.isEmpty() || !valid(bitmap, box)) return null
        val width = box.right - box.left
        val gap = firstFaceY - box.top
        val margin = max(2, width / 40)
        val bottom = firstFaceY - margin
        val top = max(box.top + margin, bottom - max(12, width / 4))
        if (gap < max(7, width / 12) || bottom - top < 5) return null
        var consecutive = 0
        var lowR = 255; var lowG = 255; var lowB = 255
        var highR = 0; var highG = 0; var highB = 0
        // A short covered card exposes a pale outline above its interior.
        // Require a contiguous patterned band, not an average over outlines.
        for (y in top until bottom) {
            var matches = 0; var pale = 0; var feltPixels = 0
            var rowLowR = 255; var rowLowG = 255; var rowLowB = 255
            var rowHighR = 0; var rowHighG = 0; var rowHighB = 0
            for (column in 0..10) {
                val x = box.left + (width * (.16f + .068f * column)).toInt()
                val color = FramePixels.get(bitmap, x, y)
                if (isPale(color)) pale++ else {
                    rowLowR = min(rowLowR, Color.red(color)); rowHighR = max(rowHighR, Color.red(color))
                    rowLowG = min(rowLowG, Color.green(color)); rowHighG = max(rowHighG, Color.green(color))
                    rowLowB = min(rowLowB, Color.blue(color)); rowHighB = max(rowHighB, Color.blue(color))
                }
                if (onFelt(color)) feltPixels++
                if (backPalette.any { distance(color, it) <= 28 }) matches++
            }
            if (matches >= 8 && pale <= 2 && feltPixels <= 1) {
                consecutive++
                lowR = min(lowR,rowLowR); highR = max(highR,rowHighR)
                lowG = min(lowG,rowLowG); highG = max(highG,rowHighG)
                lowB = min(lowB,rowLowB); highB = max(highB,rowHighB)
                val variation = maxOf(highR-lowR,highG-lowG,highB-lowB)
                if (consecutive >= max(3,width/30) && variation >= 24) return .96f
            } else {
                consecutive = 0
                lowR = 255; lowG = 255; lowB = 255
                highR = 0; highG = 0; highB = 0
            }
        }
        return null
    }

    /** Positive evidence that a complete light-faced card occupies the waste fan.
     * This deliberately says nothing about its identity: a bad OCR result must
     * not block a safe stock draw when the card itself is clearly visible.
     */
    fun unidentifiedWasteFace(bitmap: Bitmap, box: PixelBox, cardWidth: Int, cardHeight: Int): Float? {
        if (!hasReference || !valid(bitmap, box) || cardWidth < 20 || cardHeight < 30 ||
            box.right - box.left < cardWidth || box.bottom - box.top < cardHeight - 8) return null
        val stride = max(2, cardWidth / 9)
        var best = 0
        for (left in box.left..(box.right - cardWidth) step stride) {
            var pale = 0
            var total = 0
            for (fy in floatArrayOf(.36f, .45f, .54f, .65f, .73f))
                for (fx in floatArrayOf(.20f, .35f, .50f, .65f, .80f)) {
                    val x = left + (cardWidth * fx).toInt()
                    val y = box.top + (cardHeight * fy).toInt()
                    if (x in 0 until bitmap.width && y in 0 until bitmap.height) {
                        total++
                        if (isPale(FramePixels.get(bitmap, x, y))) pale++
                    }
                }
            if (total >= 20 && pale > best) best = pale
        }
        // A green felt or blue card back never satisfies this condition.
        return if (best >= 18) .96f else null
    }

    fun exposedBack(bitmap: Bitmap, box: PixelBox, cardHeight: Int): Pair<Pair<Float, Float>, Float>? {
        if (!hasReference || backSignature == null || !valid(bitmap, box)) return null
        val width = box.right - box.left
        var lastOccupied = -1
        for (y in box.bottom - 1 downTo box.top) {
            val changed = floatArrayOf(.20f, .40f, .60f, .80f).count {
                !onFelt(FramePixels.get(bitmap, (box.left + width * it).toInt(), y))
            }
            if (changed >= 3) { lastOccupied = y; break }
        }
        if (lastOccupied < box.top + cardHeight - 5) return null
        val estimate = lastOccupied + 1 - cardHeight
        for (dy in -2..2) {
            val y = estimate + dy
            if (y < box.top || y + cardHeight > box.bottom) continue
            val card = PixelBox(box.left, y, box.right, y + cardHeight)
            val confidence = stockBackConfidence(bitmap, card) ?: continue
            return (box.left + width * .5f to y + cardHeight * .5f) to confidence
        }
        return null
    }

    private fun clearSlotCorners(bitmap: Bitmap, box: PixelBox): Boolean {
        val width = box.right - box.left; val height = box.bottom - box.top
        fun clear(left: Float, top: Float, right: Float, bottom: Float): Boolean {
            val colors = mutableListOf<Int>()
            for (y in (box.top + height * top).toInt() until (box.top + height * bottom).toInt())
                for (x in (box.left + width * left).toInt() until (box.left + width * right).toInt())
                    colors += FramePixels.get(bitmap, x, y)
            if (colors.isEmpty()) return false
            val background = rgbMedian(colors)
            if (!isPale(background)) return false
            return colors.count { distance(it, background) > 28 } <= colors.size * .005f
        }
        return clear(.04f, .03f, .36f, .32f) && clear(.64f, .68f, .96f, .97f)
    }

    private fun backCandidate(bitmap: Bitmap, box: PixelBox): Boolean {
        if (felt == null || !valid(bitmap, box)) return false
        val width = box.right - box.left; val height = box.bottom - box.top
        var changed = 0; var pale = 0; var total = 0
        val colors = mutableListOf<Int>()
        for (yi in 4..40) for (xi in 3..25) {
            val p = FramePixels.get(bitmap, box.left + width * xi / 28, box.top + height * yi / 45)
            total++; if (!onFelt(p)) changed++; if (isPale(p)) pale++; colors += p
        }
        if (changed < total * .75f || pale > total * .38f) return false
        val median = rgbMedian(colors)
        // A patterned back is required; a uniform rectangle/button is not a card.
        if (colors.count { distance(it, median) >= 20 } < total * .12f) return false
        return true
    }

    private fun onFelt(pixel: Int): Boolean = felt?.let { distance(pixel, it) <= tolerance } ?: false
    private fun valid(bitmap: Bitmap, b: PixelBox): Boolean = b.left >= 0 && b.top >= 0 &&
        b.right <= bitmap.width && b.bottom <= bitmap.height && b.right > b.left && b.bottom > b.top
    private fun distance(a: Int, b: Int): Int = maxOf(abs(Color.red(a) - Color.red(b)),
        abs(Color.green(a) - Color.green(b)), abs(Color.blue(a) - Color.blue(b)))
    private fun rgbMedian(colors: List<Int>): Int {
        val n = colors.size / 2
        return (colors.map(Color::red).sorted()[n] shl 16) or
            (colors.map(Color::green).sorted()[n] shl 8) or colors.map(Color::blue).sorted()[n]
    }
    private fun isPale(pixel: Int): Boolean = minOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) >= 174 &&
        maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) - minOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) < 70
}
