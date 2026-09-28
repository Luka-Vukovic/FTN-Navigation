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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.ftnnavigation.R
import com.example.ftnnavigation.departure.Departure
import com.example.ftnnavigation.graph.Route
import com.example.ftnnavigation.schedule.ClassEntry
import com.example.ftnnavigation.schedule.ClassType
import com.example.ftnnavigation.schedule.Groups
import com.example.ftnnavigation.schedule.RoomLabel
import com.example.ftnnavigation.schedule.UpcomingClass
import com.example.ftnnavigation.schedule.classTypeLabel
import com.example.ftnnavigation.schedule.whenLabel
import com.example.ftnnavigation.ui.components.FtnTopAppBar
import com.example.ftnnavigation.ui.theme.FTNNavigationTheme
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * @param scheduleSummary npr. "4. godina · grupa 3 · ..."; null dok raspored nije izabran.
 * @param upcoming sledeći čas iz izabranog rasporeda (null ako nema ili nije izabran).
 * @param nextBuilding zgrada sale sledećeg časa (null ako se ne zna).
 * @param departure polazak na sledeći čas, isti kao u obaveštenju (iz sale prethodnog časa ili
 *   od glavnog ulaza); null = prethodni čas je u istoj sali.
 */
@Composable
fun HomeScreen(
    scheduleSummary: String?,
    upcoming: UpcomingClass?,
    now: LocalDateTime,
    nextBuilding: String?,
    departure: Departure?,
    onOpenSchedule: () -> Unit,
    onOpenMap: () -> Unit,
    onShowRoute: () -> Unit,
    onOpenNotifications: () -> Unit,
) {
    Scaffold(
        topBar = {
            FtnTopAppBar(
                title = stringResource(R.string.app_name),
                subtitle = stringResource(R.string.home_subtitle),
                actions = {
                    IconButton(onClick = onOpenNotifications) {
                        Icon(
                            painterResource(R.drawable.ic_notifications),
                            contentDescription = stringResource(R.string.notifications_title),
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
                    else -> NextClass(upcoming, now, nextBuilding, departure, onShowRoute)
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
        }
    }
}

@Composable
private fun NextClass(upcoming: UpcomingClass, now: LocalDateTime, building: String?, departure: Departure?, onShowRoute: () -> Unit) {
    val entry = upcoming.entry
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            whenLabel(upcoming, now),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(entry.subject, style = MaterialTheme.typography.titleLarge)
        Text(
            "${entry.start} – ${entry.end} · ${classTypeLabel(entry.type)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RoomLabel(if (building != null) "${entry.room} · $building" else entry.room, Modifier.padding(top = 2.dp))
        val route = departure?.route
        if (route == null) {
            Text(
                stringResource(if (departure == null) R.string.home_same_room else R.string.route_not_on_map),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            RouteEstimate(departure, route, now, onShowRoute)
        }
    }
}

/**
 * Trajanje rute (od glavnog ulaza ili iz sale prethodnog časa) i, za današnji čas koji nije
 * počeo, najkasnije vreme polaska - isto kao u obaveštenju.
 */
@Composable
private fun RouteEstimate(departure: Departure, route: Route, now: LocalDateTime, onShowRoute: () -> Unit) {
    val from = departure.fromRoom
    val leaveAt = departure.leaveAt.format(TIME_FORMAT)
    val deadline = when {
        departure.date != now.toLocalDate() || !departure.classStart.isAfter(now) -> null
        !now.isBefore(departure.leaveAt) -> stringResource(R.string.home_route_go_now)
        from == null -> stringResource(R.string.home_route_enter_by, leaveAt)
        else -> stringResource(R.string.home_route_leave_room_by, from, leaveAt)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                if (from == null) {
                    stringResource(R.string.home_route_from_entrance, route.minutes)
                } else {
                    stringResource(R.string.home_route_from_room, from, route.minutes)
                },
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

private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

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
            upcoming = UpcomingClass(entry, date),
            now = now,
            nextBuilding = "Naučno-tehnološki park",
            departure = Departure(
                entry = entry,
                date = date,
                fromRoom = null,
                route = Route(emptyList(), durationSec = 240.0, lengthM = 310.0),
                leaveAt = date.atTime(10, 11),
                notifyAt = date.atTime(10, 6),
            ),
            onOpenSchedule = {},
            onOpenMap = {},
            onShowRoute = {},
            onOpenNotifications = {},
        )
    }
}
