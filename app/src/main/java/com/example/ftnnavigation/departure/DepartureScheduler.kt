package com.example.ftnnavigation.departure

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import com.example.ftnnavigation.R
import com.example.ftnnavigation.campus.buildingOfRoom
import com.example.ftnnavigation.campus.loadCampus
import com.example.ftnnavigation.campus.loadGraph
import com.example.ftnnavigation.campus.resolveTarget
import com.example.ftnnavigation.campus.routeBetween
import com.example.ftnnavigation.schedule.ScheduleStore
import com.example.ftnnavigation.schedule.classesFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Zakazuje jedan alarm - za sledeći polazak ([nextDeparture]). Kad alarm okine,
 * [DepartureAlarmReceiver] prikaže obaveštenje i zakaže sledeći. Ponovo se zakazuje i pri
 * pokretanju aplikacije, promeni izbora rasporeda, restartu telefona i promeni vremena.
 */
object DepartureScheduler {
    /** Bez dozvole za tačne alarme obaveštenje stiže u ovom prozoru pre [Departure.notifyAt]. */
    private const val INEXACT_WINDOW_MS = 10 * 60 * 1000L

    private const val PREFS = "departure"
    private const val KEY_LAST_NOTIFY_AT = "last_notify_at"

    suspend fun reschedule(context: Context) {
        val context = context.applicationContext
        val zone = ZoneId.systemDefault()
        // Neprecizan alarm okida i pre notifyAt - taj polazak se ne zakazuje ponovo.
        val lastNotified = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_NOTIFY_AT, 0)
        val after = LocalDateTime.ofInstant(Instant.ofEpochMilli(maxOf(System.currentTimeMillis(), lastNotified)), zone)

        val store = ScheduleStore(context)
        val classes = withContext(Dispatchers.IO) { store.loadData() }.classesFor(store.loadSelection())
        val campus = loadCampus(context)
        val graph = loadGraph(context, campus)
        val departure = nextDeparture(classes, after, route = { from, to -> routeBetween(graph, campus, from, to) })

        val alarms = context.getSystemService(AlarmManager::class.java)
        if (departure == null) {
            alarms.cancel(alarmIntent(context, Intent()))
            return
        }
        val building = resolveTarget(departure.entry.room, graph, campus)?.building?.name
            ?: buildingOfRoom(departure.entry.room)?.let { campus.building(it)?.name }
        val notifyAtMs = departure.notifyAt.toEpochMilli(zone)
        val pending = alarmIntent(context, content(context, departure, building, zone))
        if (alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, notifyAtMs, pending)
        } else {
            alarms.setWindow(AlarmManager.RTC_WAKEUP, notifyAtMs - INEXACT_WINDOW_MS, INEXACT_WINDOW_MS, pending)
        }
    }

    /** Pamti da je obaveštenje za polazak sa ovim notifyAt prikazano. */
    fun markNotified(context: Context, notifyAtMs: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putLong(KEY_LAST_NOTIFY_AT, notifyAtMs) }
    }

    /** Tekst obaveštenja se sastavlja pri zakazivanju, pa receiver ne mora ponovo da računa rutu. */
    private fun content(context: Context, departure: Departure, building: String?, zone: ZoneId): Intent {
        val entry = departure.entry
        val room = if (building != null) "${entry.room} · $building" else entry.room
        val route = departure.route
        val routeLine = when {
            route == null -> context.getString(R.string.route_not_on_map)
            departure.fromRoom == null -> context.getString(R.string.home_route_from_entrance, route.minutes)
            else -> context.getString(R.string.departure_from_room, departure.fromRoom, route.minutes)
        }
        return Intent()
            .putExtra(DepartureNotifications.EXTRA_CLASS, "${entry.subject} · ${entry.start} · $room")
            .putExtra(DepartureNotifications.EXTRA_ROUTE, routeLine)
            .putExtra(DepartureNotifications.EXTRA_LEAVE_AT, departure.leaveAt.format(TIME_FORMAT))
            .putExtra(DepartureNotifications.EXTRA_LEAVE_AT_MS, departure.leaveAt.toEpochMilli(zone))
            .putExtra(DepartureNotifications.EXTRA_NOTIFY_AT_MS, departure.notifyAt.toEpochMilli(zone))
            .putExtra(DepartureNotifications.EXTRA_CLASS_START_MS, departure.classStart.toEpochMilli(zone))
    }

    /** Uvek isti PendingIntent (extras se ne porede), pa novi alarm zamenjuje stari. */
    private fun alarmIntent(context: Context, content: Intent): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        content.setClass(context, DepartureAlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun LocalDateTime.toEpochMilli(zone: ZoneId): Long = atZone(zone).toInstant().toEpochMilli()

    private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
}
