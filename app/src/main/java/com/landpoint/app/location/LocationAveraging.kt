package com.landpoint.app.location

import com.landpoint.app.util.GeoUtils
import kotlin.math.sqrt

/** Which radio produced a sample. NETWORK is the cell-tower (BTS) / Wi-Fi fix. */
enum class FixSource { GPS, NETWORK, OTHER }

/** One raw reading, before averaging. */
data class GeoSample(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double? = null,
    val accuracy: Float? = null,
    val source: FixSource = FixSource.OTHER,
    val timestamp: Long = 0L
)

enum class FixQuality {
    EXCELLENT, GOOD, FAIR, POOR;

    companion object {
        fun of(accuracyMeters: Double): FixQuality = when {
            accuracyMeters <= 5.0 -> EXCELLENT
            accuracyMeters <= 10.0 -> GOOD
            accuracyMeters <= 30.0 -> FAIR
            else -> POOR
        }
    }
}

/**
 * The combined answer. [accuracy] is deliberately pessimistic — see
 * [LocationAveraging.average] for why the textbook formula is not used as-is.
 */
data class AveragedFix(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    val accuracy: Double,
    val used: Int,
    val rejected: Int,
    val spread: Double,
    val sources: Set<FixSource>
) {
    val quality: FixQuality get() = FixQuality.of(accuracy)
}

/**
 * Combines several location samples into one better fix.
 *
 * Three ideas do the work:
 *
 *  1. **Every provider at once.** GPS and NETWORK (cell tower / Wi-Fi) are read
 *     in parallel. Indoors or under canopy the network fix may be the only one
 *     there is; outdoors it is a cheap sanity check on GPS.
 *  2. **Outlier rejection before averaging.** A single wild sample — the first
 *     GPS fix of a cold start, or a cell fix pinned to a tower a kilometre away
 *     — would drag a plain mean off the mark. Samples are measured against the
 *     *median* point and anything far outside the cluster is dropped.
 *  3. **Inverse-variance weighting.** A sample's influence is 1/sigma^2, so a
 *     5 m GPS fix outweighs a 500 m tower fix by 10,000 to 1. No hand-tuned
 *     provider priority is needed; the reported accuracies decide.
 */
object LocationAveraging {

    /** Stop early once the combined accuracy is this good. */
    const val TARGET_ACCURACY_M = 8.0

    /** Never report a fix from fewer than this many samples if more are coming. */
    const val MIN_SAMPLES = 5

    /** Hard cap so a long capture cannot grow without bound. */
    const val MAX_SAMPLES = 40

    /** Longest a capture may run before we take what we have. */
    const val WINDOW_MS = 20_000L

    /** Samples inside this radius of each other are never treated as outliers. */
    private const val REJECT_FLOOR_M = 10.0

