package com.example.ftnnavigation.widget

import com.example.ftnnavigation.departure.Departure
import com.example.ftnnavigation.graph.Route
import com.example.ftnnavigation.schedule.AgendaItem
import com.example.ftnnavigation.schedule.ClassEntry
import com.example.ftnnavigation.schedule.ClassType
import com.example.ftnnavigation.schedule.Groups
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/** Widget "Sledeće" se osvežava u prvom budućem trenutku kad se njegov tekst menja. */
class WidgetRefreshTest {

    private val monday = LocalDate.of(2026, 9, 28)

    private val lecture = AgendaItem.Class(
        ClassEntry(
            day = 1, start = "10:15", end = "12:00", room = "101", type = ClassType.PREDAVANJE,
            subject = "Predmet", lecturers = emptyList(),
            groups = Groups("SVI", all = true, elective = false, numbers = emptyList(), areas = emptyList(), biweekly = false),
        ),
        monday,
    )

    private fun at(time: String) = monday.atTime(LocalTime.parse(time))

    private val departure = Departure(
        item = lecture,
        from = null,
        route = Route(emptyList(), durationSec = 180.0, lengthM = 200.0),
        leaveAt = at("10:12"),
        notifyAt = at("09:15"),
    )

    @Test
    fun beforeLeaveAt_refreshAtLeaveAt() {
        assertEquals(at("10:12"), widgetRefreshAt(at("08:00"), lecture, departure))
    }

    @Test
    fun afterLeaveAt_refreshAtStart_thenAtEnd() {
        assertEquals(at("10:15"), widgetRefreshAt(at("10:13"), lecture, departure))
        assertEquals(at("12:00"), widgetRefreshAt(at("10:20"), lecture, departure))
    }

    /** Bez rute (sala van mape) rok za polazak se ne prikazuje, pa ni ne osvežava. */
    @Test
    fun withoutRoute_noLeaveAtRefresh() {
        assertEquals(at("10:15"), widgetRefreshAt(at("08:00"), lecture, departure.copy(route = null)))
    }

    /** Sledeća stavka je tek sutra ili je nema: u ponoć ("Sutra" postaje "Danas"). */
    @Test
    fun itemOnAnotherDay_orNone_refreshAtMidnight() {
        val evening = monday.minusDays(1).atTime(20, 0)
        assertEquals(monday.atStartOfDay(), widgetRefreshAt(evening, lecture, departure))
        assertEquals(monday.plusDays(1).atStartOfDay(), widgetRefreshAt(at("13:00"), null, null))
    }
}
