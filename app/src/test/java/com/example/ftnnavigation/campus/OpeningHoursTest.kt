package com.example.ftnnavigation.campus

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime

/** Radno vreme službi iz [BUILDING_INFO]; 28.09.2026 je ponedeljak, 03.10. subota. */
class OpeningHoursTest {

    private val menza = BUILDING_INFO.getValue("MENZA").hours!!
    private val office = BUILDING_INFO.getValue("SMESTAJ").hours!!
    private val zzzs = BUILDING_INFO.getValue("ZZZS").hours!!

    private fun at(month: Int, day: Int, time: String): LocalDateTime =
        LocalDateTime.of(2026, month, day, 0, 0).with(LocalTime.parse(time))

    @Test
    fun open_untilEndOfCurrentRange() {
        assertEquals(OpenStatus.Open(LocalTime.of(15, 0)), menza.status(at(9, 28, "12:00")))
        assertEquals(OpenStatus.Open(LocalTime.of(9, 0)), menza.status(at(9, 28, "07:00")))
    }

    @Test
    fun endIsExclusive_opensLaterToday() {
        assertEquals(OpenStatus.Closed(at(9, 28, "17:30")), menza.status(at(9, 28, "15:00")))
    }

    @Test
    fun afterLastRange_opensNextDay() {
        assertEquals(OpenStatus.Closed(at(9, 29, "07:00")), menza.status(at(9, 28, "20:00")))
    }

    @Test
    fun weekend_usesSaturdayAndSundayHours() {
        assertEquals(OpenStatus.Closed(at(10, 3, "11:00")), menza.status(at(10, 3, "09:30")))
        assertEquals(OpenStatus.Open(LocalTime.of(14, 30)), menza.status(at(10, 4, "12:00")))
        assertEquals(OpenStatus.Open(LocalTime.of(12, 0)), zzzs.status(at(10, 3, "10:00")))
    }

    @Test
    fun closedOnWeekend_opensMonday() {
        assertEquals(OpenStatus.Closed(at(10, 5, "08:00")), office.status(at(10, 2, "14:00")))
        assertEquals(OpenStatus.Closed(at(10, 5, "08:00")), office.status(at(10, 4, "10:00")))
    }

    @Test
    fun neverOpen() {
        val closed = OpeningHours(emptyList(), emptyList(), emptyList())
        assertEquals(OpenStatus.Closed(null), closed.status(at(9, 28, "10:00")))
    }
}
