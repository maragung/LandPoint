package com.landpoint.app.util

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Edits to a boundary that is already drawn, as opposed to one being captured.
 *
 * Capture has its own rules — [PolygonMath.isMeaningfulStep] for GPS and
 * [CornerDraft.acceptTap] for the map — and both are about deciding whether a
 * *new* reading is worth keeping. What is here is the other half: rearranging,
 * correcting and deleting corners that were recorded earlier, plus reading a
 * corner typed in by hand off a certificate or a surveyor's sheet.
 *
 * Every operation returns a new list and every one is index-safe: an index the
 * list does not have gives the list back unchanged, rather than throwing. The
 * callers are button handlers on a list that a walk recording can rewrite
 * underneath them, so an out-of-range index is a race to absorb, not a bug to
 * crash on.
 */
object BoundaryEdits {

    fun removeAt(points: List<GeoPoint>, index: Int): List<GeoPoint> =
        if (index !in points.indices) points
        else points.filterIndexed { i, _ -> i != index }

    fun replaceAt(points: List<GeoPoint>, index: Int, point: GeoPoint): List<GeoPoint> =
        if (index !in points.indices) points
        else points.mapIndexed { i, existing -> if (i == index) point else existing }

    /** Inserts before [index]. An index of `points.size` appends. */
    fun insertAt(points: List<GeoPoint>, index: Int, point: GeoPoint): List<GeoPoint> =
        if (index !in 0..points.size) points
        else points.toMutableList().apply { add(index, point) }

    /**
     * Swaps two corners, which is what the move-up and move-down buttons do.
     *
     * Order is not cosmetic here: the corners are a ring, so `A B C D` and
     * `A C B D` enclose different shapes and report different areas. A corner
     * typed in out of sequence has to be movable or the boundary is wrong.
     */
    fun swap(points: List<GeoPoint>, i: Int, j: Int): List<GeoPoint> {
        if (i !in points.indices || j !in points.indices || i == j) return points
        return points.toMutableList().apply {
            val held = this[i]
            this[i] = this[j]
            this[j] = held
        }
    }

    /**
     * True when [candidate] is far enough from every other corner to be a corner
     * of its own.
     *
     * [ignoreIndex] is the corner being edited: a corner nudged by a few
     * centimetres, or saved again unchanged, must not be refused for sitting on
     * top of itself. The separation rule itself is [CornerDraft.acceptTap]'s —
     * a typed corner and a tapped one deserve the same threshold.
     */
    fun isDistinct(
        points: List<GeoPoint>,
        candidate: GeoPoint,
        ignoreIndex: Int? = null
    ): Boolean = CornerDraft.acceptTap(
        existing = points.filterIndexed { i, _ -> i != ignoreIndex },
        candidate = candidate
    )

    /**
     * The side of the boundary that [candidate] falls on, given back as the index
     * to insert at, or null when it falls on no side within [toleranceM].
     *
     * This is what makes a *missed* corner fixable. Adding a corner has always
     * appended it to the end of the ring, which is right while the outline is
     * being drawn corner by corner and wrong the moment the outline is closed:
     * a corner that belongs between #2 and #3 appended as #7 draws a spike
     * across the parcel, and putting it right meant pressing move-up four times.
     *
     * A ring's last side runs from the last corner back to the first, so a
     * candidate on that one inserts at the end — which is where it would have
     * gone anyway.
     */
    fun edgeNear(points: List<GeoPoint>, candidate: GeoPoint, toleranceM: Double): Int? {
        if (points.size < 2 || toleranceM <= 0.0) return null
        // Two corners are a line with one side; three or more close into a ring,
        // which has one side per corner.
        val sides = if (points.size == 2) 1 else points.size
        var best = -1
        var bestDistance = Double.MAX_VALUE
        for (i in 0 until sides) {
            val from = points[i]
            val to = points[(i + 1) % points.size]
            val distance = distanceToSegmentM(candidate, from, to)
            if (distance >= bestDistance) continue
            // Zoomed far enough out, a fingertip covers more ground than the whole
            // parcel, and every tap anywhere on it would count as landing on a
            // side — including the ones aimed at open ground in the middle. So a
            // side may only claim a candidate that is close relative to its own
            // length, however generous the tolerance handed in.
            val reach = minOf(
                toleranceM,
                GeoUtils.distance(from.latitude, from.longitude, to.latitude, to.longitude) *
                    MAX_EDGE_REACH
            )
            if (distance > reach) continue
            bestDistance = distance
            best = i
        }
        return if (best < 0) null else best + 1
    }

