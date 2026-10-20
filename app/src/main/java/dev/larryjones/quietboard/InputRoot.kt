package dev.larryjones.quietboard

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout

/** The whole keyboard window: suggestion strip + keys/panels, with the popup overlay on top. */
@SuppressLint("ViewConstructor")
class InputRoot(context: Context) : FrameLayout(context) {
    val overlay = PopupOverlay(context)
    val topBar = TopBar(context)
    val keyboard = KeyboardView(context, overlay)
    val content: FrameLayout = KeysFrame(context)
    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    init {
        content.addView(keyboard, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(topBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, topBar.barHeight))
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

    /** Adds a panel (emoji, clipboard) that sits over the keys. */
    fun addPanel(panel: android.view.View) {
        content.addView(panel, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
}
