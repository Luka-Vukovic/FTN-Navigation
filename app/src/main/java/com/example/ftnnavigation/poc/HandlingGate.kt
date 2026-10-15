package com.example.ftnnavigation.poc

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Koraci dok korisnik stoji i premešta telefon (u džep, iz džepa) nisu hod. Proba "Džep i sedenje" 11.10.2026: tri puta u
 * prednji džep i nazad bez ijednog koraka -> 52 koraka (~28 m), džep jakne 35; svako stavljanje ili vađenje 4-11 jakih
 * "koraka" u 2-4 s. Po samom koraku (vrh, ritam, pravac hoda iz ubrzanja, udeo vertikalnog ubrzanja) se ne razlikuju od
 * hoda po stanu sa telefonom u džepu, ali se telefon pri tom okrene: od polaska iz mirovanja do 1,5 s kasnije 50-100°, a na
 * terenu (724 polaska) samo 24 puta 30° ili više.
 *
 * Zato: dok korisnik stoji ([PAUSE_NS] bez koraka), koraci se zadržavaju. Puštaju se (svi, sa svojim vremenom) kad ih ima
 * [CONFIRM_STEPS] u bar [CONFIRM_NS] ([POCKET_CONFIRM_NS] kad je telefon u mirovanju uspravan - u džepu), a telefon se od
 * mirovanja nije okrenuo više od [HANDLING_DEG] - tada je to hod i dalje sve prolazi odmah. Ako se okrenuo, telefon se premešta: zadržani koraci se odbacuju kad se smiri (okret "gore" za
 * [SETTLE_CHECK_NS] manji od [SETTLE_DEG]), i od tada se ponovo čeka hod. Pauza od [PAUSE_NS] odbacuje zadržano (i
 * pojedinačne korake u mestu). Hod pa premeštanje u hodu se ne dira (korisnik već hoda).
 *
 * Proba 11.10.2026 (replay): prednji džep 48 -> 8 koraka, jakna 32 -> 0, kauč 20 -> 6, stolica 3 -> 0; hod 50 brojanih i hod sa
 * premeštanjem netaknuti. Teren (59 "Ovde sam"): srednji promašaj 4,8 -> 4,25 m, medijan 2,9 -> 2,1 m.
 *
 * U džepu (14.10.2026): vađenje ume da traje ~4 s slabih "koraka" (vrh 0,6-1,9) pre nego što se telefon okrene - duže od
 * potvrde. Zato se iz džepa hod potvrđuje tek uz jak udar noge ([POCKET_STRONG_PEAK]) ili medijanu vrhova od bar
 * [POCKET_WALK_MEDIAN_PEAK] (hod iz lifta u džepu 09.10.: vrhovi 1,1-2,6), a tih hod posle [POCKET_SOFT_CONFIRM_NS].
 *
 * Iz ruke (15.10.2026): pre stavljanja u džep ruka ume 1,5-2 s da pomera telefon uz slabe "korake" (vrh 0,6-1,2) pre nego što
 * se okrene - potvrđeno kao hod, pa je prošlo celo stavljanje (do 22 koraka). Zato slab polazak u ruci (medijana vrhova ispod
 * [HAND_WALK_MEDIAN_PEAK]) čeka [HAND_SOFT_CONFIRM_NS] da se vidi da li se telefon okreće; ako se ne okrene, koraci se puste
 * (i na pauzi - kratak pravi hod se ne gubi, [takeReleased]). Posle odbačenog premeštanja slab polazak čeka
 * [POCKET_SOFT_CONFIRM_NS] i pauza ga odbacuje (telefon je smiren usred vađenja, a vađenje se nastavlja).
 * Proba 15.10. (replay): stavljanje u džep 3x 38 -> 16 koraka, sa zastojem 16 -> 9, hod u džepu 35/34 -> 29/29 (brojano 30);
 * teren bez promene prelaza. Prag 1,0 sa donjom medijanom bi dao 3 umesto 16, ali na terenu gubi polazak uz stavljanje
 * telefona u džep (NTP 12.10., 17 koraka i silazak II -> I).
 *
 * Proba 15.10. popodne: ruka ume i ~3 s da pomera telefon uz "korake" medijane 1,0-1,2 pre okreta (stavljanje prošlo celo, 13
 * koraka), ili okret počne tek posle 3 s čekanja (13 koraka). Zato čeka svaki polazak u ruci ispod 1,3, i to 4 s. Replay: stavljanje
 * 13 -> 0 i 16 -> 3, sa zastojem 9 -> 0, spor hod 52 -> 39 (brojano 30); teren isti srednji promašaj. Cena: polazak uz stavljanje
 * telefona u džep gubi korake do smirivanja (proba 20 -> 14, NTP 12.10. ~6 koraka i silazak II -> I). Proban i odbačen: pustiti
 * zadržano ako hod posle smirivanja nastavi bez pauze (isto što i 14.10. - lažni koraci pri "stavi i odmah kreni", lažni spratovi).
 *
 * Cena: početak hoda kasni dok se ne potvrdi (1,5 s u ruci, pa tačka sustigne), polazak uz premeštanje telefona gubi korake do
 * smirivanja, a 1-2 koraka u mestu pa pauza se ne broje (teren: ~6 % koraka, većinom takvi).
 */
