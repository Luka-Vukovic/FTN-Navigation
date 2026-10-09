package com.example.ftnnavigation.poc

import android.app.Application
import android.hardware.GeomagneticField
import android.hardware.SensorManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import android.widget.Toast
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ftnnavigation.BuildConfig
import com.example.ftnnavigation.R
import com.example.ftnnavigation.campus.BuildingCategory
import com.example.ftnnavigation.campus.BuildingDetector
import com.example.ftnnavigation.campus.CAMPUS_ID
import com.example.ftnnavigation.campus.CampusBuilding
import com.example.ftnnavigation.campus.CampusData
import com.example.ftnnavigation.campus.CampusGeo
import com.example.ftnnavigation.campus.GpsFix
import com.example.ftnnavigation.campus.RouteTarget
import com.example.ftnnavigation.campus.loadCampus
import com.example.ftnnavigation.campus.loadGraph
import com.example.ftnnavigation.campus.loadStairPaths
import com.example.ftnnavigation.campus.offCampusPlaceOf
import com.example.ftnnavigation.campus.resolveTarget
import com.example.ftnnavigation.campus.roomBuildingNames
import com.example.ftnnavigation.campus.routeBetween
import com.example.ftnnavigation.campus.crowdFactors
import com.example.ftnnavigation.campus.toggled
import com.example.ftnnavigation.campus.withRecent
import com.example.ftnnavigation.departure.Departure
import com.example.ftnnavigation.departure.DepartureScheduler
import com.example.ftnnavigation.departure.PlaceRoute
import com.example.ftnnavigation.departure.PlanLeg
import com.example.ftnnavigation.departure.departureFor
import com.example.ftnnavigation.departure.floorText
import com.example.ftnnavigation.departure.placeLocationText
import com.example.ftnnavigation.events.PlaceOptions
import com.example.ftnnavigation.graph.BuildingGraph
import com.example.ftnnavigation.graph.MatchedPosition
import com.example.ftnnavigation.graph.AmfPlan
import com.example.ftnnavigation.graph.IndoorBuilding
import com.example.ftnnavigation.graph.FPlan
import com.example.ftnnavigation.graph.ItcPlan
import com.example.ftnnavigation.graph.MiPlan
import com.example.ftnnavigation.graph.KulaPlan
import com.example.ftnnavigation.graph.NbPlan
import com.example.ftnnavigation.graph.Node
import com.example.ftnnavigation.graph.NodeType
import com.example.ftnnavigation.graph.NtpPlan
import com.example.ftnnavigation.graph.indoorBuilding
import com.example.ftnnavigation.graph.PlanPlacement
import com.example.ftnnavigation.graph.Route
import com.example.ftnnavigation.graph.routeOrFallback
import com.example.ftnnavigation.schedule.AgendaItem
import com.example.ftnnavigation.schedule.RoomSchedule
import com.example.ftnnavigation.settings.AppSettings
import com.example.ftnnavigation.settings.FloorChange
import com.example.ftnnavigation.settings.snapStepLength
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Stanje PoC ekrana. Pozicije su relativne u odnosu na plan mesta [pdrPlace] (sprat zgrade ili cela mapa
 * kampusa, 0..1 po obe ose), tako da ne zavise od rezolucije plana ni od zuma.
 */
data class PocUiState(
    /**
     * Gde je pozicija: sprat zgrade sa planom ili kampus. Menja se pri označavanju ("Ovde sam") i sama pri
     * prelazu kroz ulaz ili spojni prolaz ([PdrLocator]); sprat se u hodu ne menja (nema barometra).
     */
    val pdrPlace: PdrPlace = PdrPlace(NbPlan.BUILDING_ID),
    /** Slobodna PDR pozicija (napolju i sa GPS-om), bez lepljenja na graf - za poređenje sa [match]. */
    val rawPosition: Offset? = null,
    /** PDR pozicija zalepljena za graf sprata; null na kampusu i u spojnom prolazu. */
    val match: MatchedPosition? = null,
    /** Smer (sa ispravkom iz rekalibracije) u odnosu na "gore" plana [pdrPlace] (0 = gore). */
    val headingDeg: Float = 0f,
    /** Odstupanje telefona od pravca hoda (-180..180); null dok se premešten telefon smiruje. */
    val phoneOffsetDeg: Float? = 0f,
    val steps: Int = 0,
    val isTracking: Boolean = false,
    val isPickingStart: Boolean = false,
    /** Prikaz i ruta sa grafa (map-matching); isključeno = čist PDR (provera smera hoda). */
    val snapToGraph: Boolean = true,
    /** Ispravka smera iz rekalibracije uključena; isključeno = samo se meri ([PdrLocator.applyCorrection]). */
    val applyHeadingCorrection: Boolean = true,
    /** Poslednja izmerena greška smera u zgradi u kojoj je korisnik (PDR − stvarni, bez ispravke); null = nije merena. */
    val headingErrorDeg: Float? = null,
    /** Pređeno (m): koraci punom dužinom, a na stepeništu samo gazište ([PdrLocator.lastStepM]). */
    val distanceM: Float = 0f,
    /** Pitanje "na koji sprat?" posle vožnje liftom kad broj spratova nije siguran ([PdrLocator.liftRide]); null = nema pitanja. */
    val liftPrompt: LiftPrompt? = null,
) {

    /** Pozicija na grafu ako je lepljenje uključeno (map-matching se računa i kad nije). */
    val shownMatch: MatchedPosition? get() = match.takeIf { snapToGraph }

    /** Pozicija za prikaz i rutu: sa grafa, a dok ga nema (ili je lepljenje isključeno) čist PDR. */
    val position: Offset? get() = shownMatch?.point?.let { Offset(it.x, it.y) } ?: rawPosition
}

