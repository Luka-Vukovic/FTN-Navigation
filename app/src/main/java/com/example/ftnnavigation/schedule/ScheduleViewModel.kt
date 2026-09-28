package com.example.ftnnavigation.schedule

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Deljen između Početne i Rasporeda (vezan za aktivnost). Učitava ugrađeni schedule.json
 * i pamti izbor korisnika preko [ScheduleStore].
 */
class ScheduleViewModel(application: Application) : AndroidViewModel(application) {
    private val store = ScheduleStore(application)

    var data by mutableStateOf<ScheduleData?>(null)
        private set

    var selection by mutableStateOf(store.loadSelection())
        private set

    val selectedTimetable: Timetable?
        get() = selection?.let { sel -> data?.timetables?.find { it.id == sel.timetableId } }

    /** Časovi izabranog rasporeda filtrirani po izabranoj grupi. */
    val myClasses: List<ClassEntry>
        get() = data?.classesFor(selection).orEmpty()

    init {
        viewModelScope.launch {
            data = withContext(Dispatchers.IO) { store.loadData() }
        }
    }

    fun select(timetable: Timetable, group: Int?) {
        val selection = ScheduleSelection(timetable.id, group)
        this.selection = selection
        store.saveSelection(selection)
    }
}
