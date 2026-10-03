package com.example.ftnnavigation.poc

import androidx.compose.ui.geometry.Offset
import com.example.ftnnavigation.campus.BuildingDetector
import com.example.ftnnavigation.campus.CAMPUS_ID
import com.example.ftnnavigation.campus.CampusData
import com.example.ftnnavigation.campus.GpsFix
import com.example.ftnnavigation.campus.contains
import com.example.ftnnavigation.campus.distanceToWallM
import com.example.ftnnavigation.graph.BuildingGraph
import com.example.ftnnavigation.graph.EdgeType
import com.example.ftnnavigation.graph.INDOOR_BUILDINGS
import com.example.ftnnavigation.graph.IndoorBuilding
import com.example.ftnnavigation.graph.MapMatcher
import com.example.ftnnavigation.graph.MatchedPosition
import com.example.ftnnavigation.graph.NbPlan
import com.example.ftnnavigation.graph.Node
import com.example.ftnnavigation.graph.NodeType
import com.example.ftnnavigation.graph.PointM
import com.example.ftnnavigation.graph.indoorBuilding
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Gde je PDR pozicija: sprat zgrade sa planom, ili kampus ([CAMPUS_ID], sprat 0). */
data class PdrPlace(val buildingId: String, val floor: Int = 0) {
    val isCampus: Boolean get() = buildingId == CAMPUS_ID

    /** Zgrada sa planom; null na kampusu. */
    val building: IndoorBuilding? get() = indoorBuilding(buildingId)

    companion object {
        val CAMPUS = PdrPlace(CAMPUS_ID)
    }
}

/** Zašto je pozicija preskočila (za praćenje Mape i snimak). */
enum class PlaceReason {
    /** Korisnik je označio gde je (start ili rekalibracija). */
    RUCNO,

    /** Sa kampusa kroz ulaz u zgradu sa planom. */
    ULAZ,

    /** Iz zgrade kroz vrata ili spojni prolaz (u drugu zgradu ili na kampus). */
    PROLAZ,

    /** GPS je potvrdio da je korisnik napolju, a PDR je još bio u zgradi. */
    GPS_IZLAZ,

    /** GPS se uporno ne slaže sa PDR-om napolju - pozicija prebačena na GPS. */
    GPS_SKOK,
}

// Napolju: koraci + GPS (jednodimenzionalni Kalman po poziciji). Vrednosti su procena, NISU proverene na terenu.

/** Nesigurnost koju doda jedan korak (m²): smer i dužina koraka nisu tačni. */
private const val STEP_VARIANCE_M2 = 0.5

/** GPS lošiji od ovoga se napolju ne koristi. */
private const val MAX_FUSE_ACCURACY_M = 30f

/** GPS dalji od pozicije od ovoliko sigma je sumnjiv (skok), ne povlači tačku. */
private const val GATE_SIGMAS = 3.0

/** Posle ovoliko uzastopnih sumnjivih GPS lokacija veruje se GPS-u (PDR je odlutao). */
private const val MAX_GPS_OUTLIERS = 5

/** Nesigurnost pozicije na ulazu / posle ručnog označavanja (m²). */
private const val KNOWN_VARIANCE_M2 = 4.0

// Prelazi.

/** Ulazak: tačka bar ovoliko unutar obrisa zgrade sa planom ... */
private const val ENTER_DEPTH_M = 1.5

/** ... i ulaz te zgrade najviše ovoliko daleko (inače je to GPS greška uz zid, ne ulazak). */
private const val ENTER_RADIUS_M = 12.0

/** Tačka na grafu je na kraju hodnika (na čvoru prolaza/ulaza). */
private const val AT_GATE_M = 0.3

/** Izlazak kroz kraj hodnika: slobodna PDR pozicija je bar ovoliko iza čvora, napolje. */
private const val PASS_BEYOND_M = 1.0

/** Posle prelaza bar ovoliko koraka bez novog prelaza (da tačka ne skače napred-nazad na vratima). */
private const val MIN_STEPS_BETWEEN_CHANGES = 4

