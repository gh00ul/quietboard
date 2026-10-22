package dev.larryjones.quietboard

import android.app.KeyguardManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Drives the real keyboard service against a real EditText (no phone needed). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w384dp-h832dp-450dpi")
class ImeTest {
    private lateinit var ime: QuietIME
    private lateinit var root: InputRoot
    private lateinit var edit: EditText
    private lateinit var info: EditorInfo

    private val text get() = edit.text.toString()

    @Before fun setUp() {
        val ctx = RuntimeEnvironment.getApplication()
        ime = Robolectric.setupService(QuietIME::class.java)
        root = ime.onCreateInputView() as InputRoot
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.AT_MOST),
        )
        root.layout(0, 0, 1080, root.measuredHeight)
        edit = EditText(ctx).apply { inputType = InputType.TYPE_CLASS_TEXT }
        start(fieldId = 1)
    }

    /** Background work (dictionary load, saves) from one test mustn't land in the next. */
    @After fun tearDown() {
        ime.onDestroy()
    }

    private fun start(fieldId: Int, restarting: Boolean = false, type: Int = InputType.TYPE_CLASS_TEXT) {
        edit.inputType = type
        info = EditorInfo()
        ime.testConnection = edit.onCreateInputConnection(info)
        info.fieldId = fieldId
        info.packageName = "test.app"
        ime.onStartInputView(info, restarting)
    }

    private fun type(s: String) = s.forEach { if (it == ' ') ime.onSpace() else ime.onText(it.toString()) }

    /** Tells the keyboard where the editor's cursor and composing region are, as Android would. */
    private fun reportSelection() {
        val e = edit.text
        val sel = edit.selectionStart to edit.selectionEnd
        ime.onUpdateSelection(sel.first, sel.second, sel.first, sel.second,
            BaseInputConnection.getComposingSpanStart(e), BaseInputConnection.getComposingSpanEnd(e))
    }

    @Test fun typingAtTheEndOfAWordContinuesIt() {
        waitForEngine()
        edit.setText("becau")
        edit.setSelection(5)
        reportSelection()
        type("se ")
        assertEquals("because ", text)
    }

    @Test fun undoingAfterPunctuationKeepsThePunctuation() {
        waitForEngine()
        type("teh.")
        assertEquals("the.", text)
        ime.onDelete()
        assertEquals("the", text)
    }

    @Test fun enterAppliesThePendingCorrection() {
        waitForEngine()
        start(fieldId = 6, type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        type("teh")
        ime.onEnter()
        assertTrue(text.startsWith("the"))
    }

    @Test fun noDoubleSpacePeriodInPasswords() {
        start(fieldId = 7, type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        type("a  ")
        assertEquals("a  ", text)
        assertFalse("no glide typing in password fields", root.keyboard.gestureTyping)
    }

    @Test fun emailFieldsGetNoAutomaticSpaces() {
        waitForEngine()
        start(fieldId = 8, type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        type("gma")
        ime.onSuggestionPicked(SuggestionItem("gmail"))
        assertEquals("gmail", text)
    }

    @Test fun doubleTapOnAutoShiftLocks() {
        start(fieldId = 9, type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        assertEquals("auto-capitalised at the start", ShiftState.ON, root.keyboard.shiftState)
        ime.onShiftPress()
        assertEquals("held shift keeps capitals for chords", ShiftState.ON, root.keyboard.shiftState)
        ime.onShiftRelease(false)
        assertEquals("a tap turns auto-caps off", ShiftState.OFF, root.keyboard.shiftState)
        ime.onShiftPress(); ime.onShiftRelease(false)
        assertEquals("a quick second tap locks", ShiftState.LOCKED, root.keyboard.shiftState)
    }

    @Test fun lockScreenHidesClipboardAndSettings() {
        val km = RuntimeEnvironment.getApplication().getSystemService(KeyguardManager::class.java)
        shadowOf(km).setKeyguardLocked(true)
        start(fieldId = 10)
        ime.onTool(TopBar.Tool.CLIPBOARD)
        assertTrue(root.topBar.locked)
        assertEquals("no clipboard panel was opened", 1, root.content.childCount)
    }

    @Test fun restartingTheSameFieldKeepsSymbolsPage() {
        ime.onLayoutKey(KeyType.TO_SYMBOLS)
        start(fieldId = 1, restarting = true)
        assertEquals("same field restarted", LayoutId.SYMBOLS, root.keyboard.layout?.id)
        start(fieldId = 2, restarting = true)
        assertEquals("a different field starts on letters", LayoutId.ALPHA, root.keyboard.layout?.id)
    }

    @Test fun restartingKeepsCapsLock() {
        ime.onShiftPress(); ime.onShiftRelease(false)
        ime.onShiftPress(); ime.onShiftRelease(false) // double tap = caps lock
        assertEquals(ShiftState.LOCKED, root.keyboard.shiftState)
        start(fieldId = 1, restarting = true)
        assertEquals(ShiftState.LOCKED, root.keyboard.shiftState)
    }

    @Test fun clearedFieldDoesNotBringTheWordBack() {
        type("hello")
        assertEquals("hello", text)
        // A web chat box clears itself after "send" without restarting input.
        edit.text.clear()
        BaseInputConnection.removeComposingSpans(edit.text)
        reportSelection()
        type("x")
        assertEquals("x", text)
    }

    @Test fun staleSelectionUpdateKeepsTheWord() {
        type("h")
        // An update from before "h" was typed arrives late: no composing region reported.
        ime.onUpdateSelection(0, 0, 0, 0, -1, -1)
        type("ey")
        assertEquals("hey", text)
    }

    @Test fun swipeDeleteWithSelectionDeletesSelectionAndWords() {
        type("one two three ")
        edit.setSelection(8, 13) // "three"
        reportSelection()
        ime.onDeleteSwipeStart()
        ime.onDeleteSwipeUpdate(1)
        ime.onDeleteSwipeEnd(true)
        assertEquals("one  ", text)
    }

    @Test fun numberFieldSymbolsPageLeadsBackToNumberPad() {
        start(fieldId = 3, type = InputType.TYPE_CLASS_NUMBER)
        assertEquals(LayoutId.NUMBER, root.keyboard.layout?.id)
        ime.onLayoutKey(KeyType.TO_SYMBOLS)
        val back = root.keyboard.layout!!.rows.flatMap { it.keys }.first { it.type == KeyType.TO_ALPHA }
        assertEquals("123", back.label)
        ime.onLayoutKey(KeyType.TO_ALPHA)
        assertEquals(LayoutId.NUMBER, root.keyboard.layout?.id)
        start(fieldId = 4, type = InputType.TYPE_CLASS_DATETIME)
        assertEquals(LayoutId.DATETIME, root.keyboard.layout?.id)
        // "10:30 PM": letters are reachable from a date/time field via the second symbols page.
        ime.onLayoutKey(KeyType.TO_SYMBOLS)
        ime.onLayoutKey(KeyType.TO_SYMBOLS2)
        assertTrue(root.keyboard.layout!!.rows.flatMap { it.keys }.any { it.type == KeyType.TO_LETTERS })
        ime.onLayoutKey(KeyType.TO_LETTERS)
        assertEquals(LayoutId.ALPHA, root.keyboard.layout?.id)
    }

    @Test fun glideIsDecodedOffThreadButStaysInOrder() {
        waitForEngine()
        val (xs, ys) = swipe("hello")
        ime.onGesture(xs, ys, xs.size)
        // Space arrives before the background decode has been delivered: the word must come first.
        ime.onSpace()
        assertEquals("hello ", text)
        // And a decode that finishes on its own is applied when the main thread runs.
        val (xs2, ys2) = swipe("world")
        ime.onGesture(xs2, ys2, xs2.size)
        var tries = 0
        while (text == "hello " && tries++ < 100) {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertEquals("hello world", text)
    }

    @Test fun glideShowsTheWordWhileSwiping() {
        waitForEngine()
        val (xs, ys) = swipe("hello")
        ime.onGestureStart()
        // Most of the path so far: the decoder runs off the UI thread, the strip updates when it's done.
        val part = (xs.size * 0.8).toInt()
        ime.onGestureProgress(xs.copyOf(part), ys.copyOf(part), part)
        // The strip shows next-word predictions until the preview replaces them.
        var tries = 0
        while (root.topBar.itemsForTest().getOrNull(1)?.bold != true && tries++ < 100) {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
        }
        val preview = root.topBar.itemsForTest().getOrNull(1)
        assertTrue("a bold preview word in the middle of the strip", preview != null && preview.bold)
        assertEquals("nothing is typed until the finger lifts", "", text)
        ime.onGesture(xs, ys, xs.size)
        ime.onSpace()
        assertEquals("hello ", text)
    }

    @Test fun funThemeTypesAndDraws() {
        waitForEngine()
        Prefs(RuntimeEnvironment.getApplication()).sp.edit().putBoolean(Prefs.TAP_SPARKLES, true).commit()
        Prefs(RuntimeEnvironment.getApplication()).sp.edit().putString(Prefs.THEME, "christmas").commit()
        start(fieldId = 30)
        assertEquals(Decor.CHRISTMAS, root.keyboard.theme?.decor)
        assertTrue("the window paints the scene", root.background is ThemeBackdrop)
        val kv = root.keyboard
        val keys = kv.layout!!.rows.flatMap { it.keys }
        val t0 = SystemClock.uptimeMillis()
        for ((n, ch) in "hi".withIndex()) {
            val k = keys.first { it.text == ch.toString() }
            val t = t0 + n * 300L
            kv.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, k.touch.centerX(), k.touch.centerY(), 0))
            kv.dispatchTouchEvent(MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, k.touch.centerX(), k.touch.centerY(), 0))
        }
        // Scene, snow caps, tap bursts: the whole window draws.
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        ime.onSpace()
        assertEquals("hi ", text)
        val (xs, ys) = swipe("hello")
        ime.onGesture(xs, ys, xs.size)
        ime.onSpace()
        assertEquals("hi hello ", text)
    }

    @Test fun lockScreenNeitherShowsNorLearnsYourWords() {
        waitForEngine()
        val h = QuietIME::class.java.getDeclaredField("history").apply { isAccessible = true }.get(ime) as UserHistory
        repeat(3) { h.learn(null, "Zorbix") }
        type("zorb")
        assertTrue("unlocked: your word is offered", root.topBar.itemsForTest().any { it?.word == "Zorbix" })
        val km = RuntimeEnvironment.getApplication().getSystemService(KeyguardManager::class.java)
        shadowOf(km).setKeyguardLocked(true)
        edit.setText("")
        start(fieldId = 40)
        type("zorb")
        assertFalse("locked: not offered", root.topBar.itemsForTest().any { it?.word == "Zorbix" })
        ime.onSpace()
        val before = h.count("hello")
        type("hello ")
        assertEquals("locked: nothing learned", before, h.count("hello"))
    }

    @Test fun numbersAndAddressesAreNotAutocorrected() {
        waitForEngine()
        type("May 4th x@aol.com 'hello' ")
        assertEquals("May 4th x@aol.com 'hello' ", text)
    }

    @Test fun tappedIBeforeAGlideIsCapitalised() {
        waitForEngine()
        type("so i")
        val (xs, ys) = swipe("think")
        ime.onGesture(xs, ys, xs.size)
        ime.onSpace()
        assertTrue(text, text.startsWith("so I "))
    }

    @Test fun lettersTypedOntoAGlideInAUrlFieldAreTypedLetters() {
        waitForEngine()
        start(fieldId = 41, type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val (xs, ys) = swipe("face")
        ime.onGesture(xs, ys, xs.size)
        type("b")
        val glided = text.dropLast(1)
        type("ook")
        ime.onDelete()
        assertEquals("backspace takes one letter, not the whole glide", glided + "boo", text)
    }

    @Test fun glideSpacing() {
        waitForEngine()
        type("well-")
        val (xs, ys) = swipe("known")
        ime.onGesture(xs, ys, xs.size)
        type("5")
        assertTrue("no space after a hyphen, one before a digit: $text", text.startsWith("well-") && !text.startsWith("well- ") && text.endsWith(" 5"))
    }

    @Test fun pickingAWordBeforeTextAddsTheSpace() {
        waitForEngine()
        edit.setText("foo world")
        edit.setSelection(4)
        reportSelection()
        ime.onSuggestionPicked(SuggestionItem("hello"))
        assertEquals("foo hello world", text)
    }

    @Test fun capsFieldsStayInCapitals() {
        start(fieldId = 42, type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
        type("ab")
        assertEquals(ShiftState.ON, root.keyboard.shiftState)
    }

    @Test fun namesAreNotAutocorrected() {
        waitForEngine()
        start(fieldId = 43, type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME)
        type("aoife ")
        assertEquals("aoife ", text)
    }

    private fun tapStrip(x: Float) {
        val tb = root.topBar
        val t = SystemClock.uptimeMillis()
        tb.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, tb.height / 2f, 0))
        tb.dispatchTouchEvent(MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, x, tb.height / 2f, 0))
    }

    @Test fun anEmptyFieldShowsTheToolbar() {
        waitForEngine()
        start(fieldId = 50)
        assertFalse("tools, not sentence starters, when nothing is typed yet", root.topBar.showingSuggestions)
    }

    @Test fun theToolsButtonReachesTheToolbarOverSuggestions() {
        waitForEngine()
        type("thank ")
        assertTrue("predictions after a word", root.topBar.showingSuggestions)
        tapStrip(10f * root.resources.displayMetrics.density) // the tools button at the left
        assertFalse("the toolbar is open", root.topBar.showingSuggestions)
        assertTrue(root.topBar.toolsOpen)
        type("y")
        assertFalse("typing closes it", root.topBar.toolsOpen)
        assertTrue(root.topBar.showingSuggestions)
    }

    @Test fun aFreshCopyShowsThePasteChipUntilYouType() {
        waitForEngine()
        edit.setText("thank ")
        edit.setSelection(6)
        val cm = RuntimeEnvironment.getApplication().getSystemService(android.content.ClipboardManager::class.java)
        cm.setPrimaryClip(android.content.ClipData.newPlainText("t", "copied text"))
        shadowOf(Looper.getMainLooper()).idle()
        start(fieldId = 51)
        assertFalse("the toolbar, not predictions", root.topBar.showingSuggestions)
        assertEquals("copied text", root.topBar.pasteChip)
        type("you")
        ime.onSpace()
        assertTrue("typing moves on: predictions are back", root.topBar.showingSuggestions)
        assertEquals(null, root.topBar.pasteChip)
    }

    private fun stripWords() = root.topBar.itemsForTest().mapNotNull { it?.word }

    @Test fun predictionsComeFromTheWordsBefore() {
        waitForEngine()
        type("I'm going ")
        assertEquals("to", root.topBar.itemsForTest().getOrNull(1)?.word)
        type("to ")
        assertTrue("after \"going to\": ${stripWords()}", "be" in stripWords())
        type("the beach. How are ")
        assertEquals("you", root.topBar.itemsForTest().getOrNull(1)?.word)
        type("you, ")
        assertTrue("predictions after a comma too", stripWords().isNotEmpty())
    }

    @Test fun typingUsesTheWordsBefore() {
        waitForEngine()
        type("I'm going t")
        assertTrue("\"to\" is offered for \"t\": ${stripWords()}", "to" in stripWords())
        type("o the stor")
        assertTrue("completions too: ${stripWords()}", "store" in stripWords())
    }

    @Test fun learnsThreeWordPhrasesFromTyping() {
        waitForEngine()
        // A stronger two-word habit ("sounds good") must lose to the three-word one after "ok sounds".
        repeat(6) { type("that sounds good ") }
        repeat(3) { type("ok sounds chill ") }
        type("that sounds ")
        assertEquals("good", root.topBar.itemsForTest().getOrNull(1)?.word)
        type("ok ok sounds ")
        assertEquals("chill", root.topBar.itemsForTest().getOrNull(1)?.word)
    }

    @Test fun theKeyboardTellsAndroidItShowsLogins() {
        // Without this declaration Android never asks the keyboard for password-manager logins at all.
        val xml = RuntimeEnvironment.getApplication().resources.getXml(R.xml.method)
        var declared = false
        while (xml.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (xml.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && xml.name == "input-method") {
                declared = xml.getAttributeBooleanValue("http://schemas.android.com/apk/res/android", "supportsInlineSuggestions", false)
            }
        }
        assertTrue(declared)
    }

    @Test fun noSpaceBeforeTheNextMessageAfterSend() {
        waitForEngine()
        val (xs, ys) = swipe("hello")
        ime.onGesture(xs, ys, xs.size)
        assertTrue(waitUntil { text == "hello" })
        // The chat app sends and clears its box, restarting input on the same field.
        edit.setText("")
        start(fieldId = 1, restarting = true)
        type("i")
        assertEquals("i", text)
    }

    @Test fun backspaceUndoesACorrectionMadeByEnter() {
        waitForEngine()
        start(fieldId = 7, type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        type("teh")
        ime.onEnter()
        // The test connection can't deliver the Enter key event: put the newline in as the editor would.
        if (text == "the") edit.append("\n")
        assertEquals("the\n", text)
        // The editor reports the corrected word and the newline as two separate cursor moves.
        ime.onUpdateSelection(3, 3, 3, 3, -1, -1)
        ime.onUpdateSelection(3, 3, 4, 4, -1, -1)
        ime.onDelete()
        assertEquals("teh", text)
    }

    @Test fun anAppRestartingInputMidWordKeepsTheWord() {
        waitForEngine()
        type("teh")
        start(fieldId = 1, restarting = true) // a formatter re-sets the text on the same field
        assertEquals("teh", text)
        type(" ")
        assertEquals("still corrected", "the ", text)
    }

    @Test fun glidesInAnAddressBarAreSeparateWords() {
        waitForEngine()
        start(fieldId = 8, type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        for (w in listOf("best", "pizza")) {
            val (xs, ys) = swipe(w)
            ime.onGesture(xs, ys, xs.size)
            assertTrue(waitUntil { text.endsWith(w) })
        }
        assertEquals("best pizza", text)
    }

    @Test fun nameFieldsCapitaliseTheWordAfterAGlide() {
        waitForEngine()
        start(fieldId = 9, type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        val (xs, ys) = swipe("hello")
        ime.onGesture(xs, ys, xs.size)
        assertTrue("glided: $text", waitUntil { text.equals("hello", ignoreCase = true) })
        assertEquals("the next tapped letter starts a new name", ShiftState.ON, root.keyboard.shiftState)
    }

    @Test fun noShortcutsOnTheLockScreen() {
        waitForEngine()
        val prefs = Prefs(RuntimeEnvironment.getApplication())
        prefs.saveShortcuts(mapOf("addr" to "1 Main St"))
        shadowOf(Looper.getMainLooper()).idle()
        start(fieldId = 10)
        type("addr")
        assertTrue("your shortcut when unlocked", root.topBar.itemsForTest().any { it?.word == "1 Main St" })
        ime.onDelete(); ime.onDelete(); ime.onDelete(); ime.onDelete()
        val km = RuntimeEnvironment.getApplication().getSystemService(KeyguardManager::class.java)
        shadowOf(km).setKeyguardLocked(true)
        try {
            start(fieldId = 11)
            type("addr")
            assertFalse("not for whoever holds the locked phone", root.topBar.itemsForTest().any { it?.word == "1 Main St" })
        } finally {
            shadowOf(km).setKeyguardLocked(false)
            prefs.saveShortcuts(emptyMap())
        }
    }

    @Test fun lockedPrefsNeverTouchEncryptedStorage() {
        val app = RuntimeEnvironment.getApplication()
        val locked = object : android.content.ContextWrapper(app) {
            override fun getSharedPreferences(name: String?, mode: Int): android.content.SharedPreferences =
                error("encrypted storage read before the first unlock: $name")
            override fun createDeviceProtectedStorageContext() = app.createDeviceProtectedStorageContext()
        }
        val p = Prefs(locked, unlocked = false)
        assertEquals("defaults until unlock", "system", p.theme)
        assertTrue(p.shortcuts().isEmpty())
        p.saveShortcuts(mapOf("a" to "b")) // quietly not kept
        assertEquals(null, p.emojiRecents())
    }

    @Test fun tapSparklesAreOffUnlessTurnedOn() {
        waitForEngine()
        val prefs = Prefs(RuntimeEnvironment.getApplication())
        prefs.sp.edit().putString(Prefs.THEME, "halloween").commit()
        start(fieldId = 60)
        assertFalse("no bursts by default", root.keyboard.effects)
        prefs.sp.edit().putBoolean(Prefs.TAP_SPARKLES, true).commit()
        shadowOf(Looper.getMainLooper()).idle()
        start(fieldId = 61)
        assertTrue(root.keyboard.effects)
        prefs.sp.edit().putBoolean(Prefs.THEME_EFFECTS, false).putBoolean(Prefs.TAP_SPARKLES, false).putString(Prefs.THEME, "system").commit()
    }

    @Test fun passwordManagerLoginsTakeTheStrip() {
        waitForEngine()
        val v1 = android.os.Bundle().apply {
            putStringArrayList("androidx.autofill.inline.ui.version:key", arrayListOf(InlineAutofill.V1))
        }
        val req = ime.onCreateInlineSuggestionsRequest(v1)
        assertTrue("asks for logins", req != null)
        val style = req!!.inlinePresentationSpecs[0].style.getBundle(InlineAutofill.V1)
        assertTrue("in the chip format the password manager draws", style?.getBoolean("style_v1") == true)
        assertEquals(InlineAutofill.MAX_SUGGESTIONS, req.maxSuggestionCount)
        assertEquals("not if the system can't draw these chips", null, ime.onCreateInlineSuggestionsRequest(android.os.Bundle()))
        val km = RuntimeEnvironment.getApplication().getSystemService(KeyguardManager::class.java)
        shadowOf(km).setKeyguardLocked(true)
        assertEquals("never on the lock screen", null, ime.onCreateInlineSuggestionsRequest(v1))
        shadowOf(km).setKeyguardLocked(false)

        // Two logins arrive: they take the strip, over predictions, until you open the tools.
        type("thank ")
        val ctx = RuntimeEnvironment.getApplication()
        // The system sizes each chip itself: that size must survive being placed.
        val chip = View(ctx).apply { layoutParams = ViewGroup.LayoutParams(300, 80) }
        root.setAutofill(listOf(chip, View(ctx)))
        assertEquals(300, chip.layoutParams.width)
        assertTrue(root.topBar.showingAutofill)
        assertFalse(root.topBar.showingSuggestions)
        tapStrip(10f * root.resources.displayMetrics.density)
        assertTrue(root.topBar.toolsOpen)
        assertFalse("the toolbar covers them", root.topBar.showingAutofill)
        type("y")
        assertFalse("typing a word: suggestions (and the autocorrection) show instead", root.topBar.showingAutofill)
        assertTrue(root.topBar.showingSuggestions)
        ime.onDelete()
        assertTrue("the word's gone: the logins are back", root.topBar.showingAutofill)
        // The form is gone: an empty answer clears them.
        val empty = android.view.inputmethod.InlineSuggestionsResponse::class.java
            .getMethod("newInlineSuggestionsResponse", List::class.java).invoke(null, emptyList<Any>())
            as android.view.inputmethod.InlineSuggestionsResponse
        assertTrue(ime.onInlineSuggestionsResponse(empty))
        assertFalse(root.topBar.showingAutofill)
        assertTrue("predictions are back", root.topBar.showingSuggestions)
    }

    @Test fun worksBeforeTheFirstUnlock() {
        val app = RuntimeEnvironment.getApplication()
        // Words you'd learned before the reboot, saved in encrypted storage.
        java.io.File(app.filesDir, "user_words.tsv").writeText("Zorbix\t5\n")
        val um = app.getSystemService(android.os.UserManager::class.java)
        shadowOf(um).setUserUnlocked(false)
        val km = app.getSystemService(KeyguardManager::class.java)
        shadowOf(km).setKeyguardLocked(true)
        try {
            val locked = Robolectric.setupService(QuietIME::class.java)
            val lockedRoot = locked.onCreateInputView() as InputRoot
            lockedRoot.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.AT_MOST),
            )
            lockedRoot.layout(0, 0, 1080, lockedRoot.measuredHeight)
            val field = EditText(app).apply { inputType = InputType.TYPE_CLASS_TEXT }
            val ei = EditorInfo()
            locked.testConnection = field.onCreateInputConnection(ei)
            ei.fieldId = 70
            ei.packageName = "test.app"
            locked.onStartInputView(ei, false)
            val f = QuietIME::class.java.getDeclaredField("decoder").apply { isAccessible = true }
            assertTrue("the dictionary loads before unlock", waitUntil { f.get(locked) != null })
            "hello there ".forEach { if (it == ' ') locked.onSpace() else locked.onText(it.toString()) }
            assertEquals("the keyboard types", "hello there ", field.text.toString())
            val history = QuietIME::class.java.getDeclaredField("history").apply { isAccessible = true }.get(locked) as UserHistory
            assertEquals("your words stay locked away", 0, history.count("zorbix"))

            // Unlocked: your words load, and nothing was written over them meanwhile.
            shadowOf(um).setUserUnlocked(true)
            shadowOf(km).setKeyguardLocked(false)
            app.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_USER_UNLOCKED))
            assertTrue("your words load on unlock", waitUntil { history.count("zorbix") > 0 })
            assertEquals(5, history.count("zorbix"))
            assertTrue(java.io.File(app.filesDir, "user_words.tsv").readText().contains("Zorbix"))
            locked.onDestroy()
        } finally {
            shadowOf(um).setUserUnlocked(true)
            shadowOf(km).setKeyguardLocked(false)
        }
    }

    @Test fun settingsMoveOverFromOlderVersions() {
        val app = RuntimeEnvironment.getApplication()
        // 1.9 kept everything in encrypted storage.
        val de = app.createDeviceProtectedStorageContext()
        de.getSharedPreferences("storage_state", 0).edit().clear().commit()
        app.getSharedPreferences(Prefs.FILE, 0).edit()
            .putString(Prefs.THEME, "neon")
            .putString(Prefs.SHORTCUTS, "[{\"k\":\"addr\",\"v\":\"1 Main St\"}]")
            .commit()
        val p = Prefs(app)
        assertEquals("neon", p.theme)
        assertEquals("1 Main St", p.shortcuts()["addr"])
        assertFalse("shortcuts stay in encrypted storage", p.sp.contains(Prefs.SHORTCUTS))
        p.sp.edit().putString(Prefs.THEME, "system").commit()
    }

    @Test fun glideAfterPeriodIsCapitalized() {
        waitForEngine()
        edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        start(fieldId = 5, type = edit.inputType)
        type("Hi.")
        val (xs, ys) = swipe("hello")
        ime.onGesture(xs, ys, xs.size)
        ime.onSpace()
        assertEquals("Hi. Hello ", text)
    }

    // ---------------------------------------------------------------- your saved emails and addresses

    private val myEmail = "larry@example.com"
    private val myAddress = "1234 W Main St, Phoenix, AZ 85001"

    private fun savePersonal(emails: List<String> = listOf(myEmail), addresses: List<String> = listOf(myAddress)) {
        Prefs(RuntimeEnvironment.getApplication()).apply {
            saveEmails(emails)
            saveAddresses(addresses)
        }
        shadowOf(Looper.getMainLooper()).idle() // the keyboard hears of the change
    }

    private fun strip() = root.topBar.itemsForTest()

    /** Only one thing in the strip: your saved [value], in the middle (drawn across the whole strip). */
    private fun assertOnlyMine(value: String) {
        val items = strip()
        assertEquals(value, items.getOrNull(1)?.word)
        assertTrue("nothing beside it", items.getOrNull(0) == null && items.getOrNull(2) == null)
    }

    @Test fun yourEmailShowsInEmailFieldsAndReplacesWhatYouTyped() {
        waitForEngine()
        savePersonal()
        start(fieldId = 60, type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        assertOnlyMine(myEmail)
        assertEquals("nothing typed yet", "", strip()[1]?.replaces)
        type("LAR")
        assertOnlyMine(myEmail)
        assertEquals("LAR", strip()[1]?.replaces)
        ime.onSuggestionPicked(strip()[1]!!)
        assertEquals("no space after it in an email field", myEmail, text)
        val h = QuietIME::class.java.getDeclaredField("history").apply { isAccessible = true }.get(ime) as UserHistory
        assertEquals("the half-typed part isn't learned", 0, h.count("lar"))
        assertTrue("typed in full: not offered again", strip().none { it?.replaces != null })
    }

    @Test fun yourEmailShowsInPlainTextOnceItsClearlyAnEmail() {
        waitForEngine()
        savePersonal()
        start(fieldId = 61)
        type("mail lar")
        assertEquals("offered on the right", myEmail, strip().getOrNull(2)?.word)
        assertTrue("the word you're typing keeps the middle", strip().getOrNull(1)?.replaces == null)
        type("ry@ex")
        assertOnlyMine(myEmail)
        ime.onSuggestionPicked(strip()[1]!!)
        assertEquals("mail $myEmail ", text)
    }

    @Test fun yourAddressShowsInAddressFields() {
        waitForEngine()
        savePersonal()
        start(fieldId = 62, type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS)
        assertOnlyMine(myAddress)
        type("1234 W")
        assertOnlyMine(myAddress)
        assertEquals("1234 W", strip()[1]?.replaces)
        ime.onSpace()
        assertEquals("space never corrects your own address", "1234 W ", text)
        type("m")
        ime.onSuggestionPicked(strip()[1]!!)
        assertEquals(myAddress, text)
    }

    @Test fun streetLineFieldsGetJustTheStreet() {
        savePersonal()
        edit.hint = "Address line 1"
        start(fieldId = 63)
        assertOnlyMine("1234 W Main St")
        edit.hint = "Address line 2"
        start(fieldId = 64)
        assertTrue("not in the apartment line", strip().none { it?.replaces != null })
        edit.hint = "Email"
        start(fieldId = 65)
        assertOnlyMine(myEmail)
    }

    @Test fun yourEmailIsNeverShownOnTheLockScreen() {
        savePersonal()
        val km = RuntimeEnvironment.getApplication().getSystemService(KeyguardManager::class.java)
        shadowOf(km).setKeyguardLocked(true)
        start(fieldId = 66, type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        type("lar")
        assertTrue(strip().none { it?.replaces != null })
    }

    @Test fun nothingSavedChangesNothing() {
        waitForEngine()
        start(fieldId = 67, type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        type("gma")
        assertTrue(strip().none { it?.replaces != null })
        ime.onSuggestionPicked(SuggestionItem("gmail"))
        assertEquals("gmail", text)
    }

    private fun waitUntil(cond: () -> Boolean): Boolean {
        repeat(300) {
            shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return true
            Thread.sleep(20)
        }
        return false
    }

    private fun waitForEngine() {
        val f = QuietIME::class.java.getDeclaredField("decoder").apply { isAccessible = true }
        repeat(300) {
            shadowOf(Looper.getMainLooper()).idle()
            if (f.get(ime) != null) return
            Thread.sleep(20)
        }
        error("dictionary never loaded")
    }

    /** A straight-line swipe through the word's keys on the real keyboard geometry. */
    private fun swipe(word: String): Pair<FloatArray, FloatArray> {
        val (cx, cy) = root.keyboard.letterCenters()
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
        for (i in 0 until word.length - 1) {
            val a = word[i] - 'a'; val b = word[i + 1] - 'a'
            for (s in 0 until 12) {
                val t = s / 12f
                xs += cx[a] + t * (cx[b] - cx[a]); ys += cy[a] + t * (cy[b] - cy[a])
            }
        }
        xs += cx[word.last() - 'a']; ys += cy[word.last() - 'a']
        return xs.toFloatArray() to ys.toFloatArray()
    }
}
