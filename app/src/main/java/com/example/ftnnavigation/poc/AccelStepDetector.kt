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
 * [onStep] dobija vreme prelaska praga i visinu vrha (m/s² iznad bazne linije), a javlja se kad vrh prođe (signal padne
 * ispod [PEAK_DROP] vrha) ili najviše [PEAK_WINDOW_NS] posle prelaska - od vrha zavisi dužina koraka ([stepLengthFactor]).
 *
 * Koeficijenti filtera su podešeni za ~50 Hz (SENSOR_DELAY_GAME).
 */
class AccelStepDetector(private val onStep: (timestampNs: Long, peak: Float) -> Unit) {
    private var smoothed = GRAVITY
    private var baseline = GRAVITY
    private var armed = true
    private var lastStepNs = Long.MIN_VALUE / 2

    // Korak čeka vrh.
    private var pending = false
    private var peak = 0f

    fun onAccelerometer(x: Float, y: Float, z: Float, timestampNs: Long) {
        val magnitude = sqrt(x * x + y * y + z * z)
        smoothed += SMOOTH_ALPHA * (magnitude - smoothed)
        baseline += BASELINE_ALPHA * (magnitude - baseline)
        val signal = smoothed - baseline

        if (pending) {
            if (signal > peak) peak = signal
            if (signal < PEAK_DROP * peak || timestampNs - lastStepNs >= PEAK_WINDOW_NS) {
                pending = false
                onStep(lastStepNs, peak)
            }
        }
        if (armed && signal > STEP_THRESHOLD && timestampNs - lastStepNs >= MIN_STEP_INTERVAL_NS) {
            armed = false
            lastStepNs = timestampNs
            pending = true
            peak = signal
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

        private const val PEAK_DROP = 0.8f
        private const val PEAK_WINDOW_NS = 200_000_000L

        /**
         * Vrh od kog je korak pune dužine. Snimci 08.-09.10.2026: ustaljen hod ima vrh medijana 2,4-2,6 m/s² (10 % ispod
         * 1,4-1,6), a poslednja 3 koraka pred zaustavljanje medijana 0,9 - sitni koraci i pokreti ruke (korisnik, teren
         * 09.10.2026: "uvek tu budu na kraju 2-3 sitna koraka"). Na 12 deonica sa "Ovde sam" (poznat stvarni put) dužina
         * po vrhu smanjuje rasipanje odnosa PDR put / stvarni sa 11,0 % na 8,4 % (srednje 101 %); kratke deonice (13-21 m,
         * ranije 116-131 %) najviše. Najmanje rasipanje (7,6 %) je bilo sa punim korakom tek od 1,6, ali je u replay-u
         * skraćivalo i spor normalan hod (deonice 94-95 % -> 88 %, jedna 128 % -> 89 %) - od 1,2 je ustaljen hod netaknut
         * (manje od 10 % njegovih koraka ima vrh ispod 1,2).
         */
        private const val FULL_STEP_PEAK = 1.2f

        /** Deo pune dužine za korak sa vrhom tik iznad praga. */
        private const val MIN_STEP_FACTOR = 0.2f

        /** Deo pune dužine koraka po visini vrha: 1 od [FULL_STEP_PEAK], linearno do [MIN_STEP_FACTOR] na pragu. */
        fun stepLengthFactor(peak: Float): Float {
            if (peak >= FULL_STEP_PEAK) return 1f
            val x = ((peak - STEP_THRESHOLD) / (FULL_STEP_PEAK - STEP_THRESHOLD)).coerceIn(0f, 1f)
            return MIN_STEP_FACTOR + (1f - MIN_STEP_FACTOR) * x
        }
    }
}
