package com.example.ftnnavigation.schedule

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.json.Json

/** Izabrani raspored i grupa; [group] null = svi časovi (bez filtera po grupi). */
data class ScheduleSelection(val timetableId: String, val group: Int?)

/**
 * Ugrađeni schedule.json i izbor korisnika (SharedPreferences). Koriste ga [ScheduleViewModel]
 * i obaveštenja o polasku, koja rade i kad aplikacija nije pokrenuta.
 */
class ScheduleStore(context: Context) {
    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences("schedule", Context.MODE_PRIVATE)

    /** Blokira (čita assets) - pozivati van glavne niti. */
    fun loadData(): ScheduleData {
        val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
        return json.decodeFromString(text)
    }

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

/** Časovi izabranog rasporeda filtrirani po izabranoj grupi (prazno ako raspored ne postoji). */
fun ScheduleData.classesFor(selection: ScheduleSelection?): List<ClassEntry> {
    val selection = selection ?: return emptyList()
    return timetables.find { it.id == selection.timetableId }?.classesFor(selection.group).orEmpty()
}
