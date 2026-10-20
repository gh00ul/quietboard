package dev.larryjones.quietboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/**
 * Transparent layer above the whole input view. Never takes touches; the keyboard view feeds it
 * rectangles in this view's coordinates. Lets popups float over the suggestion strip like Gboard.
 */
class PopupOverlay(context: Context) : View(context) {
    var theme: KbTheme? = null
        set(v) { field = v; invalidate() }

    private val dp = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val trailPath = android.graphics.Path()
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    // Key preview balloon: a bigger cap above the key, joined to the key itself
    private var previewOn = false
    private val previewRect = RectF()
    private val previewKey = RectF()
    private val previewPath = android.graphics.Path()
    private val stemPath = android.graphics.Path()
    private var previewLabel = ""

    // Long-press alternates
    private var popupOn = false
    private val popupBox = RectF()
    private val cell = RectF()
    private var popupItems: List<String> = emptyList()
    private var cellW = 0f
    private var selected = 0
    private var defaultIndex = 0
    private var openX = 0f

    // Glide trail
    private var trailX = FloatArray(256)
    private var trailY = FloatArray(256)
    private var trailN = 0

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onTouchEvent(event: android.view.MotionEvent?): Boolean = false

    /**
     * Never ask for space. The default View.onMeasure takes the whole AT_MOST height the IME frame
     * offers (the full screen), which made the keyboard window fill the display with the keys at
     * the top. Report 0 unless the parent gives an exact size (FrameLayout then re-measures this
     * MATCH_PARENT child to exactly the keyboard's height).
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        fun size(spec: Int) = if (MeasureSpec.getMode(spec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(spec) else 0
        setMeasuredDimension(size(widthMeasureSpec), size(heightMeasureSpec))
    }

    fun showPreview(key: RectF, label: String) {
        val w = maxOf(key.width() * 1.3f, key.width() + 16 * dp)
        val left = (key.centerX() - w / 2).coerceIn(2 * dp, maxOf(2 * dp, width - w - 2 * dp))
        // The cap overlaps the top of the key a little so the two read as one balloon. Near the top
        // (tall keyboards) it gets shorter rather than sliding down over the key it shows.
        val bottom = key.top + key.height() * 0.3f
        val h = minOf(key.height() * 1.15f, bottom)
        val top = bottom - h
        previewRect.set(left, top, left + w, top + h)
        previewKey.set(key)
        previewPath.reset()
        val r = 12 * dp
        previewPath.addRoundRect(previewRect, r, r, android.graphics.Path.Direction.CW)
        stemPath.reset()
        stemPath.addRoundRect(previewKey, 9 * dp, 9 * dp, android.graphics.Path.Direction.CW)
        previewPath.op(stemPath, android.graphics.Path.Op.UNION)
        previewLabel = label
        previewOn = true
        invalidate()
    }

    fun hidePreview() {
        if (previewOn) { previewOn = false; invalidate() }
    }

    /** [fingerX]: where the finger is now; moving one cell width from there selects the next item. */
    fun showPopup(key: RectF, items: List<String>, fingerX: Float = key.centerX()) {
        if (items.isEmpty()) return
        val margin = 4 * dp
        cellW = key.width().coerceAtLeast(30 * dp)
        if (cellW * items.size > width - 2 * margin) cellW = (width - 2 * margin) / items.size
        val w = cellW * items.size
        // The default (first) item sits directly above the key. Extra items extend to the right,
        // or to the left (reversed) when the key is near the right edge.
        var left = key.centerX() - cellW / 2
        var display = items
        if (left + w > width - margin) {
            display = items.reversed()
            left = key.centerX() + cellW / 2 - w
        }
        left = left.coerceIn(margin, maxOf(margin, width - margin - w))
        val bottom = key.top - 3 * dp
        val h = minOf(key.height() * 1.05f, maxOf(bottom, key.height() * 0.6f))
        val top = maxOf(0f, bottom - h)
        popupBox.set(left, top, left + w, top + h)
        popupItems = display
        // The key's own default alternate starts selected, even when the popup had to shift to fit
        // (near edges, landscape, tablets) and isn't centred over the key.
        defaultIndex = if (display !== items) items.size - 1 else 0
        selected = defaultIndex
        openX = fingerX
        popupOn = true
        previewOn = false
        invalidate()
    }

