package com.example.ftnnavigation.poc

import androidx.compose.ui.geometry.Offset
import com.example.ftnnavigation.campus.CAMPUS_ID
import com.example.ftnnavigation.campus.CampusData
import com.example.ftnnavigation.campus.GpsFix
import com.example.ftnnavigation.campus.contains
import com.example.ftnnavigation.campus.distanceToWallM
import com.example.ftnnavigation.campus.seedGraph
import com.example.ftnnavigation.graph.BuildingGraph
import com.example.ftnnavigation.graph.INDOOR_BUILDINGS
import com.example.ftnnavigation.graph.IndoorPlan
import com.example.ftnnavigation.graph.Node
import com.example.ftnnavigation.graph.NodeType
import com.example.ftnnavigation.graph.PointM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.random.Random

/** Pozicija napolju i u zgradama ([PdrLocator]): prelazi, GPS, ispravka smera - nad pravim grafom. */
class PdrLocatorTest {

    private val campus = CampusData.parse(File("src/main/assets/campus.json").readText())
    private val graph = seedGraph(campus, INDOOR_BUILDINGS.map { IndoorPlan.parse(File("src/main/assets/${it.asset}").readText()) })
        .let { (nodes, edges) -> BuildingGraph(nodes, edges, campus.placements()) }

    private val declination = 5.5f
    private val stepM = 0.8f

    private fun node(id: String): Node = graph.node(id)!!

    /** Jedini sused čvora na istom spratu iste zgrade (čvorovi ulaza/prolaza su kraj hodnika). */
    private fun inner(id: String): Node =
        graph.neighbors(id).map { it.first }.single { it.buildingId == node(id).buildingId && it.floor == node(id).floor }

    private fun placeOf(node: Node) = if (node.buildingId == CAMPUS_ID) PdrPlace.CAMPUS else PdrPlace(node.buildingId, node.floor)

    /** Locator sa pozicijom na čvoru. */
    private fun locatorAt(id: String) = PdrLocator(graph, campus, declination).apply {
        setPosition(placeOf(node(id)), Offset(node(id).x, node(id).y))
    }

    /** Staza daleko od svih zgrada (napolju sigurno). */
    private val outdoor = campus.nodes.filter { it.type == NodeType.STAZA }
        .map { PointM(it.x.toDouble(), it.y.toDouble()) }
        .first { p -> campus.buildings.all { !it.contains(p) && it.distanceToWallM(p) > 30 } }

    private fun campusOffset(p: PointM) = Offset((p.x / campus.widthM).toFloat(), (p.y / campus.heightM).toFloat())

    /** Pozicija (na grafu ako postoji) u metrima kampusa. */
    private fun PdrLocator.position(): PointM {
        val p = match?.point?.let { Offset(it.x, it.y) } ?: raw!!
        return graph.placement(place.buildingId).toMeters(p.x, p.y)
    }

    /** Magnetski azimut (kao sa senzora) pravca od [from] ka [to] u metrima kampusa (x istok, y jug). */
    private fun magneticAzimuth(from: PointM, to: PointM): Float =
        Math.toDegrees(atan2(to.x - from.x, from.y - to.y)).toFloat() - declination

    /** Hoda ka čvorovima [ids] redom (dok ne stigne na pola koraka), pa još [beyondSteps] koraka istim pravcem. */
    private fun PdrLocator.walk(vararg ids: String, beyondSteps: Int = 0): List<PlaceReason> {
        val reasons = mutableListOf<PlaceReason>()
        var azimuth = 0f
        for (id in ids) {
            val target = graph.position(node(id))
            var steps = 0
            while (hypot(target.x - position().x, target.y - position().y) > stepM / 2) {
                azimuth = magneticAzimuth(position(), target)
                step(azimuth, stepM)?.let(reasons::add)
                check(++steps < 100) { "nije stigao do $id (na $place)" }
            }
        }
        repeat(beyondSteps) { step(azimuth, stepM)?.let(reasons::add) }
        return reasons
    }

    private fun distance(a: PointM, b: PointM) = hypot(a.x - b.x, a.y - b.y)

