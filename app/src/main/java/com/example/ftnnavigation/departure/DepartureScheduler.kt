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
        // Neprecizan alarm okida i pre notifyAt - taj polazak se ne zakazuje ponovo.
        val lastNotified = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_NOTIFY_AT, 0)
        val next = next(context, afterMs = maxOf(System.currentTimeMillis(), lastNotified))

        val alarms = context.getSystemService(AlarmManager::class.java)
        if (next == null) {
            alarms.cancel(alarmIntent(context, Intent()))
            return
        }
        val (notifyAtMs, content) = next
        val pending = alarmIntent(context, content)
        if (alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, notifyAtMs, pending)
        } else {
            alarms.setWindow(AlarmManager.RTC_WAKEUP, notifyAtMs - INEXACT_WINDOW_MS, INEXACT_WINDOW_MS, pending)
        }
    }

    /**
     * Probno obaveštenje (samo debug build): odmah prikazuje obaveštenje za sledeći polazak,
     * a zakazani alarm i zapamćeni poslednji polazak ostaju netaknuti. false = nema polaska.
     */
    suspend fun showTest(context: Context): Boolean {
        val context = context.applicationContext
        val (_, content) = next(context, afterMs = System.currentTimeMillis()) ?: return false
        DepartureNotifications.show(context, content)
        return true
    }

    /** Sledeći polazak posle [afterMs]: vreme obaveštenja i sadržaj za [DepartureNotifications]. */
    private suspend fun next(context: Context, afterMs: Long): Pair<Long, Intent>? {
        val zone = ZoneId.systemDefault()
        val after = LocalDateTime.ofInstant(Instant.ofEpochMilli(afterMs), zone)
        val store = ScheduleStore(context)
        val classes = withContext(Dispatchers.IO) { store.loadData() }.classesFor(store.loadSelection())
        val campus = loadCampus(context)
        val graph = loadGraph(context, campus)
        val departure = nextDeparture(classes, after, route = { from, to -> routeBetween(graph, campus, from, to) })
            ?: return null
        val building = resolveTarget(departure.entry.room, graph, campus)?.building?.name
            ?: buildingOfRoom(departure.entry.room)?.let { campus.building(it)?.name }
        return departure.notifyAt.toEpochMilli(zone) to content(context, departure, building, zone)
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
