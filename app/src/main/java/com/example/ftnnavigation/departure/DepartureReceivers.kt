package com.example.ftnnavigation.departure

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Alarm polaska (nije izvezen - okida ga samo [DepartureScheduler]): obaveštenje, pa sledeći alarm. */
class DepartureAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Alarm zakazan pre isključivanja obaveštenja (setEnabled ga briše, ali za svaki slučaj).
        val enabled = DepartureScheduler.isEnabled(context)
        val notifyAtMs = intent.getLongExtra(DepartureNotifications.EXTRA_NOTIFY_AT_MS, 0)
        // Zakasneli alarm, a obaveštenje je već stiglo kao propušteno (aplikacija otvorena u međuvremenu).
        val show = enabled && !DepartureScheduler.alreadyNotified(context, notifyAtMs)
        if (show) {
            DepartureNotifications.show(context, intent)
            DepartureScheduler.markNotified(context, notifyAtMs)
        }
        DepartureLog.alarm(context, notifyAtMs, show)
        rescheduleAsync(context, "alarm")
    }
}

/**
 * Sistemski događaji posle kojih alarm treba zakazati iznova: restart telefona (alarmi se
 * brišu), ažuriranje aplikacije, promena vremena ili zone, dozvola za tačne alarme.
 */
class DepartureRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        val reason = intent.action.orEmpty().substringAfterLast('.')
        // Sistem je alarme obrisao: odmah oni poslednji zakazani, pa tek onda računanje (koje može da se ne završi).
        if (intent.action in ALARMS_LOST) DepartureScheduler.restore(context, reason)
        rescheduleAsync(context, reason, BACKGROUND_LIMIT_MS)
    }

    private companion object {
        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        )

        val ALARMS_LOST = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)
    }
}

/** Receiver alarma sme do ~10 s, sistemski događaji u pozadini do 60 s - posle toga ANR i sistem gasi proces. */
private const val ALARM_LIMIT_MS = 9_000L
private const val BACKGROUND_LIMIT_MS = 50_000L

/**
 * Učitavanje rasporeda i grafa traje - van glavne niti, uz goAsync. Receiver se završava najkasnije posle [limitMs], a računanje
 * ide dalje dok proces živi. Teren 25.11.2026: telefon restartovan u 08:50, računanje posle BOOT_COMPLETED nije stiglo za 60 s (telefon
 * zauzet odmah posle restarta) - ANR, proces ugašen, obaveštenja u 09:30 i 10:17 nisu stigla.
 */
private fun BroadcastReceiver.rescheduleAsync(context: Context, reason: String, limitMs: Long = ALARM_LIMIT_MS) {
    val pending = goAsync()
    val scope = CoroutineScope(Dispatchers.Default)
    val work = scope.launch { DepartureScheduler.reschedule(context, reason) }
    scope.launch {
        try {
            withTimeoutOrNull(limitMs) { work.join() }
        } finally {
            pending.finish()
        }
    }
}
