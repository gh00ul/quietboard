package dev.larryjones.quietboard

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.annotation.TargetApi
import java.time.LocalDate

/** Colors used by every keyboard surface. */
data class KbTheme(
    val dark: Boolean,
    val background: Int,
    val key: Int,
    val keyPressed: Int,
    val special: Int,
    val specialPressed: Int,
    val accent: Int,
    val accentPressed: Int,
    val onAccent: Int,
    val text: Int,
    val hint: Int,
    val popup: Int,
    val popupText: Int,
    val selection: Int,
    val onSelection: Int,
    val divider: Int,
    val keyShadow: Int,
    val fonts: KbFonts = KbFonts.SYSTEM,
    /** Bottom of the background gradient ([background] at the top); the same colour for a flat one. */
    val backgroundEnd: Int = background,
    /** Outline around letter keys and function keys (0 = none). */
    val keyStroke: Int = 0,
    val specialStroke: Int = keyStroke,
    /** Neon glow around outlines and letters (0 = none). */
    val glow: Int = 0,
    /** The scene a fun theme draws behind and on the keys. */
    val decor: Decor = Decor.NONE,
    val trail: Trail = Trail.PLAIN,
    /** Colour at the tail of the glide trail; the head is [accent]. */
    val trailTail: Int = accent,
    /** Letters in a monospace face whatever the font setting (the retro terminal). */
    val mono: Boolean = false,
    /**
     * Opacity of letter and function keys. Below 1 the keys are glass: the theme's scene shows through
     * them faintly. Readability is tested against the brightest (or darkest) scene behind them.
     */
    val keyAlpha: Float = 1f,
) {
    /** Soft accent tint on the background: highlights the suggestion space will pick. */
    val chip: Int get() = blend(background, accent, if (dark) 0.22f else 0.14f)

    /** The keyboard window paints this theme's gradient and scene; the strip and keys stay see-through. */
    val hasBackdrop: Boolean get() = decor != Decor.NONE || backgroundEnd != background

    /** A theme the user can pick in Settings; [everyday] ones are the plain Gboard-style looks. */
    class Choice(val id: String, val label: String, val blurb: String, val everyday: Boolean = false)

    companion object {
        fun blend(a: Int, b: Int, t: Float): Int {
            if (t <= 0f) return a
            if (t >= 1f) return b
            fun ch(x: Int, y: Int) = (x + (y - x) * t).toInt().coerceIn(0, 255)
            return Color.argb(255, ch(Color.red(a), Color.red(b)), ch(Color.green(a), Color.green(b)), ch(Color.blue(a), Color.blue(b)))
        }

        val CHOICES = listOf(
            Choice("system", "Follow system", "Light or dark with your phone", everyday = true),
            Choice("light", "Light", "Gboard's light grey", everyday = true),
            Choice("dark", "Dark", "Gboard's dark grey", everyday = true),
            Choice("black", "Black (OLED)", "True black, easy on OLED batteries", everyday = true),
            Choice("seasonal", "Seasonal", "New Year, Valentine's, spring blossom, summer sea, Halloween, autumn and Christmas, each in its season; your usual look in between"),
            Choice("halloween", "Halloween", "A haunted hill under the full moon, bats, cobwebs and an ember trail"),
            Choice("christmas", "Christmas", "Falling snow, snow-capped keys, twinkling lights and a candy-cane trail"),
            Choice("neon", "Neon", "A synthwave sunset over a moving grid, glowing outlines"),
            Choice("sakura", "Sakura", "Cherry blossoms, Mount Fuji and drifting petals"),
            Choice("ocean", "Ocean", "Sunbeams, swaying seaweed, fish and bubbles"),
            Choice("retro", "Retro terminal", "Green phosphor, scanlines and a monospace font"),
            Choice("galaxy", "Galaxy", "Nebulae, a ringed planet and shooting stars"),
            Choice("autumn", "Autumn", "Falling leaves over warm hills"),
            Choice("valentine", "Valentine", "Floating hearts in deep rose"),
            Choice("newyear", "New Year", "Fireworks over the city at midnight"),
        )

        /** What "seasonal" shows on [date]; every other choice is itself. */
        fun effectiveId(choice: String, date: LocalDate): String {
            if (choice != "seasonal") return choice
            val m = date.monthValue
            val d = date.dayOfMonth
            return when {
                (m == 1 && d <= 7) || (m == 12 && d == 31) -> "newyear"
                m == 2 && d <= 14 -> "valentine"
                (m == 3 && d >= 20) || m == 4 -> "sakura"
                (m == 6 && d >= 21) || m == 7 || m == 8 -> "ocean"
                m == 10 -> "halloween"
                m == 11 -> "autumn"
                m == 12 -> "christmas"
                else -> "system"
            }
        }

        fun resolve(ctx: Context, prefs: Prefs, date: LocalDate = LocalDate.now()): KbTheme =
            forChoice(ctx, prefs, prefs.theme, date)

        /** How the theme [choice] (an id from [CHOICES]) looks on [date], with the user's font. */
        fun forChoice(ctx: Context, prefs: Prefs, choice: String, date: LocalDate = LocalDate.now()): KbTheme {
            val id = effectiveId(choice, date)
            val base = FUN[id] ?: colors(ctx, prefs, id)
            val fonts = when {
                base.mono -> KbFonts.MONO
                prefs.font == "system" -> KbFonts.SYSTEM
                else -> KbFonts.bundled(ctx)
            }
            return base.copy(fonts = fonts)
        }

        private fun colors(ctx: Context, prefs: Prefs, id: String): KbTheme {
            val dark = when (id) {
                "light" -> false
                "dark", "black" -> true
                else -> (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
            }
            val base = if (prefs.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                dynamic(ctx, dark)
            } else if (dark) DARK else LIGHT
            return if (id == "black") toBlack(base) else base
        }

        /** True black for OLED screens: keeps the accent colours, drops the greys a few steps. */
        private fun toBlack(t: KbTheme) = t.copy(
            background = 0xFF000000.toInt(),
            backgroundEnd = 0xFF000000.toInt(),
            key = 0xFF1C1C1E.toInt(),
            keyPressed = 0xFF3A3A3C.toInt(),
            special = blend(0xFF111113.toInt(), t.accent, 0.10f),
            specialPressed = blend(0xFF2C2C2E.toInt(), t.accent, 0.10f),
            popup = 0xFF2C2C2E.toInt(),
            keyShadow = 0x00000000,
        )

        private val LIGHT = KbTheme(
            dark = false,
            background = 0xFFE8EAED.toInt(),
            key = 0xFFFFFFFF.toInt(),
            keyPressed = 0xFFD2D5D9.toInt(),
            special = 0xFFCDD1D6.toInt(),
            specialPressed = 0xFFB4B9BF.toInt(),
            // Google Blue 700 rather than 600: the shift arrow on the grey keys needs 3:1.
            accent = 0xFF1967D2.toInt(),
            accentPressed = 0xFF1559B8.toInt(),
            onAccent = 0xFFFFFFFF.toInt(),
            text = 0xFF202124.toInt(),
            hint = 0xFF5F6368.toInt(),
            popup = 0xFFFFFFFF.toInt(),
            popupText = 0xFF202124.toInt(),
            selection = 0xFF1A73E8.toInt(),
            onSelection = 0xFFFFFFFF.toInt(),
            divider = 0x33000000,
            keyShadow = 0x29000000,
        )

        private val DARK = KbTheme(
            dark = true,
            background = 0xFF202124.toInt(),
            key = 0xFF3C4043.toInt(),
            keyPressed = 0xFF5F6368.toInt(),
            special = 0xFF2D2F33.toInt(),
            specialPressed = 0xFF4A4D51.toInt(),
            accent = 0xFF8AB4F8.toInt(),
            accentPressed = 0xFFAECBFA.toInt(),
            onAccent = 0xFF202124.toInt(),
            text = 0xFFE8EAED.toInt(),
            hint = 0xFF9AA0A6.toInt(),
            popup = 0xFF4E5257.toInt(),
            popupText = 0xFFE8EAED.toInt(),
            selection = 0xFF8AB4F8.toInt(),
            onSelection = 0xFF202124.toInt(),
            divider = 0x33FFFFFF,
            keyShadow = 0x40000000,
        )

        /** Midnight purple, pumpkin letters, a glowing ember trail. */
        private val HALLOWEEN = KbTheme(
            dark = true,
            background = 0xFF1B0F2E.toInt(),
            backgroundEnd = 0xFF0A0614.toInt(),
            key = 0xFF2B1846.toInt(),
            keyPressed = 0xFF4A2C6E.toInt(),
            special = 0xFF1F1035.toInt(),
            specialPressed = 0xFF3A2160.toInt(),
            accent = 0xFFFF7A1A.toInt(),
            accentPressed = 0xFFFF9B50.toInt(),
            onAccent = 0xFF1B0F2E.toInt(),
            text = 0xFFFFA24D.toInt(),
            hint = 0xFFB89AE0.toInt(),
            popup = 0xFF3A2160.toInt(),
            popupText = 0xFFFFE2C2.toInt(),
            selection = 0xFFFF7A1A.toInt(),
            onSelection = 0xFF1B0F2E.toInt(),
            divider = 0x40FFA24D,
            keyShadow = 0x66000000,
            decor = Decor.HALLOWEEN,
            trail = Trail.GLOW,
            trailTail = 0xFF9D4DFF.toInt(),
            keyAlpha = 0.86f,
        )

        /** Pine-green letters, cranberry function keys, a gold enter key and snow on everything. */
        private val CHRISTMAS = KbTheme(
            dark = true,
            background = 0xFF0E3B2B.toInt(),
            backgroundEnd = 0xFF072419.toInt(),
            key = 0xFF1A5C42.toInt(),
            keyPressed = 0xFF2A7A59.toInt(),
            special = 0xFFA3202B.toInt(),
            specialPressed = 0xFFC2343F.toInt(),
            accent = 0xFFF4C44E.toInt(),
            accentPressed = 0xFFFFD873.toInt(),
            onAccent = 0xFF3B2300.toInt(),
            text = 0xFFFFFBF2.toInt(),
            hint = 0xFFCFE8DA.toInt(),
            popup = 0xFFFDF8EE.toInt(),
            popupText = 0xFF0E3B2B.toInt(),
            selection = 0xFFA3202B.toInt(),
            onSelection = 0xFFFFFFFF.toInt(),
            divider = 0x40FFFFFF,
            keyShadow = 0x55000000,
            decor = Decor.CHRISTMAS,
            trail = Trail.CANDY,
            trailTail = 0xFFE53940.toInt(),
            keyAlpha = 0.84f,
        )

        /** Synthwave: dark violet, glowing magenta outlines, cyan accents. */
        private val NEON = KbTheme(
            dark = true,
            background = 0xFF12002B.toInt(),
            backgroundEnd = 0xFF05000F.toInt(),
            key = 0xFF1A0838.toInt(),
            keyPressed = 0xFF35106A.toInt(),
            special = 0xFF140530.toInt(),
            specialPressed = 0xFF2C0C58.toInt(),
            accent = 0xFF00E5FF.toInt(),
            accentPressed = 0xFF7CF4FF.toInt(),
            onAccent = 0xFF05000F.toInt(),
            text = 0xFFFDF0FF.toInt(),
            hint = 0xFFE08BFF.toInt(),
            popup = 0xFF2A0B55.toInt(),
            popupText = 0xFFFDF0FF.toInt(),
            selection = 0xFFFF2BD6.toInt(),
            onSelection = 0xFF12002B.toInt(),
            divider = 0x66FF2BD6,
            keyShadow = 0,
            keyStroke = 0xFFFF2BD6.toInt(),
            specialStroke = 0xFF00E5FF.toInt(),
            glow = 0xFFFF2BD6.toInt(),
            decor = Decor.NEON,
            trail = Trail.GLOW,
            trailTail = 0xFFFF2BD6.toInt(),
            keyAlpha = 0.80f,
        )

        /** Cherry blossom: a light theme in soft pinks. */
        private val SAKURA = KbTheme(
            dark = false,
            background = 0xFFFFEEF4.toInt(),
            backgroundEnd = 0xFFFBDDE8.toInt(),
            key = 0xFFFFFFFF.toInt(),
            keyPressed = 0xFFFFD3E2.toInt(),
            special = 0xFFF8C8D9.toInt(),
            specialPressed = 0xFFEFA9C2.toInt(),
            // Deep enough that the shift arrow reads on the pink keys whatever shows through them.
            accent = 0xFFB01E55.toInt(),
            accentPressed = 0xFF8E1544.toInt(),
            onAccent = 0xFFFFFFFF.toInt(),
            text = 0xFF4A1F33.toInt(),
            hint = 0xFF9A5A75.toInt(),
            popup = 0xFFFFFFFF.toInt(),
            popupText = 0xFF4A1F33.toInt(),
            selection = 0xFFB01E55.toInt(),
            onSelection = 0xFFFFFFFF.toInt(),
            divider = 0x40D6336C,
            keyShadow = 0x33A0305A,
            decor = Decor.SAKURA,
            trail = Trail.GLOW,
            trailTail = 0xFFFFA8C8.toInt(),
            keyAlpha = 0.84f,
        )

        /** Deep sea blues with a sea-foam accent. */
        private val OCEAN = KbTheme(
            dark = true,
            background = 0xFF0A4A72.toInt(),
            backgroundEnd = 0xFF021B33.toInt(),
            key = 0xFF0C4E78.toInt(),
            keyPressed = 0xFF1A6A9C.toInt(),
            special = 0xFF073E62.toInt(),
            specialPressed = 0xFF125A86.toInt(),
            accent = 0xFF2EE6C5.toInt(),
            accentPressed = 0xFF7CF2DC.toInt(),
            onAccent = 0xFF02263F.toInt(),
            text = 0xFFEAF8FF.toInt(),
            hint = 0xFF9CCFE8.toInt(),
            popup = 0xFF0F5C8C.toInt(),
            popupText = 0xFFEAF8FF.toInt(),
            selection = 0xFF2EE6C5.toInt(),
            onSelection = 0xFF02263F.toInt(),
            divider = 0x40EAF8FF,
            keyShadow = 0x55000A1A,
            decor = Decor.OCEAN,
            trail = Trail.GLOW,
            trailTail = 0xFF3D8BFF.toInt(),
            keyAlpha = 0.80f,
        )

        /** Green-screen terminal: phosphor text, outlined keys, scanlines, monospace. */
        private val RETRO = KbTheme(
            dark = true,
            background = 0xFF040A04.toInt(),
            backgroundEnd = 0xFF000000.toInt(),
            key = 0xFF0A160C.toInt(),
            keyPressed = 0xFF143A1C.toInt(),
            special = 0xFF07100A.toInt(),
            specialPressed = 0xFF10301A.toInt(),
            accent = 0xFF39FF6A.toInt(),
            accentPressed = 0xFF8CFFA8.toInt(),
            onAccent = 0xFF021005.toInt(),
            text = 0xFF39FF6A.toInt(),
            hint = 0xFF1FA845.toInt(),
            popup = 0xFF0E2414.toInt(),
            popupText = 0xFF5CFF85.toInt(),
            selection = 0xFF39FF6A.toInt(),
            onSelection = 0xFF021005.toInt(),
            divider = 0x6639FF6A,
            keyShadow = 0,
            keyStroke = 0x8039FF6A.toInt(),
            glow = 0x9939FF6A.toInt(),
            decor = Decor.RETRO,
            trail = Trail.PIXEL,
            mono = true,
            keyAlpha = 0.84f,
        )

        /** Deep space: violet nebulae, a ringed planet, shooting stars. */
        private val GALAXY = KbTheme(
            dark = true,
            background = 0xFF0B0C2A.toInt(),
            backgroundEnd = 0xFF04040F.toInt(),
            key = 0xFF1C1E52.toInt(),
            keyPressed = 0xFF2E3178.toInt(),
            special = 0xFF14163E.toInt(),
            specialPressed = 0xFF262A66.toInt(),
            accent = 0xFFA99BFF.toInt(),
            accentPressed = 0xFFC6BCFF.toInt(),
            onAccent = 0xFF0B0C2A.toInt(),
            text = 0xFFF0F2FF.toInt(),
            hint = 0xFFA9B0E8.toInt(),
            popup = 0xFF23265E.toInt(),
            popupText = 0xFFF0F2FF.toInt(),
            selection = 0xFFA99BFF.toInt(),
            onSelection = 0xFF0B0C2A.toInt(),
            divider = 0x40F0F2FF,
            keyShadow = 0,
            decor = Decor.GALAXY,
            trail = Trail.GLOW,
            trailTail = 0xFF3DD6FF.toInt(),
            keyAlpha = 0.80f,
        )

        /** A light theme in harvest colours: cream sky, warm hills, falling leaves. */
        private val AUTUMN = KbTheme(
            dark = false,
            background = 0xFFFFF3E2.toInt(),
            backgroundEnd = 0xFFF8DDBA.toInt(),
            key = 0xFFFFFFFF.toInt(),
            keyPressed = 0xFFF6DCC0.toInt(),
            special = 0xFFF5DCBE.toInt(),
            specialPressed = 0xFFE9C39A.toInt(),
            accent = 0xFFA33E0F.toInt(),
            accentPressed = 0xFF7F2F0A.toInt(),
            onAccent = 0xFFFFFFFF.toInt(),
            text = 0xFF3E2412.toInt(),
            hint = 0xFF8A5A3A.toInt(),
            popup = 0xFFFFFFFF.toInt(),
            popupText = 0xFF3E2412.toInt(),
            selection = 0xFFA33E0F.toInt(),
            onSelection = 0xFFFFFFFF.toInt(),
            divider = 0x40A33E0F,
            keyShadow = 0x33804020,
            decor = Decor.AUTUMN,
            trail = Trail.GLOW,
            trailTail = 0xFFF2B33D.toInt(),
            keyAlpha = 0.84f,
        )

        /** Deep rose with floating hearts. */
        private val VALENTINE = KbTheme(
            dark = true,
            background = 0xFF3A0A1F.toInt(),
            backgroundEnd = 0xFF1C030E.toInt(),
            key = 0xFF5A1632.toInt(),
            keyPressed = 0xFF7A2045.toInt(),
            special = 0xFF46102A.toInt(),
            specialPressed = 0xFF66183C.toInt(),
            accent = 0xFFFF5C8A.toInt(),
            accentPressed = 0xFFFF85A7.toInt(),
            onAccent = 0xFF2A0614.toInt(),
            text = 0xFFFFE6EE.toInt(),
            hint = 0xFFF5A3BE.toInt(),
            popup = 0xFF6A1A3C.toInt(),
            popupText = 0xFFFFE6EE.toInt(),
            selection = 0xFFFF5C8A.toInt(),
            onSelection = 0xFF2A0614.toInt(),
            divider = 0x40FFE6EE,
            keyShadow = 0x55000000,
            decor = Decor.VALENTINE,
            trail = Trail.GLOW,
            trailTail = 0xFFFFC2D4.toInt(),
            keyAlpha = 0.82f,
        )

        /** Midnight blue and champagne gold: fireworks over the skyline. */
        private val NEW_YEAR = KbTheme(
            dark = true,
            background = 0xFF0B1233.toInt(),
            backgroundEnd = 0xFF03050F.toInt(),
            key = 0xFF1A2250.toInt(),
            keyPressed = 0xFF2A3470.toInt(),
            special = 0xFF121940.toInt(),
            specialPressed = 0xFF222C60.toInt(),
            accent = 0xFFF5C542.toInt(),
            accentPressed = 0xFFFFD86B.toInt(),
            onAccent = 0xFF1A1300.toInt(),
            text = 0xFFFFF6E0.toInt(),
            hint = 0xFFD4C89A.toInt(),
            popup = 0xFF232C5E.toInt(),
            popupText = 0xFFFFF6E0.toInt(),
            selection = 0xFFF5C542.toInt(),
            onSelection = 0xFF1A1300.toInt(),
            divider = 0x40FFF6E0,
            keyShadow = 0x55000000,
            decor = Decor.NEW_YEAR,
            trail = Trail.GLOW,
            trailTail = 0xFFFFFFFF.toInt(),
            keyAlpha = 0.82f,
        )

        private val FUN = mapOf(
            "halloween" to HALLOWEEN, "christmas" to CHRISTMAS, "neon" to NEON,
            "sakura" to SAKURA, "ocean" to OCEAN, "retro" to RETRO,
            "galaxy" to GALAXY, "autumn" to AUTUMN, "valentine" to VALENTINE, "newyear" to NEW_YEAR,
        )

        /** Material You palette pulled from the wallpaper, like Gboard's dynamic theme. */
        @TargetApi(Build.VERSION_CODES.S)
        private fun dynamic(ctx: Context, dark: Boolean): KbTheme {
            fun c(id: Int) = ctx.getColor(id)
            return if (dark) KbTheme(
                dark = true,
                background = c(android.R.color.system_neutral1_900),
                key = c(android.R.color.system_neutral2_800),
                keyPressed = c(android.R.color.system_neutral2_600),
                special = c(android.R.color.system_accent2_800),
                specialPressed = c(android.R.color.system_accent2_600),
                accent = c(android.R.color.system_accent1_200),
                accentPressed = c(android.R.color.system_accent1_100),
                onAccent = c(android.R.color.system_accent1_800),
                text = c(android.R.color.system_neutral1_50),
                hint = c(android.R.color.system_neutral2_300),
                popup = c(android.R.color.system_neutral2_700),
                popupText = c(android.R.color.system_neutral1_50),
                selection = c(android.R.color.system_accent1_200),
                onSelection = c(android.R.color.system_accent1_800),
                divider = 0x33FFFFFF,
                keyShadow = 0x40000000,
            ) else KbTheme(
                dark = false,
                background = c(android.R.color.system_neutral2_50),
                key = c(android.R.color.system_neutral1_10),
                keyPressed = c(android.R.color.system_neutral2_200),
                special = c(android.R.color.system_accent2_100),
                specialPressed = c(android.R.color.system_accent2_300),
                accent = c(android.R.color.system_accent1_600),
                accentPressed = c(android.R.color.system_accent1_700),
                onAccent = c(android.R.color.system_accent1_10),
                text = c(android.R.color.system_neutral1_900),
                hint = c(android.R.color.system_neutral2_600),
                popup = c(android.R.color.system_neutral1_10),
                popupText = c(android.R.color.system_neutral1_900),
                selection = c(android.R.color.system_accent1_600),
                onSelection = c(android.R.color.system_accent1_10),
                divider = 0x22000000,
                keyShadow = 0x22000000,
            )
        }
    }
}

/** Typefaces for key labels, function keys and suggestions. */
class KbFonts(val regular: Typeface, val medium: Typeface, val bold: Typeface) {
    companion object {
        val SYSTEM = KbFonts(
            Typeface.create("sans-serif", Typeface.NORMAL),
            Typeface.create("sans-serif-medium", Typeface.NORMAL),
            Typeface.create("sans-serif", Typeface.BOLD),
        )

        val MONO = KbFonts(
            Typeface.MONOSPACE,
            Typeface.create(Typeface.MONOSPACE, Typeface.BOLD),
            Typeface.create(Typeface.MONOSPACE, Typeface.BOLD),
        )

        @Volatile private var cached: KbFonts? = null

        /** Google Sans (OFL), the same face Gboard uses. Falls back to the system font if missing. */
        fun bundled(ctx: Context): KbFonts {
            cached?.let { return it }
            val f = runCatching {
                KbFonts(
                    Typeface.createFromAsset(ctx.assets, "fonts/GoogleSans-Regular.ttf"),
                    Typeface.createFromAsset(ctx.assets, "fonts/GoogleSans-Medium.ttf"),
                    Typeface.createFromAsset(ctx.assets, "fonts/GoogleSans-Bold.ttf"),
                )
            }.getOrDefault(SYSTEM)
            cached = f
            return f
        }
    }
}
