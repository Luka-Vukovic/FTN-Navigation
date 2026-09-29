package com.example.ftnnavigation.poc

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
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
import com.example.ftnnavigation.campus.BuildingCategory
import com.example.ftnnavigation.campus.CampusData
import com.example.ftnnavigation.campus.RouteTarget
import com.example.ftnnavigation.graph.BuildingGraph
import com.example.ftnnavigation.graph.Node
import com.example.ftnnavigation.graph.NodeType
import com.example.ftnnavigation.graph.PlaceholderGraph
import com.example.ftnnavigation.graph.Route
import com.example.ftnnavigation.ui.components.FtnTopAppBar
import com.example.ftnnavigation.ui.theme.FTNNavigationTheme
import kotlin.math.roundToInt

/** Ekran Mapa: kači PDR senzore (akcelerometar za korake i osu hoda, TYPE_ROTATION_VECTOR za smer). */
@Composable
fun PocRoute(viewModel: PocViewModel = viewModel()) {
    val state = viewModel.state

    PdrSensorsEffect(
        trackSteps = state.isTracking,
        direction = viewModel.walkingDirection,
        onHeading = viewModel::onHeading,
        onStep = viewModel::onStep,
        recorder = viewModel.recorder,
    )
    // Senzori rade samo dok je Mapa aktivna - ekran se ne gasi dok traje praćenje (telefon u džepu).
    val view = LocalView.current
    DisposableEffect(view, state.isTracking) {
        view.keepScreenOn = state.isTracking
        onDispose { view.keepScreenOn = false }
    }

    PocScreen(
        state = state,
        campus = viewModel.campus,
        graph = viewModel.graph,
        mode = viewModel.mode,
        destination = viewModel.destination,
        target = viewModel.target,
        route = viewModel.route,
        onModeChange = viewModel::selectMode,
        onDestinationChange = viewModel::selectDestination,
        onPickStartToggle = viewModel::togglePickStart,
        onMapTap = viewModel::setStart,
        onTrackingToggle = viewModel::toggleTracking,
        onReset = viewModel::reset,
        onSnapToggle = viewModel::toggleSnapToGraph,
    )
}

