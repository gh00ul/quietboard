package dev.larryjones.quietboard

import java.util.Locale
import kotlin.math.exp

data class SuggestionItem(
    val word: String,
    val display: String = word,
    val bold: Boolean = false,
    /** The exact text the user typed (shown in quotes). */
    val literal: Boolean = false,
    /** An emoji offered for the word being typed; picking it keeps the word and adds the emoji. */
    val emoji: Boolean = false,
)

/** Three strip slots (left, center, right) plus the word space will autocorrect to, if any. */
class SuggestionResult(val strip: List<SuggestionItem?>, val autoCorrect: String?)

class Suggest(private val dict: Dictionary, private val history: UserHistory) {
    /** Text shortcuts: lowercase shortcut -> expansion. */
    var shortcuts: Map<String, String> = emptyMap()
    var emojiKeywords: Map<String, String> = emptyMap()
    var emojiEnabled = true
    var blockOffensive = false
    /** Built-in phrase model (null until loaded): what usually comes after the words before. */
    var phrases: PhraseModel? = null
    /**
     * Frequency points per nat of phrase-model boost, and the boost's limits (see [PhraseModel.Context.boost]).
     * Word frequencies run ~13 points per e-fold, so 13 trusts the model fully. Tuned on typos in everyday
     * sentences (PredictionBenchmark's tuning half): fixed 93.2% / wrong 6.6% without context, 95.7% / 4.1%
     * at 8 points, 96.2% / 3.6% at 13 with these limits; keystroke savings peak there too.
     */
    internal var contextPoints = CONTEXT_POINTS
    internal var boostMin = BOOST_MIN
    internal var boostMax = BOOST_MAX

    /** Never offered: offensive words when blocking is on, and words that are only "known" (slurs etc.). */
    private fun hidden(i: Int) = dict.never[i] || (blockOffensive && dict.offensive[i])

    /** Same check for a word that came from the user's history rather than the dictionary. */
    private fun blocked(word: String): Boolean {
        val i = dict.exactIndex(word.lowercase(Locale.US))
        return i >= 0 && hidden(i)
    }

    /** The dictionary's spelling of a word (lowercase when it has one), e.g. not a sentence-start "Thanks". */
    private fun canonical(word: String): String {
        val i = dict.exactIndex(word.lowercase(Locale.US))
        return if (i >= 0) dict.words[i] else word
    }

    fun emojiFor(word: String): String? {
        if (!emojiEnabled) return null
        val w = word.lowercase(Locale.US)
        emojiKeywords[w]?.let { return it }
        if (w.length <= 3 || w in NOT_PLURALS) return null
        // "cookies" -> cookie, "parties" -> party
        if (w.endsWith("ies")) emojiKeywords[w.dropLast(1)]?.let { return it }
        val singular = when {
            w.endsWith("ies") -> w.dropLast(3) + "y"
            w.endsWith("ches") || w.endsWith("shes") || w.endsWith("sses") || w.endsWith("xes") -> w.dropLast(2)
            w.endsWith("s") && !w.endsWith("ss") -> w.dropLast(1)
            else -> return null
        }
        return emojiKeywords[singular]
    }

    private fun withEmoji(strip: List<SuggestionItem?>, typed: String): List<SuggestionItem?> {
        val e = emojiFor(typed) ?: return strip
        return listOf(strip[0], strip[1], SuggestionItem(e, emoji = true))
    }

    /** The context for [prev] alone, when the caller doesn't know more ([before] is null). */
    private fun contextOf(before: TextContext?, prev: String?, sentenceStart: Boolean = false): TextContext =
        before ?: TextContext(null, prev?.lowercase(Locale.US)?.replace('\u2019', '\'') ?: if (sentenceStart) TextContext.START else null)

