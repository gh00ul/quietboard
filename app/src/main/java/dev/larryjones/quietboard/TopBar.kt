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

/**
 * Gboard-style strip: three suggestions, or the toolbar (emoji, clipboard, editing, one-handed,
 * settings, hide). While suggestions show, a tools button at the left opens the toolbar, and the
 * toolbar's back arrow returns to them; the keyboard closes the toolbar again when you type.
 */
class TopBar(context: Context) : View(context) {

    /** [MORE] and [BACK] switch between suggestions and the toolbar; the strip handles them itself. */
    enum class Tool { EMOJI, CLIPBOARD, EDIT, ONE_HAND, SETTINGS, HIDE, PASTE, MORE, BACK, RESIZE }

    interface Listener {
        fun onSuggestionPicked(item: SuggestionItem)
        /** Returns true if the long press did something (e.g. forgot a learned word). */
        fun onSuggestionLongPress(item: SuggestionItem): Boolean
        fun onTool(tool: Tool)
    }

    var listener: Listener? = null
    var theme: KbTheme? = null
        set(v) { field = v; chipFit = null; invalidate() }

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

    /** The toolbar is open over suggestions (the tools button). Typing closes it. */
    var toolsOpen = false
        set(v) {
            if (field == v) return
            field = v
            pressedSlot = -1
            pressedTool = null
            handler.removeCallbacks(longPress)
            layoutTools()
            invalidate()
            a11y.invalidateAll()
            onModeChange?.invoke()
        }

    /**
     * How many password-manager logins sit over the strip ([InputRoot.setAutofill]). While there are
     * any they take the place of suggestions, until you open the tools.
     */
    var autofillCount = 0
        set(v) {
            if (field == v) return
            field = v
            pressedSlot = -1
            handler.removeCallbacks(longPress)
            layoutTools()
            invalidate()
            a11y.invalidateAll()
            onModeChange?.invoke()
        }

    /** Logins step aside while you type a word or a panel (emoji, clipboard) is open. */
    var autofillPaused = false
        set(v) {
            if (field == v) return
            field = v
            pressedSlot = -1
            handler.removeCallbacks(longPress)
            layoutTools()
            invalidate()
            a11y.invalidateAll()
            onModeChange?.invoke()
        }

    /** Called when the strip switches between suggestions, logins and the toolbar. */
    var onModeChange: (() -> Unit)? = null

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

    private val hasSuggestions get() = items.any { it != null }
    val showingAutofill get() = autofillCount > 0 && !toolsOpen && !autofillPaused
    val showingSuggestions get() = hasSuggestions && !toolsOpen && !showingAutofill

    /** Width of the tools button, where the logins start. */
    internal val leadWidth get() = lead.toInt()

    /** Width of the tools button left of the suggestions. */
    private val lead get() = LEAD_DP * dp
    private val slotWidth get() = (width - lead) / 3f
    /** One of your saved emails or addresses alone in the strip: it gets the whole width, to be read in full. */
    private val wide get() = items.getOrNull(1)?.replaces != null && items.getOrNull(0) == null && items.getOrNull(2) == null

    /**
     * A split keyboard's gap (left and right x, as the keys below have it): the first suggestion goes
     * over the left half, the middle one and the last over the right half, none in the gap, where
     * neither thumb reaches. Null: the usual three across.
     */
    var split: Pair<Float, Float>? = null
        set(v) {
            if (field == v) return
            field = v
            pressedSlot = -1
            invalidate()
            a11y.invalidateAll()
        }
    private val splitHere get() = split?.takeIf { !wide && it.second > lead && it.second < width }

    /** The slot at [x], or -1 in a split keyboard's gap. */
    private fun slotAt(x: Float): Int {
        if (wide) return 1
        val s = splitHere ?: return ((x - lead) / slotWidth).toInt().coerceIn(0, 2)
        return when {
            x < s.first -> 0
            x < s.second -> -1
            x < slotRight(1) -> 1
            else -> 2
        }
    }
    private fun slotLeft(i: Int): Float {
        if (wide) return lead
        val s = splitHere ?: return lead + i * slotWidth
        return when (i) { 0 -> lead; 1 -> s.second; else -> (s.second + width) / 2 }
    }
    private fun slotRight(i: Int): Float {
        if (wide) return width.toFloat()
        val s = splitHere ?: return lead + (i + 1) * slotWidth
        return when (i) { 0 -> s.first; 1 -> (s.second + width) / 2; else -> width.toFloat() }
    }

    /** What the strip shows now (tests). */
    internal fun itemsForTest(): List<SuggestionItem?> = items

