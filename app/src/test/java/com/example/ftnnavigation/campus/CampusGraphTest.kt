package com.example.ftnnavigation.campus

import com.example.ftnnavigation.graph.AmfPlan
import com.example.ftnnavigation.graph.BuildingGraph
import com.example.ftnnavigation.graph.INDOOR_BUILDINGS
import com.example.ftnnavigation.graph.IndoorPlan
import com.example.ftnnavigation.graph.KulaPlan
import com.example.ftnnavigation.graph.ItcPlan
import com.example.ftnnavigation.graph.MiPlan
import com.example.ftnnavigation.graph.NbPlan
import com.example.ftnnavigation.graph.NodeType
import com.example.ftnnavigation.graph.NtpPlan
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

    private val plans = INDOOR_BUILDINGS.associate { it.buildingId to IndoorPlan.parse(File("src/main/assets/${it.asset}").readText()) }

    private val nb = plans.getValue(NbPlan.BUILDING_ID)

    private val ntp = plans.getValue(NtpPlan.BUILDING_ID)

    private val graph = seedGraph(campus, plans.values.toList()).let { (nodes, edges) -> BuildingGraph(nodes, edges, campus.placements()) }

    private fun distance(a: PointM, b: PointM) = hypot(a.x - b.x, a.y - b.y)

    private fun position(id: String) = graph.position(graph.node(id)!!)

    private fun buildingNode(id: String) = campus.building(id)!!.nodeId

    @Test
    fun everyNodeReachableFromMainEntrance() {
        graph.nodes.forEach { assertNotNull(it.id, graph.route(NbPlan.ENTRANCE_ID, it.id)) }
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
        assertEquals(setOf("MENZA", "ZZZS", "SMESTAJ", "ISHRANA", "POSTA"), services.map { it.id }.toSet())
        for (service in services) {
            val route = checkNotNull(graph.route(NbPlan.ENTRANCE_ID, service.nodeId))
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
        assertTrue(distance(position(NbPlan.ENTRANCE_ID), position("K-U-NB-1")) < 2.0)
        assertTrue(distance(position(NbPlan.PASSAGE_ID), position("K-P-NB-KULA")) < 8.0)
        assertTrue(distance(position(NbPlan.AMF_PASSAGE_ID), position("K-P-AMF-NB")) < 8.0)
        // Zgrada je 63,4 m duga; plan (sa prolazom i marginama) je 76 m.
        assertEquals(76.0, graph.placement(NbPlan.BUILDING_ID).scale.widthM.toDouble(), 1.5)
    }

    /**
     * Amfiteatri -> F-blok: spojnim prolazom iz prizemlja, bez izlaska napolje. (Iz NB-a je od 30.09.2026
     * kraće spolja: unutra se ide kroz Kulu i trem, jer prolaz iz NB-a vodi samo do zadnjih vrata
     * amfiteatara, a stepenice naviše sa njegovog kraja nisu povezane.)
     */
    @Test
    fun amfToFBlock_goesIndoors() {
        val route = checkNotNull(graph.route(graph.room("AR0")!!.id, buildingNode("F")))
        assertTrue("K-P-AMF-F" in route.nodes.map { it.id })
        assertFalse(route.nodes.any { it.type == NodeType.STAZA })
    }

    /** Nastavni blok -> Mašinski institut: napolje kroz glavni ulaz, pa stazama. */
    @Test
    fun nbToMechanicalInstitute_goesOutside() {
        val route = checkNotNull(graph.route(NbPlan.ENTRANCE_ID, buildingNode("MI")))
        assertTrue(route.nodes.any { it.type == NodeType.STAZA })
        val straight = distance(position(NbPlan.ENTRANCE_ID), position(buildingNode("MI")))
        assertTrue("${route.lengthM} m", route.lengthM in straight..straight * 2.5)
        assertEquals(route.lengthM / 1.3, route.durationSec, 1e-6)
    }

    @Test
    fun buildingOfRoom_rules() {
        mapOf(
            "NTP-307" to "NTP", "NTP-A" to "NTP", "MI B4-3" to "MI", "MI Đ3-1" to "MI", "F 315" to "F",
            "A2" to "AMF", "INT 1" to "AMF", "AH4A" to "NB", "AH-CRT" to "NB", "L1" to "AMF", "L1 (RC)" to "NB",
            "L4 (RC)" to "NB", "108A" to "NB", "312" to "NB", "ITC04" to "ITC", "ITCA1" to "ITC", "ITCS-RC" to "ITC",
            "F-208" to "F", "LG 005" to "DGG", "LG 107" to "DGG", "Scen-LAB" to "AMF", "O12" to "NB",
            "GRID-1" to "AMF", "Fizika" to "NB", "Hemija" to "NB", "Hemija 2" to "NB", "AR0" to "AMF", "AR6" to "AMF",
        ).forEach { (room, building) -> assertEquals(room, building, buildingOfRoom(room)) }
        listOf("MF-27", "MF-Sala 1").forEach {
            assertNull(it, buildingOfRoom(it))
            assertEquals("Medicinski fakultet", offCampusPlaceOf(it))
        }
        assertNull(offCampusPlaceOf("F 315"))
    }

    /** GRID-1 je ucrtan u suterenu Amfiteatara, odmah iza svog ulaza sa zapada (K-U-AMF-2). */
    @Test
    fun gridRoom_nextToOwnEntrance() {
        val target = checkNotNull(resolveTarget("GRID-1", graph, campus))
        assertFalse(target.approximate)
        assertEquals("AMF", target.node.buildingId)
        assertEquals(-1, target.node.floor)
        val route = checkNotNull(graph.route("K-U-AMF-2", target.node.id))
        assertTrue("${route.lengthM} m", route.lengthM < 15)
        assertNull(graph.node("K-Z-GRID"))
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
        // MI ima plan od 03.10.2026 - "do zgrade" je glavni ulaz; zgrada bez plana je jedan čvor K-Z-...
        assertEquals(MiPlan.ENTRANCE_ID, resolveTarget("Mašinski institut", graph, campus)?.node?.id)
        assertEquals(ItcPlan.ENTRANCE_ID, resolveTarget("Istraživačko-tehnološki centar", graph, campus)?.node?.id)
        assertEquals("K-Z-DGG", resolveTarget("Departman za građevinarstvo i geodeziju", graph, campus)?.node?.id)
    }

    /** Početna i obaveštenje: ucrtana sala -> zgrada i sprat, inače samo naziv zgrade / mesta van kampusa. */
    @Test
    fun placeLocation_roomFloorOrBuildingName() {
        mapOf(
            "204" to PlaceLocation.Room(NbPlan, 2), "204A" to PlaceLocation.Room(NbPlan, 2),
            "O12" to PlaceLocation.Room(NbPlan, 0), "NTP-307" to PlaceLocation.Room(NtpPlan, 3),
            "Kula 905" to PlaceLocation.Room(KulaPlan, 9), "GRID-1" to PlaceLocation.Room(AmfPlan, -1),
            "MF-27" to PlaceLocation.Named("Medicinski fakultet"),
            "Nastavni blok" to PlaceLocation.Named(campus.building("NB")!!.name!!),
            "Menza" to PlaceLocation.Named("Menza"),
            "ITC04" to PlaceLocation.Room(ItcPlan, 0), "ITCA1" to PlaceLocation.Room(ItcPlan, 3),
            "MI 24-A" to PlaceLocation.Named(campus.building("MI")!!.name!!),
        ).forEach { (place, location) -> assertEquals(place, location, placeLocation(place, graph, campus)) }
        assertNull(placeLocation("Hodnik", graph, campus))
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
