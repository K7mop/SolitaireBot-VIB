package com.retq.solitairebot.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.retq.solitairebot.capture.NormalizedBox
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Renders an exact saved screenshot with normalized boxes, respecting letterboxing. */
class ScreenshotCanvas(context: Context, val bitmap: Bitmap) : View(context) {
    var boxes: List<NormalizedBox> = emptyList()
        set(value) { field = value; invalidate() }
    var selectedIndex: Int = -1
        set(value) { field = value; invalidate() }
    var dragEnabled = false
    var onDrag: ((NormalizedBox) -> Unit)? = null
    var onTap: ((Int, Int) -> Unit)? = null
    var crosshair: Pair<Int, Int>? = null
        set(value) { field = value; invalidate() }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = UiKit.dp(context, 2).toFloat()
        color = Color.CYAN
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        textSize = 14f * context.resources.displayMetrics.scaledDensity
        isFakeBoldText = true
    }
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastDragged: NormalizedBox? = null

    private fun pictureRect(): RectF {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val scale = min(w.toFloat() / bitmap.width, h.toFloat() / bitmap.height)
        val drawnW = bitmap.width * scale
        val drawnH = bitmap.height * scale
        return RectF((w - drawnW) / 2f, (h - drawnH) / 2f,
            (w + drawnW) / 2f, (h + drawnH) / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.DKGRAY)
        val picture = pictureRect()
        canvas.drawBitmap(bitmap, null, picture, bitmapPaint)
        boxes.forEachIndexed { i, box ->
            linePaint.color = if (i == selectedIndex) Color.YELLOW else Color.CYAN
            linePaint.strokeWidth = UiKit.dp(context, if (i == selectedIndex) 3 else 1).toFloat()
            val rect = RectF(picture.left + box.left * picture.width(),
                picture.top + box.top * picture.height(),
                picture.left + box.right * picture.width(),
                picture.top + box.bottom * picture.height())
            canvas.drawRect(rect, linePaint)
            if (i == selectedIndex) canvas.drawText("${i + 1}", rect.left + UiKit.dp(context,3),
                rect.top + UiKit.dp(context,18), labelPaint)
        }
        crosshair?.let { (x, y) ->
            val cx = picture.left + x.toFloat() / bitmap.width * picture.width()
            val cy = picture.top + y.toFloat() / bitmap.height * picture.height()
            linePaint.color = Color.RED
            linePaint.strokeWidth = UiKit.dp(context, 3).toFloat()
            val arm = UiKit.dp(context,9).toFloat()
            canvas.drawLine(cx - arm, cy, cx + arm, cy, linePaint)
            canvas.drawLine(cx, cy - arm, cx, cy + arm, linePaint)
        }
    }

    private fun normalized(x: Float, y: Float): Pair<Float, Float> {
        val rect = pictureRect()
        return ((x - rect.left) / rect.width()).coerceIn(0f, 1f) to
            ((y - rect.top) / rect.height()).coerceIn(0f, 1f)
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!pictureRect().contains(event.x, event.y)) return false
                // Calibration gestures must not be stolen by the containing ScrollView.
                if (dragEnabled) parent?.requestDisallowInterceptTouchEvent(true)
                downX = event.x; downY = event.y
                lastDragged = null
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragEnabled && selectedIndex >= 0 &&
                    abs(event.x - downX) + abs(event.y - downY) > touchSlop) {
                    val a = normalized(downX, downY)
                    val b = normalized(event.x, event.y)
                    val minX = min(a.first, b.first).coerceAtMost(.995f)
                    val minY = min(a.second, b.second).coerceAtMost(.995f)
                    val maxX = max(a.first, b.first).coerceAtLeast(.005f)
                    val maxY = max(a.second, b.second).coerceAtLeast(.005f)
                    if (maxX - minX > 0.01f && maxY - minY > 0.01f) {
                        lastDragged = NormalizedBox(minX, minY, maxX, maxY)
                        onDrag?.invoke(lastDragged!!)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragEnabled) parent?.requestDisallowInterceptTouchEvent(false)
                if (lastDragged == null) {
                    val (nx, ny) = normalized(event.x, event.y)
                    crosshair = (nx * bitmap.width).toInt().coerceIn(0, bitmap.width - 1) to
                        (ny * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
                    onTap?.invoke(crosshair!!.first, crosshair!!.second)
                    performClick()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragEnabled) parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }
}
