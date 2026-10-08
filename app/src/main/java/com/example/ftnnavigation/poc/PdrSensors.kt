package com.example.ftnnavigation.poc

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlin.math.abs

/**
 * PDR senzori od [start] do [stop]:
 * - TYPE_ROTATION_VECTOR uvek (smer),
 * - TYPE_ACCELEROMETER + [AccelStepDetector] samo kad je [trackSteps] true.
 *
 * Oba senzora idu u [direction] (smer hoda, ne pravac telefona). [onHeading] dobija smer za
 * prikaz (azimut u stepenima, 0..360, 0 = sever, u smeru kazaljke), a [onStep] smer hoda koraka
 * (uz broj prethodnih koraka koje treba ponoviti). [lift] (ako je zadat) dobija iste uzorke i korake, a prepoznata
 * vožnja liftom ide u [onLiftRide]. [recorder] (debug) snima sve događaje. Događaji stižu na glavnoj niti.
 */
class PdrSensorSession(
    private val sensorManager: SensorManager,
    private val direction: WalkingDirection,
    private val trackSteps: Boolean,
    private val onHeading: (Float) -> Unit,
    private val onStep: (WalkingDirection.WalkStep) -> Unit,
    private val recorder: SensorRecorder? = null,
    private val lift: LiftRideDetector? = null,
    private val onLiftRide: (LiftRide) -> Unit = {},
) {
    private val rotationMatrix = FloatArray(9)
    private var lastHeading = Float.NaN
    private var lastAccelNs = 0L

    private val stepDetector = AccelStepDetector {
        lift?.onStep(lastAccelNs)
        direction.onStep(lastAccelNs)?.let { step ->
            recorder?.step(lastAccelNs, step, direction)
            onStep(step)
        }
    }

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR -> {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    recorder?.rotation(event.timestamp, rotationMatrix)
                    direction.onRotation(rotationMatrix, event.timestamp)
                    lift?.onRotation(rotationMatrix)
                    // Senzor javlja ~50 puta u sekundi - prikaz se osvežava tek na promenu od 1°.
                    val heading = direction.heading() ?: return
                    if (lastHeading.isNaN() || abs(angleDiffDeg(heading, lastHeading)) >= 1f) {
                        lastHeading = heading
                        onHeading(heading)
                    }
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    lastAccelNs = event.timestamp
                    recorder?.accelerometer(event.timestamp, event.values[0], event.values[1], event.values[2])
                    direction.onAccelerometer(event.values[0], event.values[1], event.values[2], event.timestamp)
                    stepDetector.onAccelerometer(event.values[0], event.values[1], event.values[2], event.timestamp)
                    lift?.onAccelerometer(event.values[0], event.values[1], event.values[2], event.timestamp)?.let { ride ->
                        recorder?.liftRide(event.timestamp, ride)
                        onLiftRide(ride)
                    }
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    fun start() {
        // GAME (~50 Hz) i za orijentaciju: ubrzanje se okreće u koordinate sveta uzorak po uzorak.
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME)
        }
        if (trackSteps) {
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }
        recorder?.resume(SystemClock.elapsedRealtimeNanos())
    }

    fun stop() {
        sensorManager.unregisterListener(listener)
        recorder?.pause(SystemClock.elapsedRealtimeNanos())
    }
}

/**
 * Smer za prikaz dok praćenje ne radi (samo TYPE_ROTATION_VECTOR, dok je ekran u RESUMED stanju).
 * Za vreme praćenja senzore drži [PocViewModel] ([PdrSensorSession]) nezavisno od ekrana, pa je
 * [enabled] tada false - inače bi [direction] dobijao svaku orijentaciju dvaput.
 */
@Composable
fun PdrHeadingEffect(
    enabled: Boolean,
    direction: WalkingDirection,
    onHeading: (Float) -> Unit,
) {
    val context = LocalContext.current
    val sensorManager = remember(context) { context.getSystemService(SensorManager::class.java) }
    val currentOnHeading by rememberUpdatedState(onHeading)

    LifecycleResumeEffect(sensorManager, direction, enabled) {
        val session = if (enabled) {
            PdrSensorSession(sensorManager, direction, trackSteps = false, onHeading = { currentOnHeading(it) }, onStep = {})
                .also { it.start() }
        } else {
            null
        }
        onPauseOrDispose { session?.stop() }
    }
}

/** Svodi ugao na opseg 0..360. */
fun normalizeDeg(deg: Float): Float = (deg % 360f + 360f) % 360f

/** Razlika uglova [a] - [b] svedena na -180..180. */
fun angleDiffDeg(a: Float, b: Float): Float = ((a - b) % 360f + 540f) % 360f - 180f