    @Test
    fun fromCampus_throughEntrance_intoBuilding() {
        val entrance = graph.position(node("K-U-NB-1"))
        val inside = campus.building("NB")!!.labelAt!!.let { (x, y) -> PointM(x.toDouble(), y.toDouble()) }
        // 6 m ispred ulaza, pa pravo ka sredini zgrade.
        val len = distance(entrance, inside)
        val start = PointM(entrance.x - (inside.x - entrance.x) / len * 6, entrance.y - (inside.y - entrance.y) / len * 6)
        val locator = PdrLocator(graph, campus, declination).apply { setPosition(PdrPlace.CAMPUS, campusOffset(start)) }
        val reasons = mutableListOf<PlaceReason>()
        repeat(12) { locator.step(magneticAzimuth(start, inside), stepM)?.let(reasons::add) }
        assertEquals(listOf(PlaceReason.ULAZ), reasons)
        assertEquals(PdrPlace("NB", 0), locator.place)
        assertNotNull(locator.match)
    }

    /**
     * Do kraja hodnika [gateId], pa dalje pravcem hodnika dok pozicija ne stigne na [targetId] (kraj hodnika druge
     * zgrade ili ulaz na kampusu). Vraća razloge i broj koraka posle kraja hodnika.
     */
    private fun PdrLocator.passThrough(gateId: String, targetId: String): Pair<List<PlaceReason>, Int> {
        val reasons = walk(gateId).toMutableList()
        val azimuth = magneticAzimuth(graph.position(inner(gateId)), graph.position(node(gateId)))
        val target = node(targetId)
        var steps = 0
        while (!(place == placeOf(target) && distance(position(), graph.position(target)) < 0.01)) {
            step(azimuth, stepM)?.let(reasons::add)
            check(++steps < 60) { "nije stigao do $targetId (na $place)" }
        }
        return reasons to steps
    }

    @Test
    fun outOfBuilding_throughEntrance_ontoCampus() {
        val locator = locatorAt(inner("NB-0-ULAZ").id)
        val (reasons, steps) = locator.passThrough("NB-0-ULAZ", "K-U-NB-1")
        assertEquals(listOf(PlaceReason.PROLAZ), reasons)
        assertTrue("koraka $steps", steps <= 3)
        assertEquals(PdrPlace.CAMPUS, locator.place)
        assertNull(locator.match)
    }

    /** Ulaz koji je samo kraj hodnika (bez izlaska) ne prebacuje - korisnik se okrenuo. */
    @Test
    fun atEntrance_turningBack_staysInside() {
        val locator = locatorAt(inner("NB-0-ULAZ").id)
        locator.walk("NB-0-ULAZ")
        val reasons = locator.walk(inner("NB-0-ULAZ").id)
        assertTrue(reasons.isEmpty())
        assertEquals(PdrPlace("NB", 0), locator.place)
    }

    @Test
    fun nbToKula_throughGroundFloorPassage() {
        val locator = locatorAt(inner("NB-0-PROLAZ").id)
        val (reasons, steps) = locator.passThrough("NB-0-PROLAZ", "KULA-0-PROLAZ-NB")
        assertEquals(listOf(PlaceReason.PROLAZ), reasons)
        assertTrue("koraka $steps", steps <= 4)
        // Dalje se hoda po grafu Kule.
        locator.walk(inner("KULA-0-PROLAZ-NB").id)
        assertEquals(PdrPlace("KULA", 0), locator.place)
        assertNotNull(locator.match)
    }

    @Test
    fun nbToKula_throughFirstFloorPassage() {
        val locator = locatorAt(inner("NB-1-PROLAZ").id)
        locator.passThrough("NB-1-PROLAZ", "KULA-1-PROLAZ-NB")
        assertEquals(PdrPlace("KULA", 1), locator.place)
    }

    /** Trem Kula - Amfiteatri vodi na međunivo; bira se nivo najbliži spratu sa koga se dolazi (prizemlje). */
    @Test
    fun kulaToAmf_throughPorch_toNearestLevel() {
        val locator = locatorAt(inner("KULA-0-PROLAZ-AMF").id)
        locator.passThrough("KULA-0-PROLAZ-AMF", "AMF-0-PROLAZ-KULA")
        assertEquals(PdrPlace("AMF", 0), locator.place)
    }

    @Test
    fun amfToF_throughBridge() {
        val locator = locatorAt(inner("AMF-0-PROLAZ-F").id)
        locator.passThrough("AMF-0-PROLAZ-F", "F-0-PROLAZ-AMF")
        assertEquals(PdrPlace("F", 0), locator.place)
    }

