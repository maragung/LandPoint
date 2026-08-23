package com.landpoint.app.location

import com.landpoint.app.util.GeoUtils
import kotlin.math.abs
import kotlin.math.max

/**
 * How good the satellite signal is, from what the receiver itself reports.
 *
 * Deliberately coarse. A phone can report carrier-to-noise density per satellite
 * to a tenth of a dB-Hz, and none of that helps someone standing in a field
 * deciding whether to wait another minute before marking a corner. Four steps
 * answer that question; a number does not.
 */
enum class SignalStrength { NONE, WEAK, FAIR, STRONG;

    companion object {
        /**
         * The strength implied by [satellitesUsed] satellites at [meanCn0DbHz].
         *
         * Both halves matter. Four satellites are the arithmetic minimum for a
         * three-dimensional fix, so fewer than that is weak however loudly they
         * come in; and a dozen satellites heard faintly through a canopy still
         * give a position that wanders.
         *
         * When the receiver reports no carrier-to-noise figures at all — some
         * chipsets do not — the count alone is used, and the verdict never rises
         * above [FAIR]. Claiming a strong signal on evidence that thin would be
         * the app inventing confidence the hardware never offered.
         */
        fun of(satellitesUsed: Int, meanCn0DbHz: Double?): SignalStrength = when {
            satellitesUsed <= 0 -> NONE
            satellitesUsed < MIN_SATELLITES_FOR_FIX -> WEAK
            meanCn0DbHz == null -> if (satellitesUsed >= GOOD_SATELLITE_COUNT) FAIR else WEAK
            meanCn0DbHz >= STRONG_CN0 && satellitesUsed >= GOOD_SATELLITE_COUNT -> STRONG
            meanCn0DbHz >= FAIR_CN0 -> FAIR
            else -> WEAK
        }

        /** Below this there is no three-dimensional fix to be had. */
        const val MIN_SATELLITES_FOR_FIX = 4

        /** Enough satellites for the geometry to stop being the limiting factor. */
        const val GOOD_SATELLITE_COUNT = 6

        /** Open-sky carrier-to-noise density, in dB-Hz. */
        const val STRONG_CN0 = 35.0

        /** Usable but attenuated — under trees, beside a wall, through a window. */
        const val FAIR_CN0 = 28.0
    }
}

/**
 * The satellites as the receiver last described them.
 *
 * Separate from the position because the two arrive on different clocks and for
 * different reasons: satellite status keeps updating once a second while the
 * position holds still, and a fix can be minutes old while the sky is being
 * reported live.
 */
data class GnssSnapshot(
    val visible: Int = 0,
    val used: Int = 0,
    val meanCn0DbHz: Double? = null,
    val timestamp: Long = 0L
) {
    val strength: SignalStrength get() = SignalStrength.of(used, meanCn0DbHz)

    /** True once anything at all has been heard from the sky. */
    val hasReport: Boolean get() = timestamp > 0L

    companion object {
        /**
         * A snapshot from the per-satellite figures.
         *
         * [cn0OfUsed] holds the carrier-to-noise density of only those satellites
         * actually contributing to the fix, because a mean taken over everything
         * visible is dominated by the ones at the horizon that the receiver has
         * already decided to ignore.
         */
        fun of(visible: Int, cn0OfUsed: List<Double>, timestamp: Long): GnssSnapshot {
            val usable = cn0OfUsed.filter { it.isFinite() && it > 0.0 }
            return GnssSnapshot(
                visible = visible,
                used = cn0OfUsed.size,
                meanCn0DbHz = usable.takeIf { it.isNotEmpty() }?.average(),
                timestamp = timestamp
            )
        }
    }
}

/**
 * Everything known about where the phone is, in one object, for one panel.
 *
 * Every figure here comes from Android and is passed on unchanged; nothing is
 * interpolated, and nothing is filled in when it is missing. That is the point of
 * the type: a telemetry panel that shows a plausible speed for a phone that never
 * reported one teaches the user to trust a number the hardware did not produce.
 *
 * @param moving what the sensors make of whether the phone is being carried. Used
 *   for the smoothing strength and the update interval, and shown because a user
 *   who is standing still and sees "moving" knows to stop trusting the heading.
 */
