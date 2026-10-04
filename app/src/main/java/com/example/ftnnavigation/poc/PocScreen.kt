package com.example.ftnnavigation.poc

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.example.ftnnavigation.campus.CAMPUS_ID
import com.example.ftnnavigation.campus.CampusBuilding
import com.example.ftnnavigation.campus.GpsFix
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.center
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ftnnavigation.R
import com.example.ftnnavigation.campus.BuildingCategory
import com.example.ftnnavigation.campus.ROOM_HOURS
import com.example.ftnnavigation.campus.CampusData
import com.example.ftnnavigation.campus.RouteTarget
import com.example.ftnnavigation.campus.searchDestinations
import com.example.ftnnavigation.graph.BuildingGraph
import com.example.ftnnavigation.graph.Node
import com.example.ftnnavigation.graph.NodeType
import com.example.ftnnavigation.graph.AmfPlan
import com.example.ftnnavigation.graph.IndoorBuilding
import com.example.ftnnavigation.graph.FPlan
import com.example.ftnnavigation.graph.MiPlan
import com.example.ftnnavigation.graph.KulaPlan
import com.example.ftnnavigation.graph.NbPlan
import com.example.ftnnavigation.graph.NtpPlan
import com.example.ftnnavigation.graph.indoorBuilding
import com.example.ftnnavigation.graph.Route
import com.example.ftnnavigation.schedule.AcademicCalendar
import com.example.ftnnavigation.schedule.RoomSchedule
import com.example.ftnnavigation.schedule.ScheduleData
import com.example.ftnnavigation.ui.components.FtnTopAppBar
import com.example.ftnnavigation.ui.theme.FTNNavigationTheme
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Ekran Mapa. Dok praćenje ne radi, kači samo orijentaciju (smer za prikaz); za vreme praćenja
 * senzore drži [PocViewModel] nezavisno od ekrana. GPS radi dok je Mapa na ekranu (i za vreme
 * praćenja); dozvola za lokaciju se traži pri otvaranju Mape.
 */
@Composable
fun PocRoute(
    viewModel: PocViewModel = viewModel(),
    scheduleData: ScheduleData? = null,
    calendar: AcademicCalendar? = null,
) {
    val state = viewModel.state
    val context = LocalContext.current
    val roomSchedule = remember(scheduleData, calendar) {
        if (scheduleData != null && calendar != null) RoomSchedule(scheduleData, calendar) else null
    }

    PdrHeadingEffect(
        enabled = !state.isTracking,
        direction = viewModel.walkingDirection,
        onHeading = viewModel::onHeading,
    )

    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.onLocationPermissionResult()
    }
    LaunchedEffect(Unit) {
        if (!GpsSession.hasPermission(context)) locationPermission.launch(GpsSession.PERMISSIONS)
    }
    LifecycleResumeEffect(viewModel) {
        viewModel.onMapVisible(true)
        onPauseOrDispose { viewModel.onMapVisible(false) }
    }

    PocScreen(
        state = state,
        campus = viewModel.campus,
        graph = viewModel.graph,
        mode = viewModel.mode,
        floor = viewModel.mode.building?.let(viewModel::floorOf) ?: 0,
        gps = viewModel.gps,
        currentBuilding = viewModel.currentBuilding,
        destination = viewModel.destination,
        target = viewModel.target,
        route = viewModel.route,
        routeStart = viewModel.routeStart,
        canStartTracking = viewModel.canStartTracking,
        roomSchedule = roomSchedule,
        autoRotateMap = viewModel.autoRotateMap,
        onModeChange = viewModel::selectMode,
        onFloorChange = viewModel::selectFloor,
        onDestinationChange = viewModel::selectDestination,
        onPickStartToggle = viewModel::togglePickStart,
        onMapTap = viewModel::setPosition,
        onTrackingToggle = viewModel::toggleTracking,
        onReset = viewModel::reset,
        onSnapToggle = viewModel::toggleSnapToGraph,
        onHeadingCorrectionToggle = viewModel::toggleHeadingCorrection,
    )
}

