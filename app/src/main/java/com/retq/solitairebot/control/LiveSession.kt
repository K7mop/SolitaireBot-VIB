package com.retq.solitairebot.control

import android.os.SystemClock
import com.retq.solitairebot.core.BoardState
import com.retq.solitairebot.core.Move
import com.retq.solitairebot.core.Suggestion
import com.retq.solitairebot.core.StockStatus
import com.retq.solitairebot.core.VisualSafety
import com.retq.solitairebot.vision.VisionReadout

/**
 * The current capture's observations and one physical action. A callback is scoped
 * to BOTH an execution epoch and an action ID. Only the controller consumes action
 * outcomes/timeouts; a dispatched gesture never counts as a confirmed card move.
 */
object LiveSession {
    const val FRAME_MAX_AGE_MS = 1_000L
    const val DISPATCH_TIMEOUT_MS = 2_500L
    const val VERIFY_TIMEOUT_MS = 8_000L
    const val MAX_VERIFY_LIFETIME_MS = 30_000L
    // Visual confirmation already proves that animation has settled. Do not add
    // a second 390 ms cooldown after that proof (the 0.8.9 bottleneck).
    const val SETTLE_MS = 0L

    data class ActionId(val epoch: Long, val sequence: Long)
    data class Action(val id: ActionId, val move: Move, val before: VisionReadout,
        val requestedAtMs: Long, val deliveredAtMs: Long? = null,
        val gestureDurationMs: Long = 0L, val verifyNotBeforeMs: Long = 0L,
        val lastBusyAtMs: Long? = null)
    enum class Result { UNCHANGED, DELIVERED, CONFIRMED, FAILED, EXPIRED, CANCELLED, IGNORED }
    data class Event(val result: Result, val action: Action? = null, val after: VisionReadout? = null,
        val message: String = "")

    @Volatile var readout: VisionReadout? = null
        private set
    @Volatile var suggestion: Suggestion? = null
        private set
    @Volatile var lastMoveAtMs = 0L
        private set
    @Volatile var moveInFlight = false
        private set
    @Volatile var captureGeneration = 0L
        private set
    private var epoch = 0L
    private var sequence = 0L
    private var lastRaw: VisionReadout? = null
    private var pending: Action? = null
    private val recentMoves = java.util.ArrayDeque<String>()
    private val pacing = ExecutionPacing()

    @Synchronized fun beginSession(generation: Long = 0L) {
        captureGeneration = generation
        epoch++
        sequence = 0L
        pending = null
        moveInFlight = false
        clearObservations()
        lastMoveAtMs = 0L
        recentMoves.clear()
        pacing.reset()
    }

    /** Invalidate proposals without deleting failure/cycle history in the controller. */
    @Synchronized fun clearObservations() {
        readout = null
        suggestion = null
        lastRaw = null
    }

    @Synchronized fun cancelPending(message: String): Event {
        val action = pending
        epoch++ // Also invalidates work which has already been queued for dispatch.
        pending = null
        moveInFlight = false
        clearObservations()
        return Event(if (action == null) Result.UNCHANGED else Result.CANCELLED, action, message = message)
    }

