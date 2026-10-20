package dev.larryjones.quietboard

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** A fun theme's scene: what it draws behind the strip and keys, on the keys, and when you tap. */
enum class Decor { NONE, HALLOWEEN, CHRISTMAS, NEON, SAKURA, OCEAN, RETRO }

/** How the glide trail looks. */
enum class Trail {
    /** One colour, fading in from the tail. */
    PLAIN,
    /** From [KbTheme.trailTail] to [KbTheme.accent], glowing on dark themes. */
    GLOW,
    /** White with [KbTheme.trailTail] stripes, like a candy cane. */
    CANDY,
    /** Chunky square pixels. */
    PIXEL,
}

/** Which kind of key [Scenery.keyExtra] decorates. */
enum class KeyLook { LETTER, SPECIAL, ACCENT }

/** Repeatable pseudo-random number in 0..1 for item [i]: a scene looks the same on every draw. */
internal fun noise(i: Int, salt: Int): Float {
    var h = i * -0x61c88647 + salt * 0x5bd1e995
    h = h xor (h ushr 16)
    h *= 0x45d9f3b
    h = h xor (h ushr 16)
    h *= 0x45d9f3b
    h = h xor (h ushr 16)
    return (h ushr 8).toFloat() / (1 shl 24)
}

/**
 * The keyboard window's background: the theme's gradient plus its scene. A standard theme is one
 * flat colour. Christmas lights twinkle about once a second while the keyboard is on screen (only
 * this background is redrawn, not the keys), unless [effects] is off.
 */
class ThemeBackdrop(
    private val t: KbTheme,
    private val stripH: Float,
    private val dp: Float,
    private val effects: Boolean,
) : Drawable() {
    private val paint = Paint()
    private var shaderFor = Int.MIN_VALUE
    private val twinkle = Runnable { invalidateSelf() }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (!t.hasBackdrop) {
            paint.shader = null
            paint.color = t.background
            canvas.drawRect(b, paint)
            return
        }
        if (shaderFor != b.top * 31 + b.bottom) {
            paint.shader = LinearGradient(0f, b.top.toFloat(), 0f, b.bottom.toFloat(), t.background, t.backgroundEnd, Shader.TileMode.CLAMP)
            shaderFor = b.top * 31 + b.bottom
        }
        canvas.drawRect(b, paint)
        val now = SystemClock.uptimeMillis()
        val phase = if (effects) ((now / TWINKLE_MS) % 2).toInt() else 0
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        Scenery.backdrop(canvas, t, b.width().toFloat(), b.height().toFloat(), stripH, dp, phase)
        canvas.restore()
        if (effects && t.decor == Decor.CHRISTMAS && isVisible) {
            unscheduleSelf(twinkle)
            scheduleSelf(twinkle, now + TWINKLE_MS - now % TWINKLE_MS)
        }
    }

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (!visible) unscheduleSelf(twinkle) else if (changed) invalidateSelf()
        return changed
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE

    private companion object {
        const val TWINKLE_MS = 1100L
    }
}

/**
 * Draws the fun themes' decorations: all vector shapes sized in dp, placed with [noise] so nothing
 * jumps between frames. Scenery hugs the top edge of the strip and its corners, clear of the
 * suggestions. Main thread only (one shared paint and set of paths).
 */
internal object Scenery {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val path2 = Path()
    private val clip = Path()
    private val oval = RectF()
    private val m = Matrix()
    private var dash: DashPathEffect? = null
    private var dashFor = 0f

    private val BULBS = intArrayOf(0xFFFF4D4D.toInt(), 0xFFFFD54F.toInt(), 0xFF5CFF8F.toInt(), 0xFF4FC3F7.toInt())
    private val PETAL = 0xFFF6A5C0.toInt()
    private val SNOW = 0xFFF4F9FF.toInt()
    private val NIGHT = 0xFF07040C.toInt()

    private fun reset() {
        p.reset()
        p.isAntiAlias = true
    }

    // ------------------------------------------------------------------ backdrop

    fun backdrop(c: Canvas, t: KbTheme, w: Float, h: Float, strip: Float, dp: Float, phase: Int) {
        reset()
        when (t.decor) {
            Decor.HALLOWEEN -> halloween(c, w, h, strip, dp)
            Decor.CHRISTMAS -> christmas(c, w, h, strip, dp, phase)
            Decor.NEON -> neon(c, t, w, h, strip, dp)
            Decor.SAKURA -> sakura(c, w, h, strip, dp)
            Decor.OCEAN -> ocean(c, w, h, strip, dp)
            Decor.RETRO -> {
                p.color = 0x4039FF6A
                c.drawRect(0f, strip - 0.5f * dp, w, strip + 0.5f * dp, p)
            }
            Decor.NONE -> Unit
        }
        reset()
    }