@Composable
fun PocScreen(
    state: PocUiState,
    campus: CampusData?,
    graph: BuildingGraph?,
    mode: MapMode,
    floor: Int,
    gps: GpsFix?,
    currentBuilding: CampusBuilding?,
    destination: String?,
    target: RouteTarget?,
    route: Route?,
    routeStart: RouteStart,
    canStartTracking: Boolean,
    roomSchedule: RoomSchedule?,
    /** Mapa se okreće po smeru korisnika (podešavanje). */
    autoRotateMap: Boolean,
    onModeChange: (MapMode) -> Unit,
    onFloorChange: (Int) -> Unit,
    onDestinationChange: (String?) -> Unit,
    onPickStartToggle: () -> Unit,
    onMapTap: (Offset) -> Unit,
    onTrackingToggle: () -> Unit,
    onReset: () -> Unit,
    onSnapToggle: () -> Unit,
    onHeadingCorrectionToggle: () -> Unit,
) {
    var showDestinations by rememberSaveable { mutableStateOf(false) }
    // Zgrada čiji je natpis držan na mapi kampusa - pop-up sa opisom.
    var infoBuildingId by rememberSaveable { mutableStateOf<String?>(null) }
    // Sala čiji je natpis držan na planu zgrade - pop-up sa radnim vremenom / zauzetošću.
    var infoRoomId by rememberSaveable { mutableStateOf<String?>(null) }
    Scaffold(
        topBar = {
            FtnTopAppBar(
                title = stringResource(R.string.poc_title),
                subtitle = mode.building?.let { stringResource(it.locationRes(), floorName(floor)) }
                    ?: stringResource(R.string.map_campus_location),
            )
        },
    ) { innerPadding ->
        // Samo gornji padding - donji panel sam rešava navigation bar da bi mu pozadina išla do ivice.
        Column(Modifier.padding(top = innerPadding.calculateTopPadding()).fillMaxSize()) {
            MapModeSelector(mode, currentBuilding, onModeChange)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val building = mode.building
                val place = state.pdrPlace
                val pdrHere = building != null && building.buildingId == place.buildingId && floor == place.floor
                // Smer je u odnosu na "gore" plana pozicije; za auto-rotaciju u odnosu na "gore" prikazane mape.
                val mapHeadingDeg = graph?.let {
                    val shownId = building?.buildingId ?: CAMPUS_ID
                    state.headingDeg + (it.placement(place.buildingId).rotationDeg - it.placement(shownId).rotationDeg).toFloat()
                }
                when {
                    building == null -> if (campus != null) CampusMap(
                        campus = campus,
                        graph = graph,
                        route = route,
                        routeStart = routeStart,
                        pdrBuildingId = place.buildingId,
                        position = state.position,
                        gps = gps,
                        headingDeg = state.headingDeg,
                        autoRotate = autoRotateMap,
                        mapHeadingDeg = mapHeadingDeg,
                        isPickingPosition = state.isPickingStart,
                        onTap = onMapTap,
                        onBuildingLongPress = { infoBuildingId = it.id },
                        modifier = Modifier.fillMaxSize(),
                    )
                    else -> {
                        // PDR pozicija je na spratu na kome je postavljen start; start se bira na bilo kom planu.
                        FloorPlan(
                            building = building,
                            floor = floor,
                            graph = graph,
                            route = route,
                            position = state.position.takeIf { pdrHere },
                            rawPosition = state.rawPosition.takeIf { pdrHere },
                            headingDeg = state.headingDeg,
                            autoRotate = autoRotateMap,
                            mapHeadingDeg = mapHeadingDeg,
                            isPickingStart = state.isPickingStart,
                            onTap = onMapTap,
                            onRoomLongPress = { infoRoomId = it.id },
                            // Širok plan: bez ovoga birač sprata prekriva kraj zgrade.
                            modifier = Modifier.fillMaxSize().padding(end = FLOOR_SELECTOR_SPACE),
                        )
                        FloorSelector(
                            floors = building.floors,
                            selected = floor,
                            routeFloors = route.floorsIn(building.buildingId),
                            onSelect = onFloorChange,
                            modifier = Modifier.align(Alignment.CenterEnd).padding(12.dp),
                        )
                    }
                }
                // Na planu: ako je pozicija na drugom planu (ili napolju), piše gde je. Kampus crta poziciju sa svakog plana.
                val hint = when {
                    state.isPickingStart -> stringResource(R.string.poc_hint_pick_start)
                    building == null -> null
                    state.position == null -> stringResource(R.string.poc_hint_no_start)
                    !pdrHere -> place.building?.let {
                        stringResource(R.string.poc_hint_position_elsewhere, stringResource(it.locationRes(), floorName(place.floor)))
                    } ?: stringResource(R.string.poc_hint_position_outside)
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
                            routeStart = routeStart,
                            onClear = { onDestinationChange(null) },
                        )
                    }
                    if (hint != null) HintBanner(hint)
                }
                if (mode.building != null) {
                    // Prekidači za teren, jedan iznad drugog (u redu ne staju pored birača sprata). Ispravka smera
                    // tek kad postoji pozicija - pre toga nema šta da ispravi.
                    val chipColors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surface)
                    Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                        if (state.rawPosition != null) {
                            FilterChip(
                                selected = state.applyHeadingCorrection,
                                onClick = onHeadingCorrectionToggle,
                                label = {
                                    Text(
                                        state.headingErrorDeg?.let {
                                            stringResource(R.string.poc_heading_correction_value, it.roundToInt())
                                        } ?: stringResource(R.string.poc_heading_correction),
                                    )
                                },
                                colors = chipColors,
                            )
                        }
                        FilterChip(
                            selected = state.snapToGraph,
                            onClick = onSnapToggle,
                            label = { Text(stringResource(R.string.poc_snap_to_graph)) },
                            colors = chipColors,
                        )
                    }
                }
            }
            ControlPanel(
                state = state,
                hasDestination = destination != null,
                canPickPosition = graph != null,
                canStartTracking = canStartTracking,
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
    val infoBuilding = infoBuildingId?.let { campus?.building(it) }
    if (infoBuilding != null) {
        BuildingInfoDialog(
            building = infoBuilding,
            onRoute = infoBuilding.name?.takeIf { graph != null }?.let { name ->
                {
                    onDestinationChange(name)
                    infoBuildingId = null
                }
            },
            onDismiss = { infoBuildingId = null },
        )
    }
    val infoRoom = infoRoomId?.let { graph?.node(it) }
    val infoRoomBuilding = infoRoom?.let { indoorBuilding(it.buildingId) }
    val infoRoomName = infoRoom?.name
    if (infoRoomBuilding != null && infoRoomName != null) {
        RoomInfoDialog(
            name = infoRoomName,
            location = stringResource(infoRoomBuilding.locationRes(), floorName(infoRoom.floor)),
            hours = ROOM_HOURS[infoRoomName],
            schedule = roomSchedule,
            onRoute = {
                onDestinationChange(infoRoomName)
                infoRoomId = null
            },
            onDismiss = { infoRoomId = null },
        )
    }
}