    @Synchronized fun acceptObservation(fresh: VisionReadout, nowMs: Long = SystemClock.elapsedRealtime()): Event {
        if (fresh.sessionId != captureGeneration || fresh.capturedAtMs > nowMs ||
            nowMs - fresh.capturedAtMs > FRAME_MAX_AGE_MS) return Event(Result.IGNORED)
        // Pre-completion frames cannot establish post-action stability, even if
        // OCR publishes them late; keep queued observations for canDispatch().
        val active = pending
        if (active?.deliveredAtMs != null && nowMs - active.deliveredAtMs >= MAX_VERIFY_LIFETIME_MS)
            return finish(Result.EXPIRED, "طال انتظار تحميل اللعبة؛ توقفت الحركة لإعادة القراءة", nowMs)
        if (active?.deliveredAtMs != null && fresh.capturedAtMs < active.verifyNotBeforeMs)
            return Event(Result.IGNORED)
        val previous = lastRaw
        if (previous != null && (fresh.frameId <= previous.frameId ||
                fresh.capturedAtMs <= previous.capturedAtMs)) return Event(Result.IGNORED)
        lastRaw = fresh
        if (fresh.stockBusy) {
            readout = null
            suggestion = null
            pending?.takeIf { it.deliveredAtMs != null }?.let {
                pending = it.copy(lastBusyAtMs = fresh.capturedAtMs)
            }
            // A visible loading spinner is progress owned by the game, never a
            // recycle target and never evidence that our touch failed.
            return Event(Result.UNCHANGED)
        }
        val stable = previous != null && !previous.stockBusy &&
            nowMs - previous.capturedAtMs in 0L..FRAME_MAX_AGE_MS &&
            fresh.capturedAtMs - previous.capturedAtMs in ExecutionPacing.STABLE_FRAME_SEPARATION_MS..3_500L &&
            previous.sessionId == fresh.sessionId && previous.layoutRevision == fresh.layoutRevision &&
            previous.calibrated && fresh.calibrated &&
            geometryStable(previous, fresh) && VisualSafety.sameVisibleBoard(previous.board, fresh.board) &&
            (previous.evidence().stockSignature.isBlank() || fresh.evidence().stockSignature.isBlank() ||
                previous.evidence().stockSignature == fresh.evidence().stockSignature ||
                sameConfirmedRecycleControl(previous, fresh)) &&
            (previous.evidence().wasteSignature.isBlank() || fresh.evidence().wasteSignature.isBlank() ||
                previous.evidence().wasteSignature == fresh.evidence().wasteSignature)
        readout = if (stable) fresh else null
        suggestion = null
        val action = pending ?: return Event(Result.UNCHANGED)
        val delivered = action.deliveredAtMs ?: return Event(Result.UNCHANGED)
        val verificationBase = maxOf(delivered, action.lastBusyAtMs ?: delivered)
        if (nowMs - verificationBase >= VERIFY_TIMEOUT_MS) return finish(Result.EXPIRED,
            "انتهت مهلة التحقق البصري؛ توقفت الحركة دون إعادة المحاولة", nowMs)
        if (stable && previous!!.capturedAtMs > delivered && fresh.capturedAtMs > delivered &&
            transitionConfident(action, fresh) && transitionConfident(action, previous) &&
            VisualSafety.verifiesMove(action.before.board, fresh.board, action.move,
                fresh.evidence(), action.before.evidence())) {
            val event = finish(Result.CONFIRMED, "تأكد نجاح الحركة من إطارين جديدين", nowMs, fresh)
            pacing.confirmed(fresh.capturedAtMs - delivered)
            recentMoves.addLast(action.move.signature)
            while (recentMoves.size > 24) recentMoves.removeFirst()
            return event
        }
        // A confident, settled copy of the original board is an observed no-op,
        // not an unreadable screen. Do not waste the entire 8 s OCR deadline on
        // it. Both samples must be beyond the game-response grace period.
        if (stable && previous!!.capturedAtMs - verificationBase >= pacing.unchangedDeadlineMs() &&
            transitionConfident(action, previous, expectedTransition = false) &&
            transitionConfident(action, fresh, expectedTransition = false) &&
            VisualSafety.sameVisibleBoard(action.before.board, previous.board) &&
            VisualSafety.sameVisibleBoard(action.before.board, fresh.board) &&
            (action.before.evidence().stockSignature == fresh.evidence().stockSignature ||
                sameConfirmedRecycleControl(action.before, fresh)) &&
            action.before.evidence().wasteSignature == fresh.evidence().wasteSignature) {
            return finish(Result.FAILED, "لم تتغير اللوحة بعد الحركة؛ ستُقرأ مجددًا لاختيار بديل", nowMs, fresh)
        }
        return Event(Result.UNCHANGED)
    }

    /** Called before allocating/copying a screen bitmap by the capture worker. */
    @Synchronized fun needsObservationAt(atMs: Long): Boolean {
        val action = pending ?: return true
        return action.deliveredAtMs != null && atMs >= action.verifyNotBeforeMs
    }

    @Synchronized fun pacingDescription(): String = pacing.description()

    @Synchronized fun publishSuggestion(value: Suggestion?) {
        suggestion = if (pending == null && readout != null) value else null
    }

