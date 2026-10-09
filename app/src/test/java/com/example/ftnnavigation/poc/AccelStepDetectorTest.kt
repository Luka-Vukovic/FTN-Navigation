package com.example.ftnnavigation.poc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class AccelStepDetectorTest {
    private val sampleNs = 20_000_000L // 50 Hz, kao SENSOR_DELAY_GAME

    /** Pušta [seconds] sekundi signala kroz detektor; [magnitude] daje jačinu ubrzanja u trenutku t (s). */
    private fun countSteps(seconds: Double, magnitude: (Double) -> Double): Int {
        var steps = 0
        val detector = AccelStepDetector { _, _ -> steps++ }
        val samples = (seconds * 1e9 / sampleNs).toInt()
        for (i in 0 until samples) {
            val t = i * sampleNs / 1e9
            // Ceo signal na z osi - detektor gleda samo jačinu vektora.
            detector.onAccelerometer(0f, 0f, magnitude(t).toFloat(), i * sampleNs)
        }
        return steps
    }

    @Test
    fun walking_countsOneStepPerPeak() {
        // 2 koraka u sekundi, amplituda 2 m/s², 10 s -> 20 koraka.
        val steps = countSteps(10.0) { t -> 9.81 + 2.0 * sin(2 * PI * 2.0 * t) }
        assertTrue("steps=$steps", steps in 19..20)
    }

    @Test
    fun slowWalking_isDetected() {
        // 1.4 koraka u sekundi, amplituda 1.5 m/s², 10 s -> 14 koraka.
        val steps = countSteps(10.0) { t -> 9.81 + 1.5 * sin(2 * PI * 1.4 * t) }
        assertTrue("steps=$steps", steps in 13..14)
    }

    @Test
    fun lightWalking_isDetected() {
        // Lagan hod: 1.8 koraka u sekundi, amplituda samo 1 m/s², 10 s -> 18 koraka.
        val steps = countSteps(10.0) { t -> 9.81 + 1.0 * sin(2 * PI * 1.8 * t) }
        assertTrue("steps=$steps", steps in 17..18)
    }

    @Test
    fun standingStill_withSensorNoise_countsNothing() {
        val random = Random(42)
        val steps = countSteps(10.0) { 9.81 + random.nextDouble(-0.3, 0.3) }
        assertEquals(0, steps)
    }

    /** Vrh koraka: jak hod daje veći vrh od laganog, a korak se javlja sa vremenom prelaska praga (pre vrha). */
    @Test
    fun step_reportsPeakAndThresholdTime() {
        fun peaks(amplitude: Double): List<Pair<Long, Float>> {
            val out = mutableListOf<Pair<Long, Float>>()
            val detector = AccelStepDetector { t, peak -> out += t to peak }
            for (i in 0 until 500) {
                val t = i * sampleNs / 1e9
                detector.onAccelerometer(0f, 0f, (9.81 + amplitude * sin(2 * PI * 2.0 * t)).toFloat(), i * sampleNs)
            }
            return out.drop(2)
        }
        val strong = peaks(2.5)
        val light = peaks(1.0)
        assertTrue(strong.all { it.second > 1.2f } && light.all { it.second < 1.2f })
        assertTrue(strong.zip(light).all { (s, l) -> s.second > l.second })
        // Vrh sinusa 2 Hz je na četvrtini periode (0,125 s) - prelazak praga je pre njega.
        assertTrue(strong.all { (it.first / 1_000_000L) % 500 < 125 })
    }

    @Test
    fun stepLengthFactor_fullForWalkingShorterForShuffles() {
        assertEquals(1f, AccelStepDetector.stepLengthFactor(2.6f), 0f)
        assertEquals(1f, AccelStepDetector.stepLengthFactor(1.2f), 1e-6f)
        assertEquals(0.2f, AccelStepDetector.stepLengthFactor(AccelStepDetector.STEP_THRESHOLD), 1e-6f)
        // Medijana poslednja 3 koraka pred zaustavljanje (snimci 08.-09.10.2026): ~0,9 m/s².
        assertEquals(0.6f, AccelStepDetector.stepLengthFactor(0.9f), 1e-3f)
    }

    @Test
    fun tiltingPhone_slowGravityChange_countsNothing() {
        // Spora promena (npr. telefon se naginje) ne sme da liči na korak.
        val steps = countSteps(10.0) { t -> 9.81 + 0.5 * sin(2 * PI * 0.2 * t) }
        assertEquals(0, steps)
    }
}
