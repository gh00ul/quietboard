package dev.larryjones.quietboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Locale
import kotlin.random.Random

/**
 * The phrase model on everyday sentences it never saw (4,000 from Tatoeba, CC BY 2.0 FR, in
 * src/test/resources/sentences_en.txt; the model comes from film and TV dialogue). Sentences alternate
 * between a tuning half and a held-out half, and the numbers that matter are on the held-out half:
 *  - next-word predictions: how often the word you type next is in the strip before you type a letter,
 *  - keystrokes saved: letters you don't type because the word showed up in the strip first,
 *  - autocorrect in context: typos of real words in real sentences, fixed with and without context.
 * Prints a report; the asserts only guard against regressions.
 */
class PredictionBenchmark {
    companion object {
        lateinit var dict: Dictionary
        lateinit var phrases: PhraseModel
        lateinit var sentences: List<String>

        @BeforeClass @JvmStatic
        fun load() {
            val assets = File("src/main/assets")
            dict = File(assets, "words_en.txt").inputStream().use { w ->
                Dictionary.fromStreams(w, File(assets, "corrections_en.txt").inputStream())
            }
            phrases = File(assets, "phrases_en.bin").inputStream().use { PhraseModel.fromStream(it, dict) }
            sentences = File("src/test/resources/sentences_en.txt").readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        }

        private val WORD = Regex("[A-Za-z']+")

        /** Every word of [s] with the text before it: ("I want ", "to"). */
        fun positions(s: String): List<Pair<String, String>> =
            WORD.findAll(s).map { s.substring(0, it.range.first) to it.value }.toList()

        fun tuning() = sentences.filterIndexed { i, _ -> i % 2 == 0 }
        fun heldOut() = sentences.filterIndexed { i, _ -> i % 2 == 1 }

        fun suggest(withModel: Boolean = true) =
            Suggest(dict, UserHistory(Files.createTempDirectory("qb").toFile())).also { if (withModel) it.phrases = phrases }

        /** One realistic typo of [w] (a neighbouring key, a dropped letter or a swapped pair) that isn't a word itself. */
        fun typo(w: String, rnd: Random): String? {
            if (w.length < 3 || !w.all { it in 'a'..'z' }) return null
            val i = 1 + rnd.nextInt(w.length - 1)
            val t = when (rnd.nextInt(3)) {
                0 -> DictionaryBenchmark.neighbours[w[i]]?.random(rnd)?.let { n -> w.substring(0, i) + n + w.substring(i + 1) }
                1 -> if (w.length >= 4) w.removeRange(i, i + 1) else null
                else -> if (i < w.length - 1 && w[i] != w[i + 1]) w.substring(0, i) + w[i + 1] + w[i] + w.substring(i + 2) else null
            }
            return t?.takeIf { it != w && dict.exactIndex(it) < 0 }
        }
    }

    class Rates(val n: Int, val top1: Int, val top3: Int) {
        val rate1 get() = top1.toDouble() / n
        val rate3 get() = top3.toDouble() / n
    }

    /** Is [word] what the strip offers, and is it in the middle (what space would type)? */
    private fun place(strip: List<SuggestionItem?>, word: String): Int {
        val w = word.lowercase(Locale.US)
        if (strip.getOrNull(1)?.word?.lowercase(Locale.US) == w) return 1
        return if (strip.any { it?.word?.lowercase(Locale.US) == w }) 3 else 0
    }

    /** With [oneWord], the model only gets the last token, to check the one before it helps. */
    internal fun nextWords(s: Suggest, sents: List<String>, oneWord: Boolean = false): Rates {
        var n = 0; var t1 = 0; var t3 = 0
        for (sent in sents) for ((before, word) in positions(sent)) {
            if (dict.exactIndex(word.lowercase(Locale.US)) < 0) continue
            n++
            val ctx = TextContext.of(before).let { if (oneWord) TextContext(null, it.prev1) else it }
            when (place(s.predict(null, false, before = ctx), word)) {
                1 -> { t1++; t3++ }
                3 -> t3++
            }
        }
        return Rates(n, t1, t3)
    }

    /** Share of letters you never type: the word was in the strip after fewer letters than it has. */
    internal fun keystrokesSaved(s: Suggest, sents: List<String>): Double {
        var letters = 0; var saved = 0
        for (sent in sents) for ((before, word) in positions(sent)) {
            if (dict.exactIndex(word.lowercase(Locale.US)) < 0) continue
            letters += word.length
            val ctx = TextContext.of(before)
            for (j in 0 until word.length) {
                val strip = if (j == 0) s.predict(null, false, before = ctx)
                else s.suggest(word.substring(0, j), ctx.prev1, true, emptySet(), before = ctx).let { r ->
                    // Space types the bold word, so a completion shown there counts too.
                    r.strip + listOfNotNull(r.autoCorrect?.let { SuggestionItem(it) })
                }
                if (place(strip, word) > 0) { saved += word.length - j; break }
            }
        }
        return saved.toDouble() / letters
    }

    internal fun typoCases(sents: List<String>, seed: Int = 3): List<Pair<String, Pair<String, String>>> {
        val rnd = Random(seed)
        val out = ArrayList<Pair<String, Pair<String, String>>>()
        for (sent in sents) for ((before, word) in positions(sent)) {
            val t = typo(word, rnd) ?: continue
            out += before to (t to word)
        }
        return out
    }

