package com.example.ftnnavigation.campus

import com.example.ftnnavigation.graph.BuildingGraph
import com.example.ftnnavigation.graph.INDOOR_BUILDINGS
import com.example.ftnnavigation.graph.IndoorPlan
import com.example.ftnnavigation.graph.NodeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.hypot

/** Najbliže mesto vrste ([nearestPlaces]) nad pravim grafom: toaleti iz generatora, sale po nazivu, Menza. */
class NearestPlaceTest {

    private val campus = CampusData.parse(File("src/main/assets/campus.json").readText())

    private val plans = INDOOR_BUILDINGS.map { IndoorPlan.parse(File("src/main/assets/${it.asset}").readText()) }

    private val graph = seedGraph(campus, plans).let { (nodes, edges) -> BuildingGraph(nodes, edges, campus.placements()) }

    private fun from(room: String) = checkNotNull(graph.room(room)) { room }.id

    private fun nearest(kind: PlaceKind, fromId: String) =
        nearestPlaces(kind, graph, campus) { graph.route(fromId, it) }

    @Test
    fun toilets_perBuilding_roomsWithDoorWithoutName() {
        val toilets = graph.nodes.filter { placeKindOf(it, campus) == PlaceKind.TOALET }
        // NB: suteren 2, prizemlje 1, I-IV po 2; AMF suteren 2; Kula 8 spratova po 2; F I-III; NTP prizemlje 1, I-IV
        // uz liftove i blok u donjem srednjem redu (i na III - teren 08.10.2026; do tada je tu bio NTP-315).
        assertEquals(
            mapOf("NB" to 11, "AMF" to 2, "KULA" to 16, "F" to 3, "NTP" to 9),
            toilets.groupingBy { it.buildingId }.eachCount(),
        )
        for (toilet in toilets) {
            assertEquals(NodeType.PROSTORIJA, toilet.type)
            assertNull(toilet.name)
            assertTrue(toilet.id, graph.neighbors(toilet.id).any { (n, _) -> n.type == NodeType.VRATA })
        }
    }

    /**
     * Toalet nije na mestu sale (korisnik 07.10.2026: "315 se preklapa sa toaletom" - NTP-315 i toalet su bili u istoj sobi
     * III sprata). Najbliže sale su 2,8 m (Kula) i više.
     */
    @Test
    fun toilets_notOnTopOfRooms() {
        val toilets = graph.nodes.filter { placeKindOf(it, campus) == PlaceKind.TOALET }
        for (toilet in toilets) {
            val p = graph.position(toilet)
            for (room in graph.rooms.filter { it.buildingId == toilet.buildingId && it.floor == toilet.floor }) {
                val q = graph.position(room)
                assertTrue("${toilet.id} - ${room.name}", hypot(p.x - q.x, p.y - q.y) >= 2.0)
            }
        }
    }

    /** "008" u prizemlju NB-a je na FtnGO-u toalet (ikonice M i Ž), nije u rasporedu - nije više sala. */
    @Test
    fun nbGround008_isToilet() {
        assertNull(graph.room("008"))
        assertTrue(graph.nodes.any { it.buildingId == "NB" && it.floor == 0 && it.amenity == AMENITY_TOALET })
    }

    @Test
    fun nearestToilet_onSameFloor() {
        for ((room, building, floor) in listOf(Triple("204", "NB", 2), Triple("Biblioteka", "AMF", -1), Triple("Kula 905", "KULA", 9))) {
            val toilet = nearest(PlaceKind.TOALET, from(room)).first().node
            assertEquals(room, building to floor, toilet.buildingId to toilet.floor)
        }
    }

    @Test
    fun everyKind_hasPlaces_sortedByTime() {
        for (kind in PlaceKind.entries) {
            val places = nearest(kind, from("204"))
            assertTrue(kind.name, places.isNotEmpty())
            assertEquals(places.sortedBy { it.route.durationSec }, places)
        }
        assertEquals(
            setOf("Kiosk", "MENZA"),
            nearest(PlaceKind.HRANA, from("204")).map { it.node.name ?: it.node.id.removePrefix("K-Z-") }.toSet(),
        )
        assertEquals(setOf("Biblioteka", "Čitaonica"), nearest(PlaceKind.UCENJE, from("204")).map { it.node.name }.toSet())
    }
}
