package dev.larryjones.quietboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.UserManager

/**
 * All user settings, in app-private SharedPreferences excluded from backups.
 *
 * Settings live in device-protected storage, so the keyboard works right after a reboot, before
 * you've unlocked the phone once. Nothing personal goes there: text shortcuts, your saved emails and
 * addresses, learned words and clipboard history stay in the normal encrypted storage, which opens
 * only once you unlock.
 */
class Prefs(context: Context, unlocked: Boolean = unlocked(context)) {
    val sp: SharedPreferences = settingsStorage(context, unlocked).getSharedPreferences(FILE, Context.MODE_PRIVATE)
    /** Personal data (text shortcuts, your emails and addresses, recent emoji); null until the phone has been unlocked since it started. */
    private val personal: SharedPreferences? =
        if (unlocked) context.getSharedPreferences(PERSONAL_FILE, Context.MODE_PRIVATE) else null

    /** Emoji you used lately, as saved by the keyboard (null before the first unlock or if none). */
    fun emojiRecents(): String? = personal?.getString(EMOJI_RECENTS, null)

    fun saveEmojiRecents(value: String) {
        personal?.edit()?.putString(EMOJI_RECENTS, value)?.apply()
    }

    fun clearEmojiRecents() {
        personal?.edit()?.remove(EMOJI_RECENTS)?.apply()
    }

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
    /** Fun themes: the scene's motion (snow, petals, bubbles, twinkling lights). */
    val themeEffects get() = b(THEME_EFFECTS, true)
    /** Fun themes: a little burst from each tapped key. Off unless you turn it on. */
    val tapSparkles get() = b(TAP_SPARKLES, false)

    /** [Feedback.OFF], [Feedback.LIGHT], [Feedback.MEDIUM], [Feedback.STRONG] or [Feedback.SYSTEM] */
    val vibration get() = sp.getInt(VIBRATION, 1)
    /** "system", "light", "dark", "black", "seasonal" or a fun theme ([KbTheme.CHOICES]) */
    val theme: String get() = sp.getString(THEME, "system") ?: "system"
    val emojiSuggestions get() = b(EMOJI_SUGGESTIONS, true)
    val blockOffensive get() = b(BLOCK_OFFENSIVE, false)
    /** 0 off, -1 left hand, 1 right hand */
    val oneHanded get() = sp.getInt(ONE_HANDED, 0)

    /** Text shortcuts (personal dictionary): shortcut (lowercase) -> expansion. None before the first unlock. */
    fun shortcuts(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val store = personal ?: return out
        runCatching {
            val arr = org.json.JSONArray(store.getString(SHORTCUTS, "[]"))
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
        personal?.edit()?.putString(SHORTCUTS, arr.toString())?.apply()
    }

    /** Your email addresses, saved in Settings and offered as you type them. None before the first unlock. */
    fun emails(): List<String> = stringList(MY_EMAILS)

    fun saveEmails(list: List<String>) = saveStringList(MY_EMAILS, list)

    /** Your street addresses (one line each), offered as you type them. None before the first unlock. */
    fun addresses(): List<String> = stringList(MY_ADDRESSES)

    fun saveAddresses(list: List<String>) = saveStringList(MY_ADDRESSES, list)

    private fun stringList(key: String): List<String> {
        val store = personal ?: return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(store.getString(key, "[]"))
            (0 until arr.length()).map { arr.getString(it) }.filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    private fun saveStringList(key: String, list: List<String>) {
        val arr = org.json.JSONArray()
        for (v in list) arr.put(v)
        personal?.edit()?.putString(key, arr.toString())?.apply()
    }

    /** Call when text shortcuts, emails or addresses change elsewhere (the settings screen edits them). */
    fun onShortcutsChanged(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        personal?.registerOnSharedPreferenceChangeListener(listener)
    }

    fun stopListening(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        sp.unregisterOnSharedPreferenceChangeListener(listener)
        personal?.unregisterOnSharedPreferenceChangeListener(listener)
    }

    /** "google" (bundled Google Sans) or "system" */
    val font: String get() = sp.getString(FONT, "google") ?: "google"
    /** Keyboard height in percent. */
    val heightPercent get() = sp.getInt(HEIGHT, 100)

    companion object {
        const val FILE = "settings"
        const val PERSONAL_FILE = "personal"
        const val EMOJI_RECENTS = "emoji_recents"
        private const val STATE_FILE = "storage_state"

        /** Whether the phone has been unlocked since it started (encrypted storage is open). */
        fun unlocked(context: Context): Boolean = context.getSystemService(UserManager::class.java)?.isUserUnlocked != false

        /**
         * Where settings live. Once, after updating from a version that kept them in encrypted
         * storage, moves them over. Personal values (text shortcuts, recent emoji) are taken out of
         * the old file first, so they never reach device storage even for a moment. Any failed step
         * (a full disk) leaves the old file in place to try again next time. Needs an unlocked phone;
         * until then the keyboard runs on defaults. Writes synchronously on purpose: the move must be
         * finished before anything reads the settings.
         */
        @SuppressLint("ApplySharedPref")
        @Synchronized
        fun settingsStorage(context: Context, unlocked: Boolean = unlocked(context)): Context {
            val de = context.createDeviceProtectedStorageContext()
            if (!unlocked) return de
            val state = de.getSharedPreferences(STATE_FILE, Context.MODE_PRIVATE)
            if (state.getBoolean("moved", false)) return de
            val old = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            val keep = listOf(SHORTCUTS, EMOJI_RECENTS).mapNotNull { k -> old.getString(k, null)?.let { k to it } }
            if (keep.isNotEmpty()) {
                val personal = context.getSharedPreferences(PERSONAL_FILE, Context.MODE_PRIVATE).edit()
                for ((k, v) in keep) personal.putString(k, v)
                if (!personal.commit()) return de
                val trimmed = old.edit()
                for ((k, _) in keep) trimmed.remove(k)
                if (!trimmed.commit()) return de
            }
            if (!de.moveSharedPreferencesFrom(context, FILE)) return de
            state.edit().putBoolean("moved", true).commit()
            return de
        }
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
        const val THEME_EFFECTS = "theme_effects"
        const val TAP_SPARKLES = "tap_sparkles"
        const val VIBRATION = "vibration"
        const val THEME = "theme"
        const val HEIGHT = "height"
        const val FONT = "font"
        const val EMOJI_SUGGESTIONS = "emoji_suggestions"
        const val BLOCK_OFFENSIVE = "block_offensive"
        const val ONE_HANDED = "one_handed"
        const val SHORTCUTS = "shortcuts"
        const val MY_EMAILS = "my_emails"
        const val MY_ADDRESSES = "my_addresses"
        /** Bumped by the settings screen; the running keyboard reacts by wiping its memory too. */
        const val CLEAR_HISTORY = "clear_history_at"
        const val CLEAR_CLIPBOARD = "clear_clipboard_at"
    }
}
