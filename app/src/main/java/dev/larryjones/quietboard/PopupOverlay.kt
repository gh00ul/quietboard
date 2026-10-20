package dev.larryjones.quietboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import kotlin.math.floor
import kotlin.math.sqrt

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
    private val measure = android.graphics.PathMeasure()
    private val stripes = android.graphics.Path()

    // Tap bursts (fun themes): a ring buffer of particles that fly off a key and fade.
    var effects = true
    private val px = FloatArray(MAX_PARTICLES)
    private val py = FloatArray(MAX_PARTICLES)
    private val vx = FloatArray(MAX_PARTICLES)
    private val vy = FloatArray(MAX_PARTICLES)
    private val gravity = FloatArray(MAX_PARTICLES)
    private val size = FloatArray(MAX_PARTICLES)
    private val rot = FloatArray(MAX_PARTICLES)
    private val spin = FloatArray(MAX_PARTICLES)
    private val tint = IntArray(MAX_PARTICLES)
    private val life = IntArray(MAX_PARTICLES)
    private val born = LongArray(MAX_PARTICLES)
    private var nextParticle = 0
    private val rnd = java.util.Random()

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

    /** Hides popups and the trail. Tap bursts finish on their own (a layout switch mustn't cut them off). */
    fun hideAll() {
        previewOn = false; popupOn = false; trailN = 0
        invalidate()
    }

    /** A fun theme's little burst from a tapped key at ([x], [y]). */
    fun burst(x: Float, y: Float) {
        val t = theme ?: return
        if (!effects || t.decor == Decor.NONE) return
        val now = SystemClock.uptimeMillis()
        val n = when (t.decor) {
            Decor.NEON -> 1
            Decor.HALLOWEEN, Decor.CHRISTMAS, Decor.NEW_YEAR -> 5
            else -> 4
        }
        // Speeds in dp per second, gravity in dp per second squared (negative floats up).
        fun r(a: Float, b: Float) = a + (b - a) * rnd.nextFloat()
        for (k in 0 until n) {
            val i = nextParticle
            nextParticle = (nextParticle + 1) % MAX_PARTICLES
            px[i] = x + r(-6f, 6f) * dp
            py[i] = y + r(-3f, 3f) * dp
            rot[i] = r(0f, 360f)
            tint[i] = rnd.nextInt(5)
            var g = 0f
            when (t.decor) {
                Decor.HALLOWEEN -> { vx[i] = r(-40f, 40f); vy[i] = r(-120f, -50f); g = 40f; size[i] = r(1.4f, 2.4f); life[i] = 650 }
                Decor.CHRISTMAS -> { vx[i] = r(-60f, 60f); vy[i] = r(-110f, -40f); g = 220f; size[i] = r(3f, 5f); life[i] = 700; spin[i] = r(-180f, 180f) }
                Decor.NEON -> { vx[i] = 0f; vy[i] = 0f; size[i] = 8f; life[i] = 380; tint[i] = (now / 7 % 2).toInt(); px[i] = x; py[i] = y }
                Decor.SAKURA -> { vx[i] = r(-70f, 70f); vy[i] = r(-100f, -40f); g = 200f; size[i] = r(3.5f, 5f); life[i] = 750; spin[i] = r(-240f, 240f) }
                Decor.OCEAN -> { vx[i] = r(-25f, 25f); vy[i] = r(-90f, -45f); g = -30f; size[i] = r(1.6f, 3.2f); life[i] = 700 }
                Decor.RETRO -> { vx[i] = r(-90f, 90f); vy[i] = r(-90f, 20f); size[i] = 2.6f; life[i] = 320 }
                Decor.GALAXY -> { vx[i] = r(-50f, 50f); vy[i] = r(-80f, -20f); g = 40f; size[i] = r(2.5f, 4f); life[i] = 600 }
                Decor.AUTUMN -> { vx[i] = r(-60f, 60f); vy[i] = r(-90f, -30f); g = 180f; size[i] = r(3f, 4.5f); life[i] = 800; spin[i] = r(-200f, 200f) }
                Decor.VALENTINE -> { vx[i] = r(-40f, 40f); vy[i] = r(-110f, -50f); g = -20f; size[i] = r(3f, 4.5f); life[i] = 750; spin[i] = 0f }
                Decor.NEW_YEAR -> { vx[i] = r(-90f, 90f); vy[i] = r(-130f, -60f); g = 260f; size[i] = r(3f, 5f); life[i] = 800; spin[i] = r(-400f, 400f) }
                Decor.NONE -> Unit
            }
            // To px per ms (and px per ms squared).
            vx[i] *= dp / 1000f
            vy[i] *= dp / 1000f
            gravity[i] = g * dp / 1e6f
            size[i] *= dp
            born[i] = now
        }
        invalidate()
    }

    private fun drawParticles(canvas: Canvas, t: KbTheme): Boolean {
        val now = SystemClock.uptimeMillis()
        var alive = false
        for (i in 0 until MAX_PARTICLES) {
            if (born[i] == 0L) continue
            val age = (now - born[i]).toFloat()
            if (age >= life[i]) { born[i] = 0L; continue }
            alive = true
            val f = age / life[i]
            val x = px[i] + vx[i] * age
            val y = py[i] + vy[i] * age + 0.5f * gravity[i] * age * age
            val a = (255 * (1f - f) * (1f - f * 0.5f)).toInt().coerceIn(0, 255)
            Scenery.particle(canvas, t.decor, x, y, size[i], rot[i] + spin[i] * age / 1000f, a, f, tint[i], dp)
        }
        return alive
    }

    private fun drawTrail(canvas: Canvas, t: KbTheme) {
        // Fades in from the tail like Gboard's. Drawn as ~12 continuous paths with butt caps:
        // per-segment lines would overlap at every joint and look beaded when translucent.
        val start = maxOf(1, trailN - 160)
        val span = trailN - start
        if (t.trail == Trail.PIXEL) { drawPixelTrail(canvas, t, start, span); return }
        val chunk = maxOf(3, span / 12)
        val glow = t.trail == Trail.GLOW && t.dark
        val stripe = 5f * dp
        var dashPhase = 0f
        trailPaint.strokeCap = Paint.Cap.BUTT
        var a = start
        while (a < trailN) {
            val b = minOf(trailN - 1, a + chunk)
            val f = (b - start + 1).toFloat() / span
            trailPath.reset()
            trailPath.moveTo(trailX[a - 1], trailY[a - 1])
            for (i in a..b) trailPath.lineTo(trailX[i], trailY[i])
            val alpha = (50 + 205 * f).toInt()
            val color = when (t.trail) {
                Trail.GLOW -> KbTheme.blend(t.trailTail, t.accent, f)
                Trail.CANDY -> 0xFFFFFFFF.toInt()
                else -> t.accent
            }
            trailPaint.color = color
            trailPaint.alpha = alpha
            trailPaint.strokeWidth = when (t.trail) {
                Trail.CANDY -> (3.5f + 3.5f * f) * dp
                Trail.GLOW -> (2.8f + 3.4f * f) * dp
                else -> (2.5f + 3f * f) * dp
            }
            if (glow) trailPaint.setShadowLayer((2f + 6f * f) * dp, 0f, 0f, color)
            canvas.drawPath(trailPath, trailPaint)
            if (glow) trailPaint.clearShadowLayer()
            if (t.trail == Trail.CANDY) {
                // Red stripes that carry on from one piece of the trail to the next (cut out of the
                // path by hand: a dash effect per piece per frame was ~1,500 allocations a second).
                measure.setPath(trailPath, false)
                val len = measure.length
                stripes.reset()
                var d = -dashPhase
                while (d < len) {
                    if (d + stripe > 0f) measure.getSegment(maxOf(0f, d), minOf(len, d + stripe), stripes, true)
                    d += 2 * stripe
                }
                trailPaint.color = t.trailTail
                trailPaint.alpha = alpha
                canvas.drawPath(stripes, trailPaint)
                dashPhase = (dashPhase + len) % (2 * stripe)
            }
            a = b + 1
        }
        // Scenery dropped along the way: embers, snowflakes, petals, bubbles.
        if (t.decor != Decor.NONE) {
            var i = start + ((9 - start % 9) % 9)
            while (i < trailN) {
                val dx = trailX[i] - trailX[i - 1]
                val dy = trailY[i] - trailY[i - 1]
                val d = sqrt(dx * dx + dy * dy)
                if (d > 0.01f) {
                    val side = (noise(i, 41) * 2f - 1f) * 9f * dp
                    val f = (i - start + 1).toFloat() / span
                    Scenery.trailBit(
                        canvas, t.decor, trailX[i] - dy / d * side, trailY[i] + dx / d * side,
                        (3.5f + 2f * noise(i, 42)) * dp, noise(i, 43) * 360f, (40 + 180 * f).toInt(), dp,
                    )
                }
                i += 9
            }
        }
        val hx = trailX[trailN - 1]
        val hy = trailY[trailN - 1]
        when (t.trail) {
            Trail.GLOW -> {
                fill.color = t.accent
                if (t.dark) fill.setShadowLayer(7f * dp, 0f, 0f, t.accent)
                canvas.drawCircle(hx, hy, 3.6f * dp, fill)
                fill.clearShadowLayer()
                fill.color = if (t.dark) 0xFFFFFFFF.toInt() else t.accentPressed
                canvas.drawCircle(hx, hy, 1.6f * dp, fill)
            }
            Trail.CANDY -> {
                fill.color = 0xFFFFFFFF.toInt()
                canvas.drawCircle(hx, hy, 4f * dp, fill)
                fill.color = t.trailTail
                canvas.drawCircle(hx, hy, 2f * dp, fill)
            }
            else -> {
                fill.color = t.accent
                canvas.drawCircle(hx, hy, 2.75f * dp, fill)
            }
        }
    }

    /** The retro terminal's trail: chunky pixels on a grid, gaps filled so fast swipes stay solid. */
    private fun drawPixelTrail(canvas: Canvas, t: KbTheme, start: Int, span: Int) {
        val cell = 5.5f * dp
        val inset = 0.6f * dp
        fill.color = t.accent
        if (t.glow != 0) fill.setShadowLayer(3f * dp, 0f, 0f, t.glow)
        var lastX = floor(trailX[start - 1] / cell).toInt()
        var lastY = floor(trailY[start - 1] / cell).toInt()
        for (i in start until trailN) {
            val cx = floor(trailX[i] / cell).toInt()
            val cy = floor(trailY[i] / cell).toInt()
            if (cx == lastX && cy == lastY) continue
            val steps = maxOf(kotlin.math.abs(cx - lastX), kotlin.math.abs(cy - lastY))
            fill.alpha = (60 + 195 * (i - start + 1).toFloat() / span).toInt()
            for (s in 1..steps) {
                val gx = lastX + kotlin.math.round((cx - lastX) * s.toFloat() / steps).toInt()
                val gy = lastY + kotlin.math.round((cy - lastY) * s.toFloat() / steps).toInt()
                canvas.drawRect(gx * cell + inset, gy * cell + inset, (gx + 1) * cell - inset, (gy + 1) * cell - inset, fill)
            }
            lastX = cx
            lastY = cy
        }
        fill.clearShadowLayer()
        fill.alpha = 255
    }

    override fun onDraw(canvas: Canvas) {
        val t = theme ?: return
        val radius = 12 * dp
        text.typeface = t.fonts.regular

        if (t.decor == Decor.RETRO) Scenery.scanlines(canvas, width.toFloat(), height.toFloat(), dp)
        if (trailN > 1) drawTrail(canvas, t)
        // Under the key preview, so a burst never hides the letter you just pressed.
        if (drawParticles(canvas, t)) postInvalidateOnAnimation()

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

    private companion object {
        const val MAX_PARTICLES = 48
    }
}
