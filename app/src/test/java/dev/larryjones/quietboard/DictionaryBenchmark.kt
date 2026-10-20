package dev.larryjones.quietboard

import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Locale
import kotlin.random.Random

/**
 * Measures autocorrect on realistic typos of the most common words, so dictionary changes are judged
 * by numbers instead of anecdotes. Prints a report; the asserts only guard against regressions.
 *
 * Typos: a neighbouring key hit, two letters swapped, a letter dropped, a letter doubled, a missing
 * apostrophe. Typos that are themselves real words ("form" for "from") are skipped: no keyboard can
 * fix those without context.
 */
class DictionaryBenchmark {
    companion object {
        lateinit var dict: Dictionary
        lateinit var suggest: Suggest

        @BeforeClass @JvmStatic
        fun load() {
            val assets = File("src/main/assets")
            dict = File(assets, "words_en.txt").inputStream().use { w ->
                Dictionary.fromStreams(w, File(assets, "corrections_en.txt").inputStream())
            }
            suggest = Suggest(dict, UserHistory(Files.createTempDirectory("qb").toFile()))
        }

        private val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        private val pos = HashMap<Char, Pair<Float, Float>>().apply {
            val off = listOf(0f, 0.5f, 1.5f)
            rows.forEachIndexed { r, s -> s.forEachIndexed { i, c -> this[c] = (i + off[r]) to r.toFloat() } }
        }
        internal val neighbours: Map<Char, List<Char>> = pos.keys.associateWith { c ->
            val (x, y) = pos.getValue(c)
            pos.filter { (d, p) ->
                d != c && kotlin.math.abs(p.second - y) <= 1f && kotlin.math.abs(p.first - x) <= 1.01f &&
                    !(p.second == y && kotlin.math.abs(p.first - x) > 1f)
            }.keys.toList()
        }
    }

    internal class Case(val kind: String, val typo: String, val target: String)

    internal fun casesForTuning() = cases()

    private fun cases(): List<Case> {
        val rnd = Random(7)
        // The ~2500 most frequent everyday words (lowercase, letters/apostrophes, 3+ letters).
        val common = dict.words.indices
            .filter { val w = dict.words[it]; w.length >= 3 && w == w.lowercase(Locale.US) && w.all { c -> c in 'a'..'z' || c == '\'' } }
            .filter { !dict.never[it] && !dict.offensive[it] }
            .sortedByDescending { dict.freqs[it] }
            .take(2500)
            .map { dict.words[it] }
        val out = ArrayList<Case>()
        for (w in common) {
            val letters = w.indices.filter { w[it] in 'a'..'z' }
            fun add(kind: String, t: String) { if (t != w && dict.exactIndex(t) < 0) out += Case(kind, t, w) }
            // neighbouring key (not the first letter: first-letter slips are rarer and harder)
            letters.filter { it > 0 }.randomOrNull(rnd)?.let { i ->
                neighbours[w[i]]?.randomOrNull(rnd)?.let { n -> add("neighbour", w.substring(0, i) + n + w.substring(i + 1)) }
            }
            // swapped pair
            (0 until w.length - 1).filter { w[it] != w[it + 1] && w[it] != '\'' && w[it + 1] != '\'' }.randomOrNull(rnd)?.let { i ->
                add("swap", w.substring(0, i) + w[i + 1] + w[i] + w.substring(i + 2))
            }
            // dropped letter (4+ letters)
            if (w.length >= 4) letters.filter { it > 0 }.randomOrNull(rnd)?.let { i -> add("dropped", w.removeRange(i, i + 1)) }
            // doubled letter
            letters.randomOrNull(rnd)?.let { i -> add("doubled", w.substring(0, i + 1) + w[i] + w.substring(i + 1)) }
            // missing apostrophe
            if ('\'' in w) add("apostrophe", w.replace("'", ""))
        }
        return out
    }

    @Test fun autocorrectAccuracy() {
        val cs = cases()
        val byKind = cs.groupBy { it.kind }
        var top1 = 0; var top3 = 0; var wrong = 0
        val report = StringBuilder()
        val misses = ArrayList<String>()
        for ((kind, list) in byKind) {
            var k1 = 0; var k3 = 0; var kw = 0
            for (c in list) {
                val r = suggest.suggest(c.typo, null, true, emptySet())
                val strip = r.strip.mapNotNull { it?.word?.lowercase(Locale.US) }
                if (r.autoCorrect?.lowercase(Locale.US) == c.target) k1++
                else if (r.autoCorrect != null) { kw++; if (misses.size < 40 && rndPick(c)) misses += "${c.typo}->${r.autoCorrect} (${c.target})" }
                if (r.autoCorrect?.lowercase(Locale.US) == c.target || c.target in strip) k3++
            }
            top1 += k1; top3 += k3; wrong += kw
            report.append("  %-10s n=%4d  fixed %5.1f%%  in strip %5.1f%%  wrong fix %4.1f%%\n".format(
                kind, list.size, 100.0 * k1 / list.size, 100.0 * k3 / list.size, 100.0 * kw / list.size))
        }
        val n = cs.size
        println("dictionary benchmark: $n typos of the 2500 most common words")
        print(report)
        println("  TOTAL      n=%4d  fixed %5.1f%%  in strip %5.1f%%  wrong fix %4.1f%%".format(
            n, 100.0 * top1 / n, 100.0 * top3 / n, 100.0 * wrong / n))
        println("  sample wrong fixes: $misses")
        BASELINE_CHECK(top1.toDouble() / n, top3.toDouble() / n, wrong.toDouble() / n)
    }

    private fun rndPick(c: Case) = (c.typo.hashCode() and 7) == 0

    /**
     * Regression guard, just under the measured numbers. History: 88.1% fixed / 95.1% in strip / 10.2%
     * wrong at introduction; 93.5% / 98.1% / 4.9% after tuning edit costs and the 1.6 word list.
     */
    @Suppress("FunctionName")
    private fun BASELINE_CHECK(fixed: Double, inStrip: Double, wrongFix: Double) {
        assertTrue("fixed rate dropped: $fixed", fixed >= MIN_FIXED)
        assertTrue("in-strip rate dropped: $inStrip", inStrip >= MIN_IN_STRIP)
        assertTrue("wrong-fix rate rose: $wrongFix", wrongFix <= MAX_WRONG)
    }

    private val MIN_FIXED = 0.925
    private val MIN_IN_STRIP = 0.975
    private val MAX_WRONG = 0.055
}
