package dev.larryjones.quietboard

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w384dp-h832dp-450dpi")
class FeatureTest {

    @Test fun pinnedClipsNeverExpireOrClear() {
        val ctx = RuntimeEnvironment.getApplication()
        val now = System.currentTimeMillis()
        val old = now - 3 * 60 * 60 * 1000L
        val arr = JSONArray()
            .put(JSONObject().put("t", "recent").put("at", now))
            .put(JSONObject().put("t", "old pinned").put("at", old).put("p", true))
            .put(JSONObject().put("t", "old unpinned").put("at", old))
        File(ctx.filesDir, "clipboard.json").writeText(arr.toString())

        val cb = ClipboardHistory(ctx, Prefs(ctx))
        cb.start()
        val list = cb.list().map { it.text }
        assertEquals("pinned first, expired dropped", listOf("old pinned", "recent"), list)

        cb.clear()
        assertEquals(listOf("old pinned"), cb.list().map { it.text })

        cb.togglePin(cb.list()[0])
        assertTrue("an old clip expires as soon as it is unpinned", cb.list().isEmpty())
        cb.stop()

        val dir = java.nio.file.Files.createTempDirectory("clip").toFile()
        File(dir, "clipboard.json").writeText(arr.toString())
        ClipboardHistory.clearUnpinnedOnDisk(dir)
        val left = JSONArray(File(dir, "clipboard.json").readText())
        assertEquals(1, left.length())
        assertEquals("old pinned", left.getJSONObject(0).getString("t"))
    }

    @Test fun hapticsFeelDifferentPerEvent() {
        for (level in listOf(Feedback.LIGHT, Feedback.MEDIUM, Feedback.STRONG)) {
            val key = Feedback.primitiveFor(Haptic.KEY, level).second
            assertTrue("function keys land firmer", Feedback.primitiveFor(Haptic.FUNCTION, level).second >= key)
            assertTrue("long press is the firmest", Feedback.primitiveFor(Haptic.LONG_PRESS, level).second >= key)
            assertTrue("delete repeat is lighter", Feedback.primitiveFor(Haptic.REPEAT, level).second < key)
            assertTrue("cursor steps are faint", Feedback.primitiveFor(Haptic.TICK, level).second < key)
            assertTrue(Feedback.primitiveFor(Haptic.KEY, level).second in 0.01f..1f)
        }
        assertTrue("stronger settings are stronger",
            Feedback.strength(Feedback.LIGHT) < Feedback.strength(Feedback.MEDIUM) &&
                Feedback.strength(Feedback.MEDIUM) < Feedback.strength(Feedback.STRONG))
        assertEquals(android.view.HapticFeedbackConstants.KEYBOARD_PRESS, Feedback.systemConstant(Haptic.KEY))
        assertEquals(android.view.HapticFeedbackConstants.TEXT_HANDLE_MOVE, Feedback.systemConstant(Haptic.TICK))
    }

    @Test fun phoneDefaultHapticsGoThroughTheSystem() {
        val ctx = RuntimeEnvironment.getApplication()
        val prefs = Prefs(ctx)
        prefs.sp.edit().putInt(Prefs.VIBRATION, Feedback.SYSTEM).commit()
        val view = View(ctx)
        val f = Feedback(ctx, prefs).apply { attach(view) }
        f.key(KeyType.CHAR)
        assertEquals(android.view.HapticFeedbackConstants.KEYBOARD_PRESS, shadowOf(view).lastHapticFeedbackPerformed())
        f.haptic(Haptic.LONG_PRESS)
        assertEquals(android.view.HapticFeedbackConstants.LONG_PRESS, shadowOf(view).lastHapticFeedbackPerformed())
        // The motor modes never crash, whatever the device supports.
        for (level in listOf(Feedback.OFF, Feedback.LIGHT, Feedback.MEDIUM, Feedback.STRONG)) {
            prefs.sp.edit().putInt(Prefs.VIBRATION, level).commit()
            for (h in Haptic.entries) f.haptic(h)
        }
    }

    @Test fun turningHistoryOffForgetsClips() {
        val ctx = RuntimeEnvironment.getApplication()
        val now = System.currentTimeMillis()
        File(ctx.filesDir, "clipboard.json").writeText(
            JSONArray().put(JSONObject().put("t", "secret").put("at", now))
                .put(JSONObject().put("t", "kept").put("at", now).put("p", true)).toString()
        )
        val prefs = Prefs(ctx)
        prefs.sp.edit().putBoolean(Prefs.CLIPBOARD_HISTORY, false).commit()
        val cb = ClipboardHistory(ctx, prefs)
        cb.start()
        assertEquals(listOf("kept"), cb.list().map { it.text })
        cb.stop()
    }

