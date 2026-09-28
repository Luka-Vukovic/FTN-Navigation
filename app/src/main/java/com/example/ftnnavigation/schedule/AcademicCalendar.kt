package com.example.ftnnavigation.schedule

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate

/*
 * Format assets/calendar.json - ručno prepisan zvanični "Kalendar izvođenja nastave" FTN-a
 * (slika, rasporedi/ van gita). Svake školske godine se prepisuje novi.
 */

enum class SemesterKind {
    ZIMSKI, LETNJI;

    companion object {
        /** Neparni semestri studija (1, 3, 5, 7...) su zimski, parni letnji. */
        fun of(semester: Int): SemesterKind = if (semester % 2 == 1) ZIMSKI else LETNJI
    }
}

enum class DayType {
    /** Državni ili verski praznik. */
    NERADNI,

    /** Radni dan bez nastave (npr. Božićni praznici). */
    BEZ_NASTAVE,

    /** Dodatni ispitni rok za studente koji su odslušali nastavu - nema nastave po rasporedu. */
    DODATNI_ROK,
    ISPITNI_ROK,

    /** Nastava po rasporedu drugog dana u nedelji ([CalendarRange.asDay]). */
    NADOKNADA,

    /** Samo obaveštenje (overa semestra, rangiranje) - nastava ide normalno. */
    INFO,
}

@Serializable
data class SemesterPeriod(val kind: SemesterKind, val start: String, val end: String) {
    fun contains(date: LocalDate): Boolean =
        !date.isBefore(LocalDate.parse(start)) && !date.isAfter(LocalDate.parse(end))
}

/** Dan ili niz dana ([from]..[to], uključivo) sa posebnim režimom. */
@Serializable
data class CalendarRange(
    val from: String,
    val to: String? = null,
    val type: DayType,
    val name: String,
    /** Za [DayType.NADOKNADA]: dan u nedelji (1 = ponedeljak) čiji se raspored izvodi. */
    val asDay: Int? = null,
) {
    fun contains(date: LocalDate): Boolean =
        !date.isBefore(LocalDate.parse(from)) && !date.isAfter(LocalDate.parse(to ?: from))
}

/**
 * Šta važi za [date]: [semester] ako je u periodu nastave, [scheduleDay] = dan u nedelji čiji
 * se raspored izvodi (null = nema nastave po rasporedu), [special] = posebni režimi tog dana.
 */
data class DayInfo(
    val date: LocalDate,
    val semester: SemesterKind?,
    val scheduleDay: Int?,
    val special: List<CalendarRange>,
)

@Serializable
data class AcademicCalendar(
    val year: String,
    val source: String,
    val semesters: List<SemesterPeriod>,
    val days: List<CalendarRange>,
) {
    fun dayInfo(date: LocalDate): DayInfo {
        val special = days.filter { it.contains(date) }
        val semester = semesters.find { it.contains(date) }?.kind
        val makeup = special.find { it.type == DayType.NADOKNADA }
        val noTeaching = special.any { it.type != DayType.NADOKNADA && it.type != DayType.INFO }
        val scheduleDay = when {
            semester == null -> null
            makeup != null -> makeup.asDay
            noTeaching || date.dayOfWeek == DayOfWeek.SUNDAY -> null
            else -> date.dayOfWeek.value
        }
        return DayInfo(date, semester, scheduleDay, special)
    }
}

/**
 * Da li se čas izvodi [date]: blok nastava na svoj datum, nedeljni čas u danima nastave
 * semestra [semester] - na praznik ne, a na nadoknadu po rasporedu zamenjenog dana.
 */
fun ClassEntry.occursOn(date: LocalDate, calendar: AcademicCalendar, semester: SemesterKind): Boolean {
    localDate?.let { return it == date }
    val info = calendar.dayInfo(date)
    return info.semester == semester && info.scheduleDay == day
}
