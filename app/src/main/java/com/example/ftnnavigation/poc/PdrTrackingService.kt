package com.example.ftnnavigation.poc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import com.example.ftnnavigation.MainActivity
import com.example.ftnnavigation.R

/**
 * Drži praćenje kretanja (PDR) živim od Start do Stop, i kad je ekran ugašen, otvoren drugi tab
 * ili je aplikacija u pozadini: foreground servis (trajno obaveštenje - sistem ne ubija proces)
 * i partial wake lock (bez njega procesor sa ugašenim ekranom zaspi, pa senzori ne javljaju).
 *
 * Senzore kači [PocViewModel] ([PdrSensorSession]); servis samo drži proces i procesor budnim.
 * Tip `health` (praćenje koraka), uslov za njega je HIGH_SAMPLING_RATE_SENSORS (bez pitanja).
 */
class PdrTrackingService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FTNNavigation:pdr")
                .apply { acquire(MAX_TRACKING_MS) }
        }
        // Ako sistem ubije proces, stanje praćenja (u ViewModel-u) je izgubljeno - bez ponovnog pokretanja.
        return START_NOT_STICKY
    }

    // Aplikacija uklonjena iz nedavnih: aktivnost i ViewModel nestaju, pa i praćenje.
    override fun onTaskRemoved(rootIntent: Intent?) = stopSelf()

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.pdr_channel_name), NotificationManager.IMPORTANCE_LOW)
                .apply { description = getString(R.string.pdr_channel_description) },
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_place)
            .setColor(getColor(R.color.ftn_teal))
            .setContentTitle(getString(R.string.pdr_notification_title))
            .setContentText(getString(R.string.pdr_notification_text))
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "pdr_tracking"

        // Obaveštenje o polasku koristi id 1.
        private const val NOTIFICATION_ID = 2

        /** Zaštita ako Stop izostane (npr. greška): wake lock se sam pušta posle ovoliko. */
        private const val MAX_TRACKING_MS = 3 * 60 * 60 * 1000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, PdrTrackingService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PdrTrackingService::class.java))
        }
    }
}