    @Synchronized fun beginAction(expected: VisionReadout, move: Move,
        nowMs: Long = SystemClock.elapsedRealtime()): Action? {
        if (pending != null || readout !== expected || suggestion?.move != move ||
            expected.sessionId != captureGeneration || nowMs - expected.capturedAtMs !in 0L..FRAME_MAX_AGE_MS ||
            nowMs - lastMoveAtMs < SETTLE_MS) return null
        return Action(ActionId(epoch, ++sequence), move, expected, nowMs,
            gestureDurationMs = pacing.durationFor(move)).also {
            pending = it
            moveInFlight = true
            suggestion = null
        }
    }

    @Synchronized fun isCurrent(id: ActionId): Boolean = pending?.id == id && id.epoch == epoch

    @Synchronized fun canDispatch(id: ActionId, nowMs: Long = SystemClock.elapsedRealtime()): Boolean {
        val action = pending ?: return false
        if (action.id != id || action.id.epoch != epoch || action.deliveredAtMs != null ||
            nowMs - action.requestedAtMs !in 0L..DISPATCH_TIMEOUT_MS ||
            nowMs - action.before.capturedAtMs !in 0L..FRAME_MAX_AGE_MS) return false
        val latest = lastRaw ?: return false
        return latest.sessionId == action.before.sessionId && latest.layoutRevision == action.before.layoutRevision &&
            !latest.stockBusy && VisualSafety.sameVisibleBoard(action.before.board, latest.board) &&
            geometryStable(action.before, latest)
    }

    @Synchronized fun gestureResult(id: ActionId, delivered: Boolean, message: String = "",
        nowMs: Long = SystemClock.elapsedRealtime()): Event {
        val action = pending ?: return Event(Result.IGNORED)
        if (action.id != id || id.epoch != epoch || action.deliveredAtMs != null) return Event(Result.IGNORED)
        if (!delivered) {
            return finish(Result.FAILED, message.ifBlank { "لم يقبل النظام الحركة؛ لم تُسجّل نقلة" }, nowMs)
        }
        if (nowMs - action.requestedAtMs >= DISPATCH_TIMEOUT_MS) return finish(Result.EXPIRED,
            "وصل تأكيد إرسال اللمس بعد المهلة؛ يلزم قراءة جديدة", nowMs)
        pending = action.copy(deliveredAtMs = nowMs,
            verifyNotBeforeMs = nowMs + pacing.verificationDelayMs())
        // Frames captured before delivery cannot verify this action.
        clearObservations()
        return Event(Result.DELIVERED, pending, message = "أُرسل اللمس؛ جار التحقق من انتقال الأوراق")
    }

    /** The ONE timeout consumer, invoked by LivePipeline.tick for manual and automatic work. */
    @Synchronized fun pollTimeout(nowMs: Long = SystemClock.elapsedRealtime()): Event? {
        val action = pending ?: return null
        val delivered = action.deliveredAtMs
        return when {
            delivered == null && nowMs - action.requestedAtMs >= DISPATCH_TIMEOUT_MS ->
                finish(Result.EXPIRED, "انتهت مهلة إرسال اللمس؛ توقّف التنفيذ", nowMs)
            delivered != null && (nowMs - delivered >= MAX_VERIFY_LIFETIME_MS ||
                nowMs - maxOf(delivered, action.lastBusyAtMs ?: delivered) >= VERIFY_TIMEOUT_MS) ->
                finish(Result.EXPIRED, "لم يتأكد نجاح الحركة خلال المهلة؛ توقّف التنفيذ", nowMs)
            else -> null
        }
    }

    /**
     * The game pulses its positively recognized empty-stock reset arrow. Its
     * brightness is not a different stock state. This narrow exception does not
     * apply to available/unknown stock, loading, or unidentified waste pixels.
     */
    private fun sameConfirmedRecycleControl(a: VisionReadout, b: VisionReadout): Boolean {
        fun reset(frame: VisionReadout): Boolean {
            val evidence = frame.evidence()
            return !frame.stockBusy && frame.board.stockStatus == StockStatus.EMPTY &&
                frame.board.recycleAvailable && evidence.observedStock && evidence.recycleAvailable &&
                "S" !in evidence.unknownRegions && evidence.confidence("S") >= .90f
        }
        return reset(a) && reset(b)
    }

