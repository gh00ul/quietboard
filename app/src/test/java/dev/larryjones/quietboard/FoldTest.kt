package dev.larryjones.quietboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/**
 * The keyboard on a Galaxy Z Fold's inner screen (2184 x 1968 px at 420 dpi: 832 x 750 dp), driven
 * like ImeTest drives a phone: the letters split into two halves at the edges, one for each thumb.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "sw750dp-w832dp-h750dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FoldTest {
    private lateinit var ime: QuietIME
    private lateinit var root: InputRoot
    private lateinit var edit: EditText
    private val ctx get() = RuntimeEnvironment.getApplication()
    private val dp get() = ctx.resources.displayMetrics.density
    private val text get() = edit.text.toString()

    @Before fun setUp() {
        ime = Robolectric.setupService(QuietIME::class.java)
        root = ime.onCreateInputView() as InputRoot
        edit = EditText(ctx).apply { inputType = InputType.TYPE_CLASS_TEXT }
        start(1)
    }

    @After fun tearDown() {
        ime.onDestroy()
    }

    private fun start(fieldId: Int) {
        val info = EditorInfo()
        ime.testConnection = edit.onCreateInputConnection(info)
        info.fieldId = fieldId
        info.packageName = "test.app"
        ime.onStartInputView(info, false)
        layoutRoot()
    }

    private fun layoutRoot() {
        val w = ctx.resources.displayMetrics.widthPixels
        root.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(ctx.resources.displayMetrics.heightPixels, View.MeasureSpec.AT_MOST),
        )
        root.layout(0, 0, w, root.measuredHeight)
    }

    private val kv get() = root.keyboard
    private fun key(c: Char) = kv.layout!!.rows.flatMap { it.keys }.first { it.type == KeyType.CHAR && it.text == c.toString() }

    @Test fun unfoldedTheLettersSplitIntoTwoHalves() {
        val l = kv.layout!!
        assertTrue("split on the unfolded screen", l.split)
        val w = kv.width.toFloat()
        val t = key('t'); val y = key('y'); val g = key('g'); val h = key('h')
        val gap = y.visual.left - t.visual.right
        println("fold: keyboard ${w / dp} dp wide, keys ${kv.keyWidthPx() / dp} dp, gap ${gap / dp} dp")
        assertTrue("halves at the edges", key('q').visual.left < 8 * dp && key('p').visual.right > w - 8 * dp)
        assertTrue("phone-sized keys, within a thumb's reach", kv.keyWidthPx() <= 50.5f * dp)
        assertTrue("a real gap between the halves", gap > 3 * kv.keyWidthPx())
        assertTrue("g on the left, h on the right", g.visual.right < w / 2 && h.visual.left > w / 2)
        val spaces = l.rows.last().keys.filter { it.type == KeyType.SPACE }
        assertEquals("a space bar in each half", 2, spaces.size)
        assertTrue(spaces[0].visual.right < w / 2 && spaces[1].visual.left > w / 2)
    }

    @Test fun aThumbJustInsideTheGapTypesButAPalmInItDoesNot() {
        val t = key('t'); val y = key('y')
        val gap = y.visual.left - t.visual.right
        val cy = t.visual.centerY()
        fun tap(x: Float, at: Long) {
            kv.dispatchTouchEvent(MotionEvent.obtain(at, at, MotionEvent.ACTION_DOWN, x, cy, 0))
            kv.dispatchTouchEvent(MotionEvent.obtain(at, at + 40, MotionEvent.ACTION_UP, x, cy, 0))
        }
        val now = SystemClock.uptimeMillis()
        tap(t.visual.right + kv.keyWidthPx() * 0.3f, now)
        tap(t.visual.right + gap / 2, now + 200)
        tap(y.visual.left - kv.keyWidthPx() * 0.3f, now + 400)
        assertEquals("t and y, nothing from the middle of the gap", "ty", text)
    }

    @Test fun glidesAcrossTheGapDecodeLikeOneKeyboard() {
        val (cx, _) = kv.glideCenters()
        val kw = kv.keyWidthPx()
        fun step(a: Char, b: Char) = (cx[b - 'a'] - cx[a - 'a']) / kw
        assertEquals("t to y is one key without the gap", 1f, step('t', 'y'), 0.01f)
        assertEquals(1f, step('g', 'h'), 0.01f)
        assertEquals(1f, step('v', 'b'), 0.01f)
        waitForEngine()
        for (word in listOf("hello", "thing", "your", "night")) {
            edit.setText("")
            start(10 + word.length)
            val (xs, ys) = swipe(word)
            ime.onGesture(xs, ys, xs.size)
            assertTrue("glided $word: $text", waitUntil { text.trim().equals(word, ignoreCase = true) })
        }
    }

    @Test fun oneHandedJoinsTheHalvesAtPhoneSize() {
        ime.onTool(TopBar.Tool.ONE_HAND)
        layoutRoot()
        assertFalse("one-handed isn't split", kv.layout!!.split)
        val keys = kv.layout!!.rows.flatMap { it.keys }
        val span = keys.maxOf { it.visual.right } - keys.minOf { it.visual.left }
        assertTrue("no wider than a phone keyboard: ${span / dp} dp", span <= 400 * dp)
        ime.onTool(TopBar.Tool.ONE_HAND)
        layoutRoot()
        assertTrue("and split again after", kv.layout!!.split)
    }

    @Test fun splitCanBeTurnedOff() {
        Prefs(ctx).sp.edit().putString(Prefs.SPLIT_KEYBOARD, "off").commit()
        shadowOf(Looper.getMainLooper()).idle()
        edit.setText("")
        start(2)
        assertFalse(kv.layout!!.split)
    }

    @Test fun numberPadsStayWhole() {
        edit.inputType = InputType.TYPE_CLASS_NUMBER
        start(3)
        assertEquals(LayoutId.NUMBER, kv.layout!!.id)
        assertFalse(kv.layout!!.split)
    }

    @Test @Config(qualifiers = "w384dp-h832dp-450dpi") fun phonesDontSplit() {
        assertFalse(kv.layout!!.split)
    }

    @Test @Config(qualifiers = "w832dp-h384dp-450dpi") fun phonesInLandscapeSplitOnlyIfYouAsk() {
        assertFalse("not by default", kv.layout!!.split)
        Prefs(ctx).sp.edit().putString(Prefs.SPLIT_KEYBOARD, "wide").commit()
        shadowOf(Looper.getMainLooper()).idle()
        edit.setText("")
        start(4)
        assertTrue(kv.layout!!.split)
    }

    /** QB_SCREENSHOTS=1: the unfolded keyboard as drawn, for a look (build/screens/fold_*.png). */
    @Test fun screenshots() {
        assumeTrue(System.getenv("QB_SCREENSHOTS") == "1")
        fun shot(name: String) {
            layoutRoot()
            val bmp = Bitmap.createBitmap(root.measuredWidth, root.measuredHeight, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bmp))
            File("build/screens").mkdirs()
            File("build/screens/fold_$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        shot("letters")
        ime.onLayoutKey(KeyType.TO_SYMBOLS)
        shot("symbols")
        ime.onLayoutKey(KeyType.TO_ALPHA)
        ime.onTool(TopBar.Tool.ONE_HAND)
        shot("one_handed")
    }

    // ---------------------------------------------------------------- helpers (as in ImeTest)

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
        assertTrue("dictionary never loaded", waitUntil { f.get(ime) != null })
    }

    /** A straight-line swipe through the word's keys where they really are (across the gap). */
    private fun swipe(word: String): Pair<FloatArray, FloatArray> {
        val (cx, cy) = kv.letterCenters()
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
        for (i in 0 until word.length - 1) {
            val a = word[i] - 'a'; val b = word[i + 1] - 'a'
            val steps = maxOf(12, (abs(cx[b] - cx[a]) / kv.keyWidthPx() * 6).toInt())
            for (s in 0 until steps) {
                val t = s / steps.toFloat()
                xs += cx[a] + (cx[b] - cx[a]) * t; ys += cy[a] + (cy[b] - cy[a]) * t
            }
        }
        xs += cx[word.last() - 'a']; ys += cy[word.last() - 'a']
        return xs.toFloatArray() to ys.toFloatArray()
    }
}