/**
 * Slika sprata [floor] zgrade [building] sa pan/zoom gestovima, grafom tog sprata, rutom i
 * markerom korisnika. [position] je pozicija na grafu; [rawPosition] (čist PDR, bez
 * map-matching-a) je bleda tačka za poređenje. Zum ostaje pri promeni sprata iste zgrade.
 * Uz [autoRotate] plan se okreće po smeru korisnika [mapHeadingDeg] (u odnosu na "gore" ovog plana; null = ne zna se).
 */
@Composable
private fun FloorPlan(
    building: IndoorBuilding,
    floor: Int,
    graph: BuildingGraph?,
    route: Route?,
    position: Offset?,
    rawPosition: Offset?,
    headingDeg: Float,
    autoRotate: Boolean,
    mapHeadingDeg: Float?,
    isPickingStart: Boolean,
    onTap: (Offset) -> Unit,
    onRoomLongPress: (Node) -> Unit,
    modifier: Modifier = Modifier,
) {
    val buildingId = building.buildingId
    val painter = painterResource(building.floorDrawable(floor))
    // Zum ostaje pri promeni sprata iste zgrade.
    val zoom = rememberZoomPanState(maxScale = 6f, buildingId)
    AutoRotateEffect(zoom, autoRotate && mapHeadingDeg != null, mapHeadingDeg ?: 0f)
    val floorNames = ALL_FLOORS.associateWith { floorName(it) }
    val colors = MaterialTheme.colorScheme
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = colors.onSurface, lineHeight = TextUnit.Unspecified)
    val haptics = LocalHapticFeedback.current
    val currentOnRoomLongPress by rememberUpdatedState(onRoomLongPress)
    val rooms = remember(graph, buildingId, floor) { graph?.roomsOn(buildingId, floor).orEmpty() }

    Box(
        modifier = modifier
            .clipToBounds()
            .zoomPanGestures(zoom),
        contentAlignment = Alignment.Center,
    ) {
        // Marker i tap koordinate su u koordinatama slike jer su unutar zoomPanLayer-a (graphicsLayer).
        Box(
            Modifier
                .zoomPanLayer(zoom, aspect = painter.intrinsicSize.width / painter.intrinsicSize.height)
                // Posle zoomPanLayer (graphicsLayer): dodir stiže u koordinatama plana (zum, rotacija i pomeraj već skinuti).
                .pointerInput(isPickingStart, onTap, rooms, building) {
                    if (isPickingStart) {
                        detectTapGestures { tap ->
                            onTap(Offset(tap.x / size.width, tap.y / size.height))
                        }
                    } else {
                        detectTapGestures(
                            onLongPress = { at ->
                                val k = 1f / zoom.scale
                                val slop = LABEL_TOUCH_SLOP.toPx() * k
                                val rotation = zoom.rotationDeg
                                val hit = placeRoomLabels(rooms, building, size.toSize(), textMeasurer, labelStyle, rotation)
                                    .filter {
                                        Rect(it.topLeft, it.layout.size.toSize()).inflate(slop).contains(it.inLabelFrame(at, rotation)) ||
                                            (it.dot - at).getDistance() <= ROOM_DOT_RADIUS.toPx() * k + slop
                                    }
                                    .minByOrNull {
                                        (it.topLeft + it.layout.size.toSize().center - it.inLabelFrame(at, rotation)).getDistanceSquared()
                                    }
                                if (hit != null) {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    currentOnRoomLongPress(hit.node)
                                }
                            },
                        )
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
                Canvas(Modifier.fillMaxSize()) {
                    drawGraph(
                        graph, building, floor, rooms, colors.primary, colors.secondary, textMeasurer, labelStyle,
                        1f / zoom.scale, zoom.rotationDeg,
                    )
                }
            }
            if (route != null) {
                val changeStyle = MaterialTheme.typography.labelMedium.copy(
                    color = colors.onPrimary,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = TextUnit.Unspecified,
                )
                val up = stringResource(R.string.route_floor_change_up, "%s")
                val down = stringResource(R.string.route_floor_change_down, "%s")
                Canvas(Modifier.fillMaxSize()) {
                    drawRoute(route, buildingId, floor, position, colors.primary, colors.secondary, 1f / zoom.scale)
                    drawFloorChanges(
                        route, buildingId, floor, colors.primary, textMeasurer, changeStyle, 1f / zoom.scale, zoom.rotationDeg,
                    ) { from, to ->
                        (if (to > from) up else down).format(floorNames.getValue(to))
                    }
                }
            }
            if (position != null) {
                val color = colors.primary
                Canvas(Modifier.fillMaxSize()) {
                    if (rawPosition != null && rawPosition != position) {
                        val raw = Offset(rawPosition.x * size.width, rawPosition.y * size.height)
                        drawCircle(color.copy(alpha = 0.35f), radius = 4.dp.toPx() / zoom.scale, center = raw)
                    }
                    val center = Offset(position.x * size.width, position.y * size.height)
                    // Delimo sa scale da bi marker ostao iste veličine na ekranu pri zumiranju.
                    drawUserMarker(center, headingDeg, color, 1f / zoom.scale)
                }
            }
        }
    }
}