    private fun halloween(c: Canvas, w: Float, h: Float, strip: Float, dp: Float) {
        for (i in 0 until 40) {
            val y = if (i % 3 == 0) noise(i, 2) * h else noise(i, 2) * strip
            p.color = 0xFFFFE9C9.toInt()
            p.alpha = (50 + 110 * noise(i, 3)).toInt()
            c.drawCircle(noise(i, 1) * w, y, (0.5f + 0.8f * noise(i, 4)) * dp, p)
        }
        crescent(c, w * 0.70f, 9.5f * dp, 6.5f * dp, dp)
        bat(c, w * 0.235f, 7f * dp, 7f * dp, -10f, NIGHT, dp)
        bat(c, w * 0.295f, 4.2f * dp, 4.6f * dp, 12f, NIGHT, dp)
        bat(c, w * 0.77f, 7.5f * dp, 5.5f * dp, -6f, NIGHT, dp)
        web(c, 0f, 1f, 26f * dp, dp)
        web(c, w, -1f, 21f * dp, dp)
        spider(c, 13f * dp, 6.5f * dp, 15.5f * dp, dp)
    }

    private fun crescent(c: Canvas, x: Float, y: Float, r: Float, dp: Float) {
        path.reset()
        path.addCircle(x, y, r, Path.Direction.CW)
        path2.reset()
        path2.addCircle(x + r * 0.45f, y - r * 0.25f, r * 0.86f, Path.Direction.CW)
        path.op(path2, Path.Op.DIFFERENCE)
        p.color = 0xFFFFE6A8.toInt()
        p.setShadowLayer(5f * dp, 0f, 0f, 0xAAFFC46B.toInt())
        c.drawPath(path, p)
        p.clearShadowLayer()
    }

    /** A bat [s] half-wingspans wide, a faint purple rim so it reads against the night sky. */
    private fun bat(c: Canvas, x: Float, y: Float, s: Float, tilt: Float, color: Int, dp: Float) {
        path.reset()
        path.moveTo(0f, -0.12f)
        path.lineTo(0.10f, -0.34f); path.lineTo(0.16f, -0.14f)
        path.quadTo(0.48f, -0.40f, 1.0f, -0.30f)
        path.quadTo(0.86f, -0.10f, 0.88f, 0.10f)
        path.quadTo(0.72f, -0.02f, 0.58f, 0.12f)
        path.quadTo(0.44f, 0.00f, 0.30f, 0.16f)
        path.quadTo(0.20f, 0.06f, 0.10f, 0.22f)
        path.lineTo(0f, 0.30f)
        path.lineTo(-0.10f, 0.22f)
        path.quadTo(-0.20f, 0.06f, -0.30f, 0.16f)
        path.quadTo(-0.44f, 0.00f, -0.58f, 0.12f)
        path.quadTo(-0.72f, -0.02f, -0.88f, 0.10f)
        path.quadTo(-0.86f, -0.10f, -1.0f, -0.30f)
        path.quadTo(-0.48f, -0.40f, -0.16f, -0.14f)
        path.lineTo(-0.10f, -0.34f)
        path.close()
        place(s, tilt, x, y)
        p.color = color
        p.setShadowLayer(1.6f * dp, 0f, 0f, 0x999D4DFF.toInt())
        c.drawPath(path, p)
        p.clearShadowLayer()
    }

    /** Scales, rotates and moves [path] from unit coordinates to the screen. */
    private fun place(s: Float, rot: Float, x: Float, y: Float) {
        m.reset()
        m.postScale(s, s)
        m.postRotate(rot)
        m.postTranslate(x, y)
        path.transform(m)
    }

    /** A cobweb in a top corner at [cx]; [dir] 1 opens to the right, -1 to the left. */
    private fun web(c: Canvas, cx: Float, dir: Float, r: Float, dp: Float) {
        p.style = Paint.Style.STROKE
        p.strokeWidth = 0.8f * dp
        p.color = 0x50FFFFFF
        val spokes = 5
        fun ang(i: Float) = (i / (spokes - 1)) * PI.toFloat() / 2
        for (i in 0 until spokes) {
            val a = ang(i.toFloat())
            c.drawLine(cx, 0f, cx + dir * cos(a) * r, sin(a) * r, p)
        }
        for (ring in 1..3) {
            val rr = r * ring / 3.4f
            path.reset()
            for (i in 0 until spokes) {
                val a = ang(i.toFloat())
                val x = cx + dir * cos(a) * rr
                val y = sin(a) * rr
                if (i == 0) path.moveTo(x, y) else {
                    val am = ang(i - 0.5f)
                    path.quadTo(cx + dir * cos(am) * rr * 0.8f, sin(am) * rr * 0.8f, x, y)
                }
            }
            c.drawPath(path, p)
        }
        p.style = Paint.Style.FILL
    }

