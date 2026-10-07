package com.example.ftnnavigation.poc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.ftnnavigation.R
import com.example.ftnnavigation.campus.OpeningHours
import com.example.ftnnavigation.schedule.RoomSchedule
import com.example.ftnnavigation.schedule.RoomSlot
import com.example.ftnnavigation.schedule.RoomStatus
import com.example.ftnnavigation.schedule.SHORT_DATE
import com.example.ftnnavigation.schedule.TIME_FORMAT
import com.example.ftnnavigation.schedule.classTypeLabel
import com.example.ftnnavigation.schedule.dayName
import com.example.ftnnavigation.schedule.dayNotes
import com.example.ftnnavigation.schedule.rememberNow
import com.example.ftnnavigation.schedule.roomStatus
import java.time.LocalDate

/**
 * Pop-up o prostoriji (držanje natpisa sale na planu zgrade): radno vreme ([hours], npr. Biblioteka) i/ili
 * zauzetost po rasporedima svih smerova ([schedule], za učionice), po danima. [location] je "Zgrada · sprat".
 * [onRoute] je null kad ruta nije moguća. Zvezdica pored naziva dodaje u omiljena / izbacuje ([onToggleFavorite]).
 */
@Composable
fun RoomInfoDialog(
    name: String,
    location: String,
    hours: OpeningHours?,
    schedule: RoomSchedule?,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onRoute: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val hasClasses = schedule?.hasClasses(name) == true
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Column(
                    Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(location, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                        FavoriteButton(isFavorite, onToggleFavorite)
                    }
                    hours?.let { OpeningHoursSection(it, Modifier.padding(top = 12.dp)) }
                    if (hasClasses) OccupancySection(name, schedule!!, Modifier.padding(top = 12.dp))
                    if (hours == null && !hasClasses) {
                        Text(
                            stringResource(R.string.room_info_no_data),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.building_info_close)) }
                    if (onRoute != null) {
                        Button(onClick = onRoute) { Text(stringResource(R.string.building_info_route)) }
                    }
                }
            }
        }
    }
}

/** Časovi u sali izabranog dana (strelicama dan po dan); danas i da li je sada zauzeta. */
@Composable
private fun OccupancySection(room: String, schedule: RoomSchedule, modifier: Modifier = Modifier) {
    val now by rememberNow()
    val today = now.toLocalDate()
    var date by rememberSaveable { mutableStateOf(today) }
    val slots = schedule.on(room, date)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.room_info_occupancy), style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { date = date.minusDays(1) }) {
                Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.room_info_previous_day))
            }
            Text(
                dateLabel(date, today),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (date != today) {
                TextButton(onClick = { date = today }) { Text(stringResource(R.string.schedule_today)) }
            }
            IconButton(onClick = { date = date.plusDays(1) }) {
                Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = stringResource(R.string.room_info_next_day))
            }
        }
        if (date == today) {
            val status = roomStatus(slots, now.toLocalTime())
            Text(
                when (status) {
                    is RoomStatus.Busy -> stringResource(R.string.room_info_busy_until, status.until.format(TIME_FORMAT))
                    is RoomStatus.Free -> status.until?.let { stringResource(R.string.room_info_free_until, it.format(TIME_FORMAT)) }
                        ?: stringResource(R.string.room_info_free_rest_of_day)
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (status is RoomStatus.Free) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
        if (slots.isEmpty()) {
            val info = schedule.calendar.dayInfo(date)
            val notes = dayNotes(info, timetableSemester = null).toMutableList()
            if (info.semester != null && info.semester !in schedule.semesters) {
                notes += stringResource(R.string.room_info_semester_missing)
            }
            Text(
                (notes + stringResource(R.string.room_info_no_classes_day)).joinToString("\n"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val ongoing = if (date == today) now.toLocalTime() else null
        slots.forEach { slot ->
            SlotRow(slot, isOngoing = ongoing != null && ongoing >= slot.start && ongoing < slot.end)
        }
        Text(
            stringResource(R.string.room_info_occupancy_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun dateLabel(date: LocalDate, today: LocalDate): String {
    val day = "${dayName(date.dayOfWeek.value)}, ${date.format(SHORT_DATE)}"
    return when (date) {
        today -> "${stringResource(R.string.when_today)} · $day"
        today.plusDays(1) -> "${stringResource(R.string.when_tomorrow)} · $day"
        else -> day
    }
}

/** Vreme, predmet, vrsta časa i rasporedi (smer, godina) u kojima je čas. */
@Composable
private fun SlotRow(slot: RoomSlot, isOngoing: Boolean) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            "${slot.start.format(TIME_FORMAT)}–${slot.end.format(TIME_FORMAT)}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isOngoing) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isOngoing) colors.primary else colors.onSurface,
            modifier = Modifier.width(96.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(slot.subject, style = MaterialTheme.typography.bodyMedium)
            val details = buildList {
                add(classTypeLabel(slot.type))
                if (slot.biweekly) add(stringResource(R.string.schedule_badge_biweekly))
            }
            Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Text(
                slot.timetables.map { "${it.program}, ${stringResource(R.string.schedule_year, it.year)}" }.distinct().joinToString("; "),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
