import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * Builds app/src/main/assets/phrases_en.bin: the phrase model behind next-word predictions and the
 * context used by autocorrect and glide typing.
 *
 * Source: OpenSubtitles 2018 English (opensubtitles.org, via OPUS: P. Lison and J. Tiedemann, 2016),
 * the same everyday dialogue the word frequencies come from. Only counts of word pairs and triples
 * are kept, never sentences. Download (a prefix is enough; a truncated file is fine):
 *   https://object.pouta.csc.fi/OPUS-OpenSubtitles/v2018/mono/en.txt.gz
 *
 * Run from the repo root with a single-file launch (JDK 17+), giving it plenty of memory:
 *   java -Xmx20g tools/BuildPhrases.java --corpus en.txt.gz [--save-counts counts.bin]
 *   java -Xmx8g tools/BuildPhrases.java --load-counts counts.bin [--bigrams N --trigrams N]
 *
 * Model: absolute-discounting backoff over pruned bigrams and trigrams. An n-gram is kept when it
 * changes the prediction a lot on a lot of text: count x |log(P(w | context) / P(w | shorter context))|
 * (a cheap form of entropy pruning), so a few hundred thousand of them carry most of the benefit:
 * 500k + 300k by default (2.8 MB); on everyday sentences 900k + 900k (5.8 MB) predicts the next word
 * only half a point more often.
 * N-grams that are frequent only inside one or two films (character names, catchphrases) are dropped.
 * Tokens: words from words_en.txt (lowercase), plus sentence start, comma and number markers.
 */
public class BuildPhrases {
    static final int SENT = 0, COMMA = 1, NUM = 2, FIRST_WORD = 3;
    static final int BITS = 20;
    static final long MASK = (1L << BITS) - 1;
    static final int CHUNK_LINES = 1000;
    static final int COUNTS_VERSION = 3;
    static final double DISCOUNT = 0.6;
    static final Set<String> ABBREV = new HashSet<>(Arrays.asList(
        "mr", "mrs", "ms", "dr", "st", "jr", "sr", "vs", "prof", "sgt", "capt", "lt", "col", "gen", "mt", "ft"));

