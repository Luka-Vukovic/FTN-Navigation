package com.example.ftnnavigation.poc

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import com.example.ftnnavigation.campus.BuildingCategory
import com.example.ftnnavigation.campus.CampusBuilding
import com.example.ftnnavigation.campus.CampusData
import com.example.ftnnavigation.campus.CampusPoint
import com.example.ftnnavigation.campus.LabelSide
import com.example.ftnnavigation.graph.BuildingGraph
import com.example.ftnnavigation.graph.NodeType
import com.example.ftnnavigation.graph.PlaceholderGraph
import com.example.ftnnavigation.graph.PointM
import com.example.ftnnavigation.graph.Route
import com.example.ftnnavigation.ui.theme.OnService
import com.example.ftnnavigation.ui.theme.ServiceFill
import com.example.ftnnavigation.ui.theme.ServiceOutline

// Širine u metrima - rastu sa zumom, kao na pravoj mapi.
private const val STREET_WIDTH_M = 6f
private const val PATH_WIDTH_M = 1.5f

/** Koliko oko natpisa se još računa kao držanje natpisa (natpisi su sitni). */
private val LABEL_TOUCH_SLOP = 8.dp

/** Natpis zgrade izmeren i smešten u px mape (pre zuma), za crtanje i za držanje prstom. */
private data class PlacedLabel(val building: CampusBuilding, val layout: TextLayoutResult, val topLeft: Offset)

/** [m] = px po metru, [k] = 1 / zum (natpisi ostaju iste veličine na ekranu). */
private fun Density.placeLabels(
    campus: CampusData,
    textMeasurer: TextMeasurer,
    baseStyle: TextStyle,
    m: Float,
    k: Float,
): List<PlacedLabel> {
    val style = baseStyle.copy(fontSize = (11.sp.toPx() * k).toSp())
    val gap = 6.dp.toPx() * k // natpis sa strane ne počinje baš na tački
    return campus.namedBuildings.mapNotNull { building ->
        val (x, y) = building.labelAt ?: return@mapNotNull null
        val label = textMeasurer.measure(building.label ?: building.name.orEmpty(), style)
        val left = when (building.labelSide) {
            LabelSide.CENTER -> x * m - label.size.width / 2f
            LabelSide.EAST -> x * m + gap
            LabelSide.WEST -> x * m - gap - label.size.width
        }
        PlacedLabel(building, label, Offset(left, y * m - label.size.height / 2f))
    }
}

/**
 * Spoljna mapa kampusa (OpenStreetMap): okolne zgrade, ulice i staze za orijentaciju, FTN
 * zgrade i studentske službe (toplim tonom) sa natpisima, spojni prolazi, ulazi i ruta. [position] je PDR pozicija relativno na
 * plan Nastavnog bloka - preslikava se u kampus preko smeštaja plana.
 */
