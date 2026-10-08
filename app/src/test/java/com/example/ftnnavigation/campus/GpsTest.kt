package com.example.ftnnavigation.campus

import com.example.ftnnavigation.graph.BuildingGraph
import com.example.ftnnavigation.graph.INDOOR_BUILDINGS
import com.example.ftnnavigation.graph.IndoorPlan
import com.example.ftnnavigation.graph.NbPlan
import com.example.ftnnavigation.graph.NodeType
import com.example.ftnnavigation.graph.PointM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.hypot

/** GPS na kampusu: projekcija, zgrada u kojoj je korisnik, ruta od lokacije - nad pravim campus.json. */
class GpsTest {

    private val campus = CampusData.parse(File("src/main/assets/campus.json").readText())

    private fun node(id: String) = campus.nodes.single { it.id == id }.let { PointM(it.x.toDouble(), it.y.toDouble()) }

    private fun labelOf(id: String) = campus.building(id)!!.labelAt!!.let { (x, y) -> PointM(x.toDouble(), y.toDouble()) }

    /** Staza daleko od svih zgrada (napolju sigurno). */
    private val outdoor = campus.nodes.filter { it.type == NodeType.STAZA }
        .map { PointM(it.x.toDouble(), it.y.toDouble()) }
        .first { p -> campus.buildings.all { !it.contains(p) && it.distanceToWallM(p) > 30 } }

    private fun BuildingDetector.feed(point: PointM, accuracyM: Float, times: Int = BuildingDetector.CONFIRM_FIXES) =
        repeat(times) { onFix(GpsFix(point, accuracyM)) }

    /** Službe u domu su OSM tačke - čvor je tačno na njima, pa projekcija mora da ih pogodi. */
    @Test
    fun projection_matchesBuildScript() {
        mapOf(
            "K-Z-SMESTAJ" to (45.2455021 to 19.8492921),
            "K-Z-POSTA" to (45.2460477 to 19.8513347),
        ).forEach { (id, latLon) ->
            val p = CampusGeo.toCampus(latLon.first, latLon.second)
            assertEquals(id, 0.0, hypot(p.x - node(id).x, p.y - node(id).y), 0.1)
        }
    }

    @Test
    fun contains_nbLabelInsideNb() {
        assertTrue(campus.building("NB")!!.contains(labelOf("NB")))
        assertFalse(campus.building("NB")!!.contains(outdoor))
    }

    @Test
    fun insideBuilding_afterConfirmedFixes() {
        val detector = BuildingDetector(campus)
        detector.feed(labelOf("NB"), 10f, times = BuildingDetector.CONFIRM_FIXES - 1)
        assertFalse(detector.isKnown)
        detector.feed(labelOf("NB"), 10f, times = 1)
        assertTrue(detector.isKnown)
        assertEquals("NB", detector.current?.id)
    }

    @Test
    fun outside_afterConfirmedFixes() {
        val detector = BuildingDetector(campus)
        detector.feed(labelOf("KULA"), 10f)
        detector.feed(outdoor, 5f)
        assertTrue(detector.isKnown)
        assertNull(detector.current)
    }

    /** U zgradi GPS luta - nepouzdana lokacija ne menja zgradu. */
    @Test
    fun inaccurateFixes_keepBuilding() {
        val detector = BuildingDetector(campus)
        detector.feed(labelOf("NB"), 10f)
        detector.feed(outdoor, BuildingDetector.MAX_OUTSIDE_ACCURACY_M + 5f, times = 10)
        detector.feed(labelOf("MI"), BuildingDetector.MAX_INSIDE_ACCURACY_M + 5f, times = 10)
        assertEquals("NB", detector.current?.id)
    }

    /** Lokacija uz zid (na ulazu) može biti i unutra i napolju - ne menja ništa. */
    @Test
    fun fixAtWall_undecided() {
        val detector = BuildingDetector(campus)
        detector.feed(labelOf("NB"), 10f)
        detector.feed(node("K-U-NB-1"), 8f, times = 10)
        assertEquals("NB", detector.current?.id)
    }

    /** Pojedinačna lokacija u drugoj zgradi (skok GPS-a) ne prebacuje zgradu. */
    @Test
    fun singleJump_ignored() {
        val detector = BuildingDetector(campus)
        detector.feed(labelOf("NB"), 10f)
        detector.feed(labelOf("AMF"), 10f, times = 1)
        detector.feed(labelOf("NB"), 10f, times = 1)
        assertEquals("NB", detector.current?.id)
    }

    /** Ruta od GPS lokacije kreće sa staze ili ulaza, ne iz unutrašnjosti zgrade bez plana (K-Z-ITC; do 03.10.2026 MI). */
    @Test
    fun gpsRoute_startsOutdoors() {
        val plans = INDOOR_BUILDINGS.map { IndoorPlan.parse(File("src/main/assets/${it.asset}").readText()) }
        val graph = seedGraph(campus, plans).let { (nodes, edges) -> BuildingGraph(nodes, edges, campus.placements()) }
        val inItc = labelOf("ITC")
        val x = (inItc.x / campus.widthM).toFloat()
        val y = (inItc.y / campus.heightM).toFloat()
        assertEquals("K-Z-ITC", graph.nearestNode(CAMPUS_ID, 0, x, y)?.id)
        val types = setOf(NodeType.STAZA, NodeType.ULAZ)
        val route = graph.routeFrom(CAMPUS_ID, 0, x, y, NbPlan.ENTRANCE_ID, startTypes = types)
        assertNotNull(route)
        assertTrue(route!!.nodes.first().type in types)
    }

    /**
     * Teren 08.10.2026 (snimci ekrana): ruta iz sredine ulice je išla do najbližeg čvora u stranu, pa nazad ulicom ("trougao").
     * Sada kreće sa najbliže tačke staze: sa sredine svake duge staze do njenog kraja = pola staze. (Tačka 2 m u stranu ne
     * može - neke staze imaju paralelnu stazu na ~2 m.)
     */
    @Test
    fun gpsRoute_fromMiddleOfStreet_startsOnStreet() {
        val plans = INDOOR_BUILDINGS.map { IndoorPlan.parse(File("src/main/assets/${it.asset}").readText()) }
        val graph = seedGraph(campus, plans).let { (nodes, edges) -> BuildingGraph(nodes, edges, campus.placements()) }
        val types = setOf(NodeType.STAZA, NodeType.ULAZ)
        val long = graph.edges.filter { e ->
            val a = graph.node(e.fromId)!!
            val b = graph.node(e.toId)!!
            a.buildingId == CAMPUS_ID && b.buildingId == CAMPUS_ID && a.type == NodeType.STAZA && b.type == NodeType.STAZA &&
                hypot(graph.position(a).x - graph.position(b).x, graph.position(a).y - graph.position(b).y) > 40
        }
        assertTrue(long.size > 5)
        for (edge in long) {
            val a = graph.position(graph.node(edge.fromId)!!)
            val b = graph.position(graph.node(edge.toId)!!)
            val length = hypot(b.x - a.x, b.y - a.y)
            val px = (a.x + b.x) / 2
            val py = (a.y + b.y) / 2
            val route = graph.routeFrom(CAMPUS_ID, 0, (px / campus.widthM).toFloat(), (py / campus.heightM).toFloat(), edge.toId, startTypes = types)!!
            assertTrue("${edge.fromId} - ${edge.toId}: ${route.lengthM} m", route.lengthM <= length / 2 + 0.5)
        }
    }
}
