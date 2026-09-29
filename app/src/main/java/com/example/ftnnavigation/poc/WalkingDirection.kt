package com.example.ftnnavigation.poc

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot
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
 * - **Pravac hoda iz ubrzanja** uči odstupanje (telefon u ruci ne mora da gleda kuda se ide):
 *   u svakom koraku uzdužno ubrzanje (u koordinatama sveta) prednjači vertikalnom za oko
 *   četvrt koraka (izmereno na snimcima hoda), pa korelacija horizontalnog ubrzanja sa
 *   vertikalnim kasnije minus ranije daje pravac hoda **sa znakom** ([phaseDirection]). Bočno
 *   ljuljanje (jednom po dva koraka) se u tome poništava. Odstupanje se pomera ka njemu posle
 *   svakog koraka, ali samo ako je signal jak ([MIN_PHASE_STRENGTH]) i telefon se u tim
 *   koracima nije okretao; ispravka veća od [MAX_CORRECTION_DEG] tek kad se dva poslednja
 *   jaka merenja slože. Tada se i prethodni koraci koji su već išli novim pravcem (slabo
 *   izmereni, prvi korak posle stajanja) ponavljaju u novom smeru ([WalkStep.redoSteps]) -
 *   bočni hod iz stajanja se vidi tek na 3.-5. koraku. Posle skretanja, ako je izmereni
 *   pravac daleko od procene (telefon se sada drži drugačije), odstupanje se odmah postavlja
 *   prema merenju.
 * - **Okret telefona bez okreta tela** (telefon u ruci okrenut na stranu, pogled u stranu dok
 *   se ide pravo): nagli okret pravca telefona je prvo skretanje (tačka odmah prati). Kad se
 *   telefon smiri (dva koraka), pravac hoda iz ubrzanja kaže šta je bilo - on je u koordinatama
 *   sveta, pa je posle okreta samo telefona isti kao pre. Ako je ostao na starom smeru,
 *   odstupanje se ispravlja za okret telefona, a koraci od početka okreta se ponavljaju u starom
 *   smeru ([WalkStep.redoSteps]). Isto i kad se telefon posle kratkog okreta vrati (pravac cele
 *   epizode). Veruje mu se samo ako se pre okreta slagao sa smerom hoda, a okret mora biti bar
 *   [MIN_SEPARATION_DEG].
 *
 * Pretpostavka: na početku ([reset], dugme Start) telefon je u ruci, okrenut napred.
 * Ograničenje: okret tela dok se telefon premešta (do [MAX_UNSETTLED_NS]) se ne vidi.
 *
 * Uglovi su azimuti u stepenima (0 = sever, u smeru kazaljke). Filteri su podešeni za
 * ~50 Hz (SENSOR_DELAY_GAME) za oba senzora.
 */
class WalkingDirection {
    /**
     * Smer hoda koraka (azimut 0..360). [redoSteps] prethodnih koraka treba ponoviti u istom
     * smeru - pokazalo se da je okret bio samo okret telefona, ne skretanje, ili je velika
     * ispravka pokazala da su i ti koraci išli novim pravcem.
     */
    data class WalkStep(val headingDeg: Float, val redoSteps: Int = 0)

    /** Korak (za ponavljanje posle velike ispravke): jak pravac iz ubrzanja, null ako ga nije bilo. */
    private class StepPhase(val afterPause: Boolean) {
        var strongDeg: Double? = null
        var turning = false
    }

    private class Sample(val timestampNs: Long, val east: Double, val north: Double, val up: Double)

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
    private val stepPhases = ArrayDeque<StepPhase>()

    // Poslednji korak pre stajanja: pravac iz ubrzanja se ne meri preko koraka pre njega (stari pravac).
    private var pauseFromNs = 0L

    private var offsetDeg = 0.0
    private var anchored = true

    // Pravac telefona i smer hoda u poslednjem koraku bez okreta; da li se pravac iz ubrzanja slagao.
    private var steadyPhoneDeg: Double? = null
    private var steadyWalkDeg = 0.0
    private var axisAgrees = false
    private var turn: Turn? = null

    // Pravac hoda iz ubrzanja u prethodnom koraku (null ako ga nije bilo) - za velike ispravke.
    private var lastPhaseDeg: Double? = null

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

