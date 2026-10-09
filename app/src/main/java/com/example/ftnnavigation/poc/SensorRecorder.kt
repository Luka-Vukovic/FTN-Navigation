package com.example.ftnnavigation.poc

import androidx.compose.ui.geometry.Offset
import com.example.ftnnavigation.campus.GpsFix
import java.io.BufferedWriter
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Snimak PDR senzora (samo debug build, od Start do Stop) - da se smer hoda proveri i podesi
 * van telefona: test `WalkingDirectionReplayTest` pušta snimak kroz [WalkingDirection].
 *
 * CSV, jedan red po događaju (vreme = `SensorEvent.timestamp`, ns):
 * - `R,t,m0..m8` - matrica rotacije (iz `getRotationMatrixFromVector`),
 * - `A,t,x,y,z` - akcelerometar (koordinate telefona),
 * - `S,t,smer,ponovi,odstupanje,smiren,vrh` - korak i šta je [WalkingDirection] tada vratio (vrh: od 09.10.2026 uveče,
 *   visina vrha koraka - [AccelStepDetector.stepLengthFactor]; t je vreme prelaska praga, red se upisuje posle vrha),
 * - `P,t` / `C,t` - Mapa u pauzi (senzori odjavljeni) / ponovo aktivna,
 * - `G,t,x,y,tačnost_m` - GPS lokacija u metrima kampusa (t = `Location.elapsedRealtimeNanos`, isti
 *   sat); pun GPS snimak je u [GpsRecorder],
 * - `L,t,razlog,zgrada,sprat,x,y,ispravka_smera` - pozicija preskočila ([PlaceReason]: ručno, ulaz, prolaz,
 *   GPS); x/y relativno na plan (kampus: cela mapa), t = `SystemClock.elapsedRealtimeNanos`,
 * - `H,t,zgrada,sprat,ishod,greška,uz_ispravku,pdr_m,stvarno_m,pouzdano,primenjeno` - poređenje puta pri
 *   označavanju ([HeadingCheck]; ishod MERENO / KRATKO / PREKINUTO, prazna polja kad nema vrednosti). Greška =
 *   PDR smer − stvarni bez ispravke (°), uz_ispravku = sa ispravkom koja je važila na putu; isti t kao `L` red.
 * - `Q,t,PITANJE,lift,sprat` / `Q,t,ODUSTAO` - pitanje za lift postavljeno ([LiftPrompt]) / "Nisam u liftu"; izabran
 *   sprat je `L` red sa razlogom LIFT. t kao u `L` redu.
 * - `V,t,početak,kraj,pomeraj_m` - prepoznata vožnja liftom ([LiftRide]; t = uzorak na kome je prepoznata, isti sat kao
 *   `A`); promena sprata posle nje je `L` red LIFT, a nesiguran broj spratova `Q` PITANJE.
 * - `K,t,dužina_m` - dužina koraka iz Podešavanja (na Start i pri promeni; snimci pre 08.10.2026 je nemaju - tada 0,8 m).
 *   t kao u `L` redu.
 *
 * Fajlovi: `files/pdr/` aplikacije (`adb exec-out run-as <paket> cat files/pdr/<fajl>`).
 */
class SensorRecorder private constructor(val file: File) {
    private val out: BufferedWriter = file.bufferedWriter(bufferSize = 64 * 1024)

    // Senzori se odjavljuju tek posle Stop (efekat Mape) - kasni događaji se tada ne upisuju.
    private var closed = false

    fun rotation(timestampNs: Long, matrix: FloatArray) {
        write("R,$timestampNs," + matrix.take(9).joinToString(","))
    }

    fun accelerometer(timestampNs: Long, x: Float, y: Float, z: Float) = write("A,$timestampNs,$x,$y,$z")

    fun step(timestampNs: Long, step: WalkingDirection.WalkStep, direction: WalkingDirection, peak: Float) =
        write("S,$timestampNs,${step.headingDeg},${step.redoSteps},${direction.offset},${direction.isAnchored},$peak")

    fun gps(fix: GpsFix) = write("G,${fix.elapsedNs},${fix.point.x},${fix.point.y},${fix.accuracyM}")

    /** Pozicija preskočila ([PdrLocator]): označena ručno, ulaz/prolaz, GPS. */
    fun place(timestampNs: Long, reason: PlaceReason, place: PdrPlace, point: Offset?, headingBiasDeg: Float) =
        write("L,$timestampNs,$reason,${place.buildingId},${place.floor},${point?.x},${point?.y},$headingBiasDeg")

    /** Poređenje PDR puta sa stvarnim pri označavanju (merilo greške smera). */
    fun headingCheck(timestampNs: Long, place: PdrPlace, check: HeadingCheck) {
        val fields = when (check) {
            is HeadingCheck.Measured -> with(check) {
                "MERENO,$errorDeg,$residualDeg,$walkedM,$actualM,$reliable,$applied"
            }
            is HeadingCheck.TooShort -> "KRATKO,,,${check.walkedM},${check.actualM},,"
            HeadingCheck.Interrupted -> "PREKINUTO,,,,,,"
        }
        write("H,$timestampNs,${place.buildingId},${place.floor},$fields")
    }

    /** Pitanje za lift postavljeno ([prompt]) ili odbijeno (null). */
    fun lift(timestampNs: Long, prompt: LiftPrompt?) =
        write(if (prompt != null) "Q,$timestampNs,PITANJE,${prompt.lift.id},${prompt.fromFloor}" else "Q,$timestampNs,ODUSTAO")

    /** Dužina koraka (Podešavanja) - na Start i pri promeni za vreme praćenja. */
    fun stepLength(timestampNs: Long, meters: Float) = write("K,$timestampNs,$meters")

    /** Prepoznata vožnja liftom. */
    fun liftRide(timestampNs: Long, ride: LiftRide) = write("V,$timestampNs,${ride.startNs},${ride.endNs},${ride.heightM}")

    /** Senzori odjavljeni (Mapa u pozadini, ekran ugašen) - snimak se upisuje do tu. */
    fun pause(timestampNs: Long) {
        write("P,$timestampNs")
        if (!closed) out.flush()
    }

    fun resume(timestampNs: Long) = write("C,$timestampNs")

    fun close() {
        if (closed) return
        closed = true
        out.close()
    }

    private fun write(line: String) {
        if (closed) return
        out.write(line)
        out.newLine()
    }

    companion object {
        private val NAME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        fun start(filesDir: File): SensorRecorder {
            val dir = File(filesDir, "pdr").apply { mkdirs() }
            return SensorRecorder(File(dir, "hod-${LocalDateTime.now().format(NAME)}.csv"))
        }
    }
}