    @Test
    fun nbToAmf_throughGlassPassage() {
        val locator = locatorAt(inner("NB-0-PROLAZ-AMF").id)
        locator.passThrough("NB-0-PROLAZ-AMF", "AMF-0-PROLAZ-NB")
        assertEquals(PdrPlace("AMF", 0), locator.place)
    }

    /** Dug prolaz (Kula - AMF, 17 m) se prelazi koracima: do pola puta tačka je u Kuli, van grafa. */
    @Test
    fun longPassage_takesSteps() {
        val locator = locatorAt(inner("KULA-0-PROLAZ-AMF").id)
        val (_, steps) = locator.passThrough("KULA-0-PROLAZ-AMF", "AMF-0-PROLAZ-KULA")
        assertTrue("koraka $steps", steps in 18..24)
        val again = locatorAt(inner("KULA-0-PROLAZ-AMF").id)
        again.walk("KULA-0-PROLAZ-AMF", beyondSteps = 6)
        assertEquals(PdrPlace("KULA", 0), again.place)
        assertNull(again.match)
    }

    /** Okret nazad u prolazu vraća na kraj hodnika iz koga se izašlo. */
    @Test
    fun passage_turningBack_returnsToCorridor() {
        val locator = locatorAt(inner("KULA-0-PROLAZ-AMF").id)
        locator.walk("KULA-0-PROLAZ-AMF", beyondSteps = 6)
        locator.walk(inner("KULA-0-PROLAZ-AMF").id)
        assertEquals(PdrPlace("KULA", 0), locator.place)
        assertNotNull(locator.match)
    }

    private val east = 90f - declination

    private fun outdoorLocator() =
        PdrLocator(graph, campus, declination).apply { setPosition(PdrPlace.CAMPUS, campusOffset(outdoor)) }

    /** Hoda [steps] koraka sa kompasom zakrenutim za [compassErrorDeg] od pravog istoka, pa označi stvarno mesto. */
    private fun PdrLocator.walkEastAndMark(from: PointM, steps: Int, compassErrorDeg: Float): HeadingCheck? {
        repeat(steps) { step(east + compassErrorDeg, stepM) }
        return setPosition(PdrPlace.CAMPUS, campusOffset(PointM(from.x + steps * stepM, from.y)))
    }

    /** Kompas zakrenut 40°: označavanje posle 16 m hoda meri grešku i ispravlja smer za dalje korake. */
    @Test
    fun recalibration_correctsHeadingBias() {
        val locator = outdoorLocator()
        val check = locator.walkEastAndMark(outdoor, 20, compassErrorDeg = 40f) as HeadingCheck.Measured
        assertEquals(40f, check.errorDeg, 0.5f)
        assertEquals(40f, check.residualDeg, 0.5f)
        assertEquals(1f, check.lengthRatio, 0.01f)
        assertTrue(check.reliable && check.applied)
        assertEquals(40f, locator.headingErrorDeg!!, 0.5f)
        val end = PointM(outdoor.x + 20 * stepM, outdoor.y)
        locator.step(east + 40f, stepM)
        val moved = locator.position()
        assertEquals(stepM.toDouble(), moved.x - end.x, 0.01)
        assertEquals(0.0, moved.y - end.y, 0.01)
        // Ispravka važi i za smer na ekranu.
        assertEquals(90f, locator.headingOnPlan(east + 40f), 0.01f)
    }

    /** Druga rekalibracija: ukupna greška kompasa i koliko je promašio uz ispravku od prve. */
    @Test
    fun secondRecalibration_reportsTotalAndResidual() {
        val locator = outdoorLocator()
        locator.walkEastAndMark(outdoor, 20, compassErrorDeg = 40f)
        val from = PointM(outdoor.x + 20 * stepM, outdoor.y)
        val check = locator.walkEastAndMark(from, 20, compassErrorDeg = 50f) as HeadingCheck.Measured
        assertEquals(50f, check.errorDeg, 0.5f)
        assertEquals(10f, check.residualDeg, 0.5f)
        assertEquals(50f, locator.headingErrorDeg!!, 0.5f)
        assertEquals(-50f, locator.headingBiasDeg, 0.5f)
    }

