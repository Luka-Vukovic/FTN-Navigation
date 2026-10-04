package com.example.ftnnavigation.poc

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Raspored natpisa kampusa bez preklapanja ([placeWithoutOverlap]), i na zarotiranoj mapi. */
class LabelPlacementTest {

    private val size = Size(100f, 20f)
    private val gap = 6f

    private fun request(anchor: Offset, centered: Boolean = false, preferEast: Boolean = true) =
        LabelRequest(anchor, size, labelCandidates(anchor, size, centered, preferEast, gap))

    private fun place(vararg requests: LabelRequest, rotationDeg: Float = 0f, obstacles: List<Offset> = emptyList()) =
        placeWithoutOverlap(requests.toList(), obstacles, obstacleRadius = 4f, padding = 1f, rotationDeg = rotationDeg)

    @Test
    fun noConflict_firstCandidate() {
        val a = request(Offset(0f, 0f), centered = true)
        val b = request(Offset(0f, 200f), centered = true)
        assertEquals(listOf(a.candidates[0], b.candidates[0]), place(a, b))
    }

    @Test
    fun conflict_laterLabelTakesNextCandidate() {
        val a = request(Offset(0f, 0f), centered = true)
        // Na sredini bi se preklopio sa a; istočno od svoje tačke (60 + 6) je slobodno.
        val b = request(Offset(60f, 0f), centered = true)
        assertEquals(listOf(a.candidates[0], Offset(66f, -10f)), place(a, b))
    }

    /**
     * Teren 04.10.2026: dva natpisa sa strane, jedan iznad drugog na mapi sa severom gore - posle okreta mape za 90°
     * tačke su u istom redu, pa natpis druge, koji bi pao na natpis prve, prelazi na drugu stranu svoje tačke.
     */
    @Test
    fun rotatedMap_sideLabelsInOneRow_flipSide() {
        val a = request(Offset(0f, 0f), preferEast = true) // istočno od tačke
        val b = request(Offset(0f, -150f), preferEast = false) // zapadno od tačke, 150 px severno
        assertEquals(listOf(a.candidates[0], b.candidates[0]), place(a, b))
        // Okret za 90° (= -270°): sever je desno, b je 150 px desno od a, u istom redu - natpis b bi zapadno pao na natpis a.
        val rotated = place(a, b, rotationDeg = 90f)
        assertEquals(a.candidates[0], rotated[0])
        assertEquals("b ide na drugu stranu svoje tačke", b.candidates[1], rotated[1])
    }

    @Test
    fun otherLabelsDot_isAvoided() {
        val a = request(Offset(0f, 0f), preferEast = true)
        // Tačka druge službe 30 px istočno - natpis ide zapadno.
        assertEquals(listOf(a.candidates[1]), place(a, obstacles = listOf(Offset(30f, 0f))))
    }

    @Test
    fun noSpace_labelHidden() {
        val a = request(Offset(0f, 0f), centered = true)
        // Tačke oko a zauzimaju svako mesto kandidata b.
        val b = LabelRequest(Offset(0f, 0f), size, listOf(a.candidates[0]))
        assertNull(place(a, b)[1])
    }
}
