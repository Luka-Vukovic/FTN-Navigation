package com.example.ftnnavigation.poc

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

/** Zum oko prstiju i granice pomeranja (okvir 1000 x 2000, širok plan 1000 x 400 centriran u njemu). */
class ZoomPanStateTest {

    private val viewport = IntSize(1000, 2000)
    private val content = IntSize(1000, 400)

    private fun state(content: IntSize = this.content) = ZoomPanState(maxScale = 6f).apply {
        onViewport(viewport)
        onContent(content)
    }

    /** Tačka sadržaja (u koordinatama okvira pre zuma) -> gde je na ekranu. */
    private fun ZoomPanState.screenOf(p: Offset): Offset {
        val c = Offset(viewport.width / 2f, viewport.height / 2f)
        return c + (p - c) * scale + pan
    }

    /** Visok sadržaj (posle zuma veći od okvira u oba pravca): tačka pod prstima ostaje na mestu. */
    @Test
    fun zoom_keepsPointUnderFingers() {
        val s = state(IntSize(1000, 1600))
        val finger = Offset(900f, 1100f) // desni deo plana, ispod sredine
        s.onGesture(finger, Offset.Zero, 2f)
        assertEquals(2f, s.scale)
        val after = s.screenOf(finger)
        assertEquals(finger.x, after.x, 0.01f)
        assertEquals(finger.y, after.y, 0.01f)
    }

    @Test
    fun pan_staysWithinContent() {
        val s = state()
        s.onGesture(Offset(500f, 1000f), Offset(5000f, 5000f), 2f)
        // Širina 2000 u okviru 1000: najviše 500 u stranu; visina 800 < 2000: ostaje centrirano.
        assertEquals(500f, s.pan.x, 0.01f)
        assertEquals(0f, s.pan.y, 0.01f)
    }

    @Test
    fun zoomOutToOne_recenters() {
        val s = state()
        s.onGesture(Offset(900f, 1000f), Offset.Zero, 3f)
        s.onGesture(Offset(100f, 1000f), Offset.Zero, 0.1f)
        assertEquals(1f, s.scale)
        assertEquals(0f, s.pan.x, 0.01f)
        assertEquals(0f, s.pan.y, 0.01f)
    }
}