    private fun geometryStable(a: VisionReadout, b: VisionReadout): Boolean =
        a.frameWidth == b.frameWidth && a.frameHeight == b.frameHeight &&
            a.positions.keys == b.positions.keys && a.positions.all { (key, first) ->
                b.positions[key]?.let { second -> first.card == second.card &&
                    kotlin.math.abs(first.x - second.x) <= 5 && kotlin.math.abs(first.y - second.y) <= 9 &&
                    kotlin.math.abs(first.width - second.width) <= 5 && kotlin.math.abs(first.height - second.height) <= 9
                } == true
            } && sameBackPositions(a, b)

    private fun sameBackPositions(a: VisionReadout, b: VisionReadout): Boolean =
        a.tableauBackPoints.keys == b.tableauBackPoints.keys && a.tableauBackPoints.all { (column, first) ->
            b.tableauBackPoints[column]?.let { second ->
                first.first.isFinite() && first.second.isFinite() && second.first.isFinite() && second.second.isFinite() &&
                    kotlin.math.abs(first.first - second.first) <= 5f && kotlin.math.abs(first.second - second.second) <= 9f
            } == true
        }

    private fun transitionConfident(action: Action, after: VisionReadout,
        expectedTransition: Boolean = true): Boolean {
        val keys = when (val move = action.move) {
            is Move.TableauToFoundation -> listOf("T${move.column}", "F${move.card.suit.symbol}")
            is Move.WasteToFoundation -> listOf("W", "F${move.card.suit.symbol}")
            is Move.TableauToTableau -> listOf("T${move.from}", "T${move.to}")
            is Move.WasteToTableau -> listOf("W", "T${move.to}")
            is Move.FoundationToTableau -> listOf("F${move.fromSuit.symbol}", "T${move.to}")
            is Move.FlipTableau -> listOf("T${move.column}")
            Move.DrawOne, Move.DrawThree, Move.RecycleStock -> listOf("S", "W")
        }
        val beforeEvidence = action.before.evidence()
        val afterEvidence = after.evidence()
        return keys.all { key ->
            if (key == "W" && (action.move == Move.DrawOne || action.move == Move.DrawThree)) {
                val beforeKnown = beforeEvidence.wasteObserved || beforeEvidence.wasteEmpty ||
                    beforeEvidence.wasteOccupiedUnidentified
                val afterKnown = afterEvidence.wasteObserved || afterEvidence.wasteOccupiedUnidentified ||
                    (!expectedTransition && afterEvidence.wasteEmpty)
                beforeKnown && afterKnown && beforeEvidence.confidence("W") >= .90f &&
                    afterEvidence.confidence("W") >= .90f
            } else if (key == "W" && action.move == Move.RecycleStock) {
                val beforeKnown = beforeEvidence.wasteObserved || beforeEvidence.wasteOccupiedUnidentified
                val afterKnown = if (expectedTransition) afterEvidence.wasteEmpty
                    else afterEvidence.wasteObserved || afterEvidence.wasteOccupiedUnidentified
                beforeKnown && afterKnown && beforeEvidence.confidence("W") >= .90f &&
                    afterEvidence.confidence("W") >= .90f
            } else key !in beforeEvidence.unknownRegions && key !in afterEvidence.unknownRegions &&
                beforeEvidence.confidence(key) >= .90f && afterEvidence.confidence(key) >= .90f
        }
    }

    private fun finish(result: Result, message: String, nowMs: Long, after: VisionReadout? = null): Event {
        val action = pending
        if (result == Result.FAILED || result == Result.EXPIRED) pacing.failed()
        pending = null
        moveInFlight = false
        lastMoveAtMs = nowMs
        suggestion = null
        if (result != Result.CONFIRMED) clearObservations()
        return Event(result, action, after, message)
    }

    @Synchronized fun recentSignatures(): List<String> = recentMoves.toList()
    @Synchronized fun currentAction(): Action? = pending
    @Synchronized fun reset() = beginSession(captureGeneration)
}