    private fun spider(c: Canvas, x: Float, top: Float, y: Float, dp: Float) {
        p.style = Paint.Style.STROKE
        p.strokeWidth = 0.7f * dp
        p.color = 0x60FFFFFF
        c.drawLine(x, top, x, y - 2f * dp, p)
        p.color = NIGHT
        p.strokeWidth = 0.75f * dp
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        for (side in intArrayOf(-1, 1)) for (k in 0 until 4) {
            val ky = y + (k - 1.5f) * 0.8f * dp
            path.reset()
            path.moveTo(x, ky)
            path.lineTo(x + side * 2.3f * dp, ky - 1.4f * dp + k * 0.35f * dp)
            path.lineTo(x + side * 3.6f * dp, ky + (k - 1.2f) * 1.2f * dp)
            c.drawPath(path, p)
        }
        p.style = Paint.Style.FILL
        p.setShadowLayer(1.5f * dp, 0f, 0f, 0x999D4DFF.toInt())
        c.drawCircle(x, y, 2.1f * dp, p)
        c.drawCircle(x, y - 2.3f * dp, 1.3f * dp, p)
        p.clearShadowLayer()
        p.strokeCap = Paint.Cap.BUTT
    }

    private fun christmas(c: Canvas, w: Float, h: Float, strip: Float, dp: Float, phase: Int) {
        for (i in 0 until 70) {
            val y = if (i % 3 == 0) noise(i, 6) * h else noise(i, 6) * strip
            p.color = 0xFFFFFFFF.toInt()
            p.alpha = (70 + 120 * noise(i, 7)).toInt()
            c.drawCircle(noise(i, 5) * w, y, (0.6f + 1.2f * noise(i, 8)) * dp, p)
        }
        // A string of lights along the top edge: sagging swags, two bulbs on each.
        val swags = max(2, (w / (46f * dp)).roundToInt())
        val sw = w / swags
        val y0 = 1.5f * dp
        val yc = 9f * dp
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.1f * dp
        p.color = 0xCC03100A.toInt()
        path.reset()
        path.moveTo(0f, y0)
        for (i in 0 until swags) path.quadTo(sw * (i + 0.5f), yc, sw * (i + 1), y0)
        c.drawPath(path, p)
        p.style = Paint.Style.FILL
        var b = 0
        for (i in 0 until swags) for (k in 1..2) {
            val tt = k / 3f
            val u = 1 - tt
            val x0 = sw * i
            val xm = sw * (i + 0.5f)
            val x1 = sw * (i + 1)
            val x = u * u * x0 + 2 * u * tt * xm + tt * tt * x1
            val y = u * u * y0 + 2 * u * tt * yc + tt * tt * y0
            val dx = 2 * u * (xm - x0) + 2 * tt * (x1 - xm)
            val dy = 2 * u * (yc - y0) + 2 * tt * (y0 - yc)
            val color = BULBS[b % BULBS.size]
            val lit = (b + phase) % 2 == 0
            c.save()
            c.translate(x, y)
            c.rotate(atan2(dy, dx) * 180f / PI.toFloat())
            p.color = 0xFF39403A.toInt()
            c.drawRect(-1.1f * dp, -0.4f * dp, 1.1f * dp, 1.6f * dp, p)
            p.color = color
            if (lit) p.setShadowLayer(5f * dp, 0f, 0f, color) else p.alpha = 140
            oval.set(-2.2f * dp, 1.2f * dp, 2.2f * dp, 7.2f * dp)
            c.drawOval(oval, p)
            p.clearShadowLayer()
            p.color = 0x99FFFFFF.toInt()
            c.drawCircle(-0.8f * dp, 3f * dp, 0.7f * dp, p)
            c.restore()
            b++
        }
    }

    private fun neon(c: Canvas, t: KbTheme, w: Float, h: Float, strip: Float, dp: Float) {
        // A perspective grid behind the keys, peeking through the gaps like a synthwave floor.
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f * dp
        p.color = 0x38FF2BD6
        val vx = w / 2
        for (i in -9..9) c.drawLine(vx + i * w * 0.03f, strip, vx + i * w * 0.2f, h, p)
        var y = strip + 4f * dp
        var gap = 5f * dp
        while (y < h) {
            c.drawLine(0f, y, w, y, p)
            y += gap
            gap *= 1.32f
        }
        p.style = Paint.Style.FILL
        for (i in 0 until 26) {
            p.color = if (i % 2 == 0) 0xFF00E5FF.toInt() else 0xFFFF2BD6.toInt()
            p.alpha = (60 + 120 * noise(i, 21)).toInt()
            c.drawCircle(noise(i, 22) * w, noise(i, 23) * strip * 0.9f, (0.5f + 0.7f * noise(i, 24)) * dp, p)
        }
        // The horizon: a glowing laser line between the strip and the keys.
        p.color = t.keyStroke
        p.setShadowLayer(6f * dp, 0f, 0f, t.keyStroke)
        c.drawRect(0f, strip - 0.6f * dp, w, strip + 0.6f * dp, p)
        p.clearShadowLayer()
    }

