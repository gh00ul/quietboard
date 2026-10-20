package dev.larryjones.quietboard

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.max
import kotlin.math.min

/**
 * The still part of each fun theme's picture, painted behind the strip and keys over the theme's
 * gradient. The keyboard paints it once into a bitmap; what moves is [Ambient].
 *
 * Layout: [s] is the suggestion strip's height, [k] the keys' area below it. Big or bright shapes
 * (moon, sun, planet) sit in the keys' area, where the glass keys soften them; the strip only gets
 * small things in its corners and top edge, so suggestions never sit on a bright moon.
 */
internal object Scenes {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val path2 = Path()
    private val rect = RectF()

    private fun reset() {
        p.reset()
        p.isAntiAlias = true
    }

    fun paint(c: Canvas, t: KbTheme, w: Float, h: Float, s: Float, dp: Float) {
        reset()
        val k = h - s
        when (t.decor) {
            Decor.HALLOWEEN -> halloween(c, w, h, s, k, dp)
            Decor.CHRISTMAS -> christmas(c, w, h, s, k, dp)
            Decor.NEON -> neon(c, t, w, h, s, k, dp)
            Decor.SAKURA -> sakura(c, w, h, s, k, dp)
            Decor.OCEAN -> ocean(c, w, h, s, k, dp)
            Decor.RETRO -> retro(c, w, h, s, k, dp)
            Decor.GALAXY -> galaxy(c, w, h, s, k, dp)
            Decor.AUTUMN -> autumn(c, w, h, s, k, dp)
            Decor.VALENTINE -> valentine(c, w, h, s, k, dp)
            Decor.NEW_YEAR -> newYear(c, w, h, s, k, dp)
            Decor.NONE -> Unit
        }
        reset()
    }

    // Shared layout, so the moving layer lines up with the still one.
    fun moon(w: Float, s: Float, k: Float) = floatArrayOf(w * 0.87f, s + 0.27f * k, min(0.12f * k, 0.13f * w))
    fun horizon(s: Float, k: Float) = s + 0.44f * k
    fun seabed(h: Float, k: Float) = h - 0.11f * k

    // ------------------------------------------------------------------ helpers

    /** A soft round glow, [color] at [alpha] in the middle fading to nothing at [r]. */
    private fun glow(c: Canvas, x: Float, y: Float, r: Float, color: Int, alpha: Int) {
        if (r <= 0f) return
        val inner = (color and 0x00FFFFFF) or (alpha shl 24)
        p.shader = RadialGradient(x, y, r, inner, color and 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r, p)
        p.shader = null
    }

    private fun stars(c: Canvas, n: Int, salt: Int, w: Float, top: Float, bottom: Float, color: Int, dp: Float,
                      minA: Int = 50, maxA: Int = 170, maxR: Float = 1.1f) {
        for (i in 0 until n) {
            p.color = color
            p.alpha = minA + ((maxA - minA) * noise(i, salt + 1)).toInt()
            c.drawCircle(noise(i, salt) * w, top + noise(i, salt + 2) * (bottom - top), (0.35f + maxR * noise(i, salt + 3)) * dp, p)
        }
    }

    /** Rolling hills filled down to the bottom: [waves] gentle humps around [base], up to [amp] high. */
    private fun hills(c: Canvas, w: Float, h: Float, base: Float, amp: Float, waves: Int, salt: Int, color: Int) {
        path.reset()
        path.moveTo(0f, h)
        val n = waves * 2
        var px = 0f
        var py = base - amp * 0.3f * noise(0, salt)
        path.lineTo(px, py)
        for (i in 1..n) {
            val x = w * i / n
            val y = if (i % 2 == 1) base - amp * (0.55f + 0.45f * noise(i, salt)) else base - amp * 0.15f * noise(i, salt + 1)
            val dx = (x - px) / 2
            path.cubicTo(px + dx, py, x - dx, y, x, y)
            px = x; py = y
        }
        path.lineTo(w, h)
        path.close()
        p.color = color
        c.drawPath(path, p)
    }

