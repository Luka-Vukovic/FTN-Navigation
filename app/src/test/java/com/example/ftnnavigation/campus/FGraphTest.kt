package com.example.ftnnavigation.campus

import com.example.ftnnavigation.graph.AmfPlan
import com.example.ftnnavigation.graph.BuildingGraph
import com.example.ftnnavigation.graph.FPlan
import com.example.ftnnavigation.graph.INDOOR_BUILDINGS
import com.example.ftnnavigation.graph.IndoorPlan
import com.example.ftnnavigation.graph.NbPlan
import com.example.ftnnavigation.graph.NodeType
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

/** F-blok (assets/f.json, tools/zgrade/build_f.py; evakuacioni planovi sa terena 02.10.2026) spojen sa kampusom. */
class FGraphTest {

    private val campus = CampusData.parse(File("src/main/assets/campus.json").readText())

    private val plans = INDOOR_BUILDINGS.associate { it.buildingId to IndoorPlan.parse(File("src/main/assets/${it.asset}").readText()) }

    private val f = plans.getValue(FPlan.BUILDING_ID)

    private val graph = seedGraph(campus, plans.values.toList()).let { (nodes, edges) -> BuildingGraph(nodes, edges, campus.placements()) }

    private fun room(name: String) = checkNotNull(graph.room(name)) { name }

    private fun distance(a: String, b: String): Double {
        val (pa, pb) = listOf(a, b).map { graph.position(checkNotNull(graph.node(it)) { it }) }
        return hypot((pa.x - pb.x).toDouble(), (pa.y - pb.y).toDouble())
    }

    /** Zgradu predstavlja pasarela iz Amfiteatara; spoljnog ulaza nema (korisnik, 02.10.2026). */
    @Test
    fun onlyEntrance_isPassageFromAmf() {
        assertEquals(FPlan.ENTRANCE_ID, f.entranceId)
        assertEquals(FPlan.ENTRANCE_ID, campus.building("F")!!.nodeId)
        assertEquals(FPlan.FLOORS.toList(), f.floors)
        assertNull(graph.node("K-Z-F"))
        assertEquals(setOf("K-P-AMF-F"), f.campusLinks.map { it.first() }.toSet())
        assertFalse(f.nodes.any { it.type == NodeType.ULAZ })
    }

    /** Pasarela stiže na međunivo stepeništa: veza i sa prizemljem i sa I spratom, blizu OSM spojnog dela. */
    @Test
    fun passage_reachesMezzanineToBothLevels() {
        val ids = graph.neighbors("K-P-AMF-F").map { it.first.id }
        assertTrue(ids.toString(), ids.containsAll(listOf("F-0-PROLAZ-AMF", "F-1-PROLAZ-AMF")))
        assertTrue(distance("K-P-AMF-F", "F-0-PROLAZ-AMF") < 10.0)
    }

    @Test
    fun everyNodeReachableFromNbEntrance() {
        graph.nodes.filter { it.buildingId == FPlan.BUILDING_ID }
            .forEach { assertNotNull(it.id, graph.route(NbPlan.ENTRANCE_ID, it.id)) }
    }

    /** Sale F-bloka iz rasporeda (F 315 ... F 319, F-208) su ucrtane na spratu iz broja. */
    @Test
    fun scheduleRooms_onTheirFloor() {
        val schedule = Json { ignoreUnknownKeys = true }
            .decodeFromString<ScheduleData>(File("src/main/assets/schedule.json").readText())
        val rooms = schedule.timetables.flatMap { it.classes }.map { it.room }.distinct()
            .filter { buildingOfRoom(it) == FPlan.BUILDING_ID }
        assertTrue(rooms.toString(), rooms.containsAll(listOf("F 315", "F 317", "F 318", "F 319", "F-208")))
        for (name in rooms) {
            val target = checkNotNull(resolveTarget(name, graph, campus)) { name }
            assertFalse(name, target.approximate)
            assertEquals(name, FPlan.BUILDING_ID, target.node.buildingId)
            assertEquals(name, name.filter(Char::isDigit).first().digitToInt(), target.node.floor)
        }
        assertEquals("208", FPlan.label(room("F 208").name!!))
    }

    /**
     * Brojevi sa tabli na spratovima (teren 03.10.2026): od sobe levo (istočno) od stepeništa donjim redom do istočnog
     * kraja, pa gornjim redom nazad do zapadnog kraja. Plan je okrenut kao evakuacioni (levo istok, stepenište dole).
     */
    @Test
    fun numbering_fromFloorSigns() {
        for ((floor, numbers) in mapOf(1 to listOf(101, 113, 114, 126), 2 to listOf(200, 208, 209, 224), 3 to listOf(301, 308, 309, 320))) {
            val (first, eastEnd, southFirst, westEnd) = numbers.map { room("F $it") }
            val stairs = checkNotNull(graph.node("F-$floor-S"))
            val rooms = graph.rooms.filter { it.buildingId == FPlan.BUILDING_ID && it.floor == floor }
            assertEquals("$floor", rooms.filter { it.x < stairs.x }.minBy { distance(it.id, stairs.id) }.id, first.id)
            assertEquals("$floor", rooms.minOf { it.x }, eastEnd.x, 1e-4f)
            // Gornji (južni) red: prvi broj je na istočnom kraju, poslednji na zapadnom.
            val south = rooms.filter { it.y < 0.5f }
            assertEquals("$floor", south.minBy { it.x }.id, southFirst.id)
            assertEquals("$floor", south.maxBy { it.x }.id, westEnd.id)
            // Stepenište je dole na planu (korisnik: prirodnije kad se popne na sprat).
            assertTrue(stairs.y > 0.5f)
        }
        assertEquals(room("F 202").id, resolveTarget("F 203", graph, campus)!!.node.id)
        assertEquals(room("F 224").id, resolveTarget("F 225", graph, campus)!!.node.id)
    }

    /** Iz Amfiteatara na III sprat F-bloka: pasarelom (bez staza) pa stepeništem. */
    @Test
    fun amfToThirdFloor_throughPassageAndStairs() {
        val route = checkNotNull(graph.route(room("AR0").id, room("F 315").id))
        val ids = route.nodes.map { it.id }
        assertTrue(ids.toString(), "K-P-AMF-F" in ids)
        assertFalse(route.nodes.any { it.type == NodeType.STAZA })
        assertTrue(route.nodes.any { it.buildingId == FPlan.BUILDING_ID && it.type == NodeType.STEPENISTE })
        assertEquals(AmfPlan.BUILDING_ID, route.nodes.first().buildingId)
    }
}
