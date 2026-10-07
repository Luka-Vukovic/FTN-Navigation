package com.example.ftnnavigation.poc

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.ftnnavigation.R
import com.example.ftnnavigation.departure.PlanLeg
import com.example.ftnnavigation.departure.noRouteText
import com.example.ftnnavigation.schedule.AgendaItem
import com.example.ftnnavigation.schedule.SHORT_DATE
import com.example.ftnnavigation.schedule.TIME_FORMAT
import com.example.ftnnavigation.schedule.dayName
import java.time.LocalDate

/**
 * Plan dana [date]: rute između časova i događaja redom ([legs] iz [com.example.ftnnavigation.departure.dayPlan]) - odakle,
 * dokle, vreme hoda i pauza, upozorenje kad hod traje duže od pauze. Dodir na rutu -> [onShow] (Mapa, ruta po ruta).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DayPlanSheet(
    date: LocalDate,
    legs: List<PlanLeg>,
    onShow: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding()) {
            Text(
                stringResource(R.string.day_plan_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                "${dayName(date.dayOfWeek.value)}, ${date.format(SHORT_DATE)}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                stringResource(if (legs.isEmpty()) R.string.day_plan_empty else R.string.day_plan_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp).padding(top = 4.dp, bottom = 8.dp),
            )
            LazyColumn {
                itemsIndexed(legs) { index, leg -> PlanLegRow(leg, onClick = { onShow(index) }) }
            }
        }
    }
}

@Composable
private fun PlanLegRow(leg: PlanLeg, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            leg.to.start.format(TIME_FORMAT),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(44.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(planLegTitle(leg), style = MaterialTheme.typography.bodyLarge)
            Text(
                leg.to.title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val walk = leg.route?.let { stringResource(R.string.day_plan_walk, it.minutes) }
            Text(
                listOfNotNull(walk ?: planNoRouteText(leg), planTimingText(leg)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = if (leg.tooTight) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "Glavni ulaz → 204", "204 → NTP-307". */
@Composable
internal fun planLegTitle(leg: PlanLeg): String =
    stringResource(R.string.day_plan_leg, leg.from?.place ?: stringResource(R.string.day_plan_entrance), leg.to.place.orEmpty())

/** Odakle ide ruta, za baner uz trajanje: "od glavnog ulaza", "iz sale 204", "od: Menza". */
@Composable
internal fun planFromText(leg: PlanLeg): String = when (val from = leg.from) {
    null -> stringResource(R.string.route_from_entrance)
    is AgendaItem.Class -> stringResource(R.string.route_plan_from_room, from.place)
    is AgendaItem.Event -> stringResource(R.string.route_plan_from_place, from.place.orEmpty())
}

/** Pauza pre stavke (sa upozorenjem kad je hod duži), ili za prvu u danu najkasnije vreme polaska; null bez rute. */
@Composable
internal fun planTimingText(leg: PlanLeg): String? {
    if (leg.route == null) return null
    val breakMin = leg.breakMin
    return when {
        breakMin == null -> leg.leaveAt?.let { stringResource(R.string.day_plan_leave_by, it.format(TIME_FORMAT)) }
        leg.tooTight -> stringResource(R.string.day_plan_break_tight, breakMin.toInt())
        else -> stringResource(R.string.day_plan_break, breakMin.toInt())
    }
}

/** Zašto ruta nema: ista sala / isto mesto kao prethodna stavka, ili mesto nije na mapi (van kampusa). */
@Composable
internal fun planNoRouteText(leg: PlanLeg): String = when {
    leg.samePlace && leg.to is AgendaItem.Class -> stringResource(R.string.home_same_room)
    leg.samePlace -> stringResource(R.string.home_same_place)
    else -> stringResource(noRouteText(leg.to.place.orEmpty()))
}
