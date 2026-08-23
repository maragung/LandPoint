package com.landpoint.app.location

import android.hardware.GeomagneticField
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/**
 * One live reading of where the phone is, assembled from everything that knows.
 *
 * The pieces exist separately and are all honest on their own: [LocationProvider]
 * gives what Android reported, [GnssSignal] gives what the satellites are doing,
 * [DeviceSensors] gives whether the phone is being carried and which way it points.
 * This is where they are combined into the single object a screen can show, and it is
 * the only place in the app that does so — so there is one answer to "where are we",
 * not one per screen.
 *
 * Three properties are worth stating, because each is a decision rather than a
 * consequence:
 *
 * * **Nothing is invented.** Latitude and longitude are smoothed towards the
 *   readings the receiver gave and never predicted past them; accuracy, altitude,
 *   speed and bearing are passed through untouched. A field the fix did not carry
 *   stays null all the way to the screen.
 * * **The update rate follows the fix, not the clock.** A poor or moving fix is
 *   asked for often, a good and stationary one rarely — see [TrackingCadence]. The
 *   subscription is torn down and remade when the cadence changes, which is the only
 *   way `LocationManager` accepts a new interval.
 * * **It is a cold flow.** Radios and sensors start when someone collects and stop
 *   when they stop, so lifecycle-awareness comes from the collector — a
 *   `stateIn(WhileSubscribed)` in a ViewModel, or `collectAsStateWithLifecycle` on a
 *   screen — rather than from a copy of the lifecycle kept here.
 */
