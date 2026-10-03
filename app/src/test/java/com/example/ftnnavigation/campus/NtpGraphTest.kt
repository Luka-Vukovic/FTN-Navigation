package com.example.ftnnavigation.campus

import com.example.ftnnavigation.graph.BuildingGraph
import com.example.ftnnavigation.graph.EdgeType
import com.example.ftnnavigation.graph.INDOOR_BUILDINGS
import com.example.ftnnavigation.graph.IndoorPlan
import com.example.ftnnavigation.graph.MATCH_TOLERANCE_M
import com.example.ftnnavigation.graph.MapMatcher
import com.example.ftnnavigation.graph.NbPlan
import com.example.ftnnavigation.graph.NodeType
import com.example.ftnnavigation.graph.NtpPlan
import com.example.ftnnavigation.graph.RoutingProfile
import com.example.ftnnavigation.schedule.ScheduleData
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.hypot

/** Unutrašnji graf NTP-a (assets/ntp.json, tools/ntp/build_ntp.py) spojen sa kampusom kao u aplikaciji. */
class NtpGraphTest {

    private val campus = CampusData.parse(File("src/main/assets/campus.json").readText())

    private val plans = INDOOR_BUILDINGS.associate { it.buildingId to IndoorPlan.parse(File("src/main/assets/${it.asset}").readText()) }

    private val nb = plans.getValue(NbPlan.BUILDING_ID)

    private val ntp = plans.getValue(NtpPlan.BUILDING_ID)

    private val graph = seedGraph(campus, plans.values.toList()).let { (nodes, edges) -> BuildingGraph(nodes, edges, campus.placements()) }

    private val ntpNodes = graph.nodes.filter { it.buildingId == NtpPlan.BUILDING_ID }

    @Test
    fun mainEntrance_isBuildingNode() {
        assertEquals(NtpPlan.ENTRANCE_ID, ntp.entranceId)
        assertEquals(NtpPlan.ENTRANCE_ID, campus.building(NtpPlan.BUILDING_ID)!!.nodeId)
        assertEquals(NodeType.ULAZ, graph.node(NtpPlan.ENTRANCE_ID)!!.type)
        // Zgrada sa unutrašnjim grafom nema čvor ZGRADA.
        assertEquals(null, graph.node("K-Z-NTP"))
    }

    @Test
    fun everyFloorReachableFromMainEntrance() {
        assertEquals((0..5).toSet(), ntpNodes.map { it.floor }.toSet())
        ntpNodes.forEach { assertNotNull(it.id, graph.route(NtpPlan.ENTRANCE_ID, it.id)) }
        assertTrue(ntpNodes.all { it.x in 0f..1f && it.y in 0f..1f })
    }

    /** Liftovi su na svakom spratu na istom mestu (tako su poravnati planovi). */
    @Test
    fun liftsStackedOnEveryFloor() {
        val lifts = ntpNodes.filter { it.type == NodeType.LIFT }.groupBy { it.id.substringAfterLast('-') }
        assertEquals(setOf("L1", "L2", "L3"), lifts.keys)
        for ((name, shafts) in lifts) {
            assertEquals(name, 6, shafts.size)
            assertEquals(name, 1, shafts.map { it.x to it.y }.distinct().size)
        }
    }

    /** Ulazi NTP-a su na istom mestu kao ulazi u grafu kampusa (build_campus.py ih računa iz plana). */
    @Test
    fun campusLinks_sameSpot() {
        assertEquals(4, ntp.campusLinks.size)
        for ((campusId, entranceId) in ntp.campusLinks) {
            val a = graph.position(graph.node(campusId)!!)
            val b = graph.position(graph.node(entranceId)!!)
            assertTrue("$campusId - $entranceId", hypot(a.x - b.x, a.y - b.y) < 0.5)
        }
    }

    /** Sve NTP sale iz rasporeda su ucrtane, na spratu iz broja (NTP-307 -> 3), slovne u prizemlju. */
    @Test
    fun allScheduleRooms_onTheirFloor() {
        val schedule = Json { ignoreUnknownKeys = true }
            .decodeFromString<ScheduleData>(File("src/main/assets/schedule.json").readText())
        val rooms = schedule.timetables.flatMap { it.classes }.map { it.room }.filter { it.startsWith("NTP") }.distinct()
        assertTrue(rooms.size > 30)
        for (room in rooms) {
            val target = checkNotNull(resolveTarget(room, graph, campus)) { room }
            assertFalse(room, target.approximate)
            assertEquals(room, NtpPlan.BUILDING_ID, target.node.buildingId)
            val number = room.removePrefix("NTP-").toIntOrNull()
            assertEquals(room, number?.div(100) ?: 0, target.node.floor)
        }
        graph.rooms.filter { it.buildingId == NtpPlan.BUILDING_ID }
            .forEach { assertEquals(it.name, NtpPlan.BUILDING_ID, buildingOfRoom(it.name!!)) }
    }

