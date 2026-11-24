package com.retq.solitairebot.core

/**
 * Bounded session history. Stopping/restarting automatic mode never erases failed
 * edges. A new explicitly created capture/round calls resetSession(). All access
 * is synchronized; the published enabled flag is safe for worker-side admission.
 */
class AutoPlayGuard(private val maxActions: Int = 120) {
    init { require(maxActions > 0) }
    private val attemptedEdges = mutableSetOf<String>()
    private val failedEdges = mutableSetOf<String>()
    private val confirmedStateVisits = mutableMapOf<String, Int>()
    // Only a verified useful board transition opens another stock pass. Cycling
    // the same visible stock without any progress never unlocks a blocked draw.
    private var progressVersion = 0
    @Volatile var enabled = false
        private set
    var attempts = 0
        private set
    var stoppedBecause = "لم يبدأ التشغيل"
        private set

    @Synchronized fun resetSession() {
        enabled = false
        attempts = 0
        attemptedEdges.clear()
        failedEdges.clear()
        confirmedStateVisits.clear()
        progressVersion = 0
        stoppedBecause = "لم يبدأ التشغيل"
    }

    @Synchronized fun start() {
        enabled = attempts < maxActions
        stoppedBecause = if (enabled) "" else "بلغت هذه الجلسة حد الحركات؛ ابدأ جولة جديدة"
    }

    @Synchronized fun stop(reason: String) { enabled = false; stoppedBecause = reason }

    @Synchronized fun blocked(board: BoardState, move: Move, visibleWaste: String = ""): Boolean =
        edge(board, move, visibleWaste) in attemptedEdges || edge(board, move, visibleWaste) in failedEdges

    /** Called immediately before dispatch, after all transient readiness checks. */
    @Synchronized fun authorize(board: BoardState, move: Move, requireAuto: Boolean = true,
        visibleWaste: String = ""): String? {
        if (requireAuto && !enabled) return stoppedBecause.ifBlank { "التشغيل متوقف" }
        if (board.problems().isNotEmpty()) return stopAndReturn("اللوحة غير متسقة؛ توقّف التنفيذ")
        if (attempts >= maxActions) return stopAndReturn("بلغت الجلسة حد الحركات؛ توقّف التنفيذ لمنع الاستمرار بلا نهاية")
        val key = edge(board, move, visibleWaste)
        if (key in attemptedEdges || key in failedEdges)
            return stopAndReturn("الحركة جُرّبت من الحالة نفسها؛ لن تتكرر حتى تبدأ جولة جديدة")
        attemptedEdges.add(key)
        attempts++
        return null
    }

    @Synchronized fun markFailed(board: BoardState, move: Move, reason: String,
        visibleWaste: String = "") {
        failedEdges.add(edge(board, move, visibleWaste))
        stop("حركة غير مؤكدة: $reason")
    }

    @Synchronized fun noteVerifiedProgress(before: BoardState, move: Move, after: BoardState,
        observedCoveredBacks: Set<Int> = emptySet()) {
        if (boardKey(before) == boardKey(after)) return
        when (move) {
            is Move.TableauToFoundation, is Move.WasteToFoundation,
            is Move.WasteToTableau, is Move.FlipTableau -> progressVersion++
            is Move.TableauToTableau -> {
                // Merely shuffling the same face-up run back and forth must not
                // unlock another full stock pass. A revealed back is real progress.
                val source = before.columns[move.from]
                val revealed = after.columns[move.from]
                if ((!source.exposedBack && revealed.exposedBack) ||
                    (source.hiddenCount == null && move.from in observedCoveredBacks && move.startIndex == 0 &&
                        revealed.top != null && revealed.top !in source.faceUp))
                    progressVersion++
            }
            else -> Unit // cycling stock or rolling back a foundation is not progress
        }
    }

    @Synchronized fun markConfirmed(after: BoardState, visibleWaste: String = ""): String? {
        val key = boardKey(after) + if (after.waste == null && visibleWaste.isNotBlank())
            "|WPIX=$visibleWaste" else ""
        val count = (confirmedStateVisits[key] ?: 0) + 1
        confirmedStateVisits[key] = count
        return if (count >= 3) stopAndReturn("تكررت حالة اللوحة ثلاث مرات؛ توقّف التنفيذ لمنع الدوران") else null
    }

    @Synchronized fun failed(reason: String) = stop("حركة غير مؤكدة: $reason")
    private fun stopAndReturn(reason: String): String { stop(reason); return reason }
    /** Unreadable waste may change on a verified draw; guard the physical state, not just null OCR. */
    private fun edge(board: BoardState, move: Move, visibleWaste: String) = boardKey(board) +
        (if (board.waste == null && visibleWaste.isNotBlank()) "|WPIX=$visibleWaste" else "") +
        // A new, visually verified tableau/foundation gain permits revisiting a
        // stock card from a *new* pass. Recycle by itself never resets this guard.
        (if (move == Move.DrawOne || move == Move.DrawThree || move == Move.RecycleStock)
            "|PROGRESS=$progressVersion" else "") + "|" + move.signature

    companion object {
        /** No unknown hidden-card count can manufacture a different visible state. */
        fun boardKey(board: BoardState): String = buildString {
            board.columns.forEach { col ->
                append('[')
                col.faceUp.forEach { append(it.code).append(',') }
                if (col.definitelyEmpty) append("EMPTY")
                if (col.exposedBack) append("BACK")
                append(']')
            }
            Suit.entries.forEach { append(it.symbol).append(board.foundations.getValue(it)) }
            append("W=").append(board.waste?.code ?: '?')
            append("S=").append(board.stockStatus.name)
            append("D=").append(board.drawCount)
            append("R=").append(board.recycleAvailable)
        }
    }
}
