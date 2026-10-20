package dev.larryjones.quietboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock

/**
 * The keyboard window's background: a standard theme's flat colour, or a fun theme's whole picture,
 * its still part ([Scenes], painted once into a bitmap when [cache] is on) under its moving part
 * ([Ambient]).
 *
 * Motion runs at 30 frames a second only while you're using the keyboard: [wake] (a key, a glide,
 * the keyboard opening) keeps it going for [AWAKE_MS], then the scene holds still until the next
 * wake. Only this background redraws, never the keys. No motion at all without [effects].
 */
class ThemeBackdrop(
    private val t: KbTheme,
    private val stripH: Float,
    private val dp: Float,
    private val effects: Boolean,
    private val cache: Boolean = true,
) : Drawable() {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var still: Bitmap? = null
    private var awakeUntil = 0L
    private var lastFrame = 0L
    /** Seconds of motion so far. It only advances while awake, so a pause freezes the frame. */
    private var clock = 0f
    private val frame = Runnable { invalidateSelf() }
    private val moves = effects && t.decor != Decor.NONE

    /** Keep the scene moving a while longer. */
    fun wake() {
        if (!moves) return
        val now = SystemClock.uptimeMillis()
        val wasIdle = now >= awakeUntil
        awakeUntil = now + AWAKE_MS
        if (wasIdle) invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (!t.hasBackdrop) {
            paint.color = t.background
            canvas.drawRect(b, paint)
            return
        }
        val w = b.width()
        val h = b.height()
        if (w <= 0 || h <= 0) return
        if (cache) {
            var bmp = still
            if (bmp == null || bmp.width != w || bmp.height != h) {
                bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                paintStill(Canvas(bmp), w, h)
                still = bmp
            }
            canvas.drawBitmap(bmp, b.left.toFloat(), b.top.toFloat(), paint)
        } else {
            canvas.save()
            canvas.translate(b.left.toFloat(), b.top.toFloat())
            paintStill(canvas, w, h)
            canvas.restore()
        }
        val now = SystemClock.uptimeMillis()
        val running = moves && isVisible && now < awakeUntil
        if (running) {
            if (lastFrame != 0L) clock += minOf(now - lastFrame, 100L) / 1000f
            lastFrame = now
        } else {
            lastFrame = 0L
        }
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        Ambient.draw(canvas, t, w.toFloat(), h.toFloat(), stripH, dp, clock)
        canvas.restore()
        if (running) {
            unscheduleSelf(frame)
            scheduleSelf(frame, now + FRAME_MS)
        }
    }

    private fun paintStill(c: Canvas, w: Int, h: Int) {
        val g = Paint()
        g.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), t.background, t.backgroundEnd, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), g)
        Scenes.paint(c, t, w.toFloat(), h.toFloat(), stripH, dp)
    }

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (!visible) unscheduleSelf(frame) else if (changed) wake()
        return changed
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE

    private companion object {
        const val FRAME_MS = 33L
        const val AWAKE_MS = 10_000L
    }
}
