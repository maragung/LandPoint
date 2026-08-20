package com.landpoint.app.location

import com.landpoint.app.util.GeoPoint
import com.landpoint.app.util.GeoUtils
import com.landpoint.app.util.PolygonMath

/**
 * Turns a walked track into a boundary worth printing.
 *
 * Recording every fix a phone emits does not work: a stationary GPS wanders by
 * several metres a second, and a raw track of that wander reports a fence line
 * that zig-zags and a perimeter tens of percent too long. Area suffers less
 * (the wander cancels) but perimeter does not, and both end up on a PDF next to
 * someone's land.
 *
 * Three filters, in order, keep the shape honest:
 *
 *  1. **Accuracy gate.** A fix the radio itself calls vague is not evidence of
 *     where the fence is. Dropped before it can influence anything.
 *  2. **Spacing gate.** A new vertex must be further from the last one than the
 *     fix accuracy can explain, so standing still adds nothing.
 *  3. **Simplification.** Douglas-Peucker over the kept points removes vertices
 *     that lie on a line their neighbours already describe — a straight fence
 *     walked in twenty steps becomes two corners, not twenty.
 *
 * Corner-by-corner capture stays the more accurate method and is still there;
 * this is for a boundary too long or too overgrown to stand on every corner of.
 */
object WalkTrack {

    /** Fixes vaguer than this are ignored outright. */
    const val MAX_ACCURACY_M = 25.0

    /** No vertex is ever recorded closer than this to the previous one. */
    const val MIN_STEP_M = 3.0

    /** A ring needs three distinct corners before it encloses anything. */
    const val MIN_POINTS = 3

    /**
     * Whether [sample] should become the next vertex after [previous].
     *
     * [previous] being null means this is the first point, which is always kept
     * provided it passes the accuracy gate.
     */
    fun accept(previous: GeoPoint?, sample: GeoSample): Boolean {
        val accuracy = sample.accuracy?.toDouble()
        // A fix with no stated accuracy at all is not trustworthy enough to
        // define a boundary — corner capture is the right tool on that device.
        if (accuracy == null || !accuracy.isFinite() || accuracy <= 0.0) return false
        if (accuracy > MAX_ACCURACY_M) return false

        val candidate = GeoPoint(sample.latitude, sample.longitude)
        if (previous == null) return true
        val moved = GeoUtils.distance(
            previous.latitude, previous.longitude,
            candidate.latitude, candidate.longitude
        )
        return moved >= maxOf(MIN_STEP_M, accuracy)
    }

    /**
     * Drops vertices that lie within [toleranceM] of the line their neighbours
     * already describe (Douglas-Peucker).
     *
     * Iterative rather than recursive: a long walk can produce thousands of
     * points, and a recursive implementation on a pathological track would
     * exhaust the stack on the very device that recorded it.
     */
    fun simplify(points: List<GeoPoint>, toleranceM: Double): List<GeoPoint> {
        if (points.size < 3 || toleranceM <= 0.0) return points

        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true

        val pending = ArrayDeque<Pair<Int, Int>>()
        pending.addLast(0 to points.size - 1)

        while (pending.isNotEmpty()) {
            val (first, last) = pending.removeLast()
            if (last <= first + 1) continue

            var farthest = -1
            var farthestDistance = 0.0
            for (i in first + 1 until last) {
                val d = perpendicularDistanceM(points[i], points[first], points[last])
                if (d > farthestDistance) {
                    farthestDistance = d
                    farthest = i
                }
            }

            if (farthest > 0 && farthestDistance > toleranceM) {
                keep[farthest] = true
                pending.addLast(first to farthest)
                pending.addLast(farthest to last)
            }
        }

        return points.filterIndexed { i, _ -> keep[i] }
    }

    /**
     * Finishes a walked track into a closed boundary.
     *
     * The walker usually stops near where they started, leaving a last vertex a
     * step or two from the first. Closing the ring is implicit — the boundary is
     * always treated as closed — so that near-duplicate is dropped rather than
     * left as a tiny spurious edge.
     */
    fun close(points: List<GeoPoint>, toleranceM: Double = MIN_STEP_M * 2): List<GeoPoint> {
        val simplified = simplify(points, toleranceM)
        if (simplified.size < 2) return simplified

        val first = simplified.first()
        val last = simplified.last()
        val gap = GeoUtils.distance(first.latitude, first.longitude, last.latitude, last.longitude)
        val trimmed = if (gap <= toleranceM) simplified.dropLast(1) else simplified

        return if (trimmed.size >= MIN_POINTS) trimmed else simplified
    }

    /** Live figures for the walk in progress. */
    fun progressOf(points: List<GeoPoint>): WalkProgress {
        val closed = points.size >= MIN_POINTS
        return WalkProgress(
            points = points.size,
            walkedM = PolygonMath.pathLengthM(points),
            areaSqm = if (closed) PolygonMath.areaSqm(points) else null
        )
    }

    /**
     * Great-circle distance from [point] to the segment [start]–[end].
     *
     * Computed in a local flat frame: over the length of one boundary edge the
     * curvature error is far below the GPS noise this is meant to filter, and
     * the flat form has no singularity when start and end coincide.
     */
    internal fun perpendicularDistanceM(
        point: GeoPoint,
        start: GeoPoint,
        end: GeoPoint
    ): Double {
        val metresPerDegreeLat = 111_320.0
        val metresPerDegreeLon = metresPerDegreeLat *
            kotlin.math.cos(Math.toRadians(start.latitude)).coerceAtLeast(1e-6)

        val px = (point.longitude - start.longitude) * metresPerDegreeLon
        val py = (point.latitude - start.latitude) * metresPerDegreeLat
        val ex = (end.longitude - start.longitude) * metresPerDegreeLon
        val ey = (end.latitude - start.latitude) * metresPerDegreeLat

        val lengthSquared = ex * ex + ey * ey
        // Degenerate segment: fall back to plain point-to-point distance.
        if (lengthSquared == 0.0) return kotlin.math.sqrt(px * px + py * py)

        val t = ((px * ex + py * ey) / lengthSquared).coerceIn(0.0, 1.0)
        val dx = px - t * ex
        val dy = py - t * ey
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}

data class WalkProgress(
    val points: Int,
    val walkedM: Double,
    val areaSqm: Double?
)
