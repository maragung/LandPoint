package com.landpoint.app.util

import java.util.Locale
import kotlin.math.*

object GeoUtils {

    private const val EARTH_RADIUS_M = 6371000.0

    /**
     * Haversine distance in meters.
     */
    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_M * c
    }

    /**
     * Initial bearing from (lat1,lon1) to (lat2,lon2) in degrees [0..360).
     */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(Math.toRadians(lat2))
        val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
                sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(dLon)
        val bearing = Math.toDegrees(atan2(y, x))
        return (bearing + 360.0) % 360.0
    }

    /**
     * The point [distanceM] metres from (lat1, lon1) along [bearingDeg].
     *
     * The other direction from [bearing] and [distance]: those read a boundary
     * that is already recorded as coordinates, this writes one down from how a
     * survey letter states it — each side as a bearing and a length from the
     * previous corner, with no coordinates printed at all.
     *
     * Great-circle, on the same sphere as [distance], so a corner placed here and
     * then measured back with those two comes out where it was put.
     */
    fun destination(lat1: Double, lon1: Double, bearingDeg: Double, distanceM: Double): GeoPoint {
        val angular = distanceM / EARTH_RADIUS_M
        val theta = Math.toRadians(bearingDeg)
        val phi1 = Math.toRadians(lat1)
        val lambda1 = Math.toRadians(lon1)
        // Coerced against the rounding that puts this a hair outside asin's
        // domain at the poles, where it would come back NaN.
        val sinPhi2 = (sin(phi1) * cos(angular) + cos(phi1) * sin(angular) * cos(theta))
            .coerceIn(-1.0, 1.0)
        val phi2 = asin(sinPhi2)
        val lambda2 = lambda1 + atan2(
            sin(theta) * sin(angular) * cos(phi1),
            cos(angular) - sin(phi1) * sinPhi2
        )
        return GeoPoint(
            latitude = Math.toDegrees(phi2),
            // Wrapped back into range, so a side crossing the antimeridian still
            // yields a longitude a map can place.
            longitude = (Math.toDegrees(lambda2) + 540.0) % 360.0 - 180.0
        )
    }

    /**
     * Cardinal direction (N, NE, E, SE, S, SW, W, NW) from a bearing in degrees.
     */
    fun bearingToCardinal(bearing: Double): String {
        val normalized = (bearing + 360.0) % 360.0
        return when {
            normalized < 22.5 -> "N"
            normalized < 67.5 -> "NE"
            normalized < 112.5 -> "E"
            normalized < 157.5 -> "SE"
            normalized < 202.5 -> "S"
            normalized < 247.5 -> "SW"
            normalized < 292.5 -> "W"
            normalized < 337.5 -> "NW"
            else -> "N"
        }
    }

    /**
     * Index into `R.array.cardinal_directions` (0 = N, 1 = NE … 7 = NW) so the
     * abbreviation can be translated. Kept in step with [bearingToCardinal].
     */
    fun cardinalIndex(bearing: Double): Int {
        val normalized = (bearing % 360.0 + 360.0) % 360.0
        return ((normalized + 22.5) / 45.0).toInt() % 8
    }

    /**
     * Format distance in metric (m / km) or imperial (ft / mi).
     */
    fun formatDistance(meters: Double, imperial: Boolean = false): String {
        // Distances are prose, so the reader's own decimal separator is right here
        // — unlike coordinates below, which must stay machine-readable.
        val locale = Locale.getDefault()
        return if (imperial) {
            val feet = meters * 3.28084
            if (feet < 1000) "${feet.roundToInt()} ft"
            else "%.1f mi".format(locale, feet / 5280.0)
        } else {
            if (meters < 1000) "${meters.roundToInt()} m"
            else "%.1f km".format(locale, meters / 1000.0)
        }
    }

    /**
     * A GPS accuracy figure, in whole metres.
     *
     * Rounded rather than shown to a decimal because it is an estimated radius,
     * not a measurement, and "±3.4 m" claims a precision the figure does not
     * have. Below a metre it prints as "<1" instead of rounding to a nought no
     * receiver would mean.
     */
    fun formatAccuracy(metres: Double): String =
        if (metres < 1.0) "<1" else metres.roundToInt().toString()

    /**
     * Format coordinates as decimal degrees (e.g. 37.7749, -122.4194)
     */
    fun formatDecimal(lat: Double, lon: Double): String =
        "%.6f, %.6f".format(Locale.US, lat, lon)

    /**
     * Format coordinates as DMS (degrees, minutes, seconds with N/S/E/W).
     */
    fun formatDMS(lat: Double, lon: Double): String {
        val latDMS = toDMS(abs(lat))
        val lonDMS = toDMS(abs(lon))
        val latDir = if (lat >= 0) "N" else "S"
        val lonDir = if (lon >= 0) "E" else "W"
        return "$latDMS $latDir, $lonDMS $lonDir"
    }

    private fun toDMS(decimal: Double): String {
        var deg = decimal.toInt()
        val minDecimal = (decimal - deg) * 60
        var min = minDecimal.toInt()
        // Round the seconds before carrying, not after: at 59.97" the %.1f below
        // would otherwise print 60.0", which is not a reading that exists.
        var sec = Math.round((minDecimal - min) * 600) / 10.0
        if (sec >= 60.0) {
            sec -= 60.0
            min++
        }
        if (min >= 60) {
            min -= 60
            deg++
        }
        return "%d°%d'%.1f\"".format(Locale.US, deg, min, sec)
    }

    /**
     * Generate geo:// URI for external navigation apps.
     */
    fun geoUri(lat: Double, lon: Double, label: String? = null): String {
        val encoded = label?.let { android.net.Uri.encode(it) }
        return if (encoded != null) "geo:$lat,$lon?q=$lat,$lon($encoded)"
        else "geo:$lat,$lon"
    }

    /**
     * Generate https://maps.google.com/ link for sharing.
     */
    fun googleMapsLink(lat: Double, lon: Double): String =
        "https://maps.google.com/?q=$lat,$lon"
}