    /**
     * The halfway point of one side, or null when there is no such side.
     *
     * Offered as the starting value when a corner is inserted by hand: a corner
     * added between two others is nearly always somewhere along the line between
     * them, so half way there is a shorter edit than an empty field — and it is
     * already a legal corner if the user simply accepts it.
     */
    fun edgeMidpoint(points: List<GeoPoint>, index: Int): GeoPoint? {
        if (points.size < 2) return null
        if (index !in points.indices) return null
        if (points.size == 2 && index != 0) return null
        val from = points[index]
        val to = points[(index + 1) % points.size]
        // Straight average of the coordinates. Across a parcel — tens of metres —
        // the difference from the great-circle midpoint is under a millimetre.
        return GeoPoint(
            latitude = (from.latitude + to.latitude) / 2.0,
            longitude = (from.longitude + to.longitude) / 2.0
        )
    }

    /**
     * Reads a corner typed into two text fields, or null when either field is
     * not a coordinate.
     *
     * The ranges are the same ones [com.landpoint.app.ui.edit.LandEditViewModel]
     * enforces on the land's own coordinates, so a boundary cannot hold a corner
     * the form would have rejected. Parsed with [String.toDoubleOrNull] rather
     * than a locale-aware format: these fields are filled from written records,
     * which use a dot.
     */
    fun parseLatLon(lat: String, lon: String): GeoPoint? {
        val latitude = lat.trim().toDoubleOrNull() ?: return null
        val longitude = lon.trim().toDoubleOrNull() ?: return null
        if (latitude !in -90.0..90.0) return null
        if (longitude !in -180.0..180.0) return null
        return GeoPoint(latitude = latitude, longitude = longitude)
    }

    /**
     * How far [p] is from the line between [a] and [b], in metres.
     *
     * Flat-earth, on a projection centred on [a]: the distances being measured
     * here are the width of a fingertip on a parcel map, where the curvature of
     * the earth is orders of magnitude below the GPS noise in the corners
     * themselves.
     */
    private fun distanceToSegmentM(p: GeoPoint, a: GeoPoint, b: GeoPoint): Double {
        val metresPerDegree = EARTH_RADIUS_M * PI / 180.0
        val metresPerDegreeLon = metresPerDegree * cos(a.latitude * PI / 180.0)
        val px = (p.longitude - a.longitude) * metresPerDegreeLon
        val py = (p.latitude - a.latitude) * metresPerDegree
        val bx = (b.longitude - a.longitude) * metresPerDegreeLon
        val by = (b.latitude - a.latitude) * metresPerDegree
        val lengthSq = bx * bx + by * by
        if (lengthSq == 0.0) return hypot(px, py)
        // Clamped, so a candidate off the end of the side measures to the corner
        // rather than to the infinite line through it.
        val along = ((px * bx + py * by) / lengthSq).coerceIn(0.0, 1.0)
        return hypot(px - along * bx, py - along * by)
    }

    private const val EARTH_RADIUS_M = 6371000.0

    /**
     * The furthest from a side a tap may be and still be read as on it, as a
     * fraction of that side's length. A quarter keeps the middle of even a
     * three-corner parcel reachable for appending.
     */
    private const val MAX_EDGE_REACH = 0.25
}
