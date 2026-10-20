package dev.larryjones.quietboard

import java.util.Locale

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
    /** Built-in next words: previous word (lowercase, or [SENTENCE_START]) -> likely next words. */
    var nextWords: Map<String, List<String>> = emptyMap()
    /**
     * Frequency points for a candidate that commonly follows the previous word. Tuned on typos after
     * real word pairs: 20 fixes 97.4% of them (94.5% with none) and costs 0.2 points when the word
     * you meant is not a usual follow-on; bigger values help the first case and hurt the second.
     */
    internal var contextPoints = 20f

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

    /**
     * Suggestions for [typed]. Without [learned] (lock screen, incognito fields) nothing you've taught
     * the keyboard shows or counts: no learned words, completions or word-pair habits.
     */
    fun suggest(typed: String, prev: String?, allowAutoCorrect: Boolean, rejected: Set<String>, learned: Boolean = true): SuggestionResult {
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

        // Words that commonly follow the previous one ("I" + "wold" -> would, not wild).
        val follows = prevLower?.let { nextWords[it.replace('\u2019', '\'')] }?.mapTo(HashSet()) { it.lowercase(Locale.US) }
        fun ctx(w: String) = if (follows != null && w.lowercase(Locale.US) in follows) contextPoints else 0f

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
                val s = dict.freqs[i] + userBonus(w, learned) + pairBonus(prevLower, w, learned) + ctx(w) - (dict.keys[i].length - lower.length) * 1.5f
                top.add(i, s)
            }
        }
        top.forEach { i, s -> offer(dict.words[i], s) }
        if (learned) history.completions(lower) { w, c ->
            if (c >= 2 && !blocked(w)) offer(w, 120f + 10f * minOf(c, 10) + pairBonus(prevLower, w, true))
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
                val s = dict.freqs[i] + userBonus(w, learned) + pairBonus(prevLower, w, learned) + ctx(w) - d * EDIT_COST
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
     * Next-word predictions: what you usually type after [prev] (if [learned] is allowed here), then
     * common English follow-ons, so suggestions work from the first day. At a sentence start
     * ([prev] null, [sentenceStart] true) the common first words.
     */
    fun predict(prev: String?, capitalize: Boolean, learned: Boolean = true, sentenceStart: Boolean = false): List<SuggestionItem?> {
        val key = prev?.lowercase(Locale.US)?.replace('\u2019', '\'') ?: if (sentenceStart) SENTENCE_START else return emptyList()
        val mine = if (learned && prev != null) history.predict(key, 6) else emptyList()
        val p = (mine + nextWords[key].orEmpty())
            .map { canonical(it) }
            .filter { !blocked(it) }
            .distinctBy { it.lowercase(Locale.US) }
            .take(3)
        if (p.isEmpty()) return emptyList()
        fun c(w: String) = if (capitalize) w.replaceFirstChar { it.titlecase(Locale.US) } else w
        return listOf(
            p.getOrNull(1)?.let { SuggestionItem(c(it)) },
            SuggestionItem(c(p[0])),
            p.getOrNull(2)?.let { SuggestionItem(c(it)) },
        )
    }

    /**
     * How likely each word is right after [prev], 0..1 (lowercase keys): the built-in follow-ons (earlier
     * = likelier) and, if [learned], what you've typed after it. Built on the main thread and handed to
     * the glide decoder.
     */
    fun contextFor(prev: String?, sentenceStart: Boolean, learned: Boolean = true): Map<String, Float> {
        val key = prev?.lowercase(Locale.US)?.replace('’', '\'') ?: if (sentenceStart) SENTENCE_START else return emptyMap()
        val out = HashMap<String, Float>()
        nextWords[key]?.forEachIndexed { i, w -> out[w.lowercase(Locale.US)] = maxOf(0.4f, 1f - i * 0.06f) }
        if (learned && prev != null) history.followers(key) { w, c ->
            if (c >= 2) {
                val k = w.lowercase(Locale.US)
                out[k] = maxOf(out[k] ?: 0f, minOf(1f, 0.4f + 0.15f * c))
            }
        }
        return out
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

    private fun pairBonus(prevLower: String?, w: String, learned: Boolean): Float {
        if (!learned) return 0f
        val c = history.pairCount(prevLower, w)
        return if (c <= 0) 0f else 30f * minOf(c, 3)
    }

    companion object {
        const val SENTENCE_START = "<s>"

        /** Parses nextwords_en.txt: "prev<TAB>next<TAB>next...". */
        fun parseNextWords(lines: Sequence<String>): Map<String, List<String>> {
            val out = HashMap<String, List<String>>()
            for (line in lines) {
                val parts = line.split('\t').filter { it.isNotBlank() }
                if (parts.size >= 2) out[parts[0].lowercase(Locale.US)] = parts.drop(1)
            }
            return out
        }

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
