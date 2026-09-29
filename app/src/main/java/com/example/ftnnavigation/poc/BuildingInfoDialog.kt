package com.example.ftnnavigation.poc

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.ftnnavigation.R
import com.example.ftnnavigation.campus.BUILDING_INFO
import com.example.ftnnavigation.campus.BuildingCategory
import com.example.ftnnavigation.campus.CampusBuilding
import com.example.ftnnavigation.campus.OpenStatus
import com.example.ftnnavigation.campus.OpeningHours
import com.example.ftnnavigation.schedule.TIME_FORMAT
import com.example.ftnnavigation.schedule.rememberNow
import com.example.ftnnavigation.ui.theme.OnService
import java.time.DayOfWeek
import java.time.LocalDateTime

/**
 * Pop-up sa slikom i opisom zgrade (držanje natpisa na mapi kampusa). [onRoute] je null kad
 * ruta nije moguća (graf još nije učitan).
 */
@Composable
fun BuildingInfoDialog(
    building: CampusBuilding,
    onRoute: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val info = BUILDING_INFO[building.id]
    val isService = building.category == BuildingCategory.SLUZBA
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                info?.image?.let {
                    Image(
                        painter = painterResource(it),
                        contentDescription = building.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 10f),
                    )
                }
                Column(
                    Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        stringResource(if (isService) R.string.building_info_category_service else R.string.building_info_category_ftn),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isService) OnService else MaterialTheme.colorScheme.primary,
                    )
                    Text(building.name.orEmpty(), style = MaterialTheme.typography.headlineSmall)
                    if (info != null) {
                        Text(
                            stringResource(info.description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    info?.hours?.let { OpeningHoursSection(it, Modifier.padding(top = 12.dp)) }
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

/** Da li je sada otvoreno, pa radno vreme po tipu dana (današnji red podebljan). */
@Composable
private fun OpeningHoursSection(hours: OpeningHours, modifier: Modifier = Modifier) {
    val now by rememberNow()
    val status = hours.status(now)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.building_info_hours), style = MaterialTheme.typography.titleSmall)
        Text(
            openStatusText(status, now),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (status is OpenStatus.Open) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        val today = when (now.dayOfWeek) {
            DayOfWeek.SATURDAY, DayOfWeek.SUNDAY -> now.dayOfWeek
            else -> DayOfWeek.MONDAY // predstavnik radnih dana
        }
        listOf(
            DayOfWeek.MONDAY to R.string.building_info_hours_weekday,
            DayOfWeek.SATURDAY to R.string.building_info_hours_saturday,
            DayOfWeek.SUNDAY to R.string.building_info_hours_sunday,
        ).forEach { (day, label) ->
            val ranges = hours.on(day)
            val weight = if (day == today) FontWeight.SemiBold else FontWeight.Normal
            Row(Modifier.fillMaxWidth()) {
                Text(
                    stringResource(label),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = weight,
                    modifier = Modifier.width(96.dp),
                )
                Text(
                    if (ranges.isEmpty()) {
                        stringResource(R.string.building_info_hours_closed)
                    } else {
                        ranges.joinToString(", ") { "${it.start.format(TIME_FORMAT)}–${it.end.format(TIME_FORMAT)}" }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = weight,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun openStatusText(status: OpenStatus, now: LocalDateTime): String = when (status) {
    is OpenStatus.Open -> stringResource(R.string.building_info_open_until, status.until.format(TIME_FORMAT))
    is OpenStatus.Closed -> {
        val opensAt = status.opensAt
        val time = opensAt?.format(TIME_FORMAT)
        when {
            opensAt == null -> stringResource(R.string.building_info_never_open)
            opensAt.toLocalDate() == now.toLocalDate() -> stringResource(R.string.building_info_opens_today, time!!)
            opensAt.toLocalDate() == now.toLocalDate().plusDays(1) ->
                stringResource(R.string.building_info_opens_tomorrow, time!!)
            else -> stringResource(
                R.string.building_info_opens_day,
                stringArrayResource(R.array.days_accusative)[opensAt.dayOfWeek.value - 1],
                time!!,
            )
        }
    }
}
