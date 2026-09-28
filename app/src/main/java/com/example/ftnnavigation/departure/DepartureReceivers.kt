package com.example.ftnnavigation.departure

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Alarm polaska (nije izvezen - okida ga samo [DepartureScheduler]): obaveštenje, pa sledeći alarm. */
class DepartureAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DepartureNotifications.show(context, intent)
        DepartureScheduler.markNotified(context, intent.getLongExtra(DepartureNotifications.EXTRA_NOTIFY_AT_MS, 0))
        rescheduleAsync(context)
    }
}

/**
 * Sistemski događaji posle kojih alarm treba zakazati iznova: restart telefona (alarmi se
 * brišu), ažuriranje aplikacije, promena vremena ili zone, dozvola za tačne alarme.
 */
class DepartureRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in ACTIONS) rescheduleAsync(context)
    }

    private companion object {
        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        )
    }
}

/** Učitavanje rasporeda i grafa traje - van glavne niti, uz goAsync (receiver sme do ~10 s). */
private fun BroadcastReceiver.rescheduleAsync(context: Context) {
    val pending = goAsync()
    CoroutineScope(Dispatchers.Default).launch {
        try {
            DepartureScheduler.reschedule(context)
        } finally {
            pending.finish()
        }
    }
}
