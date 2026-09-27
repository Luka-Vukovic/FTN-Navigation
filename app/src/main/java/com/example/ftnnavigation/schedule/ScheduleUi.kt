package com.example.ftnnavigation.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.ftnnavigation.R
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Trenutno vreme koje se osvežava na početku svakog minuta (za "za 10 min" i sl.). */
@Composable
fun rememberNow(): State<LocalDateTime> = produceState(LocalDateTime.now()) {
    while (true) {
        val now = LocalDateTime.now()
        value = now
        delay(Duration.between(now, now.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)).toMillis() + 50)
    }
}

@Composable
fun dayName(day: Int): String = stringArrayResource(R.array.days_full)[day - 1]

@Composable
fun classTypeLabel(type: ClassType): String = stringResource(
    when (type) {
        ClassType.PREDAVANJE -> R.string.class_type_lecture
        ClassType.AUDITORNE_VEZBE -> R.string.class_type_auditory
        ClassType.RACUNARSKE_VEZBE -> R.string.class_type_computer
        ClassType.LABORATORIJSKE_VEZBE -> R.string.class_type_lab
        ClassType.OSTALO -> R.string.class_type_other
    },
)

@Composable
private fun classTypeColor(type: ClassType): Color = when (type) {
    ClassType.PREDAVANJE -> MaterialTheme.colorScheme.primary
    ClassType.OSTALO -> MaterialTheme.colorScheme.outline
    else -> MaterialTheme.colorScheme.secondary
}

/** "Danas · u toku", "Danas · za 25 min", "Sutra", "Sreda", ... */
@Composable
fun whenLabel(upcoming: UpcomingClass, now: LocalDateTime): String {
    val daysAhead = ChronoUnit.DAYS.between(now.toLocalDate(), upcoming.date)
    return when (daysAhead) {
        0L -> {
            val minutes = Duration.between(now.toLocalTime(), upcoming.entry.startTime).toMinutes()
            val relative = when {
                minutes <= 0 -> stringResource(R.string.when_ongoing)
                minutes < 60 -> stringResource(R.string.when_in_minutes, minutes)
                else -> stringResource(R.string.when_in_hours, minutes / 60, minutes % 60)
            }
            "${stringResource(R.string.when_today)} · $relative"
        }
        1L -> stringResource(R.string.when_tomorrow)
        in 2..6 -> dayName(upcoming.date.dayOfWeek.value)
        else -> "${dayName(upcoming.date.dayOfWeek.value)}, ${upcoming.date.format(SHORT_DATE)}"
    }
}

val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d. M.")

/** Sala sa ikonicom pina (kasnije: klik vodi na mapu/rutu do sale). */
@Composable
fun RoomLabel(room: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painterResource(R.drawable.ic_place),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Text(
            room,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/** Kartica jednog časa u listi rasporeda; traka levo je boja vrste nastave. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ClassCard(entry: ClassEntry, showGroups: Boolean, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(classTypeColor(entry.type)),
            )
            Column(
                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${entry.start} – ${entry.end}",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        classTypeLabel(entry.type),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(entry.subject, style = MaterialTheme.typography.titleMedium)
                RoomLabel(entry.room)
                if (entry.lecturers.isNotEmpty()) {
                    Text(
                        entry.lecturers.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val badges = buildList {
                    entry.localDate?.let { add(stringResource(R.string.schedule_badge_date, it.format(SHORT_DATE))) }
                    if (entry.groups.biweekly) add(stringResource(R.string.schedule_badge_biweekly))
                    if (entry.groups.elective) add(stringResource(R.string.schedule_badge_elective))
                    if (showGroups && !entry.groups.all && !entry.groups.elective) {
                        add(stringResource(R.string.schedule_badge_groups, entry.groups.raw))
                    }
                }
                if (badges.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(top = 2.dp),
                    ) {
                        badges.forEach { Badge(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Badge(text: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
