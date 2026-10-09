package com.example.ftnnavigation.poc

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ftnnavigation.R
import com.example.ftnnavigation.campus.AMENITY_UCENJE
import com.example.ftnnavigation.campus.BUILDING_INFO
import com.example.ftnnavigation.campus.CampusData
import com.example.ftnnavigation.campus.NearbyPlace
import com.example.ftnnavigation.campus.OpenStatus
import com.example.ftnnavigation.campus.PlaceKind
import com.example.ftnnavigation.campus.ROOM_HOURS
import com.example.ftnnavigation.campus.nearestPlaces
import com.example.ftnnavigation.graph.BuildingGraph
import com.example.ftnnavigation.graph.Node
import com.example.ftnnavigation.graph.Route
import com.example.ftnnavigation.graph.indoorBuilding
import com.example.ftnnavigation.schedule.rememberNow

@StringRes
internal fun PlaceKind.labelRes(): Int = when (this) {
    PlaceKind.TOALET -> R.string.place_kind_toalet
    PlaceKind.HRANA -> R.string.place_kind_hrana
    PlaceKind.UCENJE -> R.string.place_kind_ucenje
    PlaceKind.SKRIPTARNICA -> R.string.place_kind_skriptarnica
}

/** Naziv mesta za listu i baner rute: sala po nazivu, služba po nazivu zgrade, toalet i sto za učenje po vrsti. */
@Composable
internal fun placeLabel(node: Node, campus: CampusData): String =
    node.name ?: campus.buildings.find { it.nodeId == node.id }?.name
        ?: stringResource(if (node.amenity == AMENITY_UCENJE) R.string.place_study_table else R.string.place_kind_toalet)

/**
 * Mesta vrste [kind] po vremenu hoda odakle kreće ruta na Mapi ([routeTo], [routeStart]) - najbliže prvo, uz zgradu,
 * sprat i radno vreme (gde se zna). Dodir -> ruta do tog mesta ([onRoute]: čvor i naziv za baner).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NearestPlaceSheet(
    kind: PlaceKind,
    graph: BuildingGraph,
    campus: CampusData,
    routeStart: RouteStart,
    routeTo: (String) -> Route?,
    onRoute: (Node, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val places = remember(kind, graph, campus) { nearestPlaces(kind, graph, campus, routeTo) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.navigationBarsPadding()) {
            Text(
                stringResource(kind.labelRes()),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            val from = stringResource(
                when (routeStart) {
                    RouteStart.PDR -> R.string.route_from_position
                    RouteStart.GPS -> R.string.route_from_gps
                    RouteStart.ENTRANCE -> R.string.route_from_entrance
                },
            )
            Text(
                stringResource(R.string.nearby_from, from),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp).padding(top = 2.dp, bottom = 8.dp),
            )
            if (places.isEmpty()) {
                Text(
                    stringResource(R.string.nearby_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
            }
            LazyColumn {
                itemsIndexed(places, key = { _, place -> place.node.id }) { index, place ->
                    val label = placeLabel(place.node, campus)
                    PlaceRow(place, label, campus, isNearest = index == 0, onClick = { onRoute(place.node, label) })
                }
            }
        }
    }
}

@Composable
private fun PlaceRow(place: NearbyPlace, label: String, campus: CampusData, isNearest: Boolean, onClick: () -> Unit) {
    val node = place.node
    val now by rememberNow()
    val hours = node.name?.let(ROOM_HOURS::get)
        ?: campus.buildings.find { it.nodeId == node.id }?.let { BUILDING_INFO[it.id]?.hours }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = if (isNearest) FontWeight.SemiBold else null)
            indoorBuilding(node.buildingId)?.let {
                Text(
                    stringResource(it.locationRes(), floorName(node.floor)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            hours?.let {
                val status = it.status(now)
                Text(
                    openStatusText(status, now),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status is OpenStatus.Open) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                stringResource(R.string.nearby_walk, place.route.minutes),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            if (isNearest) {
                Text(
                    stringResource(R.string.nearby_nearest),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
