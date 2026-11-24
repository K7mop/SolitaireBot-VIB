package com.retq.solitairebot.vision

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import com.retq.solitairebot.capture.NormalizedBoardLayout
import com.retq.solitairebot.capture.PixelBox
import com.retq.solitairebot.core.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class CardPosition(
    val x: Int, val y: Int, val width: Int, val height: Int, val card: Card, val confidence: Float,
    val exposedHeight: Int = height
) {
    val touchX: Float get() = x + width * .50f
    val touchY: Float get() = y + min(height * .35f, exposedHeight.coerceAtLeast(2) * .50f)
}

data class VisionReadout(
    val board: BoardState,
    val positions: Map<String, CardPosition>,
    val found: Int,
    val confidence: Float,
    val calibrated: Boolean,
    val warnings: List<String>,
    val atMs: Long,
    val frameWidth: Int,
    val frameHeight: Int,
    val verifiedFoundations: Set<Suit>,
    val observedFoundations: Set<Suit>,
    val capturedAtMs: Long = atMs,
    val frameId: Long = capturedAtMs,
    val sessionId: Long = 0L,
    val layoutRevision: Long = 0L,
    val observationEvidence: ObservationEvidence = ObservationEvidence(),
    val tableauBackPoints: Map<Int, Pair<Float, Float>> = emptyMap(),
    /** Measured on the capture worker; never counts as card-recognition confidence. */
    val analysisDurationMs: Long = 0L,
    val hudScore: Int? = null,
    val hudSeconds: Int? = null,
    /** A loading ring is a wait signal, never a stock/recycle action target. */
    val stockBusy: Boolean = false,
    /** Positive strip evidence only; the number and identities behind it stay unknown. */
    val coveredTableauBacks: Set<Int> = emptySet()
) {
    val regionConfidences: Map<String, Float> get() = observationEvidence.regionConfidences
    val unknownRegions: Set<String> get() = observationEvidence.unknownRegions
    val observedStock: Boolean get() = observationEvidence.observedStock
    val stockSignature: String get() = observationEvidence.stockSignature
    val observedEmptyColumns: Set<Int> get() = observationEvidence.emptyColumns
    val observedEmptyFoundations: Set<Suit> get() = observationEvidence.emptyFoundations
    val observedWasteEmpty: Boolean get() = observationEvidence.wasteEmpty
    fun evidence(): ObservationEvidence = observationEvidence
    fun summary(): String = buildString {
        append("الأعمدة: ")
        board.columns.forEachIndexed { i, col -> append("${i + 1}:${col.faceUp.joinToString(" ") { it.code }.ifEmpty { if (i in observedEmptyColumns) "فارغ" else if (col.exposedBack) "ظهر ورقة" else "؟" }} | ") }
        append("السحب: ${board.waste?.code ?: if (observedWasteEmpty) "فارغ" else if (observationEvidence.wasteOccupiedUnidentified) "ورقة ظاهرة غير مقروءة" else "؟"}")
        append(" | أوراق مقروءة: $found | مناطق غير مؤكدة: ${unknownRegions.size}")
    }
}

/**
 * Calibrated local reader. Geometry selects the exposed card before classification.
 * Empty/back states require taught visual patches, bound manual confirmation, or
 * positive geometry/background/back evidence from the automatic first-image model.
 */
class CardVision(private val templates: GlyphTemplates) {
    private val automaticRegions = AutomaticRegionReader(templates)
    private var automaticMode = false
    @Synchronized fun needsAutomaticReference(): Boolean = !automaticRegions.hasReference
    private var currentSession = Long.MIN_VALUE
    private var currentLayout = Long.MIN_VALUE
    private var templateRevision = Long.MIN_VALUE
    private var seededManual: String? = null
    private val emptyProofs = mutableMapOf<String, ByteArray>()
    private val countProofs = mutableMapOf<Int, Pair<Int, ByteArray>>()
    private data class RememberedCard(val signature: ByteArray, val visibleFraction: Float, val confidence: Float)
    private val cardMemory = mutableMapOf<Card, RememberedCard>()
    private var knownRedeals: Int? = null
    private var knownCounts = mutableMapOf<Int, Int>()
    private var lastBoard: BoardState? = null
    private var lastCompleteColumns: Set<Int> = emptySet()
    private val pendingReveals = mutableMapOf<Int, Int>()

