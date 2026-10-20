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
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

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
        switchRow(typing, "Next-word suggestions", "Predicts from your own typing history", Prefs.NEXT_WORD, true)
        switchRow(typing, "Block offensive words", "Hide swear words from suggestions and glide typing", Prefs.BLOCK_OFFENSIVE, false)
        switchRow(typing, "Emoji suggestions", "Offer \uD83C\uDF55 when you type \u201Cpizza\u201D", Prefs.EMOJI_SUGGESTIONS, true)
        shortcutSummary = actionRow(typing, "Text shortcuts", "") { openShortcuts() }
        updateShortcutSummary()

        // --- Glide
        val glide = section("Gestures")
        switchRow(glide, "Glide typing", "Slide across letters to type a word", Prefs.GESTURE_TYPING, true)
        switchRow(glide, "Show glide trail", null, Prefs.GESTURE_TRAIL, true)
        switchRow(glide, "Space bar cursor control", "Slide on the space bar to move the cursor", Prefs.SPACE_CURSOR, true)
        switchRow(glide, "Swipe to delete words", "Slide left from backspace to select and delete words", Prefs.DELETE_SWIPE, true)

        // --- Keys
        val keys = section("Keys")
        switchRow(keys, "Number row", null, Prefs.NUMBER_ROW, false)
        switchRow(keys, "Long press for symbols", "Show symbol hints on letter keys", Prefs.LONG_PRESS_SYMBOLS, false)
        switchRow(keys, "Popup on keypress", null, Prefs.KEY_PREVIEW, true)
        choiceRow(keys, "Vibration on keypress", listOf("Off", "Light", "Medium", "Strong"), listOf(0, 1, 2, 3), Prefs.VIBRATION, 1)
        switchRow(keys, "Sound on keypress", null, Prefs.SOUND, false)

        // --- Look
        val look = section("Appearance")
        choiceRowString(look, "Theme", listOf("Follow system", "Light", "Dark", "Black (OLED)"), listOf("system", "light", "dark", "black"), Prefs.THEME, "system")
        choiceRowString(look, "Font", listOf("Google Sans (Gboard)", "System font"), listOf("google", "system"), Prefs.FONT, "google")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            switchRow(look, "Material You colors", "Match your wallpaper colors", Prefs.DYNAMIC_COLOR, true)
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
                    prefs.sp.edit().remove(QuietIME.KEY_RECENTS)
                        .putLong(Prefs.CLEAR_HISTORY, System.currentTimeMillis()).apply()
                    Toast.makeText(this, "Learned words cleared", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        privacy.addView(TextView(this).apply {
            text = "• No INTERNET permission — Android blocks all network access for this app.\n" +
                "• No analytics, crash reporting, or Google libraries.\n" +
                "• Learned words, clipboard and settings are excluded from cloud backup.\n" +
                "• Passwords and incognito fields are never learned."
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
                val k = keyField.text.toString().trim().lowercase()
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
        row.addView(sw)
        row.setOnClickListener { sw.toggle() }
    }

    private fun choiceRow(parent: LinearLayout, title: String, labels: List<String>, values: List<Int>, key: String, def: Int) {
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

    private fun actionRow(parent: LinearLayout, title: String, summary: String?, onClick: () -> Unit): TextView {
        val (row, _, s) = rowBase(parent, title, summary)
        s.visibility = View.VISIBLE
        row.setOnClickListener { onClick() }
        return s
    }
}
