package com.example.ftnnavigation.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
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
    fun avoidLift_usesStairsEvenWhereLiftIsFaster() {
        // Na 5. sprat je lift brži (65 s naspram 90 s) - bez lifta ipak stepenicama.
        val route = routeTo("R5", RoutingProfile(avoidLift = true))
        assertTrue((0..5).all { route.uses("S$it") })
        assertTrue(route.nodes.none { it.type == NodeType.LIFT })
    }

    @Test(expected = IllegalArgumentException::class)
    fun avoidStairsAndLift_rejected() {
        RoutingProfile(avoidStairs = true, avoidLift = true)
    }

    @Test
    fun routeOrFallback_withoutAvoidanceOnlyWhenNoOtherWay() {
        // Lift samo do 3. sprata: bez stepenica na 3. liftom, na 5. nema puta -> stepenicama uz napomenu.
        val shortLift = BuildingGraph(graph.nodes, graph.edges.filterNot { it.type == EdgeType.LIFT && (it.toId == "L4" || it.toId == "L5") }, scale)
        val noStairs = RoutingProfile(avoidStairs = true)
        val toThird = checkNotNull(routeOrFallback(noStairs) { shortLift.route("E", "R3", it) })
        assertFalse(toThird.fallback)
        assertTrue(toThird.uses("L3"))
        val toFifth = checkNotNull(routeOrFallback(noStairs) { shortLift.route("E", "R5", it) })
        assertTrue(toFifth.fallback)
        assertTrue(toFifth.uses("S5"))
        // Bez izbegavanja nema ni napomene; nepoznat cilj ostaje bez rute.
        assertFalse(checkNotNull(routeOrFallback(RoutingProfile()) { shortLift.route("E", "R5", it) }).fallback)
        assertNull(routeOrFallback(noStairs) { shortLift.route("E", "nema", it) })
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
        val route = checkNotNull(graph.routeFrom("T", 0, 0.4f, 0.5f, "R0"))
        assertEquals("C0", route.nodes.first().id)
        assertEquals(30.0, route.lengthM, 1e-4)
        assertEquals(30.0 / 1.3, route.durationSec, 1e-4)
        assertEquals("S2", graph.nearestNode("T", 2, 0.5f, 0.35f)!!.id)
        assertNull(graph.nearestNode("T", 9, 0.5f, 0.5f))
    }

    @Test
    fun planPlacement_rotatesClockwiseAndShifts() {
        val placement = PlanPlacement(FloorScale(10f, 4f), originX = 100.0, originY = 50.0, rotationDeg = 90.0)
        // Desna ivica plana posle rotacije za 90° u smeru kazaljke gleda naniže (y raste).
        val right = placement.toMeters(1f, 0f)
        assertEquals(100.0, right.x, 1e-9)
        assertEquals(60.0, right.y, 1e-9)
        val bottom = placement.toMeters(0f, 1f)
        assertEquals(96.0, bottom.x, 1e-9)
        assertEquals(50.0, bottom.y, 1e-9)
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
        // Pravi Nastavni blok (7 nivoa, bez veza sa kampusom); polazi se sa svakog 5. čvora (brzina).
        val nb = IndoorPlan.parse(File("src/main/assets/nb.json").readText())
        val nbIds = nb.nodes.map { it.id }.toSet()
        val nbGraph = BuildingGraph(nb.graphNodes(), nb.graphEdges().filter { it.fromId in nbIds && it.toId in nbIds }, FloorScale(76f, 27.9f))
        val profiles = listOf(
            RoutingProfile(), RoutingProfile(avoidStairs = true), RoutingProfile(avoidLift = true), RoutingProfile(crowdFactor = 1.7),
            RoutingProfile(buildingCrowd = mapOf("T" to 1.3, "NB" to 1.25)),
        )
        for ((g, step) in listOf(graph to 1, nbGraph to 5)) {
            for (profile in profiles) {
                for (from in g.nodes.filterIndexed { i, _ -> i % step == 0 }) {
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

    /**
     * Dva jednako duga puta od S do T: kroz zgradu X i kroz zgradu Y. Gužva u jednoj zgradi vodi rutu kroz drugu; A*
     * ostaje tačan (faktori >= 1 - heuristika vazdušnom linijom bez gužve i dalje ne precenjuje).
     */
    @Test
    fun buildingCrowd_routeAvoidsCrowdedBuilding() {
        val nodes = listOf(
            Node("S", "Z", 0, 0.1f, 0.5f, NodeType.HODNIK),
            Node("X", "X", 0, 0.5f, 0.2f, NodeType.HODNIK),
            Node("Y", "Y", 0, 0.5f, 0.8f, NodeType.HODNIK),
            Node("T", "Z", 0, 0.9f, 0.5f, NodeType.HODNIK),
        )
        val edges = listOf("S" to "X", "X" to "T", "S" to "Y", "Y" to "T").map { (a, b) -> Edge(a, b, EdgeType.HOD) }
        val g = BuildingGraph(nodes, edges, scale)
        val plain = g.route("S", "T")!!

        val crowdedX = g.route("S", "T", RoutingProfile(buildingCrowd = mapOf("X" to 1.3)))!!
        assertTrue(crowdedX.uses("Y"))
        assertEquals(plain.durationSec, crowdedX.durationSec, 1e-5) // Float koordinate: putevi jednaki do ~1e-7 s

        val crowdedBoth = g.route("S", "T", RoutingProfile(buildingCrowd = mapOf("X" to 1.3, "Y" to 1.2)))!!
        assertTrue(crowdedBoth.uses("Y"))
        assertEquals(plain.durationSec * 1.2, crowdedBoth.durationSec, 1e-5)
    }

    @Test(expected = IllegalArgumentException::class)
    fun buildingCrowd_belowOne_rejected() {
        RoutingProfile(buildingCrowd = mapOf("NB" to 0.9))
    }

    private fun dijkstra(g: BuildingGraph, fromId: String, profile: RoutingProfile): Map<String, Double> {
        val dist = hashMapOf(fromId to 0.0)
        val queue = PriorityQueue<Pair<Double, String>>(compareBy { it.first })
        queue += 0.0 to fromId
        while (queue.isNotEmpty()) {
            val (d, id) = queue.poll()!!
            if (d > dist.getValue(id)) continue
            // Kao u A*: sala nije usputni čvor.
            if (g.node(id)!!.type == NodeType.PROSTORIJA && id != fromId) continue
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
