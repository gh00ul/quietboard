package dev.larryjones.quietboard

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.icu.text.BreakIterator
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.abs

class QuietIME : InputMethodService(),
    KeyboardView.Listener, TopBar.Listener, EmojiPanel.Listener, ClipboardPanel.Listener, EditPanel.Listener {

    private enum class Panel { KEYS, EMOJI, CLIPBOARD, EDIT }

    private class Correction(val original: String, val corrected: String, val separator: String, val prev: String?)

    private lateinit var prefs: Prefs
    private lateinit var history: UserHistory
    private lateinit var clipboard: ClipboardHistory
    private lateinit var feedback: Feedback
    private val main = Handler(Looper.getMainLooper())

    private var suggest: Suggest? = null
    private var decoder: GestureDecoder? = null

    /** Unit tests plug a fake editor in here; otherwise this is the real current connection. */
    internal var testConnection: InputConnection? = null
    private val conn: InputConnection? get() = testConnection ?: currentInputConnection

    // Glide decoding runs off the UI thread. A pending result is applied when it arrives, or
    // earlier (blocking briefly) as soon as any other input comes in, so text stays in order.
    private val gestureExec: ExecutorService = Executors.newSingleThreadExecutor()
    private var pendingGesture: Future<List<String>>? = null
    private var pendingGestureId = 0
    // Live glide preview: what the previous word makes likely (captured when the glide starts), which
    // glide is on screen, and whether a preview decode is already running (never queue them up).
    private var glideContext: Map<String, Float> = emptyMap()
    private var glideId = 0
    private var glidePreviewing = false
    private var previewBusy = false

    // Views (recreated by onCreateInputView)
    private var root: InputRoot? = null
    private var topBar: TopBar? = null
    private var keyboard: KeyboardView? = null
    private var overlay: PopupOverlay? = null
    private var content: FrameLayout? = null
    private var emojiPanel: EmojiPanel? = null
    private var clipPanel: ClipboardPanel? = null
    private var editPanel: EditPanel? = null
    private var swallowBackUp = false
    private var emojiKeywords: Map<String, String> = emptyMap()
    private var theme: KbTheme? = null
    private var panel = Panel.KEYS

    // Editor configuration
    /** Identifies a field across restarts. WebView/Compose share one view id for all their fields, so the
     *  field name, hint and label are part of it too. */
    private data class EditorKey(
        val pkg: String?, val fieldId: Int, val inputType: Int, val imeOptions: Int,
        val fieldName: String?, val hint: String?, val label: String?,
    )
    private var editorInfo: EditorInfo? = null
    /** The field input is going to right now, keyboard shown or not (null between fields). */
    private var activeEditor: EditorInfo? = null
    private var lastEditor: EditorKey? = null
    private var builtOptions: LayoutOptions? = null
    private var layoutId = LayoutId.ALPHA
    private var baseLayout = LayoutId.ALPHA
    private var field = FieldKind.TEXT
    private var isPassword = false
    /** A real text field (not a password/number/phone/date field): auto-spacing and ". " belong here. */
    private var textField = false
    /** On the lock screen: no clipboard, settings or learned predictions for whoever holds the phone. */
    private var onLockScreen = false
    private var destroyed = false
    /** Password or incognito field: nothing typed here is learned, and copies aren't recorded. */
    private var privateField = false
    /** Nothing is learned here, and what was learned isn't shown: private fields, the lock screen, learning off. */
    private var noLearning = false
    /** All-caps field (promo codes, number plates): every letter is a capital. */
    private var capChars = false
    private var suggestionsOn = false
    private var autocorrectOn = false
    private var autoCapOn = false

    // Typing state
    private var shift = ShiftState.OFF
    private var shiftHeld = false
    private var shiftManual = false
    /** Long ago, so the very first tap is never the "second tap" of a double tap. */
    private var lastShiftTap = Long.MIN_VALUE / 2
    private val composing = StringBuilder()
    private var composingPrev: String? = null
    private var composingFromGesture = false
    /**
     * The word being typed is stuck to the text before it ("x@aol", "site.org", "4th"): part of an
     * address or a number, so space never autocorrects it.
     */
    private var composingGlued = false
    /** The cached selection came from onUpdateSelection, so it's fresher than EditorInfo's initial one. */
    private var selFromUpdate = false
    /** The editor reported no composing region while we think a word is being typed (see onUpdateSelection). */
    private var composingSuspect = false
    /** A word made editable again (backspace into it, or typing at its end); not re-learned or
     *  autocorrected unless the user actually changes it. */
    private var resumedWord: String? = null
    /** The original word restored by undoing an autocorrection; trusted once it is committed unchanged. */
    private var revertedWord: String? = null
    private var gestureAlternatives: List<String> = emptyList()
    private var pendingAutocorrect: String? = null
    private var lastCorrection: Correction? = null
    private val rejected = HashSet<String>()
    private var weakSpace = false
    private var phantomSpace = false
    private var lastSpaceTime = 0L
    private var symbolTyped = false
    private var selStart = -1
    private var selEnd = -1
    private var resumeAfterDelete = false
    // Cursor anchor: where our last commit left the cursor. If it moves elsewhere (the user tapped in
    // the text), "undo autocorrect", the space swap and double-space no longer apply.
    private var anchorPending = false
    private var anchorPos = -1
    // Shift: an auto-capitalised shift that was pressed (decided on release), and a tap that turned
    // auto-caps off (a quick second tap then locks).
    private var autoShiftPressed = false
    private var tapUnshiftedAuto = false

    // Swipe-to-delete state: words before [swipeStart] join the selection, which ends at [swipeAnchor].
    private var swipeAnchor = -1
    private var swipeStart = -1
    private var swipeOrigStart = -1
    private var swipeOrigEnd = -1
    private var swipeBounds = IntArray(0)
    private var swipeLen = 0

    private val emojiRecents = ArrayList<String>()

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            Prefs.CLEAR_HISTORY -> {
                history.clear()
                emojiRecents.clear()
            }
            Prefs.CLEAR_CLIPBOARD -> { clipboard.clear(); refreshTopBar() }
            Prefs.CLIPBOARD_HISTORY -> {
                // Turning history off also forgets what was recorded (pinned clips stay).
                if (!prefs.clipboardHistory) clipboard.clear()
                refreshTopBar()
            }
            KEY_RECENTS, Prefs.ONE_HANDED -> Unit // written by the keyboard itself
            else -> {
                configDirty = true
                // Changed while the keyboard is up (Settings' "Try it here" box): apply right away.
                if (isInputViewShown) main.post { reapplyConfig() }
            }
        }
    }

    private fun reapplyConfig() {
        val info = editorInfo ?: return
        if (!configDirty || !isInputViewShown) return
        // Settle the word first: turning suggestions off mid-word would make the next letter replace it.
        flushGesture()
        if (composing.isNotEmpty()) conn?.let { ic ->
            ic.beginBatchEdit()
            commitComposingAsTyped()
            ic.endBatchEdit()
        }
        applyConfig()
        configureEditor(info)
        if (keyboard?.layout == null || layoutOptions() != builtOptions) setLayout(layoutId)
        updateShift()
        refreshSuggestions()
    }
    private var configDirty = true
    private var effectsOn: Boolean? = null

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        prefs.sp.registerOnSharedPreferenceChangeListener(prefListener)
        history = UserHistory(filesDir)
        history.loadAsync(main)
        feedback = Feedback(this, prefs)
        clipboard = ClipboardHistory(this, prefs)
        clipboard.onChange = { main.post { refreshTopBar() } }
        // Best effort: skip copies made while a password or incognito field is the active editor.
        clipboard.skipCapture = {
            getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true ||
                activeEditor?.let { isPrivateEditor(it) } ?: false
        }
        clipboard.start()
        prefs.sp.getString(KEY_RECENTS, null)?.split(SEP)?.filter { it.isNotEmpty() }?.let { emojiRecents.addAll(it) }
        Thread({
            val d = runCatching { EngineCache.dictionary(this) }.getOrNull()
            val kw = EngineCache.emojiKeywords(this)
            runCatching { EmojiData.get(this) } // warm the (glyph-filtered) emoji grid off the UI thread
            if (prefs.font != "system") runCatching { KbFonts.bundled(this) } // decode fonts off the UI thread
            main.post {
                if (destroyed) return@post
                emojiKeywords = kw
                if (d != null) {
                    suggest = Suggest(d, history)
                    decoder = GestureDecoder(d, history)
                    suggest?.nextWords = EngineCache.nextWords(this)
                    configureSuggest()
                    refreshSuggestions()
                }
            }
        }, "quietboard-dict").start()
    }

    override fun onDestroy() {
        destroyed = true
        dropGesture()
        gestureExec.shutdownNow()
        clipboard.stop()
        prefs.sp.unregisterOnSharedPreferenceChangeListener(prefListener)
        history.saveAsync()
        history.close()
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onConfigurationChanged(newConfig: Configuration) {
        // Rotation, dark mode, font size: the framework rebuilds the keyboard and restarts input without
        // finishing it first. Settle the word being typed (and any glide), or the next key replaces it.
        flushGesture()
        if (composing.isNotEmpty()) {
            conn?.finishComposingText()
            composing.setLength(0)
            composingFromGesture = false
        }
        keyboard?.cancelAll()
        super.onConfigurationChanged(newConfig)
    }

    override fun onCreateInputView(): View {
        val root = InputRoot(this)
        root.topBar.listener = this
        root.keyboard.listener = this
        this.root = root
        topBar = root.topBar; keyboard = root.keyboard; overlay = root.overlay; content = root.content
        feedback.attach(root.keyboard) // "Phone default" haptics go through the keyboard view
        emojiPanel = null; clipPanel = null; editPanel = null
        panel = Panel.KEYS
        configDirty = true
        theme = null
        applyConfig()
        return root
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val key = EditorKey(
            info.packageName, info.fieldId, info.inputType, info.imeOptions,
            info.fieldName, info.hintText?.toString(), info.label?.toString(),
        )
        // Apps restart input on the same field all the time (reformatting a phone number, toggling a
        // mode, controlled React/Compose fields). Keep the user's layout, panel and caps lock then.
        val sameField = restarting && key == lastEditor
        lastEditor = key
        editorInfo = info
        if (!sameField) dropGesture()
        // Safety net: never drop our copy of a half-typed word while the editor still marks it.
        if (composing.isNotEmpty()) conn?.finishComposingText()
        onLockScreen = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        if (onLockScreen && panel == Panel.CLIPBOARD) showPanel(Panel.KEYS)
        val keepLock = sameField && shift == ShiftState.LOCKED
        val keepSymbolTyped = sameField && symbolTyped
        // A glided word's pending space survives an app restarting input on the same field.
        val keepPhantom = sameField && phantomSpace
        applyConfig()
        configureEditor(info)
        resetState(info)
        if (sameField) {
            if (keyboard?.layout == null || layoutOptions() != builtOptions) setLayout(layoutId)
            symbolTyped = keepSymbolTyped
            if (keepLock) shift = ShiftState.LOCKED
            if (keepPhantom) { phantomSpace = true; anchorPending = true }
        } else {
            showPanel(Panel.KEYS)
            setLayout(baseLayout)
        }
        updateShift()
        refreshSuggestions()
        wakeScene()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        flushGesture()
        conn?.finishComposingText()
        composing.setLength(0)
        keyboard?.cancelAll()
        history.saveAsync()
        super.onFinishInputView(finishingInput)
    }

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        activeEditor = attribute
        selFromUpdate = false
    }

    override fun onFinishInput() {
        activeEditor = null
        composing.setLength(0)
        composingSuspect = false
        rejected.clear()
        super.onFinishInput()
    }

    private fun applyConfig() {
        val t = KbTheme.resolve(this, prefs)
        // Fun themes' tap bursts and twinkling lights; also off when the phone turns animations off.
        val effects = prefs.themeEffects && android.animation.ValueAnimator.areAnimatorsEnabled()
        if (t != theme || configDirty || effects != effectsOn) {
            theme = t
            effectsOn = effects
            topBar?.theme = t
            keyboard?.theme = t
            keyboard?.effects = effects
            overlay?.theme = t
            overlay?.effects = effects
            root?.background = ThemeBackdrop(t, (topBar?.barHeight ?: 0).toFloat(), resources.displayMetrics.density, effects)
            emojiPanel?.applyTheme(t)
            clipPanel?.applyTheme(t)
            editPanel?.applyTheme(t)
            colorNavBar(t)
        }
        if (configDirty) {
            configDirty = false
            keyboard?.apply {
                showPreview = prefs.keyPreview
                gestureTyping = prefs.gestureTyping
                gestureTrail = prefs.gestureTrail
                spaceCursor = prefs.spaceCursor
                deleteSwipe = prefs.deleteSwipe
                heightScale = prefs.heightPercent / 100f
                referenceRows = if (prefs.numberRow) 4.82f else 4f
                oneHanded = prefs.oneHanded
            }
            configureSuggest()
        }
    }

    private fun configureSuggest() {
        val s = suggest ?: return
        s.shortcuts = prefs.shortcuts()
        s.emojiKeywords = emojiKeywords
        s.emojiEnabled = prefs.emojiSuggestions
        s.blockOffensive = prefs.blockOffensive
        decoder?.blockOffensive = prefs.blockOffensive
    }

    // ------------------------------------------------------------------ hardware back

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && isInputViewShown && panel != Panel.KEYS) {
            swallowBackUp = true
            showPanel(Panel.KEYS)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && swallowBackUp) {
            swallowBackUp = false
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun colorNavBar(t: KbTheme) {
        val w = window?.window ?: return
        @Suppress("DEPRECATION")
        w.navigationBarColor = t.backgroundEnd // the bottom of a fun theme's gradient
        w.isNavigationBarContrastEnforced = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            w.insetsController?.setSystemBarsAppearance(
                if (t.dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            )
        } else {
            // Android 10: dark buttons on a light keyboard need the old flag.
            @Suppress("DEPRECATION")
            val v = w.decorView
            @Suppress("DEPRECATION")
            v.systemUiVisibility = if (t.dark) v.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            else v.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }

    private fun configureEditor(info: EditorInfo) {
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val flags = info.inputType and InputType.TYPE_MASK_FLAGS
        val textPw = cls == InputType.TYPE_CLASS_TEXT && variation in PASSWORD_VARIATIONS
        val numPw = cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        isPassword = textPw || numPw
        privateField = isPrivateEditor(info)
        field = when {
            cls == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS) -> FieldKind.EMAIL
            cls == InputType.TYPE_CLASS_TEXT && variation == InputType.TYPE_TEXT_VARIATION_URI -> FieldKind.URL
            else -> FieldKind.TEXT
        }
        baseLayout = when (cls) {
            InputType.TYPE_CLASS_NUMBER -> LayoutId.NUMBER
            InputType.TYPE_CLASS_DATETIME -> LayoutId.DATETIME
            InputType.TYPE_CLASS_PHONE -> LayoutId.PHONE
            else -> LayoutId.ALPHA
        }
        // On the lock screen whoever holds the phone types: learn nothing, show nothing learned.
        noLearning = privateField || !prefs.learnWords || onLockScreen
        capChars = cls == InputType.TYPE_CLASS_TEXT && (flags and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS) != 0
        val noSuggest = (flags and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0
        suggestionsOn = prefs.showSuggestions && cls == InputType.TYPE_CLASS_TEXT && !isPassword && !noSuggest
        // Names are whatever they are: "Aoife" must not become "Alive".
        autocorrectOn = prefs.autocorrect && suggestionsOn && field == FieldKind.TEXT &&
            variation != InputType.TYPE_TEXT_VARIATION_FILTER && variation != InputType.TYPE_TEXT_VARIATION_PERSON_NAME
        autoCapOn = prefs.autoCap && cls == InputType.TYPE_CLASS_TEXT && !isPassword
        textField = cls == InputType.TYPE_CLASS_TEXT && !isPassword

        keyboard?.enterIcon = enterIconFor(info)
        keyboard?.enterDescription = enterDescriptionFor(info)
        // No glide in passwords (a mis-decoded word would be typed invisibly) or non-text fields.
        keyboard?.gestureTyping = prefs.gestureTyping && textField
        // TalkBack must not speak password keys aloud unless the user has headphones on.
        keyboard?.obscureKeys = isPassword && !headphonesConnected()
    }

    private fun headphonesConnected(): Boolean {
        val am = getSystemService(AudioManager::class.java) ?: return false
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
    }

    /** Email and URL fields: no spaces added for you after picks and glides, no "word ." swaps. */
    private val autoSpaces get() = field == FieldKind.TEXT

    private fun isPrivateEditor(info: EditorInfo): Boolean {
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val password = (cls == InputType.TYPE_CLASS_TEXT && variation in PASSWORD_VARIATIONS) ||
            (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        return password || (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
    }

    private fun enterIconFor(info: EditorInfo): Icon {
        if ((info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return Icon.ENTER
        return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_SEARCH -> Icon.SEARCH
            EditorInfo.IME_ACTION_SEND -> Icon.SEND
            EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_NEXT -> Icon.GO
            EditorInfo.IME_ACTION_DONE -> Icon.DONE
            EditorInfo.IME_ACTION_PREVIOUS -> Icon.PREVIOUS
            else -> Icon.ENTER
        }
    }

    /** What TalkBack calls the enter key when the icon alone would mislead ("Next" shows the Go arrow). */
    private fun enterDescriptionFor(info: EditorInfo): String? {
        if ((info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return null
        info.actionLabel?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        return if ((info.imeOptions and EditorInfo.IME_MASK_ACTION) == EditorInfo.IME_ACTION_NEXT) "Next" else null
    }

    private fun resetState(info: EditorInfo) {
        composing.setLength(0)
        composingPrev = null
        composingFromGesture = false
        composingGlued = false
        composingSuspect = false
        gestureAlternatives = emptyList()
        pendingAutocorrect = null
        lastCorrection = null
        weakSpace = false
        phantomSpace = false
        lastSpaceTime = 0L
        symbolTyped = false
        shift = ShiftState.OFF
        shiftHeld = false
        shiftManual = false
        resumeAfterDelete = false
        resumedWord = null
        revertedWord = null
        anchorPending = false
        anchorPos = -1
        autoShiftPressed = false
        tapUnshiftedAuto = false
        // Showing the keyboard again without a restart: EditorInfo's selection is from when input
        // started, the updates since are newer.
        if (!selFromUpdate) {
            selStart = info.initialSelStart
            selEnd = info.initialSelEnd
        }
    }

    private fun layoutOptions() =
        LayoutOptions(prefs.numberRow, prefs.longPressSymbols, field, numericBase = baseLayout != LayoutId.ALPHA)

    private fun setLayout(id: LayoutId) {
        layoutId = id
        symbolTyped = false
        val opts = layoutOptions()
        builtOptions = opts
        keyboard?.setLayout(Layouts.build(id, opts))
        updateShift()
    }

    // ------------------------------------------------------------------ selection tracking

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        selStart = newSelStart
        selEnd = newSelEnd
        selFromUpdate = true
        // Keyboard hidden (e.g. a hardware keyboard is in use): no strip or caps to keep up to date, so
        // skip the round trips to the app.
        if (!viewShown) return
        if (swipeAnchor >= 0) return
        if (composing.isNotEmpty()) {
            val moved = newSelStart != newSelEnd || (candidatesEnd >= 0 && newSelStart != candidatesEnd)
            if (moved) {
                conn?.finishComposingText()
                composing.setLength(0)
                composingFromGesture = false
                composingSuspect = false
                resumedWord = null
                forgetEditContext()
                refreshSuggestions()
            } else {
                // No composing region: a stale update from before our latest edit, or the app cleared
                // or rewrote the field (web chat boxes after "send"). Checked lazily in syncComposing.
                composingSuspect = candidatesStart < 0 && candidatesEnd < 0
            }
        } else {
            if (candidatesStart < 0) {
                if (anchorPending) {
                    anchorPending = false
                    anchorPos = newSelStart
                } else if (anchorPos >= 0 && (newSelStart != anchorPos || newSelEnd != newSelStart)) {
                    forgetEditContext() // the user moved the cursor somewhere else
                }
            }
            if (resumeAfterDelete && newSelStart == newSelEnd && candidatesStart < 0) {
                resumeAfterDelete = false
                tryResumeComposing()
            } else if (oldSelStart != newSelStart && candidatesStart < 0) {
                refreshSuggestions()
            }
        }
        updateShift()
    }

    /** True while the keyboard is on screen (or a unit test drives it). */
    private val viewShown get() = isInputViewShown || testConnection != null

    /** Typing context that only makes sense right where we last typed. */
    private fun forgetEditContext() {
        lastCorrection = null
        weakSpace = false
        phantomSpace = false
        lastSpaceTime = 0L
        anchorPos = -1
        anchorPending = false
    }

    /** After backspacing into a word, make it editable again so suggestions come back. */
    private fun tryResumeComposing() {
        if (!suggestionsOn) return
        val ic = conn ?: return
        val before = ic.getTextBeforeCursor(48, 0) ?: return
        val after = ic.getTextAfterCursor(1, 0)
        if (!after.isNullOrEmpty() && isWordCharAt(after[0])) return
        val word = trailingWord(before) ?: return
        recompose(ic, before, word)
        refreshSuggestions()
    }

    /**
     * Makes the [word] right before the cursor the composing word again, relative to the cursor: an
     * absolute region from a (possibly stale) selection update could land on the wrong letters.
     */
    private fun recompose(ic: InputConnection, before: CharSequence, word: String) {
        ic.beginBatchEdit()
        ic.finishComposingText()
        ic.deleteSurroundingText(word.length, 0)
        ic.setComposingText(word, 1)
        ic.endBatchEdit()
        composing.setLength(0)
        composing.append(word)
        composingPrev = lastWord(before.subSequence(0, before.length - word.length))
        composingFromGesture = false
        composingGlued = gluedAt(before, word.length)
        resumedWord = word
    }

    /** The word (letters and apostrophes) ending right at the end of [before], or null. */
    private fun trailingWord(before: CharSequence): String? {
        var i = before.length
        while (i > 0 && (before[i - 1].isLetter() || isApostrophe(before[i - 1]))) i--
        val word = before.subSequence(i, before.length).toString()
        if (word.isEmpty() || word.length > Dictionary.MAX_LEN || isApostrophe(word[0])) return null
        return word
    }

    /** Whether the [wordLen]-letter word ending [before] is stuck to a non-space character other than an opening bracket or quote. */
    private fun gluedAt(before: CharSequence?, wordLen: Int): Boolean {
        val i = (before?.length ?: 0) - wordLen - 1
        if (before == null || i < 0) return false
        val c = before[i]
        return !c.isWhitespace() && c !in OPENERS
    }

    private fun isApostrophe(c: Char) = c == '\'' || c == '\u2019'

    private fun isWordCharAt(c: Char) = c.isLetterOrDigit() || isApostrophe(c)

    // ------------------------------------------------------------------ keyboard events

    override fun onKeyFeedback(key: Key) {
        feedback.key(key.type)
        wakeScene()
    }

    /** A fun theme's scene moves for a few seconds after each key, glide or opening (see [ThemeBackdrop]). */
    private fun wakeScene() {
        (root?.background as? ThemeBackdrop)?.wake()
    }

    override fun onHaptic(key: Key, kind: Haptic) {
        feedback.haptic(kind)
        feedback.sound(key.type)
    }

    override fun onText(text: String) {
        val shiftWas = shift
        val glided = flushGesture()
        // A letter tapped while the glide was still decoding took the glide's one-shot capital, which
        // the glide has used up ("Hello x", not "Hello X").
        typeText(if (glided && shiftWas == ShiftState.ON && shift == ShiftState.OFF) text.lowercase(Locale.US) else text)
    }

    private fun typeText(text: String) {
        val ic = conn ?: return
        syncComposing()
        lastCorrection = null
        resumeAfterDelete = false
        if (layoutId == LayoutId.SYMBOLS || layoutId == LayoutId.SYMBOLS2) symbolTyped = true
        if (isWordChar(text)) {
            if (phantomSpace) {
                ic.beginBatchEdit()
                commitComposingAsTyped()
                ic.commitText(" ", 1)
                ic.endBatchEdit()
                phantomSpace = false
            }
            weakSpace = false
            anchorPos = -1
            if (composingFromGesture) {
                // Letters typed onto a glided word (email/URL fields add no space): a typed word now,
                // so backspace takes one letter and the glide's alternatives no longer apply.
                composingFromGesture = false
                gestureAlternatives = emptyList()
            }
            if (suggestionsOn) {
                if (composing.isEmpty()) {
                    val before = ic.getTextBeforeCursor(64, 0)
                    val after = ic.getTextAfterCursor(1, 0)
                    if (!after.isNullOrEmpty() && isWordCharAt(after[0])) {
                        // Typing inside an existing word: insert as-is, no correcting or learning a fragment.
                        ic.commitText(text, 1)
                        afterInput()
                        refreshSuggestions()
                        return
                    }
                    // Typing at the end of an existing word ("becau|" + "se"): continue that word.
                    val tail = before?.let { trailingWord(it) }
                    if (tail != null) {
                        recompose(ic, before, tail)
                    } else {
                        composingPrev = lastWord(before)
                        composingGlued = gluedAt(before, 0)
                    }
                }
                composing.append(text)
                ic.setComposingText(composing, 1)
            } else {
                ic.commitText(text, 1)
            }
            afterInput()
            refreshSuggestions()
        } else {
            if (phantomSpace && autoSpaces && precededBySpace(text)) {
                // After a glide: "hello 5", "hello (", like a letter would get.
                ic.beginBatchEdit()
                commitComposingAsTyped()
                ic.commitText(" ", 1)
                ic.endBatchEdit()
                phantomSpace = false
            }
            commitSeparator(text)
        }
    }

    private fun precededBySpace(t: String): Boolean {
        if (t.isEmpty() || t.codePointCount(0, t.length) != 1) return false
        return Character.isDigit(t.codePointAt(0)) || t in "([{&\u201C"
    }

    private fun isWordChar(t: String): Boolean {
        if (t.isEmpty() || t.codePointCount(0, t.length) != 1) return false
        val cp = t.codePointAt(0)
        if (Character.isLetter(cp)) return true
        if (composing.isEmpty() || composingFromGesture) return false
        return isApostrophe(t[0]) || Character.isDigit(cp)
    }

    private fun commitSeparator(sep: String) {
        val ic = conn ?: return
        syncComposing()
        val isSpace = sep == " "
        val isPunct = sep in AUTOCORRECT_PUNCT
        ic.beginBatchEdit()
        if (composing.isNotEmpty()) {
            val typed = composing.toString()
            val ac = pendingAutocorrect
            // A resumed word committed unchanged was the user's on purpose: don't "fix" it now.
            if ((isSpace || isPunct) && autocorrectOn && !composingFromGesture && ac != null && ac != typed &&
                typed != resumedWord
            ) {
                ic.commitText(ac, 1)
                learn(composingPrev, ac)
                // Backspace-undo only right after a space: after punctuation it would eat the punctuation.
                lastCorrection = if (isSpace) Correction(typed, ac, sep, composingPrev) else null
                resumedWord = null
                revertedWord = null
            } else {
                ic.commitText(typed, 1)
                learnCommitted(typed)
            }
            composing.setLength(0)
            composingFromGesture = false
        }
        phantomSpace = false

        if (weakSpace && sep in SWAP_PUNCT && autoSpaces) {
            weakSpace = false
            val before = ic.getTextBeforeCursor(1, 0)
            if (before != null && before.length == 1 && before[0] == ' ') {
                ic.deleteSurroundingText(1, 0)
                ic.commitText("$sep ", 1)
                ic.endBatchEdit()
                anchorPending = true
                finishSeparator(false)
                return
            }
        }
        weakSpace = false

        val now = SystemClock.uptimeMillis()
        if (isSpace && prefs.doubleSpacePeriod && textField && field == FieldKind.TEXT && now - lastSpaceTime < 500L) {
            val before = ic.getTextBeforeCursor(2, 0)
            if (before != null && before.length == 2 && before[1] == ' ' &&
                (before[0].isLetterOrDigit() || before[0] in "\"')]")
            ) {
                ic.deleteSurroundingText(1, 0)
                ic.commitText(". ", 1)
                ic.endBatchEdit()
                lastSpaceTime = 0L
                lastCorrection = null
                anchorPending = true
                finishSeparator(true)
                return
            }
        }
        ic.commitText(sep, 1)
        ic.endBatchEdit()
        lastSpaceTime = if (isSpace) now else 0L
        anchorPending = true
        finishSeparator(isSpace)
    }

    /** Learns a committed word, except a resumed word committed unchanged (learned the first time). */
    private fun learnCommitted(word: String) {
        if (word == revertedWord) {
            // The user undid a correction and kept their spelling: trust it from now on.
            if (!noLearning) history.accept(word.replace('\u2019', '\''))
            revertedWord = null
        }
        if (word != resumedWord) learn(composingPrev, word)
        resumedWord = null
    }

    private fun finishSeparator(isSpace: Boolean) {
        if (isSpace && symbolTyped && (layoutId == LayoutId.SYMBOLS || layoutId == LayoutId.SYMBOLS2)) {
            setLayout(baseLayout)
        }
        afterInput()
        refreshSuggestions()
    }

    private fun commitComposingAsTyped() {
        syncComposing()
        if (composing.isEmpty()) return
        val w = composing.toString()
        conn?.commitText(w, 1)
        learnCommitted(w)
        composing.setLength(0)
        composingFromGesture = false
    }

    override fun onDelete() {
        flushGesture()
        val ic = conn ?: return
        syncComposing()
        resumeAfterDelete = false
        shiftManual = false
        weakSpace = false
        phantomSpace = false
        lastSpaceTime = 0L

        val lc = lastCorrection
        lastCorrection = null
        if (lc != null && composing.isEmpty()) {
            val expected = lc.corrected + lc.separator
            val before = ic.getTextBeforeCursor(expected.length, 0)?.toString()
            if (before == expected) {
                // Undo autocorrect: put back exactly what was typed and don't "fix" it again this session.
                // It's only trusted for good once committed unchanged; the correction is un-learned.
                rejected += lc.original.lowercase(Locale.US).replace('\u2019', '\'')
                if (!noLearning) history.unlearn(lc.prev, lc.corrected)
                revertedWord = lc.original
                resumedWord = null
                composingGlued = false
                ic.beginBatchEdit()
                ic.deleteSurroundingText(expected.length, 0)
                composing.append(lc.original)
                ic.setComposingText(composing, 1)
                ic.endBatchEdit()
                afterInput()
                refreshSuggestions()
                return
            }
        }

        if (composing.isNotEmpty()) {
            resumedWord = null
            if (composingFromGesture) {
                composing.setLength(0) // Gboard: backspace removes the whole glided word
            } else {
                composing.setLength(composing.length - lastGraphemeLength(composing))
            }
            ic.setComposingText(composing, 1)
            if (composing.isEmpty()) {
                ic.finishComposingText()
                composingFromGesture = false
            }
        } else {
            val selected = ic.getSelectedText(0)
            if (!selected.isNullOrEmpty()) {
                ic.commitText("", 1)
            } else {
                val before = ic.getTextBeforeCursor(32, 0)
                if (before.isNullOrEmpty()) {
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                } else {
                    ic.deleteSurroundingText(lastGraphemeLength(before), 0)
                    resumeAfterDelete = suggestionsOn
                }
            }
        }
        afterInput()
        refreshSuggestions()
    }

    private fun lastGraphemeLength(s: CharSequence): Int {
        if (s.isEmpty()) return 0
        val it = BreakIterator.getCharacterInstance()
        it.setText(s.toString())
        val end = it.last()
        val start = it.previous()
        return if (start == BreakIterator.DONE) 1 else (end - start).coerceAtLeast(1)
    }

    override fun onSpace() {
        flushGesture()
        commitSeparator(" ")
    }

    override fun onEnter() {
        flushGesture()
        val ic = conn ?: return
        syncComposing()
        val newline = enterInsertsNewline()
        // Enter ends a word like space does: apply the pending correction (undoable if it adds a newline).
        var corrected: Correction? = null
        ic.beginBatchEdit()
        if (composing.isNotEmpty()) {
            val typed = composing.toString()
            val ac = pendingAutocorrect
            if (autocorrectOn && !composingFromGesture && ac != null && ac != typed && typed != resumedWord) {
                ic.commitText(ac, 1)
                learn(composingPrev, ac)
                corrected = Correction(typed, ac, "\n", composingPrev)
                resumedWord = null
                revertedWord = null
            } else {
                ic.commitText(typed, 1)
                learnCommitted(typed)
            }
            composing.setLength(0)
            composingFromGesture = false
        }
        ic.endBatchEdit()
        phantomSpace = false
        weakSpace = false
        lastSpaceTime = 0L
        lastCorrection = if (newline) corrected else null
        anchorPending = lastCorrection != null
        sendKeyChar('\n')
        afterInput()
        refreshSuggestions()
    }

    /** Whether Enter types a newline here (vs. performing the field's action, like Send or Search). */
    private fun enterInsertsNewline(): Boolean {
        val info = editorInfo ?: return true
        return (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0 ||
            (info.imeOptions and EditorInfo.IME_MASK_ACTION) == EditorInfo.IME_ACTION_NONE
    }

    override fun onShiftPress() {
        flushGesture()
        val now = SystemClock.uptimeMillis()
        val quick = now - lastShiftTap < 350L
        lastShiftTap = now
        when (shift) {
            ShiftState.OFF -> shift = if (quick && tapUnshiftedAuto) ShiftState.LOCKED else ShiftState.ON
            // Auto-capitalised: keep capitals while held (shift+letter chords); a plain tap turns it off
            // on release. A quick second tap locks.
            ShiftState.ON -> when {
                quick -> shift = ShiftState.LOCKED
                !shiftManual -> autoShiftPressed = true
                else -> shift = ShiftState.OFF
            }
            ShiftState.LOCKED -> shift = ShiftState.OFF
        }
        tapUnshiftedAuto = false
        shiftManual = true
        shiftHeld = true
        keyboard?.shiftState = shift
        refreshSuggestionsCase()
    }

    override fun onShiftRelease(chorded: Boolean) {
        shiftHeld = false
        if (autoShiftPressed) {
            autoShiftPressed = false
            if (!chorded) {
                shift = ShiftState.OFF
                tapUnshiftedAuto = true
            }
        }
        if (chorded && shift == ShiftState.ON) {
            shift = ShiftState.OFF
            shiftManual = false
            updateShift()
        }
        keyboard?.shiftState = shift
        refreshSuggestionsCase()
    }

    /** TalkBack: one activation per step, since a timed double tap can't be done through it. */
    override fun onShiftCycle() {
        flushGesture()
        shift = when (shift) {
            ShiftState.OFF -> ShiftState.ON
            ShiftState.ON -> ShiftState.LOCKED
            ShiftState.LOCKED -> ShiftState.OFF
        }
        shiftManual = shift != ShiftState.OFF
        autoShiftPressed = false
        keyboard?.shiftState = shift
        refreshSuggestionsCase()
    }

    private fun refreshSuggestionsCase() {
        if (composing.isEmpty()) refreshSuggestions()
    }

    override fun onLayoutKey(type: KeyType) {
        flushGesture()
        when (type) {
            KeyType.TO_SYMBOLS -> setLayout(LayoutId.SYMBOLS)
            KeyType.TO_SYMBOLS2 -> setLayout(LayoutId.SYMBOLS2)
            KeyType.TO_ALPHA -> setLayout(baseLayout) // letters, or back to the number pad
            KeyType.TO_LETTERS -> setLayout(LayoutId.ALPHA) // letters from a number/date field
            KeyType.EMOJI -> showPanel(Panel.EMOJI)
            else -> Unit
        }
    }

    override fun onSpaceLongPress() {
        getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
    }

    override fun onCursorMove(steps: Int) {
        flushGesture()
        val ic = conn ?: return
        if (composing.isNotEmpty()) {
            ic.finishComposingText()
            composing.setLength(0)
            composingFromGesture = false
            resumedWord = null
        }
        forgetEditContext()
        val n = abs(steps)
        // Stop at the ends of the text: extra arrow keys would move focus to a neighbouring field.
        val room = if (steps < 0) ic.getTextBeforeCursor(n, 0)?.length else ic.getTextAfterCursor(n, 0)?.length
        val count = if (room == null) n else minOf(n, room)
        val code = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(count) { sendDownUpKeyEvents(code) }
        if (count > 0) feedback.tick()
    }

    override fun onCursorMoveEnd() {
        refreshSuggestions()
        updateShift()
    }

    override fun onDeleteSwipeStart() {
        flushGesture()
        val ic = conn ?: return
        if (composing.isNotEmpty()) {
            ic.finishComposingText()
            composing.setLength(0)
            composingFromGesture = false
        }
        forgetEditContext()
        // Ask the editor where the selection is now: the cached one can lag behind (a glide was just
        // inserted, or the app is slow to report), and the word bounds below are measured from it.
        val (s0, s1) = currentSelection(ic) ?: (selStart to selEnd)
        selStart = s0
        selEnd = s1
        val start = minOf(selStart, selEnd)
        if (start < 0) { swipeAnchor = -1; return }
        // Text before the cursor is text before the selection's start, so word bounds count from
        // there; the selection still ends where it did, so selected text is deleted along with the words.
        val before = ic.getTextBeforeCursor(2000, 0)?.toString() ?: ""
        val bounds = ArrayList<Int>()
        var i = before.length
        while (i > 0 && bounds.size < 60) {
            while (i > 0 && before[i - 1].isWhitespace()) i--
            while (i > 0 && !before[i - 1].isWhitespace()) i--
            bounds += before.length - i
        }
        swipeBounds = bounds.toIntArray()
        swipeOrigStart = selStart
        swipeOrigEnd = selEnd
        swipeStart = start
        swipeAnchor = maxOf(selStart, selEnd)
        swipeLen = 0
    }

    private fun currentSelection(ic: InputConnection): Pair<Int, Int>? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Editors on the default implementation give no offset: fall through to extracted text.
            val st = ic.getSurroundingText(0, 0, 0)
            if (st != null && st.offset >= 0) return (st.offset + st.selectionStart) to (st.offset + st.selectionEnd)
        }
        val et = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return null
        if (et.startOffset < 0 || et.selectionStart < 0) return null
        return (et.startOffset + et.selectionStart) to (et.startOffset + et.selectionEnd)
    }

    override fun onDeleteSwipeUpdate(words: Int) {
        val ic = conn ?: return
        if (swipeAnchor < 0) return
        val len = if (words <= 0 || swipeBounds.isEmpty()) 0 else swipeBounds[minOf(words, swipeBounds.size) - 1]
        if (len == swipeLen) return
        swipeLen = len
        if (len == 0) ic.setSelection(swipeOrigStart, swipeOrigEnd) else ic.setSelection(swipeStart - len, swipeAnchor)
        feedback.tick()
    }

    override fun onDeleteSwipeEnd(commit: Boolean) {
        val ic = conn
        val anchor = swipeAnchor
        swipeAnchor = -1
        if (ic == null || anchor < 0) return
        if (commit && swipeLen > 0) ic.commitText("", 1) else ic.setSelection(swipeOrigStart, swipeOrigEnd)
        swipeLen = 0
        afterInput()
        refreshSuggestions()
    }

    override fun onGestureStart() {
        wakeScene()
        flushGesture() // the previous glide's word is the context for this one
        glideId++
        glidePreviewing = true
        previewBusy = false
        glideContext = currentGlideContext()
    }

    override fun onGestureProgress(xs: FloatArray, ys: FloatArray, count: Int) {
        wakeScene()
        if (previewBusy || !glidePreviewing || !suggestionsOn) return
        val dec = decoder ?: return
        val kv = keyboard ?: return
        val (cx, cy) = kv.letterCenters()
        val kw = kv.keyWidthPx()
        val kh = kv.rowHeightPx()
        val id = glideId
        val ctx = glideContext
        val learned = !noLearning
        previewBusy = true
        try {
            gestureExec.execute {
                val best = runCatching { dec.decode(xs, ys, count, cx, cy, kw, kh, 1, ctx, learned) }.getOrDefault(emptyList()).firstOrNull()
                main.post {
                    previewBusy = false
                    if (id == glideId && glidePreviewing && best != null) {
                        topBar?.setSuggestions(listOf(null, SuggestionItem(gestureCase(best), bold = true), null))
                    }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            previewBusy = false
        }
    }

    override fun onGestureCancel() {
        glidePreviewing = false
        glideId++
        refreshSuggestions()
    }

    /** What's likely next after the word before the cursor (or the glided word still showing). */
    private fun currentGlideContext(): Map<String, Float> {
        val s = suggest ?: return emptyMap()
        val learned = !noLearning
        if (composing.isNotEmpty()) return s.contextFor(composing.toString(), false, learned)
        val before = conn?.getTextBeforeCursor(64, 0)
        val trimmed = before?.trimEnd()
        val sentenceStart = trimmed.isNullOrEmpty() || trimmed.last() in ".!?\n"
        return s.contextFor(lastWord(before), sentenceStart, learned)
    }

    override fun onGesture(xs: FloatArray, ys: FloatArray, count: Int) {
        flushGesture()
        glidePreviewing = false
        glideId++ // late preview results are stale now
        val dec = decoder ?: return
        val kv = keyboard ?: return
        val (cx, cy) = kv.letterCenters()
        val kw = kv.keyWidthPx()
        val kh = kv.rowHeightPx()
        val ctx = glideContext
        val learned = !noLearning
        val id = ++pendingGestureId
        pendingGesture = try {
            gestureExec.submit(Callable {
                val results = runCatching { dec.decode(xs, ys, count, cx, cy, kw, kh, 4, ctx, learned) }.getOrDefault(emptyList())
                main.post { if (pendingGestureId == id && pendingGesture != null) flushGesture() }
                results
            })
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            null
        }
    }

    /**
     * Applies a glide that is still decoding, waiting for it if needed (keeps typed text in order).
     * Returns whether a glided word was typed.
     */
    private fun flushGesture(): Boolean {
        val f = pendingGesture ?: return false
        pendingGesture = null
        val results = runCatching { f.get() }.getOrDefault(emptyList())
        if (results.isEmpty()) {
            refreshSuggestions() // nothing typed: don't leave the live preview in the strip
            return false
        }
        applyGesture(results)
        return true
    }

    private fun dropGesture() {
        pendingGesture?.cancel(false)
        pendingGesture = null
    }

    private fun applyGesture(results: List<String>) {
        val ic = conn ?: return
        syncComposing()
        ic.beginBatchEdit()
        if (composing.isNotEmpty()) {
            val typed = composing.toString()
            val ac = pendingAutocorrect
            if (!composingFromGesture && typed.length == 1 && autocorrectOn && ac != null && ac != typed) {
                // A one-letter word can't be glided, so it was tapped: correct it as space would ("i" -> "I").
                ic.commitText(ac, 1)
                learn(composingPrev, ac)
                composing.setLength(0)
                resumedWord = null
            } else {
                commitComposingAsTyped()
            }
            if (autoSpaces) ic.commitText(" ", 1)
        } else if (autoSpaces) {
            val before = ic.getTextBeforeCursor(1, 0)
            if (!before.isNullOrEmpty() && !before[0].isWhitespace() && before[0] !in NO_SPACE_BEFORE_GLIDE) {
                ic.commitText(" ", 1)
            }
        }
        // The space just added can start a sentence ("end.|" -> "end. |"): re-check auto-caps
        // unless the user set shift themselves.
        if (!shiftManual && !shiftHeld && shift != ShiftState.LOCKED) updateShift()
        composingPrev = lastWord(ic.getTextBeforeCursor(64, 0))
        val words = results.map { gestureCase(it) }
        if (suggestionsOn) {
            composing.append(words[0])
            composingFromGesture = true
            composingGlued = false
            gestureAlternatives = words.drop(1)
            ic.setComposingText(composing, 1)
        } else {
            // No-suggestions fields (some terminals, search boxes): plain text, nothing to pick from.
            ic.commitText(words[0], 1)
            learn(composingPrev, words[0])
            // Track where the word ended, so a tap elsewhere drops the space owed to it.
            anchorPending = true
        }
        ic.endBatchEdit()
        phantomSpace = autoSpaces
        weakSpace = false
        lastCorrection = null
        afterInput()
        refreshSuggestions()
    }

    private fun gestureCase(w: String) = when {
        // English rules, not the phone's: a Turkish phone must not turn "is" into "İs".
        shift == ShiftState.LOCKED || (shift == ShiftState.ON && capChars) -> w.uppercase(Locale.US)
        // Words with their own inner capitals keep them ("iPhone" at a sentence start).
        shift == ShiftState.ON && w.drop(1).none { it.isUpperCase() } -> w.replaceFirstChar { it.titlecase(Locale.US) }
        else -> w
    }

    // ------------------------------------------------------------------ shift / suggestions

    private fun afterInput() {
        shiftManual = false
        tapUnshiftedAuto = false
        if (!shiftHeld && shift == ShiftState.ON) shift = ShiftState.OFF
        updateShift()
    }

    private fun updateShift() {
        val kv = keyboard
        if (shift != ShiftState.LOCKED && !shiftHeld && !shiftManual && composing.isNotEmpty() && !capChars) {
            // Mid-word the answer is always "no capital"; skip the round-trip to the app, which
            // blocks the keyboard on slow apps and made typing feel laggy.
            shift = ShiftState.OFF
        } else if (shift != ShiftState.LOCKED && !shiftHeld && !shiftManual) {
            val info = editorInfo
            val ic = conn
            val auto = layoutId == LayoutId.ALPHA && autoCapOn && info != null && ic != null &&
                ic.getCursorCapsMode(info.inputType) != 0
            shift = if (auto) ShiftState.ON else ShiftState.OFF
        }
        kv?.shiftState = shift
    }

    private fun refreshSuggestions() {
        val tb = topBar ?: return
        val s = suggest
        if (!suggestionsOn || panel != Panel.KEYS || s == null) {
            pendingAutocorrect = null
            tb.setSuggestions(null)
            refreshTopBar()
            return
        }
        if (composing.isNotEmpty()) {
            if (composingFromGesture) {
                pendingAutocorrect = null
                val w = composing.toString()
                tb.setSuggestions(
                    listOf(
                        gestureAlternatives.getOrNull(0)?.let { SuggestionItem(it) },
                        SuggestionItem(w, bold = true),
                        s.emojiFor(w)?.let { SuggestionItem(it, emoji = true) }
                            ?: gestureAlternatives.getOrNull(1)?.let { SuggestionItem(it) },
                    )
                )
            } else {
                val r = s.suggest(composing.toString(), composingPrev, autocorrectOn && !composingGlued, rejected, learned = !noLearning)
                pendingAutocorrect = r.autoCorrect
                tb.setSuggestions(r.strip)
            }
        } else {
            pendingAutocorrect = null
            val preds = if (prefs.nextWord) {
                val before = conn?.getTextBeforeCursor(64, 0)
                // Only at a real sentence start (empty field, after . ! ? or a new line), not after a comma.
                val trimmed = before?.trimEnd()
                val sentenceStart = trimmed.isNullOrEmpty() || trimmed.last() in ".!?\n"
                // Your own habits stay out of incognito fields and the lock screen; built-in ones don't.
                s.predict(lastWord(before), shift != ShiftState.OFF, learned = !noLearning, sentenceStart = sentenceStart)
            } else emptyList()
            tb.setSuggestions(preds.ifEmpty { null })
        }
        refreshTopBar()
    }

    private fun refreshTopBar() {
        val tb = topBar ?: return
        tb.activeTool = when (panel) {
            Panel.EMOJI -> TopBar.Tool.EMOJI
            Panel.CLIPBOARD -> TopBar.Tool.CLIPBOARD
            Panel.EDIT -> TopBar.Tool.EDIT
            Panel.KEYS -> if (prefs.oneHanded != 0) TopBar.Tool.ONE_HAND else null
        }
        tb.locked = onLockScreen
        tb.pasteChip = if (panel == Panel.KEYS && !isPassword && !onLockScreen) clipboard.freshClip()?.text else null
        if (panel == Panel.CLIPBOARD) clipPanel?.setItems(clipboard.list(), prefs.clipboardHistory)
    }

    /**
     * If the editor dropped the composing region (see onUpdateSelection), make sure the word we think
     * is being typed is still in front of the cursor. If the app cleared or replaced the text, forget
     * the word; otherwise the next keystroke would type it back in ("hello" reappearing after send).
     */
    private fun syncComposing() {
        if (!composingSuspect) return
        composingSuspect = false
        if (composing.isEmpty()) return
        val ic = conn ?: return
        val before = ic.getTextBeforeCursor(composing.length, 0) ?: return
        if (before.toString().equals(composing.toString(), ignoreCase = true)) {
            // Still there (the update was stale, or the app dropped the region but kept the text):
            // re-mark it relative to the cursor, or the next key would type the word a second time.
            ic.beginBatchEdit()
            ic.finishComposingText()
            ic.deleteSurroundingText(before.length, 0)
            ic.setComposingText(composing, 1)
            ic.endBatchEdit()
            return
        }
        // Gone: the app cleared or replaced the text. Let go of it, and of any region left behind.
        ic.finishComposingText()
        composing.setLength(0)
        composingFromGesture = false
        pendingAutocorrect = null
        lastCorrection = null
        phantomSpace = false
        resumedWord = null
    }

    /** Last whole word before the cursor, or null at a sentence boundary. */
    private fun lastWord(text: CharSequence?): String? {
        if (text.isNullOrEmpty()) return null
        var end = text.length
        while (end > 0 && text[end - 1] == ' ') end--
        if (end == 0) return null
        var start = end
        while (start > 0 && (text[start - 1].isLetter() || isApostrophe(text[start - 1]))) start--
        if (start == end) return null
        return text.subSequence(start, end).toString()
    }

    private fun learn(prev: String?, word: String) {
        if (noLearning) return
        // "i’m" is "i'm": one spelling of the apostrophe in what's learned.
        history.learn(prev?.replace('\u2019', '\''), word.replace('\u2019', '\''))
    }

    // ------------------------------------------------------------------ top bar

    override fun onSuggestionPicked(item: SuggestionItem) {
        // A glide is still decoding: the strip on screen belongs to the previous word. Settle the glide
        // and let the user pick again from the right suggestions.
        if (pendingGesture != null) { flushGesture(); return }
        val ic = conn ?: return
        syncComposing()
        // A space after the pick at the end of the text or right before another word; before a line
        // break it waits for the next letter; none before a space or punctuation, or in email/URL fields.
        val next = ic.getTextAfterCursor(1, 0)?.firstOrNull()
        val addSpace = autoSpaces && (next == null || next.isLetterOrDigit())
        val spaceLater = autoSpaces && next == '\n'
        if (item.emoji) {
            // Keep the word (autocorrected if pending) and add the emoji after it.
            ic.beginBatchEdit()
            if (composing.isNotEmpty()) {
                val ac = pendingAutocorrect
                val typed = composing.toString()
                val word = if (autocorrectOn && ac != null && !composingFromGesture && typed != resumedWord) ac else typed
                ic.commitText(word, 1)
                if (word == typed) learnCommitted(word) else learn(composingPrev, word)
                if (autoSpaces) ic.commitText(" ", 1)
            }
            ic.commitText(if (addSpace) item.word + " " else item.word, 1)
            ic.endBatchEdit()
            composing.setLength(0)
            composingFromGesture = false
            resumedWord = null
            weakSpace = addSpace; phantomSpace = false; lastCorrection = null; lastSpaceTime = 0L
            anchorPending = true
            rememberEmoji(item.word)
            afterInput()
            refreshSuggestions()
            return
        }
        ic.beginBatchEdit()
        if (composing.isNotEmpty()) {
            val typed = composing.toString()
            if (item.literal || item.word == typed) {
                rejected += typed.lowercase(Locale.US).replace('\u2019', '\'')
                if (!noLearning) history.accept(typed.replace('\u2019', '\''))
            }
            ic.commitText(item.word, 1)
            learn(composingPrev, item.word)
        } else {
            val before = ic.getTextBeforeCursor(1, 0)
            if (autoSpaces && !before.isNullOrEmpty() && !before[0].isWhitespace()) ic.commitText(" ", 1)
            val prev = lastWord(ic.getTextBeforeCursor(64, 0))
            ic.commitText(item.word, 1)
            learn(prev, item.word)
        }
        if (addSpace) ic.commitText(" ", 1)
        ic.endBatchEdit()
        composing.setLength(0)
        composingFromGesture = false
        resumedWord = null
        revertedWord = null
        weakSpace = addSpace
        phantomSpace = spaceLater
        lastCorrection = null
        lastSpaceTime = 0L
        anchorPending = true
        afterInput()
        refreshSuggestions()
    }

    override fun onSuggestionLongPress(item: SuggestionItem): Boolean {
        // Not on the lock screen: a stranger mustn't probe or delete what you've taught it.
        if (item.emoji || noLearning || !history.forget(item.word)) return false
        Toast.makeText(this, "Removed \u201C${item.word}\u201D from learned words", Toast.LENGTH_SHORT).show()
        refreshSuggestions()
        return true
    }

    override fun onTool(tool: TopBar.Tool) {
        flushGesture()
        when (tool) {
            TopBar.Tool.EMOJI -> showPanel(if (panel == Panel.EMOJI) Panel.KEYS else Panel.EMOJI)
            TopBar.Tool.CLIPBOARD -> if (!onLockScreen) showPanel(if (panel == Panel.CLIPBOARD) Panel.KEYS else Panel.CLIPBOARD)
            TopBar.Tool.SETTINGS -> if (!onLockScreen) {
                startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                requestHideSelf(0)
            }
            TopBar.Tool.HIDE -> requestHideSelf(0)
            TopBar.Tool.EDIT -> showPanel(if (panel == Panel.EDIT) Panel.KEYS else Panel.EDIT)
            TopBar.Tool.ONE_HAND -> {
                setOneHanded(if (prefs.oneHanded == 0) 1 else 0)
                if (panel != Panel.KEYS) showPanel(Panel.KEYS)
            }
            TopBar.Tool.PASTE -> if (!onLockScreen) clipboard.freshClip()?.let {
                pasteText(it.text)
                clipboard.dismissChip()
                refreshTopBar()
            }
        }
    }

    private fun pasteText(text: String) {
        flushGesture()
        val ic = conn ?: return
        ic.beginBatchEdit()
        commitComposingAsTyped()
        ic.commitText(text, 1)
        ic.endBatchEdit()
        phantomSpace = false; weakSpace = false; lastCorrection = null
        afterInput()
        refreshSuggestions()
    }

    // ------------------------------------------------------------------ panels

    private fun showPanel(p: Panel) {
        flushGesture()
        val kv = keyboard ?: return
        if (p == Panel.CLIPBOARD && onLockScreen) return // clips are private; the phone is locked
        if (p != Panel.KEYS && composing.isNotEmpty()) {
            val ic = conn
            ic?.beginBatchEdit()
            commitComposingAsTyped()
            ic?.endBatchEdit()
        }
        panel = p
        val t = theme
        when (p) {
            Panel.KEYS -> {
                emojiPanel?.visibility = View.GONE
                clipPanel?.visibility = View.GONE
                editPanel?.visibility = View.GONE
                editPanel?.setSelecting(false)
                kv.visibility = View.VISIBLE
            }
            Panel.EDIT -> {
                val ed = editPanel ?: EditPanel(this, this).also {
                    editPanel = it
                    root?.addPanel(it)
                }
                if (t != null) ed.applyTheme(t)
                ed.setSelecting(false)
                emojiPanel?.visibility = View.GONE
                clipPanel?.visibility = View.GONE
                ed.visibility = View.VISIBLE
                kv.visibility = View.INVISIBLE
            }
            Panel.EMOJI -> {
                val ep = emojiPanel ?: EmojiPanel(this, this).also {
                    emojiPanel = it
                    root?.addPanel(it)
                }
                if (t != null) ep.applyTheme(t)
                ep.setRecents(if (onLockScreen) emptyList() else emojiRecents)
                ep.open()
                clipPanel?.visibility = View.GONE
                editPanel?.visibility = View.GONE
                ep.visibility = View.VISIBLE
                kv.visibility = View.INVISIBLE
            }
            Panel.CLIPBOARD -> {
                val cp = clipPanel ?: ClipboardPanel(this, this).also {
                    clipPanel = it
                    root?.addPanel(it)
                }
                if (t != null) cp.applyTheme(t)
                emojiPanel?.visibility = View.GONE
                editPanel?.visibility = View.GONE
                cp.visibility = View.VISIBLE
                kv.visibility = View.INVISIBLE
            }
        }
        refreshSuggestions()
    }

    override fun onEmoji(emoji: String) {
        feedback.key(KeyType.CHAR)
        flushGesture()
        val ic = conn ?: return
        ic.beginBatchEdit()
        commitComposingAsTyped()
        if (phantomSpace && autoSpaces) ic.commitText(" ", 1) // "hello 😀" after a glide
        ic.commitText(emoji, 1)
        ic.endBatchEdit()
        phantomSpace = false; weakSpace = false; lastCorrection = null
        rememberEmoji(emoji)
        afterInput()
    }

    private fun rememberEmoji(emoji: String) {
        if (privateField || onLockScreen) return // nothing from private fields or the lock screen is kept
        emojiRecents.remove(emoji)
        emojiRecents.add(0, emoji)
        while (emojiRecents.size > 40) emojiRecents.removeAt(emojiRecents.size - 1)
        prefs.sp.edit().putString(KEY_RECENTS, emojiRecents.joinToString(SEP)).apply()
    }

    // ------------------------------------------------------------------ one-handed

    private fun setOneHanded(side: Int) {
        prefs.sp.edit().putInt(Prefs.ONE_HANDED, side).apply()
        keyboard?.oneHanded = side
        refreshTopBar()
    }

    override fun onOneHandedSwap() = setOneHanded(-prefs.oneHanded)

    override fun onOneHandedExit() = setOneHanded(0)

    // ------------------------------------------------------------------ editing panel

    override fun onKeyFeedbackPanel() = feedback.key(KeyType.CHAR)

    override fun onEditAction(action: EditPanel.Action, extend: Boolean) {
        flushGesture()
        val ic = conn ?: return
        if (composing.isNotEmpty()) {
            ic.finishComposingText()
            composing.setLength(0)
            composingFromGesture = false
        }
        forgetEditContext()
        // Arrow keys past the ends of the text would move focus to another view (and close the keyboard).
        val atStart = action in BACKWARD && ic.getTextBeforeCursor(1, 0)?.isEmpty() == true
        val atEnd = action in FORWARD && ic.getTextAfterCursor(1, 0)?.isEmpty() == true
        if (atStart || atEnd) return
        val multiLine = ((editorInfo?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        when (action) {
            // Single-line fields have no line above/below: go to the start/end instead.
            EditPanel.Action.UP -> moveKey(if (multiLine) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_MOVE_HOME, extend)
            EditPanel.Action.DOWN -> moveKey(if (multiLine) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_MOVE_END, extend)
            EditPanel.Action.LEFT -> moveKey(KeyEvent.KEYCODE_DPAD_LEFT, extend)
            EditPanel.Action.RIGHT -> moveKey(KeyEvent.KEYCODE_DPAD_RIGHT, extend)
            EditPanel.Action.HOME -> moveKey(KeyEvent.KEYCODE_MOVE_HOME, extend)
            EditPanel.Action.END -> moveKey(KeyEvent.KEYCODE_MOVE_END, extend)
            EditPanel.Action.SELECT_ALL -> ic.performContextMenuAction(android.R.id.selectAll)
            EditPanel.Action.COPY -> ic.performContextMenuAction(android.R.id.copy)
            EditPanel.Action.CUT -> ic.performContextMenuAction(android.R.id.cut)
            EditPanel.Action.PASTE -> ic.performContextMenuAction(android.R.id.paste)
            EditPanel.Action.UNDO -> sendKey(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
            EditPanel.Action.REDO -> sendKey(
                KeyEvent.KEYCODE_Z,
                KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON,
            )
        }
    }

    /**
     * Cursor movement; with [extend] the move is wrapped in a Shift press, which is how both
     * EditText (shift state tracked in the text buffer) and browsers extend a selection.
     */
    private fun moveKey(code: Int, extend: Boolean) {
        if (!extend) { sendKey(code, 0); return }
        val ic = conn ?: return
        val shift = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        ic.sendKeyEvent(softKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, shift))
        ic.sendKeyEvent(softKey(KeyEvent.ACTION_DOWN, code, shift))
        ic.sendKeyEvent(softKey(KeyEvent.ACTION_UP, code, shift))
        ic.sendKeyEvent(softKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 0))
    }

    private fun sendKey(code: Int, meta: Int) {
        val ic = conn ?: return
        ic.sendKeyEvent(softKey(KeyEvent.ACTION_DOWN, code, meta))
        ic.sendKeyEvent(softKey(KeyEvent.ACTION_UP, code, meta))
    }

    /** A key event marked as coming from this soft keyboard, so the app stays in touch mode. */
    private fun softKey(action: Int, code: Int, meta: Int): KeyEvent {
        val now = SystemClock.uptimeMillis()
        return KeyEvent(
            now, now, action, code, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
            KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE,
        )
    }

    private var lastPanelDeleteFeedback = 0L

    override fun onPanelDelete() {
        // Panel delete keys repeat every ~50 ms when held: buzz on the press and every other repeat.
        val now = SystemClock.uptimeMillis()
        if (now - lastPanelDeleteFeedback > 90L) {
            feedback.key(KeyType.DELETE)
            lastPanelDeleteFeedback = now
        }
        onDelete()
    }

    override fun onPanelBack() = showPanel(Panel.KEYS)

    override fun onClipPaste(text: String) {
        feedback.key(KeyType.CHAR)
        pasteText(text)
        clipboard.dismissChip()
        showPanel(Panel.KEYS)
    }

    override fun onClipPin(item: ClipboardHistory.Item) {
        clipboard.togglePin(item)
        refreshTopBar()
    }

    override fun onClipRemove(item: ClipboardHistory.Item) {
        clipboard.remove(item)
        refreshTopBar()
    }

    override fun onClipClear() {
        clipboard.clear()
        refreshTopBar()
    }

    companion object {
        /** Recent emoji, newest first; also removed by Settings' "Clear learned words". */
        const val KEY_RECENTS = "emoji_recents"
        private const val SEP = "\u001F"
        private val AUTOCORRECT_PUNCT = setOf(".", ",", "?", "!", ":", ";", ")")
        /** Before these a word isn't "stuck" to the text: brackets, quotes, dashes, markdown marks. */
        private const val OPENERS = "([{<\"'\u201C\u2018\u00AB*_~-\u2013\u2014"
        /** No space before a glided word right after these ("(hello", "well-known", "@name"). */
        private const val NO_SPACE_BEFORE_GLIDE = "([{\"'/@#\u201C\u2018-\u2013\u2014_"
        private val SWAP_PUNCT = setOf(".", ",", "?", "!", ":", ";")
        private val BACKWARD = setOf(EditPanel.Action.LEFT, EditPanel.Action.UP, EditPanel.Action.HOME)
        private val FORWARD = setOf(EditPanel.Action.RIGHT, EditPanel.Action.DOWN, EditPanel.Action.END)
        private val PASSWORD_VARIATIONS = setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )
    }
}

/**
 * Dictionary and emoji keywords, loaded once per process. Switching keyboards and back recreates the
 * service, which used to re-parse ~100k words (and leave suggestions off meanwhile) every time.
 */
private object EngineCache {
    private var dict: Dictionary? = null
    private var keywords: Map<String, String>? = null

    @Synchronized
    fun dictionary(ctx: Context): Dictionary = dict ?: Dictionary.load(ctx).also { dict = it }

    @Synchronized
    fun emojiKeywords(ctx: Context): Map<String, String> = keywords ?: loadKeywords(ctx).also { keywords = it }

    private var next: Map<String, List<String>>? = null

    @Synchronized
    fun nextWords(ctx: Context): Map<String, List<String>> = next ?: runCatching {
        ctx.assets.open("nextwords_en.txt").bufferedReader().useLines { Suggest.parseNextWords(it) }
    }.getOrDefault(emptyMap()).also { next = it }

    private fun loadKeywords(ctx: Context): Map<String, String> {
        val kw = HashMap<String, String>()
        runCatching {
            ctx.assets.open("emoji_keywords.txt").bufferedReader().useLines { lines ->
                for (line in lines) {
                    val tab = line.indexOf('\t')
                    if (tab > 0) kw[line.substring(0, tab)] = line.substring(tab + 1)
                }
            }
            // Never suggest an emoji this phone's font can't draw (newer emoji on older Android).
            val glyphs = android.graphics.Paint()
            val drawable = kw.filterValues { glyphs.hasGlyph(it) }
            if (drawable.isNotEmpty()) { kw.clear(); kw.putAll(drawable) }
        }
        return kw
    }
}
