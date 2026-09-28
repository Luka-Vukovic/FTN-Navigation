package com.example.ftnnavigation.poc

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Smer hoda za PDR, nezavisno od toga kako se telefon drži (u ruci, u džepu).
 *
 * Smer hoda = pravac telefona + odstupanje telefona od pravca hoda:
 * - **Pravac telefona** je horizontalna projekcija zbira ose Y (gornja ivica) i ose -Z
 *   (poleđina). Dok telefon leži to je gornja ivica, a dok stoji uspravno (džep) poleđina -
 *   azimut iz `getOrientation` je tada nestabilan, jer je gornja ivica skoro vertikalna.
 *   Za korak se usrednjava preko poslednja dva koraka (pun ciklus noge - u džepu se levi i
 *   desni korak ne cik-cakiraju). Telefon se okreće sa telom, pa pravac prati skretanje.
 * - **Premeštanje telefona** (iz ruke u džep) se prepoznaje po naglom okretu gravitacije u
 *   koordinatama telefona. Dok se telefon ne smiri, smer se ne menja (pravo) i jednak je smeru
 *   od pre premeštanja (0,5-1 s pre nego što je prepoznato - pre početka pokreta); kad se
 *   smiri, odstupanje se postavlja tako da se taj smer nastavi (korisnik nastavlja kuda je
 *   išao, a tokom premeštanja se nije okretao).
 * - **Osa hoda** samo fino ispravlja odstupanje: pri hodu je horizontalno ubrzanje (u
 *   koordinatama sveta) najjače napred-nazad, pa glavna komponenta (PCA) ubrzanja u poslednja
 *   dva koraka daje pravac hoda bez znaka. Prihvata se samo ako je najviše
 *   [MAX_CORRECTION_DEG] od trenutne procene i ako se korisnik u tim koracima nije okretao -
 *   bočno ljuljanje (u ruci) i njihanje noge (u džepu) umeju da daju pogrešnu osu.
 * - **Okret telefona bez okreta tela** (telefon u ruci okrenut na stranu, pogled u stranu dok
 *   se ide pravo): nagli okret pravca telefona je prvo skretanje (tačka odmah prati). Kad se
 *   telefon smiri (dva koraka), osa hoda kaže šta je bilo - ona je u koordinatama sveta, pa je
 *   posle okreta samo telefona ista kao pre. Ako je osa ostala na starom smeru, odstupanje se
 *   ispravlja za okret telefona, a koraci od početka okreta se ponavljaju u starom smeru
 *   ([WalkStep.redoSteps]). Isto i kad se telefon posle kratkog okreta vrati (osa cele epizode).
 *   Osi se veruje samo ako se pre okreta slagala sa smerom hoda (inače je verovatno bočna), a
 *   okret mora biti bar [MIN_SEPARATION_DEG] od pravca (okret za ~180° osa ne razlikuje).
 *
 * Pretpostavka: na početku ([reset], dugme Start) telefon je u ruci, okrenut napred.
 * Ograničenja: okret tela dok se telefon premešta (do [MAX_UNSETTLED_NS]) se ne vidi; hod
 * bočno ili dijagonalno pod uglom većim od [MAX_CORRECTION_DEG] od pravca telefona se ne ispravlja.
 *
 * Uglovi su azimuti u stepenima (0 = sever, u smeru kazaljke). Filteri su podešeni za
 * ~50 Hz (SENSOR_DELAY_GAME) za oba senzora.
 */
class WalkingDirection {
    /**
     * Smer hoda koraka (azimut 0..360). [redoSteps] prethodnih koraka treba ponoviti u istom
     * smeru - pokazalo se da je okret bio samo okret telefona, ne skretanje.
     */
    data class WalkStep(val headingDeg: Float, val redoSteps: Int = 0)

    private class Sample(val timestampNs: Long, val east: Double, val north: Double)

