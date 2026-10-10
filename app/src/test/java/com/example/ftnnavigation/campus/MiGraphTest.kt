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

    /** Sale iz rasporeda (oznaka: krilo + blok + soba) su u svom krilu; osim nenađenih (MI 24-A, MI SD1). */
    @Test
    fun scheduleRooms_inTheirWing() {
        val schedule = Json { ignoreUnknownKeys = true }
            .decodeFromString<ScheduleData>(File("src/main/assets/schedule.json").readText())
        val rooms = schedule.timetables.flatMap { it.classes }.map { it.room }.distinct().filter { it.startsWith("MI ") }
        assertTrue(rooms.size > 40)
        val unknown = setOf("MI 24-A", "MI SD1")
        // Krilo -> x (px plana) kolone soba krila (build_mi.WINGS)
        val wings = mapOf('A' to -132..-33, 'B' to -2..97, 'V' to 325..424, 'G' to 455..552, 'D' to 780..880, 'Đ' to 912..1010)
        val (vx, vw) = -150f to 1180f  // viewport plana (build_mi.VIEWPORT)
        for (name in rooms) {
            val target = checkNotNull(resolveTarget(name, graph, campus)) { name }
            assertEquals(name, MiPlan.BUILDING_ID, target.node.buildingId)
            assertEquals(name, name in unknown, target.approximate)
            if (name in unknown) continue
            val wing = wings[name.removePrefix("MI ").first()] ?: continue
            val x = vx + target.node.x * vw
            assertTrue("$name x=$x", x in wing.first.toFloat()..wing.last.toFloat())
        }
    }

    /** Sprat: lista na ulazu u lamelu B - B4-0x prizemlje, B4-1 ... B4-5A sprat; galerije su na spratu. */
    @Test
    fun floors_fromWingLists() {
        assertEquals(0, room(canonicalRoom("MI B4-0B")).floor)
        assertEquals(1, room("MI B4-3").floor)
        assertEquals(1, room(canonicalRoom("MI A2-1")).floor)
        assertEquals(0, room("MI A4").floor)
        assertEquals(1, room("MI D5-Gal.").floor)
        assertEquals(0, room("MI 16").floor)
        assertEquals(1, room("MI 125").floor)
        assertEquals(room("MI A2-0").id, resolveTarget("MI A20", graph, campus)!!.node.id)
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