    fun setSuggestions(list: List<SuggestionItem?>?) {
        val new = list ?: emptyList()
        if (new == items) return // called on every keystroke: skip redraws and accessibility events
        val had = hasSuggestions
        items = new
        pressedSlot = -1
        handler.removeCallbacks(longPress)
        if (had != hasSuggestions) layoutTools()
        invalidate()
        a11y.invalidateAll()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        layoutTools()
    }

    /**
     * Places what can be tapped (the tools button; or back, the tools, paste chip and hide) in
     * on-screen order. Done when the size, mode, chip or lock state changes, not while drawing, so
     * touches and TalkBack always see what's shown.
     */
    private fun layoutTools() {
        toolRects.clear()
        chipFit = null // the chip's width may change with the buttons around it
        if (width == 0) return
        if (showingSuggestions || showingAutofill) {
            place(Tool.MORE, 0f, 0f, lead, height.toFloat())
            return
        }
        var x = 4 * dp
        if (hasSuggestions || autofillCount > 0) {
            place(Tool.BACK, 0f, 0f, lead, height.toFloat())
            x = lead
        }
        // With a fresh clip to paste, the chip takes the space of the less-used tools.
        val tools = when {
            locked -> LOCKED_TOOLS
            pasteChip != null -> CHIP_TOOLS
            else -> ALL_TOOLS
        }
        val hideSlot = 50 * dp
        val hideLeft = width - hideSlot - 4 * dp
        // Narrow screens: tools share what's left rather than running under the hide button.
        val slot = minOf(50 * dp, (hideLeft - x) / tools.size)
        for (tool in tools) {
            place(tool, x, 0f, x + slot, height.toFloat())
            x += slot
        }
        if (pasteChip != null && !locked && hideLeft - 6 * dp - (x + 6 * dp) > 60 * dp) {
            place(Tool.PASTE, x + 6 * dp, 7 * dp, hideLeft - 6 * dp, height - 7 * dp)
        }
        place(Tool.HIDE, hideLeft, 0f, width - 4 * dp, height.toFloat())
    }

    private fun toolAt(x: Float, y: Float) = toolRects.entries.firstOrNull { it.value.contains(x, y) }?.key

    /** The strip's own buttons switch modes; everything else goes to the keyboard. */
    private fun useTool(tool: Tool) {
        when (tool) {
            Tool.MORE -> toolsOpen = true
            Tool.BACK -> toolsOpen = false
            else -> listener?.onTool(tool)
        }
    }