    @Test fun clipboardRecordsTextButNotImagesOrPrivateCopies() {
        val ctx = RuntimeEnvironment.getApplication()
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        val cb = ClipboardHistory(ctx, Prefs(ctx))
        var private = false
        cb.skipCapture = { private }
        cb.start()
        cm.setPrimaryClip(ClipData.newPlainText("t", "hello there"))
        cm.setPrimaryClip(ClipData("img", arrayOf("image/png"), ClipData.Item(Uri.parse("content://media/external/images/1"))))
        private = true
        cm.setPrimaryClip(ClipData.newPlainText("t", "incognito text"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("hello there"), cb.list().map { it.text })
        cb.stop()
    }

    @Test fun expiredClipsAreDeletedFromDiskWithoutTheKeyboard() {
        val ctx = RuntimeEnvironment.getApplication()
        val now = System.currentTimeMillis()
        File(ctx.filesDir, "clipboard.json").writeText(
            JSONArray().put(JSONObject().put("t", "old").put("at", now - 2 * 60 * 60 * 1000L))
                .put(JSONObject().put("t", "new").put("at", now))
                .put(JSONObject().put("t", "pinned").put("at", now - 9 * 60 * 60 * 1000L).put("p", true)).toString()
        )
        ClipboardHistory.pruneExpiredOnDisk(ctx)
        val left = JSONArray(File(ctx.filesDir, "clipboard.json").readText())
        assertEquals(setOf("new", "pinned"), (0 until left.length()).map { left.getJSONObject(it).getString("t") }.toSet())
    }

    private val noop = object : KeyboardView.Listener {
        override fun onKeyFeedback(key: Key) {}
        override fun onText(text: String) {}
        override fun onDelete() {}
        override fun onSpace() {}
        override fun onEnter() {}
        override fun onShiftPress() {}
        override fun onShiftRelease(chorded: Boolean) {}
        override fun onLayoutKey(type: KeyType) {}
        override fun onSpaceLongPress() {}
        override fun onCursorMove(steps: Int) {}
        override fun onCursorMoveEnd() {}
        override fun onDeleteSwipeStart() {}
        override fun onDeleteSwipeUpdate(words: Int) {}
        override fun onDeleteSwipeEnd(commit: Boolean) {}
        override fun onGesture(xs: FloatArray, ys: FloatArray, count: Int) {}
        override fun onOneHandedSwap() {}
        override fun onOneHandedExit() {}
    }

    private fun laidOutRoot(): InputRoot {
        val root = InputRoot(RuntimeEnvironment.getApplication())
        root.keyboard.setLayout(Layouts.build(LayoutId.ALPHA, LayoutOptions(false, false, FieldKind.TEXT)))
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.AT_MOST),
        )
        root.layout(0, 0, 1080, root.measuredHeight)
        return root
    }

    @Test fun rolloverFromSymbolsKeyTypesTheKeyNowOnScreen() {
        val root = laidOutRoot()
        val kv = root.keyboard
        val opts = LayoutOptions(false, false, FieldKind.TEXT)
        val typed = ArrayList<String>()
        kv.listener = object : KeyboardView.Listener by noop {
            override fun onText(text: String) { typed += text }
            override fun onLayoutKey(type: KeyType) {
                if (type == KeyType.TO_SYMBOLS) kv.setLayout(Layouts.build(LayoutId.SYMBOLS, opts))
            }
        }
        val keys = kv.layout!!.rows.flatMap { it.keys }
        val sym = keys.first { it.type == KeyType.TO_SYMBOLS }
        val q = keys.first { it.text == "q" }
        val props = Array(2) { i -> MotionEvent.PointerProperties().apply { id = i } }
        fun at(vararg xy: Float) = Array(xy.size / 2) { i -> MotionEvent.PointerCoords().apply { x = xy[2 * i]; y = xy[2 * i + 1] } }
        val both = at(sym.touch.centerX(), sym.touch.centerY(), q.touch.centerX(), q.touch.centerY())
        val t = SystemClock.uptimeMillis()
        // Hold "?123", land a second finger where "1" will be, lift the first, then the second.
        kv.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, sym.touch.centerX(), sym.touch.centerY(), 0))
        kv.dispatchTouchEvent(MotionEvent.obtain(t, t + 40, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2, props, both, 0, 0, 1f, 1f, 0, 0, 0, 0))
        kv.dispatchTouchEvent(MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_POINTER_UP, 2, props, both, 0, 0, 1f, 1f, 0, 0, 0, 0))
        kv.dispatchTouchEvent(MotionEvent.obtain(t, t + 80, MotionEvent.ACTION_UP, 1, arrayOf(props[1]),
            at(q.touch.centerX(), q.touch.centerY()), 0, 0, 1f, 1f, 0, 0, 0, 0))
        assertEquals(LayoutId.SYMBOLS, kv.layout!!.id)
        assertEquals(listOf("1"), typed)
    }

    private fun tap2(kv: KeyboardView, first: Key, second: Key) {
        // first held, second taps and lifts, then first lifts (overlapping thumbs)
        val props = Array(2) { i -> MotionEvent.PointerProperties().apply { id = i } }
        fun at(vararg xy: Float) = Array(xy.size / 2) { i -> MotionEvent.PointerCoords().apply { x = xy[2 * i]; y = xy[2 * i + 1] } }
        val both = at(first.touch.centerX(), first.touch.centerY(), second.touch.centerX(), second.touch.centerY())
        val t = SystemClock.uptimeMillis()
        kv.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, first.touch.centerX(), first.touch.centerY(), 0))
        kv.dispatchTouchEvent(MotionEvent.obtain(t, t + 40, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2, props, both, 0, 0, 1f, 1f, 0, 0, 0, 0))
        kv.dispatchTouchEvent(MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2, props, both, 0, 0, 1f, 1f, 0, 0, 0, 0))
        kv.dispatchTouchEvent(MotionEvent.obtain(t, t + 80, MotionEvent.ACTION_UP, first.touch.centerX(), first.touch.centerY(), 0))
    }

    @Test fun heldBackspaceIsAppliedBeforeTheNextLetter() {
        val root = laidOutRoot()
        val kv = root.keyboard
        val events = ArrayList<String>()
        kv.listener = object : KeyboardView.Listener by noop {
            override fun onText(text: String) { events += text }
            override fun onDelete() { events += "DEL" }
        }
        val keys = kv.layout!!.rows.flatMap { it.keys }
        tap2(kv, keys.first { it.type == KeyType.DELETE }, keys.first { it.text == "s" })
        assertEquals("cat -> cas, not cat", listOf("DEL", "s"), events)
    }

    @Test fun popupStartsOnTheDefaultAlternate() {
        val ctx = RuntimeEnvironment.getApplication()
        val ov = PopupOverlay(ctx)
        ov.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
        ov.layout(0, 0, 1080, 600)
        val items = List(10) { "a$it" }
        // Near the right edge: the popup is reversed and pushed left; the default must still win.
        val key = android.graphics.RectF(990f, 400f, 1075f, 520f)
        ov.showPopup(key, items, key.centerX())
        assertEquals("a0", ov.popupSelection())
        // Moving one cell left from where the finger was selects the next alternate.
        ov.updatePopup(key.centerX() - 1080f / 10)
        assertEquals("a1", ov.popupSelection())
    }

    @Test fun keyboardHeightIsCappedInLandscape() {
        RuntimeEnvironment.setQualifiers("w800dp-h360dp-land")
        val root = InputRoot(RuntimeEnvironment.getApplication())
        val kv = root.keyboard
        kv.heightScale = 1.24f
        kv.referenceRows = 4.82f
        kv.setLayout(Layouts.build(LayoutId.ALPHA, LayoutOptions(true, false, FieldKind.TEXT)))
        kv.measure(View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val screenH = RuntimeEnvironment.getApplication().resources.displayMetrics.heightPixels
        assertTrue("keys ${kv.measuredHeight}px of a $screenH px tall screen", kv.measuredHeight <= screenH * 0.43f)
    }

    @Test fun staleTalkBackIdsDoNothingAfterALayoutSwitch() {
        val root = laidOutRoot()
        val kv = root.keyboard
        val opts = LayoutOptions(false, false, FieldKind.TEXT)
        var deletes = 0
        kv.listener = object : KeyboardView.Listener by noop {
            override fun onDelete() { deletes++ }
            override fun onLayoutKey(type: KeyType) {
                if (type == KeyType.TO_SYMBOLS) kv.setLayout(Layouts.build(LayoutId.SYMBOLS, opts))
            }
        }
        val provider = kv.accessibilityNodeProvider!!
        val symbolsId = kv.accessibilityIdOf(kv.layout!!.rows.flatMap { it.keys }.first { it.type == KeyType.TO_SYMBOLS })
        assertEquals("Symbols", provider.createAccessibilityNodeInfo(symbolsId)!!.contentDescription.toString())
        assertTrue(provider.performAction(symbolsId, AccessibilityNodeInfo.ACTION_CLICK, null))
        assertEquals(LayoutId.SYMBOLS, kv.layout!!.id)
        // Same position is now another key on the new layout: the old id must not trigger it.
        assertTrue(!provider.performAction(symbolsId, AccessibilityNodeInfo.ACTION_CLICK, null))
        assertEquals(0, deletes)
    }

    @Test fun longPressThatDoesNothingDoesntEatTheTap() {
        val ctx = RuntimeEnvironment.getApplication()
        val tb = TopBar(ctx)
        val picked = ArrayList<String>()
        tb.listener = object : TopBar.Listener {
            override fun onSuggestionPicked(item: SuggestionItem) { picked += item.word }
            override fun onSuggestionLongPress(item: SuggestionItem) = false // not a learned word
            override fun onTool(tool: TopBar.Tool) {}
        }
        tb.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        tb.layout(0, 0, 1080, tb.measuredHeight)
        tb.setSuggestions(listOf(null, SuggestionItem("hello"), null))
        val t = SystemClock.uptimeMillis()
        tb.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 540f, 50f, 0))
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(700))
        tb.dispatchTouchEvent(MotionEvent.obtain(t, t + 700, MotionEvent.ACTION_UP, 540f, 50f, 0))
        assertEquals(listOf("hello"), picked)
    }

    @Test fun talkBackSeesAndTypesKeys() {
        val root = laidOutRoot()
        val kv = root.keyboard
        val typed = ArrayList<String>()
        var deletes = 0
        kv.listener = object : KeyboardView.Listener by noop {
            override fun onText(text: String) { typed += text }
            override fun onDelete() { deletes++ }
        }
        val provider: AccessibilityNodeProvider = kv.accessibilityNodeProvider!!
        val host = provider.createAccessibilityNodeInfo(AccessibilityNodeProvider.HOST_VIEW_ID)!!
        val keys = kv.layout!!.rows.flatMap { it.keys }
        assertEquals("one virtual view per key", keys.size, host.childCount)
        fun idOf(pred: (Key) -> Boolean) = kv.accessibilityIdOf(keys.first(pred))
        val qId = idOf { it.text == "q" }
        assertEquals("q", provider.createAccessibilityNodeInfo(qId)!!.contentDescription.toString())
        assertEquals("Delete", provider.createAccessibilityNodeInfo(idOf { it.type == KeyType.DELETE })!!.contentDescription.toString())
        assertEquals("Comma", provider.createAccessibilityNodeInfo(idOf { it.text == "," })!!.contentDescription.toString())
        assertTrue(provider.performAction(qId, AccessibilityNodeInfo.ACTION_CLICK, null))
        assertTrue(provider.performAction(idOf { it.type == KeyType.DELETE }, AccessibilityNodeInfo.ACTION_CLICK, null))
        assertEquals(listOf("q"), typed)
        assertEquals(1, deletes)
    }

    @Test fun oneHandedModeLayoutAndButtons() {
        val ctx = RuntimeEnvironment.getApplication()
        val root = InputRoot(ctx)
        var swaps = 0
        var exits = 0
        root.keyboard.listener = object : KeyboardView.Listener {
            override fun onKeyFeedback(key: Key) {}
            override fun onText(text: String) {}
            override fun onDelete() {}
            override fun onSpace() {}
            override fun onEnter() {}
            override fun onShiftPress() {}
            override fun onShiftRelease(chorded: Boolean) {}
            override fun onLayoutKey(type: KeyType) {}
            override fun onSpaceLongPress() {}
            override fun onCursorMove(steps: Int) {}
            override fun onCursorMoveEnd() {}
            override fun onDeleteSwipeStart() {}
            override fun onDeleteSwipeUpdate(words: Int) {}
            override fun onDeleteSwipeEnd(commit: Boolean) {}
            override fun onGesture(xs: FloatArray, ys: FloatArray, count: Int) {}
            override fun onOneHandedSwap() { swaps++ }
            override fun onOneHandedExit() { exits++ }
        }
        val kv = root.keyboard
        kv.setLayout(Layouts.build(LayoutId.ALPHA, LayoutOptions(false, false, FieldKind.TEXT)))
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.AT_MOST),
        )
        root.layout(0, 0, 1080, root.measuredHeight)
        val w = kv.width.toFloat()

        kv.oneHanded = 1
        val keys = kv.layout!!.rows.flatMap { it.keys }
        assertTrue("right-hand keys stay in the right 80%", keys.all { it.visual.left >= w * 0.2f - 1 && it.visual.right <= w + 1 })
        kv.oneHanded = -1
        assertTrue("left-hand keys stay in the left 80%", keys.all { it.visual.left >= -1 && it.visual.right <= w * 0.8f + 1 })

        // Side buttons sit in the free 20%, stacked around the vertical center.
        val dp = ctx.resources.displayMetrics.density
        val cx = w * 0.9f
        val cy = kv.height / 2f
        fun tap(x: Float, y: Float) {
            val t = SystemClock.uptimeMillis()
            kv.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
            kv.dispatchTouchEvent(MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, x, y, 0))
            shadowOf(Looper.getMainLooper()).idle()
        }
        tap(cx, cy - 6 * dp - 20 * dp)
        assertEquals(1, swaps)
        tap(cx, cy + 6 * dp + 20 * dp)
        assertEquals(1, exits)
    }
}