@Composable
fun PocScreen(
    state: PocUiState,
    campus: CampusData?,
    graph: BuildingGraph?,
    mode: MapMode,
    destination: String?,
    target: RouteTarget?,
    route: Route?,
    onModeChange: (MapMode) -> Unit,
    onDestinationChange: (String?) -> Unit,
    onPickStartToggle: () -> Unit,
    onMapTap: (Offset) -> Unit,
    onTrackingToggle: () -> Unit,
    onReset: () -> Unit,
    onSnapToggle: () -> Unit,
) {
    var showDestinations by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            FtnTopAppBar(
                title = stringResource(R.string.poc_title),
                subtitle = stringResource(if (mode == MapMode.KAMPUS) R.string.map_campus_location else R.string.poc_location),
            )
        },
    ) { innerPadding ->
        // Samo gornji padding - donji panel sam rešava navigation bar da bi mu pozadina išla do ivice.
        Column(Modifier.padding(top = innerPadding.calculateTopPadding()).fillMaxSize()) {
            MapModeSelector(mode, onModeChange)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (mode == MapMode.KAMPUS && campus != null) {
                    CampusMap(
                        campus = campus,
                        graph = graph,
                        route = route,
                        position = state.position,
                        headingDeg = state.headingDeg,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    FloorPlan(
                        graph = graph,
                        route = route,
                        position = state.position,
                        rawPosition = state.rawPosition,
                        headingDeg = state.headingDeg,
                        isPickingStart = state.isPickingStart,
                        onTap = onMapTap,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // Start se postavlja samo na planu zgrade (PDR je za sada samo u Nastavnom bloku).
                val hint = when {
                    mode == MapMode.KAMPUS -> null
                    state.isPickingStart -> R.string.poc_hint_pick_start
                    state.position == null -> R.string.poc_hint_no_start
                    else -> null
                }
                Column(
                    Modifier.align(Alignment.TopCenter).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (destination != null) {
                        RouteBanner(
                            destination = destination,
                            target = target,
                            route = route,
                            fromPosition = state.position != null,
                            onClear = { onDestinationChange(null) },
                        )
                    }
                    if (hint != null) HintBanner(stringResource(hint))
                }
                if (mode == MapMode.ZGRADA) {
                    FilterChip(
                        selected = state.snapToGraph,
                        onClick = onSnapToggle,
                        label = { Text(stringResource(R.string.poc_snap_to_graph)) },
                        colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                    )
                }
            }
            ControlPanel(
                state = state,
                hasDestination = destination != null,
                onChooseDestination = { showDestinations = true },
                onPickStartToggle = onPickStartToggle,
                onTrackingToggle = onTrackingToggle,
                onReset = onReset,
            )
        }
    }
    if (showDestinations && graph != null && campus != null) {
        DestinationSheet(
            buildings = campus.named(BuildingCategory.FTN).mapNotNull { it.name },
            services = campus.named(BuildingCategory.SLUZBA).mapNotNull { it.name },
            rooms = graph.rooms.mapNotNull { it.name }.sorted(),
            selected = destination,
            onSelect = {
                onDestinationChange(it)
                showDestinations = false
            },
            onDismiss = { showDestinations = false },
        )
    }
}

/**
 * Slika sprata sa pan/zoom gestovima, grafom prizemlja, rutom i markerom korisnika. [position]
 * je pozicija na grafu; [rawPosition] (čist PDR, bez map-matching-a) je bleda tačka za poređenje.
 */
@Composable
private fun FloorPlan(
    graph: BuildingGraph?,
    route: Route?,
    position: Offset?,
    rawPosition: Offset?,
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
                    drawGraph(graph, PlaceholderGraph.BUILDING_ID, floor = 0, colors.primary, colors.secondary, textMeasurer, labelStyle, 1f / scale)
                }
            }
            if (route != null) {
                val colors = MaterialTheme.colorScheme
                Canvas(Modifier.fillMaxSize()) {
                    drawRoute(route, PlaceholderGraph.BUILDING_ID, position, colors.primary, colors.secondary, 1f / scale)
                }
            }
            if (position != null) {
                val color = MaterialTheme.colorScheme.primary
                Canvas(Modifier.fillMaxSize()) {
                    if (rawPosition != null && rawPosition != position) {
                        val raw = Offset(rawPosition.x * size.width, rawPosition.y * size.height)
                        drawCircle(color.copy(alpha = 0.35f), radius = 4.dp.toPx() / scale, center = raw)
                    }
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

/** Ivice i čvorovi jednog sprata zgrade; sale imaju natpis sa nazivom iz rasporeda. */
private fun DrawScope.drawGraph(
    graph: BuildingGraph,
    buildingId: String,
    floor: Int,
    edgeColor: Color,
    roomColor: Color,
    textMeasurer: TextMeasurer,
    labelStyle: TextStyle,
    k: Float,
) {
    fun Node.toOffset() = Offset(x * size.width, y * size.height)
    val nodes = graph.nodes.filter { it.buildingId == buildingId && it.floor == floor }.associateBy { it.id }
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
            NodeType.STEPENISTE, NodeType.LIFT, NodeType.ULAZ, NodeType.PROLAZ ->
                drawCircle(edgeColor, radius = 5.dp.toPx() * k, center = center)
            NodeType.STAZA, NodeType.ZGRADA -> Unit // ne postoje na planu zgrade
        }
    }
}

/**
 * Deo rute u prizemlju zgrade [buildingId] kao debela linija (od pozicije, ako je postavljena);
 * delovi van zgrade se preskaču. Odredište se obeležava ako je u zgradi.
 */
