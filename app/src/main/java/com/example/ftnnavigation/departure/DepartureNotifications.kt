package com.example.ftnnavigation.departure

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.example.ftnnavigation.MainActivity
import com.example.ftnnavigation.R

/** Obaveštenje "kreni na čas / događaj"; sadržaj stiže u extras alarma (vidi [DepartureScheduler]). */
object DepartureNotifications {
    const val EXTRA_CLASS = "class"
    const val EXTRA_ROUTE = "route"
    const val EXTRA_LEAVE_AT = "leave_at"
    const val EXTRA_LEAVE_AT_MS = "leave_at_ms"
    const val EXTRA_NOTIFY_AT_MS = "notify_at_ms"

    // Podešavanja kanala se posle kreiranja ne mogu menjati iz koda - zato nov id kad treba vibracija.
    const val CHANNEL_ID = "departure_v2"
    private const val OLD_CHANNEL_ID = "departure"

    // Jedno obaveštenje u isto vreme: sledeći polazak zamenjuje prethodni.
    private const val NOTIFICATION_ID = 1

    /** Kanal mora da postoji da bi se otvorila njegova sistemska podešavanja; ponovno kreiranje ne menja ništa. */
    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        // Stari kanal je bio bez vibracije: zvuk sa ugašenim ekranom je promakao (06.10.2026).
        manager.deleteNotificationChannel(OLD_CHANNEL_ID)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.departure_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.departure_channel_description)
                enableVibration(true)
            },
        )
    }

    /** Da li sistem prikazuje obaveštenja o polasku (dozvola, obaveštenja aplikacije i kanal). */
    fun areAllowed(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    fun show(context: Context, extras: Intent) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        createChannel(context)

        val now = System.currentTimeMillis()
        // Kad je rok već prošao (duga ruta iz prethodne sale), nema "najkasnije u".
        val title = if (now >= extras.getLongExtra(EXTRA_LEAVE_AT_MS, 0)) {
            context.getString(R.string.home_route_go_now)
        } else {
            context.getString(R.string.departure_title, extras.getStringExtra(EXTRA_LEAVE_AT))
        }
        val classLine = extras.getStringExtra(EXTRA_CLASS)
        // Događaj bez mesta nema red sa rutom.
        val text = listOfNotNull(classLine, extras.getStringExtra(EXTRA_ROUTE)).joinToString("\n")
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_place)
            .setColor(context.getColor(R.color.ftn_teal))
            .setContentTitle(title)
            .setContentText(classLine)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            // Bez isteka: propušteno obaveštenje ostaje dok ga korisnik ne skloni ili ga ne zameni sledeći polazak.
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}
