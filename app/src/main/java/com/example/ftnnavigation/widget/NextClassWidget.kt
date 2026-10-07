package com.example.ftnnavigation.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import com.example.ftnnavigation.MainActivity
import com.example.ftnnavigation.R
import com.example.ftnnavigation.campus.PlaceLocation
import com.example.ftnnavigation.departure.Departure
import com.example.ftnnavigation.departure.DepartureScheduler
import com.example.ftnnavigation.departure.floorText
import com.example.ftnnavigation.departure.leaveByText
import com.example.ftnnavigation.departure.noRouteText
import com.example.ftnnavigation.departure.routeText
import com.example.ftnnavigation.schedule.AgendaItem
import com.example.ftnnavigation.schedule.SHORT_DATE
import com.example.ftnnavigation.schedule.ScheduleStore
import com.example.ftnnavigation.schedule.TIME_FORMAT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Najmanja visina (dp) za pun raspored; niži widget (podrazumevano 4 x 1) je sažet. */
private const val FULL_LAYOUT_MIN_HEIGHT_DP = 130f

/**
 * Widget "Sledeće" na početnom ekranu telefona: sledeći čas ili događaj, mesto sa zgradom i spratom i polazak - isto kao
 * kartica na Početnoj (isti [DepartureScheduler.Planner]). Podrazumevano 4 x 1, sažeto u dva reda ("Danas 10:15 Predmet",
 * "NTP-307 · 3. sprat · kreni u 10:09"); razvučen na dva reda polja - pun raspored. Dodir otvara aplikaciju.
 *
 * Osvežava se: pri svakom zakazivanju obaveštenja ([DepartureScheduler.reschedule] - pokretanje aplikacije, izmena
 * rasporeda, događaja i podešavanja, restart, promena vremena), kad se widget postavi, i sam ([widgetRefreshAt]: rok za
 * polazak, početak i kraj stavke, ponoć - "Danas"/"Sutra") alarmom koji ne budi telefon.
 */
class NextClassWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) = updateAsync(context)

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) updateAsync(context)
    }

    /** Uklonjen poslednji widget - osvežavanje više ne treba. */
    override fun onDisabled(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(refreshIntent(context))
    }

    /** Učitavanje rasporeda i grafa traje - van glavne niti, uz goAsync. */
    private fun updateAsync(context: Context) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                updateAll(context)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val ACTION_REFRESH = "com.example.ftnnavigation.widget.REFRESH"

        /** Osvežava sve postavljene widgete; [planner] - već učitan (zakazivanje obaveštenja), inače se učita. */
        internal suspend fun updateAll(context: Context, planner: DepartureScheduler.Planner? = null) {
            val context = context.applicationContext
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, NextClassWidget::class.java))
            if (ids.isEmpty()) return
            val now = LocalDateTime.now()
            val loaded = planner ?: DepartureScheduler.Planner.load(context)
            val upcoming = loaded.upcoming(now)
            val item = upcoming?.first
            val (compact, full) = if (item == null) {
                val hasSchedule = ScheduleStore(context).loadSelection() != null
                emptyCompact(context, hasSchedule) to emptyFull(context, hasSchedule)
            } else {
                val departure = upcoming.second
                val place = item.place
                compactViews(context, now, item, departure, place?.let { loaded.placeLocation(it) }) to
                    fullViews(context, now, item, departure, place?.let { loaded.location(context, it) })
            }
            listOf(compact, full).forEach { it.setOnClickPendingIntent(android.R.id.background, openAppIntent(context)) }
            // Sistem bira najveći raspored koji staje u widget (Android 12+).
            val views = RemoteViews(mapOf(SizeF(180f, 40f) to compact, SizeF(180f, FULL_LAYOUT_MIN_HEIGHT_DP) to full))
            manager.updateAppWidget(ids, views)
            scheduleRefresh(context, widgetRefreshAt(now, item, upcoming?.second))
        }

        /** "Danas 10:15  Predmet" / "NTP-307 · 3. sprat · kreni u 10:09". */
        private fun compactViews(
            context: Context,
            now: LocalDateTime,
            item: AgendaItem,
            departure: Departure?,
            location: PlaceLocation?,
        ): RemoteViews {
            val res = context.resources
            val views = RemoteViews(context.packageName, R.layout.widget_next_class_compact)
            val whenText = if (!item.startAt.isAfter(now)) {
                res.getString(R.string.widget_compact_ongoing, item.end.format(TIME_FORMAT))
            } else {
                res.getString(R.string.widget_compact_when, dayLabel(res, item, now, R.array.days_short), item.start.format(TIME_FORMAT))
            }
            views.setTextViewText(R.id.widget_c_when, whenText)
            views.setTextViewText(R.id.widget_c_title, item.title)
            val where = when (location) {
                is PlaceLocation.Room -> floorText(res, location.floor)
                is PlaceLocation.Named -> location.name.takeIf { it != item.place }
                null -> null
            }
            val leave = leaveDeadline(departure, now)?.let { leaveAt ->
                if (!now.isBefore(leaveAt)) {
                    res.getString(R.string.widget_compact_go_now)
                } else {
                    res.getString(R.string.widget_compact_leave_by, leaveAt.format(TIME_FORMAT))
                }
            }
            views.setText(R.id.widget_c_details, listOfNotNull(item.place, where, leave).joinToString(" · ").ifEmpty { null })
            return views
        }

        /** Kao kartica "Sledeće" na Početnoj: vreme, naslov, mesto sa zgradom i spratom, ruta, rok za polazak. */
        private fun fullViews(
            context: Context,
            now: LocalDateTime,
            item: AgendaItem,
            departure: Departure?,
            location: String?,
        ): RemoteViews {
            val res = context.resources
            val views = RemoteViews(context.packageName, R.layout.widget_next_class)
            val whenText = if (!item.startAt.isAfter(now)) {
                res.getString(R.string.widget_ongoing, item.end.format(TIME_FORMAT))
            } else {
                res.getString(
                    R.string.widget_when, dayLabel(res, item, now, R.array.days_full),
                    item.start.format(TIME_FORMAT), item.end.format(TIME_FORMAT),
                )
            }
            views.setTextViewText(R.id.widget_when, whenText)
            views.setTextViewText(R.id.widget_title, item.title)
            val place = item.place
            views.setText(R.id.widget_place, place?.let { if (location != null && location != it) "$it · $location" else it })
            val route = when {
                place == null -> null
                departure == null -> res.getString(if (item is AgendaItem.Class) R.string.home_same_room else R.string.home_same_place)
                departure.route == null -> res.getString(noRouteText(place))
                else -> departure.routeText(res)
            }
            views.setText(R.id.widget_route, route)
            val deadline = leaveDeadline(departure, now)?.let {
                if (!now.isBefore(it)) res.getString(R.string.home_route_go_now) else departure!!.leaveByText(res)
            }
            views.setText(R.id.widget_deadline, deadline)
            return views
        }

        /** Rok za polazak kao na Početnoj: samo danas, pre početka stavke, kad ruta postoji; null - ne piše se. */
        private fun leaveDeadline(departure: Departure?, now: LocalDateTime): LocalDateTime? =
            departure?.takeIf { it.route != null && it.date == now.toLocalDate() && it.startAt.isAfter(now) }?.leaveAt

        /** "Danas", "Sutra", dan u nedelji ([days] - pun ili skraćen naziv), a posle nedelju dana i datum. */
        private fun dayLabel(res: Resources, item: AgendaItem, now: LocalDateTime, days: Int): String {
            val dayName = res.getStringArray(days)[item.date.dayOfWeek.value - 1]
            return when (ChronoUnit.DAYS.between(now.toLocalDate(), item.date)) {
                0L -> res.getString(R.string.when_today)
                1L -> res.getString(R.string.when_tomorrow)
                in 2..6 -> dayName
                else -> "$dayName ${item.date.format(SHORT_DATE)}"
            }
        }

        private fun emptyText(context: Context, hasSchedule: Boolean): String =
            context.getString(if (hasSchedule) R.string.widget_no_upcoming else R.string.widget_no_schedule)

        private fun emptyCompact(context: Context, hasSchedule: Boolean): RemoteViews =
            RemoteViews(context.packageName, R.layout.widget_next_class_compact).apply {
                setTextViewText(R.id.widget_c_when, context.getString(R.string.home_next_class_title))
                setTextViewText(R.id.widget_c_title, emptyText(context, hasSchedule))
                setText(R.id.widget_c_details, null)
            }

        private fun emptyFull(context: Context, hasSchedule: Boolean): RemoteViews =
            RemoteViews(context.packageName, R.layout.widget_next_class).apply {
                setTextViewText(R.id.widget_when, context.getString(R.string.home_next_class_title))
                setTextViewText(R.id.widget_title, emptyText(context, hasSchedule))
                setText(R.id.widget_place, null)
                setText(R.id.widget_route, null)
                setText(R.id.widget_deadline, null)
            }

        /** Tekst ili sakriven red (null). */
        private fun RemoteViews.setText(id: Int, text: String?) {
            setTextViewText(id, text.orEmpty())
            setViewVisibility(id, if (text == null) View.GONE else View.VISIBLE)
        }

        private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun refreshIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, NextClassWidget::class.java).setAction(ACTION_REFRESH),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /** Alarm koji ne budi telefon: widget se vidi samo kad je ekran upaljen, a tada alarm stiže. */
        private fun scheduleRefresh(context: Context, at: LocalDateTime) {
            val ms = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            context.getSystemService(AlarmManager::class.java).set(AlarmManager.RTC, ms, refreshIntent(context))
        }
    }
}

/**
 * Kad widget treba osvežiti: prvi budući od roka za polazak ("Kreni odmah"), početka stavke ("U toku"), kraja stavke
 * (sledeća stavka) i ponoći ("Danas" / "Sutra"). Bez stavke - u ponoć.
 */
internal fun widgetRefreshAt(now: LocalDateTime, item: AgendaItem?, departure: Departure?): LocalDateTime {
    val midnight = now.toLocalDate().plusDays(1).atStartOfDay()
    val moments = listOfNotNull(
        departure?.takeIf { it.route != null }?.leaveAt,
        item?.startAt,
        item?.let { it.date.atTime(it.end) },
        midnight,
    )
    return moments.filter { it.isAfter(now) }.min()
}
