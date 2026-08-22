package com.landpoint.app.util

import com.landpoint.app.location.GeoSample
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * One corner of a boundary. Kept minimal — this is what gets serialised.
 *
 * [accuracyM] is the accuracy in metres of the GPS fix this corner was taken
 * from, or null for one that was typed in, tapped on the map or derived, and so
 * has no such figure to report. It rides on the corner rather than in a list
 * beside it because corners get inserted, deleted and reordered constantly, and
 * two parallel lists would drift apart on the first of those.
 */
data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Double? = null
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

    /**
     * The first pair of sides that cross each other, numbered from 1, or null if
     * the outline is simple.
     *
     * A ring that crosses itself is not a parcel, and the trouble is that
     * [areaSqm] does not say so: the signed accumulation quietly returns the
     * *difference* between the two lobes instead of the ground enclosed, and for
     * a boundary shaped like a bow tie that figure can be almost anything,
     * including nearly zero. Nothing downstream catches it either — the shape
     * exports, prints and closes neatly. So it has to be noticed here.
     *
     * Almost always the cause is one corner typed or tapped out of sequence,
     * which is why the sides are named: the fix is to move a corner earlier or
     * later, and the user has to know which one.
     *
     * Sides sharing a corner are skipped — they meet there by construction.
     */
    fun selfCrossing(points: List<GeoPoint>): Pair<Int, Int>? {
        val n = points.size
        if (n < 4) return null

        // Projected once, flat, in metres from the first corner. Over a parcel the
        // curvature dropped here is orders of magnitude under the GPS noise in the
        // corners themselves, and a crossing is a topological fact that survives
        // any projection this local.
        val metresPerDegree = EARTH_RADIUS_M * Math.PI / 180.0
        val metresPerDegreeLon = metresPerDegree * cos(Math.toRadians(points[0].latitude))
        val xs = DoubleArray(n) { (points[it].longitude - points[0].longitude) * metresPerDegreeLon }
        val ys = DoubleArray(n) { (points[it].latitude - points[0].latitude) * metresPerDegree }

        for (i in 0 until n) {
            val iNext = (i + 1) % n
            for (j in i + 1 until n) {
                val jNext = (j + 1) % n
                // Adjacent sides, and the last against the first, share an end.
                if (i == jNext || j == iNext) continue
                if (crosses(
                        xs[i], ys[i], xs[iNext], ys[iNext],
                        xs[j], ys[j], xs[jNext], ys[jNext]
                    )
                ) {
                    return (i + 1) to (j + 1)
                }
            }
        }
        return null
    }

    /**
     * Whether the two segments properly cross, by the sign of four orientation
     * tests. Strict, so segments that merely touch end to end or lie along each
     * other are not reported: those come from a duplicated corner, which the
     * editor refuses at entry, and reporting them here would put a warning on
     * boundaries that are fine.
     */
    private fun crosses(
        ax: Double, ay: Double, bx: Double, by: Double,
        cx: Double, cy: Double, dx: Double, dy: Double
    ): Boolean {
        val d1 = cross(cx, cy, dx, dy, ax, ay)
        val d2 = cross(cx, cy, dx, dy, bx, by)
        val d3 = cross(ax, ay, bx, by, cx, cy)
        val d4 = cross(ax, ay, bx, by, dx, dy)
        if (d1 == 0.0 || d2 == 0.0 || d3 == 0.0 || d4 == 0.0) return false
        return (d1 > 0.0) != (d2 > 0.0) && (d3 > 0.0) != (d4 > 0.0)
    }

    /** Which side of the line through (x1,y1)-(x2,y2) the point (px,py) falls. */
    private fun cross(
        x1: Double, y1: Double, x2: Double, y2: Double, px: Double, py: Double
    ): Double = (x2 - x1) * (py - y1) - (y2 - y1) * (px - x1)

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

    /** One side of a boundary, stated the way a survey letter states it. */
    data class Side(val toIndex: Int, val lengthM: Double, val bearingDeg: Double)

    /**
     * The side leaving corner [index], or null when that corner starts no side.
     *
     * A survey letter does not print coordinates; it prints, for each corner in
     * turn, a bearing and a length to the next one. Reading those back out is how
     * someone checks that what they typed is the parcel on the paper — a corner
     * whose digits are transposed is invisible as a coordinate and obvious as a
     * side of 214 metres between two pegs 21 metres apart.
     *
     * [toIndex] is returned rather than left to the caller to work out, because
     * which corner comes next is exactly the part that is not obvious: the last
     * one joins back to the first, and only once the outline has closed.
     */
    fun sideFrom(points: List<GeoPoint>, index: Int): Side? {
        if (index !in points.indices) return null
        val to = when {
            index < points.size - 1 -> index + 1
            // Two corners are a line, and its one side has already been reported
            // from the first of them. Closing it would name the same side twice
            // and read as a boundary with two.
            points.size >= 3 -> 0
            else -> return null
        }
        val from = points[index]
        val next = points[to]
        return Side(
            toIndex = to,
            lengthM = GeoUtils.distance(
                from.latitude, from.longitude, next.latitude, next.longitude
            ),
            bearingDeg = GeoUtils.bearing(
                from.latitude, from.longitude, next.latitude, next.longitude
            )
        )
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

    /**
     * How far the nearest corner of [points] lies from ([lat], [lon]) in metres,
     * or null when there is no corner to measure to.
     *
     * Nearest corner rather than centroid: what makes a saved land worth offering
     * as a neighbour is that one of its pegs is standing near this one, and a
     * long thin parcel can run right past a spot its centroid is a kilometre from.
     */
    fun nearestCornerM(points: List<GeoPoint>, lat: Double, lon: Double): Double? =
        points.minOfOrNull { GeoUtils.distance(lat, lon, it.latitude, it.longitude) }

    fun GeoSample.toGeoPoint() = GeoPoint(latitude, longitude, accuracy?.toDouble())
}