    /** Okret telefona u toku: od kada (za osu), i pravac telefona i smer hoda pre okreta. */
    private class Turn(val fromNs: Long, val phoneDeg: Double, val walkDeg: Double, val axisTrusted: Boolean) {
        var steps = 0
        var maxDeg = 0.0
    }

    private val rotation = FloatArray(9)
    private var hasRotation = false

    // Pravac telefona: zbir u tekućem i prethodnom koraku, i izglađen (prikaz, smirivanje).
    private var stepSumE = 0.0
    private var stepSumN = 0.0
    private var prevStepSumE = 0.0
    private var prevStepSumN = 0.0
    private var smoothE = 0.0
    private var smoothN = 0.0

    // "Gore" u koordinatama telefona: brzo izglađen (premeštanje) i sporo (smirivanje).
    private val upFast = DoubleArray(3)
    private val upSlow = DoubleArray(3)
    private var upRef: DoubleArray? = null

    // Premeštanje u toku: od kada, i sporo "gore" pri poslednjoj proveri smirivanja.
    private var unsettledSinceNs = 0L
    private var settleRef = DoubleArray(3)
    private var settleRefNs = 0L

    private val samples = ArrayDeque<Sample>()
    private val stepTimes = ArrayDeque<Long>()
    private val stepPhoneDegs = ArrayDeque<Double>()

    private var offsetDeg = 0.0
    private var anchored = true

    // Pravac telefona i smer hoda u poslednjem koraku bez okreta; da li se osa hoda slagala.
    private var steadyPhoneDeg: Double? = null
    private var steadyWalkDeg = 0.0
    private var axisAgrees = false
    private var turn: Turn? = null

    // Smer na svakih 0,5 s dok je odstupanje poznato; pri premeštanju važi stariji od dva.
    private var recentWalkDeg: Double? = null
    private var olderWalkDeg: Double? = null
    private var recentWalkNs = 0L
    private var heldWalkDeg = 0.0

    /** Da li je odstupanje telefona poznato (false dok se premešten telefon ne smiri). */
    val isAnchored: Boolean get() = anchored

    /** Odstupanje: smer hoda - pravac telefona (-180..180). */
    val offset: Double get() = offsetDeg

    /** Nova orijentacija; [matrix] je 3x3 matrica iz `SensorManager.getRotationMatrixFromVector`. */
    fun onRotation(matrix: FloatArray, timestampNs: Long) {
        matrix.copyInto(rotation, endIndex = 9)
        // Kolone matrice su ose telefona u koordinatama sveta (istok, sever, gore).
        val e = (matrix[1] - matrix[2]).toDouble()
        val n = (matrix[4] - matrix[5]).toDouble()
        stepSumE += e
        stepSumN += n
        // Treći red je "gore" u koordinatama telefona.
        if (!hasRotation) {
            smoothE = e
            smoothN = n
            for (i in 0..2) {
                upFast[i] = matrix[6 + i].toDouble()
                upSlow[i] = upFast[i]
            }
            hasRotation = true
        } else {
            smoothE += SMOOTH_ALPHA * (e - smoothE)
            smoothN += SMOOTH_ALPHA * (n - smoothN)
            for (i in 0..2) {
                upFast[i] += UP_FAST_ALPHA * (matrix[6 + i] - upFast[i])
                upSlow[i] += UP_SLOW_ALPHA * (matrix[6 + i] - upSlow[i])
            }
        }

        if (anchored && timestampNs - recentWalkNs >= SETTLE_CHECK_NS) {
            olderWalkDeg = recentWalkDeg
            recentWalkDeg = azimuth(smoothE, smoothN) + offsetDeg
            recentWalkNs = timestampNs
        }

        val ref = upRef
        when {
            ref == null -> upRef = upFast.copyOf()
            anchored -> if (angleBetween(upFast, ref) > REPOSITION_DEG) {
                anchored = false
                heldWalkDeg = olderWalkDeg ?: recentWalkDeg ?: (azimuth(smoothE, smoothN) + offsetDeg)
                unsettledSinceNs = timestampNs
                upSlow.copyInto(settleRef)
                settleRefNs = timestampNs
            }
            timestampNs - settleRefNs >= SETTLE_CHECK_NS -> {
                if (angleBetween(upSlow, settleRef) < SETTLE_DEG ||
                    timestampNs - unsettledSinceNs >= MAX_UNSETTLED_NS
                ) {
                    settle()
                } else {
                    upSlow.copyInto(settleRef)
                    settleRefNs = timestampNs
                }
            }
        }
    }

