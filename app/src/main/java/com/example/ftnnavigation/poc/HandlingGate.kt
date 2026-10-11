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
            if (timestampNs - lastStepNs >= PAUSE_NS) {
                walking = false
                rest(timestampNs)
            }
            return 0
        }
        if (!handling && angle(upFast, restUp) > HANDLING_DEG) {
            handling = true
            upSlow.copyInto(settleRef)
            settleRefNs = timestampNs
        }
        var drop = 0
        if (handling && timestampNs - settleRefNs >= SETTLE_CHECK_NS) {
            if (angle(upSlow, settleRef) < SETTLE_DEG) {
                drop = held.size
                rest(timestampNs)
            } else {
                upSlow.copyInto(settleRef)
                settleRefNs = timestampNs
            }
        }
        if (held.isNotEmpty() && timestampNs - lastStepNs >= PAUSE_NS) {
            drop = held.size
            rest(timestampNs)
        }
        dropped += drop
        return drop
    }

    /** Korak: vraća korake koje sada treba primeniti (ovaj, zadržane, ili nijedan). */
    fun onStep(step: Step): List<Step> {
        lastStepNs = step.timeNs
        if (walking) return listOf(step)
        held += step
        // Iz džepa (uspravan u mirovanju) vađenje počinje i ~2 s pre nego što se telefon okrene, a tačku tada niko ne gleda.
        val confirmNs = if (abs(restUp[2]) < UPRIGHT_Z) POCKET_CONFIRM_NS else CONFIRM_NS
        if (handling || held.size < CONFIRM_STEPS || step.timeNs - held.first().timeNs < confirmNs) return emptyList()
        walking = true
        return held.toList().also { held.clear() }
    }

    /** Mirovanje: trenutna orijentacija je polazna, ništa se ne premešta. */
    private fun rest(timestampNs: Long) {
        held.clear()
        handling = false
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
        private const val UPRIGHT_Z = 0.5
        const val HANDLING_DEG = 30.0
        private const val SETTLE_DEG = 12.0
        private const val SETTLE_CHECK_NS = 500_000_000L
        private const val UP_FAST_ALPHA = 0.15
        private const val UP_SLOW_ALPHA = 0.02
    }
}
