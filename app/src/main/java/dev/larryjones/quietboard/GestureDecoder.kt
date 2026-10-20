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
 * Stage 2 re-ranks the best [Weights.top] with dynamic time warping, which tolerates uneven swipe
 * speed, a penalty for sharp turns in the swipe that none of the word's letters explain, and the
 * words before ("I like" vs "I line", "going to" vs "going too"; see [GlideContext]).
 *
 * Words you've typed at least twice but that aren't in the dictionary (names, slang) are glideable too.
 *
 * Not thread-safe (shared scratch arrays): call [decode] from one thread at a time. The keyboard runs
 * it on a background thread; the dictionary is read-only and [UserHistory.forEachKnown] is
 * concurrent-safe.
 */
/**
 * What the glide decoder knows about the words before a swipe: the phrase model's view of them, and
 * what you've typed after the last one (lowercase word -> 0..1). Immutable, so it can cross threads.
 */
class GlideContext(val phrase: PhraseModel.Context?, val learned: Map<String, Float>) {
    companion object {
        val NONE = GlideContext(null, emptyMap())
    }
}

class GestureDecoder(private val dict: Dictionary, private val history: UserHistory) {
    var blockOffensive = false

    /** Scoring weights. The defaults are tuned on GlideBenchmark; tests may swap in others. */
    internal data class Weights(
        val radius: Float = 1.7f,
        val maxEnds: Int = 6,
        val hard: Float = 1.5f,
        val slack: Float = 0.9f,
        val a: Float = 6.0f,
        val bp: Float = 2.0f,
        val ce: Float = 0.8f,
        val fq: Float = 5.0f,
        val cap: Float = 0.6f,
        val slang: Float = 0.4f,
        val len: Float = 1.0f,
        val dtwMix: Float = 0.8f,
        val top: Int = 40,
        /** Bonus for a word you often type after the previous one (learned entries are 0..1). */
        val ctx: Float = 1.0f,
        /**
         * Weight per nat of the phrase model's boost, and the boost's limits. Tuned on words in everyday
         * sentences (top-1 81% without context; 85.6% at 0.1, 87.4% at 0.2, 88.2% at 0.3, 87.5% at 0.45).
         */
        val lm: Float = 0.3f,
        val lmMin: Float = -4f,
        val lmMax: Float = 8f,
        /** Frequency a learned non-dictionary word counts as (0..255 scale). */
        val userFreq: Float = 110f,
        /** Penalty per key of distance between a sharp turn in the swipe and the word's nearest letter. */
        val corner: Float = 1.0f,
        /** How far (in keys) a turn may land from its letter for free: thumbs cut corners. */
        val cornerSlack: Float = 0.7f,
        /** A turn sharper than this (cosine of the angle between the strokes) counts as a corner. */
        val cornerCos: Float = 0.8f,
    )

    internal var weights = Weights()

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
    private val cornerX = FloatArray(n)
    private val cornerY = FloatArray(n)

    /** Gesture letter sequence of learned words, or "" for ones the dictionary covers / can't glide. */
    private val userSeqs = HashMap<String, String>()

    private class Cand(
        val word: String, val idx: Int, val seq: String, val pre: Float, val pen: Float, val ends: Float,
        val lenPen: Float, val loc: Float, val letters: Int, val prior: Float,
    )

