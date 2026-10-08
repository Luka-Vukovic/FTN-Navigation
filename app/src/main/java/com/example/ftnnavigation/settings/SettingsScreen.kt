package com.example.ftnnavigation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.ftnnavigation.R
import com.example.ftnnavigation.departure.DepartureSettings
import com.example.ftnnavigation.ui.components.FtnTopAppBar
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Podešavanja aplikacije (podekran sa Početne): mapa (auto-rotacija, dužina koraka), ruta (stepenice / lift) i obaveštenja o polasku
 * ([DepartureSettings]).
 */
@Composable
fun SettingsScreen(
    autoRotateMap: Boolean,
    onAutoRotateMapChange: (Boolean) -> Unit,
    stepLengthM: Float,
    onStepLengthChange: (Float) -> Unit,
    floorChange: FloorChange,
    onFloorChangeChange: (FloorChange) -> Unit,
    crowdRouting: Boolean,
    onCrowdRoutingChange: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            FtnTopAppBar(
                title = stringResource(R.string.settings_title),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.nav_back))
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
            SectionHeader(stringResource(R.string.settings_section_map))
            SettingsSwitchCard(
                title = stringResource(R.string.settings_auto_rotate_title),
                body = stringResource(R.string.settings_auto_rotate_body),
                checked = autoRotateMap,
                onCheckedChange = onAutoRotateMapChange,
            )
            StepLengthCard(stepLengthM, onStepLengthChange)
            SectionHeader(stringResource(R.string.settings_section_route))
            FloorChangeCard(floorChange, onFloorChangeChange)
            SettingsSwitchCard(
                title = stringResource(R.string.settings_crowd_title),
                body = stringResource(R.string.settings_crowd_body),
                checked = crowdRouting,
                onCheckedChange = onCrowdRoutingChange,
            )
            SectionHeader(stringResource(R.string.notifications_title))
            DepartureSettings()
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 4.dp).semantics { heading() },
    )
}

/** Decimalni zarez, dve decimale ("0,75"). */
private fun formatMeters(meters: Float): String = String.format(Locale.forLanguageTag("sr-Latn-RS"), "%.2f", meters)

/**
 * Dužina koraka: klizač na po 5 cm. Dok se vuče, menja se samo prikaz - čuva se kad se pusti (ne upisuje se svaki
 * pomeraj prsta).
 */
@Composable
private fun StepLengthCard(stepLengthM: Float, onChange: (Float) -> Unit) {
    var dragged by remember(stepLengthM) { mutableFloatStateOf(stepLengthM) }
    val shown = snapStepLength(dragged)
    SettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.settings_step_length_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.settings_step_length_value, formatMeters(shown)),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        CardBody(stringResource(R.string.settings_step_length_body))
        Slider(
            value = dragged,
            onValueChange = { dragged = it },
            onValueChangeFinished = { onChange(snapStepLength(dragged)) },
            valueRange = MIN_STEP_LENGTH_M..MAX_STEP_LENGTH_M,
            steps = ((MAX_STEP_LENGTH_M - MIN_STEP_LENGTH_M) / STEP_LENGTH_INCREMENT_M).roundToInt() - 1,
        )
        if (shown != DEFAULT_STEP_LENGTH_M) {
            TextButton(onClick = { onChange(DEFAULT_STEP_LENGTH_M) }, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.settings_step_length_reset, formatMeters(DEFAULT_STEP_LENGTH_M)))
            }
        }
    }
}

/** Kako ruta menja sprat: jedan od tri izbora (dodir na red bira). */
@Composable
private fun FloorChangeCard(selected: FloorChange, onSelect: (FloorChange) -> Unit) {
    SettingsCard {
        Text(stringResource(R.string.settings_floor_change_title), style = MaterialTheme.typography.titleMedium)
        Column(Modifier.selectableGroup()) {
            for (option in FloorChange.entries) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = option == selected, role = Role.RadioButton, onClick = { onSelect(option) })
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = option == selected, onClick = null)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(stringResource(option.labelRes()), style = MaterialTheme.typography.bodyLarge)
                        CardBody(stringResource(option.bodyRes()))
                    }
                }
            }
        }
    }
}

private fun FloorChange.labelRes(): Int = when (this) {
    FloorChange.NAJBRZE -> R.string.settings_floor_change_fastest
    FloorChange.BEZ_STEPENICA -> R.string.settings_floor_change_no_stairs
    FloorChange.BEZ_LIFTA -> R.string.settings_floor_change_no_lift
}

private fun FloorChange.bodyRes(): Int = when (this) {
    FloorChange.NAJBRZE -> R.string.settings_floor_change_fastest_body
    FloorChange.BEZ_STEPENICA -> R.string.settings_floor_change_no_stairs_body
    FloorChange.BEZ_LIFTA -> R.string.settings_floor_change_no_lift_body
}

/** Kartica sa naslovom, opisom i prekidačem; dodir bilo gde na kartici menja prekidač. */
@Composable
internal fun SettingsSwitchCard(title: String, body: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    SettingsCard {
        Row(
            Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                CardBody(body)
            }
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}

@Composable
internal fun SettingsCard(
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
internal fun CardBody(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