/** Izlazak po GPS-u: ako je PDR bio ovoliko blizu ulaza, izašao je na tom ulazu. */
private const val GPS_EXIT_GATE_M = 8.0

// Ispravka smera iz rekalibracije.

/** Najmanji put (i PDR i stvaran) od prethodnog sidra za procenu zakrenutosti smera. */
private const val MIN_BIAS_DISTANCE_M = 10.0

/** Veća razlika dužina znači da je put bio zbrkan (ili pogrešno označen) - smer se ne dira. */
private val BIAS_LENGTH_RATIO = 0.5..2.0

/** Veća ispravka je verovatno pogrešno označeno mesto. */
private const val MAX_BIAS_CORRECTION_DEG = 90f

/**
 * Poređenje PDR puta sa stvarnim pri označavanju ("Ovde sam"), od prethodnog sidra na istom spratu. Ispravka smera
 * NIJE trajno rešenje (korisnik, 03.10.2026) - ovo je pre svega merilo koliko smer trenutno promašuje.
 */
sealed interface HeadingCheck {
    /** Put kraći od [MIN_BIAS_DISTANCE_M] - ugao bi bio šum (greška dodira od 2 m na 6 m je ~20°). */
    data class TooShort(val walkedM: Float, val actualM: Float) : HeadingCheck

    /** Ispravka je uključena/isključena usred puta - PDR put je zbir koraka sa dva različita smera. */
    data object Interrupted : HeadingCheck

    /**
     * [errorDeg]: greška smera bez ispravke = PDR smer − stvarni (+ = PDR zakrenut u smeru kazaljke, kao u beleškama
     * sa terena); [residualDeg]: greška uz ispravku koja je važila na tom putu (jednaka [errorDeg] bez ispravke);
     * [lengthRatio]: PDR put / stvarni (dužina koraka). [reliable] = false: dužine se previše razlikuju ili je greška
     * > 90° (zbrkan put ili pogrešno označeno) - ne uči se. [applied]: naučena ispravka važi za dalje korake.
     */
    data class Measured(
        val errorDeg: Float,
        val residualDeg: Float,
        val walkedM: Float,
        val actualM: Float,
        val reliable: Boolean,
        val applied: Boolean,
    ) : HeadingCheck {
        val lengthRatio: Float get() = walkedM / actualM
    }
}

/**
 * Gde je korisnik: PDR na planu sprata zgrade (map-matching na graf) ili na kampusu (koraci + GPS), sa
 * prelazima između njih:
 * - kampus -> zgrada: tačka uđe u obris zgrade sa planom blizu njenog ulaza -> čvor ulaza u zgradi;
 * - zgrada -> kampus / druga zgrada: tačka na grafu stigne do kraja hodnika koji je ulaz ili spojni
 *   prolaz i korisnik nastavi napolje -> put prolaza se prelazi koracima, pa sprat/kampus iza njega;
 * - GPS potvrdi da je korisnik napolju (sveže, posle ulaska) -> kampus, iako PDR nije video izlaz.
 *
 * Sprat se ne prepoznaje (nema barometra) - prelaz stepenicama ispravlja korisnik ([setPosition]). Svako
 * ručno označavanje je i sidro: posle bar [MIN_BIAS_DISTANCE_M] hoda sa istog sprata, razlika između PDR puta
 * i stvarnog puta daje zakrenutost smera u toj zgradi (magnetno polje u zgradi), koja važi za dalje korake
 * ako je ispravka uključena ([applyCorrection]); to je privremena pomoć i merilo greške ([HeadingCheck]).
 *
 * Pozicije su relativne na plan mesta ([place]); na kampusu je "plan" cela mapa (sever gore).
 */
