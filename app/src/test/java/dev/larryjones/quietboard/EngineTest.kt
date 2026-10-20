package dev.larryjones.quietboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.random.Random

class EngineTest {
    companion object {
        lateinit var dict: Dictionary
        lateinit var history: UserHistory
        lateinit var suggest: Suggest
        lateinit var decoder: GestureDecoder

        @BeforeClass @JvmStatic
        fun load() {
            val assets = File("src/main/assets")
            val t0 = System.nanoTime()
            dict = File(assets, "words_en.txt").inputStream().use { w ->
                Dictionary.fromStreams(w, File(assets, "corrections_en.txt").inputStream())
            }
            println("dictionary: ${dict.size} words in ${(System.nanoTime() - t0) / 1_000_000} ms")
            history = UserHistory(Files.createTempDirectory("qb").toFile())
            suggest = Suggest(dict, history)
            decoder = GestureDecoder(dict, history)
        }
    }

    private fun ac(typed: String, prev: String? = null) = suggest.suggest(typed, prev, true, emptySet()).autoCorrect

    @Test fun correctsCommonTypos() {
        assertEquals("the", ac("teh"))
        assertEquals("don't", ac("dont"))
        assertEquals("I", ac("i"))
        assertEquals("I'm", ac("im"))
        assertEquals("can't", ac("cant"))
        assertEquals("hello", ac("helo"))
        assertEquals("because", ac("becuase"))
        assertEquals("The", ac("Teh"))
        for (w in listOf("recieve", "definately", "wierd", "tommorow", "jsut", "aboiut", "thnaks", "keybaord")) {
            println("$w -> ${ac(w)}  strip=${suggest.suggest(w, null, true, emptySet()).strip.map { it?.display }}")
        }
    }

    @Test fun leavesRealWordsAlone() {
        assertNull(ac("hello"))
        assertNull(ac("their"))
        assertNull(ac("its"))
        assertNull(ac("well"))
        assertNull(ac("iPhone"))
    }

    @Test fun newFollowersAreLearnedEvenWhenTheListIsFull() {
        val h = UserHistory(Files.createTempDirectory("qb").toFile())
        // "the" already has a full list of well-used followers.
        repeat(UserHistory.MAX_FOLLOWERS) { i -> repeat(3) { h.learn("the", "word" + ('a' + i)) } }
        repeat(2) { h.learn("the", "newcomer") }
        assertTrue("a new phrase must be able to join", "newcomer" in h.predict("the", 30))
        // Aging keeps counts bounded so habits can change.
        repeat(2000) { h.learn("the", "wordz") }
        assertTrue(h.pairCount("the", "wordz") <= UserHistory.PAIR_AGING_TOTAL)
        assertEquals("wordz", h.predict("the", 1).single())
    }

    @Test fun respectsRejection() {
        assertNull(suggest.suggest("teh", null, true, setOf("teh")).autoCorrect)
    }

    private fun stripWords(t: String) = suggest.suggest(t, null, true, emptySet()).strip.mapNotNull { it?.word }

    @Test fun knowsSlangAndSwears() {
        for (w in listOf("lol", "lmao", "lmfao", "idk", "tbh", "ngl", "bruh", "omg", "shit", "fuck", "fucking", "damn", "wtf", "ass", "bitch")) {
            assertNull("$w must be left alone", ac(w))
        }
        println("lma -> ${stripWords("lma")}, shi -> ${stripWords("shi")}, fuc -> ${stripWords("fuc")}, bul -> ${stripWords("bulls")}")
        assertTrue("lmao" in stripWords("lma"))
        assertNull("no rare-acronym autocorrect", ac("lma"))
        assertTrue("shit" in stripWords("shi"))
        assertTrue("fuck" in stripWords("fuc"))
        assertEquals("lmao", ac("lmoa"))
        assertEquals("shit", ac("shjt"))
        assertNull("stretched words are on purpose", ac("lmaooo"))
        assertNull(ac("shiiit"))
        assertNull(ac("nooooo"))
        suggest.blockOffensive = true
        try {
            assertTrue("shit" !in stripWords("shi"))
            assertNull("blocking hides swears but never corrects them away", ac("shit"))
        } finally {
            suggest.blockOffensive = false
        }
    }

