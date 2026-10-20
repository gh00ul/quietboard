package dev.larryjones.quietboard

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * The moving part of each fun theme's picture: snow, petals, leaves, bubbles, hearts, fireworks,
 * bats, the synthwave grid. Every position is a function of [time] (seconds), worked out fresh on
 * each frame, so there is no state to step and a paused scene simply stays where it was. Time 0 is a
 * good still frame (what you see with Theme effects off).
 *
 * Things passing through the suggestion strip are drawn at half strength so they never compete with
 * the words.
 */
internal object Ambient {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private const val TAU = (2 * PI).toFloat()

    private fun reset() {
        p.reset()
        p.isAntiAlias = true
    }

    fun draw(c: Canvas, t: KbTheme, w: Float, h: Float, s: Float, dp: Float, time: Float) {
        reset()
        val k = h - s
        when (t.decor) {
            Decor.HALLOWEEN -> { twinkle(c, w, s + 0.7f * k, s, 24, 101, 0xFFFFE9C9.toInt(), dp, time); fog(c, w, h, k, dp, time); bats(c, w, s, k, dp, time) }
            Decor.CHRISTMAS -> { snow(c, w, h, s, dp, time); bulbs(c, w, dp, time) }
            Decor.NEON -> { twinkle(c, w, Scenes.horizon(s, k) * 0.6f, s, 16, 301, 0xFFFFFFFF.toInt(), dp, time); grid(c, w, h, s, k, dp, time) }
            Decor.SAKURA -> petals(c, w, h, s, dp, time)
            Decor.OCEAN -> { seaweed(c, w, h, k, dp, time); fish(c, w, s, k, dp, time); bubbles(c, w, h, s, dp, time) }
            Decor.RETRO -> retro(c, w, s, k, dp, time)
            Decor.GALAXY -> { twinkle(c, w, h, s, 34, 501, 0xFFFFFFFF.toInt(), dp, time); shootingStar(c, w, s, k, dp, time) }
            Decor.AUTUMN -> leaves(c, w, h, s, dp, time)
            Decor.VALENTINE -> hearts(c, w, h, s, dp, time)
            Decor.NEW_YEAR -> { twinkle(c, w, h - 0.3f * k, s, 24, 701, 0xFFFFFFFF.toInt(), dp, time); fireworks(c, w, h, s, k, dp, time) }
            Decor.NONE -> Unit
        }
        reset()
    }

    private fun inStrip(y: Float, s: Float, a: Int) = if (y < s) a / 2 else a

    /** Wraps [v] into 0..[span]. */
    private fun wrap(v: Float, span: Float): Float {
        val r = v % span
        return if (r < 0f) r + span else r
    }

    /** Stars that brighten and fade on their own rhythms. */
    private fun twinkle(c: Canvas, w: Float, bottom: Float, s: Float, n: Int, salt: Int, color: Int, dp: Float, time: Float) {
        for (i in 0 until n) {
            val x = noise(i, salt) * w
            val y = noise(i, salt + 1) * bottom
            val glint = 0.5f + 0.5f * sin(time * (0.8f + 2.2f * noise(i, salt + 2)) + TAU * noise(i, salt + 3))
            p.color = color
            p.alpha = inStrip(y, s, (40 + 200 * glint * glint).toInt())
            c.drawCircle(x, y, (0.5f + 0.9f * glint) * dp, p)
        }
    }

    // ------------------------------------------------------------------ falling and rising things

    private inline fun falling(
        n: Int, salt: Int, w: Float, h: Float, dp: Float, time: Float,
        minSpeed: Float, maxSpeed: Float, sway: Float, drift: Float,
        fn: (i: Int, x: Float, y: Float, depth: Float) -> Unit,
    ) {
        val margin = 12f * dp
        val span = h + 2 * margin
        for (i in 0 until n) {
            val depth = noise(i, salt) // 0 far (small, slow) .. 1 near
            val speed = (minSpeed + (maxSpeed - minSpeed) * depth) * dp
            val y = wrap(noise(i, salt + 1) * span + speed * time, span) - margin
            val x = wrap(noise(i, salt + 2) * w + drift * dp * time +
                sway * dp * sin(time * (0.5f + 0.7f * noise(i, salt + 3)) + TAU * noise(i, salt + 4)), w + 2 * margin) - margin
            fn(i, x, y, depth)
        }
    }

