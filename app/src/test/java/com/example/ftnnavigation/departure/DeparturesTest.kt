package com.example.ftnnavigation.departure

import com.example.ftnnavigation.events.UserEvent
import com.example.ftnnavigation.graph.Route
import com.example.ftnnavigation.schedule.AgendaItem
import com.example.ftnnavigation.schedule.ClassEntry
import com.example.ftnnavigation.schedule.ClassType
import com.example.ftnnavigation.schedule.Groups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class DeparturesTest {

    private val monday = LocalDate.of(2026, 9, 28)

    private fun entry(start: String, end: String, room: String, day: Int = 1) = ClassEntry(
        day = day, start = start, end = end, room = room, type = ClassType.PREDAVANJE,
        subject = "Predmet $room", lecturers = emptyList(),
        groups = Groups("SVI", all = true, elective = false, numbers = emptyList(), areas = emptyList(), biweekly = false),
    )

    private fun event(start: String, end: String, place: String?, notify: Boolean = true) = UserEvent(
        id = start.hashCode().toLong(), title = "Događaj", date = monday.toString(), start = start, end = end,
        place = place, notify = notify,
    )

    /** Stavke dana: nedeljni časovi (bez kalendara - on ima svoj test) i događaji, po početku. */
    private fun agenda(classes: List<ClassEntry>, events: List<UserEvent> = emptyList()): (LocalDate) -> List<AgendaItem> = { date ->
        (classes.filter { it.day == date.dayOfWeek.value }.map { AgendaItem.Class(it, date) } +
            events.filter { it.occursOn(date) }.map { AgendaItem.Event(it, date) }).sortedBy { it.start }
    }

    private fun minutes(n: Int) = Route(emptyList(), durationSec = n * 60.0, lengthM = 0.0)

    /** Od ulaza 3 min do svakog mesta, između mesta 2 min; "Fizika" nije na mapi. */
    private val routes = { from: String?, to: String, _: LocalDateTime ->
        when {
            to == "Fizika" || from == "Fizika" -> null
            from == null -> minutes(3)
            else -> minutes(2)
        }
    }

    private fun at(time: String) = monday.atTime(LocalTime.parse(time))

    private fun next(classes: List<ClassEntry>, after: String, events: List<UserEvent> = emptyList()) =
        nextDeparture(agenda(classes, events), at(after), routes)

    /** Prva stavka u danu: ne zna se gde je korisnik, pa obaveštenje stiže sat pre početka. */
    @Test
    fun firstClassOfDay_fromEntrance_hourBefore() {
        val d = next(listOf(entry("10:15", "12:00", "101")), "07:00")!!
        assertNull(d.from)
        assertTrue(d.firstOfDay)
        assertEquals(at("10:12"), d.leaveAt) // rok od glavnog ulaza ostaje (Početna)
        assertEquals(at("09:15"), d.notifyAt)
    }

    @Test
    fun secondClassOfDay_notHourBefore() {
        val classes = listOf(entry("08:15", "10:00", "101"), entry("10:15", "12:00", "102"))
        val d = next(classes, "08:00")!!
        assertEquals("102", d.item.place)
        assertFalse(d.firstOfDay)
        assertEquals(at("10:08"), d.notifyAt)
    }

    /** Pre prve stavke sa mestom je samo događaj bez mesta - i dalje se ne zna gde je korisnik. */
    @Test
    fun afterEventWithoutPlace_stillFirstOfDay() {
        val d = next(listOf(entry("10:15", "12:00", "101")), "08:30", listOf(event("08:00", "08:30", place = null)))!!
        assertTrue(d.firstOfDay)
        assertEquals(at("09:15"), d.notifyAt)
    }

    /** Ako je pre prve stavke sa obaveštenjem stavka sa mestom (i bez obaveštenja), korisnik je tamo. */
    @Test
    fun afterSilentEventWithPlace_notFirstOfDay() {
        val lunch = event("08:00", "09:00", place = "Menza", notify = false)
        val d = next(listOf(entry("10:15", "12:00", "101")), "07:00", listOf(lunch))!!
        assertFalse(d.firstOfDay)
        assertEquals(at("10:08"), d.notifyAt)
    }

    @Test
    fun backToBack_fromPreviousRoom_notBeforePreviousEnds() {
        val classes = listOf(entry("08:15", "10:00", "101"), entry("10:15", "12:00", "102"))
        val d = next(classes, "07:00")!!
        assertEquals("101", d.item.place)

        val second = nextDeparture(agenda(classes), d.notifyAt, routes)!!
        assertEquals("102", second.item.place)
        assertEquals("101", second.from?.place)
        assertEquals(at("10:13"), second.leaveAt)
        assertEquals(at("10:08"), second.notifyAt)

        // Kratak odmor: 10:05 - 2 min - 5 min = 09:58, ali prethodni čas traje do 10:00.
        val tight = listOf(entry("08:15", "10:00", "101"), entry("10:05", "12:00", "102"))
        assertEquals(at("10:00"), next(tight, "09:00")!!.notifyAt)
    }

    @Test
    fun sameRoomAsPreviousClass_noNotification() {
        val classes = listOf(entry("08:15", "10:00", "101"), entry("10:15", "12:00", "101"))
        val d = next(classes, "09:00")!!
        // Sledeći polazak je tek sledećeg ponedeljka (prvi čas u danu).
        assertEquals(monday.plusWeeks(1), d.date)
        assertEquals(LocalTime.of(8, 15), d.item.start)
    }

    @Test
    fun unknownRoom_firstOfDay_hourBefore_laterAtMargin() {
        val d = next(listOf(entry("10:15", "12:00", "Fizika")), "07:00")!!
        assertNull(d.route)
        assertEquals(at("10:15"), d.leaveAt)
        assertEquals(at("09:15"), d.notifyAt)
        // Nepoznata sala posle druge stavke sa mestom: podsetnik pred početak.
        val later = next(listOf(entry("08:15", "10:00", "101"), entry("10:15", "12:00", "Fizika")), "08:00")!!
        assertNull(later.route)
        assertEquals(at("10:10"), later.notifyAt)
    }

    @Test
    fun unknownPreviousRoom_fallsBackToEntrance() {
        val classes = listOf(entry("08:15", "10:00", "Fizika"), entry("10:30", "12:00", "102"))
        val d = next(classes, "09:00")!!
        assertEquals("102", d.item.place)
        assertNull(d.from)
        assertEquals(at("10:27"), d.leaveAt)
    }

    @Test
    fun passedDeparture_movesToNextDay() {
        val classes = listOf(entry("10:15", "12:00", "101"), entry("09:00", "10:00", "201", day = 2))
        val d = next(classes, "09:16")!!
        assertEquals(monday.plusDays(1), d.date)
        assertEquals(LocalDateTime.of(monday.plusDays(1), LocalTime.of(8, 0)), d.notifyAt)
    }

    /** Početna: polazak za datu stavku, isto kao obaveštenje (i kad je obaveštenje već prošlo). */
    @Test
    fun departureFor_matchesNotification() {
        val classes = listOf(
            entry("08:15", "10:00", "101"), entry("10:15", "12:00", "102"), entry("12:15", "14:00", "102"),
            entry("09:00", "10:00", "201", day = 2),
        )
        val day = agenda(classes)(monday)
        val second = departureFor(day[1], day, routes)!!
        assertEquals("101", second.from?.place)
        assertEquals(at("10:13"), second.leaveAt)
        assertEquals(next(classes, "10:07"), second)
        // Prvi čas u danu (drugi dani se ne mešaju) je od ulaza; ista sala kao prethodni -> null.
        assertNull(departureFor(day[0], day, routes)!!.from)
        assertNull(departureFor(day[2], day, routes))
    }

    @Test
    fun eventWithoutPlace_reminderAtMargin_isNotStartingPoint() {
        val coffee = event("09:00", "09:30", place = null)
        val d = next(emptyList(), "07:00", listOf(coffee))!!
        assertEquals(coffee.title, d.item.title)
        assertNull(d.route)
        assertEquals(at("08:55"), d.notifyAt)
        // Čas posle događaja bez mesta ide od ulaza.
        val lecture = next(listOf(entry("10:15", "12:00", "101")), "09:00", listOf(coffee))!!
        assertNull(lecture.from)
    }

    @Test
    fun silentEvent_skipped_butStillStartingPoint() {
        val lunch = event("08:00", "09:00", place = "Menza", notify = false)
        val d = next(listOf(entry("10:15", "12:00", "101")), "07:00", listOf(lunch))!!
        assertEquals("101", d.item.place)
        assertEquals(lunch.place, d.from?.place)
        assertEquals(at("10:13"), d.leaveAt)
    }

    @Test
    fun eventAfterClass_fromClassRoom_samePlace_noDeparture() {
        val classes = listOf(entry("08:15", "10:00", "101"))
        val meeting = event("10:30", "11:00", place = "Menza")
        val d = next(classes, "09:00", listOf(meeting))!!
        assertEquals("Menza", d.item.place)
        assertEquals("101", d.from?.place)
        val stay = event("10:30", "11:00", place = "101")
        val day = agenda(classes, listOf(stay))(monday)
        assertNull(departureFor(day.last(), day, routes))
    }

    @Test
    fun noItems_noDeparture() {
        assertNull(next(emptyList(), "07:00"))
    }

    private fun missed(classes: List<ClassEntry>, now: String, notifiedUpTo: LocalDateTime, events: List<UserEvent> = emptyList()) =
        missedDeparture(agenda(classes, events), at(now), notifiedUpTo, routes)

    /** Restart u 6:30 bez ponovnog zakazivanja; aplikacija se pokrene tek posle vremena obaveštenja. */
    @Test
    fun missed_shownUntilItemStarts() {
        val classes = listOf(entry("09:15", "11:00", "101"))
        val yesterday = monday.minusDays(1).atTime(12, 0)
        assertNull(missed(classes, "08:10", yesterday)) // obaveštenje (08:15, sat pre) još nije na redu
        assertEquals(at("08:15"), missed(classes, "09:10", yesterday)!!.notifyAt)
        assertNull(missed(classes, "09:15", yesterday)) // čas je počeo
    }

    @Test
    fun missed_alreadyNotified_orSilent_none() {
        val classes = listOf(entry("09:15", "11:00", "101"))
        assertNull(missed(classes, "09:10", at("08:15")))
        val silent = event("09:15", "10:00", place = "Menza", notify = false)
        assertNull(missed(emptyList(), "09:10", at("07:00"), listOf(silent)))
    }

    @Test
    fun missed_several_earliestStart() {
        // Čas 08:15 (obaveštenje 07:15) i događaj 08:16 (obaveštenje 07:16 - pre njega nema završene
        // stavke sa mestom): prvi počinje čas.
        val d = missed(listOf(entry("08:15", "10:00", "101")), "08:10", at("07:00"), listOf(event("08:16", "09:00", "Menza")))!!
        assertEquals("101", d.item.place)
    }
}
