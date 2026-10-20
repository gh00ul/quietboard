package dev.larryjones.quietboard

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout

/** The whole keyboard window: suggestion strip + keys/panels, with the popup overlay on top. */
@SuppressLint("ViewConstructor")
class InputRoot(context: Context) : FrameLayout(context) {
    val overlay = PopupOverlay(context)
    val topBar = TopBar(context)
    val keyboard = KeyboardView(context, overlay)
    val content: FrameLayout = KeysFrame(context)
    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    /** Password-manager logins (Android 11+): views the system draws, placed over the strip. */
    private val autofillRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val autofill = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        visibility = GONE
        addView(autofillRow, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    init {
        content.addView(keyboard, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val bar = FrameLayout(context)
        bar.addView(topBar, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, topBar.barHeight))
        bar.addView(autofill, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, topBar.barHeight).apply {
            marginStart = topBar.leadWidth
        })
        topBar.onModeChange = { autofill.visibility = if (topBar.showingAutofill) VISIBLE else GONE }
        column.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, topBar.barHeight))
        column.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(column, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(overlay, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /**
     * Holds the keys plus the emoji/clipboard panels. Its height always comes from the keyboard
     * view alone; panels are then sized to exactly that, so a long clipboard list or the emoji grid
     * can never stretch the keyboard window.
     */
    private inner class KeysFrame(context: Context) : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            keyboard.measure(
                MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
            val h = keyboard.measuredHeight
            val exactW = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
            val exactH = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                if (c !== keyboard && c.visibility != GONE) c.measure(exactW, exactH)
            }
            setMeasuredDimension(w, h)
        }
    }

    /** Shows these login chips over the strip (none: back to suggestions). */
    fun setAutofill(views: List<View>) {
        autofillRow.removeAllViews()
        val gap = (4 * resources.displayMetrics.density).toInt()
        for (v in views) {
            autofillRow.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = gap
            })
        }
        autofill.scrollTo(0, 0)
        topBar.autofillCount = views.size
        autofill.visibility = if (topBar.showingAutofill) VISIBLE else GONE
    }

    /** Adds a panel (emoji, clipboard) that sits over the keys. */
    fun addPanel(panel: android.view.View) {
        content.addView(panel, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
}
