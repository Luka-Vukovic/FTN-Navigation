package com.example.ftnnavigation.poc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class WalkingDirectionTest {
    private val sampleNs = 20_000_000L // 50 Hz, kao SENSOR_DELAY_GAME
    private val stepNs = 500_000_000L // 2 koraka u sekundi

    private data class Vec(val e: Double, val n: Double, val u: Double) {
        operator fun plus(o: Vec) = Vec(e + o.e, n + o.n, u + o.u)
        operator fun times(k: Double) = Vec(e * k, n * k, u * k)
        infix fun dot(o: Vec) = e * o.e + n * o.n + u * o.u
        infix fun cross(o: Vec) = Vec(n * o.u - u * o.n, u * o.e - e * o.u, e * o.n - n * o.e)
    }

    /** Ose telefona u koordinatama sveta (istok, sever, gore). */
    private class Pose(val y: Vec, val z: Vec) {
        val x = y cross z
    }

    private fun forward(azimuthDeg: Double) = Math.toRadians(azimuthDeg).let { Vec(sin(it), cos(it), 0.0) }

    private val up = Vec(0.0, 0.0, 1.0)

    /** Telefon leži u ruci, gornja ivica ka [azimuthDeg]. */
    private fun inHand(azimuthDeg: Double) = Pose(y = forward(azimuthDeg), z = up)

    /**
     * Telefon uspravno u džepu na nozi, ekran okrenut ka [screenAzimuthDeg]; noga ga njiše
     * napred-nazad za [swingDeg] (oko ose poprečne na hod [walkAzimuthDeg]).
     */
    private fun inPocket(screenAzimuthDeg: Double, walkAzimuthDeg: Double, swingDeg: Double = 0.0): Pose {
        val s = Math.toRadians(swingDeg)
        val f = forward(walkAzimuthDeg)
        // Njihanje: "gore" i "napred" se okreću u ravni hoda.
        fun swing(v: Vec): Vec {
            val along = v dot f
            val vertical = v dot up
            val rest = v + f * -along + up * -vertical
            return rest + f * (along * cos(s) + vertical * sin(s)) + up * (vertical * cos(s) - along * sin(s))
        }
        return Pose(y = swing(up), z = swing(forward(screenAzimuthDeg)))
    }

    private fun matrix(p: Pose) = floatArrayOf(
        p.x.e.toFloat(), p.y.e.toFloat(), p.z.e.toFloat(),
        p.x.n.toFloat(), p.y.n.toFloat(), p.z.n.toFloat(),
        p.x.u.toFloat(), p.y.u.toFloat(), p.z.u.toFloat(),
    )

    private val random = Random(7)
    private var timeNs = 0L
    private val direction = WalkingDirection()

    /** Smer svakog koraka od početka testa, sa primenjenim ponavljanjima koraka. */
    private val allHeadings = mutableListOf<Float>()

    /**
     * Hod [seconds] sekundi u pravcu [walkDeg] sa položajem telefona [pose] (u trenutku t, s);
     * vraća smer hoda za svaki korak (ponavljanje koraka iz kasnijeg hoda se vidi samo u [allHeadings]).
     * [stepEveryNs]: razmak detektovanih koraka (bočni hod: ~1,2 s), računa se od početka hoda.
     */
    private fun walk(
        seconds: Double,
        walkDeg: Double,
        forwardAmp: Double = 1.5,
        lateralAmp: Double = 0.5,
        stepEveryNs: Long = stepNs,
        pose: (Double) -> Pose,
    ): List<Float> {
        val start = allHeadings.size
        val stepOriginNs = if (stepEveryNs == stepNs) 0L else timeNs
        val f = forward(walkDeg)
        val side = f cross up
        val end = timeNs + (seconds * 1e9).toLong()
        while (timeNs < end) {
            val t = timeNs / 1e9
            val p = pose(t)
            direction.onRotation(matrix(p), timeNs)
            // Napred-nazad i gore-dole sa svakim korakom, bočno ljuljanje sa svakim drugim.
            // Vertikalno kasni za uzdužnim oko četvrt koraka (izmereno na snimcima hoda).
            val world = f * (forwardAmp * sin(2 * PI * 2 * t)) +
                side * (lateralAmp * sin(2 * PI * t)) +
                up * (9.81 + 2.0 * sin(2 * PI * 2 * t - PI / 2)) +
                Vec(random.nextDouble(-0.2, 0.2), random.nextDouble(-0.2, 0.2), random.nextDouble(-0.2, 0.2))
            direction.onAccelerometer(
                (world dot p.x).toFloat(), (world dot p.y).toFloat(), (world dot p.z).toFloat(), timeNs,
            )
            timeNs += sampleNs
            if ((timeNs - stepOriginNs) % stepEveryNs == 0L) direction.onStep(timeNs)?.let { step ->
                for (i in allHeadings.size - minOf(step.redoSteps, allHeadings.size) until allHeadings.size) {
                    allHeadings[i] = step.headingDeg
                }
                allHeadings.add(step.headingDeg)
            }
        }
        return allHeadings.subList(start, allHeadings.size).toList()
    }

    /** Stajanje [seconds] sekundi sa telefonom u položaju [pose] (samo gravitacija). */
    private fun stand(seconds: Double, pose: Pose) {
        val end = timeNs + (seconds * 1e9).toLong()
        while (timeNs < end) {
            direction.onRotation(matrix(pose), timeNs)
            val g = up * 9.81
            direction.onAccelerometer((g dot pose.x).toFloat(), (g dot pose.y).toFloat(), (g dot pose.z).toFloat(), timeNs)
            timeNs += sampleNs
        }
    }

    private fun assertNear(expectedDeg: Double, actual: Float, toleranceDeg: Double, message: String = "") {
        val diff = abs(angleDiffDeg(actual, expectedDeg.toFloat()))
        assertTrue("$message expected ~$expectedDeg°, got $actual° (razlika $diff°)", diff <= toleranceDeg)
    }

    @Test
    fun phoneInHand_pointingForward_followsPhone() {
        stand(1.0, inHand(30.0))
        direction.reset()
        val headings = walk(10.0, 30.0) { inHand(30.0) }
        headings.forEach { assertNear(30.0, it, 10.0) }
    }

    @Test
    fun phoneInHand_heldAtAnAngle_learnsWalkingDirection() {
        // Telefon je 30° udesno od pravca hoda; prvi koraci idu kuda gleda telefon.
        stand(1.0, inHand(60.0))
        direction.reset()
        val headings = walk(10.0, 30.0) { inHand(60.0) }
        assertNear(60.0, headings.first(), 5.0, "prvi korak")
        headings.takeLast(5).forEach { assertNear(30.0, it, 5.0) }
    }

    @Test
    fun phoneInHand_heldFarFromWalkingDirection_learnsIt() {
        // Snimak 29.09.: telefon ~65° od pravca hoda od samog Start-a (više od MAX_CORRECTION_DEG).
        stand(1.0, inHand(100.0))
        direction.reset()
        val headings = walk(10.0, 30.0) { inHand(100.0) }
        // Prvi koraci su išli kuda gleda telefon, pa se posle velike ispravke ponavljaju.
        headings.forEach { assertNear(30.0, it, 5.0) }
    }

    @Test
    fun sidestep_followedWithinFewSteps() {
        // Snimci 29.09. (13:23, 13:25): bočni hod, telefon i dalje gleda napred. Tačka je
        // postepeno (30 %/korak) kasnila 5-8 koraka; kad se dva merenja slože - odmah.
        stand(1.0, inHand(30.0))
        direction.reset()
        walk(5.0, 30.0) { inHand(30.0) }
        val sideways = walk(5.0, 120.0) { inHand(30.0) }
        sideways.drop(4).forEach { assertNear(120.0, it, 10.0) }
    }

    @Test
    fun sidestepFromStanding_earlyStepsRedone() {
        // Snimci 29.09. (16:38, 16:41): hod, stajanje, pa bočni hod. Prvi korak posle stajanja
        // se ne može izmeriti, pa tačka prelazi tek na 3.-5. koraku - ti koraci se ponavljaju.
        stand(1.0, inHand(30.0))
        direction.reset()
        val straight = walk(5.0, 30.0) { inHand(30.0) }
        stand(5.0, inHand(30.0))
        val sideways = walk(5.0, 120.0) { inHand(30.0) }
        sideways.forEach { assertNear(120.0, it, 10.0) }
        // Koraci pre stajanja ostaju napred.
        allHeadings.take(straight.size).forEach { assertNear(30.0, it, 10.0) }
    }

    @Test
    fun sidestepBackAfterShortStop_allStepsRedone() {
        // Snimak 29.09. 17:24: bočno na jednu stranu, 2,9 s stajanja, pa na drugu. Prvi korak
        // posle stajanja je merio i dva koraka pre njega (jak stari pravac) i ostajao na staroj
        // strani. Koraci bočnog hoda su na ~1,2 s kao na snimku, a stari hod je jači od novog
        // (na snimku je to merenje bilo 0,38 - tik iznad praga; sa 0,5 s i istom jačinom se ne vidi).
        val sidestepNs = 1_200_000_000L
        stand(1.0, inHand(30.0))
        direction.reset()
        walk(5.0, 30.0) { inHand(30.0) }
        val first = walk(5.0, 120.0, forwardAmp = 2.5, stepEveryNs = sidestepNs) { inHand(30.0) }
        stand(1.4, inHand(30.0)) // poslednji korak u 10,8 s, sledeći u 13,6 s: razmak 2,8 s
        val back = walk(6.0, 300.0, forwardAmp = 1.0, stepEveryNs = sidestepNs) { inHand(30.0) }
        first.takeLast(2).forEach { assertNear(120.0, it, 10.0) }
        back.forEach { assertNear(300.0, it, 10.0) }
    }

    @Test
    fun lateralAxis_isNotTakenAsWalkingDirection() {
        // Jako bočno ljuljanje (u ruci): glavna osa ubrzanja je poprečna na hod - ne sme da
        // okrene smer za 90°.
        stand(1.0, inHand(30.0))
        direction.reset()
        val headings = walk(10.0, 30.0, forwardAmp = 0.3, lateralAmp = 1.5) { inHand(30.0) }
        headings.forEach { assertNear(30.0, it, 5.0) }
    }

    @Test
    fun phonePutInPocketBackwards_keepsWalkingDirection() {
        // Hod sa telefonom u ruci, pa telefon u džep ekranom napred (poleđina gleda nazad:
        // pravac telefona je suprotan hodu), pa hod dalje u istom pravcu.
        stand(1.0, inHand(30.0))
        direction.reset()
        walk(5.0, 30.0) { inHand(30.0) }
        val pocket = inPocket(screenAzimuthDeg = 30.0, walkAzimuthDeg = 30.0)
        stand(0.5, pocket)
        assertFalse("premeštanje telefona nije prepoznato", direction.isAnchored)
        assertNear(30.0, direction.heading()!!, 5.0, "smer dok se telefon smiruje")
        stand(3.0, pocket)
        assertTrue("telefon se nije smirio", direction.isAnchored)
        assertNear(180.0, direction.offset.toFloat(), 10.0, "odstupanje")

        val headings = walk(10.0, 30.0) { t -> inPocket(30.0, 30.0, swingDeg = 25.0 * sin(2 * PI * t)) }
        headings.forEach { assertNear(30.0, it, 10.0) }
    }

    /**
     * Analiza promašaja 12.10.2026 (NTP 09.10.: posle vožnje liftom u džepu odstupanje 45° umesto ~0, 24 m): smer iz džepa
     * je bio pogrešan (telefon se u džepu okrenuo), a posle vađenja u ruku se nastavljao. Iz džepa u ruku ekranom nagore -
     * odstupanje 0, kao na Start-u.
     */
    @Test
    fun phoneTakenFromPocketIntoHand_offsetReset() {
        stand(1.0, inHand(30.0))
        direction.reset()
        walk(5.0, 30.0) { inHand(30.0) }
        stand(3.0, inPocket(screenAzimuthDeg = 30.0, walkAzimuthDeg = 30.0))
        assertTrue(direction.isAnchored)
        // Telefon se u džepu okrenuo oko uspravne ose: smer iz džepa je sada 45° pogrešan.
        stand(1.0, inPocket(screenAzimuthDeg = 75.0, walkAzimuthDeg = 30.0))
        assertNear(75.0, direction.heading()!!, 5.0, "smer u džepu")
        stand(3.0, inHand(30.0))
        assertTrue("telefon se nije smirio u ruci", direction.isAnchored)
        assertNear(0.0, direction.offset.toFloat(), 5.0, "odstupanje")
        val hs = walk(4.0, 30.0) { inHand(30.0) }
        hs.forEach { assertNear(30.0, it, 10.0, "u ruci") }
    }

    /**
     * Teren 21.10.2026 (napolju, ka AMF-u): telefon je iz džepa izvađen sporo (~4 s, nagib < 45° za 1,5 s) - premeštanje nije
     * prepoznato i ostalo je odstupanje iz džepa (+149°), pokazivač ~150° pogrešno. Uspravan pa smiren u ruci = odstupanje 0.
     */
    @Test
    fun phoneTakenSlowlyFromPocketIntoHand_offsetReset() {
        fun tilted(yawDeg: Double, pitchDeg: Double): Pose {
            val th = Math.toRadians(pitchDeg)
            val f = forward(yawDeg)
            return Pose(y = f * cos(th) + up * sin(th), z = f * -sin(th) + up * cos(th))
        }
        stand(1.0, inHand(30.0))
        direction.reset()
        walk(5.0, 30.0) { inHand(30.0) }
        stand(12.0, inPocket(screenAzimuthDeg = 30.0, walkAzimuthDeg = 30.0))
        assertTrue(direction.isAnchored)
        assertNear(180.0, direction.offset.toFloat(), 10.0, "odstupanje u džepu")
        // Polako iz džepa (uspravno, ekran ka 30°) u ruku nagnutu ka licu (gornja ivica ka 30°) - 4 s, u mestu (kao na snimku).
        for (i in 0 until 40) {
            val k = i / 39.0
            stand(0.1, tilted(210.0 + 180.0 * k, 90.0 - 65.0 * k))
        }
        stand(2.0, tilted(30.0, 25.0))
        assertTrue(direction.isAnchored)
        assertNear(0.0, direction.offset.toFloat(), 10.0, "odstupanje u ruci")
        // Hod u ruci sa slabim pravcem iz ubrzanja - smer ne sme da zavisi od njegove ispravke.
        walk(3.0, 30.0, forwardAmp = 0.3) { tilted(30.0, 25.0) }.forEach { assertNear(30.0, it, 15.0, "u ruci") }
    }

    /**
     * Teren 12.10.2026 (Kula, NTP III): "Ovde sam", pa telefon u džep, a telo se za to vreme okrene ~90° - smer od pre
     * premeštanja to sakrije, pa ceo hod u džepu ide 90° pogrešno. Pravac iz ubrzanja je po koraku slab, ali usrednjen preko
     * više koraka dosledan - odstupanje se ispravi posle nekoliko koraka, a ti koraci se ponove.
     */
    @Test
    fun phonePutInPocketWhileTurning_walkingDirectionFoundInPocket() {
        stand(1.0, inHand(30.0))
        direction.reset()
        walk(5.0, 30.0) { inHand(30.0) }
        stand(0.5, inPocket(screenAzimuthDeg = 30.0, walkAzimuthDeg = 30.0))
        assertFalse(direction.isAnchored)
        // Okret ulevo za 90° dok se telefon smiruje u džepu.
        stand(3.0, inPocket(screenAzimuthDeg = 300.0, walkAzimuthDeg = 300.0))
        assertTrue(direction.isAnchored)
        assertNear(30.0, direction.heading()!!, 10.0, "smer od pre premeštanja")
        val headings = walk(8.0, 300.0, forwardAmp = 0.3) { t -> inPocket(300.0, 300.0, swingDeg = 25.0 * sin(2 * PI * t)) }
        headings.takeLast(8).forEach { assertNear(300.0, it, 20.0, "u džepu") }
        // Koraci pre ispravke su ponovljeni u novom smeru.
        allHeadings.takeLast(headings.size).drop(2).forEach { assertNear(300.0, it, 20.0, "ponovljeni") }
    }

    /**
     * Teren 12.10.2026 (NB I sprat, snimak 175756): telefon u ruci nagnut ka licu (gravitacija y 0,5, z 0,85), pa ~20 s
     * rukovanja u mestu dok se telo okretalo, pa opet isti položaj u ruci. Smirivanja su ostavila odstupanje −46° (smer od
     * pre pokreta), a telefon se drži isto kao pre - odstupanje tog položaja (0).
     */
    @Test
    fun phoneHandledInPlace_backInSameGrip_offsetOfThatGrip() {
        fun tilted(yawDeg: Double, pitchDeg: Double): Pose {
            val th = Math.toRadians(pitchDeg)
            val f = forward(yawDeg)
            return Pose(y = f * cos(th) + up * sin(th), z = f * -sin(th) + up * cos(th))
        }
        stand(1.0, tilted(30.0, 30.0))
        direction.reset()
        walk(5.0, 30.0) { tilted(30.0, 30.0) }
        // Ekranom nadole, pa nazad u ruku - telo se u međuvremenu okrenulo na 120°.
        stand(3.0, tilted(200.0, 150.0))
        assertTrue("telefon se nije smirio ekranom nadole", direction.isAnchored)
        stand(3.0, tilted(120.0, 30.0))
        assertTrue("telefon se nije smirio u ruci", direction.isAnchored)
        assertNear(0.0, direction.offset.toFloat(), 5.0, "odstupanje")
        walk(3.0, 120.0) { tilted(120.0, 30.0) }.forEach { assertNear(120.0, it, 10.0, "u ruci") }
    }

    @Test
    fun phonePutInPocketWhileWalking_thenTurn_isNotStuck() {
        // Telefon ide u džep bez zastajanja; posle toga korisnik skreće - smer mora da prati.
        // Ubrzanje u džepu nema izraženu osu (napred = bočno), pa se osa hoda nikad ne prihvata:
        // smer sme da zavisi samo od smirivanja telefona.
        stand(1.0, inHand(0.0))
        direction.reset()
        walk(5.0, 0.0) { inHand(0.0) }
        walk(6.0, 0.0, forwardAmp = 1.0, lateralAmp = 1.0) { t ->
            inPocket(0.0, 0.0, swingDeg = 25.0 * sin(2 * PI * t))
        }
        assertTrue("telefon se nije smirio u hodu", direction.isAnchored)

        val headings = walk(6.0, 90.0, forwardAmp = 1.0, lateralAmp = 1.0) { t ->
            inPocket(90.0, 90.0, swingDeg = 25.0 * sin(2 * PI * t))
        }
        headings.drop(1).forEach { assertNear(90.0, it, 15.0) }
    }

    @Test
    fun phoneTurnedUpsideDownSlowlyWhileWalking_keepsDirection() {
        // Snimak 29.09. 12:42 (~45 s): telefon se u hodu za 1,5 s okrene naopako (u džep) - između
        // dva koraka < 45°, ukupno 90° (pravac telefona se okrene za 180°). Referenca "gore" je
        // bila poslednji korak, pa premeštanje nije prepoznato i tačka je skretala. Hod u džepu:
        // slabo uzdužno i jako bočno ubrzanje (pravac iz ubrzanja ne pomaže - sa njim je i okret
        // samo telefona to ispravljao).
        fun tilted(thetaDeg: Double): Pose {
            val th = Math.toRadians(thetaDeg)
            val f = forward(30.0)
            return Pose(y = f * cos(th) + up * sin(th), z = f * -sin(th) + up * cos(th))
        }
        stand(1.0, inHand(30.0))
        direction.reset()
        walk(5.0, 30.0) { inHand(30.0) }
        val headings = walk(8.0, 30.0, forwardAmp = 0.3, lateralAmp = 1.0) { t ->
            tilted(-90.0 * ((t - 6.0) / 1.5).coerceIn(0.0, 1.0))
        }
        headings.forEach { assertNear(30.0, it, 15.0) }
    }

    @Test
    fun phoneLiftedAndTurnedBeforePocket_keepsDirectionFromBeforeLift() {
        // Snimak 29.09. 17:49: telefon se u hodu prvo podigne i polako okrene ~25° (ispod
        // TURN_DEG - smer odluta), pa se tek ~2 s kasnije okrene naopako u džep i to se prepozna.
        // Nastavlja se smer od pre podizanja, ne onaj od 0,5-1 s pre prepoznavanja.
        fun pose(yawDeg: Double, pitchDeg: Double): Pose {
            val th = Math.toRadians(pitchDeg)
            val f = forward(yawDeg)
            return Pose(y = f * cos(th) + up * sin(th), z = f * -sin(th) + up * cos(th))
        }
        stand(1.0, inHand(30.0))
        direction.reset()
        walk(5.0, 30.0) { inHand(30.0) }
        val headings = walk(10.0, 30.0, forwardAmp = 0.3, lateralAmp = 1.0) { t ->
            val yaw = 30.0 + 25.0 * ((t - 6.0) / 2.5).coerceIn(0.0, 1.0)
            val lift = 30.0 * ((t - 6.0) / 0.5).coerceIn(0.0, 1.0)
            pose(yaw, lift - 120.0 * ((t - 8.5) / 1.5).coerceIn(0.0, 1.0))
        }
        headings.takeLast(6).forEach { assertNear(30.0, it, 10.0) }
    }

    @Test
    fun phonePutInPocketBeforeFirstStep_keepsStartDirection() {
        // Start sa telefonom u ruci (okrenut ka 120°), pa odmah u džep i tek onda hod.
        stand(1.0, inHand(120.0))
        direction.reset()
        stand(1.0, inHand(120.0))
        stand(4.0, inPocket(screenAzimuthDeg = 120.0, walkAzimuthDeg = 120.0))
        val headings = walk(6.0, 120.0) { t -> inPocket(120.0, 120.0, swingDeg = 25.0 * sin(2 * PI * t)) }
        headings.forEach { assertNear(120.0, it, 10.0) }
    }

    @Test
    fun phoneInPocket_bodyTurns_headingFollowsTurnAtOnce() {
        stand(1.0, inHand(0.0))
        direction.reset()
        walk(4.0, 0.0) { inHand(0.0) }
        stand(2.0, inPocket(0.0, 0.0))
        walk(6.0, 0.0) { t -> inPocket(0.0, 0.0, swingDeg = 25.0 * sin(2 * PI * t)) }

        // Skretanje desno za 90°: telefon se okreće sa telom, smer ga odmah prati.
        val headings = walk(6.0, 90.0) { t -> inPocket(90.0, 90.0, swingDeg = 25.0 * sin(2 * PI * t)) }
        headings.drop(1).forEach { assertNear(90.0, it, 15.0) }
    }

    @Test
    fun phoneInHand_turnedAsideWhileWalkingStraight_keepsDirection() {
        // Telefon u ruci se okrene za 90° (npr. gleda se u stranu), a hod ide pravo: posle dva
        // koraka osa hoda kaže da telo nije skrenulo i koraci od okreta se ponavljaju pravo.
        stand(1.0, inHand(0.0))
        direction.reset()
        walk(5.0, 0.0) { inHand(0.0) }
        walk(4.0, 0.0) { inHand(90.0) }
        assertNear(-90.0, direction.offset.toFloat(), 5.0, "odstupanje")
        // I povratak telefona je samo okret telefona.
        walk(4.0, 0.0) { inHand(0.0) }
        allHeadings.forEachIndexed { i, it -> assertNear(0.0, it, 10.0, "korak $i") }
    }

    @Test
    fun phoneInHand_glancedAsideBriefly_keepsDirection() {
        // Telefon okrenut u stranu samo dva koraka, pa vraćen - smer ostaje, bez vijuganja.
        stand(1.0, inHand(0.0))
        direction.reset()
        walk(5.0, 0.0) { inHand(0.0) }
        walk(1.0, 0.0) { inHand(-70.0) }
        walk(4.0, 0.0) { inHand(0.0) }
        allHeadings.forEachIndexed { i, it -> assertNear(0.0, it, 10.0, "korak $i") }
    }

    @Test
    fun phoneInHand_bodyTurns_headingFollowsTurn() {
        stand(1.0, inHand(0.0))
        direction.reset()
        walk(5.0, 0.0) { inHand(0.0) }
        walk(5.0, 90.0) { inHand(90.0) }
        allHeadings.takeLast(9).forEach { assertNear(90.0, it, 10.0) }
    }

    @Test
    fun sidewaysSway_bodyTurns_isNotTakenAsPhoneTurn() {
        // Bočno ljuljanje: osa hoda je poprečna, pa bi posle skretanja za 90° bila na starom
        // pravcu - takvoj osi se ne veruje, skretanje ostaje skretanje.
        stand(1.0, inHand(0.0))
        direction.reset()
        walk(5.0, 0.0, forwardAmp = 0.3, lateralAmp = 1.5) { inHand(0.0) }
        walk(5.0, 90.0, forwardAmp = 0.3, lateralAmp = 1.5) { inHand(90.0) }
        allHeadings.takeLast(9).forEach { assertNear(90.0, it, 10.0) }
    }

    @Test
    fun uTurn_isAlwaysATurn() {
        // Okret za 180°: osa hoda je ista, pa se ne razlikuje od okreta telefona - telo se okrenulo.
        stand(1.0, inHand(0.0))
        direction.reset()
        walk(5.0, 0.0) { inHand(0.0) }
        walk(5.0, 180.0) { inHand(180.0) }
        allHeadings.takeLast(9).forEach { assertNear(180.0, it, 10.0) }
    }

    @Test
    fun nearStairs_strongPhaseFarFromHeading_ignored() {
        // Teren 09.10.2026 (NTP III -> II): niz stepenice je pravac iz ubrzanja bio ~70-90° od pravog smera, pa je velika
        // ispravka usred kraka okrenula smer (i ponovila 6 koraka). Isti hod van stepeništa - [sidestep_followedWithinFewSteps].
        stand(1.0, inHand(30.0))
        direction.reset()
        walk(5.0, 30.0) { inHand(30.0) }
        direction.nearStairs = true
        walk(5.0, 120.0) { inHand(30.0) }
        allHeadings.forEachIndexed { i, it -> assertNear(30.0, it, 10.0, "korak $i") }
    }

    @Test
    fun nearStairs_turnOnLanding_isBodyTurn() {
        // Okret na podestu: telefon se okreće sa telom, a pravac iz ubrzanja (pogrešan na stepenicama) ostaje star - van
        // stepeništa bi to bio "okret samo telefona" i koraci bi se ponovili u starom smeru (09.10.2026: 5 koraka).
        stand(1.0, inHand(0.0))
        direction.reset()
        walk(5.0, 0.0) { inHand(0.0) }
        direction.nearStairs = true
        walk(4.0, 0.0) { inHand(90.0) }
        allHeadings.takeLast(6).forEach { assertNear(90.0, it, 10.0) }
    }

    @Test
    fun standingStill_offsetUnchanged() {
        stand(1.0, inHand(45.0))
        direction.reset()
        stand(3.0, inHand(45.0))
        assertNear(45.0, direction.heading()!!, 1.0)
        assertTrue(direction.isAnchored)
    }
}