    private fun sakura(c: Canvas, w: Float, h: Float, strip: Float, dp: Float) {
        for (i in 0 until 30) {
            val y = if (i % 3 == 0) noise(i, 12) * h else noise(i, 12) * strip
            petal(c, noise(i, 11) * w, y, (3.2f + 2.4f * noise(i, 13)) * dp, noise(i, 14) * 360f, PETAL, (120 + 100 * noise(i, 15)).toInt())
        }
        // A blossoming branch reaching in from the top-right corner.
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.color = 0xFF8A5A44.toInt()
        p.strokeWidth = 1.7f * dp
        path.reset()
        path.moveTo(w + 2f * dp, 1.5f * dp)
        path.quadTo(w - 32f * dp, 9.5f * dp, w - 76f * dp, 4.5f * dp)
        c.drawPath(path, p)
        p.strokeWidth = 1.1f * dp
        path.reset()
        path.moveTo(w - 38f * dp, 7.4f * dp)
        path.quadTo(w - 46f * dp, 12f * dp, w - 54f * dp, 12.5f * dp)
        c.drawPath(path, p)
        p.style = Paint.Style.FILL
        p.strokeCap = Paint.Cap.BUTT
        blossom(c, w - 20f * dp, 5.5f * dp, 4.8f * dp)
        blossom(c, w - 50f * dp, 8f * dp, 4.1f * dp)
        blossom(c, w - 76f * dp, 4.5f * dp, 3.5f * dp)
        p.color = 0xFFFF8FB4.toInt()
        c.drawCircle(w - 55f * dp, 12.6f * dp, 1.7f * dp, p)
    }

    /** A falling petal [s] long. */
    private fun petal(c: Canvas, x: Float, y: Float, s: Float, rot: Float, color: Int, alpha: Int) {
        path.reset()
        path.moveTo(0f, -0.5f)
        path.cubicTo(0.55f, -0.35f, 0.5f, 0.3f, 0.08f, 0.5f)
        path.lineTo(0f, 0.4f)
        path.lineTo(-0.08f, 0.5f)
        path.cubicTo(-0.5f, 0.3f, -0.55f, -0.35f, 0f, -0.5f)
        path.close()
        place(s, rot, x, y)
        p.color = color
        p.alpha = alpha
        c.drawPath(path, p)
    }

    /** A five-petal cherry blossom of radius [r]. */
    private fun blossom(c: Canvas, x: Float, y: Float, r: Float) {
        p.color = 0xFFFFB7CE.toInt()
        for (k in 0 until 5) {
            val a = (k * 72 - 90) * PI.toFloat() / 180
            c.drawCircle(x + cos(a) * r * 0.52f, y + sin(a) * r * 0.52f, r * 0.5f, p)
        }
        p.color = 0xFFFFD6E3.toInt()
        c.drawCircle(x, y, r * 0.42f, p)
        p.color = 0xFFE0457B.toInt()
        c.drawCircle(x, y, r * 0.2f, p)
    }

    private fun ocean(c: Canvas, w: Float, h: Float, strip: Float, dp: Float) {
        // Sunlight slanting down from the surface.
        p.color = 0x10FFFFFF
        for (k in 0 until 3) {
            val x0 = w * (0.1f + 0.32f * k)
            path.reset()
            path.moveTo(x0, 0f)
            path.lineTo(x0 + w * 0.07f, 0f)
            path.lineTo(x0 + w * 0.2f, h)
            path.lineTo(x0 + w * 0.08f, h)
            path.close()
            c.drawPath(path, p)
        }
        // Ripples along the top edge.
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f * dp
        for (row in 0..1) {
            p.color = if (row == 0) 0x55BFEFFF else 0x30BFEFFF
            val base = (2.5f + 3.5f * row) * dp
            path.reset()
            var x = 0f
            path.moveTo(0f, base)
            while (x <= w) {
                path.lineTo(x, base + sin(x / (9f * dp) + row * 1.7f) * 1.1f * dp)
                x += 2f * dp
            }
            c.drawPath(path, p)
        }
        for (i in 0 until 24) {
            // In the strip, above or below the words, never on them.
            val band = noise(i, 35) < 0.5f
            val y = if (i % 3 == 0) noise(i, 32) * h else strip * (if (band) 0.06f + 0.22f * noise(i, 32) else 0.74f + 0.26f * noise(i, 32))
            bubble(c, noise(i, 31) * w, y, (1.3f + 2.7f * noise(i, 33)) * dp, (80 + 80 * noise(i, 34)).toInt(), dp)
        }
        p.style = Paint.Style.FILL
    }