    /** (fixed, wrongly "fixed") shares of typos in context. */
    internal fun autocorrect(s: Suggest, cases: List<Pair<String, Pair<String, String>>>, withContext: Boolean): Pair<Double, Double> {
        var fixed = 0; var wrong = 0
        for ((before, tw) in cases) {
            val (t, w) = tw
            val ctx = if (withContext) TextContext.of(before) else TextContext.NONE
            val ac = s.suggest(t, ctx.prev1?.takeIf { withContext }, true, emptySet(), before = ctx).autoCorrect?.lowercase(Locale.US)
            if (ac == w) fixed++ else if (ac != null) wrong++
        }
        return fixed.toDouble() / cases.size to wrong.toDouble() / cases.size
    }

    @Test fun predictionsOnUnseenSentences() {
        val held = heldOut()
        val s = suggest()
        val r = nextWords(s, held)
        val one = nextWords(s, held, oneWord = true)
        println("prediction benchmark: ${held.size} held-out sentences, ${r.n} words")
        println("  next word before typing a letter: in the middle %5.1f%%  in the strip %5.1f%%  (one word of context: %5.1f%% / %5.1f%%)"
            .format(100 * r.rate1, 100 * r.rate3, 100 * one.rate1, 100 * one.rate3))
        val t0 = System.nanoTime()
        val saved = keystrokesSaved(s, held.take(600))
        println("  letters saved by tapping a suggestion (600 sentences): %5.1f%%  (%.0f s)".format(100 * saved, (System.nanoTime() - t0) / 1e9))
        val cases = typoCases(held)
        val (f0, w0) = autocorrect(s, cases, false)
        val (f1, w1) = autocorrect(s, cases, true)
        println("  ${cases.size} typos in context: fixed %5.1f%% without context (%4.1f%% wrong), %5.1f%% with (%4.1f%% wrong)"
            .format(100 * f0, 100 * w0, 100 * f1, 100 * w1))
        assertTrue("predictions dropped: ${r.rate3}", r.rate3 >= MIN_IN_STRIP)
        assertTrue("middle-slot predictions dropped: ${r.rate1}", r.rate1 >= MIN_MIDDLE)
        assertTrue("the word two back stopped helping", r.rate3 > one.rate3 + 0.01)
        assertTrue("keystroke savings dropped: $saved", saved >= MIN_SAVED)
        assertTrue("context made autocorrect worse", f1 > f0 && w1 <= w0)
        assertTrue("typos in context: fixed rate dropped: $f1", f1 >= MIN_FIXED)
        assertTrue("typos in context: wrong fixes rose: $w1", w1 <= MAX_WRONG)
    }

    @Test fun predictionsAreSensible() {
        val s = suggest()
        fun mid(text: String) = s.predict(null, false, before = TextContext.of(text)).getOrNull(1)?.word
        fun strip(text: String) = s.predict(null, false, before = TextContext.of(text)).mapNotNull { it?.word }
        assertTrue("you" in strip("Thank "))
        // After a closing phrase the sentence usually just ends: that's no reason to expect "know".
        assertTrue("\"Thank you\" -> ${strip("Thank you ")}", "know" !in strip("Thank you "))
        assertEquals("caps lock: predictions in capitals too", "YOU", s.predict(null, true, before = TextContext.of("THANK "), allCaps = true).getOrNull(1)?.word)
        assertTrue("predictions after \"5pm\"", strip("See you at 5pm ").isNotEmpty())
        assertTrue("to" in strip("I'm going "))
        assertTrue("way" in strip("I'm on my "))
        assertTrue("I" in strip(""))
        assertTrue("predictions after a comma too", strip("Hey, ").isNotEmpty())
        // The word two back matters: "how are" -> you, "what are" -> you, "there are" -> no.
        assertTrue(mid("How are ") == "you")
        assertTrue("I" in strip("Hello. "))
        // No profanity or someone's name out of nowhere.
        for (t in listOf("What the ", "Shut the ", "Oh my ", "Hey ", "Thanks, ")) {
            for (w in strip(t)) {
                val i = dict.exactIndex(w.lowercase(Locale.US))
                assertTrue("$w after \"$t\"", i < 0 || !dict.offensive[i])
            }
        }
    }

    @Test fun contextCostsLittleTime() {
        val with = suggest()
        val without = suggest(withModel = false)
        val inputs = heldOut().take(300).flatMap { sent ->
            positions(sent).flatMap { (before, w) -> (1..minOf(3, w.length)).map { j -> before to w.substring(0, j) } }
        }
        fun time(s: Suggest): Double {
            repeat(2) { for ((b, t) in inputs.take(200)) s.suggest(t, null, true, emptySet(), before = TextContext.of(b)) }
            val t0 = System.nanoTime()
            for ((b, t) in inputs) s.suggest(t, null, true, emptySet(), before = TextContext.of(b))
            return (System.nanoTime() - t0) / 1e6 / inputs.size
        }
        val a = time(without)
        val b = time(with)
        println("  suggestions per keystroke: %.2f ms without the phrase model, %.2f ms with (%d calls)".format(a, b, inputs.size))
        assertTrue("the phrase model slowed typing down: $a -> $b ms", b < a * 2 + 1)
    }

    /**
     * Regression guards, just under the measured numbers. 1.8.1 (a hand-written list of ~115 follow-ons):
     * next word in the strip 12.4% (middle 6.9%), letters saved 44.8%, typos in context fixed 92.9% with
     * 6.8% wrong. 1.9.0 (phrase model): 29.5% (18.2%), 59.8%, 96.2% with 3.6% wrong.
     */
    private val MIN_IN_STRIP = 0.28
    private val MIN_MIDDLE = 0.17
    private val MIN_SAVED = 0.57
    private val MIN_FIXED = 0.955
    private val MAX_WRONG = 0.04
}
