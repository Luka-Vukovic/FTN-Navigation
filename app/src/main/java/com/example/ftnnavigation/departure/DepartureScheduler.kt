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
import com.example.ftnnavigation.schedule.TIME_FORMAT
import com.example.ftnnavigation.schedule.ScheduleStore
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Zakazuje jedan alarm - za sledeći polazak na čas ili događaj ([nextDeparture]). Kad alarm
 * okine, [DepartureAlarmReceiver] prikaže obaveštenje i zakaže sledeći. Ponovo se zakazuje i
 * pri pokretanju aplikacije, promeni izbora rasporeda ili događaja, restartu telefona i
 * promeni vremena.
 */
object DepartureScheduler {
    /** Bez dozvole za tačne alarme obaveštenje stiže u ovom prozoru pre [Departure.notifyAt]. */
    const val INEXACT_WINDOW_MIN = 10L
    private const val INEXACT_WINDOW_MS = INEXACT_WINDOW_MIN * 60 * 1000

    private const val PREFS = "departure"
    private const val KEY_LAST_NOTIFY_AT = "last_notify_at"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    /** Uključuje/isključuje obaveštenja; isključivanje briše alarm i već prikazano obaveštenje. */
    suspend fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_ENABLED, enabled) }
        if (!enabled) DepartureNotifications.cancel(context)
        reschedule(context)
    }

    suspend fun reschedule(context: Context) {
        val context = context.applicationContext
        val alarms = context.getSystemService(AlarmManager::class.java)
        val next = if (isEnabled(context)) next(context, scheduledAfter(context)) else null
        if (next == null) {
            alarms.cancel(alarmIntent(context, Intent()))
            return
        }
        val (departure, building) = next
        val zone = ZoneId.systemDefault()
        val notifyAtMs = departure.notifyAt.toEpochMilli(zone)
        val pending = alarmIntent(context, content(context, departure, building, zone))
        if (alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, notifyAtMs, pending)
        } else {
            alarms.setWindow(AlarmManager.RTC_WAKEUP, notifyAtMs - INEXACT_WINDOW_MS, INEXACT_WINDOW_MS, pending)
        }
    }

    /** Polazak za koji je zakazan alarm (null ako nema časova ni događaja), za prikaz u podešavanjima. */
    suspend fun upcoming(context: Context): Departure? =
        next(context.applicationContext, scheduledAfter(context))?.first

    /**
     * Probno obaveštenje (samo debug build): odmah prikazuje obaveštenje za sledeći polazak,
     * a zakazani alarm i zapamćeni poslednji polazak ostaju netaknuti. false = nema polaska.
     */
    suspend fun showTest(context: Context): Boolean {
        val context = context.applicationContext
        val (departure, building) = next(context, System.currentTimeMillis()) ?: return false
        DepartureNotifications.show(context, content(context, departure, building, ZoneId.systemDefault()))
        return true
    }

    /** Neprecizan alarm okida i pre notifyAt - već prikazan polazak se ne zakazuje ponovo. */
    private fun scheduledAfter(context: Context): Long =
        maxOf(System.currentTimeMillis(), prefs(context).getLong(KEY_LAST_NOTIFY_AT, 0))

    /** Sledeći polazak posle [afterMs] i naziv zgrade njegovog mesta (null ako se ne zna). */
    private suspend fun next(context: Context, afterMs: Long): Pair<Departure, String?>? {
        val after = LocalDateTime.ofInstant(Instant.ofEpochMilli(afterMs), ZoneId.systemDefault())
        val agenda = ScheduleStore(context).loadAgenda()
        val campus = loadCampus(context)
        val graph = loadGraph(context, campus)
        val departure = nextDeparture(agenda::on, after, route = { from, to -> routeBetween(graph, campus, from, to) })
            ?: return null
        val building = departure.item.place?.let { place ->
            resolveTarget(place, graph, campus)?.building?.name ?: buildingOfRoom(place)?.let { campus.building(it)?.name }
        }
        return departure to building
    }

    /** Pamti da je obaveštenje za polazak sa ovim notifyAt prikazano. */
    fun markNotified(context: Context, notifyAtMs: Long) {
        prefs(context).edit { putLong(KEY_LAST_NOTIFY_AT, notifyAtMs) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Tekst obaveštenja se sastavlja pri zakazivanju, pa receiver ne mora ponovo da računa rutu. */
    private fun content(context: Context, departure: Departure, building: String?, zone: ZoneId): Intent {
        return Intent()
            .putExtra(DepartureNotifications.EXTRA_CLASS, departure.item.summary(building))
            .putExtra(DepartureNotifications.EXTRA_ROUTE, departure.routeText(context.resources))
            .putExtra(DepartureNotifications.EXTRA_LEAVE_AT, departure.leaveAt.format(TIME_FORMAT))
            .putExtra(DepartureNotifications.EXTRA_LEAVE_AT_MS, departure.leaveAt.toEpochMilli(zone))
            .putExtra(DepartureNotifications.EXTRA_NOTIFY_AT_MS, departure.notifyAt.toEpochMilli(zone))
            .putExtra(DepartureNotifications.EXTRA_CLASS_START_MS, departure.startAt.toEpochMilli(zone))
    }

    /** Uvek isti PendingIntent (extras se ne porede), pa novi alarm zamenjuje stari. */
    private fun alarmIntent(context: Context, content: Intent): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        content.setClass(context, DepartureAlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun LocalDateTime.toEpochMilli(zone: ZoneId): Long = atZone(zone).toInstant().toEpochMilli()

}
