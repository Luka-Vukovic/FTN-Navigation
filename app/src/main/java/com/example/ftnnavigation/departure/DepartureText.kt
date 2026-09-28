package com.example.ftnnavigation.departure

import android.content.res.Resources
import com.example.ftnnavigation.R
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
    if (item.place == null) return null
    val route = route ?: return res.getString(R.string.route_not_on_map)
    return when (val from = from) {
        null -> res.getString(R.string.home_route_from_entrance, route.minutes)
        is AgendaItem.Class -> res.getString(R.string.departure_from_room, from.place, route.minutes)
        is AgendaItem.Event -> res.getString(R.string.departure_from_place, from.place, route.minutes)
    }
}

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

/** Stavka i mesto u jednom redu: "Soft kompjuting · 09:15 · NTP-A · Naučno-tehnološki park". */
fun AgendaItem.summary(building: String?): String {
    val place = place?.let { if (building != null && building != it) "$it · $building" else it }
    return listOfNotNull(title, start.format(TIME_FORMAT), place).joinToString(" · ")
}
