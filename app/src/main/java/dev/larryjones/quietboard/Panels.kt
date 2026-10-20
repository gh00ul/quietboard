package dev.larryjones.quietboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/** Round-highlight icon button drawn with [Icons]. Optional auto-repeat (for delete). */
@SuppressLint("ViewConstructor")
class IconButton(context: Context, var icon: Icon, private val repeat: Boolean = false) : View(context) {
    var color = 0xFF000000.toInt()
    var pressedColor = 0x22000000
    var onClick: (() -> Unit)? = null
    private val dp = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var down = false
    private val handler = Handler(Looper.getMainLooper())
    private val repeater = object : Runnable {
        override fun run() {
            onClick?.invoke()
            handler.postDelayed(this, 60L)
        }
    }

    init { isClickable = true }

    /** Accessibility clicks (TalkBack double-tap) arrive here, not through onTouchEvent. */
    override fun performClick(): Boolean {
        super.performClick()
        onClick?.invoke()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        if (down) {
            paint.color = pressedColor
            canvas.drawCircle(cx, cy, minOf(width, height) * 0.42f, paint)
        }
        Icons.draw(canvas, icon, cx, cy, 22 * dp, color)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                down = true; invalidate()
                if (repeat) { onClick?.invoke(); handler.postDelayed(repeater, 400L) }
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(repeater)
                val inside = e.x in 0f..width.toFloat() && e.y in 0f..height.toFloat()
                if (down && !repeat && inside) onClick?.invoke()
                down = false; invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(repeater); down = false; invalidate()
            }
        }
        return true
    }
}

private fun Context.dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

private fun textButton(ctx: Context, label: String, onClick: () -> Unit) = TextView(ctx).apply {
    text = label
    gravity = Gravity.CENTER
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val p = ctx.dp(14f).toInt()
    setPadding(p, 0, p, 0)
    setOnClickListener { onClick() }
}

private fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeWidth: Int = 0) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius
    // Neon and Retro draw keys by their outline: without it a key is invisible on the background.
    if (stroke != 0) setStroke(strokeWidth, stroke)
}

object EmojiData {
    class Category(val name: String, val tab: String, val emojis: List<String>)

    @Volatile private var cached: List<Category>? = null

    private val TABS = mapOf(
        "Smileys & Emotion" to "😀", "People & Body" to "👋", "Animals & Nature" to "🐻",
        "Food & Drink" to "🍔", "Travel & Places" to "🚗", "Activities" to "⚽",
        "Objects" to "💡", "Symbols" to "🔣", "Flags" to "🏁",
    )

    /**
     * Emoji categories, minus emoji the system font can't draw (the list is Emoji 15.1; older
     * Android versions would show empty boxes). Call off the UI thread first: checking ~1,900 glyphs
     * takes a moment.
     */
    fun get(ctx: Context): List<Category> {
        cached?.let { return it }
        val out = ArrayList<Category>()
        val glyphs = Paint()
        runCatching {
            ctx.assets.open("emoji.txt").bufferedReader().useLines { lines ->
                for (line in lines) {
                    val tab = line.indexOf('\t')
                    if (tab <= 0) continue
                    val name = line.substring(0, tab)
                    val list = line.substring(tab + 1).split(' ').filter { it.isNotEmpty() }
                    // If nothing in a category draws, hasGlyph isn't telling the truth (tests): keep all.
                    val shown = list.filter { glyphs.hasGlyph(it) }.ifEmpty { list }
                    out += Category(name, TABS[name] ?: shown.firstOrNull() ?: "?", shown)
                }
            }
        }
        cached = out
        return out
    }
}

class EmojiPanel(context: Context, private val listener: Listener) : LinearLayout(context) {
    interface Listener {
        fun onEmoji(emoji: String)
        fun onPanelDelete()
        fun onPanelBack()
    }

