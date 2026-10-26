package dev.larryjones.quietboard

import android.graphics.RectF

/** TO_ALPHA returns to the field's own layout (letters, or its number pad); TO_LETTERS always means letters. */
enum class KeyType { CHAR, SHIFT, DELETE, SPACE, ENTER, TO_SYMBOLS, TO_SYMBOLS2, TO_ALPHA, TO_LETTERS, EMOJI }

enum class LayoutId { ALPHA, SYMBOLS, SYMBOLS2, NUMBER, DATETIME, PHONE }

enum class FieldKind { TEXT, EMAIL, URL }

class Key(
    val type: KeyType,
    /** Output for CHAR keys (lowercase base form). */
    val text: String = "",
    val label: String = text,
    val weight: Float = 1f,
    /** Extra touch area that is not drawn (e.g. left of "a"). */
    val padLeft: Float = 0f,
    val padRight: Float = 0f,
    val hint: String? = null,
    val alternates: List<String> = emptyList(),
    /** If set, a long press triggers this action instead of an alternates popup. */
    val longPressAction: KeyType? = null,
    /** Drawn with the darker "function key" color. */
    val functional: Boolean = type != KeyType.CHAR && type != KeyType.SPACE,
) {
    val touch = RectF()
    val visual = RectF()
    val isLetter get() = type == KeyType.CHAR && text.length == 1 && text[0].isLetter()
}

class Row(val keys: List<Key>, val height: Float = 1f)

/**
 * [split]: for a big screen. Letters and symbols are drawn as two halves with a gap between (see
 * [Layouts.splitRows]); number, phone and date pads are docked at the right edge.
 */
class KeyboardLayout(val id: LayoutId, val rows: List<Row>, val split: Boolean = false)

data class LayoutOptions(
    val numberRow: Boolean,
    val longPressSymbols: Boolean,
    val field: FieldKind,
    /** The field's own layout is a number/date/phone pad: the symbols pages lead back to it ("123"). */
    val numericBase: Boolean = false,
    /** Big screens (an unfolded Fold, tablets): letters and symbols in two halves, one for each thumb. */
    val split: Boolean = false,
)

object Layouts {
    private val ACCENTS = mapOf(
        'a' to "à á â ä æ ã å ā", 'c' to "ç ć č", 'e' to "è é ê ë ē ę ė",
        'i' to "ì í î ï ī į", 'l' to "ł", 'n' to "ñ ń", 'o' to "ò ó ô ö õ ø ō œ",
        's' to "ß ś š", 'u' to "ù ú û ü ū", 'y' to "ÿ ý", 'z' to "ž ź ż", 'd' to "ð",
        't' to "þ", 'g' to "ğ", 'r' to "ř",
    )

    // Gboard's "long press for symbols" mapping.
    private val SYMBOL_HINTS = mapOf(
        'q' to "%", 'w' to "^", 'e' to "~", 'r' to "|", 't' to "[", 'y' to "]", 'u' to "<",
        'i' to ">", 'o' to "{", 'p' to "}",
        'a' to "@", 's' to "#", 'd' to "&", 'f' to "*", 'g' to "-", 'h' to "+", 'j' to "=",
        'k' to "(", 'l' to ")",
        'z' to "_", 'x' to "$", 'c' to "\"", 'v' to "'", 'b' to ":", 'n' to ";", 'm' to "/",
    )

    private val SYMBOL_ALTS = mapOf(
        "1" to "¹ ½ ⅓ ¼ ⅛", "2" to "² ⅔", "3" to "³ ¾ ⅜", "4" to "⁴", "5" to "⅝", "7" to "⅞",
        "0" to "ⁿ ∅", "$" to "¢ £ € ¥ ₹ ₩ ₱", "-" to "– — _ ·", "+" to "±", "(" to "< { [",
        ")" to "> } ]", "\"" to "“ ” „ « »", "'" to "‘ ’ ‚ ‹ ›", "!" to "¡", "?" to "¿",
        "%" to "‰", "*" to "† ‡ ★", "=" to "≠ ≈ ∞", "/" to "\\", "&" to "§", "#" to "№",
        "." to "… · •", "," to "‚",
    )

    private val PERIOD_ALTS = listOf(",", "?", "!", "'", "\"", ":", ";", "-", "@", "#")

