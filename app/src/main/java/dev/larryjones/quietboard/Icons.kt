package dev.larryjones.quietboard

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.sin

enum class Icon {
    SHIFT, SHIFT_ON, SHIFT_LOCKED, BACKSPACE, ENTER, SEARCH, SEND, GO, DONE, PREVIOUS,
    EMOJI, CLIPBOARD, SETTINGS, CHEVRON_DOWN, CLOSE, PASTE,
    EDIT, ONE_HAND, PIN, PIN_ON, SWAP, EXPAND, UP, DOWN, LEFT, RIGHT
}

/** Icons drawn on a 24x24 grid (Material-style strokes) so no image assets are needed. */
object Icons {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val rect = RectF()

    fun draw(canvas: Canvas, icon: Icon, cx: Float, cy: Float, size: Float, color: Int) {
        val s = size / 24f
        canvas.save()
        canvas.translate(cx - size / 2f, cy - size / 2f)
        canvas.scale(s, s)
        paint.color = color
        paint.strokeWidth = 2f
        paint.style = Paint.Style.STROKE
        path.reset()
        when (icon) {
            Icon.SHIFT, Icon.SHIFT_ON, Icon.SHIFT_LOCKED -> {
                path.moveTo(12f, 3f); path.lineTo(20.5f, 11.5f); path.lineTo(15.5f, 11.5f)
                path.lineTo(15.5f, 17.5f); path.lineTo(8.5f, 17.5f); path.lineTo(8.5f, 11.5f)
                path.lineTo(3.5f, 11.5f); path.close()
                if (icon != Icon.SHIFT) paint.style = Paint.Style.FILL_AND_STROKE
                canvas.drawPath(path, paint)
                if (icon == Icon.SHIFT_LOCKED) {
                    paint.style = Paint.Style.STROKE
                    canvas.drawLine(8.5f, 21f, 15.5f, 21f, paint)
                }
            }
            Icon.BACKSPACE -> {
                paint.strokeWidth = 1.8f
                path.moveTo(8f, 5f); path.lineTo(21f, 5f); path.lineTo(21f, 19f)
                path.lineTo(8f, 19f); path.lineTo(2.5f, 12f); path.close()
                canvas.drawPath(path, paint)
                canvas.drawLine(11.5f, 9f, 17.5f, 15f, paint)
                canvas.drawLine(17.5f, 9f, 11.5f, 15f, paint)
            }
            Icon.ENTER -> {
                path.moveTo(19.5f, 5f); path.lineTo(19.5f, 13f); path.lineTo(5f, 13f)
                canvas.drawPath(path, paint)
                path.reset()
                path.moveTo(9f, 9f); path.lineTo(5f, 13f); path.lineTo(9f, 17f)
                canvas.drawPath(path, paint)
            }
            Icon.SEARCH -> {
                canvas.drawCircle(10f, 10f, 6f, paint)
                canvas.drawLine(14.5f, 14.5f, 20f, 20f, paint)
            }
            Icon.SEND -> {
                paint.strokeWidth = 1.8f
                path.moveTo(3.5f, 20f); path.lineTo(21f, 12f); path.lineTo(3.5f, 4f)
                path.lineTo(5.5f, 12f); path.close()
                canvas.drawPath(path, paint)
                canvas.drawLine(5.5f, 12f, 12f, 12f, paint)
            }
            Icon.GO -> {
                canvas.drawLine(4f, 12f, 19f, 12f, paint)
                path.moveTo(13f, 6f); path.lineTo(19f, 12f); path.lineTo(13f, 18f)
                canvas.drawPath(path, paint)
            }
            Icon.PREVIOUS -> {
                canvas.drawLine(5f, 12f, 20f, 12f, paint)
                path.moveTo(11f, 6f); path.lineTo(5f, 12f); path.lineTo(11f, 18f)
                canvas.drawPath(path, paint)
            }
            Icon.DONE -> {
                path.moveTo(4.5f, 12.5f); path.lineTo(9.5f, 17.5f); path.lineTo(19.5f, 6.5f)
                canvas.drawPath(path, paint)
            }
            Icon.EMOJI -> {
                paint.strokeWidth = 1.8f
                canvas.drawCircle(12f, 12f, 9f, paint)
                path.moveTo(8f, 14f); path.quadTo(12f, 18.5f, 16f, 14f)
                canvas.drawPath(path, paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(9f, 10f, 1.3f, paint)
                canvas.drawCircle(15f, 10f, 1.3f, paint)
            }
            Icon.CLIPBOARD, Icon.PASTE -> {
                paint.strokeWidth = 1.8f
                rect.set(5f, 4.5f, 19f, 21f)
                canvas.drawRoundRect(rect, 2f, 2f, paint)
                rect.set(9f, 2.5f, 15f, 6.5f)
                paint.style = Paint.Style.FILL_AND_STROKE
                canvas.drawRoundRect(rect, 1f, 1f, paint)
                if (icon == Icon.PASTE) {
                    paint.style = Paint.Style.STROKE
                    canvas.drawLine(8.5f, 11f, 15.5f, 11f, paint)
                    canvas.drawLine(8.5f, 15f, 13.5f, 15f, paint)
                }
            }
            Icon.SETTINGS -> {
                paint.strokeWidth = 1.8f
                canvas.drawCircle(12f, 12f, 6.5f, paint)
                canvas.drawCircle(12f, 12f, 2.6f, paint)
                paint.strokeWidth = 3.2f
                paint.strokeCap = Paint.Cap.BUTT
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val c = cos(a).toFloat()
                    val sn = sin(a).toFloat()
                    canvas.drawLine(12f + c * 6.5f, 12f + sn * 6.5f, 12f + c * 9.5f, 12f + sn * 9.5f, paint)
                }
                paint.strokeCap = Paint.Cap.ROUND
            }
            Icon.CHEVRON_DOWN -> {
                path.moveTo(6f, 9.5f); path.lineTo(12f, 15.5f); path.lineTo(18f, 9.5f)
                canvas.drawPath(path, paint)
            }
            Icon.EDIT -> {
                // I-beam cursor between selection handles
                canvas.drawLine(12f, 4f, 12f, 20f, paint)
                canvas.drawLine(9.5f, 4f, 14.5f, 4f, paint)
                canvas.drawLine(9.5f, 20f, 14.5f, 20f, paint)
                path.moveTo(6.5f, 9f); path.lineTo(3.5f, 12f); path.lineTo(6.5f, 15f)
                path.moveTo(17.5f, 9f); path.lineTo(20.5f, 12f); path.lineTo(17.5f, 15f)
                canvas.drawPath(path, paint)
            }
            Icon.ONE_HAND -> {
                paint.strokeWidth = 1.8f
                rect.set(2.5f, 6f, 21.5f, 18f)
                canvas.drawRoundRect(rect, 2.5f, 2.5f, paint)
                paint.style = Paint.Style.FILL
                rect.set(5f, 8.5f, 14.5f, 15.5f)
                canvas.drawRoundRect(rect, 1.2f, 1.2f, paint)
                paint.style = Paint.Style.STROKE
                path.moveTo(18.5f, 10f); path.lineTo(16.5f, 12f); path.lineTo(18.5f, 14f)
                canvas.drawPath(path, paint)
            }
            Icon.PIN, Icon.PIN_ON -> {
                paint.strokeWidth = 1.8f
                path.moveTo(8.5f, 3.5f); path.lineTo(15.5f, 3.5f); path.lineTo(14.5f, 9.5f)
                path.lineTo(17.5f, 13f); path.lineTo(6.5f, 13f); path.lineTo(9.5f, 9.5f); path.close()
                if (icon == Icon.PIN_ON) paint.style = Paint.Style.FILL_AND_STROKE
                canvas.drawPath(path, paint)
                paint.style = Paint.Style.STROKE
                canvas.drawLine(12f, 13f, 12f, 20.5f, paint)
            }
            Icon.SWAP -> {
                canvas.drawLine(4f, 8f, 18f, 8f, paint)
                path.moveTo(15f, 5f); path.lineTo(18f, 8f); path.lineTo(15f, 11f)
                canvas.drawLine(20f, 16f, 6f, 16f, paint)
                path.moveTo(9f, 13f); path.lineTo(6f, 16f); path.lineTo(9f, 19f)
                canvas.drawPath(path, paint)
            }
            Icon.EXPAND -> {
                canvas.drawLine(5f, 19f, 19f, 5f, paint)
                path.moveTo(13f, 5f); path.lineTo(19f, 5f); path.lineTo(19f, 11f)
                path.moveTo(5f, 13f); path.lineTo(5f, 19f); path.lineTo(11f, 19f)
                canvas.drawPath(path, paint)
            }
            Icon.UP, Icon.DOWN, Icon.LEFT, Icon.RIGHT -> {
                canvas.save()
                canvas.rotate(when (icon) { Icon.UP -> 180f; Icon.LEFT -> 90f; Icon.RIGHT -> -90f; else -> 0f }, 12f, 12f)
                path.moveTo(6f, 9.5f); path.lineTo(12f, 15.5f); path.lineTo(18f, 9.5f)
                canvas.drawPath(path, paint)
                canvas.restore()
            }
            Icon.CLOSE -> {
                canvas.drawLine(6.5f, 6.5f, 17.5f, 17.5f, paint)
                canvas.drawLine(17.5f, 6.5f, 6.5f, 17.5f, paint)
            }
        }
        canvas.restore()
    }
}
