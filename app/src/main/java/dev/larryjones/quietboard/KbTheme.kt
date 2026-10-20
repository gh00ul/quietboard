package dev.larryjones.quietboard

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.annotation.TargetApi

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
) {
    /** Soft accent tint on the background: highlights the suggestion space will pick. */
    val chip: Int get() = blend(background, accent, if (dark) 0.22f else 0.14f)

    companion object {
        fun blend(a: Int, b: Int, t: Float): Int {
            if (t <= 0f) return a
            if (t >= 1f) return b
            fun ch(x: Int, y: Int) = (x + (y - x) * t).toInt().coerceIn(0, 255)
            return Color.argb(255, ch(Color.red(a), Color.red(b)), ch(Color.green(a), Color.green(b)), ch(Color.blue(a), Color.blue(b)))
        }

        fun resolve(ctx: Context, prefs: Prefs): KbTheme {
            val fonts = if (prefs.font == "system") KbFonts.SYSTEM else KbFonts.bundled(ctx)
            return colors(ctx, prefs).copy(fonts = fonts)
        }

        private fun colors(ctx: Context, prefs: Prefs): KbTheme {
            val dark = when (prefs.theme) {
                "light" -> false
                "dark", "black" -> true
                else -> (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
            }
            val base = if (prefs.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                dynamic(ctx, dark)
            } else if (dark) DARK else LIGHT
            return if (prefs.theme == "black") toBlack(base) else base
        }

        /** True black for OLED screens: keeps the accent colours, drops the greys a few steps. */
        private fun toBlack(t: KbTheme) = t.copy(
            background = 0xFF000000.toInt(),
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
            accent = 0xFF1A73E8.toInt(),
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
