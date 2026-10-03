package com.example.ftnnavigation.poc

import androidx.compose.ui.geometry.Offset
import com.example.ftnnavigation.campus.CampusData
import com.example.ftnnavigation.campus.GpsFix
import com.example.ftnnavigation.campus.seedGraph
import com.example.ftnnavigation.graph.BuildingGraph
import com.example.ftnnavigation.graph.INDOOR_BUILDINGS
import com.example.ftnnavigation.graph.IndoorPlan
import com.example.ftnnavigation.graph.PointM
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Pušta snimak sa telefona ([SensorRecorder]) kroz [PdrLocator]: koraci (S - smer i ponavljanje kako ih je dao
 * telefon), GPS (G) i ručna označavanja (L RUCNO, sa stanjem prekidača "Ispravi smer" iz H reda). Prelazi
 * (ULAZ, PROLAZ, GPS_IZLAZ...) se ne prepisuju iz snimka - računa ih locator, pa se porede sa zabeleženim.
 * Izlaz: `app/build/pdr-replay/<snimak>-mesto.txt` (događaji) i `-putanja.csv` (pozicija po koraku/GPS-u u
 * metrima kampusa). Samo uz PDR_REPLAY (fajl ili folder):
 * ```
 * PDR_REPLAY=../pdr/hod-20261003-122546.csv ./gradlew :app:testDebugUnitTest --tests '*PdrLocatorReplayTest*' --rerun
 * ```
 */
class PdrLocatorReplayTest {

    private val campus = CampusData.parse(File("src/main/assets/campus.json").readText())
    private val graph = seedGraph(campus, INDOOR_BUILDINGS.map { IndoorPlan.parse(File("src/main/assets/${it.asset}").readText()) })
        .let { (nodes, edges) -> BuildingGraph(nodes, edges, campus.placements()) }

    private val declination = 5.5f
    private val stepM = 0.8f

    @Test
    fun replay() {
        val path = System.getenv("PDR_REPLAY")
        assumeTrue("PDR_REPLAY nije zadat", !path.isNullOrBlank())
        val input = File(path!!)
        val files = if (input.isDirectory) input.listFiles { f -> f.extension == "csv" }!!.sorted() else listOf(input)
        val outDir = File("build/pdr-replay").apply { mkdirs() }
        // Snimci iste sesije (bez Reset-a) dele locator - naučena zakrenutost smera prelazi u sledeći snimak.
        val locator = PdrLocator(graph, campus, declination)
        for (file in files) {
            val (events, track) = replay(file, locator)
            File(outDir, file.nameWithoutExtension + "-mesto.txt").writeText(events)
            File(outDir, file.nameWithoutExtension + "-putanja.csv").writeText(track)
            println(events)
        }
    }

    private fun PdrLocator.position(): PointM? {
        val p = match?.point?.let { Offset(it.x, it.y) } ?: raw ?: return null
        return graph.placement(place.buildingId).toMeters(p.x, p.y)
    }

    private fun replay(file: File, locator: PdrLocator): Pair<String, String> {
        val lines = file.readLines().map { it.split(',') }
        locator.clearRedo()
        val out = StringBuilder("Snimak ${file.name}\n")
        val track = StringBuilder("t,vrsta,mesto,sprat,x,y,gps_x,gps_y,tacnost,slobodna_x,slobodna_y,ivica\n")
        val t0 = lines.firstNotNullOf { it.getOrNull(1)?.toLongOrNull() }
        var steps = 0
        fun sec(t: Long) = (t - t0) / 1e9
        fun log(t: Long, text: String) {
            val p = locator.position()
            out.appendLine(
                String.format(Locale.ROOT, "%8.1f s  [%4d kor.]  %-40s -> %s %d  (%s)", sec(t), steps, text,
                    locator.place.buildingId, locator.place.floor, p?.let { String.format(Locale.ROOT, "%.1f, %.1f", it.x, it.y) } ?: "-"),
            )
        }
        fun row(t: Long, kind: String, gps: GpsFix? = null) {
            val p = locator.position() ?: return
            // Slobodna PDR pozicija (pre lepljenja na graf) i ivica na kojoj je tačka.
            val placement = graph.placement(locator.place.buildingId)
            val free = locator.match?.let { placement.toMeters(it.x, it.y) }
            val edge = locator.match?.point?.let { "${it.from.id} ${it.to.id} ${String.format(Locale.ROOT, "%.2f", it.t)}" } ?: ""
            track.appendLine(
                String.format(Locale.ROOT, "%.2f,%s,%s,%d,%.2f,%.2f,%s,%s,%s,%s,%s,%s", sec(t), kind, locator.place.buildingId, locator.place.floor,
                    p.x, p.y, gps?.point?.x ?: "", gps?.point?.y ?: "", gps?.accuracyM ?: "",
                    free?.let { String.format(Locale.ROOT, "%.2f", it.x) } ?: "", free?.let { String.format(Locale.ROOT, "%.2f", it.y) } ?: "", edge),
            )
        }
        for ((i, f) in lines.withIndex()) {
            val t = f.getOrNull(1)?.toLongOrNull() ?: continue
            when (f[0]) {
                "S" -> {
                    steps++
                    val reason = locator.step(f[2].toFloat(), stepM, f[3].toInt())
                    if (reason != null) log(t, "replay: $reason")
                    row(t, "S")
                }
                "G" -> {
                    val fix = GpsFix(PointM(f[2].toDouble(), f[3].toDouble()), f[4].toFloat(), t)
                    val reason = locator.onGps(fix, tracking = true)
                    if (reason != null) log(t, "replay: $reason")
                    row(t, "G", fix)
                }
                "L" -> {
                    val reason = PlaceReason.valueOf(f[2])
                    val place = PdrPlace(f[3], f[4].toInt())
                    // Prekidač ispravke iz zakrenutosti u redu: različita od 0 -> uključen; 0 uz naučenu -> isključen.
                    val bias = f[7].toFloat()
                    if (bias != 0f) locator.applyCorrection = true
                    else if (place.buildingId == locator.place.buildingId && locator.headingErrorDeg?.let { it != 0f } == true) locator.applyCorrection = false
                    if (reason == PlaceReason.RUCNO) {
                        // Prekidač ispravke: iz H reda koji odmah sledi (ako ga ima).
                        lines.getOrNull(i + 1)?.takeIf { it[0] == "H" && it[4] == "MERENO" }?.let { locator.applyCorrection = it[10].toBoolean() }
                        val check = locator.setPosition(place, Offset(f[5].toFloat(), f[6].toFloat()))
                        log(t, "RUCNO ${place.buildingId} ${place.floor}" + ((check as? HeadingCheck.Measured)?.let {
                            String.format(Locale.ROOT, "  greška %+.1f° put %.0f %%", it.errorDeg, 100 * it.walkedM / it.actualM)
                        } ?: ""))
                        steps = 0
                        row(t, "L")
                    } else {
                        log(t, "telefon: $reason ${place.buildingId} ${place.floor}")
                    }
                }
            }
        }
        return out.toString() to track.toString()
    }
}
