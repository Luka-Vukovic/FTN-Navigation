package com.example.ftnnavigation.departure

import android.content.res.Resources
import androidx.annotation.StringRes
import com.example.ftnnavigation.R
import com.example.ftnnavigation.campus.CampusData
import com.example.ftnnavigation.campus.PlaceLocation
import com.example.ftnnavigation.campus.offCampusPlaceOf
import com.example.ftnnavigation.campus.placeLocation
import com.example.ftnnavigation.graph.BuildingGraph
import com.example.ftnnavigation.poc.locationRes
import com.example.ftnnavigation.schedule.AgendaItem
import com.example.ftnnavigation.schedule.TIME_FORMAT

/*
 * Tekstovi polaska - isti na Početnoj, u podešavanjima obaveštenja i u samom obaveštenju.
 */

/**
 * Odakle je ruta i koliko traje ("Od glavnog ulaza ~4 min", "Iz sale 101 ~2 min", "Od: Menza
 * ~3 min"); null ako stavka nema mesto (događaj bez mesta).
 */
fun Departure.routeText(res: Resources): String? {
    val place = item.place ?: return null
    val route = route ?: return res.getString(noRouteText(place))
    return when (val from = from) {
        null -> res.getString(R.string.home_route_from_entrance, route.minutes)
        is AgendaItem.Class -> res.getString(R.string.departure_from_room, from.place, route.minutes)
        is AgendaItem.Event -> res.getString(R.string.departure_from_place, from.place, route.minutes)
    }
}

/** Zašto nema rute do [place]: van kampusa (Medicinski fakultet) ili sala još nije na mapi. */
@StringRes
fun noRouteText(place: String): Int =
    if (offCampusPlaceOf(place) != null) R.string.route_off_campus else R.string.route_not_on_map

/** "Kreni od glavnog ulaza / iz sale X / od: X najkasnije u HH:MM"; null ako nema rute. */
fun Departure.leaveByText(res: Resources): String? {
    if (route == null) return null
    val time = leaveAt.format(TIME_FORMAT)
    return when (val from = from) {
        null -> res.getString(R.string.home_route_enter_by, time)
        is AgendaItem.Class -> res.getString(R.string.home_route_leave_room_by, from.place, time)
        is AgendaItem.Event -> res.getString(R.string.home_route_leave_place_by, from.place, time)
    }
}

/** Stavka i mesto u jednom redu: "Soft kompjuting · 09:15 · NTP-307 · Naučno-tehnološki park · 3. sprat". */
fun AgendaItem.summary(location: String?): String {
    val place = place?.let { if (location != null && location != it) "$it · $location" else it }
    return listOfNotNull(title, start.format(TIME_FORMAT), place).joinToString(" · ")
}

/** [placeLocation] kao tekst: "Nastavni blok · 2. sprat", "Menza", "Medicinski fakultet"; null ako se ne zna. */
fun placeLocationText(res: Resources, place: String, graph: BuildingGraph, campus: CampusData): String? =
    when (val location = placeLocation(place, graph, campus)) {
        is PlaceLocation.Room -> res.getString(location.plan.locationRes(), floorText(res, location.floor))
        is PlaceLocation.Named -> location.name
        null -> null
    }

/** "suteren" / "prizemlje" / "3. sprat" (kao na Mapi). */
fun floorText(res: Resources, floor: Int): String = when {
    floor < 0 -> res.getString(R.string.floor_basement)
    floor == 0 -> res.getString(R.string.floor_ground)
    else -> res.getString(R.string.floor_number, floor)
}