    @Test fun shortcutsAndEmoji() {
        suggest.shortcuts = mapOf("omw" to "On my way!")
        suggest.emojiKeywords = mapOf("pizza" to "\uD83C\uDF55")
        try {
            val r = suggest.suggest("omw", null, true, emptySet())
            assertNull("shortcut must not be autocorrected", r.autoCorrect)
            assertEquals("On my way!", r.strip[1]?.word)
            val p = suggest.suggest("pizza", null, true, emptySet())
            assertEquals("\uD83C\uDF55", p.strip[2]?.word)
            assertTrue(p.strip[2]!!.emoji)
            assertEquals("plural", "\uD83C\uDF55", suggest.suggest("pizzas", null, true, emptySet()).strip[2]?.word)
            val typo = suggest.suggest("piza", null, true, emptySet())
            assertEquals("pizza", typo.autoCorrect)
            assertEquals("emoji follows the corrected word", "\uD83C\uDF55", typo.strip[2]?.word)
            suggest.emojiEnabled = false
            assertTrue(suggest.suggest("pizza", null, true, emptySet()).strip.none { it?.emoji == true })
        } finally {
            suggest.shortcuts = emptyMap(); suggest.emojiKeywords = emptyMap(); suggest.emojiEnabled = true
        }
    }

    @Test fun everydayWordsAreNotCorrectedAway() {
        // AOSP demoted or dropped these; they used to become "bay", "see", "ya", "am", "son", "named"...
        for (w in listOf("gay", "sex", "ha", "jam", "sin", "naked", "congrats", "grey", "ew", "covid", "jeez", "whew")) {
            assertNull("$w must be left alone", ac(w))
        }
        assertEquals("Tim", ac("tim"))
    }

    @Test fun neverSuggestedWordsStayHidden() {
        // Known (so not corrected), but never offered as a completion.
        assertTrue("sex" !in stripWords("se"))
    }

    @Test fun harderTyposAreFixed() {
        assertEquals("double transposition", "thanks", ac("tahnsk"))
        assertEquals("people", ac("poepel"))
        assertEquals("something", ac("somehtign"))
        assertEquals("what", ac("waht"))
        assertEquals("rare acronym is not a reason to keep a typo", "get", ac("gte"))
        assertEquals("he's", ac("hes"))
        assertEquals("a lot", ac("alot"))
        assertEquals("it'll", ac("itll"))
    }

    @Test fun leavesSpecialFormsAlone() {
        assertNull("accented words aren't English typos", ac("café"))
        assertNull(ac("naïve"))
        assertNull("plural possessive", ac("parents'"))
        assertNull("no recasing to an acronym", ac("la"))
        assertNull(ac("lmk"))
        assertEquals("inner capitals survive sentence case", "iPhone", Suggest.applyCase("iPhone", "Iphone"))
    }

    @Test fun stripHasNoCaseDuplicates() {
        for (w in listOf("have", "well", "job", "than")) {
            val words = stripWords(w).map { it.lowercase() }
            assertEquals("duplicates for $w: $words", words.distinct(), words)
        }
    }

    @Test fun blockedWordsStayBlockedFromHistory() {
        val h = UserHistory(Files.createTempDirectory("qb").toFile())
        val s = Suggest(dict, h)
        repeat(3) { h.learn(null, "fuck") }
        assertTrue("fuck" in s.suggest("fu", null, true, emptySet()).strip.mapNotNull { it?.word })
        s.blockOffensive = true
        assertTrue("fuck" !in s.suggest("fu", null, true, emptySet()).strip.mapNotNull { it?.word })
    }

    @Test fun emojiPlurals() {
        suggest.emojiKeywords = mapOf("party" to "\uD83C\uDF89", "box" to "\uD83D\uDCE6", "new" to "x")
        try {
            assertEquals("\uD83C\uDF89", suggest.emojiFor("parties"))
            assertEquals("\uD83D\uDCE6", suggest.emojiFor("boxes"))
            assertNull("news isn't the plural of new", suggest.emojiFor("news"))
        } finally {
            suggest.emojiKeywords = emptyMap()
        }
    }

    @Test fun forgetRemovesFollowersInAnyCase() {
        val h = UserHistory(Files.createTempDirectory("qb").toFile())
        repeat(3) { h.learn("say", "hello") }
        assertTrue(h.forget("Hello"))
        assertTrue(h.predict("say", 5).isEmpty())
    }

