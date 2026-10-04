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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Zum, pomeranje i rotacija mape/plana. Sadržaj je centriran u okviru ([zoomPanGestures] na okviru,
 * [zoomPanLayer] na sadržaju); graphicsLayer skalira i rotira oko centra sadržaja = centra okvira.
 *
 * - Zum je oko tačke između prstiju: tačka sadržaja pod prstima ostaje pod prstima.
 * - Sadržaj ne može da ode van okvira: ivica sadržaja većeg od okvira ne ulazi u okvir, a manji od
 *   okvira (npr. širok plan po visini) ostaje centriran. Za zarotiran sadržaj važi njegov obuhvatni pravougaonik.
 * - Rotacija ([rotate], auto-rotacija mape) ostavlja istu tačku sadržaja u centru okvira.
 */
@Stable
class ZoomPanState(private val maxScale: Float) {
    var scale by mutableFloatStateOf(1f)
        private set
    var pan by mutableStateOf(Offset.Zero)
        private set

    /** Rotacija sadržaja u smeru kazaljke (stepeni). */
    var rotationDeg by mutableFloatStateOf(0f)
        private set

    private var viewport = Size.Zero
    private var content = Size.Zero

    internal fun onViewport(size: IntSize) {
        viewport = size.toSize()
        pan = clamped(pan, scale)
    }

    internal fun onContent(size: IntSize) = resize(size.toSize())

    /** Sadržaj se pri rotaciji preraspoređuje (veći/manji) - ista tačka sadržaja ostaje u centru okvira. */
    private fun resize(new: Size) {
        if (content.width > 0f) pan *= new.width / content.width
        content = new
        pan = clamped(pan, scale)
    }

    /** [centroid] i [panChange] su u koordinatama okvira. */
    fun onGesture(centroid: Offset, panChange: Offset, zoomChange: Float) {
        val newScale = (scale * zoomChange).coerceIn(1f, maxScale)
        val z = newScale / scale
        // Tačka ekrana = c + R s (q - c) + pan (c = centar okvira); q pod prstima ostaje ista pri zumu z.
        val fromCenter = centroid - Offset(viewport.width / 2, viewport.height / 2)
        pan = clamped(pan * z + fromCenter * (1 - z) + panChange, newScale)
        scale = newScale
    }

    /**
     * Rotacija sadržaja na [deg]; pomeraj se okreće zajedno sa sadržajem (ista tačka ostaje u centru okvira).
     * Nova veličina sadržaja (kao u [zoomPanLayer]) se računa odmah - granice po starom rasporedu bi skratile pomeraj.
     */
    fun rotate(deg: Float) {
        pan = pan.rotated(deg - rotationDeg)
        rotationDeg = deg
        if (content.width > 0f && content.height > 0f && viewport.width > 0f && viewport.height > 0f) {
            resize(rotatedFitSize(viewport, content.width / content.height, deg))
        } else {
            pan = clamped(pan, scale)
        }
    }

    private fun clamped(p: Offset, s: Float): Offset {
        val bounds = rotatedBounds(content, rotationDeg)
        val maxX = max(0f, (bounds.width * s - viewport.width) / 2)
        val maxY = max(0f, (bounds.height * s - viewport.height) / 2)
        return Offset(p.x.coerceIn(-maxX, maxX), p.y.coerceIn(-maxY, maxY))
    }
}

/** Tačka zarotirana za [deg] u smeru kazaljke oko (0, 0) (y nadole, kao na ekranu i u graphicsLayer-u). */
internal fun Offset.rotated(deg: Float): Offset {
    val r = Math.toRadians(deg.toDouble())
    val c = cos(r).toFloat()
    val s = sin(r).toFloat()
    return Offset(x * c - y * s, x * s + y * c)
}

/** Obuhvatni pravougaonik [size] zarotiranog za [deg]. */
internal fun rotatedBounds(size: Size, deg: Float): Size {
    val r = Math.toRadians(deg.toDouble())
    val c = abs(cos(r)).toFloat()
    val s = abs(sin(r)).toFloat()
    return Size(size.width * c + size.height * s, size.width * s + size.height * c)
}

/** Najveći sadržaj odnosa [aspect] (širina / visina) koji zarotiran za [deg] staje u [frame]. */
internal fun rotatedFitSize(frame: Size, aspect: Float, deg: Float): Size {
    val unit = rotatedBounds(Size(aspect, 1f), deg)
    val height = min(frame.width / unit.width, frame.height / unit.height)
    return Size(aspect * height, height)
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

/**
 * Na sadržaju (centriranom u okviru): veličina odnosa [aspect] koja i zarotirana staje u okvir (za 90° širok plan
 * postaje visok i zauzima visinu ekrana), pa zum, pomeraj i rotacija.
 */
fun Modifier.zoomPanLayer(state: ZoomPanState, aspect: Float): Modifier = this
    .layout { measurable, constraints ->
        // Rotacija se čita u fazi rasporeda - menja raspored bez ponovnog sastavljanja.
        val frame = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        val fit = rotatedFitSize(frame, aspect, state.rotationDeg)
        val width = fit.width.roundToInt().coerceAtLeast(1)
        val height = fit.height.roundToInt().coerceAtLeast(1)
        val placeable = measurable.measure(Constraints.fixed(width, height))
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeable.place((constraints.maxWidth - width) / 2, (constraints.maxHeight - height) / 2)
        }
    }
    .onSizeChanged(state::onContent)
    .graphicsLayer {
        scaleX = state.scale
        scaleY = state.scale
        rotationZ = state.rotationDeg
        translationX = state.pan.x
        translationY = state.pan.y
    }