    private fun bubble(c: Canvas, x: Float, y: Float, r: Float, alpha: Int, dp: Float) {
        p.style = Paint.Style.STROKE
        p.strokeWidth = 0.9f * dp
        p.color = 0xFFEAF8FF.toInt()
        p.alpha = alpha
        c.drawCircle(x, y, r, p)
        p.style = Paint.Style.FILL
        c.drawCircle(x - r * 0.35f, y - r * 0.35f, max(0.5f * dp, r * 0.2f), p)
    }

    // ------------------------------------------------------------------ keys

    /** A fun theme's extras on one key, drawn over its background and under its label. */
    fun keyExtra(c: Canvas, t: KbTheme, r: RectF, radius: Float, look: KeyLook, hasHint: Boolean, seed: Int, dp: Float) {
        reset()
        if (t.keyStroke != 0) outline(c, t, r, radius, look, dp)
        when (t.decor) {
            Decor.CHRISTMAS -> snowCap(c, r, radius, hasHint, seed, dp)
            Decor.HALLOWEEN -> if (look == KeyLook.ACCENT) pumpkin(c, r, dp)
            else -> Unit
        }
        reset()
    }

    private fun outline(c: Canvas, t: KbTheme, r: RectF, radius: Float, look: KeyLook, dp: Float) {
        val color = when (look) {
            KeyLook.ACCENT -> t.accent
            KeyLook.SPECIAL -> t.specialStroke
            KeyLook.LETTER -> t.keyStroke
        }
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.2f * dp
        p.color = color
        if (t.glow != 0) p.setShadowLayer(4f * dp, 0f, 0f, if (look == KeyLook.LETTER) t.glow else color)
        val i = 0.6f * dp
        c.drawRoundRect(r.left + i, r.top + i, r.right - i, r.bottom - i, radius, radius, p)
        p.clearShadowLayer()
        p.style = Paint.Style.FILL
    }

    /** Snow piled on the top of a key, scalloped, sometimes with an icicle drip. */
    private fun snowCap(c: Canvas, r: RectF, radius: Float, hasHint: Boolean, seed: Int, dp: Float) {
        // A long-press symbol hint sits in the top corner: keep the snow thin there.
        val capH = if (hasHint) 3f * dp else max(4.2f * dp, r.height() * 0.16f)
        clip.reset()
        clip.addRoundRect(r, radius, radius, Path.Direction.CW)
        c.save()
        c.clipPath(clip)
        val bumps = max(2, (r.width() / (10f * dp)).roundToInt())
        val bw = r.width() / bumps
        snowPath(r, capH, bumps, bw, seed, 0.9f * dp)
        p.color = 0x30000000
        c.drawPath(path, p)
        snowPath(r, capH, bumps, bw, seed, 0f)
        p.color = SNOW
        c.drawPath(path, p)
        if (!hasHint && noise(seed, 33) < 0.55f) {
            val k = (noise(seed, 34) * bumps).toInt().coerceIn(0, bumps - 1)
            val x = r.right - (k + 0.5f) * bw
            oval.set(x - 1.3f * dp, r.top + capH * 0.9f, x + 1.3f * dp, r.top + capH + (2.5f + 3f * noise(seed, 35)) * dp)
            c.drawRoundRect(oval, 1.3f * dp, 1.3f * dp, p)
        }
        c.restore()
    }

    private fun snowPath(r: RectF, capH: Float, bumps: Int, bw: Float, seed: Int, dy: Float) {
        path.reset()
        path.moveTo(r.left, r.top - 1f)
        path.lineTo(r.right, r.top - 1f)
        path.lineTo(r.right, r.top + capH * 0.7f + dy)
        for (i in 0 until bumps) {
            val x0 = r.right - i * bw
            val x1 = x0 - bw
            val sag = capH * (1.15f + 0.35f * noise(seed * 7 + i, 31))
            path.quadTo((x0 + x1) / 2, r.top + sag + dy, x1, r.top + capH * 0.7f + dy)
        }
        path.close()
    }

    /** The enter key as a pumpkin: darker ribs and a green stem poking out of the top. */
    private fun pumpkin(c: Canvas, r: RectF, dp: Float) {
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.3f * dp
        p.strokeCap = Paint.Cap.ROUND
        p.color = 0x38000000
        val cx = r.centerX()
        val cy = r.centerY()
        for (s in intArrayOf(-1, 1)) for (k in 1..2) {
            val dx = s * r.width() * 0.13f * k
            path.reset()
            path.moveTo(cx + dx * 0.5f, r.top + 2.5f * dp)
            path.quadTo(cx + dx * 1.35f, cy, cx + dx * 0.5f, r.bottom - 2.5f * dp)
            c.drawPath(path, p)
        }
        p.style = Paint.Style.FILL
        c.save()
        c.rotate(14f, cx, r.top + dp)
        p.color = 0xFF4E7A2A.toInt()
        c.drawRoundRect(cx - 1.7f * dp, r.top - 3.6f * dp, cx + 1.7f * dp, r.top + 1.6f * dp, dp, dp, p)
        c.restore()
        c.save()
        c.rotate(-25f, cx + 4f * dp, r.top - 0.5f * dp)
        p.color = 0xFF6FA83A.toInt()
        oval.set(cx + 1.5f * dp, r.top - 2f * dp, cx + 6.5f * dp, r.top + 0.6f * dp)
        c.drawOval(oval, p)
        c.restore()
    }