    private fun split(s: String?) = s?.split(' ')?.filter { it.isNotEmpty() } ?: emptyList()

    private fun sym(c: String) = Key(KeyType.CHAR, c, alternates = split(SYMBOL_ALTS[c]))

    private fun letter(c: Char, topRowDigit: String?, opts: LayoutOptions, padL: Float = 0f, padR: Float = 0f): Key {
        val alts = ArrayList<String>()
        var hint: String? = null
        if (topRowDigit != null && !opts.numberRow) {
            hint = topRowDigit
            alts += topRowDigit
        } else if (opts.longPressSymbols) {
            SYMBOL_HINTS[c]?.let { hint = it; alts += it }
        }
        alts += split(ACCENTS[c])
        return Key(KeyType.CHAR, c.toString(), hint = hint, alternates = alts, padLeft = padL, padRight = padR)
    }

    private fun bottomRow(opts: LayoutOptions, modeKey: Key, symbolsPage: Boolean): Row {
        val comma = when (opts.field) {
            FieldKind.EMAIL -> Key(KeyType.CHAR, "@")
            FieldKind.URL -> Key(KeyType.CHAR, "/")
            FieldKind.TEXT -> Key(KeyType.CHAR, ",", longPressAction = KeyType.EMOJI)
        }
        val period = if (opts.field == FieldKind.URL && !symbolsPage) {
            Key(KeyType.CHAR, ".", alternates = listOf(".com", ".net", ".org", ".io", "/", ":"))
        } else {
            Key(KeyType.CHAR, ".", alternates = PERIOD_ALTS)
        }
        return Row(
            listOf(
                modeKey,
                comma,
                Key(KeyType.SPACE, " ", label = "", weight = 5f),
                period,
                Key(KeyType.ENTER, weight = 1.5f),
            )
        )
    }

    fun build(id: LayoutId, opts: LayoutOptions): KeyboardLayout {
        val l = when (id) {
            LayoutId.ALPHA -> alpha(opts)
            LayoutId.SYMBOLS -> symbols(opts)
            LayoutId.SYMBOLS2 -> symbols2(opts)
            LayoutId.NUMBER, LayoutId.DATETIME, LayoutId.PHONE -> numberPad(id)
        }
        // Number, phone and date pads aren't cut in two: KeyboardView docks them at the edge instead.
        if (!opts.split) return l
        return KeyboardLayout(l.id, if (l.id in SPLITTABLE) splitRows(l.rows) else l.rows, split = true)
    }

    private val SPLITTABLE = setOf(LayoutId.ALPHA, LayoutId.SYMBOLS, LayoutId.SYMBOLS2)

    /**
     * For a split keyboard: a space bar across the middle of its row becomes one space bar in each
     * half. Other keys go to the half their middle is in ("g" left, "h" right); see KeyboardView.
     */
    internal fun splitRows(rows: List<Row>): List<Row> = rows.map { row ->
        val half = row.keys.sumOf { (it.weight + it.padLeft + it.padRight).toDouble() }.toFloat() / 2
        var at = 0f
        val keys = row.keys.flatMap { k ->
            val start = at + k.padLeft
            val end = start + k.weight
            at = end + k.padRight
            if (k.type == KeyType.SPACE && start < half && end > half) {
                listOf(Key(KeyType.SPACE, " ", label = "", weight = half - start), Key(KeyType.SPACE, " ", label = "", weight = end - half))
            } else listOf(k)
        }
        Row(keys, row.height)
    }

    private fun backKey(opts: LayoutOptions) = Key(KeyType.TO_ALPHA, label = if (opts.numericBase) "123" else "ABC", weight = 1.5f)

    private fun alpha(opts: LayoutOptions): KeyboardLayout {
        val rows = ArrayList<Row>()
        val digits = "1234567890"
        if (opts.numberRow) {
            rows += Row(digits.map { sym(it.toString()) }, height = 0.82f)
        }
        rows += Row("qwertyuiop".mapIndexed { i, c -> letter(c, digits[i].toString(), opts) })
        val mid = "asdfghjkl"
        rows += Row(mid.mapIndexed { i, c ->
            letter(c, null, opts, padL = if (i == 0) 0.5f else 0f, padR = if (i == mid.lastIndex) 0.5f else 0f)
        })
        rows += Row(
            listOf(Key(KeyType.SHIFT, weight = 1.5f)) +
                "zxcvbnm".map { letter(it, null, opts) } +
                listOf(Key(KeyType.DELETE, weight = 1.5f))
        )
        rows += bottomRow(opts, Key(KeyType.TO_SYMBOLS, label = "?123", weight = 1.5f), symbolsPage = false)
        return KeyboardLayout(LayoutId.ALPHA, rows)
    }