    private val categories = EmojiData.get(context)
    private var recents: List<String> = emptyList()
    private var current = 1
    private var theme: KbTheme? = null
    private val grid = GridView(context)
    private val title = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        val p = context.dp(14f).toInt()
        setPadding(p, context.dp(8f).toInt(), p, context.dp(2f).toInt())
    }
    private val tabs = LinearLayout(context)
    private val tabViews = ArrayList<TextView>()
    private val abc = textButton(context, "ABC") { listener.onPanelBack() }
    private val del = IconButton(context, Icon.BACKSPACE, repeat = true).apply {
        onClick = { listener.onPanelDelete() }
        contentDescription = "Delete"
    }
    private val adapter = EmojiAdapter()

    init {
        orientation = VERTICAL
        grid.numColumns = GridView.AUTO_FIT
        grid.columnWidth = context.dp(46f).toInt()
        grid.stretchMode = GridView.STRETCH_COLUMN_WIDTH
        grid.adapter = adapter
        grid.selector = rounded(0x22888888, context.dp(10f))
        grid.isVerticalScrollBarEnabled = false
        grid.setOnItemClickListener { _, _, pos, _ ->
            adapter.items.getOrNull(pos)?.let { listener.onEmoji(it) }
        }
        addView(title, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        tabs.orientation = HORIZONTAL
        tabs.gravity = Gravity.CENTER_VERTICAL
        // "ABC" and delete take what they need; the category tabs share the rest (a weighted ABC
        // was too narrow on small phones and wrapped into a vertical "A B C").
        // maxLines, not singleLine: a single-line TextView scrolls horizontally, and with centred text
        // in a wrap-content box that draws the label out of view.
        abc.maxLines = 1
        abc.setPadding(context.dp(12f).toInt(), 0, context.dp(8f).toInt(), 0)
        tabs.addView(abc, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        val labels = listOf("🕘") + categories.map { it.tab }
        val names = listOf("Recent") + categories.map { it.name }
        for ((i, l) in labels.withIndex()) {
            val tv = TextView(context).apply {
                text = l
                contentDescription = names[i]
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18f) // fixed 44dp bar: don't scale with font size
                setOnClickListener { select(i) }
            }
            tabViews += tv
            tabs.addView(tv, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
        tabs.addView(del, LayoutParams(context.dp(52f).toInt(), LayoutParams.MATCH_PARENT))
        addView(tabs, LayoutParams(LayoutParams.MATCH_PARENT, context.dp(44f).toInt()))
    }

    fun setRecents(list: List<String>) {
        // A copy: the keyboard reorders its live list on every pick, which used to shift the grid
        // under the finger without a redraw, so the next tap inserted a neighbouring emoji.
        recents = list.toList()
        if (current == 0) adapter.set(recents)
    }

    fun open() {
        select(if (recents.isNotEmpty()) 0 else 1)
    }

    private fun select(i: Int) {
        current = i
        title.text = if (i == 0) "Recently used" else categories.getOrNull(i - 1)?.name ?: ""
        adapter.set(if (i == 0) recents else categories.getOrNull(i - 1)?.emojis ?: emptyList())
        grid.setSelection(0)
        styleTabs()
    }

    fun applyTheme(t: KbTheme) {
        theme = t
        setBackgroundColor(if (t.hasBackdrop) 0 else t.background) // fun themes: the scene shows through
        title.setTextColor(t.hint)
        title.typeface = t.fonts.medium
        abc.setTextColor(t.text)
        abc.typeface = t.fonts.medium
        del.color = t.text
        del.pressedColor = t.keyPressed
        del.invalidate()
        styleTabs()
        adapter.notifyDataSetChanged()
    }

    private fun styleTabs() {
        val t = theme ?: return
        for ((i, tv) in tabViews.withIndex()) {
            tv.background = if (i == current) rounded(t.special, context.dp(12f), t.specialStroke, context.dp(1.2f).toInt()) else null
            tv.alpha = if (i == current) 1f else 0.75f
        }
    }

    private inner class EmojiAdapter : BaseAdapter() {
        var items: List<String> = emptyList()
        fun set(list: List<String>) { items = list; notifyDataSetChanged() }
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val tv = (convertView as? TextView) ?: TextView(context).apply {
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 27f) // fixed 48dp cells: don't scale with font size
                layoutParams = AbsListView.LayoutParams(AbsListView.LayoutParams.MATCH_PARENT, context.dp(48f).toInt())
            }
            tv.text = items[position]
            return tv
        }
    }
}

