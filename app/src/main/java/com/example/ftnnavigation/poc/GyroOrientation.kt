package com.example.ftnnavigation.poc

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// Orijentacija iz žiroskopa dok senzor orijentacije ćuti (teren 17.10.2026). Xiaomi rotation vector na test telefonu
// prestaje da šalje ~3 min pošto aplikacija ode sa ekrana (ekran ugašen ili druga aplikacija) i vraća se tek kad se
// aplikacija vrati na ekran; ponovna prijava ne pomaže, a drugog rotation vector-a na listi nema (AOSP fuzija je skrivena).
// Akcelerometar za to vreme radi - žiroskop je na istom čipu (icm4x6xx). Od poslednje matrice senzora orijentacije
// integrali se ugaona brzina, a nagib se polako vraća ka gravitaciji iz akcelerometra; smer (azimut) se ne ispravlja -
// kalibrisan žiroskop greši nekoliko stepeni za par minuta, a zamrznut smer je bio pogrešan do 180°.

/** Nagib ka gravitaciji: deo razlike po uzorku akcelerometra (50 Hz -> vremenska konstanta ~1 s). */
private const val TILT_GAIN = 0.02

/** Uzorak akcelerometra se uzima za nagib samo ako je jačina blizu g (u hodu udari idu do nekoliko m/s²). */
private const val TILT_MAX_DEVIATION = 2.0

private const val GRAVITY = 9.81

/** Najduži korak integracije (praznina u uzorcima žiroskopa se ne integrali kao jedan veliki okret). */
private const val MAX_DT_S = 0.1

class GyroOrientation {

    /** Matrica uređaj -> svet (kao `SensorManager.getRotationMatrixFromVector`: svet = R · uređaj; redovi istok, sever, gore). */
    private val r = DoubleArray(9)
    private var lastGyroNs = 0L
    var isSeeded = false
        private set

    /** Polazna orijentacija (poslednja matrica senzora orijentacije). */
    fun seed(matrix: FloatArray) {
        for (i in 0 until 9) r[i] = matrix[i].toDouble()
        lastGyroNs = 0L
        isSeeded = true
    }

    /** Ugaona brzina u koordinatama uređaja (rad/s). */
    fun onGyroscope(wx: Float, wy: Float, wz: Float, timeNs: Long) {
        if (!isSeeded) return
        val last = lastGyroNs
        lastGyroNs = timeNs
        if (last == 0L) return
        val dt = ((timeNs - last) / 1e9).coerceIn(0.0, MAX_DT_S)
        val ax = wx * dt
        val ay = wy * dt
        val az = wz * dt
        val angle = sqrt(ax * ax + ay * ay + az * az)
        if (angle < 1e-12) return
        // R = R · Exp([ω dt]×): okret u koordinatama uređaja.
        val d = rotation(ax / angle, ay / angle, az / angle, angle)
        multiplyInto(r.copyOf(), d, r)
        orthonormalize()
    }

    /** Uzorak akcelerometra (uređaj): nagib se pomera ka gravitaciji. */
    fun onAccelerometer(x: Float, y: Float, z: Float) {
        if (!isSeeded) return
        val norm = sqrt((x * x + y * y + z * z).toDouble())
        if (abs(norm - GRAVITY) > TILT_MAX_DEVIATION) return
        // "Gore" po akcelerometru, u koordinatama sveta.
        val ux = (r[0] * x + r[1] * y + r[2] * z) / norm
        val uy = (r[3] * x + r[4] * y + r[5] * z) / norm
        val uz = (r[6] * x + r[7] * y + r[8] * z) / norm
        // Osa u × (0, 0, 1): horizontalna; okret oko nje pomera u ka pravom "gore" (azimut se ne dira).
        val kx = uy
        val ky = -ux
        val s = sqrt(kx * kx + ky * ky)
        if (s < 1e-9) return
        val angle = atan2(s, uz) * TILT_GAIN
        val c = rotation(kx / s, ky / s, 0.0, angle)
        multiplyInto(c, r.copyOf(), r)
        orthonormalize()
    }

    /** Trenutna matrica (uređaj -> svet), u [out]. */
    fun matrix(out: FloatArray) {
        for (i in 0 until 9) out[i] = r[i].toFloat()
    }

    /** Azimut ose Y uređaja (stepeni, 0 = sever) - za poređenje sa senzorom orijentacije kad se vrati. */
    fun azimuthDeg(): Float = Math.toDegrees(atan2(r[1], r[4])).toFloat()

    private fun rotation(x: Double, y: Double, z: Double, angle: Double): DoubleArray {
        val c = cos(angle)
        val s = sin(angle)
        val t = 1 - c
        return doubleArrayOf(
            t * x * x + c, t * x * y - s * z, t * x * z + s * y,
            t * x * y + s * z, t * y * y + c, t * y * z - s * x,
            t * x * z - s * y, t * y * z + s * x, t * z * z + c,
        )
    }

    private fun multiplyInto(a: DoubleArray, b: DoubleArray, out: DoubleArray) {
        for (i in 0 until 3) for (j in 0 until 3) {
            out[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j]
        }
    }

    /** Kolone (ose uređaja u svetu) ponovo ortonormirane - zaokruživanje se ne nagomilava. */
    private fun orthonormalize() {
        fun col(j: Int) = doubleArrayOf(r[j], r[3 + j], r[6 + j])
        fun setCol(j: Int, v: DoubleArray) { r[j] = v[0]; r[3 + j] = v[1]; r[6 + j] = v[2] }
        fun norm(v: DoubleArray): DoubleArray { val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); return doubleArrayOf(v[0] / n, v[1] / n, v[2] / n) }
        val x = norm(col(0))
        var y = col(1)
        val dot = x[0] * y[0] + x[1] * y[1] + x[2] * y[2]
        y = norm(doubleArrayOf(y[0] - dot * x[0], y[1] - dot * x[1], y[2] - dot * x[2]))
        val z = doubleArrayOf(x[1] * y[2] - x[2] * y[1], x[2] * y[0] - x[0] * y[2], x[0] * y[1] - x[1] * y[0])
        setCol(0, x); setCol(1, y); setCol(2, z)
    }

    /** Ugao (stepeni) između "gore" po matrici i [up] (vektor u koordinatama uređaja) - za testove. */
    internal fun tiltErrorDeg(up: FloatArray): Double {
        val n = sqrt((up[0] * up[0] + up[1] * up[1] + up[2] * up[2]).toDouble())
        val uz = (r[6] * up[0] + r[7] * up[1] + r[8] * up[2]) / n
        return Math.toDegrees(asin(sqrt((1 - uz * uz).coerceAtLeast(0.0))))
    }
}
