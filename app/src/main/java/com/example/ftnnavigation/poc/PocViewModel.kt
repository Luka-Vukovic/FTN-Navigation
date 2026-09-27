package com.example.ftnnavigation.poc

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
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

// TODO: zameniti izmerenom vrednošću nakon kalibracije razmere plana (px -> m).
internal const val PLAN_WIDTH_M = 60f

// Azimut (od severa) pravca koji je "gore" na planu. TODO: izmeriti kompasom u hodniku.
private const val PLAN_UP_AZIMUTH_DEG = 0f

/**
 * Drži stanje mape preko promene taba (back stack entry čuva ViewModel dok je tab sačuvan).
 * Senzori ostaju vezani za ekran (PdrSensorsEffect): dok je drugi tab otvoren, koraci se ne broje.
 */
class PocViewModel : ViewModel() {
    var state by mutableStateOf(PocUiState())
        private set

    fun onAzimuth(azimuth: Float) {
        state = state.copy(headingDeg = normalizeDeg(azimuth - PLAN_UP_AZIMUTH_DEG))
    }

    /** Pomera poziciju za jedan korak u trenutnom smeru. */
    fun onStep(planHeightM: Float) {
        val pos = state.position ?: return
        val rad = Math.toRadians(state.headingDeg.toDouble())
        val next = Offset(
            (pos.x + state.stepLengthM * sin(rad).toFloat() / PLAN_WIDTH_M).coerceIn(0f, 1f),
            (pos.y - state.stepLengthM * cos(rad).toFloat() / planHeightM).coerceIn(0f, 1f),
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
