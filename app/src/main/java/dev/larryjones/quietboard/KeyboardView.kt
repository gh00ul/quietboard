package dev.larryjones.quietboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeProvider
import kotlin.math.abs
import kotlin.math.sqrt

enum class ShiftState { OFF, ON, LOCKED }

class KeyboardView(context: Context, private val overlay: PopupOverlay) : View(context) {

    interface Listener {
        fun onKeyFeedback(key: Key)
        /** A specific kind of feedback (delete repeat, long press); defaults to a plain key press. */
        fun onHaptic(key: Key, kind: Haptic) { onKeyFeedback(key) }
        fun onText(text: String)
        fun onDelete()
        fun onSpace()
        fun onEnter()
        fun onShiftPress()
        fun onShiftRelease(chorded: Boolean)
        /** Shift activated through TalkBack: off → on → caps lock → off, one step per activation. */
        fun onShiftCycle() { onShiftPress(); onShiftRelease(false) }
        fun onLayoutKey(type: KeyType)
        fun onSpaceLongPress()
        fun onCursorMove(steps: Int)
        fun onCursorMoveEnd()
        fun onDeleteSwipeStart()
        fun onDeleteSwipeUpdate(words: Int)
        fun onDeleteSwipeEnd(commit: Boolean)
        fun onGesture(xs: FloatArray, ys: FloatArray, count: Int)
        /** A glide began (the finger slid far enough). */
        fun onGestureStart() {}
        /** The glide so far, a few times a second, for a live preview of the word. */
        fun onGestureProgress(xs: FloatArray, ys: FloatArray, count: Int) {}
        /** A glide ended without a word (it was a sloppy tap, or the touch was cancelled). */
        fun onGestureCancel() {}
        fun onOneHandedSwap()
        fun onOneHandedExit()
    }

    var listener: Listener? = null
    var theme: KbTheme? = null
        set(v) { field = v; invalidate() }
    var shiftState = ShiftState.OFF
        set(v) { if (field != v) { field = v; invalidate() } }
    var enterIcon = Icon.ENTER
        set(v) { if (field != v) { field = v; invalidate() } }
    /** What TalkBack says for enter when the icon would mislead ("Next" uses the Go arrow); null = from the icon. */
    var enterDescription: String? = null

    /** Shown faintly on the space bar of the letters layout, like Gboard's language name. */
    var spaceLabel = "English"
        set(v) { if (field != v) { field = v; invalidate() } }

    /** Password field without headphones: TalkBack says "Key" instead of reading letters aloud. */
    var obscureKeys = false

    var showPreview = true
    /** Fun themes: a little burst of snow, embers, petals... from each key you tap. */
    var effects = true
    var gestureTyping = true
    var gestureTrail = true
    var spaceCursor = true
    var deleteSwipe = true
    /** Total rows' height in units of one letter row; keeps every layout the same height. */
    var referenceRows = 4f
        set(v) { if (field != v) { field = v; requestLayout() } }
    var heightScale = 1f
        set(v) { if (field != v) { field = v; requestLayout() } }
    /** 0 = full width, -1 = keys on the left, 1 = keys on the right. */
    var oneHanded = 0
        set(v) { if (field != v) { field = v; computeGeometry(); invalidate(); a11y.invalidateAll() } }

    private val sideArea = RectF()
    private val swapBtn = RectF()
    private val expandBtn = RectF()
    private var sideTouchId = -1
    private var sidePressed: RectF? = null

    var layout: KeyboardLayout? = null
        private set

    private val dp = resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val funcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    private val sidePad = 3 * dp
    private val topPad = 5 * dp
    private val bottomPad = 3 * dp
    private val gapH = 3 * dp
    private val gapV = 4.5f * dp
    private val radius = 9 * dp

    private var keyWidth = 1f
    private var rowHeight = 1f
    private val pressed = HashSet<Key>()
    /** Keys just let go of, fading from their pressed colour back to normal. */
    private val releasedAt = HashMap<Key, Long>()

    private fun press(k: Key) {
        pressed.add(k)
        releasedAt.remove(k)
    }

    private fun burstFrom(k: Key) {
        val t = theme ?: return
        if (!effects || t.decor == Decor.NONE) return
        val r = toOverlay(k.visual)
        overlay.burst(r.centerX(), r.top + r.height() * 0.3f)
    }

    /** "> English_" on the retro terminal's space bar, built once per label. */
    private var retroSpace = ""
    private var retroSpaceFor: String? = null

    private fun release(k: Key, by: Ptr? = null) {
        // Another finger still on the same key ("ll" with two thumbs) keeps it highlighted.
        if (ptrs.values.any { it !== by && it.key === k && !it.consumed && it.mode == Mode.NORMAL }) return
        if (pressed.remove(k)) releasedAt[k] = SystemClock.uptimeMillis()
    }

    /** 1 while held, then eases to 0 over [RELEASE_FADE_MS] after release. */
    private fun pressAmount(k: Key, now: Long): Float {
        if (k in pressed) return 1f
        val at = releasedAt[k] ?: return 0f
        val t = (now - at).toFloat() / RELEASE_FADE_MS
        if (t >= 1f) { releasedAt.remove(k); return 0f }
        return (1f - t) * (1f - t)
    }