    // ---- accessibility (TalkBack): suggestion slots 0..2, tools as TOOL_ID + ordinal
    private val a11y = object : VirtualViews(this@TopBar) {
        override fun ids(): IntArray {
            val tools = toolRects.keys.map { TOOL_ID + it.ordinal }
            return if (showingSuggestions) (tools + (0 until 3).filter { items.getOrNull(it) != null }).toIntArray()
            else tools.toIntArray()
        }

        override fun idAt(x: Float, y: Float): Int {
            toolAt(x, y)?.let { return TOOL_ID + it.ordinal }
            if (showingSuggestions) {
                val slot = slotAt(x)
                return if (items.getOrNull(slot) != null) slot else INVALID
            }
            return INVALID
        }

        override fun bounds(id: Int, out: Rect): Boolean {
            if (id < TOOL_ID) {
                if (!showingSuggestions || items.getOrNull(id) == null) return false
                out.set(slotLeft(id).toInt(), 0, slotRight(id).toInt(), height)
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
                    item.replaces != null -> "${item.word}, saved"
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
                Tool.RESIZE -> "Resize keyboard"
                Tool.SETTINGS -> "Settings"
                Tool.HIDE -> "Hide keyboard"
                Tool.PASTE -> "Paste: " + (chipLabel ?: "").take(100)
                Tool.MORE -> "Tools: emoji, clipboard, resize, settings"
                Tool.BACK -> if (autofillCount > 0) "Back to saved logins" else "Back to suggestions"
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
            useTool(toolOf(id) ?: return false)
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
        when {
            showingAutofill -> drawMore(canvas, t) // the logins are views on top
            showingSuggestions -> drawSuggestions(canvas, t)
            else -> drawToolbar(canvas, t)
        }
    }

    private fun drawMore(canvas: Canvas, t: KbTheme) {
        val cy = height / 2f
        toolRects[Tool.MORE]?.let { r ->
            if (pressedTool == Tool.MORE) {
                paint.color = t.keyPressed
                canvas.drawCircle(r.centerX(), cy, 17 * dp, paint)
            }
            Icons.draw(canvas, Icon.APPS, r.centerX(), cy, 19 * dp, dim)
        }
    }

    private val dim get() = theme?.let { KbTheme.blend(it.text, it.background, 0.18f) } ?: 0

    private fun drawSuggestions(canvas: Canvas, t: KbTheme) {
        val cy = height / 2f
        drawMore(canvas, t)
        for (i in 0 until 3) {
            val item = items.getOrNull(i)
            val left = slotLeft(i)
            val cw = slotRight(i) - left
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
            // A divider between neighbours (not across a split keyboard's gap).
            if (i < 2 && item != null && items.getOrNull(i + 1) != null && kotlin.math.abs(slotLeft(i + 1) - (left + cw)) < 1f) {
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
            if (t.keyStroke != 0) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.2f * dp
                paint.color = t.keyStroke
                canvas.drawRoundRect(r, r.height() / 2, r.height() / 2, paint)
                paint.style = Paint.Style.FILL
            }
            val isz = 17 * dp
            Icons.draw(canvas, Icon.PASTE, r.left + 10 * dp + isz / 2, cy, isz, t.text)
            chipText.color = t.text
            val s = chipFit ?: fit(label, chipText, r.width() - isz - 30 * dp).also { chipFit = it }
            canvas.save()
            canvas.clipRect(r)
            canvas.drawText(s, r.left + isz + 18 * dp, cy - (chipText.descent() + chipText.ascent()) / 2, chipText)
            canvas.restore()
        }
        for ((tool, tr) in toolRects) {
            val icon = when (tool) {
                Tool.EMOJI -> Icon.EMOJI
                Tool.CLIPBOARD -> Icon.CLIPBOARD
                Tool.SETTINGS -> Icon.SETTINGS
                Tool.EDIT -> Icon.EDIT
                Tool.ONE_HAND -> Icon.ONE_HAND
                Tool.RESIZE -> Icon.RESIZE
                Tool.HIDE -> Icon.CHEVRON_DOWN
                Tool.BACK -> Icon.CHEVRON_LEFT
                Tool.MORE -> Icon.APPS
                Tool.PASTE -> continue
            }
            val radius = minOf(19 * dp, tr.width() / 2)
            if (tool == pressedTool || tool == activeTool) {
                paint.color = if (tool == activeTool) t.special else t.keyPressed
                canvas.drawCircle(tr.centerX(), cy, radius, paint)
                if (tool == activeTool && t.specialStroke != 0) {
                    // Outline themes (Neon, Retro): the fill alone barely shows on the background.
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 1.2f * dp
                    paint.color = t.specialStroke
                    canvas.drawCircle(tr.centerX(), cy, radius, paint)
                    paint.style = Paint.Style.FILL
                }
            }
            Icons.draw(canvas, icon, tr.centerX(), cy, minOf(22 * dp, tr.width() * 0.5f), if (tool == activeTool) t.text else dim)
        }
    }

    /** Truncates with "…" so the text fits [maxW] pixels. */
    private fun fit(text: String, p: Paint, maxW: Float): String {
        if (maxW <= 0f) return ""
        if (p.measureText(text) <= maxW) return text
        val n = p.breakText(text, true, maxW - p.measureText("…"), null)
        return text.substring(0, n.coerceIn(0, text.length)).trimEnd() + "…"
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longPressed = false
                downX = ev.x; downY = ev.y
                pressedSlot = -1
                pressedTool = toolAt(ev.x, ev.y)
                if (pressedTool == null && showingSuggestions) {
                    pressedSlot = slotAt(ev.x)
                    if (items.getOrNull(pressedSlot) == null) pressedSlot = -1
                    else handler.postDelayed(longPress, 500L)
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                // Sliding away cancels: no long press, no pick, no stuck highlight.
                val moved = kotlin.math.abs(ev.x - downX) > touchSlop || kotlin.math.abs(ev.y - downY) > touchSlop
                val outside = when {
                    pressedSlot >= 0 -> ev.x < lead || slotAt(ev.x) != pressedSlot
                    else -> pressedTool?.let { toolRects[it]?.contains(ev.x, ev.y) == false } ?: false
                }
                if (moved && (outside || pressedSlot >= 0)) handler.removeCallbacks(longPress)
                if (outside) {
                    pressedSlot = -1; pressedTool = null
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                val slot = pressedSlot
                val tool = pressedTool
                pressedSlot = -1
                pressedTool = null
                if (slot >= 0) {
                    val item = items.getOrNull(slot)
                    if (!longPressed && ev.x >= lead && slotAt(ev.x) == slot && item != null) listener?.onSuggestionPicked(item)
                } else if (tool != null && toolAt(ev.x, ev.y) == tool) {
                    useTool(tool)
                }
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
        /** dp for the tools button / back arrow at the left. */
        const val LEAD_DP = 44f
        val LOCKED_TOOLS = listOf(Tool.EMOJI)
        val CHIP_TOOLS = listOf(Tool.EMOJI, Tool.CLIPBOARD)
        val ALL_TOOLS = listOf(Tool.EMOJI, Tool.CLIPBOARD, Tool.EDIT, Tool.ONE_HAND, Tool.RESIZE, Tool.SETTINGS)
    }
}
