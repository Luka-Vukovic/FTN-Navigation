package com.example.ftnnavigation.campus

import com.example.ftnnavigation.graph.BuildingGraph
import com.example.ftnnavigation.graph.EdgeType
import com.example.ftnnavigation.graph.INDOOR_BUILDINGS
import com.example.ftnnavigation.graph.IndoorPlan
import com.example.ftnnavigation.graph.NbPlan
import com.example.ftnnavigation.graph.NodeType
import com.example.ftnnavigation.graph.NtpPlan
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
import kotlin.math.hypot

/** Unutrašnji graf Nastavnog bloka (assets/nb.json, tools/nb/build_nb.py) spojen sa kampusom kao u aplikaciji. */
class NbGraphTest {

    private val campus = CampusData.parse(File("src/main/assets/campus.json").readText())

    private val plans = INDOOR_BUILDINGS.associate { it.buildingId to IndoorPlan.parse(File("src/main/assets/${it.asset}").readText()) }

    private val nb = plans.getValue(NbPlan.BUILDING_ID)

    private val ntp = plans.getValue(NtpPlan.BUILDING_ID)

    private val graph = seedGraph(campus, plans.values.toList()).let { (nodes, edges) -> BuildingGraph(nodes, edges, campus.placements()) }

    private val nbNodes = graph.nodes.filter { it.buildingId == NbPlan.BUILDING_ID }

    /** Sale iz rasporeda koje su privremeno u NB, a nisu ucrtane (AH6A, AH7A i 012 ucrtane posle terena 01.10.2026). */
    private val notDrawn = setOf("Fizika", "Hemija", "Hemija 2")

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
                room == "O12" -> 0 // 012 (u rasporedu slovo O)
                else -> room.first().digitToInt()
            }
            assertEquals(room, floor, target.node.floor)
        }
        notDrawn.forEach { assertTrue(it, checkNotNull(resolveTarget(it, graph, campus)).approximate) }
    }

    /** "L1" iz rasporeda NIJE "L1 (RC)": to je sala iznad hodnika iza amfiteatara (stepenice naviše iz prolaza). */
    @Test
    fun l1_isNotComputerCentre() {
        val l1 = checkNotNull(resolveTarget("L1", graph, campus)).node
        assertEquals("AMF", l1.buildingId)
        assertEquals(1, l1.floor)
        val route = checkNotNull(graph.route(NbPlan.AMF_PASSAGE_ID, l1.id))
        assertTrue(route.lengthM < 40)
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

    /** Teren 01.10.2026: zastakljen prolaz NB - Kula postoji i na I spratu (evakuacioni plan). */
    @Test
    fun kulaPassage_alsoOnFirstFloor() {
        val from = checkNotNull(graph.room("101"))
        val to = checkNotNull(graph.room("Kula 101"))
        val route = checkNotNull(graph.route(from.id, to.id))
        assertTrue(route.nodes.any { it.id == "NB-1-PROLAZ" })
        assertTrue(route.nodes.all { it.floor == 1 })
    }

    /** Pored stepeništa na I-IV spratu su toaleti (FtnGO ih zove 113/110, 212/209, 316/313, 412/410). */
    @Test
    fun toiletsNextToStairs_areNotRooms() {
        listOf("113", "110", "212", "209", "316", "313", "412", "410").forEach { assertNull(it, graph.room(it)) }
    }

    /** AH6 i AH7 su podeljeni na pola; "A" polovina je bliža stepeništu. */
    @Test
    fun ah6aAndAh7a_closerToStairs() {
        val stairs = checkNotNull(graph.node("NB-4-S"))
        for (name in listOf("AH6", "AH7")) {
            val a = checkNotNull(graph.room("${name}A"))
            val b = checkNotNull(graph.room(name))
            assertEquals(4, a.floor)
            val (pa, pb, ps) = listOf(a, b, stairs).map(graph::position)
            assertTrue(name, hypot(pa.x - ps.x, pa.y - ps.y) < hypot(pb.x - ps.x, pb.y - ps.y))
        }
    }

    /** Teren 02.10.2026: 012 je između 014 i 016 (prvo, 01.10., pretpostavljeno kao desni deo 015). */
    @Test
    fun room012_between014And016() {
        val (a, b, c) = listOf("014", "012", "016").map { checkNotNull(graph.room(it)) { it } }
        assertEquals(0, b.floor)
        assertTrue(a.x < b.x && b.x < c.x)
    }
}