    // ---- pointer tracking ----
    private enum class Mode { NORMAL, POPUP, SPACE_SWIPE, DELETE_SWIPE, GESTURE }

    private class Ptr(val id: Int, var key: Key, var downX: Float, var downY: Float) {
        var lastX = downX
        var mode = Mode.NORMAL
        var consumed = false
        var anchorX = downX
        var deleteWords = 0
        var repeats = 0
        var chorded = false
    }

    private val ptrs = LinkedHashMap<Int, Ptr>()
    private var longPressPtr: Ptr? = null
    private var repeatPtr: Ptr? = null
    private var gx = FloatArray(512)
    private var gy = FloatArray(512)
    private var gn = 0
    private val offset = IntArray(2)
    private val overlayOffset = IntArray(2)

    /** When the last plain key tap ended; a touch right after typing is a tap, never a glide. */
    private var lastTapUp = 0L
    /**
     * The path of a lone finger from where it landed. A glide that only starts once the fast-typing
     * pause is over (or after sliding across keys) still gets its beginning.
     */
    private var preX = FloatArray(64)
    private var preY = FloatArray(64)
    private var preN = 0
    private var preId = -1
    private var lastProgressAt = 0L

    private val longPressRunnable = Runnable { longPressPtr?.let { onLongPress(it) } }
    private val clearTrailRunnable = Runnable { overlay.clearTrail() }
    /** Upper-case labels, cached so drawing with Shift on doesn't allocate per key per frame. */
    private val upperCache = HashMap<String, String>()
    private val repeatRunnable = object : Runnable {
        override fun run() {
            val p = repeatPtr ?: return
            if (p.mode != Mode.NORMAL) return
            p.repeats++
            listener?.onDelete()
            if (p.repeats % 2 == 0) listener?.onHaptic(p.key, Haptic.REPEAT)
            handler.postDelayed(this, if (p.repeats > 12) 35L else 65L)
        }
    }

    fun setLayout(l: KeyboardLayout) {
        cancelAll()
        layout = l
        computeGeometry()
        invalidate()
        a11y.invalidateAll()
    }

    /** Letter key centers 'a'..'z' for the glide decoder (NaN when absent). */
    fun letterCenters(): Pair<FloatArray, FloatArray> {
        val xs = FloatArray(26) { Float.NaN }
        val ys = FloatArray(26) { Float.NaN }
        layout?.rows?.forEach { row ->
            row.keys.forEach { k ->
                if (k.isLetter) {
                    val c = k.text[0]
                    if (c in 'a'..'z') { xs[c - 'a'] = k.visual.centerX(); ys[c - 'a'] = k.visual.centerY() }
                }
            }
        }
        return xs to ys
    }

    fun keyWidthPx() = keyWidth
    fun rowHeightPx() = rowHeight

    private fun baseRowHeight() = 54f * dp * heightScale

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val wanted = topPad + bottomPad + baseRowHeight() * referenceRows
        // Never take most of the screen (landscape, "Extra tall" + number row): rows shrink instead,
        // so the app keeps room and the bottom row is never cut off.
        val dm = resources.displayMetrics
        val cap = dm.heightPixels * (if (dm.widthPixels > dm.heightPixels) 0.42f else 0.5f)
        setMeasuredDimension(w, minOf(wanted, cap).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        computeGeometry()
    }