    /** Little motifs either side of the space bar's label. */
    fun spaceArt(c: Canvas, t: KbTheme, r: RectF, labelHalf: Float, dp: Float) {
        val s = r.height() * 0.4f
        val gap = labelHalf + s * 0.95f
        if (r.centerX() - gap - s / 2 < r.left + 4f * dp) return
        val lx = r.centerX() - gap
        val rx = r.centerX() + gap
        val cy = r.centerY()
        reset()
        when (t.decor) {
            Decor.HALLOWEEN -> { jackOLantern(c, lx, cy, s); ghost(c, rx, cy, s) }
            Decor.CHRISTMAS -> { holly(c, lx, cy, s); candyCane(c, rx, cy, s) }
            Decor.NEON -> { sun(c, lx, cy, s); sun(c, rx, cy, s) }
            Decor.SAKURA -> { blossom(c, lx, cy, s * 0.42f); blossom(c, rx, cy, s * 0.42f) }
            Decor.OCEAN -> { fish(c, lx, cy, s); starfish(c, rx, cy, s) }
            else -> Unit
        }
        reset()
    }

    private fun jackOLantern(c: Canvas, x: Float, y: Float, s: Float) {
        p.color = 0xFFFF7A1A.toInt()
        oval.set(x - s * 0.5f, y - s * 0.38f, x + s * 0.5f, y + s * 0.4f)
        c.drawOval(oval, p)
        p.color = 0xFFF06A10.toInt()
        oval.set(x - s * 0.2f, y - s * 0.4f, x + s * 0.2f, y + s * 0.42f)
        c.drawOval(oval, p)
        p.color = 0xFF4E7A2A.toInt()
        c.drawRect(x - s * 0.06f, y - s * 0.52f, x + s * 0.06f, y - s * 0.34f, p)
        // A face lit from inside.
        p.color = 0xFFFFE08A.toInt()
        for (side in intArrayOf(-1, 1)) {
            path.reset()
            val ex = x + side * s * 0.18f
            path.moveTo(ex - s * 0.08f, y - s * 0.02f)
            path.lineTo(ex + s * 0.08f, y - s * 0.02f)
            path.lineTo(ex, y - s * 0.17f)
            path.close()
            c.drawPath(path, p)
        }
        path.reset()
        path.moveTo(x - s * 0.3f, y + s * 0.08f)
        path.lineTo(x - s * 0.17f, y + s * 0.15f)
        path.lineTo(x - s * 0.1f, y + s * 0.09f)
        path.lineTo(x, y + s * 0.16f)
        path.lineTo(x + s * 0.1f, y + s * 0.09f)
        path.lineTo(x + s * 0.17f, y + s * 0.15f)
        path.lineTo(x + s * 0.3f, y + s * 0.08f)
        path.quadTo(x, y + s * 0.4f, x - s * 0.3f, y + s * 0.08f)
        path.close()
        c.drawPath(path, p)
    }

    private fun ghost(c: Canvas, x: Float, y: Float, s: Float) {
        val hw = s * 0.32f
        val top = y - s * 0.52f
        val bot = y + s * 0.44f
        path.reset()
        path.moveTo(x - hw, bot)
        path.lineTo(x - hw, y - s * 0.1f)
        path.cubicTo(x - hw, top, x + hw, top, x + hw, y - s * 0.1f)
        path.lineTo(x + hw, bot)
        val sw = 2 * hw / 3
        for (k in 0 until 3) {
            val x0 = x + hw - k * sw
            path.quadTo(x0 - sw / 2, bot - s * 0.17f, x0 - sw, bot)
        }
        path.close()
        p.color = 0xF2F3EEFF.toInt()
        p.setShadowLayer(s * 0.2f, 0f, 0f, 0x88C9B8FF.toInt())
        c.drawPath(path, p)
        p.clearShadowLayer()
        p.color = 0xFF1B0F2E.toInt()
        c.drawCircle(x - s * 0.11f, y - s * 0.13f, s * 0.055f, p)
        c.drawCircle(x + s * 0.11f, y - s * 0.13f, s * 0.055f, p)
        oval.set(x - s * 0.05f, y - s * 0.02f, x + s * 0.05f, y + s * 0.1f)
        c.drawOval(oval, p)
    }

