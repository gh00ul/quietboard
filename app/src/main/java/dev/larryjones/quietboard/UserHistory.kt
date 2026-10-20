package dev.larryjones.quietboard

import android.os.Handler
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Words and word pairs you actually type, stored as plain text in the app's private files dir.
 * Excluded from backups (see manifest). Nothing here is ever transmitted.
 *
 * Written on the main thread only. [count] is also read by the glide decoder's background thread,
 * which is why the word map is concurrent.
 */
class UserHistory(dir: File) {
    class Entry(var word: String, var count: Int)

    private val wordsFile = File(dir, "user_words.tsv")
    private val pairsFile = File(dir, "user_pairs.tsv")
    private val io = Executors.newSingleThreadExecutor()

    @Volatile private var words = ConcurrentHashMap<String, Entry>()
    private var pairs = HashMap<String, HashMap<String, Int>>()
    private var dirty = false
    /** False until the saved history is merged in: saving before that would overwrite it with a stub. */
    private var loaded = false
    /** Bumped by [clear]; a load that started before a clear is thrown away. */
    private var generation = 0
    /** Words forgotten before the saved history finished loading; the merge must not bring them back. */
    private val forgottenDuringLoad = HashSet<String>()
    /** Set by the save thread when writing failed (disk full...), so the next save tries again. */
    @Volatile private var writeFailed = false

    fun loadAsync(main: Handler) {
        val gen = generation
        io.execute {
            val w = HashMap<String, Entry>()
            val p = HashMap<String, HashMap<String, Int>>()
            runCatching {
                for (line in readLines(wordsFile)) {
                    val parts = line.split('\t')
                    if (parts.size == 2) {
                        val c = parts[1].toIntOrNull() ?: continue
                        w[parts[0].lowercase(Locale.US)] = Entry(parts[0], c)
                    }
                }
                for (line in readLines(pairsFile)) {
                    val parts = line.split('\t')
                    if (parts.size == 3) {
                        val c = parts[2].toIntOrNull() ?: continue
                        p.getOrPut(parts[0]) { HashMap() }[parts[1]] = c
                    }
                }
            }
            main.post {
                loaded = true
                if (gen != generation) return@post // cleared while loading: keep it cleared
                if (words.isNotEmpty() || pairs.isNotEmpty()) dirty = true // learned before load: save the merge
                for (f in forgottenDuringLoad) {
                    w.remove(f); p.remove(f)
                    for (m in p.values) m.keys.removeAll { it.equals(f, ignoreCase = true) }
                    dirty = true
                }
                forgottenDuringLoad.clear()
                // Merge anything learned before loading finished.
                for ((k, e) in words) w.getOrPut(k) { Entry(e.word, 0) }.count += e.count
                for ((k, m) in pairs) {
                    val dst = p.getOrPut(k) { HashMap() }
                    for ((w2, c) in m) dst[w2] = (dst[w2] ?: 0) + c
                }
                words = ConcurrentHashMap(w)
                pairs = p
            }
        }
    }

    fun count(lower: String): Int = words[lower]?.count ?: 0

    /** A word you've typed at least twice (or kept after undoing a correction) is trusted. */
    fun isKnown(lower: String): Boolean = count(lower) >= 2

    fun learn(prev: String?, word: String) {
        if (!isLearnable(word)) return
        val lower = word.lowercase(Locale.US)
        val e = words.getOrPut(lower) { Entry(word, 0) }
        e.count = minOf(e.count + 1, 1000)
        if (word == lower) e.word = word
        if (prev != null && isLearnable(prev)) {
            val pl = prev.lowercase(Locale.US)
            val m = pairs.getOrPut(pl) { HashMap() }
            m[word] = minOf((m[word] ?: 0) + 1, 1000)
            // Evict the weakest *other* follower. Evicting the overall minimum would usually pick the
            // word just added, so once a word had a full list nothing new could ever be learned after it.
            if (m.size > MAX_KEPT_FOLLOWERS) m.entries.filter { it.key != word }.minByOrNull { it.value }?.let { m.remove(it.key) }
            // Age the counts so old habits fade and new phrases can overtake them.
            if (m.values.sum() > PAIR_AGING_TOTAL) {
                val it = m.entries.iterator()
                while (it.hasNext()) {
                    val e = it.next()
                    val halved = e.value / 2
                    if (halved == 0 && e.key != word) it.remove() else e.setValue(maxOf(halved, 1))
                }
            }
        }
        dirty = true
        trim()
    }

