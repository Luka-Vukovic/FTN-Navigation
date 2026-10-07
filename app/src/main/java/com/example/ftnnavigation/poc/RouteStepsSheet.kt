package com.example.ftnnavigation.poc

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.ftnnavigation.R
import com.example.ftnnavigation.campus.CampusData
import com.example.ftnnavigation.campus.RouteStep
import com.example.ftnnavigation.campus.Side
import com.example.ftnnavigation.campus.StepKind
import kotlin.math.roundToInt

/**
 * Uputstvo korak po korak za rutu ([com.example.ftnnavigation.campus.routeSteps]). Dodir na korak prikazuje njegovo
 * mesto na Mapi ([onShow]: zgrada i sprat, ili kampus). Za vreme praćenja ruta kreće od pozicije, pa je prvi korak
 * uvek sledeći.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RouteStepsSheet(
    steps: List<RouteStep>,
    campus: CampusData,
    onShow: (RouteStep) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.navigationBarsPadding()) {
            Text(
                stringResource(R.string.route_steps_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                stringResource(R.string.route_steps_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp).padding(top = 2.dp, bottom = 8.dp),
            )
            LazyColumn {
                itemsIndexed(steps) { index, step ->
                    StepRow(index + 1, step, campus, onClick = { onShow(step) })
                }
            }
        }
    }
}

@Composable
private fun StepRow(number: Int, step: RouteStep, campus: CampusData, onClick: () -> Unit) {
    val isGoal = step.kind == StepKind.CILJ
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            Modifier.size(28.dp).background(
                if (isGoal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                CircleShape,
            ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                number.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = if (isGoal) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(stepTitle(step, campus), style = MaterialTheme.typography.bodyLarge)
            stepDetail(step)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (step.walkM >= 1.0) {
            Text(
                stringResource(R.string.route_steps_distance, roundedMeters(step.walkM)),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Do 10 m na metar, preko toga na 5 m - graf i PDR nisu tačniji od toga. */
private fun roundedMeters(m: Double): Int = if (m < 10) m.roundToInt().coerceAtLeast(1) else (m / 5).roundToInt() * 5

@Composable
private fun stepTitle(step: RouteStep, campus: CampusData): String {
    val building = step.buildingId?.let { campus.building(it)?.name ?: it }
    val floor = step.floor?.let { floorName(it) }
    return when (step.kind) {
        StepKind.KRENI -> stringResource(R.string.step_start)
        StepKind.IZADJI_IZ_SALE -> stringResource(R.string.step_leave_room, step.name.orEmpty())
        StepKind.LEVO -> stringResource(R.string.step_left)
        StepKind.DESNO -> stringResource(R.string.step_right)
        StepKind.NAZAD -> stringResource(R.string.step_back)
        StepKind.STEPENICE -> stringResource(
            if ((step.floor ?: 0) > (step.fromFloor ?: 0)) R.string.step_stairs_up else R.string.step_stairs_down,
            floor.orEmpty(),
        )
        StepKind.LIFT -> stringResource(R.string.step_lift, floor.orEmpty())
        StepKind.KRAK -> stringResource(R.string.step_flight)
        StepKind.ULAZ -> stringResource(R.string.step_enter, building.orEmpty())
        StepKind.IZLAZ -> building?.let { stringResource(R.string.step_exit, it) } ?: stringResource(R.string.step_exit_outside)
        StepKind.PROLAZ -> stringResource(R.string.step_passage, building.orEmpty())
        StepKind.CILJ -> stringResource(R.string.step_goal, step.name ?: building.orEmpty())
    }
}

/** Drugi red: sprat posle ulaza/prolaza, strana vrata cilja. */
@Composable
private fun stepDetail(step: RouteStep): String? = when (step.kind) {
    StepKind.KRENI -> stringResource(R.string.step_start_detail)
    StepKind.ULAZ -> step.floor?.let { floorName(it) }
    StepKind.PROLAZ -> listOfNotNull(
        step.floor?.let { floorName(it) },
        if (step.steps) stringResource(R.string.step_passage_steps) else null,
    ).joinToString(" · ").ifEmpty { null }
    StepKind.CILJ -> when (step.side) {
        Side.LEVO -> stringResource(R.string.step_goal_left)
        Side.DESNO -> stringResource(R.string.step_goal_right)
        Side.PRAVO -> stringResource(R.string.step_goal_ahead)
        null -> step.floor?.let { floorName(it) }?.takeIf { step.name == null }
    }
    else -> null
}