    private fun snow(c: Canvas, w: Float, h: Float, s: Float, dp: Float, time: Float) {
        falling(90, 1100, w, h, dp, time, 10f, 36f, 9f, 2f) { _, x, y, d ->
            p.color = 0xFFFFFFFF.toInt()
            p.alpha = inStrip(y, s, (110 + 130 * d).toInt())
            c.drawCircle(x, y, (0.7f + 1.8f * d) * dp, p)
        }
    }

    private fun petals(c: Canvas, w: Float, h: Float, s: Float, dp: Float, time: Float) {
        falling(38, 1200, w, h, dp, time, 9f, 24f, 14f, 8f) { i, x, y, d ->
            val rot = noise(i, 1205) * 360f + time * (40f + 80f * noise(i, 1206)) * (if (i % 2 == 0) 1 else -1)
            Scenery.petal(c, x, y, (3.4f + 3f * d) * dp, rot, if (i % 3 == 0) 0xFFFFD1E0.toInt() else Scenery.PETAL, inStrip(y, s, (150 + 90 * d).toInt()))
        }
    }

    private fun leaves(c: Canvas, w: Float, h: Float, s: Float, dp: Float, time: Float) {
        falling(24, 1300, w, h, dp, time, 12f, 28f, 20f, 5f) { i, x, y, d ->
            val rot = noise(i, 1305) * 360f + time * (50f + 90f * noise(i, 1306)) * (if (i % 2 == 0) 1 else -1)
            Scenery.leaf(c, x, y, (6f + 5f * d) * dp, rot, Scenery.LEAVES[i % 4], inStrip(y, s, (170 + 80 * d).toInt()))
        }
    }

    private fun hearts(c: Canvas, w: Float, h: Float, s: Float, dp: Float, time: Float) {
        falling(22, 1400, w, h, dp, time, -8f, -20f, 10f, 0f) { i, x, y, d ->
            Scenery.heart(c, x, y, (5f + 7f * d) * dp, 12f * sin(time * 1.3f + i), Scenery.HEARTS[i % 4], inStrip(y, s, (110 + 100 * d).toInt()))
        }
    }

    private fun bubbles(c: Canvas, w: Float, h: Float, s: Float, dp: Float, time: Float) {
        falling(26, 1500, w, h, dp, time, -16f, -42f, 4f, 0f) { _, x, y, d ->
            Scenery.bubble(c, x, y, (1.3f + 2.6f * d) * dp, inStrip(y, s, (80 + 90 * d).toInt()), dp)
        }
    }

    // ------------------------------------------------------------------ Halloween

    private fun fog(c: Canvas, w: Float, h: Float, k: Float, dp: Float, time: Float) {
        for (band in 0..1) {
            val y = h - (0.16f - 0.09f * band) * k
            val shift = wrap(time * (5f + 4f * band) * dp, w)
            p.color = if (band == 0) 0x14E8DFFF else 0x1CE8DFFF
            for (j in -1..3) {
                val cx = j * w * 0.4f + shift + band * w * 0.2f
                c.drawOval(cx - w * 0.26f, y - 0.05f * k, cx + w * 0.26f, y + 0.05f * k, p)
            }
        }
    }

    private fun bats(c: Canvas, w: Float, s: Float, k: Float, dp: Float, time: Float) {
        val moon = Scenes.moon(w, s, k)
        for (i in 0 until 3) {
            val speed = 0.22f + 0.08f * i
            val a = time * speed + i * 2.1f
            val x = moon[0] + cos(a) * moon[2] * (1.5f + 0.4f * i)
            val y = moon[1] - moon[2] * 0.3f + sin(2 * a) * moon[2] * 0.45f
            Scenery.bat(c, x, y, (5.5f + 1.5f * i) * dp, 8f * sin(a), Scenery.NIGHT, dp, sin(time * (7f + i)))
        }
        // One more, small and high, crossing the top of the strip.
        val x = wrap(time * 14f * dp + w * 0.24f, w + 40f * dp) - 20f * dp
        Scenery.bat(c, x, 6.5f * dp, 5.5f * dp, -8f, Scenery.NIGHT, dp, sin(time * 8f))
    }

    // ------------------------------------------------------------------ Christmas

    private val BULBS = intArrayOf(0xFFFF4D4D.toInt(), 0xFFFFD54F.toInt(), 0xFF5CFF8F.toInt(), 0xFF4FC3F7.toInt())

