package com.retq.solitairebot.control

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.retq.solitairebot.core.AutoPlayGuard
import com.retq.solitairebot.core.Move
import com.retq.solitairebot.core.SolitaireSolver
import com.retq.solitairebot.core.StockPassMemory
import com.retq.solitairebot.core.RoundScoreLedger
import com.retq.solitairebot.core.SmartDecision
import com.retq.solitairebot.core.HudClockTracker
import com.retq.solitairebot.core.DeckKnowledge
import com.retq.solitairebot.storage.AppStore
import com.retq.solitairebot.storage.CaptureMonitorStore
import com.retq.solitairebot.vision.CardVision
import com.retq.solitairebot.vision.GlyphTemplates
import com.retq.solitairebot.vision.GreenHudReader
import com.retq.solitairebot.vision.VisionReadout
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Main-thread state owner. Only observe() runs on the capture worker: it computes
 * an immutable readout then posts it to the owner with its capture/request IDs.
 * Every manual or automatic action reads two new frames, dispatches once and waits
 * for a positively observed transition. UI callbacks are idempotent owner events.
 */
class LivePipeline(
    private val context: Context,
    private val monitor: CaptureMonitorStore,
    private val onAutoStopped: () -> Unit,
    private val onReadFinished: () -> Unit,
    private val environmentValid: () -> Boolean = {
        monitor.captureActive && monitor.monitoringEnabled && !monitor.assistantUiVisible
    },
    private val targetPackage: () -> String? = { AppStore(context).calibratedTargetPackage },
    private val onManualStarted: () -> Unit = {},
    private val onManualFinished: () -> Unit = {}
) {
    data class CommandResult(val accepted: Boolean, val message: String)
    private enum class Mode { IDLE, READ, MANUAL, AUTO }
    private data class MoveOutcome(val capture: Long, val layout: Long, val move: Move, val confirmed: Boolean)
    private val app = AppStore(context)
    private val main = Handler(Looper.getMainLooper())
    private val guard = AutoPlayGuard(1_800)
    private val stockMemory = StockPassMemory(15)
    private val deckKnowledge = DeckKnowledge()
    private val scoreLedger = RoundScoreLedger(15)
    private val hudClock = HudClockTracker()
    private var lastNoCandidateLogMs = 0L
    private var lastScoreLogged: Int? = null
    private val workEpoch = AtomicLong(0)
    // One immutable result may await the main owner. Never let a fast capture
    // worker queue old OCR results faster than the owner can consume them.
    private val observationInFlight = AtomicBoolean(false)
    private val readerResetEpoch = AtomicLong(0)
    private val outcomes = ConcurrentLinkedQueue<MoveOutcome>()
    @Volatile private var mode = Mode.IDLE
    @Volatile private var captureGeneration = 0L
    @Volatile private var framesNotBefore = 0L
    private var commandStartedAt = 0L
    private var autoStartedAt = 0L
    private var firstValidFrameAt = 0L
    private var lastStableAt = 0L
    @Volatile private var noCandidateFrames = 0
    private var noCandidateSince = 0L
    private var lastUnstableNoticeAt = 0L
    private var failuresThisRun = 0
    private var readErrors = 0
    private var staleAnalyses = 0
    private var lastStaleNoticeAt = 0L
    private var candidateCacheKey: String? = null
    private var candidateCache: List<com.retq.solitairebot.core.Suggestion> = emptyList()
    private var currentRoundId: String? = null
    private var targetAtStart: String? = null
    private var observedWindowRevision: Long? = null
    private var lastSavedObservationKey: String? = null
    private var lastSavedObservationAt = 0L
    private var lastStatusAt = 0L
    private var lastDiagnosticBoardKey: String? = null
    private var lastDecisionDiagnosticAt = 0L
    private var lastObserveDiagnosticAt = 0L
    private var lastReadoutDiagnosticAt = 0L

    init { RoundDiagnostics.attach(context) }

    // Worker-owned only; never reset or mutate from a UI command.
    private val glyphs by lazy { GlyphTemplates(context) }
    private val vision by lazy { CardVision(glyphs) }
    private val hudReader by lazy { GreenHudReader(context.assets) }
    @Volatile private var workerEpoch = -1L
    @Volatile private var workerLastVisionAt = 0L
    private var readerCapture = Long.MIN_VALUE
    private var readerLayout = Long.MIN_VALUE
    private var workerReaderReset = Long.MIN_VALUE
    private var workerManualProofId: String? = null

    val autoPlaying: Boolean get() = mode == Mode.AUTO && guard.enabled
    val readingManual: Boolean get() = mode == Mode.READ
    val moveInFlight: Boolean get() = LiveSession.moveInFlight
    val actionActive: Boolean get() = mode == Mode.MANUAL || autoPlaying || moveInFlight
    /** Capture may briefly sample faster during an active move, not while parked. */
    val rapidCapture: Boolean get() = mode == Mode.MANUAL || mode == Mode.READ ||
        moveInFlight || (mode == Mode.AUTO && noCandidateFrames < 3)

    /** Demand gate before RGBA conversion. Never queue OCR of a moving gesture. */
    fun wantsFrame(frameAtMs: Long): Boolean {
        if (mode == Mode.IDLE || observationInFlight.get() || frameAtMs < framesNotBefore ||
            !LiveSession.needsObservationAt(frameAtMs)) return false
        val interval = if (mode == Mode.AUTO && noCandidateFrames >= 3)
            ExecutionPacing.PARKED_SAMPLE_MS else ExecutionPacing.ACTIVE_SAMPLE_MS
        return workerEpoch != workEpoch.get() || frameAtMs - workerLastVisionAt >= interval
    }

    fun beginSession(captureGeneration: Long = 0L) {
        cancelWork("بدأت جلسة مشاركة جديدة")
        this.captureGeneration = captureGeneration
        readerResetEpoch.incrementAndGet()
        guard.resetSession()
        stockMemory.reset()
        deckKnowledge.reset()
        scoreLedger.reset()
        hudClock.reset()
        lastScoreLogged = null
        currentRoundId = app.roundId
        RoundDiagnostics.reset(currentRoundId)
        lastDiagnosticBoardKey = null
        lastDecisionDiagnosticAt = 0L
        RoundDiagnostics.record("CAPTURE", "screen capture session initialized")
        outcomes.clear()
        LiveSession.beginSession(captureGeneration)
        monitor.aiStatus = "اضغط «لقطة وبدء تلقائي» عندما تظهر لوحة اللعبة؛ ستستمر القراءة والحركات تلقائيًا"
    }

    fun invalidateCapture(reason: String, newGeneration: Long = captureGeneration) {
        cancelWork(reason)
        captureGeneration = newGeneration
        readerResetEpoch.incrementAndGet()
        LiveSession.beginSession(newGeneration)
        outcomes.clear()
        monitor.aiStatus = reason
    }

    fun readNow(): CommandResult {
        if (mode != Mode.IDLE || LiveSession.moveInFlight)
            return reject("انتظر انتهاء القراءة أو الحركة الحالية، أو أوقفها أولًا")
        val problem = prerequisites(false)
        if (problem != null) return reject(problem)
        arm(Mode.READ)
        monitor.aiStatus = "انتقل إلى اللعبة؛ ستُقرأ صورتان جديدتان دون إرسال لمس"
        return CommandResult(true, monitor.aiStatus)
    }

    fun cancelRead(reason: String = "أُلغيت قراءة الأوراق") {
        if (mode == Mode.READ) cancelWork(reason)
    }

    /** Single move also reacquires two frames after its panel is moved/hidden. */
    fun manualMove(): CommandResult {
        if (mode != Mode.IDLE || LiveSession.moveInFlight)
            return reject("توجد قراءة أو حركة قيد العمل؛ أوقفها أو انتظر نهايتها")
        val problem = prerequisites(true)
        if (problem != null) return reject(problem)
        arm(Mode.MANUAL)
        onManualStarted()
        monitor.aiStatus = "بانتظار لوحة اللعبة لقراءة نقلة واحدة وتنفيذها ثم التحقق منها"
        return CommandResult(true, monitor.aiStatus)
    }

    fun startAuto(width: Int, height: Int): CommandResult {
        RoundDiagnostics.record("AUTO_REQUEST", "startAuto called; width=$width; height=$height; mode=$mode; capture=${monitor.captureActive}; monitoring=${monitor.monitoringEnabled}")
        if (mode == Mode.AUTO) return CommandResult(false, stopAuto("أوقف المستخدم التحريك التلقائي"))
        if (mode != Mode.IDLE || LiveSession.moveInFlight)
            return reject("أكمل القراءة أو النقلة الحالية قبل بدء التلقائي")
        val problem = prerequisites(true)
        if (problem != null) {
            RoundDiagnostics.record("AUTO_REJECT", "prerequisite: $problem")
            return reject(problem)
        }
        if (!app.matchesReferenceAspect(width, height)) {
            RoundDiagnostics.record("AUTO_REJECT", "capture aspect mismatch; width=$width; height=$height")
            return reject("أبعاد المشاركة تختلف عن المعايرة")
        }
        refreshRoundHistory()
        guard.start()
        if (!guard.enabled) {
            RoundDiagnostics.record("AUTO_REJECT", "guard: ${guard.stoppedBecause}")
            return reject(guard.stoppedBecause)
        }
        arm(Mode.AUTO)
        autoStartedAt = SystemClock.elapsedRealtime()
        failuresThisRun = 0
        readErrors = 0
        candidateCacheKey = null
        monitor.autoPlaying = true
        RoundDiagnostics.record("AUTO", "started; draw mode=${app.drawModePreference}; target=${targetAtStart ?: "unknown"}")
        monitor.aiStatus = "التلقائي جاهز؛ انتقل إلى اللعبة المعايرة. يمكنك إيقافه من الزر العائم"
        return CommandResult(true, monitor.aiStatus)
    }

    /** All terminal paths restore all control owners, even if guard already stopped. */
    fun stopAuto(reason: String): String { cancelWork(reason); return reason }

    private fun reject(message: String): CommandResult {
        monitor.aiStatus = message
        return CommandResult(false, message)
    }

    private fun prerequisites(execution: Boolean): String? {
        if (!monitor.captureActive) return "ابدأ مشاركة الشاشة أولًا"
        if (!monitor.monitoringEnabled) return "ابدأ المراقبة أولًا"
        if (!app.calibrated || !app.calibrationArmedForSession)
            return "احفظ اللقطة الأولى للعبة ليتم تحديد المناطق وقراءة الأوراق تلقائيًا"
        val target = targetPackage()
        if (target.isNullOrBlank() || target == context.packageName)
            return "لم تُربط المعايرة بتطبيق اللعبة؛ التقط لوحة اللعبة من جديد"
        if (execution && !app.manualExecutionEnabled) return "فعّل إذن التحريك من واجهة التطبيق"
        if (GestureAccessibilityService.instance == null)
            return "فعّل خدمة إمكانية الوصول لمساعد السوليتير"
        val examples = GlyphTemplates(context) // Independent immutable snapshot; not worker's instance.
        if (app.autoSetupReady) {
            if (!examples.automaticRecognitionReady) return "تعذر تحميل رموز التعرف المحلية؛ أعد تثبيت النسخة الكاملة"
        } else if (examples.glyphPatternCount == 0 || examples.ranksLearned == 0 || examples.suitsLearned == 0)
            return "يلزم لقطة أولى للتهيئة التلقائية، أو قوالب التعليم في الوضع اليدوي"
        return null
    }

    private fun refreshRoundHistory() {
        val round = app.roundId
        if (round != currentRoundId) {
            // A new game round is a new vision/solver session even when Android's
            // screen-capture session and calibrated layout remain unchanged.
            // Do not carry card identities, hidden-count proofs, or queued move
            // outcomes from the previous round into the new board.
            readerResetEpoch.incrementAndGet()
            outcomes.clear()
            guard.resetSession()
            stockMemory.reset()
            deckKnowledge.reset()
            scoreLedger.reset()
            hudClock.reset()
            lastScoreLogged = null
            candidateCacheKey = null
            candidateCache = emptyList()
            lastDiagnosticBoardKey = null
            lastDecisionDiagnosticAt = 0L
            lastSavedObservationKey = null
            lastSavedObservationAt = 0L
            RoundDiagnostics.reset(round)
            LiveSession.beginSession(captureGeneration)
            currentRoundId = round
        }
    }

    private fun arm(next: Mode) {
        refreshRoundHistory()
        workEpoch.incrementAndGet()
        LiveSession.clearObservations()
        commandStartedAt = SystemClock.elapsedRealtime()
        framesNotBefore = commandStartedAt + 150L
        firstValidFrameAt = 0L
        lastStableAt = 0L
        noCandidateFrames = 0
        noCandidateSince = 0L
        lastUnstableNoticeAt = 0L
        readErrors = 0
        candidateCacheKey = null
        lastSavedObservationKey = null
        lastSavedObservationAt = 0L
        lastStatusAt = 0L
        staleAnalyses = 0
        lastStaleNoticeAt = 0L
        targetAtStart = targetPackage()
        observedWindowRevision = null
        mode = next
    }

    private fun cancelWork(reason: String) {
        workEpoch.incrementAndGet()
        mode = Mode.IDLE
        candidateCacheKey = null
        val event = LiveSession.cancelPending(reason)
        event.action?.let { outcomes.add(MoveOutcome(it.before.sessionId, it.before.layoutRevision, it.move, false)) }
        event.action?.let { if (guard.blocked(it.before.board, it.move, it.before.evidence().wasteSignature))
            guard.markFailed(it.before.board, it.move, reason, it.before.evidence().wasteSignature) }
        guard.stop(reason)
        GestureAccessibilityService.instance?.cancelQueuedGestures()
        monitor.autoPlaying = false
        monitor.aiStatus = reason
        RoundDiagnostics.record("STOP", reason)
        // Always notify: a guard refusal may have changed enabled before reaching here.
        onAutoStopped()
        onReadFinished()
        onManualFinished()
    }

    /** Called by the service heartbeat on the main thread, including manual execution. */
    fun tick(now: Long = SystemClock.elapsedRealtime()) {
        LiveSession.pollTimeout(now)?.let { handleEvent(it) }
        if (mode == Mode.IDLE) return
        if (app.roundId != currentRoundId || !app.calibrationArmedForSession ||
            app.calibratedTargetPackage != targetAtStart) {
            cancelWork("تغيّرت الجولة أو المعايرة؛ أعد بدء القراءة")
            return
        }
        if ((mode == Mode.MANUAL || mode == Mode.AUTO) &&
            (!app.manualExecutionEnabled || GestureAccessibilityService.instance == null)) {
            cancelWork("ألغي إذن التحريك أو انقطعت خدمة إمكانية الوصول")
            return
        }
        if (observedWindowRevision != null && observedWindowRevision !=
            GestureAccessibilityService.foregroundWindowRevision) {
            cancelWork("تغيّرت نافذة اللعبة أو ظهر حوار؛ توقّف التنفيذ لإعادة القراءة")
            return
        }
        val visible = environmentValid() && GestureAccessibilityService.isTargetForeground(targetAtStart)
        if (!visible) {
            if (firstValidFrameAt != 0L || LiveSession.moveInFlight)
                cancelWork("غادرت شاشة اللعبة أو حجبتها نافذة أخرى؛ توقّف التحريك")
            else if (now - commandStartedAt >= 30_000L)
                cancelWork("لم تظهر شاشة اللعبة المعايرة خلال المهلة")
            return
        }
        if (mode == Mode.AUTO && (now - autoStartedAt >= 300_000L || app.remainingGameSeconds() == 0)) {
            cancelWork("انتهت مدة الجولة أو الحد الزمني للجلسة؛ توقف التحريك")
            return
        }
        if (!LiveSession.moveInFlight) {
            val lastGood = lastStableAt.takeIf { it != 0L } ?: commandStartedAt
            if (now - lastGood >= 30_000L) {
                if (mode == Mode.AUTO) {
                    // Unreadable cards are not an instruction to stop the five-minute
                    // session. Keep capturing, without authorizing an unverified tap.
                    if (now - lastUnstableNoticeAt >= 8_000L) {
                        lastUnstableNoticeAt = now
                        monitor.aiStatus = "اللوحة غير مستقرة أو بعض الأوراق غير مقروءة؛ " +
                            "المراقبة مستمرة بلا لمس حتى تظهر قراءة مؤكدة أو تنتهي الجولة"
                    }
                } else {
                    cancelWork("لم تصل قراءة مستقرة خلال 30 ثانية؛ تأكد من وضوح اللعبة وعدم وجود نافذة تغطي الأوراق")
                    return
                }
            }
            if (mode == Mode.READ && firstValidFrameAt != 0L && now - firstValidFrameAt >= 12_000L) {
                cancelWork("لم تستقر قراءة الأوراق؛ راجع المناطق والرموز غير المعروفة")
                return
            }
            if (mode == Mode.MANUAL || mode == Mode.AUTO) tryAction()
        }
    }

    /** Worker entry; frameAtMs is acquisition time, never OCR completion time. */
    @Synchronized fun observe(bitmap: Bitmap, frameAtMs: Long = SystemClock.elapsedRealtime(),
        frameId: Long = frameAtMs, captureGeneration: Long = this.captureGeneration) {
        if (mode == Mode.IDLE || captureGeneration != this.captureGeneration || frameAtMs < framesNotBefore) return
        if (frameAtMs - lastObserveDiagnosticAt >= 2_000L) {
            lastObserveDiagnosticAt = frameAtMs
            RoundDiagnostics.record("OBSERVE", "frame=$frameId; mode=$mode; size=${bitmap.width}x${bitmap.height}; generation=$captureGeneration")
        }
        val request = workEpoch.get()
        if (workerEpoch != request) { workerEpoch = request; workerLastVisionAt = 0L }
        // Regular moves can be checked more responsively; when no safe move
        // exists, poll at a slower rate instead of burning CPU for five minutes.
        if (!wantsFrame(frameAtMs)) return
        val layoutRevision = app.layoutRevision
        if (!observationInFlight.compareAndSet(false, true)) return
        workerLastVisionAt = frameAtMs
        var handedToOwner = false
        try {
            val resetToken = readerResetEpoch.get()
            if (readerCapture != captureGeneration || readerLayout != layoutRevision || workerReaderReset != resetToken) {
                vision.reset(captureGeneration, layoutRevision)
                readerCapture = captureGeneration
                readerLayout = layoutRevision
                workerReaderReset = resetToken
                workerManualProofId = null
            }
            while (true) {
                val done = outcomes.poll() ?: break
                if (done.capture == captureGeneration && done.layout == layoutRevision) {
                    if (done.confirmed) vision.confirmedMove(done.move) else vision.unconfirmedMove(done.move)
                }
            }
            glyphs.reloadIfChanged()
            val manual = app.loadRecentlyVerifiedBoard()
            val manualProofId = app.manualProofId
            if (manualProofId != null && manualProofId != workerManualProofId) {
                vision.invalidateManualReference()
                workerManualProofId = manualProofId
            }
            val needsReference = manual != null && vision.needsManualReference(manual)
            val proof = if (needsReference) app.loadRecentManualProof() else null
            if (request != workEpoch.get() || captureGeneration != this.captureGeneration ||
                (proof != null && app.manualProofId != manualProofId)) {
                proof?.snapshot?.bitmap?.let { if (!it.isRecycled) it.recycle() }
                return
            }
            val trustedManual = if (needsReference && (proof == null || proof.board != manual ||
                    proof.layoutRevision != layoutRevision)) null else manual
            val automatic = app.autoSetupReady
            val automaticReference = if (automatic && vision.needsAutomaticReference()) app.loadAutomaticReference() else null
            val readStartedAt = SystemClock.elapsedRealtime()
            val hud = hudReader.read(bitmap)
            val readout = try {
                vision.inspect(bitmap, app.loadLayout(), trustedManual,
                    app.calibrated && app.calibrationArmedForSession,
                    app.matchesReferenceAspect(bitmap.width, bitmap.height),
                    capturedAtMs = frameAtMs, frameId = frameId, sessionId = captureGeneration,
                    layoutRevision = layoutRevision, manualSnapshot = proof?.snapshot?.bitmap,
                    automatic = automatic, drawCount = app.drawModePreference.takeIf { it == 1 || it == 3 } ?: 3,
                    automaticSnapshot = automaticReference?.bitmap)
                    .copy(analysisDurationMs =
                        (SystemClock.elapsedRealtime() - readStartedAt).coerceAtLeast(0L),
                        hudScore = hud.score, hudSeconds = hud.remainingSeconds)
            } finally {
                proof?.snapshot?.bitmap?.let { if (!it.isRecycled) it.recycle() }
                automaticReference?.bitmap?.let { if (!it.isRecycled) it.recycle() }
            }
            if (frameAtMs - lastReadoutDiagnosticAt >= 1_500L) {
                lastReadoutDiagnosticAt = frameAtMs
                RoundDiagnostics.record("READOUT", "frame=$frameId; found=${readout.found}; confidence=${readout.confidence}; calibrated=${readout.calibrated}; unknown=${readout.unknownRegions.joinToString().ifBlank { "none" }}; warnings=${readout.warnings.take(2).joinToString().take(120)}")
            }
            handedToOwner = main.post {
                try {
                    if (request == workEpoch.get() && mode != Mode.IDLE &&
                        captureGeneration == this.captureGeneration && app.layoutRevision == layoutRevision)
                        accept(readout)
                } finally { observationInFlight.set(false) }
            }
        } catch (error: RuntimeException) {
            RoundDiagnostics.record("READ_ERROR", "${error.javaClass.simpleName}: ${error.message ?: "no details"}; mode=$mode")
            handedToOwner = main.post {
                try {
                    if (request == workEpoch.get() && mode != Mode.IDLE) {
                        if (mode == Mode.AUTO && environmentValid()) {
                            RoundDiagnostics.record("READ_ERROR", "${error.javaClass.simpleName}: ${error.message ?: "no details"}")
                            // Failed OCR/bitmap processing is never a reason to tap
                            // blindly or to terminate the timed session prematurely.
                            readErrors++
                            noCandidateFrames = 3
                            framesNotBefore = SystemClock.elapsedRealtime() + 650L
                            monitor.aiStatus = "فشل تحليل لقطة ($readErrors): ${error.javaClass.simpleName}؛ " +
                                "ستستمر المراقبة دون نقر حتى تصل قراءة سليمة"
                        } else cancelWork("تعذرت قراءة الأوراق: ${error.javaClass.simpleName}")
                    }
                } finally { observationInFlight.set(false) }
            }
        } finally {
            // Epoch changes and proof-validation returns still own this lease.
            // Arm/cancel never clear another worker's outstanding publication.
            if (!handedToOwner) observationInFlight.set(false)
        }
    }

    private fun accept(frame: VisionReadout) {
        if (!environmentValid() || !GestureAccessibilityService.isTargetForeground(targetAtStart)) {
            if (SystemClock.elapsedRealtime() - lastReadoutDiagnosticAt >= 1_500L) {
                lastReadoutDiagnosticAt = SystemClock.elapsedRealtime()
                RoundDiagnostics.record("ACCEPT_DROP", "environment/foreground invalid; environment=${environmentValid()}; targetForeground=${GestureAccessibilityService.isTargetForeground(targetAtStart)}")
            }
            return
        }
        if (frame.capturedAtMs < framesNotBefore ||
            frame.capturedAtMs < GestureAccessibilityService.foregroundObservedAtMs) {
            RoundDiagnostics.record("ACCEPT_DROP", "frame predates action/foreground fence; frame=${frame.capturedAtMs}; fence=${maxOf(framesNotBefore, GestureAccessibilityService.foregroundObservedAtMs)}")
            return
        }
        val revision = GestureAccessibilityService.foregroundWindowRevision
        if (observedWindowRevision != null && observedWindowRevision != revision) {
            cancelWork("تغيّرت نافذة اللعبة؛ أعد بدء القراءة")
            return
        }
        val now = SystemClock.elapsedRealtime()
        val age = now - frame.capturedAtMs
        if (age > LiveSession.FRAME_MAX_AGE_MS) {
            LiveSession.clearObservations()
            if (mode == Mode.AUTO) {
                staleAnalyses++
                if (now - lastStaleNoticeAt >= 1_000L) {
                    lastStaleNoticeAt = now
                    monitor.aiStatus = "وصلت قراءة متأخرة (عمرها ${age}ms)؛ " +
                        "استُبعدت دون لمس، جار طلب لقطة أحدث ($staleAnalyses)"
                    RoundDiagnostics.record("STALE_FRAME", "age=${age}ms; ocr=${frame.analysisDurationMs}ms; " +
                        "limit=${LiveSession.FRAME_MAX_AGE_MS}ms; count=$staleAnalyses")
                }
            }
            return
        }
        val event = LiveSession.acceptObservation(frame)
        if (event.result == LiveSession.Result.IGNORED) return
        observedWindowRevision = revision
        if (firstValidFrameAt == 0L) firstValidFrameAt = SystemClock.elapsedRealtime()
        if (event.result != LiveSession.Result.UNCHANGED) handleEvent(event)
        if (mode == Mode.IDLE || LiveSession.moveInFlight) return
        val stable = LiveSession.readout
        if (stable == null) {
            monitor.aiStatus = if (frame.stockBusy) "اللعبة تعرض مؤشر تحميل؛ بانتظار اختفائه قبل التحريك"
                else if (frame.found == 0) "لم تُعرف أوراق مؤكدة: ${frame.warnings.take(2).joinToString("؛ ")}"
                else "عُرفت ${frame.found} مواضع؛ بانتظار إطار مستقل مطابق"
            return
        }
        lastStableAt = stable.capturedAtMs
        val diagnosticBoardKey = stable.board.toString()
        if (diagnosticBoardKey != lastDiagnosticBoardKey) {
            RoundDiagnostics.record("BOARD", "stable; found=${stable.found}; confidence=${stable.confidence}; unknown=${stable.unknownRegions.joinToString().ifBlank { "none" }}")
            lastDiagnosticBoardKey = diagnosticBoardKey
        }
        readErrors = 0
        stockMemory.observe(stable.board, stable.evidence().wasteSignature)
        deckKnowledge.observe(stable.board)
        // A fresh stable board is always reconsidered. Failed edges remain blocked
        // by AutoPlayGuard, but one failed gesture must not quarantine the whole
        // visible board: another independently verified legal move may still work.
        val savedKey = stable.board.toString() + "|" + stable.unknownRegions.sorted().joinToString()
        if (app.autoSetupReady && (savedKey != lastSavedObservationKey ||
                stable.capturedAtMs - lastSavedObservationAt >= 1_000L)) {
            app.saveAutomaticObservation(stable.board, stable.summary(), stable.capturedAtMs,
                app.activeCaptureSessionId, stable.layoutRevision, stable.unknownRegions)
            lastSavedObservationKey = savedKey
            lastSavedObservationAt = stable.capturedAtMs
        }
        if (stable.board.foundations.values.all { it == 13 } && stable.observedFoundations.size == 4) {
            cancelWork("اكتمل ترتيب الأوراق في الأساسات الأربع؛ انتهى التشغيل")
            return
        }
        val layout = app.loadLayout()
        // Prefer an explicitly marked round clock; otherwise use the assistant's
        // own monotonic elapsed time (an estimate, not OCR of the game's timer).
        val (visualTime, visualScore) = hudClock.update(stable.hudSeconds, stable.hudScore)
        if (visualScore != null && visualScore != lastScoreLogged) {
            val previous = lastScoreLogged
            RoundDiagnostics.record("HUD_SCORE", "points=$visualScore" +
                (if (previous != null) "; delta=${visualScore-previous}" else "") +
                "; remaining=${visualTime ?: "unknown"}")
            lastScoreLogged = visualScore
        }
        val timeLeft = visualTime ?: app.remainingGameSeconds() ?: if (mode == Mode.AUTO)
            ((300_000L - (SystemClock.elapsedRealtime() - autoStartedAt)).coerceAtLeast(0L) / 1000).toInt()
            else null
        val strategyStarted = SystemClock.elapsedRealtime()
        val key = stable.board.toString() + "|" + stable.evidence().wasteSignature +
            "|covered=" + stable.coveredTableauBacks.sorted().joinToString() +
            "|" + LiveSession.recentSignatures().joinToString() +
            "|time=" + when { timeLeft == null -> 3; timeLeft <= 30 -> 0;
                timeLeft <= 65 -> 1; else -> 2 }
        val candidates = if (candidateCacheKey == key) candidateCache else
            SmartDecision.choose(stable.board,
                SolitaireSolver.candidates(stable.board, LiveSession.recentSignatures(), timeLeft,
                    observedCoveredBacks = stable.coveredTableauBacks),
                stockMemory, timeLeft, deckKnowledge).also {
                candidateCacheKey = key
                candidateCache = it
            }
        val decisionMs = (SystemClock.elapsedRealtime() - strategyStarted).coerceAtLeast(0L)
        val eligible = candidates.filter {
            !guard.blocked(stable.board, it.move, stable.evidence().wasteSignature) }
        val choice = eligible.firstOrNull { MoveExecutor.canLocate(it.move, stable, layout) }
        val decisionLogAt = SystemClock.elapsedRealtime()
        if (decisionLogAt - lastDecisionDiagnosticAt >= 1_500L) {
            RoundDiagnostics.record("DECISION", "candidates=${candidates.size}; eligible=${eligible.size}; selected=${choice?.move?.signature ?: "none"}; score=${choice?.score ?: "none"}")
            lastDecisionDiagnosticAt = decisionLogAt
        }
        val stockStats = stockMemory.summary()
        val scoreStats = scoreLedger.stats()
        val diagnostic = "قانونية ${candidates.size} / غير مكررة ${eligible.size} / قابلة للتنفيذ " +
            "${eligible.count { MoveExecutor.canLocate(it.move, stable, layout) }}" +
            " | قراءة ${stable.analysisDurationMs}ms، قرار ${decisionMs}ms" +
            " | مرور مخزون ${stockStats.pass}، كشف ${stockStats.distinctKnownWaste}" +
            " | أوراق معروفة ${deckKnowledge.knownCount()}/52" +
            " | خصم تدوير مؤكد ${stockStats.costPaid}، نقاط تقديرية ${scoreStats.estimatedBaseDelta}" +
            (if (visualTime != null) " | وقت اللعبة ${visualTime}s" else "") +
            (if (visualScore != null) " | النقاط ${visualScore}" else "")
        LiveSession.publishSuggestion(choice)
        if (choice != null || mode == Mode.READ || SystemClock.elapsedRealtime() - lastStatusAt >= 250L) {
            lastStatusAt = SystemClock.elapsedRealtime()
            monitor.aiStatus = stable.summary() + "\n$diagnostic" +
                if (choice != null) "\nالاقتراح: ${choice.reason}" else
                    "\nلا توجد حركة تستند إلى مواضع مؤكدة؛ غير معروف: ${stable.unknownRegions.joinToString()}"
        }
        if (mode == Mode.READ) {
            mode = Mode.IDLE
            workEpoch.incrementAndGet()
            onReadFinished()
            return
        }
        if (choice == null) {
            val t = SystemClock.elapsedRealtime()
            if (t - lastNoCandidateLogMs >= 4_000L) {
                lastNoCandidateLogMs = t
                RoundDiagnostics.record("NO_MOVE", "stock=${stable.board.stockStatus}; " +
                    "candidates=${candidates.size}; eligible=${eligible.size}; " +
                    "unknown=${stable.unknownRegions.size}; recycle=${stockMemory.canRecycle()}")
            }
            noCandidateFrames++
            if (noCandidateSince == 0L) noCandidateSince = SystemClock.elapsedRealtime()
            if (noCandidateFrames >= 3 && SystemClock.elapsedRealtime() - noCandidateSince >= 3_000L) {
                if (mode == Mode.AUTO) {
                    // A temporary unreadable fan is not the end of a round.
                    // Keep monitoring and resume automatically after a *new*
                    // stable screen state, without repeating a blocked edge.
                    monitor.aiStatus = "لا توجد نقلة مؤكدة حاليًا؛ المراقبة مستمرة حتى انتهاء الجولة. " +
                        "$diagnostic؛ المخزون: ${stable.board.stockStatus}؛ " +
                        "مناطق غير مقروءة: ${stable.unknownRegions.joinToString().ifBlank { "لا شيء" }}"
                } else cancelWork("لم تتوفر نقلة واحدة مؤكدة؛ لم يُرسل لمس")
            }
        } else {
            noCandidateFrames = 0
            noCandidateSince = 0L
            tryAction()
        }
    }

    private fun tryAction() {
        if ((mode != Mode.AUTO && mode != Mode.MANUAL) || LiveSession.moveInFlight) return
        if (mode == Mode.AUTO && !roundHasTime()) {
            cancelWork("انتهت مدة الجولة أو الحد الزمني للجلسة؛ توقف التحريك")
            return
        }
        if (LiveSession.readout == null || LiveSession.suggestion == null) return
        val automatic = mode == Mode.AUTO
        val attempt = MoveExecutor.dispatch(context, app.loadLayout(),
            environmentValid = { mode != Mode.IDLE && environmentValid() &&
                targetAtStart == targetPackage() && (mode != Mode.AUTO || roundHasTime()) },
            expectedPackage = targetAtStart,
            authorize = { frame, move -> guard.authorize(frame.board, move, requireAuto = automatic,
                visibleWaste = frame.evidence().wasteSignature) },
            onEvent = { handleEvent(it) })
        when (attempt.disposition) {
            MoveExecutor.Disposition.QUEUED -> {
                monitor.aiStatus = attempt.message
                RoundDiagnostics.record("ACTION", "${attempt.action?.move?.signature}; " +
                    "frame=${attempt.action?.before?.frameId}; " +
                    "age=${attempt.action?.let { it.requestedAtMs - it.before.capturedAtMs }}ms; " +
                    "stroke=${attempt.action?.gestureDurationMs}ms")
            }
            MoveExecutor.Disposition.WAIT -> Unit // Transient readiness is not a failed physical move.
            MoveExecutor.Disposition.REJECTED -> cancelWork(attempt.message)
        }
    }

    private fun roundHasTime(): Boolean =
        SystemClock.elapsedRealtime() - autoStartedAt < 300_000L && app.remainingGameSeconds() != 0

    private fun handleEvent(event: LiveSession.Event) {
        when (event.result) {
            LiveSession.Result.DELIVERED -> {
                monitor.aiStatus = event.message
                val action = event.action
                RoundDiagnostics.record("TOUCH", (action?.move?.signature ?: "unknown") +
                    "; delivered=${action?.let { (it.deliveredAtMs ?: it.requestedAtMs) - it.requestedAtMs }}ms; " +
                    LiveSession.pacingDescription())
            }
            LiveSession.Result.CONFIRMED -> {
                val action = event.action ?: return
                outcomes.add(MoveOutcome(action.before.sessionId, action.before.layoutRevision, action.move, true))
                val loop = event.after?.let {
                    stockMemory.verified(action.before.board, action.move, it.board, it.evidence().wasteSignature,
                        observedCoveredBacks = action.before.coveredTableauBacks)
                    scoreLedger.verified(action.before.board, action.move, it.board)
                    RoundDiagnostics.record("CONFIRMED", "${action.move.signature}; " +
                        "delta~${scoreLedger.stats().estimatedBaseDelta}; " +
                        "knownWaste=${stockMemory.summary().distinctKnownWaste}; " +
                        "cycle=${SystemClock.elapsedRealtime() - action.requestedAtMs}ms; " +
                        "verify=${it.capturedAtMs - (action.deliveredAtMs ?: action.requestedAtMs)}ms; " +
                        "ocr=${it.analysisDurationMs}ms; age=${SystemClock.elapsedRealtime() - it.capturedAtMs}ms")
                    guard.noteVerifiedProgress(action.before.board, action.move, it.board,
                        observedCoveredBacks = action.before.coveredTableauBacks)
                    guard.markConfirmed(it.board, it.evidence().wasteSignature)
                }
                // Failed touches separated by real, visually verified progress
                // must not accumulate into a false three-failure shutdown.
                failuresThisRun = 0
                        monitor.aiStatus = event.message
                if (loop != null) {
                    // A cycle is not permission to repeat the same edge. Park and
                    // continue watching for OTHER untried moves or a real screen
                    // change. The guard retains its attempted-state history.
                    if (mode == Mode.AUTO) {
                        guard.start()
                        if (!guard.enabled) { cancelWork(guard.stoppedBecause); return }
                        noCandidateFrames = 3
                        noCandidateSince = SystemClock.elapsedRealtime()
                        monitor.aiStatus = "تكررت حالة اللوحة؛ لن تُعاد حركاتها السابقة. " +
                            "ستستمر المراقبة حتى ظهور تغيير مفيد أو انتهاء الجولة"
                    } else { cancelWork(loop); return }
                }
                if (mode == Mode.MANUAL) {
                    mode = Mode.IDLE
                    workEpoch.incrementAndGet()
                    onManualFinished()
                }
                // AUTO can use these already-verified fresh frames immediately.
                // A second fixed cooldown would waste time without adding evidence.
            }
            LiveSession.Result.FAILED, LiveSession.Result.EXPIRED, LiveSession.Result.CANCELLED -> {
                event.action?.let {
                    RoundDiagnostics.record("FAILED", "${it.move.signature}: ${event.result}; ${event.message}")
                    outcomes.add(MoveOutcome(it.before.sessionId, it.before.layoutRevision, it.move, false))
                }
                event.action?.let { if (guard.blocked(it.before.board, it.move, it.before.evidence().wasteSignature))
                    guard.markFailed(it.before.board, it.move, event.message,
                        it.before.evidence().wasteSignature) }
                // Do not repeat the failed edge. At most two recoveries read new frames and choose
                // a DIFFERENT legal action. Session/window changes always stop immediately.
                val canRecover = event.result != LiveSession.Result.CANCELLED && event.action != null &&
                    mode == Mode.AUTO && environmentValid() &&
                    GestureAccessibilityService.isTargetForeground(targetAtStart) &&
                    observedWindowRevision == GestureAccessibilityService.foregroundWindowRevision
                if (canRecover) {
                    failuresThisRun++
                    GestureAccessibilityService.instance?.cancelQueuedGestures()
                    guard.start()
                    if (!guard.enabled) { cancelWork(guard.stoppedBecause); return }
                    workEpoch.incrementAndGet()
                    LiveSession.clearObservations()
                    framesNotBefore = SystemClock.elapsedRealtime() + 550L
                    noCandidateFrames = 0; noCandidateSince = 0L
                    monitor.aiStatus = "لم تتأكد الحركة؛ حُظرت هذه النقلة من الحالة الحالية. " +
                        "سيعيد المحرك تحليل اللوحة وتجربة بديل مؤكد " +
                        "(إخفاقات متتابعة: $failuresThisRun)"
                } else cancelWork(event.message)
            }
            LiveSession.Result.UNCHANGED, LiveSession.Result.IGNORED -> Unit
        }
    }
}