/** Šta Mapa prikazuje: spoljnu mapu kampusa ili sprat zgrade sa planom ([building]). */
enum class MapMode(val building: IndoorBuilding?) {
    KAMPUS(null),
    NB(NbPlan),
    AMF(AmfPlan),
    KULA(KulaPlan),
    NTP(NtpPlan),
    F(FPlan),
    MI(MiPlan),
    ITC(ItcPlan),
}

/**
 * Izbori na prekidaču Mape: kampus, plan zgrade u kojoj je korisnik po GPS-u ([here]) i ono što je prikazano
 * ([mode]); ostale zgrade su u meniju "…". Napolju (ili dok se ne zna) samo kampus i "…" - do 04.10.2026 su tada
 * bile sve zgrade, pa su natpisi bili odsečeni ("Kam", "Amfi", "F-blo"); korisnik: "kampus i ..., gde ... daje
 * padajući meni sa zgradama".
 */
internal fun shownModes(mode: MapMode, here: CampusBuilding?): List<MapMode> =
    MapMode.entries.filter { it == MapMode.KAMPUS || it == mode || (here != null && it.building?.buildingId == here.id) }

/** Odakle kreće ruta: pozicija (PDR, napolju i sa GPS-om), GPS lokacija (bez praćenja) ili glavni ulaz NB-a. */
enum class RouteStart { PDR, GPS, ENTRANCE }

/** Plan dana [date] na Mapi: rute dana redom ([legs]), prikazana je [index]. */
data class ShownPlan(val date: LocalDate, val legs: List<PlanLeg>, val index: Int) {
    val leg: PlanLeg get() = legs[index]
}

/** GPS lokacija lošija od ovoga se ne koristi za rutu (u zgradi luta desetinama metara). */
private const val MAX_ROUTE_ACCURACY_M = 50f

/** Start praćenja sa GPS lokacije (bez označavanja) samo uz ovoliku tačnost. */
private const val MAX_START_ACCURACY_M = 20f

/** GPS ruta kreće sa staze ili sa ulaza, ne iz unutrašnjosti zgrade bez plana ili spojnog prolaza. */
private val GPS_START_TYPES = setOf(NodeType.STAZA, NodeType.ULAZ)

/** Manja gužva na ruti se ne piše u baneru. */
private const val MIN_CROWD_PERCENT = 5

/** Vibracija uz pitanje za lift (telefon je u vožnji možda u džepu): dva kratka. */
private val LIFT_VIBRATION = longArrayOf(0, 150, 120, 150)

// Visina kampusa - za magnetsku deklinaciju (tačka je referentna tačka projekcije, CampusGeo).
private const val CAMPUS_ALT_M = 80f

/**
 * Magnetski azimut pravca "gore" na planu. Plan je u mapi kampusa (x istok, y jug, sever gore)
 * zarotiran za [PlanPlacement.rotationDeg] u smeru kazaljke, pa je i "gore" (sever pre rotacije)
 * okrenuto za toliko: geografski azimut = rotationDeg. Senzor rotacije meri od magnetskog
 * severa - oduzima se deklinacija (istočna +).
 */
internal fun planUpMagneticAzimuthDeg(placement: PlanPlacement, declinationDeg: Float): Float =
    normalizeDeg(placement.rotationDeg.toFloat() - declinationDeg)

/**
 * Toast posle označavanja: koliko je smer promašio i šta je urađeno. Najviše dva reda (Android 12+ seče duže
 * tekstualne toast-ove).
 */
private fun headingCheckText(app: Application, check: HeadingCheck): String = when (check) {
    is HeadingCheck.TooShort -> app.getString(R.string.poc_heading_check_short, check.actualM.roundToInt())
    HeadingCheck.Interrupted -> app.getString(R.string.poc_heading_check_interrupted)
    is HeadingCheck.Measured -> {
        val measured = app.getString(
            R.string.poc_heading_check_measured,
            check.errorDeg.roundToInt(),
            (check.lengthRatio * 100).roundToInt(),
        )
        val outcome = when {
            !check.reliable -> app.getString(R.string.poc_heading_check_unreliable)
            !check.applied -> app.getString(R.string.poc_heading_check_not_applied)
            // Na putu je već važila ispravka: koliko je promašio uz nju (da li ispravka pomaže).
            abs(angleDiffDeg(check.residualDeg, check.errorDeg)) >= 0.5f ->
                app.getString(R.string.poc_heading_check_corrected_again, check.residualDeg.roundToInt())
            else -> app.getString(R.string.poc_heading_check_corrected)
        }
        "$measured\n$outcome"
    }
}

