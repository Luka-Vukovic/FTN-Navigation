package com.example.ftnnavigation.schedule

import android.content.Context
import androidx.core.content.edit
import com.example.ftnnavigation.events.EventDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Izabrani raspored i grupa; [group] null = svi časovi (bez filtera po grupi). */
data class ScheduleSelection(val timetableId: String, val group: Int?)

/**
 * Ugrađeni schedule.json i calendar.json i izbor korisnika (SharedPreferences). Koriste ga
 * [ScheduleViewModel] i obaveštenja o polasku, koja rade i kad aplikacija nije pokrenuta.
 */
class ScheduleStore(context: Context) {
    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences("schedule", Context.MODE_PRIVATE)

    /** Blokira (čita assets) - pozivati van glavne niti. */
    fun loadData(): ScheduleData = json.decodeFromString(readAsset(ASSET))

    /** Blokira (čita assets) - pozivati van glavne niti. */
    fun loadCalendar(): AcademicCalendar = json.decodeFromString(readAsset(CALENDAR_ASSET))

    /** Časovi izabranog rasporeda i sopstveni događaji (van aplikacije - za obaveštenja). */
    suspend fun loadAgenda(): Agenda = withContext(Dispatchers.IO) {
        val selection = loadSelection()
        val timetable = loadData().timetableFor(selection)
        Agenda(
            classes = timetable?.classesFor(selection?.group).orEmpty(),
            semester = timetable?.semesterKind,
            calendar = loadCalendar(),
            events = EventDatabase.get(context).eventDao().all(),
        )
    }

    private fun readAsset(name: String): String = context.assets.open(name).bufferedReader().use { it.readText() }

    fun loadSelection(): ScheduleSelection? {
        val id = prefs.getString(KEY_TIMETABLE, null) ?: return DEFAULT_SELECTION
        val group = if (prefs.contains(KEY_GROUP)) prefs.getInt(KEY_GROUP, 0) else null
        return ScheduleSelection(id, group)
    }

    fun saveSelection(selection: ScheduleSelection) {
        prefs.edit {
            putString(KEY_TIMETABLE, selection.timetableId)
            if (selection.group != null) putInt(KEY_GROUP, selection.group) else remove(KEY_GROUP)
        }
    }

    private companion object {
        const val ASSET = "schedule.json"
        const val CALENDAR_ASSET = "calendar.json"
        const val KEY_TIMETABLE = "timetable_id"
        const val KEY_GROUP = "group"

        // Dok ne postoji uvodni ekran za izbor: SIIT, 4. godina (7. semestar), grupa 3.
        // TODO: ukloniti kad se doda onboarding - tada bez izbora ide prazno stanje.
        val DEFAULT_SELECTION = ScheduleSelection(
            timetableId = "softversko-inzenjerstvo-i-informacione-tehnologije|OAS|7|",
            group = 3,
        )
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** Izabrani raspored (null ako nije izabran ili više ne postoji). */
fun ScheduleData.timetableFor(selection: ScheduleSelection?): Timetable? =
    selection?.let { sel -> timetables.find { it.id == sel.timetableId } }
