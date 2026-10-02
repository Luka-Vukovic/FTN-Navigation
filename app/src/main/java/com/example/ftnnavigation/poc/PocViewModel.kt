package com.example.ftnnavigation.poc

import android.app.Application
import android.hardware.GeomagneticField
import android.hardware.SensorManager
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ftnnavigation.BuildConfig
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
import com.example.ftnnavigation.campus.offCampusPlaceOf
import com.example.ftnnavigation.campus.resolveTarget
import com.example.ftnnavigation.campus.routeBetween
import com.example.ftnnavigation.departure.Departure
import com.example.ftnnavigation.departure.departureFor
import com.example.ftnnavigation.events.PlaceOptions
import com.example.ftnnavigation.graph.BuildingGraph
import com.example.ftnnavigation.graph.MapMatcher
import com.example.ftnnavigation.graph.MatchedPosition
import com.example.ftnnavigation.graph.AmfPlan
import com.example.ftnnavigation.graph.IndoorBuilding
import com.example.ftnnavigation.graph.FPlan
import com.example.ftnnavigation.graph.KulaPlan
import com.example.ftnnavigation.graph.NbPlan
import com.example.ftnnavigation.graph.NodeType
import com.example.ftnnavigation.graph.NtpPlan
import com.example.ftnnavigation.graph.indoorBuilding
import com.example.ftnnavigation.graph.PlanPlacement
import com.example.ftnnavigation.graph.Route
import com.example.ftnnavigation.schedule.AgendaItem
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/**
 * Stanje PoC ekrana. Pozicije su relativne u odnosu na sliku sprata [pdrFloor] zgrade
 * [pdrBuilding] (0..1 po obe ose), tako da ne zavise od rezolucije plana ni od zuma.
 */
data class PocUiState(
    /** Plan na kome je PDR: zgrada i sprat na kome je postavljen start (sprat se ne menja u hodu). */
    val pdrBuilding: IndoorBuilding = NbPlan,
    val pdrFloor: Int = 0,
    /** Čist PDR (koraci + smer, bez ispravki) - za poređenje sa [match]. */
    val rawPosition: Offset? = null,
    /** PDR pozicija zalepljena za graf; null dok se graf učitava. */
    val match: MatchedPosition? = null,
    /** Smer u odnosu na "gore" plana [pdrBuilding] (0 = gore). */
    val headingDeg: Float = 0f,
    /** Odstupanje telefona od pravca hoda (-180..180); null dok se premešten telefon smiruje. */
    val phoneOffsetDeg: Float? = 0f,
    val steps: Int = 0,
    // Teren 02.10.2026: hodnik III sprata NTP-a (38,3 m) = 46 i 48 detektovanih koraka (01.10. 50) -> ~0,8 m.
    // Korak korisnika (visok); drugima će biti predug - kandidat za podešavanje.
    val stepLengthM: Float = 0.8f,
    val isTracking: Boolean = false,
    val isPickingStart: Boolean = false,
    /** Prikaz i ruta sa grafa (map-matching); isključeno = čist PDR (provera smera hoda). */
    val snapToGraph: Boolean = true,
) {
    val distanceM: Float get() = steps * stepLengthM

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
}

/**
 * Izbori na prekidaču Mape. Kad se po GPS-u zna zgrada u kojoj je korisnik ([here]), samo kampus,
 * plan te zgrade i ono što je prikazano ([mode]); ostale zgrade su u meniju. Napolju, ili dok se
 * ne zna, sve.
 */
internal fun shownModes(mode: MapMode, here: CampusBuilding?): List<MapMode> =
    if (here == null) {
        MapMode.entries
    } else {
        MapMode.entries.filter { it == MapMode.KAMPUS || it == mode || it.building?.buildingId == here.id }
    }

/** Odakle kreće ruta: PDR pozicija (NB), GPS lokacija (kampus) ili glavni ulaz NB-a. */
enum class RouteStart { PDR, GPS, ENTRANCE }

/** GPS lokacija lošija od ovoga se ne koristi za rutu (u zgradi luta desetinama metara). */
private const val MAX_ROUTE_ACCURACY_M = 50f