    private fun holly(c: Canvas, x: Float, y: Float, s: Float) {
        p.color = 0xFF2E9E5B.toInt()
        hollyLeaf(c, x - s * 0.22f, y + s * 0.05f, s * 0.55f, -24f)
        hollyLeaf(c, x + s * 0.22f, y + s * 0.05f, s * 0.55f, 24f)
        p.color = 0xFFE53940.toInt()
        c.drawCircle(x - s * 0.09f, y - s * 0.1f, s * 0.12f, p)
        c.drawCircle(x + s * 0.09f, y - s * 0.1f, s * 0.12f, p)
        c.drawCircle(x, y + s * 0.05f, s * 0.12f, p)
        p.color = 0x99FFFFFF.toInt()
        c.drawCircle(x - s * 0.12f, y - s * 0.14f, s * 0.035f, p)
        c.drawCircle(x + s * 0.06f, y - s * 0.14f, s * 0.035f, p)
    }

    private fun hollyLeaf(c: Canvas, x: Float, y: Float, len: Float, rot: Float) {
        path.reset()
        path.moveTo(-0.5f, 0f)
        path.lineTo(-0.3f, -0.12f); path.lineTo(-0.22f, -0.21f); path.lineTo(-0.05f, -0.13f)
        path.lineTo(0.05f, -0.21f); path.lineTo(0.2f, -0.13f); path.lineTo(0.3f, -0.19f); path.lineTo(0.5f, 0f)
        path.lineTo(0.3f, 0.19f); path.lineTo(0.2f, 0.13f); path.lineTo(0.05f, 0.21f); path.lineTo(-0.05f, 0.13f)
        path.lineTo(-0.22f, 0.21f); path.lineTo(-0.3f, 0.12f)
        path.close()
        place(len, rot, x, y)
        c.drawPath(path, p)
    }

    private fun candyCane(c: Canvas, x: Float, y: Float, s: Float) {
        val hook = s * 0.17f
        val bot = y + s * 0.46f
        val top = y - s * 0.22f
        path.reset()
        path.moveTo(x + hook, bot)
        path.lineTo(x + hook, top)
        oval.set(x - hook, top - hook, x + hook, top + hook)
        path.arcTo(oval, 0f, -180f, false)
        path.lineTo(x - hook, top + s * 0.1f)
        p.style = Paint.Style.STROKE
        p.strokeWidth = s * 0.15f
        p.strokeCap = Paint.Cap.ROUND
        p.color = 0xFFFFFFFF.toInt()
        c.drawPath(path, p)
        p.strokeCap = Paint.Cap.BUTT
        p.color = 0xFFE53940.toInt()
        p.pathEffect = dashFor(s * 0.1f)
        c.drawPath(path, p)
        p.pathEffect = null
        p.style = Paint.Style.FILL
    }

    private fun dashFor(len: Float): DashPathEffect {
        val d = dash
        if (d != null && dashFor == len) return d
        return DashPathEffect(floatArrayOf(len, len), 0f).also { dash = it; dashFor = len }
    }

    /** A striped synthwave sun. */
    private fun sun(c: Canvas, x: Float, y: Float, s: Float) {
        val r = s * 0.42f
        clip.reset()
        clip.addCircle(x, y, r, Path.Direction.CW)
        c.save()
        c.clipPath(clip)
        val bands = 6
        for (i in 0 until bands) {
            val y0 = y - r + 2 * r * i / bands
            val y1 = y0 + 2 * r / bands
            p.color = KbTheme.blend(0xFFFFD319.toInt(), 0xFFFF2BD6.toInt(), i / (bands - 1f))
            c.drawRect(x - r, y0, x + r, y1 - if (i >= 3) (i - 2) * r * 0.07f else 0f, p)
        }
        c.restore()
    }