    @Synchronized fun reset(sessionId: Long = 0L, layoutRevision: Long = 0L) {
        currentSession = sessionId; currentLayout = layoutRevision; templateRevision = templates.currentRevision
        seededManual = null; emptyProofs.clear(); countProofs.clear(); cardMemory.clear(); knownRedeals = null
        automaticRegions.clear()
        knownCounts.clear(); lastBoard = null; lastCompleteColumns = emptySet(); pendingReveals.clear()
    }

    @Synchronized fun invalidateManualReference() {
        seededManual = null; emptyProofs.clear(); countProofs.clear(); knownCounts.clear()
        knownRedeals = null; pendingReveals.clear()
    }

    @Synchronized fun needsManualReference(board: BoardState?): Boolean = board != null &&
        (templates.currentRevision != templateRevision || seededManual != board.toString())

    /** Called only after the controller has independently verified the resulting board. */
    @Synchronized fun confirmedMove(move: Move) {
        if (move == Move.RecycleStock) knownRedeals = knownRedeals?.minus(1)?.coerceAtLeast(0)
        val source = when (move) {
            is Move.TableauToFoundation -> move.column
            is Move.TableauToTableau -> if (move.startIndex == 0) move.from else null
            else -> null
        }
        if (source != null && lastBoard?.columns?.get(source)?.exposedBack != true)
            pendingReveals.remove(source)?.let { knownCounts[source] = (it - 1).coerceAtLeast(0) }
        if (move is Move.FlipTableau && lastBoard?.columns?.get(move.column)?.top != null)
            knownCounts[move.column]?.let { knownCounts[move.column] = (it - 1).coerceAtLeast(0) }
    }

    /** A timed-out/cancelled touch may have changed the game even without verification. */
    @Synchronized fun unconfirmedMove(move: Move) {
        val source = when (move) {
            is Move.TableauToFoundation -> move.column
            is Move.TableauToTableau -> move.from
            is Move.FlipTableau -> move.column
            else -> null
        }
        source?.let { knownCounts.remove(it); countProofs.remove(it); pendingReveals.remove(it) }
        if (move == Move.RecycleStock) knownRedeals = null
    }

    @Synchronized fun inspect(
        bitmap: Bitmap, layout: NormalizedBoardLayout, manual: BoardState?, calibrated: Boolean,
        referenceAspectMatches: Boolean,
        capturedAtMs: Long = SystemClock.elapsedRealtime(), frameId: Long = capturedAtMs,
        sessionId: Long = 0L, layoutRevision: Long = 0L,
        manualSnapshot: Bitmap? = null,
        automatic: Boolean = false, drawCount: Int = 3, automaticSnapshot: Bitmap? = null
    ): VisionReadout {
        val regions = layout.foundations + layout.tableauColumns + listOf(layout.stock, layout.waste)
        val bounds = PixelBox((regions.minOf { it.left } * bitmap.width).toInt() - 4,
            (regions.minOf { it.top } * bitmap.height).toInt() - 4,
            (regions.maxOf { it.right } * bitmap.width).toInt() + 4,
            (regions.maxOf { it.bottom } * bitmap.height).toInt() + 4)
        return FramePixels.withFrame(bitmap, bounds) {
            inspectFrame(bitmap, layout, manual, calibrated, referenceAspectMatches,
                capturedAtMs, frameId, sessionId, layoutRevision, manualSnapshot,
                automatic, drawCount, automaticSnapshot)
        }
    }

