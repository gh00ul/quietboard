package dev.larryjones.quietboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * A miniature keyboard in a theme's colours and scenery for the theme picker: the strip with a
 * suggestion, then a row of keys ending in enter. Drawn with the keyboard's own code at a small scale.
 */
@SuppressLint("ViewConstructor")
class ThemeSwatch(context: Context, private val t: KbTheme) : View(context) {
    private val dp = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val backdrop = ThemeBackdrop(t, STRIP * dp, dp, effects = false)
    private val clip = Path()
    private val key = RectF()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val vw = VW * dp
        val vh = VH * dp
        clip.reset()
        clip.addRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 10 * dp, 10 * dp, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        canvas.scale(width / vw, height / vh)
        backdrop.setBounds(0, 0, vw.toInt(), vh.toInt())
        backdrop.draw(canvas)
        if (t.glow != 0) text.setShadowLayer(3.5f * dp, 0f, 0f, t.glow) else text.clearShadowLayer()

        val cy = STRIP / 2 * dp
        paint.color = t.chip
        canvas.drawRoundRect(vw / 2 - 46 * dp, cy - 16 * dp, vw / 2 + 46 * dp, cy + 16 * dp, 16 * dp, 16 * dp, paint)
        text.typeface = t.fonts.bold
        text.textSize = 18 * dp
        text.color = t.text
        canvas.drawText("hello", vw / 2, cy - (text.descent() + text.ascent()) / 2, text)

        val side = 6 * dp
        val gap = 5 * dp
        val kw = (vw - 2 * side - 3 * gap) / 4
        val top = (STRIP + 5) * dp
        val radius = 9 * dp
        text.typeface = t.fonts.regular
        text.textSize = 22 * dp
        for (i in 0 until 4) {
            val left = side + i * (kw + gap)
            key.set(left, top, left + kw, vh - 6 * dp)
            val accent = i == 3
            if (t.keyShadow != 0) {
                paint.color = t.keyShadow
                canvas.drawRoundRect(key.left, key.top + 1.2f * dp, key.right, key.bottom + 1.2f * dp, radius, radius, paint)
            }
            paint.color = if (accent) t.accent else t.key
            canvas.drawRoundRect(key, radius, radius, paint)
            if (t.decor != Decor.NONE || t.keyStroke != 0) {
                Scenery.keyExtra(canvas, t, key, radius, if (accent) KeyLook.ACCENT else KeyLook.LETTER, false, i + 1, dp)
            }
            if (accent) {
                Icons.draw(canvas, Icon.ENTER, key.centerX(), key.centerY(), 20 * dp, t.onAccent)
            } else {
                text.color = t.text
                canvas.drawText(LETTERS[i], key.centerX(), key.centerY() - (text.descent() + text.ascent()) / 2, text)
            }
        }
        canvas.restore()
    }

    companion object {
        // Drawn on a virtual 220 x 104 dp canvas, then scaled to the view.
        private const val VW = 220f
        private const val VH = 104f
        private const val STRIP = 46f
        private val LETTERS = arrayOf("h", "e", "y")
        /** Height / width. */
        const val ASPECT = VH / VW
    }
}
