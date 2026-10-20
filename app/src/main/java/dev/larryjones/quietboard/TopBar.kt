package dev.larryjones.quietboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeProvider

/** Gboard-style strip: three suggestions while typing, otherwise a toolbar. */
class TopBar(context: Context) : View(context) {

    enum class Tool { EMOJI, CLIPBOARD, EDIT, ONE_HAND, SETTINGS, HIDE, PASTE }

    interface Listener {
        fun onSuggestionPicked(item: SuggestionItem)
        /** Returns true if the long press did something (e.g. forgot a learned word). */
        fun onSuggestionLongPress(item: SuggestionItem): Boolean
        fun onTool(tool: Tool)
    }

    var listener: Listener? = null
    var theme: KbTheme? = null
        set(v) { field = v; invalidate() }

    private var items: List<SuggestionItem?> = emptyList()
    var pasteChip: String? = null
        set(v) {
            if (field == v) return
            field = v
            // A clip can be 50k characters: build the one-line label once, not on every redraw.
            chipLabel = v?.take(200)?.replace('\n', ' ')?.trim()
            chipFit = null
            layoutTools()
            invalidate()
            a11y.invalidateAll()
        }
    private var chipLabel: String? = null
    private var chipFit: String? = null
    /** Lock screen: no clipboard or settings from the toolbar (anyone holding the phone could use them). */
    var locked = false
        set(v) { if (field != v) { field = v; layoutTools(); invalidate(); a11y.invalidateAll() } }
    var activeTool: Tool? = null
        set(v) { if (field != v) { field = v; invalidate() } }

    private val dp = resources.displayMetrics.density
    val barHeight = (46 * dp).toInt()
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 17 * dp
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val boldPaint = TextPaint(textPaint).apply { typeface = Typeface.create("sans-serif", Typeface.BOLD) }
    private val chipText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        textSize = 14 * dp
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handler = Handler(Looper.getMainLooper())
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f

    private val toolRects = LinkedHashMap<Tool, RectF>()
    private val rectPool = HashMap<Tool, RectF>()

    /** Reuses one RectF per tool so drawing the toolbar doesn't allocate every frame. */
    private fun place(tool: Tool, l: Float, t: Float, r: Float, b: Float): RectF =
        rectPool.getOrPut(tool) { RectF() }.apply { set(l, t, r, b) }.also { toolRects[tool] = it }
    private var pressedSlot = -1
    private var pressedTool: Tool? = null
    private var longPressed = false
    private val longPress = Runnable {
        val item = items.getOrNull(pressedSlot)
        // Only swallow the tap if the long press actually did something.
        if (item != null && listener?.onSuggestionLongPress(item) == true) {
            longPressed = true
            pressedSlot = -1
            invalidate()
        }
    }

    val showingSuggestions get() = items.any { it != null }

    /** What the strip shows now (tests). */
    internal fun itemsForTest(): List<SuggestionItem?> = items

    fun setSuggestions(list: List<SuggestionItem?>?) {
        val new = list ?: emptyList()
        if (new == items) return // called on every keystroke: skip redraws and accessibility events
        items = new
        pressedSlot = -1
        handler.removeCallbacks(longPress)
        invalidate()
        a11y.invalidateAll()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        layoutTools()
    }

    /**
     * Places the toolbar items (left tools, paste chip, hide) in on-screen order. Done when the size,
     * chip or lock state changes, not while drawing, so touches and TalkBack always see what's shown.
     */
    private fun layoutTools() {
        toolRects.clear()
        if (width == 0) return
        val slot = 50 * dp
        var x = 4 * dp
        // With a fresh clip to paste, the chip takes the space of the less-used tools.
        val tools = when {
            locked -> LOCKED_TOOLS
            pasteChip != null -> CHIP_TOOLS
            else -> ALL_TOOLS
        }
        for (tool in tools) {
            place(tool, x, 0f, x + slot, height.toFloat())
            x += slot
        }
        val hideLeft = width - slot - 4 * dp
        if (pasteChip != null && !locked && hideLeft - 6 * dp - (x + 6 * dp) > 60 * dp) {
            place(Tool.PASTE, x + 6 * dp, 7 * dp, hideLeft - 6 * dp, height - 7 * dp)
        }
        place(Tool.HIDE, hideLeft, 0f, width - 4 * dp, height.toFloat())
    }

