package com.example.ftnnavigation.campus

import com.example.ftnnavigation.graph.BuildingGraph
import com.example.ftnnavigation.graph.NodeType
import com.example.ftnnavigation.graph.NtpPlan
import com.example.ftnnavigation.graph.PlaceholderGraph
import com.example.ftnnavigation.graph.PointM
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

/** Graf kampusa iz pravog assets/campus.json, spojen sa grafovima NB-a i NTP-a kao u aplikaciji. */
class CampusGraphTest {

    private val campus = CampusData.parse(File("src/main/assets/campus.json").readText())

    private val ntp = NtpPlan.parse(File("src/main/assets/ntp.json").readText())

    private val graph = seedGraph(campus, ntp).let { (nodes, edges) -> BuildingGraph(nodes, edges, campus.placements()) }

    private fun distance(a: PointM, b: PointM) = hypot(a.x - b.x, a.y - b.y)

    private fun position(id: String) = graph.position(graph.node(id)!!)

    private fun buildingNode(id: String) = campus.building(id)!!.nodeId

    @Test
    fun everyNodeReachableFromMainEntrance() {
        graph.nodes.forEach { assertNotNull(it.id, graph.route(PlaceholderGraph.ENTRANCE_ID, it.id)) }
    }

    @Test
    fun everyBuildingHasNode() {
        assertEquals(8, campus.named(BuildingCategory.FTN).size)
        campus.namedBuildings.forEach { assertNotNull(it.id, graph.node(it.nodeId)) }
    }

    /** Pop-up na mapi: svaka zgrada i služba sa nazivom ima opis, i nema opisa za nepostojeći id. */
    @Test
    fun everyNamedBuildingHasInfo() {
        assertEquals(campus.namedBuildings.map { it.id }.toSet(), BUILDING_INFO.keys)
    }

    /** Menza, zdravstvena zaštita i službe u domu "Slobodan Bajić": svaka sa svojim ulazom, spolja. */
    @Test
    fun studentServices_reachableOutdoors() {
        val services = campus.named(BuildingCategory.SLUZBA)
        assertEquals(setOf("MENZA", "ZZZS", "SMESTAJ", "ISHRANA"), services.map { it.id }.toSet())
        for (service in services) {
            val route = checkNotNull(graph.route(PlaceholderGraph.ENTRANCE_ID, service.nodeId))
            assertTrue(service.id, route.nodes.any { it.type == NodeType.STAZA })
            assertEquals("K-U-${service.id}-1", route.nodes[route.nodes.size - 2].id)
        }
        // Službe u domu nemaju svoj obris - dom se crta jednom, sa dvorištem.
        assertTrue(campus.building("SMESTAJ")!!.outline.isEmpty())
        val dorm = campus.buildings.single { it.name == null && it.category == BuildingCategory.SLUZBA }
        assertEquals(1, dorm.holes.size)
        // Unutrašnji prstenovi NTP-a u OSM-u nisu dvorišta.
        assertTrue(campus.building("NTP")!!.holes.isEmpty())
    }

    /** Smeštaj plana u OSM obris: glavni ulaz sa plana pada na OSM ulaz, prolaz ka Kuli na spojni deo. */
    @Test
    fun nbPlan_alignedWithOsm() {
        assertTrue(distance(position(PlaceholderGraph.ENTRANCE_ID), position("K-U-NB-1")) < 2.0)
        assertTrue(distance(position(PlaceholderGraph.PASSAGE_ID), position("K-P-NB-KULA")) < 8.0)
        assertTrue(distance(position(PlaceholderGraph.AMF_PASSAGE_ID), position("K-P-AMF-NB")) < 8.0)
        // Zgrada je ~63 m duga; plan (sa prolazom i marginama) je ~76 m.
        assertEquals(76.5, graph.placement(PlaceholderGraph.BUILDING_ID).scale.widthM.toDouble(), 1.5)
    }

    /** Nastavni blok -> F-blok: kroz spojne prolaze i Amfiteatre, bez izlaska napolje. */
    @Test
    fun nbToFBlock_goesIndoors() {
        val route = checkNotNull(graph.route(graph.room("101")!!.id, buildingNode("F")))
        val ids = route.nodes.map { it.id }
        assertTrue(ids.containsAll(listOf("K-P-AMF-NB", "K-Z-AMF", "K-P-AMF-F")))
        assertFalse(route.nodes.any { it.type == NodeType.STAZA })
    }