    private fun bulbs(c: Canvas, w: Float, dp: Float, time: Float) {
        val swags = maxOf(2, (w / (46f * dp)).toInt())
        val sw = w / swags
        val y0 = 1.5f * dp
        val yc = 9f * dp
        val phase = floor(time / 1.1f).toInt()
        var b = 0
        for (i in 0 until swags) for (step in 1..2) {
            val tt = step / 3f
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
            c.rotate((kotlin.math.atan2(dy, dx) * 180 / PI).toFloat())
            p.color = 0xFF39403A.toInt()
            c.drawRect(-1.1f * dp, -0.4f * dp, 1.1f * dp, 1.6f * dp, p)
            p.color = color
            if (lit) p.setShadowLayer(5f * dp, 0f, 0f, color) else p.alpha = 140
            c.drawOval(-2.2f * dp, 1.2f * dp, 2.2f * dp, 7.2f * dp, p)
            p.clearShadowLayer()
            p.color = 0x99FFFFFF.toInt()
            c.drawCircle(-0.8f * dp, 3f * dp, 0.7f * dp, p)
            c.restore()
            b++
        }
    }

    // ------------------------------------------------------------------ Neon

    /** The floor's cross lines, rolling toward you. */
    private fun grid(c: Canvas, w: Float, h: Float, s: Float, k: Float, dp: Float, time: Float) {
        val hy = Scenes.horizon(s, k)
        val n = 11
        val roll = wrap(time * 0.4f, 1f)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.2f * dp
        for (i in 0 until n) {
            val z = (i + roll) / n
            val y = hy + (h - hy) * z * z
            p.color = 0xFFFF2BD6.toInt()
            p.alpha = (50 + 190 * z).toInt()
            c.drawLine(0f, y, w, y, p)
        }
        p.style = Paint.Style.FILL
    }

    // ------------------------------------------------------------------ Ocean

    private fun seaweed(c: Canvas, w: Float, h: Float, k: Float, dp: Float, time: Float) {
        val bed = Scenes.seabed(h, k)
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        for (i in 0 until 7) {
            val x = w * (0.05f + 0.15f * i + 0.04f * noise(i, 1601))
            val tall = (0.22f + 0.22f * noise(i, 1602)) * k
            val sway = sin(time * (0.9f + 0.4f * noise(i, 1603)) + i) * 7f * dp
            path.reset()
            path.moveTo(x, bed + 0.04f * k)
            path.cubicTo(x - 6f * dp + sway * 0.3f, bed - tall * 0.35f, x + 6f * dp + sway * 0.7f, bed - tall * 0.7f, x + sway, bed - tall)
            p.color = if (i % 2 == 0) 0xD020905E.toInt() else 0xC02BAE6E.toInt()
            p.strokeWidth = (2.2f + 1.4f * noise(i, 1604)) * dp
            c.drawPath(path, p)
        }
        p.style = Paint.Style.FILL
    }

    private fun fish(c: Canvas, w: Float, s: Float, k: Float, dp: Float, time: Float) {
        for (i in 0 until 5) {
            val size = (6f + 5f * noise(i, 1701)) * dp
            val speed = (12f + 18f * noise(i, 1702)) * dp
            val span = w + 4 * size
            val x = w + 2 * size - wrap(noise(i, 1703) * span + speed * time, span)
            val y = s + (0.22f + 0.5f * noise(i, 1704)) * k + sin(time * 1.4f + i) * 2f * dp
            p.color = if (i % 2 == 0) 0x60BFEFFF else 0x70FFC07A
            c.drawOval(x - size, y - size * 0.42f, x + size * 0.6f, y + size * 0.42f, p)
            path.reset()
            path.moveTo(x + size * 0.45f, y)
            path.lineTo(x + size * 1.05f, y - size * 0.4f)
            path.lineTo(x + size * 1.05f, y + size * 0.4f)
            path.close()
            c.drawPath(path, p)
        }
    }

    // ------------------------------------------------------------------ Retro terminal

    private fun retro(c: Canvas, w: Float, s: Float, k: Float, dp: Float, time: Float) {
        // A bright band rolling down the screen, like an old CRT.
        val y = s + wrap(time / 5f, 1f) * k
        p.color = 0x1439FF6A
        c.drawRect(0f, y - 10f * dp, w, y + 10f * dp, p)
        p.color = 0x1C39FF6A
        c.drawRect(0f, y - 3f * dp, w, y + 3f * dp, p)
        // The cursor after the last line blinks.
        if (wrap(time, 1.1f) < 0.6f) {
            p.textSize = 9.5f * dp
            p.typeface = android.graphics.Typeface.MONOSPACE
            val lines = Scenes.retroLines
            val last = lines.size - 1
            val ly = Scenes.retroTextTop(s, dp) + last * Scenes.retroLineHeight(dp)
            val lx = 8f * dp + p.measureText(lines[last]) + 3f * dp
            p.typeface = null
            p.color = 0x5539FF6A
            c.drawRect(lx, ly - 8f * dp, lx + 5.5f * dp, ly + 1.5f * dp, p)
        }
    }

