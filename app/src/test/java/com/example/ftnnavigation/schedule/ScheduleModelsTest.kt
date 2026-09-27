package com.example.ftnnavigation.schedule

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

class ScheduleModelsTest {

    /** Pravi generisani fajl - hvata i neslaganje formata skripte i modela. */
    private val data: ScheduleData by lazy {
        Json { ignoreUnknownKeys = true }
            .decodeFromString(File("src/main/assets/schedule.json").readText())
    }

    private val siit7 by lazy {
        data.timetables.single { it.programId == "softversko-inzenjerstvo-i-informacione-tehnologije" && it.semester == 7 }
    }

    @Test
    fun asset_decodesAllTimetables() {
        assertTrue(data.timetables.size >= 30)
        assertTrue(data.timetables.all { it.classes.isNotEmpty() })
    }

    @Test
    fun siit7_matchesPdf() {
        // Strana 5 PDF-a: 30 časova, grupe 1-5.
        assertEquals(30, siit7.classes.size)
        // Mora da se poklapa sa DEFAULT_SELECTION u ScheduleViewModel.
        assertEquals("softversko-inzenjerstvo-i-informacione-tehnologije|OAS|7|", siit7.id)
        assertEquals(4, siit7.year)
        assertEquals(listOf(1, 2, 3, 4, 5), siit7.groupNumbers)
    }

    @Test
    fun groupFilter_keepsLecturesForAllAndOwnExercises() {
        val group3 = siit7.classesFor(3)
        assertTrue(group3.all { it.groups.all || 3 in it.groups.numbers })
        // 5 predavanja za sve (uto 2, čet 3) + 5 vežbi grupe 3 (pon 1, sre 3, pet 1).
        assertEquals(5, group3.count { it.groups.all })
        assertEquals(5, group3.count { !it.groups.all })
        assertEquals(siit7.classes.size, siit7.classesFor(null).size)
    }

    @Test
    fun areaGroups_mapMasterAreasToGroupNumbers() {
        val master = data.timetables.single {
            it.programId == "softversko-inzenjerstvo-i-informacione-tehnologije" && it.level == "MAS"
        }
        val ep = master.classes.first { it.groups.areas == listOf("EP") }
        assertTrue(ep.isFor(15, master.areaGroups))
        assertFalse(ep.isFor(25, master.areaGroups))
    }

    @Test
    fun nextClass_duringDay_returnsOngoingOrLaterClassToday() {
        // Utorak 29.09.2026. u 10:00 - grupa 3 ima predavanje Soft kompjuting 09:15-12:00 (u toku).
        val next = nextClass(siit7.classesFor(3), LocalDateTime.of(2026, 9, 29, 10, 0))
        assertNotNull(next)
        assertEquals(LocalDate.of(2026, 9, 29), next!!.date)
        assertEquals("09:15", next.entry.start)
        assertEquals("Soft kompjuting", next.entry.subject)
    }

    @Test
    fun nextClass_afterLastClassOfWeek_jumpsToMonday() {
        // Petak 02.10.2026. u 20:00 -> ponedeljak 05.10. prvi čas grupe 3 (12:30 Soft kompjuting).
        val next = nextClass(siit7.classesFor(3), LocalDateTime.of(2026, 10, 2, 20, 0))!!
        assertEquals(LocalDate.of(2026, 10, 5), next.date)
        assertEquals("12:30", next.entry.start)
    }

    @Test
    fun datedClass_occursOnlyOnItsDate() {
        val block = data.timetables.flatMap { it.classes }.first { it.date != null }
        val date = LocalDate.parse(block.date)
        assertTrue(block.occursOn(date))
        assertFalse(block.occursOn(date.plusWeeks(1)))
    }

    @Test
    fun nextClass_emptySchedule_isNull() {
        assertNull(nextClass(emptyList(), LocalDateTime.of(2026, 9, 29, 10, 0)))
    }
}
