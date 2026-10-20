package dev.larryjones.quietboard

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Glide typing, tuned on simulated sloppy thumb swipes (corner cutting, off-center keys).
 *
 * Stage 1 scores every word whose first/last keys are near the swipe's ends:
 *  - each middle letter is aligned to the path with an optimal in-order alignment (a greedy match
 *    lost "when" because the closest point to "h" lies on the later e→n stretch),
 *  - point-to-point distance to the ideal key path, endpoint distance, path-length ratio,
 *  - a language prior that favours common words over oddities ("Passat", "Beresford's").
 * Stage 2 re-ranks the best 40 with dynamic time warping, which tolerates uneven swipe speed.
 *
 * Not thread-safe (shared scratch arrays): call [decode] from one thread at a time. The keyboard runs
 * it on a background thread; the dictionary is read-only and [UserHistory] reads are concurrent-safe.
 */
class GestureDecoder(private val dict: Dictionary, private val history: UserHistory) {
    var blockOffensive = false

    private val n = 48
    private val ux = FloatArray(n)
    private val uy = FloatArray(n)
    private val tx = FloatArray(n)
    private val ty = FloatArray(n)
    private val wx = FloatArray(Dictionary.MAX_LEN + 1)
    private val wy = FloatArray(Dictionary.MAX_LEN + 1)
    private val align = FloatArray(n)
    private val dist = FloatArray(n)
    private val dtwRow = FloatArray((n + 1) * (n + 1))

    private class Cand(
        val idx: Int, val pre: Float, val pen: Float, val ends: Float,
        val lenPen: Float, val loc: Float, val letters: Int, val prior: Float,
    )

    fun decode(
        xs: FloatArray, ys: FloatArray, count: Int,
        centersX: FloatArray, centersY: FloatArray, kw: Float, kh: Float, limit: Int,
    ): List<String> {
        if (count < 2 || kw <= 0f || kh <= 0f) return emptyList()
        val px = FloatArray(count) { xs[it] / kw }
        val py = FloatArray(count) { ys[it] / kh }
        val userLen = resample(px, py, count, ux, uy)
        if (userLen < 0.6f) return emptyList()
        val cx = FloatArray(26) { centersX[it] / kw }
        val cy = FloatArray(26) { centersY[it] / kh }

        val starts = nearKeys(ux[0], uy[0], cx, cy)
        val ends = nearKeys(ux[n - 1], uy[n - 1], cx, cy)
        val cands = ArrayList<Cand>()

        for (s in starts) for (e in ends) {
            for (idx in dict.gestureBucket('a' + s, 'a' + e)) {
                if (dict.never[idx] || (blockOffensive && dict.offensive[idx])) continue
                val word = dict.words[idx]
                // Rare all-caps acronyms ("DSM", "BRG") are noise as glide results.
                if (word.length > 1 && dict.freqs[idx] < 120 && word.all { it.isUpperCase() }) continue
                val seq = dict.gestureSeq[idx] ?: continue
                val m = seq.length
                if (m > Dictionary.MAX_LEN) continue

                var ideal = 0f
                for (k in 0 until m) {
                    val c = seq[k] - 'a'
                    wx[k] = cx[c]; wy[k] = cy[c]
                    if (k > 0) ideal += hypot(wx[k] - wx[k - 1], wy[k] - wy[k - 1])
                }
                if (userLen < ideal * 0.35f - 0.3f || userLen > ideal * 2.5f + 1.5f) continue

                // Optimal in-order alignment of middle letters; hard-reject letters never approached.
                var ok = true
                align.fill(0f)
                for (k in 1 until m - 1) {
                    var best = Float.MAX_VALUE
                    for (i in 0 until n) {
                        val d = hypot(ux[i] - wx[k], uy[i] - wy[k])
                        dist[i] = d
                        if (d < best) best = d
                    }
                    if (best > HARD) { ok = false; break }
                    var run = Float.MAX_VALUE
                    for (i in 0 until n) {
                        if (align[i] < run) run = align[i]
                        align[i] = maxOf(0f, dist[i] - 0.45f) + run
                    }
                }
                if (!ok) continue
                var pen = 0f
                if (m > 2) { pen = Float.MAX_VALUE; for (i in 0 until n) if (align[i] < pen) pen = align[i] }

                resample(wx, wy, m, tx, ty)
                var loc = 0f
                for (i in 0 until n) loc += hypot(ux[i] - tx[i], uy[i] - ty[i])
                loc /= n
                val endD = hypot(ux[0] - tx[0], uy[0] - ty[0]) + hypot(ux[n - 1] - tx[n - 1], uy[n - 1] - ty[n - 1])
                val lenPen = abs(ln(maxOf(userLen, 0.3f) / maxOf(ideal, 0.3f)))
                val prior = prior(idx, word)
                val pre = -(A * loc + BP * pen / maxOf(1, m - 2) + CE * endD) - LEN * lenPen + prior
                cands += Cand(idx, pre, pen, endD, lenPen, loc, m, prior)
            }
        }
        if (cands.isEmpty()) return emptyList()
        cands.sortByDescending { it.pre }

        val scored = ArrayList<Pair<String, Float>>()
        for (c in cands.subList(0, min(TOP, cands.size))) {
            val seq = dict.gestureSeq[c.idx]!!
            for (k in seq.indices) { wx[k] = cx[seq[k] - 'a']; wy[k] = cy[seq[k] - 'a'] }
            resample(wx, wy, seq.length, tx, ty)
            val d = dtw()
            val sc = -LEN * c.lenPen - (A * (0.5f * d + 0.5f * c.loc) + BP * c.pen / maxOf(1, c.letters - 2) + CE * c.ends) + c.prior
            scored += dict.words[c.idx] to sc
        }
        scored.sortByDescending { it.second }
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for ((w, _) in scored) {
            if (seen.add(w.lowercase(Locale.US))) out += w
            if (out.size >= limit) break
        }
        return out
    }