/** Svi spratovi zgrada sa planom (NB -1 ... 5, AMF -1 ... 1, Kula 0 ... 9, NTP 0 ... 5, F 0 ... 3, MI 0 ... 1). */
private val ALL_FLOORS = -1..9

/** "Nastavni blok · %s" i sl. - podnaslov Mape za zgradu. */
@StringRes
private fun IndoorBuilding.locationRes(): Int = when (this) {
    NbPlan -> R.string.nb_location
    AmfPlan -> R.string.amf_location
    KulaPlan -> R.string.kula_location
    NtpPlan -> R.string.ntp_location
    FPlan -> R.string.f_location
    MiPlan -> R.string.mi_location
}

/** Spratovi kroz koje ruta prolazi u zgradi [buildingId]. */
private fun Route?.floorsIn(buildingId: String): Set<Int> =
    this?.nodes.orEmpty().filter { it.buildingId == buildingId }.map { it.floor }.toSet()

/** "suteren" / "prizemlje" / "3. sprat". */
@Composable
private fun floorName(floor: Int): String = when {
    floor < 0 -> stringResource(R.string.floor_basement)
    floor == 0 -> stringResource(R.string.floor_ground)
    else -> stringResource(R.string.floor_number, floor)
}

/** Koliko oko natpisa sale se još računa kao držanje natpisa. */
private val LABEL_TOUCH_SLOP = 8.dp