    /** Isključena ispravka: označavanje samo meri (celu grešku); uključivanje primenjuje izmereno. */
    @Test
    fun correctionOff_onlyMeasures() {
        val locator = outdoorLocator().apply { applyCorrection = false }
        locator.walkEastAndMark(outdoor, 20, compassErrorDeg = 40f).let { it as HeadingCheck.Measured }.let {
            assertEquals(40f, it.errorDeg, 0.5f)
            assertTrue(it.reliable && !it.applied)
        }
        assertEquals(0f, locator.headingBiasDeg, 0f)
        assertEquals(40f, locator.headingErrorDeg!!, 0.5f)
        assertEquals(130f, locator.headingOnPlan(east + 40f), 0.01f)
        // I drugo merenje bez ispravke daje celu grešku (ne ostatak).
        val from = PointM(outdoor.x + 20 * stepM, outdoor.y)
        val again = locator.walkEastAndMark(from, 20, compassErrorDeg = 40f) as HeadingCheck.Measured
        assertEquals(40f, again.errorDeg, 0.5f)
        assertEquals(40f, again.residualDeg, 0.5f)
        locator.applyCorrection = true
        assertEquals(90f, locator.headingOnPlan(east + 40f), 0.01f)
    }

    /** Ispravka uključena/isključena usred puta: PDR put je mešavina dva smera - ne meri se. */
    @Test
    fun correctionToggledWhileWalking_notMeasured() {
        val locator = outdoorLocator()
        locator.walkEastAndMark(outdoor, 20, compassErrorDeg = 40f)
        val from = PointM(outdoor.x + 20 * stepM, outdoor.y)
        repeat(10) { locator.step(east + 40f, stepM) }
        locator.applyCorrection = false
        val check = locator.walkEastAndMark(PointM(from.x + 10 * stepM, from.y), 10, compassErrorDeg = 40f)
        assertEquals(HeadingCheck.Interrupted, check)
        assertEquals(40f, locator.headingErrorDeg!!, 0.5f)
        // Posle novog sidra se opet meri.
        val next = PointM(from.x + 20 * stepM, from.y)
        assertTrue(locator.walkEastAndMark(next, 20, compassErrorDeg = 40f) is HeadingCheck.Measured)
    }

    @Test
    fun recalibration_afterShortWalk_keepsHeading() {
        val locator = outdoorLocator()
        val check = locator.walkEastAndMark(outdoor, 8, compassErrorDeg = 40f)
        assertEquals(8 * stepM, (check as HeadingCheck.TooShort).actualM, 0.01f)
        assertEquals(0f, locator.headingBiasDeg, 0f)
        assertNull(locator.headingErrorDeg)
    }

    /** PDR put 16 m, a označeno 40 m dalje: zbrkan put ili pogrešan dodir - meri se, ali se ne uči. */
    @Test
    fun recalibration_lengthMismatch_unreliable() {
        val locator = outdoorLocator()
        repeat(20) { locator.step(east + 20f, stepM) }
        val check = locator.setPosition(PdrPlace.CAMPUS, campusOffset(PointM(outdoor.x + 40, outdoor.y))) as HeadingCheck.Measured
        assertEquals(0.4f, check.lengthRatio, 0.01f)
        assertTrue(!check.reliable)
        assertEquals(0f, locator.headingBiasDeg, 0f)
        assertNull(locator.headingErrorDeg)
    }

    /** Označavanje na drugom spratu (stepenice) nije poređenje puta - smer se ne dira. */
    @Test
    fun recalibration_onOtherFloor_keepsHeading() {
        val locator = locatorAt(inner("NB-0-ULAZ").id)
        repeat(20) { locator.step(0f, stepM) }
        val up = node("NB-1-PROLAZ")
        assertNull(locator.setPosition(PdrPlace("NB", 1), Offset(up.x, up.y)))
        assertEquals(PdrPlace("NB", 1), locator.place)
    }

