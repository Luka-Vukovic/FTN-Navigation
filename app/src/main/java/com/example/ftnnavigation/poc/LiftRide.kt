package com.example.ftnnavigation.poc

import kotlin.math.abs
import kotlin.math.sqrt

// Vožnja liftom iz akcelerometra (telefon nema barometar). Teren 08.10.2026, NTP (6 vožnji, u ruci i u džepu): lift
// ubrzava ~0,4-0,6 m/s² 2-3,5 s (impuls), vozi mirno, pa koči isto toliko suprotno. Šum dok se stoji je ~0,05 m/s² u
// 0,5 s; hod i premeštanje telefona su mnogo bučniji (std |a| u 0,5 s preko 1). Prototip (scratchpad lift_online.py) je
// na svih 76 snimaka 29.09.-08.10. našao svih 6 vožnji i nijednu lažnu.

/** Ubrzanje se usrednjava po korpama ovolike dužine. */
private const val BIN_NS = 500_000_000L
private const val BIN_S = 0.5

/** Bazna linija (gravitacija + greška senzora) = medijana srednjih vrednosti ovoliko poslednjih korpi (20 s)... */
private const val BASE_BINS = 40

/** ... kad ih ima bar ovoliko (5 s posle početka). */
private const val MIN_BASE_BINS = 10

/** Vertikalno ubrzanje dalje od bazne linije od ovoga (m/s²) je deo impulsa. */
private const val PULSE_THRESHOLD = 0.15

/** Impuls traje bar ovoliko korpi (1 s). */
private const val PULSE_MIN_BINS = 2

/** Korpa je mirna (može biti deo impulsa): std |a| ispod ovoga i bez koraka. Hod: 1-3 m/s²; stavljanje u džep: 2-3. */
private const val BIN_CALM_STD = 0.8

/** Prosečna std |a| impulsa ispod ovoga (u liftu 0,07-0,3). */
private const val PULSE_MAX_STD = 0.45

/** Impuls menja brzinu bar ovoliko (m/s) - lift ide ~1-1,5 m/s. */
private const val MIN_PULSE_DV = 0.4

/** Kočenje najkasnije ovoliko posle polaska (P -> V u NTP-u je 16,5 s, uz stajanje na spratu dve vožnje). */
private const val MAX_RIDE_NS = 45_000_000_000L

/** Između polaska i kočenja najviše ovoliko koraka (detektor ume da prijavi korak na trzaju lifta - 08.10. I -> P). */
private const val MAX_STEPS_IN_RIDE = 2

/** Brzina koju polazak doda i kočenje oduzme mora biti slična (odnos; na snimcima 0,85-0,95). */
private val DV_RATIO = 0.5..2.0

/** Za baznu liniju pomeraja: mirne korpe ovoliko pre polaska (korisnik stoji u liftu) i one između impulsa (mirna vožnja). */
private const val BEFORE_RIDE_BINS = 6

/**
 * Prepoznata vožnja liftom: od početka polaska [startNs] do kraja kočenja [endNs], vertikalni pomeraj [heightM] (+ gore).
 * Pomeraj je dvostruka integracija ubrzanja uz brzinu 0 na oba kraja - na snimcima 08.10.2026 tačan na ~0,3 m po spratu.
 */
data class LiftRide(val startNs: Long, val endNs: Long, val heightM: Double) {
    val up: Boolean get() = heightM > 0
}

/**
 * Prepoznaje vožnju liftom iz ubrzanja: impuls (mirno ubrzanje gore ili dole bar 1 s), pa suprotan impuls (kočenje) u
 * roku od [MAX_RIDE_NS], bez hoda između. Radi uzorak po uzorak (bez gledanja unapred): vožnja se javlja kad se završi
 * kočenje. Stajanje lifta na spratu usput su dve vožnje.
 */
class LiftRideDetector {

    private class Bin(val startNs: Long, val meanUp: Double, val stdMag: Double, val steps: Int, val base: Double?) {
        val calm: Boolean get() = stdMag < BIN_CALM_STD && steps == 0
    }

    private class Pulse(val sign: Int, val startNs: Long, val base: Double) {
        var endNs = startNs + BIN_NS
        var bins = 0
        var dv = 0.0
        var stdSum = 0.0
        val std: Double get() = stdSum / bins
    }

    /** Treći red matrice rotacije: "gore" u koordinatama telefona. */
    private var up: FloatArray? = null

    private var binStart: Long? = null
    private var sumUp = 0.0
    private var sumMag = 0.0
    private var sumMag2 = 0.0
    private var samples = 0

    private val bins = ArrayDeque<Bin>()
    private val steps = ArrayDeque<Long>()
    private var current: Pulse? = null

    /** Poslednji impuls koji čeka suprotan (polazak čeka kočenje). */
    private var pending: Pulse? = null

    fun onRotation(rotationMatrix: FloatArray) {
        up = floatArrayOf(rotationMatrix[6], rotationMatrix[7], rotationMatrix[8])
    }

