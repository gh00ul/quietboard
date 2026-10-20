package dev.larryjones.quietboard

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale

/**
 * The one or two tokens right before a word, as the phrase model sees them: lowercase words,
 * [COMMA], [NUMBER], or [START] at the start of a sentence. Null where nothing useful is known
 * (after a colon or a dash, or nothing before the word at all).
 */
data class TextContext(val prev2: String?, val prev1: String?) {
    companion object {
        const val START = "<s>"
        const val COMMA = ","
        const val NUMBER = "<num>"
        val NONE = TextContext(null, null)

        /** Abbreviations whose period doesn't end the sentence ("Mr. Smith"), as in the model's builder. */
        private val ABBREV = setOf("mr", "mrs", "ms", "dr", "st", "jr", "sr", "vs", "prof", "sgt", "capt", "lt", "col", "gen", "mt", "ft")

        /** The context at the end of [text] (the text before the cursor, or before the word being typed). */
        fun of(text: CharSequence?): TextContext {
            if (text == null) return NONE
            val (prev1, end1) = tokenBefore(text, text.length)
            if (prev1 == null || prev1 == START) return TextContext(null, prev1)
            val (prev2, _) = tokenBefore(text, end1)
            return TextContext(prev2, prev1)
        }

        /** The token ending at [end] (spaces and quotes skipped) and where it starts, split as the builder splits text. */
        private fun tokenBefore(text: CharSequence, end: Int): Pair<String?, Int> {
            var i = end
            while (i > 0) {
                val c = text[i - 1]
                // Spaces (not new lines), quotes and brackets, and a lone apostrophe (an opening quote).
                if ((c.isWhitespace() && c != '\n') || c in IGNORED || (isApostrophe(c) && (i < 2 || !text[i - 2].isLetterOrDigit()))) {
                    i--
                    continue
                }
                break
            }
            if (i == 0) return START to 0
            val c = text[i - 1]
            when {
                c.isLetterOrDigit() || isApostrophe(c) -> {
                    // The whole run: letters, digits, apostrophes, and "." ":" "," between digits (5:30, 3.5).
                    var s = i
                    while (s > 0) {
                        val p = text[s - 1]
                        if (p.isLetterOrDigit() || isApostrophe(p)) { s--; continue }
                        if (p in ".:," && s > 1 && text[s - 2].isDigit() && text[s].isDigit()) { s--; continue }
                        break
                    }
                    val run = text.subSequence(s, i)
                    val first = run.firstOrNull { !isApostrophe(it) }
                    return when {
                        first == null -> null to s
                        // "5pm", "4th", "10:30": a number. "mp3", "covid19": nothing the model knows.
                        first.isDigit() -> NUMBER to s
                        run.any { it.isDigit() } -> null to s
                        else -> run.toString().lowercase(Locale.US).replace('\u2019', '\'').replace('\u2018', '\'').trim('\'').ifEmpty { null } to s
                    }
                }
                c == ',' -> return COMMA to (i - 1)
                c == '!' || c == '?' || c == '\n' -> return START to (i - 1)
                c == '.' -> {
                    if (i > 1 && text[i - 2] == '.') return null to (i - 1) // "..." trails off
                    var s = i - 1
                    while (s > 0 && text[s - 1].isLetter()) s--
                    // "Mr." isn't the end of a sentence: the abbreviation is the context. ("1st." is.)
                    val w = text.subSequence(s, i - 1).toString().lowercase(Locale.US)
                    return if (w in ABBREV && (s == 0 || !text[s - 1].isLetterOrDigit())) w to s else START to (i - 1)
                }
                // An emoji usually closes a thought: what comes next starts afresh.
                Character.isSurrogate(c) || c == '\uFE0F' || c == '\u200D' ||
                    Character.getType(c) == Character.OTHER_SYMBOL.toInt() -> return START to (i - 1)
                else -> return null to (i - 1)
            }
        }

        private const val IGNORED = "\"“”«»()[]{}*_"

        private fun isApostrophe(c: Char) = c == '\'' || c == '’' || c == '‘'
    }
}

/**
 * Built-in phrase model (assets/phrases_en.bin, made by tools/BuildPhrases.java from everyday dialogue):
 * how likely each word is after the one or two before it. It drives next-word predictions and gives
 * autocorrect and glide typing a sense of what fits ("I'm going t" -> to, "see you la" -> later).
 *
 * Read-only once loaded, so the glide decoder's thread can use it too.
 */
