package com.example.ftnnavigation.campus

import com.example.ftnnavigation.graph.AmfPlan
import com.example.ftnnavigation.graph.BuildingGraph
import com.example.ftnnavigation.graph.INDOOR_BUILDINGS
import com.example.ftnnavigation.graph.IndoorPlan
import com.example.ftnnavigation.graph.KulaPlan
import com.example.ftnnavigation.graph.NbPlan
import com.example.ftnnavigation.graph.NodeType
import com.example.ftnnavigation.graph.EdgeType
import com.example.ftnnavigation.graph.Route
import com.example.ftnnavigation.graph.RoutingProfile
import com.example.ftnnavigation.graph.routeOrFallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.hypot

/** Amfiteatri i Kula (assets/amf.json, kula.json; tools/zgrade) spojeni sa NB-om i kampusom kao u aplikaciji. */
class AmfKulaGraphTest {

    private val campus = CampusData.parse(File("src/main/assets/campus.json").readText())

    private val plans = INDOOR_BUILDINGS.associate { it.buildingId to IndoorPlan.parse(File("src/main/assets/${it.asset}").readText()) }

    private val graph = seedGraph(campus, plans.values.toList()).let { (nodes, edges) -> BuildingGraph(nodes, edges, campus.placements()) }

    private fun room(name: String) = checkNotNull(graph.room(name)) { name }

    @Test
    fun entrances_areBuildingNodes() {
        for (building in listOf(AmfPlan, KulaPlan)) {
            val plan = plans.getValue(building.buildingId)
            assertEquals(building.entranceId, plan.entranceId)
            assertEquals(building.entranceId, campus.building(building.buildingId)!!.nodeId)
            assertEquals(NodeType.ULAZ, graph.node(building.entranceId)!!.type)
            assertEquals(building.floors.toList(), plan.floors)
            assertNull(graph.node("K-Z-${building.buildingId}"))
        }
    }

    /** Veze sa kampusom (ulazi, prolazi) padaju blizu odgovarajućih tačaka iz OSM-a. */
    @Test
    fun campusLinks_nearOsm() {
        for (building in listOf(AmfPlan, KulaPlan)) {
            for ((campusId, nodeId) in plans.getValue(building.buildingId).campusLinks) {
                val a = graph.position(graph.node(campusId)!!)
                val b = graph.position(graph.node(nodeId)!!)
                assertTrue("$campusId - $nodeId", hypot(a.x - b.x, a.y - b.y) < 10.0)
            }
        }
    }

    @Test
    fun everyNodeReachableFromNbEntrance() {
        graph.nodes.filter { it.buildingId == AmfPlan.BUILDING_ID || it.buildingId == KulaPlan.BUILDING_ID }
            .forEach { assertNotNull(it.id, graph.route(NbPlan.ENTRANCE_ID, it.id)) }
    }

    /** Nazivi sala su jedinstveni u celom grafu (Kula ima iste brojeve kao NB, zato "Kula 101"). */
    @Test
    fun roomNamesUnique() {
        val names = graph.rooms.map { it.name!! }
        assertEquals(names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.toString(), names.size, names.toSet().size)
        assertNotNull(graph.room("Kula 101"))
        assertEquals(NbPlan.BUILDING_ID, room("101").buildingId)
    }

    /** Sale Amfiteatara iz rasporeda su ucrtane (ne "ruta do zgrade"). */
    @Test
    fun amfScheduleRooms_drawn() {
        val drawn = listOf("A1", "A2", "A3", "A4", "AR0", "AR3", "AR6", "Scen-LAB", "GRID-1", "INT 1", "L1")
        for (name in drawn) {
            val target = checkNotNull(resolveTarget(name, graph, campus)) { name }
            assertFalse(name, target.approximate)
            assertEquals(name, AmfPlan.BUILDING_ID, target.node.buildingId)
        }
    }

