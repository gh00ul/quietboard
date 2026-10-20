package dev.larryjones.quietboard

import android.app.KeyguardManager
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
