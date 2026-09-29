package com.example.ftnnavigation.poc

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
 * - `S,t,smer,ponovi,odstupanje,smiren` - korak i šta je [WalkingDirection] tada vratio,
 * - `P,t` / `C,t` - Mapa u pauzi (senzori odjavljeni) / ponovo aktivna.
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

    fun step(timestampNs: Long, step: WalkingDirection.WalkStep, direction: WalkingDirection) =
        write("S,$timestampNs,${step.headingDeg},${step.redoSteps},${direction.offset},${direction.isAnchored}")

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
