package com.example.ftnnavigation.poc

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * Pušta snimak hoda sa telefona ([SensorRecorder]) kroz [WalkingDirection] i piše tabelu po
 * koraku u `app/build/pdr-replay/<snimak>.txt`. Radi samo uz promenljivu PDR_REPLAY (fajl ili
 * folder sa .csv snimcima), inače se preskače:
 * ```
 * PDR_REPLAY=../pdr ./gradlew :app:testDebugUnitTest --tests '*WalkingDirectionReplayTest*' --rerun
 * ```
 * Dok je kod isti kao na telefonu, ponovljen smer se poklapa sa zabeleženim (razlika samo na
 * početku - na telefonu je orijentacija već bila izglađena pre Start-a). Posle izmene pragova
 * kolona "razlika" pokazuje šta se promenilo.
 */
class WalkingDirectionReplayTest {

    @Test
    fun replay() {
        val path = System.getenv("PDR_REPLAY")
        assumeTrue("PDR_REPLAY nije zadat", !path.isNullOrBlank())
        val input = File(path!!)
        val files = if (input.isDirectory) input.listFiles { f -> f.extension == "csv" }!!.sorted() else listOf(input)
        val outDir = File("build/pdr-replay").apply { mkdirs() }
        for (file in files) {
            val report = replay(file)
            File(outDir, file.nameWithoutExtension + ".txt").writeText(report)
            println(report)
        }
    }

    private fun replay(file: File): String {
        val direction = WalkingDirection()
        var detected = 0
        var detector = AccelStepDetector { _, _ -> detected++ }
        val matrix = FloatArray(9)
        var firstNs: Long? = null
        var steps = 0
        var maxDiff = 0f
        val out = StringBuilder()
        out.appendLine("Snimak ${file.name}")
        out.appendLine("   t [s]   #  zabeleženo  ponovljeno  razlika  ponovi  odstupanje  smiren  | ponovljeno: odstupanje  smiren")

        file.forEachLine { line ->
            val f = line.split(',')
            val t = f.getOrNull(1)?.toLongOrNull() ?: return@forEachLine
            val start = firstNs ?: t.also { firstNs = it }
            val sec = (t - start) / 1e9
            when (f[0]) {
                "R" -> {
                    for (i in 0..8) matrix[i] = f[2 + i].toFloat()
                    direction.onRotation(matrix, t)
                }
                "A" -> {
                    val (x, y, z) = f.subList(2, 5).map { it.toFloat() }
                    direction.onAccelerometer(x, y, z, t)
                    detector.onAccelerometer(x, y, z, t)
                }
                "S" -> {
                    steps++
                    val recorded = f[2].toFloat()
                    val step = direction.onStep(t) ?: return@forEachLine
                    val diff = angleDiffDeg(step.headingDeg, recorded)
                    maxDiff = maxOf(maxDiff, abs(diff))
                    out.appendLine(
                        String.format(
                            Locale.ROOT, "%8.2f %3d  %9.0f°  %9.0f°  %6.0f°  %3d/%-2d  %9.0f°  %6s  | %21.0f°  %6s",
                            sec, steps, recorded, step.headingDeg, diff, f[3].toInt(), step.redoSteps,
                            f[4].toDouble(), f[5], direction.offset, direction.isAnchored,
                        ),
                    )
                }
                "P" -> out.appendLine(String.format(Locale.ROOT, "%8.2f  --- pauza (senzori odjavljeni) ---", sec))
                "C" -> {
                    // Na telefonu se posle pauze pravi nov detektor koraka.
                    detector = AccelStepDetector { _, _ -> detected++ }
                    out.appendLine(String.format(Locale.ROOT, "%8.2f  --- nastavak ---", sec))
                }
            }
        }
        out.appendLine("Koraka: $steps zabeleženo, $detected ponovo detektovano; najveća razlika smera ${maxDiff.toInt()}°")
        return out.toString()
    }
}