    private fun inspectFrame(
        bitmap: Bitmap, layout: NormalizedBoardLayout, manual: BoardState?, calibrated: Boolean,
        referenceAspectMatches: Boolean, capturedAtMs: Long, frameId: Long,
        sessionId: Long, layoutRevision: Long, manualSnapshot: Bitmap?,
        automatic: Boolean, drawCount: Int, automaticSnapshot: Bitmap?
    ): VisionReadout {
        templates.reloadIfChanged()
        if (currentSession != sessionId || currentLayout != layoutRevision || templateRevision != templates.currentRevision)
            reset(sessionId, layoutRevision)
        automaticMode = automatic
        if (automatic && automaticSnapshot != null && !automaticRegions.hasReference &&
            automaticSnapshot.width == bitmap.width && automaticSnapshot.height == bitmap.height)
            automaticRegions.seed(automaticSnapshot, layout)
        val warnings = mutableListOf<String>()
        if (automatic && !automaticRegions.hasReference)
            warnings += "لا توجد أدلة خلفية مرتبطة بلقطة التهيئة؛ لن تُعامل المناطق غير المقروءة كفراغات"
        if (!calibrated) warnings += "اعتمد مناطق اللعبة من لقطة حديثة لهذه الجلسة"
        if (!referenceAspectMatches) warnings += "نسبة الصورة مختلفة عن مرجع المعايرة"
        val foundations = layout.foundations.map { it.toPixels(bitmap.width, bitmap.height) }
        val cardHeight = foundations.map { it.bottom - it.top }.sorted()[2]
        val cardWidth = foundations.map { it.right - it.left }.sorted()[2]
        val columnBoxes = layout.tableauColumns.map { it.toPixels(bitmap.width, bitmap.height) }
        val wasteBox = layout.waste.toPixels(bitmap.width, bitmap.height)
        val stockBox = layout.stock.toPixels(bitmap.width, bitmap.height)
        val order = listOf(Suit.CLUBS, Suit.SPADES, Suit.HEARTS, Suit.DIAMONDS)
        val manualKey = manual?.takeIf { it.problems().isEmpty() }?.toString()
        if (manual != null && manualKey != null && manualSnapshot != null &&
            manualSnapshot.width == bitmap.width && manualSnapshot.height == bitmap.height) {
            // This is the exact reviewed PNG supplied by an identity/hash/session-bound storage proof.
            // Never fingerprint the current live frame as evidence for a previous manual declaration.
            emptyProofs.clear(); countProofs.clear(); knownCounts.clear(); pendingReveals.clear()
            seededManual = manualKey
            knownRedeals = manual.redealsRemaining
            manual.columns.forEachIndexed { i, col ->
                templates.regionSignature(manualSnapshot, columnBoxes[i])?.let { signature ->
                    col.hiddenCount?.let { count -> countProofs[i] = count to signature }
                    if (col.definitelyEmpty) emptyProofs["T$i"] = signature
                }
            }
            order.forEachIndexed { i, suit ->
                if (manual.foundations.getValue(suit) == 0)
                    templates.regionSignature(manualSnapshot, foundations[i])?.let { emptyProofs["F${suit.symbol}"] = it }
            }
            // Blank manual waste means unknown in the editor; never certify it as empty here.
        }
        val positions = mutableMapOf<String, CardPosition>()
        val observedColumns = mutableSetOf<Int>(); val emptyColumns = mutableSetOf<Int>(); val completeColumns = mutableSetOf<Int>()
        val observedFoundations = mutableSetOf<Suit>(); val emptyFoundations = mutableSetOf<Suit>()
        val regionConfidence = mutableMapOf<String, Float>(); val unknown = mutableSetOf<String>()
        val exposedBacks = mutableSetOf<Int>()
        val coveredBacks = mutableSetOf<Int>()
        val backPoints = mutableMapOf<Int, Pair<Float, Float>>()

        fun emptyConfidence(box: PixelBox, key: String, label: String): Float? {
            val taught = templates.classifyRegion(bitmap, box, setOf(label))
            if (taught != null) return taught.confidence
            if (automaticMode) {
                val suit = if (key.startsWith("F")) Suit.entries.firstOrNull { key == "F${it.symbol}" } else null
                automaticRegions.emptyConfidence(bitmap, box, label, suit)?.let { return it }
            }
            val proof = emptyProofs[key] ?: return null
            val current = templates.regionSignature(bitmap, box) ?: return null
            val confidence = templates.regionSimilarity(proof, current)
            if (confidence < .97f) { emptyProofs.remove(key); return null }
            return confidence
        }

        val columns = columnBoxes.mapIndexed { i, box ->
            val empty = emptyConfidence(box, "T$i", GlyphTemplates.EMPTY_COLUMN)
            if (empty != null) {
                emptyColumns += i; observedColumns += i; completeColumns += i
                regionConfidence["T$i"] = empty; knownCounts[i] = 0
                TableauColumn(0, emptyList())
            } else {
                val geometry = columnAnchors(bitmap, box, cardHeight)
                val read = mutableListOf<CardPosition>()
                var missing = false
                // Always work from the lowest physical card; an unknown exposed card blocks the pile.
                for (index in geometry.indices.reversed()) {
                    val y = geometry[index]
                    val exposed = if (index == geometry.lastIndex) cardHeight else (geometry[index + 1] - y).coerceAtLeast(1)
                    val card = recognizeAt(bitmap, box.left, y, box.right - box.left, cardHeight, exposed)
                    if (card == null) { missing = true; break }
                    if (read.isNotEmpty() && (card.card.rank != read.first().card.rank + 1 || card.card.red == read.first().card.red)) {
                        missing = true; break
                    }
                    read.add(0, card)
                }
                if (read.isEmpty()) {
                    val back = if (automaticMode) automaticRegions.exposedBack(bitmap, box, cardHeight) else null
                    if (back != null) {
                        exposedBacks += i; backPoints[i] = back.first; regionConfidence["T$i"] = back.second
                        TableauColumn(knownCounts[i]?.takeIf { it > 0 }, emptyList(), exposedBack = true)
                    } else {
                        unknown += "T$i"
                        TableauColumn(null, emptyList())
                    }
                } else {
                    observedColumns += i
                    if (!missing) {
                        completeColumns += i
                        if (automaticMode && automaticRegions.coveredBackConfidence(bitmap, box, read.first().y) != null)
                            coveredBacks += i
                    }
                    read.forEachIndexed { j, card -> positions["T$i:$j"] = card; regionConfidence["T$i:$j"] = card.confidence }
                    positions["T$i"] = read.last(); regionConfidence["T$i"] = read.last().confidence
                    val old = lastBoard?.columns?.get(i)
                    var count = knownCounts[i]
                    if (automaticMode && !missing && abs(read.first().y - box.top) <= max(3, cardHeight / 30)) {
                        // A complete face-up run begins at the physical column origin: no hidden strip precedes it.
                        count = 0; knownCounts[i] = 0
                    } else if (automaticMode && count == 0 && read.first().y > box.top + max(3, cardHeight / 30)) {
                        // A newly visible strip above the identified suffix contradicts an old zero count.
                        count = null; knownCounts.remove(i)
                    }
                    if (old != null && i in lastCompleteColumns && old.top != read.last().card && old.faceUp.isNotEmpty() && (count ?: 0) > 0 &&
                        old.faceUp.none { it in read.map(CardPosition::card) }) {
                        // A visual new card is observed after the old exposed card left, so one reveal is possible.
                        // Keep the hidden count unknown until a controller-confirmed transition supplies it.
                        pendingReveals[i] = count!!
                        knownCounts.remove(i); count = null
                    }
                    if (manual != null && manual.columns[i].faceUp == read.map { it.card }) {
                        val proof = countProofs[i]
                        val currentPixels = if (proof != null) templates.regionSignature(bitmap, box) else null
                        if (proof != null && currentPixels != null && templates.regionSimilarity(proof.second, currentPixels) >= .97f) {
                            count = proof.first; knownCounts[i] = proof.first
                        }
                    }
                    TableauColumn(count, read.map { it.card })
                }
            }
        }

        val ranks = Suit.entries.associateWith { 0 }.toMutableMap()
        order.forEachIndexed { i, suit ->
            val key = "F${suit.symbol}"
            val empty = emptyConfidence(foundations[i], key, GlyphTemplates.EMPTY_FOUNDATION)
            if (empty != null) {
                emptyFoundations += suit; regionConfidence[key] = empty
            } else {
                val card = recognizeFixed(bitmap, foundations[i], cardHeight)
                if (card != null && card.card.suit == suit) {
                    ranks[suit] = card.card.rank; positions[key] = card
                    observedFoundations += suit; regionConfidence[key] = card.confidence
                } else unknown += key
            }
        }
        val wasteEmptyConfidence = emptyConfidence(wasteBox, "W", GlyphTemplates.EMPTY_WASTE)
        val waste = if (wasteEmptyConfidence == null) recognizeWaste(bitmap, wasteBox, cardWidth, cardHeight) else null
        if (waste != null) { positions["W"] = waste; regionConfidence["W"] = waste.confidence }
        else if (wasteEmptyConfidence != null) regionConfidence["W"] = wasteEmptyConfidence
        val unidentifiedWasteConfidence = if (waste == null && wasteEmptyConfidence == null && automaticMode)
            automaticRegions.unidentifiedWasteFace(bitmap, wasteBox, cardWidth, cardHeight) else null
        if (unidentifiedWasteConfidence != null) regionConfidence["W"] = unidentifiedWasteConfidence
        if (waste == null && wasteEmptyConfidence == null) unknown += "W"
        // Retain a visible-frame signature even when the fan's rank is unreadable.
        // It is used only to prove a STOCK DRAW changed that region, never as card OCR.
        val wasteSignature = if (automaticMode) templates.signatureId(bitmap, wasteBox) else ""
        val stockBusy = automaticMode && templates.stockBusyAutomatic(bitmap, stockBox.left,
            stockBox.top, stockBox.right - stockBox.left, stockBox.bottom - stockBox.top)
        val taughtStock = if (!stockBusy) templates.classifyRegion(bitmap, stockBox,
            setOf(GlyphTemplates.STOCK_BACK, GlyphTemplates.EMPTY_STOCK)) else null
        val observedBack = if (automaticMode && !stockBusy) automaticRegions.stockBackConfidence(bitmap, stockBox) else null
        // The green circular-arrow control may be positive evidence of EMPTY
        // even when a pale-corner empty-slot detector does not match the game's
        // dark-green stock tile. Recycle is tested as a distinct glyph, never
        // inferred merely from a missing/unknown stock back.
        val recycleGlyph = if (automaticMode && !stockBusy) templates.recognizeRecycleAutomatic(bitmap,
            stockBox.left, stockBox.top, stockBox.right - stockBox.left,
            stockBox.bottom - stockBox.top) else null
        val stockMatch = when {
            stockBusy -> null
            taughtStock?.label == GlyphTemplates.STOCK_BACK -> taughtStock
            observedBack != null -> GlyphTemplates.Match(GlyphTemplates.STOCK_BACK, observedBack)
            taughtStock?.label == GlyphTemplates.EMPTY_STOCK -> taughtStock
            recycleGlyph != null -> GlyphTemplates.Match(GlyphTemplates.EMPTY_STOCK, recycleGlyph)
            automaticMode -> automaticRegions.emptyConfidence(bitmap, stockBox,
                GlyphTemplates.EMPTY_STOCK)?.let { GlyphTemplates.Match(GlyphTemplates.EMPTY_STOCK, it) }
            else -> null
        }
        val stockStatus = when (stockMatch?.label) {
            GlyphTemplates.STOCK_BACK -> StockStatus.AVAILABLE
            GlyphTemplates.EMPTY_STOCK -> StockStatus.EMPTY
            else -> StockStatus.UNKNOWN
        }
        if (stockMatch == null) unknown += "S" else regionConfidence["S"] = stockMatch.confidence
        val recycleAvailable = stockStatus == StockStatus.EMPTY && recycleGlyph != null
        if (recycleAvailable) regionConfidence["S"] = min(regionConfidence["S"] ?: 0f, recycleGlyph!!)
        val board = BoardState(columns, ranks, waste?.card, stockStatus, knownRedeals, drawCount,
            recycleAvailable = recycleAvailable)
        warnings += board.problems()
        if (unknown.isNotEmpty()) warnings += if (automaticMode) "مناطق غير مؤكدة: ${unknown.joinToString()}؛ لا تُرسل حركة إليها حتى تتضح" else "مناطق غير مؤكدة: ${unknown.joinToString()}؛ علّم حالتها أو صحح حدودها"
        if (completeColumns.size < 7) warnings += "بعض تسلسلات الأعمدة غير مكتملة؛ تُستخدم الأوراق المقروءة المتصلة فقط"
        val evidence = ObservationEvidence(observedColumns, emptyColumns, completeColumns, observedFoundations,
            emptyFoundations, waste != null, wasteEmptyConfidence != null, stockMatch != null,
            if (stockMatch != null) templates.signatureId(bitmap, stockBox) else "", regionConfidence.toMap(), unknown,
            exposedTableauBacks = exposedBacks.toSet(), recycleAvailable = recycleAvailable,
            wasteOccupiedUnidentified = unidentifiedWasteConfidence != null,
            wasteSignature = wasteSignature)
        lastBoard = board
        lastCompleteColumns = completeColumns.toSet()
        return VisionReadout(board, positions.toMap(), columns.sumOf { it.faceUp.size } + observedFoundations.size + if (waste == null) 0 else 1,
            positions.values.minOfOrNull { it.confidence } ?: 0f, calibrated && referenceAspectMatches,
            warnings.distinct(), capturedAtMs, bitmap.width, bitmap.height, observedFoundations + emptyFoundations,
            observedFoundations, capturedAtMs, frameId, sessionId, layoutRevision, evidence, backPoints.toMap(),
            stockBusy = stockBusy, coveredTableauBacks = coveredBacks.toSet())
    }

