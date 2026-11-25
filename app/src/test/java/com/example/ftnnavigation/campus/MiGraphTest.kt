package com.example.ftnnavigation.campus

import com.example.ftnnavigation.graph.BuildingGraph
import com.example.ftnnavigation.graph.INDOOR_BUILDINGS
import com.example.ftnnavigation.graph.IndoorPlan
import com.example.ftnnavigation.graph.MiPlan
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

/** Mašinski institut (assets/mi.json, tools/zgrade/build_mi.py; evakuacioni planovi sa terena 03.10.2026). */
class MiGraphTest {

    private val campus = CampusData.parse(File("src/main/assets/campus.json").readText())

    private val plans = INDOOR_BUILDINGS.associate { it.buildingId to IndoorPlan.parse(File("src/main/assets/${it.asset}").readText()) }

    private val mi = plans.getValue(MiPlan.BUILDING_ID)

    private val graph = seedGraph(campus, plans.values.toList()).let { (nodes, edges) -> BuildingGraph(nodes, edges, campus.placements()) }

    private fun room(name: String) = checkNotNull(graph.room(name)) { name }

    /** Glavni ulaz (portirnica) je OSM ulaz MI; zgrada više nije jedan čvor K-Z-MI. */
    @Test
    fun mainEntrance_matchesOsmEntrance() {
        assertEquals(MiPlan.ENTRANCE_ID, mi.entranceId)
        assertEquals(MiPlan.ENTRANCE_ID, campus.building("MI")!!.nodeId)
        assertNull(graph.node("K-Z-MI"))
        val a = graph.position(graph.node("K-U-MI-1")!!)
        val b = graph.position(graph.node(MiPlan.ENTRANCE_ID)!!)
        assertTrue(hypot(a.x - b.x, a.y - b.y) < 2.0)
    }

    /** Sve je dostižno iz NB-a, i "ostrva" I sprata u krilima V i G (svojim stepeništem iz prizemlja). */
    @Test
    fun everythingReachable() {
        assertEquals(MiPlan.FLOORS.toList(), mi.floors)
        graph.nodes.filter { it.buildingId == MiPlan.BUILDING_ID }
            .forEach { assertNotNull(it.id, graph.route(NbPlan.ENTRANCE_ID, it.id)) }
    }

    /**
     * Sve sale iz rasporeda su ucrtane (korisnik ih obeležio, 25.11.2026) i u svom su krilu. B1, B1-A, B1-B i D0 su u redu soba
     * dugačkog dela uz krilo, D4-D u dogradnji uz krilo D, a A20 je kod krila Đ.
     */
    @Test
    fun scheduleRooms_inTheirWing() {
        val schedule = Json { ignoreUnknownKeys = true }
            .decodeFromString<ScheduleData>(File("src/main/assets/schedule.json").readText())
        val rooms = schedule.timetables.flatMap { it.classes }.map { it.room }.distinct().filter { it.startsWith("MI ") }
        assertTrue(rooms.size > 40)
        val elsewhere = setOf("MI B1-A", "MI B1-B", "MI D0", "MI D4-D", "MI A20")
        // Krilo -> x (px plana) kolone soba krila (mi_geometry)
        val wings = mapOf('A' to -132..-33, 'B' to -2..97, 'V' to 325..424, 'G' to 455..552, 'D' to 780..880, 'Đ' to 912..1010)
        val (vx, vw) = -150f to 1180f  // viewport plana (build_mi.VIEWPORT)
        for (name in rooms) {
            val target = checkNotNull(resolveTarget(name, graph, campus)) { name }
            assertEquals(name, MiPlan.BUILDING_ID, target.node.buildingId)
            assertFalse(name, target.approximate)
            if (name in elsewhere) continue
            val wing = wings[name.removePrefix("MI ").first()] ?: continue
            val x = vx + target.node.x * vw
            assertTrue("$name x=$x", x in wing.first.toFloat()..wing.last.toFloat())
        }
    }

