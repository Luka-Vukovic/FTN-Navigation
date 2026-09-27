package com.example.ftnnavigation.poc

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ftnnavigation.campus.CampusData
import com.example.ftnnavigation.campus.CampusPoint
import com.example.ftnnavigation.graph.BuildingGraph
import com.example.ftnnavigation.graph.NodeType
import com.example.ftnnavigation.graph.PlaceholderGraph
import com.example.ftnnavigation.graph.PointM
import com.example.ftnnavigation.graph.Route

// Širine u metrima - rastu sa zumom, kao na pravoj mapi.
private const val STREET_WIDTH_M = 6f
private const val PATH_WIDTH_M = 1.5f

/**
 * Spoljna mapa kampusa (OpenStreetMap): okolne zgrade, ulice i staze za orijentaciju, FTN
 * zgrade sa natpisima, spojni prolazi, ulazi i ruta. [position] je PDR pozicija relativno na
 * plan Nastavnog bloka - preslikava se u kampus preko smeštaja plana.
 */
@Composable
fun CampusMap(
    campus: CampusData,
    graph: BuildingGraph?,
    route: Route?,
    position: Offset?,
    headingDeg: Float,
    modifier: Modifier = Modifier,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val colors = MaterialTheme.colorScheme
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelMedium.copy(
        color = colors.onPrimaryContainer,
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
                },
        ) {
            // Nepromenljiv deo mape: putanje se prave jednom (zavise samo od veličine).
            Spacer(
                Modifier
                    .fillMaxSize()
                    .drawWithCache {
                        val m = size.width / campus.widthM // px po metru
                        fun CampusPoint.toPx() = Offset(this[0] * m, this[1] * m)
                        fun polyline(points: List<CampusPoint>, close: Boolean) = Path().apply {
                            points.forEachIndexed { i, p ->
                                val o = p.toPx()
                                if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y)
                            }
                            if (close) close()
                        }
                        val context = campus.context.map { polyline(it, close = true) }
                        val streets = campus.streets.map { polyline(it, close = false) }
                        val paths = campus.paths.map { polyline(it, close = false) }
                        val buildings = campus.buildings.map { polyline(it.outline, close = true) }
                        val streetStroke = Stroke(STREET_WIDTH_M * m, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        val pathStroke = Stroke(PATH_WIDTH_M * m, cap = StrokeCap.Round, join = StrokeJoin.Round)

                        onDrawBehind {
                            val k = 1f / scale // za veličine koje ostaju iste na ekranu pri zumiranju
                            context.forEach { drawPath(it, colors.surfaceDim) }
                            streets.forEach { drawPath(it, Color.White, style = streetStroke) }
                            paths.forEach { drawPath(it, colors.outlineVariant, style = pathStroke) }
                            buildings.forEach {
                                drawPath(it, colors.primaryContainer)
                                drawPath(it, colors.primary, style = Stroke(1.5.dp.toPx() * k))
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
                val style = labelStyle.copy(fontSize = (11.sp.toPx() * k).toSp())
                // Boja i stil obruba se zadaju pri crtanju: measure() kešira raspored i ne
                // razlikuje stilove koji menjaju samo crtanje.
                val halo = Stroke(2.5.dp.toPx() * k, join = StrokeJoin.Round)
                for (building in campus.namedBuildings) {
                    val (x, y) = building.labelAt ?: continue
                    val label = textMeasurer.measure(building.label ?: building.name.orEmpty(), style)
                    val topLeft = Offset(x * m - label.size.width / 2f, y * m - label.size.height / 2f)
                    drawText(label, color = Color.White, topLeft = topLeft, drawStyle = halo)
                    drawText(label, topLeft = topLeft, drawStyle = Fill)
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