    /** Sirovi akcelerometar (koordinate telefona); pamti se horizontalni deo u koordinatama sveta. */
    fun onAccelerometer(x: Float, y: Float, z: Float, timestampNs: Long) {
        if (!hasRotation) return
        val r = rotation
        samples.addLast(
            Sample(
                timestampNs,
                east = (r[0] * x + r[1] * y + r[2] * z).toDouble(),
                north = (r[3] * x + r[4] * y + r[5] * z).toDouble(),
            ),
        )
        while (timestampNs - samples.first().timestampNs > SAMPLE_WINDOW_NS) samples.removeFirst()
    }

    /** Smer za prikaz (0..360), ili null dok nema orijentacije. */
    fun heading(): Float? {
        if (!hasRotation) return null
        return normalize(walking(azimuth(smoothE, smoothN)))
    }

    /** Korak u trenutku [timestampNs]: vraća smer hoda tog koraka, ili null dok nema orijentacije. */
    fun onStep(timestampNs: Long): WalkStep? {
        if (!hasRotation) return null
        val stepPhone = azimuthOr(stepSumE, stepSumN, azimuth(smoothE, smoothN))
        val phone = azimuthOr(stepSumE + prevStepSumE, stepSumN + prevStepSumN, stepPhone)
        prevStepSumE = stepSumE
        prevStepSumN = stepSumN
        stepSumE = 0.0
        stepSumN = 0.0

        val previousStepNs = stepTimes.lastOrNull() ?: timestampNs
        stepTimes.addLast(timestampNs)
        stepPhoneDegs.addLast(stepPhone)
        if (stepTimes.size > AXIS_STEPS + 1) {
            stepTimes.removeFirst()
            stepPhoneDegs.removeFirst()
        }

        if (!anchored) return WalkStep(normalize(heldWalkDeg))
        upRef = upFast.copyOf()
        return steer(phone, previousStepNs)
    }

    /** Početak praćenja: telefon je u ruci, okrenut napred (odstupanje 0). */
    fun reset() {
        offsetDeg = 0.0
        anchored = true
        recentWalkDeg = null
        olderWalkDeg = null
        upRef = if (hasRotation) upFast.copyOf() else null
        forgetTurns()
        samples.clear()
        stepTimes.clear()
        stepPhoneDegs.clear()
        stepSumE = 0.0
        stepSumN = 0.0
        prevStepSumE = 0.0
        prevStepSumN = 0.0
    }

    /** Smer hoda za pravac telefona [phone]; dok se premešten telefon ne smiri - smer od pre premeštanja. */
    private fun walking(phone: Double): Double = if (anchored) phone + offsetDeg else heldWalkDeg

    /** Telefon se smirio posle premeštanja: odstupanje tako da se nastavi smer od pre premeštanja. */
    private fun settle() {
        offsetDeg = angleDiff(heldWalkDeg, azimuth(smoothE, smoothN))
        anchored = true
        recentWalkDeg = null
        olderWalkDeg = null
        upRef = upFast.copyOf()
        forgetTurns()
        // Ubrzanja iz premeštanja ne smeju u osu hoda.
        samples.clear()
        stepTimes.clear()
        stepPhoneDegs.clear()
    }

    private fun forgetTurns() {
        turn = null
        steadyPhoneDeg = null
        axisAgrees = false
    }

