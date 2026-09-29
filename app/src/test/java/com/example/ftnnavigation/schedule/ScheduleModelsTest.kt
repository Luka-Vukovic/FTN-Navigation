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
        // Id se čuva u izboru korisnika (ScheduleStore) - promena formata briše sačuvane izbore.
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

    /** Svaki id jednom: isti raspored iz dve verzije PDF-a (PSI -7 i -10) ostaje samo noviji. */
    @Test
    fun timetableIds_unique() {
        val duplicates = data.timetables.groupBy { it.id }.filterValues { it.size > 1 }.keys
        assertTrue(duplicates.toString(), duplicates.isEmpty())
    }

    /** Strukovne studije su poseban nivo, a u izboru idu uz akademske istog stepena. */
    @Test
    fun vocationalStudies_separateFromAcademic() {
        val levels = data.timetables.map { it.level }.toSet()
        assertEquals(setOf("OAS", "OSS", "MAS", "MSS"), levels)
        val electrical = data.timetables.filter { it.programId == "elektrotehnika" }
        assertEquals(setOf("OSS", "MSS"), electrical.map { it.level }.toSet())
        assertEquals(setOf("OAS", "MAS"), electrical.map { it.degree }.toSet())
        assertTrue(electrical.all { it.isVocational })
        assertEquals(2, electrical.map { it.programKey }.distinct().size)
    }

    /** Oblasti mešanih slova (ТиПТ) i napomena "Групе број 1 су уписане на ... - ТиПТ". */
    @Test
    fun areaGroups_mixedCaseAbbreviations() {
        val energy = data.timetables.single { it.programId == "energetika-i-procesna-tehnika" && it.semester == 7 }
        assertEquals(mapOf("TiPT" to listOf(1), "GiNT" to listOf(11)), energy.areaGroups)
        val tipt = energy.classes.first { it.groups.areas == listOf("TiPT") }
        assertTrue(tipt.isFor(1, energy.areaGroups))
        assertFalse(tipt.isFor(11, energy.areaGroups))
    }

    /** Nijedan čas ne sme da ostane bez grupe - uz izabranu grupu ga niko ne bi video. */
    @Test
    fun everyClass_hasRecognizedGroups() {
        val orphans = data.timetables.flatMap { t ->
            t.classes.filter { c -> c.groups.let { !it.all && !it.elective && it.numbers.isEmpty() && it.areas.isEmpty() } }
                .map { "${t.id}: ${it.groups.raw}" }
        }
        assertTrue(orphans.toString(), orphans.isEmpty())
    }

}
