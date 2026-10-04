package com.example.ftnnavigation.poc

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Auto-rotacija mape: izbor orijentacije (histereza), veličina zarotiranog sadržaja, zum i pomeraj pri rotaciji. */
class MapRotationTest {

    @Test
    fun quarter_withoutCurrent_isNearest() {
        assertEquals(0, mapQuarterFor(null, 10f))
        assertEquals(1, mapQuarterFor(null, 100f))
        assertEquals(2, mapQuarterFor(null, 200f))
        assertEquals(3, mapQuarterFor(null, 260f))
        assertEquals(0, mapQuarterFor(null, 350f))
    }

    /** Preko 45° se ne okreće dok smer ne ode 65° od trenutnog "gore" - u oba smera, i preko 0°/360°. */
    @Test
    fun quarter_hysteresis() {
        assertEquals(0, mapQuarterFor(0, 60f))
        assertEquals(1, mapQuarterFor(0, 70f))
        assertEquals(0, mapQuarterFor(0, 300f))
        assertEquals(3, mapQuarterFor(0, 290f))
        assertEquals(1, mapQuarterFor(1, 30f))
        assertEquals(0, mapQuarterFor(1, 20f))
        assertEquals(3, mapQuarterFor(3, 330f))
        assertEquals(0, mapQuarterFor(3, 340f))
    }

    /** Hod po dijagonali uz kolebanje kompasa ±15° ne okreće mapu ni iz jedne od dve susedne orijentacije. */
    @Test
    fun quarter_diagonalWithJitter_staysPut() {
        for (heading in 30..60) {
            assertEquals(0, mapQuarterFor(0, heading.toFloat()))
            assertEquals(1, mapQuarterFor(1, heading.toFloat()))
        }
    }

    @Test
    fun quarter_turnAround() {
        assertEquals(2, mapQuarterFor(0, 180f))
        assertEquals(0, mapQuarterFor(2, 5f))
    }

    /** Širok plan (2,5 : 1) u uskom okviru: za 90° je dvostruko veći (zauzima visinu), za 45° obuhvat staje u okvir. */
    @Test
    fun fitSize_rotatedWidePlan() {
        val frame = Size(1000f, 2000f)
        assertSize(Size(1000f, 400f), rotatedFitSize(frame, 2.5f, 0f))
        assertSize(Size(2000f, 800f), rotatedFitSize(frame, 2.5f, 90f))
        assertSize(Size(2000f, 800f), rotatedFitSize(frame, 2.5f, -270f))
        val diagonal = rotatedBounds(rotatedFitSize(frame, 2.5f, 45f), 45f)
        assertTrue(diagonal.width <= frame.width + 0.01f && diagonal.height <= frame.height + 0.01f)
        assertEquals(frame.width, diagonal.width, 0.01f) // ograničava širina
    }

    /** Zumiran plan: posle rotacije (i novog rasporeda) u centru okvira je ista tačka plana. */
    @Test
    fun rotate_keepsCenterPoint() {
        val viewport = IntSize(1000, 2000)
        val s = ZoomPanState(maxScale = 6f).apply {
            onViewport(viewport)
            onContent(IntSize(1000, 400))
        }
        s.onGesture(Offset(700f, 1000f), Offset.Zero, 2f)
        val before = s.relativeAtCenter(Size(1000f, 400f))
        s.rotate(90f)
        val content = rotatedFitSize(Size(1000f, 2000f), 2.5f, 90f)
        s.onContent(IntSize(content.width.toInt(), content.height.toInt())) // raspored posle rotacije - ista veličina
        val after = s.relativeAtCenter(content)
        assertEquals(before.x, after.x, 0.001f)
        assertEquals(before.y, after.y, 0.001f)
        assertEquals(0.6f, after.x, 0.001f)
    }

    /** Granice pomeranja važe za obuhvat zarotiranog sadržaja (za 90° širina i visina zamenjene). */
    @Test
    fun rotate_panLimitsFollowRotatedBounds() {
        val s = ZoomPanState(maxScale = 6f).apply {
            onViewport(IntSize(1000, 2000))
            onContent(IntSize(1000, 400))
        }
        s.rotate(90f) // sadržaj 2000 x 800, na ekranu 800 x 2000
        s.onGesture(Offset(500f, 1000f), Offset(5000f, 5000f), 2f)
        // Na ekranu 1600 x 4000 u okviru 1000 x 2000: najviše 300 u stranu i 1000 gore/dole.
        assertEquals(300f, s.pan.x, 0.01f)
        assertEquals(1000f, s.pan.y, 0.01f)
    }

    /** Relativna tačka sadržaja (0..1) u centru okvira: c = c + R s p + pan -> p = R⁻¹(−pan) / s. */
    private fun ZoomPanState.relativeAtCenter(content: Size): Offset {
        val p = (-pan).rotated(-rotationDeg) / scale
        return Offset(p.x / content.width + 0.5f, p.y / content.height + 0.5f)
    }

    private fun assertSize(expected: Size, actual: Size) {
        assertEquals(expected.width, actual.width, 0.01f)
        assertEquals(expected.height, actual.height, 0.01f)
    }
}
