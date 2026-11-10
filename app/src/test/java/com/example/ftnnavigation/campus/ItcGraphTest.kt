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
import kotlin.math.abs
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

    /**
     * ITC sale iz rasporeda: ITCA1 je amfiteatar na III spratu (korisnik, 09.10.2026); ITC03/ITC04 su 003/004 u prizemlju (teren
     * 09.11.2026, potvrđeno 10.11.); ITCS-03 velika učionica na zapadu suterena, ITCS-RC i ITCS-01 dve učionice na istoku (RC
     * severno) - korisnik ih je upisao na fotografiju plana suterena (teren 10.11.2026).
     */
    @Test
    fun scheduleRooms_itca1OnThird_itc03And04OnGround_itcsInBasement() {
        val schedule = Json { ignoreUnknownKeys = true }
            .decodeFromString<ScheduleData>(File("src/main/assets/schedule.json").readText())
        val names = schedule.timetables.flatMap { t -> t.classes.map { it.room } }.filter { it.startsWith("ITC") }.toSet()
        assertEquals(setOf("ITC03", "ITC04", "ITCA1", "ITCS-01", "ITCS-03", "ITCS-RC"), names)
        val campus = CampusData.parse(File("src/main/assets/campus.json").readText())
        for (name in names) {
            val target = checkNotNull(resolveTarget(name, graph, campus)) { name }
            assertEquals(name, ItcPlan.BUILDING_ID, target.node.buildingId)
            assertTrue(name, !target.approximate)
        }
        assertEquals(listOf(-1, -1, -1), listOf("ITCS-01", "ITCS-03", "ITCS-RC").map { room(it).floor })
        assertTrue(room("ITCS-03").x < room("ITCS-RC").x)
        assertTrue(room("ITCS-RC").y < room("ITCS-01").y)
        // Istočne učionice suterena su ispod 003/004.
        assertTrue(abs(room("ITCS-RC").x - room("ITC 003").x) < 0.01f && abs(room("ITCS-01").y - room("ITC 004").y) < 0.01f)
        assertEquals(3, room("ITCA1").floor)
        assertEquals(room("ITC 003").id, resolveTarget("ITC03", graph, campus)!!.node.id)
        assertEquals(room("ITC 004").id, resolveTarget("ITC04", graph, campus)!!.node.id)
        assertEquals("004", ItcPlan.label("ITC 004"))
    }

    /** Oznake sa vrata (teren 09.11.2026, stranica "ITC prostorije"): I i II sever 01-05 od zapada, istočna kolona 06/07, jug 10, 09, 08. */
    @Test
    fun roomNumbers_fromDoorSigns() {
        for (floor in 1..2) {
            val north = (1..5).map { room("ITC $floor%02d".format(it)) }
            assertEquals(north, north.sortedBy { it.x })
            val south = listOf(10, 9, 8).map { room("ITC $floor%02d".format(it)) }
            assertEquals(south, south.sortedBy { it.x })
            assertTrue(room("ITC ${floor}06").y < room("ITC ${floor}07").y)
            assertTrue(room("ITC ${floor}06").x > north.last().x)
        }
        val third = listOf("ITC 301", "ITC 302", "ITC 303", "ITC 304").map(::room)
        assertEquals(third, third.sortedBy { it.x })
        assertEquals(listOf("ITC 308", "ITC 307", "ITC 306").map(::room), listOf("ITC 308", "ITC 307", "ITC 306").map(::room).sortedBy { it.x })
        assertEquals(4, room("ITC 401").floor)
        assertEquals(listOf(0, 0, 0, 0), listOf("ITC 001", "ITC 002", "ITC 003", "ITC 004").map { room(it).floor })
    }

    /** Lift ide od suterena do III sprata (na IV je mašinska prostorija; na planu suterena je isto okno), stepenište do IV. */
    @Test
    fun lift_basementToThird_stairsToFourth() {
        val liftFloors = graph.nodes.filter { it.buildingId == ItcPlan.BUILDING_ID && it.type == NodeType.LIFT }.map { it.floor }
        assertEquals(listOf(-1, 0, 1, 2, 3), liftFloors.sorted())
        val stairFloors = graph.nodes.filter { it.buildingId == ItcPlan.BUILDING_ID && it.type == NodeType.STEPENISTE }.map { it.floor }
        assertEquals(ItcPlan.FLOORS.toList(), stairFloors.sorted())
        val noStairs = routeOrFallback(RoutingProfile(avoidStairs = true)) { graph.route(ItcPlan.ENTRANCE_ID, room("ITCA1").id, it) }!!
        assertTrue(noStairs.nodes.none { it.type == NodeType.STEPENISTE })
        // Na IV sprat samo stepenicama - ruta uz napomenu.
        assertTrue(routeOrFallback(RoutingProfile(avoidStairs = true)) { graph.route(ItcPlan.ENTRANCE_ID, room("ITC 401").id, it) }!!.fallback)
    }

    /**
     * Iz suterena Amfiteatara u ITC: najbrže iznutra (široko stepenište S2 u AMF-u, pa prolaz u prizemlju). Podrazumevano se S2
     * ne koristi (teren 30.10.2026, korisnik: "generalno se retko koristi, samo do INT i do toaleta"), pa ruta ide napolje.
     */
    @Test
    fun fromAmf_throughPassage() {
        val route = graph.route(AmfPlan.ENTRANCE_ID, room("ITCA1").id, RoutingProfile.FASTEST)!!
        val ids = route.nodes.map { it.id }
        assertTrue(ids.toString(), "ITC-0-PROLAZ-AMF" in ids)
        assertTrue(route.nodes.none { it.type == NodeType.STAZA })
        val usual = graph.route(AmfPlan.ENTRANCE_ID, room("ITCA1").id)!!.nodes.map { it.id }
        assertTrue(usual.toString(), usual.none { "S2" in it })
    }

    @Test
    fun toilets_basementToThird() {
        val toilets = graph.nodes.filter { it.buildingId == ItcPlan.BUILDING_ID && it.amenity == AMENITY_TOALET }
        assertEquals(mapOf(-1 to 2, 0 to 2, 1 to 2, 2 to 2, 3 to 2), toilets.groupingBy { it.floor }.eachCount().toSortedMap())
        assertTrue(graph.edges.none { it.type == EdgeType.LIFT && (it.fromId == "ITC-4-L" || it.toId == "ITC-4-L") })
    }
}
