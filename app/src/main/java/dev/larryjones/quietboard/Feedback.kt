package dev.larryjones.quietboard

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class Feedback(ctx: Context, private val prefs: Prefs) {
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Vibrator::class.java)
        }
    private val audio = ctx.getSystemService(AudioManager::class.java)

    fun key(type: KeyType) {
        vibrate(prefs.vibration)
        if (prefs.sound) {
            val fx = when (type) {
                KeyType.DELETE -> AudioManager.FX_KEYPRESS_DELETE
                KeyType.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
                KeyType.ENTER -> AudioManager.FX_KEYPRESS_RETURN
                else -> AudioManager.FX_KEYPRESS_STANDARD
            }
            audio?.playSoundEffect(fx, -1f)
        }
    }

    /** Subtle tick for cursor moves and swipe-delete steps. */
    fun tick() {
        if (prefs.vibration > 0) vibrate(1)
    }

    private fun vibrate(level: Int) {
        val v = vibrator ?: return
        if (level <= 0 || !v.hasVibrator()) return
        val effect = VibrationEffect.createPredefined(
            when (level) {
                1 -> VibrationEffect.EFFECT_TICK
                2 -> VibrationEffect.EFFECT_CLICK
                else -> VibrationEffect.EFFECT_HEAVY_CLICK
            }
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
        } else {
            v.vibrate(effect)
        }
    }
}
