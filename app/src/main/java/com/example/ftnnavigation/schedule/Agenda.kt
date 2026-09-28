package com.example.ftnnavigation.schedule

import com.example.ftnnavigation.events.UserEvent
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Stavka jednog dana: čas iz rasporeda ili sopstveni događaj. */
sealed interface AgendaItem {
    val date: LocalDate
    val start: LocalTime
    val end: LocalTime
    val title: String

    /** Sala ili zgrada (odredište rute); null = događaj bez mesta. */
    val place: String?

    /** Da li za stavku stiže "Kreni sada" obaveštenje. */
    val notifies: Boolean

    val startAt: LocalDateTime get() = date.atTime(start)

    data class Class(val entry: ClassEntry, override val date: LocalDate) : AgendaItem {
        override val start: LocalTime get() = entry.startTime
        override val end: LocalTime get() = entry.endTime
        override val title: String get() = entry.subject
        override val place: String get() = entry.room
        override val notifies: Boolean get() = true
    }

    data class Event(val event: UserEvent, override val date: LocalDate) : AgendaItem {
        override val start: LocalTime get() = event.startTime
        override val end: LocalTime get() = event.endTime
        override val title: String get() = event.title
        override val place: String? get() = event.place
        override val notifies: Boolean get() = event.notify
    }
}

/**
 * Sve što korisnik ima u danu: časovi izabranog rasporeda (samo u dane nastave po [calendar],
 * za semestar [semester]) i sopstveni događaji. Koriste je Raspored, Početna i obaveštenja.
 */
class Agenda(
    private val classes: List<ClassEntry>,
    private val semester: SemesterKind?,
    val calendar: AcademicCalendar?,
    private val events: List<UserEvent>,
) {
    /** Stavke dana po vremenu početka (čas pre događaja u isto vreme). */
    fun on(date: LocalDate): List<AgendaItem> {
        val dayClasses = if (calendar != null && semester != null) {
            classes.filter { it.occursOn(date, calendar, semester) }.map { AgendaItem.Class(it, date) }
        } else {
            emptyList()
        }
        val dayEvents = events.filter { it.occursOn(date) }.map { AgendaItem.Event(it, date) }
        return (dayClasses + dayEvents).sortedBy { it.start }
    }

    /**
     * Prva stavka koja još nije završena, počev od [now] (stavka u toku se računa kao sledeća).
     * [horizonDays] pokriva i raspust između semestara.
     */
    fun next(now: LocalDateTime, horizonDays: Int = 120): AgendaItem? {
        val today = now.toLocalDate()
        for (offset in 0..horizonDays) {
            val date = today.plusDays(offset.toLong())
            val first = on(date).firstOrNull { offset > 0 || it.end > now.toLocalTime() }
            if (first != null) return first
        }
        return null
    }

    companion object {
        val EMPTY = Agenda(emptyList(), null, null, emptyList())
    }
}
