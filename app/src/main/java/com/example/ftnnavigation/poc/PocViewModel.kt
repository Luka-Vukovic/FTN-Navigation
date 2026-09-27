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
import com.example.ftnnavigation.campus.resolveTarget
import com.example.ftnnavigation.campus.seedGraph
import com.example.ftnnavigation.graph.BuildingGraph
import com.example.ftnnavigation.graph.GraphDatabase
import com.example.ftnnavigation.graph.GraphRepository
import com.example.ftnnavigation.graph.PlaceholderGraph
import com.example.ftnnavigation.graph.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin

/**
 * Stanje PoC ekrana. Pozicija je relativna u odnosu na sliku sprata (0..1 po obe ose),
 * tako da ne zavisi od rezolucije plana ni od zuma.
 */
data class PocUiState(
    val position: Offset? = null,
    val headingDeg: Float = 0f,
    val steps: Int = 0,
    val stepLengthM: Float = 0.7f, // TODO: kalibrisati merenjem 20 m + brojanjem koraka
    val isTracking: Boolean = false,
    val isPickingStart: Boolean = false,
) {
    val distanceM: Float get() = steps * stepLengthM
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
     * Ruta do odredišta: od postavljene pozicije (najbliži čvor), inače od glavnog ulaza.
     * Ponovo se računa pri svakom koraku - graf je mali, A* traje ispod milisekunde.
     */
    val route: Route? by derivedStateOf {
        val graph = graph ?: return@derivedStateOf null
        val target = target ?: return@derivedStateOf null
        val position = state.position
        if (position != null) {
            graph.routeFrom(PlaceholderGraph.BUILDING_ID, 0, position.x, position.y, target.node.id)
        } else {
            graph.route(PlaceholderGraph.ENTRANCE_ID, target.node.id)
        }
    }

    init {
        viewModelScope.launch {
            val campus = withContext(Dispatchers.IO) {
                CampusData.parse(application.assets.open(CampusData.ASSET).bufferedReader().use { it.readText() })
            }
            this@PocViewModel.campus = campus
            val (nodes, edges) = seedGraph(campus)
            val repository = GraphRepository(GraphDatabase.get(application).graphDao())
            graph = repository.load(nodes, edges, campus.placements())
        }
    }

    /** Ruta od glavnog ulaza do sale, ili null ako se ne zna gde je sala (ili se graf učitava). */
    fun routeFromEntrance(room: String): Route? {
        val graph = graph ?: return null
        val target = resolveTarget(room, graph, campus ?: return null) ?: return null
        return graph.route(PlaceholderGraph.ENTRANCE_ID, target.node.id)
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

    /** Pomera poziciju za jedan korak u trenutnom smeru. */
    fun onStep() {
        val pos = state.position ?: return
        val planScale = graph?.placement(PlaceholderGraph.BUILDING_ID)?.scale ?: return
        val rad = Math.toRadians(state.headingDeg.toDouble())
        val next = Offset(
            (pos.x + state.stepLengthM * sin(rad).toFloat() / planScale.widthM).coerceIn(0f, 1f),
            (pos.y - state.stepLengthM * cos(rad).toFloat() / planScale.heightM).coerceIn(0f, 1f),
        )
        state = state.copy(position = next, steps = state.steps + 1)
    }

    /** Start se postavlja na planu zgrade, pa Mapa prelazi na njega. */
    fun togglePickStart() {
        state = state.copy(isPickingStart = !state.isPickingStart)
        if (state.isPickingStart) mode = MapMode.ZGRADA
    }

    fun setStart(point: Offset) {
        state = state.copy(position = point, isPickingStart = false)
    }

    fun toggleTracking() {
        state = state.copy(isTracking = !state.isTracking)
    }

    // Smer se zadržava - dolazi sa senzora, nije deo sesije praćenja.
    fun reset() {
        state = PocUiState(headingDeg = state.headingDeg)
    }
}