    /** INT 1: suteren, odmah pored GRID-1, ali bez direktne veze - oboje na hodnik svojim vratima. */
    @Test
    fun int1_nextToGridButSeparate() {
        val int1 = room("INT 1")
        val grid = room("GRID-1")
        assertEquals(-1, int1.floor)
        val doors = { id: String -> graph.neighbors(id).map { it.first.id }.toSet() }
        assertTrue(doors(int1.id).intersect(doors(grid.id)).isEmpty())
        val route = checkNotNull(graph.route(int1.id, grid.id))
        assertTrue(route.nodes.any { it.type == NodeType.HODNIK })
        assertTrue("${route.lengthM} m", route.lengthM < 20)
    }

    /** Prolaz iz NB-a vodi stepenicama naniže do zadnjih vrata A1, A2 i A4 ("Amphitheaters A1 A2 A4"). */
    @Test
    fun nbToAmphitheatre_throughPassage() {
        val route = checkNotNull(graph.route(NbPlan.ENTRANCE_ID, room("A2").id))
        val ids = route.nodes.map { it.id }
        assertTrue(ids.toString(), "K-P-AMF-NB" in ids)
        assertFalse(route.nodes.any { it.type == NodeType.STAZA })
    }

    /** Sala nije usputni čvor: do AR sala ide se hodnicima (preko Kule), ne kroz amfiteatar. */
    @Test
    fun nbToArRoom_notThroughAmphitheatre() {
        val route = checkNotNull(graph.route(NbPlan.ENTRANCE_ID, room("AR3").id))
        assertFalse(route.nodes.dropLast(1).any { it.type == NodeType.PROSTORIJA })
        assertFalse(route.nodes.any { it.type == NodeType.STAZA })
        assertTrue(route.nodes.any { it.buildingId == KulaPlan.BUILDING_ID })
    }

    /** Kula: na IX sprat liftom kad se izbegavaju stepenice, inače stepeništem; sprat iz broja sale. */
    @Test
    fun kulaTopFloor() {
        val target = room("Kula 905")
        assertEquals(9, target.floor)
        val byLift = checkNotNull(graph.route(KulaPlan.ENTRANCE_ID, target.id, RoutingProfile(avoidStairs = true)))
        assertEquals(listOf(0, 9), byLift.nodes.filter { it.type == NodeType.LIFT }.map { it.floor })
        assertEquals("905", KulaPlan.label(target.name!!))
    }

    /** Kula spaja NB i Amfiteatre: iz prizemlja NB-a kroz Kulu i trem, bez izlaska na staze. */
    @Test
    fun kulaConnectsNbAndAmf() {
        val route = checkNotNull(graph.route(NbPlan.PASSAGE_ID, AmfPlan.ENTRANCE_ID))
        val ids = route.nodes.map { it.id }
        assertTrue(ids.toString(), ids.containsAll(listOf("K-P-NB-KULA", "KULA-0-PROLAZ-NB", "KULA-0-PROLAZ-AMF", "K-P-AMF-KULA")))
        assertFalse(route.nodes.any { it.type == NodeType.STAZA })
    }

    /** Teren 01.10.2026: trem iz Kule stiže na međunivo - odatle dole na -1 ili gore u prizemlje Amfiteatara. */
    @Test
    fun kulaPorch_reachesMezzanineToBothLevels() {
        val ids = graph.neighbors("K-P-AMF-KULA").map { it.first.id }
        assertTrue(ids.containsAll(listOf("AMF-m1-PROLAZ-KULA", "AMF-0-PROLAZ-KULA")))
    }

    /**
     * Teren 10.10.2026 (korisnik označio granice): gornji red prizemlja "AR0 AR1 AR2 AR3 | stepenište | AR4 AR5 AR6", bez soba
     * između (02.10.: "AR0 AR1 X AR2 AR3 X AR4 | stepenište | AR5 X X ? AR6" - X sobe su delovi AR sala).
     */
    @Test
    fun arRooms_inOrderAroundWideStairs() {
        val xs = (0..6).map { room("AR$it").x }
        assertEquals(xs.sorted(), xs)
        val stairs = checkNotNull(graph.node("AMF-0-S2-D")).x  // prizemlje je najviši nivo S2: samo dno kraka naniže
        assertTrue(room("AR3").x < stairs && stairs < room("AR4").x)
        assertEquals(xs.last(), graph.rooms.filter { it.buildingId == AmfPlan.BUILDING_ID && it.floor == 0 }.maxOf { it.x })
        // Gornji red prizemlja: samo AR sale (nema soba bez naziva između njih).
        val row = plans.getValue(AmfPlan.BUILDING_ID).nodes.filter { it.floor == 0 && it.type == NodeType.PROSTORIJA && it.y < room("AR0").y + 0.01f }
        assertEquals((0..6).map { "AR$it" }.toSet(), row.mapNotNull { it.name }.toSet())
    }

