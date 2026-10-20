package dev.larryjones.quietboard

import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Android's IME frame measures the input view with "at most the screen height".
 * The keyboard must report only its own height, or the IME window fills the screen
 * and the keys end up at the top of the phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w384dp-h832dp-450dpi")
class LayoutTest {
    private val screenW = 1080
    private val screenH = 2340

    private fun measure(root: InputRoot): Int {
        root.measure(
            View.MeasureSpec.makeMeasureSpec(screenW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(screenH, View.MeasureSpec.AT_MOST),
        )
        root.layout(0, 0, screenW, root.measuredHeight)
        return root.measuredHeight
    }

    private fun newRoot(): InputRoot {
        val root = InputRoot(RuntimeEnvironment.getApplication())
        root.keyboard.setLayout(Layouts.build(LayoutId.ALPHA, LayoutOptions(false, false, FieldKind.TEXT)))
        return root
    }

    @Test fun keyboardWrapsContent() {
        val root = newRoot()
        val h = measure(root)
        val expected = root.topBar.barHeight + root.keyboard.measuredHeight
        println("keyboard window height: $h px (screen $screenH px, expected $expected px)")
        assertEquals(expected, h)
        assertEquals("overlay must cover exactly the keyboard", h, root.overlay.measuredHeight)
    }

    @Test fun panelsDoNotResizeKeyboard() {
        val ctx = RuntimeEnvironment.getApplication()
        val root = newRoot()
        val base = measure(root)

        val clip = ClipboardPanel(ctx, object : ClipboardPanel.Listener {
            override fun onClipPaste(text: String) {}
            override fun onClipPin(item: ClipboardHistory.Item) {}
            override fun onClipRemove(item: ClipboardHistory.Item) {}
            override fun onClipClear() {}
            override fun onPanelBack() {}
        })
        clip.setItems(List(30) { ClipboardHistory.Item("Copied item number $it with a fairly long line of text", 0L) }, true)
        root.addPanel(clip)
        root.keyboard.visibility = View.INVISIBLE
        assertEquals("clipboard panel changed keyboard height", base, measure(root))
        assertEquals(root.keyboard.measuredHeight, clip.measuredHeight)

        clip.visibility = View.GONE
        val emoji = EmojiPanel(ctx, object : EmojiPanel.Listener {
            override fun onEmoji(emoji: String) {}
            override fun onPanelDelete() {}
            override fun onPanelBack() {}
        })
        emoji.open()
        root.addPanel(emoji)
        assertEquals("emoji panel changed keyboard height", base, measure(root))
        assertEquals(root.keyboard.measuredHeight, emoji.measuredHeight)

        emoji.visibility = View.GONE
        val edit = EditPanel(ctx, object : EditPanel.Listener {
            override fun onEditAction(action: EditPanel.Action, extend: Boolean) {}
            override fun onKeyFeedbackPanel() {}
            override fun onPanelDelete() {}
            override fun onPanelBack() {}
        })
        root.addPanel(edit)
        assertEquals("edit panel changed keyboard height", base, measure(root))
        assertEquals(root.keyboard.measuredHeight, edit.measuredHeight)
    }
}
