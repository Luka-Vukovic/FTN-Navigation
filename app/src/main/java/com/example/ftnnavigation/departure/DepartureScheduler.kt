package com.example.ftnnavigation.departure

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import com.example.ftnnavigation.MainActivity
import com.example.ftnnavigation.R
import com.example.ftnnavigation.campus.CampusData
import com.example.ftnnavigation.campus.PlaceLocation
import com.example.ftnnavigation.campus.crowdFactors
import com.example.ftnnavigation.campus.loadCampus
import com.example.ftnnavigation.campus.loadGraph
import com.example.ftnnavigation.campus.placeLocation
import com.example.ftnnavigation.campus.routeBetween
import com.example.ftnnavigation.graph.BuildingGraph
import com.example.ftnnavigation.graph.Route
import com.example.ftnnavigation.graph.RoutingProfile
import com.example.ftnnavigation.schedule.Agenda
import com.example.ftnnavigation.schedule.AgendaItem
import com.example.ftnnavigation.schedule.RoomSchedule
import com.example.ftnnavigation.widget.NextClassWidget
import com.example.ftnnavigation.settings.AppSettings
import com.example.ftnnavigation.schedule.TIME_FORMAT
import com.example.ftnnavigation.schedule.ScheduleStore
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Zakazuje alarme za sledeća obaveštenja o polasku na čas ili događaj ([nextDeparture], [ALARMS_AHEAD]). Kad alarm
 * okine, [DepartureAlarmReceiver] prikaže obaveštenje i zakaže sledeće. Ponovo se zakazuje i
 * pri pokretanju aplikacije, promeni izbora rasporeda ili događaja, restartu telefona i
 * promeni vremena; tada stiže i obaveštenje koje je u međuvremenu propušteno.
 */
object DepartureScheduler {
    /** Bez dozvole za tačne alarme obaveštenje stiže u ovom prozoru pre [Departure.notifyAt]. */
    const val INEXACT_WINDOW_MIN = 10L
    private const val INEXACT_WINDOW_MS = INEXACT_WINDOW_MIN * 60 * 1000

    /**
     * Toliko sledećih obaveštenja ima svoj alarm. Bio je jedan, a sledeći se zakazivao tek kad prethodni okine - sistem (Xiaomi) proces
     * posle prikazanog obaveštenja zamrzne, pa je zakazivanje kasnilo. Teren 23.11.2026: prvo obaveštenje 11:33, a zakazivanje drugog
     * podsetnika (12:22) je završeno tek u 16:02, kad je aplikacija otvorena - drugi podsetnik nije stigao (i 12.-20.11. 15 s - 19 min kasno).
     */
    private const val ALARMS_AHEAD = 2

    private val mutex = Mutex()

    private const val PREFS = "departure"
    private const val KEY_LAST_NOTIFY_AT = "last_notify_at"
    private const val KEY_ENABLED = "enabled"

