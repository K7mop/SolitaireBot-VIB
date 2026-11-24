package com.retq.solitairebot.control

import com.retq.solitairebot.core.*
import com.retq.solitairebot.vision.CardPosition
import com.retq.solitairebot.vision.VisionReadout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Pure state tests: every clock is explicit; no Android runtime calls are needed. */
class LiveSessionTest {
    private val ace = Card(1, Suit.SPADES)
    private val two = Card(2, Suit.SPADES)
    private val move = Move.TableauToFoundation(0, ace)
    private fun board(moved: Boolean = false) = BoardState(
        listOf(TableauColumn(0, if (moved) emptyList() else listOf(ace)), TableauColumn(0, listOf(two))) +
            List(5) { TableauColumn(0, emptyList()) },
        Suit.entries.associateWith { if (it == Suit.SPADES && moved) 1 else 0 }, null, StockStatus.UNKNOWN)
    private fun frame(moved: Boolean, at: Long, frameId: Long = at, confidence: Float = 1f): VisionReadout {
        val b = board(moved)
        val positions = mutableMapOf<String, CardPosition>()
        b.columns.forEachIndexed { i, col -> col.top?.let {
            positions["T$i"] = CardPosition(i * 100 + 10, 700, 80, 120, it, 1f)
        } }
        if (moved) positions["FS"] = CardPosition(100, 400, 80, 120, ace, 1f)
        val conf = ((0..6).associate { "T$it" to 1f } + Suit.entries.associate { "F${it.symbol}" to 1f } +
            mapOf("W" to 1f)).toMutableMap().also { it["T0"] = confidence }
        val evidence = ObservationEvidence(observedColumns = (0..6).toSet(),
            emptyColumns = b.columns.indices.filter { b.columns[it].definitelyEmpty }.toSet(),
            completeColumns = (0..6).toSet(), observedFoundations = Suit.entries.toSet(),
            emptyFoundations = b.foundations.filterValues { it == 0 }.keys,
            wasteObserved = true, wasteEmpty = true, regionConfidences = conf)
        return VisionReadout(b, positions, positions.size, 1f, true, emptyList(), at,
            1080, 2316, Suit.entries.toSet(), if (moved) setOf(Suit.SPADES) else emptySet(),
            capturedAtMs = at, frameId = frameId, sessionId = 7L, layoutRevision = 1L, observationEvidence = evidence)
    }
    @Before fun reset() { LiveSession.beginSession(7L) }
    private fun pending(at: Long = 11_600L): LiveSession.Action {
        LiveSession.acceptObservation(frame(false, at - 500), at - 500)
        val fresh = frame(false, at)
        LiveSession.acceptObservation(fresh, at)
        LiveSession.publishSuggestion(Suggestion(move, 100, "fixture"))
        return checkNotNull(LiveSession.beginAction(fresh, move, at))
    }
    @Test fun `queued action has a deadline even without a gesture callback`() {
        pending()
        val event = LiveSession.pollTimeout(14_100L)
        assertEquals(LiveSession.Result.EXPIRED, event?.result)
        assertFalse(LiveSession.moveInFlight)
        assertNull(LiveSession.pollTimeout(15_000L))
    }
    @Test fun `cancel invalidates queued dispatch`() {
        val action = pending()
        assertTrue(LiveSession.canDispatch(action.id, 11_650L))
        LiveSession.cancelPending("stop")
        assertFalse(LiveSession.canDispatch(action.id, 11_700L))
    }
    @Test fun `late callback cannot change a newer pending action`() {
        val first = pending()
        LiveSession.cancelPending("new request")
        val next = pending(16_000L)
        assertEquals(LiveSession.Result.IGNORED,
            LiveSession.gestureResult(first.id, false, nowMs = 16_100L).result)
        assertTrue(LiveSession.isCurrent(next.id))
    }
    @Test fun `verification requires two independent post delivery frames`() {
        val action = pending()
        assertEquals(LiveSession.Result.DELIVERED,
            LiveSession.gestureResult(action.id, true, nowMs = 12_000L).result)
        assertEquals(LiveSession.Result.UNCHANGED,
            LiveSession.acceptObservation(frame(true, 14_500), 14_500).result)
        assertEquals(LiveSession.Result.IGNORED,
            LiveSession.acceptObservation(frame(true, 14_600, 14_500), 14_600).result)
        assertEquals(LiveSession.Result.CONFIRMED,
            LiveSession.acceptObservation(frame(true, 15_000), 15_000).result)
    }
    @Test fun `old frame cannot be relabelled fresh by OCR completion`() {
        assertEquals(LiveSession.Result.IGNORED,
            LiveSession.acceptObservation(frame(false, 10_000), 15_000).result)
        assertNull(LiveSession.readout)
    }
    @Test fun `other capture generation is ignored`() {
        assertEquals(LiveSession.Result.IGNORED,
            LiveSession.acceptObservation(frame(false, 10_000).copy(sessionId = 6), 10_000).result)
    }
    @Test fun `verification checks endpoint confidence not board average`() {
        val action = pending()
        LiveSession.gestureResult(action.id, true, nowMs = 12_000L)
        LiveSession.acceptObservation(frame(true, 14_500, confidence = .89f), 14_500)
        assertEquals(LiveSession.Result.UNCHANGED,
            LiveSession.acceptObservation(frame(true, 15_000, confidence = .89f), 15_000).result)
        assertTrue(LiveSession.moveInFlight)
    }
    @Test fun `verification timeout is a single terminal event`() {
        val action = pending()
        LiveSession.gestureResult(action.id, true, nowMs = 12_000L)
        assertEquals(LiveSession.Result.EXPIRED, LiveSession.pollTimeout(20_000L)?.result)
        assertNull(LiveSession.pollTimeout(20_500L))
        assertEquals(LiveSession.Result.IGNORED,
            LiveSession.gestureResult(action.id, true, nowMs = 21_000L).result)
    }

    @Test fun `fresh frame cannot pair with an expired earlier observation`() {
        LiveSession.acceptObservation(frame(false, 10_000L), 10_000L)
        val afterGap = frame(false, 11_500L)
        LiveSession.acceptObservation(afterGap, 11_500L)
        assertNull(LiveSession.readout)
        LiveSession.publishSuggestion(Suggestion(move, 100, "fixture"))
        assertNull(LiveSession.beginAction(afterGap, move, 11_500L))
        val secondFresh = frame(false, 12_000L)
        LiveSession.acceptObservation(secondFresh, 12_000L)
        assertSame(secondFresh, LiveSession.readout)
        LiveSession.publishSuggestion(Suggestion(move, 100, "fixture"))
        assertNotNull(LiveSession.beginAction(secondFresh, move, 12_000L))
    }

    @Test fun `post action verification also requires both frames within freshness budget`() {
        val action = pending()
        LiveSession.gestureResult(action.id, true, nowMs = 12_000L)
        LiveSession.acceptObservation(frame(true, 12_500L), 12_500L)
        assertEquals(LiveSession.Result.UNCHANGED,
            LiveSession.acceptObservation(frame(true, 14_000L), 14_000L).result)
        assertTrue(LiveSession.moveInFlight)
        assertEquals(LiveSession.Result.CONFIRMED,
            LiveSession.acceptObservation(frame(true, 14_500L), 14_500L).result)
    }
}