    // ---- accessibility (TalkBack): suggestion slots 0..2, tools as TOOL_ID + ordinal
    private val a11y = object : VirtualViews(this@TopBar) {
        override fun ids(): IntArray =
            if (showingSuggestions) (0 until 3).filter { items.getOrNull(it) != null }.toIntArray()
            else toolRects.keys.map { TOOL_ID + it.ordinal }.toIntArray()

        override fun idAt(x: Float, y: Float): Int {
            if (showingSuggestions) {
                val slot = (x / (width / 3f)).toInt().coerceIn(0, 2)
                return if (items.getOrNull(slot) != null) slot else INVALID
            }
            val tool = toolRects.entries.firstOrNull { it.value.contains(x, y) }?.key ?: return INVALID
            return TOOL_ID + tool.ordinal
        }

        override fun bounds(id: Int, out: Rect): Boolean {
            if (id < TOOL_ID) {
                if (!showingSuggestions || items.getOrNull(id) == null) return false
                val cw = width / 3f
                out.set((id * cw).toInt(), 0, ((id + 1) * cw).toInt(), height)
                return true
            }
            val r = toolRects[toolOf(id) ?: return false] ?: return false
            r.roundOut(out)
            return true
        }

        override fun describe(id: Int): CharSequence? {
            if (id < TOOL_ID) {
                val item = items.getOrNull(id) ?: return null
                return when {
                    item.emoji -> "Emoji ${item.word}"
                    item.literal -> "${item.word}, as typed"
                    item.bold -> "${item.word}, autocorrect"
                    else -> item.word
                }
            }
            return when (toolOf(id)) {
                Tool.EMOJI -> "Emoji"
                Tool.CLIPBOARD -> "Clipboard"
                Tool.EDIT -> "Text editing"
                Tool.ONE_HAND -> "One-handed mode"
                Tool.SETTINGS -> "Settings"
                Tool.HIDE -> "Hide keyboard"
                Tool.PASTE -> "Paste: " + (chipLabel ?: "").take(100)
                null -> null
            }
        }

        override fun canLongClick(id: Int) = id < TOOL_ID

        override fun click(id: Int, long: Boolean): Boolean {
            if (id < TOOL_ID) {
                val item = items.getOrNull(id) ?: return false
                if (long) return listener?.onSuggestionLongPress(item) == true
                listener?.onSuggestionPicked(item)
                return true
            }
            listener?.onTool(toolOf(id) ?: return false)
            return true
        }
    }

    private fun toolOf(id: Int) = Tool.entries.getOrNull(id - TOOL_ID)

    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = a11y

