package com.example.ftnnavigation.poc

import androidx.compose.ui.geometry.Offset
import com.example.ftnnavigation.campus.BuildingDetector
import com.example.ftnnavigation.campus.CAMPUS_ID
import com.example.ftnnavigation.campus.CampusData
import com.example.ftnnavigation.campus.GpsFix
import com.example.ftnnavigation.campus.contains
import com.example.ftnnavigation.campus.distanceToWallM
import com.example.ftnnavigation.graph.BuildingGraph
import com.example.ftnnavigation.graph.EdgePoint
import com.example.ftnnavigation.graph.EdgeType
import com.example.ftnnavigation.graph.INDOOR_BUILDINGS
import com.example.ftnnavigation.graph.IndoorBuilding
import com.example.ftnnavigation.graph.MapMatcher
import com.example.ftnnavigation.graph.MatchedPosition
import com.example.ftnnavigation.graph.NbPlan
import com.example.ftnnavigation.graph.Node
import com.example.ftnnavigation.graph.NodeType
import com.example.ftnnavigation.graph.PlanPoint
import com.example.ftnnavigation.graph.PointM
import com.example.ftnnavigation.graph.StairPath
import com.example.ftnnavigation.graph.indoorBuilding
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
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

    /** Stepenicama na drugi sprat (okret na podestu između dva leta, [StairWalk]). */
    STEPENICE,

    /** Liftom: prepoznata vožnja ([LiftRide]) ili izbor sprata u pitanju ([LiftPrompt]). */
    LIFT,

    /**
     * Samo u snimku: pozicija u trenutku Start-a (teren 05.10.2026 - "Ovde sam" pre Start-a se ne snima, pa replay nije
     * znao odakle je snimak krenuo).
     */
    START,
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

/**
 * ... i ulaz te zgrade najviše ovoliko daleko (inače je to GPS greška uz zid, ne ulazak). Bilo 12 m: na terenu
 * (03.10.2026, NTP) GPS i PDR su korisnika koji ide spolja uz zid držali ~4 m u obrisu, pa je ulazak okinuo
 * ~12 m (10 s) pre vrata; sa 6 m na istom snimku ~5 m pre vrata (GPS je tu promašivao ~6 m). Manje od 5 m na
 * tom snimku više ne okida - tada ostaje "Ovde sam".
 */
private const val ENTER_RADIUS_M = 6.0

/**
 * Kad su uslovi za ulazak ispunjeni, tačka je i dalje do [ENTER_RADIUS_M] od ulaza (teren 08.10.2026: NB 5,3 m - korisnik
 * "možda 7 koraka pre"; NTP opet prerano). Ulazak tek kad koraci pređu taj put ka ulazu (deo koraka u pravcu ulaza),
 * osim ovoliko (vrata, korak).
 */
private const val ENTER_SLACK_M = 1.0

/** Ulazak koji čeka se otkazuje ako se tačka udalji od ulaza više od ovoga. */
private const val ENTER_CANCEL_M = 12.0

/** Tačka na grafu je na kraju hodnika (na čvoru prolaza/ulaza). */
private const val AT_GATE_M = 0.3

/** Izlazak kroz kraj hodnika: slobodna PDR pozicija je bar ovoliko iza čvora, napolje. */
private const val PASS_BEYOND_M = 1.0

/** Posle prelaza bar ovoliko koraka bez novog prelaza (da tačka ne skače napred-nazad na vratima). */
private const val MIN_STEPS_BETWEEN_CHANGES = 4

/**
 * Izlazak po GPS-u tek kad je GPS bar ovoliko od zida zgrade (napolju). Teren 05.10.2026, NTP: korisnik unutra, a GPS
 * ~20 s 8-10 m ispred zgrade (tačnost 3-8 m), pa skok u zgradu (13-21 m).
 */
private const val GPS_EXIT_WALL_M = 15.0

/** Izlazak po GPS-u: ako je PDR bio ovoliko blizu ulaza, izašao je na tom ulazu. */
private const val GPS_EXIT_GATE_M = 8.0

// Stepenice (vrednosti su procena; snimci sa terena: letovi od 12-14 koraka, podest stepeništa NTP ~5 x 4 m).

/** Ulazak u stepenište: tačka na grafu na ivici do čvora stepeništa, najviše ovoliko od njega (ili pola ivice). */
private const val STAIR_ENTER_M = 1.5

/**
 * Let - okret - let dok tačka nije na stepeništu računa se kao stepenište ako je prvi let počeo najviše ovoliko od
 * čvora stepeništa (tačka odluta: PDR korak je 0,8 m, a gazište ~0,3 m). Okret u hodniku tik uz stepenište sa letovima
 * od bar 6 koraka bi se ovde pogrešno video kao sprat - nije proveravano na terenu.
 */
private const val STAIR_NEAR_M = 5.0

/** Koliko poslednjih koraka van stepeništa se pamti za prepoznavanje leta. */
private const val WATCH_STEPS = 60

/** Korak na stepenicama se po planu pomera samo za dubinu gazišta (korisnik: "više koraka uz manju pređenu distancu"). */
private const val STAIR_STEP_M = 0.3f

/** Na stepeništu tačka ostaje najviše ovoliko od čvora stepeništa. */
private const val STAIR_RADIUS_M = 3.0

/**
 * Izlazak sa stepeništa: hod (kratkim koracima) od čvora stepeništa ka hodniku bar ovoliko, pravcem koji nije let
 * ([StairWalk.leaves]). Bilo 2 m bez provere pravca: na F (03.10.2026) treći let ide ka hodniku i tačka je izlazila usred
 * silaska.
 */
private const val STAIR_EXIT_M = 1.2

/** Ispravka sprata ("Ovde sam") do ovoliko koraka posle promene na stepeništu uči smer okreta tog stepeništa. */
private const val STAIR_LEARN_STEPS = 80

// Stepenište sa krakovima ([StairPath], teren 05.10.2026): tačka ide krakom, preko međupodesta, pa drugim krakom - kao
// na crtežu korisnika (NB: levo od lifta gore, iza lifta, desno od lifta). Ranije je stajala do 3 m od čvora stepeništa
// ("vrtela se u krugu"), a posle promene sprata bila na čvoru kraka naviše - "vezao me je za pogrešne stepenice".

/** Koraka po kraku za prikaz (snimci 05.10.2026: krakovi NB i NTP S1 9-16 koraka). */
private const val FLIGHT_TREADS = 12

/**
 * Prvi krak od ulaska u stepenište najviše ovoliko koraka (sa prilazom: tačka stigne do dna kraka pre korisnika - NB I -> P
 * i NTP P -> I 05.10.2026: 18 koraka do okreta; sa 18 je tačka izlazila sa stepeništa tik pred okret). Kao
 * [StairWalk]: hod hodnikom je 25-77 koraka.
 */
