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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
 * @param routeToNext ruta od glavnog ulaza do sale sledećeg časa (null ako sala nije na mapi).
 * @param onTestNotification probno obaveštenje o polasku; null = bez dugmeta (release build).
 */
@Composable
fun HomeScreen(
    scheduleSummary: String?,
    upcoming: UpcomingClass?,
    now: LocalDateTime,
    nextBuilding: String?,
    routeToNext: Route?,
    onOpenSchedule: () -> Unit,
    onOpenMap: () -> Unit,
    onShowRoute: () -> Unit,
    onTestNotification: (() -> Unit)? = null,
) {
    Scaffold(
        topBar = {
            FtnTopAppBar(
                title = stringResource(R.string.app_name),
                subtitle = stringResource(R.string.home_subtitle),
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
                    else -> NextClass(upcoming, now, nextBuilding, routeToNext, onShowRoute)
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
            if (onTestNotification != null) {
                OutlinedButton(onClick = onTestNotification, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(stringResource(R.string.debug_test_notification))
                }
            }
        }
    }
}

@Composable
private fun NextClass(upcoming: UpcomingClass, now: LocalDateTime, building: String?, route: Route?, onShowRoute: () -> Unit) {
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
        if (route == null) {
            Text(
                stringResource(R.string.route_not_on_map),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            RouteEstimate(upcoming, now, route, onShowRoute)
        }
    }
}

/** Vreme od ulaza do sale i, za današnji čas koji nije počeo, najkasnije vreme ulaska u zgradu. */
@Composable
private fun RouteEstimate(upcoming: UpcomingClass, now: LocalDateTime, route: Route, onShowRoute: () -> Unit) {
    val start = upcoming.date.atTime(upcoming.entry.startTime)
    val enterBy = start.minusMinutes(route.minutes.toLong())
    val deadline = when {
        upcoming.date != now.toLocalDate() || !start.isAfter(now) -> null
        now.isBefore(enterBy) -> stringResource(R.string.home_route_enter_by, enterBy.format(TIME_FORMAT))
        else -> stringResource(R.string.home_route_go_now)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.home_route_from_entrance, route.minutes),
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
    FTNNavigationTheme {
        HomeScreen(
            scheduleSummary = "4. godina · grupa 3 · Softversko inženjerstvo i informacione tehnologije",
            upcoming = UpcomingClass(
                ClassEntry(
                    day = 2, start = "10:15", end = "12:00", room = "NTP-001", type = ClassType.PREDAVANJE,
                    subject = "Napredne veb tehnologije", lecturers = listOf("dr Branko Milosavljević"),
                    groups = Groups("SVI", all = true, elective = false, numbers = emptyList(), areas = emptyList(), biweekly = false),
                ),
                LocalDate.of(2026, 9, 29),
            ),
            now = now,
            nextBuilding = "Naučno-tehnološki park",
            routeToNext = Route(emptyList(), durationSec = 240.0, lengthM = 310.0),
            onOpenSchedule = {},
            onOpenMap = {},
            onShowRoute = {},
        )
    }
}
