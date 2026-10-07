package com.example.ftnnavigation.events

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.ftnnavigation.R
import com.example.ftnnavigation.graph.Node
import com.example.ftnnavigation.poc.DestinationSheet
import com.example.ftnnavigation.schedule.TIME_FORMAT
import com.example.ftnnavigation.schedule.dayName
import com.example.ftnnavigation.ui.components.FtnTopAppBar
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Mesta koja se nude za događaj - ista kao odredišta na Mapi. */
data class PlaceOptions(
    val buildings: List<String>,
    val services: List<String>,
    val rooms: List<String>,
    val roomBuildings: Map<String, List<String>>,
    val roomNodes: Map<String, Node>,
    /** Omiljena i nedavna odredišta - na vrhu izbora, kao na Mapi. */
    val favorites: List<String>,
    val recents: List<String>,
    val onToggleFavorite: (String) -> Unit,
)

/**
 * Novi ([event] null, za dan [initialDate]) ili postojeći događaj. [defaultUntil] = predlog
 * kraja nedeljnog ponavljanja (kraj semestra). [places] null dok se mapa učitava.
 */
@Composable
fun EventEditScreen(
    event: UserEvent?,
    initialDate: LocalDate,
    defaultUntil: LocalDate,
    places: PlaceOptions?,
    onSave: (UserEvent) -> Unit,
    onDelete: (UserEvent) -> Unit,
    onBack: () -> Unit,
) {
    // Stanje u stringovima (ISO) - preživljava rotaciju bez posebnog Saver-a.
    var title by rememberSaveable { mutableStateOf(event?.title.orEmpty()) }
    var date by rememberSaveable { mutableStateOf(event?.date ?: initialDate.toString()) }
    var start by rememberSaveable { mutableStateOf(event?.start ?: DEFAULT_START) }
    var end by rememberSaveable { mutableStateOf(event?.end ?: DEFAULT_END) }
    var place by rememberSaveable { mutableStateOf(event?.place) }
    var note by rememberSaveable { mutableStateOf(event?.note.orEmpty()) }
    var repeatWeekly by rememberSaveable { mutableStateOf(event?.repeatWeekly ?: false) }
    var repeatUntil by rememberSaveable { mutableStateOf(event?.repeatUntil ?: defaultUntil.toString()) }
    var notify by rememberSaveable { mutableStateOf(event?.notify ?: true) }
    var triedSave by rememberSaveable { mutableStateOf(false) }
    var picker by rememberSaveable { mutableStateOf<Picker?>(null) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    val titleError = title.isBlank()
    val timeError = LocalTime.parse(end) <= LocalTime.parse(start)
    val untilError = repeatWeekly && LocalDate.parse(repeatUntil) < LocalDate.parse(date)

    Scaffold(
        topBar = {
            FtnTopAppBar(
                title = stringResource(if (event == null) R.string.event_new_title else R.string.event_edit_title),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.nav_back))
                    }
                },
                actions = {
                    if (event != null) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.event_delete))
                        }
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(R.string.event_field_title)) },
                singleLine = true,
                isError = triedSave && titleError,
                supportingText = if (triedSave && titleError) {
                    { Text(stringResource(R.string.event_error_title)) }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth(),
            )
            PickerField(stringResource(R.string.event_field_date), dateLabel(LocalDate.parse(date))) { picker = Picker.DATE }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PickerField(stringResource(R.string.event_field_start), start, Modifier.weight(1f)) { picker = Picker.START }
                PickerField(
                    stringResource(R.string.event_field_end),
                    end,
                    Modifier.weight(1f),
                    error = if (timeError) stringResource(R.string.event_error_time) else null,
                ) { picker = Picker.END }
            }
            PickerField(stringResource(R.string.event_field_place), place ?: stringResource(R.string.event_no_place)) {
                picker = Picker.PLACE
            }
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text(stringResource(R.string.event_field_note)) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            SwitchRow(stringResource(R.string.event_repeat), repeatWeekly) { repeatWeekly = it }
            if (repeatWeekly) {
                PickerField(
                    stringResource(R.string.event_repeat_until),
                    dateLabel(LocalDate.parse(repeatUntil)),
                    error = if (untilError) stringResource(R.string.event_error_until) else null,
                ) { picker = Picker.UNTIL }
            }
            SwitchRow(stringResource(R.string.event_notify), notify, hint = stringResource(R.string.event_notify_hint)) { notify = it }
            Button(
                onClick = {
                    triedSave = true
                    if (titleError || timeError || untilError) return@Button
                    onSave(
                        UserEvent(
                            id = event?.id ?: 0,
                            title = title.trim(),
                            date = date,
                            start = start,
                            end = end,
                            place = place,
                            note = note.trim().ifEmpty { null },
                            repeatWeekly = repeatWeekly,
                            repeatUntil = if (repeatWeekly) repeatUntil else null,
                            notify = notify,
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text(stringResource(R.string.event_save))
            }
        }
    }

    when (picker) {
        Picker.DATE -> DateDialog(LocalDate.parse(date), onDismiss = { picker = null }) {
            // Kraj ponavljanja ne sme pre početka - pomera se zajedno sa datumom.
            if (LocalDate.parse(repeatUntil) < it) repeatUntil = it.toString()
            date = it.toString()
            picker = null
        }
        Picker.UNTIL -> DateDialog(LocalDate.parse(repeatUntil), onDismiss = { picker = null }) {
            repeatUntil = it.toString()
            picker = null
        }
        Picker.START -> TimeDialog(LocalTime.parse(start), onDismiss = { picker = null }) {
            // Trajanje ostaje isto kad se pomeri početak.
            val length = Duration.between(LocalTime.parse(start), LocalTime.parse(end))
            start = it.format(TIME_FORMAT)
            if (!length.isNegative && !length.isZero && it.plus(length) > it) end = it.plus(length).format(TIME_FORMAT)
            picker = null
        }
        Picker.END -> TimeDialog(LocalTime.parse(end), onDismiss = { picker = null }) {
            end = it.format(TIME_FORMAT)
            picker = null
        }
        Picker.PLACE -> if (places != null) {
            DestinationSheet(
                buildings = places.buildings,
                services = places.services,
                rooms = places.rooms,
                roomBuildings = places.roomBuildings,
                roomNodes = places.roomNodes,
                favorites = places.favorites,
                recents = places.recents,
                onToggleFavorite = places.onToggleFavorite,
                selected = place,
                onSelect = {
                    place = it
                    picker = null
                },
                onDismiss = { picker = null },
                title = stringResource(R.string.event_field_place),
                noneLabel = stringResource(R.string.event_no_place),
            )
        }
        null -> Unit
    }

    if (confirmDelete && event != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.event_delete_confirm, event.title)) },
            text = if (event.repeatWeekly) {
                { Text(stringResource(R.string.event_delete_repeat_note)) }
            } else {
                null
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete(event)
                }) { Text(stringResource(R.string.event_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.dialog_cancel)) }
            },
        )
    }
}

