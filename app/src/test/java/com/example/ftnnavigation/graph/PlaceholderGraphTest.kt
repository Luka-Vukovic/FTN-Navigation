package com.example.ftnnavigation.graph

import com.example.ftnnavigation.graph.PlaceholderGraph.ENTRANCE_ID
import com.example.ftnnavigation.graph.PlaceholderGraph.PASSAGE_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Podaci prizemlja: povezanost i raspored u odnosu na plan. */
class PlaceholderGraphTest {

    // Plan: 750 x 255 px fotografije, 0,1 m/px.
    private val graph = BuildingGraph(PlaceholderGraph.nodes, PlaceholderGraph.edges, FloorScale(75f, 25.5f))

    @Test
    fun roomsUniqueAndInsidePlan() {
        val names = graph.rooms.map { it.name!! }
        assertEquals(names.size, names.toSet().size)
        assertTrue(graph.nodes.all { it.floor == 0 && it.x in 0f..1f && it.y in 0f..1f })
    }

    /** Sale podrazumevanog rasporeda (SIIT, 4. godina, grupa 3) su na mapi. */
    @Test
    fun defaultScheduleRoomsPresent() {
        listOf("NTP-001", "F 315", "NTP-A", "A2", "NTP-307", "L4 (RC)", "L6 (RC)", "F 318")
            .forEach { assertNotNull(it, graph.room(it)) }
    }

    @Test
    fun everyNodeReachableFromEntrance() {
        graph.nodes.forEach { assertNotNull(it.id, graph.route(ENTRANCE_ID, it.id)) }
    }

    @Test
    fun entranceToRoom_goesThroughVestibuleHallAndDoor() {
        val route = checkNotNull(graph.route(ENTRANCE_ID, graph.room("NTP-001")!!.id))
        assertEquals(
            listOf(ENTRANCE_ID, "NB-0-PREDVORJE", "NB-0-H677", "NB-0-H660") +
                listOf(612, 596, 580, 569, 546, 525, 497, 477).map { "NB-0-H$it" } +
                listOf("NB-0-V477_379", "NB-0-NTP-001"),
            route.nodes.map { it.id },
        )
        val metres = route.nodes.zipWithNext { a, b -> Math.hypot((a.x - b.x) * 75.0, (a.y - b.y) * 25.5) }.sum()
        assertEquals(metres, route.lengthM, 1e-4)
        assertEquals(metres / 1.3, route.durationSec, 1e-4)
    }

    @Test
    fun passage_leadsIntoHall() {
        val route = checkNotNull(graph.route(PASSAGE_ID, graph.room("NTP-001")!!.id))
        assertEquals("NB-0-H435", route.nodes[1].id)
    }
}
