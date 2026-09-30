package com.example.ftnnavigation.poc

import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import kotlin.math.max

/**
 * Zum i pomeranje mape/plana. Sadržaj je centriran u okviru ([zoomPanGestures] na okviru,
 * [zoomPanLayer] na sadržaju); graphicsLayer skalira oko centra sadržaja = centra okvira.
 *
 * - Zum je oko tačke između prstiju: tačka sadržaja pod prstima ostaje pod prstima.
 * - Sadržaj ne može da ode van okvira: ivica sadržaja većeg od okvira ne ulazi u okvir, a manji od
 *   okvira (npr. širok plan po visini) ostaje centriran.
 */
@Stable
class ZoomPanState(private val maxScale: Float) {
    var scale by mutableFloatStateOf(1f)
        private set
    var pan by mutableStateOf(Offset.Zero)
        private set

    private var viewport = Size.Zero
    private var content = Size.Zero

    internal fun onViewport(size: IntSize) {
        viewport = size.toSize()
        pan = clamped(pan, scale)
    }

    internal fun onContent(size: IntSize) {
        content = size.toSize()
        pan = clamped(pan, scale)
    }

    /** [centroid] i [panChange] su u koordinatama okvira. */
    fun onGesture(centroid: Offset, panChange: Offset, zoomChange: Float) {
        val newScale = (scale * zoomChange).coerceIn(1f, maxScale)
        val z = newScale / scale
        // Tačka ekrana = c + s * (q - c) + pan (c = centar okvira); q pod prstima ostaje ista pri zumu z.
        val fromCenter = centroid - Offset(viewport.width / 2, viewport.height / 2)
        pan = clamped(pan * z + fromCenter * (1 - z) + panChange, newScale)
        scale = newScale
    }

    private fun clamped(p: Offset, s: Float): Offset {
        val maxX = max(0f, (content.width * s - viewport.width) / 2)
        val maxY = max(0f, (content.height * s - viewport.height) / 2)
        return Offset(p.x.coerceIn(-maxX, maxX), p.y.coerceIn(-maxY, maxY))
    }
}

/** Stanje zuma; novo (vraćeno na početak) kad se promeni neki od [keys] (npr. druga zgrada). */
@Composable
fun rememberZoomPanState(maxScale: Float, vararg keys: Any?): ZoomPanState = remember(*keys) { ZoomPanState(maxScale) }

/** Na okviru: gestovi zuma i pomeranja. */
fun Modifier.zoomPanGestures(state: ZoomPanState): Modifier = this
    .onSizeChanged(state::onViewport)
    .pointerInput(state) {
        detectTransformGestures { centroid, panChange, zoomChange, _ -> state.onGesture(centroid, panChange, zoomChange) }
    }

/** Na sadržaju (centriranom u okviru): primenjuje zum i pomeraj. */
fun Modifier.zoomPanLayer(state: ZoomPanState): Modifier = this
    .onSizeChanged(state::onContent)
    .graphicsLayer {
        scaleX = state.scale
        scaleY = state.scale
        translationX = state.pan.x
        translationY = state.pan.y
    }
