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

/** Plan dana: rute između stavki redom, pauze i upozorenje kad hod traje duže od pauze. */
class DayPlanTest {

    private val monday = LocalDate.of(2026, 9, 28)

    private fun lecture(start: String, end: String, room: String) = AgendaItem.Class(
        ClassEntry(
            day = 1, start = start, end = end, room = room, type = ClassType.PREDAVANJE,
            subject = "Predmet $room", lecturers = emptyList(),
            groups = Groups("SVI", all = true, elective = false, numbers = emptyList(), areas = emptyList(), biweekly = false),
        ),
        monday,
    )

    private fun event(start: String, end: String, place: String?) = AgendaItem.Event(
        UserEvent(id = start.hashCode().toLong(), title = "Događaj", date = monday.toString(), start = start, end = end, place = place),
        monday,
    )

    private fun minutes(n: Int) = Route(emptyList(), durationSec = n * 60.0, lengthM = 0.0)

    /** Od ulaza 3 min; 101 -> NTP-307 10 min, ostalo 2 min; "Fizika" nije na mapi. */
    private val routes = { from: String?, to: String, _: LocalDateTime ->
        when {
            to == "Fizika" || from == "Fizika" -> null
            from == null -> minutes(3)
            from == "101" && to == "NTP-307" -> minutes(10)
            else -> minutes(2)
        }
    }

    private fun at(time: String) = monday.atTime(LocalTime.parse(time))

    @Test
    fun legsInOrder_firstFromEntrance_thenFromPreviousPlace() {
        val day = listOf(lecture("08:15", "10:00", "101"), lecture("10:15", "12:00", "102"), lecture("12:15", "14:00", "103"))
        val legs = dayPlan(day, routes)
        assertEquals(listOf("101", "102", "103"), legs.map { it.to.place })
        assertEquals(listOf(null, "101", "102"), legs.map { it.from?.place })
        // Prva u danu: bez pauze, najkasnije vreme polaska od glavnog ulaza (kao na Početnoj).
        assertNull(legs[0].breakMin)
        assertEquals(at("08:12"), legs[0].leaveAt)
        assertEquals(listOf(15L, 15L), legs.drop(1).map { it.breakMin })
        assertTrue(legs.none { it.tooTight })
    }

    /** Hod 10 min, a pauza 5 min - upozorenje. */
    @Test
    fun walkLongerThanBreak_tooTight() {
        val day = listOf(lecture("08:15", "10:00", "101"), lecture("10:05", "12:00", "NTP-307"))
        val leg = dayPlan(day, routes)[1]
        assertEquals(5L, leg.breakMin)
        assertEquals(10, leg.route!!.minutes)
        assertTrue(leg.tooTight)
    }

    /** Ista sala dva puta zaredom: nema rute ni upozorenja, ali je stavka u planu. */
    @Test
    fun sameRoom_noRoute() {
        val day = listOf(lecture("08:15", "10:00", "101"), lecture("10:15", "12:00", "101"))
        val leg = dayPlan(day, routes)[1]
        assertTrue(leg.samePlace)
        assertNull(leg.route)
        assertFalse(leg.tooTight)
    }

    /** Događaj bez mesta se preskače, a pauza se računa od prethodne stavke sa mestom. */
    @Test
    fun eventWithoutPlace_skipped_breakFromPreviousPlace() {
        val day = listOf(lecture("08:15", "10:00", "101"), event("10:00", "10:10", place = null), lecture("10:15", "12:00", "102"))
        val legs = dayPlan(day, routes)
        assertEquals(listOf("101", "102"), legs.map { it.to.place })
        assertEquals("101", legs[1].from?.place)
        assertEquals(15L, legs[1].breakMin)
    }

    /** Sala koja nije na mapi: ruta do nje ne postoji, a sledeća kreće od glavnog ulaza (kao obaveštenje). */
    @Test
    fun placeNotOnMap_noRoute_nextFromEntrance() {
        val day = listOf(lecture("08:15", "10:00", "Fizika"), lecture("10:15", "12:00", "102"))
        val legs = dayPlan(day, routes)
        assertNull(legs[0].route)
        assertFalse(legs[0].samePlace)
        assertNull(legs[0].leaveAt)
        assertNull(legs[1].from)
        assertEquals(3, legs[1].route!!.minutes)
        assertEquals(15L, legs[1].breakMin) // pauza je i dalje od kraja Fizike
    }
}
