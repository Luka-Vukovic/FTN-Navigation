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
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Pušta snimak sa telefona ([SensorRecorder]) kroz [PdrLocator]: koraci (S - smer i ponavljanje kako ih je dao
 * telefon), GPS (G), ručna označavanja (L RUCNO, sa stanjem prekidača "Ispravi smer" iz H reda) i odgovori na pitanje
 * za lift (L LIFT, Q ODUSTAO). Vožnje liftom replay prepoznaje sam ([LiftRideDetector] nad redovima R, A i S). Prelazi
 * (ULAZ, PROLAZ, GPS_IZLAZ, LIFT...) se ne prepisuju iz snimka - računa ih locator, pa se porede sa zabeleženim.
 * Izlaz: `app/build/pdr-replay/<snimak>-mesto.txt` (događaji) i `-putanja.csv` (pozicija po koraku/GPS-u u
 * metrima kampusa). Samo uz PDR_REPLAY (fajl ili folder):
 * ```
 * PDR_REPLAY=../pdr/hod-20261003-122546.csv ./gradlew :app:testDebugUnitTest --tests '*PdrLocatorReplayTest*' --rerun
 * ```
 */
class PdrLocatorReplayTest {

    private val campus = CampusData.parse(File("src/main/assets/campus.json").readText())
    private val plans = INDOOR_BUILDINGS.map { IndoorPlan.parse(File("src/main/assets/${it.asset}").readText()) }
    private val stairPaths = plans.flatMap { it.stairPaths() }
    private val graph = seedGraph(campus, plans)
        .let { (nodes, edges) -> BuildingGraph(nodes, edges, campus.placements()) }

    private val declination = 5.5f

    // PDR_FULL_STEPS=1: svaki korak pune dužine (kao pre 09.10.2026 uveče) - za poređenje.
    private val fullSteps = System.getenv("PDR_FULL_STEPS") == "1"
    // Snimci bez `K` reda (pre 08.10.2026) su hodani sa 0,8 m.
    private var stepM = 0.8f