    private fun recognizeFixed(bitmap: Bitmap, box: PixelBox, height: Int): CardPosition? =
        recognizeAt(bitmap, box.left, box.top, box.right - box.left, min(height, box.bottom - box.top), height)

    /** The fan's rightmost full card is located geometrically before its glyph is classified. */
    private fun recognizeWaste(bitmap: Bitmap, box: PixelBox, width: Int, height: Int): CardPosition? {
        if (box.right - box.left < width || box.bottom - box.top < height - 8) return null
        var right = -1
        for (x in box.right - 1 downTo box.left) {
            var pale = 0
            for (f in floatArrayOf(.06f, .10f, .15f)) if (isPale(bitmap, x, box.top + (height * f).toInt())) pale++
            if (pale >= 2) { right = x; break }
        }
        if (right < 0) return null
        val expected = (right + 2 - width).coerceIn(box.left, max(box.left, box.right - width))
        // A very clear match at the inferred physical edge needs no six extra
        // OCR passes. Any uncertainty still takes the full bounded edge search.
        val centered = recognizeAt(bitmap, expected, box.top, width, height, height)
        if (centered != null && centered.confidence >= .97f) return centered
        // Search only the inferred rightmost card's edge, never promote a covered left card.
        return (-3..3).mapNotNull { dx ->
            if (dx == 0) return@mapNotNull centered
            val x = expected + dx
            if (x < box.left || x + width > box.right) null else recognizeAt(bitmap, x, box.top, width, height, height)
        }.maxWithOrNull(compareBy<CardPosition> { it.confidence }.thenBy { -abs(it.x - expected) })
    }