class ClipboardPanel(context: Context, private val listener: Listener) : LinearLayout(context) {
    interface Listener {
        fun onClipPaste(text: String)
        fun onClipPin(item: ClipboardHistory.Item)
        fun onClipRemove(item: ClipboardHistory.Item)
        fun onClipClear()
        fun onPanelBack()
    }

    private var theme: KbTheme? = null
    private val title = TextView(context).apply {
        text = "Clipboard"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER_VERTICAL
    }
    private val abc = textButton(context, "ABC") { listener.onPanelBack() }
    private val clear = textButton(context, "Clear all") { listener.onClipClear() }
    private val list = ListView(context)
    private val empty = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        val p = context.dp(24f).toInt()
        setPadding(p, p, p, p)
    }
    private val adapter = ClipAdapter()

    init {
        orientation = VERTICAL
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(abc, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
            addView(title, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            addView(clear, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        }
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, context.dp(44f).toInt()))
        val body = FrameLayout(context)
        list.adapter = adapter
        list.divider = null
        list.dividerHeight = 0
        list.selector = rounded(0, 0f)
        val pad = context.dp(8f).toInt()
        list.setPadding(pad, 0, pad, pad)
        list.clipToPadding = false
        body.addView(list, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        body.addView(empty, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    fun setItems(items: List<ClipboardHistory.Item>, enabled: Boolean) {
        adapter.items = items
        adapter.notifyDataSetChanged()
        empty.text = if (!enabled) "Clipboard history is off.\nTurn it on in Quietboard settings."
        else "Copied text shows up here.\nKept on this device for 1 hour \u2014 pin anything you want to keep."
        empty.visibility = if (items.isEmpty()) VISIBLE else GONE
        list.visibility = if (items.isEmpty()) GONE else VISIBLE
        // "Clear all" keeps pinned clips, so hide it when there's nothing it would remove.
        clear.visibility = if (items.none { !it.pinned }) INVISIBLE else VISIBLE
    }

    fun applyTheme(t: KbTheme) {
        theme = t
        setBackgroundColor(if (t.hasBackdrop) 0 else t.background) // fun themes: the scene shows through
        title.setTextColor(t.text)
        abc.setTextColor(t.text)
        clear.setTextColor(t.accent)
        empty.setTextColor(t.hint)
        title.typeface = t.fonts.medium
        abc.typeface = t.fonts.medium
        clear.typeface = t.fonts.medium
        empty.typeface = t.fonts.regular
        adapter.notifyDataSetChanged()
    }

    private fun age(time: Long): String {
        val min = (System.currentTimeMillis() - time) / 60_000L
        return when {
            time <= 0L -> ""
            min < 1 -> "Just now"
            min == 1L -> "1 min ago"
            else -> "$min min ago"
        }
    }

    private inner class ClipAdapter : BaseAdapter() {
        var items: List<ClipboardHistory.Item> = emptyList()
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val t = theme
            val row = (convertView as? LinearLayout) ?: LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val texts = LinearLayout(context).apply {
                    orientation = VERTICAL
                    val p = context.dp(14f).toInt()
                    setPadding(p, context.dp(11f).toInt(), 0, context.dp(11f).toInt())
                }
                texts.addView(TextView(context).apply {
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                })
                texts.addView(TextView(context).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setPadding(0, context.dp(2f).toInt(), 0, 0)
                })
                addView(texts, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                addView(IconButton(context, Icon.PIN), LayoutParams(context.dp(44f).toInt(), context.dp(48f).toInt()))
                addView(IconButton(context, Icon.CLOSE), LayoutParams(context.dp(48f).toInt(), context.dp(48f).toInt()))
                layoutParams = AbsListView.LayoutParams(AbsListView.LayoutParams.MATCH_PARENT, AbsListView.LayoutParams.WRAP_CONTENT)
            }
            val item = items[position]
            val texts = row.getChildAt(0) as LinearLayout
            val text = texts.getChildAt(0) as TextView
            val meta = texts.getChildAt(1) as TextView
            meta.text = if (item.pinned) "Pinned" else age(item.time)
            meta.visibility = if (meta.text.isNullOrEmpty()) View.GONE else View.VISIBLE
            val pin = row.getChildAt(1) as IconButton
            val close = row.getChildAt(2) as IconButton
            text.text = item.text
            pin.icon = if (item.pinned) Icon.PIN_ON else Icon.PIN
            pin.contentDescription = if (item.pinned) "Unpin" else "Pin"
            close.contentDescription = "Remove"
            if (t != null) {
                text.setTextColor(t.text)
                text.typeface = t.fonts.regular
                meta.setTextColor(if (item.pinned) t.accent else t.hint)
                meta.typeface = t.fonts.medium
                pin.color = if (item.pinned) t.accent else t.hint
                pin.pressedColor = t.keyPressed
                close.color = t.hint
                close.pressedColor = t.keyPressed
                row.background = android.graphics.drawable.InsetDrawable(
                    rounded(t.key, context.dp(14f), t.keyStroke, context.dp(1.2f).toInt()), 0, context.dp(3f).toInt(), 0, context.dp(3f).toInt()
                )
            }
            row.setOnClickListener { listener.onClipPaste(item.text) }
            close.onClick = { listener.onClipRemove(item) }
            pin.onClick = { listener.onClipPin(item) }
            close.invalidate()
            pin.invalidate()
            return row
        }
    }
}