    @Test
    fun replay() {
        val path = System.getenv("PDR_REPLAY")
        assumeTrue("PDR_REPLAY nije zadat", !path.isNullOrBlank())
        val input = File(path!!)
        val files = if (input.isDirectory) input.listFiles { f -> f.extension == "csv" }!!.sorted() else listOf(input)
        val outDir = File("build/pdr-replay").apply { mkdirs() }
        // Snimci iste sesije (bez Reset-a) dele locator - naučena zakrenutost smera prelazi u sledeći snimak.
        val locator = PdrLocator(graph, campus, declination, stairPaths)
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
        stepM = 0.8f
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
        // Vrh koraka (dužina koraka po jačini): iz S reda (od 09.10.2026 uveče), a za starije snimke iz istog detektora
        // ponovo pušten kroz A redove (k-ti detektovan korak = k-ti S red; posle pauze nov detektor, kao na telefonu).
        val peaks = ArrayDeque<Float>()
        var detector = AccelStepDetector { _, peak -> peaks.addLast(peak) }
        for (f in lines) when (f[0]) {
            "A" -> detector.onAccelerometer(f[2].toFloat(), f[3].toFloat(), f[4].toFloat(), f[1].toLong())
            "C" -> detector = AccelStepDetector { _, peak -> peaks.addLast(peak) }
        }
        val lift = LiftRideDetector()
        // PDR_DIRECTION=1: smer koraka računa i [WalkingDirection] (kao na telefonu, uz [PdrLocator.nearStairs]), umesto
        // smera zabeleženog u S redu - za proveru izmena smera hoda zajedno sa locator-om.
        val direction = if (System.getenv("PDR_DIRECTION") == "1") WalkingDirection() else null
        var maxDiff = 0f
        for ((i, f) in lines.withIndex()) {
            val t = f.getOrNull(1)?.toLongOrNull() ?: continue
            when (f[0]) {
                "R" -> FloatArray(9) { f[2 + it].toFloat() }.let {
                    lift.onRotation(it)
                    direction?.onRotation(it, t)
                }
                "A" -> lift.onAccelerometer(f[2].toFloat(), f[3].toFloat(), f[4].toFloat(), t).also {
                    direction?.onAccelerometer(f[2].toFloat(), f[3].toFloat(), f[4].toFloat(), t)
                }?.let { ride ->
                    val before = locator.place
                    val ride0 = String.format(Locale.ROOT, "%.1f-%.1f s, %+.1f m", sec(ride.startNs), sec(ride.endNs), ride.heightM)
                    when (val outcome = locator.liftRide(ride)) {
                        is LiftOutcome.Moved -> log(t, "replay: vožnja $ride0, ${before.floor} -> ${outcome.change.toFloor}")
                        is LiftOutcome.Ask -> log(t, "replay: vožnja $ride0, pitanje (predlog ${outcome.prompt.suggested})")
                        null -> log(t, "replay: vožnja $ride0, nije kod lifta")
                    }
                    row(t, "V")
                }
                "V" -> log(t, String.format(Locale.ROOT, "telefon: vožnja %+.1f m", f[4].toDouble()))
                "Q" -> if (f[2] == "ODUSTAO") {
                    locator.dismissLift()
                    log(t, "telefon: nisam u liftu")
                } else {
                    log(t, "telefon: pitanje za lift ${f[3]}")
                }
                "K" -> stepM = f[2].toFloat()
                "S" -> {
                    lift.onStep(t)
                    steps++
                    val recalculated = peaks.removeFirstOrNull()
                    val peak = f.getOrNull(6)?.toFloatOrNull() ?: recalculated ?: AccelStepDetector.STEP_THRESHOLD
                    var heading = f[2].toFloat()
                    var redo = f[3].toInt()
                    direction?.onStep(t)?.let { step ->
                        if (step.redoSteps != redo) log(t, "smer: ponovi ${step.redoSteps} (telefon $redo)")
                        maxDiff = maxOf(maxDiff, abs(angleDiffDeg(step.headingDeg, heading)))
                        heading = step.headingDeg
                        redo = step.redoSteps
                    }
                    val reason = locator.step(heading, if (fullSteps) stepM else stepM * AccelStepDetector.stepLengthFactor(peak), redo, t)
                    direction?.nearStairs = locator.nearStairs
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
                    else if (locator.headingErrorDeg?.let { it != 0f } == true) locator.applyCorrection = false
                    if (reason == PlaceReason.START) {
                        // Zakrenutost kakvu je telefon tada primenjivao (naučena u replay-u može malo da odstupa).
                        // Snimci do 09.10.2026 uveče: ispravka je bila po zgradi - ovde postaje zajednička.
                        if (bias != 0f) locator.restoreBias(bias)
                        // Pozicija na Start (od 05.10.2026): replay kreće odatle ako je drugde (Ovde sam pre Start-a).
                        val at = Offset(f[5].toFloat(), f[6].toFloat())
                        val now = locator.match?.point?.let { Offset(it.x, it.y) } ?: locator.raw
                        val scale = graph.placement(place.buildingId).scale
                        val apart = now?.let { hypot((it.x - at.x) * scale.widthM, (it.y - at.y) * scale.heightM) }
                        if (place != locator.place || apart == null || apart > 0.5) {
                            locator.setPosition(place, at, measure = false)
                            log(t, "START ${place.buildingId} ${place.floor} (postavljeno)")
                        } else {
                            log(t, "START ${place.buildingId} ${place.floor}")
                        }
                        steps = 0
                        row(t, "L")
                    } else if (reason == PlaceReason.RUCNO) {
                        // Prekidač ispravke: iz H reda koji odmah sledi (ako ga ima).
                        lines.getOrNull(i + 1)?.takeIf { it[0] == "H" && it[4] == "MERENO" }?.let { locator.applyCorrection = it[10].toBoolean() }
                        val check = locator.setPosition(place, Offset(f[5].toFloat(), f[6].toFloat()))
                        log(t, "RUCNO ${place.buildingId} ${place.floor}" + ((check as? HeadingCheck.Measured)?.let {
                            String.format(Locale.ROOT, "  greška %+.1f° put %.0f %%", it.errorDeg, 100 * it.walkedM / it.actualM)
                        } ?: ""))
                        steps = 0
                        row(t, "L")
                    } else if (reason == PlaceReason.LIFT) {
                        // Telefon: vožnja ili izbor u pitanju (snimci do 08.10.2026: pitanje posle stajanja). Replay je već
                        // na tom spratu (sam prepoznao vožnju) - ništa; čeka pitanje - izbor; inače se postavlja kao telefon.
                        val note = when {
                            locator.liftPrompt != null -> if (locator.selectLiftFloor(place.floor) != null) "" else " (?)"
                            locator.place == place -> " (replay već tu)"
                            else -> {
                                locator.setPosition(place, Offset(f[5].toFloat(), f[6].toFloat()), measure = false)
                                " (replay nije prepoznao - postavljeno)"
                            }
                        }
                        log(t, "telefon: LIFT ${place.buildingId} ${place.floor}$note")
                        row(t, "L")
                    } else {
                        log(t, "telefon: $reason ${place.buildingId} ${place.floor}")
                    }
                }
            }
        }
        if (peaks.isNotEmpty()) out.appendLine("Detektor ponovo pušten: ${peaks.size} koraka više nego S redova (vrhovi možda pomereni)")
        if (direction != null) out.appendLine(String.format(Locale.ROOT, "Smer (WalkingDirection): najveća razlika od telefona %.0f°", maxDiff))
        return out.toString() to track.toString()
    }
}
