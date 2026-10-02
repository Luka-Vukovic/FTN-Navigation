package com.example.ftnnavigation.schedule

import com.example.ftnnavigation.campus.ROOM_HOURS
import com.example.ftnnavigation.graph.INDOOR_BUILDINGS
import com.example.ftnnavigation.graph.IndoorPlan
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/** Zauzetost sala po svim rasporedima (pravi schedule.json) i pravom kalendaru 2026/2027. */
class RoomScheduleTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val data: ScheduleData = json.decodeFromString(File("src/main/assets/schedule.json").readText())
    private val calendar: AcademicCalendar = json.decodeFromString(File("src/main/assets/calendar.json").readText())
    private val schedule = RoomSchedule(data, calendar)

    private val tuesday = LocalDate.of(2026, 9, 29)

    private fun slot(start: String, end: String) =
        RoomSlot(LocalTime.parse(start), LocalTime.parse(end), "Predmet", ClassType.PREDAVANJE, biweekly = false, timetables = emptyList())

    @Test
    fun amphitheatre_allProgramsOnDay_sharedLectureOnce() {
        val slots = schedule.on("A1", tuesday)
        assertEquals(listOf("08:15", "10:15", "12:15", "14:15", "16:15", "18:30"), slots.map { it.start.toString() })
        // Osnovi elektrotehnike 1 je zajedničko predavanje EET-a i MiR-a - jedan termin sa oba rasporeda.
        val shared = slots.single { it.subject == "Osnovi elektrotehnike 1" }
        assertEquals(
            setOf("energetika-elektronika-i-telekomunikacije", "merenje-i-regulacija"),
            shared.timetables.map { it.programId }.toSet(),
        )
    }

    @Test
    fun status_busyUntilEndOfClass_freeUntilNext_freeRestOfDay() {
        val slots = schedule.on("A1", tuesday)
        assertEquals(RoomStatus.Free(LocalTime.of(8, 15)), roomStatus(slots, LocalTime.of(7, 0)))
        assertEquals(RoomStatus.Busy(LocalTime.of(12, 0)), roomStatus(slots, LocalTime.of(11, 0)))
        assertEquals(RoomStatus.Free(LocalTime.of(12, 15)), roomStatus(slots, LocalTime.of(12, 5)))
        assertEquals(RoomStatus.Free(null), roomStatus(slots, LocalTime.of(20, 30)))
    }

    @Test
    fun status_backToBackAndOverlappingClasses_busyUntilLastEnd() {
        val slots = listOf(slot("08:00", "10:00"), slot("09:00", "09:30"), slot("10:00", "12:00"), slot("12:15", "13:00"))
        assertEquals(RoomStatus.Busy(LocalTime.of(12, 0)), roomStatus(slots, LocalTime.of(9, 15)))
        // Kraj časa je isključiv: u 12:00 je slobodna do sledećeg.
        assertEquals(RoomStatus.Free(LocalTime.of(12, 15)), roomStatus(slots, LocalTime.of(12, 0)))
    }

    @Test
    fun aliasFromSchedule_countsForRoomOnPlan() {
        // U rasporedu "O12" (slovo O), na planu NB-a "012".
        assertTrue(schedule.hasClasses("012"))
        assertEquals(2, schedule.on("012", LocalDate.of(2026, 10, 5)).size)
    }

    @Test
    fun calendar_holidayAndSundayEmpty_makeupFollowsReplacedDay() {
        assertEquals(emptyList<RoomSlot>(), schedule.on("A1", LocalDate.of(2026, 11, 11))) // Dan primirja (sreda)
        assertEquals(emptyList<RoomSlot>(), schedule.on("A1", LocalDate.of(2026, 10, 4))) // nedelja
        // Subota 14.11. je nadoknada za sredu 11.11. - kao bilo koja sreda.
        val wednesday = schedule.on("A1", LocalDate.of(2026, 10, 7))
        assertTrue(wednesday.isNotEmpty())
        assertEquals(wednesday, schedule.on("A1", LocalDate.of(2026, 11, 14)))
    }

    @Test
    fun roomHours_everyRoomIsOnSomePlan() {
        val names = INDOOR_BUILDINGS.flatMap { building ->
            IndoorPlan.parse(File("src/main/assets/${building.asset}").readText()).nodes.mapNotNull { it.name }
        }.toSet()
        assertTrue((ROOM_HOURS.keys - names).toString(), names.containsAll(ROOM_HOURS.keys))
    }
}
