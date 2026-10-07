package com.example.ftnnavigation.schedule

import com.example.ftnnavigation.campus.canonicalRoom
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Čas u sali jednog dana. Isti čas je često u više rasporeda (zajednička predavanja više smerova) -
 * tada je jedan termin sa svim tim rasporedima ([timetables]).
 */
data class RoomSlot(
    val start: LocalTime,
    val end: LocalTime,
    val subject: String,
    val type: ClassType,
    /** Svake druge nedelje - ne znamo koje, pa se prikazuje svake nedelje uz oznaku. */
    val biweekly: Boolean,
    val timetables: List<Timetable>,
)

/** Prostorija slobodna po rasporedu: do [until] (početak sledećeg časa); null = do kraja dana. */
data class FreeRoom(val name: String, val until: LocalTime?)

sealed interface RoomStatus {
    /** Zauzeta do [until] (kraj niza časova koji se nastavljaju jedan na drugi). */
    data class Busy(val until: LocalTime) : RoomStatus

    /** Slobodna do [until] (početak sledećeg časa); null = do kraja dana. */
    data class Free(val until: LocalTime?) : RoomStatus
}

/**
 * Zauzetost sala po časovima iz SVIH rasporeda (ne samo izabranog), po kalendaru nastave. Oznake sala se
 * svode na oznaku sa plana ([canonicalRoom]: O12 -> 012). Ispiti, konsultacije i nadoknade nisu u rasporedima.
 */
class RoomSchedule(data: ScheduleData, val calendar: AcademicCalendar) {
    private val byRoom: Map<String, List<Pair<Timetable, ClassEntry>>> = data.timetables
        .flatMap { timetable -> timetable.classes.map { timetable to it } }
        .groupBy { (_, entry) -> canonicalRoom(entry.room) }

    /** Semestri za koje postoje rasporedi (letnji PDF-ovi izlaze kasnije). */
    val semesters: Set<SemesterKind> = data.timetables.map { it.semesterKind }.toSet()

    /** Da li sala ima ijedan čas u rasporedima (učionica), bez obzira na dan. */
    fun hasClasses(room: String): Boolean = canonicalRoom(room) in byRoom

    /** Časovi svih sala na dan [date] (oznake kao na planu), samo sale koje tog dana imaju časove. */
    fun allOn(date: LocalDate): Map<String, List<RoomSlot>> =
        byRoom.keys.associateWith { on(it, date) }.filterValues { it.isNotEmpty() }

    /** Rasporedi za [date] su učitani (van semestra nastave ih i ne treba); letnji PDF-ovi izlaze kasnije. */
    fun knows(date: LocalDate): Boolean = calendar.dayInfo(date).semester.let { it == null || it in semesters }

    /**
     * Prostorije iz [rooms] koje se pojavljuju u rasporedima (učionice - ne kancelarije, toaleti...) i po rasporedu su u
     * [now] slobodne bar još [minMinutes] minuta. Redosled kao u [rooms]. Raspored ne zna za ispite, konsultacije,
     * nadoknade ni zaključana vrata - slobodna po rasporedu ne znači i stvarno dostupna.
     */
    fun freeRooms(rooms: List<String>, now: LocalDateTime, minMinutes: Long): List<FreeRoom> {
        val time = now.toLocalTime()
        return rooms.filter(::hasClasses).mapNotNull { room ->
            val status = roomStatus(on(room, now.toLocalDate()), time) as? RoomStatus.Free ?: return@mapNotNull null
            val until = status.until
            FreeRoom(room, until).takeIf { until == null || Duration.between(time, until).toMinutes() >= minMinutes }
        }
    }

    /** Časovi u sali [room] na dan [date], po početku. */
    fun on(room: String, date: LocalDate): List<RoomSlot> =
        byRoom[canonicalRoom(room)].orEmpty()
            .filter { (timetable, entry) -> entry.occursOn(date, calendar, timetable.semesterKind) }
            .groupBy { (_, entry) -> SlotKey(entry.startTime, entry.endTime, entry.subject, entry.type) }
            .map { (key, classes) ->
                RoomSlot(
                    start = key.start,
                    end = key.end,
                    subject = key.subject,
                    type = key.type,
                    biweekly = classes.all { (_, entry) -> entry.groups.biweekly },
                    timetables = classes.map { it.first }.distinctBy { it.id },
                )
            }
            .sortedWith(compareBy({ it.start }, { it.end }))

    private data class SlotKey(val start: LocalTime, val end: LocalTime, val subject: String, val type: ClassType)
}

/** Da li je sala zauzeta u [time] po časovima dana [slots] (po početku). */
fun roomStatus(slots: List<RoomSlot>, time: LocalTime): RoomStatus {
    if (slots.none { time >= it.start && time < it.end }) {
        return RoomStatus.Free(slots.filter { it.start > time }.minOfOrNull { it.start })
    }
    // Kraj zauzetosti: časovi koji počinju pre kraja dosadašnjih (preklapaju se ili se nastavljaju) ga produžavaju.
    var until = time
    for (slot in slots) {
        if (slot.start <= until && slot.end > until) until = slot.end
    }
    return RoomStatus.Busy(until)
}