private val ROOM_DOT_RADIUS = 5.dp

/**
 * Natpis sale izmeren i smešten u px plana (pre zuma), za crtanje i za držanje prstom; [dot] je tačka sale.
 * [topLeft] je u ravni natpisa: plan zarotiran oko [dot] nazad za rotaciju mape, pa je natpis na ekranu uspravan.
 */
private data class PlacedRoomLabel(val node: Node, val layout: TextLayoutResult, val topLeft: Offset, val dot: Offset) {
    /** Tačka plana [at] u ravni natpisa (mapa zarotirana za [rotationDeg]). */
    fun inLabelFrame(at: Offset, rotationDeg: Float): Offset = dot + (at - dot).rotated(rotationDeg)
}

/** Sale sa nazivom na spratu [floor] zgrade [buildingId]. */
private fun BuildingGraph.roomsOn(buildingId: String, floor: Int): List<Node> =
    nodes.filter { it.buildingId == buildingId && it.floor == floor && it.type == NodeType.PROSTORIJA && it.name != null }

/**
 * Natpisi sala na planu veličine [size]. Natpis je deo plana (raste sa zumom, kao tekst na pravom planu) da bi
 * stao u sobu, i na strani sobe dalje od hodnika, da ga ivica ka hodniku ne preseca. Na zarotiranoj mapi
 * ([rotationDeg]) natpis ostaje uspravan, na istoj strani sobe.
 */
private fun Density.placeRoomLabels(
    rooms: List<Node>,
    building: IndoorBuilding,
    size: Size,
    textMeasurer: TextMeasurer,
    labelStyle: TextStyle,
    rotationDeg: Float,
): List<PlacedRoomLabel> {
    val labelHeight = building.labelHeight
    val style = labelStyle.copy(fontSize = (size.height * labelHeight).toSp())
    val gap = size.height * labelHeight / 2
    return rooms.map { node ->
        val dot = Offset(node.x * size.width, node.y * size.height)
        val label = textMeasurer.measure(building.label(node.name.orEmpty()), style)
        val width = label.size.width.toFloat()
        val height = label.size.height.toFloat()
        // Gore ili dole na planu - na ekranu (u ravni natpisa) taj pravac je zarotiran sa mapom.
        val away = Offset(0f, if (node.y < 0.5f) -1f else 1f).rotated(rotationDeg)
        val center = dot + away * (gap + (abs(away.x) * width + abs(away.y) * height) / 2)
        PlacedRoomLabel(node, label, center - Offset(width / 2, height / 2), dot)
    }
}