    /** Cheap shape scan first; expensive glyph extraction is limited to physical card boundaries. */
    private fun columnAnchors(bitmap: Bitmap, box: PixelBox, height: Int): List<Int> {
        if (box.right - box.left < 20 || height < 30 || box.bottom - box.top < height - 8) return emptyList()
        fun paleRow(y: Int): Boolean = floatArrayOf(.25f, .50f, .75f).count {
            isPale(bitmap, box.left + ((box.right - box.left) * it).toInt(), y)
        } >= 2
        // A blue card back may have white horizontal stripes at its top edge.
        // A true face stays predominantly pale for several rows immediately
        // after the visible top, not merely on a single decorative stripe.
        fun startsWithFace(y: Int): Boolean {
            if (y < box.top || y + max(10, height / 12) >= bitmap.height) return false
            var paleSamples = 0; var total = 0
            for (dy in 3..max(9, height / 12)) for (f in floatArrayOf(.25f, .5f, .75f)) {
                val x = box.left + ((box.right - box.left) * f).toInt()
                if (isPale(bitmap, x, y + dy)) paleSamples++
                total++
            }
            return paleSamples >= total * .55f
        }
        var bottom = -1
        for (y in min(bitmap.height - 1, box.bottom - 1) downTo box.top + height / 2) {
            if (paleRow(y) && paleRow(y - 2) && paleRow(y - 4)) { bottom = y; break }
        }
        if (bottom < 0) return emptyList()
        val bottomTop = (bottom + 2 - height).coerceAtLeast(box.top)
        if (bottomTop + height > bitmap.height || !startsWithFace(bottomTop)) return emptyList()
        val anchors = mutableListOf<Int>()
        if (startsWithFace(box.top)) anchors += box.top
        val minimumGap = max(7, height / 14)
        for (y in box.top + 2 until bottomTop - minimumGap) {
            if (paleRow(y + 2) && !paleRow(y - 1) && startsWithFace(y) &&
                (anchors.isEmpty() || y - anchors.last() >= minimumGap)) anchors += y
        }
        if (anchors.isNotEmpty() && bottomTop - anchors.last() < minimumGap) anchors.removeAt(anchors.lastIndex)
        anchors += bottomTop
        return anchors.takeLast(13)
    }

