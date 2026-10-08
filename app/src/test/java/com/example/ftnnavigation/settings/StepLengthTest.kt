package com.example.ftnnavigation.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class StepLengthTest {
    @Test
    fun snap_roundsToIncrementWithinRange() {
        assertEquals(0.75f, snapStepLength(0.7499f))
        assertEquals(0.75f, snapStepLength(0.76f))
        assertEquals(0.8f, snapStepLength(0.79f))
        assertEquals(MIN_STEP_LENGTH_M, snapStepLength(0.2f))
        assertEquals(MAX_STEP_LENGTH_M, snapStepLength(1.7f))
    }

    @Test
    fun snap_everySliderValueIsExact() {
        // Vrednosti klizača (Float) moraju posle zaokruživanja biti tačno 0,50, 0,55 ... 1,00 - podrazumevana se poredi sa !=.
        val values = (0..10).map { snapStepLength(MIN_STEP_LENGTH_M + it * STEP_LENGTH_INCREMENT_M) }
        assertEquals((50..100 step 5).map { it / 100f }, values)
        assertEquals(DEFAULT_STEP_LENGTH_M, snapStepLength(DEFAULT_STEP_LENGTH_M))
    }
}
