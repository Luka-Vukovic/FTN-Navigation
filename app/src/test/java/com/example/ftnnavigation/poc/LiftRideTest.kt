package com.example.ftnnavigation.poc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * [LiftRideDetector] na simuliranom ubrzanju (100 Hz). Oblik vožnje sa snimaka 08.10.2026 (NTP): mirno ubrzanje
 * ~0,5 m/s² 2-3 s, mirna vožnja, isto toliko kočenje; šum stajanja ~0,1 m/s². Na pravim snimcima detektor proverava
 * `PdrLocatorReplayTest` (uz PDR_REPLAY).
 */
class LiftRideTest {

    private val sampleNs = 10_000_000L
    private val gravity = 9.72

    /** Telefon: "gore" sveta u koordinatama telefona (treći red matrice rotacije). */
    private class Phone(val up: FloatArray) {
        val matrix = FloatArray(9).also { up.copyInto(it, 6) }
    }

    private val inHand = Phone(floatArrayOf(0f, 0f, 1f))

    /** U džepu naopako: gornja ivica telefona gleda dole. */
    private val upsideDown = Phone(floatArrayOf(0f, -1f, 0f))

    private class Sim(val detector: LiftRideDetector, val phone: Phone, val random: Random) {
        var t = 0L
        val rides = mutableListOf<LiftRide>()
    }

    private fun sim(phone: Phone = inHand) = Sim(LiftRideDetector(), phone, Random(7)).also { it.detector.onRotation(phone.matrix) }

    /** [seconds] uzoraka: vertikalno ubrzanje [vertical](s od početka dela) + šum [noise]; korak svakih [stepEveryS]. */
    private fun Sim.run(seconds: Double, noise: Double = 0.1, stepEveryS: Double? = null, vertical: (Double) -> Double = { 0.0 }) {
        val n = (seconds * 1e9 / sampleNs).toInt()
        var nextStep = stepEveryS
        for (i in 0 until n) {
            val s = i * sampleNs / 1e9
            val a = gravity + vertical(s) + random.nextDouble(-1.0, 1.0) * noise * 1.7
            detector.onAccelerometer(phone.up[0] * a.toFloat(), phone.up[1] * a.toFloat(), phone.up[2] * a.toFloat(), t)
                ?.let { rides += it }
            if (nextStep != null && s >= nextStep) {
                detector.onStep(t)
                nextStep += stepEveryS!!
            }
            t += sampleNs
        }
    }

    /** Vožnja: ubrzanje [accel] za [rampS], mirna vožnja [cruiseS], kočenje; pomeraj = accel·rampS·(rampS + cruiseS). */
    private fun Sim.ride(accel: Double, rampS: Double, cruiseS: Double) {
        run(rampS) { accel }
        run(cruiseS)
        run(rampS) { -accel }
    }

    @Test
    fun rideUpTwoFloors_detectedWithHeight() {
        val s = sim()
        s.run(10.0)
        s.ride(0.5, 3.0, 2.0)  // 0,5·3·5 = 7,5 m
        s.run(5.0)
        assertEquals(1, s.rides.size)
        val ride = s.rides.single()
        assertTrue(ride.up)
        assertEquals(7.5, ride.heightM, 0.6)
        assertEquals(10.0, ride.startNs / 1e9, 0.6)
        assertEquals(18.0, ride.endNs / 1e9, 0.6)
    }

    @Test
    fun rideDownOneFloor_pocketUpsideDown() {
        val s = sim(upsideDown)
        s.run(10.0)
        s.ride(-0.45, 2.5, 0.8)  // 0,45·2,5·3,3 ≈ 3,7 m
        s.run(5.0)
        val ride = s.rides.single()
        assertEquals(-3.7, ride.heightM, 0.5)
    }

    /** P -> V u NTP-u (08.10.2026): ubrzanje ~0,5 m/s² 3 s, mirna vožnja ~10 s, ~19 m. */
    @Test
    fun longRide_fiveFloors() {
        val s = sim()
        s.run(12.0)
        s.ride(0.5, 3.0, 10.0)  // 0,5·3·13 = 19,5 m
        s.run(5.0)
        assertEquals(19.5, s.rides.single().heightM, 1.2)
    }

    @Test
    fun standingStill_noRide() {
        val s = sim()
        s.run(120.0, noise = 0.2)
        assertEquals(emptyList<LiftRide>(), s.rides)
    }

    /** Hod: vertikalno ~2 m/s² u ritmu koraka (2 Hz), uz korake. */
    @Test
    fun walking_noRide() {
        val s = sim()
        s.run(10.0)
        s.run(60.0, noise = 0.5, stepEveryS = 0.5) { 2.0 * sin(2 * PI * 2 * it) }
        s.run(10.0)
        assertEquals(emptyList<LiftRide>(), s.rides)
    }

    /** Spor uspon (rampa, nagnut telefon...) bez kočenja nije vožnja; ni kočenje bez polaska. */
    @Test
    fun singlePulse_noRide() {
        val s = sim()
        s.run(10.0)
        s.run(3.0) { 0.5 }
        s.run(60.0)
        s.run(3.0) { 0.5 }
        s.run(10.0)
        assertEquals(emptyList<LiftRide>(), s.rides)
    }

    /** Polazak i kočenje, a između hod: nije vožnja (dva slučajna impulsa). */
    @Test
    fun walkingBetweenPulses_noRide() {
        val s = sim()
        s.run(10.0)
        s.run(3.0) { 0.5 }
        s.run(10.0, noise = 0.5, stepEveryS = 0.5) { 2.0 * sin(2 * PI * 2 * it) }
        s.run(3.0) { -0.5 }
        s.run(10.0)
        assertEquals(emptyList<LiftRide>(), s.rides)
    }

    /**
     * Teren 08.10.2026, P -> II u džepu: telefon stavljen u džep (jak šum i lažni koraci) do samog polaska - impuls
     * počinje odmah posle toga. Prva verzija prototipa je šum lepila za impuls i vožnju promašila.
     */
    @Test
    fun pocketJustBeforeRide_stillDetected() {
        val s = sim()
        s.run(10.0)
        s.run(4.0, noise = 2.5, stepEveryS = 0.4)
        s.ride(0.55, 3.0, 2.0)  // 8,25 m
        s.run(5.0)
        assertEquals(8.25, s.rides.single().heightM, 0.8)
    }

    /** Lift stao usput (08.10.2026, V -> IV -> III): dve vožnje. */
    @Test
    fun stopOnTheWay_twoRides() {
        val s = sim()
        s.run(10.0)
        s.ride(-0.45, 2.0, 1.5)
        s.run(20.0)
        s.ride(-0.45, 2.0, 1.5)
        s.run(5.0)
        assertEquals(2, s.rides.size)
        assertTrue(s.rides.all { !it.up && it.heightM in -4.2..-2.8 })
    }

    /** Detektor koraka ume da prijavi korak na trzaju lifta (08.10.2026, I -> P) - vožnja se i dalje prepoznaje. */
    @Test
    fun falseStepInRide_stillDetected() {
        val s = sim()
        s.run(10.0)
        s.run(2.5) { -0.5 }
        s.detector.onStep(s.t)
        s.run(1.0)
        s.run(2.5) { 0.5 }
        s.run(5.0)
        assertEquals(1, s.rides.size)
    }
}