    /** Korak sa detektora koraka (isti sat kao uzorci). */
    fun onStep(timeNs: Long) {
        steps.addLast(timeNs)
        while (steps.size > 200) steps.removeFirst()
    }

    /** Uzorak akcelerometra (koordinate telefona, m/s²); vraća vožnju kad se završi kočenje. */
    fun onAccelerometer(x: Float, y: Float, z: Float, timeNs: Long): LiftRide? {
        val u = up ?: return null
        var ride: LiftRide? = null
        val start = binStart ?: timeNs.also { binStart = it }
        if (timeNs >= start + BIN_NS) {
            // Praznina u senzorima (odjava): sve od pre se zaboravlja.
            if (timeNs >= start + 2 * BIN_NS) {
                reset()
                binStart = timeNs
            } else {
                ride = closeBin(start)
                binStart = start + BIN_NS
            }
        }
        val vertical = u[0] * x + u[1] * y + u[2] * z
        val mag = sqrt((x * x + y * y + z * z).toDouble())
        sumUp += vertical
        sumMag += mag
        sumMag2 += mag * mag
        samples++
        return ride
    }

    fun reset() {
        binStart = null
        sumUp = 0.0
        sumMag = 0.0
        sumMag2 = 0.0
        samples = 0
        bins.clear()
        current = null
        pending = null
    }

    private fun closeBin(start: Long): LiftRide? {
        if (samples == 0) return null
        val mean = sumUp / samples
        val magMean = sumMag / samples
        val std = sqrt((sumMag2 / samples - magMean * magMean).coerceAtLeast(0.0))
        sumUp = 0.0
        sumMag = 0.0
        sumMag2 = 0.0
        samples = 0
        val stepCount = steps.count { it >= start && it < start + BIN_NS }
        val history = bins.takeLast(BASE_BINS).map { it.meanUp }
        val base = if (history.size >= MIN_BASE_BINS) median(history) else null
        val bin = Bin(start, mean, std, stepCount, base)
        bins.addLast(bin)
        while (bins.size > 2 * MAX_RIDE_NS / BIN_NS) bins.removeFirst()
        if (base == null) return null

        val a = mean - base
        val sign = when {
            !bin.calm -> 0
            a > PULSE_THRESHOLD -> 1
            a < -PULSE_THRESHOLD -> -1
            else -> 0
        }
        val pulse = current
        if (pulse != null && sign == pulse.sign) {
            pulse.add(bin, a)
            return null
        }
        current = if (sign != 0) Pulse(sign, start, base).also { it.add(bin, a) } else null
        return pulse?.let(::finish)
    }

    private fun Pulse.add(bin: Bin, a: Double) {
        bins++
        dv += a * BIN_S
        stdSum += bin.stdMag
        endNs = bin.startNs + BIN_NS
    }

    /** Impuls se završio: ako je kočenje posle polaska - vožnja; inače čeka kao mogući polazak. */
    private fun finish(pulse: Pulse): LiftRide? {
        if (pulse.bins < PULSE_MIN_BINS || pulse.std >= PULSE_MAX_STD || abs(pulse.dv) < MIN_PULSE_DV) return null
        val start = pending
        if (start != null && start.sign == -pulse.sign && pulse.startNs - start.endNs <= MAX_RIDE_NS) {
            val between = steps.count { it >= start.endNs && it < pulse.startNs }
            if (between <= MAX_STEPS_IN_RIDE && abs(pulse.dv) / abs(start.dv) in DV_RATIO) {
                pending = null
                return LiftRide(start.startNs, pulse.endNs, height(start, pulse))
            }
        }
        pending = pulse
        return null
    }

    /**
     * Pomeraj od korpe pre polaska do korpe posle kočenja: brzina je 0 na oba kraja (ostatak brzine na kraju je greška
     * bazne linije - skida se linearno). Bazna linija: medijana mirnih korpi pre polaska i između impulsa (tada je pravo
     * ubrzanje 0), inače bazna linija polaska.
     */
    private fun height(start: Pulse, stop: Pulse): Double {
        val calm = bins.filter {
            it.calm && ((it.startNs >= start.startNs - BEFORE_RIDE_BINS * BIN_NS && it.startNs < start.startNs) ||
                (it.startNs >= start.endNs && it.startNs < stop.startNs))
        }.map { it.meanUp }
        val base = if (calm.size >= 3) median(calm) else start.base
        val ride = bins.filter { it.startNs >= start.startNs - BIN_NS && it.startNs < stop.endNs + BIN_NS }
        if (ride.isEmpty()) return 0.0
        val velocity = DoubleArray(ride.size + 1)
        for ((k, bin) in ride.withIndex()) velocity[k + 1] = velocity[k] + (bin.meanUp - base) * BIN_S
        val drift = velocity[ride.size]
        var height = 0.0
        for (k in ride.indices) {
            height += ((velocity[k] + velocity[k + 1]) / 2 - drift * (k + 0.5) / ride.size) * BIN_S
        }
        return height
    }

    private fun median(values: List<Double>): Double = values.sorted()[values.size / 2]
}