    /**
     * Suggestions for [typed], after [prev] (the word before) or, better, [before] (the two tokens
     * before). Without [learned] (lock screen, incognito fields) nothing you've taught the keyboard
     * shows or counts: no learned words, completions or word-pair habits.
     */
    fun suggest(
        typed: String, prev: String?, allowAutoCorrect: Boolean, rejected: Set<String>, learned: Boolean = true,
        before: TextContext? = null,
    ): SuggestionResult {
        // A typographic apostrophe (don’t) looks words up like a plain one.
        val lower = typed.lowercase(Locale.US).replace('’', '\'')
        if (typed.any { it.isDigit() }) {
            // No dictionary words have digits, but shortcuts can ("gr8", "b4").
            val sc = shortcuts[lower]
            if (sc != null) {
                return SuggestionResult(listOf(SuggestionItem(typed, "“$typed”", literal = true), SuggestionItem(sc), null), null)
            }
            return SuggestionResult(listOf(null, SuggestionItem(typed), null), null)
        }
        val prevLower = prev?.lowercase(Locale.US)
        val exactIdx = dict.exactIndex(lower)
        val shortcut = shortcuts[lower]
        val userKnown = learned && history.isKnown(lower)
        // Kept in exactly this spelling: learned "I" (from corrected "i"s) mustn't stop "i" -> "I".
        val userKept = userKnown && history.spelling(lower)?.replace('\u2019', '\'') == typed.replace('\u2019', '\'')
        // Typed in lowercase, but the only dictionary form is a rare name/acronym ("gte" for GTE, "tue"):
        // most likely a typo, so look for corrections too.
        val rareCapsOnly = exactIdx >= 0 && typed == lower && dict.words[exactIdx] != lower && dict.freqs[exactIdx] < 110 &&
            !dict.hasLowercase(lower)
        // "parents'", or a closing quote ('hello'): a known word with an apostrophe after it. Not a
        // contraction being typed ("wouldn'" goes on to "wouldn't").
        val quoted = lower.length > 1 && lower.endsWith("'") && !dict.hasPrefix(lower) &&
            (dict.exactIndex(lower.dropLast(1)) >= 0 || (learned && history.isKnown(lower.dropLast(1))))
        val known = (exactIdx >= 0 && !rareCapsOnly) || userKnown || shortcut != null || quoted

        // What fits after the words before ("I" + "wold" -> would, not wild; "going t" -> to).
        val words2 = contextOf(before, prev)
        val here = phrases?.context(words2)?.takeIf { it.known }
        fun ctx(i: Int) = if (here == null) 0f else contextPoints * here.boost(i).coerceIn(boostMin, boostMax)

        // Keyed by lowercase so "Haven"/"haven" or a learned "Thanks" never show up twice.
        val scores = HashMap<String, Float>()
        val display = HashMap<String, String>()
        fun offer(word: String, score: Float) {
            val key = word.lowercase(Locale.US)
            if (key == lower) return
            val cur = scores[key]
            if (cur == null || score > cur) scores[key] = score
            if (key !in display) display[key] = canonical(word)
        }

        // 1) Completions of what you've typed so far.
        val top = BoundedTop(8)
        dict.forEachWithPrefix(lower) { i ->
            if (dict.keys[i] != lower && !hidden(i)) {
                val w = dict.words[i]
                val s = dict.freqs[i] + userBonus(w, learned) + pairBonus(prevLower, w, learned) + tripleBonus(words2, w, learned) + ctx(i) - (dict.keys[i].length - lower.length) * 1.5f
                top.add(i, s)
            }
        }
        top.forEach { i, s -> offer(dict.words[i], s) }
        if (learned) history.completions(lower) { w, c ->
            if (c >= 2 && !blocked(w)) offer(w, 120f + 10f * minOf(c, 10) + pairBonus(prevLower, w, true) + tripleBonus(words2, w, true))
        }

        // 2) Typo corrections.
        var bestFix: String? = null
        var bestFixScore = -Float.MAX_VALUE
        // Once the user has kept a word (undid its correction), whitelist/case fixes leave it alone too.
        // ...and all-caps input is deliberate ("ID", "IM"): don't turn it into "I'D".
        val allCaps = typed.length >= 2 && typed.all { !it.isLetter() || it.isUpperCase() }
        // A capitalised word typed as the dictionary spells it is a name ("in Bern"), not a typo of "been".
        val properNoun = exactIdx >= 0 && typed != lower && dict.hasSpelling(typed)
        val whitelisted = if (userKept || allCaps || properNoun) null else dict.whitelistFor(lower)
        val caseFixed = if (userKept) null else caseFix(exactIdx, typed)
        if (!known && lower.length >= 2) {
            val maxDist = when (lower.length) { 2 -> 0.75f; 3, 4 -> 1.0f; else -> 1.6f }
            dict.corrections(lower, maxDist) { i, d ->
                if (hidden(i)) return@corrections
                val w = dict.words[i]
                // Rare all-caps acronyms are noise as targets when typing in lowercase ("lmk" -> "LM").
                if (typed == lower && w.length > 1 && dict.freqs[i] < 120 && w.all { !it.isLetter() || it.isUpperCase() }) {
                    return@corrections
                }
                val s = dict.freqs[i] + userBonus(w, learned) + pairBonus(prevLower, w, learned) + tripleBonus(words2, w, learned) + ctx(i) - d * EDIT_COST
                offer(w, s - 5f)
                if (s > bestFixScore && dict.freqs[i] >= 40) { bestFixScore = s; bestFix = w }
            }
        }

        // 3) Decide whether space should autocorrect.
        val mixedCase = typed.drop(1).any { it.isUpperCase() } && !typed.all { !it.isLetter() || it.isUpperCase() }
        // "lmaooo", "nooo", "shiiit": stretched on purpose, so never "fix" it.
        val stretched = STRETCHED.containsMatchIn(lower)
        // Accented input ("café", "naïve", "señor") isn't in this English list: never "correct" it away.
        val accented = lower.any { it.code > 0x7F && it.isLetter() }
        var auto: String? = null
        // Short all-caps words are abbreviations ("IM", "ID", "TV"); longer caps-lock words still get fixed.
        val acronym = allCaps && typed.length <= 3
        if (allowAutoCorrect && lower !in rejected && !mixedCase && !stretched && !acronym && shortcut == null) {
            auto = when {
                whitelisted != null -> whitelisted
                caseFixed != null -> caseFixed
                accented -> null
                // A rare acronym typed in lowercase while still typing a longer word ("lma" -> "lmao"):
                // that's a completion, not a correction.
                !known -> bestFix?.takeUnless { rareCapsOnly && it.lowercase(Locale.US).startsWith(lower) }
                else -> null
            }?.let { applyCase(it, typed) }
            if (auto == typed) auto = null
        }

        // With autocorrect off, still offer the capitalised form ("I", "Monday").
        val extra = if (auto == null && caseFixed != null) listOf(applyCase(caseFixed, typed)) else emptyList()
        val ranked = (extra + scores.entries.sortedByDescending { it.value }.map { applyCase(display.getValue(it.key), typed) })
            .filter { it != auto && it != typed }
            .distinctBy { it.lowercase(Locale.US) }

        val strip: List<SuggestionItem?> = when {
            shortcut != null -> listOf(
                SuggestionItem(typed, "“$typed”", literal = true),
                SuggestionItem(shortcut),
                ranked.getOrNull(0)?.let { SuggestionItem(it) },
            )
            auto != null -> listOf(
                SuggestionItem(typed, "“$typed”", literal = true),
                SuggestionItem(auto, bold = true),
                ranked.getOrNull(0)?.let { SuggestionItem(it) },
            )
            known -> listOf(
                ranked.getOrNull(0)?.let { SuggestionItem(it) },
                SuggestionItem(typed),
                ranked.getOrNull(1)?.let { SuggestionItem(it) },
            )
            else -> listOf(
                SuggestionItem(typed, "“$typed”", literal = true),
                ranked.getOrNull(0)?.let { SuggestionItem(it) },
                ranked.getOrNull(1)?.let { SuggestionItem(it) },
            )
        }
        return SuggestionResult(withEmoji(strip, auto ?: typed), auto)
    }