    /** Sprat sale po stranici "MI prostorije" (korisnik, na licu mesta); 125 nije nađena - vodi u 125A. */
    @Test
    fun floors_asMarkedOnSite() {
        listOf("A20", "16", "B1-A", "SD1", "24-A", "D0", "V4-4", "G2-2", "D2", "D3", "D4", "D4-D", "D5", "Đ2", "Đ3-2")
            .forEach { assertEquals(it, 0, room("MI $it").floor) }
        listOf("A2-1", "A2-4A", "A3Gal./2", "B4-3", "B4-5A", "V4-1", "V3-5", "G2-1", "G3-1C", "D4-A", "D5-Gal.", "Đ1-1", "Đ5-1", "125A")
            .forEach { assertEquals(it, 1, room("MI $it").floor) }
        assertEquals(room("MI 125A").id, resolveTarget("MI 125", graph, campus)!!.node.id)
        assertEquals(room("MI 125A").id, room(canonicalRoom("MI 125")).id)
    }

    /**
     * Do soba na spratu stiže se pravim stepeništem: A2 i B4 stepenicama u hodniku A|B (korisnik, 25.11.2026), A3Gal./2 iz A3, u krilima
     * V, G, D i Đ svojim stepeništem iz prizemlja; Đ1-1 iz dugačkog dela (evakuacioni plan).
     */
    @Test
    fun upstairsRooms_byTheirOwnStairs() {
        mapOf(
            "A2-1" to "AB", "A2-0" to "AB", "B4-3" to "AB", "B4-5A" to "AB", "A3Gal./2" to "SAB", "V4-1" to "SV", "V4-3" to "SV",
            "V3-5" to "SV2", "G3-1A" to "SG", "G3-1K" to "SG", "G2-1" to "SG2", "G2-4" to "SG2", "D5-Gal." to "SD1", "D4-A" to "SD2",
            "Đ3-1" to "SĐ", "Đ5-1" to "SĐ", "Đ2-1" to "SĐ",
        ).forEach { (name, stair) ->
            val route = checkNotNull(graph.route(MiPlan.ENTRANCE_ID, room("MI $name").id)) { name }
            val climbed = route.nodes.filter { it.type == NodeType.STEPENISTE && it.floor == 0 }.map { it.id }
            assertEquals(name, listOf("MI-0-$stair"), climbed)
        }
        val route = checkNotNull(graph.route(MiPlan.ENTRANCE_ID, room("MI Đ1-1").id))
        assertEquals(listOf("MI-0-S2"), route.nodes.filter { it.type == NodeType.STEPENISTE && it.floor == 0 }.map { it.id })
    }

    /** Pravo stepenište u hodniku A|B ima putanju (kretanje tačke po stepenicama), a toaleti su na oba sprata. */
    @Test
    fun straightStairwellAndToilets() {
        assertEquals(listOf("AB", "AB"), mi.stairwells.map { it.key })
        val toilets = graph.nodes.filter { it.buildingId == MiPlan.BUILDING_ID && it.amenity == AMENITY_TOALET }
        assertEquals(mapOf(0 to 5, 1 to 2), toilets.groupingBy { it.floor }.eachCount())
    }

    /** Iz NB-a u MI: napolje, stazama, kroz glavni ulaz MI, pa stepenicama na sprat. */
    @Test
    fun nbToMiUpstairs_throughMainEntrance() {
        val route = checkNotNull(graph.route(NbPlan.ENTRANCE_ID, room("MI B4-3").id))
        assertTrue(route.nodes.any { it.type == NodeType.STAZA })
        assertTrue(MiPlan.ENTRANCE_ID in route.nodes.map { it.id })
        assertTrue(route.nodes.any { it.buildingId == MiPlan.BUILDING_ID && it.type == NodeType.STEPENISTE })
        assertFalse(route.nodes.any { it.buildingId == MiPlan.BUILDING_ID && it.floor == 1 && it.type == NodeType.ULAZ })
    }
}