/** GPS ruta kreće sa staze ili sa ulaza, ne iz unutrašnjosti zgrade bez plana ili spojnog prolaza. */
private val GPS_START_TYPES = setOf(NodeType.STAZA, NodeType.ULAZ)

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

    /** Magnetski azimut pravca "gore" na planu, po zgradi; prazno dok se kampus učitava. */
    private var planUpAzimuths = mapOf<String, Float>()

    /** Magnetski azimut "gore" plana na kome je PDR (0 dok se kampus učitava). */
    private val planUpAzimuthDeg: Float get() = planUpAzimuths[state.pdrBuilding.buildingId] ?: 0f

    /** Poslednji azimut sa senzora - da se smer preračuna kad se PDR premesti na drugi plan. */
    private var lastAzimuthDeg: Float? = null

    /** Map-matching PDR pozicije na graf sprata [PocUiState.pdrFloor] zgrade [PocUiState.pdrBuilding] (kad se graf učita). */
    private var matcher: MapMatcher? = null

    var mode by mutableStateOf(MapMode.NB)
        private set

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

    /** Gde vodi ruta do [destination]; null ako se ne zna gde je (ili se graf učitava). */
    val target: RouteTarget? by derivedStateOf {
        val graph = graph ?: return@derivedStateOf null
        val campus = campus ?: return@derivedStateOf null
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

    /** Odakle kreće [route]: PDR pozicija ima prednost (tačnija je u zgradi), pa GPS, pa glavni ulaz NB-a. */
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
     * lokaciji - graf je mali, A* traje ispod milisekunde.
     */
    val route: Route? by derivedStateOf {
        val graph = graph ?: return@derivedStateOf null
        val campus = campus ?: return@derivedStateOf null
        val target = target ?: return@derivedStateOf null
        val match = state.shownMatch
        val raw = state.rawPosition
        val gps = routeGps
        when {
            match != null -> graph.routeFrom(match.point, target.node.id)
            raw != null -> graph.routeFrom(state.pdrBuilding.buildingId, state.pdrFloor, raw.x, raw.y, target.node.id)
            gps != null -> graph.routeFrom(
                CAMPUS_ID, 0,
                (gps.point.x / campus.widthM).toFloat(), (gps.point.y / campus.heightM).toFloat(),
                target.node.id,
                startTypes = GPS_START_TYPES,
            )
            else -> graph.route(NbPlan.ENTRANCE_ID, target.node.id)
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
            planUpAzimuths = campus.plans.mapValues { planUpMagneticAzimuthDeg(it.value.toPlacement(), declination) }
            lastAzimuthDeg?.let(::onHeading)
            val graph = loadGraph(application, campus)
            this@PocViewModel.graph = graph
            matcher = MapMatcher(graph, state.pdrBuilding.buildingId, state.pdrFloor)
            // Start postavljen dok se graf učitavao.
            state.rawPosition?.let { state = state.copy(match = matcher?.start(it.x, it.y)) }
        }
    }

    /**
     * Polazak kao u obaveštenju: sa mesta prethodne stavke istog dana ([day]), inače od glavnog
     * ulaza. Null = prethodna stavka je na istom mestu. Dok se graf učitava, ruta je null.
     */
    fun departureFor(item: AgendaItem, day: List<AgendaItem>): Departure? {
        val graph = graph
        val campus = campus
        return departureFor(item, day, route = { from, to ->
            if (graph != null && campus != null) routeBetween(graph, campus, from, to) else null
        })
    }

    /** Mesta za događaje - ista kao odredišta na Mapi; null dok se mapa učitava. */
    val placeOptions: PlaceOptions? by derivedStateOf {
        val campus = campus ?: return@derivedStateOf null
        val graph = graph ?: return@derivedStateOf null
        PlaceOptions(
            buildings = campus.named(BuildingCategory.FTN).mapNotNull { it.name },
            services = campus.named(BuildingCategory.SLUZBA).mapNotNull { it.name },
            rooms = graph.rooms.mapNotNull { it.name }.sorted(),
        )
    }

    /** Zgrada sale (za prikaz uz salu) ili mesto van kampusa, ili null ako se ne zna. */
    fun buildingNameOf(room: String): String? {
        offCampusPlaceOf(room)?.let { return it }
        val graph = graph ?: return null
        return resolveTarget(room, graph, campus ?: return null)?.building?.name
    }

    /**
     * Menja odredište. Sala u zgradi sa planom -> Mapa prelazi na njen sprat te zgrade; drugo -> kampus.
     */
    fun selectDestination(room: String?) {
        destination = room
        val node = target?.node ?: return
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

    /** Smer za prikaz (azimut iz [walkingDirection]). */
    fun onHeading(azimuth: Float) {
        lastAzimuthDeg = azimuth
        state = state.copy(
            headingDeg = normalizeDeg(azimuth - planUpAzimuthDeg),
            phoneOffsetDeg = walkingDirection.offset.toFloat().takeIf { walkingDirection.isAnchored },
        )
    }

    /** Pozicija pre koraka - za ponavljanje koraka kad se ispravi smer (okret samo telefona). */
    private class StepStart(val raw: Offset, val match: MatchedPosition?)

    private val stepStarts = ArrayDeque<StepStart>()

    /**
     * Pomera poziciju za jedan korak u smeru hoda; ako [step] traži, prethodni koraci se
     * ponavljaju u tom smeru (od pozicije pre njih). Pozicija na grafu prati korak.
     */
    fun onStep(step: WalkingDirection.WalkStep) {
        var raw = state.rawPosition ?: return
        val planScale = graph?.placement(state.pdrBuilding.buildingId)?.scale ?: return
        var match = state.match
        val redo = step.redoSteps.coerceAtMost(stepStarts.size)
        if (redo > 0) {
            val from = stepStarts[stepStarts.size - redo]
            repeat(redo) { stepStarts.removeLast() }
            raw = from.raw
            match = from.match
        }

        val rad = Math.toRadians((step.headingDeg - planUpAzimuthDeg).toDouble())
        val dxM = state.stepLengthM * sin(rad)
        val dyM = -state.stepLengthM * cos(rad)
        repeat(redo + 1) {
            stepStarts.addLast(StepStart(raw, match))
            if (stepStarts.size > WalkingDirection.MAX_TURN_STEPS) stepStarts.removeFirst()
            raw = Offset(
                (raw.x + dxM / planScale.widthM).toFloat().coerceIn(0f, 1f),
                (raw.y + dyM / planScale.heightM).toFloat().coerceIn(0f, 1f),
            )
            match = match?.let { matcher?.step(it, dxM, dyM) } ?: matcher?.start(raw.x, raw.y)
        }
        state = state.copy(rawPosition = raw, match = match, steps = state.steps + 1)
    }

    /**
     * Start se postavlja na prikazanom spratu zgrade sa planom. Sa kampusa Mapa prelazi na zgradu u
     * kojoj je korisnik po GPS-u (ako ima plan), inače na zgradu prethodnog starta.
     */
    fun togglePickStart() {
        state = state.copy(isPickingStart = !state.isPickingStart)
        if (state.isPickingStart && mode.building == null) {
            val building = currentBuilding?.let { indoorBuilding(it.id) } ?: state.pdrBuilding
            mode = MapMode.entries.first { it.building == building }
        }
    }

    /** Start na prikazanom planu: PDR od sada radi na tom spratu te zgrade. */
    fun setStart(point: Offset) {
        val building = mode.building ?: return
        val floor = floorOf(building)
        stepStarts.clear()
        if (building != state.pdrBuilding || floor != state.pdrFloor) {
            matcher = graph?.let { MapMatcher(it, building.buildingId, floor) }
        }
        state = state.copy(
            pdrBuilding = building,
            pdrFloor = floor,
            rawPosition = point,
            match = matcher?.start(point.x, point.y),
            isPickingStart = false,
        )
        // Smer u odnosu na "gore" novog plana.
        lastAzimuthDeg?.let(::onHeading)
    }

    fun toggleSnapToGraph() {
        state = state.copy(snapToGraph = !state.snapToGraph)
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
    }

    private fun startTracking() {
        // Start se pritiska sa telefonom u ruci - odstupanje od pravca hoda se uči iznova.
        walkingDirection.reset()
        stepStarts.clear()
        val app = getApplication<Application>()
        if (BuildConfig.DEBUG) recorder = SensorRecorder.start(app.filesDir)
        session = PdrSensorSession(
            app.getSystemService(SensorManager::class.java),
            walkingDirection,
            trackSteps = true,
            onHeading = ::onHeading,
            onStep = ::onStep,
            recorder = recorder,
        ).also { it.start() }
        PdrTrackingService.start(app)
        state = state.copy(isTracking = true)
        updateGps()
    }

    private fun stopTracking() {
        session?.stop()
        session = null
        PdrTrackingService.stop(getApplication())
        recorder?.close()
        recorder = null
        state = state.copy(isTracking = false)
        updateGps()
    }

    override fun onCleared() {
        stopTracking()
        gpsSession.stop()
    }

    // Smer se zadržava - dolazi sa senzora, nije deo sesije praćenja.
    fun reset() {
        stopTracking()
        walkingDirection.reset()
        stepStarts.clear()
        state = PocUiState(
            pdrBuilding = state.pdrBuilding,
            pdrFloor = state.pdrFloor,
            headingDeg = state.headingDeg,
            phoneOffsetDeg = 0f,
            snapToGraph = state.snapToGraph,
        )
    }
}
