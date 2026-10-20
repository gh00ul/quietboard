package dev.larryjones.quietboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Locale
import java.util.Random

/**
 * Glide-typing accuracy on simulated sloppy swipes of the ~1,500 most common words, in two swipe
 * styles and three sloppiness levels. Words alternate between a tuning half and a held-out half, so
 * tuned weights are judged on words they never saw. A second part measures the previous word's help
 * in real sentences ("thank" -> "you", "I'm going" -> "to").
 *
 * Words whose swipe is identical to a more common word's ("too"/"to", "off"/"of") are left out of the
 * shape-only numbers: only context can tell those apart. The context part glides every word of everyday
 * sentences the phrase model never saw (see PredictionBenchmark), those included.
 */
class GlideBenchmark {
    companion object {
        lateinit var dict: Dictionary
        lateinit var decoder: GestureDecoder
        lateinit var suggest: Suggest
        lateinit var phrases: PhraseModel

        @BeforeClass @JvmStatic
        fun load() {
            val assets = File("src/main/assets")
            dict = File(assets, "words_en.txt").inputStream().use { Dictionary.fromStreams(it, null) }
            val history = UserHistory(Files.createTempDirectory("qb").toFile())
            decoder = GestureDecoder(dict, history)
            suggest = Suggest(dict, history)
            phrases = File(assets, "phrases_en.bin").inputStream().use { PhraseModel.fromStream(it, dict) }
            suggest.phrases = phrases
            PredictionBenchmark.load()
        }

        val SLOPS = listOf(0.25f, 0.35f, 0.45f)
    }

    class Result(val n: Int, val top1: Int, val top3: Int) {
        val rate1 get() = top1.toDouble() / n
        val rate3 get() = top3.toDouble() / n
    }

    /** (all common glideable words by frequency, words whose swipe a more common word shares). */
    internal fun commonWords(): Pair<List<String>, Set<String>> {
        val idx = dict.words.indices
            .filter {
                val w = dict.words[it]
                w.length >= 2 && w == w.lowercase(Locale.US) && dict.gestureSeq[it] != null &&
                    !dict.never[it] && !dict.offensive[it] && w.all { c -> c in 'a'..'z' || c == '\'' }
            }
            .sortedByDescending { dict.freqs[it] }
        val bestBySeq = HashMap<String, Int>()
        for (i in dict.words.indices) {
            val s = dict.gestureSeq[i] ?: continue
            if (dict.never[i]) continue
            val cur = bestBySeq[s]
            if (cur == null || dict.freqs[i] > dict.freqs[cur]) bestBySeq[s] = i
        }
        val words = idx.take(1500).map { dict.words[it] }
        val shadowed = words.filter { w ->
            val i = dict.exactIndex(w)
            dict.words[bestBySeq.getValue(dict.gestureSeq[i]!!)].lowercase(Locale.US) != w
        }.toSet()
        return words to shadowed
    }

    internal fun split(): Pair<List<String>, List<String>> {
        val (words, shadowed) = commonWords()
        val usable = words.filter { it !in shadowed }
        return usable.filterIndexed { i, _ -> i % 2 == 0 } to usable.filterIndexed { i, _ -> i % 2 == 1 }
    }

    internal fun measure(words: List<String>, slops: List<Float> = SLOPS, styles: List<SwipeSim.Style> = SwipeSim.Style.entries, seed: Long = 11): Result {
        val rnd = Random(seed)
        var n = 0; var t1 = 0; var t3 = 0
        for (style in styles) for (slop in slops) for (w in words) {
            val (xs, ys) = SwipeSim.swipe(w, rnd, slop, style)
            val out = decoder.decode(xs, ys, xs.size, SwipeSim.cx, SwipeSim.cy, SwipeSim.KW, SwipeSim.KH, 3)
            n++
            if (out.firstOrNull()?.lowercase(Locale.US) == w) t1++
            if (out.any { it.lowercase(Locale.US) == w }) t3++
        }
        return Result(n, t1, t3)
    }

