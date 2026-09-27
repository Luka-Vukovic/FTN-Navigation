package com.example.ftnnavigation.poc

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * Kači PDR senzore dok je ekran u RESUMED stanju (u pozadini se odjavljuju):
 * - TYPE_ROTATION_VECTOR uvek, da se smer vidi i pre starta,
 * - TYPE_ACCELEROMETER + [AccelStepDetector] samo kad je [trackSteps] true.
 *
 * [onAzimuth] dobija azimut u stepenima (0..360, 0 = sever, u smeru kazaljke).
 */
@Composable
fun PdrSensorsEffect(
    trackSteps: Boolean,
    onAzimuth: (Float) -> Unit,
    onStep: () -> Unit,
) {
    val context = LocalContext.current
    val sensorManager = remember(context) { context.getSystemService(SensorManager::class.java) }
    val currentOnAzimuth by rememberUpdatedState(onAzimuth)
    val currentOnStep by rememberUpdatedState(onStep)

    LifecycleResumeEffect(sensorManager, trackSteps) {
        val rotationMatrix = FloatArray(9)
        val orientation = FloatArray(3)
        val stepDetector = AccelStepDetector { currentOnStep() }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR -> {
                        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                        SensorManager.getOrientation(rotationMatrix, orientation)
                        currentOnAzimuth(normalizeDeg(Math.toDegrees(orientation[0].toDouble()).toFloat()))
                    }
                    Sensor.TYPE_ACCELEROMETER -> stepDetector.onAccelerometer(
                        event.values[0], event.values[1], event.values[2], event.timestamp,
                    )
                }
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }

        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI)
        }
        if (trackSteps) {
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }

        onPauseOrDispose { sensorManager.unregisterListener(listener) }
    }
}

/** Svodi ugao na opseg 0..360. */
fun normalizeDeg(deg: Float): Float = (deg % 360f + 360f) % 360f