    @Test fun builtInNextWordsWorkFromDayOne() {
        val h = UserHistory(Files.createTempDirectory("qb").toFile())
        val s = Suggest(dict, h)
        s.nextWords = File("src/main/assets/nextwords_en.txt").useLines { Suggest.parseNextWords(it) }
        fun words(prev: String?, start: Boolean = false, learned: Boolean = true) =
            s.predict(prev, false, learned, start).mapNotNull { it?.word }
        assertTrue(words("thank").contains("you"))
        assertTrue("I" in words(null, start = true))
        assertTrue("nothing after a comma-ish non-word", words(null).isEmpty())
        // Your own habit comes first, but only where learning is allowed.
        repeat(3) { h.learn("thank", "goodness") }
        assertEquals("goodness", s.predict("thank", false).getOrNull(1)?.word)
        assertTrue("goodness" !in words("thank", learned = false))
    }

    @Test fun nextWordsAssetIsSane() {
        val lines = File("src/main/assets/nextwords_en.txt").readLines().filter { it.isNotBlank() }
        assertTrue(lines.size > 100)
        for (l in lines) {
            val parts = l.split('\t')
            assertTrue("bad line: $l", parts.size >= 2 && parts.none { it.isBlank() || ' ' in it })
            assertEquals("keys are lowercase: $l", parts[0].lowercase(), parts[0])
        }
    }

    @Test fun brandsAndContractions() {
        assertEquals("YouTube", ac("youtube"))
        assertEquals("Instagram", ac("instagram"))
        assertEquals("Lyft is recased, not \"corrected\" to lift", "Lyft", ac("lyft"))
        assertEquals("iOS", ac("ios"))
        assertTrue("don't" in stripWords("don"))
        assertEquals("I'm", ac("im"))
        assertNull("all caps is deliberate: ID stays ID", ac("ID"))
        assertNull(ac("IM"))
        assertEquals("and", ac("amd"))
        assertEquals("would", ac("wold"))
    }

    @Test fun shortcutsWithDigitsExpand() {
        suggest.shortcuts = mapOf("gr8" to "great", "b4" to "before")
        try {
            assertEquals("great", suggest.suggest("gr8", null, true, emptySet()).strip[1]?.word)
            assertEquals("before", suggest.suggest("B4", null, true, emptySet()).strip[1]?.word)
            assertNull(suggest.suggest("gr8", null, true, emptySet()).autoCorrect)
        } finally {
            suggest.shortcuts = emptyMap()
        }
    }

    @Test fun emojiKeywordAssetIsSane() {
        val lines = File("src/main/assets/emoji_keywords.txt").readLines()
        val map = lines.associate { it.substringBefore('\t') to it.substringAfter('\t') }
        assertEquals("\uD83C\uDF55", map["pizza"])
        assertTrue(map.size > 500)
        assertTrue("stopwords must not map", map.keys.none { it in setOf("the", "and", "with", "face") })
    }

    @Test fun completesPrefixes() {
        val strip = suggest.suggest("tha", null, true, emptySet()).strip.mapNotNull { it?.word }
        println("tha -> $strip")
        assertTrue(strip.any { it == "that" || it == "thanks" || it == "than" })
    }

    @Test fun suggestionSpeed() {
        val words = listOf("teh", "helo", "becuase", "recieve", "keybaord", "a", "th", "somethign", "tomorow", "pls")
        repeat(3) { words.forEach { w -> suggest.suggest(w, null, true, emptySet()) } }
        val t0 = System.nanoTime()
        repeat(10) { words.forEach { w -> suggest.suggest(w, null, true, emptySet()) } }
        val perCall = (System.nanoTime() - t0) / 100 / 1000.0
        println("suggest(): %.2f ms per keystroke (desktop JVM)".format(perCall / 1000 * 1000 / 1000))
        assertTrue(perCall < 60_000)
    }

    // ---- glide typing on a synthetic 1000px-wide QWERTY ----
    private val kw = 100f
    private val kh = 140f
    private val cx = FloatArray(26) { Float.NaN }
    private val cy = FloatArray(26) { Float.NaN }

    init {
        "qwertyuiop".forEachIndexed { i, c -> cx[c - 'a'] = 50f + 100f * i; cy[c - 'a'] = 70f }
        "asdfghjkl".forEachIndexed { i, c -> cx[c - 'a'] = 100f + 100f * i; cy[c - 'a'] = 210f }
        "zxcvbnm".forEachIndexed { i, c -> cx[c - 'a'] = 200f + 100f * i; cy[c - 'a'] = 350f }
    }

