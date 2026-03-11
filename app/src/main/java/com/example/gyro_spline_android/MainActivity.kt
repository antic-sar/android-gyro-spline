package com.example.gyro_spline_android

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.gyro_spline_android.ui.theme.GyroSplineAndroidTheme
import design.spline.runtime.SplineObject
import design.spline.runtime.SplineView
import design.spline.runtime.Vector3
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.delay

private const val SPLINE_SCENE_URL =
    "https://build.spline.design/SCHshn90bJB7fyKG6c41/scene.splinecontent"
private const val TARGET_OBJECT_NAME = "Subject"
private const val LOG_TAG = "GyroSpline"
private const val RECREATE_SPLINE_VIEW_ON_FOREGROUND = false
private const val SENSOR_INTERVAL_US = 16_666 // ~60Hz
private const val SENSOR_LPF_ALPHA = 0.10f
private const val BASELINE_ADAPT_FACTOR = 0.0035f
private const val BASELINE_WARMUP_SAMPLES = 18
private const val RESUME_SETTLE_MS = 900L
private const val SUBJECT_REBIND_INTERVAL_MS = 500L
private const val DEAD_ZONE = 0.0125f
private const val MAX_ROTATION_X = 24f
private const val MAX_ROTATION_Y = 16f
private const val ROTATION_SENSITIVITY_X = 28f
private const val ROTATION_SENSITIVITY_Y = 24f
private const val ROTATION_SMOOTHING_FACTOR = 0.065f

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GyroSplineAndroidTheme {
                GyroSplineScreen()
            }
        }
    }
}

private class RotationUpdater {
    var updateRotation: ((Float, Float) -> Unit)? = null
    var splineView: SplineView? = null
    var subject: SplineObject? = null
    var hasLoggedMissingObject = false
    var lastSubjectResolveMs = 0L
}

