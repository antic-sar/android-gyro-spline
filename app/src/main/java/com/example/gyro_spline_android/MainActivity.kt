package com.example.gyro_spline_android

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.example.gyro_spline_android.ui.theme.GyroSplineAndroidTheme
import design.spline.runtime.SplineView
import design.spline.runtime.Vector3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private const val SPLINE_SCENE_URL =
    "https://build.spline.design/SCHshn90bJB7fyKG6c41/scene.splinecontent"
private const val TARGET_OBJECT_NAME = "Subject"
private const val LOG_TAG = "GyroSpline"
private const val SENSOR_INTERVAL_US = 16_666 // ~60Hz
private const val SENSOR_LPF_ALPHA = 0.10f
private const val BASELINE_ADAPT_FACTOR = 0.0035f
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
}

@Composable
private fun GyroSplineScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val sensorManager = remember(context) {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }
    val motionSensor = remember(sensorManager) {
        sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }
    val rotationUpdater = remember { RotationUpdater() }

    DisposableEffect(sensorManager, motionSensor, rotationUpdater) {
        var smoothedRotationX = 0f
        var smoothedRotationY = 0f
        var hasFiltered = false
        var filteredX = 0f
        var filteredY = 0f
        var filteredZ = 0f
        var hasBaseline = false
        var baselineX = 0f
        var baselineY = 0f

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
                } else {
                    baselineX += (x - baselineX) * BASELINE_ADAPT_FACTOR
                    baselineY += (y - baselineY) * BASELINE_ADAPT_FACTOR
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

        if (motionSensor != null) {
            sensorManager.registerListener(listener, motionSensor, SENSOR_INTERVAL_US)
        } else {
            Log.w(LOG_TAG, "No gravity/accelerometer sensor available on this device.")
        }

        onDispose {
            sensorManager.unregisterListener(listener)
            rotationUpdater.updateRotation = null
        }
    }

    AndroidView(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        factory = { viewContext ->
            SplineView(viewContext).apply {
                loadUrl(SPLINE_SCENE_URL) {
                    val subject = findObjectByName(TARGET_OBJECT_NAME)
                    if (subject == null) {
                        Log.w(
                            LOG_TAG,
                            "Object '$TARGET_OBJECT_NAME' was not found. Update TARGET_OBJECT_NAME."
                        )
                    }
                    rotationUpdater.updateRotation = { rotationX, rotationY ->
                        subject?.rotation?.let { currentRotation ->
                            subject.rotation = Vector3(rotationX, rotationY, currentRotation.z)
                        }
                    }
                }
            }
        }
    )
}

private fun clamp(value: Float, minValue: Float, maxValue: Float): Float {
    return max(minValue, min(value, maxValue))
}
