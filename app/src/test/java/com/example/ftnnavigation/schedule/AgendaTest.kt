package com.example.ftnnavigation.schedule

import com.example.ftnnavigation.events.UserEvent
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

/** Pravi raspored (SIIT 7. semestar, grupa 3) po pravom kalendaru 2026/2027, uz sopstvene događaje. */
class AgendaTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val data: ScheduleData = json.decodeFromString(File("src/main/assets/schedule.json").readText())
    private val calendar: AcademicCalendar = json.decodeFromString(File("src/main/assets/calendar.json").readText())
    private val siit7 = data.timetables.single { it.id == "softversko-inzenjerstvo-i-informacione-tehnologije|OAS|7|" }

    private fun agenda(events: List<UserEvent> = emptyList()) =
        Agenda(siit7.classesFor(3), siit7.semesterKind, calendar, events)

    private fun titles(date: String) = agenda().on(LocalDate.parse(date)).map { it.title }

    @Test
    fun next_duringDay_returnsOngoingOrLaterItemToday() {
        // Utorak 29.09.2026. u 10:00 - predavanje Soft kompjuting 09:15-12:00 je u toku.
        val next = agenda().next(LocalDateTime.of(2026, 9, 29, 10, 0)) as AgendaItem.Class
        assertEquals(LocalDate.of(2026, 9, 29), next.date)
        assertEquals("09:15", next.entry.start)
        assertEquals("Soft kompjuting", next.title)
    }

    @Test
    fun next_afterLastClassOfWeek_jumpsToMonday() {
        // Petak 02.10.2026. u 20:00 -> ponedeljak 05.10. (12:30 Soft kompjuting); nedelja je neradna.
        val next = agenda().next(LocalDateTime.of(2026, 10, 2, 20, 0))!!
        assertEquals(LocalDate.of(2026, 10, 5), next.date)
        assertEquals("12:30", next.start.toString())
    }

    @Test
    fun holiday_noClasses_makeupDay_followsReplacedWeekday() {
        val wednesday = titles("2026-11-18")
        assertTrue(wednesday.isNotEmpty())
        assertTrue(titles("2026-11-11").isEmpty()) // Dan primirja (sreda)
        assertEquals(wednesday, titles("2026-11-14")) // subota po rasporedu za sredu
        // Ponedeljak 4. 1. po rasporedu za petak.
        assertEquals(titles("2027-01-15"), titles("2027-01-04"))
    }

    @Test
    fun afterWinterSemester_noMoreClassesOfWinterTimetable() {
        // Izabran je zimski (7.) semestar - u letnjem nema njegovih časova.
        assertNull(agenda().next(LocalDateTime.of(2027, 1, 17, 0, 0)))
        assertTrue(titles("2027-02-09").isEmpty())
    }

    @Test
    fun datedClass_occursOnlyOnItsDate() {
        val block = data.timetables.flatMap { it.classes }.first { it.date != null }
        val date = LocalDate.parse(block.date)
        assertTrue(block.occursOn(date, calendar, SemesterKind.ZIMSKI))
        assertFalse(block.occursOn(date.plusWeeks(1), calendar, SemesterKind.ZIMSKI))
    }

    @Test
    fun weeklyEvent_repeatsUntilInclusive_ignoresAcademicCalendar() {
        val gym = UserEvent(
            id = 1, title = "Teretana", date = "2026-11-04", start = "07:00", end = "08:00",
            repeatWeekly = true, repeatUntil = "2026-11-18",
        )
        val agenda = agenda(listOf(gym))
        fun hasGym(date: String) = agenda.on(LocalDate.parse(date)).any { it is AgendaItem.Event }
        assertFalse(hasGym("2026-10-28"))
        assertTrue(hasGym("2026-11-04"))
        assertTrue(hasGym("2026-11-11")) // praznik ne utiče na sopstvene događaje
        assertTrue(hasGym("2026-11-18"))
        assertFalse(hasGym("2026-11-25"))
        assertFalse(hasGym("2026-11-05")) // drugi dan u nedelji
    }

    @Test
    fun eventsAndClasses_sortedByStart_nextCanBeEvent() {
        // Između predavanja (09:15-12:00) i Računarske grafike (12:15).
        val lunch = UserEvent(id = 2, title = "Ručak", date = "2026-09-29", start = "12:00", end = "12:14", place = "Menza")
        val coffee = UserEvent(id = 3, title = "Kafa", date = "2026-09-29", start = "08:00", end = "08:30")
        val agenda = agenda(listOf(lunch, coffee))
        val day = agenda.on(LocalDate.of(2026, 9, 29))
        assertEquals(day.sortedBy { it.start }, day)
        assertEquals("Kafa", day.first().title)
        assertEquals("Kafa", agenda.next(LocalDateTime.of(2026, 9, 29, 7, 0))!!.title)
        // Predavanje se završilo u 12:00 - sledeći je ručak, pa grafika.
        assertEquals("Ručak", agenda.next(LocalDateTime.of(2026, 9, 29, 12, 1))!!.title)
        assertEquals("Računarska grafika", agenda.next(LocalDateTime.of(2026, 9, 29, 12, 14))!!.title)
    }

    @Test
    fun emptyAgenda_nextIsNull() {
        assertNull(Agenda.EMPTY.next(LocalDateTime.of(2026, 9, 29, 10, 0)))
    }
}
