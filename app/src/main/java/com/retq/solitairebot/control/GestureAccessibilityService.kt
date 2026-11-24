package com.retq.solitairebot.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.retq.solitairebot.storage.CaptureMonitorStore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Explicitly enabled Android service. Tracks window package, never reads window text. */
class GestureAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: GestureAccessibilityService? = null
            private set
        @Volatile var foregroundPackageName: String? = null
            private set
        @Volatile var foregroundObservedAtMs = 0L
            private set
        @Volatile var foregroundWindowId: Int? = null
            private set
        @Volatile var foregroundWindowRevision = 0L
            private set

        fun isTargetForeground(expected: String?): Boolean =
            !expected.isNullOrBlank() && instance != null && foregroundPackageName == expected
    }

    private val dispatchEpoch = AtomicLong(0)
    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        foregroundPackageName = null
        foregroundObservedAtMs = 0L
        foregroundWindowId = null
        foregroundWindowRevision++
        dispatchEpoch.incrementAndGet()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val name = event.packageName?.toString()?.takeIf { it.isNotBlank() } ?: return
        // A TYPE_APPLICATION_OVERLAY window belongs to this package even while the
        // underlying game remains foreground. Do not replace the game identity with
        // our own non-focusable control panel. The Activity lifecycle is the source
        // of truth for whether a real assistant screen is visible, and when it is,
        // the regular event below MUST invalidate the game identity.
        if (name == packageName && !CaptureMonitorStore(this).assistantUiVisible) return
        if (foregroundPackageName != name || foregroundWindowId != event.windowId) {
            dispatchEpoch.incrementAndGet()
            foregroundWindowRevision++
        }
        foregroundPackageName = name
        foregroundWindowId = event.windowId
        foregroundObservedAtMs = SystemClock.elapsedRealtime()
    }

    /** Invalidate queued dispatch work; already delivered touches cannot be recalled. */
    fun cancelQueuedGestures() { dispatchEpoch.incrementAndGet() }

    override fun onInterrupt() {
        cancelQueuedGestures()
        foregroundPackageName = null
        foregroundWindowId = null
        foregroundWindowRevision++
    }

    override fun onDestroy() {
        cancelQueuedGestures()
        if (instance === this) {
            instance = null
            foregroundPackageName = null
            foregroundObservedAtMs = 0L
            foregroundWindowId = null
            foregroundWindowRevision++
        }
        super.onDestroy()
    }

    fun sendGesture(
        from: PointF,
        to: PointF?,
        expectedPackage: String?,
        durationMs: Long = if (to == null) ExecutionPacing.INITIAL_TAP_MS else ExecutionPacing.INITIAL_DRAG_MS,
        isCurrent: () -> Boolean,
        beforeDispatch: () -> String? = { null },
        onDone: (Boolean, String) -> Unit
    ) {
        val requestEpoch = dispatchEpoch.get()
        val once = AtomicBoolean(false)
        fun complete(ok: Boolean, message: String) {
            if (once.compareAndSet(false, true)) onDone(ok, message)
        }
        // Queue on the main looper but repeat ALL authorization checks when it runs.
        main.post {
            if (instance !== this || requestEpoch != dispatchEpoch.get() || !isCurrent()) {
                complete(false, "ألغي طلب الحركة أو تغيّرت الجلسة قبل إرسال اللمس")
                return@post
            }
            if (expectedPackage == packageName || !isTargetForeground(expectedPackage)) {
                complete(false, "شاشة اللعبة المعايرة ليست في المقدمة؛ لم يُرسل لمس")
                return@post
            }
            val metrics = android.util.DisplayMetrics()
            val manager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            manager.defaultDisplay.getRealMetrics(metrics)
            fun valid(p: PointF): Boolean = p.x.isFinite() && p.y.isFinite() &&
                p.x >= 0f && p.y >= 0f && p.x < metrics.widthPixels && p.y < metrics.heightPixels
            if (!valid(from) || (to != null && !valid(to))) {
                complete(false, "إحداثيات اللمس خارج الشاشة؛ لم تُنفّذ الحركة")
                return@post
            }
            val refusal = beforeDispatch()
            if (refusal != null) { complete(false, refusal); return@post }
            // No further asynchronous gap between the authorization and dispatch.
            try {
                val path = Path().apply {
                    moveTo(from.x, from.y)
                    if (to != null) lineTo(to.x, to.y)
                }
                val description = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0,
                        durationMs.coerceIn(if (to == null) 35L else 110L, if (to == null) 100L else 350L)))
                    .build()
                val queued = dispatchGesture(description, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        complete(true, "انتهى إرسال اللمس؛ يلزم التحقق من الأوراق")
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        complete(false, "ألغى النظام إيماءة اللمس")
                    }
                }, main)
                if (!queued) complete(false, "لم يقبل النظام إيماءة اللمس")
            } catch (error: RuntimeException) {
                complete(false, "تعذر إرسال اللمس: ${error.javaClass.simpleName}")
            }
        }
    }
}
