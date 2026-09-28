package com.example.ftnnavigation.departure

import com.example.ftnnavigation.graph.Route
import com.example.ftnnavigation.schedule.ClassEntry
import com.example.ftnnavigation.schedule.ClassType
import com.example.ftnnavigation.schedule.Groups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private fun minutes(n: Int) = Route(emptyList(), durationSec = n * 60.0, lengthM = 0.0)

    /** Od ulaza 3 min do svake sale, između sala 2 min; "Fizika" nije na mapi. */
    private val routes = { from: String?, to: String ->
        when {
            to == "Fizika" || from == "Fizika" -> null
            from == null -> minutes(3)
            else -> minutes(2)
        }
    }

    private fun at(time: String) = monday.atTime(LocalTime.parse(time))

    @Test
    fun firstClassOfDay_fromEntrance_withMargin() {
        val d = nextDeparture(listOf(entry("10:15", "12:00", "101")), at("07:00"), routes)!!
        assertNull(d.fromRoom)
        assertEquals(at("10:12"), d.leaveAt)
        assertEquals(at("10:07"), d.notifyAt)
    }

    @Test
    fun backToBack_fromPreviousRoom_notBeforePreviousEnds() {
        val classes = listOf(entry("08:15", "10:00", "101"), entry("10:15", "12:00", "102"))
        val d = nextDeparture(classes, at("07:00"), routes)!!
        assertEquals("101", d.entry.room)

        val second = nextDeparture(classes, d.notifyAt, routes)!!
        assertEquals("102", second.entry.room)
        assertEquals("101", second.fromRoom)
        assertEquals(at("10:13"), second.leaveAt)
        assertEquals(at("10:08"), second.notifyAt)

        // Kratak odmor: 10:05 - 2 min - 5 min = 09:58, ali prethodni čas traje do 10:00.
        val tight = listOf(entry("08:15", "10:00", "101"), entry("10:05", "12:00", "102"))
        assertEquals(at("10:00"), nextDeparture(tight, at("09:00"), routes)!!.notifyAt)
    }

    @Test
    fun sameRoomAsPreviousClass_noNotification() {
        val classes = listOf(entry("08:15", "10:00", "101"), entry("10:15", "12:00", "101"))
        val d = nextDeparture(classes, at("09:00"), routes)!!
        // Sledeći polazak je tek sledećeg ponedeljka (prvi čas u danu).
        assertEquals(monday.plusWeeks(1), d.date)
        assertEquals("08:15", d.entry.start)
    }

    @Test
    fun unknownRoom_reminderAtMargin() {
        val d = nextDeparture(listOf(entry("10:15", "12:00", "Fizika")), at("07:00"), routes)!!
        assertNull(d.route)
        assertEquals(at("10:15"), d.leaveAt)
        assertEquals(at("10:10"), d.notifyAt)
    }

    @Test
    fun unknownPreviousRoom_fallsBackToEntrance() {
        val classes = listOf(entry("08:15", "10:00", "Fizika"), entry("10:30", "12:00", "102"))
        val d = nextDeparture(classes, at("09:00"), routes)!!
        assertEquals("102", d.entry.room)
        assertNull(d.fromRoom)
        assertEquals(at("10:27"), d.leaveAt)
    }

    @Test
    fun passedDeparture_movesToNextDay() {
        val classes = listOf(entry("10:15", "12:00", "101"), entry("09:00", "10:00", "201", day = 2))
        val d = nextDeparture(classes, at("10:08"), routes)!!
        assertEquals(monday.plusDays(1), d.date)
        assertEquals(LocalDateTime.of(monday.plusDays(1), LocalTime.of(8, 52)), d.notifyAt)
    }

    /** Početna: polazak za dati čas, isto kao obaveštenje (i kad je obaveštenje već prošlo). */
    @Test
    fun departureFor_matchesNotification() {
        val classes = listOf(
            entry("08:15", "10:00", "101"), entry("10:15", "12:00", "102"), entry("12:15", "14:00", "102"),
            entry("09:00", "10:00", "201", day = 2),
        )
        val second = departureFor(classes[1], monday, classes, routes)!!
        assertEquals("101", second.fromRoom)
        assertEquals(at("10:13"), second.leaveAt)
        assertEquals(nextDeparture(classes, at("10:07"), routes), second)
        // Prvi čas u danu (drugi dani se ne mešaju) je od ulaza; isti čas kao prethodni -> null.
        assertNull(departureFor(classes[0], monday, classes, routes)!!.fromRoom)
        assertNull(departureFor(classes[2], monday, classes, routes))
    }

    @Test
    fun noClasses_noDeparture() {
        assertNull(nextDeparture(emptyList(), at("07:00"), routes))
    }
}
