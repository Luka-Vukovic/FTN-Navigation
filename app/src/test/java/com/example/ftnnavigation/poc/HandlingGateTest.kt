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
    private fun run(
        gate: HandlingGate, seconds: Double, steps: List<Double>, peak: Float = 1.5f, tilt: (Double) -> Double,
    ): List<Double> {
        val applied = mutableListOf<Double>()
        val pending = steps.sorted().toMutableList()
        var t = 0L
        while (t <= (seconds * 1e9).toLong()) {
            gate.onRotation(matrix(tilt(t / 1e9)), t)
            gate.takeReleased().forEach { applied += Math.round(it.timeNs / 1e6) / 1000.0 }
            while (pending.isNotEmpty() && pending.first() * 1e9 <= t) {
                val ns = (pending.removeAt(0) * 1e9).toLong()
                gate.onStep(HandlingGate.Step(ns, WalkingDirection.WalkStep(0f), peak)).forEach { applied += Math.round(it.timeNs / 1e6) / 1000.0 }
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
    fun takingOutOfPocketStanding_weakStepsLongBeforeTurn_dropped() {
        // Proba 14.10.2026 (168-172 s): 7 slabih "koraka" (vrh 0,6-1,3) ~4 s pre nego što se telefon okrene - duže od
        // potvrde u džepu (3 s), ali bez udara noge.
        val gate = HandlingGate()
        val handling = listOf(2.0, 2.9, 3.4, 3.9, 4.4, 5.4, 5.9)
        val applied = run(gate, 12.0, handling, peak = 0.7f) { t -> 90.0 - 60.0 * ((t - 5.5) / 1.0).coerceIn(0.0, 1.0) }
        assertTrue("applied=$applied", applied.isEmpty())
        assertEquals(7, gate.dropped)
    }

    @Test
    fun softWalkFromPocket_appliedAfterLongerConfirmation() {
        // Tih hod sa telefonom u džepu, bez jakog udara - pušta se posle 8 s, svi koraci.
        val walk = steps(1.0, 0.55, 24)
        val applied = run(HandlingGate(), 16.0, walk, peak = 0.8f) { t -> 85.0 + 10.0 * sin(2 * Math.PI * t / 1.1) }
        assertEquals(walk, applied)
    }

    @Test
    fun puttingInPocketFromHand_weakStepsBeforeTurn_dropped() {
        // Proba 15.10.2026 (111-122 s): ruka ~2 s pomera telefon uz slabe "korake" (vrh 0,6-1,2, na ~0,45 s) pre nego što se
        // okrene ka džepu, pa još ~5 s nameštanja u džepu - ranije potvrđeno kao hod posle 1,5 s i prošlo celo (22 koraka).
        val gate = HandlingGate()
        val handling = steps(2.0, 0.45, 22)
        val applied = run(gate, 16.0, handling, peak = 0.8f) { t -> 10.0 + 80.0 * ((t - 3.5) / 2.0).coerceIn(0.0, 1.0) }
        assertTrue("applied=$applied", applied.isEmpty())
        assertEquals(22, gate.dropped)
    }

    @Test
    fun weakWalkInHand_appliedAfterWait() {
        // Spor, tih hod u ruci (hod po stanu 15.10.: vrhovi 0,6-1,2) - telefon se ne okreće, pušta se posle 3 s, svi koraci.
        val walk = steps(1.0, 0.85, 14)
        val applied = run(HandlingGate(), 16.0, walk, peak = 0.7f) { 20.0 }
        assertEquals(walk, applied)
    }

    @Test
    fun shortWeakWalkInHand_thenStop_applied() {
        // 4 tiha koraka pa stoji: telefon se nije okrenuo - na pauzi se puste (kao ranije posle 1,5 s).
        val walk = steps(1.0, 0.6, 4)
        val applied = run(HandlingGate(), 8.0, walk, peak = 0.7f) { 20.0 }
        assertEquals(walk, applied)
    }

    @Test
    fun weakStepsRightAfterDroppedHandling_dropped() {
        // Proba 15.10.2026 (411-420 s): vađenje iz džepa, telefon se smiri usred pokreta (6 odbačeno), pa još ~4 s slabih
        // "koraka" bez okreta i pauza - ranije pušteno kao hod u ruci.
        val gate = HandlingGate()
        val handling = steps(2.0, 0.5, 6) + steps(5.6, 0.7, 6)
        val applied = run(gate, 14.0, handling, peak = 0.7f) { t -> 90.0 - 70.0 * ((t - 3.5) / 1.2).coerceIn(0.0, 1.0) }
        assertTrue("applied=$applied", applied.isEmpty())
        assertEquals(12, gate.dropped)
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