    /** Sirovi akcelerometar (koordinate telefona); pamti se u koordinatama sveta. */
    fun onAccelerometer(x: Float, y: Float, z: Float, timestampNs: Long) {
        if (!hasRotation) return
        val r = rotation
        samples.addLast(
            Sample(
                timestampNs,
                east = (r[0] * x + r[1] * y + r[2] * z).toDouble(),
                north = (r[3] * x + r[4] * y + r[5] * z).toDouble(),
                up = (r[6] * x + r[7] * y + r[8] * z).toDouble(),
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
        val afterPause = stepTimes.size == 1 || timestampNs - previousStepNs > PAUSE_NS
        if (afterPause) pauseFromNs = previousStepNs
        stepPhases.addLast(StepPhase(afterPause))
        if (stepPhases.size > MAX_TURN_STEPS + 1) stepPhases.removeFirst()
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
        stepPhases.clear()
        pauseFromNs = 0L
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
        stepPhases.clear()
    }

    private fun forgetTurns() {
        turn = null
        steadyPhoneDeg = null
        axisAgrees = false
        lastPhaseDeg = null
    }

    /** Smer koraka sa pravcem telefona [phone]: fina ispravka, ili praćenje okreta (vidi opis klase). */
    private fun steer(phone: Double, previousStepNs: Long): WalkStep {
        val steady = steadyPhoneDeg
        if (turn == null && steady != null && abs(angleDiff(phone, steady)) > TURN_DEG) {
            turn = Turn(previousStepNs, steady, steadyWalkDeg, axisAgrees)
            lastPhaseDeg = null
        }
        val turn = turn
        if (turn == null) {
            val redo = correctOffset(phone)
            steadyPhoneDeg = phone
            steadyWalkDeg = phone + offsetDeg
            return WalkStep(normalize(phone + offsetDeg), redo)
        }

        stepPhases.last().turning = true
        turn.steps++
        turn.maxDeg = maxOf(turn.maxDeg, abs(angleDiff(phone, turn.phoneDeg)))
        val settled = phoneSteady()
        // Dok se telefon okreće - kao skretanje.
        if (!settled && turn.steps < MAX_TURN_STEPS) return WalkStep(normalize(phone + offsetDeg))

        this.turn = null
        steadyPhoneDeg = phone
        val walk = if (settled) walkAfterTurn(turn, phone) else null
        val phoneOnly = walk != null && turn.axisTrusted && abs(angleDiff(walk, turn.walkDeg)) <= AXIS_MATCH_DEG
        when {
            phoneOnly -> offsetDeg = angleDiff(turn.walkDeg, phone)
            // Skretanje, a hod posle njega je daleko od procene: telefon se sada drži drugačije.
            walk != null && abs(angleDiff(walk, phone + offsetDeg)) > MAX_CORRECTION_DEG -> offsetDeg = angleDiff(walk, phone)
        }
        lastPhaseDeg = walk
        steadyWalkDeg = phone + offsetDeg
        return if (phoneOnly) {
            WalkStep(normalize(turn.walkDeg), redoSteps = turn.steps - 1)
        } else {
            WalkStep(normalize(phone + offsetDeg))
        }
    }

    /**
     * Pravac hoda iz ubrzanja posle okreta [turn] (telefon sada ka [phone]): ako je jednak smeru
     * od pre okreta, okrenuo se samo telefon. Null ako se ne zna.
     */
    private fun walkAfterTurn(turn: Turn, phone: Double): Double? {
        // Telefon ostao okrenut: poslednja dva koraka. Telefon se vratio: ako je bio dovoljno
        // okrenut, cela epizoda - stari smer je samo ako telo nije skretalo.
        val fromNs = when {
            abs(angleDiff(phone, turn.phoneDeg)) >= MIN_SEPARATION_DEG -> stepTimes.first()
            turn.maxDeg >= MIN_SEPARATION_DEG -> turn.fromNs
            else -> return null
        }
        return phaseDirection(fromNs)
    }

    /** Pravac telefona se u poslednja dva koraka nije menjao (nema okretanja). */
    private fun phoneSteady(): Boolean {
        if (stepTimes.size <= AXIS_STEPS) return false
        val first = stepPhoneDegs.first()
        return stepPhoneDegs.all { abs(angleDiff(it, first)) <= MAX_TURN_DEG }
    }

    /**
     * Odstupanje se pomera ka pravcu hoda iz ubrzanja (vidi opis klase); vraća koliko prethodnih
     * koraka treba ponoviti u novom smeru (samo posle velike ispravke).
     */
    private fun correctOffset(phone: Double): Int {
        // Okretanje u tim koracima: pravac bi bio mešavina dva.
        if (!phoneSteady()) return 0
        // Prvi korak posle stajanja: bez koraka pre stajanja (inače jako merenje starog pravca -
        // snimak 17:24, bočni hod na drugu stranu posle 2,9 s stajanja).
        val walk = phaseDirection(maxOf(stepTimes.first(), pauseFromNs)) ?: return 0
        val previous = lastPhaseDeg
        lastPhaseDeg = walk
        stepPhases.last().strongDeg = walk
        val diff = angleDiff(walk, phone + offsetDeg)
        axisAgrees = abs(diff) <= AXIS_MATCH_DEG
        // Jedan prozor ume da prevari (zastoj, sudar) - velika ispravka tek kad se slože dva
        // poslednja jaka merenja (ne moraju biti u uzastopnim koracima), i tada odmah cela
        // (bočni hod, telefon se drži drugačije) - postepeno je tačka kasnila 5-8 koraka.
        if (abs(diff) > MAX_CORRECTION_DEG) {
            if (previous == null || abs(angleDiff(walk, previous)) > AXIS_MATCH_DEG) return 0
            offsetDeg = angleDiff(walk, phone)
            return stepsWalkedToward(walk)
        }
        offsetDeg = angleDiff(offsetDeg + OFFSET_GAIN * diff, 0.0)
        return 0
    }

    /**
     * Koliko je koraka pre tekućeg verovatno već išlo pravcem [walk]: unazad dok se ne naiđe na
     * jako merenje drugog pravca ili na okret telefona. Prvi korak posle stajanja je poslednji
     * (bočni hod iz stajanja: merenje prvog koraka je skoro sve stajanje, pa je slabo).
     */
    private fun stepsWalkedToward(walk: Double): Int {
        var redo = 0
        for (i in stepPhases.size - 2 downTo 0) {
            val step = stepPhases[i]
            val deg = step.strongDeg
            if (step.turning || (deg != null && abs(angleDiff(deg, walk)) > AXIS_MATCH_DEG)) break
            redo++
            if (step.afterPause) break
        }
        return redo
    }

    /**
     * Pravac hoda (azimut, sa znakom) iz ubrzanja od [fromNs]: vektor korelacije horizontalnog
     * ubrzanja h(t) sa vertikalnim v(t + τ) - v(t - τ) za τ do [MAX_LAG_SAMPLES] uzoraka. Uzdužno
     * ubrzanje prednjači vertikalnom, pa vektor pokazuje napred. Null ako ubrzanja nema ili je
     * veza slaba (stajanje, okretanje, udarci).
     */
    private fun phaseDirection(fromNs: Long): Double? {
        val window = samples.filter { it.timestampNs >= fromNs }
        val n = window.size
        if (n < MIN_AXIS_SAMPLES) return null
        val meanE = window.sumOf { it.east } / n
        val meanN = window.sumOf { it.north } / n
        val meanU = window.sumOf { it.up } / n
        val east = DoubleArray(n) { window[it].east - meanE }
        val north = DoubleArray(n) { window[it].north - meanN }
        val up = DoubleArray(n) { window[it].up - meanU }
        var ce = 0.0
        var cn = 0.0
        for (lag in 1..MAX_LAG_SAMPLES) {
            for (i in 0 until n - lag) {
                ce += east[i] * up[i + lag] - east[i + lag] * up[i]
                cn += north[i] * up[i + lag] - north[i + lag] * up[i]
            }
        }
        val horizontal = (0 until n).sumOf { east[it] * east[it] + north[it] * north[it] } / n
        val vertical = (0 until n).sumOf { up[it] * up[it] } / n
        if (horizontal < MIN_AXIS_VARIANCE || vertical == 0.0) return null
        val strength = hypot(ce, cn) / (n * MAX_LAG_SAMPLES) / sqrt(horizontal * vertical)
        if (strength < MIN_PHASE_STRENGTH) return null
        return azimuth(ce, cn)
    }

    companion object {
        /** Koliko se odstupanje pomera ka pravcu hoda iz ubrzanja posle svakog koraka. */
        const val OFFSET_GAIN = 0.3

        /** Veća ispravka od ove traži da se i prethodni korak slaže (vidi [correctOffset]). */
        const val MAX_CORRECTION_DEG = 45.0

        /** Okret gravitacije u koordinatama telefona koji znači da je telefon premešten. */
        const val REPOSITION_DEG = 45.0

        /** Okret pravca telefona (u odnosu na poslednji korak bez okreta) koji može biti skretanje. */
        const val TURN_DEG = 30.0

        /** Pravac hoda iz ubrzanja "je" neki smer ako je od njega najviše ovoliko. */
        const val AXIS_MATCH_DEG = 20.0

        /** Okret telefona manji od ovoga se po pravcu hoda iz ubrzanja ne razlikuje od skretanja. */
        const val MIN_SEPARATION_DEG = 30.0

        /** Najviše koraka od početka okreta do odluke; toliko se koraka može ponoviti. */
        const val MAX_TURN_STEPS = 6

        /** Telefon je smiren kad mu se (sporo izglađena) gravitacija za 0,5 s pomeri manje od ovoga. */
        const val SETTLE_DEG = 12.0

        private const val SETTLE_CHECK_NS = 500_000_000L
        private const val MAX_UNSETTLED_NS = 4_000_000_000L

        /** Pravac hoda se računa iz poslednja dva koraka (pun ciklus: levi + desni). */
        private const val AXIS_STEPS = 2
        private const val MAX_TURN_DEG = 20.0

        private const val MIN_AXIS_SAMPLES = 20
        private const val MIN_AXIS_VARIANCE = 0.05 // (m/s²)² - ispod toga korisnik stoji

        /** Normalizovana korelacija (0..1) ispod koje se pravac iz ubrzanja ne uzima (snimci: hod uglavnom 0,45-0,9). */
        const val MIN_PHASE_STRENGTH = 0.35
        private const val MAX_LAG_SAMPLES = 15 // 0,3 s na 50 Hz: do pola koraka

        /** Razmak koraka duži od ovoga je stajanje (bočni hod: do ~2 s po koraku, stajanje od 2,9 s). */
        private const val PAUSE_NS = 2_500_000_000L

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

        private fun angleBetween(a: DoubleArray, b: DoubleArray): Double {
            val dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
            val norm = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]) * sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2])
            if (norm == 0.0) return 0.0
            return Math.toDegrees(acos((dot / norm).coerceIn(-1.0, 1.0)))
        }
    }
}