/**
 * Stanje mape i rute. Vezan za aktivnost: preživljava promenu taba, a Početna preko njega
 * nudi rutu do sale sledećeg časa. Od Start do Stop drži PDR senzore ([PdrSensorSession]) i
 * [PdrTrackingService], pa se koraci broje i sa ugašenim ekranom, na drugom tabu i dok je
 * aplikacija u pozadini (dok aktivnost postoji).
 */
class PocViewModel(application: Application) : AndroidViewModel(application) {
    var state by mutableStateOf(PocUiState())
        private set

    /** Mapa kampusa iz assets-a (null dok se učitava). */
    var campus by mutableStateOf<CampusData?>(null)
        private set

    /** Graf kampusa i zgrada (null dok se učitava iz baze). NB ima 7 nivoa (-1 ... 5), AMF 2, Kula 10, NTP 6. */
    var graph by mutableStateOf<BuildingGraph?>(null)
        private set

    /** Pozicija: PDR na planu, napolju i GPS, prelazi između zgrada i kampusa (null dok se graf učitava). */
    private var locator: PdrLocator? = null

    /** Poslednji azimut sa senzora - da se smer preračuna kad se PDR premesti na drugi plan. */
    private var lastAzimuthDeg: Float? = null

    var mode by mutableStateOf(MapMode.NB)
        private set

    /** Auto-rotacija mape (Podešavanja): mapa se okreće za po 90° po smeru korisnika. */
    var autoRotateMap by mutableStateOf(AppSettings.autoRotateMap(application))
        private set

    fun updateAutoRotateMap(enabled: Boolean) {
        autoRotateMap = enabled
        AppSettings.setAutoRotateMap(getApplication(), enabled)
    }

    /** Dužina koraka (Podešavanja): koliko tačka pređe po koraku. Važi od sledećeg koraka, i usred praćenja. */
    var stepLengthM by mutableStateOf(AppSettings.stepLengthM(application))
        private set

    fun updateStepLength(meters: Float) {
        stepLengthM = snapStepLength(meters)
        AppSettings.setStepLengthM(getApplication(), stepLengthM)
        recorder?.stepLength(SystemClock.elapsedRealtimeNanos(), stepLengthM)
    }

    /** Promena sprata na ruti (Podešavanja): najbrže, bez stepenica ili bez lifta. */
    var floorChange by mutableStateOf(AppSettings.floorChange(application))
        private set

    /** Menja i vreme polaska - obaveštenje se zakazuje iznova. */
    fun updateFloorChange(value: FloorChange) {
        floorChange = value
        val app = getApplication<Application>()
        AppSettings.setFloorChange(app, value)
        viewModelScope.launch { DepartureScheduler.reschedule(app) }
    }

    /** Sprat koji Mapa prikazuje, po zgradi (podrazumevano prizemlje). PDR je na spratu gde je start. */
    private var floors by mutableStateOf(mapOf<String, Int>())

    fun floorOf(building: IndoorBuilding): Int = floors[building.buildingId] ?: 0

    private fun showFloor(building: IndoorBuilding, floor: Int) {
        floors = floors + (building.buildingId to floor)
    }

    /** Smer hoda iz senzora (ne pravac telefona); na Start se pretpostavlja telefon u ruci. */
    val walkingDirection = WalkingDirection()

    /** Snimak senzora od Start do Stop (samo debug build) - vidi [SensorRecorder]. */
    private var recorder: SensorRecorder? = null

    /** Senzori za vreme praćenja (od Start do Stop). */
    private var session: PdrSensorSession? = null

    /** GPS: radi dok je Mapa na ekranu ([onMapVisible]) i za vreme praćenja. */
    private val gpsRecorder = if (BuildConfig.DEBUG) GpsRecorder(application.filesDir) else null
    private val gpsSession = GpsSession(application, gpsRecorder, ::onGpsFix)
    private var mapVisible = false

    /** Poslednja GPS lokacija; null dok GPS ne radi (nema dozvole, Mapa nije na ekranu i nema praćenja). */
    var gps by mutableStateOf<GpsFix?>(null)
        private set

    private var buildingDetector: BuildingDetector? = null

    /** Zgrada u kojoj je korisnik po GPS-u; null = napolju ili se ne zna. */
    var currentBuilding by mutableStateOf<CampusBuilding?>(null)
        private set

    /** Naziv sale (kao u rasporedu) ili zgrade; čuva se i dok se graf još učitava. */
    var destination by mutableStateOf<String?>(null)
        private set