    /**
     * Next-word predictions after [prev] (or, better, the two tokens [before]): what usually comes next
     * in everyday English, blended with what you type there (if [learned] is allowed here) so your
     * habits win once you've shown them. At a sentence start ([sentenceStart]) the common first words.
     */
    fun predict(
        prev: String?, capitalize: Boolean, learned: Boolean = true, sentenceStart: Boolean = false,
        before: TextContext? = null,
    ): List<SuggestionItem?> {
        val ctx = contextOf(before, prev, sentenceStart)
        val key = ctx.prev1 ?: return emptyList()
        val model = phrases?.context(ctx)
        // What you type after this word, and after these two words ("ok sounds" -> good).
        val personal = learned && key.first().isLetter()
        val (mine, total) = if (personal) habits { history.followers(key, it) } else NO_HABITS
        val (mine3, total3) = if (personal) habits { history.tripleFollowers(ctx.prev2, key, it) } else NO_HABITS
        // How much your own counts weigh against the built-in model: half once you've typed 2 words here.
        val mu = total / (total + PERSONAL_PRIOR)
        val mu3 = total3 / (total3 + PERSONAL_PRIOR)
        val display = HashMap<String, String>()
        val modelP = HashMap<String, Float>()
        model?.top(MODEL_CANDIDATES) { i -> predictable(i) }?.forEach { (i, lp) ->
            modelP[dict.keys[i]] = exp(lp)
            display[dict.keys[i]] = dict.words[i]
        }
        for ((k, wc) in mine.entries + mine3.entries) {
            if (k !in display && !blocked(wc.first)) display[k] = canonical(wc.first)
        }
        fun modelProb(k: String): Float = modelP[k] ?: run {
            val id = dict.exactIndex(k).let { if (it >= 0) phrases?.idOf(it) ?: -1 else -1 }
            if (model != null && id >= PhraseModel.FIRST_WORD) exp(model.logProb(id)) else 0f
        }
        val scores = HashMap<String, Float>()
        for (k in display.keys) {
            val pair = (1 - mu) * modelProb(k) + mu * (mine[k]?.second ?: 0) / maxOf(total, 1)
            scores[k] = (1 - mu3) * pair + mu3 * (mine3[k]?.second ?: 0) / maxOf(total3, 1)
        }
        val p = scores.entries.sortedByDescending { it.value }.take(3).map { display.getValue(it.key) }
        if (p.isEmpty()) return emptyList()
        fun c(w: String) = if (capitalize) w.replaceFirstChar { it.titlecase(Locale.US) } else w
        return listOf(
            p.getOrNull(1)?.let { SuggestionItem(c(it)) },
            SuggestionItem(c(p[0])),
            p.getOrNull(2)?.let { SuggestionItem(c(it)) },
        )
    }

