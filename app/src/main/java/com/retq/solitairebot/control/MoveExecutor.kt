package com.retq.solitairebot.control

import android.content.Context
import android.graphics.PointF
import android.os.SystemClock
import com.retq.solitairebot.capture.NormalizedBoardLayout
import com.retq.solitairebot.capture.NormalizedBox
import com.retq.solitairebot.core.Move
import com.retq.solitairebot.core.Suit
import com.retq.solitairebot.storage.AppStore
import com.retq.solitairebot.vision.VisionReadout

/** One checked physical action. Timing and completion are owned solely by LiveSession/LivePipeline. */
object MoveExecutor {
    enum class Disposition { QUEUED, WAIT, REJECTED }
    data class Attempt(val disposition: Disposition, val message: String,
        val action: LiveSession.Action? = null)

    fun dispatch(
        context: Context,
        layout: NormalizedBoardLayout,
        environmentValid: () -> Boolean,
        expectedPackage: String?,
        authorize: (VisionReadout, Move) -> String?,
        onEvent: (LiveSession.Event) -> Unit
    ): Attempt {
        val app = AppStore(context)
        val service = GestureAccessibilityService.instance
            ?: return Attempt(Disposition.REJECTED, "فعّل خدمة إمكانية الوصول من إعدادات أندرويد")
        if (!app.manualExecutionEnabled)
            return Attempt(Disposition.REJECTED, "إذن التحريك غير مفعّل")
        if (!app.calibrated || !app.calibrationArmedForSession)
            return Attempt(Disposition.REJECTED, "يلزم اعتماد معايرة مرتبطة بلقطة الجلسة الحالية")
        if (!environmentValid() || !GestureAccessibilityService.isTargetForeground(expectedPackage))
            return Attempt(Disposition.WAIT, "بانتظار ظهور شاشة اللعبة المعايرة")
        if (LiveSession.moveInFlight)
            return Attempt(Disposition.WAIT, "بانتظار تأكيد الحركة السابقة")
        val now = SystemClock.elapsedRealtime()
        val frame = LiveSession.readout ?: return Attempt(Disposition.WAIT, "بانتظار إطارين ثابتين")
        val proposal = LiveSession.suggestion ?: return Attempt(Disposition.WAIT, "لا توجد نقلة مؤكدة الآن")
        if (frame.capturedAtMs < GestureAccessibilityService.foregroundObservedAtMs ||
            now - frame.capturedAtMs !in 0L..LiveSession.FRAME_MAX_AGE_MS)
            return Attempt(Disposition.WAIT, "القراءة قديمة؛ جار طلب إطار جديد")
        if (!frame.calibrated || frame.layoutRevision != app.layoutRevision || frame.board.problems().isNotEmpty())
            return Attempt(Disposition.REJECTED, "تغيّرت المعايرة أو أصبحت بيانات اللوحة غير متسقة")
        val dimensions = realSize(context)
        if (frame.frameWidth != dimensions.first || frame.frameHeight != dimensions.second)
            return Attempt(Disposition.REJECTED, "أبعاد الالتقاط لا تطابق الشاشة الكاملة؛ أعد المعايرة")
        val points = pointsFor(proposal.move, frame, layout)
            ?: return Attempt(Disposition.REJECTED, "لم تتأكد مناطق السحب والإسقاط من الصورة الحالية")
        val action = LiveSession.beginAction(frame, proposal.move, now)
            ?: return Attempt(Disposition.WAIT, "تغيّرت القراءة؛ جار انتظار اقتراح جديد")
        service.sendGesture(points.first, points.second, expectedPackage,
            durationMs = action.gestureDurationMs,
            isCurrent = {
                LiveSession.canDispatch(action.id) && environmentValid() &&
                    frame.capturedAtMs >= GestureAccessibilityService.foregroundObservedAtMs &&
                    app.manualExecutionEnabled && app.calibrationArmedForSession &&
                    app.layoutRevision == frame.layoutRevision && app.calibratedTargetPackage == expectedPackage
            },
            beforeDispatch = { authorize(frame, proposal.move) },
            onDone = { delivered, message ->
                val event = LiveSession.gestureResult(action.id, delivered, message)
                if (event.result != LiveSession.Result.IGNORED) {
                    if (event.result == LiveSession.Result.DELIVERED) app.invalidateManualVerification()
                    onEvent(event)
                }
            })
        return Attempt(Disposition.QUEUED, "جار إرسال النقلة ${proposal.move.signature}", action)
    }

    fun canLocate(move: Move, frame: VisionReadout, layout: NormalizedBoardLayout): Boolean =
        pointsFor(move, frame, layout) != null