private const val MAX_FIRST_FLIGHT_STEPS = 24

/** Korak dalje od ovoliko od pravca kraka je početak okreta (na podestu se okreće kroz 3-6 koraka). */
private const val TURN_START_DEG = 45f

/** Okret od ovoliko je završen (sledi let); do 180° tačka ide preko podesta srazmerno okretu. */
private const val TURN_DONE_DEG = 135f

/**
 * Korak pravo: promena smera od prethodnog koraka do ovoliko. Na krakovima se smer koraka menja do ~6-11°, a pri
 * okretu na podestu 10-20° po koraku (snimci 05.10.2026) - zato podest traži i više takvih koraka zaredom.
 */
private const val STRAIGHT_STEP_DEG = 10f

/** Okret na podestu je završen kad je smer ovoliko koraka zaredom stalan. */
private const val TURN_SETTLED_STEPS = 2

/** Posle silaska sa stepeništa ponovo ulazi tek posle ovoliko koraka (tačka je još kod dna kraka). */
private const val STAIR_REENTER_STEPS = 3

/** Pravo stepenište (bez okreta): sprat se menja posle ovolikog dela puta. */
private const val STRAIGHT_CHANGE_AT = 0.75

/** Prvi korak na stepeništu dalje od ovoliko od smera kraka (na planu) - nije krenuo uz krak. */
private const val NOT_ON_FLIGHT_DEG = 100f

/** Posle kraja drugog kraka još ovoliko koraka pravo -> korisnik je sišao sa stepeništa (podest sprata, hodnik). */
private const val EXIT_OVERFLOW_STEPS = 3

/** Na podestu sprata ovoliko koraka pravo bez punog okreta -> nastavio je hodnikom, ne sledećim krakom. */
private const val LANDING_EXIT_STEPS = 4

/**
 * Drugi krak bez promene sprata posle ovoliko koraka: okret nije bio podest (korisnik se okrenuo i vratio istim krakom,
 * ili let nije bio dovoljno dug za [StairWalk]) - nazad na sprat sa koga je krenuo.
 */
private const val SECOND_FLIGHT_NO_CHANGE_STEPS = FLIGHT_STEPS + 2

// Lift: vožnja se prepoznaje iz akcelerometra ([LiftRideDetector], teren 08.10.2026), a broj spratova iz pomeraja. Do
// 08.10. je pitanje "na koji sprat?" stizalo posle 10 s stajanja na 3 m od lifta - na terenu prerano (dok čeka lift, čim
// uđe), lažno (čekanje bez vožnje, posle vožnje u prizemlju) ili nikako (stavljanje telefona u džep pravi lažne korake -
// tačka se odmakla ~10 m od lifta).

/**
 * Vožnja se pripisuje najbližem liftu na spratu tačke, ako je najviše ovoliko daleko: tačka ume da se odmakne od lifta
 * (lažni koraci pri stavljanju u džep - 08.10.2026 ~10 m). Jezgra sa liftovima u NTP-u su desetinama metara razmaknuta.
 */
private const val LIFT_RIDE_NEAR_M = 15.0

/**
 * Visina sprata za broj spratova iz pomeraja vožnje. NTP (08.10.2026, 6 vožnji): 3,5-4,05 m po spratu, P -> V 19,2 m.
 * NB i Kula: ista vrednost je PRETPOSTAVKA (nije mereno).
 */
private const val FLOOR_HEIGHT_M = 3.75

/** Broj spratova (pomeraj / visina sprata) dalji od celog broja od ovoga nije siguran -> pitanje. Na snimcima do 0,16. */
private const val FLOOR_ROUNDING = 0.35

/**
 * Pitanje "na koji sprat?" posle vožnje liftom [lift] kad broj spratova iz pomeraja nije siguran: [floors] su spratovi
 * do kojih lift ide, bez sprata [fromFloor] na kome je tačka (od najvišeg); [suggested] je sprat najbliži izmerenom.
 */
data class LiftPrompt(val lift: Node, val fromFloor: Int, val floors: List<Int>, val suggested: Int?) {
    val buildingId: String get() = lift.buildingId
}

/** Vožnja liftom u zgradi [buildingId] sa sprata [fromFloor] na [toFloor] (toast). */
data class LiftChange(val buildingId: String, val fromFloor: Int, val toFloor: Int)

