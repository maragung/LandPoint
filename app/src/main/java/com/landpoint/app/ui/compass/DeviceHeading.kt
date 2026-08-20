package com.landpoint.app.ui.compass

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Device heading in degrees from magnetic north, smoothed to stop the needle
 * jittering. Returns null while no compass hardware is present.
 */
@Composable
fun rememberDeviceHeading(): Float? {
    val context = LocalContext.current
    val sensorManager = remember {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }
    val hasCompass = remember {
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null ||
                (sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null &&
                        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null)
    }

    var heading by remember { mutableFloatStateOf(Float.NaN) }

    DisposableEffect(hasCompass) {
        if (!hasCompass) return@DisposableEffect onDispose {}

        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val rotationMatrix = FloatArray(9)
        val orientation = FloatArray(3)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientation)
                val degrees = (Math.toDegrees(orientation[0].toDouble()).toFloat() + 360f) % 360f

                heading = if (heading.isNaN()) {
                    degrees
                } else {
                    // Low-pass filter across the 0/360 wrap point.
                    var delta = degrees - heading
                    if (delta > 180f) delta -= 360f
                    if (delta < -180f) delta += 360f
                    (heading + delta * 0.15f + 360f) % 360f
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        if (rotationSensor != null) {
            sensorManager.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_UI)
        }

        onDispose { sensorManager.unregisterListener(listener) }
    }

    return heading.takeIf { !it.isNaN() }
}
