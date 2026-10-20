package dev.larryjones.quietboard

import android.content.Context
import android.content.SharedPreferences

/** All user settings. Stored in app-private SharedPreferences, excluded from backups. */
class Prefs(context: Context) {
    val sp: SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun b(key: String, def: Boolean) = sp.getBoolean(key, def)

    val numberRow get() = b(NUMBER_ROW, false)
    val longPressSymbols get() = b(LONG_PRESS_SYMBOLS, false)
    val keyPreview get() = b(KEY_PREVIEW, true)
    val gestureTyping get() = b(GESTURE_TYPING, true)
    val gestureTrail get() = b(GESTURE_TRAIL, true)
    val spaceCursor get() = b(SPACE_CURSOR, true)
    val deleteSwipe get() = b(DELETE_SWIPE, true)
    val autocorrect get() = b(AUTOCORRECT, true)
    val autoCap get() = b(AUTO_CAP, true)
    val doubleSpacePeriod get() = b(DOUBLE_SPACE_PERIOD, true)
    val showSuggestions get() = b(SUGGESTIONS, true)
    val learnWords get() = b(LEARN_WORDS, true)
    val nextWord get() = b(NEXT_WORD, true)
    val clipboardHistory get() = b(CLIPBOARD_HISTORY, true)
    val sound get() = b(SOUND, false)
    val dynamicColor get() = b(DYNAMIC_COLOR, true)

    /** [Feedback.OFF], [Feedback.LIGHT], [Feedback.MEDIUM], [Feedback.STRONG] or [Feedback.SYSTEM] */
    val vibration get() = sp.getInt(VIBRATION, 1)
    /** "system", "light", "dark", "black" */
    val theme: String get() = sp.getString(THEME, "system") ?: "system"
    val emojiSuggestions get() = b(EMOJI_SUGGESTIONS, true)
    val blockOffensive get() = b(BLOCK_OFFENSIVE, false)
    /** 0 off, -1 left hand, 1 right hand */
    val oneHanded get() = sp.getInt(ONE_HANDED, 0)

    /** Text shortcuts (personal dictionary): shortcut (lowercase) -> expansion. */
    fun shortcuts(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        runCatching {
            val arr = org.json.JSONArray(sp.getString(SHORTCUTS, "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val k = o.getString("k").trim().lowercase(java.util.Locale.US)
                val v = o.getString("v")
                if (k.isNotEmpty() && v.isNotEmpty()) out[k] = v
            }
        }
        return out
    }

    fun saveShortcuts(map: Map<String, String>) {
        val arr = org.json.JSONArray()
        for ((k, v) in map) arr.put(org.json.JSONObject().put("k", k).put("v", v))
        sp.edit().putString(SHORTCUTS, arr.toString()).apply()
    }

    /** "google" (bundled Google Sans) or "system" */
    val font: String get() = sp.getString(FONT, "google") ?: "google"
    /** Keyboard height in percent. */
    val heightPercent get() = sp.getInt(HEIGHT, 100)

    companion object {
        const val FILE = "settings"
        const val NUMBER_ROW = "number_row"
        const val LONG_PRESS_SYMBOLS = "long_press_symbols"
        const val KEY_PREVIEW = "key_preview"
        const val GESTURE_TYPING = "gesture_typing"
        const val GESTURE_TRAIL = "gesture_trail"
        const val SPACE_CURSOR = "space_cursor"
        const val DELETE_SWIPE = "delete_swipe"
        const val AUTOCORRECT = "autocorrect"
        const val AUTO_CAP = "auto_cap"
        const val DOUBLE_SPACE_PERIOD = "double_space_period"
        const val SUGGESTIONS = "suggestions"
        const val LEARN_WORDS = "learn_words"
        const val NEXT_WORD = "next_word"
        const val CLIPBOARD_HISTORY = "clipboard_history"
        const val SOUND = "sound"
        const val DYNAMIC_COLOR = "dynamic_color"
        const val VIBRATION = "vibration"
        const val THEME = "theme"
        const val HEIGHT = "height"
        const val FONT = "font"
        const val EMOJI_SUGGESTIONS = "emoji_suggestions"
        const val BLOCK_OFFENSIVE = "block_offensive"
        const val ONE_HANDED = "one_handed"
        const val SHORTCUTS = "shortcuts"
        /** Bumped by the settings screen; the running keyboard reacts by wiping its memory too. */
        const val CLEAR_HISTORY = "clear_history_at"
        const val CLEAR_CLIPBOARD = "clear_clipboard_at"
    }
}
