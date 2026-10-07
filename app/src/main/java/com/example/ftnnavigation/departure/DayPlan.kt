package com.example.ftnnavigation.departure

import com.example.ftnnavigation.graph.Route
import com.example.ftnnavigation.schedule.AgendaItem
import java.time.Duration
import java.time.LocalDateTime

/**
 * Jedna ruta plana dana: do stavke [to], sa mesta prethodne stavke [from] (null = od glavnog ulaza - prva u danu, ili se
 * ne zna gde je prethodno mesto), isto kao polazak u obaveštenju ([departureFor]). [route] null = mesto nije na mapi ili
 * je [samePlace] (prethodna stavka je na istom mestu). [breakMin] = pauza od kraja prethodne stavke sa mestom do
 * početka [to] (null - prva u danu). [leaveAt] = najkasnije vreme polaska (null bez rute).
 */
data class PlanLeg(
    val to: AgendaItem,
    val from: AgendaItem?,
    val route: Route?,
    val breakMin: Long?,
    val samePlace: Boolean,
    val leaveAt: LocalDateTime?,
) {
    /** Hod traje duže od pauze između dve stavke - ne stiže se na vreme. */
    val tooTight: Boolean get() = route != null && breakMin != null && route.minutes > breakMin
}

/**
 * Rute dana redom: za svaku stavku sa mestom ([day] = stavke tog dana, kao [com.example.ftnnavigation.schedule.Agenda.on])
 * ruta sa mesta prethodne stavke, kao u obaveštenju. Stavke bez mesta (događaj bez mesta) se preskaču, ali ne prekidaju
 * lanac - pauza se računa od prethodne stavke sa mestom.
 */
fun dayPlan(day: List<AgendaItem>, route: PlaceRoute): List<PlanLeg> =
    day.filter { it.place != null }.map { item ->
        val previous = previousWithPlace(item, day)
        val departure = departureFor(item, day, route)
        PlanLeg(
            to = item,
            from = departure?.from,
            route = departure?.route,
            breakMin = previous?.let { Duration.between(it.end, item.start).toMinutes() },
            samePlace = departure == null,
            leaveAt = departure?.takeIf { it.route != null }?.leaveAt,
        )
    }
