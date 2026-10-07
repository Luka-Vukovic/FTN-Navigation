package com.example.ftnnavigation.campus

import com.example.ftnnavigation.schedule.ClassType
import com.example.ftnnavigation.schedule.RoomSchedule
import com.example.ftnnavigation.schedule.RoomSlot
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.exp

// Procena gužve između časova iz rasporeda svih smerova. Korisnik (07.10.2026): "ne treba preterivati sa faktorom gužve,
// jer ne dolaze baš svi studenti na predavanja" -> mala posećenost predavanja i umeren najveći faktor.
// SVE VREDNOSTI SU PRETPOSTAVKA (raspored nema broj upisanih studenata ni posećenost).

/** Upisanih po rasporedu (smer i godina) na predavanju, i koliko njih dolazi. */
private const val LECTURE_ENROLLED = 80.0
private const val LECTURE_ATTENDANCE = 0.4

/** Vežbe: jedna grupa, dolazi većina (vežbe se uglavnom boduju). */
private const val GROUP_SIZE = 16.0
private const val EXERCISE_ATTENDANCE = 0.8

/** Čas koji počinje ili se završava ovoliko pre ili posle trenutka puni hodnike. */
const val CROWD_WINDOW_MIN = 10L

/** Najviše ovoliko sporije (+30 %), i broj ljudi u zgradi pri kome je gužva ~63 % od najveće. */
const val CROWD_MAX_EXTRA = 0.3
private const val PEOPLE_SCALE = 400.0

/** Koliko ljudi izlazi iz časa ili ulazi na čas [slot]. Čas svake druge nedelje (ne zna se koje) - pola. */
internal fun peopleOf(slot: RoomSlot): Double {
    val people = if (slot.type == ClassType.PREDAVANJE) {
        LECTURE_ENROLLED * slot.timetables.size.coerceAtLeast(1) * LECTURE_ATTENDANCE
    } else {
        GROUP_SIZE * EXERCISE_ATTENDANCE
    }
    return if (slot.biweekly) people / 2 else people
}

/**
 * Procena ljudi u hodnicima po zgradi u [at]: zbir [peopleOf] za časove koji se završavaju ili počinju do
 * [CROWD_WINDOW_MIN] minuta od [at] (čas koji se završava i sledeći koji počinje se broje oba). Zgrada sale po oznaci iz
 * rasporeda ([buildingOfRoom]); sale bez poznate zgrade (Medicinski fakultet) se ne broje.
 */
fun peopleMoving(schedule: RoomSchedule, at: LocalDateTime): Map<String, Double> {
    val time = at.toLocalTime()
    val result = HashMap<String, Double>()
    for ((room, slots) in schedule.allOn(at.toLocalDate())) {
        val building = buildingOfRoom(room) ?: continue
        for (slot in slots) {
            val events = listOf(slot.start, slot.end).count { near(it, time) }
            if (events > 0) result.merge(building, events * peopleOf(slot), Double::plus)
        }
    }
    return result
}

private fun near(event: LocalTime, time: LocalTime): Boolean = abs(Duration.between(time, event).toMinutes()) <= CROWD_WINDOW_MIN

/** Faktor gužve za broj ljudi: 1 bez gužve, raste sve sporije do 1 + [CROWD_MAX_EXTRA]. */
fun crowdFactor(people: Double): Double = 1 + CROWD_MAX_EXTRA * (1 - exp(-people / PEOPLE_SCALE))

/** Faktori gužve po zgradi u [at] za [com.example.ftnnavigation.graph.RoutingProfile.buildingCrowd]. */
fun crowdFactors(schedule: RoomSchedule, at: LocalDateTime): Map<String, Double> =
    peopleMoving(schedule, at).mapValues { crowdFactor(it.value) }
