package com.example.ftnnavigation.campus

import com.example.ftnnavigation.graph.BuildingGraph
import com.example.ftnnavigation.graph.DggPlan
import com.example.ftnnavigation.graph.INDOOR_BUILDINGS
import com.example.ftnnavigation.graph.IndoorPlan
import com.example.ftnnavigation.graph.NbPlan
import com.example.ftnnavigation.graph.NodeType
import com.example.ftnnavigation.schedule.ScheduleData
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.hypot

/**
 * Departman za građevinarstvo i geodeziju (assets/dgg.json, tools/zgrade/build_dgg.py; evakuacioni planovi sa terena 10.11.2026)
 * spojen sa kampusom.
 */
class DggGraphTest {

    private val campus = CampusData.parse(File("src/main/assets/campus.json").readText())

    private val plans = INDOOR_BUILDINGS.associate { it.buildingId to IndoorPlan.parse(File("src/main/assets/${it.asset}").readText()) }

    private val dgg = plans.getValue(DggPlan.BUILDING_ID)

    private val graph = seedGraph(campus, plans.values.toList()).let { (nodes, edges) -> BuildingGraph(nodes, edges, campus.placements()) }

    private fun room(name: String) = checkNotNull(graph.room(name)) { name }

    private fun distance(a: String, b: String): Double {
        val (pa, pb) = listOf(a, b).map { graph.position(checkNotNull(graph.node(it)) { it }) }
        return hypot((pa.x - pb.x).toDouble(), (pa.y - pb.y).toDouble())
    }

    /** Glavni ulaz sa plana prizemlja je ulaz kampusa (istočni zid); čvora K-Z-DGG više nema. */
    @Test
    fun entrance_linkedToCampus() {
        assertEquals(DggPlan.ENTRANCE_ID, dgg.entranceId)
        assertEquals(DggPlan.ENTRANCE_ID, campus.building("DGG")!!.nodeId)
        assertEquals(DggPlan.FLOORS.toList(), dgg.floors)
        assertNull(graph.node("K-Z-DGG"))
        assertTrue(distance("K-U-DGG-1", DggPlan.ENTRANCE_ID) < 1.0)
    }

    @Test
    fun everyNodeReachableFromNbEntrance() {
        graph.nodes.filter { it.buildingId == DggPlan.BUILDING_ID }
            .forEach { assertNotNull(it.id, graph.route(NbPlan.ENTRANCE_ID, it.id)) }
    }

    /**
     * Sve LG sale iz rasporeda su ucrtane: 001, 002, 005 u prizemlju (sa plana), 105, 106, 107 na I spratu (105 upisao korisnik,
     * 106 i 107 PRETPOSTAVKA - druga učionica i amfiteatar); natpis bez "LG ".
     */
    @Test
    fun scheduleRooms_onTheirFloor() {
        val schedule = Json { ignoreUnknownKeys = true }
            .decodeFromString<ScheduleData>(File("src/main/assets/schedule.json").readText())
        val names = schedule.timetables.flatMap { t -> t.classes.map { it.room } }.filter { it.startsWith("LG ") }.toSet()
        assertEquals(setOf("LG 001", "LG 002", "LG 005", "LG 105", "LG 106", "LG 107"), names)
        for (name in names) {
            val target = checkNotNull(resolveTarget(name, graph, campus)) { name }
            assertTrue(name, !target.approximate)
            assertEquals(name, name.removePrefix("LG ").first().digitToInt(), target.node.floor)
        }
        assertEquals("105", DggPlan.label("LG 105"))
    }

    /** Oznake sa planova: P 001-005, II 201-213, III 301-312 (čajne kuhinje 214 i 313 nisu sale); I 101 i 105 od korisnika. */
    @Test
    fun roomNumbers_fromPlans() {
        val rooms = graph.nodes.filter { it.buildingId == DggPlan.BUILDING_ID && it.type == NodeType.PROSTORIJA && it.name != null }
        assertEquals(listOf(0, 1, 2, 3).associateWith { f -> listOf(5, 6, 13, 12)[f] }, rooms.groupingBy { it.floor }.eachCount().toSortedMap())
        rooms.forEach { assertEquals(it.name, it.name!!.removePrefix("LG ").first().digitToInt(), it.floor) }
        // 105 je severnija (levo na planu) od dve učionice uz istočni zid, 101 je severno od nje.
        assertTrue(room("LG 105").x < room("LG 106").x)
        assertTrue(room("LG 101").x < room("LG 105").x)
    }

    /** Stepenište i lift (u oknu stepeništa, kao u NB - korisnik 10.11.2026) na svim spratovima; toaleti na svakom spratu. */
    @Test
    fun stairsLiftAndToilets_onEveryFloor() {
        for (type in listOf(NodeType.STEPENISTE, NodeType.LIFT)) {
            val floors = graph.nodes.filter { it.buildingId == DggPlan.BUILDING_ID && it.type == type }.map { it.floor }.sorted()
            assertEquals(type.name, DggPlan.FLOORS.toList(), floors)
        }
        val toilets = graph.nodes.filter { it.buildingId == DggPlan.BUILDING_ID && it.amenity == AMENITY_TOALET }.map { it.floor }.toSet()
        assertEquals(DggPlan.FLOORS.toSet(), toilets)
    }

    /** Iz NB-a do LG 107 spolja, kroz glavni ulaz DGG-a, pa na I sprat. */
    @Test
    fun fromNb_outsideThroughMainEntrance() {
        val route = graph.route(NbPlan.ENTRANCE_ID, room("LG 107").id)!!
        val ids = route.nodes.map { it.id }
        assertTrue(ids.toString(), "K-U-DGG-1" in ids && DggPlan.ENTRANCE_ID in ids)
        assertEquals(1, route.nodes.last().floor)
    }
}
