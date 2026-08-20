package com.landpoint.app.util

import com.landpoint.app.location.GeoSample
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** One corner of a boundary. Kept minimal — this is what gets serialised. */
data class GeoPoint(
    val latitude: Double,
    val longitude: Double
)

/**
 * Geodesic area and perimeter for a boundary walked on the ground.
 *
 * Area uses the spherical-excess method (the same approach as Google Maps'
 * `SphericalUtil.computeArea`) rather than projecting to a flat plane. For a
 * small field the difference is negligible, but the formula costs nothing extra
 * and does not quietly degrade for a large estate or at high latitude.
 */
object PolygonMath {

    private const val EARTH_RADIUS_M = 6371000.0

    /**
     * Signed-area accumulation over each edge, taken absolute at the end, so
     * winding order does not matter and the caller need not normalise it.
     */
    fun areaSqm(points: List<GeoPoint>): Double {
        if (points.size < 3) return 0.0

        var total = 0.0
        val last = points.last()
        var prevTanLat = tan((Math.PI / 2 - Math.toRadians(last.latitude)) / 2)
        var prevLon = Math.toRadians(last.longitude)

        points.forEach { point ->
            val tanLat = tan((Math.PI / 2 - Math.toRadians(point.latitude)) / 2)
            val lon = Math.toRadians(point.longitude)
            total += polarTriangleArea(tanLat, lon, prevTanLat, prevLon)
            prevTanLat = tanLat
            prevLon = lon
        }

        return abs(total * EARTH_RADIUS_M * EARTH_RADIUS_M)
    }

    private fun polarTriangleArea(
        tan1: Double,
        lon1: Double,
        tan2: Double,
        lon2: Double
    ): Double {
        val deltaLon = lon1 - lon2
        val t = tan1 * tan2
        return 2.0 * atan2(t * sin(deltaLon), 1 + t * cos(deltaLon))
    }

    /** Total length of the closed boundary, including the edge back to the start. */
    fun perimeterM(points: List<GeoPoint>): Double {
        if (points.size < 2) return 0.0
        var total = 0.0
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            total += GeoUtils.distance(a.latitude, a.longitude, b.latitude, b.longitude)
        }
        return total
    }

    /** Length of the open path as walked so far — what to show mid-capture. */
    fun pathLengthM(points: List<GeoPoint>): Double {
        if (points.size < 2) return 0.0
        var total = 0.0
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            total += GeoUtils.distance(a.latitude, a.longitude, b.latitude, b.longitude)
        }
        return total
    }

    /**
     * Representative point for the boundary, used as the land's stored
     * latitude/longitude so a polygon still has one pin on the map and in
     * exports. This is the centroid of the vertices, which for a walked field
     * sits inside the shape; a true area centroid is not worth the extra
     * machinery here.
     */
    fun centroid(points: List<GeoPoint>): GeoPoint? {
        if (points.isEmpty()) return null
        if (points.size == 1) return points.first()

        // Averaged in 3-D so a boundary spanning the date line does not average
        // to the opposite side of the planet.
        var x = 0.0
        var y = 0.0
        var z = 0.0
        points.forEach {
            val lat = Math.toRadians(it.latitude)
            val lon = Math.toRadians(it.longitude)
            x += cos(lat) * cos(lon)
            y += cos(lat) * sin(lon)
            z += sin(lat)
        }
        val n = points.size
        x /= n; y /= n; z /= n
        val hyp = kotlin.math.sqrt(x * x + y * y)
        return GeoPoint(
            latitude = Math.toDegrees(atan2(z, hyp)),
            longitude = Math.toDegrees(atan2(y, x))
        )
    }

    /**
     * True when [candidate] is far enough from the previous vertex to be worth
     * recording. Walking a boundary produces a lot of near-duplicate points; a
     * threshold tied to the fix accuracy keeps the shape honest instead of
     * recording GPS jitter as corners.
     */
    fun isMeaningfulStep(
        previous: GeoPoint?,
        candidate: GeoPoint,
        accuracyM: Double
    ): Boolean {
        if (previous == null) return true
        val moved = GeoUtils.distance(
            previous.latitude, previous.longitude,
            candidate.latitude, candidate.longitude
        )
        return moved >= maxOf(2.0, accuracyM)
    }

    fun GeoSample.toGeoPoint() = GeoPoint(latitude, longitude)
}
