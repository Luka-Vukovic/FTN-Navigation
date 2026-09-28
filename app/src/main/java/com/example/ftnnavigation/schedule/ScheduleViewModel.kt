package com.example.ftnnavigation.schedule

import android.app.Application
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ftnnavigation.departure.DepartureScheduler
import com.example.ftnnavigation.events.EventDatabase
import com.example.ftnnavigation.events.UserEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Deljen između Početne i Rasporeda (vezan za aktivnost). Učitava ugrađeni schedule.json i
 * calendar.json, pamti izbor korisnika preko [ScheduleStore] i drži sopstvene događaje.
 */
class ScheduleViewModel(application: Application) : AndroidViewModel(application) {
    private val store = ScheduleStore(application)
    private val eventDao = EventDatabase.get(application).eventDao()

    var data by mutableStateOf<ScheduleData?>(null)
        private set

    var calendar by mutableStateOf<AcademicCalendar?>(null)
        private set

    var selection by mutableStateOf(store.loadSelection())
        private set

    /** Sopstveni događaji; prate bazu. */
    var events by mutableStateOf<List<UserEvent>>(emptyList())
        private set

    val selectedTimetable: Timetable?
        get() = data?.timetableFor(selection)

    /** Časovi izabranog rasporeda (po grupi i kalendaru) i sopstveni događaji. */
    val agenda: Agenda by derivedStateOf {
        val timetable = selectedTimetable
        Agenda(
            classes = timetable?.classesFor(selection?.group).orEmpty(),
            semester = timetable?.semesterKind,
            calendar = calendar,
            events = events,
        )
    }

    init {
        viewModelScope.launch {
            val (data, calendar) = withContext(Dispatchers.IO) { store.loadData() to store.loadCalendar() }
            this@ScheduleViewModel.data = data
            this@ScheduleViewModel.calendar = calendar
        }
        viewModelScope.launch {
            eventDao.observeAll().collect { events = it }
        }
    }

    fun select(timetable: Timetable, group: Int?) {
        val selection = ScheduleSelection(timetable.id, group)
        this.selection = selection
        store.saveSelection(selection)
    }

    fun event(id: Long): UserEvent? = events.find { it.id == id }

    /** Kraj semestra u kome je [date] (predlog kraja nedeljnog ponavljanja), ili null van semestra. */
    fun semesterEnd(date: LocalDate): LocalDate? =
        calendar?.semesters?.find { it.contains(date) }?.let { LocalDate.parse(it.end) }

    /** Novi (id 0) ili izmenjen događaj; obaveštenje se zakazuje iznova. */
    fun saveEvent(event: UserEvent) = changeEvents { eventDao.upsert(event) }

    fun deleteEvent(event: UserEvent) = changeEvents { eventDao.delete(event) }

    private fun changeEvents(change: suspend () -> Unit) {
        viewModelScope.launch {
            change()
            DepartureScheduler.reschedule(getApplication())
        }
    }
}