    private fun recognizeAt(bitmap: Bitmap, x: Int, estimatedY: Int, width: Int, height: Int, exposed: Int): CardPosition? {
        if (width < 20 || height < 30) return null
        var best: CardPosition? = null
        // Small edge tolerance is resolution-scaled and cannot jump to another card in the column.
        val tolerance = min(4, max(1, height / 35))
        val offsets = listOf(0) + (1..tolerance).flatMap { listOf(-it, it) }
        if (exposed >= height * .49f) for (dy in offsets) {
            val y = estimatedY + dy
            if (x < 0 || y < 0 || x + width > bitmap.width || y + height > bitmap.height) continue
            val match = (if (automaticMode) templates.recognizeAutomatic(bitmap, x, y, width, height,
                fullFaceVisible = exposed >= height * .98f) else null)
                ?: templates.recognize(bitmap, x, y, width, height) ?: continue
            if (best == null || match.second > best.confidence + .0001f ||
                (abs(match.second - best.confidence) <= .0001f && abs(dy) < abs(best.y - estimatedY)))
                best = CardPosition(x, y, width, height, match.first, match.second, exposed.coerceAtMost(height))
            if (automaticMode && dy == 0) break
        }
        if (best != null) {
            val found = best
            templates.regionSignature(bitmap, PixelBox(found.x, found.y, found.x + width, found.y + height))?.let {
                val fraction = min(1f, exposed.toFloat() / height)
                val old = cardMemory[found.card]
                if (old == null || fraction >= old.visibleFraction)
                    cardMemory[found.card] = RememberedCard(it, fraction, found.confidence)
            }
            return found
        }
        if (exposed >= height * .18f && exposed < height * .75f && x >= 0 && x + width <= bitmap.width) {
            val fraction = exposed.toFloat() / height
            val matches = mutableMapOf<Card, CardPosition>()
            for (dy in -tolerance..tolerance) {
                val y = estimatedY + dy
                if (y < 0 || y + height > bitmap.height) continue
                val pixels = templates.regionSignature(bitmap, PixelBox(x, y, x + width, y + height)) ?: continue
                for ((card, remembered) in cardMemory) {
                    if (remembered.visibleFraction < fraction) continue
                    val confidence = min(remembered.confidence, templates.prefixSimilarity(remembered.signature, pixels, fraction))
                    val old = matches[card]
                    if (old == null || confidence > old.confidence + .0001f ||
                        (abs(confidence - old.confidence) <= .0001f && abs(dy) < abs(old.y - estimatedY)))
                        matches[card] = CardPosition(x, y, width, height, card, confidence, exposed)
                }
            }
            val ranked = matches.values.sortedByDescending { it.confidence }
            val match = ranked.firstOrNull() ?: return null
            if (match.confidence >= .96f && match.confidence - (ranked.getOrNull(1)?.confidence ?: 0f) >= .045f) return match
        }
        return null
    }

    private fun isPale(bitmap: Bitmap, x: Int, y: Int): Boolean {
        if (x !in 0 until bitmap.width || y !in 0 until bitmap.height) return false
        val color = FramePixels.get(bitmap, x, y)
        val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
        return minOf(r, g, b) >= 175 && maxOf(r, g, b) - minOf(r, g, b) < 70
    }
}
