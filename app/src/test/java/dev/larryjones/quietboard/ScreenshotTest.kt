package dev.larryjones.quietboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.View
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Draws the actual keyboard views (same onDraw code that runs on the phone) into PNGs.
 * Run with: QB_SCREENSHOTS=1 ./gradlew testDebugUnitTest --tests '*ScreenshotTest*'
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w384dp-h832dp-450dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTest {

    private fun render(themeName: String, file: String, dynamic: Boolean = false, setup: (TopBar, KeyboardView, PopupOverlay, Int) -> Unit) {
        val ctx = RuntimeEnvironment.getApplication()
        val prefs = Prefs(ctx)
        prefs.sp.edit().putString(Prefs.THEME, themeName).putBoolean(Prefs.DYNAMIC_COLOR, dynamic).commit()
        val theme = KbTheme.resolve(ctx, prefs)
        val overlay = PopupOverlay(ctx)
        val kv = KeyboardView(ctx, overlay)
        val tb = TopBar(ctx)
        kv.theme = theme; tb.theme = theme; overlay.theme = theme
        kv.setLayout(Layouts.build(LayoutId.ALPHA, LayoutOptions(false, false, FieldKind.TEXT)))

        val w = (384 * ctx.resources.displayMetrics.density).toInt()
        val exact = View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY)
        val any = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        tb.measure(exact, any); tb.layout(0, 0, w, tb.measuredHeight)
        kv.measure(exact, any); kv.layout(0, 0, w, kv.measuredHeight)
        val total = tb.measuredHeight + kv.measuredHeight
        overlay.measure(exact, View.MeasureSpec.makeMeasureSpec(total, View.MeasureSpec.EXACTLY))
        overlay.layout(0, 0, w, total)

        setup(tb, kv, overlay, tb.measuredHeight)

        val bmp = Bitmap.createBitmap(w, total, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        tb.draw(c)
        c.save(); c.translate(0f, tb.measuredHeight.toFloat()); c.clipRect(0, 0, w, kv.measuredHeight); kv.draw(c); c.restore()
        overlay.draw(c)
        File("build/screens").mkdirs()
        File("build/screens/$file").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun renderRoot(themeName: String, file: String, dynamic: Boolean, setup: (InputRoot, KbTheme, android.content.Context) -> Unit) {
        val ctx = RuntimeEnvironment.getApplication()
        val prefs = Prefs(ctx)
        prefs.sp.edit().putString(Prefs.THEME, themeName).putBoolean(Prefs.DYNAMIC_COLOR, dynamic).commit()
        val theme = KbTheme.resolve(ctx, prefs)
        val root = InputRoot(ctx)
        root.setBackgroundColor(theme.background)
        root.keyboard.theme = theme; root.topBar.theme = theme; root.overlay.theme = theme
        root.keyboard.setLayout(Layouts.build(LayoutId.ALPHA, LayoutOptions(false, false, FieldKind.TEXT)))
        setup(root, theme, ctx)
        val w = (384 * ctx.resources.displayMetrics.density).toInt()
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(4000, View.MeasureSpec.AT_MOST))
        root.layout(0, 0, w, root.measuredHeight)
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(4000, View.MeasureSpec.AT_MOST))
        root.layout(0, 0, w, root.measuredHeight)
        val bmp = Bitmap.createBitmap(w, root.measuredHeight, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File("build/screens").mkdirs()
        File("build/screens/$file").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun keyRect(kv: KeyboardView, ch: String, dy: Int): RectF {
        val k = kv.layout!!.rows.flatMap { it.keys }.first { it.text == ch }
        return RectF(k.visual.left, k.visual.top + dy, k.visual.right, k.visual.bottom + dy)
    }

    @Test
    fun renderScreens() {
        assumeTrue(System.getenv("QB_SCREENSHOTS") != null)

        val typing: (TopBar, KeyboardView, PopupOverlay, Int) -> Unit = { tb, kv, _, _ ->
            kv.enterIcon = Icon.SEND
            tb.setSuggestions(
                listOf(
                    SuggestionItem("helo", "\u201Chelo\u201D", literal = true),
                    SuggestionItem("hello", bold = true),
                    SuggestionItem("help"),
                )
            )
        }
        val toolbar: (TopBar, KeyboardView, PopupOverlay, Int) -> Unit = { tb, kv, ov, dy ->
            kv.shiftState = ShiftState.ON
            kv.enterIcon = Icon.ENTER
            tb.pasteChip = "larryjones.stream/parlays"
            ov.showPopup(keyRect(kv, "a", dy), listOf("À", "Á", "Â", "Ä", "Æ", "Ã", "Å", "Ā"))
        }
        val glide: (TopBar, KeyboardView, PopupOverlay, Int) -> Unit = { tb, kv, ov, dy ->
            kv.shiftState = ShiftState.LOCKED
            tb.setSuggestions(listOf(SuggestionItem("HELP"), SuggestionItem("HELLO", bold = true), SuggestionItem("HELL")))
            val pts = listOf("h", "e", "l", "o").map { keyRect(kv, it, dy) }
            val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
            for (i in 0 until pts.size - 1) for (st in 0 until 20) {
                val t = st / 20f
                xs += pts[i].centerX() + (pts[i + 1].centerX() - pts[i].centerX()) * t
                ys += pts[i].centerY() + (pts[i + 1].centerY() - pts[i].centerY()) * t +
                    (kotlin.math.sin(t * Math.PI) * 12).toFloat()
            }
            xs += pts.last().centerX(); ys += pts.last().centerY()
            ov.setTrail(xs.toFloatArray(), ys.toFloatArray(), xs.size, 0f, 0f)
        }
        val emojiTyping: (TopBar, KeyboardView, PopupOverlay, Int) -> Unit = { tb, kv, _, _ ->
            tb.setSuggestions(listOf(SuggestionItem("pizzas"), SuggestionItem("pizza"), SuggestionItem("\uD83C\uDF55", emoji = true)))
            kv.enterIcon = Icon.SEND
        }
        render("dark", "md_emoji.png", dynamic = true, setup = emojiTyping)
        render("dark", "md_onehand.png", dynamic = true) { tb, kv, _, _ ->
            tb.activeTool = TopBar.Tool.ONE_HAND
            kv.oneHanded = 1
        }
        renderRoot("dark", "md_edit.png", dynamic = true) { root, t, ctx ->
            val ed = EditPanel(ctx, object : EditPanel.Listener {
                override fun onEditAction(action: EditPanel.Action, extend: Boolean) {}
                override fun onKeyFeedbackPanel() {}
                override fun onPanelDelete() {}
                override fun onPanelBack() {}
            })
            ed.applyTheme(t); ed.setSelecting(true)
            root.addPanel(ed)
            root.keyboard.visibility = View.INVISIBLE
            root.topBar.activeTool = TopBar.Tool.EDIT
        }
        renderRoot("light", "md_clip.png", dynamic = true) { root, t, ctx ->
            val cp = ClipboardPanel(ctx, object : ClipboardPanel.Listener {
                override fun onClipPaste(text: String) {}
                override fun onClipPin(item: ClipboardHistory.Item) {}
                override fun onClipRemove(item: ClipboardHistory.Item) {}
                override fun onClipClear() {}
                override fun onPanelBack() {}
            })
            cp.applyTheme(t)
            cp.setItems(
                listOf(
                    ClipboardHistory.Item("Home Wi-Fi: Quiet-5G", 0L, pinned = true),
                    ClipboardHistory.Item("Order #4471-B shipped \u2014 tracking 1Z999AA1012", 0L),
                    ClipboardHistory.Item("Running about 10 min late, start without me", 0L),
                ),
                true,
            )
            root.addPanel(cp)
            root.keyboard.visibility = View.INVISIBLE
            root.topBar.activeTool = TopBar.Tool.CLIPBOARD
        }
        val preview: (TopBar, KeyboardView, PopupOverlay, Int) -> Unit = { tb, kv, ov, dy ->
            tb.setSuggestions(listOf(SuggestionItem("then"), SuggestionItem("the", bold = true), SuggestionItem("they")))
            ov.showPreview(keyRect(kv, "g", dy), "g")
        }
        render("light", "preview.png", setup = preview)
        render("dark", "md_preview.png", dynamic = true, setup = preview)
        render("black", "black.png", setup = typing)
        renderRoot("dark", "md_emojipanel.png", dynamic = true) { root, t, ctx ->
            val ep = EmojiPanel(ctx, object : EmojiPanel.Listener {
                override fun onEmoji(emoji: String) {}
                override fun onPanelDelete() {}
                override fun onPanelBack() {}
            })
            ep.applyTheme(t); ep.open()
            root.addPanel(ep)
            root.keyboard.visibility = View.INVISIBLE
            root.topBar.activeTool = TopBar.Tool.EMOJI
        }
        render("dark", "dark.png", setup = typing)
        render("dark", "glide.png", setup = glide)
        render("light", "light.png", setup = toolbar)
        render("dark", "md_dark.png", dynamic = true, setup = typing)
        render("dark", "md_glide.png", dynamic = true, setup = glide)
        render("light", "md_light.png", dynamic = true, setup = toolbar)
    }
}