    /** Language prior: frequency, your own usage, and a nudge away from names/possessives/acronyms. */
    private fun prior(idx: Int, word: String): Float {
        var p = FQ * dict.freqs[idx] / 255f
        // Names, possessives and acronyms are unlikely glides, but contractions aren't ("it's", "I'd").
        val contraction = word.startsWith("I'") || word in CONTRACTIONS
        val odd = !contraction && ((word[0].isUpperCase() && word != "I") || word.endsWith("'s") ||
            (word.length > 1 && word.all { it.isUpperCase() }))
        if (odd) p -= CAP
        if (dict.slang[idx]) p -= SLANG
        val c = history.count(dict.keys[idx])
        if (c > 0) p += 0.3f + 0.05f * min(c, 10)
        return p
    }

    private fun nearKeys(x: Float, y: Float, cx: FloatArray, cy: FloatArray): List<Int> {
        val d = (0 until 26).filter { !cx[it].isNaN() }.map { it to hypot(x - cx[it], y - cy[it]) }.sortedBy { it.second }
        val out = d.filter { it.second < RADIUS }.take(MAX_ENDS).map { it.first }
        return out.ifEmpty { d.take(1).map { it.first } }
    }

    /** Banded dynamic time warping between the user path (ux,uy) and ideal path (tx,ty). */
    private fun dtw(): Float {
        val w = n + 1
        val inf = Float.MAX_VALUE / 4
        java.util.Arrays.fill(dtwRow, inf)
        dtwRow[0] = 0f
        for (i in 1..n) {
            val lo = maxOf(1, i - BAND)
            val hi = min(n, i + BAND)
            for (j in lo..hi) {
                val dx = ux[i - 1] - tx[j - 1]
                val dy = uy[i - 1] - ty[j - 1]
                val cost = sqrt(dx * dx + dy * dy)
                var best = dtwRow[(i - 1) * w + j]
                val left = dtwRow[i * w + j - 1]
                if (left < best) best = left
                val diag = dtwRow[(i - 1) * w + j - 1]
                if (diag < best) best = diag
                dtwRow[i * w + j] = cost + best
            }
        }
        return dtwRow[n * w + n] / (2 * n)
    }

    /** Resamples a polyline to [n] evenly spaced points; returns its length. */
    private fun resample(xs: FloatArray, ys: FloatArray, count: Int, outX: FloatArray, outY: FloatArray): Float {
        var total = 0f
        for (i in 1 until count) total += hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        if (total <= 0f || count < 2) {
            for (i in 0 until n) { outX[i] = xs[0]; outY[i] = ys[0] }
            return 0f
        }
        val step = total / (n - 1)
        outX[0] = xs[0]; outY[0] = ys[0]
        var o = 1
        var acc = 0f
        var i = 1
        var lx = xs[0]
        var ly = ys[0]
        while (i < count && o < n) {
            val seg = hypot(xs[i] - lx, ys[i] - ly)
            if (acc + seg >= step && seg > 0f) {
                val t = (step - acc) / seg
                val nx = lx + t * (xs[i] - lx)
                val ny = ly + t * (ys[i] - ly)
                outX[o] = nx; outY[o] = ny; o++
                lx = nx; ly = ny
                acc = 0f
            } else {
                acc += seg
                lx = xs[i]; ly = ys[i]
                i++
            }
        }
        while (o < n) { outX[o] = xs[count - 1]; outY[o] = ys[count - 1]; o++ }
        return total
    }

    companion object {
        // Tuned on a sloppy-swipe simulator: 98% top-1 at typical sloppiness, 92% when very sloppy.
        private const val RADIUS = 1.45f
        private const val MAX_ENDS = 5
        private const val HARD = 1.5f
        private const val A = 4.0f
        private const val BP = 4.0f
        private const val CE = 0.8f
        private const val FQ = 5.0f
        private const val CAP = 0.6f
        private const val SLANG = 0.4f
        private const val LEN = 3.0f
        private const val TOP = 40
        private const val BAND = 10
        private val CONTRACTIONS = setOf(
            "it's", "let's", "he's", "she's", "that's", "what's", "there's", "here's", "who's", "where's", "how's",
        )
    }
}

/** sqrt(dx² + dy²). kotlin.math.hypot guards against overflow we can't hit and is ~2x slower here. */
private fun hypot(dx: Float, dy: Float) = sqrt(dx * dx + dy * dy)
