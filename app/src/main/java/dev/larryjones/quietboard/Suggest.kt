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
        val singular = when {
            w.endsWith("ies") -> w.dropLast(3) + "y" // parties -> party
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

    fun suggest(typed: String, prev: String?, allowAutoCorrect: Boolean, rejected: Set<String>): SuggestionResult {
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
        val userKnown = history.isKnown(lower)
        // Typed in lowercase, but the only dictionary form is a rare name/acronym ("gte" for GTE, "tue"):
        // most likely a typo, so look for corrections too.
        val rareCapsOnly = exactIdx >= 0 && typed == lower && dict.words[exactIdx] != lower && dict.freqs[exactIdx] < 110
        // "parents'": the plural possessive of a known word.
        val possessive = lower.length > 2 && lower.endsWith("s'") && dict.exactIndex(lower.dropLast(1)) >= 0
        val known = (exactIdx >= 0 && !rareCapsOnly) || userKnown || shortcut != null || possessive

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
                val s = dict.freqs[i] + userBonus(w) + pairBonus(prevLower, w) - (dict.keys[i].length - lower.length) * 1.5f
                top.add(i, s)
            }
        }
        top.forEach { i, s -> offer(dict.words[i], s) }
        history.completions(lower) { w, c ->
            if (c >= 2 && !blocked(w)) offer(w, 120f + 10f * minOf(c, 10) + pairBonus(prevLower, w))
        }

        // 2) Typo corrections.
        var bestFix: String? = null
        var bestFixScore = -Float.MAX_VALUE
        // Once the user has kept a word (undid its correction), whitelist/case fixes leave it alone too.
        val whitelisted = if (userKnown) null else dict.whitelistFor(lower)
        val caseFixed = if (userKnown) null else caseFix(exactIdx, typed)
        if (!known && lower.length >= 2) {
            val maxDist = when (lower.length) { 2 -> 0.75f; 3, 4 -> 1.0f; else -> 1.6f }
            dict.corrections(lower, maxDist) { i, d ->
                if (hidden(i)) return@corrections
                val w = dict.words[i]
                // Rare all-caps acronyms are noise as targets when typing in lowercase ("lmk" -> "LM").
                if (typed == lower && w.length > 1 && dict.freqs[i] < 120 && w.all { !it.isLetter() || it.isUpperCase() }) {
                    return@corrections
                }
                val s = dict.freqs[i] + userBonus(w) + pairBonus(prevLower, w) - d * 120f
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
        if (allowAutoCorrect && lower !in rejected && !mixedCase && !stretched && shortcut == null) {
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

    /** Next-word predictions learned from your own typing. */
    fun predict(prev: String?, capitalize: Boolean): List<SuggestionItem?> {
        if (prev == null) return emptyList()
        val p = history.predict(prev.lowercase(Locale.US), 6)
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

    private fun userBonus(w: String): Float {
        val c = history.count(w.lowercase(Locale.US))
        return if (c <= 0) 0f else 20f + 8f * minOf(c, 8)
    }

    private fun pairBonus(prevLower: String?, w: String): Float {
        val c = history.pairCount(prevLower, w)
        return if (c <= 0) 0f else 30f * minOf(c, 3)
    }

    companion object {
        private val STRETCHED = Regex("(\\p{L})\\1\\1")
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