    /**
     * Oznake sa vrata (teren 03.10.2026): red kancelarija uz donji zid broji unazad sleva nadesno (I: 138 ... 125,
     * IV: 433 ... 420), sobe sa više brojeva su jedna sala sa aliasima (119-123, 221/222), a podeljene dve sale.
     */
    @Test
    fun fieldLabels_fromDoors() {
        for ((floor, first) in mapOf(1 to 138, 2 to 238, 3 to 337, 4 to 433)) {
            val row = (0 until 14).map { checkNotNull(graph.room("NTP-${first - it}")) { "NTP-${first - it}" } }
            assertTrue(row.all { it.floor == floor })
            assertEquals("sprat $floor", row.sortedBy { it.x }, row)
            assertEquals(1, row.map { it.y }.distinct().size)
        }
        assertEquals(graph.room("NTP-119")!!.id, resolveTarget("NTP-120", graph, campus)!!.node.id)
        assertEquals(graph.room("NTP-221")!!.id, resolveTarget("NTP-222", graph, campus)!!.node.id)
        val (a, b) = listOf("NTP-115", "NTP-116").map { checkNotNull(graph.room(it)) }
        assertTrue(a.id != b.id && a.floor == 1 && b.floor == 1)
    }

    @Test
    fun routeToThirdFloor_changesFloorsOnce() {
        val route = checkNotNull(graph.route(NtpPlan.ENTRANCE_ID, graph.room("NTP-307")!!.id))
        assertEquals(3, route.nodes.last().floor)
        // Na sprat se ide jednim stepeništem ili liftom, bez vraćanja.
        val floors = route.nodes.map { it.floor }
        assertEquals(floors.sorted(), floors)
        assertTrue(route.nodes.any { it.type == NodeType.STEPENISTE || it.type == NodeType.LIFT })
        // Tri sprata stepenicama (18 s) su u vremenu, a dužina je samo hod po spratovima.
        assertTrue(route.durationSec > route.lengthM / 1.3 + 3 * 17.9)
    }

    @Test
    fun avoidStairs_takesLift() {
        val route = checkNotNull(graph.route(NtpPlan.ENTRANCE_ID, graph.room("NTP-504")!!.id, RoutingProfile(avoidStairs = true)))
        assertFalse(route.nodes.any { it.type == NodeType.STEPENISTE })
        val lifts = route.nodes.filter { it.type == NodeType.LIFT }
        assertEquals(listOf(0, 5), lifts.map { it.floor })
        assertEquals(EdgeType.LIFT, graph.neighbors(lifts[0].id).first { it.first.id == lifts[1].id }.second)
    }

    /** Iz Nastavnog bloka u NTP: napolje, stazama, pa kroz jedan od ulaza NTP-a. */
    @Test
    fun nbToNtp_goesOutsideThroughNtpEntrance() {
        val route = checkNotNull(graph.route(NbPlan.ENTRANCE_ID, graph.room("NTP-208")!!.id))
        assertTrue(route.nodes.any { it.type == NodeType.STAZA })
        val entrances = ntp.campusLinks.map { it[1] }.toSet()
        assertEquals(1, route.nodes.count { it.id in entrances })
        assertEquals(2, route.nodes.last().floor)
    }

    /**
     * PDR u NTP-u (01.10.2026): na svakom spratu map-matching ima hodnike, a hod od glavnog ulaza
     * ostaje na spratu i blizu slobodne pozicije (tolerancija).
     */
    @Test
    fun pdr_mapMatchingOnEveryFloor() {
        val scale = graph.placement(NtpPlan.BUILDING_ID).scale
        for (floor in NtpPlan.floors) {
            val matcher = MapMatcher(graph, NtpPlan.BUILDING_ID, floor)
            val hall = ntpNodes.first { it.floor == floor && it.type == NodeType.HODNIK }
            val start = matcher.start(hall.x, hall.y)
            assertNotNull("sprat $floor", start)
            var position = start!!
            for (step in 1..20) position = matcher.step(position, 0.7, 0.0)
            assertEquals(floor, position.point.from.floor)
            val dx = (position.x - position.point.x) * scale.widthM
            val dy = (position.y - position.point.y) * scale.heightM
            assertTrue(hypot(dx, dy) <= MATCH_TOLERANCE_M + 1e-6)
        }
    }

    /**
     * NTP-A je u prizemlju poslovnog dela (teren 01.10.2026), blizu ulaza "ULAZ - FTN" sa Fruškogorske. Vrata su na
     * zidu suprotnom od najbližeg zida zgrade (teren 03.10.2026) - južnom, dalje od zida zgrade nego sala.
     */
    @Test
    fun ntpA_inBusinessPart_nearFruskogorskaEntrance() {
        val room = checkNotNull(graph.room("NTP-A"))
        assertEquals(0, room.floor)
        assertTrue(room.x in 0f..1f && room.y in 0f..1f)
        val route = checkNotNull(graph.route("K-U-NTP-4", room.id))
        assertTrue("${route.lengthM}", route.lengthM < 40)
        val door = graph.neighbors(room.id).single().first
        assertEquals(NodeType.VRATA, door.type)
        val outline = campus.building(NtpPlan.BUILDING_ID)!!
        assertTrue(outline.distanceToWallM(graph.position(door)) > outline.distanceToWallM(graph.position(room)))
    }

    /** Evakuacioni putevi se ne koriste (korisnik, 02.10.2026): terasa V sprata ne spaja hodnik i desno jezgro. */
    @Test
    fun topFloorTerrace_notUsed() {
        val route = checkNotNull(graph.route("NTP-5-T1600", checkNotNull(graph.room("NTP-504")).id))
        assertTrue(route.nodes.any { it.floor == 4 })
    }
}
