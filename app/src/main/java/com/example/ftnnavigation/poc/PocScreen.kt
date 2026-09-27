package com.example.ftnnavigation.poc

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ftnnavigation.R
import com.example.ftnnavigation.graph.BuildingGraph
import com.example.ftnnavigation.graph.Node
import com.example.ftnnavigation.graph.NodeType
import com.example.ftnnavigation.ui.components.FtnTopAppBar
import com.example.ftnnavigation.ui.theme.FTNNavigationTheme

/** Ekran Mapa: drži ViewModel i kači PDR senzore (akcelerometar za korake, TYPE_ROTATION_VECTOR za smer). */
@Composable
fun PocRoute(viewModel: PocViewModel = viewModel()) {
    val state = viewModel.state

    PdrSensorsEffect(
        trackSteps = state.isTracking,
        onAzimuth = viewModel::onAzimuth,
        onStep = viewModel::onStep,
    )

    PocScreen(
        state = state,
        graph = viewModel.graph,
        onPickStartToggle = viewModel::togglePickStart,
        onMapTap = viewModel::setStart,
        onTrackingToggle = viewModel::toggleTracking,
        onReset = viewModel::reset,
    )
}

@Composable
fun PocScreen(
    state: PocUiState,
    graph: BuildingGraph?,
    onPickStartToggle: () -> Unit,
    onMapTap: (Offset) -> Unit,
    onTrackingToggle: () -> Unit,
    onReset: () -> Unit,
) {
    Scaffold(
        topBar = {
            FtnTopAppBar(
                title = stringResource(R.string.poc_title),
                subtitle = stringResource(R.string.poc_location),
            )
        },
    ) { innerPadding ->
        // Samo gornji padding - donji panel sam rešava navigation bar da bi mu pozadina išla do ivice.
        Column(Modifier.padding(top = innerPadding.calculateTopPadding()).fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                FloorPlan(
                    graph = graph,
                    position = state.position,
                    headingDeg = state.headingDeg,
                    isPickingStart = state.isPickingStart,
                    onTap = onMapTap,
                    modifier = Modifier.fillMaxSize(),
                )
                val hint = when {
                    state.isPickingStart -> R.string.poc_hint_pick_start
                    state.position == null -> R.string.poc_hint_no_start
                    else -> null
                }
                if (hint != null) {
                    HintBanner(
                        text = stringResource(hint),
                        modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
                    )
                }
            }
            ControlPanel(
                state = state,
                onPickStartToggle = onPickStartToggle,
                onTrackingToggle = onTrackingToggle,
                onReset = onReset,
            )
        }
    }
}