/** Ivice i čvorovi jednog sprata zgrade; sale ([rooms]) imaju natpis sa nazivom iz rasporeda. */
private fun DrawScope.drawGraph(
    graph: BuildingGraph,
    building: IndoorBuilding,
    floor: Int,
    rooms: List<Node>,
    edgeColor: Color,
    roomColor: Color,
    textMeasurer: TextMeasurer,
    labelStyle: TextStyle,
    k: Float,
    rotationDeg: Float,
) {
    fun Node.toOffset() = Offset(x * size.width, y * size.height)
    val nodes = graph.nodes.filter { it.buildingId == building.buildingId && it.floor == floor }.associateBy { it.id }
    for (edge in graph.edges) {
        val a = nodes[edge.fromId] ?: continue
        val b = nodes[edge.toId] ?: continue
        drawLine(edgeColor.copy(alpha = 0.35f), a.toOffset(), b.toOffset(), strokeWidth = 2.dp.toPx() * k)
    }
    for (node in nodes.values) {
        val center = node.toOffset()
        when (node.type) {
            NodeType.HODNIK, NodeType.VRATA -> drawCircle(edgeColor.copy(alpha = 0.5f), radius = 3.dp.toPx() * k, center = center)
            NodeType.PROSTORIJA -> drawCircle(roomColor, radius = ROOM_DOT_RADIUS.toPx() * k, center = center)
            NodeType.STEPENISTE, NodeType.LIFT, NodeType.ULAZ, NodeType.PROLAZ ->
                drawCircle(edgeColor, radius = 5.dp.toPx() * k, center = center)
            NodeType.STAZA, NodeType.ZGRADA -> Unit // ne postoje na planu zgrade
        }
    }
    for (label in placeRoomLabels(rooms, building, size, textMeasurer, labelStyle, rotationDeg)) {
        rotate(-rotationDeg, pivot = label.dot) { drawText(label.layout, topLeft = label.topLeft) }
    }
}

/**
 * Deo rute na spratu [floor] zgrade [buildingId] kao debela linija (od pozicije, ako je
 * postavljena); delovi van zgrade i na drugim spratovima se preskaču. Odredište se obeležava ako
 * je na tom spratu.
 */