    private fun symbols(opts: LayoutOptions): KeyboardLayout {
        val rows = ArrayList<Row>()
        rows += Row("1234567890".map { sym(it.toString()) })
        rows += Row(listOf("@", "#", "$", "_", "&", "-", "+", "(", ")", "/").map { sym(it) })
        rows += Row(
            listOf(Key(KeyType.TO_SYMBOLS2, label = "=\\<", weight = 1.5f)) +
                listOf("*", "\"", "'", ":", ";", "!", "?").map { sym(it) } +
                listOf(Key(KeyType.DELETE, weight = 1.5f))
        )
        rows += bottomRow(opts, backKey(opts), symbolsPage = true)
        return KeyboardLayout(LayoutId.SYMBOLS, rows)
    }

    private fun symbols2(opts: LayoutOptions): KeyboardLayout {
        val rows = ArrayList<Row>()
        rows += Row(listOf("~", "`", "|", "•", "√", "π", "÷", "×", "¶", "∆").map { sym(it) })
        rows += Row(listOf("£", "¢", "€", "¥", "^", "°", "=", "{", "}", "\\").map { sym(it) })
        rows += Row(
            listOf(Key(KeyType.TO_SYMBOLS, label = "?123", weight = 1.5f)) +
                listOf("%", "©", "®", "™", "✓", "[", "]").map { sym(it) } +
                listOf(Key(KeyType.DELETE, weight = 1.5f))
        )
        val bottom = Row(
            listOf(
                backKey(opts),
                // From a number/date field this page is the way to letters ("10:30 PM").
                if (opts.numericBase) Key(KeyType.TO_LETTERS, label = "ABC") else sym("<"),
                Key(KeyType.SPACE, " ", label = "", weight = 5f),
                sym(">"),
                Key(KeyType.ENTER, weight = 1.5f),
            )
        )
        rows += bottom
        return KeyboardLayout(LayoutId.SYMBOLS2, rows)
    }

    private fun numberPad(id: LayoutId): KeyboardLayout {
        fun n(c: String, alts: List<String> = emptyList(), hint: String? = null) =
            Key(KeyType.CHAR, c, alternates = alts, hint = hint, weight = 1f)
        val space = Key(KeyType.SPACE, " ", label = "", weight = 1f)
        // Number and date pads get a way out to every other symbol (and, from the second page, letters).
        val symbols = Key(KeyType.TO_SYMBOLS, label = "!?#", weight = 1f)
        val rows = ArrayList<Row>()
        when (id) {
            LayoutId.PHONE -> {
                rows += Row(listOf(n("1"), n("2"), n("3"), n("-", listOf("(", ")"))))
                rows += Row(listOf(n("4"), n("5"), n("6"), space))
                rows += Row(listOf(n("7"), n("8"), n("9"), Key(KeyType.DELETE, weight = 1f)))
                rows += Row(listOf(n("*", listOf("#", ",", ";")), n("0", listOf("+"), hint = "+"), n("#"), Key(KeyType.ENTER)))
            }
            LayoutId.DATETIME -> {
                rows += Row(listOf(n("1"), n("2"), n("3"), n("/", listOf("-", "."))))
                rows += Row(listOf(n("4"), n("5"), n("6"), n(":")))
                rows += Row(listOf(n("7"), n("8"), n("9"), Key(KeyType.DELETE, weight = 1f)))
                rows += Row(listOf(symbols, n("0"), space, Key(KeyType.ENTER)))
            }
            else -> {
                rows += Row(listOf(n("1"), n("2"), n("3"), n("-")))
                rows += Row(listOf(n("4"), n("5"), n("6"), space))
                rows += Row(listOf(n("7"), n("8"), n("9"), Key(KeyType.DELETE, weight = 1f)))
                rows += Row(listOf(symbols, n("0"), n(".", listOf(",")), Key(KeyType.ENTER)))
            }
        }
        return KeyboardLayout(id, rows)
    }
}
