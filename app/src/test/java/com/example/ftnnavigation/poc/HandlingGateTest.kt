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
        gate: HandlingGate, seconds: Double, steps: List<Double>, peak: Float = 1.5f, peaks: List<Float>? = null,
        up: ((Double) -> DoubleArray)? = null, tilt: (Double) -> Double = { 0.0 },
    ): List<Double> {
        val applied = mutableListOf<Double>()
        val pending = steps.indices.sortedBy { steps[it] }.toMutableList()
        var t = 0L
        while (t <= (seconds * 1e9).toLong()) {
            val m = up?.let { f -> FloatArray(9).also { m -> f(t / 1e9).forEachIndexed { i, v -> m[6 + i] = v.toFloat() } } }
            gate.onRotation(m ?: matrix(tilt(t / 1e9)), t)
            gate.takeReleased().forEach { applied += Math.round(it.timeNs / 1e6) / 1000.0 }
            while (pending.isNotEmpty() && steps[pending.first()] * 1e9 <= t) {
                val i = pending.removeAt(0)
                val ns = (steps[i] * 1e9).toLong()
                val step = HandlingGate.Step(ns, WalkingDirection.WalkStep(0f), peaks?.get(i) ?: peak)
                gate.onStep(step).forEach { applied += Math.round(it.timeNs / 1e6) / 1000.0 }
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
    fun puttingInPocketFromHand_mediumStepsBeforeTurn_dropped() {
        // Proba 15.10.2026 popodne (250-257 s): ruka ~3 s pomera telefon (vrhovi 0,6-1,3 - medijana 1,1, iznad ranijeg praga
        // 0,9), pa ga okreće u džep - ranije potvrđeno kao hod posle 1,5 s i prošlo celo stavljanje (13 koraka).
        val gate = HandlingGate()
        val handling = listOf(2.17, 2.82, 3.89, 4.50, 4.89, 5.37, 5.71, 5.97, 6.51, 6.83, 7.20, 8.08, 8.61)
        val peaks = listOf(0.63f, 0.87f, 1.08f, 1.24f, 1.30f, 1.13f, 0.73f, 2.54f, 1.43f, 0.72f, 1.13f, 1.30f, 0.82f)
        // Okret od mirovanja na snimku: 17° (5,37 s), 36° (5,71), 61° (5,97), 106° (8,08).
        val applied = run(gate, 14.0, handling, peaks = peaks) { t -> 30.0 + 100.0 * ((t - 5.0) / 1.6).coerceIn(0.0, 1.0) }
        assertTrue("applied=$applied", applied.isEmpty())
        assertEquals(13, gate.dropped)
    }

    @Test
    fun slowWeakStartInHand_turnAfterThreeSeconds_dropped_walkKept() {
        // Proba 15.10.2026 popodne (680-700 s): ruka pomera telefon ~2,6 s (vrhovi 0,7-1,2), okret ka džepu počne tek posle
        // 3 s čekanja - ranije pušteno kao slab hod u ruci, pa i celo stavljanje (13 koraka); posle stoji 3 s i polako hoda.
        val handling = listOf(2.44, 3.27, 3.78, 4.38, 5.31, 5.57, 5.85, 6.12, 6.72, 7.01, 7.76, 8.28, 8.70)
        val peaks = listOf(0.66f, 0.91f, 0.98f, 1.16f, 1.04f, 0.91f, 1.80f, 0.80f, 0.83f, 0.68f, 0.66f, 3.03f, 1.03f)
        val walk = steps(11.75, 1.0, 12)
        // Okret na snimku prelazi 30° ~2,8 s posle prvog "koraka" (5,2 s), 80° posle još 0,8 s.
        val applied = run(HandlingGate(), 26.0, handling + walk, peaks = peaks + List(walk.size) { 3f }) { t ->
            30.0 + 70.0 * ((t - 4.9) / 1.2).coerceIn(0.0, 1.0)
        }
        assertEquals(walk, applied)
    }

    @Test
    fun sittingWithPhoneInHand_fidgetAndStandingUp_dropped_walkKept() {
        // Teren 23.10.2026 (NTP hol, klupa 1118-1441 s): hod, pa ~5 min sedenja sa telefonom u ruci; tri niza "koraka" su
        // prošla kao hod (1316, 1352, 1434 s - vremena i vrhovi sa snimka). Pa ustajanje sa klupe 22.10. (1590-1592 s: 6 "koraka"
        // na 0,26-0,43 s) i pravi hod na ~0,65 s.
        val before = steps(1.0, 0.55, 10)
        val fidget = listOf(30.0, 31.21, 31.62, 32.93, 45.0, 45.39, 46.37, 47.11, 47.81, 60.0, 60.79, 62.18, 63.71)
        val fidgetPeaks = listOf(1.32f, 1.20f, 1.66f, 0.72f, 0.73f, 1.39f, 0.98f, 1.62f, 1.04f, 0.62f, 0.76f, 0.70f, 0.82f)
        val standUp = listOf(75.0, 75.35, 75.78, 76.13, 76.39, 76.65)
        val standUpPeaks = listOf(1.24f, 0.71f, 0.91f, 1.54f, 1.82f, 2.95f)
        val after = steps(78.04, 0.65, 12)
        val applied = run(
            HandlingGate(), 90.0, before + fidget + standUp + after,
            peaks = List(before.size) { 1.5f } + fidgetPeaks + standUpPeaks + List(after.size) { 1.8f },
        ) { 45.0 }
        assertEquals(before + after, applied)
    }

    @Test
    fun quietSlowWalkAfterLongRest_applied() {
        // Proba 15.10.2026 popodne (54-60 s, "10 tihih koraka"): posle dugog stajanja spor, tih hod u ruci - razmaci 0,26-1,15 s
        // (dupli koraci detektora), bez ritma, ali 8 koraka bez prekida (na klupi su nizovi od 4-5).
        val before = steps(1.0, 0.55, 10)
        val quiet = listOf(34.58, 34.97, 35.82, 36.07, 36.98, 37.97, 39.12, 40.27)
        val peaks = listOf(1.69f, 0.77f, 2.19f, 1.41f, 1.94f, 0.96f, 1.39f, 1.58f)
        val applied = run(HandlingGate(), 48.0, before + quiet, peaks = List(before.size) { 1.5f } + peaks) { 20.0 }
        assertEquals(before + quiet, applied)
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

    /** Nagib po tačkama (s, stepeni), linearno između njih. */
    private fun tiltPath(vararg points: Pair<Double, Double>): (Double) -> Double = { t ->
        val i = points.indexOfFirst { it.first > t }
        when (i) {
            -1 -> points.last().second
            0 -> points.first().second
            else -> {
                val (t0, a0) = points[i - 1]
                val (t1, a1) = points[i]
                a0 + (a1 - a0) * (t - t0) / (t1 - t0)
            }
        }
    }

    @Test
    fun weakWalkInHand_phoneLiftedToFace_stepsKept() {
        // Proba 16.10. (hand-look, 103-113 s): tih polazak, posle 4 koraka telefon podignut ka licu (skoro uspravan), pa
        // postepeno spušten - hod bez prekida. Bilo: 10 od 13 koraka odbačeno kao premeštanje.
        val walk = listOf(3.15, 3.70, 4.49, 4.87, 5.23, 6.02, 6.93, 7.85, 8.77, 9.50, 10.44, 11.34, 12.19, 13.01)
        val peaks = listOf(0.78f, 0.81f, 0.93f, 0.73f, 2.49f, 1.64f, 1.67f, 0.92f, 1.38f, 1.99f, 1.65f, 1.43f, 0.92f, 1.14f)
        val applied = run(
            HandlingGate(), 16.0, walk, peaks = peaks,
            tilt = tiltPath(0.0 to 23.0, 4.9 to 25.0, 6.0 to 82.0, 6.9 to 70.0, 7.85 to 57.0, 8.8 to 46.0, 9.5 to 37.0, 11.3 to 30.0, 13.0 to 24.0),
        )
        assertEquals(walk, applied)
    }

    @Test
    fun putInPocketAndWalkAway_looseSwing_walkKept() {
        // Proba 16.10. (away, 855-866 s): stavi u džep i odmah kreni; u labavom džepu se telefon ljulja ~35°, pa je posle
        // svakog smirivanja to bilo novo premeštanje i hod je odbacivan (14 od 20 koraka).
        val insertion = listOf(5.86, 6.46, 6.93)
        val walk = listOf(7.69, 8.55, 9.17, 9.61, 10.01, 10.96, 11.74, 12.33, 13.21, 14.18, 15.04, 15.84, 16.67, 17.29, 18.36)
        val peaks = listOf(0.89f, 1.29f, 1.05f, 5.9f, 3.49f, 2.68f, 0.66f, 3.23f, 13.77f, 2.37f, 1.63f, 3.41f, 9.47f, 2.0f, 0.91f, 2.73f, 0.95f, 3.32f)
        // "Gore" u koordinatama telefona sa snimka: u ruci, pa iz ruke na bok i u džep, pa ljuljanje između dva položaja.
        val hand = doubleArrayOf(0.03, 0.33, 0.94)
        val side = doubleArrayOf(0.85, 0.45, 0.24)
        val forward = doubleArrayOf(0.45, 0.83, 0.30)
        val back = doubleArrayOf(0.26, 0.95, -0.20)
        fun mix(a: DoubleArray, b: DoubleArray, k: Double) = DoubleArray(3) { a[it] + (b[it] - a[it]) * k.coerceIn(0.0, 1.0) }
        val applied = run(HandlingGate(), 20.0, insertion + walk, peaks = peaks, up = { t ->
            when {
                t < 5.4 -> hand
                t < 6.4 -> mix(hand, side, t - 5.4)
                t < 7.4 -> mix(side, forward, t - 6.4)
                else -> mix(forward, back, 0.5 - 0.5 * cos(2 * Math.PI * (t - 7.4) / 1.6))
            }
        })
        assertTrue("applied=$applied", applied.containsAll(walk))
        assertTrue("applied=$applied", applied.none { it < 6.9 })
    }
}
