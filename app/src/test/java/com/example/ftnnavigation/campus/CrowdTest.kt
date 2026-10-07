package com.example.ftnnavigation.campus

import com.example.ftnnavigation.schedule.AcademicCalendar
import com.example.ftnnavigation.schedule.ClassType
import com.example.ftnnavigation.schedule.RoomSchedule
import com.example.ftnnavigation.schedule.RoomSlot
import com.example.ftnnavigation.schedule.ScheduleData
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/**
 * Gužva između časova ([crowdFactors]) nad pravim rasporedima i kalendarom. Utorak 06.10.2026 (vrednosti iz ispisa pri
 * izradi): NB oko 12:00 ~720 ljudi (+25 %), u 11:30 ~40 (+3 %); najveće u danu +25 %.
 */
class CrowdTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val data = json.decodeFromString<ScheduleData>(File("src/main/assets/schedule.json").readText())
    private val schedule = RoomSchedule(data, json.decodeFromString<AcademicCalendar>(File("src/main/assets/calendar.json").readText()))

    private val tuesday = LocalDate.of(2026, 10, 6)

    private fun slot(type: ClassType, timetables: Int = 1, biweekly: Boolean = false) = RoomSlot(
        LocalTime.of(10, 15), LocalTime.of(12, 0), "Predmet", type, biweekly, timetables = data.timetables.take(timetables),
    )

    /** Predavanje: 80 upisanih po rasporedu, dolazi 40 %; vežbe: grupa od 16, dolazi 80 %; svake druge nedelje - pola. */
    @Test
    fun peopleOfClass_moderateAttendance() {
        assertEquals(32.0, peopleOf(slot(ClassType.PREDAVANJE)), 1e-9)
        assertEquals(64.0, peopleOf(slot(ClassType.PREDAVANJE, timetables = 2)), 1e-9)
        assertEquals(12.8, peopleOf(slot(ClassType.RACUNARSKE_VEZBE)), 1e-9)
        assertEquals(6.4, peopleOf(slot(ClassType.AUDITORNE_VEZBE, biweekly = true)), 1e-9)
    }

    @Test
    fun factor_fromOne_neverAboveMax() {
        assertEquals(1.0, crowdFactor(0.0), 1e-9)
        assertTrue(crowdFactor(100.0) < crowdFactor(400.0))
        assertTrue(crowdFactor(1e6) <= 1 + CROWD_MAX_EXTRA)
    }

    @Test
    fun breakBetweenClasses_crowdedDuringClassesCalm() {
        val noon = crowdFactors(schedule, tuesday.atTime(12, 0)).getValue("NB")
        val during = crowdFactors(schedule, tuesday.atTime(11, 30))["NB"] ?: 1.0
        assertTrue("$noon / $during", noon > 1.15 && during < 1.05)
    }

    @Test
    fun noClasses_noCrowd() {
        assertEquals(emptyMap<String, Double>(), crowdFactors(schedule, tuesday.atTime(3, 0)))
        assertEquals(emptyMap<String, Double>(), crowdFactors(schedule, LocalDate.of(2026, 10, 4).atTime(12, 0))) // nedelja
        assertEquals(emptyMap<String, Double>(), crowdFactors(schedule, LocalDate.of(2026, 11, 11).atTime(12, 0))) // praznik
    }

    @Test
    fun wholeDay_moderate() {
        for (minutes in (7 * 60..21 * 60) step 5) {
            for ((building, factor) in crowdFactors(schedule, tuesday.atTime(minutes / 60, minutes % 60))) {
                assertTrue("$building $minutes", factor in 1.0..(1 + CROWD_MAX_EXTRA))
            }
        }
    }
}