    /** Sadržaj zakazanog alarma po mestu (`saved0`, `saved1`) - za [restore]. */
    private const val KEY_SAVED = "saved"

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    /** Uključuje/isključuje obaveštenja; isključivanje briše alarm i već prikazano obaveštenje. */
    suspend fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_ENABLED, enabled) }
        if (!enabled) DepartureNotifications.cancel(context)
        reschedule(context)
    }

    /** [reason]: ko traži zakazivanje - samo za [DepartureLog]. */
    suspend fun reschedule(context: Context, reason: String = "aplikacija") = mutex.withLock {
        val context = context.applicationContext
        val alarms = context.getSystemService(AlarmManager::class.java)
        val zone = ZoneId.systemDefault()
        val planner = if (isEnabled(context)) Planner.load(context) else null
        planner?.let { showMissed(context, it, zone) }
        var departures = planner?.nextAlarms(context, zone).orEmpty()
        // Računanje može da potraje (sistem zamrzne proces) - ako je vreme alarma u međuvremenu prošlo, računa se ponovo.
        while (planner != null && departures.isNotEmpty() && !departures.first().notifyAt.isAfter(LocalDateTime.now(zone))) {
            departures = planner.nextAlarms(context, zone)
        }
        for (slot in 0 until ALARMS_AHEAD) {
            // Neprecizni alarmi (prozor pre notifyAt) mogu da okinu van reda - tada samo prvi.
            val departure = departures.getOrNull(slot)?.takeIf { slot == 0 || alarms.canScheduleExactAlarms() }
            if (planner == null || departure == null) {
                alarms.cancel(alarmIntent(context, Intent(), slot))
                prefs(context).edit { remove(KEY_SAVED + slot) }
            } else {
                schedule(context, alarms, departure, planner, zone, slot, reason)
            }
        }
        if (departures.isEmpty()) DepartureLog.nothing(context, reason)
        // Svi povodi za novo zakazivanje (izmena rasporeda/događaja, podešavanja, restart, vreme) menjaju i widget. Posle alarma - oni su
        // važniji ako sistem zamrzne proces.
        NextClassWidget.updateAll(context, planner)
    }

    private fun schedule(
        context: Context, alarms: AlarmManager, departure: Departure, planner: Planner, zone: ZoneId, slot: Int, reason: String,
    ) {
        val notifyAtMs = departure.notifyAt.toEpochMilli(zone)
        val content = content(context, departure, planner.location(context, departure), zone)
        prefs(context).edit { putString(KEY_SAVED + slot, content.toUri(Intent.URI_INTENT_SCHEME)) }
        val exact = setAlarm(context, alarms, notifyAtMs, alarmIntent(context, content, slot))
        DepartureLog.scheduled(context, reason, departure.notifyAt, exact, logItem(departure))
    }

    /** Alarm u [notifyAtMs]; true = tačan (kao budilnik), false = prozor pre tog vremena (bez dozvole za tačne alarme). */
    private fun setAlarm(context: Context, alarms: AlarmManager, notifyAtMs: Long, pending: PendingIntent): Boolean {
        val exact = alarms.canScheduleExactAlarms()
        if (exact) {
            // Kao budilnik: `setExactAndAllowWhileIdle` kasni - drugi podsetnik 35-154 s (teren 10.-13.11.2026), ostali 214-216 s (18.11.,
            // 20.11. i 23.11.2026), a drugi podsetnici kao budilnik od 14.11. svi 0-1 s. Ikonica budilnika se na HyperOS-u ne vidi
            // (korisnik 23.11.; ni za budilnik Xiaomi kalendara koji stalno čeka u 00:00).
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            alarms.setAlarmClock(AlarmManager.AlarmClockInfo(notifyAtMs, open), pending)
        } else {
            alarms.setWindow(AlarmManager.RTC_WAKEUP, notifyAtMs - INEXACT_WINDOW_MS, INEXACT_WINDOW_MS, pending)
        }
        return exact
    }

    /**
     * Alarmi poslednjeg zakazivanja, bez računanja (posle restarta telefona i ažuriranja aplikacije ih sistem obriše): sadržaj
     * obaveštenja je sačuvan pri zakazivanju. [reschedule] ih posle zameni (isti PendingIntent po mestu). Obaveštenje kome je vreme u
     * međuvremenu prošlo stiže odmah, ako rok za polazak još nije prošao.
     */
    fun restore(context: Context, reason: String) {
        val context = context.applicationContext
        if (!isEnabled(context)) return
        val alarms = context.getSystemService(AlarmManager::class.java)
        val now = System.currentTimeMillis()
        for (slot in 0 until ALARMS_AHEAD) {
            val uri = prefs(context).getString(KEY_SAVED + slot, null) ?: continue
            // Samo extras: `parseUri` dodaje akciju, a po njoj bi se PendingIntent razlikovao od onog iz [reschedule].
            val content = runCatching { Intent().putExtras(Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)) }.getOrNull() ?: continue
            val notifyAtMs = content.getLongExtra(DepartureNotifications.EXTRA_NOTIFY_AT_MS, 0)
            val leaveAtMs = content.getLongExtra(DepartureNotifications.EXTRA_LEAVE_AT_MS, 0)
            if (alreadyNotified(context, notifyAtMs) || maxOf(notifyAtMs, leaveAtMs) <= now) continue
            setAlarm(context, alarms, notifyAtMs, alarmIntent(context, content, slot))
            DepartureLog.restored(context, reason, notifyAtMs)
        }
    }

    private fun logItem(departure: Departure) =
        "${departure.item.date} ${departure.startAt.format(TIME_FORMAT)} ${departure.item.place ?: "-"}"

    /** Polazak za koji je zakazan alarm (null ako nema časova ni događaja), za prikaz u podešavanjima. */
    suspend fun upcoming(context: Context): Departure? =
        Planner.load(context.applicationContext).next(scheduledAfter(context).toLocalDateTime(ZoneId.systemDefault()))

    /**
     * Probno obaveštenje (samo debug build): odmah prikazuje obaveštenje za sledeći polazak,
     * a zakazani alarm i zapamćeni poslednji polazak ostaju netaknuti. false = nema polaska.
     */
    suspend fun showTest(context: Context): Boolean {
        val context = context.applicationContext
        val zone = ZoneId.systemDefault()
        val planner = Planner.load(context)
        val departure = planner.next(LocalDateTime.now(zone)) ?: return false
        DepartureNotifications.show(context, content(context, departure, planner.location(context, departure), zone))
        return true
    }

    /**
     * Obaveštenje koje nije stiglo na vreme (alarm se nije zakazao - telefon ugašen, Xiaomi bez
     * "Automatskog pokretanja" posle restarta), a stavka još nije počela: prikazuje se odmah.
     * Posle toga se sva obaveštenja do sada računaju kao obrađena (ne stiže još jedno zakasnelo).
     */
    private fun showMissed(context: Context, planner: Planner, zone: ZoneId) {
        val now = System.currentTimeMillis()
        val notifiedUpTo = prefs(context).getLong(KEY_LAST_NOTIFY_AT, 0).toLocalDateTime(zone)
        val missed = planner.missed(now.toLocalDateTime(zone), notifiedUpTo) ?: return
        DepartureNotifications.show(context, content(context, missed, planner.location(context, missed), zone))
        DepartureLog.missed(context, missed.notifyAt, logItem(missed))
        markNotified(context, now)
    }

    /** Sledećih [ALARMS_AHEAD] obaveštenja, po vremenu. */
    private fun Planner.nextAlarms(context: Context, zone: ZoneId): List<Departure> =
        generateSequence(next(scheduledAfter(context).toLocalDateTime(zone))) { next(it.notifyAt) }.take(ALARMS_AHEAD).toList()

    /** Obaveštenje sa ovim notifyAt je već prikazano (alarm, ili kao propušteno pri zakazivanju). */
    fun alreadyNotified(context: Context, notifyAtMs: Long): Boolean = notifyAtMs <= prefs(context).getLong(KEY_LAST_NOTIFY_AT, 0)

    /** Neprecizan alarm okida i pre notifyAt - već prikazan polazak se ne zakazuje ponovo. */
    private fun scheduledAfter(context: Context): Long =
        maxOf(System.currentTimeMillis(), prefs(context).getLong(KEY_LAST_NOTIFY_AT, 0))

    /**
     * Raspored, kampus i graf za računanje polazaka; učitavanje traje, pa jednom po zakazivanju. Koristi ga i widget
     * "Sledeće" ([com.example.ftnnavigation.widget.NextClassWidget]).
     */
    internal class Planner(
        private val agenda: Agenda,
        private val campus: CampusData,
        private val graph: BuildingGraph,
        /** Promena sprata iz Podešavanja (bez stepenica / bez lifta menja vreme polaska). */
        private val profile: RoutingProfile,
        /** Zauzetost sala svih rasporeda - gužva u vreme dolaska; null = gužva isključena u Podešavanjima. */
        private val roomSchedule: RoomSchedule?,
    ) {
        private fun route(from: String?, to: String, arriveAt: LocalDateTime): Route? {
            val crowd = roomSchedule?.let { crowdFactors(it, arriveAt) }.orEmpty()
            return routeBetween(graph, campus, from, to, profile.copy(buildingCrowd = crowd))
        }

        fun next(after: LocalDateTime): Departure? = nextDeparture(agenda::on, after, ::route)

        fun missed(now: LocalDateTime, notifiedUpTo: LocalDateTime): Departure? =
            missedDeparture(agenda::on, now, notifiedUpTo, ::route)

        /**
         * Sledeća stavka (i ona u toku) sa polaskom kao na Početnoj ([departureFor]; null = prethodna stavka je na istom
         * mestu); null ako stavke nema.
         */
        fun upcoming(now: LocalDateTime): Pair<AgendaItem, Departure?>? {
            val item = agenda.next(now) ?: return null
            return item to departureFor(item, agenda.on(item.date), ::route)
        }

        /** Zgrada i sprat sale (ili zgrada, mesto van kampusa) stavke polaska ([placeLocationText]); null ako se ne zna. */
        fun location(context: Context, departure: Departure): String? = departure.item.place?.let { location(context, it) }

        fun location(context: Context, place: String): String? = placeLocationText(context.resources, place, graph, campus)

        /** Gde je mesto, nesastavljeno (sažet widget piše samo sprat). */
        fun placeLocation(place: String): PlaceLocation? = placeLocation(place, graph, campus)

        companion object {
            suspend fun load(context: Context): Planner {
                val campus = loadCampus(context)
                val store = ScheduleStore(context)
                val roomSchedule = if (AppSettings.crowdRouting(context)) {
                    withContext(Dispatchers.IO) { RoomSchedule(store.loadData(), store.loadCalendar()) }
                } else {
                    null
                }
                return Planner(
                    store.loadAgenda(), campus, loadGraph(context, campus),
                    AppSettings.floorChange(context).profile, roomSchedule,
                )
            }
        }
    }

    /** Pamti da je obaveštenje za polazak sa ovim notifyAt prikazano. */
    fun markNotified(context: Context, notifyAtMs: Long) {
        prefs(context).edit { putLong(KEY_LAST_NOTIFY_AT, notifyAtMs) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Tekst obaveštenja se sastavlja pri zakazivanju, pa receiver ne mora ponovo da računa rutu. */
    private fun content(context: Context, departure: Departure, location: String?, zone: ZoneId): Intent {
        return Intent()
            .putExtra(DepartureNotifications.EXTRA_CLASS, departure.item.summary(location))
            .putExtra(DepartureNotifications.EXTRA_ROUTE, departure.routeText(context.resources))
            .putExtra(DepartureNotifications.EXTRA_LEAVE_AT, departure.leaveAt.format(TIME_FORMAT))
            .putExtra(DepartureNotifications.EXTRA_LEAVE_AT_MS, departure.leaveAt.toEpochMilli(zone))
            .putExtra(DepartureNotifications.EXTRA_NOTIFY_AT_MS, departure.notifyAt.toEpochMilli(zone))
            // Drugi podsetnik prve stavke ima uobičajen naslov ("Kreni najkasnije u").
            .putExtra(DepartureNotifications.EXTRA_FIRST_OF_DAY, departure.firstOfDay && !departure.reminder)
            .putExtra(DepartureNotifications.EXTRA_START_AT, departure.startAt.format(TIME_FORMAT))
    }

    /** Po mestu ([slot]) uvek isti PendingIntent (extras se ne porede), pa novi alarm zamenjuje stari. */
    private fun alarmIntent(context: Context, content: Intent, slot: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        slot,
        content.setClass(context, DepartureAlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun LocalDateTime.toEpochMilli(zone: ZoneId): Long = atZone(zone).toInstant().toEpochMilli()

    private fun Long.toLocalDateTime(zone: ZoneId): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(this), zone)

}
