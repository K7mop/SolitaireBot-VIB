package com.retq.solitairebot.capture

import com.retq.solitairebot.control.FloatingControls
import com.retq.solitairebot.control.LivePipeline
import com.retq.solitairebot.control.RoundDiagnostics
import com.retq.solitairebot.control.GestureAccessibilityService
import com.retq.solitairebot.storage.AppStore
import com.retq.solitairebot.storage.CaptureMonitorStore
import com.retq.solitairebot.ui.MainActivity
import com.retq.solitairebot.vision.CardVision
import com.retq.solitairebot.vision.GlyphTemplates
import com.retq.solitairebot.vision.VisionReadout

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Live capture + calibrated on-device card reader; gesture use is explicit and opt-in. */
class ScreenCaptureService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val EXTRA_FROM_NOTIFICATION = "from_notification"
        const val ACTION_START = "com.retq.solitairebot.capture.START"
        const val ACTION_STOP = "com.retq.solitairebot.capture.STOP"
        const val ACTION_SNAPSHOT = "com.retq.solitairebot.capture.SNAPSHOT"
        const val ACTION_REFRESH_OVERLAY = "com.retq.solitairebot.capture.REFRESH_OVERLAY"
        const val ACTION_TOGGLE_MONITORING = "com.retq.solitairebot.capture.TOGGLE_MONITORING"
        const val ACTION_READ_BOARD = "com.retq.solitairebot.capture.READ_BOARD"
        const val ACTION_EXECUTE_MOVE = "com.retq.solitairebot.capture.EXECUTE_MOVE"
        const val ACTION_TOGGLE_AUTO = "com.retq.solitairebot.capture.TOGGLE_AUTO"
        const val ACTION_START_AUTO = "com.retq.solitairebot.capture.START_AUTO"
        const val ACTION_MARK_ROUND_START = "com.retq.solitairebot.capture.MARK_ROUND_START"
        const val ACTION_STOP_AUTO = "com.retq.solitairebot.capture.STOP_AUTO"
        const val ACTION_APP_FOREGROUND = "com.retq.solitairebot.capture.APP_FOREGROUND"
        const val ACTION_APP_BACKGROUND = "com.retq.solitairebot.capture.APP_BACKGROUND"
        private const val CHANNEL_ID = "solitaire_capture_monitor_v1"
        private const val NOTIFICATION_ID = 1001
    }

    private val store by lazy { CaptureMonitorStore(this) }
    private val appStore by lazy { AppStore(this) }
    private val live by lazy { LivePipeline(this, store,
        onAutoStopped = { restoreAutoOverlay() },
        onReadFinished = { restoreReadOverlay() },
        onManualStarted = { prepareWorkSurface() },
        onManualFinished = { restoreReadOverlay() },
        environmentValid = { capturing.get() && !stopping.get() && store.monitoringEnabled &&
            !store.assistantUiVisible && capturedContentVisible && workSurfaceClear },
        targetPackage = { appStore.calibratedTargetPackage }) }
    private val ui = Handler(Looper.getMainLooper())
    private val capturing = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    private val generation = AtomicLong(0)
    private val frameSequence = AtomicLong(0)
    @Volatile private var captureConfig = CaptureConfig()
    @Volatile private var captureSessionId: String? = null
    @Volatile private var workSurfaceClear = false
    @Volatile private var workFramesAfterMs = 0L
    private val frameBuffers = ConcurrentHashMap<ImageReader, Pair<Handler, RgbaFrameBuffer>>()
    private val snapshotGate = CaptureRequestGate()
    private val automaticGate = AutomaticSetupGate()
    private var worker: HandlerThread? = null
    private var workerHandler: Handler? = null
    @Volatile private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var floating: FloatingControls? = null
    private var densityDpi = 0
    private var snapshotHiddenFor: Long? = null
    private var snapshotHideAfterMs = 0L
    @Volatile private var snapshotSurfaceClear = false
    @Volatile private var capturedContentVisible = true
    @Volatile private var lastAnalysisAtMs = 0L
    @Volatile private var lastPipelineSampleDiagnosticAtMs = 0L
    @Volatile private var lastFrameDetailsAtMs = 0L
    private var lastNotificationAtMs = 0L
    private var lastNotificationText = ""
    private var lastOverlayCheckMs = 0L

    private val lockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) finishSession("توقفت المشاركة عند قفل الشاشة")
        }
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            if (!capturing.get()) return
            val now = SystemClock.elapsedRealtime()
            val request = snapshotGate.pendingRequestId
            live.tick(now)
            if (request != null) {
                if (snapshotGate.expire(now, request)) {
                    cancelAutomaticSetup("انتهت مهلة اللقطة؛ لم يبدأ التلقائي")
                    restoreOverlay(request)
                    store.captureStatus = "لم تصل لقطة جديدة خلال 15 ثانية؛ أغلق الإشعارات واطلب اللقطة مجددًا"
                } else if (snapshotHiddenFor == null && now >= snapshotHideAfterMs &&
                    !store.assistantUiVisible && capturedContentVisible) {
                    prepareSnapshot(request)
                }
            }
            if (now - lastOverlayCheckMs >= 1_500L && snapshotHiddenFor == null) {
                lastOverlayCheckMs = now
                synchronizeOverlay()
            }
            publishState()
            ui.postDelayed(this, 500L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(lockReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(lockReceiver, filter)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_START) {
            if (capturing.get()) { synchronizeOverlay(); return START_NOT_STICKY }
            val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            @Suppress("DEPRECATION")
            val data = if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            else intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
            if (code != Activity.RESULT_OK || data == null) {
                store.captureStatus = "لم تبدأ المشاركة؛ يلزم إذن مشاركة جديد من التطبيق"
                stopSelf(startId)
                return START_NOT_STICKY
            }
            try {
                enterForeground()
                beginSession(code, data)
            } catch (error: Exception) {
                finishSession("تعذر بدء الالتقاط: ${error.javaClass.simpleName}")
            }
            return START_NOT_STICKY
        }
        if (!capturing.get()) {
            if (action != ACTION_STOP) store.captureStatus = "ابدأ مشاركة الشاشة من التطبيق أولًا"
            stopSelf(startId)
            return START_NOT_STICKY
        }
        when (action) {
            ACTION_STOP -> finishSession("أوقفت مشاركة الشاشة")
            ACTION_SNAPSHOT -> queueSnapshot(intent.getBooleanExtra(EXTRA_FROM_NOTIFICATION, false))
            ACTION_REFRESH_OVERLAY -> {
                cancelAutomaticSetup("أُلغيت التهيئة عند فتح لوحة التحكم")
                live.stopAuto("أُعيد فتح لوحة التحكم؛ توقفت الحركة والقراءة")
                restoreOverlay(snapshotGate.pendingRequestId)
                snapshotGate.cancel()
                store.overlayEnabled = true
                synchronizeOverlay()
                floating?.finishActiveControl()
            }
            ACTION_TOGGLE_MONITORING -> {
                cancelAutomaticSetup("أُلغيت التهيئة عند تغيير المراقبة")
                if (store.monitoringEnabled) live.stopAuto("أُوقفت المراقبة؛ توقفت الحركة والقراءة")
                store.monitoringEnabled = !store.monitoringEnabled
                store.captureStatus = if (store.monitoringEnabled) "استؤنف فحص الإطارات"
                    else "فحص الإطارات متوقف مؤقتًا؛ مشاركة الشاشة ما زالت نشطة"
            }
            ACTION_READ_BOARD -> {
                cancelAutomaticSetup("انتقلت إلى القراءة اليدوية")
                if (snapshotGate.pendingRequestId != null) {
                    store.aiStatus = "أكمل حفظ اللقطة الحالية أولًا"
                } else if (!store.monitoringEnabled) store.aiStatus = "ابدأ المراقبة أولًا"
                else {
                    val result = live.readNow()
                    if (result.accepted && !prepareWorkSurface()) {
                        live.stopAuto("لا توجد مساحة لزر الإيقاف خارج مناطق الأوراق؛ راجع حدود المعايرة")
                    }
                }
            }
            ACTION_EXECUTE_MOVE -> {
                cancelAutomaticSetup("انتقلت إلى نقلة واحدة")
                if (snapshotGate.pendingRequestId != null) store.aiStatus = "انتظر اكتمال حفظ اللقطة"
                else if (!store.monitoringEnabled) store.aiStatus = "ابدأ المراقبة أولًا"
                else if (!prepareWorkSurface()) store.aiStatus = "تعذر وضع زر الإيقاف خارج مناطق الأوراق؛ راجع المعايرة"
                else {
                    val result = live.manualMove()
                    if (!result.accepted) restoreReadOverlay()
                }
            }
            ACTION_STOP_AUTO -> {
                cancelAutomaticSetup("أوقف المستخدم التهيئة والحركة والقراءة")
                val cancelled = snapshotGate.pendingRequestId
                snapshotGate.cancel(cancelled); restoreOverlay(cancelled)
                live.stopAuto("أوقف المستخدم الحركة والقراءة")
            }
            ACTION_TOGGLE_AUTO, ACTION_START_AUTO -> {
                RoundDiagnostics.record("SERVICE_AUTO_COMMAND", "action=${action.substringAfterLast('.')}; preparing=${store.automaticPreparing}; alreadyAuto=${live.autoPlaying}; snapshotPending=${snapshotGate.pendingRequestId != null}")
                if (store.automaticPreparing) {
                    cancelAutomaticSetup("أوقف المستخدم التهيئة التلقائية")
                    val cancelled = snapshotGate.pendingRequestId
                    snapshotGate.cancel(cancelled); restoreOverlay(cancelled)
                    live.stopAuto("أُوقفت التهيئة التلقائية")
                }
                else if (live.autoPlaying && action == ACTION_TOGGLE_AUTO) live.stopAuto("أوقف المستخدم التشغيل التلقائي")
                else if (live.autoPlaying) store.aiStatus = "التنفيذ التلقائي يعمل بالفعل"
                else if (snapshotGate.pendingRequestId != null) store.aiStatus = "انتظر اكتمال حفظ اللقطة"
                else if (!prepareWorkSurface()) {
                    RoundDiagnostics.record("AUTO_REJECT", "prepareWorkSurface returned false")
                    store.aiStatus = "تعذر وضع زر الإيقاف خارج مناطق الأوراق؛ راجع المعايرة"
                } else {
                    store.monitoringEnabled = true
                    val result = live.startAuto(reader?.width ?: 0, reader?.height ?: 0)
                    RoundDiagnostics.record("AUTO_RESULT", "accepted=${result.accepted}; message=${result.message}")
                    if (!result.accepted) restoreAutoOverlay()
                }
            }
            ACTION_MARK_ROUND_START -> {
                cancelAutomaticSetup("بدأت جولة جديدة أثناء التهيئة")
                live.stopAuto("بدأت جولة جديدة؛ ابدأ التنفيذ عندما تظهر أوراق اللعبة")
                appStore.startGameTimer()
                live.beginSession(generation.get())
                store.aiStatus = "بدأ عدّاد الجولة: 5 دقائق. الأوراق موزعة في اللعبة؛ اضغط تشغيل تلقائي عندما تكون جاهزًا"
            }
            ACTION_APP_FOREGROUND -> {
                cancelAutomaticSetup("عُدت إلى المساعد أثناء التهيئة؛ لم يبدأ التلقائي")
                store.assistantUiVisible = true
                live.stopAuto("عُدت إلى المساعد؛ توقفت الحركة والقراءة حتى تبدأهما من جديد")
            }
            ACTION_APP_BACKGROUND -> store.assistantUiVisible = false
        }
        if (capturing.get()) publishState(force = true)
        return START_NOT_STICKY
    }

    private fun enterForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID,
            "مراقبة مشاركة الشاشة", NotificationManager.IMPORTANCE_LOW))
        val notification = buildNotification("جار بدء مشاركة الشاشة")
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(summary: String): Notification {
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun action(label: String, code: Int, name: String): Notification.Action {
            val intent = Intent(this, ScreenCaptureService::class.java).setAction(name)
                .putExtra(EXTRA_FROM_NOTIFICATION, true)
            val pending = PendingIntent.getService(this, code, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            return Notification.Action.Builder(Icon.createWithResource(this,
                android.R.drawable.ic_menu_view), label, pending).build()
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("مساعد السوليتير — مراقبة الشاشة")
            .setContentText(summary)
            .setStyle(Notification.BigTextStyle().bigText(summary))
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(action("إيقاف التلقائي", 5, ACTION_STOP_AUTO))
            .addAction(action("إظهار الأزرار", 3, ACTION_REFRESH_OVERLAY))
            .addAction(action("إيقاف", 4, ACTION_STOP))
            .build()
    }

    private fun beginSession(resultCode: Int, permission: Intent) {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val size = if (Build.VERSION.SDK_INT >= 30) {
            wm.maximumWindowMetrics.bounds.let { it.width() to it.height() }
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }
        require(size.first > 0 && size.second > 0)
        cancelAutomaticSetup()
        captureConfig = CaptureConfig(size.first, size.second)
        densityDpi = resources.displayMetrics.densityDpi
        synchronized(snapshotGate) {
            generation.incrementAndGet()
            snapshotGate.cancel()
            snapshotSurfaceClear = false
        }
        capturedContentVisible = true
        captureSessionId = UUID.randomUUID().toString().also(appStore::beginCaptureSession)
        stopping.set(false)
        worker = HandlerThread("SolitaireCaptureMonitor").also { it.start() }
        workerHandler = Handler(worker!!.looper)
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val owner = checkNotNull(manager.getMediaProjection(resultCode, permission))
        projection = owner
        projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                ui.post {
                    if (projection === owner) finishSession("أوقف النظام أو المستخدم مشاركة الشاشة")
                }
            }
            override fun onCapturedContentResize(width: Int, height: Int) {
                if (width > 0 && height > 0) ui.post {
                    if (projection === owner) resizeCapture(width, height)
                }
            }
            override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                ui.post {
                    if (projection === owner) {
                        capturedContentVisible = isVisible
                        if (!isVisible) {
                            cancelAutomaticSetup("لم تعد اللعبة ظاهرة أثناء التهيئة")
                            live.stopAuto("لم تعد شاشة اللعبة ظاهرة؛ توقف التنفيذ")
                        }
                    }
                }
            }
        }.also { owner.registerCallback(it, workerHandler) }
        reader = createReader(captureConfig.targetWidth, captureConfig.targetHeight)
        display = projection!!.createVirtualDisplay("SolitaireCaptureMonitor", size.first, size.second, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, workerHandler)
        synchronized(snapshotGate) {
            capturing.set(true)
            store.captureActive = true
        }
        store.monitoringEnabled = true
        store.capturedFrames = 0
        store.frameDetails = "بانتظار أول إطار"
        store.captureStatus = "مشاركة الشاشة تعمل؛ انتقل إلى شاشة التجربة"
        appStore.calibrationArmedForSession = false
        live.beginSession(generation.get())
        lastAnalysisAtMs = 0L
        lastFrameDetailsAtMs = 0L
        synchronizeOverlay()
        ui.removeCallbacks(heartbeat)
        ui.post(heartbeat)
    }

    private fun createReader(width: Int, height: Int): ImageReader {
        val handler = checkNotNull(workerHandler)
        val buffer = RgbaFrameBuffer()
        return ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).also {
            frameBuffers[it] = handler to buffer
            it.setOnImageAvailableListener({ incoming -> processFrame(incoming, buffer) }, handler)
        }
    }

    private fun releaseReader(old: ImageReader?) {
        if (old == null) return
        try { old.setOnImageAvailableListener(null, null) } catch (_: Exception) { }
        val owned = frameBuffers.remove(old)
        // Reader/bitmap cleanup is ordered after every already running decoder on its own worker.
        if (owned != null) owned.first.post {
            try { old.close() } catch (_: Exception) { }
            owned.second.close()
        } else try { old.close() } catch (_: Exception) { }
    }

    /** Projection/surface lifecycle is serialized on the UI thread with begin/stop. */
    private fun resizeCapture(width: Int, height: Int) {
        val previous = reader
        if (!capturing.get() || stopping.get() || (previous?.width == width && previous.height == height)) return
        cancelAutomaticSetup("تغير حجم الشاشة؛ التقط لقطة جديدة")
        val cancelledRequest = synchronized(snapshotGate) {
            generation.incrementAndGet()
            val request = snapshotGate.pendingRequestId
            snapshotGate.cancel(request)
            snapshotSurfaceClear = false
            request
        }
        var replacement: ImageReader? = null
        try {
            val next = createReader(width, height)
            replacement = next
            val currentDisplay = checkNotNull(display)
            currentDisplay.resize(width, height, densityDpi)
            currentDisplay.surface = next.surface
            reader = next
            captureConfig = CaptureConfig(width, height)
            replacement = null // The active session now owns this reader.
            releaseReader(previous)
            lastAnalysisAtMs = 0L
            store.captureStatus = "تغير حجم الالتقاط إلى $width × $height؛ أعد طلب اللقطة إن لزم"
        } catch (error: Exception) {
            releaseReader(replacement)
            finishSession("تعذر تغيير حجم الالتقاط: ${error.javaClass.simpleName}")
            return
        }
        appStore.calibrationArmedForSession = false
        captureSessionId = UUID.randomUUID().toString().also { appStore.beginCaptureSession(it, preserveRound = true) }
        live.invalidateCapture("تغيّرت أبعاد الالتقاط؛ يلزم إعادة اعتماد المعايرة", generation.get())
        restoreReadOverlay()
        if (cancelledRequest != null) restoreOverlay(cancelledRequest)
        publishState(force = true)
    }

    private fun queueSnapshot(fromNotification: Boolean) {
        cancelAutomaticSetup()
        live.stopAuto("توقفت الحركة لحفظ اللقطة الأولى وقراءتها")
        if (appStore.autoStartAfterSnapshot) {
            appStore.calibrationArmedForSession = false
            appStore.clearAutomaticObservation()
            store.automaticPreparing = true
            appStore.automaticSetupStatus = "بانتظار اللقطة الأولى؛ يليها تحديد المناطق والقراءة تلقائيًا"
            store.aiStatus = appStore.automaticSetupStatus
        }
        val now = SystemClock.elapsedRealtime()
        val old = synchronized(snapshotGate) {
            val previous = snapshotGate.pendingRequestId
            snapshotGate.cancel(previous)
            val requested = snapshotGate.start(now, fromNotification)
            if (appStore.autoStartAfterSnapshot) automaticGate.begin(generation.get(), requested)
            previous
        }
        restoreOverlay(old)
        snapshotHideAfterMs = now + if (fromNotification) 3_000L else 200L
        store.captureStatus = if (fromNotification)
            "أغلق لوحة الإشعارات خلال 3 ثوانٍ؛ ستُحفظ الشاشة الظاهرة بعدها"
        else if (store.assistantUiVisible)
            "ارجع إلى الشاشة المطلوبة خلال 15 ثانية لحفظ لقطة"
        else "جار تجهيز لقطة دون الأزرار العائمة"
        publishState(force = true)
    }

    /** Hide on the UI thread, drain old buffers on the capture thread, then accept a later frame. */
    private fun prepareSnapshot(request: Long) {
        if (!snapshotGate.isCurrent(request)) return
        snapshotHiddenFor = request
        snapshotSurfaceClear = false
        floating?.hideForCapture()
        store.overlayVisible = false
        val session = generation.get()
        workerHandler?.post {
            if (!capturing.get() || generation.get() != session || !snapshotGate.isCurrent(request)) return@post
            try { reader?.acquireLatestImage()?.close() } catch (_: IllegalStateException) { }
            synchronized(snapshotGate) {
                if (capturing.get() && generation.get() == session &&
                    snapshotGate.markOverlayHidden(SystemClock.elapsedRealtime(), request)) {
                    snapshotSurfaceClear = true
                }
            }
        }
    }

    private fun restoreOverlay(request: Long?) {
        if (request != null && snapshotHiddenFor != request) return
        if (snapshotHiddenFor != null) floating?.restoreAfterCapture()
        snapshotHiddenFor = null
        snapshotSurfaceClear = false
        store.overlayVisible = floating?.isVisible == true
    }

    private fun processFrame(incoming: ImageReader, buffer: RgbaFrameBuffer) {
        // Closed readers can still have queued callbacks after rotation or stop.
        val image = try { incoming.acquireLatestImage() } catch (_: IllegalStateException) { null } ?: return
        image.use { frame ->
            if (!capturing.get() || incoming !== reader) return
            val now = SystemClock.elapsedRealtime()
            val session = generation.get()
            val proofSession = captureSessionId ?: return
            val targetAtAcquisition = GestureAccessibilityService.foregroundPackageName
            val targetWindow = GestureAccessibilityService.foregroundWindowRevision
            // Producer timestamps identify distinct frames; their source-specific timebase is never
            // treated as elapsedRealtime. Acquisition time precedes conversion/recognition.
            if (!buffer.acceptTimestamp(frame.timestamp)) return
            val frameId = frameSequence.incrementAndGet()
            val request = snapshotGate.pendingRequestId
            val takeSnapshot = request != null && snapshotGate.ready(now, now,
                snapshotSurfaceClear && !store.assistantUiVisible && capturedContentVisible, request)
            // Drain old frames immediately, but convert only when the control
            // pipeline can consume a fresh frame. Gesture/animation fences and
            // one outstanding analysis are checked BEFORE the expensive copy.
            val analysisGap = when {
                live.rapidCapture -> 33L
                live.autoPlaying -> 450L
                else -> 1_000L / captureConfig.maxFramesPerSecond
            }
            val workReady = !store.assistantUiVisible && capturedContentVisible &&
                workSurfaceClear && now >= workFramesAfterMs
            val wantsBoard = workReady && live.wantsFrame(now)
            val needsMonitorSample = !live.actionActive && !live.readingManual &&
                now - lastFrameDetailsAtMs >= 1_000L
            if (now - lastPipelineSampleDiagnosticAtMs >= 2_500L) {
                lastPipelineSampleDiagnosticAtMs = now
                RoundDiagnostics.record("CAPTURE_SAMPLE", "frame=$frameId; monitoring=${store.monitoringEnabled}; workReady=$workReady; wantsBoard=$wantsBoard; auto=${live.autoPlaying}; assistantVisible=${store.assistantUiVisible}; contentVisible=$capturedContentVisible; workSurfaceClear=$workSurfaceClear; snapshot=$takeSnapshot")
            }
            if (!takeSnapshot && (!store.monitoringEnabled ||
                now - lastAnalysisAtMs < analysisGap || (!wantsBoard && !needsMonitorSample))) return
            lastAnalysisAtMs = now
            try {
                val bitmap = buffer.convert(frame)
                run {
                    val details = if (takeSnapshot || now - lastFrameDetailsAtMs >= 1_000L)
                        describeFrame(bitmap) else null
                    val currentFrame = synchronized(snapshotGate) {
                        if (capturing.get() && generation.get() == session && incoming === reader) {
                            store.capturedFrames += 1
                            if (details != null) {
                                store.frameDetails = details
                                lastFrameDetailsAtMs = now
                            }
                            true
                        } else false
                    }
                    if (wantsBoard && currentFrame && store.monitoringEnabled && !store.assistantUiVisible &&
                        capturedContentVisible && workSurfaceClear && now >= workFramesAfterMs &&
                        generation.get() == session && incoming === reader) {
                        live.observe(bitmap, now, frameId, session)
                    }
                    if (takeSnapshot && currentFrame) {
                        val snapshotRequest = checkNotNull(request)
                        val automaticToken = automaticGate.pendingFor(snapshotRequest)
                        var savedSnapshotId: String? = null
                        val saved = store.saveSnapshot(bitmap, snapshotGate, shouldCommit = {
                            capturing.get() && generation.get() == session && incoming === reader &&
                                snapshotGate.ready(now, SystemClock.elapsedRealtime(),
                                    snapshotSurfaceClear && !store.assistantUiVisible && capturedContentVisible,
                                    snapshotRequest) &&
                                GestureAccessibilityService.foregroundPackageName == targetAtAcquisition &&
                                GestureAccessibilityService.foregroundWindowRevision == targetWindow
                        }, onCommitted = {
                            savedSnapshotId = checkNotNull(appStore.recordSnapshot(proofSession, now, bitmap.width, bitmap.height,
                                targetAtAcquisition)) { "تعذر ربط اللقطة بالجلسة الحالية" }
                        })
                        val completed = synchronized(snapshotGate) {
                            if (saved && capturing.get() && generation.get() == session &&
                                snapshotGate.cancel(snapshotRequest)) {
                                store.captureStatus = if (automaticToken != null) "حُفظت اللقطة؛ جار تحديد المناطق وقراءة الأوراق تلقائيًا"
                                    else "تم حفظ اللقطة على الهاتف"
                                if (automaticToken != null && automaticGate.isCurrent(automaticToken, session)) {
                                    appStore.automaticSetupStatus = store.captureStatus
                                    store.aiStatus = store.captureStatus
                                }
                                true
                            } else false
                        }
                        if (completed) {
                            ui.post {
                                if (capturing.get() && generation.get() == session) {
                                    restoreOverlay(snapshotRequest)
                                    publishState(force = true)
                                }
                            }
                            if (automaticToken != null && savedSnapshotId != null)
                                prepareAutomaticBoard(bitmap, checkNotNull(savedSnapshotId), automaticToken,
                                    proofSession, targetAtAcquisition, targetWindow, now, frameId)
                        }
                    }
                }
            } catch (error: Exception) {
                RoundDiagnostics.record("CAPTURE_ERROR", "${error.javaClass.simpleName}: ${error.message ?: "no details"}; frame=$frameId")
                val cancelled = synchronized(snapshotGate) {
                    if (capturing.get() && generation.get() == session && incoming === reader &&
                        snapshotGate.pendingRequestId == request) {
                        store.captureStatus = "تعذرت معالجة الصورة: ${error.javaClass.simpleName}"
                        request != null && snapshotGate.cancel(request)
                    } else false
                }
                if (cancelled) ui.post {
                    if (capturing.get() && generation.get() == session) {
                        cancelAutomaticSetup("تعذرت قراءة اللقطة؛ لم يبدأ التلقائي")
                        restoreOverlay(request)
                    }
                }
            }
        }
    }

    private fun cancelAutomaticSetup(reason: String? = null) {
        automaticGate.cancel()
        if (store.automaticPreparing && reason != null) appStore.automaticSetupStatus = reason
        store.automaticPreparing = false
    }

    /** Worker computes only; the UI owner commits and starts after checking the same request. */
    private fun prepareAutomaticBoard(bitmap: Bitmap, snapshotId: String, token: AutomaticSetupGate.Token,
        proofSession: String, target: String?, targetWindow: Long, frameAt: Long, frameId: Long) {
        if (!automaticGate.isCurrent(token, generation.get())) return
        val width = bitmap.width
        val height = bitmap.height
        var layout: NormalizedBoardLayout? = null
        var reading: VisionReadout? = null
        var message: String
        try {
            require(!target.isNullOrBlank() && target != packageName) { "اللقطة ليست من لعبة ظاهرة؛ افتح اللعبة أولًا" }
            val templates = GlyphTemplates(this)
            require(templates.automaticRecognitionReady) { "تعذر تحميل رموز التعرف المحلية من النسخة المثبتة" }
            val detected = AutoBoardSetup.detect(bitmap, templates)
            layout = detected.layout
            message = detected.message
            val detectedLayout = layout
            if (detectedLayout != null) {
                reading = CardVision(templates).inspect(bitmap, detectedLayout, null, true, true,
                    capturedAtMs = frameAt, frameId = frameId, sessionId = token.generation,
                    layoutRevision = appStore.layoutRevision + 1, automatic = true,
                    drawCount = appStore.drawModePreference.takeIf { it == 1 || it == 3 } ?: 3,
                    automaticSnapshot = bitmap)
                message += "\n" + checkNotNull(reading).summary()
            }
        } catch (error: Exception) {
            message = "تعذرت التهيئة التلقائية: ${error.message ?: error.javaClass.simpleName}"
            layout = null
        }
        val resultLayout = layout
        val resultReading = reading
        val resultMessage = message
        ui.post {
            if (!automaticGate.isCurrent(token, generation.get())) return@post
            val current = capturing.get() && !stopping.get() && captureSessionId == proofSession &&
                appStore.isSnapshotCurrent(snapshotId, 30_000L) && !store.assistantUiVisible &&
                capturedContentVisible && GestureAccessibilityService.isTargetForeground(target) &&
                GestureAccessibilityService.foregroundWindowRevision == targetWindow
            if (!current) {
                cancelAutomaticSetup("تغيرت الشاشة أثناء قراءة اللقطة؛ لم يبدأ التلقائي")
                store.aiStatus = appStore.automaticSetupStatus
                publishState(force = true)
                return@post
            }
            if (!automaticGate.complete(token, generation.get())) return@post
            store.automaticPreparing = false
            if (resultLayout == null || resultReading == null || resultReading.found == 0 ||
                resultReading.board.problems().isNotEmpty()) {
                appStore.automaticSetupStatus = resultMessage + "\nلم يبدأ التحريك: يلزم ظهور لوحة واضحة بتخطيط مدعوم."
                store.aiStatus = appStore.automaticSetupStatus
                publishState(force = true)
                return@post
            }
            try {
                appStore.saveLayout(resultLayout, width, height, snapshotId, automatic = true)
                appStore.saveAutomaticObservation(resultReading.board, resultReading.summary(), frameAt,
                    proofSession, appStore.layoutRevision, resultReading.unknownRegions)
                appStore.automaticSetupStatus = resultMessage
                store.monitoringEnabled = true
                if (!prepareWorkSurface()) {
                    store.aiStatus = "تعذر وضع زر إيقاف خارج الأوراق؛ لم يبدأ التحريك"
                } else {
                    // No screenshot/UI action is treated as proof that the game's own timer started.
                    val started = live.startAuto(width, height)
                    store.captureStatus = "مشاركة الشاشة نشطة؛ اللقطة الأولى محفوظة والتهيئة مكتملة"
                    if (!started.accepted) restoreAutoOverlay()
                }
            } catch (error: Exception) {
                live.stopAuto("تعذر اعتماد التهيئة: ${error.message ?: error.javaClass.simpleName}")
                appStore.calibrationArmedForSession = false
            }
            publishState(force = true)
        }
    }

    private fun describeFrame(bitmap: Bitmap): String {
        var sum = 0L
        var dark = 0
        for (iy in 1..10) for (ix in 1..10) {
            val c = bitmap.getPixel((bitmap.width * ix / 11).coerceAtMost(bitmap.width - 1),
                (bitmap.height * iy / 11).coerceAtMost(bitmap.height - 1))
            val brightness = (Color.red(c) + Color.green(c) + Color.blue(c)) / 3
            sum += brightness
            if (brightness < 8) dark++
        }
        val suffix = if (dark >= 98) " — الصورة سوداء تقريبًا؛ قد تكون الشاشة محمية أو غير ظاهرة" else ""
        return "${bitmap.width} × ${bitmap.height} | سطوع تقريبي ${sum * 100 / (100 * 255)}%$suffix"
    }

    private fun synchronizeOverlay() {
        if (!capturing.get()) return
        if (!store.overlayEnabled || !Settings.canDrawOverlays(this)) {
            cancelAutomaticSetup("يلزم إذن النافذة العائمة لزر الإيقاف")
            live.stopAuto("ألغي إذن النافذة العائمة؛ توقفت الحركة والقراءة")
            floating?.hide()
            floating = null
            store.overlayVisible = false
            store.overlayStatus = "امنح إذن الظهور فوق التطبيقات ثم اضغط «إظهار أزرار التحكم»"
            return
        }
        if (snapshotHiddenFor != null) return
        if (floating == null) floating = FloatingControls(this,
            onSnapshot = { queueSnapshot(false) },
            onReadBoard = { onStartCommand(Intent(this, ScreenCaptureService::class.java)
                .setAction(ACTION_READ_BOARD), 0, 0) },
            onSingleMove = { onStartCommand(Intent(this, ScreenCaptureService::class.java)
                .setAction(ACTION_EXECUTE_MOVE), 0, 0) },
            onToggleAuto = { onStartCommand(Intent(this, ScreenCaptureService::class.java)
                .setAction(ACTION_TOGGLE_AUTO), 0, 0) },
            onToggleMonitoring = {
                onStartCommand(Intent(this, ScreenCaptureService::class.java)
                    .setAction(ACTION_TOGGLE_MONITORING), 0, 0)
            },
            onStop = { finishSession("أوقفت مشاركة الشاشة") },
            onCancelWork = {
                cancelAutomaticSetup("أُوقفت التهيئة من الزر العائم")
                live.stopAuto("أوقف المستخدم الحركة والقراءة من الزر العائم")
            },
            onMarkRound = { onStartCommand(Intent(this, ScreenCaptureService::class.java)
                .setAction(ACTION_MARK_ROUND_START), 0, 0) })
        val error = floating!!.show()
        store.overlayVisible = floating?.isVisible == true
        store.overlayStatus = error ?: "الأزرار جاهزة؛ اسحب عنوان اللوحة لتحريكها أو صغّرها"
    }

    private fun publishState(force: Boolean = false) {
        if (!capturing.get()) return
        store.overlayVisible = floating?.isVisible == true
        val panel = "${store.aiStatus}\nفُحصت ${store.capturedFrames} إطارات\n${store.frameDetails}"
        floating?.update(panel, store.monitoringEnabled, store.autoPlaying, store.automaticPreparing, appStore.autoStartAfterSnapshot)
        val summary = "${store.captureStatus}\n${store.aiStatus}\n${store.overlayStatus}"
        val now = SystemClock.elapsedRealtime()
        if (force || (summary != lastNotificationText && now - lastNotificationAtMs >= 1_000L)) {
            try {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(summary))
                lastNotificationText = summary
                lastNotificationAtMs = now
            } catch (_: SecurityException) {
                store.overlayStatus = "اسمح بالإشعارات من إعدادات التطبيق لعرض نتيجة الأوامر"
            }
        }
    }

    private fun restoreAutoOverlay() {
        restoreWorkSurface()
        if (capturing.get()) publishState(force = true)
    }

    private fun restoreReadOverlay() {
        restoreWorkSurface()
        if (capturing.get()) publishState(force = true)
    }

    private fun prepareWorkSurface(): Boolean {
        synchronizeOverlay()
        workSurfaceClear = floating?.showActiveStop(appStore.loadLayout()) == true
        workFramesAfterMs = SystemClock.elapsedRealtime() + 350L
        val owner = reader
        val session = generation.get()
        workerHandler?.post {
            if (capturing.get() && generation.get() == session && reader === owner) {
                try { owner?.acquireLatestImage()?.close() } catch (_: IllegalStateException) { }
            }
        }
        return workSurfaceClear
    }

    private fun restoreWorkSurface() {
        if (!live.actionActive && !live.readingManual) {
            workSurfaceClear = false
            floating?.finishActiveControl()
        }
    }

    private fun finishSession(message: String) {
        stopSession(message)
        stopSelf()
    }

    private fun stopSession(message: String) {
        if (!stopping.compareAndSet(false, true)) return
        cancelAutomaticSetup("انتهت المشاركة أثناء التهيئة")
        synchronized(snapshotGate) {
            capturing.set(false)
            store.captureActive = false
            generation.incrementAndGet()
            snapshotGate.cancel()
        }
        ui.removeCallbacks(heartbeat)
        live.stopAuto("أوقفت مشاركة الشاشة")
        live.beginSession(generation.get())
        captureSessionId = null
        appStore.endCaptureSession()
        workSurfaceClear = false
        snapshotHiddenFor = null
        snapshotSurfaceClear = false
        floating?.hide()
        floating = null
        store.overlayVisible = false
        store.overlayStatus = "النافذة متوقفة مع مشاركة الشاشة"
        val oldReader = reader
        reader = null
        try { display?.release() } catch (_: Exception) { }
        display = null
        releaseReader(oldReader)
        val p = projection
        val cb = projectionCallback
        projection = null
        projectionCallback = null
        if (p != null && cb != null) try { p.unregisterCallback(cb) } catch (_: Exception) { }
        try { p?.stop() } catch (_: Exception) { }
        worker?.quitSafely()
        worker = null
        workerHandler = null
        store.captureStatus = message
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopSession("انتهت خدمة مشاركة الشاشة")
        try { unregisterReceiver(lockReceiver) } catch (_: Exception) { }
        super.onDestroy()
    }
}