/** A rounded key used by the editing panel: text or icon, optional auto-repeat. */
@SuppressLint("ViewConstructor")
class PanelKey(context: Context, val label: String?, val icon: Icon?, private val repeat: Boolean = false) : View(context) {
    var theme: KbTheme? = null
        set(v) { field = v; invalidate() }
    var functional = true
    var active = false
        // Selected = on, for TalkBack (the Select toggle).
        set(v) { field = v; isSelected = v; invalidate() }
    var onClick: (() -> Unit)? = null
    /** Fired once per touch (for haptics), unlike onClick which repeats on repeating keys. */
    var onPress: (() -> Unit)? = null
    private val dp = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private var down = false
    private val handler = Handler(Looper.getMainLooper())
    private val repeater = object : Runnable {
        override fun run() {
            onClick?.invoke()
            handler.postDelayed(this, 50L)
        }
    }

    init { isClickable = true; contentDescription = label ?: icon?.let { ICON_NAMES[it] } ?: icon?.name }

    /** Accessibility clicks (TalkBack double-tap) arrive here, not through onTouchEvent. */
    override fun performClick(): Boolean {
        super.performClick()
        onPress?.invoke()
        onClick?.invoke()
        return true
    }

    private companion object {
        val ICON_NAMES = mapOf(
            Icon.UP to "Up", Icon.DOWN to "Down", Icon.LEFT to "Left", Icon.RIGHT to "Right", Icon.BACKSPACE to "Delete",
        )
    }

    override fun onDraw(canvas: Canvas) {
        val t = theme ?: return
        val inset = 3 * dp
        val bg = when {
            active -> t.accent
            down -> if (functional) t.specialPressed else t.keyPressed
            functional -> t.special
            else -> t.key
        }
        val fg = if (active) t.onAccent else t.text
        paint.color = bg
        canvas.drawRoundRect(inset, inset, width - inset, height - inset, 12 * dp, 12 * dp, paint)
        val stroke = if (active) 0 else if (functional) t.specialStroke else t.keyStroke
        if (stroke != 0) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.2f * dp
            paint.color = stroke
            val s = inset + 0.6f * dp
            canvas.drawRoundRect(s, s, width - s, height - s, 12 * dp, 12 * dp, paint)
            paint.style = Paint.Style.FILL
        }
        if (icon != null) {
            Icons.draw(canvas, icon, width / 2f, height / 2f, minOf(width, height) * 0.38f, fg)
        } else if (label != null) {
            text.color = fg
            text.typeface = t.fonts.medium
            text.textSize = 15 * dp
            canvas.drawText(label, width / 2f, height / 2f - (text.descent() + text.ascent()) / 2, text)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                down = true; invalidate()
                onPress?.invoke()
                if (repeat) { onClick?.invoke(); handler.postDelayed(repeater, 400L) }
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(repeater)
                val inside = e.x in 0f..width.toFloat() && e.y in 0f..height.toFloat()
                if (down && !repeat && inside) onClick?.invoke()
                down = false; invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { handler.removeCallbacks(repeater); down = false; invalidate() }
        }
        return true
    }
}

