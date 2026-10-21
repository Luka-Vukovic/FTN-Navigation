package com.example.ftnnavigation.poc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prepoznavanje sprata na stepeništu po okretu između letova ([StairWalk]) - pravila sa snimaka 03.10. i 04.10.2026. */
class StairWalkTest {

    /**
     * Teren 12.10.2026, Kula: ~40 koraka hodnikom NB-a do stepeništa Kule, pa okret i nazad - povratak je paralelan tom
     * hodniku, a hodnik nije let (duži od 24 koraka): izlazak sa stepeništa posle par koraka, ne tek posle 43.
     */
    @Test
    fun backAlongLongCorridor_leaves() {
        val walk = StairWalk()
        repeat(40) { walk.step(270f) }
        repeat(4) { walk.step(170f) }
        val back = (1..6).map { walk.step(95f); walk.leaves(towardCorridorM = 1.5, exitM = 1.2) }
        assertTrue("$back", back.last())
    }

    private val stepNs = 650_000_000L

    /** Hod: [headings] redom, korak na 0,65 s (ili sa pauzom pre koraka iz [pausesBefore]); vraća prvu stranu okreta po koraku. */
    private fun StairWalk.walk(headings: List<Float>, pausesBefore: Set<Int> = emptySet(), startNs: Long = 0L): List<Int> {
        var t = startNs
        return headings.mapIndexed { i, h ->
            t += if (i in pausesBefore) 10_000_000_000L else stepNs
            step(h, timeNs = t)
            floorTurn(0, 0)
        }
    }

    private fun flight(heading: Float, steps: Int) = List(steps) { heading + (it % 3 - 1) * 4f }

    /** Okret na podestu: u hodu, kroz [steps] koraka. */
    private fun turn(from: Float, by: Float, steps: Int = 4) = (1..steps).map { from + by * it / (steps + 1) }

    @Test
    fun flightTurnRightFlight_isOneFloorTurningRight() {
        val walk = StairWalk()
        val turns = walk.walk(flight(250f, 10) + turn(250f, 180f) + flight(70f, 8))
        // Okret tek posle 6 koraka drugog leta.
        val first = turns.indexOfFirst { it != 0 }
        assertEquals(10 + 4 + FLIGHT_STEPS - 1, first)
        assertEquals(1, turns[first])
    }

    @Test
    fun turningLeft_isTheOtherSide() {
        val turns = StairWalk().walk(flight(250f, 10) + turn(250f, -190f) + flight(60f, 8))
        assertEquals(-1, turns.first { it != 0 })
    }

    /** NB I -> P (03.10.): pre stepeništa okret ulevo iz hodnika (iste strane) - meri se od kraja prvog leta. */
    @Test
    fun sameSideTurnBeforeFirstFlight_doesNotHide() {
        val turns = StairWalk().walk(flight(350f, 8) + turn(350f, -95f, 6) + flight(255f, 10) + turn(255f, -200f, 8) + flight(55f, 7))
        assertEquals(-1, turns.first { it != 0 })
        // Ne pre drugog leta (okret iz hodnika nije podest).
        assertTrue(turns.indexOfFirst { it != 0 } >= 8 + 6 + 10 + 8)
    }

    /** F III (03.10.): lutanje po podestu (nizovi od 4 koraka) i okret ka prvom letu se ne broje. */
    @Test
    fun turnOnLandingBeforeFirstFlight_notCounted() {
        val wander = listOf(312f, 317f, 340f, 358f, 18f, 26f, 33f, 20f, 355f, 331f, 302f, 270f)
        val turns = StairWalk().walk(wander + flight(220f, 13) + turn(220f, 180f) + flight(40f, 12))
        val first = turns.indexOfFirst { it != 0 }
        assertTrue("okret na podestu je brojan (korak $first)", first >= wander.size + 13)
        assertEquals(1, turns[first])
    }