data class LocationTelemetry(
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Double?,
    val altitudeM: Double?,
    val speedMps: Double?,
    val bearingDeg: Double?,
    val timestamp: Long,
    val source: FixSource,
    val satellites: GnssSnapshot = GnssSnapshot(),
    val moving: Boolean = false
) {
    /** The accuracy-based verdict, or null when the fix carries no accuracy. */
    val quality: FixQuality? get() = accuracyM?.let { FixQuality.of(it) }
}

/**
 * How often to ask for a position, and how far the phone must move first.
 *
 * A location request is a radio duty cycle, and the honest way to spend less
 * battery is to ask for less rather than to ask for the same and throw half away.
 * The interval therefore follows what is happening: dense while the fix is still
 * converging or the user is walking a boundary, sparse once the fix is good and
 * the phone has been put down.
 */
object TrackingCadence {

    /** While there is no usable fix yet, or the fix is still poor. */
    const val URGENT_MS = 1_000L

    /** Walking a boundary: fast enough that a corner is not cut. */
    const val MOVING_MS = 2_000L

    /** Holding position with a fix that is not yet good. */
    const val SETTLING_MS = 4_000L

    /** Good fix, phone stationary. Nothing is changing; stop asking as often. */
    const val RESTING_MS = 8_000L

    /** Accuracy at or below this is as good as this app needs. */
    const val GOOD_ACCURACY_M = 10.0

    /** Above this the fix is not yet worth slowing down for. */
    const val POOR_ACCURACY_M = 25.0

    fun intervalMs(accuracyM: Double?, moving: Boolean): Long = when {
        accuracyM == null || accuracyM > POOR_ACCURACY_M -> URGENT_MS
        moving -> MOVING_MS
        accuracyM <= GOOD_ACCURACY_M -> RESTING_MS
        else -> SETTLING_MS
    }

    /**
     * The distance filter to pair with [intervalMs], in metres.
     *
     * Zero while converging: the first fixes barely move, and filtering them out
     * would leave the screen empty for as long as the user is standing still.
     * Once there is a good fix, a filter below the fix's own accuracy would only
     * be reporting noise, so the accuracy sets the floor.
     */
    fun minDistanceM(accuracyM: Double?, moving: Boolean): Float = when {
        accuracyM == null || accuracyM > POOR_ACCURACY_M -> 0f
        moving -> 1f
        else -> (accuracyM / 2.0).toFloat()
    }
}

/**
 * Whether the phone is being carried, from the accelerometer and the fix.
 *
 * The accelerometer is used for one thing only — telling "held still" from "being
 * walked with" — and never to work out where the phone has gone. Integrating
 * acceleration into a position drifts by tens of metres within a minute, and this
 * app prints its positions next to somebody's land.
 *
 * Two independent signals, either of which is enough: a reported speed above
 * walking pace, or acceleration that keeps varying by more than gravity's own
 * noise. The second matters because a phone in a pocket at 0.8 m/s often reports
 * no speed at all.
 */
class MotionGate {

    private var lastMagnitude = Double.NaN
    private var restlessness = 0.0

    /** True once the recent shaking is more than a hand holding a phone still. */
    var moving: Boolean = false
        private set

    /**
     * Feeds one accelerometer reading, in m/s² on the three axes.
     *
     * The magnitude is used rather than the axes, so the answer does not depend on
     * which way up the phone is held, and gravity subtracts out of the *change*
     * without needing to be estimated.
     */
    fun onAcceleration(x: Double, y: Double, z: Double) {
        val magnitude = kotlin.math.sqrt(x * x + y * y + z * z)
        if (lastMagnitude.isNaN()) {
            lastMagnitude = magnitude
            return
        }
        val jolt = abs(magnitude - lastMagnitude)
        lastMagnitude = magnitude
        // A leaky accumulator rather than a threshold on each reading: a single
        // knock against a table is not walking, and a low-pass filter here is the
        // difference between a steady indicator and one that flickers.
        restlessness = restlessness * DECAY + jolt * (1.0 - DECAY)
        moving = restlessness > RESTLESS_THRESHOLD
    }

    /**
     * Feeds what the fix says about speed, which settles it when it is there.
     *
     * A speed the receiver is confident about outranks the accelerometer in both
     * directions: it can only be produced by actual movement, and its absence at
     * a good accuracy is decent evidence of standing still.
     */
    fun onSpeed(speedMps: Double?) {
        val speed = speedMps?.takeIf { it.isFinite() && it >= 0.0 } ?: return
        if (speed >= WALKING_MPS) {
            moving = true
            restlessness = max(restlessness, RESTLESS_THRESHOLD * 2)
        }
    }

