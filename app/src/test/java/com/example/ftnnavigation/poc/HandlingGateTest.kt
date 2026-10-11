package com.example.ftnnavigation.poc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class HandlingGateTest {
    private val rotationNs = 20_000_000L // 50 Hz

    /** Matrica čiji je treći red "gore" u koordinatama telefona: [tiltDeg] 0 = leži (u ruci), 90 = uspravan (džep). */
    private fun matrix(tiltDeg: Double): FloatArray {
        val r = Math.toRadians(tiltDeg)
        return FloatArray(9).also {
            it[7] = sin(r).toFloat()
            it[8] = cos(r).toFloat()
        }
    }

    /**
     * Pušta [seconds] sekundi: orijentacija iz [tilt] (s -> stepeni), koraci u trenucima [steps] (s). Vraća vremena
     * primenjenih koraka (s).
     */
    private fun run(gate: HandlingGate, seconds: Double, steps: List<Double>, tilt: (Double) -> Double): List<Double> {
        val applied = mutableListOf<Double>()
        val pending = steps.sorted().toMutableList()
        var t = 0L
        while (t <= (seconds * 1e9).toLong()) {
            gate.onRotation(matrix(tilt(t / 1e9)), t)
            while (pending.isNotEmpty() && pending.first() * 1e9 <= t) {
                val ns = (pending.removeAt(0) * 1e9).toLong()
                gate.onStep(HandlingGate.Step(ns, WalkingDirection.WalkStep(0f), 1.5f)).forEach { applied += Math.round(it.timeNs / 1e6) / 1000.0 }
            }
            t += rotationNs
        }
        return applied
    }

    private fun steps(from: Double, every: Double, count: Int) = List(count) { Math.round((from + it * every) * 1000) / 1000.0 }

    @Test
    fun walkStartInHand_allStepsApplied_afterConfirmation() {
        val walk = steps(1.0, 0.5, 20)
        val applied = run(HandlingGate(), 12.0, walk) { 30.0 }
        assertEquals(walk, applied)
    }

    @Test
    fun walkStartFromPocket_allStepsApplied() {
        // Telefon u džepu, nogavica ga ljulja +-15°.
        val walk = steps(1.0, 0.55, 20)
        val applied = run(HandlingGate(), 14.0, walk) { t -> 85.0 + 15.0 * sin(2 * Math.PI * t / 1.1) }
        assertEquals(walk, applied)
    }

    @Test
    fun puttingInPocketStanding_stepsDropped() {
        // Proba 11.10.2026: u mestu, telefon iz ruke u džep za ~2 s, uz 6 "koraka", pa stoji.
        val gate = HandlingGate()
        val handling = listOf(2.0, 2.33, 2.6, 3.0, 3.4, 3.8)
        val applied = run(gate, 10.0, handling) { t -> 30.0 + 60.0 * ((t - 2.0) / 2.0).coerceIn(0.0, 1.0) }
        assertTrue("applied=$applied", applied.isEmpty())
        assertEquals(6, gate.dropped)
    }

    @Test
    fun takingOutOfPocketStanding_slowStart_stepsDropped() {
        // Vađenje: ~2 s "koraka" pre nego što se telefon okrene (11.10.2026, 10-14 s).
        val gate = HandlingGate()
        val handling = listOf(2.0, 2.4, 3.1, 3.9, 4.9, 5.8)
        val applied = run(gate, 12.0, handling) { t -> 90.0 - 60.0 * ((t - 4.5) / 1.5).coerceIn(0.0, 1.0) }
        assertTrue("applied=$applied", applied.isEmpty())
    }

    @Test
    fun singleStepsInPlace_dropped() {
        val gate = HandlingGate()
        val applied = run(gate, 10.0, listOf(1.0, 1.6, 5.0)) { 30.0 }
        assertTrue(applied.isEmpty())
        assertEquals(3, gate.dropped)
    }

    @Test
    fun handlingWhileWalking_untouched() {
        // Hod u ruci, pa telefon u džep u hodu (posle 5 s) - korisnik već hoda, ništa se ne odbacuje.
        val walk = steps(1.0, 0.5, 24)
        val applied = run(HandlingGate(), 14.0, walk) { t -> 30.0 + 60.0 * ((t - 5.0) / 2.0).coerceIn(0.0, 1.0) }
        assertEquals(walk, applied)
    }

    @Test
    fun putInPocketThenWalkAway_handlingDropped_walkKept() {
        // Stoji, stavi telefon u džep (2-4 s, 5 "koraka"), smiri se, pa krene (6 s ->).
        val handling = listOf(2.0, 2.4, 2.8, 3.2, 3.6)
        val walk = steps(6.0, 0.55, 16)
        val applied = run(HandlingGate(), 16.0, handling + walk) { t -> 30.0 + 60.0 * ((t - 2.0) / 2.0).coerceIn(0.0, 1.0) }
        assertEquals(walk, applied)
    }
}
