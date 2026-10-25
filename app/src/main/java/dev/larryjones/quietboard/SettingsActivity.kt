package dev.larryjones.quietboard

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate
import java.util.Locale

class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var root: LinearLayout
    private lateinit var step1: TextView
    private lateinit var step2: TextView
    private var accent = 0
    private var textPrimary = 0
    private var textSecondary = 0
    private var card = 0
    private var shortcutSummary: TextView? = null
    private val savedSummaries = HashMap<SavedKind, TextView>()

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun dpi(v: Float) = dp(v).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        resolveColors()

        val scroll = ScrollView(this).apply { isFillViewport = true }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(20f), dpi(28f), dpi(20f), dpi(40f))
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(textPrimary)
        })
        root.addView(TextView(this).apply {
            text = "A Gboard-style keyboard with no internet permission. Everything stays on this phone."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(textSecondary)
            setPadding(0, dpi(4f), 0, dpi(16f))
        })

        // --- Setup
        val setup = card()
        step1 = actionRow(setup, "1. Turn on Quietboard", "") {
            try {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            } catch (_: android.content.ActivityNotFoundException) {
                // Some managed/OEM builds lack this screen.
                Toast.makeText(this, "Open Settings \u2192 System \u2192 Keyboard and enable Quietboard", Toast.LENGTH_LONG).show()
            }
        }
        step2 = actionRow(setup, "2. Switch to Quietboard", "") {
            getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
        }
        root.addView(EditText(this).apply {
            hint = "Try it here"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            setPadding(dpi(16f), dpi(14f), dpi(16f), dpi(14f))
            background = rounded(card, dp(16f))
            setTextColor(textPrimary)
            setHintTextColor(textSecondary)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dpi(12f) }
        })

        // --- Typing
        val typing = section("Typing")
        switchRow(typing, "Autocorrect", "Fixes typos when you press space. Backspace undoes it.", Prefs.AUTOCORRECT, true)
        switchRow(typing, "Auto-capitalization", "Capitalize the first word of each sentence", Prefs.AUTO_CAP, true)
        switchRow(typing, "Double-space period", "Double-tap space to type \". \"", Prefs.DOUBLE_SPACE_PERIOD, true)
        switchRow(typing, "Show suggestion strip", null, Prefs.SUGGESTIONS, true)
        switchRow(typing, "Learn new words", "Remembers words you type, on this device only", Prefs.LEARN_WORDS, true)
        switchRow(typing, "Next-word suggestions", "Common next words, then what you usually type (learned on this phone)", Prefs.NEXT_WORD, true)
        switchRow(typing, "Block offensive words", "Hide swear words from suggestions and glide typing", Prefs.BLOCK_OFFENSIVE, false)
        switchRow(typing, "Emoji suggestions", "Offer \uD83C\uDF55 when you type \u201Cpizza\u201D", Prefs.EMOJI_SUGGESTIONS, true)
        shortcutSummary = actionRow(typing, "Text shortcuts", "") { openShortcuts() }
        updateShortcutSummary()
        for (kind in SavedKind.entries) {
            savedSummaries[kind] = actionRow(typing, kind.title, "") { openSaved(kind) }
            updateSavedSummary(kind)
        }
        switchRow(typing, "Remember emails & addresses", "Adds the ones you type in email and address fields to the lists above. Long-press one in the suggestion strip to forget it.", Prefs.REMEMBER_INFO, true)

        // --- Glide
        val glide = section("Gestures")
        switchRow(glide, "Glide typing", "Slide across letters to type a word", Prefs.GESTURE_TYPING, true)
        switchRow(glide, "Show glide trail", null, Prefs.GESTURE_TRAIL, true)
        switchRow(glide, "Space bar cursor control", "Slide on the space bar to move the cursor", Prefs.SPACE_CURSOR, true)
        switchRow(glide, "Swipe to delete words", "Slide left from backspace to select and delete words", Prefs.DELETE_SWIPE, true)

        // --- Keys
        val keys = section("Keys")
        switchRow(keys, "Number row", null, Prefs.NUMBER_ROW, false)
        choiceRowString(
            keys, "Split keyboard", listOf("On big screens (unfolded Fold, tablets)", "Also on phones in landscape", "Off"),
            listOf("auto", "wide", "off"), Prefs.SPLIT_KEYBOARD, "auto",
        )
        switchRow(keys, "Long press for symbols", "Show symbol hints on letter keys", Prefs.LONG_PRESS_SYMBOLS, false)
        switchRow(keys, "Popup on keypress", null, Prefs.KEY_PREVIEW, true)
        choiceRow(
            keys, "Vibration on keypress", listOf("Off", "Light", "Medium", "Strong", "Phone default"),
            listOf(Feedback.OFF, Feedback.LIGHT, Feedback.MEDIUM, Feedback.STRONG, Feedback.SYSTEM), Prefs.VIBRATION, Feedback.LIGHT,
        ) {
            // Let the user feel the choice right away: a key press, then a firmer function key.
            val f = Feedback(this, prefs).apply { attach(window.decorView) }
            f.haptic(Haptic.KEY)
            window.decorView.postDelayed({ f.haptic(Haptic.FUNCTION) }, 160L)
        }
        switchRow(keys, "Sound on keypress", null, Prefs.SOUND, false)

        // --- Look
        val look = section("Appearance")
        themeRow(look)
        switchRow(look, "Scene motion", "Fun themes: snow, petals, bubbles and other motion while you type", Prefs.THEME_EFFECTS, true)
        switchRow(look, "Tap sparkles", "Fun themes: a little burst from each key you tap", Prefs.TAP_SPARKLES, false)
        choiceRowString(look, "Font", listOf("Google Sans (Gboard)", "System font"), listOf("google", "system"), Prefs.FONT, "google")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            switchRow(look, "Material You colors", "Match your wallpaper colors (Light, Dark, Black and Follow system)", Prefs.DYNAMIC_COLOR, true)
        }
        choiceRow(look, "Keyboard height", listOf("Short", "Normal", "Tall", "Extra tall"), listOf(88, 100, 112, 124), Prefs.HEIGHT, 100)

        // --- Clipboard & privacy
        val privacy = section("Clipboard & privacy")
        switchRow(privacy, "Clipboard history", "Keeps copied text for 1 hour, pinned items until you unpin them. Skips password-manager copies, images, and copies made while you're in a password or incognito field.", Prefs.CLIPBOARD_HISTORY, true) { on ->
            // Off also forgets what was recorded (the running keyboard does the same in memory).
            if (!on) ClipboardHistory.clearUnpinnedOnDisk(filesDir)
        }
        actionRow(privacy, "Clear clipboard history", null) {
            ClipboardHistory.clearUnpinnedOnDisk(filesDir)
            prefs.sp.edit().putLong(Prefs.CLEAR_CLIPBOARD, System.currentTimeMillis()).apply()
            Toast.makeText(this, "Clipboard history cleared (pinned items kept)", Toast.LENGTH_SHORT).show()
        }
        actionRow(privacy, "Clear learned words", "Forget every word, phrase and recent emoji Quietboard has learned") {
            AlertDialog.Builder(this)
                .setTitle("Clear learned words?")
                .setMessage("This can't be undone.")
                .setPositiveButton("Clear") { _, _ ->
                    UserHistory.deleteFiles(filesDir)
                    prefs.clearEmojiRecents()
                    prefs.sp.edit().putLong(Prefs.CLEAR_HISTORY, System.currentTimeMillis()).apply()
                    Toast.makeText(this, "Learned words cleared", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        privacy.addView(TextView(this).apply {
            text = "• No INTERNET permission — Android blocks all network access for this app.\n" +
                "• No analytics, crash reporting, or Google libraries.\n" +
                "• Learned words, clipboard and settings are excluded from cloud backup.\n" +
                "• Passwords and incognito fields are never learned.\n" +
                "• Your saved emails and addresses stay on this phone and never show on the lock screen."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(textSecondary)
            setLineSpacing(dp(3f), 1f)
            setPadding(dpi(16f), dpi(12f), dpi(16f), dpi(16f))
        })

        root.addView(TextView(this).apply {
            val v = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
            text = "Quietboard $v · words: AOSP (Apache 2.0) + FrequencyWords/OpenSubtitles (CC BY-SA 4.0) · Google Sans (OFL)"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(textSecondary)
            gravity = Gravity.CENTER
            setPadding(0, dpi(24f), 0, 0)
        })
    }

    override fun onResume() {
        super.onResume()
        refreshSetup()
        SavedKind.entries.forEach(::updateSavedSummary) // the keyboard may have remembered more meanwhile
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refreshSetup()
    }

    private fun refreshSetup() {
        val imm = getSystemService(InputMethodManager::class.java) ?: return
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
        val selected = current.startsWith("$packageName/")
        step1.text = if (enabled) "Enabled ✓" else "Open Keyboard settings and enable Quietboard"
        step2.text = if (selected) "Quietboard is your keyboard ✓" else if (enabled) "Tap to pick Quietboard" else "Do step 1 first"
    }

    // ---------------------------------------------------------------- text shortcuts

    private fun updateShortcutSummary() {
        val n = prefs.shortcuts().size
        shortcutSummary?.text = if (n == 0) "Type a short code, get the full text in the suggestion strip (e.g. omw \u2192 On my way!)"
        else "$n shortcut${if (n == 1) "" else "s"}"
    }

    private fun openShortcuts() {
        val map = prefs.shortcuts()
        val keys = map.keys.toList()
        val items = if (keys.isEmpty()) arrayOf("No shortcuts yet \u2014 tap Add") else keys.map { "$it  \u2192  ${map[it]}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Text shortcuts")
            .setItems(items) { _, which -> if (keys.isNotEmpty()) editShortcut(keys[which]) else editShortcut(null) }
            .setPositiveButton("Add") { _, _ -> editShortcut(null) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun editShortcut(existing: String?) {
        val map = LinkedHashMap(prefs.shortcuts())
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(22f), dpi(8f), dpi(22f), 0)
        }
        val keyField = EditText(this).apply {
            hint = "Shortcut, e.g. omw"
            isSingleLine = true
            setText(existing ?: "")
        }
        val valueField = EditText(this).apply {
            hint = "Expands to, e.g. On my way!"
            setText(existing?.let { map[it] } ?: "")
        }
        box.addView(keyField)
        box.addView(valueField)
        val b = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "New shortcut" else "Edit shortcut")
            .setView(box)
            .setPositiveButton("Save", null) // validated below, without closing the dialog on errors
            .setNegativeButton("Cancel") { _, _ -> openShortcuts() }
        if (existing != null) {
            b.setNeutralButton("Delete") { _, _ ->
                map.remove(existing)
                prefs.saveShortcuts(map)
                updateShortcutSummary()
                openShortcuts()
            }
        }
        val dialog = b.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                // English rules like the lookup: a Turkish phone must not store "IDK" as "ıdk".
                val k = keyField.text.toString().trim().lowercase(Locale.US)
                val v = valueField.text.toString().trim()
                when {
                    k.isEmpty() -> keyField.error = "Enter a shortcut"
                    v.isEmpty() -> valueField.error = "Enter the text it expands to"
                    // The keyboard only offers shortcuts for words it is composing: letters first, then
                    // letters, digits or apostrophes. Anything else ("2day", "e.g") would never appear.
                    !SHORTCUT_CODE.matches(k) -> keyField.error = "Start with a letter; use only letters, digits and '"
                    k != existing && map.containsKey(k) -> keyField.error = "\u201C$k\u201D already expands to \u201C${map[k]}\u201D"
                    else -> {
                        // Rename in place so the list keeps its order.
                        val updated = LinkedHashMap<String, String>()
                        if (existing != null && existing in map) {
                            for ((key, value) in map) if (key == existing) updated[k] = v else updated[key] = value
                        } else {
                            updated.putAll(map); updated[k] = v
                        }
                        prefs.saveShortcuts(updated)
                        updateShortcutSummary()
                        dialog.dismiss()
                        openShortcuts()
                    }
                }
            }
        }
        dialog.show()
    }

    // ---------------------------------------------------------------- your emails and addresses

    /** Your own emails and addresses, offered in the suggestion strip as you start typing them ([PersonalInfo]). */
    private enum class SavedKind(val title: String, val noun: String, val empty: String, val hint: String, val inputType: Int) {
        EMAIL(
            "Your email addresses", "email",
            "Shows your email in the suggestion strip as you start typing it",
            "you@example.com",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
        ),
        ADDRESS(
            "Your addresses", "address",
            "Shows your address in the suggestion strip as you start typing it",
            "123 Main St, Phoenix, AZ 85001",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS or InputType.TYPE_TEXT_FLAG_CAP_WORDS,
        ),
    }

    private fun savedList(kind: SavedKind) = if (kind == SavedKind.EMAIL) prefs.emails() else prefs.addresses()

    private fun saveList(kind: SavedKind, list: List<String>) {
        if (kind == SavedKind.EMAIL) prefs.saveEmails(list) else prefs.saveAddresses(list)
        updateSavedSummary(kind)
    }

    private fun updateSavedSummary(kind: SavedKind) {
        val n = savedList(kind).size
        savedSummaries[kind]?.text = if (n == 0) "${kind.empty}. Kept on this phone only."
        else "$n saved · kept on this phone only"
    }

    private fun openSaved(kind: SavedKind) {
        val list = savedList(kind)
        val items = if (list.isEmpty()) arrayOf("None yet — tap Add") else list.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(kind.title)
            .setItems(items) { _, which -> editSaved(kind, list.getOrNull(which)) }
            .setPositiveButton("Add") { _, _ -> editSaved(kind, null) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun editSaved(kind: SavedKind, existing: String?) {
        val list = savedList(kind).toMutableList()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(22f), dpi(8f), dpi(22f), 0)
        }
        val field = EditText(this).apply {
            hint = kind.hint
            inputType = kind.inputType
            isSingleLine = true
            setText(existing ?: "")
            // Saved here only on Save: the keyboard mustn't remember a half-edited or cancelled one.
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        box.addView(field)
        val b = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "New ${kind.noun}" else "Edit ${kind.noun}")
            .setView(box)
            .setPositiveButton("Save", null) // validated below, without closing the dialog on errors
            .setNegativeButton("Cancel") { _, _ -> openSaved(kind) }
        if (existing != null) {
            b.setNeutralButton("Delete") { _, _ ->
                list.remove(existing)
                saveList(kind, list)
                openSaved(kind)
            }
        }
        val dialog = b.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val v = if (kind == SavedKind.EMAIL) field.text.toString().trim() else PersonalInfo.oneLine(field.text.toString())
                val dup = list.any { it.equals(v, ignoreCase = true) && !it.equals(existing, ignoreCase = true) }
                when {
                    v.isEmpty() -> field.error = "Enter your ${kind.noun}"
                    kind == SavedKind.EMAIL && !PersonalInfo.looksLikeEmail(v) -> field.error = "That doesn't look like an email address"
                    dup -> field.error = "Already saved"
                    else -> {
                        // Edit in place so the list keeps its order (the first one is offered first).
                        val i = if (existing != null) list.indexOf(existing) else -1
                        if (i >= 0) list[i] = v else list += v
                        saveList(kind, list)
                        dialog.dismiss()
                        openSaved(kind)
                    }
                }
            }
        }
        dialog.show()
    }

    private companion object {
        val SHORTCUT_CODE = Regex("^\\p{L}[\\p{L}\\p{N}']*$")
    }

    // ---------------------------------------------------------------- UI helpers

    private fun resolveColors() {
        val tv = TypedValue()
        fun attr(id: Int, fallback: Int): Int {
            if (!theme.resolveAttribute(id, tv, true)) return fallback
            return if (tv.resourceId != 0) getColor(tv.resourceId) else tv.data
        }
        val dark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        accent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getColor(if (dark) android.R.color.system_accent1_200 else android.R.color.system_accent1_600)
        } else attr(android.R.attr.colorAccent, 0xFF1A73E8.toInt())
        textPrimary = attr(android.R.attr.textColorPrimary, if (dark) 0xFFE8EAED.toInt() else 0xFF202124.toInt())
        textSecondary = attr(android.R.attr.textColorSecondary, if (dark) 0xFF9AA0A6.toInt() else 0xFF5F6368.toInt())
        card = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getColor(if (dark) android.R.color.system_neutral2_800 else android.R.color.system_neutral2_50)
        } else if (dark) 0xFF2D2F33.toInt() else 0xFFF1F3F4.toInt()
    }

    private fun rounded(color: Int, r: Float) = GradientDrawable().apply { setColor(color); cornerRadius = r }

    private fun card(): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(card, dp(20f))
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dpi(6f) }
        }
        root.addView(c)
        return c
    }

    private fun section(title: String): LinearLayout {
        root.addView(TextView(this).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(accent)
            setPadding(dpi(8f), dpi(26f), 0, dpi(4f))
        })
        return card()
    }

    private fun rowBase(parent: LinearLayout, title: String, summary: String?): Triple<LinearLayout, TextView, TextView> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dpi(64f)
            setPadding(dpi(16f), dpi(10f), dpi(16f), dpi(10f))
            isClickable = true
            val out = TypedValue()
            if (theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)) {
                foreground = getDrawable(out.resourceId)
            }
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val t = TextView(this).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(textPrimary)
        }
        val s = TextView(this).apply {
            text = summary ?: ""
            visibility = if (summary.isNullOrEmpty()) View.GONE else View.VISIBLE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(textSecondary)
        }
        texts.addView(t)
        texts.addView(s)
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        parent.addView(row)
        return Triple(row, t, s)
    }

    private fun switchRow(
        parent: LinearLayout, title: String, summary: String?, key: String, def: Boolean,
        onChange: ((Boolean) -> Unit)? = null,
    ) {
        val (row, _, _) = rowBase(parent, title, summary)
        @Suppress("DEPRECATION")
        val sw = Switch(this).apply {
            isChecked = prefs.sp.getBoolean(key, def)
            thumbTintList = null
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(accent, textSecondary and 0x55FFFFFF),
            )
            setOnCheckedChangeListener { _, v ->
                prefs.sp.edit().putBoolean(key, v).apply()
                onChange?.invoke(v)
            }
        }
        // One TalkBack stop per setting: the row is the switch ("Scene motion, on, switch").
        sw.isClickable = false
        sw.isFocusable = false
        sw.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        row.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Switch::class.java.name
                info.isCheckable = true
                info.isChecked = sw.isChecked
            }
        }
        row.addView(sw)
        row.setOnClickListener { sw.toggle() }
    }

    private fun choiceRow(
        parent: LinearLayout, title: String, labels: List<String>, values: List<Int>, key: String, def: Int,
        onPicked: (() -> Unit)? = null,
    ) {
        val (row, _, summary) = rowBase(parent, title, labels[values.indexOf(prefs.sp.getInt(key, def)).coerceAtLeast(0)])
        summary.visibility = View.VISIBLE
        row.setOnClickListener {
            val cur = values.indexOf(prefs.sp.getInt(key, def)).coerceAtLeast(0)
            AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(labels.toTypedArray(), cur) { d, which ->
                    prefs.sp.edit().putInt(key, values[which]).apply()
                    summary.text = labels[which]
                    d.dismiss()
                    onPicked?.invoke()
                }
                .show()
        }
    }

    private fun choiceRowString(parent: LinearLayout, title: String, labels: List<String>, values: List<String>, key: String, def: String) {
        val (row, _, summary) = rowBase(parent, title, labels[values.indexOf(prefs.sp.getString(key, def)).coerceAtLeast(0)])
        summary.visibility = View.VISIBLE
        row.setOnClickListener {
            val cur = values.indexOf(prefs.sp.getString(key, def)).coerceAtLeast(0)
            AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(labels.toTypedArray(), cur) { d, which ->
                    prefs.sp.edit().putString(key, values[which]).apply()
                    summary.text = labels[which]
                    d.dismiss()
                }
                .show()
        }
    }

    // ---------------------------------------------------------------- theme picker

    private fun themeRow(parent: LinearLayout) {
        val (row, _, summary) = rowBase(parent, "Theme", themeLabel(prefs.theme))
        summary.visibility = View.VISIBLE
        row.setOnClickListener { pickTheme(summary) }
    }

    private fun themeLabel(id: String): String {
        val choice = KbTheme.CHOICES.firstOrNull { it.id == id } ?: KbTheme.CHOICES[0]
        if (choice.id != "seasonal") return choice.label
        val now = KbTheme.effectiveId("seasonal", LocalDate.now())
        return KbTheme.CHOICES.firstOrNull { it.id == now && now != "system" }?.let { "Seasonal \u00B7 ${it.label} now" } ?: "Seasonal"
    }

    /** Every theme as a card with a miniature of the real keyboard in it; a tap picks it. */
    private fun pickTheme(summary: TextView) {
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(12f), dpi(4f), dpi(12f), dpi(8f))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Theme")
            .setView(ScrollView(this).apply { addView(list) })
            .setNegativeButton("Cancel", null)
            .create()
        val current = prefs.theme
        val today = LocalDate.now()
        fun section(title: String, choices: List<KbTheme.Choice>) {
            list.addView(TextView(this).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(accent)
                setPadding(dpi(6f), dpi(14f), 0, dpi(6f))
            })
            for (pair in choices.chunked(2)) {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                for (choice in pair) {
                    val card = themeCard(choice, choice.id == current, today) {
                        prefs.sp.edit().putString(Prefs.THEME, choice.id).apply()
                        summary.text = themeLabel(choice.id)
                        dialog.dismiss()
                    }
                    row.addView(card, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(dpi(4f), dpi(4f), dpi(4f), dpi(4f))
                    })
                }
                if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f).apply { setMargins(dpi(4f), 0, dpi(4f), 0) })
                list.addView(row)
            }
        }
        section("Everyday", KbTheme.CHOICES.filter { it.everyday })
        section("Fun", KbTheme.CHOICES.filter { !it.everyday })
        dialog.show()
    }

    private fun themeCard(choice: KbTheme.Choice, selected: Boolean, today: LocalDate, onPick: () -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(6f), dpi(6f), dpi(6f), dpi(8f))
            background = GradientDrawable().apply {
                cornerRadius = dp(16f)
                setColor(if (selected) KbTheme.blend(card, accent, 0.16f) else card)
                if (selected) setStroke(dpi(2f), accent)
            }
            isClickable = true
            val out = TypedValue()
            if (theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)) foreground = getDrawable(out.resourceId)
            // One TalkBack item per card: name, what it is, and whether it's the current one.
            contentDescription = choice.label + ". " + choice.blurb + (if (selected) ". Selected" else "")
            setOnClickListener { onPick() }
        }
        val look = KbTheme.forChoice(this, prefs, choice.id, today)
        box.addView(KeyboardPreview(this, look), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(TextView(this).apply {
            text = if (selected) "\u2713 " + themeLabel(choice.id) else themeLabel(choice.id)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (selected) accent else textPrimary)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dpi(4f), dpi(8f), dpi(4f), 0)
        })
        box.addView(TextView(this).apply {
            text = choice.blurb
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(textSecondary)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dpi(4f), dpi(2f), dpi(4f), 0)
        })
        return box
    }

    private fun actionRow(parent: LinearLayout, title: String, summary: String?, onClick: () -> Unit): TextView {
        val (row, _, s) = rowBase(parent, title, summary)
        s.visibility = View.VISIBLE
        row.setOnClickListener { onClick() }
        return s
    }
}
