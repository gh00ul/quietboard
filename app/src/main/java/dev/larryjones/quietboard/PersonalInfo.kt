package dev.larryjones.quietboard

/**
 * Your own email addresses and street addresses, saved in Settings, offered in the suggestion strip
 * as you start typing them. They're kept with your text shortcuts (encrypted storage, no backups) and
 * never shown on the lock screen or in password fields.
 *
 * In an email or address field they're offered as soon as the field is empty and win the strip once
 * what you've typed matches. In any other text field one shows at the right of the strip after three
 * matching characters, and takes the strip over once you type an "@" (you're clearly writing an email).
 */
class PersonalInfo(emails: List<String>, addresses: List<String>) {
    val emails = emails.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    val addresses = addresses.map { oneLine(it) }.filter { it.isNotEmpty() }.distinct()
    val isEmpty get() = emails.isEmpty() && addresses.isEmpty()

    /** What a field asks for, as far as the keyboard can tell. */
    enum class Field {
        EMAIL,
        /** A whole postal address. */
        ADDRESS,
        /** Only the street line ("Address line 1"): the part of an address before its first comma. */
        STREET,
        OTHER,
    }

    /**
     * One of your saved values. Picking it replaces [typed], the text right before the cursor that
     * matched its start (empty when offered on an empty field). [strong] means you're clearly typing
     * it (its own kind of field, or an "@" typed), so it may take the strip over.
     */
    class Match(val value: String, val typed: String, val strong: Boolean)

    /** Your values that the end of [before] (the text before the cursor) starts, best first, at most [max]. */
    fun matches(before: CharSequence, field: Field, max: Int = 3): List<Match> {
        if (isEmpty || max <= 0) return emptyList()
        val out = ArrayList<Match>()
        if (field == Field.EMAIL || field == Field.OTHER) emailMatches(before, field, out)
        if (field != Field.EMAIL) addressMatches(before, field, out)
        return out.distinctBy { it.value }.take(max)
    }

    private fun emailMatches(before: CharSequence, field: Field, out: MutableList<Match>) {
        if (emails.isEmpty()) return
        var i = before.length
        while (i > 0 && !before[i - 1].isWhitespace()) i--
        while (i < before.length && before[i] in OPENERS) i++
        val token = before.subSequence(i, before.length).toString()
        if (token.isEmpty()) {
            // An empty email field, or the next recipient after "a@b.com, ": all of them.
            if (field == Field.EMAIL) for (e in emails) out += Match(e, "", true)
            return
        }
        val strong = field == Field.EMAIL || '@' in token
        // Elsewhere three letters at least: "la" is far more likely "last" than "larry@...".
        if (!strong && token.length < MIN_OTHER) return
        for (e in emails) {
            if (e.length > token.length && e.startsWith(token, ignoreCase = true)) out += Match(e, token, strong)
        }
    }

    private fun addressMatches(before: CharSequence, field: Field, out: MutableList<Match>) {
        if (addresses.isEmpty()) return
        val values = if (field == Field.STREET) addresses.map { it.substringBefore(',').trim() }.distinct() else addresses
        // Addresses are one line: only the line the cursor is on counts.
        val line = before.subSequence(before.lastIndexOf('\n') + 1, before.length).toString()
        val strong = field == Field.ADDRESS || field == Field.STREET
        val found = ArrayList<Pair<Int, Match>>()
        for (v in values) {
            // The longest stretch ending at the cursor that starts a word and starts the address too
            // ("I live at 12" -> "12"; "1234 W Ma" -> all of it).
            var start = 0
            while (start < line.length) {
                val wordStart = start == 0 || line[start - 1].isWhitespace() || line[start - 1] in OPENERS
                if (wordStart && !line[start].isWhitespace()) {
                    val typed = line.substring(start)
                    if (v.length > typed.length && v.startsWith(typed, ignoreCase = true)) {
                        if (strong || typed.length >= MIN_OTHER) found += typed.length to Match(v, typed, strong)
                        break
                    }
                }
                start++
            }
            // A blank address field: offer it straight away.
            if (strong && line.isBlank() && found.none { it.second.value == v }) found += 0 to Match(v, "", true)
        }
        // Whatever matched the most of what you typed first; saved order breaks ties.
        found.sortedByDescending { it.first }.forEach { out += it.second }
    }

    companion object {
        /** Characters typed elsewhere before an email or address shows (outside its own kind of field). */
        const val MIN_OTHER = 3
        /** Opening brackets and quotes before an address aren't part of it ("<larry@..."). */
        private const val OPENERS = "([{<\"'“‘"

        /** Addresses are inserted on one line: line breaks become ", ". */
        fun oneLine(s: String): String =
            s.trim().split(Regex("\\s*\\n\\s*")).filter { it.isNotEmpty() }.joinToString(", ").replace(Regex("[ \\t]+"), " ")

        /** Loose check for something that looks like an email address (one "@", a dot in the domain, no spaces). */
        fun looksLikeEmail(s: String): Boolean = EMAIL.matches(s)

        private val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    }
}