/** Gboard-style text editing: cursor keys, selection, clipboard actions, undo/redo. */
class EditPanel(context: Context, private val listener: Listener) : LinearLayout(context) {
    enum class Action { UP, DOWN, LEFT, RIGHT, HOME, END, SELECT_ALL, COPY, CUT, PASTE, UNDO, REDO }

    interface Listener {
        /** [extend] is true while "Select" is on: moves extend the selection. */
        fun onEditAction(action: Action, extend: Boolean)
        fun onKeyFeedbackPanel()
        fun onPanelDelete()
        fun onPanelBack()
    }

    private val keys = ArrayList<PanelKey>()
    private lateinit var select: PanelKey
    var selecting = false
        private set

    init {
        orientation = VERTICAL
        val pad = context.dp(4f).toInt()
        setPadding(pad, pad, pad, pad)
        fun key(
            label: String?, icon: Icon?, repeat: Boolean = false, functional: Boolean = true,
            press: Boolean = true, onClick: () -> Unit,
        ) =
            PanelKey(context, label, icon, repeat).also {
                it.functional = functional
                if (press) it.onPress = { listener.onKeyFeedbackPanel() }
                it.onClick = onClick
                keys += it
            }
        fun act(a: Action) = { listener.onEditAction(a, selecting) }
        fun row(vararg items: Pair<PanelKey, Float>) = LinearLayout(context).apply {
            orientation = HORIZONTAL
            for ((k, w) in items) addView(k, LayoutParams(0, LayoutParams.MATCH_PARENT, w))
            this@EditPanel.addView(this, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        }
        select = key("Select", null) { setSelecting(!selecting) }
        row(
            key("Home", null, onClick = act(Action.HOME)) to 1f,
            key(null, Icon.UP, repeat = true, functional = false, onClick = act(Action.UP)) to 1f,
            key("End", null, onClick = act(Action.END)) to 1f,
            key("Select all", null) { listener.onEditAction(Action.SELECT_ALL, false) } to 1.15f,
        )
        row(
            key(null, Icon.LEFT, repeat = true, functional = false, onClick = act(Action.LEFT)) to 1f,
            select to 1f,
            key(null, Icon.RIGHT, repeat = true, functional = false, onClick = act(Action.RIGHT)) to 1f,
            key("Copy", null) { listener.onEditAction(Action.COPY, false); setSelecting(false) } to 1.15f,
        )
        row(
            key("Undo", null) { listener.onEditAction(Action.UNDO, false) } to 1f,
            key(null, Icon.DOWN, repeat = true, functional = false, onClick = act(Action.DOWN)) to 1f,
            key("Redo", null) { listener.onEditAction(Action.REDO, false) } to 1f,
            key("Paste", null) { listener.onEditAction(Action.PASTE, false); setSelecting(false) } to 1.15f,
        )
        row(
            key("ABC", null) { setSelecting(false); listener.onPanelBack() } to 2f,
            key("Cut", null) { listener.onEditAction(Action.CUT, false); setSelecting(false) } to 1f,
            key(null, Icon.BACKSPACE, repeat = true, press = false) { listener.onPanelDelete() } to 1.15f, // gives its own feedback
        )
    }

    fun setSelecting(on: Boolean) {
        selecting = on
        select.active = on
    }

    fun applyTheme(t: KbTheme) {
        setBackgroundColor(if (t.hasBackdrop) 0 else t.background) // fun themes: the scene shows through
        for (k in keys) k.theme = t
    }
}
