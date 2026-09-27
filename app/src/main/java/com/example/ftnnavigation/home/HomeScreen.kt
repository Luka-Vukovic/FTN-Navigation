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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.ftnnavigation.R
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

/**
 * @param scheduleSummary npr. "4. godina · grupa 3 · ..."; null dok raspored nije izabran.
 * @param upcoming sledeći čas iz izabranog rasporeda (null ako nema ili nije izabran).
 */
@Composable
fun HomeScreen(
    scheduleSummary: String?,
    upcoming: UpcomingClass?,
    now: LocalDateTime,
    onOpenSchedule: () -> Unit,
    onOpenMap: () -> Unit,
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
                    else -> NextClass(upcoming, now)
                }
            }
            HomeCard(
                icon = R.drawable.ic_map,
                title = stringResource(R.string.home_map_title),
                subtitle = stringResource(R.string.poc_location),
                actionLabel = stringResource(R.string.home_open_map),
                onAction = onOpenMap,
            ) {
                CardBody(stringResource(R.string.home_map_body))
            }
        }
    }
}

@Composable
private fun NextClass(upcoming: UpcomingClass, now: LocalDateTime) {
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
        RoomLabel(entry.room, Modifier.padding(top = 2.dp))
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
            onOpenSchedule = {},
            onOpenMap = {},
        )
    }
}