    /**
     * Whether the built-in model may offer dictionary entry [i] unasked: not hidden, never profanity
     * (you can still type it, and once you have it's yours to predict), and not a name.
     */
    private fun predictable(i: Int): Boolean {
        if (hidden(i) || dict.offensive[i]) return false
        val w = dict.words[i]
        return w == "I" || w.startsWith("I'") || !w[0].isUpperCase() || dict.freqs[i] >= NAME_FREQ
    }

    /**
     * What the glide decoder should expect after [prev] (or the two tokens [before]): the phrase
     * model's view and, if [learned], what you've typed after that word. Built on the main thread and
     * handed to the decoder's.
     */
    fun contextFor(prev: String?, sentenceStart: Boolean, learned: Boolean = true, before: TextContext? = null): GlideContext {
        val ctx = contextOf(before, prev, sentenceStart)
        val out = HashMap<String, Float>()
        val key = ctx.prev1
        if (learned && key != null && key.first().isLetter()) {
            history.followers(key) { w, c ->
                if (c >= 2) {
                    val k = w.lowercase(Locale.US)
                    out[k] = maxOf(out[k] ?: 0f, minOf(1f, 0.4f + 0.15f * c))
                }
            }
            // A three-word habit is surer than a two-word one.
            history.tripleFollowers(ctx.prev2, key) { w, c ->
                if (c >= 2) {
                    val k = w.lowercase(Locale.US)
                    out[k] = maxOf(out[k] ?: 0f, minOf(1f, 0.5f + 0.2f * c))
                }
            }
        }
        return GlideContext(phrases?.context(ctx)?.takeIf { it.known }, out)
    }

