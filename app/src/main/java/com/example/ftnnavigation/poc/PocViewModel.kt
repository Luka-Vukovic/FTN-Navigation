package com.example.ftnnavigation.poc

import android.app.Application
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ftnnavigation.campus.CampusData
import com.example.ftnnavigation.campus.RouteTarget
import com.example.ftnnavigation.campus.loadCampus
import com.example.ftnnavigation.campus.loadGraph
import com.example.ftnnavigation.campus.resolveTarget
import com.example.ftnnavigation.campus.routeBetween
import com.example.ftnnavigation.graph.BuildingGraph
import com.example.ftnnavigation.graph.MapMatcher
import com.example.ftnnavigation.graph.MatchedPosition
import com.example.ftnnavigation.graph.PlaceholderGraph
import com.example.ftnnavigation.graph.Route
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
    val steps: Int = 0,
    val stepLengthM: Float = 0.7f, // TODO: kalibrisati merenjem 20 m + brojanjem koraka
    val isTracking: Boolean = false,
    val isPickingStart: Boolean = false,
) {
    val distanceM: Float get() = steps * stepLengthM

    /** Pozicija za prikaz i rutu: sa grafa, a dok ga nema čist PDR. */
    val position: Offset? get() = match?.point?.let { Offset(it.x, it.y) } ?: rawPosition
}

/** Šta Mapa prikazuje: spoljnu mapu kampusa ili plan prizemlja Nastavnog bloka. */
enum class MapMode { KAMPUS, ZGRADA }

// Azimut (od severa) pravca koji je "gore" na planu. TODO: izmeriti kompasom u hodniku.
private const val PLAN_UP_AZIMUTH_DEG = 0f

/**
 * Stanje mape i rute. Vezan za aktivnost: preživljava promenu taba, a Početna preko njega
 * nudi rutu do sale sledećeg časa. Senzori ostaju vezani za ekran Mape (PdrSensorsEffect):
 * dok je drugi tab otvoren, koraci se ne broje.
 */
class PocViewModel(application: Application) : AndroidViewModel(application) {
    var state by mutableStateOf(PocUiState())
        private set

    /** Mapa kampusa iz assets-a (null dok se učitava). */
    var campus by mutableStateOf<CampusData?>(null)
        private set

    /** Graf kampusa i zgrada (null dok se učitava iz baze). Zgrade su za sada samo prizemlje. */
    var graph by mutableStateOf<BuildingGraph?>(null)
        private set

    /** Map-matching PDR pozicije na graf prizemlja Nastavnog bloka (kad se graf učita). */
    private var matcher: MapMatcher? = null

    var mode by mutableStateOf(MapMode.ZGRADA)
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

    /**
     * Ruta do odredišta: od pozicije na grafu (kroz bolji kraj njene ivice), inače od glavnog
     * ulaza. Ponovo se računa pri svakom koraku - graf je mali, A* traje ispod milisekunde.
     */
    val route: Route? by derivedStateOf {
        val graph = graph ?: return@derivedStateOf null
        val target = target ?: return@derivedStateOf null
        val match = state.match
        val raw = state.rawPosition
        when {
            match != null -> graph.routeFrom(match.point, target.node.id)
            raw != null -> graph.routeFrom(PlaceholderGraph.BUILDING_ID, 0, raw.x, raw.y, target.node.id)
            else -> graph.route(PlaceholderGraph.ENTRANCE_ID, target.node.id)
        }
    }

    init {
        viewModelScope.launch {
            val campus = loadCampus(application)
            this@PocViewModel.campus = campus
            val graph = loadGraph(application, campus)
            matcher = MapMatcher(graph, PlaceholderGraph.BUILDING_ID, floor = 0)
            this@PocViewModel.graph = graph
            // Start postavljen dok se graf učitavao.
            state.rawPosition?.let { state = state.copy(match = matcher?.start(it.x, it.y)) }
        }
    }

    /** Ruta od glavnog ulaza do sale, ili null ako se ne zna gde je sala (ili se graf učitava). */
    fun routeFromEntrance(room: String): Route? {
        return routeBetween(graph ?: return null, campus ?: return null, fromRoom = null, toRoom = room)
    }

    /** Zgrada sale (za prikaz uz salu), ili null ako se ne zna. */
    fun buildingNameOf(room: String): String? {
        val graph = graph ?: return null
        return resolveTarget(room, graph, campus ?: return null)?.building?.name
    }

    /** Menja odredište; ako je van Nastavnog bloka, Mapa prelazi na kampus. */
    fun selectDestination(room: String?) {
        destination = room
        val building = target?.node?.buildingId ?: return
        if (building != PlaceholderGraph.BUILDING_ID) mode = MapMode.KAMPUS
    }

    fun selectMode(mode: MapMode) {
        this.mode = mode
    }

    fun onAzimuth(azimuth: Float) {
        state = state.copy(headingDeg = normalizeDeg(azimuth - PLAN_UP_AZIMUTH_DEG))
    }

    /** Pomera poziciju za jedan korak u trenutnom smeru; pozicija na grafu prati korak. */
    fun onStep() {
        val raw = state.rawPosition ?: return
        val planScale = graph?.placement(PlaceholderGraph.BUILDING_ID)?.scale ?: return
        val rad = Math.toRadians(state.headingDeg.toDouble())
        val dxM = state.stepLengthM * sin(rad)
        val dyM = -state.stepLengthM * cos(rad)
        val nextRaw = Offset(
            (raw.x + dxM / planScale.widthM).toFloat().coerceIn(0f, 1f),
            (raw.y + dyM / planScale.heightM).toFloat().coerceIn(0f, 1f),
        )
        val match = state.match?.let { matcher?.step(it, dxM, dyM) } ?: matcher?.start(nextRaw.x, nextRaw.y)
        state = state.copy(rawPosition = nextRaw, match = match, steps = state.steps + 1)
    }

    /** Start se postavlja na planu zgrade, pa Mapa prelazi na njega. */
    fun togglePickStart() {
        state = state.copy(isPickingStart = !state.isPickingStart)
        if (state.isPickingStart) mode = MapMode.ZGRADA
    }

    fun setStart(point: Offset) {
        state = state.copy(rawPosition = point, match = matcher?.start(point.x, point.y), isPickingStart = false)
    }

    fun toggleTracking() {
        state = state.copy(isTracking = !state.isTracking)
    }

    // Smer se zadržava - dolazi sa senzora, nije deo sesije praćenja.
    fun reset() {
        state = PocUiState(headingDeg = state.headingDeg)
    }
}
