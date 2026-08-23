package com.landpoint.app.location

import android.hardware.GeomagneticField
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * Everything that knows where the phone is, joined into one live reading.
 *
 * The pieces exist separately and are each honest on their own: [LocationProvider]
 * gives what Android reported, [GnssSignal] gives what the satellites are doing,
 * [DeviceSensors] gives whether the phone is being carried and which way it points.
 * This is where they are combined into the single [TrackingState] a screen can show,
 * and it is the only place in the app that does so — so there is one answer to
 * "where are we", not one per screen.
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
     * Tracking state, for as long as it is collected.
     *
     * Emits on every fix, on every satellite report — with or without a position, so
     * a user waiting under a canopy can see why — and on a compass reading at most
     * [COMPASS_MIN_GAP_MS] apart, since the rotation vector arrives about fifteen
     * times a second and no one reads a number that fast.
     *
     * Emits nothing at all when there is no permission and no provider, rather than
     * failing: the screens already show the permission state themselves, and a
     * thrown `SecurityException` inside a `viewModelScope` would end the process. It
     * keeps asking, though — see [restartingOnEnd] — so granting the permission or
     * switching location on starts tracking without leaving the screen.
     */
    fun observe(): Flow<TrackingState> = channelFlow {
        val gate = MotionGate()
        val smoother = PositionSmoother()
        var state = TrackingState()
        var declination = 0.0
        var lastCompassAt = 0L

        suspend fun publish(next: TrackingState) {
            state = next
            send(next)
        }

        // What the sky looks like. Independent of having a position — a user standing
        // under a roof needs to see two satellites at 20 dB-Hz to understand why
        // there is no fix yet, and that report arrives whether or not one comes.
        launch {
            gnss.observe().restartingOnEnd(RETRY_GAP_MS).collect { snapshot ->
                publish(state.copy(satellites = snapshot, moving = gate.moving))
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
                val now = System.currentTimeMillis()
                if (now - lastCompassAt < COMPASS_MIN_GAP_MS) return@collect
                lastCompassAt = now
                publish(
                    state.copy(compassDeg = normalise(magnetic + declination), moving = gate.moving)
                )
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
            if (state.hasFix) return@launch
            publish(state.copy(fix = bootstrap.toTelemetry()))
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
                    provider.observeLocation(wanted.intervalMs, wanted.minDistanceM)
                        .restartingOnEnd(RETRY_GAP_MS)
                        .collect { fix ->
                            gate.onSpeed(fix.speed?.toDouble())
                            val accuracyM = fix.accuracy?.toDouble()
                            val moving = gate.moving
                            val (latitude, longitude) =
                                smoother.feed(fix.latitude, fix.longitude, accuracyM, moving)
                            declination = declinationAt(fix)
                            publish(
                                state.copy(
                                    fix = fix.toTelemetry(
                                        latitude = latitude,
                                        longitude = longitude
                                    ),
                                    moving = moving
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
     * Bearings everywhere else in LandPoint — exports, side lengths, the boundary
     * drawing — are true-north bearings, because that is what a land document means
     * by a direction. A compass reading is not, and the difference reaches several
     * degrees across Indonesia. Converting here keeps the distinction out of the UI,
     * which only ever sees one kind of north.
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
        // Qualified: `provider` alone would still resolve to this fix's own
        // provider name, but the tracker holds a LocationProvider by that name too,
        // and a reader should not have to work out which one wins.
        source = FixSource.of(this.provider)
    )

    /**
     * Subscribes again, [gapMs] later, to a stream that ended.
     *
     * The location and satellite streams end quietly instead of throwing when there
     * is nothing to listen to: permission refused, or every provider switched off.
     * Both are things the user can put right — in the permission dialog, or in the
     * system's own quick settings — while this screen stays open, and neither sends
     * any signal back to say so. Asking again on a slow loop is what makes tracking
     * recover on its own instead of only after the screen is left and reopened.
     *
     * A working stream never reaches the delay: `callbackFlow` with `awaitClose` runs
     * until its collector stops, so this costs nothing whenever tracking is live.
     */
    private fun <T> Flow<T>.restartingOnEnd(gapMs: Long): Flow<T> = flow {
        while (true) {
            emitAll(this@restartingOnEnd)
            delay(gapMs)
        }
    }

    private fun normalise(degrees: Double): Double = (degrees % FULL_TURN + FULL_TURN) % FULL_TURN

    private companion object {
        /** Fast enough for the panel to look live, slow enough for a person to read. */
        const val COMPASS_MIN_GAP_MS = 250L

        const val FULL_TURN = 360.0

        /**
         * How long to wait before asking a refused subscription again.
         *
         * Long enough that a permanent refusal is not a busy loop, short enough that
         * granting the permission looks immediate to the person who just granted it.
         */
        const val RETRY_GAP_MS = 5_000L

        /**
         * A short buffer, dropping the oldest reading when a collector falls behind.
         *
         * Tracking state is a current state, not a log: a screen that was slow to
         * recompose wants the newest position, and replaying a queue of stale ones
         * would walk the marker through where the user has already been.
         */
        const val CHANNEL_CAPACITY = 4
    }
}

/** How often, and after how far, to ask for the next fix. */
private data class Cadence(val intervalMs: Long, val minDistanceM: Float)