    /** Nastavni blok -> Mašinski institut: napolje kroz glavni ulaz, pa stazama. */
    @Test
    fun nbToMechanicalInstitute_goesOutside() {
        val route = checkNotNull(graph.route(PlaceholderGraph.ENTRANCE_ID, buildingNode("MI")))
        assertTrue(route.nodes.any { it.type == NodeType.STAZA })
        val straight = distance(position(PlaceholderGraph.ENTRANCE_ID), position(buildingNode("MI")))
        assertTrue("${route.lengthM} m", route.lengthM in straight..straight * 2.5)
        assertEquals(route.lengthM / 1.3, route.durationSec, 1e-6)
    }

    @Test
    fun buildingOfRoom_rules() {
        mapOf(
            "NTP-307" to "NTP", "NTP-A" to "NTP", "MI B4-3" to "MI", "MI Đ3-1" to "MI", "F 315" to "F",
            "A2" to "AMF", "INT 1" to "AMF", "AH4A" to "NB", "AH-CRT" to "NB", "L1" to "NB",
            "L4 (RC)" to "NB", "108A" to "NB", "312" to "NB", "ITC04" to "ITC", "ITCA1" to "ITC", "ITCS-RC" to "ITC",
            "F-208" to "F", "LG 005" to "DGG", "LG 107" to "DGG", "Scen-LAB" to "NB", "O12" to "NB",
            "GRID-1" to "AMF", "Fizika" to "NB", "Hemija" to "NB", "Hemija 2" to "NB", "AR0" to "F", "AR6" to "F",
        ).forEach { (room, building) -> assertEquals(room, building, buildingOfRoom(room)) }
        listOf("MF-27", "MF-Sala 1").forEach {
            assertNull(it, buildingOfRoom(it))
            assertEquals("Medicinski fakultet", offCampusPlaceOf(it))
        }
        assertNull(offCampusPlaceOf("F 315"))
    }

    /** GRID ima svoj ulaz na Amfiteatrima: ruta vodi do njega, ne kroz ostatak zgrade. */
    @Test
    fun gridRoom_routesToOwnEntrance() {
        val target = checkNotNull(resolveTarget("GRID-1", graph, campus))
        assertEquals("K-Z-GRID", target.node.id)
        assertEquals("AMF", target.building?.id)
        assertTrue(target.approximate)
        val route = checkNotNull(graph.route(PlaceholderGraph.ENTRANCE_ID, "K-Z-GRID"))
        assertEquals("K-U-GRID-1", route.nodes[route.nodes.size - 2].id)
        // Ulaz GRID-a nije ulaz Amfiteatara: iz Amfiteatara se do GRID-a ide spolja.
        assertFalse(graph.neighbors("K-Z-AMF").any { (node, _) -> node.id == "K-U-GRID-1" })
    }

    /** Sale podrazumevanog rasporeda (SIIT, 4. godina, grupa 3): ucrtane ili bar do zgrade. */
    @Test
    fun defaultScheduleRooms_resolve() {
        val ntp = checkNotNull(resolveTarget("NTP-001", graph, campus))
        assertFalse(ntp.approximate)
        assertEquals("NTP", ntp.building?.id)
        assertEquals(NodeType.PROSTORIJA, ntp.node.type)
        val lab = checkNotNull(resolveTarget("L4 (RC)", graph, campus))
        assertFalse(lab.approximate)
        assertEquals(NodeType.PROSTORIJA, lab.node.type)
        listOf("F 315", "NTP-A", "A2", "NTP-307", "L6 (RC)", "F 318")
            .forEach { assertNotNull(it, resolveTarget(it, graph, campus)) }
        assertEquals("K-Z-MI", resolveTarget("Mašinski institut", graph, campus)?.node?.id)
    }

    /** Svaka sala iz rasporeda ima zgradu, osim onih za koje se zna da je ne znamo. */
    @Test
    fun allScheduleRooms_haveBuildingOrKnownUnknown() {
        val schedule = Json { ignoreUnknownKeys = true }
            .decodeFromString<ScheduleData>(File("src/main/assets/schedule.json").readText())
        // MF-: Medicinski fakultet, van kampusa (namerno nije na mapi). Sve ostale sale imaju zgradu.
        val unknown = listOf("MF-")
        schedule.timetables.flatMap { it.classes }.map { it.room }.distinct()
            .filter { room -> unknown.none { room.startsWith(it) } }
            .forEach { assertNotNull(it, resolveTarget(it, graph, campus)) }
    }
}
