package com.example.ftnnavigation.campus

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.example.ftnnavigation.R
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Opis i slika zgrade za pop-up na mapi kampusa. Nije iz OSM-a, pa nije u campus.json: tekst je u
 * strings.xml, a slike (iz images/, smanjene) u res/drawable-nodpi/building_*.webp.
 */
data class BuildingInfo(
    @StringRes val description: Int,
    /** Null kad slika zgrade još ne postoji. */
    @DrawableRes val image: Int?,
    /** Samo za službe; null = nije poznato. */
    val hours: OpeningHours? = null,
)

/** Od [start] (uključivo) do [end] (isključivo), u istom danu. */
data class TimeRange(val start: LocalTime, val end: LocalTime) {
    operator fun contains(time: LocalTime) = time >= start && time < end
}

/** Radno vreme po tipu dana; prazna lista = ne radi. Praznici se ne gledaju (nije poznato). */
data class OpeningHours(
    val weekday: List<TimeRange>,
    val saturday: List<TimeRange>,
    val sunday: List<TimeRange>,
) {
    fun on(day: DayOfWeek): List<TimeRange> = when (day) {
        DayOfWeek.SATURDAY -> saturday
        DayOfWeek.SUNDAY -> sunday
        else -> weekday
    }

    fun status(now: LocalDateTime): OpenStatus {
        on(now.dayOfWeek).find { now.toLocalTime() in it }?.let { return OpenStatus.Open(it.end) }
        // Sledeće otvaranje: kasnije danas, pa narednih 7 dana.
        on(now.dayOfWeek).firstOrNull { it.start > now.toLocalTime() }
            ?.let { return OpenStatus.Closed(now.toLocalDate().atTime(it.start)) }
        for (days in 1L..7L) {
            val date = now.toLocalDate().plusDays(days)
            on(date.dayOfWeek).firstOrNull()?.let { return OpenStatus.Closed(date.atTime(it.start)) }
        }
        return OpenStatus.Closed(null)
    }
}

sealed interface OpenStatus {
    data class Open(val until: LocalTime) : OpenStatus

    /** [opensAt] null = nikad ne radi. */
    data class Closed(val opensAt: LocalDateTime?) : OpenStatus
}

/** "07:00-09:00" -> [TimeRange]. */
private fun hours(vararg ranges: String): List<TimeRange> = ranges.map { range ->
    val (start, end) = range.split("-")
    TimeRange(LocalTime.parse(start), LocalTime.parse(end))
}

// Radno vreme (korisnik, 29.09.2026).
private val OFFICE_HOURS = OpeningHours(weekday = hours("08:00-14:00"), saturday = emptyList(), sunday = emptyList())

/** Po [CampusBuilding.id]; svaka zgrada sa nazivom treba da ima unos (proverava `CampusGraphTest`). */
val BUILDING_INFO: Map<String, BuildingInfo> = mapOf(
    "NB" to BuildingInfo(R.string.building_info_nb, R.drawable.building_nb),
    "AMF" to BuildingInfo(R.string.building_info_amf, R.drawable.building_amf),
    "F" to BuildingInfo(R.string.building_info_f, R.drawable.building_f),
    "KULA" to BuildingInfo(R.string.building_info_kula, R.drawable.building_kula),
    "ITC" to BuildingInfo(R.string.building_info_itc, R.drawable.building_itc),
    "MI" to BuildingInfo(R.string.building_info_mi, R.drawable.building_mi),
    "NTP" to BuildingInfo(R.string.building_info_ntp, R.drawable.building_ntp),
    "DGG" to BuildingInfo(R.string.building_info_dgg, R.drawable.building_dgg),
    "MENZA" to BuildingInfo(
        R.string.building_info_menza,
        R.drawable.building_menza,
        OpeningHours(
            weekday = hours("07:00-09:00", "11:00-15:00", "17:30-20:00"),
            saturday = hours("07:00-09:00", "11:00-14:30"),
            sunday = hours("07:00-09:00", "11:00-14:30"),
        ),
    ),
    "ZZZS" to BuildingInfo(
        R.string.building_info_zzzs,
        R.drawable.building_zzzs,
        OpeningHours(weekday = hours("07:00-20:00"), saturday = hours("08:00-12:00"), sunday = emptyList()),
    ),
    "SMESTAJ" to BuildingInfo(R.string.building_info_smestaj, R.drawable.building_smestaj, OFFICE_HOURS),
    "ISHRANA" to BuildingInfo(R.string.building_info_ishrana, R.drawable.building_ishrana, OFFICE_HOURS),
)