    /**
     * Teren 12.10.2026, NB II -> III (snimak 175756, smer na planu): krak ~352°, okret, 7 koraka popreko preko širokog
     * međupodesta (~99°), okret, krak ~176°. Niz popreko nije let - okret se meri od prvog kraka.
     */
    @Test
    fun walkAcrossWideLanding_stillOneFloor() {
        val headings = listOf(7f, 4f, 358f, 353f, 351f, 352f, 351f, 350f, 351f, 351f, 351f, 355f, 1f, 17f, 46f, 75f) +
            listOf(89f, 99f, 98f, 98f, 98f, 101f, 107f) + listOf(119f, 137f, 151f, 160f) +
            listOf(169f, 174f, 179f, 179f, 175f, 174f, 176f, 179f, 186f)
        val turns = StairWalk().walk(headings)
        assertEquals(1, turns.firstOrNull { it != 0 })
        // Isti hod bez kraka posle podesta (hodnik popreko, pa nazad) - nije sprat.
        assertTrue(StairWalk().walk(headings.take(23) + List(8) { 280f }).all { it == 0 })
    }

    /** Hodnik gore-dole pored stepeništa: okret u mestu (između dva koraka) nije podest. */
    @Test
    fun inPlaceTurn_notAFloor() {
        val turns = StairWalk().walk(flight(90f, 12) + flight(270f, 12))
        assertTrue(turns.all { it == 0 })
    }

    /** Hodnik: 25-77 koraka u jednom pravcu nije let (letovi na snimcima 7-17). */
    @Test
    fun longCorridorWalk_notAFlight() {
        val turns = StairWalk().walk(flight(90f, 30) + turn(90f, 180f) + flight(270f, 12))
        assertTrue(turns.all { it == 0 })
    }

    /** NB 04.10.: korisnik stoji, detektor javlja "korake" na 4-42 s - to nije let; ni okret za vreme pauze. */
    @Test
    fun pauses_breakFlightsAndTurns() {
        val headings = flight(90f, 10) + turn(90f, 180f) + flight(270f, 10)
        assertTrue(StairWalk().walk(headings, pausesBefore = setOf(12)).all { it == 0 })
        val standing = StairWalk().walk(flight(90f, 10) + turn(90f, 180f) + flight(270f, 10), pausesBefore = (14 until 24).toSet())
        assertTrue(standing.all { it == 0 })
    }

    /** Drugi sprat u istom hodu: još pun krug (podest sprata + podest između spratova) od kraja prvog leta. */
    @Test
    fun secondFloor_afterAnotherFullTurn() {
        val walk = StairWalk()
        var changes = 0
        val headings = flight(250f, 10) + turn(250f, 180f) + flight(70f, 10) + turn(70f, 180f) + flight(250f, 10) +
            turn(250f, 180f) + flight(70f, 8)
        val at = mutableListOf<Int>()
        var t = 0L
        for ((i, h) in headings.withIndex()) {
            t += stepNs
            walk.step(h, timeNs = t)
            if (walk.floorTurn(changes, if (changes > 0) 1 else 0) != 0) {
                walk.confirmChange()
                changes++
                at += i
            }
        }
        assertEquals(2, changes)
        // Druga promena tek posle trećeg okreta (prvi let - podest - let - podest sprata - let - podest - let).
        assertTrue(at[1] > 10 + 4 + 10 + 4 + 10 + 4)
    }