class PdrLocator(
    private val graph: BuildingGraph,
    private val campus: CampusData,
    declinationDeg: Float,
) {
    var place = PdrPlace(NbPlan.BUILDING_ID)
        private set

    /** Slobodna PDR pozicija (bez lepljenja na graf); null dok nije postavljena. */
    var raw: Offset? = null
        private set

    /** Pozicija na grafu sprata; null na kampusu i u prolazu. */
    var match: MatchedPosition? = null
        private set

    /** Naučena zakrenutost smera po zgradi (iz rekalibracija); dodaje se azimutu sa senzora ako je [applyCorrection]. */
    private val biases = HashMap<String, Float>()

    /**
     * Ispravka smera uključena. Isključena: zakrenutost se i dalje meri i pamti, ali se ne primenjuje - svako
     * označavanje tada pokazuje celu grešku smera. Promena usred puta prekida merenje do sledećeg sidra.
     */
    var applyCorrection: Boolean = true
        set(value) {
            if (field != value && anchor != null && (walkedX != 0.0 || walkedY != 0.0)) pathInterrupted = true
            field = value
        }

    private var pathInterrupted = false

    /** Ispravka koja se primenjuje u zgradi u kojoj je korisnik (0 ako je isključena ili još nije naučena). */
    val headingBiasDeg: Float get() = if (applyCorrection) biasOf(place) else 0f

    /** Poslednja pouzdano izmerena greška smera (bez ispravke) u zgradi u kojoj je korisnik; null dok nije merena. */
    val headingErrorDeg: Float? get() = biases[place.buildingId]?.let { angleDiffDeg(0f, it) }

    private val planUp: Map<String, Float> = (INDOOR_BUILDINGS.map { it.buildingId } + CAMPUS_ID)
        .associateWith { planUpMagneticAzimuthDeg(graph.placement(it), declinationDeg) }

    private val matchers = HashMap<PdrPlace, MapMatcher>()

    private fun matcherFor(place: PdrPlace): MapMatcher? =
        if (place.isCampus) null else matchers.getOrPut(place) { MapMatcher(graph, place.buildingId, place.floor) }

    // Napolju.
    private var varianceM2 = KNOWN_VARIANCE_M2
    private var gpsOutliers = 0

    /** U zgradi: nov detektor posle svakog sidra, da izlazak potvrde samo lokacije posle ulaska. */
    private var exitDetector: BuildingDetector? = null

    private var stepsSinceChange = MIN_STEPS_BETWEEN_CHANGES

    // Sidro (poslednje poznato mesto) i zbir PDR koraka od njega, u metrima plana mesta sidra.
    private var anchorPlace: PdrPlace? = null
    private var anchor: PointM? = null
    private var walkedX = 0.0
    private var walkedY = 0.0

    /** Pozicija pre koraka - za ponavljanje koraka kad se ispravi smer ([WalkingDirection.WalkStep.redoSteps]). */
    private class StepStart(val raw: Offset, val match: MatchedPosition?, val walkedX: Double, val walkedY: Double)

    private val stepStarts = ArrayDeque<StepStart>()

    /** Kraj hodnika ([node]) kroz koji se izlazi, i čvor iza njega ([target]: kraj hodnika druge zgrade ili kampus). */
    private class Gateway(val node: Node, val target: Node)

    private val gateways: Map<PdrPlace, List<Gateway>> = buildGateways()

    /** Ulaz kampusa ([outside], K-U-...) i čvor zgrade sa planom iza njega. */
    private class Entrance(val outside: Node, val inside: Node)

    private val entrances: Map<String, List<Entrance>> = graph.nodes
        .filter { it.buildingId == CAMPUS_ID && it.type == NodeType.ULAZ }
        .flatMap { e -> graph.neighbors(e.id).map { it.first }.filter { it.isIndoor }.map { Entrance(e, it) } }
        .groupBy { it.inside.buildingId }

    /**
     * Prolaz u toku, u metrima kampusa: od kraja hodnika [start] do čvora iza njega ([target]). Pređeno se meri
     * u pravcu hodnika ka izlazu ([outward], jedinični) - dugi prolazi (NB - AMF, Kula - AMF) su produžetak
     * hodnika, a kod kratkih (vrata, NB - Kula) se krajevi dve zgrade skoro poklapaju pa pravac do cilja ne znači ništa.
     */
    private class Passage(val from: PdrPlace, val target: Node, val start: PointM, val end: PointM, val outward: PointM, var doneM: Double) {
        val lengthM = distance(start, end)
    }

    private var passage: Passage? = null

    /** Smer za prikaz, u odnosu na "gore" plana mesta. */
    fun headingOnPlan(azimuthDeg: Float): Float = normalizeDeg(azimuthDeg + headingBiasDeg - planUp.getValue(place.buildingId))

    /**
     * Korisnik je označio gde je ([point] na planu [place]) - start ili rekalibracija. Vraća poređenje puta od
     * prethodnog sidra; null ako sidro nije na istom spratu (start, stepenice, prolaz). Pouzdano izmerena greška
     * se pamti kao zakrenutost zgrade (primenjuje se ako je [applyCorrection]).
     */
    fun setPosition(place: PdrPlace, point: Offset): HeadingCheck? {
        val check = checkHeading(place, point)
        if (check is HeadingCheck.Measured && check.reliable) biases[place.buildingId] = angleDiffDeg(0f, check.errorDeg)
        setAnchor(place, point)
        varianceM2 = KNOWN_VARIANCE_M2
        return check
    }

    /** Start napolju sa GPS lokacije. */
    fun startFromGps(fix: GpsFix) {
        setAnchor(PdrPlace.CAMPUS, relative(PdrPlace.CAMPUS, fix.point))
        varianceM2 = (fix.accuracyM * fix.accuracyM).toDouble()
    }

    /** Novo praćenje: koraci od pre se ne ponavljaju. */
    fun clearRedo() = stepStarts.clear()

    /** Briše poziciju i naučene ispravke smera; mesto ostaje (za prikaz). */
    fun reset() {
        raw = null
        match = null
        passage = null
        anchor = null
        anchorPlace = null
        exitDetector = null
        stepStarts.clear()
        biases.clear()
    }

    /**
     * Jedan korak u pravcu [azimuthDeg] (magnetski, sa senzora). Ako [redoSteps] > 0, toliko prethodnih
     * koraka se ponavlja u tom pravcu (ispravljen smer). Vraća razlog ako je pozicija prešla na drugo mesto.
     */
    fun step(azimuthDeg: Float, lengthM: Float, redoSteps: Int = 0): PlaceReason? {
        var raw = raw ?: return null
        passage?.let { return passageStep(it, azimuthDeg, lengthM) }
        val scale = graph.placement(place.buildingId).scale
        val matcher = matcherFor(place)
        var match = match
        val redo = redoSteps.coerceAtMost(stepStarts.size)
        if (redo > 0) {
            val from = stepStarts[stepStarts.size - redo]
            repeat(redo) { stepStarts.removeLast() }
            raw = from.raw
            match = from.match
            walkedX = from.walkedX
            walkedY = from.walkedY
        }
        val (dxM, dyM) = stepVector(place, azimuthDeg, lengthM)
        repeat(redo + 1) {
            stepStarts.addLast(StepStart(raw, match, walkedX, walkedY))
            if (stepStarts.size > WalkingDirection.MAX_TURN_STEPS) stepStarts.removeFirst()
            raw = Offset(
                (raw.x + dxM / scale.widthM).toFloat().coerceIn(0f, 1f),
                (raw.y + dyM / scale.heightM).toFloat().coerceIn(0f, 1f),
            )
            match = matcher?.let { m -> match?.let { m.step(it, dxM, dyM) } ?: m.start(raw.x, raw.y) }
            walkedX += dxM
            walkedY += dyM
            if (place.isCampus) varianceM2 += STEP_VARIANCE_M2
        }
        this.raw = raw
        this.match = match
        if (++stepsSinceChange < MIN_STEPS_BETWEEN_CHANGES) return null
        return if (place.isCampus) enterBuilding() else leaveThroughGateway()
    }

    /**
     * GPS lokacija. Napolju povlači poziciju ka sebi (jače što je tačnija, a PDR nesigurniji); bez praćenja
     * ([tracking]) pozicija je GPS lokacija. U zgradi samo potvrđuje izlazak. Vraća razlog ako je pozicija preskočila.
     */
    fun onGps(fix: GpsFix, tracking: Boolean): PlaceReason? {
        val raw = raw ?: return null
        if (passage != null) return null
        if (!place.isCampus) {
            val detector = exitDetector ?: return null
            if (!detector.onFix(fix) || !detector.isKnown || detector.current != null) return null
            exitByGps(fix)
            return PlaceReason.GPS_IZLAZ
        }
        if (fix.accuracyM > MAX_FUSE_ACCURACY_M) return null
        val gpsVariance = (fix.accuracyM * fix.accuracyM).toDouble()
        if (!tracking) {
            setAnchor(PdrPlace.CAMPUS, relative(PdrPlace.CAMPUS, fix.point))
            varianceM2 = gpsVariance
            return null
        }
        val p = meters(PdrPlace.CAMPUS, raw)
        val dx = fix.point.x - p.x
        val dy = fix.point.y - p.y
        if (hypot(dx, dy) > GATE_SIGMAS * sqrt(varianceM2 + gpsVariance)) {
            if (++gpsOutliers < MAX_GPS_OUTLIERS) return null
            setAnchor(PdrPlace.CAMPUS, relative(PdrPlace.CAMPUS, fix.point))
            varianceM2 = gpsVariance
            return PlaceReason.GPS_SKOK
        }
        gpsOutliers = 0
        val k = varianceM2 / (varianceM2 + gpsVariance)
        this.raw = relative(PdrPlace.CAMPUS, PointM(p.x + k * dx, p.y + k * dy))
        varianceM2 *= 1 - k
        return null
    }

    private fun biasOf(place: PdrPlace) = biases[place.buildingId] ?: 0f

    /**
     * Korak u metrima plana mesta [plan] (x udesno, y naniže); smer je ispravljen zakrenutošću zgrade u kojoj
     * je korisnik ([place]) - u prolazu se korak meri na mapi kampusa.
     */
    private fun stepVector(plan: PdrPlace, azimuthDeg: Float, lengthM: Float): Pair<Double, Double> {
        val rad = Math.toRadians((azimuthDeg + headingBiasDeg - planUp.getValue(plan.buildingId)).toDouble())
        return lengthM * sin(rad) to -lengthM * cos(rad)
    }

    private fun setAnchor(place: PdrPlace, point: Offset) {
        this.place = place
        raw = point
        match = matcherFor(place)?.start(point.x, point.y)
        passage = null
        anchorPlace = place
        anchor = meters(place, point, local = true)
        walkedX = 0.0
        walkedY = 0.0
        pathInterrupted = false
        stepStarts.clear()
        gpsOutliers = 0
        exitDetector = if (place.isCampus) null else BuildingDetector(campus)
        stepsSinceChange = MIN_STEPS_BETWEEN_CHANGES
    }

    /** Prelaz: pozicija na čvoru [node] (zgrada ili kampus). */
    private fun moveTo(node: Node) {
        setAnchor(node.place, Offset(node.x, node.y))
        varianceM2 = KNOWN_VARIANCE_M2
        stepsSinceChange = 0
    }

    /**
     * Greška smera: ugao od stvarnog puta (od sidra do [point]) do PDR puta (zbir koraka od sidra, sa ispravkom
     * koja je tada važila - [headingBiasDeg]). Samo na istom spratu kao sidro.
     */
    private fun checkHeading(place: PdrPlace, point: Offset): HeadingCheck? {
        val anchor = anchor ?: return null
        if (place != anchorPlace || passage != null) return null
        if (pathInterrupted) return HeadingCheck.Interrupted
        val actual = meters(place, point, local = true)
        val ax = actual.x - anchor.x
        val ay = actual.y - anchor.y
        val actualM = hypot(ax, ay)
        val walkedM = hypot(walkedX, walkedY)
        if (actualM < MIN_BIAS_DISTANCE_M || walkedM < MIN_BIAS_DISTANCE_M) {
            return HeadingCheck.TooShort(walkedM.toFloat(), actualM.toFloat())
        }
        val residual = angleDiffDeg(azimuthOf(walkedX, walkedY), azimuthOf(ax, ay))
        return HeadingCheck.Measured(
            errorDeg = angleDiffDeg(residual - headingBiasDeg, 0f),
            residualDeg = residual,
            walkedM = walkedM.toFloat(),
            actualM = actualM.toFloat(),
            reliable = actualM / walkedM in BIAS_LENGTH_RATIO && abs(residual) <= MAX_BIAS_CORRECTION_DEG,
            applied = applyCorrection,
        )
    }

    /** Napolju: tačka duboko u obrisu zgrade sa planom, blizu njenog ulaza -> u zgradu, na taj ulaz. */
    private fun enterBuilding(): PlaceReason? {
        val p = meters(PdrPlace.CAMPUS, raw ?: return null)
        for ((buildingId, list) in entrances) {
            val outline = campus.building(buildingId) ?: continue
            if (!outline.contains(p) || outline.distanceToWallM(p) < ENTER_DEPTH_M) continue
            val entrance = list.minBy { distance(graph.position(it.outside), p) }
            if (distance(graph.position(entrance.outside), p) > ENTER_RADIUS_M) continue
            moveTo(entrance.inside)
            return PlaceReason.ULAZ
        }
        return null
    }

    /** U zgradi: tačka na grafu na kraju hodnika (ulaz/prolaz), a korisnik ide dalje napolje -> prolaz. */
    private fun leaveThroughGateway(): PlaceReason? {
        val match = match ?: return null
        val point = match.point
        val scale = graph.placement(place.buildingId).scale
        fun m(x: Float, y: Float) = PointM(x * scale.widthM.toDouble(), y * scale.heightM.toDouble())
        val onEdge = m(point.x, point.y)
        val free = m(match.x, match.y)
        for (gate in gateways[place].orEmpty()) {
            val other = when (gate.node.id) {
                point.from.id -> point.to
                point.to.id -> point.from
                else -> continue
            }
            val end = m(gate.node.x, gate.node.y)
            if (distance(onEdge, end) > AT_GATE_M) continue
            val inner = m(other.x, other.y)
            val beyond = along(free, end, inner, end)
            if (beyond < PASS_BEYOND_M) continue
            // Pravac hodnika ka izlazu, u metrima kampusa.
            val start = graph.position(gate.node)
            val back = graph.position(other)
            val length = distance(back, start)
            val outward = PointM((start.x - back.x) / length, (start.y - back.y) / length)
            val passage = Passage(place, gate.target, start, graph.position(gate.target), outward, beyond)
            this.passage = passage
            stepStarts.clear()
            return updatePassage(passage)
        }
        return null
    }

    /** Korak u prolazu: računa se deo koraka u pravcu izlaza (nazad umanjuje pređeno). */
    private fun passageStep(passage: Passage, azimuthDeg: Float, lengthM: Float): PlaceReason? {
        val (dx, dy) = stepVector(PdrPlace.CAMPUS, azimuthDeg, lengthM)
        passage.doneM += dx * passage.outward.x + dy * passage.outward.y
        return updatePassage(passage)
    }

    private fun updatePassage(passage: Passage): PlaceReason? {
        val before = place
        when {
            passage.doneM >= passage.lengthM -> {
                moveTo(passage.target)
                return PlaceReason.PROLAZ
            }
            // Vratio se u hodnik.
            passage.doneM < -PASS_BEYOND_M -> {
                setAnchor(passage.from, relative(passage.from, passage.start))
                stepsSinceChange = 0
                return PlaceReason.PROLAZ.takeIf { place != before }
            }
        }
        // Do pola puta je pozicija u zgradi iz koje se izlazi, posle u onoj u koju se ulazi.
        val shown = if (passage.doneM < passage.lengthM / 2) passage.from else passage.target.place
        val t = (passage.doneM / passage.lengthM).coerceIn(0.0, 1.0)
        place = shown
        raw = relative(shown, PointM(passage.start.x + (passage.end.x - passage.start.x) * t, passage.start.y + (passage.end.y - passage.start.y) * t))
        match = null
        return PlaceReason.PROLAZ.takeIf { place != before }
    }

    /** GPS je potvrdio izlazak: na ulazu ako je PDR bio blizu njega, inače na GPS lokaciji. */
    private fun exitByGps(fix: GpsFix) {
        val here = meters(place, match?.point?.let { Offset(it.x, it.y) } ?: raw ?: return, local = true)
        val gate = gateways[place].orEmpty()
            .filter { it.target.type == NodeType.ULAZ && !it.target.isIndoor }
            .minByOrNull { distance(meters(place, Offset(it.node.x, it.node.y), local = true), here) }
            ?.takeIf { distance(meters(place, Offset(it.node.x, it.node.y), local = true), here) <= GPS_EXIT_GATE_M }
        if (gate != null) {
            moveTo(gate.target)
        } else {
            setAnchor(PdrPlace.CAMPUS, relative(PdrPlace.CAMPUS, fix.point))
            varianceM2 = (fix.accuracyM * fix.accuracyM).toDouble()
            stepsSinceChange = 0
        }
    }

    private fun buildGateways(): Map<PdrPlace, List<Gateway>> {
        val result = HashMap<PdrPlace, MutableList<Gateway>>()
        for (node in graph.nodes.filter { it.isIndoor }) {
            for ((next, type) in graph.neighbors(node.id)) {
                if (type != EdgeType.HOD || next.buildingId == node.buildingId) continue
                // Spojni prolaz kampusa (K-P-...) vodi dalje u drugu zgradu sa planom; ako je nema (ITC), na kampus.
                val onward = if (next.isIndoor || next.type != NodeType.PROLAZ) null else {
                    graph.neighbors(next.id).map { it.first }
                        .filter { it.isIndoor && it.buildingId != node.buildingId }
                        .minWithOrNull(compareBy({ abs(it.floor - node.floor) }, { it.floor }))
                }
                // Čvor prolaza kampusa (težište OSM spojnog dela) nije na putu kojim se hoda - preskače se.
                result.getOrPut(node.place) { mutableListOf() } += Gateway(node, onward ?: next)
            }
        }
        return result
    }

    /** Metri plana mesta [place]: [local] = bez smeštaja (za sidro i korake), inače metri kampusa. */
    private fun meters(place: PdrPlace, p: Offset, local: Boolean = false): PointM {
        val placement = graph.placement(place.buildingId)
        return if (local) {
            PointM(p.x * placement.scale.widthM.toDouble(), p.y * placement.scale.heightM.toDouble())
        } else {
            placement.toMeters(p.x, p.y)
        }
    }

    private fun relative(place: PdrPlace, p: PointM): Offset =
        graph.placement(place.buildingId).toRelative(p).let { (x, y) -> Offset(x, y) }

    private val Node.isIndoor: Boolean get() = indoorBuilding(buildingId) != null

    private val Node.place: PdrPlace get() = if (isIndoor) PdrPlace(buildingId, floor) else PdrPlace.CAMPUS
}

private fun distance(a: PointM, b: PointM) = hypot(a.x - b.x, a.y - b.y)

/** Koliko je [p] od [origin] u pravcu [from] -> [to] (m); 0 ako pravca nema. */
private fun along(p: PointM, origin: PointM, from: PointM, to: PointM): Double {
    val length = distance(from, to).takeIf { it > 0 } ?: return 0.0
    return ((p.x - origin.x) * (to.x - from.x) + (p.y - origin.y) * (to.y - from.y)) / length
}

/** Azimut vektora u metrima plana (x udesno, y naniže): 0 = gore, u smeru kazaljke. */
private fun azimuthOf(dx: Double, dy: Double): Float = Math.toDegrees(atan2(dx, -dy)).toFloat()