    /** A jagged mountain ridge standing on [base], peaks up to [amp]; optional glowing [edge]. */
    private fun ridge(c: Canvas, w: Float, base: Float, amp: Float, salt: Int, color: Int, edge: Int = 0, dp: Float = 1f) {
        path.reset()
        path.moveTo(0f, base)
        val n = 9
        for (i in 0..n) {
            val x = w * i / n
            val peak = i % 2 == 1
            val y = base - amp * (if (peak) 0.55f + 0.45f * noise(i, salt) else 0.1f + 0.25f * noise(i, salt + 1))
            path.lineTo(x, y)
        }
        path.lineTo(w, base)
        path.close()
        p.color = color
        c.drawPath(path, p)
        if (edge != 0) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1.2f * dp
            p.strokeJoin = Paint.Join.ROUND
            p.color = edge
            p.setShadowLayer(4f * dp, 0f, 0f, edge)
            c.drawPath(path, p)
            p.clearShadowLayer()
            p.style = Paint.Style.FILL
        }
    }

    // ------------------------------------------------------------------ Halloween

    private fun halloween(c: Canvas, w: Float, h: Float, s: Float, k: Float, dp: Float) {
        stars(c, 70, 100, w, 0f, s + 0.7f * k, 0xFFFFE9C9.toInt(), dp)
        val (mx, my, mr) = moon(w, s, k).let { Triple(it[0], it[1], it[2]) }
        glow(c, mx, my, mr * 3.2f, 0xFFFFC46B.toInt(), 0x60)
        p.color = 0xFFFFE9B8.toInt()
        c.drawCircle(mx, my, mr, p)
        p.color = 0x30A0763A
        c.drawCircle(mx - mr * 0.3f, my - mr * 0.2f, mr * 0.22f, p)
        c.drawCircle(mx + mr * 0.25f, my + mr * 0.3f, mr * 0.16f, p)
        c.drawCircle(mx + mr * 0.35f, my - mr * 0.35f, mr * 0.1f, p)
        // A soft wisp of cloud across the moon.
        p.color = 0x701B0F2E
        rect.set(mx - mr * 1.9f, my + mr * 0.18f, mx + mr * 0.9f, my + mr * 0.62f)
        c.drawOval(rect, p)

        val far = h - 0.30f * k
        hills(c, w, h, far, 0.08f * k, 3, 7, 0xFF180C2A.toInt())
        house(c, w * 0.2f, far + 0.01f * k, 0.24f * k, dp)
        val near = h - 0.15f * k
        hills(c, w, h, near, 0.06f * k, 4, 9, 0xFF0A0611.toInt())
        deadTree(c, w * 0.9f, near + 0.02f * k, 0.44f * k)
        for (i in 0 until 4) {
            grave(c, w * (0.44f + 0.1f * i + 0.03f * noise(i, 5)), near + 0.015f * k, 0.065f * k * (0.8f + 0.4f * noise(i, 6)))
        }
        for (i in 0 until 3) pumpkin(c, w * (0.08f + 0.29f * i + 0.04f * noise(i, 8)), h - 0.07f * k, 0.042f * k, dp)

        Scenery.web(c, 0f, 1f, 26f * dp, dp)
        Scenery.web(c, w, -1f, 21f * dp, dp)
        Scenery.spider(c, 13f * dp, 6.5f * dp, 15.5f * dp, dp)
    }

    private fun house(c: Canvas, x: Float, base: Float, s: Float, dp: Float) {
        p.color = 0xFF0B0614.toInt()
        val bw = s * 0.9f
        val bh = s * 0.5f
        c.drawRect(x - bw / 2, base - bh, x + bw / 2, base + s * 0.3f, p)
        path.reset()
        path.moveTo(x - bw * 0.62f, base - bh)
        path.lineTo(x - bw * 0.1f, base - bh - s * 0.36f)
        path.lineTo(x + bw * 0.62f, base - bh)
        path.close()
        c.drawPath(path, p)
        val tx = x + bw * 0.3f
        c.drawRect(tx - s * 0.09f, base - bh - s * 0.42f, tx + s * 0.09f, base - bh + 1f, p)
        path.reset()
        path.moveTo(tx - s * 0.13f, base - bh - s * 0.42f)
        path.lineTo(tx, base - bh - s * 0.72f)
        path.lineTo(tx + s * 0.13f, base - bh - s * 0.42f)
        path.close()
        c.drawPath(path, p)
        // Lit windows.
        p.color = 0xFFFFB347.toInt()
        p.setShadowLayer(4f * dp, 0f, 0f, 0xFFFF8A1A.toInt())
        val ww = s * 0.07f
        val wh = s * 0.1f
        for (wx in floatArrayOf(x - bw * 0.28f, x - bw * 0.02f)) c.drawRect(wx, base - bh * 0.62f, wx + ww, base - bh * 0.62f + wh, p)
        c.drawRect(tx - ww / 2, base - bh - s * 0.3f, tx + ww / 2, base - bh - s * 0.3f + wh, p)
        p.clearShadowLayer()
    }

    private fun deadTree(c: Canvas, x: Float, base: Float, s: Float) {
        p.color = 0xFF07040C.toInt()
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        fun br(x0: Float, y0: Float, x1: Float, y1: Float, wFrac: Float) {
            p.strokeWidth = s * wFrac
            c.drawLine(x + x0 * s, base + y0 * s, x + x1 * s, base + y1 * s, p)
        }
        br(0f, 0f, -0.03f, -0.58f, 0.06f)
        br(-0.02f, -0.36f, -0.26f, -0.6f, 0.032f)
        br(-0.02f, -0.48f, 0.2f, -0.72f, 0.03f)
        br(-0.03f, -0.56f, -0.07f, -0.88f, 0.025f)
        br(-0.26f, -0.6f, -0.38f, -0.63f, 0.014f)
        br(-0.26f, -0.6f, -0.28f, -0.76f, 0.014f)
        br(0.2f, -0.72f, 0.31f, -0.75f, 0.013f)
        br(0.2f, -0.72f, 0.21f, -0.86f, 0.013f)
        br(-0.07f, -0.88f, 0.02f, -0.97f, 0.012f)
        p.style = Paint.Style.FILL
    }

    private fun grave(c: Canvas, x: Float, base: Float, s: Float) {
        p.color = 0xFF2A2238.toInt()
        rect.set(x - s * 0.35f, base - s, x + s * 0.35f, base + s * 0.2f)
        c.drawRoundRect(rect, s * 0.35f, s * 0.35f, p)
        p.color = 0x40000000
        c.drawRect(x - s * 0.04f, base - s * 0.75f, x + s * 0.04f, base - s * 0.35f, p)
        c.drawRect(x - s * 0.16f, base - s * 0.62f, x + s * 0.16f, base - s * 0.54f, p)
    }

    private fun pumpkin(c: Canvas, x: Float, y: Float, r: Float, dp: Float) {
        glow(c, x, y, r * 3f, 0xFFFF8A1A.toInt(), 0x55)
        p.color = 0xFFE8650F.toInt()
        rect.set(x - r, y - r * 0.8f, x + r, y + r * 0.8f)
        c.drawOval(rect, p)
        p.color = 0xFF4E7A2A.toInt()
        c.drawRect(x - r * 0.12f, y - r * 1.05f, x + r * 0.12f, y - r * 0.7f, p)
        p.color = 0xFFFFE08A.toInt()
        for (side in intArrayOf(-1, 1)) {
            path.reset()
            val ex = x + side * r * 0.38f
            path.moveTo(ex - r * 0.16f, y - r * 0.02f)
            path.lineTo(ex + r * 0.16f, y - r * 0.02f)
            path.lineTo(ex, y - r * 0.32f)
            path.close()
            c.drawPath(path, p)
        }
        rect.set(x - r * 0.5f, y + r * 0.12f, x + r * 0.5f, y + r * 0.42f)
        c.drawRoundRect(rect, r * 0.1f, r * 0.1f, p)
    }

    // ------------------------------------------------------------------ Christmas

    private fun christmas(c: Canvas, w: Float, h: Float, s: Float, k: Float, dp: Float) {
        stars(c, 55, 200, w, 0f, s + 0.55f * k, 0xFFFFFFFF.toInt(), dp, 40, 150, 0.9f)
        val sx = w * 0.84f
        val sy = s + 0.17f * k
        glow(c, sx, sy, 0.2f * k, 0xFFFFE08A.toInt(), 0x70)
        Scenery.sparkle(c, sx, sy, 0.14f * k, 0xFFFFF2C2.toInt(), 255, 6f * dp)
        Scenery.sparkle(c, sx, sy, 0.07f * k, 0xFFFFFFFF.toInt(), 255)

        val far = h - 0.36f * k
        hills(c, w, h, far, 0.1f * k, 2, 21, 0xFF2C5E4E.toInt())
        hills(c, w, h, far + 0.02f * k, 0.07f * k, 2, 22, 0x66DCEBF2)
        for (i in 0 until 6) pine(c, w * (0.04f + 0.17f * i + 0.05f * noise(i, 23)), far + 0.03f * k, (0.12f + 0.05f * noise(i, 24)) * k, false, dp)
        val near = h - 0.16f * k
        hills(c, w, h, near, 0.06f * k, 3, 25, 0xFFEEF5FF.toInt())
        pine(c, w * 0.08f, near + 0.03f * k, 0.32f * k, true, dp)
        pine(c, w * 0.93f, near + 0.02f * k, 0.36f * k, true, dp)
        pine(c, w * 0.6f, near + 0.03f * k, 0.22f * k, true, dp)

        // The string of lights' wire along the top; the bulbs twinkle in the moving layer.
        val swags = max(2, (w / (46f * dp)).toInt())
        val sw = w / swags
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.1f * dp
        p.color = 0xCC03100A.toInt()
        path.reset()
        path.moveTo(0f, 1.5f * dp)
        for (i in 0 until swags) path.quadTo(sw * (i + 0.5f), 9f * dp, sw * (i + 1), 1.5f * dp)
        c.drawPath(path, p)
        p.style = Paint.Style.FILL
    }

    /** A pine: three tiers; snowy ones get white edges and a few coloured lights. */
    private fun pine(c: Canvas, x: Float, base: Float, s: Float, snowy: Boolean, dp: Float) {
        p.color = 0xFF4A2E1E.toInt()
        c.drawRect(x - s * 0.04f, base - s * 0.12f, x + s * 0.04f, base, p)
        for (tier in 0 until 3) {
            val bottom = base - s * (0.1f + tier * 0.26f)
            val half = s * (0.34f - tier * 0.08f)
            val top = bottom - s * 0.42f
            path.reset()
            path.moveTo(x - half, bottom)
            path.lineTo(x, top)
            path.lineTo(x + half, bottom)
            path.close()
            p.color = if (snowy) 0xFF0F4430.toInt() else 0xFF0B2E22.toInt()
            c.drawPath(path, p)
            if (snowy) {
                p.style = Paint.Style.STROKE
                p.strokeWidth = max(1f * dp, s * 0.025f)
                p.strokeCap = Paint.Cap.ROUND
                p.color = 0xFFF4F9FF.toInt()
                c.drawLine(x - half, bottom, x + half, bottom, p)
                c.drawLine(x - half * 0.25f, top + (bottom - top) * 0.25f, x, top, p)
                p.style = Paint.Style.FILL
            }
        }
        if (snowy) {
            for (i in 0 until 7) {
                val fy = 0.15f + 0.7f * noise(i, (x * 7).toInt())
                val fx = (noise(i, (x * 11).toInt()) - 0.5f) * 0.5f * fy
                p.color = Scenery.FIREWORKS[i % 4]
                p.setShadowLayer(2.5f * dp, 0f, 0f, p.color)
                c.drawCircle(x + fx * s, base - s * (0.1f + (1 - fy) * 0.75f), 1.2f * dp, p)
                p.clearShadowLayer()
            }
            Scenery.sparkle(c, x, base - s * 1.04f, s * 0.12f, 0xFFFFE08A.toInt(), 255, 3f * dp)
        }
    }

    // ------------------------------------------------------------------ Neon

    private fun neon(c: Canvas, t: KbTheme, w: Float, h: Float, s: Float, k: Float, dp: Float) {
        val hy = horizon(s, k)
        p.shader = LinearGradient(
            0f, 0f, 0f, hy,
            intArrayOf(0xFF12002B.toInt(), 0xFF2A0550.toInt(), 0xFF8E1A7E.toInt(), 0xFFFF6A5A.toInt()),
            floatArrayOf(0f, 0.45f, 0.82f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, w, hy, p)
        p.shader = null
        stars(c, 40, 300, w, 0f, hy * 0.6f, 0xFFFFFFFF.toInt(), dp, 40, 160, 0.8f)

        val sr = min(0.3f * k, 0.3f * w)
        val sx = w * 0.5f
        glow(c, sx, hy, sr * 1.9f, 0xFFFF4FA0.toInt(), 0x66)
        path.reset()
        path.addCircle(sx, hy, sr, Path.Direction.CW)
        var yy = hy - sr * 0.6f
        var gap = sr * 0.035f
        while (yy < hy) {
            path2.reset()
            path2.addRect(sx - sr, yy, sx + sr, yy + gap, Path.Direction.CW)
            path.op(path2, Path.Op.DIFFERENCE)
            yy += gap + sr * 0.1f
            gap *= 1.4f
        }
        c.save()
        c.clipRect(0f, 0f, w, hy)
        p.shader = LinearGradient(0f, hy - sr, 0f, hy, 0xFFFFE45C.toInt(), 0xFFFF3D9A.toInt(), Shader.TileMode.CLAMP)
        c.drawPath(path, p)
        p.shader = null
        c.restore()

        ridge(c, w, hy, 0.16f * k, 31, 0xFF2A0845.toInt())
        ridge(c, w, hy, 0.09f * k, 37, 0xFF14022A.toInt(), 0xAAFF2BD6.toInt(), dp)

        p.color = 0xFF07000F.toInt()
        c.drawRect(0f, hy, w, h, p)
        // The floor's grid: lines running to the vanishing point (the cross lines move, in Ambient).
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f * dp
        p.color = 0x70FF2BD6
        for (i in -12..12) c.drawLine(sx + i * w * 0.025f, hy, sx + i * w * 0.3f, h, p)
        p.style = Paint.Style.FILL
        p.color = t.keyStroke
        p.setShadowLayer(6f * dp, 0f, 0f, t.keyStroke)
        c.drawRect(0f, hy - 0.6f * dp, w, hy + 0.6f * dp, p)
        p.clearShadowLayer()
    }

    // ------------------------------------------------------------------ Sakura

    private fun sakura(c: Canvas, w: Float, h: Float, s: Float, k: Float, dp: Float) {
        val hx = w * 0.22f
        val hyy = s + 0.3f * k
        glow(c, hx, hyy, 0.34f * k, 0xFFFFFFFF.toInt(), 0xA0)
        p.color = 0xC8FFF4F8.toInt()
        c.drawCircle(hx, hyy, 0.1f * k, p)

        // Mount Fuji, pale, with its snow cap.
        val base = h - 0.18f * k
        val peakX = w * 0.62f
        val peakY = s + 0.28f * k
        path.reset()
        path.moveTo(w * 0.24f, base)
        path.cubicTo(w * 0.42f, base - 0.06f * k, peakX - 0.09f * w, peakY + 0.05f * k, peakX - 0.045f * w, peakY)
        path.lineTo(peakX + 0.045f * w, peakY)
        path.cubicTo(peakX + 0.09f * w, peakY + 0.05f * k, w * 0.82f, base - 0.06f * k, w * 1.0f, base)
        path.close()
        p.color = 0xFFE7C2D6.toInt()
        c.drawPath(path, p)
        c.save()
        c.clipPath(path)
        path2.reset()
        path2.moveTo(peakX - 0.25f * w, peakY - 2f)
        path2.lineTo(peakX + 0.25f * w, peakY - 2f)
        val snowY = peakY + 0.12f * k
        val teeth = 7
        for (i in teeth downTo 0) {
            val x = peakX - 0.25f * w + 0.5f * w * i / teeth
            path2.lineTo(x, snowY + (if (i % 2 == 0) 0.03f * k else -0.02f * k) * (0.6f + 0.4f * noise(i, 41)))
        }
        path2.close()
        p.color = 0xFFFFFFFF.toInt()
        c.drawPath(path2, p)
        c.restore()

        hills(c, w, h, h - 0.16f * k, 0.05f * k, 3, 43, 0xFFF6C9DA.toInt())
        torii(c, w * 0.12f, h - 0.15f * k, 0.15f * k)
        hills(c, w, h, h - 0.07f * k, 0.04f * k, 4, 45, 0xFFF2B5CB.toInt())
        p.shader = LinearGradient(0f, h - 0.32f * k, 0f, h, 0x00FFFFFF, 0x70FFFFFF, Shader.TileMode.CLAMP)
        c.drawRect(0f, h - 0.32f * k, w, h, p)
        p.shader = null

        // Blossoming branches: one reaching in from the top-right corner, one low on the left.
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
        p.strokeWidth = 1.5f * dp
        path.reset()
        path.moveTo(-2f * dp, 3f * dp)
        path.quadTo(26f * dp, 11f * dp, 58f * dp, 5f * dp)
        c.drawPath(path, p)
        p.style = Paint.Style.FILL
        Scenery.blossom(c, w - 20f * dp, 5.5f * dp, 4.8f * dp)
        Scenery.blossom(c, w - 50f * dp, 8f * dp, 4.1f * dp)
        Scenery.blossom(c, w - 76f * dp, 4.5f * dp, 3.5f * dp)
        Scenery.blossom(c, 14f * dp, 7.5f * dp, 4.4f * dp)
        Scenery.blossom(c, 36f * dp, 9f * dp, 3.8f * dp)
        Scenery.blossom(c, 56f * dp, 5f * dp, 3.2f * dp)
    }

    private fun torii(c: Canvas, x: Float, base: Float, s: Float) {
        p.color = 0xFFD2475E.toInt()
        c.drawRect(x - s * 0.36f, base - s, x - s * 0.28f, base, p)
        c.drawRect(x + s * 0.28f, base - s, x + s * 0.36f, base, p)
        c.drawRect(x - s * 0.44f, base - s * 0.78f, x + s * 0.44f, base - s * 0.7f, p)
        path.reset()
        path.moveTo(x - s * 0.56f, base - s * 0.98f)
        path.quadTo(x, base - s * 0.92f, x + s * 0.56f, base - s * 0.98f)
        path.lineTo(x + s * 0.52f, base - s * 1.1f)
        path.quadTo(x, base - s * 1.04f, x - s * 0.52f, base - s * 1.1f)
        path.close()
        c.drawPath(path, p)
    }

    // ------------------------------------------------------------------ Ocean

    private fun ocean(c: Canvas, w: Float, h: Float, s: Float, k: Float, dp: Float) {
        p.color = 0x12FFFFFF
        for (i in 0 until 4) {
            val x0 = w * (0.06f + 0.26f * i)
            path.reset()
            path.moveTo(x0, 0f)
            path.lineTo(x0 + w * 0.07f, 0f)
            path.lineTo(x0 + w * 0.22f, h)
            path.lineTo(x0 + w * 0.09f, h)
            path.close()
            c.drawPath(path, p)
        }
        // Ripples of the surface along the top edge.
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f * dp
        for (row in 0..1) {
            p.color = if (row == 0) 0x55BFEFFF else 0x30BFEFFF
            val y0 = (2.5f + 3.5f * row) * dp
            path.reset()
            var x = 0f
            path.moveTo(0f, y0)
            while (x <= w) {
                path.lineTo(x, y0 + kotlin.math.sin(x / (9f * dp) + row * 1.7f) * 1.1f * dp)
                x += 2f * dp
            }
            c.drawPath(path, p)
        }
        p.style = Paint.Style.FILL

        val bed = seabed(h, k)
        hills(c, w, h, bed - 0.04f * k, 0.05f * k, 3, 51, 0xFF0B3A57.toInt())
        for (i in 0 until 4) {
            p.color = 0xFF062035.toInt()
            val rx = w * (0.1f + 0.27f * i + 0.06f * noise(i, 52))
            val rr = (0.04f + 0.03f * noise(i, 53)) * k
            rect.set(rx - rr * 1.5f, bed - rr, rx + rr * 1.5f, bed + rr)
            c.drawOval(rect, p)
        }
        coral(c, w * 0.86f, bed + 0.01f * k, 0.26f * k, 0xD0FF6F91.toInt(), dp)
        coral(c, w * 0.16f, bed + 0.02f * k, 0.2f * k, 0xD0FFA25C.toInt(), dp)
        hills(c, w, h, bed + 0.05f * k, 0.035f * k, 4, 55, 0xFF072842.toInt())
        for (i in 0 until 3) {
            Scenery.sparkle(c, w * (0.35f + 0.18f * i), h - 0.03f * k, 0.035f * k, 0xFFFF8A65.toInt(), 200)
        }
    }

    /** Branching coral: a few stems forking twice. */
    private fun coral(c: Canvas, x: Float, base: Float, s: Float, color: Int, dp: Float) {
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.color = color
        for (stem in 0 until 3) {
            val a = (-0.5f + stem * 0.5f)
            val x1 = x + a * s * 0.35f
            val y1 = base - s * (0.45f + 0.1f * noise(stem, 57))
            p.strokeWidth = max(2f * dp, s * 0.07f)
            c.drawLine(x, base, x1, y1, p)
            p.strokeWidth = max(1.4f * dp, s * 0.045f)
            for (fork in intArrayOf(-1, 1)) {
                val x2 = x1 + fork * s * 0.16f
                val y2 = y1 - s * (0.25f + 0.1f * noise(stem * 2 + fork, 58))
                c.drawLine(x1, y1, x2, y2, p)
                p.strokeWidth = max(1f * dp, s * 0.03f)
                c.drawLine(x2, y2, x2 + fork * s * 0.07f, y2 - s * 0.15f, p)
                c.drawLine(x2, y2, x2 - fork * s * 0.05f, y2 - s * 0.13f, p)
                p.strokeWidth = max(1.4f * dp, s * 0.045f)
            }
        }
        p.style = Paint.Style.FILL
    }

    // ------------------------------------------------------------------ Retro terminal

    private val RETRO_LINES = arrayOf(
        "quietboard 1.8 (offline build)",
        "> network ............. none",
        "> learned words ....... this phone only",
        "> cloud backup ........ off",
        "> glide decoder ....... ready",
        "> theme ............... retro_terminal",
        "$ ./type --quietly",
        "READY.",
    )

    fun retroTextTop(s: Float, dp: Float) = s + 14f * dp
    fun retroLineHeight(dp: Float) = 13f * dp
    val retroLines get() = RETRO_LINES

    private fun retro(c: Canvas, w: Float, h: Float, s: Float, k: Float, dp: Float) {
        // Faint terminal output behind the keys.
        p.typeface = Typeface.MONOSPACE
        p.textSize = 9.5f * dp
        p.color = 0x3039FF6A
        var y = retroTextTop(s, dp)
        for (line in RETRO_LINES) {
            if (y > h - 6f * dp) break
            c.drawText(line, 8f * dp, y, p)
            y += retroLineHeight(dp)
        }
        p.typeface = null
        p.shader = RadialGradient(
            w / 2, s + k / 2, max(w, h) * 0.75f,
            intArrayOf(0x00000000, 0x00000000, 0x99000000.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, w, h, p)
        p.shader = null
        p.color = 0x4039FF6A
        c.drawRect(0f, s - 0.5f * dp, w, s + 0.5f * dp, p)
    }

    // ------------------------------------------------------------------ Galaxy

    private fun galaxy(c: Canvas, w: Float, h: Float, s: Float, k: Float, dp: Float) {
        glow(c, w * 0.22f, s + 0.4f * k, 0.62f * k, 0xFF7B2FF7.toInt(), 0x78)
        glow(c, w * 0.72f, s + 0.72f * k, 0.72f * k, 0xFF00A8FF.toInt(), 0x50)
        glow(c, w * 0.5f, s + 0.1f * k, 0.42f * k, 0xFFFF4FA3.toInt(), 0x44)
        // The Milky Way: a soft diagonal band dusted with stars.
        c.save()
        c.rotate(-18f, w / 2, s + k / 2)
        val top = s + k * 0.3f
        val bottom = s + k * 0.7f
        p.shader = LinearGradient(0f, top, 0f, bottom, intArrayOf(0x00FFFFFF, 0x2CFFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        c.drawRect(-w * 0.3f, top, w * 1.3f, bottom, p)
        p.shader = null
        for (i in 0 until 160) {
            p.color = 0xFFFFFFFF.toInt()
            p.alpha = 30 + (110 * noise(i, 401)).toInt()
            val y = top + (bottom - top) * (0.5f + (noise(i, 402) - 0.5f) * noise(i, 403))
            c.drawCircle(-w * 0.3f + 1.6f * w * noise(i, 404), y, (0.3f + 0.6f * noise(i, 405)) * dp, p)
        }
        c.restore()
        stars(c, 150, 500, w, 0f, h, 0xFFFFFFFF.toInt(), dp, 50, 210, 1.1f)
        for (i in 0 until 7) {
            val x = w * noise(i, 511)
            val y = h * noise(i, 512)
            glow(c, x, y, 7f * dp, 0xFFBFD4FF.toInt(), 0x90)
            Scenery.sparkle(c, x, y, (6f + 6f * noise(i, 513)) * dp, 0xFFFFFFFF.toInt(), 235)
        }
        planet(c, w * 0.82f, s + 0.34f * k, min(0.14f * k, 0.13f * w), dp)
        p.color = 0xFF9A9AB0.toInt()
        c.drawCircle(w * 0.18f, s + 0.78f * k, 0.03f * k, p)
        p.color = 0x55000000
        c.drawCircle(w * 0.18f + 0.01f * k, s + 0.78f * k + 0.008f * k, 0.026f * k, p)
    }

    private fun planet(c: Canvas, x: Float, y: Float, r: Float, dp: Float) {
        glow(c, x, y, r * 2.2f, 0xFFE07AFF.toInt(), 0x40)
        c.save()
        c.rotate(-16f, x, y)
        p.style = Paint.Style.STROKE
        p.strokeWidth = r * 0.16f
        p.color = 0xB0E8D8FF.toInt()
        rect.set(x - r * 2f, y - r * 0.5f, x + r * 2f, y + r * 0.5f)
        c.save()
        c.clipRect(x - r * 2.2f, y - r, x + r * 2.2f, y)
        c.drawOval(rect, p)
        c.restore()
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(
            x - r * 0.35f, y - r * 0.35f, r * 1.5f,
            intArrayOf(0xFFFFD1A1.toInt(), 0xFFE07A8F.toInt(), 0xFF4A2266.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawCircle(x, y, r, p)
        p.shader = null
        path.reset()
        path.addCircle(x, y, r, Path.Direction.CW)
        c.save()
        c.clipPath(path)
        p.color = 0x24FFFFFF
        c.drawRect(x - r, y - r * 0.35f, x + r, y - r * 0.2f, p)
        c.drawRect(x - r, y + r * 0.1f, x + r, y + r * 0.3f, p)
        c.restore()
        p.style = Paint.Style.STROKE
        p.strokeWidth = r * 0.16f
        p.color = 0xD0F2E6FF.toInt()
        c.save()
        c.clipRect(x - r * 2.2f, y, x + r * 2.2f, y + r)
        c.drawOval(rect, p)
        c.restore()
        p.style = Paint.Style.FILL
        c.restore()
    }

    // ------------------------------------------------------------------ Autumn

    private fun autumn(c: Canvas, w: Float, h: Float, s: Float, k: Float, dp: Float) {
        val sx = w * 0.72f
        val sy = s + 0.4f * k
        glow(c, sx, sy, 0.42f * k, 0xFFFFB45A.toInt(), 0x90)
        p.color = 0xFFFFD08A.toInt()
        c.drawCircle(sx, sy, 0.12f * k, p)
        hills(c, w, h, h - 0.36f * k, 0.08f * k, 2, 61, 0xFFF4CC98.toInt())
        hills(c, w, h, h - 0.25f * k, 0.07f * k, 3, 63, 0xFFEAB074.toInt())
        for (i in 0 until 8) {
            val tx = w * (0.03f + 0.13f * i + 0.04f * noise(i, 65))
            val ts = (0.09f + 0.06f * noise(i, 66)) * k
            roundTree(c, tx, h - 0.25f * k + 0.03f * k * noise(i, 67), ts, Scenery.LEAVES[(noise(i, 68) * 4).toInt().coerceIn(0, 3)])
        }
        hills(c, w, h, h - 0.12f * k, 0.05f * k, 4, 69, 0xFFDB914E.toInt())
        // A branch in the top-left corner with a few leaves hanging from it.
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.color = 0xFF7A4A2A.toInt()
        p.strokeWidth = 1.8f * dp
        path.reset()
        path.moveTo(-2f * dp, 2f * dp)
        path.quadTo(34f * dp, 10f * dp, 74f * dp, 4f * dp)
        c.drawPath(path, p)
        p.style = Paint.Style.FILL
        for (i in 0 until 4) {
            val lx = (14f + 17f * i) * dp
            Scenery.leaf(c, lx, (10.5f - 1.2f * i) * dp, (8f + 2f * noise(i, 70)) * dp, 180f + (noise(i, 71) - 0.5f) * 50f, Scenery.LEAVES[i % 4], 255)
        }
    }

    private fun roundTree(c: Canvas, x: Float, base: Float, s: Float, color: Int) {
        p.color = 0xFF7A4A2A.toInt()
        c.drawRect(x - s * 0.06f, base - s * 0.55f, x + s * 0.06f, base + s * 0.1f, p)
        p.color = color
        c.drawCircle(x, base - s * 0.85f, s * 0.42f, p)
        c.drawCircle(x - s * 0.28f, base - s * 0.62f, s * 0.3f, p)
        c.drawCircle(x + s * 0.28f, base - s * 0.62f, s * 0.3f, p)
        p.color = 0x22000000
        c.drawCircle(x + s * 0.12f, base - s * 0.6f, s * 0.26f, p)
    }

    // ------------------------------------------------------------------ Valentine

    private fun valentine(c: Canvas, w: Float, h: Float, s: Float, k: Float, dp: Float) {
        glow(c, w * 0.5f, s + 0.5f * k, 0.8f * k, 0xFFFF2E6E.toInt(), 0x46)
        Scenery.heart(c, w * 0.5f, s + 0.52f * k, 0.95f * k, 0f, 0xFFFF5C8A.toInt(), 0x26)
        for (i in 0 until 28) {
            val r = (4f + 12f * noise(i, 81)) * dp
            val y = if (r > 8f * dp) s + noise(i, 82) * k else noise(i, 82) * h
            glow(c, w * noise(i, 83), y, r, Scenery.HEARTS[i % 4], 40 + (70 * noise(i, 84)).toInt())
        }
        for (i in 0 until 16) {
            Scenery.sparkle(c, w * noise(i, 85), h * noise(i, 86), (3f + 4f * noise(i, 87)) * dp, 0xFFFFE6EE.toInt(), 80 + (120 * noise(i, 88)).toInt())
        }
    }

    // ------------------------------------------------------------------ New Year

    private fun newYear(c: Canvas, w: Float, h: Float, s: Float, k: Float, dp: Float) {
        stars(c, 90, 700, w, 0f, h - 0.3f * k, 0xFFFFFFFF.toInt(), dp, 40, 170, 0.9f)
        p.shader = LinearGradient(0f, h - 0.42f * k, 0f, h, 0x00F5C542, 0x48F5C542, Shader.TileMode.CLAMP)
        c.drawRect(0f, h - 0.42f * k, w, h, p)
        p.shader = null
        // The skyline, windows lit here and there.
        var x = 0f
        var i = 0
        while (x < w) {
            val bw = (0.05f + 0.06f * noise(i, 71)) * w
            val bh = (0.07f + 0.15f * noise(i, 72)) * k
            p.color = 0xFF060A1E.toInt()
            c.drawRect(x, h - bh, x + bw, h, p)
            if (bh > 0.18f * k) c.drawRect(x + bw * 0.45f, h - bh - 0.05f * k, x + bw * 0.55f, h - bh, p)
            p.color = 0x99FFD86B.toInt()
            var wy = h - bh + 3f * dp
            var row = 0
            while (wy < h - 3f * dp) {
                var wx = x + 2f * dp
                var col = 0
                while (wx < x + bw - 3f * dp) {
                    if (noise(i * 977 + row * 31 + col, 73) > 0.78f) c.drawRect(wx, wy, wx + 1.6f * dp, wy + 2.2f * dp, p)
                    wx += 5f * dp
                    col++
                }
                wy += 5f * dp
                row++
            }
            x += bw + 0.6f * dp
            i++
        }
    }
}
