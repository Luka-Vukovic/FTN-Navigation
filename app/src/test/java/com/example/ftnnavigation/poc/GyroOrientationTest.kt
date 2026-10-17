package com.example.ftnnavigation.poc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class GyroOrientationTest {

    /** Matrica uređaj -> svet: telefon leži ekranom nagore, gornja ivica ka azimutu [yawDeg], nagnut oko ose X za [pitchDeg]. */
    private fun matrix(yawDeg: Double, pitchDeg: Double = 0.0): FloatArray {
        val y = Math.toRadians(yawDeg)
        val p = Math.toRadians(pitchDeg)
        // Rz(-yaw) · Rx(pitch): azimut je u smeru kazaljke, okret oko Z suprotno.
        val rz = doubleArrayOf(cos(y), sin(y), 0.0, -sin(y), cos(y), 0.0, 0.0, 0.0, 1.0)
        val rx = doubleArrayOf(1.0, 0.0, 0.0, 0.0, cos(p), -sin(p), 0.0, sin(p), cos(p))
        return FloatArray(9) { k ->
            val i = k / 3
            val j = k % 3
            (0 until 3).sumOf { rz[i * 3 + it] * rx[it * 3 + j] }.toFloat()
        }
    }

    @Test
    fun seed_keepsAzimuth() {
        val g = GyroOrientation()
        g.seed(matrix(30.0))
        assertEquals(30f, g.azimuthDeg(), 0.01f)
    }

    @Test
    fun turnAroundVertical_followedByAzimuth() {
        val g = GyroOrientation()
        g.seed(matrix(0.0))
        // 90°/s oko ose Z uređaja (telefon leži) 1 s: pozitivan okret je suprotno od kazaljke -> azimut −90°.
        var t = 1_000_000_000L
        repeat(51) {
            g.onGyroscope(0f, 0f, Math.toRadians(90.0).toFloat(), t)
            t += 20_000_000L
        }
        assertEquals(-90f, g.azimuthDeg(), 1f)
    }

    @Test
    fun wrongTilt_pulledToGravity_azimuthKept() {
        val g = GyroOrientation()
        // Matrica kaže nagnut 25°, a telefon leži (akcelerometar meri gravitaciju duž Z uređaja).
        g.seed(matrix(40.0, pitchDeg = 25.0))
        val before = g.tiltErrorDeg(floatArrayOf(0f, 0f, 9.81f))
        repeat(250) { g.onAccelerometer(0f, 0f, 9.81f) }
        val after = g.tiltErrorDeg(floatArrayOf(0f, 0f, 9.81f))
        assertTrue("pre $before", before > 20)
        assertTrue("posle $after", after < 1)
        assertEquals(40f, g.azimuthDeg(), 2f)
    }

    @Test
    fun strongAcceleration_ignoredForTilt() {
        val g = GyroOrientation()
        g.seed(matrix(0.0))
        // Jačina 12,7 m/s² (udar u hodu) - ne liči na gravitaciju.
        repeat(100) { g.onAccelerometer(8f, 0f, 9.81f) }
        assertEquals(0.0, g.tiltErrorDeg(floatArrayOf(0f, 0f, 9.81f)), 0.01)
    }
}
