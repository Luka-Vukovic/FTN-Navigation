package com.example.ftnnavigation.departure

import com.example.ftnnavigation.graph.Route
import com.example.ftnnavigation.schedule.AgendaItem
import java.time.LocalDate
import java.time.LocalDateTime

/** Koliko pre poslednjeg trenutka za polazak stiže obaveštenje. */
const val DEPARTURE_MARGIN_MIN = 5L

/**
 * Koliko pre početka prve stavke u danu stiže obaveštenje: korisnik tada može biti bilo gde
 * (kod kuće, van kampusa), pa ruta od glavnog ulaza ne govori kad da krene.
 */
const val FIRST_OF_DAY_NOTICE_MIN = 60L

/**
 * Polazak na čas ili događaj [item]. [leaveAt] = najkasnije vreme polaska (početak - trajanje
 * rute), kao na Početnoj; [notifyAt] = kad stiže obaveštenje (rezerva ranije, ali ne pre kraja
 * prethodne stavke). [from] = prethodna stavka istog dana odakle ide ruta (null = od glavnog
 * ulaza). [route] null = mesto nije na mapi (ili ga nema), pa je obaveštenje samo podsetnik.
 * [firstOfDay] = stavka sa mestom pre koje tog dana nema nijedne stavke sa mestom - obaveštenje
 * stiže [FIRST_OF_DAY_NOTICE_MIN] pre početka.
 */
data class Departure(
    val item: AgendaItem,
    val from: AgendaItem?,
    val route: Route?,
    val leaveAt: LocalDateTime,
    val notifyAt: LocalDateTime,
    val firstOfDay: Boolean = false,
) {
    val date: LocalDate get() = item.date
    val startAt: LocalDateTime get() = item.startAt
}

/**
 * Ruta između dva mesta (fromPlace null = od glavnog ulaza), ili null ako se neko od njih ne zna; [arriveAt] je početak
 * stavke na koju se ide - u to vreme je gužva između časova.
 */
typealias PlaceRoute = (fromPlace: String?, toPlace: String, arriveAt: LocalDateTime) -> Route?

/**
 * Prvi polazak čije obaveštenje stiže posle [after]. [agenda] daje stavke dana, [route] rutu
 * između dva mesta (fromPlace null = od glavnog ulaza), ili null ako se neko od njih ne zna.
 * Događaji sa isključenim obaveštenjem se preskaču, ali i dalje mogu biti mesto polaska.
 */
fun nextDeparture(
    agenda: (LocalDate) -> List<AgendaItem>,
    after: LocalDateTime,
    route: PlaceRoute,
    marginMin: Long = DEPARTURE_MARGIN_MIN,
    horizonDays: Int = 120,
): Departure? {
    for (offset in 0..horizonDays) {
        val day = agenda(after.toLocalDate().plusDays(offset.toLong()))
        // Obaveštenja istog dana ne moraju da idu redom (duža ruta, kraj prethodne stavke).
        val first = day
            .filter { it.notifies }
            .mapNotNull { departureFor(it, day, route, marginMin) }
            .filter { it.notifyAt.isAfter(after) }
            .minByOrNull { it.notifyAt }
        if (first != null) return first
    }
    return null
}

/**
 * Propušten polazak: obaveštenje je trebalo da stigne posle [notifiedUpTo], a do [now] nije
 * stiglo (telefon ugašen, sistem nije pokrenuo aplikaciju posle restarta), a stavka još nije
 * počela - pa obaveštenje stiže odmah. Od više propuštenih, onaj koji najpre počinje.
 */
fun missedDeparture(
    agenda: (LocalDate) -> List<AgendaItem>,
    now: LocalDateTime,
    notifiedUpTo: LocalDateTime,
    route: PlaceRoute,
    marginMin: Long = DEPARTURE_MARGIN_MIN,
): Departure? {
    val day = agenda(now.toLocalDate())
    return day
        .filter { it.notifies && it.startAt.isAfter(now) }
        .mapNotNull { departureFor(it, day, route, marginMin) }
        .filter { it.notifyAt.isAfter(notifiedUpTo) && !it.notifyAt.isAfter(now) }
        .minByOrNull { it.startAt }
}

/** Prethodna stavka sa mestom: poslednja završena do početka [item] (izborni časovi mogu da se preklapaju). */
internal fun previousWithPlace(item: AgendaItem, day: List<AgendaItem>): AgendaItem? =
    day.filter { it != item && it.place != null && it.end <= item.start }.maxByOrNull { it.end }

/**
 * Polazak na [item] (isto računanje kao za obaveštenje - Početna ga prikazuje), ili null ako
 * je prethodna stavka na istom mestu, pa nema kuda da se ide. [day] = stavke tog dana.
 */
fun departureFor(
    item: AgendaItem,
    day: List<AgendaItem>,
    route: PlaceRoute,
    marginMin: Long = DEPARTURE_MARGIN_MIN,
): Departure? {
    val previous = previousWithPlace(item, day)
    val place = item.place
    if (place != null && previous?.place == place) return null // već si tu

    // Ako se ne zna gde je prethodno mesto, ruta ide od glavnog ulaza.
    val fromRoute = previous?.place?.let { route(it, place ?: return@let null, item.startAt) }
    val chosen = fromRoute ?: place?.let { route(null, it, item.startAt) }

    val leaveAt = item.startAt.minusMinutes(chosen?.minutes?.toLong() ?: 0)
    var notifyAt = leaveAt.minusMinutes(marginMin)
    // Obaveštenje usred prethodne stavke ne pomaže - stiže kad se ona završi.
    if (previous != null) notifyAt = maxOf(notifyAt, item.date.atTime(previous.end))
    // Prva stavka sa mestom: ne zna se odakle korisnik kreće. Događaj bez mesta ostaje podsetnik.
    val firstOfDay = place != null && previous == null
    if (firstOfDay) notifyAt = minOf(notifyAt, item.startAt.minusMinutes(FIRST_OF_DAY_NOTICE_MIN))
    return Departure(item, if (fromRoute != null) previous else null, chosen, leaveAt, notifyAt, firstOfDay)
}