    private fun computeGeometry() {
        val l = layout ?: return
        if (width == 0 || height == 0) return
        val totalRows = l.rows.sumOf { it.height.toDouble() }.toFloat()
        val rowUnit = (height - topPad - bottomPad) / totalRows
        var y = topPad
        // One-handed: keys take 80% of the width on one side, the rest holds swap/expand buttons.
        val keysW = if (oneHanded != 0) width * 0.8f else width.toFloat()
        val keysLeft = if (oneHanded == 1) width - keysW else 0f
        val usable = keysW - 2 * sidePad
        if (oneHanded != 0) {
            if (oneHanded == 1) sideArea.set(0f, 0f, keysLeft, height.toFloat())
            else sideArea.set(keysW, 0f, width.toFloat(), height.toFloat())
            val bs = minOf(sideArea.width() * 0.8f, 52 * dp)
            val cx = sideArea.centerX()
            val cy = sideArea.centerY()
            swapBtn.set(cx - bs / 2, cy - bs - 6 * dp, cx + bs / 2, cy - 6 * dp)
            expandBtn.set(cx - bs / 2, cy + 6 * dp, cx + bs / 2, cy + bs + 6 * dp)
        } else {
            sideArea.setEmpty(); swapBtn.setEmpty(); expandBtn.setEmpty()
        }
        keyWidth = 0f
        for ((ri, row) in l.rows.withIndex()) {
            val rh = row.height * rowUnit
            val totalW = row.keys.sumOf { (it.weight + it.padLeft + it.padRight).toDouble() }.toFloat()
            val unit = usable / totalW
            if (row.height == 1f && keyWidth == 0f) keyWidth = unit
            var x = keysLeft + sidePad
            for ((ki, k) in row.keys.withIndex()) {
                val kw = (k.weight + k.padLeft + k.padRight) * unit
                val top = if (ri == 0) 0f else y
                val bottom = if (ri == l.rows.lastIndex) height.toFloat() else y + rh
                val left = if (ki == 0) keysLeft else x
                val right = if (ki == row.keys.lastIndex) keysLeft + keysW else x + kw
                k.touch.set(left, top, right, bottom)
                k.visual.set(x + k.padLeft * unit + gapH, y + gapV, x + kw - k.padRight * unit - gapH, y + rh - gapV)
                x += kw
            }
            y += rh
        }
        rowHeight = rowUnit
        if (keyWidth == 0f) keyWidth = usable / 10f
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        val t = theme ?: return
        val l = layout ?: return
        // Fun themes: the window behind paints the gradient and scene; the gaps show it.
        if (!t.hasBackdrop) canvas.drawColor(t.background)
        labelPaint.typeface = t.fonts.regular
        funcPaint.typeface = t.fonts.medium
        hintPaint.typeface = t.fonts.medium
        if (t.glow != 0) {
            labelPaint.setShadowLayer(3.5f * dp, 0f, 0f, t.glow)
            funcPaint.setShadowLayer(3.5f * dp, 0f, 0f, t.glow)
        } else {
            labelPaint.clearShadowLayer()
            funcPaint.clearShadowLayer()
        }
        val extras = t.decor != Decor.NONE || t.keyStroke != 0
        var seed = 0
        val letterSize = minOf(rowHeight * 0.43f, keyWidth * 0.62f)
        labelPaint.textSize = letterSize
        funcPaint.textSize = rowHeight * 0.26f
        hintPaint.textSize = rowHeight * 0.17f
        val iconSize = rowHeight * 0.42f

        if (oneHanded != 0) {
            for (i in 0..1) {
                val r = if (i == 0) swapBtn else expandBtn
                keyPaint.color = if (sidePressed === r) t.specialPressed else t.special
                canvas.drawRoundRect(r, r.width() / 2, r.width() / 2, keyPaint)
                Icons.draw(canvas, if (i == 0) Icon.SWAP else Icon.EXPAND, r.centerX(), r.centerY(), r.width() * 0.45f, t.text)
            }
        }
        val now = SystemClock.uptimeMillis()
        for (row in l.rows) for (k in row.keys) {
            val amount = pressAmount(k, now)
            val accentKey = k.type == KeyType.ENTER || (k.type == KeyType.SHIFT && shiftState == ShiftState.LOCKED)
            val bg = when {
                accentKey -> KbTheme.blend(t.accent, t.accentPressed, amount)
                k.functional -> KbTheme.blend(t.special, t.specialPressed, amount)
                else -> KbTheme.blend(t.key, t.keyPressed, amount)
            }
            if (t.keyShadow != 0) {
                keyPaint.color = t.keyShadow
                canvas.drawRoundRect(k.visual.left, k.visual.top + 1.2f * dp, k.visual.right, k.visual.bottom + 1.2f * dp, radius, radius, keyPaint)
            }
            keyPaint.color = bg
            canvas.drawRoundRect(k.visual, radius, radius, keyPaint)
            seed++
            if (extras) {
                val look = when {
                    accentKey -> KeyLook.ACCENT
                    k.functional -> KeyLook.SPECIAL
                    else -> KeyLook.LETTER
                }
                val corner = (k.hint != null && k.type == KeyType.CHAR) || k.longPressAction == KeyType.EMOJI
                Scenery.keyExtra(canvas, t, k.visual, radius, look, corner, seed, dp)
            }

            val cx = k.visual.centerX()
            val cy = k.visual.centerY()
            when (k.type) {
                KeyType.CHAR -> {
                    val label = shifted(k.label)
                    labelPaint.color = t.text
                    labelPaint.textSize = if (label.length > 2) letterSize * 0.6f else letterSize
                    canvas.drawText(label, cx, baseline(labelPaint, cy), labelPaint)
                    k.hint?.let {
                        hintPaint.color = t.hint
                        canvas.drawText(it, k.visual.right - 5.5f * dp, k.visual.top + hintPaint.textSize + 3f * dp, hintPaint)
                    }
                    if (k.longPressAction == KeyType.EMOJI) {
                        val s = rowHeight * 0.18f
                        Icons.draw(canvas, Icon.EMOJI, k.visual.right - s / 2 - 5 * dp, k.visual.top + s / 2 + 4.5f * dp, s, t.hint)
                    }
                }
                KeyType.SHIFT -> {
                    val icon = when (shiftState) {
                        ShiftState.OFF -> Icon.SHIFT
                        ShiftState.ON -> Icon.SHIFT_ON
                        ShiftState.LOCKED -> Icon.SHIFT_LOCKED
                    }
                    // One-shot shift: filled arrow in the accent colour; caps lock: accent key, arrow + bar.
                    val color = when (shiftState) {
                        ShiftState.OFF -> t.text
                        ShiftState.ON -> t.accent
                        ShiftState.LOCKED -> t.onAccent
                    }
                    Icons.draw(canvas, icon, cx, cy, iconSize, color)
                }
                KeyType.DELETE -> Icons.draw(canvas, Icon.BACKSPACE, cx, cy, iconSize, t.text)
                KeyType.ENTER -> Icons.draw(canvas, enterIcon, cx, cy, iconSize, t.onAccent)
                KeyType.EMOJI -> Icons.draw(canvas, Icon.EMOJI, cx, cy, iconSize, t.text)
                KeyType.SPACE -> if (l.id == LayoutId.ALPHA && spaceLabel.isNotEmpty() && k.visual.width() > 0f) {
                    hintPaint.color = t.hint
                    hintPaint.textAlign = Paint.Align.CENTER
                    val size = hintPaint.textSize
                    hintPaint.textSize = rowHeight * 0.22f
                    val label = if (t.decor == Decor.RETRO) {
                        if (retroSpaceFor != spaceLabel) { retroSpace = "> ${spaceLabel}_"; retroSpaceFor = spaceLabel }
                        retroSpace
                    } else spaceLabel
                    canvas.drawText(label, cx, baseline(hintPaint, cy), hintPaint)
                    if (t.decor != Decor.NONE) Scenery.spaceArt(canvas, t, k.visual, hintPaint.measureText(label) / 2, dp)
                    hintPaint.textSize = size
                    hintPaint.textAlign = Paint.Align.RIGHT
                }
                KeyType.TO_SYMBOLS, KeyType.TO_SYMBOLS2, KeyType.TO_ALPHA, KeyType.TO_LETTERS -> {
                    funcPaint.color = t.text
                    canvas.drawText(k.label, cx, baseline(funcPaint, cy), funcPaint)
                }
            }
        }
        if (releasedAt.isNotEmpty()) postInvalidateOnAnimation()
    }

