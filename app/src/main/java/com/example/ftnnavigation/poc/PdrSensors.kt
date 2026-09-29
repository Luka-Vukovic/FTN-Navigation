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
 * Kači PDR senzore dok je ekran u RESUMED stanju (u pozadini se odjavljuju):
 * - TYPE_ROTATION_VECTOR uvek, da se smer vidi i pre starta,
 * - TYPE_ACCELEROMETER + [AccelStepDetector] samo kad je [trackSteps] true.
 *
 * Oba senzora idu u [direction] (smer hoda, ne pravac telefona). [onHeading] dobija smer za
 * prikaz (azimut u stepenima, 0..360, 0 = sever, u smeru kazaljke), a [onStep] smer hoda koraka
 * (uz broj prethodnih koraka koje treba ponoviti). [recorder] (debug) snima sve događaje.
 */
@Composable
fun PdrSensorsEffect(
    trackSteps: Boolean,
    direction: WalkingDirection,
    onHeading: (Float) -> Unit,
    onStep: (WalkingDirection.WalkStep) -> Unit,
    recorder: SensorRecorder? = null,
) {
    val context = LocalContext.current
    val sensorManager = remember(context) { context.getSystemService(SensorManager::class.java) }
    val currentOnHeading by rememberUpdatedState(onHeading)
    val currentOnStep by rememberUpdatedState(onStep)
    val currentRecorder by rememberUpdatedState(recorder)

    LifecycleResumeEffect(sensorManager, direction, trackSteps) {
        val rotationMatrix = FloatArray(9)
        var lastHeading = Float.NaN
        var lastAccelNs = 0L
        val stepDetector = AccelStepDetector {
            direction.onStep(lastAccelNs)?.let { step ->
                currentRecorder?.step(lastAccelNs, step, direction)
                currentOnStep(step)
            }
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR -> {
                        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                        currentRecorder?.rotation(event.timestamp, rotationMatrix)
                        direction.onRotation(rotationMatrix, event.timestamp)
                        // Senzor javlja ~50 puta u sekundi - prikaz se osvežava tek na promenu od 1°.
                        val heading = direction.heading() ?: return
                        if (lastHeading.isNaN() || abs(angleDiffDeg(heading, lastHeading)) >= 1f) {
                            lastHeading = heading
                            currentOnHeading(heading)
                        }
                    }
                    Sensor.TYPE_ACCELEROMETER -> {
                        lastAccelNs = event.timestamp
                        currentRecorder?.accelerometer(event.timestamp, event.values[0], event.values[1], event.values[2])
                        direction.onAccelerometer(event.values[0], event.values[1], event.values[2], event.timestamp)
                        stepDetector.onAccelerometer(event.values[0], event.values[1], event.values[2], event.timestamp)
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }

        // GAME (~50 Hz) i za orijentaciju: ubrzanje se okreće u koordinate sveta uzorak po uzorak.
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME)
        }
        if (trackSteps) {
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }

        currentRecorder?.resume(SystemClock.elapsedRealtimeNanos())
        onPauseOrDispose {
            sensorManager.unregisterListener(listener)
            currentRecorder?.pause(SystemClock.elapsedRealtimeNanos())
        }
    }
}

/** Svodi ugao na opseg 0..360. */
fun normalizeDeg(deg: Float): Float = (deg % 360f + 360f) % 360f

/** Razlika uglova [a] - [b] svedena na -180..180. */
fun angleDiffDeg(a: Float, b: Float): Float = ((a - b) % 360f + 540f) % 360f - 180f
