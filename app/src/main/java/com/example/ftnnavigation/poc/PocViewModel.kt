package com.example.ftnnavigation.poc

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ftnnavigation.R
import com.example.ftnnavigation.graph.BuildingGraph
import com.example.ftnnavigation.graph.FloorScale
import com.example.ftnnavigation.graph.GraphDatabase
import com.example.ftnnavigation.graph.GraphRepository
import com.example.ftnnavigation.graph.PlaceholderGraph
import kotlinx.coroutines.launch
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

// Širina plana (x 305..1055 px fotografije evakuacionog plana). Procena: ~0,1 m/px (kancelarija
// u gornjem redu ~3,5 m). TODO: zameniti izmerenom vrednošću nakon kalibracije razmere plana.
private const val PLAN_WIDTH_M = 75f

// Azimut (od severa) pravca koji je "gore" na planu. TODO: izmeriti kompasom u hodniku.
private const val PLAN_UP_AZIMUTH_DEG = 0f

/**
 * Drži stanje mape preko promene taba (back stack entry čuva ViewModel dok je tab sačuvan).
 * Senzori ostaju vezani za ekran (PdrSensorsEffect): dok je drugi tab otvoren, koraci se ne broje.
 */
class PocViewModel(application: Application) : AndroidViewModel(application) {
    var state by mutableStateOf(PocUiState())
        private set

    /** Graf zgrade (null dok se učitava iz baze); svi spratovi dele placeholder plan. */
    var graph by mutableStateOf<BuildingGraph?>(null)
        private set

    // Visina plana u metrima sledi iz odnosa stranica slike.
    private val planScale = application.getDrawable(R.drawable.floor_plan_placeholder)!!.let {
        FloorScale(PLAN_WIDTH_M, PLAN_WIDTH_M * it.intrinsicHeight / it.intrinsicWidth)
    }

    init {
        viewModelScope.launch {
            val repository = GraphRepository(GraphDatabase.get(application).graphDao())
            graph = repository.loadBuilding(PlaceholderGraph.BUILDING_ID, planScale)
        }
    }

    fun onAzimuth(azimuth: Float) {
        state = state.copy(headingDeg = normalizeDeg(azimuth - PLAN_UP_AZIMUTH_DEG))
    }

    /** Pomera poziciju za jedan korak u trenutnom smeru. */
    fun onStep() {
        val pos = state.position ?: return
        val rad = Math.toRadians(state.headingDeg.toDouble())
        val next = Offset(
            (pos.x + state.stepLengthM * sin(rad).toFloat() / planScale.widthM).coerceIn(0f, 1f),
            (pos.y - state.stepLengthM * cos(rad).toFloat() / planScale.heightM).coerceIn(0f, 1f),
        )
        state = state.copy(position = next, steps = state.steps + 1)
    }

    fun togglePickStart() {
        state = state.copy(isPickingStart = !state.isPickingStart)
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
