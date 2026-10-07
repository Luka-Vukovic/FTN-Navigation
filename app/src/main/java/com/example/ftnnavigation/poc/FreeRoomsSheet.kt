package com.example.ftnnavigation.poc

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.ftnnavigation.R
import com.example.ftnnavigation.graph.BuildingGraph
import com.example.ftnnavigation.graph.INDOOR_BUILDINGS
import com.example.ftnnavigation.graph.Node
import com.example.ftnnavigation.graph.Route
import com.example.ftnnavigation.graph.indoorBuilding
import com.example.ftnnavigation.schedule.FreeRoom
import com.example.ftnnavigation.schedule.RoomSchedule
import com.example.ftnnavigation.schedule.TIME_FORMAT
import com.example.ftnnavigation.schedule.dayNotes
import com.example.ftnnavigation.schedule.rememberNow

/** Izbor "slobodna još bar": minuti. */
private val MIN_FREE_OPTIONS = listOf(15L, 30L, 60L, 120L)
private const val DEFAULT_MIN_FREE = 30L

/** Slobodna prostorija sa svojim čvorom i rutom odavde (null - puta nema). */
private data class FreeRoomOption(val room: FreeRoom, val node: Node, val route: Route?)

/**
 * Prostorije koje su po rasporedu nastave sada slobodne bar još izabrano vreme (samo prostorije iz rasporeda), po
 * vremenu hoda odavde ([routeTo] - kao ruta na Mapi), sa izborom zgrade (podrazumevano [hereBuildingId] - zgrada u
 * kojoj je korisnik). Napomena: slobodna po rasporedu ne znači i stvarno dostupna. Dodir -> ruta ([onRoute]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FreeRoomsSheet(
    graph: BuildingGraph,
    schedule: RoomSchedule,
    hereBuildingId: String?,
    routeTo: (String) -> Route?,
    onRoute: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val now by rememberNow()
    var minMinutes by rememberSaveable { mutableStateOf(DEFAULT_MIN_FREE) }
    var building by rememberSaveable { mutableStateOf(hereBuildingId?.takeIf { indoorBuilding(it) != null }) }
    val today = now.toLocalDate()
    val known = schedule.knows(today)
    // Sve slobodne (svih zgrada) sa rutom; računa se jednom u minuti i pri promeni izbora - A* do ~100 sala.
    val all = remember(now, minMinutes, graph, schedule) {
        if (!known) return@remember emptyList()
        val rooms = graph.rooms.filter { it.name != null }.associateBy { it.name!! }
        schedule.freeRooms(rooms.keys.sorted(), now, minMinutes).map {
            val node = rooms.getValue(it.name)
            FreeRoomOption(it, node, routeTo(node.id))
        }.sortedBy { it.route?.durationSec ?: Double.MAX_VALUE }
    }
    val buildings = INDOOR_BUILDINGS.filter { b -> all.any { it.node.buildingId == b.buildingId } }.map { it.buildingId }
    val shownBuilding = building?.takeIf { it in buildings }
    val shown = all.filter { shownBuilding == null || it.node.buildingId == shownBuilding }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxHeight().navigationBarsPadding()) {
            Text(
                stringResource(R.string.free_rooms_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                stringResource(R.string.free_rooms_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp).padding(top = 4.dp, bottom = 8.dp),
            )
            val info = schedule.calendar.dayInfo(today)
            val notes = when {
                !known -> listOf(stringResource(R.string.room_info_semester_missing))
                info.scheduleDay == null -> dayNotes(info, timetableSemester = null) + stringResource(R.string.free_rooms_no_teaching_today)
                else -> emptyList()
            }
            notes.forEach {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 4.dp),
                )
            }
            ChipRow {
                Text(
                    stringResource(R.string.free_rooms_min_label),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
                MIN_FREE_OPTIONS.forEach { option ->
                    FilterChip(
                        selected = option == minMinutes,
                        onClick = { minMinutes = option },
                        label = { Text(durationLabel(option)) },
                    )
                }
            }
            if (buildings.size > 1) {
                ChipRow {
                    FilterChip(
                        selected = shownBuilding == null,
                        onClick = { building = null },
                        label = { Text(stringResource(R.string.free_rooms_all_buildings)) },
                    )
                    buildings.forEach { id ->
                        val mode = MapMode.entries.first { it.building?.buildingId == id }
                        FilterChip(
                            selected = shownBuilding == id,
                            onClick = { building = id },
                            label = { Text(stringResource(mode.labelRes())) },
                        )
                    }
                }
            }
            LazyColumn(Modifier.weight(1f)) {
                if (known && shown.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.free_rooms_none, durationLabel(minMinutes)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                        )
                    }
                }
                items(shown, key = { it.node.id }) { option ->
                    FreeRoomRow(option, onClick = { onRoute(option.room.name) })
                }
            }
        }
    }
}

@Composable
private fun ChipRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun durationLabel(minutes: Long): String =
    if (minutes >= 60) stringResource(R.string.free_rooms_min_hours, (minutes / 60).toInt())
    else stringResource(R.string.free_rooms_min_minutes, minutes.toInt())

@Composable
private fun FreeRoomRow(option: FreeRoomOption, onClick: () -> Unit) {
    val node = option.node
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(option.room.name, style = MaterialTheme.typography.bodyLarge)
            indoorBuilding(node.buildingId)?.let {
                Text(
                    stringResource(it.locationRes(), floorName(node.floor)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                option.room.until?.let { stringResource(R.string.free_rooms_until, it.format(TIME_FORMAT)) }
                    ?: stringResource(R.string.free_rooms_rest_of_day),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            option.route?.let {
                Text(
                    stringResource(R.string.free_rooms_walk, it.minutes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
