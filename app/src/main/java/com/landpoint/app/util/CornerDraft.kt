package com.landpoint.app.util

/**
 * The rule for a corner placed by tapping the map, as opposed to one captured
 * from GPS.
 *
 * [PolygonMath.isMeaningfulStep] guards the GPS path and is tuned for jitter: it
 * demands at least `max(2 m, accuracy)` of movement, because a phone standing
 * still reports a cloud of points that would otherwise be recorded as corners. A
 * tap has none of that. It carries no accuracy figure, and the 2 m floor would
 * refuse the corner of a shed whose sides really are a metre apart.
 *
 * At zoom 19 one pixel is roughly 0.3 m on the ground, so the only tap worth
 * refusing is one that lands on a corner already placed — a double tap, which
 * would add a second vertex on top of the first and give the polygon an edge of
 * zero length.
 */
object CornerDraft {

    /**
     * A tap closer than this to an existing corner is treated as a repeat of
     * that corner rather than a new one. Just under two pixels at zoom 19.
     */
    const val MIN_TAP_SEPARATION_M = 0.5

    /**
     * True when [candidate] is far enough from *every* corner in [existing] to
     * be a corner of its own.
     *
     * Every corner is checked, not just the last one, so a tap cannot quietly
     * stack a second vertex onto a corner placed earlier in the ring.
     */
    fun acceptTap(
        existing: List<GeoPoint>,
        candidate: GeoPoint,
        minSeparationM: Double = MIN_TAP_SEPARATION_M
    ): Boolean = existing.none { corner ->
        GeoUtils.distance(
            corner.latitude, corner.longitude,
            candidate.latitude, candidate.longitude
        ) < minSeparationM
    }
}