    fun updatePopup(x: Float) {
        if (!popupOn) return
        if (cellW <= 0f || popupItems.isEmpty()) return
        val i = (defaultIndex + kotlin.math.round((x - openX) / cellW).toInt()).coerceIn(0, popupItems.size - 1)
        if (i != selected) { selected = i; invalidate() }
    }

    val popupVisible get() = popupOn

    fun popupSelection(): String? = if (popupOn) popupItems.getOrNull(selected) else null

    fun hidePopup() {
        if (popupOn) { popupOn = false; invalidate() }
    }

    fun setTrail(xs: FloatArray, ys: FloatArray, count: Int, offX: Float, offY: Float) {
        if (trailX.size < count) {
            trailX = FloatArray(count * 2)
            trailY = FloatArray(count * 2)
        }
        for (i in 0 until count) {
            trailX[i] = xs[i] + offX
            trailY[i] = ys[i] + offY
        }
        trailN = count
        invalidate()
    }

    fun clearTrail() {
        if (trailN != 0) { trailN = 0; invalidate() }
    }

    fun hideAll() {
        previewOn = false; popupOn = false; trailN = 0
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val t = theme ?: return
        val radius = 12 * dp
        text.typeface = t.fonts.regular

        if (trailN > 1) {
            // Fades in from the tail like Gboard's. Drawn as ~12 continuous paths with butt caps:
            // per-segment lines would overlap at every joint and look beaded when translucent.
            val start = maxOf(1, trailN - 160)
            val span = trailN - start
            val chunk = maxOf(3, span / 12)
            trailPaint.strokeCap = Paint.Cap.BUTT
            var a = start
            while (a < trailN) {
                val b = minOf(trailN - 1, a + chunk)
                val f = (b - start + 1).toFloat() / span
                trailPath.reset()
                trailPath.moveTo(trailX[a - 1], trailY[a - 1])
                for (i in a..b) trailPath.lineTo(trailX[i], trailY[i])
                trailPaint.color = t.accent
                trailPaint.alpha = (50 + 205 * f).toInt()
                trailPaint.strokeWidth = (2.5f + 3f * f) * dp
                canvas.drawPath(trailPath, trailPaint)
                a = b + 1
            }
            fill.color = t.accent
            canvas.drawCircle(trailX[trailN - 1], trailY[trailN - 1], 2.75f * dp, fill)
        }

        if (previewOn) {
            fill.color = t.popup
            fill.setShadowLayer(8 * dp, 0f, 2.5f * dp, if (t.dark) 0x77000000 else 0x44000000)
            canvas.drawPath(previewPath, fill)
            fill.clearShadowLayer()
            text.color = t.popupText
            text.textSize = previewRect.height() * 0.52f
            canvas.drawText(previewLabel, previewRect.centerX(), centerBaseline(previewRect.centerY()), text)
        }

        if (popupOn) {
            fill.color = t.popup
            fill.setShadowLayer(10 * dp, 0f, 3 * dp, if (t.dark) 0x88000000.toInt() else 0x55000000)
            canvas.drawRoundRect(popupBox, radius, radius, fill)
            fill.clearShadowLayer()
            val r = cell
            for ((i, item) in popupItems.withIndex()) {
                r.set(popupBox.left + i * cellW, popupBox.top, popupBox.left + (i + 1) * cellW, popupBox.bottom)
                if (i == selected) {
                    fill.color = t.selection
                    val inset = 3 * dp
                    canvas.drawRoundRect(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset, radius - inset, radius - inset, fill)
                }
                text.color = if (i == selected) t.onSelection else t.popupText
                text.textSize = popupBox.height() * (if (item.length > 2) 0.3f else 0.42f)
                canvas.drawText(item, r.centerX(), centerBaseline(r.centerY()), text)
            }
        }
    }

    private fun centerBaseline(cy: Float) = cy - (text.descent() + text.ascent()) / 2
}