    /** Mark a word as one you meant (e.g. you undid an autocorrection of it). */
    fun accept(word: String) {
        if (!isLearnable(word)) return
        val lower = word.lowercase(Locale.US)
        val e = words.getOrPut(lower) { Entry(word, 0) }
        if (e.count < 2) e.count = 2
        dirty = true
    }

    fun forget(word: String): Boolean {
        val lower = word.lowercase(Locale.US)
        if (!loaded) forgottenDuringLoad += lower
        var removed = words.remove(lower) != null
        if (pairs.remove(lower) != null) removed = true
        // Followers are stored as typed ("Hello" at a sentence start): match any case.
        for (m in pairs.values) if (m.keys.removeAll { it.equals(word, ignoreCase = true) }) removed = true
        if (removed) dirty = true
        return removed
    }

    /** Takes back one [learn] (the user undid an autocorrection to [word]). */
    fun unlearn(prev: String?, word: String) {
        val lower = word.lowercase(Locale.US)
        words[lower]?.let { e -> e.count -= 1; if (e.count <= 0) words.remove(lower) }
        if (prev != null) {
            val m = pairs[prev.lowercase(Locale.US)]
            val c = m?.get(word)
            if (m != null && c != null) { if (c <= 1) m.remove(word) else m[word] = c - 1 }
        }
        dirty = true
    }

    fun completions(prefixLower: String, out: (word: String, count: Int) -> Unit) {
        for ((k, e) in words) if (k.startsWith(prefixLower) && k != prefixLower) out(e.word, e.count)
    }

    fun pairCount(prevLower: String?, word: String): Int {
        if (prevLower == null) return 0
        return pairs[prevLower]?.get(word) ?: 0
    }

    fun predict(prevLower: String, limit: Int): List<String> {
        val m = pairs[prevLower] ?: return emptyList()
        return m.entries.filter { it.value >= 2 }.sortedByDescending { it.value }.take(limit).map { it.key }
    }

    fun clear() {
        generation++
        words = ConcurrentHashMap()
        pairs = HashMap()
        dirty = false
        io.execute { deleteFiles(wordsFile.parentFile!!) }
    }

    fun saveAsync() {
        if (!(dirty || writeFailed) || !loaded || io.isShutdown) return
        writeFailed = false
        dirty = false
        val w = words.values.map { it.word to it.count }
        val p = pairs.flatMap { (k, m) -> m.map { Triple(k, it.key, it.value) } }
        io.execute {
            val ok = writeAtomic(wordsFile, buildString { for ((word, c) in w) append(word).append('\t').append(c).append('\n') }) &&
                writeAtomic(pairsFile, buildString {
                    for ((a, b, c) in p) append(a).append('\t').append(b).append('\t').append(c).append('\n')
                })
            if (!ok) writeFailed = true
        }
    }

    /** Stops the save thread once queued saves have run. Call after the final [saveAsync]. */
    fun close() {
        io.shutdown()
    }

    private fun writeAtomic(f: File, text: String): Boolean = SafeFile.write(f, text.toByteArray())

    private fun readLines(f: File): List<String> = SafeFile.read(f)?.let { String(it).lines() } ?: emptyList()

    private fun trim() {
        if (words.size > 12_000) {
            val drop = words.entries.sortedBy { it.value.count }.take(2_000).map { it.key }
            drop.forEach { words.remove(it) }
        }
        if (pairs.size > 6_000) {
            val drop = pairs.entries.sortedBy { it.value.values.sum() }.take(1_000).map { it.key }
            drop.forEach { pairs.remove(it) }
        }
    }

    companion object {
        /** Deletes the saved history, temp copies included (used by Settings too). */
        fun deleteFiles(dir: File) {
            SafeFile.delete(File(dir, "user_words.tsv"))
            SafeFile.delete(File(dir, "user_pairs.tsv"))
        }

        const val MAX_FOLLOWERS = 24
        /** Room for newcomers: a new follower needs a few uses to get counted, not to be evicted at once. */
        const val MAX_KEPT_FOLLOWERS = 2 * MAX_FOLLOWERS
        const val PAIR_AGING_TOTAL = 400

        fun isLearnable(w: String): Boolean {
            if (w.isEmpty() || w.length > Dictionary.MAX_LEN) return false
            if (w.length == 1 && w.lowercase(Locale.US) != "i" && w.lowercase(Locale.US) != "a") return false
            if (!w[0].isLetter()) return false
            return w.all { it.isLetter() || it == '\'' }
        }
    }
}
