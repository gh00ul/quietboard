package dev.larryjones.quietboard

import android.content.Context
import java.io.InputStream
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

/**
 * Read-only English word list bundled in the APK (AOSP LatinIME list, Apache-2.0).
 * Everything happens in memory; nothing is ever looked up online.
 */
class Dictionary private constructor(
    val words: Array<String>,
    val keys: Array<String>,
    val freqs: IntArray,
    /** Profanity (never slurs, which aren't in the list at all); hidden when "Block offensive words" is on. */
    val offensive: BooleanArray,
    /** Texting slang/abbreviations ("lol", "rn"): normal suggestions, handicapped as glide results. */
    val slang: BooleanArray,
    /** Real words that are never offered (AOSP-flagged words incl. slurs): known, so not "corrected". */
    val never: BooleanArray,
    private val whitelist: Map<String, String>,
) {
    private val exact = HashMap<String, Int>(words.size * 2)
    private val byLength: Array<IntArray>

    /** Letters-only, consecutive-duplicate-free form of each word for glide typing ("hello" -> "helo"). */
    val gestureSeq: Array<String?> = arrayOfNulls(words.size)
    private val gestureBuckets: Array<IntArray>

    init {
        // "AF"/"af", "FML"/"fml": if any spelling of a word is offensive, every spelling is.
        val offensiveKeys = HashSet<String>()
        for (i in keys.indices) if (offensive[i]) offensiveKeys += keys[i]
        for (i in keys.indices) if (keys[i] in offensiveKeys) offensive[i] = true

        for (i in keys.indices) {
            val k = keys[i]
            val cur = exact[k]
            if (cur == null) {
                exact[k] = i
            } else {
                val curIsLower = words[cur] == k
                val newIsLower = words[i] == k
                if ((newIsLower && !curIsLower) || (newIsLower == curIsLower && freqs[i] > freqs[cur])) exact[k] = i
            }
        }

        val counts = IntArray(MAX_LEN + 1)
        for (k in keys) counts[min(k.length, MAX_LEN)]++
        val lenArrays = Array(MAX_LEN + 1) { IntArray(counts[it]) }
        val fill = IntArray(MAX_LEN + 1)
        for (i in keys.indices) {
            val l = min(keys[i].length, MAX_LEN)
            lenArrays[l][fill[l]++] = i
        }
        byLength = lenArrays

        val bucketLists = Array(26 * 26) { ArrayList<Int>() }
        for (i in keys.indices) {
            val sb = StringBuilder()
            var ok = true
            for (ch in keys[i]) {
                if (ch == '\'') continue
                if (ch !in 'a'..'z') { ok = false; break }
                if (sb.isEmpty() || sb[sb.length - 1] != ch) sb.append(ch)
            }
            if (!ok || sb.length < 2) continue
            val s = sb.toString()
            gestureSeq[i] = s
            bucketLists[(s[0] - 'a') * 26 + (s[s.length - 1] - 'a')].add(i)
        }
        gestureBuckets = Array(26 * 26) { bucketLists[it].toIntArray() }
    }

    val size get() = words.size

    /** Index of the preferred entry for this lowercase key, or -1. */
    fun exactIndex(lower: String): Int = exact[lower] ?: -1

    fun whitelistFor(lower: String): String? = whitelist[lower]

    fun gestureBucket(first: Char, last: Char): IntArray = gestureBuckets[(first - 'a') * 26 + (last - 'a')]

    /** Calls [fn] with every index whose key starts with [prefix] (lowercase). */
    inline fun forEachWithPrefix(prefix: String, fn: (Int) -> Unit) {
        var i = lowerBound(prefix)
        while (i < keys.size && keys[i].startsWith(prefix)) {
            fn(i); i++
        }
    }

    fun lowerBound(prefix: String): Int {
        var lo = 0
        var hi = keys.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (keys[mid] < prefix) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Candidates within [maxDist] weighted edits of [typed] (lowercase). */
    fun corrections(typed: String, maxDist: Float, out: (index: Int, dist: Float) -> Unit) {
        val n = typed.length
        val spread = if (maxDist < 1f) 1 else 2
        val f0 = typed[0]
        val f1 = if (n > 1) typed[1] else ' '
        for (len in maxOf(1, n - spread)..min(MAX_LEN, n + spread)) {
            for (idx in byLength[len]) {
                val k = keys[idx]
                if (abs(k.length - n) > spread) continue
                // Cheap pre-filter: the first letter is right, a neighbour, or shifted by one.
                val c0 = k[0]
                if (c0 != f0 && !adjacent(f0, c0) && c0 != f1 && (k.length < 2 || k[1] != f0) && fold(c0) != fold(f0)) continue
                val d = distance(typed, k, maxDist)
                if (d <= maxDist) out(idx, d)
            }
        }
    }

    // Scratch rows for the edit-distance DP (main thread only).
    private val rowA = FloatArray(MAX_LEN + 2)
    private val rowB = FloatArray(MAX_LEN + 2)
    private val rowC = FloatArray(MAX_LEN + 2)

    /**
     * Damerau-Levenshtein with keyboard-aware weights: hitting a neighbouring key is cheaper than a
     * random substitution, and a missing apostrophe ("dont" -> "don't") is almost free.
     */
    fun distance(a: String, b: String, cutoff: Float): Float {
        val n = a.length
        val m = b.length
        if (n > MAX_LEN || m > MAX_LEN) return Float.MAX_VALUE
        var prev2 = rowA
        var prev = rowB
        var cur = rowC
        prev[0] = 0f
        for (j in 1..m) prev[j] = prev[j - 1] + insCost(b[j - 1])
        var prevRowMin = 0f
        for (i in 1..n) {
            cur[0] = i.toFloat()
            var rowMin = cur[0]
            val ca = a[i - 1]
            for (j in 1..m) {
                val cb = b[j - 1]
                var v = prev[j - 1] + subCost(ca, cb)
                // Hitting a letter twice ("helllo") or missing a double ("helo") is a cheap mistake.
                val del = prev[j] + (if (i > 1 && a[i - 2] == ca) 0.3f else 1f)
                if (del < v) v = del
                val ins = cur[j - 1] + (if (j > 1 && b[j - 2] == cb) 0.3f else insCost(cb))
                if (ins < v) v = ins
                if (i > 1 && j > 1 && ca == b[j - 2] && a[i - 2] == cb && ca != cb) {
                    val t = prev2[j - 2] + TRANSPOSE
                    if (t < v) v = t
                }
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            // Give up only if no path can come back under the cutoff: a transposition in the next row can
            // start from the previous one ("tahnsk" -> "thanks" dips above it in the middle).
            if (rowMin > cutoff && prevRowMin + TRANSPOSE > cutoff) return Float.MAX_VALUE
            prevRowMin = rowMin
            val tmp = prev2; prev2 = prev; prev = cur; cur = tmp
        }
        return prev[m]
    }

    private fun insCost(c: Char) = if (c == '\'') 0.25f else 1f

    private fun adjacent(a: Char, b: Char) =
        a in 'a'..'z' && b in 'a'..'z' && ADJ[(a - 'a') * 26 + (b - 'a')]

    /** Accent-free base letter: é -> e, ñ -> n (cached; only non-ASCII letters need work). */
    private val folded = HashMap<Char, Char>()

    private fun fold(c: Char): Char =
        if (c.code < 0x80) c else folded.getOrPut(c) { Normalizer.normalize(c.toString(), Normalizer.Form.NFD)[0] }

    private fun subCost(a: Char, b: Char): Float {
        if (a == b) return 0f
        if (fold(a) == fold(b)) return 0.1f // "cafe" vs "café": nearly the same word
        if (a in 'a'..'z' && b in 'a'..'z' && ADJ[(a - 'a') * 26 + (b - 'a')]) return 0.6f
        return 1f
    }

    companion object {
        const val MAX_LEN = 24
        /** A swapped pair ("teh") is the most common typo: cheaper than hitting a neighbouring key. */
        private const val TRANSPOSE = 0.5f

        private val ADJ: BooleanArray = BooleanArray(26 * 26).also { adj ->
            val rows = listOf("qwertyuiop" to 0f, "asdfghjkl" to 0.5f, "zxcvbnm" to 1.5f)
            val pos = HashMap<Char, Pair<Float, Float>>()
            rows.forEachIndexed { r, (s, off) -> s.forEachIndexed { i, c -> pos[c] = (i + off) to r.toFloat() } }
            for ((c1, p1) in pos) for ((c2, p2) in pos) {
                if (c1 == c2) continue
                val dx = abs(p1.first - p2.first)
                val dy = abs(p1.second - p2.second)
                if (dy <= 1f && dx <= 1.01f && !(dy == 0f && dx > 1f)) adj[(c1 - 'a') * 26 + (c2 - 'a')] = true
            }
        }

        fun load(ctx: Context): Dictionary {
            val words = ctx.assets.open("words_en.txt")
            val corrections = runCatching { ctx.assets.open("corrections_en.txt") }.getOrNull()
            return words.use { w -> fromStreams(w, corrections) }
        }

        fun fromStreams(wordStream: InputStream, correctionStream: InputStream?): Dictionary {
            val ws = ArrayList<String>(110_000)
            var fs = IntArray(110_000)
            val offSet = HashSet<Int>()
            val slangSet = HashSet<Int>()
            val neverSet = HashSet<Int>()
            wordStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val parts = line.split('\t')
                    if (parts.size < 2 || parts[0].isEmpty()) continue
                    val f = parts[1].trim().toIntOrNull() ?: continue
                    if (ws.size == fs.size) fs = fs.copyOf(fs.size * 2)
                    fs[ws.size] = f
                    if (parts.size > 2) {
                        if ('o' in parts[2]) offSet += ws.size
                        if ('s' in parts[2]) slangSet += ws.size
                        if ('n' in parts[2]) neverSet += ws.size
                    }
                    ws.add(parts[0])
                }
            }
            var off = BooleanArray(ws.size) { it in offSet }
            var sl = BooleanArray(ws.size) { it in slangSet }
            var nv = BooleanArray(ws.size) { it in neverSet }
            val wl = HashMap<String, String>()
            correctionStream?.bufferedReader()?.useLines { lines ->
                for (line in lines) {
                    val p = line.split('\t')
                    if (p.size == 2) wl[p[0]] = p[1]
                }
            }
            var words = ws.toTypedArray()
            var freqs = fs.copyOf(ws.size)
            var keys = Array(words.size) { words[it].lowercase(Locale.US) }
            // The asset ships pre-sorted; re-sort only if that ever stops being true.
            var sorted = true
            for (i in 1 until keys.size) if (keys[i - 1] > keys[i]) { sorted = false; break }
            if (!sorted) {
                val order = keys.indices.sortedBy { keys[it] }
                words = Array(order.size) { words[order[it]] }
                freqs = IntArray(order.size) { freqs[order[it]] }
                keys = Array(order.size) { keys[order[it]] }
                val o2 = off
                off = BooleanArray(order.size) { o2[order[it]] }
                val s2 = sl
                sl = BooleanArray(order.size) { s2[order[it]] }
                val n2 = nv
                nv = BooleanArray(order.size) { n2[order[it]] }
            }
            return Dictionary(words, keys, freqs, off, sl, nv, wl)
        }
    }
}