/** Šta je vožnja promenila: tačka je na liftu novog sprata, ili sprat nije siguran pa se pita ([PdrLocator.liftPrompt]). */
sealed interface LiftOutcome {
    data class Moved(val change: LiftChange) : LiftOutcome
    data class Ask(val prompt: LiftPrompt) : LiftOutcome
}

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
 * - stepenice: tačka stigne do čvora stepeništa -> koraci su kratki (gazište), tačka ostaje kod stepeništa, a okret
 *   na podestu menja sprat; strana okreta je gore ili dole (stepenište je spirala - [STAIR_UP_TURN], naučeno sa
 *   najnižeg/najvišeg nivoa ili iz ispravke). Hod od stepeništa ka hodniku vraća tačku na graf sprata.
 *
 * - lift: prepoznata vožnja ([LiftRide]) kod lifta -> tačka na liftu sprata izračunatog iz pomeraja ([liftRide]); kad
 *   broj spratova nije siguran, pitanje na koji sprat ([LiftPrompt], [selectLiftFloor]). Koraci dok pitanje čeka se pamte
 *   i ponove od lifta na izabranom spratu.
 *
 * Svako
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
    stairPaths: List<StairPath> = emptyList(),
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

    /** Ulazak čeka: tačka je bila [remainingM] od ulaza [entrance]; [walkedM] je zbir koraka od tada. */
    private class PendingEntry(val entrance: Entrance, val remainingM: Double) {
        var walkedM = 0.0
    }

    private var pendingEntry: PendingEntry? = null

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

    private val stairs: Map<String, Stair> = stairsOf(graph)

    /**
     * Putanje stepeništa sa krakovima po (ključ stepeništa, sprat); može više varijanti (AMF S1: sa podesta levim ili
     * desnim bočnim krakom).
     */
    private val paths: Map<Pair<String, Int>, List<List<PlanPoint>>> =
        stairPaths.groupBy({ it.stairKey to it.floor }, { it.points })

    /**
     * Strana okreta pri penjanju po crtežu krakova (+1 u smeru kazaljke): od kraka naviše ka međupodestu i drugom kraku -
     * ako je ista za sve varijante. NB S i NTP S1-S3: +1 (snimci 05.10.2026 se slažu). Pravo stepenište nema okret.
     */
    private val pathUpTurns: Map<String, Int> = stairPaths.groupBy { it.stairKey }.mapNotNull { (key, list) ->
        list.map { upTurnOf(it.points) }.distinct().singleOrNull()?.takeIf { it != 0 }?.let { key to it }
    }.toMap()

    /** Varijante sa najnižeg sprata sa crtežom - za sprat bez crteža krakova (NB V sprat; isto mesto u zgradi). */
    private val anyPaths: Map<String, List<List<PlanPoint>>> = stairPaths.groupBy { it.stairKey }
        .mapValues { (_, list) -> list.filter { it.floor == list.minOf { p -> p.floor } }.map { it.points } }

    private fun pathsOf(stair: Stair, floor: Int): List<List<PlanPoint>>? = paths[stair.key to floor] ?: anyPaths[stair.key]

    /** Putanja hoda [c] na trenutnom spratu (varijanta [Climb.variant]). */
    private fun pathOf(c: Climb): List<PlanPoint>? = pathsOf(c.stair, place.floor)?.let { it[c.variant.coerceIn(it.indices)] }

    /** Bira varijantu putanje čija je strana okreta pri penjanju [upTurn] (ako je ima). */
    private fun selectVariant(c: Climb, upTurn: Int) {
        val variants = pathsOf(c.stair, place.floor) ?: return
        variants.indexOfFirst { upTurnOf(it) == upTurn }.takeIf { it >= 0 }?.let { c.variant = it }
    }

    /** Naučen smer okreta pri penjanju po stepeništu (+1 u smeru kazaljke, −1 suprotno); važi pre [STAIR_UP_TURN]. */
    private val learnedUpTurns = HashMap<String, Int>()

    /** Naučeni smerovi okreta (za čuvanje između pokretanja aplikacije). */
    val learnedStairTurns: Map<String, Int> get() = learnedUpTurns.toMap()

    fun restoreStairTurns(turns: Map<String, Int>) {
        learnedUpTurns.putAll(turns.filterValues { it == 1 || it == -1 })
    }

    /** Odredište (zgrada i sprat), ako je zadato - kad se smer na stepeništu ne zna, pretpostavlja se ka njemu. */
    var destination: PdrPlace? = null

    /** Poslednja promena sprata na stepeništu; null posle ručnog označavanja ili Reset-a. */
    var lastStairChange: StairChange? = null
        private set

    private var stepsSinceStairChange = 0

    /**
     * Hod po stepeništu [stair]: tačka je na čvoru stepeništa (ili na ivici ka hodniku), [offsetX]/[offsetY] je hod
     * kratkim koracima od čvora (metri plana). [sense]: strana okreta prve promene sprata (0 dok je nije bilo).
     */
    private class Climb(val stair: Stair, val walk: StairWalk, var sense: Int = 0, var changes: Int = 0) {
        var offsetX = 0.0
        var offsetY = 0.0

        // Stepenište sa krakovima ([StairPath]): deo putanje i koliko je pređeno (0..1).
        /** Penje se (putanja od dna kraka naviše) ili silazi (od dna kraka naniže). */
        var up = true
        var part = ClimbPart.FIRST
        var progress = 0.0

        /** Smer kraka kojim se ide (magnetski azimut), dok se ide pravo. */
        var flightHeading: Float? = null
        var lastHeading: Float? = null

        /** Okret od početka podesta (+ u smeru kazaljke). */
        var turned = 0f

        /** Uzastopni koraci pravo (na podestu) / posle kraja kraka. */
        var straight = 0

        /** Koraka na drugom kraku; promena sprata u ovom delu hoda. */
        var secondSteps = 0
        var changedInLeg = false

        /** Silazi sa međupodesta u prolaz: ivica (čvor pre prolaza, čvor prolaza). */
        var exitGate: Pair<Node, Node>? = null

        /** Varijanta putanje (AMF S1: levi ili desni bočni krak). */
        var variant = 0
    }

    /** Delovi putanje kroz stepenište: krak, međupodest, drugi krak (već sprat iznad/ispod), podest sprata ka sledećem kraku. */
    private enum class ClimbPart { FIRST, LANDING, SECOND, FLOOR_LANDING }

    private var climb: Climb? = null

    private var stepsSinceStairExit = STAIR_REENTER_STEPS

    /** Koraci po spratu van stepeništa: let - okret - let blizu stepeništa je hod po stepeništu i kad tačka nije na njemu. */
    private var watch = StairWalk()

    /**
     * Koliko je poslednji korak stvarno prešao (m): na stepeništu samo gazište. Kad se let prepozna tek posle okreta
     * ([stairTurnNearby]), u ovom koraku se oduzima višak već brojanih koraka leta (zbir za "Pređeno" ostaje tačan).
     */
    var lastStepM = 0f
        private set

    /** Liftovi po spratu zgrade. */
    private val lifts: Map<PdrPlace, List<Node>> = graph.nodes
        .filter { it.type == NodeType.LIFT && it.isIndoor }
        .groupBy { it.place }

    /** Pitanje za lift čeka odgovor; null = nema pitanja. */
    var liftPrompt: LiftPrompt? = null
        private set

    /** Koraci od pitanja za lift (ponavljaju se od lifta na izabranom spratu). */
    private class PendingStep(val azimuthDeg: Float, val lengthM: Float, val redoSteps: Int, val timeNs: Long?)

    private val liftSteps = mutableListOf<PendingStep>()

    /** Smer za prikaz, u odnosu na "gore" plana mesta. */
    fun headingOnPlan(azimuthDeg: Float): Float = normalizeDeg(azimuthDeg + headingBiasDeg - planUp.getValue(place.buildingId))

    /**
     * Korisnik je označio gde je ([point] na planu [place]) - start ili rekalibracija. Vraća poređenje puta od
     * prethodnog sidra; null ako sidro nije na istom spratu (start, stepenice, prolaz). Pouzdano izmerena greška
     * se pamti kao zakrenutost zgrade (primenjuje se ako je [applyCorrection]). [measure] = false: samo pozicija, bez
     * merenja smera i učenja (replay: START red snimka nije označavanje).
     */
    fun setPosition(place: PdrPlace, point: Offset, measure: Boolean = true): HeadingCheck? {
        // Označio je gde je - pitanje za lift (ako čeka) više ne važi.
        clearLift()
        if (!measure) {
            lastStairChange = null
            setAnchor(place, point)
            varianceM2 = KNOWN_VARIANCE_M2
            return null
        }
        learnStairTurn(place)
        val check = checkHeading(place, point)
        if (check is HeadingCheck.Measured && check.reliable) biases[place.buildingId] = angleDiffDeg(0f, check.errorDeg)
        setAnchor(place, point)
        varianceM2 = KNOWN_VARIANCE_M2
        return check
    }

    /** Start napolju sa GPS lokacije. */
    fun startFromGps(fix: GpsFix) {
        clearLift()
        setAnchor(PdrPlace.CAMPUS, relative(PdrPlace.CAMPUS, fix.point))
        varianceM2 = (fix.accuracyM * fix.accuracyM).toDouble()
    }

    /**
     * Novo praćenje: koraci od pre se ne ponavljaju, a ni let stepeništa se ne nastavlja (replay: lažna promena posle
     * Start-a).
     */
    fun clearRedo() {
        stepStarts.clear()
        watch = StairWalk()
        clearLift()
    }

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
        climb = null
        watch = StairWalk()
        lastStairChange = null
        clearLift()
    }

    /**
     * Prepoznata vožnja liftom ([LiftRideDetector]): najbliži lift na spratu tačke (u zgradi, van prolaza, najviše
     * [LIFT_RIDE_NEAR_M]) i broj spratova iz pomeraja. Siguran broj, a lift ide do tog sprata -> tačka na liftu tog sprata
     * ([LiftOutcome.Moved]); inače pitanje ([LiftOutcome.Ask], sa spratom najbližim izmerenom). null: nije kod lifta (ili
     * pitanje već čeka).
     */
    fun liftRide(ride: LiftRide): LiftOutcome? {
        if (liftPrompt != null) return null
        val raw = raw ?: return null
        if (passage != null || place.isCampus) return null
        val here = match?.point?.let { local(it.x, it.y) } ?: local(raw.x, raw.y)
        val lift = lifts[place].orEmpty()
            .minByOrNull { distance(local(it.x, it.y), here) }
            ?.takeIf { distance(local(it.x, it.y), here) <= LIFT_RIDE_NEAR_M }
            ?: return null
        val targets = graph.neighbors(lift.id)
            .filter { (other, type) -> type == EdgeType.LIFT && other.buildingId == lift.buildingId }
            .associate { it.first.floor to it.first }
        if (targets.isEmpty()) return null
        val from = place.floor
        val measured = ride.heightM / FLOOR_HEIGHT_M
        val floors = Math.round(measured).toInt()
        val target = targets[from + floors]
        if (floors != 0 && abs(measured - floors) <= FLOOR_ROUNDING && target != null) {
            liftSteps.clear()
            lastStairChange = null
            moveTo(target)
            return LiftOutcome.Moved(LiftChange(lift.buildingId, from, target.floor))
        }
        // Nesigurno: predlog je sprat najbliži izmerenom, u smeru vožnje.
        val suggested = targets.keys.filter { if (ride.up) it > from else it < from }.minByOrNull { abs(it - (from + measured)) }
        liftSteps.clear()
        val prompt = LiftPrompt(lift, from, targets.keys.sortedDescending(), suggested)
        liftPrompt = prompt
        return LiftOutcome.Ask(prompt)
    }

    /**
     * Odgovor na pitanje za lift: tačka na liftu na spratu [floor], pa se ponove koraci napravljeni dok je pitanje čekalo
     * (izašao iz lifta i hodao pre nego što je odgovorio). Vraća [PlaceReason.LIFT]; null ako pitanja nema.
     */
    fun selectLiftFloor(floor: Int): PlaceReason? {
        val prompt = liftPrompt ?: return null
        val target = graph.neighbors(prompt.lift.id)
            .firstOrNull { (other, type) -> type == EdgeType.LIFT && other.floor == floor }?.first
            ?: return null
        val pending = liftSteps.toList()
        liftPrompt = null
        liftSteps.clear()
        lastStairChange = null
        moveTo(target)
        for (s in pending) step(s.azimuthDeg, s.lengthM, s.redoSteps, s.timeNs)
        return PlaceReason.LIFT
    }

    /** "Nisam u liftu": pitanje se sklanja, tačka ostaje gde je. */
    fun dismissLift() {
        liftPrompt = null
        liftSteps.clear()
    }

    private fun clearLift() {
        liftPrompt = null
        liftSteps.clear()
    }

    /**
     * Jedan korak u pravcu [azimuthDeg] (magnetski, sa senzora). Ako [redoSteps] > 0, toliko prethodnih
     * koraka se ponavlja u tom pravcu (ispravljen smer). [timeNs]: vreme koraka (bilo koji sat) - pauza prekida let na
     * stepeništu. Vraća razlog ako je pozicija prešla na drugo mesto.
     */
    fun step(azimuthDeg: Float, lengthM: Float, redoSteps: Int = 0, timeNs: Long? = null): PlaceReason? {
        var raw = raw ?: return null
        // Pitanje za lift čeka: korak se pamti (ponavlja se na izabranom spratu), a na ovom spratu ide dalje (možda nije
        // bio u liftu).
        if (liftPrompt != null) liftSteps += PendingStep(azimuthDeg, lengthM, redoSteps, timeNs)
        stepsSinceStairChange++
        lastStepM = if (climb != null) STAIR_STEP_M else lengthM
        passage?.let { return passageStep(it, azimuthDeg, lengthM) }
        climb?.let { return climbStep(it, azimuthDeg, redoSteps, timeNs) }
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
        if (!place.isCampus) {
            watch.step(azimuthDeg, redo, timeNs, match?.point?.let { local(it.x, it.y) } ?: local(raw.x, raw.y))
            watch.trim(WATCH_STEPS)
            if (startClimb(azimuthDeg)) return null
            stairTurnNearby(azimuthDeg)?.let { return it }
        }
        if (++stepsSinceChange < MIN_STEPS_BETWEEN_CHANGES) return null
        return if (place.isCampus) enterBuilding(lengthM.toDouble() * (redo + 1)) else leaveThroughGateway()
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
            detector.onFix(fix)
            if (!detector.isKnown || detector.current != null) return null
            // GPS tik uz zgradu još nije izlazak: posle ulaska GPS par sekundi zaostaje ispred vrata (teren 05.10.2026, NTP:
            // ulaz -> GPS izlaz -> ulaz ... 5 puta za 20 s, "uđe ali nestane"). Kroz vrata izlazi PDR; GPS je rezerva.
            if ((campus.building(place.buildingId)?.distanceToWallM(fix.point) ?: Double.POSITIVE_INFINITY) < GPS_EXIT_WALL_M) return null
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
        climb = null
        watch = StairWalk()
        anchorPlace = place
        anchor = meters(place, point, local = true)
        walkedX = 0.0
        walkedY = 0.0
        pathInterrupted = false
        stepStarts.clear()
        gpsOutliers = 0
        exitDetector = if (place.isCampus) null else BuildingDetector(campus)
        stepsSinceChange = MIN_STEPS_BETWEEN_CHANGES
        pendingEntry = null
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

    /** Metri plana mesta (bez smeštaja) čvora/tačke. */
    private fun local(x: Float, y: Float): PointM = meters(place, Offset(x, y), local = true)

    /**
     * Tačka na grafu stigla do stepeništa (ivica do čvora stepeništa, blizu njega) -> hod po stepeništu. Koraci pre toga
     * ([watch]) ostaju u hodu - prvi let je možda već počeo.
     */
    private fun startClimb(azimuthDeg: Float): Boolean {
        // Tek sišao sa stepeništa (kod dna kraka) - ne ulazi odmah opet.
        if (++stepsSinceStairExit < STAIR_REENTER_STEPS) return false
        val point = (match ?: return false).point
        val node = listOf(point.from, point.to).firstOrNull { it.type == NodeType.STEPENISTE } ?: return false
        val stair = stairs[stairKey(node)] ?: return false
        val corridor = stair.corridor(node)
        // Samo iz hodnika; preko međupodesta (iz prolaza NB - AMF ka holu) se silazi na sprat, ne ulazi u krak.
        if (point.from.id != corridor.id && point.to.id != corridor.id) return false
        val s = local(node.x, node.y)
        val edge = distance(s, local(corridor.x, corridor.y))
        if (distance(local(point.x, point.y), s) > min(STAIR_ENTER_M, edge / 2)) return false
        // Sa dna kraka naniže se (verovatno) silazi; putanju potvrđuje ili menja strana okreta na međupodestu.
        climb = Climb(stair, watch).apply {
            up = !node.id.endsWith(DOWN_FOOT_SUFFIX)
            lastHeading = azimuthDeg
        }.also(::showClimb)
        watch = StairWalk()
        stepStarts.clear()
        return true
    }

    /**
     * Let - okret - let ([StairWalk.floorTurn]) dok tačka nije na stepeništu, a prvi let je počeo do [STAIR_NEAR_M] od
     * stepeništa sa više nivoa -> korisnik je na tom stepeništu (tačka je odlutala: na snimcima je let paralelan
     * hodniku ili grani pored stepeništa - NB P -> I kroz granu prolaza ka Amfiteatrima, AMF S1 duž hodnika).
     */
    private fun stairTurnNearby(azimuthDeg: Float): PlaceReason? {
        if (watch.floorTurn(0, 0) == 0) return null
        val start = watch.flightStart() ?: return null
        fun Stair.distanceTo(p: PointM) = nodesOn(place.floor).minOf { distance(local(it.x, it.y), p) }
        // Pravo stepenište (AMF S2) nema okret - let - okret - let pored njega nije njegov.
        val stair = stairs.values
            .filter { it.buildingId == place.buildingId && place.floor in it.nodes }
            .filter { s -> pathsOf(s, place.floor)?.all(::isStraight) != true }
            .minByOrNull { it.distanceTo(start) }
            ?.takeIf { it.distanceTo(start) <= STAIR_NEAR_M }
            ?: return null
        val climb = Climb(stair, watch).apply { lastHeading = azimuthDeg }
        val stairSteps = watch.stepsSinceFlightStart()
        val reason = changeFloorOnStairs(climb) ?: return null
        // Koraci od početka leta su bili na stepeništu - brojani su punom dužinom.
        lastStepM -= (lastStepM - STAIR_STEP_M) * stairSteps
        this.climb = climb
        watch = StairWalk()
        stepStarts.clear()
        showClimb(climb)
        return reason
    }

    /** Korak na stepeništu: kratak, okret se sabira; okret na podestu menja sprat, hod ka hodniku izlazi sa stepeništa. */
    private fun climbStep(climb: Climb, azimuthDeg: Float, redoSteps: Int, timeNs: Long?): PlaceReason? {
        climb.walk.step(azimuthDeg, redoSteps, timeNs)
        val (dx, dy) = stepVector(place, azimuthDeg, STAIR_STEP_M)
        walkedX += dx
        walkedY += dy
        val path = pathOf(climb)
        if (path != null) return if (isStraight(path)) straightStep(climb, azimuthDeg) else pathStep(climb, azimuthDeg)
        climb.offsetX += dx
        climb.offsetY += dy
        val r = hypot(climb.offsetX, climb.offsetY)
        if (r > STAIR_RADIUS_M) {
            climb.offsetX *= STAIR_RADIUS_M / r
            climb.offsetY *= STAIR_RADIUS_M / r
        }
        val reason = changeFloorOnStairs(climb)
        if (reason == null && climb.walk.leaves(towardCorridorM(climb), STAIR_EXIT_M)) {
            leaveStairs(climb)
            return null
        }
        showClimb(climb)
        return reason
    }

    /**
     * Korak na stepeništu sa krakovima: tačka ide putanjom (krak, međupodest, drugi krak, podest sprata). Na kraku
     * [FLIGHT_TREADS] koraka do kraja, na podestu srazmerno okretu (meri ga žiroskop - i kad kompas promašuje). Sprat
     * menja [StairWalk] (let - okret - let), kao i bez krakova.
     */
    private fun pathStep(climb: Climb, azimuthDeg: Float): PlaceReason? {
        val turn = climb.lastHeading?.let { angleDiffDeg(azimuthDeg, it) } ?: 0f
        climb.lastHeading = azimuthDeg
        val reason = changeFloorOnStairs(climb)
        if (reason == null && advanceOnPath(climb, azimuthDeg, turn)) {
            leaveStairs(climb)
            return null
        }
        showClimb(climb)
        return reason
    }

    /**
     * Korak na pravom stepeništu (AMF S2: dva kraka u nizu, bez okreta - [StairWalk] ga ne vidi): tačka ide celom putanjom
     * ([FLIGHT_TREADS] koraka po kraku), a sprat se menja posle tri četvrtine puta. Okret nazad ili hod dalje od kraja ->
     * silazi sa stepeništa.
     */
    private fun straightStep(c: Climb, azimuthDeg: Float): PlaceReason? {
        c.lastHeading = azimuthDeg
        if (c.flightHeading == null && abs(angleDiffDeg(headingOnPlan(azimuthDeg), flightPlanDeg(c))) > NOT_ON_FLIGHT_DEG) {
            c.progress = 0.0
            leaveStairs(c)
            return null
        }
        val ref = c.flightHeading ?: azimuthDeg.also { c.flightHeading = it }
        val off = angleDiffDeg(azimuthDeg, ref)
        if (abs(off) > TURN_DONE_DEG) {
            // Okrenuo se nazad - silazi tamo gde je (na spratu na kome je tačka).
            leaveStairs(c)
            return null
        }
        if (abs(off) <= STRAIGHT_STEP_DEG) c.flightHeading = normalizeDeg(ref + off * 0.3f)
        if (c.progress >= 1.0 && ++c.straight >= EXIT_OVERFLOW_STEPS) {
            leaveStairs(c)
            return null
        }
        c.progress = (c.progress + 1.0 / (2 * FLIGHT_TREADS)).coerceAtMost(1.0)
        var reason: PlaceReason? = null
        if (!c.changedInLeg && c.progress >= STRAIGHT_CHANGE_AT) {
            val from = place.floor
            val to = from + if (c.up) 1 else -1
            if (to in c.stair.nodes) {
                c.changedInLeg = true
                c.changes++
                place = PdrPlace(place.buildingId, to)
                lastStairChange = StairChange(c.stair.key, c.stair.buildingId, from, to, turn = 0, guessed = false)
                stepsSinceStairChange = 0
                reason = PlaceReason.STEPENICE
            }
        }
        showClimb(c)
        return reason
    }

    /** Pomera tačku po putanji stepeništa; true = korisnik silazi sa stepeništa (na mestu koje je [showClimb] postavio). */
    private fun advanceOnPath(c: Climb, heading: Float, turn: Float): Boolean {
        when (c.part) {
            ClimbPart.FIRST, ClimbPart.SECOND -> {
                // Prvi korak sa dna kraka nazad ka hodniku (stigao do stepeništa i vratio se; ili iz prolaza preko
                // međupodesta ka holu) - nije na kraku. Prag je širok: kompas u zgradi promaši i 50°.
                if (c.flightHeading == null && c.part == ClimbPart.FIRST &&
                    abs(angleDiffDeg(headingOnPlan(heading), flightPlanDeg(c))) > NOT_ON_FLIGHT_DEG
                ) {
                    c.progress = 0.0
                    return true
                }
                val ref = c.flightHeading ?: heading.also { c.flightHeading = it }
                val off = angleDiffDeg(heading, ref)
                if (abs(off) <= TURN_START_DEG) {
                    // Smer kraka prati samo korake pravo - inače bi pri postepenom okretu (NTP 05.10.2026: 15° po koraku)
                    // išao za okretom i okret nikad ne bi prešao TURN_START_DEG.
                    if (abs(off) <= STRAIGHT_STEP_DEG) c.flightHeading = normalizeDeg(ref + off * 0.3f)
                    if (c.part == ClimbPart.SECOND && !c.changedInLeg && ++c.secondSteps >= SECOND_FLIGHT_NO_CHANGE_STEPS) {
                        // Okret nije bio međupodest (vratio se istim krakom) - nazad na podest sprata sa koga je krenuo.
                        c.part = ClimbPart.FIRST
                        c.progress = 0.0
                        return true
                    }
                    if (c.progress < 1.0) {
                        c.progress = (c.progress + 1.0 / FLIGHT_TREADS).coerceAtMost(1.0)
                        return false
                    }
                    c.straight++
                    // Na kraju prvog kraka pravo dalje u prolaz sa podesta (AMF S1: niz krak u trem ka Kuli).
                    if (c.part == ClimbPart.FIRST && c.straight >= EXIT_OVERFLOW_STEPS) {
                        gateAhead(c)?.let {
                            c.exitGate = it
                            return true
                        }
                    }
                    return when (c.part) {
                        // Drugi krak je gotov, a hoda dalje pravo - podest sprata, hodnik.
                        ClimbPart.SECOND -> c.straight >= EXIT_OVERFLOW_STEPS
                        // Prvi "krak" duži od [MAX_FIRST_FLIGHT_STEPS] - hodnik pored stepeništa, nije se penjao.
                        else -> (FLIGHT_TREADS + c.straight >= MAX_FIRST_FLIGHT_STEPS).also { if (it) c.progress = 0.0 }
                    }
                }
                // Početak okreta: posle prvog kraka međupodest; posle drugog podest sprata ka sledećem kraku (ako se
                // okreće na istu stranu kao na međupodestu), inače silazi sa stepeništa.
                if (c.part == ClimbPart.SECOND) {
                    if (c.sense == 0 || (if (off > 0) 1 else -1) != c.sense) return true
                    c.part = ClimbPart.FLOOR_LANDING
                } else {
                    c.part = ClimbPart.LANDING
                }
                c.turned = off
                c.straight = 0
                labelDirection(c)
                c.progress = (abs(c.turned) / 180.0).coerceIn(0.0, 1.0)
                return false
            }
            ClimbPart.LANDING, ClimbPart.FLOOR_LANDING -> {
                c.turned += turn
                c.progress = (abs(c.turned) / 180.0).coerceIn(0.0, 1.0)
                if (c.part == ClimbPart.LANDING) labelDirection(c)
                // Na podestu se okreće postepeno (NB 05.10.2026: 10-20° po koraku kroz ~10 koraka) - pravo je tek
                // korak skoro bez promene smera.
                if (abs(turn) > STRAIGHT_STEP_DEG) {
                    c.straight = 0
                    return false
                }
                c.straight++
                if (abs(c.turned) >= TURN_DONE_DEG) {
                    if (c.straight < TURN_SETTLED_STEPS) return false
                    // Okret završen: sledeći krak (posle podesta sprata - krak ka sledećem spratu).
                    if (c.part == ClimbPart.LANDING) {
                        c.part = ClimbPart.SECOND
                        c.secondSteps = 1
                    } else {
                        c.part = ClimbPart.FIRST
                        c.changedInLeg = false
                    }
                    c.flightHeading = heading
                    c.progress = 1.0 / FLIGHT_TREADS
                    c.straight = 0
                    return false
                }
                // Hoda pravo bez punog okreta: sa podesta sprata nastavio hodnikom; sa međupodesta u prolaz, ako ga ima (NB ->
                // Amfiteatri), inače nema kuda - nije se ni penjao (okret u hodniku pored stepeništa) -> nazad na dno kraka.
                if (c.straight < LANDING_EXIT_STEPS) return false
                if (c.part == ClimbPart.LANDING) {
                    c.exitGate = c.stair.nodes[place.floor]?.let { landingGate(c.stair, it) }.takeIf { c.up }
                    c.part = ClimbPart.FIRST
                    c.progress = 0.0
                }
                return true
            }
        }
    }

    /** Smer prvog kraka na planu (0 = gore, u smeru kazaljke): naviše od dna kraka naviše, naniže od dna kraka naniže. */
    private fun flightPlanDeg(c: Climb): Float {
        val (a, b) = firstFlight(c) ?: return 0f
        return azimuthOf(b.x - a.x, b.y - a.y)
    }

    /** Prvi krak hoda [c] u metrima plana: (dno, vrh) - naviše od dna kraka naviše, naniže od dna kraka naniže. */
    private fun firstFlight(c: Climb): Pair<PointM, PointM>? {
        val path = pathOf(c) ?: return null
        val (a, b) = if (c.up) path[0] to path[1] else path[3] to path[2]
        return local(a.x, a.y) to local(b.x, b.y)
    }

    /**
     * Prolaz sa podesta na kraju prvog kraka, pravo ispred (AMF S1: trem ka Kuli ispod krakova): do 0,5 m iza kraja
     * kraka i najviše 3,5 m u stranu. NB prolaz ka AMF-u je sa strane međupodesta - do njega se skreće ([landingGate]).
     */
    private fun gateAhead(c: Climb): Pair<Node, Node>? {
        val start = (if (c.up) c.stair.nodes[place.floor] else c.stair.downNodes[place.floor]) ?: return null
        val gate = landingGate(c.stair, start) ?: return null
        val (a, b) = firstFlight(c) ?: return null
        val length = distance(a, b).takeIf { it > 0 } ?: return null
        val ux = (b.x - a.x) / length
        val uy = (b.y - a.y) / length
        val g = local(gate.second.x, gate.second.y)
        val ahead = (g.x - b.x) * ux + (g.y - b.y) * uy
        val aside = abs((g.x - b.x) * uy - (g.y - b.y) * ux)
        return gate.takeIf { ahead >= -0.5 && aside <= 3.5 }
    }

    /**
     * Na međupodestu, pre promene sprata: strana okreta po crtežu krakova kaže kojim krakom je išao (penjanje se okreće
     * na stranu [pathUpTurns]) - ako je ušao na drugi krak nego što se mislilo, tačka prelazi na njega. Stepenište sa dva
     * nivoa (AMF S1): smer je poznat (sa najnižeg gore, sa najvišeg dole), a strana okreta bira bočni krak.
     */
    private fun labelDirection(c: Climb) {
        if (c.changes > 0 || abs(c.turned) < TURN_START_DEG) return
        val turn = if (c.turned > 0) 1 else -1
        val canUp = (place.floor + 1) in c.stair.nodes
        val canDown = (place.floor - 1) in c.stair.nodes
        c.up = if (canUp && canDown) pathUpTurns[c.stair.key]?.let { turn == it } ?: c.up else canUp
        selectVariant(c, if (c.up) turn else -turn)
    }

    /**
     * Okret od [STAIR_TURN_DEG] na jednu stranu (pa svakih sledećih [STAIR_TURN_PER_FLOOR_DEG]) -> sprat više ili niže,
     * po strani okreta. Vraća [PlaceReason.STEPENICE] ako je sprat promenjen.
     */
    private fun changeFloorOnStairs(climb: Climb): PlaceReason? {
        val sense = climb.walk.floorTurn(climb.changes, climb.sense).takeIf { it != 0 } ?: return null
        val stair = climb.stair
        val from = place.floor
        val up = (from + 1) in stair.nodes
        val down = (from - 1) in stair.nodes
        val known = pathUpTurns[stair.key] ?: learnedUpTurns[stair.key] ?: STAIR_UP_TURN[stair.key]
        val hint = destination?.takeIf { it.buildingId == stair.buildingId && it.floor != from }?.floor
        // Sa najnižeg/najvišeg nivoa smer je poznat - i uči se strana okreta (ako se već ne zna; stepeništa sa dva nivoa
        // ne treba učiti).
        val learn = stair.nodes.size > 2 && known == null
        val step = when {
            up && !down -> 1.also { if (learn) learnedUpTurns[stair.key] = sense }
            down && !up -> (-1).also { if (learn) learnedUpTurns[stair.key] = -sense }
            !up -> return null
            known != null -> if (known == sense) 1 else -1
            hint != null -> if (hint > from) 1 else -1
            else -> 1
        }
        climb.walk.confirmChange()
        climb.sense = sense
        climb.changes++
        // Na novom spratu hod se meri od čvora stepeništa: drugi let se vraća ka podestu sprata, pa bi stari hod
        // (do 3 m) mogao odmah da izgleda kao izlazak u hodnik.
        climb.offsetX = 0.0
        climb.offsetY = 0.0
        // Sa krakovima: tačka na drugom kraku (već sprat iznad/ispod), koliko je koraka njime prešao.
        climb.up = step > 0
        selectVariant(climb, if (climb.up) sense else -sense)
        climb.part = ClimbPart.SECOND
        climb.changedInLeg = true
        climb.secondSteps = climb.walk.currentRunSteps()
        climb.progress = (climb.secondSteps.toDouble() / FLIGHT_TREADS).coerceAtMost(1.0)
        climb.flightHeading = climb.lastHeading
        climb.straight = 0
        place = PdrPlace(place.buildingId, from + step)
        lastStairChange = StairChange(stair.key, stair.buildingId, from, from + step, sense, guessed = up && down && known == null)
        stepsSinceStairChange = 0
        return PlaceReason.STEPENICE
    }

    /** Hod na stepeništu (od čvora) u pravcu hodnika, u metrima. */
    private fun towardCorridorM(climb: Climb): Double {
        val s = climb.stair.nodes.getValue(place.floor)
        val c = climb.stair.corridors.getValue(place.floor)
        val a = local(s.x, s.y)
        val b = local(c.x, c.y)
        val length = distance(a, b).takeIf { it > 0 } ?: return 0.0
        return (climb.offsetX * (b.x - a.x) + climb.offsetY * (b.y - a.y)) / length
    }

    /**
     * Pozicija na stepeništu. Sa krakovima: tačka na putanji (penjanje od dna kraka naviše, silazak od dna kraka naniže),
     * bez lepljenja na graf. Bez krakova: na ivici stepenište - hodnik, koliko je hodao ka hodniku; slobodna = čvor + hod.
     */
    private fun showClimb(climb: Climb) {
        val path = pathOf(climb)
        if (path != null) {
            val ordered = if (climb.up) path else path.asReversed()
            if (isStraight(path)) {
                val scale = graph.placement(place.buildingId).scale
                raw = pointAlong(ordered, climb.progress, scale.widthM.toDouble(), scale.heightM.toDouble())
                match = null
                return
            }
            val (a, b, c, d) = ordered
            val (from, to) = when (climb.part) {
                ClimbPart.FIRST -> a to b
                ClimbPart.LANDING -> b to c
                ClimbPart.SECOND -> c to d
                ClimbPart.FLOOR_LANDING -> d to a
            }
            val t = climb.progress.toFloat()
            raw = Offset(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t)
            match = null
            return
        }
        val s = climb.stair.nodes.getValue(place.floor)
        val c = climb.stair.corridors.getValue(place.floor)
        val length = distance(local(s.x, s.y), local(c.x, c.y))
        val t = if (length > 0) (towardCorridorM(climb) / length).coerceIn(0.0, 1.0) else 0.0
        val scale = graph.placement(place.buildingId).scale
        val free = Offset(
            (s.x + climb.offsetX / scale.widthM).toFloat().coerceIn(0f, 1f),
            (s.y + climb.offsetY / scale.heightM).toFloat().coerceIn(0f, 1f),
        )
        raw = free
        match = MatchedPosition(EdgePoint(s, c, t), free.x, free.y)
    }

    /** Sa stepeništa u hodnik: od tačke na stepeništu (kraj kraka / ivica stepenište - hodnik) dalje map-matching sprata. */
    private fun leaveStairs(climb: Climb) {
        this.climb = null
        stepStarts.clear()
        stepsSinceStairExit = 0
        // Bez promene sprata koraci ostaju za sledeći ulazak (let pre okreta); posle promene ne - isti okret bi se brojao
        // još jednom.
        if (climb.changes == 0) watch = climb.walk.detached()
        climb.exitGate?.let { (inner, gate) ->
            // Na kraju puta preko međupodesta, na vratima prolaza - sledeći koraci napolje prelaze prolaz.
            raw = Offset(gate.x, gate.y)
            match = MatchedPosition(EdgePoint(inner, gate, 1.0), gate.x, gate.y)
            return
        }
        showClimb(climb)
        val point = match?.point?.let { Offset(it.x, it.y) } ?: raw!!
        raw = point
        match = matcherFor(place)?.start(point.x, point.y)
    }

    /**
     * Prolaz sa međupodesta stepeništa [stair] na trenutnom spratu (NB prizemlje -> Amfiteatri): od dna kraka naviše
     * putem koji ne ide u hodnik do čvora prolaza. Vraća (čvor pre prolaza, čvor prolaza).
     */
    private fun landingGate(stair: Stair, foot: Node): Pair<Node, Node>? {
        val gates = gateways[place].orEmpty().map { it.node.id }.toSet()
        var prev = foot
        var current = graph.neighbors(foot.id).map { it.first }
            .firstOrNull { it.floor == foot.floor && it.buildingId == foot.buildingId && it.id != stair.corridor(foot).id } ?: return null
        repeat(4) {
            if (current.id in gates) return prev to current
            val next = graph.neighbors(current.id).map { it.first }
                .firstOrNull { it.id != prev.id && it.floor == foot.floor && it.buildingId == foot.buildingId } ?: return null
            prev = current
            current = next
        }
        return null
    }

    /**
     * Ručno označavanje ubrzo posle promene sprata na stepeništu: na koju stranu je stvarno išao (gore ili dole od
     * sprata sa koga je krenuo) -> strana okreta pri penjanju za to stepenište.
     */
    private fun learnStairTurn(place: PdrPlace) {
        val change = lastStairChange ?: return
        lastStairChange = null
        if (stepsSinceStairChange > STAIR_LEARN_STEPS || place.buildingId != change.buildingId) return
        if ((stairs[change.stairKey]?.nodes?.size ?: 0) <= 2 || change.stairKey in pathUpTurns) return
        val actual = place.floor.compareTo(change.fromFloor)
        if (actual != 0) learnedUpTurns[change.stairKey] = if (actual > 0) change.turn else -change.turn
    }

    /** Napolju: tačka duboko u obrisu zgrade sa planom, blizu njenog ulaza -> u zgradu, na taj ulaz. */
    /**
     * Napolju posle koraka ([stepM]: dužina koraka, uz ponovljene): tačka u obrisu zgrade sa planom blizu njenog ulaza ->
     * ulazak, ali tek kad koraci pređu put koji je tačka tada imala do ulaza ([PendingEntry]). Pređeni put je zbir dužina
     * koraka, ne deo u pravcu ulaza: tačka koja uđe u obris pravo kroz vrata je već iza njih, a tačka koju GPS drži bočno
     * od vrata ide pored njih (teren 08.10.2026, NB: tačka 5 m bočno, korisnik ide popreko na zid ka vratima).
     */
    private fun enterBuilding(stepM: Double): PlaceReason? {
        val p = meters(PdrPlace.CAMPUS, raw ?: return null)
        pendingEntry?.let { pending ->
            pending.walkedM += stepM
            if (pending.walkedM >= pending.remainingM - ENTER_SLACK_M) {
                moveTo(pending.entrance.inside)
                return PlaceReason.ULAZ
            }
            if (distance(graph.position(pending.entrance.outside), p) <= ENTER_CANCEL_M) return null
            pendingEntry = null
        }
        for ((buildingId, list) in entrances) {
            val outline = campus.building(buildingId) ?: continue
            if (!outline.contains(p) || outline.distanceToWallM(p) < ENTER_DEPTH_M) continue
            val entrance = list.minBy { distance(graph.position(it.outside), p) }
            val toDoor = distance(graph.position(entrance.outside), p)
            if (toDoor > ENTER_RADIUS_M) continue
            if (toDoor <= ENTER_SLACK_M) {
                moveTo(entrance.inside)
                return PlaceReason.ULAZ
            }
            pendingEntry = PendingEntry(entrance, toDoor)
            return null
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

/**
 * Pravo stepenište (AMF S2: dva kraka u nizu): drugi krak ide istim smerom kao prvi. Kod stepeništa sa okretom su suprotni.
 */
private fun isStraight(path: List<PlanPoint>): Boolean {
    val (a, b, c, d) = path
    return (b.x - a.x) * (d.x - c.x) + (b.y - a.y) * (d.y - c.y) > 0
}

/** Strana okreta pri penjanju putanjom [path] (+1 u smeru kazaljke; 0 za pravo stepenište). */
private fun upTurnOf(path: List<PlanPoint>): Int {
    if (isStraight(path)) return 0
    val (a, b, c) = path
    // Ugao u relativnim koordinatama (x udesno, y naniže) ima isti znak kao u metrima.
    return if ((b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x) > 0) 1 else -1
}

/** Tačka na izlomljenoj liniji [points] na delu [t] (0..1) njene dužine (u relativnim koordinatama plana [scale]). */
private fun pointAlong(points: List<PlanPoint>, t: Double, widthM: Double, heightM: Double): Offset {
    val lengths = points.zipWithNext { p, q -> hypot((q.x - p.x) * widthM, (q.y - p.y) * heightM) }
    var rest = lengths.sum() * t.coerceIn(0.0, 1.0)
    for ((i, length) in lengths.withIndex()) {
        if (rest <= length || i == lengths.lastIndex) {
            val k = if (length > 0) (rest / length).coerceIn(0.0, 1.0).toFloat() else 0f
            val (p, q) = points[i] to points[i + 1]
            return Offset(p.x + (q.x - p.x) * k, p.y + (q.y - p.y) * k)
        }
        rest -= length
    }
    return Offset(points.last().x, points.last().y)
}

/** Koliko je [p] od [origin] u pravcu [from] -> [to] (m); 0 ako pravca nema. */
private fun along(p: PointM, origin: PointM, from: PointM, to: PointM): Double {
    val length = distance(from, to).takeIf { it > 0 } ?: return 0.0
    return ((p.x - origin.x) * (to.x - from.x) + (p.y - origin.y) * (to.y - from.y)) / length
}

/** Azimut vektora u metrima plana (x udesno, y naniže): 0 = gore, u smeru kazaljke. */
private fun azimuthOf(dx: Double, dy: Double): Float = Math.toDegrees(atan2(dx, -dy)).toFloat()
