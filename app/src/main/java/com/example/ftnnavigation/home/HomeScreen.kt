package com.example.ftnnavigation.home

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.ftnnavigation.R
import com.example.ftnnavigation.departure.Departure
import com.example.ftnnavigation.departure.leaveByText
import com.example.ftnnavigation.departure.noRouteText
import com.example.ftnnavigation.departure.routeText
import com.example.ftnnavigation.graph.Route
import com.example.ftnnavigation.schedule.AgendaItem
import com.example.ftnnavigation.schedule.ClassEntry
import com.example.ftnnavigation.schedule.ClassType
import com.example.ftnnavigation.schedule.Groups
import com.example.ftnnavigation.schedule.RoomLabel
import com.example.ftnnavigation.schedule.TIME_FORMAT
import com.example.ftnnavigation.schedule.classTypeLabel
import com.example.ftnnavigation.schedule.whenLabel
import com.example.ftnnavigation.ui.components.FtnTopAppBar
import com.example.ftnnavigation.ui.theme.FTNNavigationTheme
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * @param scheduleSummary npr. "4. godina · grupa 3 · ..."; null dok raspored nije izabran.
 * @param upcoming sledeći čas ili sopstveni događaj (null ako nema).
 * @param nextBuilding zgrada njegovog mesta (null ako se ne zna).
 * @param departure polazak, isti kao u obaveštenju (sa mesta prethodne stavke ili od glavnog
 *   ulaza); null = prethodna stavka je na istom mestu.
 */
@Composable
fun HomeScreen(
    scheduleSummary: String?,
    upcoming: AgendaItem?,
    now: LocalDateTime,
    nextBuilding: String?,
    departure: Departure?,
    onOpenSchedule: () -> Unit,
    onOpenMap: () -> Unit,
    onShowRoute: () -> Unit,
    onOpenSettings: () -> Unit,
    /** Slobodne prostorije po rasporedu (null - raspored i mapa se još učitavaju). */
    onOpenFreeRooms: (() -> Unit)?,
) {
    Scaffold(
        topBar = {
            FtnTopAppBar(
                title = stringResource(R.string.app_name),
                subtitle = stringResource(R.string.home_subtitle),
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            painterResource(R.drawable.ic_settings),
                            contentDescription = stringResource(R.string.settings_title),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.home_greeting),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            HomeCard(
                icon = R.drawable.ic_calendar,
                title = stringResource(R.string.home_next_class_title),
                subtitle = scheduleSummary,
                actionLabel = stringResource(
                    if (scheduleSummary == null) R.string.home_choose_program else R.string.home_open_schedule,
                ),
                onAction = onOpenSchedule,
            ) {
                when {
                    scheduleSummary == null -> CardBody(stringResource(R.string.home_next_class_empty))
                    upcoming == null -> CardBody(stringResource(R.string.home_no_upcoming))
                    else -> NextItem(upcoming, now, nextBuilding, departure, onShowRoute)
                }
            }
            HomeCard(
                icon = R.drawable.ic_map,
                title = stringResource(R.string.home_map_title),
                subtitle = stringResource(R.string.home_map_subtitle),
                actionLabel = stringResource(R.string.home_open_map),
                onAction = onOpenMap,
            ) {
                CardBody(stringResource(R.string.home_map_body))
            }
            if (onOpenFreeRooms != null) {
                HomeCard(
                    icon = R.drawable.ic_meeting_room,
                    title = stringResource(R.string.home_free_rooms_title),
                    subtitle = stringResource(R.string.home_free_rooms_subtitle),
                    actionLabel = stringResource(R.string.home_free_rooms_action),
                    onAction = onOpenFreeRooms,
                ) {
                    CardBody(stringResource(R.string.home_free_rooms_body))
                }
            }
        }
    }
}

@Composable
private fun NextItem(item: AgendaItem, now: LocalDateTime, building: String?, departure: Departure?, onShowRoute: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            whenLabel(item.date, item.start, now),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(item.title, style = MaterialTheme.typography.titleLarge)
        val kind = when (item) {
            is AgendaItem.Class -> classTypeLabel(item.entry.type)
            is AgendaItem.Event -> stringResource(R.string.event_label)
        }
        Text(
            "${item.start.format(TIME_FORMAT)} – ${item.end.format(TIME_FORMAT)} · $kind",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Događaj bez mesta nema ni rutu.
        val place = item.place ?: return@Column
        RoomLabel(if (building != null && building != place) "$place · $building" else place, Modifier.padding(top = 2.dp))
        if (departure?.route == null) {
            val note = when {
                departure != null -> noRouteText(place)
                item is AgendaItem.Class -> R.string.home_same_room
                else -> R.string.home_same_place
            }
            Text(
                stringResource(note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            RouteEstimate(departure, now, onShowRoute)
        }
    }
}

/**
 * Trajanje rute (od glavnog ulaza ili sa mesta prethodne stavke) i, za današnju stavku koja
 * nije počela, najkasnije vreme polaska - isto kao u obaveštenju.
 */
@Composable
private fun RouteEstimate(departure: Departure, now: LocalDateTime, onShowRoute: () -> Unit) {
    val res = LocalResources.current
    val deadline = when {
        departure.date != now.toLocalDate() || !departure.startAt.isAfter(now) -> null
        !now.isBefore(departure.leaveAt) -> stringResource(R.string.home_route_go_now)
        else -> departure.leaveByText(res)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                departure.routeText(res).orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (deadline != null) {
                Text(deadline, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        TextButton(onClick = onShowRoute) {
            Text(stringResource(R.string.home_show_route))
        }
    }
}

@Composable
private fun CardBody(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun HomeCard(
    @DrawableRes icon: Int,
    title: String,
    actionLabel: String,
    onAction: () -> Unit,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(40.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(icon),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Column(Modifier.padding(start = 12.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            content()
            FilledTonalButton(onClick = onAction, modifier = Modifier.align(Alignment.End)) {
                Text(actionLabel)
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    val now = LocalDateTime.of(2026, 9, 29, 9, 50)
    val date = LocalDate.of(2026, 9, 29)
    val entry = ClassEntry(
        day = 2, start = "10:15", end = "12:00", room = "NTP-001", type = ClassType.PREDAVANJE,
        subject = "Napredne veb tehnologije", lecturers = listOf("dr Branko Milosavljević"),
        groups = Groups("SVI", all = true, elective = false, numbers = emptyList(), areas = emptyList(), biweekly = false),
    )
    FTNNavigationTheme {
        HomeScreen(
            scheduleSummary = "4. godina · grupa 3 · Softversko inženjerstvo i informacione tehnologije",
            upcoming = AgendaItem.Class(entry, date),
            now = now,
            nextBuilding = "Naučno-tehnološki park",
            departure = Departure(
                item = AgendaItem.Class(entry, date),
                from = null,
                route = Route(emptyList(), durationSec = 240.0, lengthM = 310.0),
                leaveAt = date.atTime(10, 11),
                notifyAt = date.atTime(10, 6),
            ),
            onOpenSchedule = {},
            onOpenMap = {},
            onShowRoute = {},
            onOpenSettings = {},
            onOpenFreeRooms = {},
        )
    }
}
