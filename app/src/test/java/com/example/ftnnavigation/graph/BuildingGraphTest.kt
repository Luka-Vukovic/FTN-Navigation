package com.example.ftnnavigation.graph

import com.example.ftnnavigation.graph.PlaceholderGraph.ENTRANCE_ID
import com.example.ftnnavigation.graph.PlaceholderGraph.liftId
import com.example.ftnnavigation.graph.PlaceholderGraph.stairsId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.PriorityQueue

class BuildingGraphTest {

    // Plan: 750 x 255 px fotografije, 0,1 m/px.
    private val scale = FloorScale(widthM = 75f, heightM = 25.5f)
    private val graph = BuildingGraph(PlaceholderGraph.nodes, PlaceholderGraph.edges, scale)

    private fun routeTo(room: String, profile: RoutingProfile = RoutingProfile()): Route =
        checkNotNull(graph.route(ENTRANCE_ID, graph.room(room)!!.id, profile))

    private fun Route.uses(id: String) = nodes.any { it.id == id }

    @Test
    fun placeholder_roomNamesUniqueAndOnMatchingFloor() {
        val names = graph.rooms.map { it.name!! }
        assertEquals(names.size, names.toSet().size)
        // NTP-307 -> 3. sprat; NTP-001 -> prizemlje.
        graph.rooms.filter { it.name!!.matches(Regex("""NTP-\d.*""")) }
            .forEach { assertEquals(it.name!![4].digitToInt(), it.floor) }
        assertTrue(graph.nodes.all { it.x in 0f..1f && it.y in 0f..1f })
    }

    @Test
    fun placeholder_everyNodeReachableFromEntrance() {
        graph.nodes.forEach { assertNotNull(it.id, graph.route(ENTRANCE_ID, it.id)) }
    }

    @Test
    fun sameFloor_followsCorridorAndTimeIsDistanceOverSpeed() {
        val route = routeTo("NTP-001")
        // Glavni ulaz -> predvorje -> hol (sleva od stepeništa) -> vrata -> sala.
        assertEquals(
            listOf(ENTRANCE_ID, "NB-0-PREDVORJE", "NB-0-H677", "NB-0-H660") +
                listOf(612, 596, 580, 569, 546, 525, 497, 477).map { "NB-0-H$it" } +
                listOf("NB-0-V477_379", "NB-0-NTP-001"),
            route.nodes.map { it.id },
        )
        val metres = route.nodes.zipWithNext { a, b ->
            Math.hypot((a.x - b.x) * 75.0, (a.y - b.y) * 25.5)
        }.sum()
        assertEquals(metres / 1.3, route.durationSec, 1e-4)
    }

    @Test
    fun passage_leadsIntoHall() {
        val route = checkNotNull(graph.route(PlaceholderGraph.PASSAGE_ID, graph.room("NTP-001")!!.id))
        assertEquals("NB-0-H435", route.nodes[1].id)
    }

    @Test
    fun fewFloorsUp_takesStairs_manyFloors_takesLift() {
        // 3 sprata: stepenice 3 * 18 s < lift 45 + 3 * 4 s.
        val toThird = routeTo("NTP-307")
        assertTrue(toThird.uses(stairsId(1)))
        assertFalse(toThird.uses(liftId(0)))
        // 5 spratova: stepenice 90 s > lift 65 s.
        val toFifth = routeTo("NTP-504")
        assertTrue(toFifth.uses(liftId(0)) && toFifth.uses(liftId(5)))
        assertFalse(toFifth.uses(stairsId(1)))
    }

    @Test
    fun avoidStairs_usesLift() {
        val route = routeTo("NTP-307", RoutingProfile(avoidStairs = true))
        assertTrue(route.uses(liftId(0)) && route.uses(liftId(3)))
        assertTrue(route.nodes.none { it.type == NodeType.STEPENISTE })
    }

    @Test
    fun goingDown_isFasterThanGoingUp() {
        val a = graph.room("NTP-001")!!.id
        val b = graph.room("NTP-222")!!.id
        assertTrue(graph.route(b, a)!!.durationSec < graph.route(a, b)!!.durationSec)
    }

    @Test
    fun crowd_slowsWalkingButNotLiftWait() {
        val normal = routeTo("NTP-001").durationSec
        assertEquals(normal * 2, routeTo("NTP-001", RoutingProfile(crowdFactor = 2.0)).durationSec, 1e-6)
        // U gužvi se isplati lift i na 3. sprat.
        assertTrue(routeTo("NTP-307", RoutingProfile(crowdFactor = 2.0)).uses(liftId(3)))
    }

    @Test
    fun unknownNode_returnsNull() {
        assertNull(graph.route(ENTRANCE_ID, "nema"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun crowdBelowOne_rejected() {
        RoutingProfile(crowdFactor = 0.5)
    }

    @Test(expected = IllegalArgumentException::class)
    fun stairsAcrossTwoFloors_rejected() {
        BuildingGraph(PlaceholderGraph.nodes, listOf(Edge(stairsId(0), stairsId(2), EdgeType.STEPENICE)), scale)
    }

    /** A* mora da nađe isto vreme kao Dijkstra (bez heuristike) za svaki par čvorova. */
    @Test
    fun aStar_matchesDijkstra() {
        val profiles = listOf(RoutingProfile(), RoutingProfile(avoidStairs = true), RoutingProfile(crowdFactor = 1.7))
        for (profile in profiles) {
            for (from in graph.nodes) {
                val expected = dijkstra(from.id, profile)
                for (to in graph.nodes) {
                    assertEquals("${from.id} -> ${to.id}", expected.getValue(to.id), graph.route(from.id, to.id, profile)!!.durationSec, 1e-9)
                }
            }
        }
    }

    private fun dijkstra(fromId: String, profile: RoutingProfile): Map<String, Double> {
        val dist = hashMapOf(fromId to 0.0)
        val queue = PriorityQueue<Pair<Double, String>>(compareBy { it.first })
        queue += 0.0 to fromId
        while (queue.isNotEmpty()) {
            val (d, id) = queue.poll()!!
            if (d > dist.getValue(id)) continue
            for ((next, type) in graph.neighbors(id)) {
                val nd = d + (graph.cost(graph.node(id)!!, next, type, profile) ?: continue)
                if (nd < (dist[next.id] ?: Double.POSITIVE_INFINITY)) {
                    dist[next.id] = nd
                    queue += nd to next.id
                }
            }
        }
        return dist
    }
}
