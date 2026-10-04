package com.example.ftnnavigation.poc

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Preko granice od 45° između dve orijentacije treba preći još ovoliko, da se mapa ne okreće tamo-amo kad je smer
 * blizu dijagonale (okret tek kad je smer > 65° od trenutnog "gore").
 */
internal const val ROTATION_HYSTERESIS_DEG = 20f

/** Nova orijentacija mora da važi ovoliko pre okreta mape - kratak pogled u stranu je ne okreće. */
private const val ROTATION_HOLD_MS = 1000L

private const val ROTATION_ANIMATION_MS = 500

/**
 * Orijentacija mape (0..3, mapa zarotirana za −90° × orijentacija) za smer [headingDeg] u odnosu na "gore" mape:
 * najbliža smeru, ali trenutna ([current]) ostaje dok smer ne ode [ROTATION_HYSTERESIS_DEG] preko polovine.
 * Bez trenutne (null) - najbliža.
 */
internal fun mapQuarterFor(current: Int?, headingDeg: Float): Int {
    if (current != null && abs(angleDiffDeg(headingDeg, current * 90f)) <= 45f + ROTATION_HYSTERESIS_DEG) return current
    return Math.floorMod((normalizeDeg(headingDeg) / 90f).roundToInt(), 4)
}

/**
 * Auto-rotacija mape: [state] se okreće za po 90° tako da smer korisnika ([headingDeg], u odnosu na "gore" mape)
 * bude što bliže gore na ekranu. Nova orijentacija posle [ROTATION_HOLD_MS] (uz histerezu [mapQuarterFor]), sa
 * mekim prelazom. Kad se mapa otvori, odmah u najbližoj orijentaciji; isključeno ([enabled] false) -> nazad na 0°.
 */
@Composable
internal fun AutoRotateEffect(state: ZoomPanState, enabled: Boolean, headingDeg: Float) {
    val heading by rememberUpdatedState(headingDeg)
    var quarter by remember(state) { mutableIntStateOf(0) }
    LaunchedEffect(state, enabled) {
        if (!enabled) {
            quarter = 0
            rotateTo(state, 0f)
            return@LaunchedEffect
        }
        quarter = mapQuarterFor(null, heading)
        state.rotate(-90f * quarter)
        snapshotFlow { mapQuarterFor(quarter, heading) }
            .distinctUntilChanged()
            .collectLatest { next ->
                if (next == quarter) return@collectLatest
                // Smer se vrati pre isteka -> collectLatest prekida čekanje.
                delay(ROTATION_HOLD_MS)
                quarter = next
                rotateTo(state, -90f * next)
            }
    }
}

/** Meki prelaz na [targetDeg] kraćim putem (iz 270° u 0° preko 360°, ne unazad za 270°). */
private suspend fun rotateTo(state: ZoomPanState, targetDeg: Float) {
    val from = state.rotationDeg
    val to = from + angleDiffDeg(targetDeg, from)
    if (abs(to - from) < 0.01f) return
    animate(from, to, animationSpec = tween(ROTATION_ANIMATION_MS, easing = FastOutSlowInEasing)) { value, _ ->
        state.rotate(value)
    }
}
