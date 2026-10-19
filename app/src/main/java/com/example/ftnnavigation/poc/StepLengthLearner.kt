package com.example.ftnnavigation.poc

import com.example.ftnnavigation.campus.GpsFix
import com.example.ftnnavigation.graph.PointM
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Dužina koraka iz GPS-a napolju (korisnik, teren 19.10.2026: "treba razmisliti o nekakvom pametnom učenju dužine koraka",
 * "neka se to radi napolju"). Na deonici od bar [MIN_DISTANCE_M] po GPS-u (lokacije do [MAX_ACCURACY_M]) poredi se GPS pomeraj
 * sa zbirom koraka (vektori smera koraka puta [AccelStepDetector.stepLengthFactor], bez podešene dužine): dužina punog koraka =
 * GPS pomeraj / pomeraj u koracima. Deonica se broji samo ako je hod bio skoro prav ([STRAIGHT_RATIO] - greška smera tada malo
 * menja pomeraj), a dužina je u opsegu [MIN_STEP_M]..[MAX_STEP_M]. Naučena dužina je medijana poslednjih [SAMPLES] deonica (od
 * [MIN_SAMPLES]) - GPS na krajevima deonice greši par metara.
 *
 * U zgradi se ne uči: "Ovde sam" u zgradama je davalo put 82-143 % (19.10.), a rasipanje je od situacije (džep, ulazak), ne od
 * dužine koraka; napolju je na 146 m bilo 98 %.
 */
class StepLengthLearner(samples: List<Float> = emptyList()) {
    private var start: PointM? = null
    private var sumX = 0.0
    private var sumY = 0.0
    private var units = 0.0
    private val recent = ArrayDeque(samples.takeLast(SAMPLES))

    /** Dužine koraka sa poslednjih deonica (za čuvanje). */
    val samples: List<Float> get() = recent.toList()

    /** Prekid deonice (u zgradi, smer nepoznat, kraj praćenja); naučeno ostaje. */
    fun reset() {
        start = null
        sumX = 0.0
        sumY = 0.0
        units = 0.0
    }

    /** Ručno podešena dužina - naučeno se briše. */
    fun clear() {
        recent.clear()
        reset()
    }

    /** Korak napolju: smer na mapi kampusa (stepeni, sever gore) i deo pune dužine ([AccelStepDetector.stepLengthFactor]). */
    fun onStep(headingDeg: Float, factor: Float) {
        if (start == null) return
        val rad = Math.toRadians(headingDeg.toDouble())
        sumX += factor * sin(rad)
        sumY -= factor * cos(rad)
        units += factor
    }

    /** GPS lokacija napolju; vraća novu naučenu dužinu punog koraka (m) ili null. */
    fun onGps(fix: GpsFix): Float? {
        if (fix.accuracyM > MAX_ACCURACY_M) return null
        val from = start ?: run {
            start = fix.point
            return null
        }
        val gpsM = hypot(fix.point.x - from.x, fix.point.y - from.y)
        if (gpsM < MIN_DISTANCE_M) return null
        val net = hypot(sumX, sumY)
        val straight = units > 0 && net >= STRAIGHT_RATIO * units
        reset()
        start = fix.point
        if (!straight) return null
        val stepM = (gpsM / net).toFloat()
        if (stepM !in MIN_STEP_M..MAX_STEP_M) return null
        recent.addLast(stepM)
        if (recent.size > SAMPLES) recent.removeFirst()
        if (recent.size < MIN_SAMPLES) return null
        return recent.sorted()[recent.size / 2]
    }

    companion object {
        const val MIN_DISTANCE_M = 60.0
        const val MAX_ACCURACY_M = 8f
        const val STRAIGHT_RATIO = 0.85
        const val MIN_STEP_M = 0.5f
        const val MAX_STEP_M = 1.1f
        const val SAMPLES = 5
        const val MIN_SAMPLES = 3
    }
}
