package dev.larryjones.quietboard

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build

/**
 * Whether a foldable is half open, from Android's own hinge-angle sensor (Android 11+; no Google
 * libraries). It listens only between [start] and [stop]: the keyboard starts it while it's on
 * screen on a big display. [onChange] is told when [halfOpen] flips.
 */
class FoldPosture(private val context: Context, private val onChange: () -> Unit) : SensorEventListener {
    /** Half open, like a laptop or a book: the hinge between about 30° and 150°. */
    var halfOpen = false
        private set
    private var listening = false

    fun start() {
        if (listening || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val sm = context.getSystemService(SensorManager::class.java) ?: return
        val hinge = sm.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE) ?: return
        listening = sm.registerListener(this, hinge, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun stop() {
        if (!listening) return
        listening = false
        context.getSystemService(SensorManager::class.java)?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val angle = event.values.firstOrNull() ?: return
        // Some give either side, so a hinge resting near a limit doesn't flip the keyboard back and forth.
        val now = if (halfOpen) angle in 20f..160f else angle in 30f..150f
        if (now == halfOpen) return
        halfOpen = now
        onChange()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