    /**
     * Teren 21.10.2026, NB (smer na planu sa snimka 112511): P -> I -> II i II -> I -> P. U drugom kraku drugog sprata kompas
     * klizi (182° -> 226°, 206° -> 157°) - niz od 20° je bio kraći od [FLIGHT_STEPS] i drugi sprat nije prepoznat ni gore ni
     * dole (posle 8 koraka kraka "vratio se"). Posle prve promene let sme da klizi.
     */
    @Test
    fun secondFloor_compassDriftsAlongFlight_stillCounts() {
        val up = listOf(34f, 31f, 26f, 18f, 10f, 9f, 8f, 8f, 11f, 12f, 13f, 12f, 10f, 11f, 14f, 17f, 41f, 67f, 93f, 114f, 135f,
            159f, 186f, 201f, 201f, 196f, 189f, 181f, 181f, 192f, 189f, 193f, 197f, 203f, 228f, 257f, 290f, 309f, 331f, 345f,
            358f, 6f, 14f, 18f, 18f, 17f, 21f, 24f, 27f, 31f, 47f, 69f, 91f, 109f, 128f, 147f, 168f) +
            listOf(182f, 191f, 190f, 189f, 193f, 204f, 211f, 215f, 216f, 219f, 223f, 226f)
        val down = listOf(31f, 24f, 20f, 22f, 20f, 16f, 16f, 15f, 15f, 17f, 17f, 15f, 11f, 6f, 5f, 11f, 16f, 9f, 340f, 314f, 289f,
            279f, 271f, 256f, 232f, 215f, 204f, 199f, 198f, 201f, 206f, 205f, 200f, 195f, 188f, 182f, 177f, 158f, 136f, 112f, 91f,
            70f, 50f, 32f, 20f, 15f, 12f, 13f, 12f, 9f, 9f, 9f, 8f, 359f, 337f, 313f, 288f, 282f, 271f, 251f) +
            listOf(220f, 206f, 201f, 196f, 193f, 189f, 184f, 175f, 164f, 157f, 158f)
        for ((headings, lastFlight) in listOf(up to up.size - 12, down to down.size - 11)) {
            val walk = StairWalk()
            var changes = 0
            var sense = 0
            val at = mutableListOf<Int>()
            var t = 0L
            for ((i, h) in headings.withIndex()) {
                t += stepNs
                walk.step(h, timeNs = t)
                val s = walk.floorTurn(changes, sense)
                if (s != 0) {
                    walk.confirmChange()
                    changes++
                    sense = s
                    at += i
                }
            }
            assertEquals("$at", 2, changes)
            // Pre 8. koraka poslednjeg kraka (tada je bio "vratio se").
            assertTrue("$at", at[1] < lastFlight + 7)
        }
    }

    /**
     * Teren 05.10.2026 (NB I -> III, NTP I -> II): posle prve promene korisnik zastane na podestu (2,2-3,3 s, gleda
     * obaveštenje) - sledeći sprat se i dalje broji. Do tada je svaka pauza od prvog leta odbijala sve sledeće spratove.
     */
    @Test
    fun secondFloor_pauseOnLandingAfterFirstChange_stillCounts() {
        val walk = StairWalk()
        var changes = 0
        val headings = flight(10f, 10) + turn(10f, 180f) + flight(190f, 10) + turn(190f, 180f) + flight(10f, 10) +
            turn(10f, 180f) + flight(190f, 8)
        // Pauza u drugom letu (posle promene, NTP 12:00:46) i pred trećim letom (NB 11:36:16).
        val pauses = mapOf(10 + 4 + 8 to 2_200_000_000L, 10 + 4 + 10 + 4 to 3_250_000_000L)
        var t = 0L
        for ((i, h) in headings.withIndex()) {
            t += pauses[i] ?: stepNs
            walk.step(h, timeNs = t)
            if (walk.floorTurn(changes, if (changes > 0) 1 else 0) != 0) {
                walk.confirmChange()
                changes++
            }
        }
        assertEquals(2, changes)
    }

    @Test
    fun leaves_onlyByNonFlightWalkTowardCorridor() {
        val walk = StairWalk()
        walk.walk(flight(250f, 10) + turn(250f, 180f) + flight(70f, 10))
        // Drugi let (paralelan prvom) nije izlazak ni kad ide ka hodniku ...
        assertFalse(walk.leaves(towardCorridorM = 3.0, exitM = 1.2))
        // ... ni okret na podestu (kratki nizovi) ...
        walk.walk(turn(70f, 90f, 2), startNs = 100_000_000_000L)
        assertFalse(walk.leaves(towardCorridorM = 3.0, exitM = 1.2))
        // ... a hod drugim pravcem ka hodniku jeste.
        walk.walk(flight(160f, 4), startNs = 102_000_000_000L)
        assertTrue(walk.leaves(towardCorridorM = 1.5, exitM = 1.2))
        assertFalse(walk.leaves(towardCorridorM = 0.5, exitM = 1.2))
    }
}
