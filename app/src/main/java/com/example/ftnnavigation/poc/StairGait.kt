package com.example.ftnnavigation.poc

import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sqrt

// Korak na stepenicama naspram koraka po ravnom, iz ubrzanja (analiza snimaka 15.10.2026, scratchpad stairsteps2.py /
// descent.py): pri silasku sa telefonom u ruci je horizontalno ubrzanje u odnosu na vertikalno manje (std 0,28 naspram 0,38)
// i vertikalni udar jači (raspon 8,7 naspram 6,8 m/s²). Jedan korak se ne razlikuje pouzdano, nekoliko zaredom da (AUC
// 0,85-0,89 za prozor od 5 koraka). Penjanje se slabije vidi (0,83 samo odnos), džep se ne vidi (poređenje je sa hodom u ruci).

/** Uzorci za osobine koraka: najviše ovoliko pre koraka (kao u analizi). */
private const val MAX_STEP_WINDOW_NS = 1_200_000_000L

/** Ubrzanje se pamti ovoliko unazad (korak stiže do 0,2 s posle svog vremena; zadržani koraci kasnije - osobine se računaju odmah). */
private const val SAMPLE_KEEP_NS = 2_000_000_000L

/** Najmanje uzoraka u koraku (50 Hz: korak ~0,6 s = 30). */
private const val MIN_SAMPLES = 5

/** Telefon u ruci (ekran ka gore): z osa telefona u svetu bar ovoliko (u džepu ~0). */
private const val HAND_UP_Z = 0.5

/** Poređenje sa ovoliko poslednjih koraka po ravnom u ruci... */
private const val BASELINE_STEPS = 60

/** ... kad ih ima bar ovoliko. */
private const val MIN_BASELINE_STEPS = 15

/** Težina raspona vertikalnog ubrzanja u znaku (odnos horizontalno/vertikalno je pouzdaniji: sporiji koraci usred kraka
 * NTP 12.10.2026 su imali slabiji udar, a odnos je ostao kao na stepenicama). */
private const val RANGE_WEIGHT = 0.5

/** Koliko poslednjih koraka (osobina) se pamti, da zadržan korak ([HandlingGate]) nađe svoje. */
private const val KEEP_STEPS = 50

class StairGait {

    private class Sample(val timeNs: Long, val vertical: Float, val horizontal: Float, val upZ: Float)

    /** Osobine koraka: raspon vertikalnog ubrzanja, odnos std horizontalnog i vertikalnog, telefon u ruci. */
    private class Features(val range: Double, val ratio: Double, val inHand: Boolean)

    private val rotation = FloatArray(9)
    private var hasRotation = false
    private val samples = ArrayDeque<Sample>()
    private var lastStepNs: Long? = null
    private val features = LinkedHashMap<Long, Features>()
    private val baseRange = ArrayDeque<Double>()
    private val baseRatio = ArrayDeque<Double>()

    fun onRotation(matrix: FloatArray) {
        matrix.copyInto(rotation, endIndex = 9)
        hasRotation = true
    }

    fun onAccelerometer(x: Float, y: Float, z: Float, timeNs: Long) {
        if (!hasRotation) return
        val r = rotation
        val vertical = r[6] * x + r[7] * y + r[8] * z
        val horizontal = hypot(r[0] * x + r[1] * y + r[2] * z, r[3] * x + r[4] * y + r[5] * z)
        samples.addLast(Sample(timeNs, vertical, horizontal, r[8]))
        while (samples.isNotEmpty() && samples.first().timeNs < timeNs - SAMPLE_KEEP_NS) samples.removeFirst()
    }

    /** Detektovan korak (vreme koraka, kao [AccelStepDetector]): osobine od prethodnog koraka do njega. */
    fun onStep(timeNs: Long) {
        val from = maxOf(lastStepNs ?: (timeNs - 600_000_000L), timeNs - MAX_STEP_WINDOW_NS)
        lastStepNs = timeNs
        val window = samples.filter { it.timeNs in (from + 1)..timeNs }
        if (window.size < MIN_SAMPLES) return
        val v = window.map { it.vertical.toDouble() }
        val h = window.map { it.horizontal.toDouble() }
        val vStd = std(v)
        if (vStd <= 0.0) return
        features[timeNs] = Features(v.max() - v.min(), std(h) / vStd, window.map { it.upZ.toDouble() }.average() > HAND_UP_Z)
        while (features.size > KEEP_STEPS) features.remove(features.keys.first())
    }

    /**
     * Koliko korak liči na stepenice u odnosu na hod istog korisnika po ravnom: RANGE_WEIGHT · ln(raspon / baza) −
     * ln(odnos / baza); ~0 po ravnom, ~0,5-1,5 na kraku pri silasku. null: telefon nije u ruci, nema osobina ili baze.
     */
    fun score(timeNs: Long): Float? {
        val f = features[timeNs]?.takeIf { it.inHand } ?: return null
        if (baseRange.size < MIN_BASELINE_STEPS) return null
        return (RANGE_WEIGHT * ln(f.range / median(baseRange)) - ln(f.ratio / median(baseRatio))).toFloat()
    }

    /** Korak [timeNs] je bio po ravnom (tačka nije na stepeništu) - ulazi u bazu ako je telefon bio u ruci. */
    fun flat(timeNs: Long) {
        val f = features[timeNs]?.takeIf { it.inHand && it.range > 0 && it.ratio > 0 } ?: return
        baseRange.addLast(f.range)
        baseRatio.addLast(f.ratio)
        if (baseRange.size > BASELINE_STEPS) {
            baseRange.removeFirst()
            baseRatio.removeFirst()
        }
    }

    private fun std(values: List<Double>): Double {
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }

    private fun median(values: Collection<Double>): Double {
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}
