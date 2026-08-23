package com.landpoint.app.map

import com.landpoint.app.util.GeoPoint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/** Metres per degree of latitude. Constant enough for an area estimate. */
private const val METRES_PER_DEGREE = 111_320.0

/**
 * A rectangle on the earth, in plain numbers.
 *
 * MapLibre has its own `LatLngBounds` and the app uses it at the edges, but not
 * here: area, tile counts and download budgets are arithmetic, and arithmetic that
 * imports an Android library can only be tested on a device. This type is what lets
 * the whole download-estimation path be a unit test.
 *
 * Does not cross the antimeridian. A boundary that did would be a plot spanning half
 * the planet, and the download picker cannot produce one.
 */
data class GeoBounds(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double
) {
    init {
        require(south <= north) { "south $south is above north $north" }
        require(west <= east) { "west $west is east of east $east" }
    }

    val centreLatitude: Double get() = (south + north) / 2
    val centreLongitude: Double get() = (west + east) / 2

    /**
     * Rough surface area in square kilometres.
     *
     * A flat-earth approximation with a cosine correction for the latitude, which is
     * accurate to a fraction of a percent over any rectangle small enough for
     * someone to want to download it — and this number only ever decides whether a
     * request is inside a limit, never what gets stored.
     */
    val areaSquareKm: Double
        get() {
            val heightMetres = (north - south) * METRES_PER_DEGREE
            val widthMetres =
                (east - west) * METRES_PER_DEGREE * cos(Math.toRadians(centreLatitude))
            return abs(heightMetres * widthMetres) / 1_000_000.0
        }

    /** True when the rectangle has no extent, which nothing can be drawn from. */
    val isDegenerate: Boolean get() = south == north || west == east

    companion object {

        /** The rectangle holding every point given, or null for fewer than two. */
        fun of(points: List<GeoPoint>): GeoBounds? {
            if (points.size < 2) return null
            var south = points.first().latitude
            var north = south
            var west = points.first().longitude
            var east = west
            for (point in points) {
                south = min(south, point.latitude)
                north = max(north, point.latitude)
                west = min(west, point.longitude)
                east = max(east, point.longitude)
            }
            val bounds = GeoBounds(south, west, north, east)
            return if (bounds.isDegenerate) null else bounds
        }
    }
}
