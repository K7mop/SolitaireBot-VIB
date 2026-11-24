import android.os.*
import android.content.Context
import android.graphics.Bitmap
import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.retq.solitairebot.control.*
import com.retq.solitairebot.core.*
import com.retq.solitairebot.storage.*
import com.retq.solitairebot.vision.*

/**
 * Deterministic event-loop benchmark of PRODUCTION LivePipeline + LiveSession +
 * MoveExecutor + GestureAccessibilityService. OCR is a fixture, game animation
 * takes 60 ms after touch completion, and callbacks run on the fake main looper.
 * It measures controller overhead, NOT phone FPS or actual game score.
 */
private fun benchBoard(moved: Boolean): BoardState = BoardState(
    listOf(TableauColumn(0, if (moved) emptyList() else listOf(Card(1, Suit.SPADES))),
        TableauColumn(0, listOf(Card(2, Suit.SPADES)))) + List(5) { TableauColumn(0, emptyList()) },
    Suit.entries.associateWith { if (it == Suit.SPADES && moved) 1 else 0 }, null, StockStatus.UNKNOWN)
private fun benchFrame(moved: Boolean, at: Long): VisionReadout {
    val b = benchBoard(moved)
    val positions = mutableMapOf<String, CardPosition>()
    b.columns.forEachIndexed { i, col -> col.top?.let {
        val position = CardPosition(i * 100 + 10, 700, 80, 120, it, 1f)
        positions["T$i"] = position; positions["T$i:0"] = position
    } }
    if (moved) positions["FS"] = CardPosition(10, 400, 80, 120, Card(1, Suit.SPADES), 1f)
    val conf = (0..6).associate { "T$it" to 1f } + positions.mapValues { 1f } +
        Suit.entries.associate { "F${it.symbol}" to 1f } + mapOf("W" to 1f, "S" to 1f)
    val evidence = ObservationEvidence(observedColumns = b.columns.indices.filter { b.columns[it].top != null }.toSet(),
        emptyColumns = b.columns.indices.filter { b.columns[it].definitelyEmpty }.toSet(), completeColumns = (0..6).toSet(),
        observedFoundations = b.foundations.filterValues { it > 0 }.keys,
        emptyFoundations = b.foundations.filterValues { it == 0 }.keys, wasteEmpty = true, regionConfidences = conf)
    return VisionReadout(b, positions, positions.size, 1f, true, emptyList(), at, 1080, 2316,
        Suit.entries.toSet(), b.foundations.filterValues { it > 0 }.keys,
        capturedAtMs = at, frameId = at, sessionId = 7, layoutRevision = 1, observationEvidence = evidence)
}
fun main(args: Array<String>) {
    val captureInterval = args.firstOrNull()?.toLong() ?: 33L
    val expectedUpperBound = args.getOrNull(1)?.toLong() ?: 450L
    Handler.scheduled.clear(); AccessibilityService.deliveries.clear()
    AppStore.allowed = true; CaptureMonitorStore.activityVisible = false
    SystemClock.now = 100_000L
    val service = GestureAccessibilityService(); service.onServiceConnected()
    service.onAccessibilityEvent(AccessibilityEvent())
    val context = Context(); val monitor = CaptureMonitorStore(context)
    val pipeline = LivePipeline(context, monitor, {}, {})
    pipeline.beginSession(7); check(pipeline.startAuto(1080, 2316).accepted)
    var firstDispatch = 0L
    var secondDispatch = 0L
    var completed = false
    var visibleAfter = Long.MAX_VALUE
    var sampled = 0
    for (elapsed in 0L..6_000L) {
        val time = 100_000L + elapsed
        SystemClock.now = time
        if (firstDispatch != 0L && !completed) {
            val delivery = AccessibilityService.deliveries.first()
            val duration = delivery.first.strokes.single().duration
            if (time >= firstDispatch + duration) {
                delivery.second.onCompleted(delivery.first)
                completed = true
                visibleAfter = time + 60L
            }
        }
        if (elapsed % captureInterval == 0L) {
            val frame = benchFrame(time >= visibleAfter, time)
            pipeline.observe(Bitmap(frame), time, time, 7); sampled++
        }
        if (elapsed % 250L == 0L) pipeline.tick(time)
        Handler.runReady()
        if (firstDispatch == 0L && AccessibilityService.deliveries.isNotEmpty()) firstDispatch = time
        if (AccessibilityService.deliveries.size == 2) { secondDispatch = time; break }
    }
    check(firstDispatch > 0L && secondDispatch > firstDispatch)
    val duration = secondDispatch - firstDispatch
    println("production controller cycle=${duration}ms; capture interval=${captureInterval}ms; " +
        "stroke=${AccessibilityService.deliveries.first().first.strokes.single().duration}ms; " +
        "first dispatch=${firstDispatch - 100_000L}ms; next dispatch=${secondDispatch - 100_000L}ms; sampled=$sampled")
    check(duration <= expectedUpperBound) { "Controller cycle ${duration}ms exceeded ${expectedUpperBound}ms regression budget" }
    println("PASS: two sequential production-controller actions completed within the stated latency budget")
}