    /** Odredište zadato čvorom ([selectPlace]: toalet nema naziv); null - [destination] je naziv sale ili zgrade. */
    private var destinationNodeId by mutableStateOf<String?>(null)

    /** Gde vodi ruta do [destination]; null ako se ne zna gde je (ili se graf učitava). */
    val target: RouteTarget? by derivedStateOf {
        val graph = graph ?: return@derivedStateOf null
        val campus = campus ?: return@derivedStateOf null
        destinationNodeId?.let { id ->
            return@derivedStateOf graph.node(id)?.let { RouteTarget(it, campus.building(it.buildingId), approximate = false) }
        }
        destination?.let { resolveTarget(it, graph, campus) }
    }

    /** GPS lokacija dovoljno tačna za početak rute, na mapi kampusa (van nje ruta nema smisla). */
    private val routeGps: GpsFix?
        get() {
            val campus = campus ?: return null
            return gps?.takeIf {
                it.accuracyM <= MAX_ROUTE_ACCURACY_M &&
                    it.point.x in 0.0..campus.widthM.toDouble() && it.point.y in 0.0..campus.heightM.toDouble()
            }
        }

    /**
     * GPS lokacija za start praćenja bez označavanja: tačna i napolju (ili u zgradi bez plana). U zgradi sa
     * planom GPS nije dovoljno tačan - tamo se označava na planu.
     */
    private val startGps: GpsFix?
        get() = routeGps?.takeIf {
            it.accuracyM <= MAX_START_ACCURACY_M && currentBuilding?.let { b -> indoorBuilding(b.id) } == null
        }

    /** Praćenje može da počne: pozicija postoji ili je napolju dobar GPS. */
    val canStartTracking: Boolean by derivedStateOf {
        graph != null && (state.rawPosition != null || startGps != null)
    }

    /** Odakle kreće [route]: pozicija ima prednost (u zgradi PDR, napolju PDR + GPS), pa GPS, pa glavni ulaz NB-a. */
    val routeStart: RouteStart by derivedStateOf {
        when {
            state.rawPosition != null -> RouteStart.PDR
            routeGps != null -> RouteStart.GPS
            else -> RouteStart.ENTRANCE
        }
    }

    /**
     * Ruta do odredišta: od pozicije na grafu (kroz bolji kraj njene ivice), od GPS lokacije (preko
     * najbliže staze ili ulaza), inače od glavnog ulaza. Ponovo se računa pri svakom koraku i
     * lokaciji - graf je mali, A* traje ispod milisekunde. Promena sprata po podešavanju ([floorChange]); gde po
     * njemu puta nema, ruta bez izbegavanja ([Route.fallback] - baner to piše).
     */
    val route: Route? by derivedStateOf {
        val plan = plan
        // Plan dana: ruta sa mesta prethodne stavke (izračunata pri otvaranju), ne od pozicije.
        if (plan != null) plan.leg.route else target?.let { routeTo(it.node.id) }
    }

    /** Plan dana na Mapi: rute dana redom, prikazana je jedna; null - Mapa prikazuje rutu do [destination]. */
    var plan by mutableStateOf<ShownPlan?>(null)
        private set

    /** Prikazuje rutu [index] plana dana [date] ([legs] iz [dayPlan]); Mapa prelazi na njen početak. */
    fun showPlan(date: LocalDate, legs: List<PlanLeg>, index: Int) {
        if (legs.isEmpty()) return
        val shown = ShownPlan(date, legs, index.coerceIn(legs.indices))
        plan = shown
        destinationNodeId = null
        destination = shown.leg.to.place
        (shown.leg.route?.nodes?.firstOrNull() ?: target?.node)?.let(::showPlace)
    }

    /** Prethodna ([delta] −1) ili sledeća (+1) ruta plana dana. */
    fun stepPlan(delta: Int) {
        val plan = plan ?: return
        showPlan(plan.date, plan.legs, plan.index + delta)
    }

    /** Rute dana [day] redom (plan dana), istim računanjem kao polazak ([departureFor]); prazno dok se graf učitava. */
    fun dayPlan(day: List<AgendaItem>): List<PlanLeg> {
        val route = placeRoute() ?: return emptyList()
        return com.example.ftnnavigation.departure.dayPlan(day, route)
    }

    /** Ruta između dva mesta za polazak / plan dana: podešavanje sprata i gužva u vreme dolaska. */
    private fun placeRoute(): PlaceRoute? {
        val graph = graph ?: return null
        val campus = campus ?: return null
        val profile = floorChange.profile
        return { from, to, arriveAt -> routeBetween(graph, campus, from, to, profile.copy(buildingCrowd = crowdAt(arriveAt))) }
    }

    /** Omiljena odredišta (zvezdica u izboru odredišta i u pop-up-u sale/zgrade), redom dodavanja. */
    var favorites by mutableStateOf(AppSettings.favorites(application))
        private set