    /** (text before, word) for every glideable word of the given sentences. */
    internal fun contextPairs(sentences: List<String>): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (s in sentences) for ((before, w) in PredictionBenchmark.positions(s)) {
            val i = dict.exactIndex(w.lowercase(Locale.US))
            if (i >= 0 && dict.gestureSeq[i] != null && SwipeSim.seq(w).length >= 2) out += before to w
        }
        return out
    }

    internal fun measureContext(pairs: List<Pair<String, String>>, useContext: Boolean, seed: Long = 5): Result {
        val rnd = Random(seed)
        var n = 0; var t1 = 0; var t3 = 0
        for (style in SwipeSim.Style.entries) for ((before, w) in pairs) {
            val (xs, ys) = SwipeSim.swipe(w, rnd, 0.35f, style)
            val ctx = if (useContext) suggest.contextFor(null, false, before = TextContext.of(before)) else GlideContext.NONE
            val out = decoder.decode(xs, ys, xs.size, SwipeSim.cx, SwipeSim.cy, SwipeSim.KW, SwipeSim.KH, 3, ctx)
            n++
            if (out.firstOrNull()?.lowercase(Locale.US) == w.lowercase(Locale.US)) t1++
            if (out.any { it.lowercase(Locale.US) == w.lowercase(Locale.US) }) t3++
        }
        return Result(n, t1, t3)
    }

    @Test fun glideAccuracy() {
        val (tune, held) = split()
        val t0 = System.nanoTime()
        val rTune = measure(tune)
        val rHeld = measure(held)
        val ms = (System.nanoTime() - t0) / 1e6 / (rTune.n + rHeld.n)
        println("glide benchmark: ${tune.size}+${held.size} words x ${SLOPS.size} sloppiness x 2 styles, %.1f ms/swipe".format(ms))
        println("  tuning half   top1 %5.1f%%  top3 %5.1f%%".format(100 * rTune.rate1, 100 * rTune.rate3))
        println("  held-out half top1 %5.1f%%  top3 %5.1f%%".format(100 * rHeld.rate1, 100 * rHeld.rate3))
        for (slop in SLOPS) for (style in SwipeSim.Style.entries) {
            val r = measure(held, listOf(slop), listOf(style))
            println("    held-out %-6s slop %.2f  top1 %5.1f%%  top3 %5.1f%%".format(style, slop, 100 * r.rate1, 100 * r.rate3))
        }
        val pairs = contextPairs(PredictionBenchmark.heldOut().take(500))
        val without = measureContext(pairs, false)
        val with = measureContext(pairs, true)
        println("  in sentences (${pairs.size} words x 2 styles): top1 without context %5.1f%%  with %5.1f%%  (top3 %5.1f%% / %5.1f%%)"
            .format(100 * without.rate1, 100 * with.rate1, 100 * without.rate3, 100 * with.rate3))
        assertTrue("held-out top1 dropped: ${rHeld.rate1}", rHeld.rate1 >= MIN_HELD_TOP1)
        assertTrue("context made glides worse", with.rate1 >= without.rate1)
        assertTrue("glides in sentences dropped: ${with.rate1}", with.rate1 >= MIN_SENTENCE_TOP1)
    }

    @Test fun learnedWordsAreGlideable() {
        val h = UserHistory(Files.createTempDirectory("qb").toFile())
        val d = GestureDecoder(dict, h)
        d.weights = decoder.weights
        val name = "Zorbix" // not in the dictionary
        val (xs, ys) = SwipeSim.swipe(name, Random(1), 0.2f, SwipeSim.Style.SMOOTH)
        assertTrue(name !in d.decode(xs, ys, xs.size, SwipeSim.cx, SwipeSim.cy, SwipeSim.KW, SwipeSim.KH, 4))
        repeat(2) { h.learn(null, name) }
        assertEquals(name, d.decode(xs, ys, xs.size, SwipeSim.cx, SwipeSim.cy, SwipeSim.KW, SwipeSim.KH, 4).firstOrNull())
        // Lock screen and incognito fields: learned words don't glide.
        assertTrue(name !in d.decode(xs, ys, xs.size, SwipeSim.cx, SwipeSim.cy, SwipeSim.KW, SwipeSim.KH, 4, GlideContext.NONE, learned = false))
    }

    @Test fun previousWordBreaksTies() {
        // Same swipe, different previous word: context picks the likely one.
        var wins = 0
        val rnd = Random(4)
        val cases = listOf("I " to "like", "Thank " to "you", "Good " to "morning", "How " to "are", "See " to "you", "I'm going " to "to")
        for ((before, w) in cases) repeat(4) {
            val (xs, ys) = SwipeSim.swipe(w, rnd, 0.4f, SwipeSim.Style.CUT)
            val ctx = suggest.contextFor(null, false, before = TextContext.of(before))
            val out = decoder.decode(xs, ys, xs.size, SwipeSim.cx, SwipeSim.cy, SwipeSim.KW, SwipeSim.KH, 3, ctx)
            if (out.firstOrNull() == w) wins++
        }
        assertTrue("context should settle most of these: $wins/${cases.size * 4}", wins >= cases.size * 3)
    }

    /** Regression guard, just under the measured 79.4% (72.2% before tuning). */
    private val MIN_HELD_TOP1 = 0.78
    /** Words in everyday sentences: 81.8% without context, 82.3% with 1.8.1's follow-on list, 88.7% with the phrase model. */
    private val MIN_SENTENCE_TOP1 = 0.875
}
