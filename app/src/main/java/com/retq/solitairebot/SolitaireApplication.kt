package com.retq.solitairebot

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.retq.solitairebot.capture.ScreenCaptureService
import com.retq.solitairebot.storage.CaptureMonitorStore

/** One foreground owner covers every assistant screen, including permission and editor flows. */
class SolitaireApplication : Application(), Application.ActivityLifecycleCallbacks {
    private var visibleActivities = 0
    private val main = Handler(Looper.getMainLooper())
    private val publishBackground = Runnable { if (visibleActivities == 0) publish(false) }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
    }

    private fun publish(visible: Boolean) {
        val state = CaptureMonitorStore(this)
        val changed = state.assistantUiVisible != visible
        state.assistantUiVisible = visible // Immediate check by queued gesture dispatch, before service command.
        if (changed && state.captureActive) {
            val action = if (visible) ScreenCaptureService.ACTION_APP_FOREGROUND
                else ScreenCaptureService.ACTION_APP_BACKGROUND
            try {
                startService(Intent(this, ScreenCaptureService::class.java).setAction(action))
            } catch (_: RuntimeException) {
                // The immediate runtime flag still blocks dispatch; the service heartbeat owns cancellation.
                state.aiStatus = "تغيّرت واجهة التطبيق؛ ينتظر المساعد تأكيد شاشة اللعبة"
            }
        }
    }

    override fun onActivityStarted(activity: Activity) {
        visibleActivities++
        main.removeCallbacks(publishBackground)
        publish(true)
    }
    override fun onActivityStopped(activity: Activity) {
        visibleActivities = (visibleActivities - 1).coerceAtLeast(0)
        // Coalesce a transition between two assistant activities. Do not briefly allow game touches.
        main.post(publishBackground)
    }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
