package dev.larryjones.quietboard

import android.annotation.TargetApi
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/** What just happened under the finger, so each kind of touch can feel a little different. */
enum class Haptic {
    /** A letter, digit or symbol. */
    KEY,
    /** Space, Enter, Delete, Shift, layout keys: a touch firmer, like a bigger key. */
    FUNCTION,
    /** Delete auto-repeat: lighter, so holding it doesn't buzz. */
    REPEAT,
    /** A long press opened accents or switched something. */
    LONG_PRESS,
    /** One step of a space-bar cursor move or swipe-to-delete: a faint detent. */
    TICK,
}

/**
 * Key haptics and sounds.
 *
 * Strengths "Light/Medium/Strong" use the vibration motor directly: on phones that support them
 * (most Android 11+ flagships), short composed click/tick primitives scaled to the strength, which
 * feel crisp instead of buzzy; elsewhere the system's predefined tick/click effects. "Phone default"
 * hands each event to the system's own keyboard haptics, which follow the phone's touch-feedback
 * settings. Effects are built once per strength, not on every key press.
 */
class Feedback(ctx: Context, private val prefs: Prefs) {
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Vibrator::class.java)
        }
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private val canVibrate = vibrator?.hasVibrator() == true
    private val primitives = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && canVibrate &&
        vibrator!!.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK, VibrationEffect.Composition.PRIMITIVE_TICK)
    private val lowTick = primitives && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        vibrator!!.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_LOW_TICK)

    /** The view that receives "Phone default" haptics (the keyboard). */
    private var view: View? = null

    private var builtFor = -1
    private val effects = arrayOfNulls<VibrationEffect>(Haptic.entries.size)

    fun attach(v: View?) {
        view = v
    }

    /** A key went down: haptic plus the matching click sound. */
    fun key(type: KeyType) {
        haptic(if (type == KeyType.CHAR) Haptic.KEY else Haptic.FUNCTION)
        sound(type)
    }

    /** Subtle detent for cursor moves and swipe-delete steps. */
    fun tick() = haptic(Haptic.TICK)

    fun haptic(event: Haptic) {
        val level = prefs.vibration
        if (level == OFF) return
        if (level == SYSTEM) {
            view?.performHapticFeedback(systemConstant(event))
            return
        }
        if (!canVibrate) return
        val effect = effectFor(event, level) ?: return
        val v = vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // "Touch" usage: the system applies the user's touch-feedback intensity and mutes it when
            // touch feedback is off or in battery saver.
            v.vibrate(effect, Api33.TOUCH)
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(effect, SONIFICATION)
        }
    }

    fun sound(type: KeyType) {
        if (!prefs.sound) return
        val fx = when (type) {
            KeyType.DELETE -> AudioManager.FX_KEYPRESS_DELETE
            KeyType.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
            KeyType.ENTER -> AudioManager.FX_KEYPRESS_RETURN
            else -> AudioManager.FX_KEYPRESS_STANDARD
        }
        audio?.playSoundEffect(fx, -1f)
    }

    private fun effectFor(event: Haptic, level: Int): VibrationEffect? {
        if (builtFor != level) {
            for (h in Haptic.entries) effects[h.ordinal] = build(h, level)
            builtFor = level
        }
        return effects[event.ordinal]
    }

    private fun build(event: Haptic, level: Int): VibrationEffect? = runCatching {
        if (primitives && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val (primitive, scale) = primitiveFor(event, level, lowTick)
            VibrationEffect.startComposition().addPrimitive(primitive, scale).compose()
        } else {
            VibrationEffect.createPredefined(predefinedFor(event, level))
        }
    }.getOrNull()

    companion object {
        const val OFF = 0
        const val LIGHT = 1
        const val MEDIUM = 2
        const val STRONG = 3
        /** Let the phone decide (its own keyboard haptics and settings). */
        const val SYSTEM = 4

        private val SONIFICATION: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        /** Base intensity of each strength setting (0..1). */
        fun strength(level: Int) = when (level) {
            LIGHT -> 0.45f
            MEDIUM -> 0.7f
            STRONG -> 1f
            else -> 0f
        }

        /** Composed primitive and its scale for an event, at a strength (used on Android 11+ only). */
        @TargetApi(Build.VERSION_CODES.R)
        fun primitiveFor(event: Haptic, level: Int, lowTick: Boolean = false): Pair<Int, Float> {
            val s = strength(level)
            val click = VibrationEffect.Composition.PRIMITIVE_CLICK
            val tick = VibrationEffect.Composition.PRIMITIVE_TICK
            val faint = if (lowTick && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) VibrationEffect.Composition.PRIMITIVE_LOW_TICK else tick
            return when (event) {
                Haptic.KEY -> click to s
                Haptic.FUNCTION -> click to minOf(1f, s * 1.25f)
                Haptic.LONG_PRESS -> click to minOf(1f, s * 1.5f)
                Haptic.REPEAT -> tick to s * 0.75f
                Haptic.TICK -> faint to s * 0.6f
            }
        }

        /** Predefined effect for phones without primitives: the old 3-step mapping, per event. */
        fun predefinedFor(event: Haptic, level: Int): Int {
            val steps = intArrayOf(VibrationEffect.EFFECT_TICK, VibrationEffect.EFFECT_CLICK, VibrationEffect.EFFECT_HEAVY_CLICK)
            val base = (level - 1).coerceIn(0, 2)
            return when (event) {
                Haptic.KEY -> steps[base]
                Haptic.FUNCTION, Haptic.LONG_PRESS -> steps[minOf(2, base + 1)]
                Haptic.REPEAT, Haptic.TICK -> VibrationEffect.EFFECT_TICK
            }
        }

        /** The system keyboard haptic for an event ("Phone default"). */
        fun systemConstant(event: Haptic): Int = when (event) {
            Haptic.KEY, Haptic.FUNCTION, Haptic.REPEAT -> HapticFeedbackConstants.KEYBOARD_PRESS
            Haptic.LONG_PRESS -> HapticFeedbackConstants.LONG_PRESS
            Haptic.TICK -> HapticFeedbackConstants.TEXT_HANDLE_MOVE
        }
    }
}

/** Android 13+ only; touched only behind a version check. */
@TargetApi(Build.VERSION_CODES.TIRAMISU)
private object Api33 {
    val TOUCH: VibrationAttributes = VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH)
}