private enum class Picker { DATE, START, END, PLACE, UNTIL }

private const val DEFAULT_START = "10:00"
private const val DEFAULT_END = "11:00"
private val DATE_FORMAT = DateTimeFormatter.ofPattern("d. M. yyyy.")

@Composable
private fun dateLabel(date: LocalDate): String = "${dayName(date.dayOfWeek.value)}, ${date.format(DATE_FORMAT)}"

/** Polje koje se ne kuca, već otvara izbor (datum, vreme, mesto). */
@Composable
private fun PickerField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    onClick: () -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        if (error != null) {
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, hint: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (hint != null) {
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // Klik obrađuje ceo red (toggleable).
        Switch(checked = checked, onCheckedChange = null)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateDialog(initial: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    // DatePicker radi sa ponoći u UTC.
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.toEpochDay() * MS_PER_DAY)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(LocalDate.ofEpochDay(it / MS_PER_DAY)) } ?: onDismiss()
            }) { Text(stringResource(R.string.dialog_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
    ) {
        DatePicker(state)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(initial: LocalTime, onDismiss: () -> Unit, onPick: (LocalTime) -> Unit) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)) }) { Text(stringResource(R.string.dialog_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
        text = { TimePicker(state) },
    )
}

private const val MS_PER_DAY = 24 * 60 * 60 * 1000L
