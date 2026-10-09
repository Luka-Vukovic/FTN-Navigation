package com.example.ftnnavigation.campus

import com.example.ftnnavigation.graph.AmfPlan
import com.example.ftnnavigation.graph.BuildingGraph
import com.example.ftnnavigation.graph.EdgeType
import com.example.ftnnavigation.graph.INDOOR_BUILDINGS
import com.example.ftnnavigation.graph.IndoorPlan
import com.example.ftnnavigation.graph.ItcPlan
import com.example.ftnnavigation.graph.NbPlan
import com.example.ftnnavigation.graph.NodeType
import com.example.ftnnavigation.graph.RoutingProfile
import com.example.ftnnavigation.graph.routeOrFallback
import com.example.ftnnavigation.schedule.ScheduleData
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.hypot

/** ITC (assets/itc.json, tools/zgrade/build_itc.py; evakuacioni planovi sa terena 09.10.2026) spojen sa kampusom. */
class ItcGraphTest {

    private val campus = CampusData.parse(File("src/main/assets/campus.json").readText())

    private val plans = INDOOR_BUILDINGS.associate { it.buildingId to IndoorPlan.parse(File("src/main/assets/${it.asset}").readText()) }

    private val itc = plans.getValue(ItcPlan.BUILDING_ID)

    private val graph = seedGraph(campus, plans.values.toList()).let { (nodes, edges) -> BuildingGraph(nodes, edges, campus.placements()) }

    private fun room(name: String) = checkNotNull(graph.room(name)) { name }

    private fun distance(a: String, b: String): Double {
        val (pa, pb) = listOf(a, b).map { graph.position(checkNotNull(graph.node(it)) { it }) }
        return hypot((pa.x - pb.x).toDouble(), (pa.y - pb.y).toDouble())
    }

    /** Glavni ulaz na severu (OSM ulaz) i prolaz iz prizemlja u Amfiteatre; čvora K-Z-ITC više nema. */
    @Test
    fun entranceAndPassage_linkedToCampus() {
        assertEquals(ItcPlan.ENTRANCE_ID, itc.entranceId)
        assertEquals(ItcPlan.ENTRANCE_ID, campus.building("ITC")!!.nodeId)
        assertEquals(ItcPlan.FLOORS.toList(), itc.floors)
        assertNull(graph.node("K-Z-ITC"))
        assertTrue(distance("K-U-ITC-1", ItcPlan.ENTRANCE_ID) < 5.0)
        val passage = graph.neighbors("K-P-ITC-AMF").map { it.first.id }
        assertTrue(passage.toString(), passage.containsAll(listOf("ITC-0-PROLAZ-AMF", "AMF-0-PROLAZ-ITC")))
        assertTrue(distance("ITC-0-PROLAZ-AMF", "AMF-0-PROLAZ-ITC") < 5.0)
    }

    @Test
    fun everyNodeReachableFromNbEntrance() {
        graph.nodes.filter { it.buildingId == ItcPlan.BUILDING_ID }
            .forEach { assertNotNull(it.id, graph.route(NbPlan.ENTRANCE_ID, it.id)) }
    }

    /** Sve ITC sale iz rasporeda su ucrtane; ITCA1 je amfiteatar na III spratu (korisnik, 09.10.2026). */
    @Test
    fun scheduleRooms_drawn_itca1OnThirdFloor() {
        val schedule = Json { ignoreUnknownKeys = true }
            .decodeFromString<ScheduleData>(File("src/main/assets/schedule.json").readText())
        val names = schedule.timetables.flatMap { t -> t.classes.map { it.room } }.filter { it.startsWith("ITC") }.toSet()
        assertEquals(setOf("ITC03", "ITC04", "ITCA1", "ITCS-01", "ITCS-03", "ITCS-RC"), names)
        names.forEach { assertEquals(it, ItcPlan.BUILDING_ID, room(it).buildingId) }
        assertEquals(3, room("ITCA1").floor)
    }

    /** Lift ide od prizemlja do III sprata (na IV je mašinska prostorija), stepenište do IV. */
    @Test
    fun lift_groundToThird_stairsToFourth() {
        val liftFloors = graph.nodes.filter { it.buildingId == ItcPlan.BUILDING_ID && it.type == NodeType.LIFT }.map { it.floor }
        assertEquals(listOf(0, 1, 2, 3), liftFloors.sorted())
        val stairFloors = graph.nodes.filter { it.buildingId == ItcPlan.BUILDING_ID && it.type == NodeType.STEPENISTE }.map { it.floor }
        assertEquals(ItcPlan.FLOORS.toList(), stairFloors.sorted())
        val noStairs = routeOrFallback(RoutingProfile(avoidStairs = true)) { graph.route(ItcPlan.ENTRANCE_ID, room("ITCA1").id, it) }!!
        assertTrue(noStairs.nodes.none { it.type == NodeType.STEPENISTE })
        // Na IV sprat samo stepenicama - ruta uz napomenu.
        assertTrue(routeOrFallback(RoutingProfile(avoidStairs = true)) { graph.route(ItcPlan.ENTRANCE_ID, room("ITCS-RC").id, it) }!!.fallback)
    }

    /** Iz Amfiteatara u ITC se ide iznutra, kroz prolaz u prizemlju (ne napolje). */
    @Test
    fun fromAmf_throughPassage() {
        val route = graph.route(AmfPlan.ENTRANCE_ID, room("ITCA1").id)!!
        val ids = route.nodes.map { it.id }
        assertTrue(ids.toString(), "ITC-0-PROLAZ-AMF" in ids)
        assertTrue(route.nodes.none { it.type == NodeType.STAZA })
    }

    @Test
    fun toilets_onGroundToThird() {
        val toilets = graph.nodes.filter { it.buildingId == ItcPlan.BUILDING_ID && it.amenity == AMENITY_TOALET }
        assertEquals(mapOf(0 to 2, 1 to 2, 2 to 2, 3 to 2), toilets.groupingBy { it.floor }.eachCount().toSortedMap())
        assertTrue(graph.edges.none { it.type == EdgeType.LIFT && (it.fromId == "ITC-4-L" || it.toId == "ITC-4-L") })
    }
}
