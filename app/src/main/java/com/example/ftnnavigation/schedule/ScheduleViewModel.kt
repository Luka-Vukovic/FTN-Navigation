package com.example.ftnnavigation.schedule

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Izabrani raspored i grupa; [group] null = svi časovi (bez filtera po grupi). */
data class ScheduleSelection(val timetableId: String, val group: Int?)

/**
 * Deljen između Početne i Rasporeda (vezan za aktivnost). Učitava ugrađeni schedule.json
 * i pamti izbor korisnika u SharedPreferences.
 */
class ScheduleViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("schedule", Context.MODE_PRIVATE)

    var data by mutableStateOf<ScheduleData?>(null)
        private set

    var selection by mutableStateOf<ScheduleSelection?>(loadSelection())
        private set

    val selectedTimetable: Timetable?
        get() = selection?.let { sel -> data?.timetables?.find { it.id == sel.timetableId } }

    /** Časovi izabranog rasporeda filtrirani po izabranoj grupi. */
    val myClasses: List<ClassEntry>
        get() = selectedTimetable?.classesFor(selection?.group).orEmpty()

    init {
        viewModelScope.launch {
            data = withContext(Dispatchers.IO) {
                val text = application.assets.open(ASSET).bufferedReader().use { it.readText() }
                json.decodeFromString<ScheduleData>(text)
            }
        }
    }

    fun select(timetable: Timetable, group: Int?) {
        selection = ScheduleSelection(timetable.id, group)
        prefs.edit {
            putString(KEY_TIMETABLE, timetable.id)
            if (group != null) putInt(KEY_GROUP, group) else remove(KEY_GROUP)
        }
    }

    private fun loadSelection(): ScheduleSelection? {
        val id = prefs.getString(KEY_TIMETABLE, null) ?: return DEFAULT_SELECTION
        val group = if (prefs.contains(KEY_GROUP)) prefs.getInt(KEY_GROUP, 0) else null
        return ScheduleSelection(id, group)
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