    private companion object {
        /** Roughly 3 km/h — slower than this is not a walk the map should follow. */
        const val WALKING_MPS = 0.8

        /** Per-reading weight of history. At ~50 Hz this smooths over a second. */
        const val DECAY = 0.9

        /**
         * Change in acceleration magnitude, in m/s², that counts as being carried.
         *
         * A phone resting on a surface reads well under 0.05; held still in a hand,
         * around 0.1; walked with, above 0.5 on every step.
         */
        const val RESTLESS_THRESHOLD = 0.25
    }
}

/**
 * Damps the wander of a stationary receiver without lagging a moving one.
 *
 * A phone left on a wall reports a position that drifts by several metres a
 * second, and drawing that raw makes the marker crawl around while the user
 * stands still. Averaging it away would then leave the marker trailing behind
 * someone who starts walking, which is worse — so the strength of the smoothing
 * follows [MotionGate]: heavy at rest, barely there in motion.
 *
 * Not a Kalman filter, and not pretending to be. A filter that models velocity
 * would need to be trusted enough to predict, and a prediction is precisely what
 * this app must not print. This only ever reports a weighted mean of positions
 * the receiver actually gave.
 */
class PositionSmoother {

    private var latitude = Double.NaN
    private var longitude = Double.NaN
    private var rejections = 0

    /** The last accepted output, or null before the first fix. */
    val current: Pair<Double, Double>?
        get() = if (latitude.isNaN()) null else latitude to longitude

    /** Forgets everything, for a new capture or after permission comes back. */
    fun reset() {
        latitude = Double.NaN
        longitude = Double.NaN
        rejections = 0
    }

    /**
     * Folds one fix in and returns the position to show.
     *
     * Returns the fix unchanged when it is the first, and when the phone is moving
     * fast enough that smoothing would show the user behind where they are.
     */
    fun feed(latitudeIn: Double, longitudeIn: Double, accuracyM: Double?, moving: Boolean): Pair<Double, Double> {
        if (latitude.isNaN()) {
            latitude = latitudeIn
            longitude = longitudeIn
            return latitude to longitude
        }

        val jump = GeoUtils.distance(latitude, longitude, latitudeIn, longitudeIn)
        val tolerated = max(OUTLIER_FLOOR_M, 3.0 * (accuracyM ?: OUTLIER_FLOOR_M))
        if (!moving && jump > tolerated && rejections < MAX_REJECTIONS) {
            // Further away than its own uncertainty can explain, from a phone that
            // is not being carried: almost always a reflected signal. Held back —
            // but only for a few readings, because a receiver that has genuinely
            // just corrected itself keeps reporting the new position, and refusing
            // it forever would pin the marker to a place the user has left.
            rejections++
            return latitude to longitude
        }
        rejections = 0

        val alpha = if (moving) MOVING_ALPHA else RESTING_ALPHA
        latitude += alpha * (latitudeIn - latitude)
        // Longitude differences are taken the short way round so a fix either side
        // of the antimeridian does not average to the far side of the planet.
        longitude = normaliseLongitude(longitude + alpha * shortestLongitudeDelta(longitudeIn - longitude))
        return latitude to longitude
    }

    private fun shortestLongitudeDelta(delta: Double): Double = when {
        delta > 180.0 -> delta - 360.0
        delta < -180.0 -> delta + 360.0
        else -> delta
    }

    private fun normaliseLongitude(value: Double): Double {
        var lon = value
        while (lon > 180.0) lon -= 360.0
        while (lon < -180.0) lon += 360.0
        return lon
    }

    private companion object {
        /** Follow the receiver closely; the user is ahead of any average. */
        const val MOVING_ALPHA = 0.6

        /** Standing still: most of what arrives is noise, so let little of it in. */
        const val RESTING_ALPHA = 0.15

        /** No jump smaller than this is ever treated as an outlier. */
        const val OUTLIER_FLOOR_M = 15.0

        /** After this many refusals in a row, the receiver is believed. */
        const val MAX_REJECTIONS = 3
    }
}
