package dev.larryjones.quietboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.view.View

/**
 * A miniature of the real keyboard in a theme, for the theme picker: the strip with suggestions, the
 * letters, and a glide trail through "hello", drawn by the keyboard's own views (scene, glass keys,
 * snow caps and all) at a small scale. Still: no motion in the picker.
 */
@SuppressLint("ViewConstructor")
class KeyboardPreview(context: Context, private val t: KbTheme) : View(context) {
    private val dp = resources.displayMetrics.density
    private val overlay = PopupOverlay(context)
    private val keys = KeyboardView(context, overlay)
    private val bar = TopBar(context)
    private val baseW = (360 * dp).toInt()
    private val baseH: Int
    private val backdrop: ThemeBackdrop
    private val clip = Path()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        keys.theme = t
        bar.theme = t
        overlay.theme = t
        keys.effects = false
        overlay.effects = false
        keys.setLayout(Layouts.build(LayoutId.ALPHA, LayoutOptions(false, false, FieldKind.TEXT)))
        val exact = MeasureSpec.makeMeasureSpec(baseW, MeasureSpec.EXACTLY)
        val any = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        bar.measure(exact, any)
        bar.layout(0, 0, baseW, bar.measuredHeight)
        keys.measure(exact, any)
        keys.layout(0, 0, baseW, keys.measuredHeight)
        baseH = bar.measuredHeight + keys.measuredHeight
        overlay.measure(exact, MeasureSpec.makeMeasureSpec(baseH, MeasureSpec.EXACTLY))
        overlay.layout(0, 0, baseW, baseH)
        bar.setSuggestions(listOf(SuggestionItem("hey"), SuggestionItem("hello", bold = true), SuggestionItem("help")))
        glideThrough("hello", bar.measuredHeight.toFloat())
        backdrop = ThemeBackdrop(t, bar.measuredHeight.toFloat(), dp, effects = false, cache = false)
        backdrop.setBounds(0, 0, baseW, baseH)
    }

    /** A gently curved trail through the word's letters, like a glide in progress. */
    private fun glideThrough(word: String, top: Float) {
        val (cx, cy) = keys.letterCenters()
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        for (i in 0 until word.length - 1) {
            val a = word[i] - 'a'
            val b = word[i + 1] - 'a'
            for (step in 0 until 16) {
                val f = step / 16f
                xs += cx[a] + (cx[b] - cx[a]) * f
                ys += cy[a] + (cy[b] - cy[a]) * f + kotlin.math.sin(f * Math.PI).toFloat() * 10f * dp
            }
        }
        xs += cx[word.last() - 'a']
        ys += cy[word.last() - 'a']
        overlay.setTrail(xs.toFloatArray(), ys.toFloatArray(), xs.size, 0f, top)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w.toLong() * baseH / baseW).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        clip.reset()
        clip.addRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 12 * dp, 12 * dp, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        canvas.scale(width.toFloat() / baseW, height.toFloat() / baseH)
        backdrop.draw(canvas)
        bar.draw(canvas)
        canvas.save()
        canvas.translate(0f, bar.measuredHeight.toFloat())
        // A standard theme's keys fill their whole canvas with colour: keep that off the strip.
        canvas.clipRect(0, 0, baseW, keys.measuredHeight)
        keys.draw(canvas)
        canvas.restore()
        overlay.draw(canvas)
        canvas.restore()
    }
}