    // ------------------------------------------------------------------ Galaxy

    private fun shootingStar(c: Canvas, w: Float, s: Float, k: Float, dp: Float, time: Float) {
        val period = 5.5f
        val cycle = floor(time / period).toInt()
        val local = time - cycle * period
        if (local > 0.9f) return
        val f = local / 0.9f
        val x0 = w * (0.2f + 0.6f * noise(cycle, 1901))
        val y0 = s + 0.05f * k + 0.25f * k * noise(cycle, 1902)
        val len = 0.35f * w
        val hx = x0 - len * f
        val hy = y0 + len * 0.35f * f
        p.strokeCap = Paint.Cap.ROUND
        p.style = Paint.Style.STROKE
        for (seg in 0 until 6) {
            val a = seg / 6f
            p.strokeWidth = (1.8f - 1.2f * a) * dp
            p.color = 0xFFFFFFFF.toInt()
            p.alpha = ((1 - a) * 230 * (1 - f * 0.6f)).toInt()
            val t0 = a * 0.18f
            val t1 = (a + 1f / 6f) * 0.18f
            c.drawLine(hx + len * t0, hy - len * 0.35f * t0, hx + len * t1, hy - len * 0.35f * t1, p)
        }
        p.style = Paint.Style.FILL
    }

    // ------------------------------------------------------------------ New Year

    private fun fireworks(c: Canvas, w: Float, h: Float, s: Float, k: Float, dp: Float, time: Float) {
        val period = 3.2f
        for (slot in 0 until 3) {
            val tt = time + slot * 1.07f
            val cycle = floor(tt / period).toInt()
            val local = tt - cycle * period
            val seed = cycle * 3 + slot
            val bx = w * (0.15f + 0.7f * noise(seed, 2001))
            val by = s + (0.08f + 0.34f * noise(seed, 2002)) * k
            val color = Scenery.FIREWORKS[((cycle + slot) % Scenery.FIREWORKS.size + Scenery.FIREWORKS.size) % Scenery.FIREWORKS.size]
            if (local < 0.7f) {
                // The rocket climbing, easing out as it nears the top.
                val f = local / 0.7f
                val e = 1 - (1 - f) * (1 - f)
                val y = h - (h - by) * e
                p.color = 0xFFFFF0C8.toInt()
                c.drawCircle(bx, y, 1.3f * dp, p)
                p.color = 0x66FFF0C8
                c.drawRect(bx - 0.5f * dp, y, bx + 0.5f * dp, y + 10f * dp, p)
            } else if (local < 2.6f) {
                val tb = local - 0.7f
                val fade = 1 - tb / 1.9f
                if (tb < 0.25f) {
                    p.color = color
                    p.alpha = ((1 - tb / 0.25f) * 120).toInt()
                    c.drawCircle(bx, by, (10f + 30f * tb) * dp, p)
                }
                val n = 28
                p.strokeCap = Paint.Cap.ROUND
                for (i in 0 until n) {
                    val a = i * TAU / n + noise(seed * 31 + i, 2003) * 0.2f
                    val v = (62f + 34f * noise(seed * 37 + i, 2004)) * dp
                    val dist = v * tb * (1 - 0.3f * tb)
                    val drop = 0.5f * 30f * dp * tb * tb
                    val x = bx + cos(a) * dist
                    val y = by + sin(a) * dist + drop
                    // A short streak behind each spark, and a soft halo.
                    val back = v * 0.06f
                    val alpha = inStrip(y, s, (255 * fade * fade).toInt().coerceIn(0, 255))
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = 1.6f * dp
                    p.color = color
                    p.alpha = alpha
                    c.drawLine(x - cos(a) * back, y - sin(a) * back, x, y, p)
                    p.style = Paint.Style.FILL
                    p.alpha = alpha / 4
                    c.drawCircle(x, y, 3.6f * dp, p)
                    p.alpha = alpha
                    c.drawCircle(x, y, 1.7f * dp, p)
                }
            }
        }
    }
}
