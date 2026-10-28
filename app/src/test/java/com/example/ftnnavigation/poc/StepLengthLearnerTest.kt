package com.example.ftnnavigation.poc

import com.example.ftnnavigation.campus.GpsFix
import com.example.ftnnavigation.graph.PointM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StepLengthLearnerTest {

    /** Hod pravo na istok, korak [stepM]; GPS svaki drugi korak (tačnost [accuracy]). Vraća poslednju naučenu dužinu. */
    private fun walkEast(learner: StepLengthLearner, steps: Int, stepM: Double, factor: Float = 1f, accuracy: Float = 5f,
                         heading: (Int) -> Float = { 90f }): Float? {
        var learned: Float? = null
        var x = 0.0
        learner.onGps(GpsFix(PointM(x, 0.0), accuracy))
        repeat(steps) { i ->
            learner.onStep(heading(i), factor)
            x += stepM * factor
            if (i % 2 == 1) learner.onGps(GpsFix(PointM(x, 0.0), accuracy))?.let { learned = it }
        }
        return learned
    }

    @Test
    fun straightWalk_learnsStepLengthAfterThreeSections() {
        val learner = StepLengthLearner()
        // 3 deonice od 60 m pri 0,82 m = ~74 koraka po deonici.
        assertEquals(0.82f, walkEast(learner, 240, 0.82)!!, 0.01f)
        assertEquals(3, learner.samples.size.coerceAtMost(3))
    }

    @Test
    fun weakSteps_countAsPartOfFullStep() {
        val learner = StepLengthLearner()
        // Koraci od pola pune dužine (faktor 0,5) - pun korak je i dalje 0,8 m.
        assertEquals(0.8f, walkEast(learner, 480, 0.8, factor = 0.5f)!!, 0.01f)
    }

    @Test
    fun zigzagOrInaccurateGps_notLearned() {
        // Smer koraka levo-desno (±60°): pomeraj u koracima je pola zbira - deonica se ne broji.
        assertNull(walkEast(StepLengthLearner(), 400, 0.8, heading = { if (it % 2 == 0) 30f else 150f }))
        assertNull(walkEast(StepLengthLearner(), 400, 0.8, accuracy = 15f))
    }

    @Test
    fun samplesKept_clearedByManualSetting() {
        val learner = StepLengthLearner(listOf(0.78f, 0.80f))
        val learned = walkEast(learner, 80, 0.82)
        assertEquals("treća deonica (posle dve sačuvane)", 0.80f, learned!!, 0.001f)
        assertEquals(learned, learner.learned)
        learner.clear()
        assertTrue(learner.samples.isEmpty())
        assertNull(learner.learned)
    }
}
