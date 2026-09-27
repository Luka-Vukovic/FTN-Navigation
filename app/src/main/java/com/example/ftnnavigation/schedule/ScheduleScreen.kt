package com.example.ftnnavigation.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.ftnnavigation.R
import com.example.ftnnavigation.ui.components.FtnTopAppBar
import java.time.LocalDate

@Composable
fun ScheduleScreen(viewModel: ScheduleViewModel) {
    val data = viewModel.data
    val timetable = viewModel.selectedTimetable
    val group = viewModel.selection?.group
    var showSelection by rememberSaveable { mutableStateOf(false) }

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
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding).fillMaxSize()) {
            when {
                data == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                timetable == null -> EmptySchedule(onChoose = { showSelection = true })
                else -> TimetableContent(timetable, viewModel.myClasses, showGroups = group == null)
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
private fun TimetableContent(timetable: Timetable, classes: List<ClassEntry>, showGroups: Boolean) {
    // Radni dani uvek; vikend samo ako u rasporedu ima nastave.
    val days = (1..5) + classes.map { it.day }.filter { it > 5 }.distinct().sorted()
    val today = LocalDate.now().dayOfWeek.value
    var selectedDay by rememberSaveable { mutableIntStateOf(if (today in days) today else days.first()) }
    val shortNames = stringArrayResource(R.array.days_short)
    val dayClasses = classes.filter { it.day == selectedDay }

    Column(Modifier.fillMaxSize()) {
        PrimaryScrollableTabRow(
            selectedTabIndex = days.indexOf(selectedDay),
            edgePadding = 8.dp,
        ) {
            days.forEach { day ->
                Tab(
                    selected = day == selectedDay,
                    onClick = { selectedDay = day },
                    text = { Text(shortNames[day - 1]) },
                )
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (dayClasses.isEmpty()) {
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
            items(dayClasses) { entry -> ClassCard(entry, showGroups) }
            item { TimetableFooter(timetable) }
        }
    }
}

@Composable
private fun TimetableFooter(timetable: Timetable) {
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (timetable.notes.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.schedule_notes), style = MaterialTheme.typography.titleSmall)
                    timetable.notes.forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        timetable.lastModified?.let {
            Text(
                stringResource(R.string.schedule_last_modified, it),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
