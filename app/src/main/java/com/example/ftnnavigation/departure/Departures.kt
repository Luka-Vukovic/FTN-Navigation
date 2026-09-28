package com.example.ftnnavigation.departure

import com.example.ftnnavigation.graph.Route
import com.example.ftnnavigation.schedule.ClassEntry
import java.time.LocalDate
import java.time.LocalDateTime

/** Koliko pre poslednjeg trenutka za polazak stiže obaveštenje. */
const val DEPARTURE_MARGIN_MIN = 5L

/**
 * Polazak na čas. [leaveAt] = najkasnije vreme polaska (početak časa - trajanje rute), kao na
 * Početnoj; [notifyAt] = kad stiže obaveštenje (rezerva ranije, ali ne pre kraja prethodnog
 * časa). [fromRoom] null = od glavnog ulaza, inače iz sale prethodnog časa istog dana.
 * [route] null = sala nije na mapi, pa je obaveštenje samo podsetnik pred početak.
 */
data class Departure(
    val entry: ClassEntry,
    val date: LocalDate,
    val fromRoom: String?,
    val route: Route?,
    val leaveAt: LocalDateTime,
    val notifyAt: LocalDateTime,
) {
    val classStart: LocalDateTime get() = date.atTime(entry.startTime)
}

/**
 * Prvi polazak čije obaveštenje stiže posle [after]. [route] daje rutu između dve sale iz
 * rasporeda (fromRoom null = od glavnog ulaza), ili null ako se neka od njih ne zna.
 */
fun nextDeparture(
    classes: List<ClassEntry>,
    after: LocalDateTime,
    route: (fromRoom: String?, toRoom: String) -> Route?,
    marginMin: Long = DEPARTURE_MARGIN_MIN,
    horizonDays: Int = 120,
): Departure? {
    for (offset in 0..horizonDays) {
        val date = after.toLocalDate().plusDays(offset.toLong())
        val day = classes.filter { it.occursOn(date) }
        // Obaveštenja istog dana ne moraju da idu redom časova (duža ruta, kraj prethodnog časa).
        val first = day
            .mapNotNull { departureFor(it, date, day, route, marginMin) }
            .filter { it.notifyAt.isAfter(after) }
            .minByOrNull { it.notifyAt }
        if (first != null) return first
    }
    return null
}

private fun departureFor(
    entry: ClassEntry,
    date: LocalDate,
    day: List<ClassEntry>,
    route: (fromRoom: String?, toRoom: String) -> Route?,
    marginMin: Long,
): Departure? {
    // Prethodni čas: poslednji završen do početka ovog (izborni časovi mogu da se preklapaju).
    val previous = day.filter { it !== entry && it.endTime <= entry.startTime }.maxByOrNull { it.endTime }
    if (previous?.room == entry.room) return null // već si u sali

    // Ako se ne zna gde je prethodna sala, ruta ide od glavnog ulaza.
    val fromRoute = previous?.let { route(it.room, entry.room) }
    val fromRoom = if (fromRoute != null) previous?.room else null
    val chosen = fromRoute ?: route(null, entry.room)

    val start = date.atTime(entry.startTime)
    val leaveAt = start.minusMinutes(chosen?.minutes?.toLong() ?: 0)
    var notifyAt = leaveAt.minusMinutes(marginMin)
    // Obaveštenje usred prethodnog časa ne pomaže - stiže kad se čas završi.
    if (previous != null) notifyAt = maxOf(notifyAt, date.atTime(previous.endTime))
    return Departure(entry, date, fromRoom, chosen, leaveAt, notifyAt)
}