    /**
     * Decodes a swipe into up to [limit] words, best first, given what comes before it ([context]).
     */
    fun decode(
        xs: FloatArray, ys: FloatArray, count: Int,
        centersX: FloatArray, centersY: FloatArray, kw: Float, kh: Float, limit: Int,
        context: GlideContext = GlideContext.NONE,
        learned: Boolean = true,
    ): List<String> {
        val w = weights
        if (count < 2 || kw <= 0f || kh <= 0f) return emptyList()
        val px = FloatArray(count) { xs[it] / kw }
        val py = FloatArray(count) { ys[it] / kh }
        val userLen = resample(px, py, count, ux, uy)
        if (userLen < 0.6f) return emptyList()
        val cx = FloatArray(26) { centersX[it] / kw }
        val cy = FloatArray(26) { centersY[it] / kh }

        val corners = if (w.corner > 0f) findCorners(w.cornerCos) else 0

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
                evaluate(word, idx, seq, dictPrior(idx, word, learned), userLen, cx, cy)?.let { cands += it }
            }
        }
        // Your own words that the dictionary doesn't have.
        val startSet = starts.toHashSet()
        val endSet = ends.toHashSet()
        // (Not on the lock screen or in incognito fields: [learned] is false there.)
        if (learned) history.forEachKnown { word, c ->
            val seq = userSeqs.getOrPut(word.lowercase(Locale.US)) { userSeq(word) }
            if (seq.isEmpty() || (seq[0] - 'a') !in startSet || (seq[seq.length - 1] - 'a') !in endSet) return@forEachKnown
            if (blockOffensive && dict.offensive.getOrElse(dict.exactIndex(word.lowercase(Locale.US))) { false }) return@forEachKnown
            val prior = w.fq * w.userFreq / 255f + 0.3f + 0.05f * min(c, 10)
            evaluate(word, dict.exactIndex(word.lowercase(Locale.US)), seq, prior, userLen, cx, cy)?.let { cands += it }
        }
        if (cands.isEmpty()) return emptyList()
        cands.sortByDescending { it.pre }

        val scored = ArrayList<Pair<String, Float>>()
        for (c in cands.subList(0, min(w.top, cands.size))) {
            val seq = c.seq
            for (k in seq.indices) { wx[k] = cx[seq[k] - 'a']; wy[k] = cy[seq[k] - 'a'] }
            resample(wx, wy, seq.length, tx, ty)
            val d = dtw()
            var sc = -w.len * c.lenPen - (w.a * (w.dtwMix * d + (1 - w.dtwMix) * c.loc) +
                w.bp * c.pen / maxOf(1, c.letters - 2) + w.ce * c.ends) + c.prior
            if (context.learned.isNotEmpty()) sc += w.ctx * (context.learned[c.word.lowercase(Locale.US)] ?: 0f)
            context.phrase?.let { sc += w.lm * it.boost(c.idx).coerceIn(w.lmMin, w.lmMax) }
            // Every sharp turn should be at one of the word's letters ("hello" turns at the l).
            if (corners > 0) {
                var miss = 0f
                for (q in 0 until corners) {
                    var best = Float.MAX_VALUE
                    for (ch in seq) {
                        val d = hypot(cornerX[q] - cx[ch - 'a'], cornerY[q] - cy[ch - 'a'])
                        if (d < best) best = d
                    }
                    miss += maxOf(0f, best - w.cornerSlack)
                }
                sc -= w.corner * miss
            }
            scored += c.word to sc
        }
        scored.sortByDescending { it.second }
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for ((word, _) in scored) {
            if (seen.add(word.lowercase(Locale.US))) out += word
            if (out.size >= limit) break
        }
        return out
    }

    /** Stage-1 score of one word against the resampled swipe, or null if it can't be this swipe. */
    private fun evaluate(word: String, idx: Int, seq: String, prior: Float, userLen: Float, cx: FloatArray, cy: FloatArray): Cand? {
        val w = weights
        val m = seq.length
        if (m > Dictionary.MAX_LEN) return null
        var ideal = 0f
        for (k in 0 until m) {
            val c = seq[k] - 'a'
            if (cx[c].isNaN()) return null
            wx[k] = cx[c]; wy[k] = cy[c]
            if (k > 0) ideal += hypot(wx[k] - wx[k - 1], wy[k] - wy[k - 1])
        }
        if (userLen < ideal * 0.35f - 0.3f || userLen > ideal * 2.5f + 1.5f) return null

        // Optimal in-order alignment of middle letters; hard-reject letters never approached.
        align.fill(0f)
        for (k in 1 until m - 1) {
            var best = Float.MAX_VALUE
            for (i in 0 until n) {
                val d = hypot(ux[i] - wx[k], uy[i] - wy[k])
                dist[i] = d
                if (d < best) best = d
            }
            if (best > w.hard) return null
            var run = Float.MAX_VALUE
            for (i in 0 until n) {
                if (align[i] < run) run = align[i]
                align[i] = maxOf(0f, dist[i] - w.slack) + run
            }
        }
        var pen = 0f
        if (m > 2) { pen = Float.MAX_VALUE; for (i in 0 until n) if (align[i] < pen) pen = align[i] }

        resample(wx, wy, m, tx, ty)
        var loc = 0f
        for (i in 0 until n) loc += hypot(ux[i] - tx[i], uy[i] - ty[i])
        loc /= n
        val endD = hypot(ux[0] - tx[0], uy[0] - ty[0]) + hypot(ux[n - 1] - tx[n - 1], uy[n - 1] - ty[n - 1])
        val lenPen = abs(ln(maxOf(userLen, 0.3f) / maxOf(ideal, 0.3f)))
        val pre = -(w.a * loc + w.bp * pen / maxOf(1, m - 2) + w.ce * endD) - w.len * lenPen + prior
        return Cand(word, idx, seq, pre, pen, endD, lenPen, loc, m, prior)
    }

    /** Letters-only, repeat-free sequence of a learned word, or "" if the dictionary already has it. */
    private fun userSeq(word: String): String {
        if (dict.exactIndex(word.lowercase(Locale.US)) >= 0) return ""
        val sb = StringBuilder()
        for (ch in word.lowercase(Locale.US)) {
            if (ch == '\'') continue
            if (ch !in 'a'..'z') return ""
            if (sb.isEmpty() || sb[sb.length - 1] != ch) sb.append(ch)
        }
        return if (sb.length >= 2) sb.toString() else ""
    }

    /** Language prior: frequency, your own usage, and a nudge away from names/possessives/acronyms. */
    private fun dictPrior(idx: Int, word: String, learned: Boolean): Float {
        val w = weights
        var p = w.fq * dict.freqs[idx] / 255f
        // Names, possessives and acronyms are unlikely glides, but contractions aren't ("it's", "I'd").
        val contraction = word.startsWith("I'") || word in CONTRACTIONS
        val odd = !contraction && ((word[0].isUpperCase() && word != "I") || word.endsWith("'s") ||
            (word.length > 1 && word.all { it.isUpperCase() }))
        if (odd) p -= w.cap
        if (dict.slang[idx]) p -= w.slang
        val c = if (learned) history.count(dict.keys[idx]) else 0
        if (c > 0) p += 0.3f + 0.05f * min(c, 10)
        return p
    }

    private fun nearKeys(x: Float, y: Float, cx: FloatArray, cy: FloatArray): List<Int> {
        val w = weights
        val d = (0 until 26).filter { !cx[it].isNaN() }.map { it to hypot(x - cx[it], y - cy[it]) }.sortedBy { it.second }
        val out = d.filter { it.second < w.radius }.take(w.maxEnds).map { it.first }
        return out.ifEmpty { d.take(1).map { it.first } }
    }

    /**
     * Finds the sharp turns in the resampled swipe (ux,uy) and stores the sharpest point of each in
     * cornerX/cornerY. A turn is where the stroke before and after a point meet at more than the
     * angle whose cosine is [cos]. Returns how many.
     */
    private fun findCorners(cos: Float): Int {
        var count = 0
        var bestI = -1
        var bestCos = 2f
        for (i in CORNER_SPAN until n - CORNER_SPAN) {
            val ax = ux[i] - ux[i - CORNER_SPAN]
            val ay = uy[i] - uy[i - CORNER_SPAN]
            val bx = ux[i + CORNER_SPAN] - ux[i]
            val by = uy[i + CORNER_SPAN] - uy[i]
            val la = hypot(ax, ay)
            val lb = hypot(bx, by)
            val c = if (la > 0.08f && lb > 0.08f) (ax * bx + ay * by) / (la * lb) else 1f
            if (c < cos) {
                if (c < bestCos) { bestCos = c; bestI = i }
            } else if (bestI >= 0) {
                cornerX[count] = ux[bestI]; cornerY[count] = uy[bestI]; count++
                bestI = -1; bestCos = 2f
            }
        }
        if (bestI >= 0) { cornerX[count] = ux[bestI]; cornerY[count] = uy[bestI]; count++ }
        return count
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
        private const val BAND = 10
        /** Resampled points on each side of a point when measuring how sharply the swipe turns there. */
        private const val CORNER_SPAN = 3
        private val CONTRACTIONS = setOf(
            "it's", "let's", "he's", "she's", "that's", "what's", "there's", "here's", "who's", "where's", "how's",
        )
    }
}

/** sqrt(dx² + dy²). kotlin.math.hypot guards against overflow we can't hit and is ~2x slower here. */
private fun hypot(dx: Float, dy: Float) = sqrt(dx * dx + dy * dy)
