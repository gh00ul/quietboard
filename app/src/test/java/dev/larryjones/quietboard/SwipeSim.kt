package dev.larryjones.quietboard

import java.util.Random

/**
 * Simulated thumb swipes on a 1000×420 px QWERTY (keys 100×140 px), for glide tests and tuning.
 *
 * Two styles, so tuning can't just learn one simulator's quirks:
 *  - [Style.SMOOTH]: a Catmull-Rom curve through jittered key centres, then smoothed (the original
 *    simulator the decoder was first tuned on);
 *  - [Style.CUT]: corner-cutting strokes. Each middle key is passed at a point pulled toward the line
 *    between its neighbours, ends land off-centre, and the stroke wobbles a little.
 */
object SwipeSim {
    enum class Style { SMOOTH, CUT }

    private const val MAX_CUT = 0.4f
    const val KW = 100f
    const val KH = 140f
    val cx = FloatArray(26) { Float.NaN }
    val cy = FloatArray(26) { Float.NaN }

    init {
        "qwertyuiop".forEachIndexed { i, c -> cx[c - 'a'] = 50f + 100f * i; cy[c - 'a'] = 70f }
        "asdfghjkl".forEachIndexed { i, c -> cx[c - 'a'] = 100f + 100f * i; cy[c - 'a'] = 210f }
        "zxcvbnm".forEachIndexed { i, c -> cx[c - 'a'] = 200f + 100f * i; cy[c - 'a'] = 350f }
    }

    /** Letters-only, repeat-free key sequence of a word ("hello" -> "helo"). */
    fun seq(word: String): String {
        val sb = StringBuilder()
        for (c in word.lowercase()) if (c in 'a'..'z' && (sb.isEmpty() || sb.last() != c)) sb.append(c)
        return sb.toString()
    }

    fun swipe(word: String, rnd: Random, slop: Float, style: Style): Pair<FloatArray, FloatArray> =
        if (style == Style.SMOOTH) smooth(word, rnd, slop) else cut(word, rnd, slop)

    private fun smooth(word: String, rnd: Random, slop: Float): Pair<FloatArray, FloatArray> {
        val s = seq(word)
        val px = s.map { cx[it - 'a'] / KW + (rnd.nextGaussian() * slop).toFloat() }
        val py = s.map { cy[it - 'a'] / KH + (rnd.nextGaussian() * slop).toFloat() }
        val dx = ArrayList<Float>(); val dy = ArrayList<Float>()
        for (i in 0 until px.size - 1) {
            val i0 = maxOf(i - 1, 0); val i3 = minOf(i + 2, px.size - 1)
            for (st in 0 until 10) {
                val t = st / 10f; val t2 = t * t; val t3 = t2 * t
                fun cr(p0: Float, p1: Float, p2: Float, p3: Float) =
                    0.5f * (2 * p1 + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3)
                dx += cr(px[i0], px[i], px[i + 1], px[i3]); dy += cr(py[i0], py[i], py[i + 1], py[i3])
            }
        }
        dx += px.last(); dy += py.last()
        val k = 2 + rnd.nextInt(4)
        val sx = FloatArray(dx.size); val sy = FloatArray(dy.size)
        for (i in dx.indices) {
            var ax = 0f; var ay = 0f
            for (j in i - k / 2 until i - k / 2 + k) { val q = j.coerceIn(0, dx.size - 1); ax += dx[q]; ay += dy[q] }
            sx[i] = ax / k; sy[i] = ay / k
        }
        sx[0] = dx[0]; sy[0] = dy[0]; sx[sx.size - 1] = dx.last(); sy[sy.size - 1] = dy.last()
        return FloatArray(sx.size) { sx[it] * KW } to FloatArray(sy.size) { sy[it] * KH }
    }

    private fun cut(word: String, rnd: Random, slop: Float): Pair<FloatArray, FloatArray> {
        val s = seq(word)
        val kx = s.map { cx[it - 'a'] / KW }
        val ky = s.map { cy[it - 'a'] / KH }
        // Via points: ends off-centre; middle keys pulled toward the chord between their neighbours.
        val vx = FloatArray(s.length); val vy = FloatArray(s.length)
        for (i in s.indices) {
            var x = kx[i]; var y = ky[i]
            if (i in 1 until s.length - 1) {
                // Round the corner toward the inside of the turn, by at most ~0.4 key: a thumb still
                // aims at the key (on a back-and-forth turn the chord midpoint can be keys away).
                val u = 0.15f + rnd.nextFloat() * 0.35f
                var px = u * ((kx[i - 1] + kx[i + 1]) / 2 - x)
                var py = u * ((ky[i - 1] + ky[i + 1]) / 2 - y)
                val len = kotlin.math.sqrt(px * px + py * py)
                if (len > MAX_CUT) { px *= MAX_CUT / len; py *= MAX_CUT / len }
                x += px; y += py
            }
            val j = if (i == 0 || i == s.length - 1) slop * 1.2f else slop
            vx[i] = x + (rnd.nextGaussian() * j).toFloat()
            vy[i] = y + (rnd.nextGaussian() * j).toFloat()
        }
        val ox = ArrayList<Float>(); val oy = ArrayList<Float>()
        val phase = rnd.nextFloat() * 6.28f
        val wob = slop * 0.15f
        for (i in 0 until s.length - 1) {
            for (st in 0 until 12) {
                val t = st / 12f
                val x = vx[i] + (vx[i + 1] - vx[i]) * t
                val y = vy[i] + (vy[i + 1] - vy[i]) * t
                val a = phase + (i * 12 + st) * 0.35f
                ox += x + wob * kotlin.math.sin(a); oy += y + wob * kotlin.math.cos(a * 0.7f)
            }
        }
        ox += vx.last(); oy += vy.last()
        return FloatArray(ox.size) { ox[it] * KW } to FloatArray(oy.size) { oy[it] * KH }
    }
}