class HandlingGate {
    class Step(val timeNs: Long, val walk: WalkingDirection.WalkStep, val peak: Float)

    private var walking = false
    private var lastStepNs = 0L
    private val held = ArrayList<Step>()

    // "Gore" u koordinatama telefona: brzo (premeštanje), sporo (smirivanje); u mirovanju.
    private val upFast = DoubleArray(3)
    private val upSlow = DoubleArray(3)
    private val restUp = DoubleArray(3)
    private var hasUp = false
    private var handling = false
    private val settleRef = DoubleArray(3)
    private var settleRefNs = 0L

    /** Okret telefona od poslednjeg mirovanja (za analizu u replay-u). */
    internal val restAngleDeg: Double get() = angle(upFast, restUp)

    /** Telefon je u mirovanju bio uspravan (u džepu). */
    internal val restInPocket: Boolean get() = abs(restUp[2]) < UPRIGHT_Z

    /** Odluke kapije (za replay). */
    internal var trace: ((Long, String) -> Unit)? = null

    /** Odbačeni koraci od početka (za prikaz i snimak). */
    var dropped = 0
        private set

    /** Nova orijentacija ([matrix] kao u [WalkingDirection.onRotation]); vraća broj upravo odbačenih koraka. */
    fun onRotation(matrix: FloatArray, timestampNs: Long): Int {
        for (i in 0..2) {
            val v = matrix[6 + i].toDouble()
            if (!hasUp) {
                upFast[i] = v
                upSlow[i] = v
            } else {
                upFast[i] += UP_FAST_ALPHA * (v - upFast[i])
                upSlow[i] += UP_SLOW_ALPHA * (v - upSlow[i])
            }
        }
        if (!hasUp) {
            hasUp = true
            rest(timestampNs)
        }
        if (walking) {
            if (paused(timestampNs)) {
                walking = false
                rest(timestampNs)
            }
            return 0
        }
        if (!handling && angle(upFast, restUp) > HANDLING_DEG) {
            trace?.invoke(timestampNs, "premeštanje, zadržano ${held.size}")
            handling = true
            upSlow.copyInto(settleRef)
            settleRefNs = timestampNs
        }
        var drop = 0
        if (handling && timestampNs - settleRefNs >= SETTLE_CHECK_NS) {
            if (angle(upSlow, settleRef) < SETTLE_DEG) {
                trace?.invoke(timestampNs, "smiren, odbačeno ${held.size}")
                drop = held.size
                rest(timestampNs)
                afterHandling = true
            } else {
                upSlow.copyInto(settleRef)
                settleRefNs = timestampNs
            }
        }
        // Slab polazak u ruci se odluči kasnije: ako se telefon do tada nije okrenuo, to je hod (kao da je potvrđen odmah).
        val paused = paused(timestampNs)
        val softDone = held.isNotEmpty() && ((paused && !afterHandling) || timestampNs - held.first().timeNs >= softWaitNs())
        if (softDone && softHandWalk()) {
            trace?.invoke(timestampNs, "slab hod u ruci pušten, ${held.size}")
            late += held
            walking = !paused
            afterHandling = false
            if (walking) held.clear() else rest(timestampNs)
        } else if (held.isNotEmpty() && paused) {
            trace?.invoke(timestampNs, "pauza, odbačeno ${held.size}")
            drop = held.size
            rest(timestampNs)
        }
        dropped += drop
        return drop
    }

