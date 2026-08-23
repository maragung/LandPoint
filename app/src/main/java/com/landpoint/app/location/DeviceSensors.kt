package com.landpoint.app.location

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.view.Surface
import android.view.WindowManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** One accelerometer reading, in m/s² on the device's own three axes. */
data class Acceleration(val x: Double, val y: Double, val z: Double)

/**
 * The two things the phone's own sensors are allowed to say about a position.
 *
 * Deliberately narrow. An IMU can be integrated into a position, and doing so drifts
 * by tens of metres inside a minute — unusable for an app that prints coordinates
 * beside someone's land. So the accelerometer answers one question, "is this phone
 * being carried", and the rotation vector answers another, "which way is it
 * pointing". Neither ever moves the marker.
 *
 * Both streams end quietly when the hardware is absent, which is a real case: a
 * cheap tablet often has neither a magnetometer nor a rotation vector.
 */
class DeviceSensors(private val context: Context) {

    private val sensorManager: SensorManager?
        get() = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    /** True when there is an accelerometer to tell still from walking. */
    val hasMotionSensor: Boolean
        get() = sensorOrNull(Sensor.TYPE_ACCELEROMETER) != null

    /** True when the device can say which way it is facing. */
    val hasCompass: Boolean
        get() = sensorOrNull(Sensor.TYPE_ROTATION_VECTOR) != null

    /**
     * Accelerometer readings for as long as they are collected.
     *
     * At the UI rate rather than the slowest one available, because [MotionGate]'s
     * smoothing is tuned to about half a second of history and a slower feed would
     * stretch that to several seconds — long enough that the marker keeps being
     * smoothed as though stationary while the user is already walking. The sensor
     * itself is a low-power one, and this only runs while a screen that shows a
     * position is in the foreground.
     */
    fun observeMotion(): Flow<Acceleration> = callbackFlow {
        val manager = sensorManager
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (manager == null || sensor == null) {
            close()
            return@callbackFlow
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.values.size < 3) return
                trySend(
                    Acceleration(
                        x = event.values[0].toDouble(),
                        y = event.values[1].toDouble(),
                        z = event.values[2].toDouble()
                    )
                )
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        awaitClose { manager.unregisterListener(listener) }
    }

    /**
     * Heading in degrees clockwise from **magnetic** north, smoothed.
     *
     * Magnetic, not true: the correction between the two depends on where on Earth
     * the phone is, which this class does not know. [LocationTracker] applies it from
     * the fix, so the number shown to the user is true north — the same north as the
     * bearings elsewhere in the app.
     *
     * The rotation vector is used rather than accelerometer + magnetometer because
     * the platform has already fused those two, with the gyroscope where there is
     * one, and its answer is both steadier and correct while the phone is tilted.
     * Readings are remapped for the current display rotation, so turning the phone
     * on its side does not turn the reported heading with it.
     */
    fun observeHeading(): Flow<Double> = callbackFlow {
        val manager = sensorManager
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (manager == null || sensor == null) {
            close()
            return@callbackFlow
        }

        val rotationMatrix = FloatArray(MATRIX_SIZE)
        val remapped = FloatArray(MATRIX_SIZE)
        val orientation = FloatArray(3)
        val smoother = HeadingSmoother()

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                val axes = screenAxes(displayRotation())
                val matrix = if (
                    SensorManager.remapCoordinateSystem(rotationMatrix, axes.first, axes.second, remapped)
                ) {
                    remapped
                } else {
                    rotationMatrix
                }
                SensorManager.getOrientation(matrix, orientation)
                val degrees = Math.toDegrees(orientation[0].toDouble())
                trySend(smoother.feed(degrees))
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        awaitClose { manager.unregisterListener(listener) }
    }

    private fun sensorOrNull(type: Int): Sensor? =
        runCatching { sensorManager?.getDefaultSensor(type) }.getOrNull()

    /**
     * How far the screen is turned from its natural orientation.
     *
     * Asked of the display this context belongs to where there is one, and of the
     * default display otherwise — this class is usually built with an application
     * context, which on API 30 and up has no display of its own. A device that
     * answers neither is treated as unrotated, which is right for the phones that
     * cannot rotate at all and a bounded error anywhere else: a heading indicator
     * ninety degrees out, never a wrong coordinate.
     */
    private fun displayRotation(): Int = runCatching {
        val ownDisplay =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) context.display?.rotation else null
        ownDisplay ?: defaultDisplayRotation()
    }.getOrNull() ?: Surface.ROTATION_0

    /** The pre-API-30 route, and still the only one an application context has. */
    @Suppress("DEPRECATION")
    private fun defaultDisplayRotation(): Int? =
        context.getSystemService(WindowManager::class.java)?.defaultDisplay?.rotation

    private companion object {
        const val MATRIX_SIZE = 9

        /** Which device axes the rotated screen's own x and y now lie along. */
        fun screenAxes(rotation: Int): Pair<Int, Int> = when (rotation) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
    }
}

/**
 * A low-pass filter for an angle, taken the short way round the circle.
 *
 * A compass reading wanders by a few degrees while the phone is held still, and a
 * needle that follows it exactly is unreadable. Averaging the raw numbers instead
 * would be worse: 359° and 1° average to 180°, so a phone pointing north would
 * report south every time it crossed the wrap. The delta is therefore taken as the
 * shorter of the two ways round before it is damped.
 *
 * Kept out of [DeviceSensors] so it can be tested without a sensor.
 */
internal class HeadingSmoother(private val alpha: Double = SMOOTHING) {

    private var heading = Double.NaN

    /** The last value fed in, filtered, or null before the first reading. */
    val current: Double?
        get() = if (heading.isNaN()) null else heading

    /** Folds one reading in and returns the heading to show, in 0°–360°. */
    fun feed(degrees: Double): Double {
        if (!degrees.isFinite()) return if (heading.isNaN()) 0.0 else heading
        val reading = normalise(degrees)
        if (heading.isNaN()) {
            heading = reading
            return heading
        }
        var delta = reading - heading
        if (delta > HALF_TURN) delta -= FULL_TURN
        if (delta < -HALF_TURN) delta += FULL_TURN
        heading = normalise(heading + delta * alpha)
        return heading
    }

    private fun normalise(degrees: Double): Double = (degrees % FULL_TURN + FULL_TURN) % FULL_TURN

    private companion object {
        /**
         * Weight of each new reading.
         *
         * Matches the compass screen's own filter, so the two do not disagree about
         * which way the phone is pointing when both are on screen.
         */
        const val SMOOTHING = 0.15
        const val HALF_TURN = 180.0
        const val FULL_TURN = 360.0
    }
}