private fun DrawScope.drawRoute(route: Route, buildingId: String, position: Offset?, color: Color, targetColor: Color, k: Float) {
    fun Node.isHere() = this.buildingId == buildingId && floor == 0
    fun Node.toOffset() = Offset(x * size.width, y * size.height)
    val path = Path()
    var drawing = false
    if (position != null) {
        path.moveTo(position.x * size.width, position.y * size.height)
        drawing = true
    }
    for (node in route.nodes) {
        if (!node.isHere()) {
            drawing = false
            continue
        }
        val p = node.toOffset()
        if (drawing) path.lineTo(p.x, p.y) else path.moveTo(p.x, p.y)
        drawing = true
    }
    drawPath(path, color, style = Stroke(5.dp.toPx() * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
    val target = route.nodes.last()
    if (target.isHere()) drawTargetMarker(target.toOffset(), color, targetColor, k)
}

/** Odredište: prsten u boji rute sa tačkom u sredini. */
internal fun DrawScope.drawTargetMarker(center: Offset, color: Color, targetColor: Color, k: Float) {
    drawCircle(color, radius = 10.dp.toPx() * k, center = center)
    drawCircle(Color.White, radius = 7.dp.toPx() * k, center = center)
    drawCircle(targetColor, radius = 5.dp.toPx() * k, center = center)
}

/** Plava tačka sa konusom smera. 0° = gore na planu (poravnanje plana sa severom je TODO). */
internal fun DrawScope.drawUserMarker(center: Offset, headingDeg: Float, color: Color, k: Float) {
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

/**
 * Odredište i procena rute; ako sala nije na mapi, to piše umesto procene. Ispod piše zgrada
 * sale - ako sala nije ucrtana, ruta vodi samo do zgrade.
 */
@Composable
private fun RouteBanner(destination: String, target: RouteTarget?, route: Route?, fromPosition: Boolean, onClear: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        shadowElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(R.drawable.ic_place),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(destination, style = MaterialTheme.typography.titleMedium)
                val details = if (route == null) {
                    stringResource(R.string.route_not_on_map)
                } else {
                    val from = stringResource(if (fromPosition) R.string.route_from_position else R.string.route_from_entrance)
                    stringResource(R.string.route_summary, route.minutes, route.lengthM.toInt()) + " · " + from
                }
                Text(
                    details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val building = target?.building?.name
                if (route != null && building != null && building != destination) {
                    Text(
                        stringResource(if (target.approximate) R.string.route_to_building else R.string.route_in_building, building),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            IconButton(onClick = onClear) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.route_clear))
            }
        }
    }
}

/**
 * Izbor odredišta: zgrade FTN-a, studentske službe i sale Nastavnog bloka. Koristi ga i
 * izmena događaja (mesto događaja) - tada [noneLabel] dodaje stavku bez mesta ([onSelect] null).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DestinationSheet(
    buildings: List<String>,
    services: List<String>,
    rooms: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
    title: String = stringResource(R.string.route_destinations_title),
    noneLabel: String? = null,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp),
        )
        LazyColumn(Modifier.navigationBarsPadding()) {
            if (noneLabel != null) {
                item { DestinationItem(noneLabel, isSelected = selected == null, onClick = { onSelect(null) }) }
            }
            item { SheetSectionHeader(stringResource(R.string.route_destinations_buildings)) }
            items(buildings) { building ->
                DestinationItem(building, isSelected = building == selected, onClick = { onSelect(building) })
            }
            item { SheetSectionHeader(stringResource(R.string.route_destinations_services)) }
            items(services) { service ->
                DestinationItem(service, isSelected = service == selected, onClick = { onSelect(service) })
            }
            item { SheetSectionHeader(stringResource(R.string.route_destinations_rooms)) }
            items(rooms) { room ->
                DestinationItem(room, isSelected = room == selected, onClick = { onSelect(room) })
            }
        }
    }
}

@Composable
private fun SheetSectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 24.dp).padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun DestinationItem(name: String, isSelected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(name) },
        leadingContent = {
            Icon(
                painterResource(R.drawable.ic_place),
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        colors = ListItemDefaults.colors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        ),
        modifier = Modifier.padding(horizontal = 8.dp).clickable(onClick = onClick),
    )
}

/** Prekidač prikaza Mape: kampus (spolja) ili plan Nastavnog bloka. */
@Composable
private fun MapModeSelector(mode: MapMode, onModeChange: (MapMode) -> Unit) {
    val options = listOf(MapMode.KAMPUS to R.string.map_mode_campus, MapMode.ZGRADA to R.string.map_mode_building)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        options.forEachIndexed { index, (option, label) ->
            SegmentedButton(
                selected = mode == option,
                onClick = { onModeChange(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) {
                Text(stringResource(label))
            }
        }
    }
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
    hasDestination: Boolean,
    onChooseDestination: () -> Unit,
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
                    stringResource(R.string.poc_stat_phone_offset),
                    state.phoneOffsetDeg?.let { stringResource(R.string.poc_phone_offset_value, it.roundToInt()) }
                        ?: stringResource(R.string.poc_phone_offset_settling),
                )
                StatItem(
                    stringResource(R.string.poc_stat_distance),
                    stringResource(R.string.poc_distance_value, state.distanceM),
                )
            }
            OutlinedButton(onClick = onChooseDestination, modifier = Modifier.fillMaxWidth()) {
                Icon(painterResource(R.drawable.ic_place), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(
                        if (hasDestination) R.string.route_change_destination else R.string.route_choose_destination,
                    ),
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
                rawPosition = Offset(0.3f, 0.5f),
                headingDeg = 90f,
                steps = 42,
                isTracking = true,
            ),
            campus = null,
            graph = null,
            mode = MapMode.ZGRADA,
            destination = "NTP-307",
            target = null,
            route = null,
            onModeChange = {},
            onDestinationChange = {},
            onPickStartToggle = {},
            onMapTap = {},
            onTrackingToggle = {},
            onReset = {},
            onSnapToggle = {},
        )
    }
}
