package com.example.ftnnavigation.campus

import com.example.ftnnavigation.graph.BuildingGraph
import com.example.ftnnavigation.graph.EdgeType
import com.example.ftnnavigation.graph.IndoorPlan
import com.example.ftnnavigation.graph.NbPlan
import com.example.ftnnavigation.graph.NodeType
import com.example.ftnnavigation.graph.RoutingProfile
import com.example.ftnnavigation.schedule.ScheduleData
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Unutrašnji graf Nastavnog bloka (assets/nb.json, tools/nb/build_nb.py) spojen sa kampusom kao u aplikaciji. */
class NbGraphTest {

    private val campus = CampusData.parse(File("src/main/assets/campus.json").readText())

    private val nb = IndoorPlan.parse(File("src/main/assets/nb.json").readText())

    private val ntp = IndoorPlan.parse(File("src/main/assets/ntp.json").readText())

    private val graph = seedGraph(campus, nb, ntp).let { (nodes, edges) -> BuildingGraph(nodes, edges, campus.placements()) }

    private val nbNodes = graph.nodes.filter { it.buildingId == NbPlan.BUILDING_ID }

    /** Sale iz rasporeda za koje se zna da su u NB, ali ih nema na snimcima FtnGO-a. */
    private val notDrawn = setOf("AH6A", "AH7A", "O12", "Fizika", "Hemija", "Hemija 2")

    @Test
    fun entranceAndPassages_areConstants() {
        assertEquals(NbPlan.ENTRANCE_ID, nb.entranceId)
        assertEquals(NbPlan.ENTRANCE_ID, campus.building(NbPlan.BUILDING_ID)!!.nodeId)
        assertEquals(NodeType.ULAZ, graph.node(NbPlan.ENTRANCE_ID)!!.type)
        assertEquals(NodeType.PROLAZ, graph.node(NbPlan.PASSAGE_ID)!!.type)
        assertEquals(NodeType.PROLAZ, graph.node(NbPlan.AMF_PASSAGE_ID)!!.type)
        assertNull(graph.node("K-Z-NB"))
    }

    @Test
    fun roomsUnique_nodesInsidePlan_allFloorsPresent() {
        val names = nbNodes.filter { it.type == NodeType.PROSTORIJA }.map { it.name!! }
        assertEquals(names.size, names.toSet().size)
        assertTrue(nbNodes.all { it.x in 0f..1f && it.y in 0f..1f })
        assertEquals(NbPlan.FLOORS.toList(), nb.floors)
        assertEquals(NbPlan.FLOORS.toSet(), nbNodes.map { it.floor }.toSet())
    }

    @Test
    fun everyNodeReachableFromMainEntrance() {
        nbNodes.forEach { assertNotNull(it.id, graph.route(NbPlan.ENTRANCE_ID, it.id)) }
    }

    /** Sale NB iz rasporeda su ucrtane, na spratu iz oznake: 312 -> 3, L… (RC) -> 3, AH… -> 4, AH9/AH-CRT -> 5. */
    @Test
    fun scheduleRooms_onTheirFloor() {
        val schedule = Json { ignoreUnknownKeys = true }
            .decodeFromString<ScheduleData>(File("src/main/assets/schedule.json").readText())
        val rooms = schedule.timetables.flatMap { it.classes }.map { it.room }.distinct()
            .filter { buildingOfRoom(it) == NbPlan.BUILDING_ID && it !in notDrawn }
        assertTrue(rooms.size > 40)
        for (room in rooms) {
            val target = checkNotNull(resolveTarget(room, graph, campus)) { room }
            assertFalse(room, target.approximate)
            assertEquals(room, NbPlan.BUILDING_ID, target.node.buildingId)
            val floor = when {
                room == "AH9" || room == "AH-CRT" -> 5
                room.startsWith("AH") -> 4
                room.startsWith("L") -> 3
                else -> room.first().digitToInt()
            }
            assertEquals(room, floor, target.node.floor)
        }
        notDrawn.forEach { assertTrue(it, checkNotNull(resolveTarget(it, graph, campus)).approximate) }
    }

    /** "L1" iz rasporeda je ista sala kao "L1 (RC)". */
    @Test
    fun l1Alias_resolvesToComputerLab() {
        assertEquals(graph.room("L1 (RC)"), resolveTarget("L1", graph, campus)?.node)
    }

    /** 204/204A, 205/205A, 208/208A: jedna učionica sa dva ulaza; oznaka sa A vodi u istu salu. */
    @Test
    fun twoEntranceRooms_areOneRoom() {
        for (room in listOf("204", "205", "208")) {
            val node = graph.room(room)!!
            assertNull(graph.room("${room}A"))
            assertEquals(node, resolveTarget("${room}A", graph, campus)?.node)
            assertEquals(room, 2, graph.neighbors(node.id).count { (n, _) -> n.type == NodeType.VRATA })
        }
    }

    @Test
    fun routeToThirdFloor_changesFloorsOnce() {
        val route = checkNotNull(graph.route(NbPlan.ENTRANCE_ID, graph.room("312")!!.id))
        assertEquals(3, route.nodes.last().floor)
        val floors = route.nodes.map { it.floor }
        assertEquals(floors.sorted(), floors)
    }

    @Test
    fun avoidStairs_takesLift() {
        val route = checkNotNull(graph.route(NbPlan.ENTRANCE_ID, graph.room("AH3")!!.id, RoutingProfile(avoidStairs = true)))
        assertFalse(route.nodes.any { it.type == NodeType.STEPENISTE })
        val lifts = route.nodes.filter { it.type == NodeType.LIFT }
        assertEquals(listOf(0, 4), lifts.map { it.floor })
        assertEquals(EdgeType.LIFT, graph.neighbors(lifts[0].id).first { it.first.id == lifts[1].id }.second)
    }

    /** V sprat (potkrovlje) je samo stepenicama: glavnim ili spiralnim iz hodnika IV sprata. */
    @Test
    fun topFloor_onlyByStairs() {
        val route = checkNotNull(graph.route(NbPlan.ENTRANCE_ID, graph.room("AH9")!!.id))
        assertTrue(route.nodes.any { it.type == NodeType.STEPENISTE && it.floor == 5 })
        assertNull(graph.route(NbPlan.ENTRANCE_ID, graph.room("AH9")!!.id, RoutingProfile(avoidStairs = true)))
    }

    /** Suteren: stepeništem iz prizemlja. */
    @Test
    fun basement_reachable() {
        val route = checkNotNull(graph.route(NbPlan.ENTRANCE_ID, graph.room("P03")!!.id))
        assertEquals(-1, route.nodes.last().floor)
    }

    /** Iz Kule (spojni prolaz) do sale na I spratu: kroz hol prizemlja pa stepeništem. */
    @Test
    fun passageFromKula_leadsIntoHall() {
        val route = checkNotNull(graph.route(NbPlan.PASSAGE_ID, graph.room("101")!!.id))
        assertEquals(0, route.nodes[1].floor)
        assertEquals(NodeType.HODNIK, route.nodes[1].type)
        assertEquals(1, route.nodes.last().floor)
    }
}