    private fun realSize(context: Context): Pair<Int, Int> {
        val metrics = android.util.DisplayMetrics()
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        @Suppress("DEPRECATION")
        manager.defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun pointsFor(move: Move, frame: VisionReadout, layout: NormalizedBoardLayout): Pair<PointF, PointF?>? {
        if (!frame.calibrated || frame.stockBusy || frame.board.problems().isNotEmpty()) return null
        val evidence = frame.evidence()
        fun confident(key: String): Boolean = key !in evidence.unknownRegions &&
            (evidence.regionConfidences[key] ?: frame.positions[key]?.confidence ?: 0f) >= .90f
        fun recognized(key: String, expected: com.retq.solitairebot.core.Card? = null): PointF? =
            frame.positions[key]?.takeIf { confident(key) && (expected == null || it.card == expected) }
                ?.let { PointF(it.touchX, it.touchY) }
        fun center(box: NormalizedBox) = PointF((box.left + box.right) * .5f * frame.frameWidth,
            (box.top + box.bottom) * .5f * frame.frameHeight)
        fun tableau(column: Int): PointF? {
            if (!evidence.hasColumn(column) || !confident("T$column")) return null
            return recognized("T$column") ?: if (column in evidence.emptyColumns &&
                frame.board.columns[column].definitelyEmpty) {
                val box = layout.tableauColumns[column]
                PointF((box.left + box.right) * .5f * frame.frameWidth,
                    box.top * frame.frameHeight + (box.right - box.left) * frame.frameWidth * .4f)
            } else null
        }
        fun foundation(suit: Suit): PointF? {
            if (!evidence.hasFoundation(suit) || !confident("F${suit.symbol}")) return null
            return center(layout.foundations[when (suit) {
                Suit.CLUBS -> 0; Suit.SPADES -> 1; Suit.HEARTS -> 2; Suit.DIAMONDS -> 3
            }])
        }
        fun pair(from: PointF?, to: PointF?): Pair<PointF, PointF?>? =
            if (from != null && to != null) from to to else null
        return when (move) {
            is Move.TableauToFoundation -> if (move.column in evidence.observedColumns)
                pair(recognized("T${move.column}", move.card), foundation(move.card.suit)) else null
            is Move.WasteToFoundation -> if (evidence.wasteObserved)
                pair(recognized("W", move.card), foundation(move.card.suit)) else null
            is Move.TableauToTableau -> {
                if (move.from !in evidence.observedColumns || move.startIndex !in frame.board.columns[move.from].faceUp.indices ||
                    frame.board.columns[move.from].faceUp.drop(move.startIndex) != move.cards) return null
                val key = "T${move.from}:${move.startIndex}"
                val from = recognized(key, move.cards.first())
                    ?: if (move.cards.size == 1) recognized("T${move.from}", move.cards.single()) else null
                pair(from, tableau(move.to))
            }
            is Move.WasteToTableau -> if (evidence.wasteObserved)
                pair(recognized("W", move.card), tableau(move.to)) else null
            is Move.FoundationToTableau -> if (move.fromSuit in evidence.observedFoundations)
                pair(recognized("F${move.fromSuit.symbol}", move.card), tableau(move.to)) else null
            is Move.FlipTableau -> {
                if (move.column !in frame.board.columns.indices ||
                    move.column !in evidence.exposedTableauBacks ||
                    !frame.board.columns[move.column].exposedBack || !confident("T${move.column}")) return null
                val point = frame.tableauBackPoints[move.column] ?: return null
                val box = layout.tableauColumns[move.column]
                if (!point.first.isFinite() || !point.second.isFinite() ||
                    point.first <= box.left * frame.frameWidth || point.first >= box.right * frame.frameWidth ||
                    point.second <= box.top * frame.frameHeight || point.second >= box.bottom * frame.frameHeight) return null
                PointF(point.first, point.second) to null
            }
            Move.DrawOne, Move.DrawThree -> if (evidence.observedStock && confident("S") &&
                ((move == Move.DrawOne && frame.board.drawCount == 1) || (move == Move.DrawThree && frame.board.drawCount == 3)) &&
                ((confident("W") && (evidence.wasteObserved || evidence.wasteEmpty)) ||
                    (evidence.wasteOccupiedUnidentified && evidence.wasteSignature.isNotBlank() &&
                        evidence.confidence("W") >= .90f)) &&
                frame.board.stockStatus == com.retq.solitairebot.core.StockStatus.AVAILABLE)
                center(layout.stock) to null else null
            Move.RecycleStock -> if (evidence.observedStock && confident("S") &&
                ((confident("W") && evidence.wasteObserved && frame.board.waste != null) ||
                    (evidence.wasteOccupiedUnidentified && evidence.wasteSignature.isNotBlank() &&
                        evidence.confidence("W") >= .90f)) &&
                frame.board.stockStatus == com.retq.solitairebot.core.StockStatus.EMPTY &&
                ((frame.board.redealsRemaining ?: 0) > 0 || (frame.board.redealsRemaining == null &&
                    frame.board.recycleAvailable && evidence.recycleAvailable))) center(layout.stock) to null else null
        }
    }
}
