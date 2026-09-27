package com.example.ftnnavigation.poc

import kotlin.math.sqrt

/**
 * Detektor koraka nad sirovim akcelerometrom (zamena za TYPE_STEP_DETECTOR, koji na
 * Redmi Note 14 Pro+ ne javlja korake pri kratkom hodu).
 *
 * Svaki korak daje vrh u jačini ubrzanja. Signal je (izglađena jačina - spora bazna linija),
 * pa nagib telefona i gravitacija ne utiču. Korak se broji kad signal pređe [STEP_THRESHOLD],
 * a sledeći tek kad signal padne ispod [RESET_THRESHOLD] (histerezis) i prođe [MIN_STEP_INTERVAL_NS].
 *
 * Koeficijenti filtera su podešeni za ~50 Hz (SENSOR_DELAY_GAME).
 */
class AccelStepDetector(private val onStep: () -> Unit) {
    private var smoothed = GRAVITY
    private var baseline = GRAVITY
    private var armed = true
    private var lastStepNs = Long.MIN_VALUE / 2

    fun onAccelerometer(x: Float, y: Float, z: Float, timestampNs: Long) {
        val magnitude = sqrt(x * x + y * y + z * z)
        smoothed += SMOOTH_ALPHA * (magnitude - smoothed)
        baseline += BASELINE_ALPHA * (magnitude - baseline)
        val signal = smoothed - baseline

        if (armed && signal > STEP_THRESHOLD && timestampNs - lastStepNs >= MIN_STEP_INTERVAL_NS) {
            armed = false
            lastStepNs = timestampNs
            onStep()
        } else if (!armed && signal < RESET_THRESHOLD) {
            armed = true
        }
    }

    companion object {
        private const val GRAVITY = 9.81f

        // TODO: podesiti na terenu (hod sa telefonom u ruci ispred sebe).
        const val STEP_THRESHOLD = 0.6f // m/s² iznad bazne linije (1.0 je propuštao lagan hod)
        const val RESET_THRESHOLD = 0.0f
        const val MIN_STEP_INTERVAL_NS = 250_000_000L // najviše 4 koraka u sekundi

        private const val SMOOTH_ALPHA = 0.25f // uklanja drhtanje ruke
        private const val BASELINE_ALPHA = 0.02f // τ ≈ 1 s, prati gravitaciju/nagib
    }
}
