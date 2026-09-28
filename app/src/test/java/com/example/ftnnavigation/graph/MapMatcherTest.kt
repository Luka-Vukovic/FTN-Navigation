package com.example.ftnnavigation.graph

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** Map-matching nad veštačkim spratom 100 x 100 m (0,01 plana = 1 m). */
class MapMatcherTest {

    /**
     * Glavni hodnik A-B-C (y = 50 m), iz B ogranak na sever do D (vrata). Paralelni hodnik
     * P1-P2 je 3 m južnije, iza zida: sa glavnim je povezan tek na istočnom kraju (C-P2).
     */
    private val graph: BuildingGraph = run {
        fun node(id: String, x: Float, y: Float) = Node(id, "T", 0, x, y, NodeType.HODNIK)
        val nodes = listOf(
            node("A", 0.1f, 0.5f), node("B", 0.5f, 0.5f), node("C", 0.9f, 0.5f), node("D", 0.5f, 0.2f),
            node("P1", 0.1f, 0.53f), node("P2", 0.9f, 0.53f),
        )
        val edges = listOf("A" to "B", "B" to "C", "B" to "D", "P1" to "P2", "C" to "P2")
            .map { (a, b) -> Edge(a, b, EdgeType.HOD) }
        BuildingGraph(nodes, edges, FloorScale(100f, 100f))
    }

    private val matcher = MapMatcher(graph, "T", 0)

    /** [steps] koraka od 0,7 m u smeru [headingDeg] (0 = gore/sever na planu, u smeru kazaljke). */
    private fun MatchedPosition.walk(headingDeg: Double, steps: Int): MatchedPosition {
        val rad = Math.toRadians(headingDeg)
        return (1..steps).fold(this) { pos, _ -> matcher.step(pos, 0.7 * sin(rad), -0.7 * cos(rad)) }
    }

    private fun MatchedPosition.assertOn(from: String, to: String, x: Float, y: Float) {
        assertEquals(from to to, point.from.id to point.to.id)
        assertEquals(x, point.x, 1e-4f)
        assertEquals(y, point.y, 1e-4f)
    }

    @Test
    fun start_snapsToNearestEdge() {
        val start = checkNotNull(matcher.start(0.3f, 0.51f))
        start.assertOn("A", "B", 0.3f, 0.5f)
        // Slobodna pozicija ostaje gde je postavljena (1 m od ivice, u toleranciji).
        assertEquals(0.51f, start.y, 1e-6f)
    }

    @Test
    fun compassDrift_staysInCorridor_doesNotJumpThroughWall() {
        // Kompas vuče 10° na jug: čist PDR bi posle 20 koraka bio 2,4 m južno od hodnika, bliže
        // paralelnom hodniku (3 m) nego svom - ali on se po grafu ne dostiže.
        val end = checkNotNull(matcher.start(0.2f, 0.5f)).walk(headingDeg = 100.0, steps = 20)
        val alongM = 20 * 0.7 * cos(Math.toRadians(10.0))
        end.assertOn("A", "B", (0.2 + alongM / 100).toFloat(), 0.5f)
        // Odstupanje od ivice je ograničeno tolerancijom.
        assertEquals(0.5f + (MATCH_TOLERANCE_M / 100).toFloat(), end.y, 1e-4f)
    }

    @Test
    fun turnIntoBranch_keepsLateralSteps() {
        // 1 m pre račvanja, pa 10 koraka na sever: prvi korak ostaje u hodniku (ogranak je dalje
        // od 1 m), drugi prelazi na ogranak (1 m < 1,4 m); ukupno 7 m severno od B.
        val end = checkNotNull(matcher.start(0.49f, 0.5f)).walk(headingDeg = 0.0, steps = 10)
        end.assertOn("B", "D", 0.5f, 0.43f)
    }

    @Test
    fun walkingPastCorridorEnd_stopsAtEndNode() {
        val end = checkNotNull(matcher.start(0.12f, 0.5f)).walk(headingDeg = 270.0, steps = 10)
        end.assertOn("A", "B", 0.1f, 0.5f)
    }

    @Test
    fun routeFromEdgePoint_goesThroughBetterEnd() {
        // 10 m od A, 30 m od B: do D je kroz B 30 + 30 m, a kroz A 10 + 40 + 30 m.
        val point = EdgePoint(graph.node("A")!!, graph.node("B")!!, 0.25)
        val route = checkNotNull(graph.routeFrom(point, "D"))
        assertEquals(listOf("B", "D"), route.nodes.map { it.id })
        assertEquals(60.0, route.lengthM, 1e-4)
        assertEquals(60.0 / 1.3, route.durationSec, 1e-4)
        // Do A je bliže nazad.
        assertEquals(listOf("A"), graph.routeFrom(point, "A")!!.nodes.map { it.id })
    }
}
