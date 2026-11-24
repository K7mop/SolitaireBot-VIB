package com.retq.solitairebot.control

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.retq.solitairebot.capture.NormalizedBoardLayout
import kotlin.math.abs
import kotlin.math.min

/** Movable live-capture controls. All methods must be called on the main thread. */
class FloatingControls(
    private val context: Context,
    private val onSnapshot: () -> Unit,
    private val onToggleMonitoring: () -> Unit,
    private val onReadBoard: () -> Unit,
    private val onSingleMove: () -> Unit,
    private val onToggleAuto: () -> Unit,
    private val onStop: () -> Unit,
    private val onCancelWork: () -> Unit = onStop,
    private val onMarkRound: () -> Unit = {}
) {
    private val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
    private var attached = false
    private var expanded = true
    private var temporaryHideDepth = 0
    private var downX = 0f
    private var downY = 0f
    private var initialX = 0
    private var initialY = 0
    private var moved = false
    private var activeControl = false
    private var savedPosition: Pair<Int, Int>? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private lateinit var monitoringButton: Button

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density + .5f).toInt()

    private val status = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 12f
        text = "جار بدء مشاركة الشاشة"
        gravity = Gravity.START
        setPadding(dp(5), dp(4), dp(5), dp(6))
        maxLines = 6
        minHeight = dp(48)
        contentDescription = "حالة مشاركة الشاشة ومراقبة الإطارات"
    }
    private val title = TextView(context).apply {
        text = "مساعد السوليتير · تلقائي"
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        textSize = 12f
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setPadding(dp(6), 0, dp(6), 0)
        minHeight = dp(36)
        contentDescription = "اسحب لتحريك الأزرار أو اضغط لتصغيرها"
    }
    private fun actionButton(label: String, action: () -> Unit): Button = Button(context).apply {
        text = label
        textSize = 12f
        isAllCaps = false
        maxLines = 2
        minimumHeight = 0
        minimumWidth = 0
        minHeight = 0
        minWidth = 0
        setPadding(dp(2), dp(3), dp(2), dp(3))
        setTextColor(Color.rgb(18, 35, 51))
        backgroundTintList = ColorStateList.valueOf(Color.rgb(236, 243, 250))
        setOnClickListener { action() }
    }
    private val snapshotButton = actionButton("لقطة وبدء تلقائي", onSnapshot)
    private val autoButton = actionButton("تشغيل تلقائي") { onToggleAuto() }
    private val body = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))
        monitoringButton = actionButton("إيقاف المراقبة", onToggleMonitoring)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(snapshotButton,
                LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                    setMargins(dp(2), dp(1), dp(2), dp(1))
                })
            addView(monitoringButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                setMargins(dp(2), dp(1), dp(2), dp(1))
            })
        })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(actionButton("قراءة الأوراق", onReadBoard),
                LinearLayout.LayoutParams(0, dp(44), 1f))
            addView(actionButton("تنفيذ نقلة", onSingleMove),
                LinearLayout.LayoutParams(0, dp(44), 1f))
            addView(autoButton, LinearLayout.LayoutParams(0, dp(44), 1f))
        })
        addView(actionButton("إيقاف المشاركة", onStop).apply {
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(Color.rgb(148, 44, 49))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)).apply {
            setMargins(dp(2), dp(3), dp(2), dp(1))
        })
        addView(actionButton("بدأت الجولة الآن · 5 دقائق", onMarkRound),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)))
    }
    private val header = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(title, LinearLayout.LayoutParams(0, dp(36), 1f))
        addView(actionButton("−") { setExpanded(false) }.apply {
            contentDescription = "تصغير إلى الزر العائم"
            textSize = 21f
        }, LinearLayout.LayoutParams(dp(38), dp(36)))
    }
    private val bubble = actionButton("المساعد\nفتح") {
        if (activeControl) onCancelWork() else setExpanded(true)
    }.apply {
        textSize = 10f
        contentDescription = "فتح أزرار مساعد السوليتير؛ اسحب لتحريك الزر"
        visibility = View.GONE
    }
    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        setPadding(dp(5), dp(4), dp(5), dp(4))
        background = GradientDrawable().apply {
            setColor(Color.argb(244, 16, 30, 47))
            cornerRadius = dp(14).toFloat()
        }
        elevation = dp(8).toFloat()
        addView(header)
        addView(body)
        addView(bubble, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT))
    }
    @Suppress("RtlHardcoded")
    private val params = WindowManager.LayoutParams(
        panelWidth(), WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, baseFlags,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        // Coordinates match full-display MediaProjection and accessibility coordinates.
        flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        x = dp(8)
        y = dp(48)
    }

    val isVisible: Boolean get() = attached && temporaryHideDepth == 0 && root.visibility == View.VISIBLE
    val isExpanded: Boolean get() = expanded

    init {
        title.setOnClickListener { setExpanded(false) }
        installDrag(title)
        installDrag(bubble)
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> clampPosition() }
    }

    private fun screenSize(): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        manager.defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels.coerceAtLeast(1) to metrics.heightPixels.coerceAtLeast(1)
    }

    private fun panelWidth(): Int = min(dp(280), (screenSize().first - dp(12)).coerceAtLeast(1))

    /** Null means attached successfully; callers should display non-null diagnostics. */
    fun show(): String? {
        if (!Settings.canDrawOverlays(context)) return "إذن الظهور فوق التطبيقات غير ممنوح"
        if (attached) return null
        return try {
            params.width = if (expanded) panelWidth() else dp(58)
            root.visibility = if (temporaryHideDepth == 0) View.VISIBLE else View.INVISIBLE
            params.flags = if (temporaryHideDepth == 0) baseFlags else
                baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            manager.addView(root, params)
            attached = true
            root.post { clampPosition() }
            null
        } catch (error: Exception) {
            attached = false
            "تعذر إظهار الأزرار العائمة: ${error.javaClass.simpleName}"
        }
    }

    fun update(statusText: String, monitoring: Boolean, autoPlaying: Boolean, preparing: Boolean = false, startAfterSnapshot: Boolean = true) {
        status.text = statusText
        snapshotButton.text = if (startAfterSnapshot) "لقطة وبدء تلقائي" else "حفظ لقطة فقط"
        monitoringButton.text = if (monitoring) "إيقاف المراقبة" else "بدء المراقبة"
        autoButton.text = if (preparing) "إيقاف التهيئة" else if (autoPlaying) "إيقاف تلقائي" else "استئناف التلقائي"
        bubble.contentDescription = if (activeControl) "إيقاف الحركة والقراءة فورًا. $statusText"
            else "فتح أزرار مساعد السوليتير. $statusText"
    }

    fun expand() = setExpanded(true)

    /** Keep a reachable stop button visible while OCR/gestures run, outside every board region. */
    fun showActiveStop(layout: NormalizedBoardLayout): Boolean {
        val (width, height) = screenSize()
        val boxes = (listOf(layout.stock, layout.waste) + layout.foundations + layout.tableauColumns)
            .map { it.toPixels(width, height) }
            .map { OverlayPlacement.Rect(it.left, it.top, it.right, it.bottom) }
        // Every straight drag between game regions also stays inside this bounding rectangle.
        val envelope = OverlayPlacement.Rect(boxes.minOf { it.left }, boxes.minOf { it.top },
            boxes.maxOf { it.right }, boxes.maxOf { it.bottom })
        val place = OverlayPlacement.find(width, height, dp(58), dp(28), dp(48), dp(6), listOf(envelope)) ?: return false
        if (!activeControl) savedPosition = params.x to params.y
        activeControl = true
        setExpanded(false)
        params.x = place.left
        params.y = place.top
        bubble.text = "إيقاف\nالمساعد"
        bubble.setTextColor(Color.WHITE)
        bubble.backgroundTintList = ColorStateList.valueOf(Color.rgb(148, 44, 49))
        updateLayout()
        return isVisible
    }

    fun finishActiveControl(expand: Boolean = true) {
        activeControl = false
        bubble.text = "المساعد\nفتح"
        bubble.setTextColor(Color.rgb(18, 35, 51))
        bubble.backgroundTintList = ColorStateList.valueOf(Color.rgb(236, 243, 250))
        savedPosition?.let { (x, y) -> params.x = x; params.y = y }
        savedPosition = null
        setExpanded(expand)
    }

    private fun setExpanded(value: Boolean) {
        expanded = value
        header.visibility = if (value) View.VISIBLE else View.GONE
        body.visibility = if (value) View.VISIBLE else View.GONE
        bubble.visibility = if (value) View.GONE else View.VISIBLE
        root.setPadding(if (value) dp(5) else 0, if (value) dp(4) else 0,
            if (value) dp(5) else 0, if (value) dp(4) else 0)
        params.width = if (value) panelWidth() else dp(58)
        params.height = if (value) WindowManager.LayoutParams.WRAP_CONTENT else dp(58)
        updateLayout()
        root.post { clampPosition() }
    }

    private fun installDrag(view: View) {
        view.setOnTouchListener { target, event ->
            // Hand the complete DOWN/MOVE/UP sequence to Button while it is the fixed stop control.
            if (activeControl) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    initialX = params.x
                    initialY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) moved = true
                    if (moved) {
                        params.x = initialX + dx.toInt()
                        params.y = initialY + dy.toInt()
                        clampPosition()
                        updateLayout()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) target.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun clampPosition() {
        val (screenWidth, screenHeight) = screenSize()
        val width = if (expanded) panelWidth() else min(dp(58), screenWidth)
        val height = if (!expanded) dp(58) else root.height.takeIf { it > 0 } ?: dp(280)
        val newX = params.x.coerceIn(0, (screenWidth - width).coerceAtLeast(0))
        val newY = params.y.coerceIn(0, (screenHeight - height).coerceAtLeast(0))
        if (newX != params.x || newY != params.y || params.width != width) {
            params.x = newX
            params.y = newY
            params.width = width
            updateLayout()
        }
    }

    private fun updateLayout() {
        if (!attached) return
        try {
            manager.updateViewLayout(root, params)
        } catch (_: Exception) {
            // isVisible becomes false so the service can display or recover a lost window.
            hide()
        }
    }

    /** Pair with restoreAfterCapture; prevents the panel appearing in saved screenshots. */
    fun hideForCapture() {
        temporaryHideDepth++
        root.visibility = View.INVISIBLE
        params.flags = baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        updateLayout()
    }

    fun restoreAfterCapture() {
        temporaryHideDepth = (temporaryHideDepth - 1).coerceAtLeast(0)
        if (temporaryHideDepth != 0) return
        root.visibility = View.VISIBLE
        params.flags = baseFlags
        updateLayout()
    }

    fun hide() {
        if (attached) {
            try { manager.removeView(root) } catch (_: Exception) { }
        }
        attached = false
        temporaryHideDepth = 0
        root.visibility = View.VISIBLE
        params.flags = baseFlags
        activeControl = false
        savedPosition = null
    }
}