    /** "i" -> "I", "monday" -> "Monday" when only the capitalised form is a word. */
    private fun caseFix(exactIdx: Int, typed: String): String? {
        if (exactIdx < 0) return null
        val w = dict.words[exactIdx]
        if (w == typed || typed != typed.lowercase(Locale.US)) return null
        // Only common capitalised words ("I", "Monday", "John"): never acronyms ("la" -> "LA", "lt" -> "LT"),
        // and not names that are everyday lowercase words too ("bob", "lee").
        if (w.length > 1 && (dict.freqs[exactIdx] < 110 || w.all { !it.isLetter() || it.isUpperCase() })) return null
        if (typed in NEVER_RECASE) return null
        return if (w.any { it.isUpperCase() }) w else null
    }

    private fun userBonus(w: String, learned: Boolean): Float {
        if (!learned) return 0f
        val c = history.count(w.lowercase(Locale.US))
        return if (c <= 0) 0f else 20f + 8f * minOf(c, 8)
    }

    private fun tripleBonus(ctx: TextContext, w: String, learned: Boolean): Float {
        if (!learned) return 0f
        val c = history.tripleCount(ctx.prev2, ctx.prev1, w)
        return if (c <= 0) 0f else 30f * minOf(c, 3)
    }

    /** Your followers from [each]: lowercase -> (as typed, count) for those typed twice or more, and the total. */
    private fun habits(each: ((String, Int) -> Unit) -> Unit): Pair<Map<String, Pair<String, Int>>, Int> {
        val mine = HashMap<String, Pair<String, Int>>()
        var total = 0
        each { w, c ->
            total += c
            val k = w.lowercase(Locale.US)
            if (c >= 2 && (mine[k]?.second ?: 0) < c) mine[k] = w to c
        }
        return mine to total
    }

    private fun pairBonus(prevLower: String?, w: String, learned: Boolean): Float {
        if (!learned) return 0f
        val c = history.pairCount(prevLower, w)
        return if (c <= 0) 0f else 30f * minOf(c, 3)
    }

    companion object {
        const val CONTEXT_POINTS = 13f
        const val BOOST_MIN = -3f
        const val BOOST_MAX = 8f
        /** Your counts after a word weigh as much as the built-in model once they add up to this. */
        private const val PERSONAL_PRIOR = 2f
        private val NO_HABITS = emptyMap<String, Pair<String, Int>>() to 0
        private const val MODEL_CANDIDATES = 8
        /** Capitalised words this common are everyday words ("God", "English"), not someone's name. */
        private const val NAME_FREQ = 150

        private val STRETCHED = Regex("(\\p{L})\\1\\1")
        /** Frequency points one edit costs (frequencies are ~13 points per e-fold of usage). */
        private const val EDIT_COST = 120f
        private val NEVER_RECASE = setOf("bob", "lee", "san", "los", "ira", "pat", "sue", "will", "mark", "bill", "rob")
        /** Words ending in "s" that aren't plurals of an emoji keyword ("news" isn't "new"). */
        private val NOT_PLURALS = setOf("news", "dies", "goods", "rights", "lens", "species", "series", "always", "glass")

        fun applyCase(word: String, typed: String): String {
            if (typed.length >= 2 && typed.all { !it.isLetter() || it.isUpperCase() }) return word.uppercase(Locale.US)
            // Words with their own inner capitals keep them ("iPhone", "eBay", "McDonald").
            if (word.drop(1).any { it.isUpperCase() }) return word
            if (typed.isNotEmpty() && typed[0].isUpperCase()) return word.replaceFirstChar { it.titlecase(Locale.US) }
            return word
        }
    }
}

/** Keeps the N best (index, score) pairs without sorting everything. */
private class BoundedTop(private val n: Int) {
    private val idx = IntArray(n)
    private val score = FloatArray(n)
    private var size = 0

    fun add(i: Int, s: Float) {
        if (size < n) {
            idx[size] = i; score[size] = s; size++
            return
        }
        var min = 0
        for (k in 1 until n) if (score[k] < score[min]) min = k
        if (s > score[min]) { idx[min] = i; score[min] = s }
    }

    fun forEach(fn: (Int, Float) -> Unit) {
        for (k in 0 until size) fn(idx[k], score[k])
    }
}