private fun DrawScope.drawRoute(
    route: Route,
    buildingId: String,
    floor: Int,
    position: Offset?,
    color: Color,
    targetColor: Color,
    k: Float,
) {
    fun Node.isHere() = this.buildingId == buildingId && this.floor == floor
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

/**
 * Gde ruta napušta sprat [floor] stepenicama ili liftom: oblačić sa spratom na kome se silazi sa
 * stepeništa/lifta ([label] od, do). Isto i na spratu kroz koji ruta samo prolazi. Na zarotiranoj mapi
 * ([rotationDeg]) oblačić ostaje uspravan, iznad tačke.
 */
private fun DrawScope.drawFloorChanges(
    route: Route,
    buildingId: String,
    floor: Int,
    color: Color,
    textMeasurer: TextMeasurer,
    style: TextStyle,
    k: Float,
    rotationDeg: Float,
    label: (from: Int, to: Int) -> String,
) {
    val nodes = route.nodes
    for (i in 0 until nodes.size - 1) {
        val here = nodes[i]
        if (here.buildingId != buildingId || here.floor != floor || nodes[i + 1].floor == floor) continue
        // Kraj vertikalnog dela: dok se sprat menja istim stepeništem/liftom.
        var j = i + 1
        while (j + 1 < nodes.size && nodes[j + 1].buildingId == buildingId && nodes[j + 1].floor != nodes[j].floor) j++
        if (nodes[j].buildingId != buildingId) continue
        val text = textMeasurer.measure(label(floor, nodes[j].floor), style.copy(fontSize = (style.fontSize.toPx() * k).toSp()))
        val pad = 6.dp.toPx() * k
        val center = Offset(here.x * size.width, here.y * size.height)
        val topLeft = center + Offset(-text.size.width / 2f - pad, -text.size.height - 2 * pad - 14.dp.toPx() * k)
        rotate(-rotationDeg, pivot = center) {
            drawRoundRect(
                color,
                topLeft = topLeft,
                size = Size(text.size.width + 2 * pad, text.size.height + 2 * pad),
                cornerRadius = CornerRadius(8.dp.toPx() * k),
            )
            drawText(text, topLeft = topLeft + Offset(pad, pad))
        }
    }
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
private fun RouteBanner(destination: String, target: RouteTarget?, route: Route?, routeStart: RouteStart, onClear: () -> Unit) {
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
                    val from = stringResource(
                        when (routeStart) {
                            RouteStart.PDR -> R.string.route_from_position
                            RouteStart.GPS -> R.string.route_from_gps
                            RouteStart.ENTRANCE -> R.string.route_from_entrance
                        },
                    )
                    stringResource(R.string.route_summary, route.minutes, route.lengthM.toInt()) + " · " + from
                }
                Text(
                    details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val building = target?.building?.name
                if (route != null && building != null && building != destination) {
                    // Sala u zgradi sa spratovima: i sprat.
                    val where = if (!target.approximate && indoorBuilding(target.node.buildingId) != null) {
                        stringResource(R.string.building_with_floor, building, floorName(target.node.floor))
                    } else {
                        building
                    }
                    Text(
                        stringResource(if (target.approximate) R.string.route_to_building else R.string.route_in_building, where),
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
 * Izbor odredišta: zgrade FTN-a, studentske službe i sale, sa pretragom po nazivu
 * ([searchDestinations]). Koristi ga i izmena događaja (mesto događaja) - tada [noneLabel] dodaje
 * stavku bez mesta ([onSelect] null).
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
    var query by rememberSaveable { mutableStateOf("") }
    val sections = listOf(
        R.string.route_destinations_buildings to buildings,
        R.string.route_destinations_services to services,
        R.string.route_destinations_rooms to rooms,
    ).map { (header, names) -> header to searchDestinations(names, query) }
    val focusManager = LocalFocusManager.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        // Puna visina, da se prozor ne skuplja dok se kuca (lista se skraćuje).
        Column(Modifier.fillMaxHeight().navigationBarsPadding().imePadding()) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.route_destinations_search)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(
                                painterResource(R.drawable.ic_close),
                                contentDescription = stringResource(R.string.route_destinations_search_clear),
                            )
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 4.dp),
            )
            LazyColumn(Modifier.weight(1f)) {
                if (noneLabel != null && query.isBlank()) {
                    item { DestinationItem(noneLabel, isSelected = selected == null, onClick = { onSelect(null) }) }
                }
                for ((header, names) in sections) {
                    if (names.isEmpty()) continue
                    item(key = header) { SheetSectionHeader(stringResource(header)) }
                    items(names) { name ->
                        DestinationItem(name, isSelected = name == selected, onClick = { onSelect(name) })
                    }
                }
                if (sections.all { it.second.isEmpty() }) {
                    item {
                        Text(
                            stringResource(R.string.route_destinations_no_results, query.trim()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                        )
                    }
                }
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

@StringRes
private fun MapMode.labelRes(): Int = when (this) {
    MapMode.KAMPUS -> R.string.map_mode_campus
    MapMode.NB -> R.string.map_mode_building
    MapMode.AMF -> R.string.map_mode_amf
    MapMode.KULA -> R.string.map_mode_kula
    MapMode.NTP -> R.string.map_mode_ntp
    MapMode.F -> R.string.map_mode_f
    MapMode.MI -> R.string.map_mode_mi
}

/**
 * Prekidač prikaza Mape: kampus (spolja) ili plan zgrade. Kad se po GPS-u zna u kojoj je zgradi
 * korisnik ([here]), prikazani su samo kampus i ta zgrada (sa oznakom lokacije), a ostale su u
 * meniju "…" ([shownModes]).
 */
@Composable
private fun MapModeSelector(mode: MapMode, here: CampusBuilding?, onModeChange: (MapMode) -> Unit) {
    val shown = shownModes(mode, here)
    val hidden = MapMode.entries - shown.toSet()
    val count = shown.size + if (hidden.isEmpty()) 0 else 1
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        shown.forEachIndexed { index, option ->
            val isHere = here != null && option.building?.buildingId == here.id
            SegmentedButton(
                selected = mode == option,
                onClick = { onModeChange(option) },
                shape = SegmentedButtonDefaults.itemShape(index, count),
                // Do pet izbora: bez kvačice, da natpisi stanu; zgrada u kojoj je korisnik ima oznaku.
                icon = {
                    if (isHere) {
                        Icon(
                            painterResource(R.drawable.ic_my_location),
                            contentDescription = stringResource(R.string.map_mode_here),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                },
            ) {
                Text(stringResource(option.labelRes()), maxLines = 1, style = MaterialTheme.typography.labelMedium)
            }
        }
        if (hidden.isNotEmpty()) {
            var expanded by remember { mutableStateOf(false) }
            val moreDescription = stringResource(R.string.map_mode_more_description)
            SegmentedButton(
                selected = false,
                onClick = { expanded = true },
                shape = SegmentedButtonDefaults.itemShape(count - 1, count),
                icon = {},
            ) {
                // Meni je u dugmetu da bi se otvorio ispod njega.
                Box {
                    Text(
                        stringResource(R.string.map_mode_more),
                        maxLines = 1,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.semantics { contentDescription = moreDescription },
                    )
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        for (option in hidden) {
                            DropdownMenuItem(
                                text = { Text(stringResource(option.labelRes())) },
                                onClick = {
                                    expanded = false
                                    onModeChange(option)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Širina birača sprata sa marginom (40 dp dugme + 2 x 4 dp + 12 dp). */
private val FLOOR_SELECTOR_SPACE = 60.dp

/**
 * Izbor sprata (odozgo V ... I, P, -1). Spratovi kroz koje prolazi ruta imaju tačku, da se vidi gde
 * ruta nastavlja.
 */
@Composable
private fun FloorSelector(
    floors: IntRange,
    selected: Int,
    routeFloors: Set<Int>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.large, shadowElevation = 3.dp, modifier = modifier) {
        // Kula ima 10 nivoa - birač se pomera ako ne stane.
        Column(Modifier.verticalScroll(rememberScrollState()).padding(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (floor in floors.reversed()) {
                val isSelected = floor == selected
                val name = floorName(floor)
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) colors.primary else Color.Transparent)
                        .clickable(onClickLabel = stringResource(R.string.floor_select, name)) { onSelect(floor) }
                        .semantics { contentDescription = name },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (floor == 0) stringResource(R.string.floor_ground_short) else floor.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isSelected) colors.onPrimary else colors.onSurface,
                    )
                    if (floor in routeFloors) {
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 4.dp)
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(if (isSelected) colors.onPrimary else colors.primary),
                        )
                    }
                }
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
    canPickPosition: Boolean,
    canStartTracking: Boolean,
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
                // "Ovde sam" radi i za vreme praćenja (rekalibracija).
                OutlinedButton(
                    onClick = onPickStartToggle,
                    enabled = canPickPosition,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        stringResource(
                            if (state.isPickingStart) R.string.poc_cancel else R.string.poc_set_position,
                        ),
                    )
                }
                Button(
                    onClick = onTrackingToggle,
                    enabled = (state.isTracking || canStartTracking) && !state.isPickingStart,
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
            mode = MapMode.NB,
            floor = 0,
            gps = null,
            currentBuilding = null,
            destination = "NTP-307",
            target = null,
            route = null,
            routeStart = RouteStart.PDR,
            canStartTracking = true,
            roomSchedule = null,
            autoRotateMap = false,
            onModeChange = {},
            onFloorChange = {},
            onDestinationChange = {},
            onPickStartToggle = {},
            onMapTap = {},
            onTrackingToggle = {},
            onReset = {},
            onSnapToggle = {},
            onHeadingCorrectionToggle = {},
        )
    }
}
