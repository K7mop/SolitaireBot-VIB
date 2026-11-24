package com.retq.solitairebot.ui

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.retq.solitairebot.capture.ScreenCaptureService
import com.retq.solitairebot.storage.CaptureMonitorStore
import com.retq.solitairebot.storage.AppStore
import com.retq.solitairebot.vision.GlyphTemplates
import com.retq.solitairebot.control.GestureAccessibilityService
import com.retq.solitairebot.control.RoundDiagnostics

/** First-screenshot automatic local practice, with advanced manual fallback tools. */
class MainActivity : AsyncActivity() {
    private val store by lazy { CaptureMonitorStore(this) }
    private val appStore by lazy { AppStore(this) }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var captureText: TextView
    private lateinit var overlayText: TextView
    private lateinit var frameText: TextView
    private lateinit var monitoringButton: Button
    private lateinit var snapshotButton: Button
    private lateinit var startAutoButton: Button
    private lateinit var stopAutoButton: Button
    private val templates by lazy { GlyphTemplates(this) }
    private var templateSummary = "جار تحميل تغطية القوالب…"
    private var previewVersion = 0
    private var snapshotBaselineId: String? = null
    private var overlaySettingsPending = false
    private var captureAfterOverlay = false
    private var projectionRequestInFlight = false
    private var activityResumed = false
    private var awaitingSnapshotPreview = false
    private var snapshotBaselineModifiedAt = 0L
    private var snapshotRequestedAtMs = 0L
    private var previewDialog: AlertDialog? = null
    private var previewBitmap: Bitmap? = null
    private var previewImage: ImageView? = null
    private val statusUpdater = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overlaySettingsPending = savedInstanceState?.getBoolean("overlay_settings_pending") ?: false
        captureAfterOverlay = savedInstanceState?.getBoolean("capture_after_overlay") ?: false
        projectionRequestInFlight = savedInstanceState?.getBoolean("projection_request_in_flight") ?: false
        awaitingSnapshotPreview = savedInstanceState?.getBoolean("awaiting_snapshot_preview") ?: false
        snapshotBaselineModifiedAt = savedInstanceState?.getLong("snapshot_baseline_modified_at") ?: 0L
        snapshotBaselineId = savedInstanceState?.getString("snapshot_baseline_id")
        snapshotRequestedAtMs = savedInstanceState?.getLong("snapshot_requested_at_ms") ?: 0L
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(18), dp(18), dp(18), dp(28))
            setBackgroundColor(Color.rgb(248, 250, 253))
        }
        root.addView(label("مساعد السوليتير — 0.9.0 — تشغيل سريع وذكاء متوازن", true))
        root.addView(label("فعّل خدمة اللمس وإذن التحريك والنافذة العائمة، ثم شارك الشاشة وافتح اللعبة التجريبية. عندما تظهر الأوراق اضغط «لقطة وبدء تلقائي» مرة واحدة. بعدها يقرأ المساعد الشاشة ويحرك ويسحب ويقلب الأوراق تلقائيًا، ويتحقق من كل حركة. الرموز غير الواضحة تبقى مجهولة."))
        captureText = label("")
        overlayText = label("")
        frameText = label("")
        root.addView(captureText)
        root.addView(overlayText)
        root.addView(frameText)
        root.addView(button("فتح إعدادات إمكانية الوصول") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(button("بدء مشاركة الشاشة") { requestCapture() })
        root.addView(button("إظهار أزرار التحكم") { requestOverlay() })
        monitoringButton = button("إيقاف المراقبة") {
            sendControl(ScreenCaptureService.ACTION_TOGGLE_MONITORING)
        }
        root.addView(monitoringButton)
        snapshotButton = button("لقطة وتهيئة اللوحة") { requestScreenSnapshot() }
        root.addView(snapshotButton)
        root.addView(button("عرض آخر لقطة محفوظة") { showSavedSnapshot() })
        val advanced = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        root.addView(button("أدوات يدوية متقدمة (اختيارية)") {
            advanced.visibility = if (advanced.visibility == View.GONE) View.VISIBLE else View.GONE
        })
        root.addView(advanced)
        advanced.addView(button("معايرة مناطق اللعبة من آخر لقطة") {
            startActivity(Intent(this, CalibrationActivity::class.java))
        })
        advanced.addView(button("تعليم أشكال الأوراق من آخر لقطة") {
            startActivity(Intent(this, TrainingActivity::class.java))
        })
        root.addView(button("عرض حالة اللوحة المقروءة تلقائيًا / تعديل اختياري") {
            startActivity(Intent(this, BoardEditorActivity::class.java))
        })
        root.addView(button("تفعيل التحريك التلقائي بعد اللقطة الأولى") {
            appStore.practiceOnlyAcknowledged = true
            appStore.manualExecutionEnabled = true
            appStore.autoStartAfterSnapshot = true
            Toast.makeText(this, "فُعّل التشغيل بعد اللقطة الأولى. فعّل خدمة إمكانية الوصول أيضًا إن لم تكن مفعلة.", Toast.LENGTH_LONG).show()
            refresh()
        })
        root.addView(button("إلغاء إذن التحريك") {
            sendControl(ScreenCaptureService.ACTION_STOP_AUTO, quiet = true)
            appStore.manualExecutionEnabled = false
            appStore.practiceOnlyAcknowledged = false
            Toast.makeText(this, "أُوقف إذن التحريك من داخل التطبيق", Toast.LENGTH_SHORT).show()
            refresh()
        })
        advanced.addView(button("قراءة الأوراق فقط (دون تحريك)") {
            sendControl(ScreenCaptureService.ACTION_READ_BOARD)
        })
        advanced.addView(button("تنفيذ النقلة المقترحة مرة واحدة") {
            sendControl(ScreenCaptureService.ACTION_EXECUTE_MOVE)
        })
        advanced.addView(button("تحديد وقت بداية الجولة يدويًا — اختياري") {
            sendControl(ScreenCaptureService.ACTION_MARK_ROUND_START)
        })
        startAutoButton = button("ابدأ اللعب الآن") {
            when {
                !store.captureActive -> {
                    Toast.makeText(this, "ابدأ مشاركة الشاشة أولًا، ثم اضغط ابدأ اللعب الآن", Toast.LENGTH_LONG).show()
                    requestCapture()
                }
                !appStore.manualExecutionEnabled -> Toast.makeText(this,
                    "فعّل إذن التحريك مرة واحدة من زر تفعيل التحريك التلقائي", Toast.LENGTH_LONG).show()
                appStore.calibrationArmedForSession -> sendControl(ScreenCaptureService.ACTION_START_AUTO)
                else -> requestScreenSnapshot()
            }
        }
        stopAutoButton = button("إيقاف التحريك فورًا") {
            sendControl(ScreenCaptureService.ACTION_STOP_AUTO)
        }
        root.addView(startAutoButton)
        root.addView(stopAutoButton)

        root.addView(button("تصدير تقرير الجولة") {
            val report = RoundDiagnostics.report()
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "SolitaireBot 0.8.10 round diagnostics")
                putExtra(Intent.EXTRA_TEXT, report)
            }
            startActivity(Intent.createChooser(share, "مشاركة تقرير الجولة"))
        })
        root.addView(button("إيقاف مشاركة الشاشة") {
            if (store.captureActive) sendControl(ScreenCaptureService.ACTION_STOP)
            else Toast.makeText(this, "مشاركة الشاشة متوقفة بالفعل", Toast.LENGTH_SHORT).show()
        })
        advanced.addView(button("نمط السحب: تلقائي / ورقة / ثلاث ورقات") {
            val values = intArrayOf(0, 1, 3)
            val selected = values.indexOf(appStore.drawModePreference).coerceAtLeast(0)
            AlertDialog.Builder(this).setTitle("إعداد السحب في اللعبة")
                .setSingleChoiceItems(arrayOf("تلقائي: ضغطة واحدة على المخزون", "سحب ورقة واحدة (Draw 1)", "سحب ثلاث ورقات (Draw 3)"), selected) { dialog, which ->
                    appStore.drawModePreference = values[which]
                    dialog.dismiss(); refresh()
                }.setNegativeButton("إغلاق", null).show()
        })
        advanced.addView(button("حفظ اللقطات فقط دون بدء تلقائي") {
            appStore.autoStartAfterSnapshot = false
            sendControl(ScreenCaptureService.ACTION_STOP_AUTO, quiet = true)
            Toast.makeText(this, "لن يبدأ التلقائي من اللقطة حتى تضغط زر تفعيل التحريك مجددًا", Toast.LENGTH_LONG).show()
        })
        root.addView(label("ابدأ الجولة في اللعبة بنفسك؛ الأوراق موزعة مسبقًا. اللقطة الأولى تبدأ عمل المساعد فقط، ولا تضغط زر بدء الجولة في اللعبة. يتوقف المساعد عند مغادرة اللعبة أو الضغط على زر الإيقاف الأحمر. حد التشغيل خمس دقائق من بدء المساعد؛ وقت اللعبة نفسه لا يُفترض من وقت اللقطة."))
        root.addView(label("الصور محفوظة داخل التطبيق على هذا الجهاز فقط. لا يرسل التطبيق الصور إلى أي جهة."))
        UiKit.show(this, root)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 702)
        }
        refresh()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + .5f).toInt()

    private fun label(content: String, title: Boolean = false) = TextView(this).apply {
        text = content
        textSize = if (title) 24f else 15f
        setTextColor(Color.rgb(21, 32, 48))
        if (title) typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.START
        setPadding(dp(3), dp(8), dp(3), dp(8))
    }

    private fun button(content: String, action: () -> Unit) = Button(this).apply {
        text = content
        textSize = 15f
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun requestCapture() {
        if (projectionRequestInFlight) return
        if (store.captureActive) {
            requestOverlay()
            Toast.makeText(this, "مشاركة الشاشة تعمل بالفعل", Toast.LENGTH_SHORT).show()
            return
        }
        store.overlayEnabled = true
        if (!Settings.canDrawOverlays(this)) {
            openOverlaySettings(thenCapture = true)
        } else {
            launchCaptureConsent()
        }
    }

    private fun requestOverlay() {
        // Record the request before Settings opens; permission alone never toggles this preference.
        store.overlayEnabled = true
        if (!Settings.canDrawOverlays(this)) {
            openOverlaySettings(thenCapture = false)
        } else if (store.captureActive) {
            sendControl(ScreenCaptureService.ACTION_REFRESH_OVERLAY)
        } else {
            store.overlayStatus = "الإذن ممنوح؛ ستظهر الأزرار عند بدء مشاركة الشاشة"
            refresh()
        }
    }

    private fun openOverlaySettings(thenCapture: Boolean) {
        store.overlayEnabled = true
        overlaySettingsPending = true
        captureAfterOverlay = thenCapture
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (error: Exception) {
            overlaySettingsPending = false
            captureAfterOverlay = false
            store.overlayStatus = "تعذر فتح إعدادات الظهور فوق التطبيقات: ${error.javaClass.simpleName}"
            refresh()
            if (thenCapture) explainMissingOverlayPermission()
        }
    }

    private fun explainMissingOverlayPermission() {
        AlertDialog.Builder(this)
            .setTitle("إذن الأزرار العائمة غير ممنوح")
            .setMessage("فعّل الظهور فوق التطبيقات لإظهار أزرار التحكم. يمكنك أيضًا متابعة مشاركة الشاشة واستخدام إشعارها.")
            .setPositiveButton("فتح الإعدادات") { _, _ -> openOverlaySettings(thenCapture = true) }
            .setNeutralButton("متابعة بدون نافذة") { _, _ -> launchCaptureConsent() }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun launchCaptureConsent() {
        if (projectionRequestInFlight || store.captureActive) return
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            val captureIntent = if (Build.VERSION.SDK_INT >= 34) {
                manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            } else {
                manager.createScreenCaptureIntent()
            }
            projectionRequestInFlight = true
            @Suppress("DEPRECATION")
            startActivityForResult(captureIntent, 701)
        } catch (error: Exception) {
            projectionRequestInFlight = false
            store.captureStatus = "تعذر طلب مشاركة الشاشة: ${error.javaClass.simpleName}"
            refresh()
        }
    }

    @Deprecated("MediaProjection consent without AndroidX on API 26+")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 701) return
        projectionRequestInFlight = false
        if (resultCode != RESULT_OK || data == null) {
            store.captureStatus = "لم تبدأ المشاركة؛ ألغيت طلب الإذن"
            refresh()
            return
        }
        val service = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
        }
        try {
            startForegroundService(service)
        } catch (error: Exception) {
            store.captureStatus = "تعذر بدء خدمة المشاركة: ${error.javaClass.simpleName}"
            refresh()
        }
    }

    private fun sendControl(action: String, quiet: Boolean = false): Boolean {
        if (!store.captureActive) {
            if (!quiet) Toast.makeText(this, "ابدأ مشاركة الشاشة أولًا", Toast.LENGTH_SHORT).show()
            return false
        }
        return try {
            startService(Intent(this, ScreenCaptureService::class.java).setAction(action))
            true
        } catch (error: Exception) {
            store.captureStatus = "تعذر إرسال الأمر: ${error.javaClass.simpleName}"
            if (!quiet) refresh()
            false
        }
    }

    private fun showSavedSnapshot() {
        awaitingSnapshotPreview = false
        closePreview()
        val request = previewVersion
        background(work = { appStore.loadSnapshot() }, discard = { it?.recycle() },
            onError = { Toast.makeText(this, "تعذر فتح اللقطة: ${it.message}", Toast.LENGTH_LONG).show() }) { bitmap ->
            if (bitmap == null) {
                Toast.makeText(this, "احفظ لقطة من شاشة اللعبة أولًا", Toast.LENGTH_LONG).show()
                return@background
            }
            if (request != previewVersion || !activityResumed) { bitmap.recycle(); return@background }
            val image = ImageView(this).apply {
                adjustViewBounds = true;scaleType = ImageView.ScaleType.FIT_CENTER
                setImageBitmap(bitmap);contentDescription = "آخر لقطة محفوظة محليًا"
            }
            val container = ScrollView(this).apply {
                addView(image, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,FrameLayout.LayoutParams.WRAP_CONTENT))
            }
            val dialog = AlertDialog.Builder(this)
                .setTitle("لقطة محفوظة · ${bitmap.width} × ${bitmap.height}").setView(container)
                .setPositiveButton("إغلاق", null).create()
            previewDialog=dialog;previewBitmap=bitmap;previewImage=image
            dialog.setOnDismissListener {
                image.setImageDrawable(null)
                if (!bitmap.isRecycled) bitmap.recycle()
                if (previewDialog === dialog) {previewDialog=null;previewBitmap=null;previewImage=null}
            }
            dialog.show()
        }
    }

    private fun requestScreenSnapshot() {
        val previousModifiedAt = store.snapshotFile().lastModified()
        snapshotBaselineId = appStore.snapshotIdentity()?.id
        if (!sendControl(ScreenCaptureService.ACTION_SNAPSHOT)) return
        awaitingSnapshotPreview = !appStore.autoStartAfterSnapshot
        snapshotBaselineModifiedAt = previousModifiedAt
        snapshotRequestedAtMs = SystemClock.elapsedRealtime()
        Toast.makeText(this,
            "ارجع إلى اللعبة خلال 15 ثانية. بعد اللقطة سيبدأ التلقائي عند نجاح القراءة؛ ابق في اللعبة لمتابعته.",
            Toast.LENGTH_LONG).show()
    }

    private fun previewRequestedSnapshotIfReady() {
        if (!activityResumed || !awaitingSnapshotPreview) return
        val currentId = appStore.snapshotIdentity()?.id
        if (currentId != null && currentId != snapshotBaselineId) {
            showSavedSnapshot()
        } else if (SystemClock.elapsedRealtime() - snapshotRequestedAtMs !in 0L..16_000L) {
            // The service reports expiry; never preview an older file as this request's result.
            awaitingSnapshotPreview = false
        }
    }

    private fun closePreview() {
        previewVersion++
        val dialog = previewDialog
        previewImage?.setImageDrawable(null)
        previewBitmap?.let { if (!it.isRecycled) it.recycle() }
        previewDialog = null
        previewImage = null
        previewBitmap = null
        dialog?.dismiss()
    }

    private fun refresh() {
        if (!::captureText.isInitialized) return
        captureText.text = "المشاركة: ${if (store.captureActive) "نشطة" else "متوقفة"}\n${store.captureStatus}"
        snapshotButton.text = if (appStore.autoStartAfterSnapshot) "لقطة وتهيئة اللوحة" else "حفظ لقطة دون تحريك"
        val permission = Settings.canDrawOverlays(this)
        overlayText.text = "إذن النافذة: ${if (permission) "ممنوح" else "غير ممنوح"}" +
            "\nالأزرار العائمة: ${if (store.overlayVisible) "ظاهرة" else "غير ظاهرة حاليًا"}" +
            "\n${store.overlayStatus}"
        val clock = appStore.remainingGameSeconds()
        val calibration = if (appStore.calibrationArmedForSession) "معتمدة لهذه المشاركة" else
            if (appStore.calibrated) "محفوظة؛ تحتاج اعتمادًا من لقطة حالية" else "غير معتمدة"
        frameText.text = "الإطارات: ${store.capturedFrames}\n${store.frameDetails}" +
            "\nحالة القراءة والتنفيذ: ${store.aiStatus}" +
            "\nنتيجة التهيئة: ${appStore.automaticSetupStatus}" +
            "\nالتلقائي: ${if (store.autoPlaying) "يعمل" else "متوقف"}" +
            "\nوقت الجولة: ${clock?.let { "${it / 60}:${(it % 60).toString().padStart(2, '0')}" } ?: "غير محدد؛ حد تشغيل المساعد خمس دقائق"}" +
            "\nالمعايرة: $calibration" +
            "\nخدمة اللمس: ${if (GestureAccessibilityService.instance != null) "متصلة" else "غير مفعلة من أندرويد"}" +
            "\nالتحريك: ${if (appStore.manualExecutionEnabled) "مسموح" else "معطل"}" +
            "\n$templateSummary"
        startAutoButton.isEnabled = store.captureActive && !store.autoPlaying && !store.automaticPreparing
        stopAutoButton.isEnabled = store.captureActive
        monitoringButton.text = if (store.monitoringEnabled) "إيقاف المراقبة" else "بدء المراقبة"
        previewRequestedSnapshotIfReady()
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        background(work = { templates.reloadIfChanged();
            "التعرف المحلي الجاهز: ${if (templates.automaticRecognitionReady) "متاح" else "غير متاح"}\n" + templates.coverageSummary },
            onError = { templateSummary = "تعذر تحميل القوالب" }) { templateSummary = it; refresh() }
        handler.removeCallbacks(statusUpdater)
        handler.post(statusUpdater)
        if (overlaySettingsPending) {
            val shouldCapture = captureAfterOverlay
            overlaySettingsPending = false
            captureAfterOverlay = false
            if (Settings.canDrawOverlays(this)) {
                store.overlayEnabled = true
                if (store.captureActive) sendControl(ScreenCaptureService.ACTION_REFRESH_OVERLAY, quiet = true)
                else store.overlayStatus = "الإذن ممنوح؛ ستظهر الأزرار عند بدء مشاركة الشاشة"
                if (shouldCapture) handler.post {
                    if (activityResumed && !isFinishing) launchCaptureConsent()
                }
            } else {
                store.overlayStatus = "لم يُمنح إذن الظهور فوق التطبيقات"
                if (shouldCapture) handler.post {
                    if (activityResumed && !isFinishing) explainMissingOverlayPermission()
                }
            }
        }
    }

    override fun onPause() {
        activityResumed = false
        handler.removeCallbacks(statusUpdater)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("overlay_settings_pending", overlaySettingsPending)
        outState.putBoolean("capture_after_overlay", captureAfterOverlay)
        outState.putBoolean("projection_request_in_flight", projectionRequestInFlight)
        outState.putBoolean("awaiting_snapshot_preview", awaitingSnapshotPreview)
        outState.putLong("snapshot_baseline_modified_at", snapshotBaselineModifiedAt)
        outState.putString("snapshot_baseline_id", snapshotBaselineId)
        outState.putLong("snapshot_requested_at_ms", snapshotRequestedAtMs)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        closePreview()
        super.onDestroy()
    }
}