    override fun dispatchHoverEvent(event: MotionEvent): Boolean = a11y.onHover(event) || super.dispatchHoverEvent(event)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), barHeight)
    }

    override fun onDraw(canvas: Canvas) {
        val t = theme ?: return
        // Fun themes: the window behind paints the gradient and scene.
        if (!t.hasBackdrop) canvas.drawColor(t.background)
        textPaint.typeface = t.fonts.regular
        boldPaint.typeface = t.fonts.bold
        if (t.glow != 0) {
            textPaint.setShadowLayer(3f * dp, 0f, 0f, t.glow)
            boldPaint.setShadowLayer(3f * dp, 0f, 0f, t.glow)
        } else {
            textPaint.clearShadowLayer()
            boldPaint.clearShadowLayer()
        }
        chipText.typeface = t.fonts.regular
        if (showingSuggestions) drawSuggestions(canvas, t) else drawToolbar(canvas, t)
    }

    private fun drawSuggestions(canvas: Canvas, t: KbTheme) {
        val cw = width / 3f
        val cy = height / 2f
        for (i in 0 until 3) {
            val item = items.getOrNull(i)
            val left = i * cw
            if (i == pressedSlot && item != null) {
                paint.color = t.keyPressed
                paint.alpha = 140
                canvas.drawRoundRect(left + 4 * dp, 5 * dp, left + cw - 4 * dp, height - 5 * dp, 18 * dp, 18 * dp, paint)
                paint.alpha = 255
            }
            if (item != null) {
                val p = if (item.bold) boldPaint else textPaint
                p.color = if (item.literal) t.hint else t.text
                val normalSize = p.textSize
                if (item.emoji) p.textSize = 26 * dp
                val s = fit(item.display, p, cw - 22 * dp)
                if (item.bold && i != pressedSlot) {
                    // The word space will type: a soft pill so it's clear what happens next.
                    val half = minOf(p.measureText(s) / 2 + 14 * dp, cw / 2 - 4 * dp)
                    paint.color = t.chip
                    canvas.drawRoundRect(left + cw / 2 - half, cy - 17 * dp, left + cw / 2 + half, cy + 17 * dp, 17 * dp, 17 * dp, paint)
                }
                canvas.drawText(s, left + cw / 2, cy - (p.descent() + p.ascent()) / 2, p)
                p.textSize = normalSize
            }
            if (i < 2 && item != null && items.getOrNull(i + 1) != null) {
                paint.color = t.divider
                canvas.drawRoundRect(left + cw - 0.5f * dp, cy - 8 * dp, left + cw + 0.5f * dp, cy + 8 * dp, 0.5f * dp, 0.5f * dp, paint)
            }
        }
    }

    private fun drawToolbar(canvas: Canvas, t: KbTheme) {
        val cy = height / 2f
        val r = toolRects[Tool.PASTE]
        val label = chipLabel
        if (r != null && label != null) {
            paint.color = if (pressedTool == Tool.PASTE) t.keyPressed else t.key
            canvas.drawRoundRect(r, r.height() / 2, r.height() / 2, paint)
            val isz = 17 * dp
            Icons.draw(canvas, Icon.PASTE, r.left + 10 * dp + isz / 2, cy, isz, t.text)
            chipText.color = t.text
            val s = chipFit ?: fit(label, chipText, r.width() - isz - 30 * dp).also { chipFit = it }
            canvas.save()
            canvas.clipRect(r)
            canvas.drawText(s, r.left + isz + 18 * dp, cy - (chipText.descent() + chipText.ascent()) / 2, chipText)
            canvas.restore()
        }
        for ((tool, r) in toolRects) {
            val icon = when (tool) {
                Tool.EMOJI -> Icon.EMOJI
                Tool.CLIPBOARD -> Icon.CLIPBOARD
                Tool.SETTINGS -> Icon.SETTINGS
                Tool.EDIT -> Icon.EDIT
                Tool.ONE_HAND -> Icon.ONE_HAND
                Tool.HIDE -> Icon.CHEVRON_DOWN
                Tool.PASTE -> continue
            }
            if (tool == pressedTool || tool == activeTool) {
                paint.color = if (tool == activeTool) t.special else t.keyPressed
                canvas.drawCircle(r.centerX(), cy, 19 * dp, paint)
            }
            Icons.draw(canvas, icon, r.centerX(), cy, 22 * dp, if (tool == activeTool) t.text else KbTheme.blend(t.text, t.background, 0.18f))
        }
    }

    /** Truncates with "…" so the text fits [maxW] pixels. */
    private fun fit(text: String, p: Paint, maxW: Float): String {
        if (maxW <= 0f) return ""
        if (p.measureText(text) <= maxW) return text
        val n = p.breakText(text, true, maxW - p.measureText("\u2026"), null)
        return text.substring(0, n.coerceIn(0, text.length)).trimEnd() + "\u2026"
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longPressed = false
                downX = ev.x; downY = ev.y
                if (showingSuggestions) {
                    pressedSlot = (ev.x / (width / 3f)).toInt().coerceIn(0, 2)
                    if (items.getOrNull(pressedSlot) == null) pressedSlot = -1
                    else handler.postDelayed(longPress, 500L)
                } else {
                    pressedTool = toolRects.entries.firstOrNull { it.value.contains(ev.x, ev.y) }?.key
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                // Sliding away cancels: no long press, no pick, no stuck highlight.
                val moved = kotlin.math.abs(ev.x - downX) > touchSlop || kotlin.math.abs(ev.y - downY) > touchSlop
                val outside = if (showingSuggestions) {
                    pressedSlot >= 0 && (ev.x / (width / 3f)).toInt().coerceIn(0, 2) != pressedSlot
                } else {
                    pressedTool?.let { toolRects[it]?.contains(ev.x, ev.y) == false } ?: false
                }
                if (moved && (outside || pressedSlot >= 0)) handler.removeCallbacks(longPress)
                if (outside) {
                    pressedSlot = -1; pressedTool = null
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                if (showingSuggestions) {
                    val slot = (ev.x / (width / 3f)).toInt().coerceIn(0, 2)
                    val item = items.getOrNull(slot)
                    if (!longPressed && slot == pressedSlot && item != null) listener?.onSuggestionPicked(item)
                } else {
                    val tool = toolRects.entries.firstOrNull { it.value.contains(ev.x, ev.y) }?.key
                    if (tool != null && tool == pressedTool) listener?.onTool(tool)
                }
                pressedSlot = -1
                pressedTool = null
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)
                pressedSlot = -1; pressedTool = null
                invalidate()
            }
        }
        return true
    }

    private companion object {
        const val TOOL_ID = 100
        val LOCKED_TOOLS = listOf(Tool.EMOJI)
        val CHIP_TOOLS = listOf(Tool.EMOJI, Tool.CLIPBOARD)
        val ALL_TOOLS = listOf(Tool.EMOJI, Tool.CLIPBOARD, Tool.EDIT, Tool.ONE_HAND, Tool.SETTINGS)
    }
}
