package com.example.ftnnavigation.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.PriorityQueue

/** A* i težine, nad veštačkom zgradom sa 6 spratova (plan 60 x 24 m). */
class BuildingGraphTest {

    private val scale = FloorScale(widthM = 60f, heightM = 24f)

    /**
     * Na svakom spratu: hodnik (sredina), stepenište iznad i lift ispod njega, sala desno.
     * Ulaz levo u prizemlju. Stepenice vode na susedni sprat, lift između bilo koja dva.
     */
    private val graph: BuildingGraph = run {
        val floors = 0..5
        val nodes = mutableListOf(Node("E", "T", 0, 0.1f, 0.5f, NodeType.ULAZ))
        val edges = mutableListOf(Edge("E", "C0", EdgeType.HOD))
        for (f in floors) {
            nodes += Node("C$f", "T", f, 0.5f, 0.5f, NodeType.HODNIK)
            nodes += Node("S$f", "T", f, 0.5f, 0.4f, NodeType.STEPENISTE)
            nodes += Node("L$f", "T", f, 0.5f, 0.6f, NodeType.LIFT)
            nodes += Node("R$f", "T", f, 0.9f, 0.5f, NodeType.PROSTORIJA, "R$f")
            edges += listOf(Edge("C$f", "S$f", EdgeType.HOD), Edge("C$f", "L$f", EdgeType.HOD), Edge("C$f", "R$f", EdgeType.HOD))
            if (f + 1 in floors) edges += Edge("S$f", "S${f + 1}", EdgeType.STEPENICE)
            for (g in f + 1..floors.last) edges += Edge("L$f", "L$g", EdgeType.LIFT)
        }
        BuildingGraph(nodes, edges, scale)
    }

    private fun routeTo(room: String, profile: RoutingProfile = RoutingProfile()): Route =
        checkNotNull(graph.route("E", graph.room(room)!!.id, profile))

    private fun Route.uses(id: String) = nodes.any { it.id == id }

    @Test
    fun sameFloor_timeIsDistanceOverSpeed() {
        val route = routeTo("R0")
        assertEquals(listOf("E", "C0", "R0"), route.nodes.map { it.id })
        // 0,4 + 0,4 plana po širini = 48 m.
        assertEquals(48.0, route.lengthM, 1e-4)
        assertEquals(48.0 / 1.3, route.durationSec, 1e-4)
    }

    @Test
    fun fewFloorsUp_takesStairs_manyFloors_takesLift() {
        // 3 sprata: stepenice 3 * 18 s < lift 45 + 3 * 4 s.
        val toThird = routeTo("R3")
        assertTrue(toThird.uses("S1"))
        assertFalse(toThird.uses("L0"))
        // 5 spratova: stepenice 90 s > lift 65 s.
        val toFifth = routeTo("R5")
        assertTrue(toFifth.uses("L0") && toFifth.uses("L5"))
        assertFalse(toFifth.uses("S1"))
    }

    @Test
    fun avoidStairs_usesLift() {
        val route = routeTo("R3", RoutingProfile(avoidStairs = true))
        assertTrue(route.uses("L0") && route.uses("L3"))
        assertTrue(route.nodes.none { it.type == NodeType.STEPENISTE })
    }

    @Test
    fun goingDown_isFasterThanGoingUp() {
        assertTrue(graph.route("R2", "R0")!!.durationSec < graph.route("R0", "R2")!!.durationSec)
    }

    @Test
    fun crowd_slowsWalkingButNotLiftWait() {
        val normal = routeTo("R0").durationSec
        assertEquals(normal * 2, routeTo("R0", RoutingProfile(crowdFactor = 2.0)).durationSec, 1e-6)
        // U gužvi se isplati lift i na 3. sprat.
        assertTrue(routeTo("R3", RoutingProfile(crowdFactor = 2.0)).uses("L3"))
    }

    @Test
    fun routeFrom_walksToNearestNodeFirst() {
        // Tačka 6 m levo od hodnika C0 -> najbliži je C0, pa još 24 m do sale.
        val route = checkNotNull(graph.routeFrom(0, 0.4f, 0.5f, "R0"))
        assertEquals("C0", route.nodes.first().id)
        assertEquals(30.0, route.lengthM, 1e-4)
        assertEquals(30.0 / 1.3, route.durationSec, 1e-4)
        assertEquals("S2", graph.nearestNode(2, 0.5f, 0.35f)!!.id)
        assertNull(graph.nearestNode(9, 0.5f, 0.5f))
    }

    @Test
    fun unknownNode_returnsNull() {
        assertNull(graph.route("E", "nema"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun crowdBelowOne_rejected() {
        RoutingProfile(crowdFactor = 0.5)
    }

    @Test(expected = IllegalArgumentException::class)
    fun stairsAcrossTwoFloors_rejected() {
        BuildingGraph(graph.nodes, listOf(Edge("S0", "S2", EdgeType.STEPENICE)), scale)
    }

    /** A* mora da nađe isto vreme kao Dijkstra (bez heuristike) za svaki par čvorova. */
    @Test
    fun aStar_matchesDijkstra() {
        val placeholder = BuildingGraph(PlaceholderGraph.nodes, PlaceholderGraph.edges, scale)
        val profiles = listOf(RoutingProfile(), RoutingProfile(avoidStairs = true), RoutingProfile(crowdFactor = 1.7))
        for (g in listOf(graph, placeholder)) {
            for (profile in profiles) {
                for (from in g.nodes) {
                    val expected = dijkstra(g, from.id, profile)
                    for (to in g.nodes) {
                        val actual = g.route(from.id, to.id, profile)?.durationSec
                        if (expected[to.id] == null) assertNull(actual)
                        else assertEquals("${from.id} -> ${to.id}", expected.getValue(to.id), actual!!, 1e-9)
                    }
                }
            }
        }
    }

    private fun dijkstra(g: BuildingGraph, fromId: String, profile: RoutingProfile): Map<String, Double> {
        val dist = hashMapOf(fromId to 0.0)
        val queue = PriorityQueue<Pair<Double, String>>(compareBy { it.first })
        queue += 0.0 to fromId
        while (queue.isNotEmpty()) {
            val (d, id) = queue.poll()!!
            if (d > dist.getValue(id)) continue
            for ((next, type) in g.neighbors(id)) {
                val nd = d + (g.cost(g.node(id)!!, next, type, profile) ?: continue)
                if (nd < (dist[next.id] ?: Double.POSITIVE_INFINITY)) {
                    dist[next.id] = nd
                    queue += nd to next.id
                }
            }
        }
        return dist
    }
}
