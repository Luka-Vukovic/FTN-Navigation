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
 * (uz broj prethodnih koraka koje treba ponoviti), visinu vrha koraka ([AccelStepDetector.stepLengthFactor]) i vreme koraka -
 * samo koraci koje [HandlingGate] pusti (premeštanje telefona u mestu nije hod; zadržani koraci stižu kasnije, sa svojim vremenom). [lift] (ako je zadat) dobija iste uzorke i korake, a prepoznata
 * vožnja liftom ide u [onLiftRide]. [recorder] (debug) snima sve događaje. Događaji stižu na glavnoj niti.
 */
class PdrSensorSession(
    private val sensorManager: SensorManager,
    private val direction: WalkingDirection,
    private val trackSteps: Boolean,
    private val onHeading: (Float) -> Unit,
    private val onStep: (WalkingDirection.WalkStep, Float, Long) -> Unit,
    private val recorder: SensorRecorder? = null,
    private val lift: LiftRideDetector? = null,
    private val onLiftRide: (LiftRide) -> Unit = {},
    private val gait: StairGait? = null,
) {
    private val rotationMatrix = FloatArray(9)
    private var lastHeading = Float.NaN
    private var lastAccelNs = 0L
    private val gate = HandlingGate()

    // Senzori orijentacije: podrazumevani prvi (na test telefonu Xiaomi), pa ostali sa liste (AOSP fuzija) - za zamenu kad ućuti.
    private val rotationSensors: List<Sensor> = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR).let { default ->
        listOfNotNull(default) + sensorManager.getSensorList(Sensor.TYPE_ROTATION_VECTOR).filter { it != default }
    }
    private var rotationIndex = 0
    private var lastRotationNs = 0L
    private var lastWatchdogNs = 0L

    // Korak stiže kad prođe vrh (do 0,2 s posle prelaska praga), sa vremenom prelaska.
    private val stepDetector = AccelStepDetector { timeNs, peak ->
        lift?.onStep(timeNs)
        gait?.onStep(timeNs)
        direction.onStep(timeNs)?.let { step ->
            recorder?.step(timeNs, step, direction, peak)
            for (s in gate.onStep(HandlingGate.Step(timeNs, step, peak))) onStep(s.walk, s.peak, s.timeNs)
        }
    }

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR -> {
                    lastRotationNs = event.timestamp
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    recorder?.rotation(event.timestamp, rotationMatrix)
                    direction.onRotation(rotationMatrix, event.timestamp)
                    lift?.onRotation(rotationMatrix)
                    gait?.onRotation(rotationMatrix)
                    if (trackSteps) {
                        gate.onRotation(rotationMatrix, event.timestamp).takeIf { it > 0 }?.let {
                            recorder?.droppedSteps(event.timestamp, it)
                        }
                        // Slab polazak u ruci se pušta kasnije (posle čekanja ili na pauzi).
                        for (s in gate.takeReleased()) onStep(s.walk, s.peak, s.timeNs)
                    }
                    // Senzor javlja ~50 puta u sekundi - prikaz se osvežava tek na promenu od 1°.
                    val heading = direction.heading() ?: return
                    if (lastHeading.isNaN() || abs(angleDiffDeg(heading, lastHeading)) >= 1f) {
                        lastHeading = heading
                        onHeading(heading)
                    }
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    lastAccelNs = event.timestamp
                    checkRotation(event.timestamp)
                    recorder?.accelerometer(event.timestamp, event.values[0], event.values[1], event.values[2])
                    direction.onAccelerometer(event.values[0], event.values[1], event.values[2], event.timestamp)
                    // Pre detektora koraka - korak koji on javi vidi i ovaj uzorak.
                    gait?.onAccelerometer(event.values[0], event.values[1], event.values[2], event.timestamp)
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

    /**
     * Senzor orijentacije ćuti dok akcelerometar radi -> ponovo se prijavljuje, a ako ćuti i [ROTATION_SWITCH_NS], prelazi na
     * sledeći senzor sa liste. Teren 16.10.2026 (i od 09.10.): rotation vector je prestajao na 100-290 s (ekran ugašen ili
     * aplikacija u pozadini), a akcelerometar je radio - koraci su išli zamrznutim smerom (177 koraka napolju pravo). Kratke
     * praznine od ~3 s se poklapaju sa Google Play uslugama koje se prijavljuju na isti senzor (`dumpsys sensorservice`).
     */
    private fun checkRotation(nowNs: Long) {
        if (lastRotationNs == 0L || rotationSensors.isEmpty()) return
        val silentNs = nowNs - lastRotationNs
        if (silentNs < ROTATION_SILENT_NS || nowNs - lastWatchdogNs < ROTATION_RETRY_NS) return
        lastWatchdogNs = nowNs
        sensorManager.unregisterListener(listener, rotationSensors[rotationIndex])
        val switch = silentNs >= ROTATION_SWITCH_NS && rotationSensors.size > 1
        if (switch) rotationIndex = (rotationIndex + 1) % rotationSensors.size
        val sensor = rotationSensors[rotationIndex]
        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        recorder?.rotationWatchdog(nowNs, if (switch) "ZAMENA" else "PONOVO", sensor.name, silentNs)
    }

    fun start() {
        // GAME (~50 Hz) i za orijentaciju: ubrzanje se okreće u koordinate sveta uzorak po uzorak.
        rotationIndex = 0
        lastRotationNs = 0L
        rotationSensors.firstOrNull()?.let {
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
            PdrSensorSession(sensorManager, direction, trackSteps = false, onHeading = { currentOnHeading(it) }, onStep = { _, _, _ -> })
                .also { it.start() }
        } else {
            null
        }
        onPauseOrDispose { session?.stop() }
    }
}

/** Senzor orijentacije ćuti ovoliko (a akcelerometar radi) -> ponovna prijava ([PdrSensorSession] checkRotation). */
private const val ROTATION_SILENT_NS = 1_000_000_000L

/** Između dve ponovne prijave. */
private const val ROTATION_RETRY_NS = 2_000_000_000L

/** Ćuti i posle ponovne prijave -> drugi senzor sa liste (AOSP fuzija umesto Xiaomi-jevog). */
private const val ROTATION_SWITCH_NS = 3_000_000_000L

/** Svodi ugao na opseg 0..360. */
fun normalizeDeg(deg: Float): Float = (deg % 360f + 360f) % 360f

/** Razlika uglova [a] - [b] svedena na -180..180. */
fun angleDiffDeg(a: Float, b: Float): Float = ((a - b) % 360f + 540f) % 360f - 180f