/** Slika sprata sa pan/zoom gestovima, grafom prizemlja i markerom korisnika. */
@Composable
private fun FloorPlan(
    graph: BuildingGraph?,
    position: Offset?,
    headingDeg: Float,
    isPickingStart: Boolean,
    onTap: (Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val painter = painterResource(R.drawable.floor_plan_placeholder)
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .clipToBounds()
            .pointerInput(Unit) {
                detectTransformGestures { _, panChange, zoomChange, _ ->
                    scale = (scale * zoomChange).coerceIn(1f, 6f)
                    pan += panChange
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // Marker i tap koordinate su u koordinatama slike jer su unutar graphicsLayer-a.
        Box(
            Modifier
                .aspectRatio(painter.intrinsicSize.width / painter.intrinsicSize.height)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = pan.x
                    translationY = pan.y
                }
                .pointerInput(isPickingStart, onTap) {
                    if (!isPickingStart) return@pointerInput
                    detectTapGestures { tap ->
                        onTap(Offset(tap.x / size.width, tap.y / size.height))
                    }
                },
        ) {
            Image(
                painter = painter,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
            if (graph != null) {
                val colors = MaterialTheme.colorScheme
                val textMeasurer = rememberTextMeasurer()
                val labelStyle = MaterialTheme.typography.labelSmall.copy(color = colors.onSurface, lineHeight = TextUnit.Unspecified)
                Canvas(Modifier.fillMaxSize()) {
                    drawGraph(graph, floor = 0, colors.primary, colors.secondary, textMeasurer, labelStyle, 1f / scale)
                }
            }
            if (position != null) {
                val color = MaterialTheme.colorScheme.primary
                Canvas(Modifier.fillMaxSize()) {
                    val center = Offset(position.x * size.width, position.y * size.height)
                    // Delimo sa scale da bi marker ostao iste veličine na ekranu pri zumiranju.
                    drawUserMarker(center, headingDeg, color, 1f / scale)
                }
            }
        }
    }
}

/** Visina natpisa sale kao deo visine plana. */
private const val LABEL_HEIGHT = 0.03f

/** Ivice i čvorovi jednog sprata; sale imaju natpis sa nazivom iz rasporeda. */
private fun DrawScope.drawGraph(
    graph: BuildingGraph,
    floor: Int,
    edgeColor: Color,
    roomColor: Color,
    textMeasurer: TextMeasurer,
    labelStyle: TextStyle,
    k: Float,
) {
    fun Node.toOffset() = Offset(x * size.width, y * size.height)
    val nodes = graph.nodes.filter { it.floor == floor }.associateBy { it.id }
    for (edge in graph.edges) {
        val a = nodes[edge.fromId] ?: continue
        val b = nodes[edge.toId] ?: continue
        drawLine(edgeColor.copy(alpha = 0.35f), a.toOffset(), b.toOffset(), strokeWidth = 2.dp.toPx() * k)
    }
    for (node in nodes.values) {
        val center = node.toOffset()
        when (node.type) {
            NodeType.HODNIK, NodeType.VRATA -> drawCircle(edgeColor.copy(alpha = 0.5f), radius = 3.dp.toPx() * k, center = center)
            NodeType.PROSTORIJA -> {
                drawCircle(roomColor, radius = 5.dp.toPx() * k, center = center)
                // Natpis je deo plana (raste sa zumom, kao tekst na pravom planu) da bi stao u sobu.
                val style = labelStyle.copy(fontSize = (size.height * LABEL_HEIGHT).toSp())
                val label = textMeasurer.measure(node.name.orEmpty(), style)
                // Natpis na strani sobe dalje od hodnika, da ga ivica ka hodniku ne preseca.
                val gap = size.height * LABEL_HEIGHT / 2
                val dy = if (node.y < 0.5f) -gap - label.size.height else gap
                drawText(label, topLeft = center + Offset(-label.size.width / 2f, dy))
            }
            NodeType.STEPENISTE, NodeType.LIFT, NodeType.ULAZ ->
                drawCircle(edgeColor, radius = 5.dp.toPx() * k, center = center)
        }
    }
}

/** Plava tačka sa konusom smera. 0° = gore na planu (poravnanje plana sa severom je TODO). */
private fun DrawScope.drawUserMarker(center: Offset, headingDeg: Float, color: Color, k: Float) {
    val coneRadius = 36.dp.toPx() * k
    drawArc(
        color = color.copy(alpha = 0.25f),
        startAngle = headingDeg - 90f - 30f,
        sweepAngle = 60f,
        useCenter = true,
        topLeft = center - Offset(coneRadius, coneRadius),
        size = Size(coneRadius * 2, coneRadius * 2),
    )
    drawCircle(Color.White, radius = 10.dp.toPx() * k, center = center)
    drawCircle(color, radius = 7.dp.toPx() * k, center = center)
    drawCircle(
        color.copy(alpha = 0.4f),
        radius = 10.dp.toPx() * k,
        center = center,
        style = Stroke(1.dp.toPx() * k),
    )
}

@Composable
private fun HintBanner(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun ControlPanel(
    state: PocUiState,
    onPickStartToggle: () -> Unit,
    onTrackingToggle: () -> Unit,
    onReset: () -> Unit,
) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatItem(stringResource(R.string.poc_stat_steps), state.steps.toString())
                StatItem(
                    stringResource(R.string.poc_stat_heading),
                    stringResource(R.string.poc_heading_value, state.headingDeg.toInt()),
                )
                StatItem(
                    stringResource(R.string.poc_stat_distance),
                    stringResource(R.string.poc_distance_value, state.distanceM),
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onPickStartToggle,
                    enabled = !state.isTracking,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        stringResource(
                            if (state.isPickingStart) R.string.poc_cancel else R.string.poc_set_start,
                        ),
                    )
                }
                Button(
                    onClick = onTrackingToggle,
                    enabled = state.position != null && !state.isPickingStart,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(if (state.isTracking) R.string.poc_stop else R.string.poc_start))
                }
                TextButton(onClick = onReset) {
                    Text(stringResource(R.string.poc_reset))
                }
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall)
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PocScreenPreview() {
    FTNNavigationTheme {
        PocScreen(
            state = PocUiState(
                position = Offset(0.3f, 0.5f),
                headingDeg = 90f,
                steps = 42,
                isTracking = true,
            ),
            graph = null,
            onPickStartToggle = {},
            onMapTap = {},
            onTrackingToggle = {},
            onReset = {},
        )
    }
}