    /** Smer koraka sa pravcem telefona [phone]: fina ispravka, ili praćenje okreta (vidi opis klase). */
    private fun steer(phone: Double, previousStepNs: Long): WalkStep {
        val steady = steadyPhoneDeg
        if (turn == null && steady != null && abs(angleDiff(phone, steady)) > TURN_DEG) {
            turn = Turn(previousStepNs, steady, steadyWalkDeg, axisAgrees)
        }
        val turn = turn
        if (turn == null) {
            correctOffset(phone)
            steadyPhoneDeg = phone
            steadyWalkDeg = phone + offsetDeg
            return WalkStep(normalize(phone + offsetDeg))
        }

        turn.steps++
        turn.maxDeg = maxOf(turn.maxDeg, axisDiff(phone, turn.phoneDeg))
        val settled = phoneSteady()
        // Dok se telefon okreće - kao skretanje.
        if (!settled && turn.steps < MAX_TURN_STEPS) return WalkStep(normalize(phone + offsetDeg))

        this.turn = null
        steadyPhoneDeg = phone
        val phoneOnly = settled && turn.axisTrusted && onlyPhoneTurned(turn, phone)
        if (phoneOnly) offsetDeg = angleDiff(turn.walkDeg, phone)
        steadyWalkDeg = phone + offsetDeg
        return if (phoneOnly) {
            WalkStep(normalize(turn.walkDeg), redoSteps = turn.steps - 1)
        } else {
            WalkStep(normalize(phone + offsetDeg))
        }
    }

    /** Da li osa hoda kaže da se u [turn] okrenuo samo telefon (sada ka [phone]), a ne telo. */
    private fun onlyPhoneTurned(turn: Turn, phone: Double): Boolean {
        // Telefon ostao okrenut: osa posle okreta je ili stari smer (samo telefon) ili nov (telo).
        // Telefon se vratio: ako je bio dovoljno okrenut, osa cele epizode je stari smer samo ako
        // telo nije skretalo.
        val fromNs = when {
            axisDiff(phone, turn.phoneDeg) >= MIN_SEPARATION_DEG -> stepTimes.first()
            turn.maxDeg >= MIN_SEPARATION_DEG -> turn.fromNs
            else -> return false
        }
        val axis = walkingAxis(fromNs) ?: return false
        return axisDiff(axis, turn.walkDeg) <= AXIS_MATCH_DEG
    }

    /** Pravac telefona se u poslednja dva koraka nije menjao (nema okretanja). */
    private fun phoneSteady(): Boolean {
        if (stepTimes.size <= AXIS_STEPS) return false
        val first = stepPhoneDegs.first()
        return stepPhoneDegs.all { abs(angleDiff(it, first)) <= MAX_TURN_DEG }
    }

    /** Fina ispravka odstupanja iz ose hoda (vidi opis klase). */
    private fun correctOffset(phone: Double) {
        // Okretanje u tim koracima: osa bi bila mešavina dva pravca.
        if (!phoneSteady()) return
        val axis = walkingAxis(stepTimes.first()) ?: return
        var diff = angleDiff(axis, phone + offsetDeg)
        if (diff > 90.0) diff -= 180.0
        if (diff < -90.0) diff += 180.0
        axisAgrees = abs(diff) <= AXIS_MATCH_DEG
        if (abs(diff) > MAX_CORRECTION_DEG) return
        offsetDeg = angleDiff(offsetDeg + OFFSET_GAIN * diff, 0.0)
    }

    /**
     * Pravac glavne ose horizontalnog ubrzanja od [fromNs] (azimut, jedan od dva smera),
     * ili null ako ubrzanja nema ili nije izraženo u jednom pravcu.
     */
    private fun walkingAxis(fromNs: Long): Double? {
        val window = samples.filter { it.timestampNs >= fromNs }
        if (window.size < MIN_AXIS_SAMPLES) return null
        val meanE = window.sumOf { it.east } / window.size
        val meanN = window.sumOf { it.north } / window.size
        var see = 0.0
        var snn = 0.0
        var sen = 0.0
        for (s in window) {
            val de = s.east - meanE
            val dn = s.north - meanN
            see += de * de
            snn += dn * dn
            sen += de * dn
        }
        see /= window.size
        snn /= window.size
        sen /= window.size
        val half = (see + snn) / 2
        val spread = sqrt(((see - snn) / 2) * ((see - snn) / 2) + sen * sen)
        val major = half + spread
        val minor = half - spread
        if (major < MIN_AXIS_VARIANCE || minor / major > MAX_AXIS_RATIO) return null
        val angle = 0.5 * atan2(2 * sen, see - snn) // od istoka, suprotno kazaljci
        return azimuth(cos(angle), sin(angle))
    }