    /** Nedavna odredišta (izabrana za rutu), najnovije prvo. */
    var recents by mutableStateOf(AppSettings.recents(application))
        private set

    fun toggleFavorite(name: String) {
        favorites = favorites.toggled(name)
        AppSettings.setFavorites(getApplication(), favorites)
    }

    /** Zgrada u kojoj je korisnik: zgrada pozicije (PDR), inače po GPS-u; null = napolju ili se ne zna. */
    val hereBuildingId: String?
        get() = state.pdrPlace.building?.buildingId?.takeIf { state.rawPosition != null } ?: currentBuilding?.id

    /** Ruta do čvora [nodeId] odakle kreće i ruta na Mapi ([routeStart]); null dok se graf učitava ili puta nema. */
    fun routeTo(nodeId: String): Route? {
        val graph = graph ?: return null
        val campus = campus ?: return null
        val match = state.shownMatch
        val raw = state.rawPosition
        val gps = routeGps
        val place = state.pdrPlace
        return routeOrFallback(floorChange.profile.copy(buildingCrowd = crowdNow)) { profile ->
            when {
                match != null -> graph.routeFrom(match.point, nodeId, profile)
                raw != null -> graph.routeFrom(
                    place.buildingId, place.floor, raw.x, raw.y, nodeId, profile,
                    startTypes = GPS_START_TYPES.takeIf { place.isCampus },
                )
                gps != null -> graph.routeFrom(
                    CAMPUS_ID, 0,
                    (gps.point.x / campus.widthM).toFloat(), (gps.point.y / campus.heightM).toFloat(),
                    nodeId, profile,
                    startTypes = GPS_START_TYPES,
                )
                else -> graph.route(NbPlan.ENTRANCE_ID, nodeId, profile)
            }
        }
    }

    init {
        viewModelScope.launch {
            val campus = loadCampus(application)
            this@PocViewModel.campus = campus
            buildingDetector = BuildingDetector(campus)
            val declination = GeomagneticField(
                CampusGeo.REF_LAT.toFloat(), CampusGeo.REF_LON.toFloat(), CAMPUS_ALT_M, System.currentTimeMillis(),
            ).declination
            val graph = loadGraph(application, campus)
            // Pozicija se ne može postaviti pre ovoga ("Ovde sam" i Start čekaju graf).
            locator = PdrLocator(graph, campus, declination, loadStairPaths(application)).apply {
                applyCorrection = state.applyHeadingCorrection
                restoreStairTurns(AppSettings.stairTurns(application))
                restoreHeadingErrors(AppSettings.headingErrors(application))
            }
            this@PocViewModel.graph = graph
            lastAzimuthDeg?.let(::onHeading)
        }
    }

    /**
     * Polazak kao u obaveštenju: sa mesta prethodne stavke istog dana ([day]), inače od glavnog
     * ulaza. Null = prethodna stavka je na istom mestu. Dok se graf učitava, ruta je null.
     */
    fun departureFor(item: AgendaItem, day: List<AgendaItem>): Departure? {
        val route = placeRoute()
        return departureFor(item, day, route = { from, to, arriveAt -> route?.invoke(from, to, arriveAt) })
    }

    /** Zauzetost sala svih rasporeda (za gužvu); postavlja je `FtnApp` kad se raspored učita. */
    private var roomSchedule by mutableStateOf<RoomSchedule?>(null)

    fun updateRoomSchedule(schedule: RoomSchedule?) {
        roomSchedule = schedule
    }

    /** Gužva između časova u ruti (Podešavanja). */
    var crowdRouting by mutableStateOf(AppSettings.crowdRouting(application))
        private set

    /** Menja i vreme polaska - obaveštenje se zakazuje iznova. */
    fun updateCrowdRouting(enabled: Boolean) {
        crowdRouting = enabled
        val app = getApplication<Application>()
        AppSettings.setCrowdRouting(app, enabled)
        viewModelScope.launch { DepartureScheduler.reschedule(app) }
    }

