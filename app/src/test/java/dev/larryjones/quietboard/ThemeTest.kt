package dev.larryjones.quietboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import kotlin.math.pow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w384dp-h832dp-450dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemeTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    private fun prefs(dynamic: Boolean = false) = Prefs(ctx).also {
        it.sp.edit().putBoolean(Prefs.DYNAMIC_COLOR, dynamic).commit()
    }

    private val funIds = listOf("halloween", "christmas", "neon", "sakura", "ocean", "retro", "galaxy", "autumn", "valentine", "newyear")
    private fun day(m: Int, d: Int) = LocalDate.of(2026, m, d)

    @Test fun seasonalFollowsTheCalendar() {
        val expected = mapOf(
            day(1, 1) to "newyear", day(1, 7) to "newyear", day(1, 8) to "system",
            day(2, 1) to "valentine", day(2, 14) to "valentine", day(2, 15) to "system",
            day(3, 19) to "system", day(3, 20) to "sakura", day(4, 30) to "sakura", day(5, 1) to "system",
            day(6, 20) to "system", day(6, 21) to "ocean", day(8, 31) to "ocean", day(9, 1) to "system",
            day(10, 1) to "halloween", day(10, 31) to "halloween", day(11, 1) to "autumn", day(11, 30) to "autumn",
            day(12, 1) to "christmas", day(12, 30) to "christmas", day(12, 31) to "newyear",
        )
        for ((date, id) in expected) assertEquals("$date", id, KbTheme.effectiveId("seasonal", date))
        assertEquals("other choices are themselves", "neon", KbTheme.effectiveId("neon", day(10, 20)))

        val p = prefs()
        p.sp.edit().putString(Prefs.THEME, "seasonal").commit()
        assertEquals(Decor.HALLOWEEN, KbTheme.resolve(ctx, p, day(10, 20)).decor)
        assertEquals(Decor.CHRISTMAS, KbTheme.resolve(ctx, p, day(12, 20)).decor)
        assertEquals(Decor.NONE, KbTheme.resolve(ctx, p, day(5, 20)).decor)
        // Every season's theme is a real fun theme.
        for (id in expected.values.toSet() - "system") assertTrue(id, id in funIds)
    }

    @Test fun standardThemesAreUnchanged() {
        // The fun-theme machinery must leave the standard themes as they were: flat, no scenery.
        for (dynamic in listOf(false, true)) for (id in listOf("system", "light", "dark", "black")) {
            val t = KbTheme.forChoice(ctx, prefs(dynamic), id, day(10, 20))
            assertFalse("$id has no backdrop", t.hasBackdrop)
            assertEquals(Decor.NONE, t.decor)
            assertEquals(Trail.PLAIN, t.trail)
            assertEquals(0, t.keyStroke)
            assertEquals(0, t.glow)
            assertEquals("solid keys", 1f, t.keyAlpha)
        }
        assertEquals(Color.BLACK, KbTheme.forChoice(ctx, prefs(), "black").backgroundEnd)
    }

    @Test fun everyChoiceResolves() {
        for (c in KbTheme.CHOICES) KbTheme.forChoice(ctx, prefs(), c.id, day(5, 20))
        assertEquals("every fun theme is in the picker", funIds.toSet() + "seasonal", KbTheme.CHOICES.filter { !it.everyday }.map { it.id }.toSet())
        for (id in funIds) {
            val t = KbTheme.forChoice(ctx, prefs(), id)
            assertTrue("$id draws a scene", t.decor != Decor.NONE && t.hasBackdrop)
            assertEquals("Material You never repaints a fun theme", t, KbTheme.forChoice(ctx, prefs(true), id))
        }
        val p = prefs()
        p.sp.edit().putString(Prefs.THEME, "something-from-an-old-version").commit()
        assertEquals("unknown ids fall back to the standard theme", Decor.NONE, KbTheme.resolve(ctx, p).decor)
        assertSame(KbFonts.MONO, KbTheme.forChoice(ctx, prefs(), "retro").fonts)
    }

    private fun lum(c: Int): Double {
        fun ch(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch(Color.red(c)) + 0.7152 * ch(Color.green(c)) + 0.0722 * ch(Color.blue(c))
    }

    private fun contrast(a: Int, b: Int): Double {
        val la = lum(a)
        val lb = lum(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    @Test fun everyThemeIsReadable() {
        // WCAG AA: 4.5:1 for text, 3:1 for icons and the small long-press hints.
        val bad = ArrayList<String>()
        for (c in KbTheme.CHOICES) {
            val t = KbTheme.forChoice(ctx, prefs(), c.id, day(5, 20))
            fun need(what: String, fg: Int, bg: Int, min: Double) {
                val r = contrast(fg, bg)
                if (r < min) bad += "${c.id} $what %.2f < $min".format(r)
            }
            need("letters on keys", t.text, t.key, 4.5)
            need("labels on function keys", t.text, t.special, 4.5)
            need("suggestions on the strip", t.text, t.background, 4.5)
            need("the picked suggestion's pill", t.text, t.chip, 4.5)
            need("popup letters", t.popupText, t.popup, 4.5)
            need("the selected popup letter", t.onSelection, t.selection, 4.5)
            need("the enter icon", t.onAccent, t.accent, 3.0)
            need("hints on keys", t.hint, t.key, 3.0)
            need("the typed word on the strip", t.hint, t.background, 3.0)
            need("shift on a function key", t.accent, t.special, 3.0)
            if (t.backgroundEnd != t.background) need("suggestions low on the gradient", t.text, t.backgroundEnd, 4.5)
            if (t.keyAlpha < 1f) {
                // Glass keys: whatever the scene shows through them, at worst pure white behind a dark
                // theme's keys (black behind a light theme's), the letters stay readable.
                val worst = if (t.dark) Color.WHITE else Color.BLACK
                val key = KbTheme.blend(t.key, worst, 1 - t.keyAlpha)
                val special = KbTheme.blend(t.special, worst, 1 - t.keyAlpha)
                need("letters on glass keys", t.text, key, 4.5)
                need("labels on glass function keys", t.text, special, 4.5)
                need("hints on glass keys", t.hint, key, 3.0)
                need("shift on a glass function key", t.accent, special, 3.0)
            }
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun everySceneDraws() {
        val dp = ctx.resources.displayMetrics.density
        val w = (384 * dp).toInt()
        val h = (300 * dp).toInt()
        for (id in funIds) {
            val t = KbTheme.forChoice(ctx, prefs(), id)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            ThemeBackdrop(t, 46 * dp, dp, effects = true).apply { setBounds(0, 0, w, h) }.draw(c)
            // The same gradient without the scene, to tell the scenery apart from the background.
            val plain = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            ThemeBackdrop(t.copy(decor = Decor.NONE), 46 * dp, dp, effects = false).apply { setBounds(0, 0, w, h) }.draw(Canvas(plain))
            var differs = 0
            for (x in 0 until w step 3) for (y in 0 until (46 * dp).toInt() step 3) if (bmp.getPixel(x, y) != plain.getPixel(x, y)) differs++
            if (t.decor != Decor.RETRO) assertTrue("$id drew its scenery on the strip: $differs", differs > 50)
            val key = RectF(10 * dp, 60 * dp, 46 * dp, 110 * dp)
            for (look in KeyLook.entries) for (hint in listOf(false, true)) Scenery.keyExtra(c, t, key, 9 * dp, look, hint, 3, dp)
            Scenery.spaceArt(c, t, RectF(60 * dp, 200 * dp, 300 * dp, 250 * dp), 30 * dp, dp)
            for (age in listOf(0f, 0.5f, 0.99f)) Scenery.particle(c, t.decor, 100 * dp, 150 * dp, 4 * dp, 30f, 200, age, 1, dp)
            Scenery.trailBit(c, t.decor, 150 * dp, 150 * dp, 4 * dp, 10f, 200, dp)
            Scenery.scanlines(c, w.toFloat(), h.toFloat(), dp)
            // The moving layer at a few moments, including well into a long session.
            for (time in listOf(0f, 0.35f, 1.7f, 13.3f, 3600f)) Ambient.draw(c, t, w.toFloat(), h.toFloat(), 46 * dp, dp, time)
        }
    }

    @Test fun everySceneMoves() {
        val dp = ctx.resources.displayMetrics.density
        val w = (384 * dp).toInt()
        val h = (300 * dp).toInt()
        for (id in funIds) {
            val t = KbTheme.forChoice(ctx, prefs(), id)
            fun frame(time: Float) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                Ambient.draw(Canvas(it), t, w.toFloat(), h.toFloat(), 46 * dp, dp, time)
            }
            val a = frame(0f)
            val b = frame(2.3f)
            var differs = 0
            for (x in 0 until w step 4) for (y in 0 until h step 4) if (a.getPixel(x, y) != b.getPixel(x, y)) differs++
            assertTrue("$id moves: $differs", differs > 0)
        }
        // Without theme effects a wake does nothing and drawing still works.
        val still = ThemeBackdrop(KbTheme.forChoice(ctx, prefs(), "christmas"), 46 * dp, dp, effects = false)
        still.wake()
        still.setBounds(0, 0, w, h)
        still.draw(Canvas(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)))
    }
}
