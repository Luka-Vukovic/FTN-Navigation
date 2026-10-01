package com.example.ftnnavigation.poc

import android.location.Location
import com.example.ftnnavigation.campus.GpsFix
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Snimak GPS-a (samo debug build) - za proveru tačnosti i pragova [com.example.ftnnavigation.campus.BuildingDetector]
 * na terenu. Snima dok GPS radi (Mapa na ekranu ili praćenje), nezavisno od PDR snimka; jedan fajl
 * po danu, dopisuje se. Svaki red se odmah upisuje (lokacija stiže jednom u sekundi).
 *
 * CSV (t = `elapsedRealtimeNanos`, isti sat kao `SensorEvent.timestamp` u PDR snimku; vreme = HH:mm:ss):
 * - `O,t,vreme,provider` - GPS uključen,
 * - `G,t,vreme,lat,lon,tačnost_m,x,y` - lokacija (x/y u metrima kampusa),
 * - `Z,t,vreme,zgrada` - promena zgrade ([com.example.ftnnavigation.campus.BuildingDetector]); `-` = napolju,
 * - `X,t,vreme` - GPS isključen.
 *
 * Fajlovi: `files/gps/` aplikacije (`adb exec-out run-as <paket> cat files/gps/<fajl>`).
 */
class GpsRecorder(private val filesDir: File) {
    private var out: BufferedWriter? = null

    fun started(timeNs: Long, provider: String) {
        if (out == null) {
            val dir = File(filesDir, "gps").apply { mkdirs() }
            // Dopisivanje: Mapa se otvara i zatvara više puta dnevno.
            out = FileOutputStream(File(dir, "gps-${LocalDate.now().format(DAY)}.csv"), true).bufferedWriter()
        }
        write("O,$timeNs,${now()},$provider")
    }

    fun location(location: Location, fix: GpsFix) =
        write("G,${fix.elapsedNs},${now()},${location.latitude},${location.longitude},${fix.accuracyM},${fix.point.x},${fix.point.y}")

    fun building(timeNs: Long, buildingId: String?) = write("Z,$timeNs,${now()},${buildingId ?: "-"}")

    fun stopped(timeNs: Long) {
        write("X,$timeNs,${now()}")
        out?.close()
        out = null
    }

    private fun write(line: String) {
        val out = out ?: return
        out.write(line)
        out.newLine()
        out.flush()
    }

    private fun now() = LocalTime.now().format(TIME)

    private companion object {
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}
