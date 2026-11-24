package com.retq.solitairebot.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/** Session state is deliberately process-local: process death cannot report a live capture. */
class CaptureMonitorStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("solitaire_v07", Context.MODE_PRIVATE)

    private object Runtime {
        @Volatile var captureActive = false
        @Volatile var monitoringEnabled = true
        @Volatile var assistantUiVisible = true
        @Volatile var overlayVisible = false
        @Volatile var overlayStatus = "تظهر الأزرار بعد منح الإذن وبدء المشاركة"
        @Volatile var captureStatus = "لم تبدأ مشاركة الشاشة"
        @Volatile var capturedFrames = 0L
        @Volatile var frameDetails = "بانتظار أول إطار"
        @Volatile var aiStatus = "لم تبدأ قراءة الأوراق"
        @Volatile var autoPlaying = false
        @Volatile var automaticPreparing = false
    }

    var overlayEnabled: Boolean
        get() = prefs.getBoolean("overlay_enabled", true)
        set(value) { prefs.edit().putBoolean("overlay_enabled", value).apply() }
    var captureActive: Boolean
        get() = Runtime.captureActive
        set(value) { Runtime.captureActive = value }
    var monitoringEnabled: Boolean
        get() = Runtime.monitoringEnabled
        set(value) { Runtime.monitoringEnabled = value }
    var assistantUiVisible: Boolean
        get() = Runtime.assistantUiVisible
        set(value) { Runtime.assistantUiVisible = value }
    var overlayVisible: Boolean
        get() = Runtime.overlayVisible
        set(value) { Runtime.overlayVisible = value }
    var overlayStatus: String
        get() = Runtime.overlayStatus
        set(value) { Runtime.overlayStatus = value }
    var captureStatus: String
        get() = Runtime.captureStatus
        set(value) { Runtime.captureStatus = value }
    var capturedFrames: Long
        get() = Runtime.capturedFrames
        set(value) { Runtime.capturedFrames = value }
    var frameDetails: String
        get() = Runtime.frameDetails
        set(value) { Runtime.frameDetails = value }

    var aiStatus: String
        get() = Runtime.aiStatus
        set(value) { Runtime.aiStatus = value }
    var autoPlaying: Boolean
        get() = Runtime.autoPlaying
        set(value) { Runtime.autoPlaying = value }

    var automaticPreparing: Boolean
        get() = Runtime.automaticPreparing
        set(value) { Runtime.automaticPreparing = value }

    fun snapshotFile(): File = File(app.filesDir, "last_board.png")

    /** Encode outside the request lock; commit only if the same session/request still owns it. */
    fun saveSnapshot(bitmap: Bitmap, commitLock: Any, shouldCommit: () -> Boolean,
        onCommitted: () -> Unit = {}): Boolean {
        // A stopping worker may still encode while a new capture session starts.
        val temp = File.createTempFile("capture-", ".tmp", app.filesDir)
        try {
            temp.outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "تعذر ترميز الصورة" }
            }
            return synchronized(commitLock) {
                if (!shouldCommit()) false
                else {
                    // Both files are in filesDir, so rename replaces the previous PNG atomically.
                    check(temp.renameTo(snapshotFile())) { "تعذر حفظ الصورة على الجهاز" }
                    onCommitted()
                    true
                }
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    fun loadSnapshot(): Bitmap? = try {
        snapshotFile().takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.absolutePath) }
    } catch (_: Exception) { null }
}
