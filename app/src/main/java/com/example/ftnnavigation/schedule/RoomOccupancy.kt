package com.example.ftnnavigation.schedule

import com.example.ftnnavigation.campus.canonicalRoom
import java.time.LocalDate
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
