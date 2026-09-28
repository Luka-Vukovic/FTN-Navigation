package com.example.ftnnavigation.schedule

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
        // Mora da se poklapa sa DEFAULT_SELECTION u ScheduleStore.
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

}
