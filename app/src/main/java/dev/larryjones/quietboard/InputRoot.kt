package dev.larryjones.quietboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
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
        // The strip is drawn left to right in every language: keep the logins right of the tools button.
        layoutDirection = LAYOUT_DIRECTION_LTR
        addView(autofillRow, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setOnScrollChangeListener { _, _, _, _, _ -> clipChips() }
    }

    init {
        // An English keyboard laid out left to right whatever the phone's language: in Arabic or Hebrew
        // the strip's buttons and the edit panel's Home/End and arrows would otherwise swap sides.
        layoutDirection = LAYOUT_DIRECTION_LTR
        content.addView(keyboard, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val bar = FrameLayout(context)
        bar.addView(topBar, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, topBar.barHeight))
        bar.layoutDirection = LAYOUT_DIRECTION_LTR
        bar.addView(autofill, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, topBar.barHeight).apply {
            leftMargin = topBar.leadWidth
        })
        autofillRow.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> clipChips() }
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
            // The system sizes each chip itself (its view can't measure its own content): keep that size.
            val lp = v.layoutParams
            autofillRow.addView(v, LinearLayout.LayoutParams(
                lp?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT, lp?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { rightMargin = gap })
        }
        autofill.scrollTo(0, 0)
        topBar.autofillCount = views.size
        autofill.visibility = if (topBar.showingAutofill) VISIBLE else GONE
    }

    /**
     * Each chip is a surface drawn over the whole window: it isn't cut off by the scrolling row, so cut
     * it to the part that's in view, or it would cover (and take taps meant for) the tools button.
     */
    private fun clipChips() {
        val from = autofill.scrollX
        val to = from + autofill.width
        for (i in 0 until autofillRow.childCount) {
            val c = autofillRow.getChildAt(i)
            val l = maxOf(from, c.left) - c.left
            val r = minOf(to, c.right) - c.left
            c.clipBounds = if (r > l) Rect(l, 0, r, c.height) else Rect(0, 0, 0, 0)
        }
    }

    /** Adds a panel (emoji, clipboard) that sits over the keys. */
    fun addPanel(panel: android.view.View) {
        content.addView(panel, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
}
