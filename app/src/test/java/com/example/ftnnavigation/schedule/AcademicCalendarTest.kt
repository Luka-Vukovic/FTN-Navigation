package com.example.ftnnavigation.schedule

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** Pravi assets/calendar.json (kalendar 2026/2027) - proverava i ručni prepis. */
class AcademicCalendarTest {

    private val calendar: AcademicCalendar =
        Json { ignoreUnknownKeys = true }.decodeFromString(File("src/main/assets/calendar.json").readText())

    private fun info(date: String) = calendar.dayInfo(LocalDate.parse(date))

    @Test
    fun ordinaryDays_teachByOwnWeekday_sundayOff() {
        assertEquals(1, info("2026-09-28").scheduleDay) // prvi dan zimskog semestra
        assertEquals(6, info("2026-10-03").scheduleDay) // subota je radna
        assertNull(info("2026-10-04").scheduleDay) // nedelja
        assertEquals(SemesterKind.ZIMSKI, info("2026-10-04").semester)
    }

    @Test
    fun holidaysAndAdditionalExamTerms_noTeaching() {
        for (date in listOf("2026-11-11", "2026-11-07", "2026-12-25", "2027-01-01", "2027-01-07", "2027-02-15", "2027-05-04")) {
            assertNull(date, info(date).scheduleDay)
        }
        // 1-3. 5. su i Vaskršnji praznici i Praznik rada.
        assertEquals(2, info("2027-05-01").special.size)
    }

    @Test
    fun makeupDays_followAnotherWeekday() {
        assertEquals(3, info("2026-11-14").scheduleDay) // subota po rasporedu za sredu (11. 11.)
        assertEquals(5, info("2027-01-04").scheduleDay) // ponedeljak po rasporedu za petak (25. 12.)
        assertEquals(5, info("2027-01-05").scheduleDay) // utorak po rasporedu za petak (8. 1.)
        assertEquals(1, info("2027-02-20").scheduleDay)
        assertEquals(2, info("2027-02-27").scheduleDay)
        assertEquals(5, info("2027-03-24").scheduleDay)
        assertEquals(1, info("2027-03-25").scheduleDay)
    }

    @Test
    fun infoDays_keepTeaching() {
        val overa = info("2026-12-28")
        assertEquals(1, overa.scheduleDay)
        assertEquals(DayType.INFO, overa.special.single().type)
    }

    @Test
    fun outsideSemesters_noTeaching() {
        val exams = info("2027-01-20")
        assertNull(exams.semester)
        assertNull(exams.scheduleDay)
        assertEquals(DayType.ISPITNI_ROK, exams.special.single().type)
        assertEquals(1, info("2027-02-08").scheduleDay) // prvi dan letnjeg semestra
        assertEquals(SemesterKind.LETNJI, info("2027-02-08").semester)
        assertNull(info("2027-06-07").semester)
        assertTrue(info("2027-07-20").special.isEmpty()) // raspust
    }

    @Test
    fun semesterKind_oddWinterEvenSummer() {
        assertEquals(SemesterKind.ZIMSKI, SemesterKind.of(7))
        assertEquals(SemesterKind.LETNJI, SemesterKind.of(8))
    }
}