class PhraseModel private constructor(
    private val dict: Dictionary,
    /** Model id -> -ln P(word), in tenths of a nat. */
    private val uni: ByteArray,
    /** Model id -> dictionary index (-1 for the markers). */
    private val dictIndexOf: IntArray,
    /** Dictionary index -> model id, or -1. */
    private val idOfDict: IntArray,
    private val biStart: IntArray,
    private val biBo: ByteArray,
    private val biWord: IntArray,
    private val biLp: ByteArray,
    /** Per bigram context ([biTopStart]), positions of its likeliest followers, likeliest first. */
    private val biTopStart: IntArray,
    private val biTop: IntArray,
    private val triKey: LongArray,
    private val triStart: IntArray,
    private val triBo: ByteArray,
    private val triWord: IntArray,
    private val triLp: ByteArray,
) {
    val size get() = biWord.size + triWord.size

    private fun tokenId(token: String?): Int = when (token) {
        null -> -1
        TextContext.START -> START
        TextContext.COMMA -> COMMA
        TextContext.NUMBER -> NUMBER
        else -> dict.exactIndex(token).let { if (it < 0) -1 else idOfDict[it] }
    }

    /** What the model expects after [ctx]; [Context.NONE]-like (no opinion) when it knows nothing. */
    fun context(ctx: TextContext): Context {
        val b = tokenId(ctx.prev1)
        if (b < 0) return Context(-1, -1)
        val a = tokenId(ctx.prev2)
        var tri = -1
        if (a >= 0) {
            val key = (a.toLong() shl BITS) or b.toLong()
            var lo = 0
            var hi = triKey.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val k = triKey[mid]
                if (k < key) lo = mid + 1 else if (k > key) hi = mid - 1 else { tri = mid; break }
            }
        }
        return Context(b, tri)
    }

    /** Model id of a dictionary entry, or -1. */
    fun idOf(dictIndex: Int): Int = if (dictIndex in idOfDict.indices) idOfDict[dictIndex] else -1

    /** One context's view: log-probabilities of the words that may come next. Immutable. */
    inner class Context internal constructor(private val b: Int, private val tri: Int) {
        /** Whether the model has any opinion here. */
        val known get() = b >= 0 && (tri >= 0 || biStart[b + 1] > biStart[b])

        private val triBackoff = if (tri >= 0) triBo[tri] / 10f else 0f
        private val biBackoff = if (b >= 0 && biStart[b + 1] > biStart[b]) biBo[b] / 10f else 0f

        /** ln P(word | context) for model id [id]. */
        fun logProb(id: Int): Float {
            var bo = 0f
            if (tri >= 0) {
                val k = find(triWord, triStart[tri], triStart[tri + 1], id)
                if (k >= 0) return -(triLp[k].toInt() and 0xFF) / 10f
                bo += triBackoff
            }
            if (b >= 0) {
                val k = find(biWord, biStart[b], biStart[b + 1], id)
                if (k >= 0) return bo - (biLp[k].toInt() and 0xFF) / 10f
                bo += biBackoff
            }
            return bo - (uni[id].toInt() and 0xFF) / 10f
        }

        /**
         * How much likelier the dictionary entry [dictIndex] is here than in general, in nats (its log
         * probability ratio): positive for words that fit ("to" after "going"), negative for ones that
         * don't ("the" after "the"), 0 without context.
         */
        fun boost(dictIndex: Int): Float {
            if (b < 0) return 0f
            val id = idOf(dictIndex)
            if (id < FIRST_WORD) return triBackoff + biBackoff
            return logProb(id) + (uni[id].toInt() and 0xFF) / 10f
        }

        /** The likeliest next words: (dictionary index, ln P), best first. [keep] filters by dictionary index. */
        fun top(n: Int, keep: (Int) -> Boolean): List<Pair<Int, Float>> {
            if (b < 0) return emptyList()
            val seen = HashSet<Int>()
            val out = ArrayList<Pair<Int, Float>>()
            fun consider(id: Int): Boolean {
                if (!seen.add(id)) return false
                val di = dictIndexOf[id]
                if (di < 0 || !keep(di)) return false
                out += di to logProb(id)
                return true
            }
            if (tri >= 0) for (k in triStart[tri] until triStart[tri + 1]) consider(triWord[k])
            // Words only the bigram list has all share one backoff, so their order is the list's: its
            // likeliest few (found at load) are enough, unless the filter or the trigram list took too many.
            var fresh = 0
            for (k in biTopStart[b] until biTopStart[b + 1]) {
                val id = biWord[biTop[k]]
                val inTri = tri >= 0 && find(triWord, triStart[tri], triStart[tri + 1], id) >= 0
                if (consider(id) && !inTri) fresh++
            }
            if (fresh < n && biStart[b + 1] - biStart[b] > biTopStart[b + 1] - biTopStart[b]) {
                for (k in biStart[b] until biStart[b + 1]) consider(biWord[k])
            }
            out.sortByDescending { it.second }
            return if (out.size > n) out.subList(0, n) else out
        }
    }

    companion object {
        const val START = 0
        const val COMMA = 1
        const val NUMBER = 2
        const val FIRST_WORD = 3
        private const val BITS = 20

        /** How many of each context's likeliest bigram followers [Context.top] looks at first. */
        private const val TOP = 24

        /** For each context, the positions of its [TOP] likeliest followers (smallest -ln P first). */
        private fun likeliest(start: IntArray, lp: ByteArray, v: Int): Pair<IntArray, IntArray> {
            val topStart = IntArray(v + 1)
            var total = 0
            for (c in 0 until v) { topStart[c] = total; total += minOf(TOP, start[c + 1] - start[c]) }
            topStart[v] = total
            val top = IntArray(total)
            val buf = IntArray(TOP)
            for (c in 0 until v) {
                var n = 0
                for (k in start[c] until start[c + 1]) {
                    val q = lp[k].toInt() and 0xFF
                    if (n == TOP && q >= (lp[buf[n - 1]].toInt() and 0xFF)) continue
                    // Insert into the small sorted buffer, dropping the worst when full.
                    var j = if (n < TOP) n++ else n - 1
                    while (j > 0 && (lp[buf[j - 1]].toInt() and 0xFF) > q) { buf[j] = buf[j - 1]; j-- }
                    buf[j] = k
                }
                System.arraycopy(buf, 0, top, topStart[c], n)
            }
            return topStart to top
        }

        private fun find(words: IntArray, from: Int, to: Int, id: Int): Int {
            var lo = from
            var hi = to - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val w = words[mid]
                if (w < id) lo = mid + 1 else if (w > id) hi = mid - 1 else return mid
            }
            return -1
        }

        fun load(ctx: android.content.Context, dict: Dictionary): PhraseModel? =
            runCatching { ctx.assets.open("phrases_en.bin").use { fromStream(it, dict) } }.getOrNull()

        fun fromStream(stream: InputStream, dict: Dictionary): PhraseModel {
            val bytes = stream.readBytes()
            val d = DataInputStream(ByteArrayInputStream(bytes))
            val magic = ByteArray(4).also { d.readFully(it) }
            if (String(magic, Charsets.US_ASCII) != "QBPM" || d.readInt() != 1) throw IOException("not a phrase model")
            val v = d.readInt()
            val dictIndexOf = IntArray(v) { -1 }
            val idOfDict = IntArray(dict.size) { -1 }
            for (id in FIRST_WORD until v) {
                val key = d.readUTF()
                val pref = dict.exactIndex(key)
                if (pref < 0) continue // not in this word list: the model just won't suggest it
                dictIndexOf[id] = pref
                var i = dict.lowerBound(key)
                while (i < dict.size && dict.keys[i] == key) { idOfDict[i] = id; i++ }
            }
            val uni = ByteArray(v)
            d.readFully(uni, FIRST_WORD, v - FIRST_WORD)

            fun varint(): Int {
                var shift = 0
                var r = 0
                while (true) {
                    val b = d.readUnsignedByte()
                    r = r or ((b and 0x7F) shl shift)
                    if (b < 0x80) return r
                    shift += 7
                }
            }

            // Bigram contexts.
            val nBi = d.readInt()
            val biStart = IntArray(v + 1)
            val biBo = ByteArray(v)
            var words = IntArray(1 shl 16)
            var probs = ByteArray(1 shl 16)
            var size = 0
            fun readFollowers() {
                val n = varint()
                if (size + n > words.size) {
                    val cap = maxOf(words.size * 2, size + n)
                    words = words.copyOf(cap); probs = probs.copyOf(cap)
                }
                var w = 0
                for (k in 0 until n) { w += varint(); words[size + k] = w }
                d.readFully(probs, size, n)
                size += n
            }
            var ctx = 0
            var filled = 0
            repeat(nBi) {
                ctx += varint()
                while (filled <= ctx) biStart[filled++] = size
                biBo[ctx] = d.readByte()
                readFollowers()
            }
            while (filled <= v) biStart[filled++] = size
            val biWord = words.copyOf(size)
            val biLp = probs.copyOf(size)

            // Trigram contexts.
            val nTri = d.readInt()
            val triKey = LongArray(nTri)
            val triStart = IntArray(nTri + 1)
            val triBo = ByteArray(nTri)
            words = IntArray(1 shl 16); probs = ByteArray(1 shl 16); size = 0
            var a = 0
            var b = 0
            for (t in 0 until nTri) {
                val da = varint()
                val bb = varint()
                if (da == 0) b += bb else { a += da; b = bb }
                triKey[t] = (a.toLong() shl BITS) or b.toLong()
                triStart[t] = size
                triBo[t] = d.readByte()
                readFollowers()
            }
            triStart[nTri] = size
            val (topStart, top) = likeliest(biStart, biLp, v)
            return PhraseModel(dict, uni, dictIndexOf, idOfDict, biStart, biBo, biWord, biLp, topStart, top,
                triKey, triStart, triBo, words.copyOf(size), probs.copyOf(size))
        }
    }
}
