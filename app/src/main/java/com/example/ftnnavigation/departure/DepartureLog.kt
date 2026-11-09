package com.example.ftnnavigation.departure

import android.content.Context
import com.example.ftnnavigation.BuildConfig
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Dnevnik obaveštenja o polasku (samo debug build) - za dijagnozu zakasnelih obaveštenja. Teren 09.11.2026: snimak ekrana od
 * 04.11. - čas u 14:15, obaveštenje "Kreni odmah" stiglo u 15:39, a sistemski log telefona (`logcat -b events`) je počinjao tek
 * tog dana, pa se uzrok nije video. Jedan fajl, dopisuje se; svaki red odmah.
 *
 * CSV (vremena lokalno, `yyyy-MM-dd HH:mm:ss`):
 * - `Z,sada,povod,notifyAt,tačan|prozor,stavka` - zakazan alarm (povod: ko je pozvao zakazivanje),
 * - `N,sada,povod` - nema polaska (alarm obrisan),
 * - `A,sada,notifyAt,kašnjenje_s,prikazano` - alarm stigao u receiver (prikazano = obaveštenja uključena),
 * - `P,sada,notifyAt,stavka` - prikazano propušteno obaveštenje (pri zakazivanju).
 *
 * Fajl: `files/obavestenja/obavestenja.csv` (`adb exec-out run-as <paket> cat files/obavestenja/obavestenja.csv`).
 */
object DepartureLog {
    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun scheduled(context: Context, reason: String, notifyAt: LocalDateTime, exact: Boolean, item: String) =
        write(context, "Z,${now()},$reason,${notifyAt.format(TIME)},${if (exact) "tačan" else "prozor"},${clean(item)}")

    fun nothing(context: Context, reason: String) = write(context, "N,${now()},$reason")

    fun alarm(context: Context, notifyAtMs: Long, shown: Boolean) {
        val delayS = (System.currentTimeMillis() - notifyAtMs) / 1000
        write(context, "A,${now()},${format(notifyAtMs)},$delayS,$shown")
    }

    fun missed(context: Context, notifyAt: LocalDateTime, item: String) =
        write(context, "P,${now()},${notifyAt.format(TIME)},${clean(item)}")

    private fun clean(text: String) = text.replace(',', ';').replace('\n', ' ')

    private fun now() = LocalDateTime.now().format(TIME)

    private fun format(ms: Long) = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault()).format(TIME)

    private fun write(context: Context, line: String) {
        if (!BuildConfig.DEBUG) return
        // Dnevnik ne sme da obori zakazivanje ni receiver.
        runCatching {
            val dir = File(context.filesDir, "obavestenja").apply { mkdirs() }
            File(dir, "obavestenja.csv").appendText(line + "\n")
        }
    }
}