@Composable
private fun GyroSplineScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val sensorManager = remember(context) {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }
    val motionSensor = remember(sensorManager) {
        sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }
    val rotationUpdater = remember { RotationUpdater() }
    var hasStartedOnce by remember { mutableStateOf(false) }
    var isSceneLoading by remember { mutableStateOf(true) }
    var loadingStartedAtMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    DisposableEffect(sensorManager, motionSensor, rotationUpdater, lifecycleOwner) {
        var smoothedRotationX = 0f
        var smoothedRotationY = 0f
        var hasFiltered = false
        var filteredX = 0f
        var filteredY = 0f
        var filteredZ = 0f
        var hasBaseline = false
        var baselineX = 0f
        var baselineY = 0f
        var baselineSampleCount = 0
        var isListening = false
        var ignoreOutputUntilNs = 0L

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val type = event.sensor.type
                if (type != Sensor.TYPE_GRAVITY && type != Sensor.TYPE_ACCELEROMETER) return

                var x = event.values[0] / SensorManager.GRAVITY_EARTH
                var y = event.values[1] / SensorManager.GRAVITY_EARTH
                var z = event.values[2] / SensorManager.GRAVITY_EARTH

                if (!hasFiltered) {
                    filteredX = x
                    filteredY = y
                    filteredZ = z
                    hasFiltered = true
                }
                filteredX += (x - filteredX) * SENSOR_LPF_ALPHA
                filteredY += (y - filteredY) * SENSOR_LPF_ALPHA
                filteredZ += (z - filteredZ) * SENSOR_LPF_ALPHA

                val magnitude = sqrt(
                    (filteredX * filteredX + filteredY * filteredY + filteredZ * filteredZ).toDouble()
                ).toFloat()
                if (magnitude < 1e-4f) return

                x = filteredX / magnitude
                y = filteredY / magnitude

                if (!hasBaseline) {
                    baselineX = x
                    baselineY = y
                    hasBaseline = true
                    baselineSampleCount = 0
                    return
                }

                if (baselineSampleCount < BASELINE_WARMUP_SAMPLES) {
                    baselineX += (x - baselineX) * 0.25f
                    baselineY += (y - baselineY) * 0.25f
                    baselineSampleCount++
                    return
                }

                baselineX += (x - baselineX) * BASELINE_ADAPT_FACTOR
                baselineY += (y - baselineY) * BASELINE_ADAPT_FACTOR

                if (event.timestamp < ignoreOutputUntilNs) {
                    return
                }

                var deltaX = x - baselineX
                var deltaY = y - baselineY

                if (abs(deltaX) < DEAD_ZONE) deltaX = 0f
                if (abs(deltaY) < DEAD_ZONE) deltaY = 0f

                val targetX = clamp(
                    value = -deltaY * ROTATION_SENSITIVITY_X,
                    minValue = -MAX_ROTATION_X,
                    maxValue = MAX_ROTATION_X
                )
                val targetY = clamp(
                    value = deltaX * ROTATION_SENSITIVITY_Y,
                    minValue = -MAX_ROTATION_Y,
                    maxValue = MAX_ROTATION_Y
                )

                smoothedRotationX += (targetX - smoothedRotationX) * ROTATION_SMOOTHING_FACTOR
                smoothedRotationY += (targetY - smoothedRotationY) * ROTATION_SMOOTHING_FACTOR

                rotationUpdater.updateRotation?.invoke(
                    smoothedRotationX,
                    smoothedRotationY
                )
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        fun startMotionUpdates() {
            if (isListening) return
            if (motionSensor == null) {
                Log.w(LOG_TAG, "No gravity/accelerometer sensor available on this device.")
                return
            }

            // Recalibrate after app resume to avoid stale background sensor state.
            smoothedRotationX = 0f
            smoothedRotationY = 0f
            hasFiltered = false
            hasBaseline = false
            baselineSampleCount = 0
            rotationUpdater.updateRotation?.invoke(0f, 0f)
            ignoreOutputUntilNs =
                SystemClock.elapsedRealtimeNanos() + (RESUME_SETTLE_MS * 1_000_000L)
            sensorManager.registerListener(listener, motionSensor, SENSOR_INTERVAL_US)
            isListening = true
        }

        fun stopMotionUpdates() {
            if (!isListening) return
            sensorManager.unregisterListener(listener)
            isListening = false
        }

        val lifecycle = lifecycleOwner.lifecycle
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    if (RECREATE_SPLINE_VIEW_ON_FOREGROUND && hasStartedOnce) {
                        // Force fresh Spline runtime after returning from background.
                        rotationUpdater.updateRotation = null
                        rotationUpdater.splineView = null
                        rotationUpdater.subject = null
                        rotationUpdater.hasLoggedMissingObject = false
                        rotationUpdater.lastSubjectResolveMs = 0L
                        isSceneLoading = true
                        loadingStartedAtMs = SystemClock.elapsedRealtime()
                        Log.d(LOG_TAG, "Recreating SplineView for clean foreground resume.")
                    }
                    hasStartedOnce = true
                    rotationUpdater.subject = null
                    rotationUpdater.lastSubjectResolveMs = 0L
                    rotationUpdater.splineView?.play()
                    startMotionUpdates()
                }
                Lifecycle.Event.ON_STOP -> {
                    rotationUpdater.splineView?.stop()
                    stopMotionUpdates()
                }
                else -> Unit
            }
        }

        lifecycle.addObserver(lifecycleObserver)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            hasStartedOnce = true
            startMotionUpdates()
        }

        onDispose {
            lifecycle.removeObserver(lifecycleObserver)
            stopMotionUpdates()
            rotationUpdater.updateRotation = null
            rotationUpdater.splineView = null
            rotationUpdater.subject = null
            rotationUpdater.hasLoggedMissingObject = false
            rotationUpdater.lastSubjectResolveMs = 0L
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                SplineView(viewContext).apply {
                    rotationUpdater.splineView = this
                    rotationUpdater.subject = null
                    rotationUpdater.lastSubjectResolveMs = 0L
                    isSceneLoading = true
                    loadingStartedAtMs = SystemClock.elapsedRealtime()
                    loadScene(viewContext) {
                        isSceneLoading = false
                        rotationUpdater.subject = findObjectByName(TARGET_OBJECT_NAME)
                        rotationUpdater.updateRotation = fun(rotationX: Float, rotationY: Float) {
                            val liveView = rotationUpdater.splineView ?: return
                            var subject = rotationUpdater.subject
                            if (subject == null) {
                                val nowMs = SystemClock.elapsedRealtime()
                                if (nowMs - rotationUpdater.lastSubjectResolveMs >= SUBJECT_REBIND_INTERVAL_MS) {
                                    rotationUpdater.lastSubjectResolveMs = nowMs
                                    subject = liveView.findObjectByName(TARGET_OBJECT_NAME)
                                    rotationUpdater.subject = subject
                                }
                            }
                            if (subject == null) {
                                if (!rotationUpdater.hasLoggedMissingObject) {
                                    Log.w(
                                        LOG_TAG,
                                        "Object '$TARGET_OBJECT_NAME' was not found. Update TARGET_OBJECT_NAME."
                                    )
                                    rotationUpdater.hasLoggedMissingObject = true
                                }
                                return
                            }
                            rotationUpdater.hasLoggedMissingObject = false
                            subject.rotation.let { currentRotation ->
                                subject.rotation = Vector3(rotationX, rotationY, currentRotation.z)
                            }
                        }
                    }
                }
            }
        )

        if (isSceneLoading) {
            SceneLoadingOverlay(loadingStartedAtMs)
        }
    }
}

private fun clamp(value: Float, minValue: Float, maxValue: Float): Float {
    return max(minValue, min(value, maxValue))
}

@Composable
private fun SceneLoadingOverlay(loadingStartedAtMs: Long) {
    val elapsedMs by produceState(initialValue = 0L, key1 = loadingStartedAtMs) {
        while (true) {
            value = (SystemClock.elapsedRealtime() - loadingStartedAtMs).coerceAtLeast(0L)
            delay(80)
        }
    }

    val rawProgress = ((1f - exp((-elapsedMs.toFloat() / 1800f).toDouble())) * 0.92f).toFloat()
    val progress = rawProgress.coerceIn(0f, 0.92f)
    val phase = when {
        elapsedMs < 1200L -> "Warming up renderer"
        elapsedMs < 2800L -> "Loading scene assets"
        else -> "Calibrating motion sensors"
    }
    val dots = ".".repeat(((elapsedMs / 350L) % 4L).toInt())
    val seconds = elapsedMs / 1000.0

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xEE000000))
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(color = Color.White)
        Text(
            text = "$phase$dots",
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 18.dp, bottom = 12.dp)
        )
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        Text(
            text = String.format("Preparing 3D scene (%.1fs)", seconds),
            color = Color(0xFFB8B8B8),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp)
        )
    }
}

private fun SplineView.loadScene(context: Context, onLoaded: () -> Unit) {
    val localSceneResId = context.resources.getIdentifier("scene", "raw", context.packageName)
    if (localSceneResId != 0) {
        Log.d(LOG_TAG, "Loading local scene resource for faster startup.")
        loadResource(localSceneResId, onLoaded)
    } else {
        loadUrl(SPLINE_SCENE_URL, onLoaded)
    }
}
