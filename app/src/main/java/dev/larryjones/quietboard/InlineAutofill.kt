package dev.larryjones.quietboard

import android.annotation.TargetApi
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.util.Size
import android.util.TypedValue
import android.view.View
import android.view.inputmethod.InlineSuggestionsRequest
import android.widget.inline.InlinePresentationSpec
import kotlin.math.roundToInt

/**
 * Password-manager logins in the suggestion strip (Android 11+). The keyboard only asks for them and
 * places them: the system draws each chip and fills the fields when you tap one, so Quietboard never
 * sees a username or password. Works with autofill services that support inline suggestions
 * (Proton Pass, Bitwarden, 1Password, Google...).
 *
 * The chip style uses androidx.autofill's format (UiVersions + InlineSuggestionUi v1), written out by
 * hand so the app stays free of libraries. The services render with that library, so the keys must
 * match it exactly.
 */
@TargetApi(Build.VERSION_CODES.R)
internal object InlineAutofill {
    private const val VERSIONS_KEY = "androidx.autofill.inline.ui.version:key"
    const val V1 = "androidx.autofill.inline.ui.version:v1"
    const val MAX_SUGGESTIONS = 6

    /** Whether the system can draw the v1 chips this keyboard asks for. */
    fun supports(uiExtras: Bundle): Boolean = uiExtras.getStringArrayList(VERSIONS_KEY)?.contains(V1) == true

    fun request(ctx: Context, t: KbTheme, barHeight: Int, uiExtras: Bundle): InlineSuggestionsRequest? {
        if (!supports(uiExtras)) return null
        val dp = ctx.resources.displayMetrics.density
        fun px(v: Float) = (v * dp).roundToInt()
        val spec = InlinePresentationSpec.Builder(Size(px(40f), px(30f)), Size(px(240f), barHeight - px(8f)))
            .setStyle(style(ctx, t))
            .build()
        return InlineSuggestionsRequest.Builder(listOf(spec))
            .setMaxSuggestionCount(MAX_SUGGESTIONS)
            .setSupportedLocales(LocaleList.getDefault())
            .build()
    }

    /** The theme's chip look: a soft pill in the key colour, the theme's text colours. */
    fun style(ctx: Context, t: KbTheme): Bundle {
        val dp = ctx.resources.displayMetrics.density
        fun px(v: Float) = (v * dp).roundToInt()
        val chip = Bundle().apply {
            putBoolean("view_style", true)
            putParcelable("background", Icon.createWithResource(ctx, R.drawable.autofill_chip).setTint(t.chip))
            putIntArray("padding", intArrayOf(px(12f), 0, px(12f), 0))
            putIntArray("layout_margin", intArrayOf(px(3f), px(4f), px(3f), px(4f)))
        }
        fun text(color: Int, sp: Float) = Bundle().apply {
            putBoolean("text_view_style", true)
            putInt("text_color", color)
            putFloat("text_size", sp)
            putInt("text_size_unit", TypedValue.COMPLEX_UNIT_SP)
        }
        val icon = Bundle().apply {
            putBoolean("image_view_style", true)
            putIntArray("layout_margin", intArrayOf(0, 0, px(6f), 0))
            putInt("image_max_width", px(20f))
            putInt("image_max_height", px(20f))
        }
        val v1 = Bundle().apply {
            putBoolean("style_v1", true)
            putBundle("chip_style", chip)
            putBundle("title_style", text(t.text, 14f))
            putBundle("subtitle_style", text(t.hint, 12f))
            putBundle("start_icon_style", icon)
            putBundle("single_icon_chip_style", chip)
            putInt("layout_direction", ctx.resources.configuration.layoutDirection.takeIf { it == View.LAYOUT_DIRECTION_RTL } ?: View.LAYOUT_DIRECTION_LTR)
        }
        return Bundle().apply {
            putStringArrayList(VERSIONS_KEY, arrayListOf(V1))
            putBundle(V1, v1)
        }
    }
}