    /** Plain Euclidean distance; kotlin.math.hypot is far slower and these run per touch event. */
    private fun dist(dx: Float, dy: Float) = sqrt(dx * dx + dy * dy)

    private fun baseline(p: Paint, cy: Float) = cy - (p.descent() + p.ascent()) / 2

    private fun shifted(s: String): String {
        if (shiftState == ShiftState.OFF || layout?.id != LayoutId.ALPHA) return s
        if (s.codePointCount(0, s.length) != 1 || !Character.isLetter(s.codePointAt(0))) return s
        return upperCache.getOrPut(s) { String(Character.toChars(Character.toUpperCase(s.codePointAt(0)))) }
    }

    // ---------------------------------------------------------------- touch

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (ptrs.isNotEmpty()) cancelAll()
                val x = ev.getX(0); val y = ev.getY(0)
                if (sideArea.contains(x, y)) {
                    sideTouchId = ev.getPointerId(0)
                    sidePressed = when {
                        swapBtn.contains(x, y) -> swapBtn
                        expandBtn.contains(x, y) -> expandBtn
                        else -> null
                    }
                    invalidate()
                    return true
                }
                down(ev.getPointerId(0), x, y)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val i = ev.actionIndex
                val x = ev.getX(i); val y = ev.getY(i)
                // The empty one-handed side isn't a key: a palm or second thumb there must not commit
                // the key the first finger is holding.
                if (!sideArea.contains(x, y)) down(ev.getPointerId(i), x, y)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until ev.pointerCount) {
                    val id = ev.getPointerId(i)
                    val p = ptrs[id] ?: continue
                    if (p.mode == Mode.GESTURE) {
                        for (h in 0 until ev.historySize) addGesturePoint(ev.getHistoricalX(i, h), ev.getHistoricalY(i, h))
                    } else if (p.mode == Mode.NORMAL && id == preId) {
                        for (h in 0 until ev.historySize) addPre(ev.getHistoricalX(i, h), ev.getHistoricalY(i, h))
                    }
                    move(p, ev.getX(i), ev.getY(i))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = ev.actionIndex
                // Android 13+ marks a rejected palm/accidental touch this way: it must not type.
                val canceled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    (ev.flags and MotionEvent.FLAG_CANCELED) != 0
                if (ev.getPointerId(i) == sideTouchId) {
                    val hit = sidePressed
                    sideTouchId = -1
                    sidePressed = null
                    invalidate()
                    if (!canceled && hit != null && hit.contains(ev.getX(i), ev.getY(i))) {
                        if (hit === swapBtn) listener?.onOneHandedSwap() else listener?.onOneHandedExit()
                    }
                    return true
                }
                if (canceled) cancelPointer(ev.getPointerId(i)) else up(ev.getPointerId(i), ev.getX(i), ev.getY(i))
            }
            MotionEvent.ACTION_CANCEL -> { sideTouchId = -1; sidePressed = null; cancelAll() }
        }
        return true
    }

    private fun keyAt(x: Float, y: Float): Key? {
        val l = layout ?: return null
        if (sideArea.contains(x, y)) return null
        for (row in l.rows) for (k in row.keys) if (k.touch.contains(x, y)) return k
        var best: Key? = null
        var bestD = Float.MAX_VALUE
        for (row in l.rows) for (k in row.keys) {
            val d = dist(k.touch.centerX() - x, k.touch.centerY() - y)
            if (d < bestD) { bestD = d; best = k }
        }
        return best
    }

    private fun down(id: Int, x: Float, y: Float) {
        // Mid-glide, popup, space-bar cursor or backspace swipe: a second finger would commit or delete
        // the wrong thing (typing during a backspace swipe replaced the selection, then got deleted).
        if (ptrs.values.any { it.mode != Mode.NORMAL }) return
        // Fast two-thumb typing: a new touch commits the previous key immediately. Firing can switch
        // the layout ("?123" then a digit) and clear [ptrs], so iterate over a copy.
        for (other in ptrs.values.toList()) {
            if (other.mode != Mode.NORMAL || other.consumed || other.key.type == KeyType.SHIFT) continue
            cancelLongPress(other)
            if (other.key.type == KeyType.DELETE) {
                // Backspace still held as the next key lands: apply it now, or the new letter would be
                // typed first and then deleted when backspace lifts. A repeating backspace just stops.
                if (repeatPtr === other) { handler.removeCallbacks(repeatRunnable); repeatPtr = null }
                if (other.repeats == 0) listener?.onDelete()
            } else {
                fire(other)
                // Counts as a tap: a glide must not start right after fast two-thumb typing.
                if (other.key.type == KeyType.CHAR) lastTapUp = SystemClock.uptimeMillis()
            }
            other.consumed = true
            release(other.key, other)
        }
        // Look the key up only now: if that switched layouts, the touch belongs to the new one
        // (setLayout already cancelled the other touches); if it opened a panel, drop it.
        if (visibility != VISIBLE) return
        val key = keyAt(x, y) ?: return
        val p = Ptr(id, key, x, y)
        ptrs[id] = p
        if (ptrs.size == 1) { preId = id; preN = 0; addPre(x, y) } else preId = -1
        press(key)
        burstFrom(key)
        listener?.onKeyFeedback(key)
        when (key.type) {
            KeyType.SHIFT -> listener?.onShiftPress()
            KeyType.DELETE -> {
                repeatPtr = p
                handler.removeCallbacks(repeatRunnable)
                handler.postDelayed(repeatRunnable, 400L)
            }
            else -> Unit
        }
        scheduleLongPress(p)
        if (showPreview && key.type == KeyType.CHAR) showPreviewFor(key) else overlay.hidePreview()
        invalidate()
    }

    private fun scheduleLongPress(p: Ptr) {
        val k = p.key
        if (k.alternates.isEmpty() && k.longPressAction == null && k.type != KeyType.SPACE) return
        longPressPtr = p
        handler.removeCallbacks(longPressRunnable)
        handler.postDelayed(longPressRunnable, if (k.type == KeyType.SPACE) 450L else 300L)
    }

    private fun cancelLongPress(p: Ptr) {
        if (longPressPtr === p) {
            handler.removeCallbacks(longPressRunnable)
            longPressPtr = null
        }
    }

    /**
     * Slide distances in "keys": the smaller of key width and row height. In landscape keys are twice
     * as wide as rows are tall, and a width-based threshold was longer than the hop to the next row.
     */
    private val keyUnit get() = minOf(keyWidth, rowHeight)

    private fun move(p: Ptr, x: Float, y: Float) {
        p.lastX = x
        when (p.mode) {
            Mode.NORMAL -> {
                if (p.id == preId) addPre(x, y)
                if (p.consumed) return
                val dx = x - p.downX
                val dy = y - p.downY
                val k = p.key
                if (k.type == KeyType.SPACE && spaceCursor && abs(dx) > keyWidth * 0.45f) {
                    p.mode = Mode.SPACE_SWIPE
                    p.anchorX = p.downX
                    cancelLongPress(p)
                    move(p, x, y)
                    return
                }
                if (k.type == KeyType.DELETE && deleteSwipe && p.repeats == 0 && dx < -keyWidth * 0.6f) {
                    p.mode = Mode.DELETE_SWIPE
                    handler.removeCallbacks(repeatRunnable)
                    listener?.onDeleteSwipeStart()
                    move(p, x, y)
                    return
                }
                // Glide only on a deliberate slide: far enough, and not in the middle of fast tapping
                // (sloppy thumb taps drift and used to turn into glides, eating letters).
                if (gestureTyping && k.isLetter && layout?.id == LayoutId.ALPHA && ptrs.size == 1 &&
                    dist(dx, dy) > keyUnit * 0.7f && android.os.SystemClock.uptimeMillis() - lastTapUp > 300L
                ) {
                    startGesture(p, x, y)
                    return
                }
                if (k.type == KeyType.SHIFT || k.type == KeyType.DELETE || k.type == KeyType.SPACE) return
                // A tap that drifts slightly over a key border keeps its key; only a real slide changes it.
                if (dist(dx, dy) < keyUnit * 0.45f) return
                val k2 = keyAt(x, y) ?: return
                // Sliding onto Space/Delete/Shift keeps the letter: landing there by accident would
                // otherwise start a cursor swipe or delete instead of typing.
                if (k2 !== k && k2.type != KeyType.SHIFT && k2.type != KeyType.SPACE && k2.type != KeyType.DELETE) {
                    release(k, p); press(k2)
                    p.key = k2
                    // Measure later slides from here, not from where the finger first landed.
                    p.downX = x; p.downY = y; p.anchorX = x
                    cancelLongPress(p)
                    scheduleLongPress(p)
                    if (showPreview && k2.type == KeyType.CHAR) showPreviewFor(k2) else overlay.hidePreview()
                    invalidate()
                }
            }
            Mode.SPACE_SWIPE -> {
                val step = 11 * dp
                val steps = ((x - p.anchorX) / step).toInt()
                if (steps != 0) {
                    p.anchorX += steps * step
                    listener?.onCursorMove(steps)
                }
            }
            Mode.DELETE_SWIPE -> {
                val dist = p.downX - x
                val words = if (dist < keyWidth * 0.6f) 0 else 1 + ((dist - keyWidth * 0.6f) / (keyWidth * 0.8f)).toInt()
                if (words != p.deleteWords) {
                    p.deleteWords = words
                    listener?.onDeleteSwipeUpdate(words)
                }
            }
            Mode.POPUP -> overlay.updatePopup(x + offX())
            Mode.GESTURE -> addGesturePoint(x, y)
        }
    }

    private fun up(id: Int, x: Float, y: Float) {
        val p = ptrs.remove(id) ?: return
        cancelLongPress(p)
        if (repeatPtr === p) {
            handler.removeCallbacks(repeatRunnable)
            repeatPtr = null
        }
        release(p.key, p)
        when (p.mode) {
            Mode.NORMAL -> {
                if (p.key.type == KeyType.SHIFT) {
                    listener?.onShiftRelease(p.chorded)
                } else if (!p.consumed) {
                    fire(p)
                    if (p.key.type == KeyType.CHAR) lastTapUp = SystemClock.uptimeMillis()
                } else if (p.key.type == KeyType.CHAR) {
                    lastTapUp = SystemClock.uptimeMillis()
                }
            }
            Mode.POPUP -> {
                overlay.updatePopup(x + offX())
                val sel = overlay.popupSelection()
                overlay.hidePopup()
                if (sel != null) emitText(sel)
            }
            Mode.SPACE_SWIPE -> listener?.onCursorMoveEnd()
            Mode.DELETE_SWIPE -> listener?.onDeleteSwipeEnd(p.deleteWords > 0)
            Mode.GESTURE -> {
                addGesturePoint(x, y)
                // Length in key widths across and rows down, as the decoder measures it: it reads
                // nothing into a path under 0.6 of those, so a shorter slide must stay a tap.
                var len = 0f
                for (i in 1 until gn) len += dist((gx[i] - gx[i - 1]) / keyWidth, (gy[i] - gy[i - 1]) / rowHeight)
                // A short slide that ends on another letter is a two-letter glide ("we", "as");
                // one that stays near its start key was a sloppy tap.
                val end = keyAt(x, y)
                val toOtherLetter = end != null && end !== p.key && end.isLetter && len >= 0.65f
                if (len < 1.1f && !toOtherLetter) {
                    listener?.onGestureCancel()
                    overlay.clearTrail()
                    emitText(shifted(p.key.text))
                    lastTapUp = SystemClock.uptimeMillis()
                } else {
                    listener?.onGesture(gx.copyOf(gn), gy.copyOf(gn), gn)
                    handler.removeCallbacks(clearTrailRunnable)
                    handler.postDelayed(clearTrailRunnable, 120L)
                }
            }
        }
        hidePreviewIfIdle()
        invalidate()
    }

    private fun hidePreviewIfIdle() {
        if (ptrs.values.none { it.key.type == KeyType.CHAR && it.mode == Mode.NORMAL && !it.consumed }) overlay.hidePreview()
    }

    /** Drops one touch without acting on it (the system cancelled just that pointer). */
    private fun cancelPointer(id: Int) {
        val p = ptrs.remove(id) ?: return
        cancelLongPress(p)
        if (repeatPtr === p) { handler.removeCallbacks(repeatRunnable); repeatPtr = null }
        release(p.key, p)
        when (p.mode) {
            Mode.NORMAL -> if (p.key.type == KeyType.SHIFT) listener?.onShiftRelease(p.chorded)
            Mode.POPUP -> overlay.hidePopup()
            Mode.SPACE_SWIPE -> listener?.onCursorMoveEnd()
            Mode.DELETE_SWIPE -> listener?.onDeleteSwipeEnd(false)
            Mode.GESTURE -> { gn = 0; overlay.clearTrail(); listener?.onGestureCancel() }
        }
        hidePreviewIfIdle()
        invalidate()
    }

    private fun fire(p: Ptr) {
        val k = p.key
        when (k.type) {
            KeyType.CHAR -> emitText(shifted(k.text))
            KeyType.SPACE -> listener?.onSpace()
            KeyType.ENTER -> listener?.onEnter()
            KeyType.DELETE -> if (p.repeats == 0) listener?.onDelete()
            KeyType.SHIFT -> Unit
            KeyType.TO_SYMBOLS, KeyType.TO_SYMBOLS2, KeyType.TO_ALPHA, KeyType.TO_LETTERS, KeyType.EMOJI ->
                listener?.onLayoutKey(k.type)
        }
    }

    private fun emitText(s: String) {
        for (o in ptrs.values) if (o.key.type == KeyType.SHIFT) o.chorded = true
        listener?.onText(s)
    }

    private fun onLongPress(p: Ptr) {
        longPressPtr = null
        if (p.mode != Mode.NORMAL || p.consumed || ptrs[p.id] !== p) return
        val k = p.key
        when {
            k.type == KeyType.SPACE -> {
                p.consumed = true
                listener?.onHaptic(k, Haptic.LONG_PRESS)
                listener?.onSpaceLongPress()
            }
            k.longPressAction != null -> {
                p.consumed = true
                overlay.hidePreview()
                release(k, p)
                listener?.onHaptic(k, Haptic.LONG_PRESS)
                listener?.onLayoutKey(k.longPressAction)
            }
            k.alternates.isNotEmpty() -> {
                p.mode = Mode.POPUP
                overlay.hidePreview()
                listener?.onHaptic(k, Haptic.LONG_PRESS)
                overlay.showPopup(toOverlay(k.visual), k.alternates.map { shifted(it) }, p.lastX + offX())
            }
        }
        invalidate()
    }

    private fun startGesture(p: Ptr, x: Float, y: Float) {
        p.mode = Mode.GESTURE
        cancelLongPress(p)
        handler.removeCallbacks(clearTrailRunnable)
        overlay.hidePreview()
        pressed.clear()
        gn = 0
        lastProgressAt = SystemClock.uptimeMillis()
        // The whole path from touch-down, not just from the last key it slid onto.
        if (p.id == preId && preN > 0) {
            for (i in 0 until preN) addGesturePoint(preX[i], preY[i])
        } else {
            addGesturePoint(p.downX, p.downY)
        }
        addGesturePoint(x, y)
        preId = -1
        listener?.onGestureStart()
        invalidate()
    }

    private fun addGesturePoint(x: Float, y: Float) {
        if (gn > 0 && dist(x - gx[gn - 1], y - gy[gn - 1]) < 1.5f * dp) return
        if (gn == gx.size) {
            gx = gx.copyOf(gn * 2); gy = gy.copyOf(gn * 2)
        }
        gx[gn] = x; gy[gn] = y; gn++
        // Live preview: hand the path so far to the decoder ~10 times a second.
        val now = SystemClock.uptimeMillis()
        if (gn >= 3 && now - lastProgressAt >= PROGRESS_MS && ptrs.values.any { it.mode == Mode.GESTURE }) {
            lastProgressAt = now
            listener?.onGestureProgress(gx.copyOf(gn), gy.copyOf(gn), gn)
        }
        if (gestureTrail) {
            updateOffsets()
            overlay.setTrail(gx, gy, gn, (offset[0] - overlayOffset[0]).toFloat(), (offset[1] - overlayOffset[1]).toFloat())
        }
    }

    private fun addPre(x: Float, y: Float) {
        if (preN > 0 && dist(x - preX[preN - 1], y - preY[preN - 1]) < 1.5f * dp) return
        if (preN == preX.size) {
            if (preN >= MAX_PRE) return
            preX = preX.copyOf(preN * 2); preY = preY.copyOf(preN * 2)
        }
        preX[preN] = x; preY[preN] = y; preN++
    }

    private fun showPreviewFor(k: Key) {
        overlay.showPreview(toOverlay(k.visual), shifted(k.label))
    }

    private fun updateOffsets() {
        getLocationInWindow(offset)
        overlay.getLocationInWindow(overlayOffset)
    }

    private fun offX(): Float {
        updateOffsets()
        return (offset[0] - overlayOffset[0]).toFloat()
    }

    private fun toOverlay(r: RectF): RectF {
        updateOffsets()
        val dx = (offset[0] - overlayOffset[0]).toFloat()
        val dy = (offset[1] - overlayOffset[1]).toFloat()
        return RectF(r.left + dx, r.top + dy, r.right + dx, r.bottom + dy)
    }

    /** Abort every touch in progress (layout switch, window hidden, ACTION_CANCEL). */
    fun cancelAll() {
        handler.removeCallbacks(longPressRunnable)
        handler.removeCallbacks(repeatRunnable)
        handler.removeCallbacks(clearTrailRunnable)
        longPressPtr = null
        repeatPtr = null
        for (p in ptrs.values) {
            when {
                p.mode == Mode.DELETE_SWIPE -> listener?.onDeleteSwipeEnd(false)
                p.mode == Mode.SPACE_SWIPE -> listener?.onCursorMoveEnd()
                p.mode == Mode.GESTURE -> listener?.onGestureCancel()
                p.key.type == KeyType.SHIFT && p.mode == Mode.NORMAL -> listener?.onShiftRelease(p.chorded)
            }
        }
        ptrs.clear()
        pressed.clear()
        releasedAt.clear()
        gn = 0
        overlay.hideAll()
        invalidate()
    }

    // ---------------------------------------------------------------- accessibility (TalkBack)

    private val a11y = object : VirtualViews(this@KeyboardView) {
        private fun keys() = layout?.rows?.flatMap { it.keys } ?: emptyList()

        override fun ids(): IntArray {
            val n = keys().size
            return if (oneHanded != 0) IntArray(n + 2) { if (it < n) it else SIDE_SWAP + it - n } else IntArray(n) { it }
        }

        override fun idAt(x: Float, y: Float): Int {
            if (swapBtn.contains(x, y)) return SIDE_SWAP
            if (expandBtn.contains(x, y)) return SIDE_EXPAND
            val k = keyAt(x, y) ?: return INVALID
            return keys().indexOfFirst { it === k }.takeIf { it >= 0 } ?: INVALID
        }

        override fun bounds(id: Int, out: Rect): Boolean {
            val r = when (id) {
                SIDE_SWAP -> swapBtn.takeIf { oneHanded != 0 }
                SIDE_EXPAND -> expandBtn.takeIf { oneHanded != 0 }
                else -> keys().getOrNull(id)?.visual
            } ?: return false
            r.roundOut(out)
            return true
        }

        override fun describe(id: Int): CharSequence? = when (id) {
            SIDE_SWAP -> "Move keyboard to the other side"
            SIDE_EXPAND -> "Full-width keyboard"
            else -> keys().getOrNull(id)?.let { describeKey(it) }
        }

        // Long-press alternates (accents, digits, ".com") as TalkBack actions: there's no way to slide
        // through the popup with touch exploration.
        override fun customActions(id: Int): List<Pair<Int, CharSequence>> {
            val k = keys().getOrNull(id) ?: return emptyList()
            if (obscureKeys) return emptyList()
            return k.alternates.mapIndexed { i, alt -> (ALT_ACTION_BASE + i) to "Type ${shifted(alt)}" }
        }

        override fun performCustom(id: Int, action: Int): Boolean {
            val alt = keys().getOrNull(id)?.alternates?.getOrNull(action - ALT_ACTION_BASE) ?: return false
            emitText(shifted(alt))
            return true
        }

        override fun canLongClick(id: Int): Boolean {
            val k = keys().getOrNull(id) ?: return false
            return k.type == KeyType.SPACE || k.longPressAction != null
        }

        override fun click(id: Int, long: Boolean): Boolean {
            when (id) {
                SIDE_SWAP -> { listener?.onOneHandedSwap(); return true }
                SIDE_EXPAND -> { listener?.onOneHandedExit(); return true }
            }
            val k = keys().getOrNull(id) ?: return false
            listener?.onKeyFeedback(k)
            when {
                long && k.type == KeyType.SPACE -> listener?.onSpaceLongPress()
                long -> k.longPressAction?.let { listener?.onLayoutKey(it) }
                k.type == KeyType.SHIFT -> listener?.onShiftCycle()
                k.type == KeyType.DELETE -> listener?.onDelete()
                else -> fire(Ptr(-1, k, 0f, 0f))
            }
            return true
        }
    }

    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = a11y

    /** The accessibility id of a key on the current layout (tests). */
    internal fun accessibilityIdOf(k: Key): Int = a11y.virtualId(layout?.rows?.flatMap { it.keys }?.indexOf(k) ?: -1)

    override fun dispatchHoverEvent(event: MotionEvent): Boolean = a11y.onHover(event) || super.dispatchHoverEvent(event)

    private fun describeKey(k: Key): String = when (k.type) {
        KeyType.CHAR -> if (obscureKeys) "Key" else SPOKEN[k.text] ?: shifted(k.label)
        KeyType.SHIFT -> when (shiftState) {
            ShiftState.OFF -> "Shift"
            ShiftState.ON -> "Shift, on"
            ShiftState.LOCKED -> "Caps lock, on"
        }
        KeyType.DELETE -> "Delete"
        KeyType.SPACE -> "Space"
        KeyType.ENTER -> enterDescription ?: when (enterIcon) {
            Icon.SEARCH -> "Search"
            Icon.SEND -> "Send"
            Icon.GO -> "Go"
            Icon.DONE -> "Done"
            Icon.PREVIOUS -> "Previous"
            else -> "Enter"
        }
        KeyType.TO_SYMBOLS -> "Symbols"
        KeyType.TO_SYMBOLS2 -> "More symbols"
        KeyType.TO_ALPHA -> if (k.label == "123") "Numbers" else "Letters"
        KeyType.TO_LETTERS -> "Letters"
        KeyType.EMOJI -> "Emoji"
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE && ptrs.isNotEmpty()) cancelAll()
    }

    override fun onDetachedFromWindow() {
        cancelAll()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val RELEASE_FADE_MS = 160f
        const val PROGRESS_MS = 90L
        /** Points kept for a lone finger before it becomes a glide (it's a tap or a slide until then). */
        const val MAX_PRE = 1024
        const val ALT_ACTION_BASE = 0x7E000000
        const val SIDE_SWAP = 10_000
        const val SIDE_EXPAND = 10_001
        /** What TalkBack should say for keys whose label it would read oddly or not at all. */
        val SPOKEN = mapOf(
            "," to "Comma", "." to "Period", "'" to "Apostrophe", "\"" to "Quote", "-" to "Dash",
            "/" to "Slash", "\\" to "Backslash", "_" to "Underscore", "|" to "Vertical bar", "~" to "Tilde",
            "`" to "Backtick", "^" to "Caret", "*" to "Asterisk", "#" to "Hash", "@" to "At sign",
        )
    }
}
