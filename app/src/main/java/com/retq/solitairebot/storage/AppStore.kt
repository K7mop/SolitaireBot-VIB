package com.retq.solitairebot.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import com.retq.solitairebot.capture.BattleArenaPortraitProfile
import com.retq.solitairebot.capture.NormalizedBoardLayout
import com.retq.solitairebot.capture.NormalizedBox
import com.retq.solitairebot.core.BoardState
import com.retq.solitairebot.core.Card
import com.retq.solitairebot.core.StockStatus
import com.retq.solitairebot.core.Suit
import com.retq.solitairebot.core.TableauColumn
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Persistent geometry/templates and process-local capture/round evidence have distinct lifetimes. */
class AppStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("solitaire_v07", Context.MODE_PRIVATE)

    data class SnapshotIdentity(
        val id: String, val sessionId: String, val capturedAtMs: Long,
        val width: Int, val height: Int, val targetPackage: String?,
        val sha256: String, val roundId: String?
    )
    data class SnapshotData(val bitmap: Bitmap, val identity: SnapshotIdentity)
    data class ManualProof(val board: BoardState, val snapshot: SnapshotData, val layoutRevision: Long)
    data class AutomaticObservation(val board: BoardState, val summary: String, val capturedAtMs: Long,
        val sessionId: String, val layoutRevision: Long, val unknownRegions: Set<String> = emptySet())
    private object AutomaticRuntime {
        @Volatile var observation: AutomaticObservation? = null
        @Volatile var status = "احفظ لقطة أولى واضحة للعبة لتهيئتها تلقائيًا"
    }

    companion object {
        const val CALIBRATION_MAX_AGE_MS = 10 * 60_000L
        private val runtime = CaptureRoundState()
    }
    val activeCaptureSessionId: String? get() = runtime.sessionId
    val roundId: String? get() = runtime.roundId
    val layoutRevision: Long get() = prefs.getLong("layout_revision", 0L)
    val calibrationSnapshotId: String? get() = prefs.getString("calibration_snapshot_id", null)
    val calibratedTargetPackage: String? get() = if (calibrationArmedForSession)
        prefs.getString("calibration_target_package", null) else null

    /** Call once for every new capture or geometry generation; never resurrect a persisted session. */
    fun beginCaptureSession(id: String, preserveRound: Boolean = false) {
        runtime.beginSession(id, preserveRound)
        invalidateCalibrationProof()
        invalidateManualVerification()
        clearAutomaticObservation()
    }
    fun endCaptureSession() {
        runtime.endSession()
        invalidateCalibrationProof()
        invalidateManualVerification()
        clearAutomaticObservation()
    }

    var captureStatus: String
        get() = prefs.getString("capture_status", "لم يبدأ الالتقاط") ?: "لم يبدأ الالتقاط"
        set(value) = prefs.edit().putString("capture_status", value).apply()

    /** Only the explicit 'بدأت الجولة الآن' action calls this; capture/reading never starts the clock. */
    fun startGameTimer() {
        runtime.startRound(UUID.randomUUID().toString(), SystemClock.elapsedRealtime())
        invalidateManualVerification()
    }
    fun remainingGameSeconds(): Int? = runtime.remainingSeconds(SystemClock.elapsedRealtime())

    var capturedFrames: Long
        get() = prefs.getLong("captured_frames", 0L)
        set(value) = prefs.edit().putLong("captured_frames", value).apply()
    var overlayEnabled: Boolean
        get() = prefs.getBoolean("overlay_enabled", false)
        set(value) = prefs.edit().putBoolean("overlay_enabled", value).apply()

    /** Setting true cannot arm a saved layout without fresh proof; saveLayout is the arming operation. */
    var calibrationArmedForSession: Boolean
        get() = calibrated && activeCaptureSessionId != null &&
            prefs.getBoolean("calibration_armed", false) &&
            prefs.getString("calibration_session_id", null) == activeCaptureSessionId &&
            !calibrationSnapshotId.isNullOrBlank()
        set(value) {
            if (!value) invalidateCalibrationProof()
            // A legacy caller cannot manufacture evidence by setting a Boolean.
        }
    /** One initial capture is the explicit start action; analysis remains local. */
    var autoStartAfterSnapshot: Boolean
        get() = prefs.getBoolean("auto_start_after_snapshot", true)
        set(value) = prefs.edit().putBoolean("auto_start_after_snapshot", value).apply()
    /** 0=automatic stock tap (count is not guessed), 1/3=known game setting. */
    var drawModePreference: Int
        get() = prefs.getInt("draw_mode", 1).takeIf { it == 0 || it == 1 || it == 3 } ?: 1
        set(value) { require(value == 0 || value == 1 || value == 3); prefs.edit().putInt("draw_mode", value).apply() }
    val autoSetupReady: Boolean get() = calibrationArmedForSession && prefs.getBoolean("automatic_layout", false)
    var automaticSetupStatus: String
        get() = AutomaticRuntime.status
        set(value) { AutomaticRuntime.status = value }
    fun automaticObservation(): AutomaticObservation? = AutomaticRuntime.observation?.takeIf {
        it.sessionId == activeCaptureSessionId && it.layoutRevision == layoutRevision && autoSetupReady
    }
    fun saveAutomaticObservation(board: BoardState, summary: String, capturedAtMs: Long,
        expectedSession: String?, expectedLayout: Long, unknownRegions: Set<String> = emptySet()) {
        if (expectedSession == null || activeCaptureSessionId != expectedSession ||
            expectedLayout != layoutRevision || !autoSetupReady || board.problems().isNotEmpty()) return
        AutomaticRuntime.observation = AutomaticObservation(board, summary, capturedAtMs, expectedSession, expectedLayout, unknownRegions.toSet())
    }
    fun clearAutomaticObservation() {
        AutomaticRuntime.observation = null
        AutomaticRuntime.status = "احفظ لقطة أولى واضحة للعبة لتهيئتها تلقائيًا"
    }
    /** The bound initial PNG seeds geometry/empty/back recognition, never manual verification. */
    fun loadAutomaticReference(): SnapshotData? {
        if (!autoSetupReady) return null
        val bound = calibrationSnapshotId ?: return null
        if (!isSnapshotCurrent(bound, CALIBRATION_MAX_AGE_MS)) return null
        val loaded = loadSnapshotWithIdentity() ?: return null
        if (!autoSetupReady || loaded.identity.id != bound || calibrationSnapshotId != bound) {
            loaded.bitmap.recycle(); return null
        }
        return loaded
    }
    var practiceOnlyAcknowledged: Boolean
        get() = prefs.getBoolean("practice_only_ack", false)
        set(value) = prefs.edit().putBoolean("practice_only_ack", value).apply()
    var manualExecutionEnabled: Boolean
        get() = prefs.getBoolean("manual_execution_enabled", false)
        set(value) = prefs.edit().putBoolean("manual_execution_enabled", value).apply()
    var recentSuggestion: String
        get() = prefs.getString("suggestion", "لا توجد نقلة مؤكدة") ?: "لا توجد نقلة مؤكدة"
        set(value) = prefs.edit().putString("suggestion", value).apply()
    var recognizedCards: String
        get() = prefs.getString("recognized", "لم تتم قراءة الأوراق بعد") ?: "لم تتم قراءة الأوراق بعد"
        set(value) = prefs.edit().putString("recognized", value).apply()

    fun snapshotFile(): File = File(appContext.filesDir, "last_board.png")

    /** Invoke on capture worker immediately after a current-session PNG commit, under the commit lock. */
    fun recordSnapshot(captureSessionId: String, frameAtMs: Long, width: Int, height: Int,
        targetPackage: String?): String? {
        if (captureSessionId != activeCaptureSessionId || width <= 0 || height <= 0 ||
            SystemClock.elapsedRealtime() - frameAtMs !in 0L..15_000L) return null
        val file = snapshotFile()
        if (!file.isFile) return null
        val digest = file.inputStream().use { stream ->
            val hash = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(16_384)
            while (true) { val n = stream.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
            hash.digest().joinToString("") { "%02x".format(it) }
        }
        if (captureSessionId != activeCaptureSessionId) return null
        val id = UUID.randomUUID().toString()
        val obj = JSONObject().put("id", id).put("session", captureSessionId)
            .put("at", frameAtMs).put("width", width).put("height", height)
            .put("package", targetPackage ?: JSONObject.NULL).put("sha256", digest)
            .put("round", runtime.roundForFrame(frameAtMs) ?: JSONObject.NULL)
        prefs.edit().putString("snapshot_identity", obj.toString()).apply()
        return id
    }

    fun snapshotIdentity(): SnapshotIdentity? = try {
        val raw = prefs.getString("snapshot_identity", null)
        if (raw == null) null else JSONObject(raw).let { o ->
            SnapshotIdentity(o.getString("id"), o.getString("session"), o.getLong("at"),
                o.getInt("width"), o.getInt("height"),
                if (o.isNull("package")) null else o.getString("package"), o.getString("sha256"),
                if (o.isNull("round")) null else o.getString("round"))
        }
    } catch (_: Exception) { null }

    fun isSnapshotCurrent(snapshotId: String, maxAgeMs: Long = 120_000L): Boolean {
        val saved = snapshotIdentity() ?: return false
        return SnapshotProofPolicy.current(saved.id, snapshotId, saved.sessionId, activeCaptureSessionId,
            saved.capturedAtMs, SystemClock.elapsedRealtime(), maxAgeMs)
    }
    private fun requireSnapshot(id: String, maxAgeMs: Long = 120_000L): SnapshotIdentity {
        val saved = snapshotIdentity()
        require(saved != null && isSnapshotCurrent(id, maxAgeMs)) {
            "هذه اللقطة قديمة أو من مشاركة أخرى؛ احفظ لقطة جديدة للعبة وأعد فتح الأداة"
        }
        return saved
    }

    /** Blocking decode/hash only: every UI caller runs it on its worker executor. */
    fun loadSnapshotWithIdentity(): SnapshotData? {
        val identity = snapshotIdentity() ?: return null
        return try {
            val bytes = snapshotFile().readBytes()
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            if (digest != identity.sha256 || snapshotIdentity()?.id != identity.id) return null
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            if (bitmap.width != identity.width || bitmap.height != identity.height ||
                snapshotIdentity()?.id != identity.id) { bitmap.recycle(); return null }
            SnapshotData(bitmap, identity)
        } catch (_: Exception) { null }
    }
    fun loadSnapshot(): Bitmap? = try {
        snapshotFile().takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.absolutePath) }
    } catch (_: Exception) { null }

    private fun storedLayout(): NormalizedBoardLayout? {
        val raw = prefs.getString("layout", null) ?: return null
        return try {
            val data = JSONObject(raw)
            fun box(key: String): NormalizedBox = decodeBox(data.getJSONArray(key))
            fun boxes(key: String): List<NormalizedBox> = data.getJSONArray(key).let { arr ->
                (0 until arr.length()).map { decodeBox(arr.getJSONArray(it)) }
            }
            NormalizedBoardLayout(box("stock"), box("waste"), boxes("foundations"), boxes("columns"),
                if (data.has("screenBounds")) box("screenBounds") else NormalizedBox(0f, 0f, 1f, 1f))
        } catch (_: Exception) {
            prefs.edit().putBoolean("calibrated", false).remove("layout").apply()
            invalidateCalibrationProof()
            null
        }
    }
    fun loadLayout(): NormalizedBoardLayout = storedLayout() ?: BattleArenaPortraitProfile.boardLayout

    fun saveLayout(layout: NormalizedBoardLayout, referenceWidth: Int, referenceHeight: Int,
        snapshotId: String, automatic: Boolean = false) {
        val proof = requireSnapshot(snapshotId, CALIBRATION_MAX_AGE_MS)
        require(referenceWidth == proof.width && referenceHeight == proof.height) { "أبعاد المعايرة لا تطابق اللقطة" }
        val obj = JSONObject().put("stock", encodeBox(layout.stock)).put("waste", encodeBox(layout.waste))
            .put("foundations", JSONArray(layout.foundations.map(::encodeBox)))
            .put("columns", JSONArray(layout.tableauColumns.map(::encodeBox)))
            .put("screenBounds", encodeBox(layout.screenBounds))
        prefs.edit().putString("layout", obj.toString()).putBoolean("calibrated", true)
            .putBoolean("automatic_layout", automatic)
            .putBoolean("calibration_armed", true).putString("calibration_session_id", proof.sessionId)
            .putString("calibration_snapshot_id", proof.id).putString("calibration_target_package", proof.targetPackage)
            .putLong("layout_revision", layoutRevision + 1)
            .putFloat("calibration_aspect", referenceWidth.toFloat() / referenceHeight).apply()
        invalidateManualVerification()
    }
    @Deprecated("Pass the exact loaded snapshot ID")
    fun saveLayout(layout: NormalizedBoardLayout, referenceWidth: Int, referenceHeight: Int) {
        throw IllegalStateException("أعد فتح المعايرة من لقطة حالية محددة")
    }
    private fun invalidateCalibrationProof() {
        prefs.edit().putBoolean("calibration_armed", false).remove("calibration_session_id")
            .remove("calibration_snapshot_id").remove("calibration_target_package")
            .putBoolean("automatic_layout", false).apply()
    }
    val calibrated: Boolean get() = prefs.getBoolean("calibrated", false) && storedLayout() != null
    fun matchesReferenceAspect(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0 || !calibrated) return false
        val expected = prefs.getFloat("calibration_aspect", -1f)
        return expected > 0 && kotlin.math.abs(width.toFloat() / height - expected) < 0.005f
    }

    fun saveBoard(board: BoardState, snapshotId: String) {
        val proof = requireSnapshot(snapshotId)
        require(proof.roundId == roundId) { "تغيرت الجولة بعد التقاط الصورة؛ احفظ لقطة من الجولة الحالية" }
        require(board.problems().isEmpty()) { board.problems().joinToString("\n") }
        val json = JSONObject().put("columns", JSONArray(board.columns.map { col ->
            JSONObject().put("hidden", col.hiddenCount ?: JSONObject.NULL)
                .put("back", col.exposedBack).put("cards", JSONArray(col.faceUp.map { it.code }))
        })).put("foundations", JSONObject().apply {
            board.foundations.forEach { (s, rank) -> put(s.symbol.toString(), rank) }
        }).put("waste", board.waste?.code ?: JSONObject.NULL).put("stock", board.stockStatus.name)
            .put("redeals", board.redealsRemaining ?: JSONObject.NULL)
            .put("drawCount", board.drawCount).put("recycleAvailable", board.recycleAvailable)
        prefs.edit().putString("manual_board", json.toString())
            .putLong("manual_verified_elapsed", SystemClock.elapsedRealtime())
            .putString("manual_snapshot_id", proof.id).putString("manual_session_id", proof.sessionId)
            .putString("manual_round_id", proof.roundId).putLong("manual_layout_revision", layoutRevision).apply()
    }
    @Deprecated("Pass the exact loaded snapshot ID")
    fun saveBoard(board: BoardState) { throw IllegalStateException("أعد فتح مراجعة اللوحة من لقطة حالية محددة") }

    fun loadRecentlyVerifiedBoard(): BoardState? {
        val proofId = prefs.getString("manual_snapshot_id", null) ?: return null
        if (!isSnapshotCurrent(proofId) || prefs.getString("manual_session_id", null) != activeCaptureSessionId ||
            prefs.getString("manual_round_id", null) != roundId ||
            prefs.getLong("manual_layout_revision", -1L) != layoutRevision ||
            SystemClock.elapsedRealtime() - prefs.getLong("manual_verified_elapsed", -1L) !in 0L..120_000L) return null
        return loadBoard()
    }

    /** Metadata cache key only; obtaining it never decodes a bitmap. */
    val manualProofId: String? get() = if (loadRecentlyVerifiedBoard() != null)
        prefs.getString("manual_snapshot_id", null) else null

    /**
     * Worker-only reference for visual verification. The caller owns/recycles the returned bitmap.
     * Manual empty-region evidence must come from these approved pixels, never from a new live frame.
     */
    fun loadRecentManualProof(): ManualProof? {
        val before = manualProofId ?: return null
        val revision = layoutRevision
        val board = loadRecentlyVerifiedBoard() ?: return null
        val snapshot = loadSnapshotWithIdentity() ?: return null
        if (snapshot.identity.id != before || manualProofId != before || layoutRevision != revision) {
            snapshot.bitmap.recycle()
            return null
        }
        return ManualProof(board, snapshot, revision)
    }
    fun invalidateManualVerification() {
        prefs.edit().remove("manual_snapshot_id").remove("manual_session_id")
            .remove("manual_round_id").remove("manual_verified_elapsed").putLong("manual_verified_at", 0L).apply()
    }
    fun loadBoard(): BoardState? = try {
        val raw = prefs.getString("manual_board", null)
        if (raw == null) null else {
            val obj = JSONObject(raw)
            val cols = obj.getJSONArray("columns")
            val columns = (0 until cols.length()).map { i ->
                val col = cols.getJSONObject(i); val cards = col.getJSONArray("cards")
                TableauColumn(if (col.isNull("hidden")) null else col.getInt("hidden"),
                    (0 until cards.length()).map { j -> Card.parse(cards.getString(j)) ?: error("Bad card") },
                    col.optBoolean("back", false))
            }
            val f = obj.getJSONObject("foundations")
            BoardState(columns, Suit.entries.associateWith { f.getInt(it.symbol.toString()) },
                if (obj.isNull("waste")) null else Card.parse(obj.getString("waste")) ?: error("Bad waste"),
                StockStatus.valueOf(obj.getString("stock")),
                if (obj.isNull("redeals")) null else obj.getInt("redeals"),
                obj.optInt("drawCount", 3), obj.optBoolean("recycleAvailable", false)).takeIf { it.problems().isEmpty() }
        }
    } catch (_: Exception) { null }

    private fun encodeBox(box: NormalizedBox) = JSONArray().put(box.left.toDouble())
        .put(box.top.toDouble()).put(box.right.toDouble()).put(box.bottom.toDouble())
    private fun decodeBox(arr: JSONArray) = NormalizedBox(arr.getDouble(0).toFloat(), arr.getDouble(1).toFloat(),
        arr.getDouble(2).toFloat(), arr.getDouble(3).toFloat())
}