    /** Simulated human swipe: off-center key hits, Catmull-Rom curve, rounded corners. */
    private fun sloppySwipe(word: String, rnd: java.util.Random, slop: Float): Pair<FloatArray, FloatArray> {
        val seq = StringBuilder()
        for (c in word.lowercase()) if (c in 'a'..'z' && (seq.isEmpty() || seq.last() != c)) seq.append(c)
        val px = seq.map { cx[it - 'a'] / kw + (rnd.nextGaussian() * slop).toFloat() }
        val py = seq.map { cy[it - 'a'] / kh + (rnd.nextGaussian() * slop).toFloat() }
        val dx = ArrayList<Float>(); val dy = ArrayList<Float>()
        for (i in 0 until px.size - 1) {
            val i0 = maxOf(i - 1, 0); val i3 = minOf(i + 2, px.size - 1)
            for (st in 0 until 10) {
                val t = st / 10f; val t2 = t * t; val t3 = t2 * t
                fun cr(p0: Float, p1: Float, p2: Float, p3: Float) =
                    0.5f * (2 * p1 + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3)
                dx += cr(px[i0], px[i], px[i + 1], px[i3]); dy += cr(py[i0], py[i], py[i + 1], py[i3])
            }
        }
        dx += px.last(); dy += py.last()
        val k = 2 + rnd.nextInt(4)
        val sx = FloatArray(dx.size); val sy = FloatArray(dy.size)
        for (i in dx.indices) {
            var ax = 0f; var ay = 0f
            for (j in i - k / 2 until i - k / 2 + k) { val q = j.coerceIn(0, dx.size - 1); ax += dx[q]; ay += dy[q] }
            sx[i] = ax / k; sy[i] = ay / k
        }
        sx[0] = dx[0]; sy[0] = dy[0]; sx[sx.size - 1] = dx.last(); sy[sy.size - 1] = dy.last()
        return FloatArray(sx.size) { sx[it] * kw } to FloatArray(sy.size) { sy[it] * kh }
    }

    @Test fun decodesSloppyGlides() {
        // Fixed everyday words, minus ones whose swipe is identical to a more common word
        // ("too" = "to", "off" = "of"): no keyboard can tell those apart without context.
        val list = ("the and that have for not with you this but his from they say her she will one all would there their what " +
            "out about who get which when make can like time just him know take people into year your good some could them see " +
            "other than then now look only come its over think also back after use two how our work first well way even new want " +
            "because any these give day most really thing typing especially something feels using lmao bruh gonna sorry thanks " +
            "maybe tomorrow phone message night today great love please never always little world school shit").split(" ")
        val bestBySeq = HashMap<String, Int>()
        for (i in dict.words.indices) {
            val sq = dict.gestureSeq[i] ?: continue
            val cur = bestBySeq[sq]
            if (cur == null || dict.freqs[i] > dict.freqs[cur]) bestBySeq[sq] = i
        }
        val words = list.filter { w ->
            val i = dict.exactIndex(w)
            val sq = if (i >= 0) dict.gestureSeq[i] else null
            sq != null && dict.words[bestBySeq[sq]!!].lowercase() == w
        }
        println("glide test set: ${words.size} words")
        for ((slop, need1, need3) in listOf(Triple(0.3f, 0.92, 0.96), Triple(0.4f, 0.82, 0.92))) {
            val rnd = java.util.Random(11)
            var top1 = 0; var top3 = 0
            val misses = ArrayList<String>()
            val t0 = System.nanoTime()
            for (w in words) {
                val (xs, ys) = sloppySwipe(w, rnd, slop)
                val out = decoder.decode(xs, ys, xs.size, cx, cy, kw, kh, 4)
                if (out.firstOrNull() == w) top1++ else misses += "$w->${out.take(3)}"
                if (w in out.take(3)) top3++
            }
            val ms = (System.nanoTime() - t0) / 1_000_000.0 / words.size
            println("sloppy glide slop=$slop: top1 $top1/${words.size}, top3 $top3/${words.size}, %.1f ms/word; misses ${misses.take(8)}".format(ms))
            assertTrue("top-1 too low at slop $slop: $top1", top1 >= words.size * need1)
            assertTrue("top-3 too low at slop $slop: $top3", top3 >= words.size * need3)
        }
        // The exact misses from Larry's screenshots.
        val rnd = java.util.Random(3)
        for ((w, need) in listOf("when" to 9, "especially" to 9, "typing" to 7)) {
            var ok = 0
            repeat(10) { val (xs, ys) = sloppySwipe(w, rnd, 0.3f); if (decoder.decode(xs, ys, xs.size, cx, cy, kw, kh, 4).firstOrNull() == w) ok++ }
            println("glide $w: $ok/10")
            assertTrue("$w decoded only $ok/10", ok >= need)
        }
    }
}