    companion object {
        /** Koliko se odstupanje pomera ka izmerenoj osi hoda posle svakog koraka. */
        const val OFFSET_GAIN = 0.15

        /** Najveća razlika ose hoda od procene koja se prihvata kao ispravka. */
        const val MAX_CORRECTION_DEG = 45.0

        /** Okret gravitacije u koordinatama telefona koji znači da je telefon premešten. */
        const val REPOSITION_DEG = 45.0

        /** Okret pravca telefona (u odnosu na poslednji korak bez okreta) koji može biti skretanje. */
        const val TURN_DEG = 30.0

        /** Osa hoda "je" neki smer ako je od njega najviše ovoliko (bez znaka). */
        const val AXIS_MATCH_DEG = 15.0

        /** Okret telefona manji od ovoga (ili blizu 180°) se po osi hoda ne razlikuje od skretanja. */
        const val MIN_SEPARATION_DEG = 30.0

        /** Najviše koraka od početka okreta do odluke; toliko se koraka može ponoviti. */
        const val MAX_TURN_STEPS = 6

        /** Telefon je smiren kad mu se (sporo izglađena) gravitacija za 0,5 s pomeri manje od ovoga. */
        const val SETTLE_DEG = 12.0

        private const val SETTLE_CHECK_NS = 500_000_000L
        private const val MAX_UNSETTLED_NS = 4_000_000_000L

        /** Osa hoda se računa iz poslednja dva koraka (pun ciklus: levi + desni). */
        private const val AXIS_STEPS = 2
        private const val MAX_TURN_DEG = 20.0

        private const val MIN_AXIS_SAMPLES = 20
        private const val MIN_AXIS_VARIANCE = 0.05 // (m/s²)² - ispod toga korisnik stoji
        private const val MAX_AXIS_RATIO = 0.4 // sporedna/glavna varijansa: hod je napred-nazad

        private const val SAMPLE_WINDOW_NS = 5_000_000_000L // okret: do MAX_TURN_STEPS koraka
        private const val SMOOTH_ALPHA = 0.1 // prikaz: bez njihanja telefona u džepu
        private const val UP_FAST_ALPHA = 0.05 // τ ≈ 0,4 s: njihanje noge se usrednjava
        private const val UP_SLOW_ALPHA = 0.02 // τ ≈ 1 s: smirivanje posle premeštanja

        private fun azimuth(east: Double, north: Double) = Math.toDegrees(atan2(east, north))

        private fun azimuthOr(east: Double, north: Double, fallback: Double) =
            if (hypot(east, north) > 1e-6) azimuth(east, north) else fallback

        private fun normalize(deg: Double) = ((deg % 360.0 + 360.0) % 360.0).toFloat()

        /** Razlika uglova [a] - [b] svedena na -180..180. */
        private fun angleDiff(a: Double, b: Double) = ((a - b) % 360.0 + 540.0) % 360.0 - 180.0

        /** Ugao između pravaca [a] i [b] bez znaka (0..90): osa hoda ne zna napred od nazad. */
        private fun axisDiff(a: Double, b: Double) = abs(angleDiff(a, b)).let { minOf(it, 180.0 - it) }

        private fun angleBetween(a: DoubleArray, b: DoubleArray): Double {
            val dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
            val norm = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]) * sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2])
            if (norm == 0.0) return 0.0
            return Math.toDegrees(acos((dot / norm).coerceIn(-1.0, 1.0)))
        }
    }
}