    /**
     * Trustworthy 1-sigma for a sample, in metres.
     *
     * Android radios under-report: a network fix that claims 12 m is really a
     * tower or Wi-Fi centroid, so each source gets a floor. Samples with no
     * accuracy at all get a deliberately bad fallback rather than being dropped,
     * which lets them still break a tie without moving a good fix.
     */
    fun sigmaOf(sample: GeoSample): Double {
        val reported = sample.accuracy
            ?.takeIf { it > 0f && it.isFinite() }
            ?.toDouble()
        val floor = when (sample.source) {
            FixSource.GPS -> 3.0
            FixSource.NETWORK -> 30.0
            FixSource.OTHER -> 10.0
        }
        val fallback = when (sample.source) {
            FixSource.GPS -> 30.0
            FixSource.NETWORK -> 500.0
            FixSource.OTHER -> 200.0
        }
        return maxOf(reported ?: fallback, floor)
    }
    /**
     * Combines [samples] into a single fix, or null if the list is empty.
     *
     * Outliers are measured against the component-wise median rather than the
     * mean, so one bad sample cannot move the thing it is being judged against.
     * A sample is dropped when it sits further from the median than its own
     * uncertainty can explain — which keeps a legitimately vague 500 m tower
     * fix while discarding a 5 m GPS fix that landed 200 m away.
     */
    fun average(samples: List<GeoSample>): AveragedFix? {
        if (samples.isEmpty()) return null
        if (samples.size == 1) {
            val only = samples[0]
            val sigma = sigmaOf(only)
            return AveragedFix(
                latitude = only.latitude,
                longitude = only.longitude,
                altitude = only.altitude,
                accuracy = sigma,
                used = 1,
                rejected = 0,
                spread = 0.0,
                sources = setOf(only.source)
            )
        }

        val medianLat = median(samples.map { it.latitude })
        // A plain median of longitudes straddling ±180° averages to 0 — the
        // Atlantic. Taking the median of offsets from one sample keeps the
        // reference inside the cluster wherever the cluster happens to sit.
        val reference = samples.first().longitude
        val medianLon = normaliseLongitude(
            reference + median(samples.map { wrapLongitude(it.longitude - reference) })
        )

        val distances = samples.map {
            GeoUtils.distance(medianLat, medianLon, it.latitude, it.longitude)
        }
        val typical = median(distances)
        val cluster = maxOf(REJECT_FLOOR_M, 2.5 * typical)

        val kept = samples.filterIndexed { i, sample ->
            distances[i] <= cluster + 2.0 * sigmaOf(sample)
        }.ifEmpty { samples }

        // Offsets from the median keep the arithmetic well-conditioned and make
        // the antimeridian a non-event: only differences are ever summed.
        var sumWeight = 0.0
        var sumLat = 0.0
        var sumLon = 0.0
        var sumAlt = 0.0
        var altWeight = 0.0
        var bestSigma = Double.MAX_VALUE

        kept.forEach { sample ->
            val sigma = sigmaOf(sample)
            val weight = 1.0 / (sigma * sigma)
            sumWeight += weight
            sumLat += weight * (sample.latitude - medianLat)
            sumLon += weight * (wrapLongitude(sample.longitude - medianLon))
            sample.altitude?.let {
                sumAlt += weight * it
                altWeight += weight
            }
            if (sigma < bestSigma) bestSigma = sigma
        }

        val lat = medianLat + sumLat / sumWeight
        val lon = normaliseLongitude(medianLon + sumLon / sumWeight)

        // Weighted RMS scatter of the surviving samples around the answer. If the
        // readings disagree more than the maths predicts, the readings win.
        var sumSquared = 0.0
        kept.forEach { sample ->
            val sigma = sigmaOf(sample)
            val weight = 1.0 / (sigma * sigma)
            val d = GeoUtils.distance(lat, lon, sample.latitude, sample.longitude)
            sumSquared += weight * d * d
        }
        val spread = sqrt(sumSquared / sumWeight)

        // The textbook 1/sqrt(sum of weights) assumes independent errors. GPS
        // errors are anything but — multipath and atmospheric bias persist for
        // minutes, so N samples are worth far less than N. Never claim better
        // than half the best single sample, and never better than the observed
        // scatter. Under-promising here is the whole point: this number ends up
        // printed on a PDF next to someone's land.
        val theoretical = 1.0 / sqrt(sumWeight)
        val accuracy = maxOf(theoretical, bestSigma / 2.0, spread)

        return AveragedFix(
            latitude = lat,
            longitude = lon,
            altitude = if (altWeight > 0.0) sumAlt / altWeight else null,
            accuracy = accuracy,
            used = kept.size,
            rejected = samples.size - kept.size,
            spread = spread,
            sources = kept.map { it.source }.toSet()
        )
    }

    /**
     * Whether collecting can stop: enough samples, good enough, or out of time.
     */
    fun shouldStop(samples: List<GeoSample>, elapsedMs: Long): Boolean {
        if (samples.size >= MAX_SAMPLES) return true
        if (elapsedMs >= WINDOW_MS) return true
        if (samples.size < MIN_SAMPLES) return false
        val fix = average(samples) ?: return false
        return fix.accuracy <= TARGET_ACCURACY_M
    }

    internal fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid]
        else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    /** Shortest signed longitude difference, so ±180° is not a 360° jump. */
    private fun wrapLongitude(delta: Double): Double = when {
        delta > 180.0 -> delta - 360.0
        delta < -180.0 -> delta + 360.0
        else -> delta
    }

    private fun normaliseLongitude(lon: Double): Double {
        var value = lon
        while (value > 180.0) value -= 360.0
        while (value < -180.0) value += 360.0
        return value
    }
}