    /**
     * Contexts seen fewer times than this get no list of their own: a word seen 300 times in films says
     * little about what follows it in texts ("lol" -> "account" from one film).
     */
    static int minBiContext = 1000;
    static int minTriContext = 50;
    static Map<String, Integer> vocab = new HashMap<>();
    static List<String> names = new ArrayList<>();
    static long[] uni;
    static long total;
    /**
     * Times a sentence end or a break (colon, dash, "...") came right after each token, and after each
     * pair of tokens. That share isn't any word, so it must not go to the backoff for unseen words
     * (after "thank you" the sentence usually just ends; that's no reason to expect "know").
     */
    static long[] stop1;
    static Table stop2;
    static long stopTotal;
    static int chunks;
    static Table bi, tri;

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) opt.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        String root = opt.getOrDefault("root", ".");
        String words = opt.getOrDefault("words", root + "/app/src/main/assets/words_en.txt");
        String out = opt.getOrDefault("out", root + "/app/src/main/assets/phrases_en.bin");
        int nBi = Integer.parseInt(opt.getOrDefault("bigrams", "500000"));
        int nTri = Integer.parseInt(opt.getOrDefault("trigrams", "300000"));
        minBiContext = Integer.parseInt(opt.getOrDefault("min-bigram-context", "" + minBiContext));
        minTriContext = Integer.parseInt(opt.getOrDefault("min-trigram-context", "" + minTriContext));

        names.add("<s>"); names.add(","); names.add("<num>");
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(words), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                String k = line.substring(0, tab).toLowerCase(Locale.ROOT);
                if (!vocab.containsKey(k)) { vocab.put(k, names.size()); names.add(k); }
            }
        }
        uni = new long[names.size()];
        stop1 = new long[names.size()];

        if (opt.containsKey("load-counts")) {
            loadCounts(opt.get("load-counts"));
        } else {
            bi = new Table(27);
            tri = new Table(28);
            stop2 = new Table(26);
            long maxLines = Long.parseLong(opt.getOrDefault("max-lines", "" + Long.MAX_VALUE));
            Writer holdout = opt.containsKey("holdout")
                ? new OutputStreamWriter(new FileOutputStream(opt.get("holdout")), StandardCharsets.UTF_8) : null;
            count(opt.get("corpus"), maxLines, holdout);
            if (holdout != null) holdout.close();
            if (opt.containsKey("save-counts")) saveCounts(opt.get("save-counts"));
        }
        build(nBi, nTri, out, opt.get("dump"));
    }

    // ------------------------------------------------------------------ counting

    static int h1 = -1, h2 = -1;
    static boolean pendingStart = true;

    static void word(int id) {
        if (pendingStart) { uni[SENT]++; h2 = -1; h1 = SENT; pendingStart = false; }
        total++;
        uni[id]++;
        if (h1 >= 0) {
            bi.inc(((long) h1 << BITS) | id, chunks);
            // Comma and number followers too: they're part of what the context's probabilities add up to.
            if (h2 >= 0) tri.inc(((long) h2 << (2 * BITS)) | ((long) h1 << BITS) | id, chunks);
        }
        h2 = h1; h1 = id;
    }

    /** End of a sentence: counts as a token (the sentence end follows the last word). */
    static void end() {
        if (!pendingStart) stop();
        pendingStart = true; h1 = -1; h2 = -1;
    }

    /** Something with no useful context after it (a colon, a dash, "..."). */
    static void brk() { if (!pendingStart) { stop(); h1 = -1; h2 = -1; } }

    /** No word comes next: counts toward the context's "not a word" share. */
    static void stop() {
        total++;
        stopTotal++;
        if (h1 >= 0) {
            stop1[h1]++;
            if (h2 >= 0) stop2.inc(((long) h2 << BITS) | h1, chunks);
        }
    }

    static void unknown() {
        if (pendingStart) { uni[SENT]++; pendingStart = false; }
        total++;
        h1 = -1; h2 = -1;
    }

    static boolean isApos(char c) { return c == '\'' || c == '’' || c == '‘'; }

    static boolean ignorable(char c) {
        return "\"“”«»()[]{}*_♪♫#~<>|".indexOf(c) >= 0;
    }

    static void count(String corpus, long maxLines, Writer holdout) throws IOException {
        InputStream in = new FileInputStream(corpus);
        if (corpus.endsWith(".gz")) in = new GZIPInputStream(in, 1 << 16);
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 20);
        StringBuilder sb = new StringBuilder();
        long lineNo = 0;
        long t0 = System.currentTimeMillis();
        int held = 0;
        try {
            for (String s; (s = r.readLine()) != null && lineNo < maxLines; lineNo++) {
                chunks = (int) (lineNo / CHUNK_LINES);
                if (lineNo % 500 == 250) {
                    if (holdout != null && held < 200_000) { holdout.write(s); holdout.write('\n'); held++; }
                    end();
                    continue;
                }
                line(s, sb);
                if (lineNo % 10_000_000 == 0 && lineNo > 0) {
                    System.err.printf("%,d lines, %,d tokens, bigrams %,d, trigrams %,d, %ds%n",
                        lineNo, total, bi.size, tri.size, (System.currentTimeMillis() - t0) / 1000);
                }
            }
        } catch (EOFException e) {
            System.err.println("corpus ends early (truncated download): using what was read");
        }
        end();
        chunks = (int) (lineNo / CHUNK_LINES) + 1;
        System.err.printf("counted %,d lines, %,d tokens in %ds%n", lineNo, total, (System.currentTimeMillis() - t0) / 1000);
    }

    static void line(String s, StringBuilder sb) {
        int n = s.length();
        int i = 0;
        boolean dash = false;
        while (i < n && (s.charAt(i) == ' ' || s.charAt(i) == '-' || s.charAt(i) == '–' || s.charAt(i) == '—')) {
            if (s.charAt(i) != ' ') dash = true;
            i++;
        }
        // A line continues the previous sentence only if that one wasn't finished and this starts in lowercase.
        int f = i;
        while (f < n && !Character.isLetterOrDigit(s.charAt(f))) f++;
        boolean cont = !pendingStart && !dash && f < n && Character.isLowerCase(s.charAt(f));
        if (!cont) end();
        String last = null;
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isLetter(c) || (isApos(c) && i + 1 < n && Character.isLetter(s.charAt(i + 1)))) {
                sb.setLength(0);
                int j = i;
                if (isApos(c)) { sb.append('\''); j++; }
                while (j < n) {
                    char d = s.charAt(j);
                    if (Character.isLetter(d)) sb.append(Character.toLowerCase(d));
                    else if (isApos(d) && j + 1 < n && Character.isLetter(s.charAt(j + 1))) sb.append('\'');
                    else break;
                    j++;
                }
                if (j < n && isApos(s.charAt(j)) && vocab.containsKey(sb + "'")) { sb.append('\''); j++; }
                String w = sb.toString();
                Integer id = vocab.get(w);
                if (id == null && w.startsWith("'")) id = vocab.get(w.substring(1));
                if (id == null) unknown(); else word(id);
                last = w;
                i = j;
                // Glued digits ("mp3"): no useful context.
                if (i < n && Character.isDigit(s.charAt(i))) {
                    while (i < n && Character.isLetterOrDigit(s.charAt(i))) i++;
                    brk();
                }
                continue;
            }
            if (Character.isDigit(c)) {
                int j = i;
                while (j < n && (Character.isLetterOrDigit(s.charAt(j)) ||
                    ((s.charAt(j) == '.' || s.charAt(j) == ':' || s.charAt(j) == ',') && j + 1 < n && Character.isDigit(s.charAt(j + 1))))) j++;
                word(NUM);
                last = null;
                i = j;
                continue;
            }
            if (c == ',') { word(COMMA); last = null; i++; continue; }
            if (c == '.') {
                int j = i;
                while (j < n && s.charAt(j) == '.') j++;
                if (j - i >= 2) brk();
                else if (last == null || !ABBREV.contains(last)) end();
                last = null;
                i = j;
                continue;
            }
            if (c == '!' || c == '?') {
                while (i < n && (s.charAt(i) == '!' || s.charAt(i) == '?')) i++;
                end();
                last = null;
                continue;
            }
            i++;
            if (Character.isWhitespace(c) || ignorable(c) || isApos(c)) continue;
            brk(); // : ; - / & … and anything else
            last = null;
        }
    }

    /** Open-addressing long -> (count, number of 1000-line chunks it appears in), pruned when full. */
    static final class Table {
        long[] keys; int[] cnt; int[] df; int[] last;
        final int mask; final int limit;
        int size; int floor;

        Table(int bits) {
            int cap = 1 << bits;
            keys = new long[cap]; cnt = new int[cap]; df = new int[cap]; last = new int[cap];
            Arrays.fill(last, -1);
            mask = cap - 1; limit = (int) (cap * 0.75);
        }

        static int hash(long k) {
            k ^= k >>> 33; k *= 0xff51afd7ed558ccdL; k ^= k >>> 33; k *= 0xc4ceb9fe1a85ec53L; k ^= k >>> 33;
            return (int) k;
        }

        void inc(long key, int chunk) {
            int h = hash(key) & mask;
            while (true) {
                long k = keys[h];
                if (k == key) {
                    cnt[h]++;
                    if (last[h] != chunk) { df[h]++; last[h] = chunk; }
                    return;
                }
                if (k == 0) {
                    keys[h] = key; cnt[h] = 1; df[h] = 1; last[h] = chunk;
                    if (++size > limit) prune();
                    return;
                }
                h = (h + 1) & mask;
            }
        }

        void put(long key, int c, int d) {
            int h = hash(key) & mask;
            while (keys[h] != 0) h = (h + 1) & mask;
            keys[h] = key; cnt[h] = c; df[h] = d; last[h] = -1;
            size++;
        }

        int get(long key) {
            int h = hash(key) & mask;
            while (true) {
                long k = keys[h];
                if (k == key) return cnt[h];
                if (k == 0) return 0;
                h = (h + 1) & mask;
            }
        }

        /** Lossy counting: drop the rarest entries until the table is half full. */
        void prune() {
            int keep;
            // Search from 1 each time: a floor that only grows would throw away phrases that first
            // appear late in the corpus long before they could reach it.
            floor = 0;
            do {
                floor++;
                keep = 0;
                for (int i = 0; i < keys.length; i++) if (keys[i] != 0 && cnt[i] > floor) keep++;
            } while (keep > keys.length / 2);
            long[] k2 = new long[keep]; int[] c2 = new int[keep]; int[] d2 = new int[keep]; int[] l2 = new int[keep];
            int j = 0;
            for (int i = 0; i < keys.length; i++) if (keys[i] != 0 && cnt[i] > floor) {
                k2[j] = keys[i]; c2[j] = cnt[i]; d2[j] = df[i]; l2[j] = last[i]; j++;
            }
            Arrays.fill(keys, 0L);
            size = 0;
            for (int i = 0; i < keep; i++) { put(k2[i], c2[i], d2[i]); }
            // restore the chunk markers
            for (int i = 0; i < keep; i++) {
                int h = hash(k2[i]) & mask;
                while (keys[h] != k2[i]) h = (h + 1) & mask;
                last[h] = l2[i];
            }
            System.err.printf("  pruned a table to %,d entries (counts <= %d dropped)%n", keep, floor);
        }
    }

    static void saveCounts(String path) throws IOException {
        try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(path), 1 << 20))) {
            o.writeInt(COUNTS_VERSION);
            o.writeInt(names.hashCode()); // ids are positions in words_en.txt: the same list, or nothing
            o.writeInt(uni.length); o.writeLong(total); o.writeInt(chunks); o.writeLong(stopTotal);
            for (long u : uni) o.writeLong(u);
            for (long u : stop1) o.writeLong(u);
            for (Table t : new Table[]{bi, tri, stop2}) {
                int n = 0;
                for (int i = 0; i < t.keys.length; i++) if (t.keys[i] != 0 && t.cnt[i] >= 2) n++;
                o.writeInt(n);
                for (int i = 0; i < t.keys.length; i++) if (t.keys[i] != 0 && t.cnt[i] >= 2) {
                    o.writeLong(t.keys[i]); o.writeInt(t.cnt[i]); o.writeInt(t.df[i]);
                }
            }
        }
    }

    static void loadCounts(String path) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(path), 1 << 20))) {
            if (in.readInt() != COUNTS_VERSION) throw new IOException("counts from an older builder: count the corpus again");
            if (in.readInt() != names.hashCode()) throw new IOException("counts were made with a different words_en.txt");
            int v = in.readInt();
            if (v != uni.length) throw new IOException("counts were made with a different words_en.txt");
            total = in.readLong(); chunks = in.readInt(); stopTotal = in.readLong();
            for (int i = 0; i < v; i++) uni[i] = in.readLong();
            for (int i = 0; i < v; i++) stop1[i] = in.readLong();
            Table[] ts = new Table[3];
            for (int t = 0; t < 3; t++) {
                int n = in.readInt();
                int bits = 4;
                while ((1L << bits) * 0.6 < n) bits++;
                ts[t] = new Table(bits);
                for (int i = 0; i < n; i++) ts[t].put(in.readLong(), in.readInt(), in.readInt());
            }
            bi = ts[0]; tri = ts[1]; stop2 = ts[2];
        }
    }

    // ------------------------------------------------------------------ pruning and output

    /** Frequent only inside a few films: far fewer chunks than its count would spread over. */
    static boolean bursty(int count, int df) {
        double expected = chunks * (1 - Math.exp(-(double) count / chunks));
        return df < 0.15 * expected;
    }

    static double[] logU;

    static void build(int nBi, int nTri, String out, String dump) throws IOException {
        int V = uni.length;
        logU = new double[V];
        for (int i = 0; i < V; i++) logU[i] = Math.log(Math.max(uni[i], 0.5) / total);

        // Bigrams: score every candidate, keep the best nBi.
        int minCount = 3;
        List<long[]> cand = new ArrayList<>();
        int nCand = 0;
        for (int i = 0; i < bi.keys.length; i++) {
            long k = bi.keys[i];
            if (k == 0 || bi.cnt[i] < minCount) continue;
            int w = (int) (k & MASK);
            if (w < FIRST_WORD || bursty(bi.cnt[i], bi.df[i]) || uni[(int) (k >>> BITS)] < minBiContext) continue;
            nCand++;
        }
        long[] biKey = new long[nCand]; int[] biCnt = new int[nCand]; double[] biScore = new double[nCand];
        int j = 0;
        for (int i = 0; i < bi.keys.length; i++) {
            long k = bi.keys[i];
            if (k == 0 || bi.cnt[i] < minCount) continue;
            int w = (int) (k & MASK);
            if (w < FIRST_WORD || bursty(bi.cnt[i], bi.df[i]) || uni[(int) (k >>> BITS)] < minBiContext) continue;
            int b = (int) (k >>> BITS);
            double p = Math.min(0.99, (double) bi.cnt[i] / uni[b]);
            biKey[j] = k; biCnt[j] = bi.cnt[i];
            biScore[j] = bi.cnt[i] * Math.abs(Math.log(p) - logU[w]);
            j++;
        }
        int[] biKeep = topIndices(biScore, nBi);
        // Sort kept by key (context, then word).
        Integer[] order = new Integer[biKeep.length];
        for (int i = 0; i < order.length; i++) order[i] = biKeep[i];
        Arrays.sort(order, Comparator.comparingLong(a -> biKey[a]));
        // Per context: discounted probabilities and backoff weight.
        Map<Integer, double[]> biBackoff = new HashMap<>(); // context -> {sum P, sum U}
        Map<Long, Double> biP = new HashMap<>(order.length * 2);
        for (int idx : order) {
            long k = biKey[idx];
            int b = (int) (k >>> BITS), w = (int) (k & MASK);
            double p = Math.min(0.99, (biCnt[idx] - DISCOUNT) / uni[b]);
            biP.put(k, p);
            double[] s = biBackoff.computeIfAbsent(b, x -> new double[2]);
            s[0] += p; s[1] += Math.exp(logU[w]);
        }
        Map<Integer, Double> biAlpha = new HashMap<>();
        for (Map.Entry<Integer, double[]> e : biBackoff.entrySet()) {
            double[] s = e.getValue();
            // What's left for unlisted words: not the listed ones, and not "no word at all".
            biAlpha.put(e.getKey(), clampAlpha(Math.max(1e-4, 1 - s[0] - nonWordBi(e.getKey())) / Math.max(1e-4, 1 - s[1] - nonWordUni())));
        }
        System.err.printf("bigrams: %,d candidates, kept %,d in %,d contexts%n", nCand, order.length, biBackoff.size());

        // Trigrams, against the pruned bigram model.
        nCand = 0;
        for (int i = 0; i < tri.keys.length; i++) {
            long k = tri.keys[i];
            if (k != 0 && (k & MASK) >= FIRST_WORD && tri.cnt[i] >= minCount && !bursty(tri.cnt[i], tri.df[i]) && bi.get(k >>> BITS) >= minTriContext) nCand++;
        }
        long[] triKey = new long[nCand]; int[] triCnt = new int[nCand]; int[] triCtxCnt = new int[nCand]; double[] triScore = new double[nCand];
        j = 0;
        for (int i = 0; i < tri.keys.length; i++) {
            long k = tri.keys[i];
            if (k == 0 || (k & MASK) < FIRST_WORD || tri.cnt[i] < minCount || bursty(tri.cnt[i], tri.df[i]) || bi.get(k >>> BITS) < minTriContext) continue;
            int a = (int) (k >>> (2 * BITS)), b = (int) ((k >>> BITS) & MASK), w = (int) (k & MASK);
            int cab = Math.max(bi.get(((long) a << BITS) | b), tri.cnt[i]);
            double p = Math.min(0.99, (double) tri.cnt[i] / cab);
            triKey[j] = k; triCnt[j] = tri.cnt[i]; triCtxCnt[j] = cab;
            triScore[j] = tri.cnt[i] * Math.abs(Math.log(p) - Math.log(biModel(b, w, biP, biAlpha)));
            j++;
        }
        int[] triKeep = topIndices(triScore, nTri);
        Integer[] tOrder = new Integer[triKeep.length];
        for (int i = 0; i < tOrder.length; i++) tOrder[i] = triKeep[i];
        Arrays.sort(tOrder, Comparator.comparingLong(a -> triKey[a]));
        Map<Long, double[]> triBackoff = new HashMap<>();
        Map<Long, Double> triP = new HashMap<>(tOrder.length * 2);
        for (int idx : tOrder) {
            long k = triKey[idx];
            long ctx = k >>> BITS;
            int b = (int) (ctx & MASK), w = (int) (k & MASK);
            double p = Math.min(0.99, (triCnt[idx] - DISCOUNT) / triCtxCnt[idx]);
            triP.put(k, p);
            double[] s = triBackoff.computeIfAbsent(ctx, x -> new double[2]);
            s[0] += p; s[1] += biModel(b, w, biP, biAlpha);
        }
        Map<Long, Double> triAlpha = new HashMap<>();
        for (Map.Entry<Long, double[]> e : triBackoff.entrySet()) {
            double[] s = e.getValue();
            int a = (int) (e.getKey() >>> BITS), b = (int) (e.getKey() & MASK);
            triAlpha.put(e.getKey(), clampAlpha(Math.max(1e-4, 1 - s[0] - nonWordTri(a, b)) / Math.max(1e-4, 1 - s[1] - nonWordBi(b))));
        }
        System.err.printf("trigrams: %,d candidates, kept %,d in %,d contexts%n", nCand, tOrder.length, triBackoff.size());

        // Model vocabulary: every word the kept n-grams mention, most frequent first.
        Set<Integer> used = new HashSet<>();
        for (long k : biP.keySet()) { used.add((int) (k >>> BITS)); used.add((int) (k & MASK)); }
        for (long k : triP.keySet()) { used.add((int) (k >>> (2 * BITS))); used.add((int) ((k >>> BITS) & MASK)); used.add((int) (k & MASK)); }
        List<Integer> vocabIds = new ArrayList<>();
        for (int id : used) if (id >= FIRST_WORD) vocabIds.add(id);
        vocabIds.sort((x, y) -> Long.compare(uni[y], uni[x]));
        int[] remap = new int[V];
        Arrays.fill(remap, -1);
        remap[SENT] = SENT; remap[COMMA] = COMMA; remap[NUM] = NUM;
        for (int i = 0; i < vocabIds.size(); i++) remap[vocabIds.get(i)] = FIRST_WORD + i;
        int mv = FIRST_WORD + vocabIds.size();

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(bytes);
        o.writeBytes("QBPM");
        o.writeInt(1);
        o.writeInt(mv);
        for (int id : vocabIds) o.writeUTF(names.get(id));
        for (int id : vocabIds) o.writeByte(q(logU[id]));

        // Bigram contexts, in new-id order; followers in new-id order.
        TreeMap<Integer, TreeMap<Integer, Double>> biOut = new TreeMap<>();
        for (Map.Entry<Long, Double> e : biP.entrySet()) {
            long k = e.getKey();
            biOut.computeIfAbsent(remap[(int) (k >>> BITS)], x -> new TreeMap<>()).put(remap[(int) (k & MASK)], e.getValue());
        }
        Map<Integer, Integer> newToOld = new HashMap<>();
        for (int i = 0; i < V; i++) if (remap[i] >= 0) newToOld.put(remap[i], i);
        o.writeInt(biOut.size());
        int prevCtx = 0;
        for (Map.Entry<Integer, TreeMap<Integer, Double>> e : biOut.entrySet()) {
            varint(o, e.getKey() - prevCtx); prevCtx = e.getKey();
            o.writeByte(qa(Math.log(biAlpha.get(newToOld.get(e.getKey())))));
            followers(o, e.getValue());
        }
        // Trigram contexts, by (a, b).
        TreeMap<Long, TreeMap<Integer, Double>> triOut = new TreeMap<>();
        for (Map.Entry<Long, Double> e : triP.entrySet()) {
            long k = e.getKey();
            int a = remap[(int) (k >>> (2 * BITS))], b = remap[(int) ((k >>> BITS) & MASK)], w = remap[(int) (k & MASK)];
            triOut.computeIfAbsent(((long) a << BITS) | b, x -> new TreeMap<>()).put(w, e.getValue());
        }
        o.writeInt(triOut.size());
        int prevA = 0, prevB = 0;
        for (Map.Entry<Long, TreeMap<Integer, Double>> e : triOut.entrySet()) {
            int a = (int) (e.getKey() >>> BITS), b = (int) (e.getKey() & MASK);
            varint(o, a - prevA);
            varint(o, a == prevA ? b - prevB : b);
            prevA = a; prevB = b;
            long oldCtx = ((long) newToOld.get(a) << BITS) | newToOld.get(b);
            o.writeByte(qa(Math.log(triAlpha.get(oldCtx))));
            followers(o, e.getValue());
        }
        o.flush();
        try (FileOutputStream f = new FileOutputStream(out)) { bytes.writeTo(f); }
        System.err.printf("wrote %s: %,d bytes, %,d words%n", out, bytes.size(), vocabIds.size());

        if (dump != null) dumpContexts(dump, biP, biAlpha, triP);
    }

    /** Share of a token's occurrences followed by no word: a sentence end, a break, a comma or a number. */
    static double nonWordBi(int b) {
        long n = stop1[b] + bi.get(((long) b << BITS) | COMMA) + bi.get(((long) b << BITS) | NUM);
        return Math.min(0.999, (double) n / Math.max(1, uni[b]));
    }

    static double nonWordTri(int a, int b) {
        long ctx = ((long) a << BITS) | b;
        long n = stop2.get(ctx) + tri.get((ctx << BITS) | COMMA) + tri.get((ctx << BITS) | NUM);
        return Math.min(0.999, (double) n / Math.max(1, bi.get(ctx)));
    }

    static double nonWordUni() {
        return (double) (stopTotal + uni[COMMA] + uni[NUM]) / total;
    }

    static double biModel(int b, int w, Map<Long, Double> biP, Map<Integer, Double> biAlpha) {
        Double p = biP.get(((long) b << BITS) | w);
        if (p != null) return p;
        return biAlpha.getOrDefault(b, 1.0) * Math.exp(logU[w]);
    }

    static double clampAlpha(double a) { return Math.max(Math.exp(-12), Math.min(Math.exp(12), a)); }

    static void followers(DataOutputStream o, TreeMap<Integer, Double> f) throws IOException {
        varint(o, f.size());
        int prev = 0;
        for (int w : f.keySet()) { varint(o, w - prev); prev = w; }
        for (double p : f.values()) o.writeByte(q(Math.log(p)));
    }

    /** -ln P in tenths of a nat, 0..255. */
    static int q(double logP) { return (int) Math.max(0, Math.min(255, Math.round(-logP * 10))); }

    /** ln(backoff weight) in tenths of a nat, -127..127 (signed). */
    static int qa(double logA) { return (int) Math.max(-127, Math.min(127, Math.round(logA * 10))); }

    static void varint(DataOutputStream o, int v) throws IOException {
        if (v < 0) throw new IOException("negative varint");
        while (v >= 0x80) { o.writeByte((v & 0x7F) | 0x80); v >>>= 7; }
        o.writeByte(v);
    }

    static int[] topIndices(double[] score, int n) {
        if (score.length <= n) { int[] all = new int[score.length]; for (int i = 0; i < all.length; i++) all[i] = i; return all; }
        double[] s = score.clone();
        Arrays.sort(s);
        double cut = s[s.length - n];
        System.err.printf("  keeping scores above %.1f%n", cut);
        int[] out = new int[n];
        int k = 0;
        for (int i = 0; i < score.length && k < n; i++) if (score[i] > cut) out[k++] = i;
        for (int i = 0; i < score.length && k < n; i++) if (score[i] == cut) out[k++] = i;
        return out;
    }

    static void dumpContexts(String path, Map<Long, Double> biP, Map<Integer, Double> biAlpha, Map<Long, Double> triP) throws IOException {
        String[][] probes = {
            {"<s>"}, {"i"}, {"you"}, {"thank"}, {"thanks"}, {"how"}, {"what"}, {"see"}, {"good"}, {"the"}, {"to"}, {"my"},
            {"on"}, {"at"}, {"<num>"}, {","}, {"love"}, {"can"}, {"let"}, {"want"}, {"going"}, {"hey"}, {"lol"}, {"omg"}, {"gonna"},
            {"ok"}, {"okay"}, {"please"}, {"miss"}, {"happy"}, {"was"}, {"have"}, {"a"}, {"be"}, {"of"}, {"in"},
            {"i", "want"}, {"want", "to"}, {"going", "to"}, {"i'm", "going"}, {"how", "are"}, {"what", "are"},
            {"<s>", "thank"}, {"see", "you"}, {"hey", ","}, {"<s>", "i"}, {"i", "love"}, {"do", "you"}, {"i", "don't"},
            {"let", "me"}, {"thank", "you"}, {"love", "you"}, {"miss", "you"}, {"on", "my"}, {"at", "the"}, {"in", "the"}, {"talk", "to"}, {"call", "me"}, {"good", "morning"},
        };
        try (PrintWriter p = new PrintWriter(new OutputStreamWriter(new FileOutputStream(path), StandardCharsets.UTF_8))) {
            for (String[] ctx : probes) {
                Integer b = id(ctx[ctx.length - 1]);
                Integer a = ctx.length > 1 ? id(ctx[0]) : null;
                if (b == null || (ctx.length > 1 && a == null)) { p.println(String.join(" ", ctx) + ": ?"); continue; }
                TreeMap<Double, String> best = new TreeMap<>(Comparator.reverseOrder());
                if (a != null) {
                    long pre = (((long) a << BITS) | b) << BITS;
                    for (Map.Entry<Long, Double> e : triP.entrySet()) if ((e.getKey() & ~MASK) == pre) best.put(e.getValue(), names.get((int) (e.getKey() & MASK)));
                } else {
                    for (Map.Entry<Long, Double> e : biP.entrySet()) if ((e.getKey() >>> BITS) == b) best.put(e.getValue(), names.get((int) (e.getKey() & MASK)));
                }
                long seen = a != null ? bi.get(((long) a << BITS) | b) : uni[b];
                StringBuilder sb = new StringBuilder(String.join(" ", ctx)).append(" [").append(seen).append("] (").append(best.size()).append("):");
                int k = 0;
                for (Map.Entry<Double, String> e : best.entrySet()) {
                    if (k++ >= 14) break;
                    sb.append(String.format(" %s %.3f", e.getValue(), e.getKey()));
                }
                p.println(sb);
            }
        }
    }

    static Integer id(String w) {
        switch (w) { case "<s>": return SENT; case ",": return COMMA; case "<num>": return NUM; default: return vocab.get(w); }
    }
}
