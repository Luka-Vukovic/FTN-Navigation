package com.example.ftnnavigation.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.ftnnavigation.R
import com.example.ftnnavigation.ui.components.FtnTopAppBar
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * Raspored po datumima: nedelja po nedelja, za izabrani dan časovi (po kalendaru nastave) i
 * sopstveni događaji. [onAddEvent] otvara novi događaj za izabrani dan, [onEditEvent] izmenu.
 */
@Composable
fun ScheduleScreen(
    viewModel: ScheduleViewModel,
    onAddEvent: (LocalDate) -> Unit,
    onEditEvent: (Long) -> Unit,
) {
    val data = viewModel.data
    val timetable = viewModel.selectedTimetable
    val group = viewModel.selection?.group
    var showSelection by rememberSaveable { mutableStateOf(false) }
    // Čuva se kao broj dana (LocalDate nije Saveable).
    var selectedDay by rememberSaveable { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    val selectedDate = LocalDate.ofEpochDay(selectedDay)

    Scaffold(
        topBar = {
            FtnTopAppBar(
                title = stringResource(R.string.schedule_title),
                subtitle = timetable?.let { selectionSummary(it, group) },
                actions = {
                    if (timetable != null) {
                        IconButton(onClick = { showSelection = true }) {
                            Icon(
                                painterResource(R.drawable.ic_edit),
                                contentDescription = stringResource(R.string.schedule_change),
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (timetable != null) {
                ExtendedFloatingActionButton(
                    onClick = { onAddEvent(selectedDate) },
                    icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                    text = { Text(stringResource(R.string.event_add)) },
                )
            }
        },
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding).fillMaxSize()) {
            when {
                data == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                timetable == null -> EmptySchedule(onChoose = { showSelection = true })
                else -> AgendaContent(
                    agenda = viewModel.agenda,
                    timetable = timetable,
                    date = selectedDate,
                    onDateChange = { selectedDay = it.toEpochDay() },
                    showGroups = group == null,
                    onEditEvent = onEditEvent,
                )
            }
        }
    }

    if (showSelection && data != null) {
        ScheduleSelectionSheet(
            data = data,
            current = timetable,
            currentGroup = group,
            onSave = { tt, g ->
                viewModel.select(tt, g)
                showSelection = false
            },
            onDismiss = { showSelection = false },
        )
    }
}

/** "4. godina · grupa 3 · Softversko inženjerstvo i ..." - najvažnije prvo, jer se kraj skraćuje. */
@Composable
fun selectionSummary(timetable: Timetable, group: Int?): String = listOfNotNull(
    stringResource(R.string.schedule_year, timetable.year),
    if (group != null) stringResource(R.string.schedule_group, group) else stringResource(R.string.schedule_all_groups),
    timetable.module,
    timetable.program,
).joinToString(" · ")

@Composable
private fun AgendaContent(
    agenda: Agenda,
    timetable: Timetable,
    date: LocalDate,
    onDateChange: (LocalDate) -> Unit,
    showGroups: Boolean,
    onEditEvent: (Long) -> Unit,
) {
    val today = LocalDate.now()
    val monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val week = (0L..6L).map { monday.plusDays(it) }
    val shortNames = stringArrayResource(R.array.days_short)
    val items = agenda.on(date)
    val notes = agenda.calendar?.dayInfo(date)?.let { dayNotes(it, timetable.semesterKind) }.orEmpty()

    Column(Modifier.fillMaxSize()) {
        WeekHeader(
            monday = monday,
            isCurrentWeek = today in week,
            onPrevious = { onDateChange(date.minusWeeks(1)) },
            onNext = { onDateChange(date.plusWeeks(1)) },
            onToday = { onDateChange(today) },
        )
        PrimaryTabRow(selectedTabIndex = date.dayOfWeek.value - 1) {
            week.forEach { day ->
                Tab(
                    selected = day == date,
                    onClick = { onDateChange(day) },
                    text = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(shortNames[day.dayOfWeek.value - 1])
                            Text(
                                day.dayOfMonth.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (day == today) FontWeight.Bold else null,
                            )
                        }
                    },
                )
            }
        }
        LazyColumn(
            // Dole mesto za dugme "Događaj", da ne prekrije poslednju karticu.
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(notes) { DayNote(it) }
            if (items.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.schedule_no_classes),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    )
                }
            }
            items(items) { item ->
                when (item) {
                    is AgendaItem.Class -> ClassCard(item.entry, showGroups)
                    is AgendaItem.Event -> EventCard(item.event, onClick = { onEditEvent(item.event.id) })
                }
            }
            item { TimetableFooter(timetable) }
        }
    }
}

@Composable
private fun WeekHeader(
    monday: LocalDate,
    isCurrentWeek: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious) {
            Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.schedule_week_previous))
        }
        Text(
            "${monday.format(SHORT_DATE)} – ${monday.plusDays(6).format(SHORT_DATE)} ${monday.plusDays(6).year}.",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
        )
        if (!isCurrentWeek) {
            TextButton(onClick = onToday) { Text(stringResource(R.string.schedule_today)) }
        }
        IconButton(onClick = onNext) {
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = stringResource(R.string.schedule_week_next))
        }
    }
}

/**
 * Napomene za dan iz kalendara nastave: praznici, nadoknade, ispitni rokovi, van semestra,
 * ili raspored za drugi semestar ([timetableSemester] null = bez te napomene).
 */
@Composable
internal fun dayNotes(info: DayInfo, timetableSemester: SemesterKind?): List<String> {
    val accusative = stringArrayResource(R.array.days_accusative)
    val notes = info.special.map { range ->
        when (range.type) {
            DayType.NERADNI -> stringResource(R.string.schedule_day_off, range.name)
            DayType.BEZ_NASTAVE -> stringResource(R.string.schedule_no_teaching, range.name)
            DayType.NADOKNADA -> stringResource(R.string.schedule_makeup, range.name, accusative[(range.asDay ?: 1) - 1])
            DayType.DODATNI_ROK, DayType.ISPITNI_ROK, DayType.INFO -> range.name
        }
    }
    val semester = info.semester
    return when {
        semester == null && info.special.isEmpty() -> listOf(stringResource(R.string.schedule_out_of_semester))
        timetableSemester != null && semester != null && semester != timetableSemester -> notes + stringResource(
            R.string.schedule_other_semester,
            stringResource(if (timetableSemester == SemesterKind.ZIMSKI) R.string.semester_winter else R.string.semester_summer),
        )
        else -> notes
    }
}

@Composable
private fun DayNote(text: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun TimetableFooter(timetable: Timetable) {
    timetable.lastModified?.let {
        Text(
            stringResource(R.string.schedule_last_modified, it),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun EmptySchedule(onChoose: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(96.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_calendar),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            )
        }
        Text(
            stringResource(R.string.schedule_empty_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.schedule_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onChoose, modifier = Modifier.padding(top = 8.dp)) {
            Text(stringResource(R.string.schedule_choose))
        }
    }
}