@Composable
fun CampusMap(
    campus: CampusData,
    graph: BuildingGraph?,
    route: Route?,
    position: Offset?,
    headingDeg: Float,
    onBuildingLongPress: (CampusBuilding) -> Unit,
    modifier: Modifier = Modifier,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val colors = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val currentOnBuildingLongPress by rememberUpdatedState(onBuildingLongPress)
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelMedium.copy(
        fontWeight = FontWeight.SemiBold,
        lineHeight = TextUnit.Unspecified,
    )
    val entrances = remember(campus) { campus.nodes.filter { it.type == NodeType.ULAZ } }

    Box(
        modifier = modifier
            .background(colors.surfaceContainer)
            .clipToBounds()
            .pointerInput(Unit) {
                detectTransformGestures { _, panChange, zoomChange, _ ->
                    scale = (scale * zoomChange).coerceIn(1f, 8f)
                    pan += panChange
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .aspectRatio(campus.widthM / campus.heightM)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = pan.x
                    translationY = pan.y
                }
                // Posle graphicsLayer: dodir stiže u koordinatama mape (zum i pomeraj već skinuti).
                // Pomeranje mape troši događaje, pa prekida i držanje.
                .pointerInput(campus) {
                    detectTapGestures(
                        onLongPress = { at ->
                            val k = 1f / scale
                            val m = size.width / campus.widthM
                            val slop = LABEL_TOUCH_SLOP.toPx() * k
                            val hit = placeLabels(campus, textMeasurer, labelStyle, m, k)
                                .filter { Rect(it.topLeft, it.layout.size.toSize()).inflate(slop).contains(at) }
                                .minByOrNull { (it.topLeft + it.layout.size.toSize().center - at).getDistanceSquared() }
                            if (hit != null) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                currentOnBuildingLongPress(hit.building)
                            }
                        },
                    )
                },
        ) {
            // Nepromenljiv deo mape: putanje se prave jednom (zavise samo od veličine).
            Spacer(
                Modifier
                    .fillMaxSize()
                    .drawWithCache {
                        val m = size.width / campus.widthM // px po metru
                        fun CampusPoint.toPx() = Offset(this[0] * m, this[1] * m)
                        fun Path.addLine(points: List<CampusPoint>, close: Boolean) {
                            points.forEachIndexed { i, p ->
                                val o = p.toPx()
                                if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y)
                            }
                            if (close) close()
                        }
                        fun polyline(points: List<CampusPoint>) = Path().apply { addLine(points, close = false) }
                        // Dvorišta su rupe: EvenOdd ne boji deo unutar unutrašnjeg prstena.
                        fun polygon(rings: List<List<CampusPoint>>) = Path().apply {
                            fillType = PathFillType.EvenOdd
                            rings.forEach { addLine(it, close = true) }
                        }
                        val context = campus.context.map(::polygon)
                        val streets = campus.streets.map(::polyline)
                        val paths = campus.paths.map(::polyline)
                        val buildings = campus.buildings.filter { it.outline.isNotEmpty() }
                            .map { polygon(listOf(it.outline) + it.holes) to it.category }
                        // Službe koje su samo deo zgrade: tačka na mestu službe.
                        val servicePoints = campus.buildings.filter { it.outline.isEmpty() }.mapNotNull { it.labelAt?.toPx() }
                        val streetStroke = Stroke(STREET_WIDTH_M * m, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        val pathStroke = Stroke(PATH_WIDTH_M * m, cap = StrokeCap.Round, join = StrokeJoin.Round)

                        onDrawBehind {
                            val k = 1f / scale // za veličine koje ostaju iste na ekranu pri zumiranju
                            context.forEach { drawPath(it, colors.surfaceDim) }
                            streets.forEach { drawPath(it, Color.White, style = streetStroke) }
                            paths.forEach { drawPath(it, colors.outlineVariant, style = pathStroke) }
                            for ((path, category) in buildings) {
                                val (fill, outline) = when (category) {
                                    BuildingCategory.FTN -> colors.primaryContainer to colors.primary
                                    BuildingCategory.SLUZBA -> ServiceFill to ServiceOutline
                                }
                                drawPath(path, fill)
                                drawPath(path, outline, style = Stroke(1.5.dp.toPx() * k))
                            }
                            for (center in servicePoints) {
                                drawCircle(Color.White, radius = 4.dp.toPx() * k, center = center)
                                drawCircle(ServiceOutline, radius = 2.5.dp.toPx() * k, center = center)
                            }
                            for (entrance in entrances) {
                                val center = Offset(entrance.x * m, entrance.y * m)
                                drawCircle(Color.White, radius = 4.dp.toPx() * k, center = center)
                                drawCircle(colors.primary, radius = 2.5.dp.toPx() * k, center = center)
                            }
                        }
                    },
            )
            if (graph != null && (route != null || position != null)) {
                Canvas(Modifier.fillMaxSize()) {
                    val k = 1f / scale
                    val m = size.width / campus.widthM
                    fun PointM.toPx() = Offset(x.toFloat() * m, y.toFloat() * m)
                    val nbPlacement = graph.placement(PlaceholderGraph.BUILDING_ID)
                    if (route != null) {
                        val start = position?.let { nbPlacement.toMeters(it.x, it.y).toPx() }
                        val points = listOfNotNull(start) + route.nodes.map { graph.position(it).toPx() }
                        val path = Path()
                        points.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
                        drawPath(path, colors.primary, style = Stroke(5.dp.toPx() * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        drawTargetMarker(points.last(), colors.primary, colors.secondary, k)
                    }
                    if (position != null) {
                        val center = nbPlacement.toMeters(position.x, position.y).toPx()
                        // Smer je u odnosu na "gore" plana; plan je u kampusu zarotiran.
                        drawUserMarker(center, headingDeg + nbPlacement.rotationDeg.toFloat(), colors.primary, k)
                    }
                }
            }
            // Natpisi zgrada su iznad svega (i rute), iste veličine na ekranu, sa belim obrubom
            // da se čitaju i preko zidova, staza i rute.
            Canvas(Modifier.fillMaxSize()) {
                val k = 1f / scale
                val m = size.width / campus.widthM
                // Boja i stil obruba se zadaju pri crtanju: measure() kešira raspored i ne
                // razlikuje stilove koji menjaju samo crtanje.
                val halo = Stroke(2.5.dp.toPx() * k, join = StrokeJoin.Round)
                for ((building, label, topLeft) in placeLabels(campus, textMeasurer, labelStyle, m, k)) {
                    val color = if (building.category == BuildingCategory.SLUZBA) OnService else colors.onPrimaryContainer
                    drawText(label, color = Color.White, topLeft = topLeft, drawStyle = halo)
                    drawText(label, color = color, topLeft = topLeft, drawStyle = Fill)
                }
            }
        }
        Surface(
            color = colors.surface.copy(alpha = 0.8f),
            shape = MaterialTheme.shapes.extraSmall,
            modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
        ) {
            Text(
                campus.attribution,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}
