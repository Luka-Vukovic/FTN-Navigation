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
import com.example.ftnnavigation.campus.CampusData
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
import com.example.ftnnavigation.graph.NbPlan
import com.example.ftnnavigation.graph.NtpPlan
import com.example.ftnnavigation.graph.PlanPlacement
import com.example.ftnnavigation.graph.Route
import com.example.ftnnavigation.schedule.AgendaItem
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/**
 * Stanje PoC ekrana. Pozicije su relativne u odnosu na sliku sprata (0..1 po obe ose),
 * tako da ne zavise od rezolucije plana ni od zuma.
 */
data class PocUiState(
    /** Čist PDR (koraci + smer, bez ispravki) - za poređenje sa [match]. */
    val rawPosition: Offset? = null,
    /** PDR pozicija zalepljena za graf; null dok se graf učitava. */
    val match: MatchedPosition? = null,
    val headingDeg: Float = 0f,
    /** Odstupanje telefona od pravca hoda (-180..180); null dok se premešten telefon smiruje. */
    val phoneOffsetDeg: Float? = 0f,
    val steps: Int = 0,
    val stepLengthM: Float = 0.7f, // TODO: kalibrisati merenjem 20 m + brojanjem koraka
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

/** Šta Mapa prikazuje: spoljnu mapu kampusa, sprat Nastavnog bloka ili sprat NTP-a. */
enum class MapMode { KAMPUS, NB, NTP }

// Gde je kampus - za magnetsku deklinaciju (ista tačka kao projekcija u build_campus.py).
private const val CAMPUS_LAT = 45.2455f
private const val CAMPUS_LON = 19.85f
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

    /** Graf kampusa i zgrada (null dok se učitava iz baze). NB ima 7 nivoa (-1 ... 5), NTP 6. */
    var graph by mutableStateOf<BuildingGraph?>(null)
        private set

    /** Magnetski azimut pravca "gore" na planu NB; 0 dok se kampus učitava. */
    private var planUpAzimuthDeg = 0f

    /** Map-matching PDR pozicije na graf prizemlja Nastavnog bloka (kad se graf učita). */
    private var matcher: MapMatcher? = null

    var mode by mutableStateOf(MapMode.NB)
        private set

    /** Sprat Nastavnog bloka koji Mapa prikazuje (0 = prizemlje, -1 = suteren). PDR je samo u prizemlju. */
    var nbFloor by mutableStateOf(0)
        private set

    /** Sprat NTP-a koji Mapa prikazuje (0 = prizemlje). */
    var ntpFloor by mutableStateOf(0)
        private set

    /** Smer hoda iz senzora (ne pravac telefona); na Start se pretpostavlja telefon u ruci. */
    val walkingDirection = WalkingDirection()

    /** Snimak senzora od Start do Stop (samo debug build) - vidi [SensorRecorder]. */
    private var recorder: SensorRecorder? = null

    /** Senzori za vreme praćenja (od Start do Stop). */
    private var session: PdrSensorSession? = null

    /** Naziv sale (kao u rasporedu) ili zgrade; čuva se i dok se graf još učitava. */
    var destination by mutableStateOf<String?>(null)
        private set

    /** Gde vodi ruta do [destination]; null ako se ne zna gde je (ili se graf učitava). */
    val target: RouteTarget? by derivedStateOf {
        val graph = graph ?: return@derivedStateOf null
        val campus = campus ?: return@derivedStateOf null
        destination?.let { resolveTarget(it, graph, campus) }
    }

    /**
     * Ruta do odredišta: od pozicije na grafu (kroz bolji kraj njene ivice), inače od glavnog
     * ulaza. Ponovo se računa pri svakom koraku - graf je mali, A* traje ispod milisekunde.
     */
    val route: Route? by derivedStateOf {
        val graph = graph ?: return@derivedStateOf null
        val target = target ?: return@derivedStateOf null
        val match = state.shownMatch
        val raw = state.rawPosition
        when {
            match != null -> graph.routeFrom(match.point, target.node.id)
            raw != null -> graph.routeFrom(NbPlan.BUILDING_ID, 0, raw.x, raw.y, target.node.id)
            else -> graph.route(NbPlan.ENTRANCE_ID, target.node.id)
        }
    }

    init {
        viewModelScope.launch {
            val campus = loadCampus(application)
            this@PocViewModel.campus = campus
            campus.placements()[NbPlan.BUILDING_ID]?.let {
                val declination = GeomagneticField(CAMPUS_LAT, CAMPUS_LON, CAMPUS_ALT_M, System.currentTimeMillis()).declination
                planUpAzimuthDeg = planUpMagneticAzimuthDeg(it, declination)
            }
            val graph = loadGraph(application, campus)
            matcher = MapMatcher(graph, NbPlan.BUILDING_ID, floor = 0)
            this@PocViewModel.graph = graph
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
     * Menja odredište. Sala u Nastavnom bloku ili NTP-u -> Mapa prelazi na njen sprat te zgrade; drugo ->
     * kampus.
     */
    fun selectDestination(room: String?) {
        destination = room
        val node = target?.node ?: return
        when (node.buildingId) {
            NbPlan.BUILDING_ID -> {
                mode = MapMode.NB
                nbFloor = node.floor
            }
            NtpPlan.BUILDING_ID -> {
                mode = MapMode.NTP
                ntpFloor = node.floor
            }
            else -> mode = MapMode.KAMPUS
        }
    }

    /** Sprat zgrade koja je na Mapi. */
    fun selectFloor(floor: Int) {
        when (mode) {
            MapMode.NB -> nbFloor = floor
            MapMode.NTP -> ntpFloor = floor
            MapMode.KAMPUS -> Unit
        }
    }

    fun selectMode(mode: MapMode) {
        this.mode = mode
    }

    /** Smer za prikaz (azimut iz [walkingDirection]). */
    fun onHeading(azimuth: Float) {
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
        val planScale = graph?.placement(NbPlan.BUILDING_ID)?.scale ?: return
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

    /** Start se postavlja na planu prizemlja Nastavnog bloka, pa Mapa prelazi na njega. */
    fun togglePickStart() {
        state = state.copy(isPickingStart = !state.isPickingStart)
        if (state.isPickingStart) {
            mode = MapMode.NB
            nbFloor = 0
        }
    }

    fun setStart(point: Offset) {
        stepStarts.clear()
        state = state.copy(rawPosition = point, match = matcher?.start(point.x, point.y), isPickingStart = false)
    }

    fun toggleSnapToGraph() {
        state = state.copy(snapToGraph = !state.snapToGraph)
    }

    fun toggleTracking() {
        if (state.isTracking) stopTracking() else startTracking()
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
    }

    private fun stopTracking() {
        session?.stop()
        session = null
        PdrTrackingService.stop(getApplication())
        recorder?.close()
        recorder = null
        state = state.copy(isTracking = false)
    }

    override fun onCleared() = stopTracking()

    // Smer se zadržava - dolazi sa senzora, nije deo sesije praćenja.
    fun reset() {
        stopTracking()
        walkingDirection.reset()
        stepStarts.clear()
        state = PocUiState(headingDeg = state.headingDeg, phoneOffsetDeg = 0f, snapToGraph = state.snapToGraph)
    }
}