    /** Zadržani koraci koje je [onRotation] pustio (slab polazak u ruci, odlučen kasnije); posle poziva lista je prazna. */
    fun takeReleased(): List<Step> = late.toList().also { late.clear() }

    private val late = ArrayList<Step>()

    // Telefon je upravo premeštan i smiren (zadržani koraci odbačeni): slab polazak tada čeka duže - vađenje ume da se nastavi.
    private var afterHandling = false

    private fun paused(timestampNs: Long) = timestampNs - lastStepNs >= PAUSE_NS

    private fun softWaitNs() = if (afterHandling) POCKET_SOFT_CONFIRM_NS else HAND_SOFT_CONFIRM_NS

    private fun softHandWalk(): Boolean =
        !handling && abs(restUp[2]) >= UPRIGHT_Z && held.size >= CONFIRM_STEPS && held.last().timeNs - held.first().timeNs >= CONFIRM_NS

    /** Korak: vraća korake koje sada treba primeniti (ovaj, zadržane, ili nijedan). */
    fun onStep(step: Step): List<Step> {
        lastStepNs = step.timeNs
        if (walking) return listOf(step)
        held += step
        // Iz džepa (uspravan u mirovanju) vađenje počinje i ~2 s pre nego što se telefon okrene, a tačku tada niko ne gleda.
        val inPocket = abs(restUp[2]) < UPRIGHT_Z
        val confirmNs = if (inPocket) POCKET_CONFIRM_NS else CONFIRM_NS
        if (handling || held.size < CONFIRM_STEPS || step.timeNs - held.first().timeNs < confirmNs) return emptyList()
        // U džepu hod ima jak udar noge uz telefon; vađenje (ruka u džepu) samo slabe "korake" - dok se telefon ne okrene.
        if (inPocket && !pocketWalk() && step.timeNs - held.first().timeNs < POCKET_SOFT_CONFIRM_NS) return emptyList()
        if (!inPocket && medianPeak() < HAND_WALK_MEDIAN_PEAK && step.timeNs - held.first().timeNs < softWaitNs()) return emptyList()
        trace?.invoke(step.timeNs, "hod potvrđen, ${held.size} (${if (inPocket) "džep" else "ruka"})")
        walking = true
        afterHandling = false
        return held.toList().also { held.clear() }
    }

    private fun pocketWalk(): Boolean {
        if (held.any { it.peak >= POCKET_STRONG_PEAK }) return true
        return medianPeak() >= POCKET_WALK_MEDIAN_PEAK
    }

    private fun medianPeak(): Float {
        val peaks = held.map { it.peak }.sorted()
        return peaks[peaks.size / 2]
    }

    /** Mirovanje: trenutna orijentacija je polazna, ništa se ne premešta. */
    private fun rest(timestampNs: Long) {
        held.clear()
        handling = false
        afterHandling = false
        upFast.copyInto(restUp)
        settleRefNs = timestampNs
    }

    private fun angle(a: DoubleArray, b: DoubleArray): Double {
        val na = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
        val nb = sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2])
        if (na == 0.0 || nb == 0.0) return 0.0
        return Math.toDegrees(acos(((a[0] * b[0] + a[1] * b[1] + a[2] * b[2]) / (na * nb)).coerceIn(-1.0, 1.0)))
    }

    companion object {
        const val PAUSE_NS = 1_500_000_000L
        const val CONFIRM_STEPS = 3
        const val CONFIRM_NS = 1_500_000_000L
        const val POCKET_CONFIRM_NS = 3_000_000_000L
        const val POCKET_STRONG_PEAK = 3f
        const val POCKET_WALK_MEDIAN_PEAK = 1.5f
        const val POCKET_SOFT_CONFIRM_NS = 8_000_000_000L
        const val HAND_WALK_MEDIAN_PEAK = 1.3f
        const val HAND_SOFT_CONFIRM_NS = 4_000_000_000L
        private const val UPRIGHT_Z = 0.5
        const val HANDLING_DEG = 30.0
        private const val SETTLE_DEG = 12.0
        private const val SETTLE_CHECK_NS = 500_000_000L
        private const val UP_FAST_ALPHA = 0.15
        private const val UP_SLOW_ALPHA = 0.02
    }
}
