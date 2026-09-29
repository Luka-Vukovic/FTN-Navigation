package com.example.ftnnavigation.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.ftnnavigation.R

/** Promena izbora rasporeda (olovka na Rasporedu, ili izbor iz praznog stanja). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleSelectionSheet(
    data: ScheduleData,
    current: Timetable?,
    currentGroup: Int?,
    onSave: (Timetable, Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(stringResource(R.string.selection_title), style = MaterialTheme.typography.titleLarge)
            ScheduleSelectionForm(data, current, currentGroup, stringResource(R.string.selection_save), onSave)
        }
    }
}

/**
 * Izbor: nivo studija -> program -> godina -> modul (ako ih ima više) -> grupa, i dugme
 * [saveLabel]. Svaki nivo se "pomiri" sa ponudom ispod sebe (npr. promena programa zadržava
 * godinu ako postoji), pa nema posebne logike za resetovanje. Bez skrolovanja - to je na
 * ekranu koji je koristi (sheet, uvodni ekran).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScheduleSelectionForm(
    data: ScheduleData,
    current: Timetable?,
    currentGroup: Int?,
    saveLabel: String,
    onSave: (Timetable, Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Nivo je Osnovne / Master; strukovne studije su u istoj listi programa, sa oznakom.
    var level by rememberSaveable { mutableStateOf(current?.degree ?: "OAS") }
    var programKey by rememberSaveable { mutableStateOf(current?.programKey) }
    var year by rememberSaveable { mutableStateOf(current?.year) }
    var timetableId by rememberSaveable { mutableStateOf(current?.id) }
    var group by rememberSaveable { mutableStateOf(currentGroup) }

    val levels = data.timetables.map { it.degree }.distinct().sortedBy { if (it == "OAS") 0 else 1 }
    val forLevel = data.timetables.filter { it.degree == level }
    val programs = forLevel.distinctBy { it.programKey }.sortedWith(compareBy({ it.program }, { it.isVocational }))
    val vocationalLabel = stringResource(R.string.selection_program_vocational)
    // Program se bira svesno (nema podrazumevanog) - do tada nema godine, grupe ni čuvanja.
    val program = programs.find { it.programKey == programKey }
    val forProgram = forLevel.filter { it.programKey == program?.programKey }
    val years = forProgram.map { it.year }.distinct().sorted()
    val selectedYear = year?.takeIf { it in years } ?: years.firstOrNull()
    val modules = forProgram.filter { it.year == selectedYear }
    val timetable = modules.find { it.id == timetableId } ?: modules.firstOrNull()
    val groups = timetable?.groupNumbers.orEmpty()
    val selectedGroup = group?.takeIf { it in groups }
    val areaByGroup = timetable?.areaGroups.orEmpty().flatMap { (area, numbers) -> numbers.map { it to area } }.toMap()

    Column(modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (levels.size > 1) {
            Section(stringResource(R.string.selection_level)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    levels.forEachIndexed { index, value ->
                        SegmentedButton(
                            selected = value == level,
                            onClick = { level = value },
                            shape = SegmentedButtonDefaults.itemShape(index, levels.size),
                        ) {
                            Text(stringResource(if (value == "MAS") R.string.selection_level_mas else R.string.selection_level_oas))
                        }
                    }
                }
            }
        }

        DropdownField(
            label = stringResource(R.string.selection_program),
            options = programs,
            selected = program,
            optionLabel = { if (it.isVocational) vocationalLabel.format(it.program) else it.program },
            onSelect = { programKey = it.programKey },
        )

        if (years.size > 1) {
            Section(stringResource(R.string.selection_year)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    years.forEachIndexed { index, value ->
                        SegmentedButton(
                            selected = value == selectedYear,
                            onClick = { year = value },
                            shape = SegmentedButtonDefaults.itemShape(index, years.size),
                        ) {
                            Text(value.toString())
                        }
                    }
                }
            }
        }

        if (modules.size > 1) {
            DropdownField(
                label = stringResource(R.string.selection_module),
                options = modules,
                selected = timetable,
                optionLabel = { it.module.orEmpty() },
                onSelect = { timetableId = it.id },
            )
        }

        if (groups.isNotEmpty()) {
            Section(stringResource(R.string.selection_group)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = selectedGroup == null,
                        onClick = { group = null },
                        label = { Text(stringResource(R.string.selection_group_all)) },
                    )
                    groups.forEach { number ->
                        FilterChip(
                            selected = selectedGroup == number,
                            onClick = { group = number },
                            label = { Text(areaByGroup[number]?.let { "$number · $it" } ?: number.toString()) },
                        )
                    }
                }
            }
        }

        Button(
            onClick = { if (timetable != null) onSave(timetable, selectedGroup) },
            enabled = timetable != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(saveLabel)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> DropdownField(
    label: String,
    options: List<T>,
    selected: T?,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected?.let(optionLabel).orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}