class LocationTracker(
    private val provider: LocationProvider,
    private val gnss: GnssSignal,
    private val sensors: DeviceSensors
) {

    /**
     * Telemetry, for as long as it is collected.
     *
     * Emits on every fix, on every satellite report once there is a fix to attach it
     * to, and on a compass reading at most [COMPASS_MIN_GAP_MS] apart — the rotation
     * vector arrives about fifteen times a second and no one reads a number that
     * fast.
     *
     * Emits nothing at all when there is no permission and no provider, rather than
     * failing: the screens already show the permission state themselves, and a
     * thrown `SecurityException` inside a `viewModelScope` would end the process.
     */
    fun observe(): Flow<LocationTelemetry> = channelFlow {
        val gate = MotionGate()
        val smoother = PositionSmoother()
        var satellites = GnssSnapshot()
        var compass: Double? = null
        var declination = 0.0
        var latest: LocationTelemetry? = null
        var lastCompassAt = 0L

        suspend fun publish(telemetry: LocationTelemetry) {
            latest = telemetry
            send(telemetry)
        }

        // What the sky looks like. Independent of having a position — a user standing
        // under a roof needs to see two satellites at 20 dB-Hz to understand why
        // there is no fix yet, and that report arrives whether or not one comes.
        launch {
            gnss.observe().collect { snapshot ->
                satellites = snapshot
                latest?.let { publish(it.copy(satellites = snapshot, moving = gate.moving)) }
            }
        }

        // Still or being carried. Feeds the gate only; the readings never reach a
        // coordinate.
        launch {
            sensors.observeMotion().collect { gate.onAcceleration(it.x, it.y, it.z) }
        }

        // Which way the phone points, corrected from magnetic to true north with the
        // declination for wherever the last fix was.
        launch {
            sensors.observeHeading().collect { magnetic ->
                val trueNorth = normalise(magnetic + declination)
                compass = trueNorth
                val current = latest ?: return@collect
                val now = System.currentTimeMillis()
                if (now - lastCompassAt < COMPASS_MIN_GAP_MS) return@collect
                lastCompassAt = now
                publish(current.copy(compassDeg = trueNorth, moving = gate.moving))
            }
        }

        // Somewhere to start from. The platform's last known position is usually
        // available immediately, which is the difference between a screen that shows
        // a location and one that shows a spinner for the first thirty seconds.
        //
        // Not fed to the smoother: a last-known fix can be from another town, and
        // seeding the filter with it would make the first real fix look like an
        // outlier and hold the marker back from where the user actually is.
        launch {
            val bootstrap = provider.getCurrentLocation() ?: return@launch
            if (latest != null) return@launch
            publish(bootstrap.toTelemetry(satellites = satellites, moving = gate.moving, compassDeg = compass))
        }

        // Live fixes, at an interval that follows how good they are.
        val cadence = MutableStateFlow(
            Cadence(TrackingCadence.URGENT_MS, TrackingCadence.minDistanceM(null, moving = false))
        )
        launch {
            var subscription: Job? = null
            cadence.collect { wanted ->
                // Cancelled from here rather than from inside the subscription: a
                // coroutine cannot wait for its own cancellation, and this collector
                // is a different one.
                subscription?.cancelAndJoin()
                subscription = launch {
                    provider.observeLocation(wanted.intervalMs, wanted.minDistanceM).collect { fix ->
                        gate.onSpeed(fix.speed?.toDouble())
                        val accuracyM = fix.accuracy?.toDouble()
                        val moving = gate.moving
                        val (latitude, longitude) =
                            smoother.feed(fix.latitude, fix.longitude, accuracyM, moving)
                        declination = declinationAt(fix)
                        publish(
                            fix.toTelemetry(
                                satellites = satellites,
                                moving = moving,
                                compassDeg = compass,
                                latitude = latitude,
                                longitude = longitude
                            )
                        )
                        cadence.value = Cadence(
                            intervalMs = TrackingCadence.intervalMs(accuracyM, moving),
                            minDistanceM = TrackingCadence.minDistanceM(accuracyM, moving)
                        )
                    }
                }
            }
        }
    }.buffer(CHANNEL_CAPACITY, BufferOverflow.DROP_OLDEST)

    /**
     * Degrees between magnetic north and true north where the fix was taken.
     *
     * Bearings everywhere else in LandPoint — exports, area calculations, the
     * boundary drawing — are true-north bearings, because that is what a land
     * document means by a direction. A compass reading is not, and the difference
     * reaches ten degrees in parts of Indonesia's east. Converting here keeps the
     * distinction out of the UI, which only ever sees one kind of north.
     */
    private fun declinationAt(fix: LocationData): Double = runCatching {
        GeomagneticField(
            fix.latitude.toFloat(),
            fix.longitude.toFloat(),
            (fix.altitude ?: 0.0).toFloat(),
            fix.timestamp.takeIf { it > 0L } ?: System.currentTimeMillis()
        ).declination.toDouble()
    }.getOrDefault(0.0)

    private fun LocationData.toTelemetry(
        satellites: GnssSnapshot,
        moving: Boolean,
        compassDeg: Double?,
        latitude: Double = this.latitude,
        longitude: Double = this.longitude
    ) = LocationTelemetry(
        latitude = latitude,
        longitude = longitude,
        accuracyM = accuracy?.toDouble(),
        altitudeM = altitude,
        speedMps = speed?.toDouble(),
        bearingDeg = bearing?.toDouble()?.let { normalise(it) },
        timestamp = timestamp,
        source = FixSource.of(provider),
        satellites = satellites,
        moving = moving,
        compassDeg = compassDeg
    )

    private fun normalise(degrees: Double): Double = (degrees % FULL_TURN + FULL_TURN) % FULL_TURN

    private companion object {
        /** Enough for the panel to look live, slow enough for a person to read. */
        const val COMPASS_MIN_GAP_MS = 250L

        const val FULL_TURN = 360.0

        /**
         * A short buffer, dropping the oldest reading when a collector falls behind.
         *
         * Telemetry is a current state, not a log: a screen that was slow to
         * recompose wants the newest position, and replaying a queue of stale ones
         * would walk the marker through where the user has already been.
         */
        const val CHANNEL_CAPACITY = 4
    }
}

/** How often, and after how far, to ask for the next fix. */
private data class Cadence(val intervalMs: Long, val minDistanceM: Float)