    /** Napolju: kompas zakrenut 15° (u ruci je bilo ~±10°), GPS (šum ±5 m) drži poziciju blizu stvarne. */
    @Test
    fun gps_keepsDriftingPdrOnTrack() {
        val random = Random(7)
        fun walkEast(withGps: Boolean): Double {
            val locator = PdrLocator(graph, campus, declination).apply { setPosition(PdrPlace.CAMPUS, campusOffset(outdoor)) }
            var truth = outdoor
            repeat(60) { i ->
                locator.step(90f - declination + 15f, stepM)
                truth = PointM(truth.x + stepM, truth.y)
                if (withGps && i % 2 == 1) {
                    val noisy = PointM(truth.x + random.nextDouble(-5.0, 5.0), truth.y + random.nextDouble(-5.0, 5.0))
                    locator.onGps(GpsFix(noisy, 8f), tracking = true)
                }
            }
            return distance(locator.position(), truth)
        }
        val withoutGps = walkEast(withGps = false)
        val withGps = walkEast(withGps = true)
        assertTrue("bez GPS-a $withoutGps m", withoutGps > 10)
        assertTrue("sa GPS-om $withGps m", withGps < 6)
    }

    @Test
    fun gps_singleJumpIgnored_persistentJumpTrusted() {
        val locator = PdrLocator(graph, campus, declination).apply { setPosition(PdrPlace.CAMPUS, campusOffset(outdoor)) }
        val far = PointM(outdoor.x, outdoor.y + 60)
        assertNull(locator.onGps(GpsFix(far, 8f), tracking = true))
        assertTrue(distance(locator.position(), outdoor) < 0.01)
        // Prva je već bila sumnjiva; peta zaredom prebacuje.
        val reasons = List(4) { locator.onGps(GpsFix(far, 8f), tracking = true) }
        assertEquals(PlaceReason.GPS_SKOK, reasons.last())
        assertTrue(distance(locator.position(), far) < 0.01)
    }

    /** Bez praćenja (nema koraka) pozicija napolju je GPS lokacija. */
    @Test
    fun gps_withoutTracking_followsGps() {
        val locator = PdrLocator(graph, campus, declination).apply { setPosition(PdrPlace.CAMPUS, campusOffset(outdoor)) }
        val there = PointM(outdoor.x + 10, outdoor.y)
        locator.onGps(GpsFix(there, 8f), tracking = false)
        assertTrue(distance(locator.position(), there) < 0.01)
    }

    /** PDR nije video izlaz (stoji kod ulaza), GPS potvrdi napolju -> kampus, na ulazu. */
    @Test
    fun gpsOutside_nearEntrance_exitsThere() {
        val locator = locatorAt(inner("NB-0-ULAZ").id)
        val reasons = List(3) { locator.onGps(GpsFix(outdoor, 5f), tracking = true) }
        assertEquals(PlaceReason.GPS_IZLAZ, reasons.last())
        assertEquals(PdrPlace.CAMPUS, locator.place)
        assertTrue(distance(locator.position(), graph.position(node("K-U-NB-1"))) < 0.01)
    }

    /** Daleko od ulaza: na GPS lokaciji. Nepouzdan GPS u zgradi ne izbacuje napolje. */
    @Test
    fun gpsOutside_farFromEntrance_exitsAtGps() {
        val locator = locatorAt("NB-0-PROLAZ")
        repeat(10) { assertNull(locator.onGps(GpsFix(outdoor, 40f), tracking = true)) }
        assertEquals(PdrPlace("NB", 0), locator.place)
        List(3) { locator.onGps(GpsFix(outdoor, 5f), tracking = true) }
        assertEquals(PdrPlace.CAMPUS, locator.place)
        assertTrue(distance(locator.position(), outdoor) < 0.01)
    }

    /** Ponovljeni koraci (ispravljen smer hoda) se računaju od pozicije pre njih, i u PDR putu za ispravku smera. */
    @Test
    fun redoSteps_replaceEarlierSteps() {
        val locator = PdrLocator(graph, campus, declination).apply { setPosition(PdrPlace.CAMPUS, campusOffset(outdoor)) }
        val north = -declination
        repeat(3) { locator.step(north + 180f, stepM) }
        locator.step(north, stepM, redoSteps = 3)
        val p = locator.position()
        assertEquals(outdoor.x, p.x, 0.01)
        assertEquals(outdoor.y - 4 * stepM, p.y, 0.01)
        repeat(10) { locator.step(north, stepM) }
        val check = locator.setPosition(PdrPlace.CAMPUS, campusOffset(PointM(outdoor.x, outdoor.y - 14 * stepM)))
        assertEquals(0f, (check as HeadingCheck.Measured).errorDeg, 0.5f)
    }
}
