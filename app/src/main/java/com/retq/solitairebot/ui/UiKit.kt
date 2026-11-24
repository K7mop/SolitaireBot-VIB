package com.retq.solitairebot.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

object UiKit {
    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density + .5f).toInt()
    fun column(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        setPadding(dp(context,18), dp(context,16), dp(context,18), dp(context,24))
        setBackgroundColor(Color.rgb(248, 250, 253))
    }
    fun text(context: Context, content: String, large: Boolean = false): TextView = TextView(context).apply {
        text = content
        textSize = if (large) 21f else 15f
        setTextColor(Color.rgb(21, 32, 48))
        if (large) typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.START
        setPadding(dp(context,4), dp(context,8), dp(context,4), dp(context,8))
    }
    fun button(context: Context, label: String, click: () -> Unit) = Button(context).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        minHeight = dp(context,48)
        setOnClickListener { click() }
    }
    /** Identical edge-to-edge/inset handling on every screen, including IME during editor input. */
    fun show(activity: Activity, content: View, scrollId: Int = 0x534F001): ScrollView {
        val scroll = ScrollView(activity).apply {
            id = scrollId
            clipToPadding = true
            isFillViewport = true
            addView(content)
        }
        if (Build.VERSION.SDK_INT >= 30) {
            activity.window.setDecorFitsSystemWindows(false)
            scroll.setOnApplyWindowInsetsListener { view, insets ->
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or
                    WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                insets
            }
        } else {
            scroll.fitsSystemWindows = true
        }
        activity.setContentView(scroll)
        scroll.requestApplyInsets()
        return scroll
    }
}
