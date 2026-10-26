package dev.larryjones.quietboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.hardware.Sensor
import android.hardware.SensorManager
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
import org.robolectric.shadows.SensorEventBuilder
import org.robolectric.shadows.ShadowSensor
import java.io.File
import kotlin.math.abs

/**
 * The keyboard on a Galaxy Z Fold's inner screen (1968 x 2184 px at 420 dpi: 750 x 832 dp, held
 * upright; some tests turn it), driven like ImeTest drives a phone: the letters split into two
 * halves at the edges, one for each thumb, and the strip, editing panel and number pads follow.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "sw750dp-w750dp-h832dp-port-420dpi")
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

    @Test fun numberPadsSitAtTheRightEdgeWithinThumbReach() {
        edit.inputType = InputType.TYPE_CLASS_NUMBER
        start(3)
        assertEquals(LayoutId.NUMBER, kv.layout!!.id)
        val keys = kv.layout!!.rows.flatMap { it.keys }
        val left = keys.minOf { it.visual.left }
        println("fold: number pad ${(kv.width - left) / dp} dp wide at the right edge")
        assertTrue("as wide as the letters' right half", kv.width - left <= 5 * 50.5f * dp + 8 * dp)
        assertTrue("not cut in two", keys.all { it.visual.left >= left })
        // The empty side is no key: a palm there types nothing.
        val one = keys.first { it.text == "1" }
        val at = SystemClock.uptimeMillis()
        kv.dispatchTouchEvent(MotionEvent.obtain(at, at, MotionEvent.ACTION_DOWN, left / 2, one.visual.centerY(), 0))
        kv.dispatchTouchEvent(MotionEvent.obtain(at, at + 40, MotionEvent.ACTION_UP, left / 2, one.visual.centerY(), 0))
        assertEquals("", text)
        kv.dispatchTouchEvent(MotionEvent.obtain(at + 200, at + 200, MotionEvent.ACTION_DOWN, one.visual.centerX(), one.visual.centerY(), 0))
        kv.dispatchTouchEvent(MotionEvent.obtain(at + 200, at + 240, MotionEvent.ACTION_UP, one.visual.centerX(), one.visual.centerY(), 0))
        assertEquals("1", text)
    }

    @Test fun suggestionsSitOverTheHalvesNotTheGap() {
        waitForEngine()
        start(5)
        type("th")
        val tb = root.topBar
        val (gapL, gapR) = kv.splitSpan!!
        assertEquals("the strip knows where the gap is", kv.splitSpan, tb.split)
        val items = tb.itemsForTest()
        assertTrue("three suggestions: $items", items.all { it != null })
        fun tap(x: Float) {
            val at = SystemClock.uptimeMillis()
            tb.dispatchTouchEvent(MotionEvent.obtain(at, at, MotionEvent.ACTION_DOWN, x, tb.height / 2f, 0))
            tb.dispatchTouchEvent(MotionEvent.obtain(at, at + 40, MotionEvent.ACTION_UP, x, tb.height / 2f, 0))
        }
        tap((gapL + gapR) / 2)
        assertEquals("nothing in the gap", "th", text)
        // The middle suggestion is the first one over the right half, under the right thumb.
        tap(gapR + 10 * dp)
        assertEquals(items[1]!!.word + " ", text)
    }

    @Test fun theEditingPanelSplitsLikeTheKeys() {
        ime.onTool(TopBar.Tool.EDIT)
        layoutRoot()
        val panel = (0 until root.content.childCount).map { root.content.getChildAt(it) }.filterIsInstance<EditPanel>().single()
        val (gapL, gapR) = kv.splitSpan!!
        fun keyX(label: String): Pair<Float, Float> {
            val k = panelKeys(panel).first { it.label == label }
            // Its left edge within the panel (the window isn't attached in a test, so sum the parents' offsets).
            var l = 0f
            var v: View = k
            while (v !== panel) { l += v.left; v = v.parent as View }
            return l to l + k.width
        }
        for (label in listOf("Home", "End", "Select", "Undo", "Redo", "Cut")) {
            assertTrue("$label under the left thumb", keyX(label).second <= gapL + 2 * dp)
        }
        for (label in listOf("Select all", "Copy", "Paste")) {
            assertTrue("$label under the right thumb", keyX(label).first >= gapR - 2 * dp)
        }
    }

    @Test @Config(qualifiers = "sw750dp-w832dp-h750dp-land-420dpi") fun halfFoldedOnATableTheKeysStayWhole() {
        val hinge = addHinge()
        start(6) // the keyboard starts listening to the hinge when it shows
        assertTrue("flat open: split", kv.layout!!.split)
        hingeAt(hinge, 95f)
        assertFalse("half open, hinge across: like a laptop, whole keys", kv.layout!!.split)
        hingeAt(hinge, 155f)
        assertFalse("no flip-flopping just past the limit (out only above 160°)", kv.layout!!.split)
        hingeAt(hinge, 178f)
        assertTrue("opened flat again: split", kv.layout!!.split)
    }

    @Test fun halfOpenLikeABookStaysSplit() {
        val hinge = addHinge()
        start(7)
        hingeAt(hinge, 95f)
        assertTrue("held upright, a half on each panel: still split", kv.layout!!.split)
    }

    private fun addHinge(): Sensor {
        val sensor = ShadowSensor.newInstance(Sensor.TYPE_HINGE_ANGLE)
        shadowOf(ime.getSystemService(SensorManager::class.java)).addSensor(sensor)
        return sensor
    }

    private fun hingeAt(sensor: Sensor, degrees: Float) {
        shadowOf(ime.getSystemService(SensorManager::class.java)).sendSensorEventToListeners(
            SensorEventBuilder.newBuilder().setSensor(sensor).setValues(floatArrayOf(degrees)).setTimestamp(SystemClock.elapsedRealtimeNanos()).build()
        )
        shadowOf(Looper.getMainLooper()).idle()
        layoutRoot()
    }

    private fun panelKeys(v: View): List<PanelKey> = when (v) {
        is PanelKey -> listOf(v)
        is android.view.ViewGroup -> (0 until v.childCount).flatMap { panelKeys(v.getChildAt(it)) }
        else -> emptyList()
    }

    private fun type(s: String) = s.forEach { if (it == ' ') ime.onSpace() else ime.onText(it.toString()) }

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
        waitForEngine()
        type("th")
        shot("letters")
        ime.onLayoutKey(KeyType.TO_SYMBOLS)
        shot("symbols")
        ime.onLayoutKey(KeyType.TO_ALPHA)
        ime.onTool(TopBar.Tool.EDIT)
        shot("editing")
        ime.onTool(TopBar.Tool.EDIT)
        edit.setText("")
        edit.inputType = InputType.TYPE_CLASS_PHONE
        start(9)
        shot("phone_pad")
        edit.inputType = InputType.TYPE_CLASS_TEXT
        start(10)
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