    /** Kiosk (teren 02.10.2026): suteren, odmah pored stepeništa S1; radno vreme radnim danom 7-18. */
    @Test
    fun kiosk_nextToStairs() {
        val kiosk = room("Kiosk")
        assertEquals(-1, kiosk.floor)
        val (pk, ps) = listOf(kiosk, checkNotNull(graph.node("AMF-m1-S1"))).map(graph::position)
        assertTrue(hypot(pk.x - ps.x, pk.y - ps.y) < 4f)
        assertNotNull(ROOM_HOURS["Kiosk"])
    }

    // "Bez stepenica": krakovi do podesta/međunivoa su HOD ivice sa stepenicima (Edge.steps) i ne koriste se.

    private val stepEdges = graph.edges.filter { it.steps }.flatMap { listOf(it.fromId to it.toId, it.toId to it.fromId) }.toSet()

    private fun Route.stepsUsed(): List<Pair<String, String>> =
        nodes.zipWithNext { a, b -> a.id to b.id }.filter { it in stepEdges || graph.neighbors(it.first).any { (n, t) -> n.id == it.second && t == EdgeType.STEPENICE } }

    /** Označeni su NB prolaz ka AMF-u sa podesta, krakovi S1 AMF-a do podesta trema i pasarela F-bloka na podest. */
    @Test
    fun stepEdges_marked() {
        assertEquals(
            setOf(
                "NB-0-S" to "NB-0-PODEST", "AMF-m1-S1" to "AMF-m1-PODEST-S1", "AMF-0-S1-D-KRAK" to "AMF-0-PODEST-S1-D",
                "K-P-AMF-F" to "F-0-PROLAZ-AMF", "K-P-AMF-F" to "F-1-PROLAZ-AMF",
            ),
            graph.edges.filter { it.steps }.map { it.fromId to it.toId }.toSet(),
        )
        assertTrue(graph.edges.filter { it.steps }.all { it.type == EdgeType.HOD })
    }

    /** Bez stepenica se ne prelazi nijedan stepenik - ni krakom do podesta - ka bilo kojoj sali. */
    @Test
    fun avoidStairs_neverUsesStepEdges() {
        val noStairs = RoutingProfile(avoidStairs = true)
        for (target in graph.rooms) {
            val route = graph.route(NbPlan.ENTRANCE_ID, target.id, noStairs) ?: continue
            assertEquals(target.name, emptyList<Pair<String, String>>(), route.stepsUsed())
        }
    }

    /**
     * Ranije (pre oznake) "bez stepenica" je vodilo NB -> A2 liftom pa krakom do podesta i staklenim prolazom; sada staklenim
     * prolazom ne ide, a do F-bloka puta nema (pasarela na podest, bez lifta) -> stepenicama uz napomenu.
     */
    @Test
    fun avoidStairs_noGlassPassageToAmf_fBlockOnlyWithNote() {
        val noStairs = RoutingProfile(avoidStairs = true)
        val toA2 = routeOrFallback(noStairs) { graph.route(room("101").id, room("A2").id, it) }
        assertNotNull(toA2)
        if (!toA2!!.fallback) assertFalse(toA2.nodes.any { it.id == "NB-0-PODEST" })
        assertNull(graph.route(room("101").id, room("F 315").id, noStairs))
        assertTrue(routeOrFallback(noStairs) { graph.route(room("101").id, room("F 315").id, it) }!!.fallback)
        // Bez podešavanja ruta i dalje ide staklenim prolazom (kraće).
        assertTrue(graph.route(room("101").id, room("A2").id)!!.nodes.any { it.id == "NB-0-PODEST" })
    }
}
