package com.example.ftnnavigation.poc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** Simuliran hod: korak na 0,6 s, ubrzanje 50 Hz. Na stepenicama jači vertikalni udar, manje horizontalnog ubrzanja. */
class StairGaitTest {

    private val stepNs = 600_000_000L
    private val sampleNs = 20_000_000L

    /** Telefon ekranom nagore (u ruci). */
    private val hand = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    /** Telefon uspravno (džep): z osa telefona je vodoravna. */
    private val pocket = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f)

    private var t = 0L

    /** Jedan korak: [vertical] i [horizontal] amplitude (m/s²); vraća vreme koraka. */
    private fun StairGait.walkStep(vertical: Double, horizontal: Double, rotation: FloatArray = hand): Long {
        onRotation(rotation)
        val start = t
        while (t < start + stepNs) {
            t += sampleNs
            val phase = 2 * PI * (t - start) / stepNs
            val v = (9.81 + vertical * sin(phase)).toFloat()
            val h = (horizontal * sin(phase + 0.7)).toFloat()
            // Svet -> telefon: telefon ekranom nagore ima iste ose; uspravan - vertikala je y ose telefona.
            if (rotation === hand) onAccelerometer(h, 0f, v, t) else onAccelerometer(h, v, 0f, t)
        }
        onStep(t)
        return t
    }

    private fun StairGait.flatWalk(steps: Int) = repeat(steps) { flat(walkStep(2.0, 1.0)) }

    @Test
    fun flatThenStairs_scoreRisesOnStairs() {
        val gait = StairGait()
        val first = gait.walkStep(2.0, 1.0)
        assertNull("bez baze nema znaka", gait.score(first))
        gait.flatWalk(20)
        val flat = gait.score(gait.walkStep(2.0, 1.0))!!
        assertEquals("po ravnom ~0", 0f, flat, 0.15f)
        val stairs = gait.score(gait.walkStep(3.5, 0.6))!!
        assertTrue("na stepenicama: $stairs", stairs > 0.6f)
    }

    @Test
    fun pocket_noScore() {
        val gait = StairGait()
        gait.flatWalk(20)
        assertNull(gait.score(gait.walkStep(3.5, 0.6, pocket)))
        assertNotNull(gait.score(gait.walkStep(3.5, 0.6)))
    }

    /** Korak u džepu ne ulazi u bazu - baza ostaje od hoda u ruci. */
    @Test
    fun pocketSteps_notInBaseline() {
        val gait = StairGait()
        repeat(20) { gait.flat(gait.walkStep(2.0, 1.0, pocket)) }
        assertNull("baza samo od koraka u ruci", gait.score(gait.walkStep(2.0, 1.0)))
    }
}
