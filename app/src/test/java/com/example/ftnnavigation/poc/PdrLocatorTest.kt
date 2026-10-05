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
    private val plans = INDOOR_BUILDINGS.map { IndoorPlan.parse(File("src/main/assets/${it.asset}").readText()) }
    private val stairPaths = plans.flatMap { it.stairPaths() }
    private val graph = seedGraph(campus, plans)
        .let { (nodes, edges) -> BuildingGraph(nodes, edges, campus.placements()) }

    private val declination = 5.5f
    private val stepM = 0.8f

    private fun node(id: String): Node = graph.node(id)!!

    /** Jedini sused čvora na istom spratu iste zgrade (čvorovi ulaza/prolaza su kraj hodnika). */
    private fun inner(id: String): Node =
        graph.neighbors(id).map { it.first }.single { it.buildingId == node(id).buildingId && it.floor == node(id).floor }

    private fun placeOf(node: Node) = if (node.buildingId == CAMPUS_ID) PdrPlace.CAMPUS else PdrPlace(node.buildingId, node.floor)

    /** Locator sa pozicijom na čvoru. */
    private fun locatorAt(id: String) = PdrLocator(graph, campus, declination, stairPaths).apply {
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
        val locator = PdrLocator(graph, campus, declination, stairPaths).apply { setPosition(PdrPlace.CAMPUS, campusOffset(start)) }
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

    /**
     * Teren 03.10.2026: na putu ka Kuli tačka je skrenula u granu sale 021 (vrata uz hol) i ostala u sali ~45
     * koraka - prolaz nije okinuo. Iz sale, hodom pravcem hodnika ka prolazu, tačka se vraća u hodnik i prelazi.
     */
    @Test
    fun trappedInRoomBesideCorridor_walkingOn_reachesKula() {
        val locator = locatorAt("NB-0-021-3")
        val azimuth = magneticAzimuth(graph.position(node("NB-0-H537_420")), graph.position(inner("NB-0-PROLAZ")))
        val reasons = mutableListOf<PlaceReason>()
        var steps = 0
        while (locator.place != PdrPlace("KULA", 0)) {
            locator.step(azimuth, stepM)?.let(reasons::add)
            check(++steps < 60) { "nije stigao do Kule (na ${locator.place}, ${locator.match?.point})" }
        }
        assertEquals(listOf(PlaceReason.PROLAZ), reasons)
    }

    /** Teren 03.10.2026: u NB prizemlju kompas je vukao ~+22° ka salama uz hodnik - tačka mora ostati u hodniku do Kule. */
    @Test
    fun nbToKula_compassDraggingTowardRooms_staysInCorridor() {
        val locator = locatorAt("NB-0-H633_420")
        val azimuth = magneticAzimuth(graph.position(node("NB-0-H633_420")), graph.position(inner("NB-0-PROLAZ"))) + 22f
        val reasons = mutableListOf<PlaceReason>()
        var steps = 0
        while (locator.place != PdrPlace("KULA", 0)) {
            locator.step(azimuth, stepM)?.let(reasons::add)
            assertTrue("u sali: ${locator.match?.point}", locator.match?.point?.let { it.from.type == NodeType.HODNIK || it.to.type == NodeType.HODNIK } ?: true)
            check(++steps < 60) { "nije stigao do Kule (na ${locator.place}, ${locator.match?.point})" }
        }
        assertEquals(listOf(PlaceReason.PROLAZ), reasons)
    }

    /**
     * Teren 04.10.2026: kompas ~+35° (ka gornjoj strani plana), korisnik ide hodnikom NB prizemlja ka holu - tačka je
     * na raskrsnici skrenula u krak ka prolazu u Amfiteatre (10,8 m naviše), prešla ga i okinula prolaz u AMF.
     */
    @Test
    fun nbCorridor_compassDraggingTowardAmfBranch_noPassageToAmf() {
        val locator = locatorAt("NB-0-H744_420")
        val azimuth = magneticAzimuth(graph.position(node("NB-0-H744_420")), graph.position(inner("NB-0-PROLAZ"))) + 35f
        val reasons = mutableListOf<PlaceReason>()
        var steps = 0
        while (locator.place != PdrPlace("KULA", 0)) {
            locator.step(azimuth, stepM)?.let(reasons::add)
            assertTrue("skrenula u krak ka AMF-u: ${locator.match?.point}", locator.place.buildingId != "AMF")
            check(++steps < 70) { "nije stigao do Kule (na ${locator.place}, ${locator.match?.point})" }
        }
        assertEquals(listOf(PlaceReason.PROLAZ), reasons)
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
        PdrLocator(graph, campus, declination, stairPaths).apply { setPosition(PdrPlace.CAMPUS, campusOffset(outdoor)) }

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
            val locator = PdrLocator(graph, campus, declination, stairPaths).apply { setPosition(PdrPlace.CAMPUS, campusOffset(outdoor)) }
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
        val locator = PdrLocator(graph, campus, declination, stairPaths).apply { setPosition(PdrPlace.CAMPUS, campusOffset(outdoor)) }
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
        val locator = PdrLocator(graph, campus, declination, stairPaths).apply { setPosition(PdrPlace.CAMPUS, campusOffset(outdoor)) }
        val there = PointM(outdoor.x + 10, outdoor.y)
        locator.onGps(GpsFix(there, 8f), tracking = false)
        assertTrue(distance(locator.position(), there) < 0.01)
    }

    /**
     * Teren 05.10.2026 (NTP, "uđe ali nestane"): posle ulaska GPS par sekundi stoji ispred vrata - to nije izlazak (bilo:
     * ulaz -> GPS izlaz -> ulaz ... 5 puta za 20 s). Tek GPS dalje od zgrade izvodi napolje.
     */
    @Test
    fun gpsJustOutsideWall_afterEntering_noExit_fartherOut_exits() {
        val ntp = campus.building("NTP")!!
        val door = graph.position(node("NTP-0-ULAZ-FTN"))
        /** Tačka napolju, oko vrata, čija je udaljenost od zida NTP-a najbliža [wallM] (i dalja od drugih zgrada). */
        fun outside(wallM: Double): PointM = (0 until 360 step 2).flatMap { a ->
            (1..40).map { r -> PointM(door.x + r * Math.cos(Math.toRadians(a.toDouble())), door.y + r * Math.sin(Math.toRadians(a.toDouble()))) }
        }.filter { p -> campus.buildings.none { it.contains(p) } && campus.buildings.all { it.distanceToWallM(p) >= 3.5 } }
            .minBy { kotlin.math.abs(ntp.distanceToWallM(it) - wallM) }
        val locator = locatorAt(inner("NTP-0-ULAZ-FTN").id)
        // Kao na snimku: GPS 4 m, pa 10 m ispred zgrade (tačnost 3 m).
        for (wallM in listOf(4.0, 10.0)) {
            val near = outside(wallM)
            assertTrue(List(5) { locator.onGps(GpsFix(near, 3f), tracking = true) }.all { it == null })
            assertEquals(PdrPlace("NTP", 0), locator.place)
        }
        val far = outside(20.0)
        assertTrue(PlaceReason.GPS_IZLAZ in List(3) { locator.onGps(GpsFix(far, 3f), tracking = true) })
        assertTrue(locator.place.isCampus)
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
        val locator = PdrLocator(graph, campus, declination, stairPaths).apply { setPosition(PdrPlace.CAMPUS, campusOffset(outdoor)) }
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

    // Stepenice (04.10.2026): okret na podestu između dva leta menja sprat, strana okreta je gore ili dole.
    // Od 05.10.2026 NB S i NTP S1-S3 imaju krakove ([StairPath]): tačka ide krakom, preko međupodesta, drugim krakom.

    private val stairs = stairsOf(graph)

    /** Locator na hodniku uz čvor stepeništa [nodeId], pa hod do njega; vraća pravac hodnik -> stepenište. */
    private fun atStairNode(nodeId: String, turns: Map<String, Int> = emptyMap()): Pair<PdrLocator, Float> {
        val foot = node(nodeId)
        val corridor = stairs.getValue(stairKey(foot)).corridor(foot)
        val locator = locatorAt(corridor.id).apply { restoreStairTurns(turns) }
        locator.walk(foot.id)
        return locator to magneticAzimuth(graph.position(corridor), graph.position(foot))
    }

    private fun atStairs(key: String, floor: Int, turns: Map<String, Int> = emptyMap()) =
        atStairNode(stairs.getValue(key).nodes.getValue(floor).id, turns)

    /** Putanja stepeništa [key] na spratu [floor] u metrima kampusa (dno naviše, okret, okret, dno naniže). */
    private fun stairPath(key: String, floor: Int, variant: Int = 0): List<PointM> {
        val path = stairPaths.filter { it.stairKey == key && it.floor == floor }[variant]
        return path.points.map { graph.placement(key.substringBefore('/')).toMeters(it.x, it.y) }
    }

    /** Magnetski azimut kraka naviše stepeništa [key] (od dna ka međupodestu). */
    private fun upFlightAzimuth(key: String, floor: Int) = stairPath(key, floor).let { magneticAzimuth(it[0], it[1]) }

    /** Rastojanje tačke [p] od duži [a]-[b] (m). */
    private fun distanceToSegment(p: PointM, a: PointM, b: PointM): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
        return distance(p, PointM(a.x + t * dx, a.y + t * dy))
    }

    /** Let od [first] koraka, okret za [turnDeg] u hodu (4 koraka), let od [second] koraka; razlozi prelaza. */
    private fun PdrLocator.flights(away: Float, turnDeg: Float, first: Int = 10, second: Int = 8): List<PlaceReason> {
        val headings = List(first) { away } + (1..4).map { away + turnDeg * it / 5 } + List(second) { away + turnDeg }
        return headings.mapNotNull { step(it, stepM) }
    }

    /**
     * Teren 05.10.2026 (crtež korisnika): sa podesta I sprata levim krakom gore, iza lifta udesno, desnim krakom na II -
     * tačka ide tim putem, a na novom spratu je na desnom kraku (ranije: na čvoru levog - "pogrešne stepenice").
     */
    @Test
    fun nbStairs_turningRight_goesUp_alongFlights() {
        val (locator, away) = atStairs("NB/S", 1)
        val path1 = stairPath("NB/S", 1)
        repeat(6) { locator.step(away, stepM) }
        assertEquals(PdrPlace("NB", 1), locator.place)
        assertTrue("na levom kraku", distanceToSegment(locator.position(), path1[0], path1[1]) < 0.1)
        assertTrue("krenuo uz krak", distance(locator.position(), path1[0]) > 1.5)
        // Okret udesno na međupodestu: tačka iza lifta.
        repeat(2) { locator.step(away + 45f * (it + 1), stepM) }
        assertTrue("na međupodestu", distanceToSegment(locator.position(), path1[1], path1[2]) < 0.1)
        val reasons = (List(2) { away + 135f } + List(8) { away + 180f }).mapNotNull { locator.step(it, stepM) }
        assertEquals(listOf(PlaceReason.STEPENICE), reasons)
        assertEquals(PdrPlace("NB", 2), locator.place)
        assertEquals(StairChange("NB/S", "NB", 1, 2, turn = 1, guessed = false), locator.lastStairChange)
        val path2 = stairPath("NB/S", 2)
        assertTrue("na desnom kraku", distanceToSegment(locator.position(), path2[2], path2[3]) < 0.1)
        assertTrue("bliže dnu desnog kraka", distance(locator.position(), path2[3]) < distance(locator.position(), path2[2]))
        assertEquals(0.3f, locator.lastStepM, 1e-6f)
    }

    /** Silazak: sa dna desnog kraka (S-D), okret ulevo -> sprat niže, tačka na levom kraku sprata ispod. */
    @Test
    fun nbStairs_fromDownFlight_turningLeft_goesDown() {
        val (locator, away) = atStairNode("NB-2-S-D")
        assertEquals(listOf(PlaceReason.STEPENICE), locator.flights(away, -180f))
        assertEquals(PdrPlace("NB", 1), locator.place)
        val path1 = stairPath("NB/S", 1)
        assertTrue("na levom kraku I sprata", distanceToSegment(locator.position(), path1[1], path1[0]) < 0.1)
    }

    /** Sa dna levog kraka, a okret ulevo: strana okreta odlučuje (silazak), tačka prelazi na pravi krak. */
    @Test
    fun nbStairs_turningLeft_goesDown() {
        val (locator, away) = atStairs("NB/S", 2)
        assertEquals(listOf(PlaceReason.STEPENICE), locator.flights(away, -180f))
        assertEquals(PdrPlace("NB", 1), locator.place)
        val path1 = stairPath("NB/S", 1)
        assertTrue(distanceToSegment(locator.position(), path1[1], path1[0]) < 0.1)
    }

    /** Posle drugog kraka pravo dalje: sa stepeništa u hol, tačka opet na grafu sprata, korak opet pune dužine. */
    @Test
    fun afterStairs_walkingOut_backOnFloorGraph() {
        val (locator, away) = atStairs("NB/S", 1)
        locator.flights(away, 180f)
        repeat(10) { locator.step(away + 180f, stepM) }
        assertEquals(PdrPlace("NB", 2), locator.place)
        assertNotNull("na grafu", locator.match)
        val downFoot = graph.position(node("NB-2-S-D"))
        assertTrue(distance(locator.position(), downFoot) > 1.0)
        assertEquals(stepM, locator.lastStepM, 1e-6f)
    }

    /** Hod do stepeništa i nazad (bez letova) ne menja sprat, a tačka ne kreće uz krak. */
    @Test
    fun toStairsAndBack_noFloorChange() {
        val (locator, away) = atStairs("NB/S", 1)
        val reasons = List(8) { away + 180f }.mapNotNull { locator.step(it, stepM) }
        assertTrue(reasons.isEmpty())
        assertEquals(PdrPlace("NB", 1), locator.place)
        assertNotNull("na grafu", locator.match)
    }

    /**
     * Teren 05.10.2026 (NB I -> III, NTP I -> II): više spratova zaredom, sa zastajanjem na podestu posle prve promene -
     * oba sprata, i tačka na kraju na desnom kraku III sprata.
     */
    @Test
    fun nbStairs_twoFloors_withPauseOnLanding() {
        val (locator, away) = atStairs("NB/S", 1)
        val headings = List(10) { away } + (1..4).map { away + 36f * it } + List(10) { away + 180f } +
            (1..4).map { away + 180f + 36f * it } + List(10) { away } + (1..4).map { away + 36f * it } + List(8) { away + 180f }
        var t = 0L
        val reasons = headings.mapIndexedNotNull { i, h ->
            t += if (i == 10 + 4 + 10 + 4) 3_250_000_000L else 650_000_000L
            locator.step(h, stepM, timeNs = t)
        }
        assertEquals(listOf(PlaceReason.STEPENICE, PlaceReason.STEPENICE), reasons)
        assertEquals(PdrPlace("NB", 3), locator.place)
        val path3 = stairPath("NB/S", 3)
        assertTrue(distanceToSegment(locator.position(), path3[2], path3[3]) < 0.1)
    }

    /** NTP S1: strana okreta je iz crteža krakova (gornji red naviše, međupodest desno) - ne uči se, ne pretpostavlja. */
    @Test
    fun ntpStairs_turnFromFlights() {
        val (locator, _) = atStairs("NTP/S1", 2)
        val up = upFlightAzimuth("NTP/S1", 2)
        assertEquals(listOf(PlaceReason.STEPENICE), locator.flights(up, 180f))
        assertEquals(PdrPlace("NTP", 3), locator.place)
        assertEquals(false, locator.lastStairChange!!.guessed)
        assertNull(locator.learnedStairTurns["NTP/S1"])
    }

    /**
     * Prolaz ka Amfiteatrima je sa međupodesta glavnog stepeništa NB (levim krakom gore, iza lifta udesno, pa u prolaz) -
     * i uz grešku kompasa ±20° vodi u Amfiteatre.
     */
    @Test
    fun nbToAmf_fromStairLanding_withCompassError() {
        for (errorDeg in listOf(-20f, 20f)) {
            val (locator, away) = atStairs("NB/S", 0)
            val up = away + errorDeg
            val headings = List(7) { up } + listOf(up + 45f, up + 90f) + List(4) { up + 90f } + listOf(up + 45f) + List(12) { up }
            val reasons = headings.mapNotNull { locator.step(it, stepM) }
            assertEquals("greška $errorDeg°", PdrPlace("AMF", 0), locator.place)
            assertTrue(PlaceReason.STEPENICE !in reasons)
        }
    }

    /**
     * Teren 03.10.2026 (NB P -> I, AMF 0 -> −1): let ide paralelno hodniku, tačka ne stigne do čvora stepeništa. Let -
     * okret - let koji počinje do 5 m od stepeništa je ipak to stepenište; dalje od stepeništa (šetnja hodnikom) nije.
     */
    @Test
    fun flightsAlongCorridorNextToStairs_changeFloor_farAway_doNot() {
        val east = magneticAzimuth(graph.position(node("NB-0-H653_420")), graph.position(node("NB-0-H744_420")))
        val near = locatorAt("NB-0-H653_420")
        assertEquals(listOf(PlaceReason.STEPENICE), near.flights(east, 180f, first = 8))
        assertEquals(PdrPlace("NB", 1), near.place)
        // Okret udesno je penjanje: na I sprat se stiže desnim krakom.
        val path1 = stairPath("NB/S", 1)
        assertTrue(distanceToSegment(near.position(), path1[2], path1[3]) < 0.1)
        val far = locatorAt("NB-0-H744_420")
        assertTrue(far.flights(east, 180f, first = 8).isEmpty())
        assertEquals(PdrPlace("NB", 0), far.place)
    }

    /** Sa najnižeg nivoa se može samo gore - i tako se nauči strana okreta stepeništa bez crteža krakova (Kula). */
    @Test
    fun fromLowestLevel_goesUp_andLearnsTurn() {
        val (locator, away) = atStairs("KULA/S", 0)
        assertEquals(listOf(PlaceReason.STEPENICE), locator.flights(away, -180f))
        assertEquals(PdrPlace("KULA", 1), locator.place)
        assertEquals(false, locator.lastStairChange!!.guessed)
        assertEquals(-1, locator.learnedStairTurns["KULA/S"])
        // Naučeno važi na drugim spratovima: okret ulevo je gore, udesno dole.
        val (up, awayUp) = atStairs("KULA/S", 2, locator.learnedStairTurns)
        up.flights(awayUp, -180f)
        assertEquals(PdrPlace("KULA", 3), up.place)
        val (down, awayDown) = atStairs("KULA/S", 2, locator.learnedStairTurns)
        down.flights(awayDown, 180f)
        assertEquals(PdrPlace("KULA", 1), down.place)
    }

    /** Strana se ne zna, a sprat nije krajnji: pretpostavka ka odredištu; "Ovde sam" na drugom spratu uči stranu. */
    @Test
    fun unknownTurn_guessesTowardDestination_correctionLearns() {
        val (locator, away) = atStairs("KULA/S", 3)
        locator.destination = PdrPlace("KULA", 1)
        locator.flights(away, 180f)
        assertEquals(PdrPlace("KULA", 2), locator.place)
        assertEquals(true, locator.lastStairChange!!.guessed)
        val s4 = stairs.getValue("KULA/S").nodes.getValue(4)
        locator.setPosition(PdrPlace("KULA", 4), Offset(s4.x, s4.y))
        // Okret udesno je bio penjanje (3 -> 4).
        assertEquals(1, locator.learnedStairTurns["KULA/S"])
    }

    // AMF po evakuacionom planu prizemlja (teren 05.10.2026: "nije dobra pozicija stepenica na AMF prizemlju").

    /** Magnetski azimut od tačke [from] do [to] putanje stepeništa [key] na spratu [floor]. */
    private fun pathAzimuth(key: String, floor: Int, from: Int, to: Int) =
        stairPath(key, floor).let { magneticAzimuth(it[from], it[to]) }

    /**
     * S1: iz suterena srednjim krakom do podesta (trem ka Kuli), pa bočnim krakom gore u prizemlje - okret ulevo vodi na
     * desni krak, udesno na levi; tačka je na tom kraku.
     */
    @Test
    fun amfS1_fromBasement_turnSideChoosesSideFlight() {
        for ((turnDeg, variant) in listOf(-180f to 0, 180f to 1)) {
            val (locator, _) = atStairNode("AMF-m1-S1")
            assertEquals(listOf(PlaceReason.STEPENICE), locator.flights(pathAzimuth("AMF/S1", -1, 0, 1), turnDeg))
            assertEquals(PdrPlace("AMF", 0), locator.place)
            val side = stairPath("AMF/S1", 0, variant)
            assertTrue("okret $turnDeg°", distanceToSegment(locator.position(), side[2], side[3]) < 0.1)
        }
    }

    /** S1: iz prizemlja niz bočni krak i pravo dalje - u trem ka Kuli (podest), pa u Kulu. */
    @Test
    fun amfS1_fromGroundFloor_downSideFlight_intoKulaPorch() {
        val (locator, _) = atStairNode("AMF-0-S1-D")
        val south = pathAzimuth("AMF/S1", 0, 3, 2)
        var steps = 0
        while (locator.place.buildingId != "KULA") {
            locator.step(south, stepM)
            check(++steps < 60) { "nije stigao u Kulu (na ${locator.place})" }
        }
        assertEquals(PdrPlace("KULA", 0), locator.place)
    }

    /** S1: iz suterena srednjim krakom i pravo dalje - takođe u trem ka Kuli. */
    @Test
    fun amfS1_fromBasement_upMiddleFlight_intoKulaPorch() {
        val (locator, _) = atStairNode("AMF-m1-S1")
        val south = pathAzimuth("AMF/S1", -1, 0, 1)
        var steps = 0
        while (locator.place.buildingId != "KULA") {
            check(locator.step(south, stepM) != PlaceReason.STEPENICE) { "promena sprata umesto trema" }
            check(++steps < 60) { "nije stigao u Kulu (na ${locator.place})" }
        }
    }

    /** S2: pravo stepenište (dva kraka u nizu, bez okreta) - iz prizemlja niz oba kraka u suteren, pa u hodnik. */
    @Test
    fun amfS2_straight_fromGroundFloor_downToBasement() {
        val (locator, _) = atStairNode("AMF-0-S2-D")
        val down = pathAzimuth("AMF/S2", 0, 3, 0)
        val reasons = List(30) { locator.step(down, stepM) }.filterNotNull()  // 2 kraka po 12 + 3 dalje
        assertEquals(listOf(PlaceReason.STEPENICE), reasons)
        assertEquals(PdrPlace("AMF", -1), locator.place)
        assertEquals(StairChange("AMF/S2", "AMF", 0, -1, turn = 0, guessed = false), locator.lastStairChange)
        assertNotNull("sišao sa stepeništa na graf", locator.match)
    }

    // Lift: pitanje na koji sprat posle stajanja kod lifta.

    private val sec = 1_000_000_000L

    /** Korisnik je iz hodnika ispred lifta [liftId] ušao u lift; poslednji korak u vremenu 0 (stajanje se meri od njega). */
    private fun atLift(liftId: String): PdrLocator {
        val lift = graph.position(node(liftId))
        val locator = locatorAt(inner(liftId).id)
        val azimuth = magneticAzimuth(graph.position(inner(liftId)), lift)
        val steps = (distance(locator.position(), lift) / stepM).toInt().coerceAtLeast(1)
        for (i in steps - 1 downTo 0) locator.step(azimuth, stepM, timeNs = -i * 600_000_000L)
        assertTrue("u liftu", distance(locator.position(), lift) < 1.0)
        assertNull(locator.checkLift(0))
        return locator
    }

    /** Azimut od lifta ka hodniku ispred njega. */
    private fun outOfLift(liftId: String) = magneticAzimuth(graph.position(node(liftId)), graph.position(inner(liftId)))

    @Test
    fun lift_standingStill_asksFloor() {
        val locator = atLift("NB-0-L")
        assertNull("posle 9 s još ne", locator.checkLift(9 * sec))
        val prompt = locator.checkLift(10 * sec)
        assertNotNull(prompt)
        assertEquals("NB-0-L", prompt!!.lift.id)
        assertEquals(0, prompt.fromFloor)
        // Lift NB ide -1 ... IV (na V sprat ne), bez sprata na kome je.
        assertEquals(listOf(4, 3, 2, 1, -1), prompt.floors)
        assertNull(prompt.suggested)
        assertEquals(prompt, locator.liftPrompt)
        assertNull("pita se jednom", locator.checkLift(20 * sec))
    }

    @Test
    fun lift_chosenFloor_stepsAfterExitRepeatedThere() {
        val locator = atLift("NB-0-L")
        locator.checkLift(10 * sec)
        // Izašao iz lifta i hodao pre odgovora (telefon u džepu): 3 koraka ka hodniku.
        val out = outOfLift("NB-0-L")
        repeat(3) { locator.step(out, stepM, timeNs = (20 + it) * sec) }
        assertEquals("dok čeka odgovor, tačka ostaje na spratu", PdrPlace("NB", 0), locator.place)
        assertEquals(PlaceReason.LIFT, locator.selectLiftFloor(3))
        assertEquals(PdrPlace("NB", 3), locator.place)
        assertNull(locator.liftPrompt)
        assertNotNull(locator.match)
        // Ista 3 koraka od lifta na III spratu (lift je na istom mestu na svakom spratu).
        val walked = distance(locator.position(), graph.position(node("NB-3-L")))
        assertTrue("od lifta ${"%.1f".format(walked)} m", walked in 1.5..2.6)
    }

    @Test
    fun lift_destinationFloorSuggested_onlyIfLiftGoesThere() {
        val locator = atLift("NB-0-L").apply { destination = PdrPlace("NB", 3) }
        assertEquals(3, locator.checkLift(10 * sec)!!.suggested)
        val toTop = atLift("NB-0-L").apply { destination = PdrPlace("NB", 5) }
        assertNull("lift NB ne ide na V sprat", toTop.checkLift(10 * sec)!!.suggested)
    }

    @Test
    fun lift_walkingPastOrStandingAway_noPrompt() {
        // Hod pored lifta (korak na 0,6 s): nijednom 10 s bez koraka.
        val locator = atLift("NB-0-L")
        val out = outOfLift("NB-0-L")
        var t = 0L
        repeat(15) {
            t += 600_000_000L
            locator.step(out, stepM, timeNs = t)
            assertNull(locator.checkLift(t + 500_000_000L))
        }
        // Stoji, ali > 3 m od lifta.
        assertTrue(distance(locator.position(), graph.position(node("NB-0-L"))) > 3.0)
        assertNull(locator.checkLift(t + 60 * sec))
    }

    @Test
    fun lift_dismissed_asksAgainOnlyAfterStepAndStanding() {
        val locator = atLift("NB-0-L")
        locator.checkLift(10 * sec)
        locator.dismissLift()
        assertNull(locator.liftPrompt)
        assertNull("bez koraka se ne pita ponovo", locator.checkLift(60 * sec))
        // Ušao u lift (korak) i stoji.
        locator.step(outOfLift("NB-0-L") + 180f, stepM, timeNs = 61 * sec)
        assertNull(locator.checkLift(70 * sec))
        assertNotNull(locator.checkLift(71 * sec))
    }

    @Test
    fun lift_manualPosition_clearsPrompt() {
        val locator = atLift("NB-0-L")
        locator.checkLift(10 * sec)
        val there = node("NB-2-L")
        locator.setPosition(PdrPlace("NB", 2), Offset(there.x, there.y))
        assertNull(locator.liftPrompt)
        assertNull(locator.selectLiftFloor(3))
        assertEquals(PdrPlace("NB", 2), locator.place)
    }

    /**
     * Replay 03.10.2026: "Ovde sam" u holu kod lifta NB I sprata, pa 10-140 s stajanja (beleške) - nije vožnja. Pitanje tek
     * kad posle označavanja napravi korak (ušao u lift) i stoji.
     */
    @Test
    fun lift_afterManualPosition_noPromptUntilStep() {
        val locator = locatorAt("NB-1-L")
        assertNull(locator.checkLift(0))
        assertNull(locator.checkLift(140 * sec))
        locator.step(outOfLift("NB-1-L") + 180f, stepM, timeNs = 141 * sec)
        assertNotNull(locator.checkLift(151 * sec))
    }

    @Test
    fun lift_kulaAndNtp_floorsOfThatLift() {
        assertEquals((1..9).reversed().toList(), atLift("KULA-0-L").checkLift(10 * sec)!!.floors)
        assertEquals(listOf(5, 4, 3, 2, 1), atLift("NTP-0-L2").checkLift(10 * sec)!!.floors)
    }

    @Test
    fun lift_onStairsOrCampus_noPrompt() {
        val (onStairs, _) = atStairNode("NB-0-S")
        assertNull(onStairs.checkLift(0))
        assertNull("na stepeništu se ne pita (lift NB je u sredini stepeništa)", onStairs.checkLift(30 * sec))
        val outside = outdoorLocator()
        assertNull(outside.checkLift(0))
        assertNull(outside.checkLift(30 * sec))
    }
}