    /** Trenutni minut - gužva se menja sa vremenom, a ruta ne sme da se računa iznova pri svakom čitanju. */
    private var minute by mutableStateOf(LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES))

    // Posle deklaracije [minute]: viewModelScope (Main.immediate) izvrši prvi upis odmah, još u konstruktoru - u init bloku
    // iznad deklaracije polje je još null (pad pri pokretanju, 07.10.2026).
    init {
        viewModelScope.launch {
            while (true) {
                val now = LocalDateTime.now()
                minute = now.truncatedTo(ChronoUnit.MINUTES)
                delay(Duration.between(now, minute.plusMinutes(1)).toMillis().coerceAtLeast(1))
            }
        }
    }

    /** Gužva po zgradi u [at] (prazno ako je isključena ili raspored nije učitan). */
    private fun crowdAt(at: LocalDateTime): Map<String, Double> =
        roomSchedule?.takeIf { crowdRouting }?.let { crowdFactors(it, at) }.orEmpty()

    /** Gužva sada - za rutu na Mapi. */
    private val crowdNow: Map<String, Double> by derivedStateOf { crowdAt(minute) }

    /** Najveća gužva na ruti, u procentima sporijeg hoda (za baner); null - bez gužve vredne pomena. */
    val routeCrowdPercent: Int? by derivedStateOf {
        val buildings = route?.nodes?.map { it.buildingId }?.toSet().orEmpty()
        val extra = buildings.maxOfOrNull { crowdNow[it] ?: 1.0 }?.minus(1) ?: 0.0
        (extra * 100).roundToInt().takeIf { it >= MIN_CROWD_PERCENT }
    }

    /** Mesta za događaje - ista kao odredišta na Mapi; null dok se mapa učitava. */
    val placeOptions: PlaceOptions? by derivedStateOf {
        val campus = campus ?: return@derivedStateOf null
        val graph = graph ?: return@derivedStateOf null
        PlaceOptions(
            buildings = campus.named(BuildingCategory.FTN).mapNotNull { it.name },
            services = campus.named(BuildingCategory.SLUZBA).mapNotNull { it.name },
            rooms = graph.rooms.mapNotNull { it.name }.sorted(),
            roomBuildings = roomBuildingNames(graph, campus),
            roomNodes = graph.rooms.associateBy { it.name!! },
            favorites = favorites,
            recents = recents,
            onToggleFavorite = ::toggleFavorite,
        )
    }

    /** Gde je mesto (za prikaz uz salu na Početnoj): "Nastavni blok · 2. sprat", zgrada ili mesto van kampusa; null ako se ne zna. */
    fun locationOf(place: String): String? {
        offCampusPlaceOf(place)?.let { return it }
        val graph = graph ?: return null
        return placeLocationText(getApplication<Application>().resources, place, graph, campus ?: return null)
    }

    /**
     * Menja odredište. Sala u zgradi sa planom -> Mapa prelazi na njen sprat te zgrade; drugo -> kampus.
     */
    fun selectDestination(room: String?) {
        plan = null
        destinationNodeId = null
        destination = room
        if (room != null) {
            recents = recents.withRecent(room)
            AppSettings.setRecents(getApplication(), recents)
        }
        showPlace(target?.node ?: return)
    }

    /** Odredište je čvor [node] (najbliže mesto - i toalet bez naziva), u baneru [label]. */
    fun selectPlace(node: Node, label: String) {
        plan = null
        destinationNodeId = node.id
        destination = label
        showPlace(node)
    }

    /** Mapa prikazuje mesto čvora: plan njegove zgrade na njegovom spratu, ili kampus (napolju, zgrada bez plana). */
    fun showPlace(node: Node) {
        val building = indoorBuilding(node.buildingId)
        mode = MapMode.entries.first { it.building == building }
        if (building != null) showFloor(building, node.floor)
    }

    /** Sprat zgrade koja je na Mapi. */
    fun selectFloor(floor: Int) {
        mode.building?.let { showFloor(it, floor) }
    }

    fun selectMode(mode: MapMode) {
        this.mode = mode
    }

    /** Smer za prikaz (azimut iz [walkingDirection]), sa ispravkom iz rekalibracije, u odnosu na "gore" plana. */
    fun onHeading(azimuth: Float) {
        lastAzimuthDeg = azimuth
        state = state.copy(
            headingDeg = locator?.headingOnPlan(azimuth) ?: normalizeDeg(azimuth),
            phoneOffsetDeg = walkingDirection.offset.toFloat().takeIf { walkingDirection.isAnchored },
        )
    }

    /**
     * Pomera poziciju za jedan korak u smeru hoda (i ponavlja prethodne korake ako [step] traži). Slab korak (nizak [peak]
     * - sitni koraci pri zaustavljanju) je kraći od podešene dužine ([AccelStepDetector.stepLengthFactor]).
     */
    fun onStep(step: WalkingDirection.WalkStep, peak: Float) {
        val locator = locator ?: return
        if (locator.raw == null) return
        val before = locator.place
        // Kad se smer na stepeništu ne zna, pretpostavlja se ka spratu odredišta.
        locator.destination = target?.node?.let { PdrPlace(it.buildingId, it.floor) }
        val lengthM = stepLengthM * AccelStepDetector.stepLengthFactor(peak)
        val reason = locator.step(step.headingDeg, lengthM, step.redoSteps, SystemClock.elapsedRealtimeNanos())
        walkingDirection.nearStairs = locator.nearStairs
        state =state.copy(steps = state.steps + 1, distanceM = (state.distanceM + locator.lastStepM).coerceAtLeast(0f))
        updatePosition(before, reason)
        if (reason == PlaceReason.STEPENICE) onStairChange(locator)
    }

    /**
     * Prepoznata vožnja liftom: tačka na liftu izračunatog sprata (toast, kao na stepenicama), a kad broj spratova nije
     * siguran - pitanje na koji sprat, uz vibraciju.
     */
    private fun onLiftRide(ride: LiftRide) {
        val locator = locator ?: return
        val before = locator.place
        when (val outcome = locator.liftRide(ride) ?: return) {
            is LiftOutcome.Moved -> {
                updatePosition(before, PlaceReason.LIFT)
                val app = getApplication<Application>()
                val text = app.getString(
                    R.string.poc_lift_changed,
                    floorText(app.resources, outcome.change.fromFloor),
                    floorText(app.resources, outcome.change.toFloor),
                )
                Toast.makeText(app, text, Toast.LENGTH_LONG).show()
            }
            is LiftOutcome.Ask -> {
                recorder?.lift(SystemClock.elapsedRealtimeNanos(), outcome.prompt)
                state = state.copy(liftPrompt = outcome.prompt)
                getApplication<Application>().getSystemService(VibratorManager::class.java)
                    ?.defaultVibrator?.vibrate(VibrationEffect.createWaveform(LIFT_VIBRATION, -1))
            }
        }
    }

    /** Odgovor na pitanje za lift: pozicija na liftu na spratu [floor] (+ koraci posle izlaska); Mapa ide za njom. */
    fun selectLiftFloor(floor: Int) {
        val locator = locator ?: return
        val before = locator.place
        val reason = locator.selectLiftFloor(floor)
        state = state.copy(liftPrompt = null)
        updatePosition(before, reason)
    }

    /** "Nisam u liftu". */
    fun dismissLift() {
        locator?.dismissLift()
        if (state.liftPrompt != null) recorder?.lift(SystemClock.elapsedRealtimeNanos(), null)
        state = state.copy(liftPrompt = null)
    }

    /** Sprat promenjen na stepeništu: obaveštenje (i da sprat ispravi ako nije tačan) i čuvanje naučenog smera okreta. */
    private fun onStairChange(locator: PdrLocator) {
        val change = locator.lastStairChange ?: return
        val app = getApplication<Application>()
        AppSettings.setStairTurns(app, locator.learnedStairTurns)
        val text = app.getString(R.string.poc_stairs_changed, floorText(app.resources, change.fromFloor), floorText(app.resources, change.toFloor)) +
            if (change.guessed) "\n" + app.getString(R.string.poc_stairs_guessed) else ""
        Toast.makeText(app, text, Toast.LENGTH_LONG).show()
    }

    /**
     * Prepisuje poziciju iz [locator]-a u stanje. Kad pozicija pređe na drugo mesto (ulaz, prolaz, GPS),
     * Mapa ide za njom ako je prikazivala mesto sa koga je otišla.
     */
    private fun updatePosition(before: PdrPlace, reason: PlaceReason?) {
        val locator = locator ?: return
        val place = locator.place
        state = state.copy(
            pdrPlace = place,
            rawPosition = locator.raw,
            match = locator.match,
            headingErrorDeg = locator.headingErrorDeg,
        )
        if (reason == null) return
        recorder?.place(SystemClock.elapsedRealtimeNanos(), reason, place, locator.raw, locator.headingBiasDeg)
        if (place != before && isShowing(before)) show(place)
        lastAzimuthDeg?.let(::onHeading)
    }

    private fun isShowing(place: PdrPlace): Boolean {
        val building = mode.building ?: return place.isCampus
        return building.buildingId == place.buildingId && floorOf(building) == place.floor
    }

    private fun show(place: PdrPlace) {
        val building = place.building
        mode = MapMode.entries.first { it.building == building }
        if (building != null) showFloor(building, place.floor)
    }

    /**
     * "Ovde sam": sledeći dodir na prikazanoj mapi (plan sprata ili kampus) postavlja poziciju - i za vreme
     * praćenja. Sa kampusa Mapa prelazi na zgradu u kojoj je korisnik po GPS-u, ako ima plan.
     */
    fun togglePickStart() {
        state = state.copy(isPickingStart = !state.isPickingStart)
        if (state.isPickingStart && mode == MapMode.KAMPUS) {
            currentBuilding?.let { indoorBuilding(it.id) }?.let { building -> mode = MapMode.entries.first { it.building == building } }
        }
    }

    /**
     * Pozicija na prikazanoj mapi ([point] relativno na plan sprata ili mapu kampusa): start ili rekalibracija.
     * Posle hoda sa istog sprata javlja koliko je smer promašio (i ispravlja ga, ako je ispravka uključena).
     */
    fun setPosition(point: Offset) {
        val locator = locator ?: return
        val building = mode.building
        val place = if (building != null) PdrPlace(building.buildingId, floorOf(building)) else PdrPlace.CAMPUS
        val before = locator.place
        val check = locator.setPosition(place, point)
        // Ispravka sprata posle stepeništa je mogla da nauči smer okreta, a merenje smera zajedničku ispravku.
        AppSettings.setStairTurns(getApplication(), locator.learnedStairTurns)
        AppSettings.setHeadingErrors(getApplication(), locator.learnedHeadingErrors)
        // Označavanje sklanja i pitanje za lift (sprat je izabran na planu).
        state = state.copy(isPickingStart = false, liftPrompt = null)
        updatePosition(before, PlaceReason.RUCNO)
        if (check != null) {
            recorder?.headingCheck(SystemClock.elapsedRealtimeNanos(), place, check)
            val app = getApplication<Application>()
            Toast.makeText(app, headingCheckText(app, check), Toast.LENGTH_LONG).show()
        }
    }

    fun toggleSnapToGraph() {
        state = state.copy(snapToGraph = !state.snapToGraph)
    }

    /** Ispravka smera uključena/isključena; smer na ekranu se odmah preračunava. */
    fun toggleHeadingCorrection() {
        val apply = !state.applyHeadingCorrection
        locator?.applyCorrection = apply
        state = state.copy(applyHeadingCorrection = apply)
        lastAzimuthDeg?.let(::onHeading)
    }

    fun toggleTracking() {
        if (state.isTracking) stopTracking() else startTracking()
    }

    /** Mapa je na ekranu (RESUMED) ili nije - GPS radi dok je na ekranu. */
    fun onMapVisible(visible: Boolean) {
        mapVisible = visible
        updateGps()
    }

    /** Posle pitanja za dozvolu lokacije (odobrena ili ne). */
    fun onLocationPermissionResult() = updateGps()

    private fun updateGps() {
        if (mapVisible || state.isTracking) {
            gpsSession.start()
        } else {
            gpsSession.stop()
            gps = null
        }
    }

    private fun onGpsFix(fix: GpsFix) {
        gps = fix
        recorder?.gps(fix)
        val detector = buildingDetector ?: return
        if (detector.onFix(fix)) {
            currentBuilding = detector.current
            gpsRecorder?.building(fix.elapsedNs, detector.current?.id)
        }
        val locator = locator ?: return
        val before = locator.place
        updatePosition(before, locator.onGps(fix, state.isTracking))
    }

    /** Bez pozicije praćenje kreće sa GPS lokacije (napolju), na kampusu. */
    private fun startTracking() {
        val locator = locator ?: return
        if (state.rawPosition == null) {
            val fix = startGps ?: return
            val before = locator.place
            locator.startFromGps(fix)
            updatePosition(before, PlaceReason.RUCNO)
            mode = MapMode.KAMPUS
        }
        // Start se pritiska sa telefonom u ruci - odstupanje od pravca hoda se uči iznova.
        walkingDirection.reset()
        locator.clearRedo()
        val app = getApplication<Application>()
        if (BuildConfig.DEBUG) recorder = SensorRecorder.start(app.filesDir)
        // Gde je tačka (na grafu, ako je zalepljena) - replay odatle kreće.
        val shown = locator.match?.point?.let { Offset(it.x, it.y) } ?: locator.raw
        recorder?.place(SystemClock.elapsedRealtimeNanos(), PlaceReason.START, locator.place, shown, locator.headingBiasDeg)
        recorder?.stepLength(SystemClock.elapsedRealtimeNanos(), stepLengthM)
        session = PdrSensorSession(
            app.getSystemService(SensorManager::class.java),
            walkingDirection,
            trackSteps = true,
            onHeading = ::onHeading,
            onStep = ::onStep,
            recorder = recorder,
            lift = LiftRideDetector(),
            onLiftRide = ::onLiftRide,
        ).also { it.start() }
        PdrTrackingService.start(app)
        state = state.copy(isTracking = true)
        updateGps()
    }

    private fun stopTracking() {
        session?.stop()
        session = null
        // Bez praćenja nema koraka koje bi pitanje ponovilo.
        locator?.dismissLift()
        state = state.copy(liftPrompt = null)
        PdrTrackingService.stop(getApplication())
        recorder?.close()
        recorder = null
        // Smer na Mapi bez praćenja je pravac telefona: odstupanje naučeno u praćenju (npr. +180° - telefon je bio naopako u
        // džepu) ili smer zadržan dok se premešten telefon smiruje bi ostali do zatvaranja aplikacije (teren 09.10.2026:
        // "nekad kad napolju otvorim aplikaciju pokazuje skroz suprotan smer, ispravi se kad je zatvorim i ponovo otvorim").
        walkingDirection.reset()
        state = state.copy(isTracking = false)
        updateGps()
    }

    override fun onCleared() {
        stopTracking()
        gpsSession.stop()
    }

    // Smer se zadržava - dolazi sa senzora, nije deo sesije praćenja. Naučena ispravka smera ostaje (osobina telefona).
    fun reset() {
        stopTracking()
        walkingDirection.reset()
        locator?.reset()
        state = PocUiState(
            pdrPlace = state.pdrPlace,
            phoneOffsetDeg = 0f,
            snapToGraph = state.snapToGraph,
            applyHeadingCorrection = state.applyHeadingCorrection,
        )
        lastAzimuthDeg?.let(::onHeading)
    }
}