    private fun fish(c: Canvas, x: Float, y: Float, s: Float) {
        p.color = 0xFFFFB74D.toInt()
        oval.set(x - s * 0.3f, y - s * 0.2f, x + s * 0.3f, y + s * 0.2f)
        c.drawOval(oval, p)
        path.reset()
        path.moveTo(x - s * 0.22f, y)
        path.lineTo(x - s * 0.5f, y - s * 0.2f)
        path.lineTo(x - s * 0.5f, y + s * 0.2f)
        path.close()
        c.drawPath(path, p)
        p.color = 0xFF02263F.toInt()
        c.drawCircle(x + s * 0.14f, y - s * 0.04f, s * 0.05f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = s * 0.045f
        p.color = 0xAAEAF8FF.toInt()
        c.drawCircle(x + s * 0.44f, y - s * 0.2f, s * 0.06f, p)
        c.drawCircle(x + s * 0.54f, y - s * 0.38f, s * 0.04f, p)
        p.style = Paint.Style.FILL
    }

    private fun starfish(c: Canvas, x: Float, y: Float, s: Float) {
        path.reset()
        for (k in 0 until 10) {
            val a = (-PI / 2 + k * PI / 5).toFloat()
            val rr = if (k % 2 == 0) s * 0.42f else s * 0.19f
            val px = x + cos(a) * rr
            val py = y + sin(a) * rr
            if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        p.color = 0xFFFF8A65.toInt()
        p.style = Paint.Style.FILL_AND_STROKE
        p.strokeJoin = Paint.Join.ROUND
        p.strokeWidth = s * 0.08f
        c.drawPath(path, p)
        p.style = Paint.Style.FILL
        p.color = 0x66FFFFFF
        for (k in 0 until 5) {
            val a = (-PI / 2 + k * 2 * PI / 5).toFloat()
            c.drawCircle(x + cos(a) * s * 0.22f, y + sin(a) * s * 0.22f, s * 0.035f, p)
        }
    }

    // ------------------------------------------------------------------ trail and taps

    /** A small piece of scenery dropped along the glide trail (nothing for themes without one). */
    fun trailBit(c: Canvas, decor: Decor, x: Float, y: Float, size: Float, rot: Float, alpha: Int, dp: Float) {
        reset()
        when (decor) {
            Decor.HALLOWEEN -> ember(c, x, y, size * 0.3f, 0xFFFFB347.toInt(), alpha, dp)
            Decor.CHRISTMAS -> snowflake(c, x, y, size, rot, alpha, dp)
            Decor.SAKURA -> petal(c, x, y, size, rot, PETAL, alpha)
            Decor.OCEAN -> bubble(c, x, y, size * 0.35f, alpha, dp)
            else -> Unit
        }
        reset()
    }

    /**
     * One particle of a tap burst. [age] runs 0..1 over its life; [tint] picks between the theme's
     * two particle colours.
     */
    fun particle(c: Canvas, decor: Decor, x: Float, y: Float, size: Float, rot: Float, alpha: Int, age: Float, tint: Int, dp: Float) {
        reset()
        when (decor) {
            Decor.HALLOWEEN -> ember(c, x, y, size, if (tint == 0) 0xFFFFB347.toInt() else 0xFFFF6A00.toInt(), alpha, dp)
            Decor.CHRISTMAS -> snowflake(c, x, y, size, rot, alpha, dp)
            Decor.NEON -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = 1.6f * dp
                val color = if (tint == 0) 0xFFFF2BD6.toInt() else 0xFF00E5FF.toInt()
                p.color = color
                p.alpha = alpha
                p.setShadowLayer(4f * dp, 0f, 0f, color)
                c.drawCircle(x, y, size * (0.6f + 2.6f * age), p)
                p.clearShadowLayer()
            }
            Decor.SAKURA -> petal(c, x, y, size, rot, if (tint == 0) PETAL else 0xFFFFD1E0.toInt(), alpha)
            Decor.OCEAN -> bubble(c, x, y, size, alpha, dp)
            Decor.RETRO -> {
                p.color = 0xFF39FF6A.toInt()
                p.alpha = alpha
                c.drawRect(x - size / 2, y - size / 2, x + size / 2, y + size / 2, p)
            }
            Decor.NONE -> Unit
        }
        reset()
    }

    private fun ember(c: Canvas, x: Float, y: Float, r: Float, color: Int, alpha: Int, dp: Float) {
        p.color = color
        p.alpha = alpha
        p.setShadowLayer(3f * dp, 0f, 0f, color)
        c.drawCircle(x, y, r, p)
        p.clearShadowLayer()
    }

    private fun snowflake(c: Canvas, x: Float, y: Float, s: Float, rot: Float, alpha: Int, dp: Float) {
        p.style = Paint.Style.STROKE
        p.strokeWidth = max(0.8f * dp, s * 0.13f)
        p.strokeCap = Paint.Cap.ROUND
        p.color = 0xFFFFFFFF.toInt()
        p.alpha = alpha
        for (k in 0 until 3) {
            val a = (rot + k * 60f) * PI.toFloat() / 180
            val dx = cos(a) * s / 2
            val dy = sin(a) * s / 2
            c.drawLine(x - dx, y - dy, x + dx, y + dy, p)
        }
        p.style = Paint.Style.FILL
        p.strokeCap = Paint.Cap.BUTT
    }

    /** Old-monitor scanlines over everything (the retro terminal). */
    fun scanlines(c: Canvas, w: Float, h: Float, dp: Float) {
        reset()
        p.color = 0x2A000000
        val step = 3f * dp
        var y = 0f
        while (y < h) {
            c.drawRect(0f, y, w, y + 0.9f * dp, p)
            y += step
        }
        p.color = 0x0839FF6A
        c.drawRect(0f, 0f, w, h, p)
        reset()
    }
}
