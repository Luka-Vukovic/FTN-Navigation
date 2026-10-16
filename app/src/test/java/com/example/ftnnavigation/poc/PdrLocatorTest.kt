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
import kotlin.math.abs
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

    /** Pravac (jedinični) spoljnog zida zgrade [id] najbližeg tački [p]. */
    private fun wallDirection(id: String, p: PointM): PointM {
        val ring = campus.building(id)!!.outline.map { (x, y) -> PointM(x.toDouble(), y.toDouble()) }
        val (a, b) = ring.indices.map { ring[it] to ring[(it + 1) % ring.size] }.minBy { (a, b) -> distanceToSegment(p, a, b) }
        val len = distance(a, b)
        return PointM((b.x - a.x) / len, (b.y - a.y) / len)
    }

    /**
     * Teren 08.10.2026: ulazak u NB "možda 7 koraka pre" - tačka (GPS + PDR) je već bila u obrisu, 5,3 m od ulaza (bočno, a
     * korisnik je išao ka vratima). Ulazak tek kad koraci pređu taj put: tačka 2,5 m u obrisu, ~5,5 m uz zid od ulaza.
     */
    @Test
    fun fromCampus_alreadyInOutlineBeforeDoor_entersAfterWalkingToDoor() {
        val entrance = graph.position(node("K-U-NB-1"))
        val outline = campus.building("NB")!!
        val wall = wallDirection("NB", entrance)
        fun at(along: Double, inward: Double, sign: Double) = PointM(
            entrance.x + wall.x * along - sign * wall.y * inward,
            entrance.y + wall.y * along + sign * wall.x * inward,
        )
        val sign = listOf(1.0, -1.0).single { outline.contains(at(0.0, 2.5, it)) }
        val start = listOf(5.5, -5.5).map { at(it, 2.5, sign) }.first { outline.contains(it) && outline.distanceToWallM(it) >= 1.5 }
        val locator = PdrLocator(graph, campus, declination, stairPaths).apply { setPosition(PdrPlace.CAMPUS, campusOffset(start)) }
        val toward = magneticAzimuth(start, at(0.0, 2.5, sign))
        val entered = (1..10).firstOrNull { locator.step(toward, stepM) == PlaceReason.ULAZ }
        assertNotNull(entered)
        // ~6 m do ulaza: najmanje (5,5 − 1) / 0,8 koraka; ranije ulazak odmah, čim je tačka do 6 m od ulaza.
        assertTrue("ušao posle $entered koraka", entered!! >= 5)
        assertEquals(PdrPlace("NB", 0), locator.place)
    }

    /** Jedinični pravac od ulaza [insideId] (čvor zgrade) ka hodniku, u metrima kampusa. */
    private fun inwardOf(insideId: String): PointM {
        val from = graph.position(node(insideId))
        val to = graph.position(inner(insideId))
        val len = distance(from, to)
        return PointM((to.x - from.x) / len, (to.y - from.y) / len)
    }

    /**
     * Teren 09.10.2026, NB: koraci kroz glavni ulaz ka unutra, a GPS (5-8 m) vuče tačku uz zid, van obrisa - tačka je ostala
     * napolju do "Ovde sam". Ulazak po pravcu koraka kroz vrata.
     */
    @Test
    fun fromCampus_stepsThroughDoor_gpsDraggingAlongWall_enters() {
        val entrance = graph.position(node("K-U-NB-1"))
        val inward = inwardOf("NB-0-ULAZ")
        val wall = wallDirection("NB", entrance)
        val start = PointM(entrance.x - inward.x * 2.5, entrance.y - inward.y * 2.5)
        val gps = PointM(entrance.x - inward.x * 3 + wall.x * 8, entrance.y - inward.y * 3 + wall.y * 8)
        val locator = PdrLocator(graph, campus, declination, stairPaths).apply { setPosition(PdrPlace.CAMPUS, campusOffset(start)) }
        val azimuth = magneticAzimuth(entrance, PointM(entrance.x + inward.x, entrance.y + inward.y))
        val entered = (1..8).firstOrNull {
            locator.onGps(GpsFix(gps, 5f), tracking = true)
            locator.step(azimuth, stepM) == PlaceReason.ULAZ
        }
        assertNotNull("nije ušao (tačka ${locator.position()})", entered)
        assertEquals(PdrPlace("NB", 0), locator.place)
    }

    /** Hod spolja uz zid pored ulaza (pravac koraka nije kroz vrata) nije ulazak. */
    @Test
    fun fromCampus_walkingAlongWallPastDoor_doesNotEnter() {
        val entrance = graph.position(node("K-U-NB-1"))
        val inward = inwardOf("NB-0-ULAZ")
        val wall = wallDirection("NB", entrance)
        val start = PointM(entrance.x - inward.x * 1.5 - wall.x * 8, entrance.y - inward.y * 1.5 - wall.y * 8)
        val locator = PdrLocator(graph, campus, declination, stairPaths).apply { setPosition(PdrPlace.CAMPUS, campusOffset(start)) }
        val azimuth = magneticAzimuth(entrance, PointM(entrance.x + wall.x, entrance.y + wall.y))
        val reasons = (1..20).mapNotNull { locator.step(azimuth, stepM) }
        assertTrue("$reasons", reasons.isEmpty())
        assertEquals(PdrPlace.CAMPUS, locator.place)
    }

    /**
     * Teren 09.10.2026 uveče, NTP: korisnik ide pravo ka PASAŽ-u i ~2 koraka od zida skreće uz zid ka glavnom ulazu - ulazak
     * kroz PASAŽ je okinuo, a tačka je stajala na ulazu dok je on hodao uz zid (kasnije lažan izlazak na drugom kraju PASAŽ-a).
     * Sada se ulazak vraća napolje čim pomeraj koraka ide uz zid, a tačka na grafu ne odmiče.
     */
    @Test
    fun enteredThenWalkingAlongWall_backOutside() {
        val entrance = graph.position(node("K-U-NTP-1"))
        val inward = inwardOf("NTP-0-ULAZ-PASAZ-I")
        val main = graph.position(node("K-U-NTP-3"))
        val wall = wallDirection("NTP", entrance).let { w -> if (w.x * (main.x - entrance.x) + w.y * (main.y - entrance.y) > 0) w else PointM(-w.x, -w.y) }
        val start = PointM(entrance.x - inward.x * 6, entrance.y - inward.y * 6)
        val locator = PdrLocator(graph, campus, declination, stairPaths).apply { setPosition(PdrPlace.CAMPUS, campusOffset(start)) }
        val towardDoor = magneticAzimuth(start, entrance)
        val entered = (1..12).firstOrNull { locator.step(towardDoor, stepM) == PlaceReason.ULAZ }
        assertNotNull("nije ušao", entered)
        assertEquals(PdrPlace("NTP", 0), locator.place)
        val alongWall = magneticAzimuth(entrance, PointM(entrance.x + wall.x, entrance.y + wall.y))
        val back = (1..10).firstOrNull { locator.step(alongWall, stepM) == PlaceReason.NIJE_USAO }
        assertNotNull("ostao u zgradi", back)
        assertEquals(PdrPlace.CAMPUS, locator.place)
        // Ne ulazi odmah opet kroz isti ulaz.
        val again = (1..4).mapNotNull { locator.step(alongWall, stepM) }
        assertTrue("$again", again.isEmpty())
    }

    /** Ulazak pa hod hodnikom u zgradu (pravac ulaza) ostaje u zgradi. */
    @Test
    fun enteredThenWalkingIn_staysInside() {
        val entrance = graph.position(node("K-U-NTP-1"))
        val inward = inwardOf("NTP-0-ULAZ-PASAZ-I")
        val start = PointM(entrance.x - inward.x * 6, entrance.y - inward.y * 6)
        val locator = PdrLocator(graph, campus, declination, stairPaths).apply { setPosition(PdrPlace.CAMPUS, campusOffset(start)) }
        val azimuth = magneticAzimuth(start, entrance)
        val reasons = (1..25).mapNotNull { locator.step(azimuth, stepM) }
        assertEquals(listOf(PlaceReason.ULAZ), reasons)
        assertEquals(PdrPlace("NTP", 0), locator.place)
    }

    /** Tačka napolju nesumnjivo duboko u obrisu zgrade sa planom (ulaz nije prepoznat) -> na najbliži hodnik prizemlja. */
    @Test
    fun deepInsideOutline_entersNearestCorridor() {
        val outline = campus.building("NTP")!!
        val deep = (0..450 step 2).flatMap { x -> (0..345 step 2).map { y -> PointM(x.toDouble(), y.toDouble()) } }
            .first { outline.contains(it) && outline.distanceToWallM(it) >= 20 }
        val locator = PdrLocator(graph, campus, declination, stairPaths).apply { setPosition(PdrPlace.CAMPUS, campusOffset(deep)) }
        val reasons = (1..6).mapNotNull { locator.step(it * 90f, 0.3f) }
        assertEquals(listOf(PlaceReason.ULAZ), reasons)
        assertEquals(PdrPlace("NTP", 0), locator.place)
        assertNotNull(locator.match)
    }

    /**
     * Teren 09.10.2026, NTP prizemlje: tačka na slepom kraju hodnika, korisnik se ~10 m vrti u holu - izvlačenje zaglavljene
     * tačke ju je stavilo na 15 m udaljen "ULAZ - FTN" i sledeći korak je bio izlazak na kampus.
     */
    @Test
    fun stuckAtCorridorEnd_walkingTowardFarExit_doesNotLeave() {
        val locator = locatorAt("NTP-0-H450")
        // Azimuti koraka sa snimka (hod-20261009-112308, 362-383 s): hodnikom do kraja, pa po holu.
        val azimuths = listOf(
            249, 246, 243, 232, 223, 210, 200, 177, 184, 200, 217, 230, 227, 224, 224, 223, 229, 230, 235, 235, 236, 231,
            228, 228, 222, 220, 220, 214, 210, 201, 191, 183,
        )
        val reasons = azimuths.mapNotNull { locator.step(it.toFloat(), stepM) }
        assertTrue("$reasons", reasons.isEmpty())
        assertEquals(PdrPlace("NTP", 0), locator.place)
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

    /**
     * Isti trem, a odredište je na -1: tamo se i stiže (teren 16.10.2026: dva puta iz trema pravo niz srednji krak na -1, a tačka
     * je otišla u prizemlje; po koracima na podestu se gore i dole ne razlikuju).
     */
    @Test
    fun kulaToAmf_throughPorch_toDestinationLevel() {
        val locator = locatorAt(inner("KULA-0-PROLAZ-AMF").id)
        locator.destination = PdrPlace("AMF", -1)
        locator.passThrough("KULA-0-PROLAZ-AMF", "AMF-m1-PROLAZ-KULA")
        assertEquals(PdrPlace("AMF", -1), locator.place)
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

    /** Druga rekalibracija: ukupna greška kompasa i koliko je promašio uz ispravku od prve; ispravka = prosek merenja. */
    @Test
    fun secondRecalibration_reportsTotalAndResidual() {
        val locator = outdoorLocator()
        locator.walkEastAndMark(outdoor, 20, compassErrorDeg = 40f)
        val from = PointM(outdoor.x + 20 * stepM, outdoor.y)
        val check = locator.walkEastAndMark(from, 20, compassErrorDeg = 50f) as HeadingCheck.Measured
        assertEquals(50f, check.errorDeg, 0.5f)
        assertEquals(10f, check.residualDeg, 0.5f)
        assertEquals(45f, locator.headingErrorDeg!!, 0.5f)
        assertEquals(-45f, locator.headingBiasDeg, 0.5f)
    }

    /**
     * Zajednička ispravka (teren 09.10.2026: greška +4..+17° i napolju i u svim zgradama): izmerena napolju važi i u zgradi,
     * ostaje posle Reset-a, i prenosi se ([learnedHeadingErrors] / [PdrLocator.restoreHeadingErrors]); prosek poslednjih 5.
     */
    @Test
    fun sharedCorrection_measuredOutside_appliesInBuilding_keptAfterReset() {
        val locator = outdoorLocator()
        locator.walkEastAndMark(outdoor, 20, compassErrorDeg = 12f)
        locator.setPosition(PdrPlace("NB", 0), Offset(node("NB-0-ULAZ").x, node("NB-0-ULAZ").y))
        assertEquals(-12f, locator.headingBiasDeg, 0.5f)
        locator.reset()
        assertEquals(12f, locator.headingErrorDeg!!, 0.5f)
        val other = PdrLocator(graph, campus, declination, stairPaths).apply { restoreHeadingErrors(locator.learnedHeadingErrors) }
        assertEquals(-12f, other.headingBiasDeg, 0.5f)
        other.restoreHeadingErrors(listOf(1f, 2f, 3f, 4f, 5f, 6f, 7f))
        assertEquals(5f, other.headingErrorDeg!!, 1e-4f)
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

    /**
     * Ispravka iz pravca hodnika (12.10.2026): hod hodnikom NB prizemlja (istočni deo - duge ivice; zapadni deo je podeljen
     * na ivice kraće od 3 m i tu se ne uči) sa kompasom +10° - ispravka ide ka −10°; posle 20 min bez učenja se zaboravlja
     * (09.10.2026: jutarnja iz NTP-a je uveče u NB-u bila pogrešna). Isključena ispravka ne uči.
     */
    @Test
    fun corridorWalk_learnsCompassError_forgottenAfterTwentyMinutes() {
        val azimuth = magneticAzimuth(graph.position(node("NB-0-H744_420")), graph.position(node("NB-0-H1015_420"))) + 10f
        val off = locatorAt("NB-0-H744_420").apply { applyCorrection = false }
        repeat(30) { off.step(azimuth, stepM, timeNs = it * 600_000_000L) }
        assertEquals(0f, off.corridorBiasDeg, 0f)

        val locator = locatorAt("NB-0-H744_420")
        repeat(30) { locator.step(azimuth, stepM, timeNs = it * 600_000_000L) }
        assertEquals(PdrPlace("NB", 0), locator.place)
        assertTrue("naučeno ${locator.corridorBiasDeg}", locator.corridorBiasDeg in -10f..-2f)
        assertEquals(locator.corridorBiasDeg, locator.headingBiasDeg, 1e-6f)
        val learned = locator.corridorBiasDeg
        locator.step(azimuth, stepM, timeNs = 30 * 600_000_000L + 10 * 60_000_000_000L)
        assertTrue("posle 10 min još važi", locator.corridorBiasDeg <= learned)
        locator.step(azimuth, stepM, timeNs = 31 * 600_000_000L + 31 * 60_000_000_000L)
        // Isti korak posle brisanja već ponovo uči (deo stepena).
        assertEquals(0f, locator.corridorBiasDeg, 0.5f)
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

    /**
     * Teren 10.10.2026, NTP: detektor je od ranije potvrdio "napolju" (GPS tik uz zid, izlazak blokiran), a prva lokacija
     * posle Start-a (poslednja poznata, tačnost 25 m) je izbacila korisnika iz hola. Izlazak samo uz tačnu lokaciju.
     */
    @Test
    fun gpsOutsideConfirmedEarlier_inaccurateFix_noExit() {
        val ntp = campus.building("NTP")!!
        val door = graph.position(node("NTP-0-ULAZ-FTN"))
        fun outside(wallM: Double): PointM = (0 until 360 step 2).flatMap { a ->
            (1..40).map { r -> PointM(door.x + r * Math.cos(Math.toRadians(a.toDouble())), door.y + r * Math.sin(Math.toRadians(a.toDouble()))) }
        }.filter { p -> campus.buildings.none { it.contains(p) } && campus.buildings.all { it.distanceToWallM(p) >= 3.5 } }
            .minBy { kotlin.math.abs(ntp.distanceToWallM(it) - wallM) }
        val locator = locatorAt(inner("NTP-0-ULAZ-FTN").id)
        assertTrue(List(4) { locator.onGps(GpsFix(outside(5.0), 3f), tracking = true) }.all { it == null })
        assertNull(locator.onGps(GpsFix(outside(20.0), 25f), tracking = true))
        // Teren 12.10.2026, NTP: na stepeništu uz staklenu fasadu lokacije tačnosti 19 m i 11,6 m - nije izlazak.
        assertNull(locator.onGps(GpsFix(outside(20.0), 19.2f), tracking = true))
        assertNull(locator.onGps(GpsFix(outside(20.0), 11.6f), tracking = true))
        assertEquals(PdrPlace("NTP", 0), locator.place)
        assertEquals(PlaceReason.GPS_IZLAZ, locator.onGps(GpsFix(outside(20.0), 4f), tracking = true))
    }

    /** Proba kod kuće (11.10.2026): tačna GPS lokacija daleko van mape kampusa ne izbacuje iz zgrade ni ne pomera tačku. */
    @Test
    fun gpsOffCampusMap_ignored() {
        val home = PointM(-3000.0, 2500.0)
        val inside = locatorAt(inner("NTP-0-ULAZ-FTN").id)
        assertTrue(List(10) { inside.onGps(GpsFix(home, 4f), tracking = true) }.all { it == null })
        assertEquals(PdrPlace("NTP", 0), inside.place)
        val outdoors = PdrLocator(graph, campus, declination, stairPaths).apply { setPosition(PdrPlace.CAMPUS, campusOffset(PointM(200.0, 150.0))) }
        val before = outdoors.position()
        assertTrue(List(10) { outdoors.onGps(GpsFix(home, 4f), tracking = true) }.all { it == null })
        assertTrue(distance(outdoors.position(), before) < 0.01)
    }

    /**
     * Teren 10.10.2026, NTP: izašao kroz glavni ulaz i stajao ispred vrata (beleška, sitni koraci), a GPS je tačku povukao
     * u obris - 33 s posle izlaska "ušao" opet. Kroz ulaz kroz koji je izašao ulazi se samo koracima kroz vrata.
     */
    @Test
    fun exitedThroughDoor_standingOutsideGpsPullsIn_noReentry_walkingIn_enters() {
        val locator = locatorAt(inner("NTP-0-ULAZ").id)
        val (out, _) = locator.passThrough("NTP-0-ULAZ", "K-U-NTP-3")
        assertEquals(PlaceReason.PROLAZ, out.last())
        assertTrue(locator.place.isCampus)
        val door = graph.position(node("K-U-NTP-3"))
        val inward = inwardOf("NTP-0-ULAZ")
        val wall = wallDirection("NTP", door)
        // GPS 4 m u obrisu, 4 m uz zid od vrata; sitni koraci uz zid.
        val pulled = PointM(door.x + inward.x * 4 + wall.x * 4, door.y + inward.y * 4 + wall.y * 4)
        assertTrue(campus.building("NTP")!!.contains(pulled))
        val along = magneticAzimuth(door, PointM(door.x + wall.x, door.y + wall.y))
        val reasons = (1..20).flatMap { i ->
            listOfNotNull(locator.onGps(GpsFix(pulled, 3f), tracking = true), locator.step(along + if (i % 2 == 0) 180f else 0f, 0.3f))
        }
        assertTrue("$reasons", PlaceReason.ULAZ !in reasons)
        assertTrue(locator.place.isCampus)
        // Kroz vrata ka unutra - ulazi.
        val back = PdrLocator(graph, campus, declination, stairPaths).apply {
            setPosition(PdrPlace.CAMPUS, campusOffset(PointM(door.x - inward.x * 3, door.y - inward.y * 3)))
        }
        val walkIn = magneticAzimuth(door, PointM(door.x + inward.x, door.y + inward.y))
        assertTrue(PlaceReason.ULAZ in (1..10).mapNotNull { back.step(walkIn, stepM) })
    }

    /**
     * Teren 10.10.2026, NTP: GPS i PDR su korisnika držali ~9 m od glavnog ulaza (uz zid), pa je kroz vrata i hol tačka
     * "ušla" tek duboko u obrisu - 16 m od vrata, na granu sale 001. Sada na ulaz pored koga je trag prošao, pa koraci od
     * tog mesta kroz zgradu.
     */
    @Test
    fun deepInsideOutline_afterPassingEntrance_entersThereWithLaterSteps() {
        val ntp = campus.building("NTP")!!
        val door = graph.position(node("K-U-NTP-3"))
        val inward = inwardOf("NTP-0-ULAZ")
        val wall = wallDirection("NTP", door)
        val sides = listOf(1.0, -1.0).map { s -> PointM(door.x - inward.x * 3 + wall.x * 8 * s, door.y - inward.y * 3 + wall.y * 8 * s) }
        val start = sides.first { p ->
            val deep = PointM(p.x + inward.x * 22, p.y + inward.y * 22)
            ntp.contains(deep) && ntp.distanceToWallM(deep) >= 16
        }
        val locator = PdrLocator(graph, campus, declination, stairPaths).apply { setPosition(PdrPlace.CAMPUS, campusOffset(start)) }
        val walkIn = magneticAzimuth(door, PointM(door.x + inward.x, door.y + inward.y))
        var steps = 0
        while (locator.place.isCampus) {
            locator.step(walkIn, stepM)
            check(++steps < 60) { "nije ušao" }
        }
        assertEquals(PdrPlace("NTP", 0), locator.place)
        // Od mesta gde je trag prošao pored vrata (3 m ispred njih) do ulaska: koraka * dužina - 3 m kroz vrata.
        val walkedIn = steps * stepM - 3.0
        val inside = graph.position(node("NTP-0-ULAZ"))
        assertTrue("${distance(locator.position(), inside)} m od ulaza, hodao ${walkedIn} m", distance(locator.position(), inside) <= walkedIn + 2)
        val point = locator.match!!.point
        assertTrue("na hodniku", listOf(point.from, point.to).none { it.type == NodeType.PROSTORIJA || it.type == NodeType.VRATA })
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
        // 9 koraka uz krak (12 gazišta): okret pre 60 % kraka nije međupodest (FULL_FLIGHT_STAIRS, 12.10.2026).
        repeat(9) { locator.step(away, stepM) }
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

    /**
     * Teren 12.10.2026 (snimak 175756, NB II -> III): krak, okret ~100° udesno, 5 koraka popreko preko širokog međupodesta
     * (iza lifta), okret do 180°, krak. Tačka je posle 4 koraka pravo vraćena na dno kraka, a niz popreko je bio "let" -
     * sprat se nije promenio.
     */
    @Test
    fun nbStairs_walkAcrossWideLanding_goesUp() {
        val (locator, away) = atStairs("NB/S", 2)
        val headings = List(12) { away } + listOf(15f, 45f, 75f, 95f).map { away + it } + List(5) { away + 98f } +
            listOf(115f, 135f, 150f, 165f).map { away + it } + List(9) { away + 180f }
        val reasons = headings.mapNotNull { locator.step(it, stepM) }
        assertEquals(listOf(PlaceReason.STEPENICE), reasons)
        assertEquals(PdrPlace("NB", 3), locator.place)
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

    /**
     * Analiza promašaja 12.10.2026 (NTP II 08.10., NTP P 10.10., NB P 09.10.): korak 70-87° od pravca ka stepeništu je
     * puštao tačku na krak dok je korisnik išao pored stepeništa - sada samo korak do 50° od tog pravca ili pravca kraka.
     */
    @Test
    fun besideStairNode_stepAcross_doesNotClimb() {
        val foot = node("NB-1-S")
        val corridor = stairs.getValue(stairKey(foot)).corridor(foot)
        val footM = graph.position(foot)
        val corridorM = graph.position(corridor)
        val t = (1.0 / distance(footM, corridorM)).coerceAtMost(0.5).toFloat()
        val locator = PdrLocator(graph, campus, declination, stairPaths).apply {
            setPosition(PdrPlace("NB", 1), Offset(foot.x + (corridor.x - foot.x) * t, foot.y + (corridor.y - foot.y) * t))
        }
        val toward = magneticAzimuth(corridorM, footM)
        val flight = upFlightAzimuth("NB/S", 1)
        val across = listOf(toward + 70f, toward - 70f).maxBy { abs(angleDiffDeg(it, flight)) }
        assertTrue(abs(angleDiffDeg(across, flight)) > 50f)
        repeat(3) {
            assertNull(locator.step(across, stepM))
            assertNotNull("ušao na stepenište (korak ${it + 1})", locator.match)
        }
        assertEquals(PdrPlace("NB", 1), locator.place)
    }

    /**
     * Silazak sa stepeništa posle drugog kraka (koraci pravo dalje): tačka je na ivici dno kraka - hodnik koliko je tim
     * koracima prešla, ne na dnu kraka (analiza promašaja 12.10.2026, NTP II 08.10.: tačka je ostala na dnu kraka, korisnik je
     * skrenuo hodnikom, posle 3 koraka je opet ušla na krak - 25 m).
     */
    @Test
    fun afterSecondFlight_leavingStraight_pointTowardCorridor() {
        val (locator, away) = atStairs("NB/S", 1)
        locator.flights(away, 180f)
        val downFoot = node("NB-2-S-D")
        val footM = graph.position(downFoot)
        val corridorM = graph.position(stairs.getValue(stairKey(downFoot)).corridor(downFoot))
        var steps = 0
        while (locator.match == null) {
            locator.step(away + 180f, stepM)
            check(++steps < 15) { "nije sišao sa stepeništa" }
        }
        assertEquals(PdrPlace("NB", 2), locator.place)
        assertTrue("na ivici ka hodniku", distanceToSegment(locator.position(), footM, corridorM) < 0.1)
        assertTrue("na dnu kraka: ${distance(locator.position(), footM)} m", distance(locator.position(), footM) > 1.0)
    }

    /**
     * Teren 13.10.2026, NB II -> III: "malo je zakasnelo prešao sa stepenica na ravni deo, nekih 3 koraka kasnije" - posle kraja
     * drugog kraka tačka je stajala na vrhu dok se izlazak ne potvrdi. Sada svaki korak dalje pomera tačku ka hodniku.
     */
    @Test
    fun afterSecondFlight_stepsBeforeExitConfirmed_pointMovesTowardCorridor() {
        val (locator, away) = atStairs("NB/S", 1)
        locator.flights(away, 180f)
        val footM = graph.position(node("NB-2-S-D"))
        val shown = mutableListOf<Double>()
        while (locator.match == null) {
            locator.step(away + 180f, stepM)
            shown += distance(locator.position(), footM)
            check(shown.size < 15) { "nije sišao sa stepeništa" }
        }
        // Poslednja dva koraka pre izlaska (dok se čeka potvrda) već idu od vrha kraka ka hodniku.
        val (a, b) = shown.dropLast(1).takeLast(2)
        assertTrue("pre izlaska: $shown", a > 0.3 && b > a)
    }

    // Znak stepenica iz ubrzanja ([StairGait], analiza 15.10.2026): ~0 po ravnom, ~1 na kraku pri silasku.
    private val flatGait = 0f
    private val stairGait = 1f

    /**
     * Analiza 15.10.2026: pri silasku je tačka ulazila u stepenište medijana 7-8 koraka pre nego što ubrzanje pokaže krak (hod
     * po podestu sprata ka kraku) i ti koraci su je spuštali niz krak. Dok koraci liče na ravan hod, tačka čeka na vrhu kraka.
     */
    @Test
    fun descent_flatStepsOnLanding_pointWaitsAtTopOfFlight() {
        val (locator, _) = atStairNode("NB-2-S-D")
        val path = stairPath("NB/S", 2)
        val down = magneticAzimuth(path[3], path[2])
        repeat(4) { assertNull(locator.step(down, stepM, stairScore = flatGait)) }
        assertTrue("čeka na vrhu kraka", distance(locator.position(), path[3]) < 0.1)
        repeat(3) { locator.step(down, stepM, stairScore = stairGait) }
        val shown = distance(locator.position(), path[3])
        assertTrue("krenuo niz krak: $shown m", shown > 0.4 * distance(path[3], path[2]) * 3 / 11)
        assertTrue("na kraku", distanceToSegment(locator.position(), path[3], path[2]) < 0.1)

        // Bez znaka (džep, nema baze) - kao ranije: tačka kreće odmah.
        val (old, _) = atStairNode("NB-2-S-D")
        repeat(4) { old.step(down, stepM) }
        assertTrue(distance(old.position(), path[3]) > 0.5)
    }

    /** Silazak sa II na I, drugi krak; posle [secondSteps] koraka drugog kraka još [after] koraka pravo sa znakom [gait]. */
    private fun descentThenStraight(secondSteps: Int, after: Int, gait: Float?): Pair<PdrLocator, Int?> {
        val (locator, _) = atStairNode("NB-2-S-D")
        val path = stairPath("NB/S", 2)
        val down = magneticAzimuth(path[3], path[2])
        val headings = List(10) { down } + (1..4).map { down - 180f * it / 5 } + List(secondSteps) { down - 180f }
        assertEquals(listOf(PlaceReason.STEPENICE), headings.mapNotNull { locator.step(it, stepM, stairScore = stairGait) })
        assertEquals(PdrPlace("NB", 1), locator.place)
        var left: Int? = null
        for (i in 1..after) {
            locator.step(down - 180f, stepM, stairScore = gait)
            if (locator.match != null) {
                left = i
                break
            }
        }
        return locator to left
    }

    /** Poslednji krak silaska: koraci već liče na ravan hod pre očekivanog broja gazišta - sišao je, tačka izlazi sa stepeništa. */
    @Test
    fun descent_flatStepsBeforeTreadCount_leavesStairs() {
        val (_, flat) = descentThenStraight(secondSteps = 6, after = 10, gait = flatGait)
        // Medijana poslednja 3 koraka: dva koraka po ravnom su dovoljna.
        assertEquals("izlazak na drugom koraku po ravnom", 2, flat)
        // Bez znaka: tek posle preostalih gazišta i 3 koraka pravo.
        val (_, old) = descentThenStraight(secondSteps = 6, after = 15, gait = null)
        assertTrue("bez znaka $old", old!! > 5)
    }

    /** Posle očekivanog broja gazišta koraci i dalje liče na stepenice - krak je duži, tačka još ne izlazi (najviše 4 koraka). */
    @Test
    fun descent_stillStairLikeAfterTreadCount_staysOnStairs() {
        val (onStairs, stillStairs) = descentThenStraight(secondSteps = 11, after = 4, gait = stairGait)
        assertNull("još na stepeništu", stillStairs)
        assertNull(onStairs.match)
        val (_, old) = descentThenStraight(secondSteps = 11, after = 4, gait = null)
        assertEquals(3, old)
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
     * Teren 09.10.2026 uveče, NTP S1 III -> II -> I -> P (hod-20261009-182930: azimut, vreme u 0,1 s). Na I spratu je okret
     * ka sledećem kraku počinjao postepeno posle kraja kraka od 12 gazišta, pa je tačka izlazila sa stepeništa i I -> P nije
     * prepoznato ("posle toga na prizemlje nije lepo prešao"; NTP krak ima ~14 koraka).
     */
    @Test
    fun ntpStairs_threeFloorsDown_gradualTurnOnLandings() {
        val steps = listOf(
            327 to 8, 326 to 11, 323 to 19, 324 to 26, 325 to 37, 326 to 43, 327 to 50, 328 to 55, 330 to 61, 331 to 67,
            334 to 73, 335 to 80, 335 to 86, 335 to 91, 335 to 98, 335 to 103, 331 to 109, 328 to 115, 318 to 119, 288 to 125,
            257 to 133, 236 to 139, 207 to 147, 183 to 153, 159 to 163, 154 to 169, 154 to 175, 155 to 182, 154 to 188, 153 to 194,
            152 to 200, 151 to 206, 152 to 213, 154 to 219, 155 to 226, 155 to 232, 154 to 238, 150 to 244, 141 to 250, 123 to 257,
            103 to 263, 78 to 269, 54 to 276, 32 to 282, 9 to 289, 348 to 296, 335 to 305, 333 to 311, 334 to 318, 337 to 324,
            337 to 331, 338 to 336, 340 to 342, 338 to 347, 339 to 353, 340 to 359, 338 to 365, 336 to 371, 334 to 376, 329 to 382,
            321 to 386, 304 to 394, 285 to 400, 255 to 406, 228 to 413, 205 to 420, 187 to 427, 173 to 432, 156 to 441, 149 to 447,
            146 to 453, 148 to 459, 149 to 465, 149 to 471, 147 to 477, 149 to 483, 151 to 490, 152 to 496, 152 to 503, 149 to 509,
            142 to 515, 136 to 523, 130 to 528, 116 to 535, 94 to 542, 68 to 549, 43 to 557, 25 to 564, 5 to 572, 350 to 578,
            338 to 588, 332 to 594, 326 to 600, 324 to 607, 326 to 614, 328 to 620, 329 to 626, 329 to 633, 325 to 639, 319 to 646,
            316 to 652, 314 to 659, 309 to 665, 303 to 672, 293 to 677, 276 to 683, 259 to 689, 238 to 695, 219 to 703, 206 to 710,
            187 to 719, 173 to 724, 157 to 733, 150 to 743, 147 to 749, 145 to 756, 144 to 762, 143 to 768, 144 to 775, 147 to 782,
        )
        val locator = PdrLocator(graph, campus, declination, stairPaths).apply { setPosition(PdrPlace("NTP", 3), Offset(0.5409f, 0.470082f)) }
        val reasons = steps.mapNotNull { (azimuth, t) -> locator.step(azimuth.toFloat(), 0.75f, timeNs = t * 100_000_000L) }
        assertEquals(List(3) { PlaceReason.STEPENICE }, reasons)
        assertEquals(PdrPlace("NTP", 0), locator.place)
    }

    /**
     * Teren 09.10.2026 uveče, NTP III: "Ovde sam" pored S1, pa hod ka hodniku ~90° od kraka (hod-20261009-182421) - tačka je
     * krenula krakom (prag 100° od smera kraka), korisnik je dvaput ponovo pokrenuo praćenje. Korak bliži smeru ka hodniku
     * nego smeru kraka nije na kraku.
     */
    @Test
    fun besideStairs_walkingTowardCorridor_staysOnFloor() {
        val locator = PdrLocator(graph, campus, declination, stairPaths).apply { setPosition(PdrPlace("NTP", 3), Offset(0.55188125f, 0.43177563f)) }
        val s1 = graph.position(node("NTP-3-S1"))
        listOf(59, 58).forEach { locator.step(it.toFloat(), 0.75f) }
        // Ranije: prvi korak na krak, drugi nazad na čvor S1, pa opet na krak čim je tačka bila do 1,5 m od njega.
        assertNotNull("na grafu hodnika", locator.match)
        assertTrue("odmakao se od stepeništa", distance(locator.position(), s1) > 1.5)
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
            // Krak do podesta: 9 koraka (NB krak ima 12 gazišta; okret pre 60 % kraka nije podest - [PdrLocator] FULL_FLIGHT_STAIRS).
            val headings = List(9) { up } + listOf(up + 45f, up + 90f) + List(4) { up + 90f } + listOf(up + 45f) + List(12) { up }
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
    fun flightsNextToStairs_changeFloor_farAway_doNot() {
        // Let ide pravcem krakova (od 10.10.2026 uslov za stepenište sa nacrtanim krakovima), drugi let bar 9 koraka.
        val axis = upFlightAzimuth("NB/S", 0)
        val near = locatorAt("NB-0-H653_420")
        assertEquals(listOf(PlaceReason.STEPENICE), near.flights(axis, 180f, first = 8, second = 10))
        assertEquals(PdrPlace("NB", 1), near.place)
        // Okret udesno je penjanje: na I sprat se stiže desnim krakom.
        val path1 = stairPath("NB/S", 1)
        assertTrue(distanceToSegment(near.position(), path1[2], path1[3]) < 0.1)
        val far = locatorAt("NB-0-H744_420")
        assertTrue(far.flights(axis, 180f, first = 8, second = 10).isEmpty())
        assertEquals(PdrPlace("NB", 0), far.place)
    }

    /**
     * Teren 10.10.2026, NB I sprat (snimak 12:30:07): hod po holu popreko na krakove, petlja od ~280° udesno, pa hod
     * pravcem kraka - bila je to lažna promena na II sprat. Pa hod tamo-amo pravcem krakova sa kratkim drugim "letom"
     * (8 koraka) - posle prve ispravke lažno I -> P. Ništa od toga nije stepenište.
     */
    @Test
    fun hallNextToStairs_acrossFlightsOrShortSecondRun_noFloorChange() {
        // Azimuti koraka sa snimka (hod-20261010-123007, 119-160 s): izlazak sa stepeništa u hol, hol popreko na krakove,
        // petlja udesno, pravcem kraka, okret ulevo, pa nazad.
        // Od 12.10.2026 (tačka više ne ulazi u granu lifta, pa stigne do kraka) samo do okreta nazad: 10 koraka pravcem kraka,
        // okret ulevo i nazad je na NB isto što i silazak desnim krakom (oba kraka polaze ka severu) - iz azimuta se ne
        // razlikuje. Na snimku (sa vremenom i ispravkom smera) lažne promene nema.
        val azimuths = listOf(
            132.1, 149.9, 160.2, 160.5, 147.2, 127.0, 100.3, 75.3, 60.2, 44.4, 15.3, 350.2, 340.4, 338.6, 338.5, 338.9, 339.0,
            338.7, 337.8, 334.7, 329.7, 333.7, 11.5, 53.7, 109.3, 147.8, 166.7, 172.8, 177.9, 182.6, 191.3, 210.8, 232.9, 244.5,
            252.0, 255.5, 256.9, 257.0, 254.2, 251.1, 250.3, 249.3, 247.0, 238.7,
        )
        val locator = locatorAt("NB-1-H653_423")
        val reasons = azimuths.mapNotNull { locator.step(it.toFloat(), stepM) }
        assertTrue("$reasons", reasons.isEmpty())
        assertEquals(PdrPlace("NB", 1), locator.place)
        // Tamo-amo pravcem krakova, drugi "let" od 8 koraka.
        val axis = upFlightAzimuth("NB/S", 1)
        val back = locatorAt("NB-1-H653_423")
        assertTrue(back.flights(axis, -180f, first = 10, second = 8).isEmpty())
        assertEquals(PdrPlace("NB", 1), back.place)
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

    /**
     * Teren 12.10.2026 (snimak 180423, 459-483 s): sa kraja hodnika prizemlja koso na levi bočni krak, niz njega, okret na
     * podestu i srednjim krakom u suteren. Levog kraka u grafu nije bilo, a tačka je ivicu hodnik - S1-D (0,5 m) preskočila i
     * išla krakom kao hodnikom - sprat se nije promenio ("graf prepoznaje samo jednu stranu stepenica").
     */
    @Test
    fun amfS1_fromGroundCorridor_leftSideFlightDown_thenMiddleFlight_toBasement() {
        // Tačka je ivicu hodnik - S1-D već preskočila: na vrhu levog kraka.
        val locator = locatorAt("AMF-0-S1-D-KRAK-L")
        val reasons = mutableListOf<PlaceReason>()
        val down = magneticAzimuth(graph.position(node("AMF-0-S1-D-KRAK-L")), graph.position(node("AMF-0-PODEST-S1-D-L")))
        // Na snimku 8 koraka niz krak (kratki koraci na stepenicama).
        repeat(8) { locator.step(down, 0.5f)?.let(reasons::add) }
        // Okret na podestu (180°) i srednjim krakom naniže.
        (1..4).forEach { locator.step(down - 45f * it, 0.5f)?.let(reasons::add) }
        repeat(10) { locator.step(down - 180f, 0.5f)?.let(reasons::add) }
        assertTrue("$reasons", PlaceReason.STEPENICE in reasons)
        assertEquals(PdrPlace("AMF", -1), locator.place)
    }

    /**
     * Teren 16.10.2026 (a-m1): prizemljem do S1, levom trakom niz levi bočni krak, okret na podestu ulevo i srednjim krakom na -1
     * (azimuti sa snimka, uz ispravku smera tog trenutka -11°). Tačka je išla desnim krakom, a okret na podestu je posle 8 koraka
     * srednjim krakom proglašen povratkom gore: hodnik prizemlja ide istim pravcem kao bočni krak, pa je "prvi let" sa hodom
     * kroz hodnik bio duži od 24 koraka i promena sprata nije priznata ("aplikacija misli da idem opet na prizemlje").
     */
    @Test
    fun amfS1_fromGroundCorridor_downSideFlight_turnOnLanding_middleFlightToBasement() {
        val locator = locatorAt("AMF-0-H250_-10")
        // (magnetski azimut, dužina koraka po vrhu) sa snimka.
        val steps = listOf(
            39f to 0.75f, 44f to 0.75f, 95f to 0.75f, 91f to 0.70f, 90f to 0.75f, 87f to 0.75f, 85f to 0.75f, 86f to 0.75f,
            86f to 0.75f, 86f to 0.75f, 82f to 0.75f, 75f to 0.75f, 76f to 0.75f, 76f to 0.75f, 75f to 0.75f, 71f to 0.75f,
            67f to 0.75f, 64f to 0.75f, 59f to 0.32f, 52f to 0.75f, 46f to 0.75f, 42f to 0.35f, 42f to 0.20f, 45f to 0.25f,
            56f to 0.75f, 57f to 0.75f, 54f to 0.75f, 55f to 0.75f, 60f to 0.75f, 63f to 0.75f, 62f to 0.75f, 58f to 0.75f,
            54f to 0.75f, 53f to 0.75f, 54f to 0.75f, 55f to 0.75f, 56f to 0.75f, 55f to 0.75f, 52f to 0.75f, 47f to 0.75f,
            42f to 0.75f, 32f to 0.75f, 24f to 0.75f, 5f to 0.32f, 340f to 0.75f, 301f to 0.44f, 270f to 0.36f, 248f to 0.75f,
            236f to 0.75f, 233f to 0.75f, 233f to 0.75f, 234f to 0.75f, 236f to 0.75f, 238f to 0.75f, 238f to 0.75f, 240f to 0.75f,
            241f to 0.75f, 241f to 0.24f, 240f to 0.23f, 241f to 0.75f, 240f to 0.75f, 237f to 0.34f, 234f to 0.75f, 236f to 0.75f,
        )
        // Znak stepenica ([StairGait]) tih koraka - silazak čeka na vrhu kraka dok koraci ne liče na stepenice.
        val gait = listOf(
            -1.33f, -0.57f, -0.04f, -0.59f, -0.41f, -0.38f, -0.11f, -0.11f, 0.11f, 0.25f, -0.01f, 0.43f, 0.52f, 0.26f, 0.23f, 0.78f,
            0.28f, 0.06f, 0.62f, 0.41f, 0.81f, 0.06f, 0.07f, -0.17f, -0.62f, 0.40f, 0.23f, 0.44f, 0.29f, -0.10f, 0.60f, 1.25f, 0.75f,
            1.19f, 0.66f, 0.80f, 0.90f, 1.15f, 0.67f, 1.02f, 1.05f, 0.23f, 0.87f, -0.32f, -0.73f, -0.76f, -0.60f, -1.09f, 0.48f, 0.82f,
            0.48f, 0.45f, 0.29f, 0.83f, -0.25f, 0.83f, 0.47f, 0.66f, 0.37f, 0.05f, 0.76f, 0.41f, 0.44f, 0.07f,
        )
        val reasons = steps.zip(gait).mapIndexedNotNull { i, (step, score) ->
            locator.step(step.first - 11f, step.second, timeNs = i * 600_000_000L, stairScore = score)
        }
        assertEquals("$reasons", listOf(PlaceReason.STEPENICE), reasons)
        assertEquals(PdrPlace("AMF", -1), locator.place)
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

    /**
     * Teren 10.10.2026 (ulazak kroz glavni ulaz AMF-a, -1): hodnikom do S1 i srednjim krakom na podest, nekoliko koraka
     * dalje (trem ka Kuli), pa okret nazad i bočnim krakom gore. Tačka je preletela krak kao hodnik, otišla trem u Kulu i
     * vratila se na -1 - sada je međupodest deo oba nivoa: povratak iz trema je okret na podestu, gore u prizemlje.
     */
    @Test
    fun amfS1_fromBasementCorridor_intoPorchAndBack_upToGroundFloor() {
        val locator = locatorAt("AMF-m1-H257_120")
        val south = pathAzimuth("AMF/S1", -1, 0, 1)
        val reasons = mutableListOf<PlaceReason>()
        repeat(28) { locator.step(south, stepM)?.let(reasons::add) }
        assertEquals(PdrPlace("AMF", -1), locator.place)
        val back = (1..4).map { south - 45f * it } + List(16) { south - 180f }
        back.forEach { locator.step(it, stepM)?.let(reasons::add) }
        assertTrue("$reasons", PlaceReason.STEPENICE in reasons && PlaceReason.PROLAZ !in reasons)
        assertEquals(PdrPlace("AMF", 0), locator.place)
    }

    /**
     * Teren 13.10.2026 (hod-20261013-095045, 94-121 s): hodnikom suterena do S1, srednjim krakom na podest, korak-dva ka
     * tremu i odmah okret (~180°) na desni bočni krak u prizemlje - u trem nije ni ušao. Tačka je stajala na vratima trema,
     * map-matching ju je vratio niz srednji krak na -1 (na telefonu i lažan izlazak napolje kroz glavni ulaz).
     */
    @Test
    fun amfS1_fromBasement_upMiddleFlight_turnAtPorchDoor_upToGroundFloor() {
        val azimuths = listOf(
            75.5, 75.2, 73.6, 75.5, 73.1, 71.2, 71.0, 71.0, 71.9, 71.7, 68.8, 66.8, 65.1, 64.7, 67.8, 69.6, 64.7, 62.6, 64.9,
            65.3, 64.6, 64.2, 61.3, 59.5, 62.1, 62.7, 62.7, 62.3, 60.7, 51.9, 29.1, 357.8, 324.3, 300.4, 278.8, 271.2, 265.0,
            258.2, 252.4, 248.7, 242.5, 238.1, 238.2, 239.1, 244.2, 246.9, 246.9, 249.0,
        )
        val locator = locatorAt("AMF-m1-H257_60")
        locator.restoreBias(-8.9f)
        val reasons = azimuths.mapNotNull { locator.step(it.toFloat(), 0.75f) }
        assertTrue("$reasons", PlaceReason.STEPENICE in reasons && PlaceReason.PROLAZ !in reasons)
        assertEquals(PdrPlace("AMF", 0), locator.place)
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

    // Lift: prepoznata vožnja ([LiftRide]) menja sprat po pomeraju; pitanje samo kad broj spratova nije siguran.

    private val sec = 1_000_000_000L

    /** Visina sprata kao u [PdrLocator] (NTP, teren 08.10.2026). */
    private val floorM = 3.75

    private fun ride(floors: Double) = LiftRide(0, 8 * sec, floors * floorM)

    /**
     * Korisnik je iz hodnika ispred lifta [liftId] ušao u lift. Tačka od 12.10.2026 ne ulazi u granu lifta (kao u salu) -
     * ostaje u hodniku ispred njega, a vožnja se pripisuje najbližem liftu.
     */
    private fun atLift(liftId: String): PdrLocator {
        val lift = graph.position(node(liftId))
        val front = graph.position(inner(liftId))
        val locator = locatorAt(inner(liftId).id)
        val azimuth = magneticAzimuth(front, lift)
        val steps = (distance(locator.position(), lift) / stepM).toInt().coerceAtLeast(1)
        for (i in steps - 1 downTo 0) locator.step(azimuth, stepM, timeNs = -i * 600_000_000L)
        assertTrue("ispred lifta", distance(locator.position(), lift) <= distance(front, lift) + 1.0)
        return locator
    }

    /** Azimut od lifta ka hodniku ispred njega. */
    private fun outOfLift(liftId: String) = magneticAzimuth(graph.position(node(liftId)), graph.position(inner(liftId)))

    @Test
    fun lift_rideUp_movesToMeasuredFloor() {
        val locator = atLift("NB-0-L")
        val outcome = locator.liftRide(ride(3.1))
        assertEquals(LiftOutcome.Moved(LiftChange("NB", 0, 3)), outcome)
        assertEquals(PdrPlace("NB", 3), locator.place)
        assertNull(locator.liftPrompt)
        assertNotNull("na grafu novog sprata", locator.match)
        assertTrue(distance(locator.position(), graph.position(node("NB-3-L"))) < 0.5)
    }

    /** Teren 08.10.2026: NTP II -> P liftom L2 (7,5 m), I -> P liftom L1 (3,5 m). */
    @Test
    fun lift_rideDown_ntp() {
        val fromSecond = atLift("NTP-2-L2")
        assertEquals(LiftOutcome.Moved(LiftChange("NTP", 2, 0)), fromSecond.liftRide(LiftRide(0, 8 * sec, -7.5)))
        val fromFirst = atLift("NTP-1-L1")
        assertEquals(LiftOutcome.Moved(LiftChange("NTP", 1, 0)), fromFirst.liftRide(LiftRide(0, 6 * sec, -3.5)))
        assertEquals(PdrPlace("NTP", 0), fromFirst.place)
    }

    /**
     * Teren 12.10.2026, NTP III posle vožnje P -> III: iz L1 se izlazi gore u gornji hodnik, pa levo ka glavnom hodniku i
     * niz njega. Tačka je bila vezana za koso postavljen lift (18 koraka na liftu), pa je prešla u granu toaleta pored njega.
     */
    @Test
    fun ntpLiftExit_upThenLeftIntoMainCorridor() {
        val locator = atLift("NTP-0-L1")
        assertEquals(LiftOutcome.Moved(LiftChange("NTP", 0, 3)), locator.liftRide(LiftRide(0, 9 * sec, 11.0)))
        val lift = graph.position(node("NTP-3-L1"))
        val exit = graph.position(node("NTP-3-U513"))
        val corner = graph.position(node("NTP-3-M595"))
        val main = graph.position(node("NTP-3-M736"))
        repeat(3) { locator.step(magneticAzimuth(lift, exit), stepM) }
        repeat(5) { locator.step(magneticAzimuth(exit, corner), stepM) }
        repeat(5) { locator.step(magneticAzimuth(corner, main), stepM) }
        assertTrue("u glavnom hodniku", distance(locator.position(), main) < 1.5)
    }

    /**
     * Teren 12.10.2026, NTP III: "Ovde sam" tik pored S1 (na kraju glavnog hodnika), pa hodnikom niz hodnik, okret i nazad.
     * Koraci su bili 70-100° od kraka naniže, a tačka je išla krakom i posle okreta "sišla" na II. Azimuti sa snimka
     * (hod-20261012-120310, 1116-1145 s).
     */
    @Test
    fun ntpBesideS1_walkingDownCorridorAndBack_staysOnFloor() {
        val azimuths = listOf(
            11.6, 5.8, 45.2, 58.7, 65.6, 66.2, 67.3, 68.6, 69.2, 68.1, 67.7, 71.3, 73.1, 73.3, 71.7, 67.4, 65.5, 63.1, 57.4,
            44.5, 330.1, 290.4, 263.3, 257.0, 255.9, 258.8, 259.6, 257.5, 258.1, 258.8, 257.3, 252.6, 254.7, 261.9, 265.2,
        )
        val locator = locatorAt("NTP-3-M450")
        val reasons = azimuths.mapNotNull { locator.step(it.toFloat(), stepM) }
        assertTrue("$reasons", PlaceReason.STEPENICE !in reasons)
        assertEquals(PdrPlace("NTP", 3), locator.place)
    }

    /**
     * Teren 13.10.2026 (hod-20261013-101142, 172-214 s; i 12.10. uveče): NTP III -> II stepeništem S1, pa sa podesta II
     * sprata ~8 koraka na jug pored dna sledećeg kraka naniže i istočno ka L1. Pravac sledećeg kraka je istok - tačka je
     * posle 3 koraka opet ušla na njega ("nije otišao na L1, nego je krenuo na stepenice").
     */
    @Test
    fun ntpS1_downOneFloor_thenPastNextFlightTowardLift_staysOnFloor() {
        val azimuths = listOf(
            189.2, 186.2, 173.0, 158.6, 146.0, 137.7, 135.0, 145.0, 169.3, 192.1, 237.8, 270.1, 293.9, 317.7, 323.4, 325.9,
            327.4, 329.2, 330.7, 328.7, 325.7, 324.1, 323.5, 319.9, 314.7, 315.9, 318.5, 318.3, 311.4, 286.3, 253.5, 223.8,
            193.5, 177.6, 157.1, 149.6, 145.1, 144.9, 146.0, 146.0, 138.5, 130.5, 130.3, 134.7, 135.0, 138.3, 141.3, 134.3,
            123.6, 113.6, 96.0, 76.9, 62.5, 50.0, 47.2, 47.3, 44.7, 37.7, 29.0, 8.0, 353.9, 341.2, 334.7, 335.1, 340.2, 350.6,
        )
        val locator = locatorAt("NTP-3-U513")
        locator.restoreBias(-12.2f)
        val reasons = azimuths.mapNotNull { locator.step(it.toFloat(), 0.75f) }
        assertEquals(listOf(PlaceReason.STEPENICE), reasons)
        assertEquals(PdrPlace("NTP", 2), locator.place)
        assertNotNull("na grafu sprata, ne na stepeništu", locator.match)
    }

    /**
     * Teren 16.10.2026 (n-turnoff, azimuti sa snimka, ispravka smera tog trenutka -16,3°): S1 III -> II, pa sa dna kraka pravo
     * nadole u hodnik i ka L1. S1-D je imao izlaz samo nazad ka M450, pa je tačka stajala na stepeništu ("opet se zaglavio na
     * stepenicama, možda treba prilagoditi graf") - stepenište je dole otvoreno - sa S1-D koso pred L1 (U513).
     */
    @Test
    fun ntpS1_downOneFloor_thenStraightIntoLiftCorridor_reachesLift() {
        val azimuths = listOf(
            348.2, 357.3, 8.6, 359.3, 345.9, 335.4, 330.7, 329.9, 327.3, 327.0, 329.1, 330.3, 333.6, 334.4, 334.8, 333.5, 329.1,
            317.9, 310.7, 303.2, 282.6, 264.0, 239.5, 214.3, 191.3, 168.3, 157.7, 155.4, 155.4, 157.4, 159.5, 160.1, 158.4,
            156.3, 156.2, 155.0, 156.1, 157.6, 156.6, 151.7, 139.7, 110.5, 83.9, 59.7, 53.1, 50.0, 49.5, 48.4, 41.9, 29.0, 8.1,
        )
        val locator = locatorAt("NTP-3-M450")
        locator.restoreBias(-16.3f)
        val reasons = azimuths.mapNotNull { locator.step(it.toFloat(), 0.75f) }
        assertEquals(listOf(PlaceReason.STEPENICE), reasons)
        assertEquals(PdrPlace("NTP", 2), locator.place)
        val lift = graph.position(node("NTP-2-U513"))
        assertTrue("tačka ${locator.position()} ${locator.match?.point} daleko od L1 $lift", distance(locator.position(), lift) < 3.0)
    }

    /**
     * Teren 13.10.2026, NTP V -> P liftom L2: −20,3 m = 5,4 sprata (duge vožnje mere malo više). Niže od P lift ne ide ->
     * P, bez pitanja. Preko pola sprata dalje od poslednjeg sprata i dalje pita ([lift_beyondLiftFloors_asks]).
     */
    @Test
    fun lift_beyondLastFloorByLessThanHalf_movesToLastFloor() {
        val locator = atLift("NTP-5-L2")
        assertEquals(LiftOutcome.Moved(LiftChange("NTP", 5, 0)), locator.liftRide(LiftRide(0, 17 * sec, -20.3)))
        assertEquals(PdrPlace("NTP", 0), locator.place)
    }

    /** Stajanje na IV usput (08.10.2026, V -> III): dve vožnje po jedan sprat. */
    @Test
    fun lift_stopOnTheWay_twoRides() {
        val locator = atLift("NTP-5-L1")
        assertEquals(LiftOutcome.Moved(LiftChange("NTP", 5, 4)), locator.liftRide(LiftRide(0, 6 * sec, -3.6)))
        assertEquals(LiftOutcome.Moved(LiftChange("NTP", 4, 3)), locator.liftRide(LiftRide(28 * sec, 34 * sec, -3.6)))
        assertEquals(PdrPlace("NTP", 3), locator.place)
    }

    @Test
    fun lift_uncertainFloors_asks_stepsAfterExitRepeatedOnChosenFloor() {
        val locator = atLift("NB-0-L")
        val outcome = locator.liftRide(ride(2.6))
        assertTrue(outcome is LiftOutcome.Ask)
        val prompt = locator.liftPrompt!!
        assertEquals("NB-0-L", prompt.lift.id)
        assertEquals(0, prompt.fromFloor)
        // Lift NB ide -1 ... IV (na V sprat ne), bez sprata na kome je; predlog je najbliži izmerenom.
        assertEquals(listOf(4, 3, 2, 1, -1), prompt.floors)
        assertEquals(3, prompt.suggested)
        // Izašao iz lifta i hodao pre odgovora (telefon u džepu): 3 koraka ka hodniku.
        val out = outOfLift("NB-0-L")
        repeat(3) { locator.step(out, stepM, timeNs = (20 + it) * sec) }
        assertEquals("dok čeka odgovor, tačka ostaje na spratu", PdrPlace("NB", 0), locator.place)
        assertNull("dok pitanje čeka, nova vožnja se ne obrađuje", locator.liftRide(ride(1.0)))
        assertEquals(PlaceReason.LIFT, locator.selectLiftFloor(3))
        assertEquals(PdrPlace("NB", 3), locator.place)
        assertNull(locator.liftPrompt)
        // Ista 3 koraka od lifta na III spratu (lift je na istom mestu na svakom spratu).
        val walked = distance(locator.position(), graph.position(node("NB-3-L")))
        assertTrue("od lifta ${"%.1f".format(walked)} m", walked in 1.5..2.6)
    }

    @Test
    fun lift_beyondLiftFloors_asks() {
        // NB lift ne ide na V sprat.
        val locator = atLift("NB-0-L")
        val outcome = locator.liftRide(ride(5.0)) as LiftOutcome.Ask
        assertEquals(4, outcome.prompt.suggested)
        assertEquals(PdrPlace("NB", 0), locator.place)
    }

    /** Teren 08.10.2026, V -> III u džepu: stavljanje u džep je napravilo 8 lažnih koraka, tačka ~6-10 m od lifta. */
    @Test
    fun lift_pointWalkedAwayByPocketSteps_stillRidesThatLift() {
        val locator = atLift("NB-0-L")
        val out = outOfLift("NB-0-L")
        repeat(8) { locator.step(out, stepM, timeNs = it * 300_000_000L) }
        assertTrue(distance(locator.position(), graph.position(node("NB-0-L"))) > 3.0)
        assertEquals(LiftOutcome.Moved(LiftChange("NB", 0, 2)), locator.liftRide(ride(2.0)))
        assertTrue(distance(locator.position(), graph.position(node("NB-2-L"))) < 0.5)
    }

    @Test
    fun lift_farFromLiftOrOutside_ignored() {
        // Kraj hodnika ka Kuli je daleko od lifta.
        val atEntrance = locatorAt("NB-0-PROLAZ")
        assertNull(atEntrance.liftRide(ride(2.0)))
        assertEquals(PdrPlace("NB", 0), atEntrance.place)
        assertNull(outdoorLocator().liftRide(ride(2.0)))
        // AMF nema lift.
        assertNull(locatorAt("AMF-0-S1-D").liftRide(ride(1.0)))
    }

    @Test
    fun lift_manualPosition_clearsPrompt() {
        val locator = atLift("NB-0-L")
        locator.liftRide(ride(2.5))
        assertNotNull(locator.liftPrompt)
        val there = node("NB-2-L")
        locator.setPosition(PdrPlace("NB", 2), Offset(there.x, there.y))
        assertNull(locator.liftPrompt)
        assertNull(locator.selectLiftFloor(3))
        assertEquals(PdrPlace("NB", 2), locator.place)
    }

    @Test
    fun lift_dismissed_nextRideHandled() {
        val locator = atLift("NB-0-L")
        locator.liftRide(ride(2.5))
        locator.dismissLift()
        assertNull(locator.liftPrompt)
        assertEquals(PdrPlace("NB", 0), locator.place)
        assertEquals(LiftOutcome.Moved(LiftChange("NB", 0, 1)), locator.liftRide(ride(1.0)))
    }

    @Test
    fun lift_kulaAndNtp_floorsOfThatLift() {
        assertEquals(LiftOutcome.Moved(LiftChange("KULA", 0, 9)), atLift("KULA-0-L").liftRide(ride(9.1)))
        assertEquals(listOf(5, 4, 3, 2, 1), (atLift("NTP-0-L2").liftRide(ride(1.5)) as LiftOutcome.Ask).prompt.floors)
    }
}
