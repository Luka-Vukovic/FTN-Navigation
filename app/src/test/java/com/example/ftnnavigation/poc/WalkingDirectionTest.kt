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

    /**
     * Hod [seconds] sekundi u pravcu [walkDeg] sa položajem telefona [pose] (u trenutku t, s);
     * vraća smer hoda za svaki korak.
     */
    private fun walk(
        seconds: Double,
        walkDeg: Double,
        forwardAmp: Double = 1.5,
        lateralAmp: Double = 0.5,
        pose: (Double) -> Pose,
    ): List<Float> {
        val headings = mutableListOf<Float>()
        val f = forward(walkDeg)
        val side = f cross up
        val end = timeNs + (seconds * 1e9).toLong()
        while (timeNs < end) {
            val t = timeNs / 1e9
            val p = pose(t)
            direction.onRotation(matrix(p), timeNs)
            // Napred-nazad i gore-dole sa svakim korakom, bočno ljuljanje sa svakim drugim.
            val world = f * (forwardAmp * sin(2 * PI * 2 * t)) +
                side * (lateralAmp * sin(2 * PI * t)) +
                up * (9.81 + 2.0 * sin(2 * PI * 2 * t + 1.0)) +
                Vec(random.nextDouble(-0.2, 0.2), random.nextDouble(-0.2, 0.2), random.nextDouble(-0.2, 0.2))
            direction.onAccelerometer(
                (world dot p.x).toFloat(), (world dot p.y).toFloat(), (world dot p.z).toFloat(), timeNs,
            )
            timeNs += sampleNs
            if (timeNs % stepNs == 0L) direction.onStep(timeNs)?.let(headings::add)
        }
        return headings
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
    fun standingStill_offsetUnchanged() {
        stand(1.0, inHand(45.0))
        direction.reset()
        stand(3.0, inHand(45.0))
        assertNear(45.0, direction.heading()!!, 1.0)
        assertTrue(direction.isAnchored)
    }
}
