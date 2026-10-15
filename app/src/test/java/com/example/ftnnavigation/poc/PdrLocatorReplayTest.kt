package com.example.ftnnavigation.poc

import androidx.compose.ui.geometry.Offset
import com.example.ftnnavigation.campus.CampusData
import com.example.ftnnavigation.campus.GpsFix
import com.example.ftnnavigation.campus.seedGraph
import com.example.ftnnavigation.graph.BuildingGraph
import com.example.ftnnavigation.graph.INDOOR_BUILDINGS
import com.example.ftnnavigation.graph.IndoorPlan
import com.example.ftnnavigation.graph.NodeType
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
    // PDR_CORRECTION=1: "Ispravi smer" uključen ceo snimak (kako god je bio na telefonu) - za proveru učenja smera.
    private val forceCorrection = System.getenv("PDR_CORRECTION") == "1"

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
        File(outDir, "promasaji.csv").writeText(misses.toString())
        File(outDir, "potvrde.csv").writeText(confirms.toString())
    }

    /**
     * Red `potvrde.csv`: [HandlingGate] je pustio zadržane korake (hod potvrđen iz mirovanja) - koliko ih je, za koliko s,
     * vrhovi, da li je mirovanje bilo u džepu, najveći okret od mirovanja do 1 / 2 / 4 s posle i koraka do sledeće pauze.
     */
    private class Confirm(val file: String, val t: Double, val held: Int, val spanS: Double, val peaks: List<Float>, val pocket: Boolean) {
        var turn1 = 0.0
        var turn2 = 0.0
        var turn4 = 0.0
        var after = 0
        var lastT = t
        fun line() = String.format(Locale.ROOT, "%s,%.2f,%d,%.2f,%s,%s,%.0f,%.0f,%.0f,%d,%.1f", file.removePrefix("hod-"), t, held, spanS,
            peaks.joinToString(" ") { String.format(Locale.ROOT, "%.2f", it) }, pocket, turn1, turn2, turn4, after, lastT - t)
    }

    private val confirms = StringBuilder("snimak,t,zadrzano,raspon,vrhovi,dzep,okret1,okret2,okret4,posle,trajanje\n")

    /**
     * Red `promasaji.csv` za "Ovde sam" ([marked] na [place]): sidro (poslednji START / RUCNO / prelaz - [anchorKind]),
     * PDR put od sidra, prikazana i slobodna pozicija - sve u metrima plana mesta (bez smeštaja).
     */
    private fun missRow(file: File, t: Double, steps: Int, anchorKind: String, locator: PdrLocator, place: PdrPlace, marked: Offset): String {
        fun f(v: Double?) = v?.let { String.format(Locale.ROOT, "%.2f", it) } ?: ""
        val here = locator.place
        val m = locator.localMeters(place, marked)
        val shown = (locator.match?.point?.let { Offset(it.x, it.y) } ?: locator.raw)?.let { locator.localMeters(here, it) }
        val free = locator.raw?.let { locator.localMeters(here, it) }
        val trace = locator.anchorTrace
        val edge = locator.match?.point?.let { "${it.from.type}-${it.to.type}" } ?: ""
        // Koliko je označeno mesto daleko od grafa sprata (tačka zalepljena za graf ne može bliže).
        val offGraph = if (place.isCampus) null else graph.nearestEdgePoint(place.buildingId, place.floor,
            graph.placement(place.buildingId).toMeters(marked.x, marked.y), NodeType.entries.toSet())?.second
        return listOf(
            file.nameWithoutExtension.removePrefix("hod-"), f(t), steps.toString(), anchorKind,
            "${place.buildingId} ${place.floor}", "${here.buildingId} ${here.floor}", "${trace?.place?.buildingId ?: ""} ${trace?.place?.floor ?: ""}",
            (trace?.interrupted ?: false).toString(), f(trace?.anchor?.x), f(trace?.anchor?.y), f(trace?.walkedX), f(trace?.walkedY),
            f(m.x), f(m.y), f(shown?.x), f(shown?.y), f(free?.x), f(free?.y), edge, f(locator.headingBiasDeg.toDouble()),
            f(offGraph),
        ).joinToString(",")
    }

    private val misses = StringBuilder(
        "snimak,t,koraka,sidro,oznaceno,mesto,mesto_sidra,prekinuto,sidro_x,sidro_y,put_x,put_y,x,y,prikaz_x,prikaz_y,slobodna_x,slobodna_y,ivica,ispravka,van_grafa,graf_x,graf_y\n",
    )

    private fun PdrLocator.position(): PointM? {
        val p = match?.point?.let { Offset(it.x, it.y) } ?: raw ?: return null
        return graph.placement(place.buildingId).toMeters(p.x, p.y)
    }

    private fun replay(file: File, locator: PdrLocator): Pair<String, String> {
        val lines = file.readLines().map { it.split(',') }
        locator.clearRedo()
        stepM = 0.8f
        val out = StringBuilder("Snimak ${file.name}\n")
        val track = StringBuilder("t,vrsta,mesto,sprat,x,y,gps_x,gps_y,tacnost,slobodna_x,slobodna_y,ivica,smer,duzina\n")
        val t0 = lines.firstNotNullOf { it.getOrNull(1)?.toLongOrNull() }
        var steps = 0
        // Odakle je poslednje sidro (START, RUCNO, prelaz) - za `promasaji.csv`.
        var anchorKind = "-"
        fun sec(t: Long) = (t - t0) / 1e9
        fun log(t: Long, text: String) {
            val p = locator.position()
            out.appendLine(
                String.format(Locale.ROOT, "%8.1f s  [%4d kor.]  %-40s -> %s %d  (%s)", sec(t), steps, text,
                    locator.place.buildingId, locator.place.floor, p?.let { String.format(Locale.ROOT, "%.1f, %.1f", it.x, it.y) } ?: "-"),
            )
        }
        fun row(t: Long, kind: String, gps: GpsFix? = null, azimuthDeg: Float? = null, lengthM: Float? = null) {
            val p = locator.position() ?: return
            // Slobodna PDR pozicija (pre lepljenja na graf) i ivica na kojoj je tačka; za korak i smer na planu (sa
            // ispravkom) i dužina.
            val placement = graph.placement(locator.place.buildingId)
            val free = locator.match?.let { placement.toMeters(it.x, it.y) }
            val edge = locator.match?.point?.let { "${it.from.id} ${it.to.id} ${String.format(Locale.ROOT, "%.2f", it.t)}" } ?: ""
            track.appendLine(
                String.format(Locale.ROOT, "%.2f,%s,%s,%d,%.2f,%.2f,%s,%s,%s,%s,%s,%s,%s,%s", sec(t), kind, locator.place.buildingId, locator.place.floor,
                    p.x, p.y, gps?.point?.x ?: "", gps?.point?.y ?: "", gps?.accuracyM ?: "",
                    free?.let { String.format(Locale.ROOT, "%.2f", it.x) } ?: "", free?.let { String.format(Locale.ROOT, "%.2f", it.y) } ?: "", edge,
                    azimuthDeg?.let { String.format(Locale.ROOT, "%.0f", locator.headingOnPlan(it)) } ?: "",
                    lengthM?.let { String.format(Locale.ROOT, "%.2f", it) } ?: ""),
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
        // PDR_DETECTOR=1: koraci iz detektora ponovo puštenog kroz A redove (kao na telefonu, korak u trenutku kad prođe vrh),
        // smer iz [WalkingDirection]; S redovi se ne koriste - za proveru izmena detektora koraka.
        val redetect = System.getenv("PDR_DETECTOR") == "1"
        // PDR_DIRECTION=1: smer koraka računa i [WalkingDirection] (kao na telefonu, uz [PdrLocator.nearStairs]), umesto
        // smera zabeleženog u S redu - za proveru izmena smera hoda zajedno sa locator-om.
        val direction = if (redetect || System.getenv("PDR_DIRECTION") == "1") WalkingDirection() else null
        var maxDiff = 0f
        // Koraci uz stanje smera (`-koraci.csv`): vrh, da li je odstupanje poznato (false dok se premešten telefon smiruje).
        val stepRows = StringBuilder("t,vrh,sidro,odstupanje\n")
        // Kao na telefonu: koraci prolaze kroz [HandlingGate] (premeštanje telefona u mestu nije hod).
        val gate = HandlingGate()
        var confirm: Confirm? = null
        fun apply(s: HandlingGate.Step) {
            val lengthM = if (fullSteps) stepM else stepM * AccelStepDetector.stepLengthFactor(s.peak)
            val reason = locator.step(s.walk.headingDeg, lengthM, s.walk.redoSteps, s.timeNs)
            direction?.nearStairs = locator.nearStairs
            direction?.onStairs = locator.onStairs
            if (reason != null) anchorKind = reason.name
            if (reason != null) log(s.timeNs, "replay: $reason")
            row(s.timeNs, "S", azimuthDeg = s.walk.headingDeg, lengthM = lengthM)
        }
        fun walk(t: Long, recorded: WalkingDirection.WalkStep?, peak: Float) {
            lift.onStep(t)
            steps++
            val step = direction?.onStep(t)
            if (direction != null) stepRows.appendLine(String.format(Locale.ROOT, "%.2f,%.2f,%s,%.0f", sec(t), peak, direction.isAnchored, direction.offset))
            if (step != null && recorded != null) {
                if (step.redoSteps != recorded.redoSteps) log(t, "smer: ponovi ${step.redoSteps} (telefon ${recorded.redoSteps})")
                maxDiff = maxOf(maxDiff, abs(angleDiffDeg(step.headingDeg, recorded.headingDeg)))
            }
            val pocket = gate.restInPocket
            val released = gate.onStep(HandlingGate.Step(t, step ?: recorded ?: return, peak))
            if (released.size > 1) {
                confirm?.let { confirms.appendLine(it.line()) }
                confirm = Confirm(file.nameWithoutExtension, sec(t), released.size, sec(t) - sec(released.first().timeNs),
                    released.map { it.peak }, pocket)
            } else if (released.isNotEmpty()) confirm?.let { it.after++; it.lastT = sec(t) }
            released.forEach(::apply)
        }
        fun newDetector() = AccelStepDetector { timeNs, peak -> walk(timeNs, null, peak) }
        var liveDetector = newDetector()
        var redetected = 0
        var recordedSteps = 0
        for ((i, f) in lines.withIndex()) {
            val t = f.getOrNull(1)?.toLongOrNull() ?: continue
            when (f[0]) {
                "R" -> FloatArray(9) { f[2 + it].toFloat() }.let {
                    lift.onRotation(it)
                    direction?.onRotation(it, t)
                    gate.onRotation(it, t).takeIf { n -> n > 0 }?.let { n -> log(t, "odbačeno koraka: $n") }
                    gate.takeReleased().takeIf { r -> r.isNotEmpty() }?.let { released ->
                        confirm?.let { c -> confirms.appendLine(c.line()) }
                        confirm = Confirm(file.nameWithoutExtension, sec(t), released.size, sec(released.last().timeNs) - sec(released.first().timeNs),
                            released.map { s -> s.peak }, false)
                        released.forEach(::apply)
                    }
                    confirm?.let { c ->
                        val dt = sec(t) - c.t
                        if (dt <= 1.0) c.turn1 = maxOf(c.turn1, gate.restAngleDeg)
                        if (dt <= 2.0) c.turn2 = maxOf(c.turn2, gate.restAngleDeg)
                        if (dt <= 4.0) c.turn4 = maxOf(c.turn4, gate.restAngleDeg)
                    }
                }
                "C" -> if (redetect) liveDetector = newDetector()
                "A" -> run {
                    // Redosled kao u [PdrSensorSession]: smer, detektor koraka, lift.
                    direction?.onAccelerometer(f[2].toFloat(), f[3].toFloat(), f[4].toFloat(), t)
                    if (redetect) {
                        val before = steps
                        liveDetector.onAccelerometer(f[2].toFloat(), f[3].toFloat(), f[4].toFloat(), t)
                        redetected += steps - before
                    }
                    lift.onAccelerometer(f[2].toFloat(), f[3].toFloat(), f[4].toFloat(), t)
                }?.let { ride ->
                    val before = locator.place
                    val ride0 = String.format(Locale.ROOT, "%.1f-%.1f s, %+.1f m", sec(ride.startNs), sec(ride.endNs), ride.heightM)
                    when (val outcome = locator.liftRide(ride)) {
                        is LiftOutcome.Moved -> {
                            anchorKind = "LIFT"
                            log(t, "replay: vožnja $ride0, ${before.floor} -> ${outcome.change.toFloor}")
                        }
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
                "S" -> if (redetect) {
                    recordedSteps++
                } else {
                    val recalculated = peaks.removeFirstOrNull()
                    val peak = f.getOrNull(6)?.toFloatOrNull() ?: recalculated ?: AccelStepDetector.STEP_THRESHOLD
                    walk(t, WalkingDirection.WalkStep(f[2].toFloat(), f[3].toInt()), peak)
                }
                "G" -> {
                    val fix = GpsFix(PointM(f[2].toDouble(), f[3].toDouble()), f[4].toFloat(), t)
                    val reason = locator.onGps(fix, tracking = true)
                    if (reason != null) anchorKind = reason.name
                    if (reason != null) log(t, "replay: $reason")
                    row(t, "G", fix)
                }
                "L" -> {
                    val reason = PlaceReason.valueOf(f[2])
                    val place = PdrPlace(f[3], f[4].toInt())
                    // Prekidač ispravke iz zakrenutosti u redu: različita od 0 -> uključen; 0 uz naučenu -> isključen.
                    val bias = f[7].toFloat()
                    if (bias != 0f || forceCorrection) locator.applyCorrection = true
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
                        // Kao [PocViewModel.setPosition]: smer hoda odmah zna da je tačka uz stepenište.
                        direction?.nearStairs = locator.nearStairs
                        direction?.onStairs = locator.onStairs
                        steps = 0
                        anchorKind = "START"
                        row(t, "L")
                    } else if (reason == PlaceReason.RUCNO) {
                        // Prekidač ispravke: iz H reda koji odmah sledi (ako ga ima).
                        lines.getOrNull(i + 1)?.takeIf { it[0] == "H" && it[4] == "MERENO" }?.let { locator.applyCorrection = it[10].toBoolean() || forceCorrection }
                        // Promašaj: tačka pre "Ovde sam" naspram označene (drugi sprat/zgrada - bez broja).
                        val placeBefore = locator.place
                        val before = locator.position()
                        val missLine = missRow(file, sec(t), steps, anchorKind, locator, place, Offset(f[5].toFloat(), f[6].toFloat()))
                        val check = locator.setPosition(place, Offset(f[5].toFloat(), f[6].toFloat()))
                        direction?.nearStairs = locator.nearStairs
                        direction?.onStairs = locator.onStairs
                        // Označeno mesto zalepljeno za graf (ono što aplikacija posle prikazuje).
                        val snapped = (locator.match?.point?.let { Offset(it.x, it.y) } ?: locator.raw)?.let { locator.localMeters(place, it) }
                        misses.appendLine(missLine + String.format(Locale.ROOT, ",%.2f,%.2f", snapped?.x ?: Double.NaN, snapped?.y ?: Double.NaN))
                        val miss = before?.takeIf { placeBefore == place }?.let { b -> locator.position()?.let { hypot(it.x - b.x, it.y - b.y) } }
                        log(t, "RUCNO ${place.buildingId} ${place.floor}" + (miss?.let { String.format(Locale.ROOT, "  promašaj %.1f m", it) } ?: "  promašaj -") + ((check as? HeadingCheck.Measured)?.let {
                            String.format(Locale.ROOT, "  greška %+.1f° put %.0f %%", it.errorDeg, 100 * it.walkedM / it.actualM)
                        } ?: "") + String.format(Locale.ROOT, "  hodnik %+.1f°", locator.corridorBiasDeg))
                        steps = 0
                        anchorKind = "RUCNO"
                        row(t, "L")
                    } else if (reason == PlaceReason.LIFT) {
                        // Telefon: vožnja ili izbor u pitanju (snimci do 08.10.2026: pitanje posle stajanja). Replay je već
                        // na tom spratu (sam prepoznao vožnju) - ništa; čeka pitanje - izbor; inače se postavlja kao telefon.
                        val note = when {
                            locator.liftPrompt != null -> if (locator.selectLiftFloor(place.floor) != null) {
                                anchorKind = "LIFT"
                                ""
                            } else {
                                " (?)"
                            }
                            locator.place == place -> " (replay već tu)"
                            else -> {
                                anchorKind = "LIFT"
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
        confirm?.let { confirms.appendLine(it.line()) }
        if (direction != null) File("build/pdr-replay", file.nameWithoutExtension + "-koraci.csv").writeText(stepRows.toString())
        if (redetect) out.appendLine("Detektor: $redetected koraka (telefon $recordedSteps)")
        else if (peaks.isNotEmpty()) out.appendLine("Detektor ponovo pušten: ${peaks.size} koraka više nego S redova (vrhovi možda pomereni)")
        if (direction != null) out.appendLine(String.format(Locale.ROOT, "Smer (WalkingDirection): najveća razlika od telefona %.0f°", maxDiff))
        return out.toString() to track.toString()
    }
}
